package com.agent.tool.registry;

import com.agent.tool.common.BizException;
import com.agent.tool.model.ToolDefinition;
import com.agent.tool.model.ToolHandler;
import com.agent.tool.model.ToolMeta;
import com.agent.tool.model.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册表（R5-01）+ 工具契约治理（IN-06）。
 *
 * <p>IN-06 验收：「工具注册表登记全部工具；变更时自动列出受影响 Agent/流程；
 * schema 变更触发 L1 契约测试」——三条逐条断言。
 */
class ToolRegistryTest {

    private static final String SCHEMA_V1 = """
            {"type":"object","properties":{"expr":{"type":"string"}},"required":["expr"]}""";
    private static final String SCHEMA_V2 = """
            {"type":"object","properties":{"expr":{"type":"string"},"precision":{"type":"integer"}},"required":["expr"]}""";

    private static ToolMeta meta(String name, String version, String schema) {
        return new ToolMeta(name, version, "test tool", schema, false, 3000,
                List.of(), List.of(), false, null, null, "unit-test");
    }

    private static ToolHandler handler(String name) {
        return new ToolHandler() {
            @Override public String name() { return name; }
            @Override public ToolOutcome execute(Map<String, Object> arguments, com.agent.tool.model.ToolContext context) {
                return ToolOutcome.ok("ok");
            }
        };
    }

    // ─────────── R5-01 注册 / 列出 / 卸载 ───────────
    @Test
    void registerListAndUnregister() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        registry.register(meta("http", "1.0.0", SCHEMA_V1), handler("http"));

        assertEquals(2, registry.list().size(), "注册后应可列出");
        assertTrue(registry.find("calculator").isPresent());

        assertTrue(registry.unregister("http"), "应可卸载");
        assertEquals(1, registry.list().size());
        assertFalse(registry.unregister("http"), "重复卸载返回 false，不抛异常");
        assertTrue(registry.find("http").isEmpty());
    }

    @Test
    void handlerNameMismatchRejected() {
        ToolRegistry registry = new ToolRegistry();
        BizException e = assertThrows(BizException.class,
                () -> registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("http")));
        assertTrue(e.getMessage().contains("不一致"), "名字对不上必须当场拒绝，否则审计与调用会错位");
    }

    @Test
    void requireUnknownToolThrowsToolNotAllowed() {
        ToolRegistry registry = new ToolRegistry();
        assertThrows(BizException.class, () -> registry.require("nope"));
    }

    @Test
    void listIsSortedStable() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("http", "1.0.0", SCHEMA_V1), handler("http"));
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        assertEquals(List.of("calculator", "http"),
                registry.list().stream().map(item -> item.meta().name()).toList());
    }

    // ─────────── IN-06 版本化 + 变更历史 ───────────
    @Test
    void firstRegistrationIsRecordedAsRegister() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));

        List<ToolVersionRecord> versions = registry.versions("calculator");
        assertEquals(1, versions.size());
        assertEquals("REGISTER", versions.get(0).action());
        assertEquals("1.0.0", versions.get(0).version());
        assertNotNull(versions.get(0).schemaHash());
    }

    @Test
    void schemaChangeIsDetectedAndDiffersFromVersionBump() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        String hashV1 = registry.versions("calculator").get(0).schemaHash();

        // 只升版本、schema 不变 → VERSION_UPGRADE
        registry.register(meta("calculator", "1.1.0", SCHEMA_V1), handler("calculator"));
        assertEquals("VERSION_UPGRADE", registry.versions("calculator").get(1).action());

        // schema 变了 → SCHEMA_CHANGE（这是必须触发契约测试的那种变更）
        registry.register(meta("calculator", "1.2.0", SCHEMA_V2), handler("calculator"));
        List<ToolVersionRecord> versions = registry.versions("calculator");
        assertEquals("SCHEMA_CHANGE", versions.get(2).action());
        assertFalse(hashV1.equals(versions.get(2).schemaHash()), "schema 变了指纹必须跟着变");
        assertEquals("1.1.0", ((ToolVersionRecord) versions.get(2)).previousVersion());
    }

    @Test
    void deprecateMarksToolAndKeepsHistory() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("old-tool", "1.0.0", SCHEMA_V1), handler("old-tool"));
        ToolDefinition deprecated = registry.deprecate("old-tool", "2026-09-19", "2026-10-19");

        assertTrue(deprecated.meta().deprecated());
        assertEquals("2026-10-19", deprecated.meta().removedAfter());
        assertEquals("DEPRECATE", registry.versions("old-tool").get(1).action());
        assertTrue(registry.find("old-tool").isPresent(), "废弃期内容仍可调用（只是标记）");
    }

    @Test
    void changeLogCollectsAcrossToolsNewestFirst() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("a", "1.0.0", SCHEMA_V1), handler("a"));
        registry.register(meta("b", "1.0.0", SCHEMA_V1), handler("b"));
        assertEquals(2, registry.changeLog().size());
    }

    // ─────────── IN-06 影响分析 ───────────
    @Test
    void impactListsRegisteredConsumers() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        registry.registerConsumer("calculator", "brain-planner", "combo-task");
        registry.registerConsumer("calculator", "other-agent", null);

        ImpactReport report = registry.impact("calculator");
        assertEquals(List.of("brain-planner", "other-agent"), report.affectedAgents());
        assertEquals(List.of("combo-task"), report.affectedFlows());
    }

    @Test
    void schemaChangeTriggersContractTestWhenConsumersExist() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        registry.registerConsumer("calculator", "brain-planner", "combo-task");
        registry.register(meta("calculator", "1.1.0", SCHEMA_V2), handler("calculator"));

        ImpactReport report = registry.impact("calculator");
        assertTrue(report.schemaChanged(), "发生过的 schema 变更要能被查到");
        assertTrue(report.contractTestRequired(), "有消费方 + schema 变更 → 必须触发 L1 契约测试");
    }

    @Test
    void impactIsHonestAboutMissingConsumers() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        ImpactReport report = registry.impact("calculator");

        assertTrue(report.affectedAgents().isEmpty());
        assertTrue(report.note().contains("显式登记"),
                "无消费方时必须说明只覆盖『显式登记过』的调用方，不许暗示已全覆盖");
        assertFalse(report.contractTestRequired(), "无消费方则无从回归");
    }

    @Test
    void fingerprintIsStableAndSchemaSensitive() {
        String a = ToolRegistry.fingerprint(SCHEMA_V1);
        String b = ToolRegistry.fingerprint(SCHEMA_V1);
        String c = ToolRegistry.fingerprint(SCHEMA_V2);
        assertEquals(a, b, "同 schema 指纹必须稳定");
        assertFalse(a.equals(c), "不同 schema 指纹必须不同");
        assertEquals(16, a.length());
    }

    @Test
    void summaryReportsRegistryBackendHonestly() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(meta("calculator", "1.0.0", SCHEMA_V1), handler("calculator"));
        Map<String, Object> summary = registry.summary();
        assertEquals(1, summary.get("tool_count"));
        assertEquals("in-memory", summary.get("registry_backend"),
                "注册表本体是内存实现，不得声称已接 etcd");
    }
}