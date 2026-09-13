package com.agent.session.orchestration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

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
 */
public final class OutboundHttp {

    /** 连接超时：本机/内网调用，3 秒足够；过长只会拖慢失败反馈。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 读超时：覆盖意图识别与本地检索的 P99（毫秒级），留足余量。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private OutboundHttp() {
    }

    /** 构建统一配置的 HTTP/1.1 客户端。 */
    public static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** 构建统一配置的 RestClient（HTTP/1.1 + 连接/读超时）。 */
    public static RestClient restClient(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
