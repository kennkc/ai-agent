package com.agent.tool.exec;

import com.agent.tool.audit.AuditEventPublisher;
import com.agent.tool.audit.ToolAuditLog;
import com.agent.tool.common.ErrorCode;
import com.agent.tool.guard.ToolGuard;
import com.agent.tool.model.ToolCallResult;
import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolHandler;
import com.agent.tool.model.ToolMeta;
import com.agent.tool.model.ToolOutcome;
import com.agent.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 工具执行引擎（R5-02 / 测试案例 TC-05 TC-06）。
 *
 * <p>重点不是"成功路径能跑通"，而是**失败路径全都写进了审计**——
 * R5-08 验收「全量记录」在"被拦 / 超时 / 熔断"这些分支上最容易漏，故逐个断言。
 */
class ToolExecutorTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{"expr":{"type":"string","minLength":1}},
             "required":["expr"],"additionalProperties":false}""";

    private ToolRegistry registry;
    private ToolExecutor executor;
    private ToolAuditLog auditLog;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        ToolGuard guard = new ToolGuard("calculator,slow,flaky");
        // PG 不可达 → 审计走内存降级（这也顺带验证降级路径本身可用）
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("no postgres in unit test")).when(jdbc).execute(anyString());
        auditLog = new ToolAuditLog(jdbc, 100);
        AuditEventPublisher publisher = new AuditEventPublisher("127.0.0.1:9092", "lifeform.tool.invoked", false);
        executor = new ToolExecutor(registry, guard, auditLog, publisher, new ToolMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), 3, 30000L);
    }

    private void registerOk(String name) {
        registry.register(new ToolMeta(name, "1.0.0", "test", SCHEMA, false, 3000,
                List.of(), List.of(), false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return name; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.ok("computed:" + arguments.get("expr"));
            }
        });
    }

    private ToolCallResult call(String tool, Map<String, Object> args) {
        return executor.execute(tool, args, ToolContext.of("tenant-a", ToolCallResult.newCallId()));
    }

    // ─────────── 成功路径 ───────────
    @Test
    void successfulCallIsNormalizedAndAudited() {
        registerOk("calculator");
        ToolCallResult result = call("calculator", Map.of("expr", "128*17"));

        assertTrue(result.success());
        assertEquals("computed:128*17", result.output());
        assertEquals("calculator", result.toolName());
        assertEquals("1.0.0", result.toolVersion());
        assertNotNull(result.callId());
        assertNotNull(result.auditId(), "R5-08：成功调用必须留审计");
        assertTrue(result.toMap().containsKey("latency_ms"));

        List<Map<String, Object>> rows = auditLog.list("tenant-a", 10);
        assertEquals(1, rows.size());
        assertEquals(true, rows.get(0).get("success"));
        assertEquals("calculator", rows.get(0).get("tool_name"));
    }

    // ─────────── 失败路径也必须留审计 ───────────
    @Test
    void toolOutsideWhitelistIsRejectedAndAudited() {
        registry.register(new ToolMeta("danger", "1.0.0", "d", SCHEMA, false, 3000, List.of(), List.of(),
                false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return "danger"; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                throw new IllegalStateException("绝不应被执行到");
            }
        });
        ToolCallResult result = call("danger", Map.of("expr", "1"));

        assertFalse(result.success());
        assertEquals(ErrorCode.AGENT_TOOL_NOT_ALLOWED.code(), result.errorCode());
        assertNotNull(result.auditId(), "被拒调用同样要留审计（R5-08 全量记录）");
        assertEquals(1, auditLog.list("tenant-a", 10).size());
    }

    @Test
    void invalidArgumentsFailWithFieldLevelDetailsAndAreAudited() {
        registerOk("calculator");
        ToolCallResult result = call("calculator", Map.of());

        assertFalse(result.success());
        assertEquals(ErrorCode.AGENT_TOOL_ARGS_INVALID.code(), result.errorCode());
        assertFalse(result.details().isEmpty(), "字段级 details 要带回给调用方");
        assertEquals(1, auditLog.list("tenant-a", 10).size());
    }

    @Test
    void sensitiveArgumentsAreBlockedBeforeExecution() {
        registerOk("calculator");
        ToolCallResult result = call("calculator", Map.of("expr", "rm -rf /"));

        assertFalse(result.success());
        assertEquals(ErrorCode.AGENT_TOOL_ARGS_BLOCKED.code(), result.errorCode());
        assertNotNull(result.auditId());
    }

    @Test
    void toolBusinessFailureIsReportedNotSwallowed() {
        registry.register(new ToolMeta("flaky", "1.0.0", "f", SCHEMA, false, 3000, List.of(), List.of(),
                false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return "flaky"; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED", "上游 500");
            }
        });
        ToolCallResult result = call("flaky", Map.of("expr", "1"));
        assertFalse(result.success());
        assertEquals("上游 500", result.errorMessage());
        assertNotNull(result.auditId());
    }

    /**
     * 失败路径必须把沙箱侧的 stdout/stderr/exit_code 带回调用方。
     *
     * <p>回归背景：{@code CodeToolTest} 一直断言 handler 层 `data` 里有 stderr，
     * 但 {@code ToolExecutor} 在 `ToolOutcome → ToolCallResult` 转换时把它丢掉了，
     * 于是调用方只看到「沙箱执行返回非零退出码 1」，拿不到真正原因（如 Read-only file system），
     * 直接违反 R5-05「返回 stdout/stderr」与 R5-02「错误友好」。
     * **单测各自自洽、链路丢字段** —— 本条锁死跨层不变量。
     */
    @Test
    void sandboxFailureKeepsStdoutStderrExitCodeAndBackend() {
        registry.register(new ToolMeta("flaky", "1.0.0", "沙箱工具", SCHEMA, true, 3000,
                List.of(), List.of(), false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return "flaky"; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED", "沙箱执行返回非零退出码 1",
                        Map.of("exit_code", 1,
                                "stdout", "",
                                "stderr", "OSError: [Errno 30] Read-only file system: '/etc/pwned'",
                                "sandbox_backend", "docker",
                                "sandbox_degraded", false));
            }
        });

        ToolCallResult result = call("flaky", Map.of("expr", "x"));

        assertFalse(result.success());
        assertEquals("AGENT_TOOL_EXEC_FAILED", result.errorCode());
        assertEquals("1", result.details().get("exit_code"), "非零退出码要回传");
        assertTrue(result.details().get("stderr").contains("Read-only file system"),
                "stderr 必须回传，否则「错误友好」只是口号");
        assertEquals("docker", result.details().get("sandbox_backend"),
                "失败路径也要能看出这次跑在哪");
        assertEquals("docker", result.sandboxBackend(),
                "沙箱事实同时挂到结果字段上，供视图层直接消费");
        assertTrue(result.sandboxed());
        assertFalse(result.degraded());
        assertNotNull(result.auditId(), "失败同样要留审计");
    }

    @Test
    void oversizedStderrIsClippedNotDropped() {
        String huge = "E".repeat(5000);
        registry.register(new ToolMeta("flaky", "1.0.0", "沙箱工具", SCHEMA, true, 3000,
                List.of(), List.of(), false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return "flaky"; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED", "非零退出码 1",
                        Map.of("exit_code", 1, "stderr", huge));
            }
        });

        ToolCallResult result = call("flaky", Map.of("expr", "x"));

        String stderr = result.details().get("stderr");
        assertNotNull(stderr, "大段 stderr 应被截断保留，不是整段丢弃");
        assertTrue(stderr.length() < huge.length(), "超长 stderr 必须截断，避免撑爆错误信封");
        assertTrue(stderr.startsWith("E"));
    }

    // ─────────── TC-05 超时熔断 ───────────

    @Test
    void slowToolTimesOutAndIsAudited() {
        registry.register(new ToolMeta("slow", "1.0.0", "s", SCHEMA, false, 120, List.of(), List.of(),
                false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return "slow"; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolOutcome.ok("too late");
            }
        });
        long started = System.currentTimeMillis();
        ToolCallResult result = call("slow", Map.of("expr", "1"));
        long elapsed = System.currentTimeMillis() - started;

        assertFalse(result.success());
        assertEquals(ErrorCode.AGENT_TIMEOUT.code(), result.errorCode());
        assertTrue(elapsed < 3000, "超时必须真的切断（需求：熔断 < 5s），实测 " + elapsed + "ms");
        assertNotNull(result.auditId(), "超时也要留审计");
    }

    @Test
    void consecutiveFailuresOpenCircuitAndFastFail() {
        registry.register(new ToolMeta("flaky", "1.0.0", "f", SCHEMA, false, 3000, List.of(), List.of(),
                false, null, null, "unit"), new ToolHandler() {
            @Override public String name() { return "flaky"; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED", "总是失败");
            }
        });

        for (int i = 0; i < 3; i++) {
            assertFalse(call("flaky", Map.of("expr", "1")).success());
        }
        ToolCallResult fourth = call("flaky", Map.of("expr", "1"));
        assertEquals(ErrorCode.AGENT_TOOL_CIRCUIT_OPEN.code(), fourth.errorCode(),
                "连续 3 次失败后应快速失败而不是继续打上游");
        assertEquals("open", executor.breakerState("flaky").get("state"));
        assertEquals(4, auditLog.list("tenant-a", 10).size(), "熔断调用也要留审计");
    }

    // ─────────── 指标 ───────────
    @Test
    void metricsTrackSuccessRateAndPercentiles() {
        registerOk("calculator");
        call("calculator", Map.of("expr", "1+1"));
        call("calculator", Map.of());
        call("calculator", Map.of("expr", "rm -rf /"));

        ToolMetrics metrics = new ToolMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        metrics.record("calculator", true, 12, null);
        metrics.record("calculator", false, 30, ErrorCode.AGENT_TOOL_ARGS_INVALID.code());

        Map<String, Object> snapshot = metrics.snapshot();
        assertEquals(2, snapshot.get("window_size"));
        assertEquals(0.5, snapshot.get("success_rate"));
        assertEquals(30L, snapshot.get("p99_ms"));
        assertTrue(((Map<?, ?>) snapshot.get("calls_by_tool")).containsKey("calculator"));
    }
}