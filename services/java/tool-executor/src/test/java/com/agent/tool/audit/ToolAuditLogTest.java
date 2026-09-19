package com.agent.tool.audit;

import com.agent.tool.common.ErrorCode;
import com.agent.tool.model.ToolCallResult;
import com.agent.tool.model.ToolContext;
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
 * 工具审计（R5-08）：**PG 不可达时的降级必须可见**，且租户隔离、容量淘汰行为明确。
 */
class ToolAuditLogTest {

    private final ToolContext context = ToolContext.of("tenant-a", "call-1");

    private ToolAuditLog memoryBacked(int capacity) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("no postgres")).when(jdbc).execute(anyString());
        return new ToolAuditLog(jdbc, capacity);
    }

    private static ToolCallResult result(String tool, boolean success, String errorCode) {
        return success
                ? new ToolCallResultStub(tool, true, null).build()
                : new ToolCallResultStub(tool, false, errorCode).build();
    }

    /** 小工具：绕过 ToolCallResult 的构造私有性 */
    private static final class ToolCallResultStub {
        private final String tool;
        private final boolean success;
        private final String errorCode;

        private ToolCallResultStub(String tool, boolean success, String errorCode) {
            this.tool = tool; this.success = success; this.errorCode = errorCode;
        }

        private ToolCallResult build() {
            ToolCallResult base = ToolCallResult.failure("call-1", tool, "1.0.0",
                    errorCode, "err", 5L);
            if (success) {
                return ToolCallResult.success("call-1",
                        com.agent.tool.model.ToolDefinition.bind(
                                new com.agent.tool.model.ToolMeta(tool, "1.0.0", "d", "{}", false, 1000,
                                        List.of(), List.of(), false, null, null, "t"),
                                null), "out", 5L, false, null, false);
            }
            return base;
        }
    }

    @Test
    void degradedToMemoryWhenPgUnavailable() {
        ToolAuditLog log = memoryBacked(100);
        assertEquals("memory", log.health().get("backend"));
        assertEquals(true, log.health().get("degraded"), "PG 不可达必须自报降级，不得假装落库成功");
    }

    @Test
    void recordAndReadBack() {
        ToolAuditLog log = memoryBacked(100);
        String auditId = log.record(context, result("calculator", true, null), Map.of("expr", "128*17"));
        assertNotNull(auditId);

        List<Map<String, Object>> rows = log.list("tenant-a", 10);
        assertEquals(1, rows.size());
        assertEquals("calculator", rows.get(0).get("tool_name"));
        assertEquals(true, rows.get(0).get("success"));
        assertEquals("1.0.0", rows.get(0).get("tool_version"));
        assertNotNull(rows.get(0).get("created_at"));
    }

    @Test
    void tenantIsolation() {
        ToolAuditLog log = memoryBacked(100);
        log.record(context, result("calculator", true, null), Map.of());
        log.record(ToolContext.of("tenant-b", "call-2"), result("http", true, null), Map.of());

        assertEquals(1, log.list("tenant-a", 10).size());
        assertEquals(1, log.list("tenant-b", 10).size());
        assertEquals("http", log.list("tenant-b", 10).get(0).get("tool_name"));
    }

    @Test
    void credentialsAreRedactedInArgsSummary() {
        ToolAuditLog log = memoryBacked(100);
        log.record(context, result("http", true, null),
                Map.of("url", "https://x/y", "api_token", "super-secret-value"));

        String summary = String.valueOf(log.list("tenant-a", 10).get(0).get("args"));
        assertFalse(summary.contains("super-secret-value"), "审计库不能成为凭证泄露面");
        assertTrue(summary.contains("chars)"), "应保留长度信息以可诊断");
    }

    @Test
    void memoryWindowDropsOldestAndSaysSo() {
        ToolAuditLog log = memoryBacked(3);
        for (int i = 0; i < 5; i++) {
            log.record(context, result("calculator", true, null), Map.of());
        }
        assertEquals(3, log.list("tenant-a", 10).size(), "内存窗口按容量裁剪");
        assertEquals(2L, log.health().get("dropped"));
        assertEquals(2L, log.stats("tenant-a").get("dropped"));
    }

    @Test
    void statsReportSuccessAndBlockedCounts() {
        ToolAuditLog log = memoryBacked(100);
        log.record(context, result("calculator", true, null), Map.of());
        log.record(context, result("calculator", false, ErrorCode.AGENT_TOOL_ARGS_BLOCKED.code()), Map.of());
        log.record(context, result("calculator", false, ErrorCode.AGENT_TOOL_ARGS_INVALID.code()), Map.of());

        Map<String, Object> stats = log.stats("tenant-a");
        assertEquals("memory", stats.get("backend"));
        assertEquals(true, stats.get("degraded"));
        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("success"));
        assertEquals(1L, stats.get("blocked"), "只有安全拦截计入 blocked，参数格式错不算");
    }

    @Test
    void statsAreEmptyButWellFormedWithNoData() {
        ToolAuditLog log = memoryBacked(100);
        Map<String, Object> stats = log.stats("tenant-a");
        assertEquals(0L, stats.get("total"));
        assertEquals(null, stats.get("success_rate"));
    }
}