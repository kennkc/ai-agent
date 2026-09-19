package com.agent.tool.sandbox;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker 沙箱后端的**探测契约**测试（2026-09-19 缺陷回归）。
 *
 * <p>背景：{@code docker info} + {@code image inspect} 在 Windows Docker Desktop 上
 * 冷探测约 5-6s。上游 wp-bff 对工具域子请求的预算只有 2500ms（`server.js:46`
 * 的 `httpGetJson` 默认值），于是 {@code /api/wp/tools} 拿不到沙箱状态，
 * 忽略超时后返回 {@code sandbox: null} + {@code partial: ["sandbox_unavailable"]}。
 * <b>端点并非不可用，而是回得太慢</b> —— 一个"慢"被上游表达成了"没有"。
 *
 * <p>因此本类断言的不是"沙箱能不能跑"，而是**{@code available()} 不得阻塞调用方**：
 * 探测再慢也只允许发生在启动期或后台刷新线程里。
 */
class DockerSandboxBackendTest {

    /** 慢探测（800ms）：模拟 Windows 上 docker CLI 的真实冷启动成本。 */
    private static BooleanSupplierLike slowProbe(AtomicInteger calls) {
        return new BooleanSupplierLike(calls, 800, true);
    }

    @Test
    void slowProbeNeverBlocksTheCaller() {
        AtomicInteger calls = new AtomicInteger();
        // TTL=0 → 每次 available() 都会判定"缓存过期"，从而必然走后台刷新分支
        DockerSandboxBackend backend = new DockerSandboxBackend(
                true, "agent-sandbox:latest", "python3", 256, slowProbe(calls), 0L);

        long started = System.currentTimeMillis();
        boolean first = backend.available();
        long cost = System.currentTimeMillis() - started;

        assertTrue(first, "构造期已同步探得 true，available() 应返回该缓存结论");
        assertTrue(cost < 300,
                "available() 必须在请求线程内立即返回；实测 " + cost
                        + "ms —— 若逼近探测耗时说明又退回了同步阻塞（正是本次缺陷）");
        assertTrue(calls.get() >= 1, "构造期应至少探测过一次");
    }

    @Test
    void cachedProbeIsReusedWithinTtl() {
        AtomicInteger calls = new AtomicInteger();
        DockerSandboxBackend backend = new DockerSandboxBackend(
                true, "agent-sandbox:latest", "python3", 256, slowProbe(calls), 60_000L);

        int afterConstruction = calls.get();
        for (int i = 0; i < 5; i++) {
            backend.available();
        }
        assertEquals(afterConstruction, calls.get(),
                "TTL 内的重复查询不得触发新的探测（避免探针风暴）");
    }

    @Test
    void disabledBackendIsFalseAndNeverProbes() {
        AtomicInteger calls = new AtomicInteger();
        DockerSandboxBackend backend = new DockerSandboxBackend(
                false, "agent-sandbox:latest", "python3", 256, slowProbe(calls), 0L);

        long started = System.currentTimeMillis();
        assertFalse(backend.available(), "docker.enabled=false 时恒为不可用");
        assertTrue(System.currentTimeMillis() - started < 100, "禁用分支不得有任何探测成本");
        assertEquals(0, calls.get(), "禁用时连启动探测都不该发生");
    }

    /** 生产构造签名（4 参数 + @Value）在 enabled=false 时同样零探测。 */
    @Test
    void productionConstructorStaysCheapWhenDisabled() {
        long started = System.currentTimeMillis();
        DockerSandboxBackend backend =
                new DockerSandboxBackend(false, "agent-sandbox:latest", "python3", 256);
        assertFalse(backend.available());
        assertTrue(System.currentTimeMillis() - started < 100,
                "不启用 Docker 后端的测试/部署场景不得被探测拖慢");
    }

    /** 便捷包装：可计数、可设耗时与返回值的探测函数。 */
    private static final class BooleanSupplierLike implements java.util.function.BooleanSupplier {
        private final AtomicInteger calls;
        private final long sleepMs;
        private final boolean result;

        private BooleanSupplierLike(AtomicInteger calls, long sleepMs, boolean result) {
            this.calls = calls;
            this.sleepMs = sleepMs;
            this.result = result;
        }

        @Override public boolean getAsBoolean() {
            calls.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }
    }
}
