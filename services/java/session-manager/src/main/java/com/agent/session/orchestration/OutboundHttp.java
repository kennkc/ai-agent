package com.agent.session.orchestration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 跨服务 HTTP 调用的统一出口（缺陷 D-1 修复 + 超时补齐）。
 *
 * <p>背景：{@code RestClient.builder().build()} 在无 Apache HttpClient 依赖时回退到
 * {@link JdkClientHttpRequestFactory}，底层 {@link HttpClient} 默认协商 HTTP/2。
 * 对明文连接，JDK 客户端会先发送 {@code Upgrade: h2c} + {@code HTTP2-Settings} 升级握手；
 * uvicorn 的 h11 实现不兼容该升级，会把请求判为非法并<strong>丢弃请求体</strong>，
 * 于是 FastAPI 返回 422「body Field required」，Java 侧再包装成上游不可用。
 *
 * <p>本类显式锁定 HTTP/1.1，并统一补齐连接超时与读超时，避免 Python 侧卡住时把会话请求一起拖死。
 * 新增跨服务调用请一律经由本类，不要再直接使用 {@code RestClient.builder()}。
 *
 * <h2>读超时按「操作类型」分档（2026-09-19）</h2>
 *
 * 原先所有出站调用共用 {@code 10s}，在登记表 `contracts/timeout-budget.yaml` 里造成两处倒挂：
 * <ul>
 *   <li><b>GAP-01</b> 问答 → nlp-service：下游 LLM 级联最坏 22s、端到端总预算 25s，
 *       而上游只给 10s —— 上游必然先放弃，并把「慢」上报成「大脑层不可用」；</li>
 *   <li><b>GAP-02</b> 检索 → body-service：上游预算 10s 与下游读超时 10s <b>相等</b>，余量 0。</li>
 * </ul>
 *
 * 教训与 wp-bff 侧同源：<b>不要给所有调用一个统一默认超时</b> ——
 * 纯查询是毫秒级，LLM 生成是秒级，统一值必然对一方过长、对另一方过短。
 *
 * <p>档位常量只给**上限**；实际读超时还要受 {@link RequestBudget} 剩余量约束，
 * 使整条链的最坏耗时收敛为「一个总预算」而不是「各档之和」。
 */
public final class OutboundHttp {

    /** 连接超时：本机/内网调用，3 秒足够；过长只会拖慢失败反馈。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 意图识别档：nlp-service 规则级联，毫秒级返回（登记表 TB-11 之外的意图边）。 */
    public static final Duration INTENT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 检索档：覆盖 body-service 检索热路径的端到端预算（登记表 TB-12）。
     *
     * <p>原值 10s 与下游读超时相等（余量 0）——「相等」在跨服务调用里等同于**必然先放弃的一方是上游**。
     */
    public static final Duration RETRIEVE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 生成档：覆盖 nlp-service {@code POST /api/nlp/brain/ask} 的**端到端总预算**
     * （`BRAIN_TOTAL_BUDGET_MS = 20000`，登记表 TB-11）。
     *
     * <p>30s / 20s = 1.5x 满足表内余量要求。GAP-01 于 2026-09-19 闭合 ——
     * 闭合方式是**收紧下游**（nlp 侧 25s → 20s，原 25s 大于级联推导最坏 22s、从不构成约束），
     * 而不是调大本档：链路是 {@code wp-bff → session → nlp → LLM} 两级嵌套，
     * 若内层不设上限，最坏耗时是「各级超时 × 重试次数」的乘积，上游无论调多大都可能不够，
     * 而代价是「快速失败并降级」变成「长时间等待后仍降级」。正确做法是**内层收敛、外层覆盖**（REC-01）。
     */
    public static final Duration BRAIN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 一次 ask 请求的**总预算**（登记表 TB-09 的 downstream 口径）。
     *
     * <p>取值 35s = 意图档 5s + 生成档 30s —— **各档上限之和恰好等于总预算**，
     * 这样 {@link RequestBudget} 的收窄作用只在「上游提前耗时」时生效，而不会凭空砍掉某一跳。
     * 若各档之和大于总预算，最后几跳会被压缩到无法完成，属于自相矛盾的配置。
     */
    public static final long ASK_TOTAL_BUDGET_MS = 35_000;

    /**
     * 缺省档：仅用于尚未声明操作类型的调用。
     *
     * <p><b>新代码不要依赖它</b> —— 请显式选用上面三个具名档位，并在登记表登记对应边。
     */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * RestClient 缓存，键为 {@code baseUrl|读取超时(秒)}。
     *
     * <p>读超时是在**工厂构造期**固定的，所以每个不同超时值需要一个实例。
     * 为避免按毫秒取值导致实例无限增长，档位/剩余预算统一**向上取整到秒**后入键 ——
     * 代价是某一跳可能比剩余预算多跑不足 1 秒，这是刻意的取舍（缓存有界优先）。
     */
    private static final ConcurrentMap<String, RestClient> CACHE = new ConcurrentHashMap<>();

    private OutboundHttp() {
    }

    /** 构建统一配置的 HTTP/1.1 客户端。 */
    public static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** 缺省档的 RestClient（等价于 {@link #DEFAULT_READ_TIMEOUT}）。 */
    public static RestClient restClient(String baseUrl) {
        return restClient(baseUrl, DEFAULT_READ_TIMEOUT);
    }

    /** 指定读超时的 RestClient；同一 {@code (baseUrl, 秒)} 组合复用实例。 */
    public static RestClient restClient(String baseUrl, Duration readTimeout) {
        Duration effective = normalize(readTimeout);
        return CACHE.computeIfAbsent(baseUrl + "|" + effective.toSeconds(), key -> build(baseUrl, effective));
    }

    /** 归一化：null / 非正 → 缺省档；否则向上取整到秒，且至少 1 秒（0 秒会被解释为「无超时」）。 */
    private static Duration normalize(Duration readTimeout) {
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            return DEFAULT_READ_TIMEOUT;
        }
        long millis = readTimeout.toMillis();
        long seconds = Math.max(1, (millis + 999) / 1000);
        return Duration.ofSeconds(seconds);
    }

    private static RestClient build(String baseUrl, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 仅供单测：清空客户端缓存。
     *
     * <p>生产路径不需要 —— 缓存键的取值空间由档位与总预算决定，本身有界。
     */
    static void clearClientCache() {
        CACHE.clear();
    }

    /** 仅供单测：当前缓存条目数，用于断言「不会按毫秒无限增长」。 */
    static int clientCacheSize() {
        return CACHE.size();
    }
}
