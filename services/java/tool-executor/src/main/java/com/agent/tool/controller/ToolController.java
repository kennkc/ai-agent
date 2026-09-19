package com.agent.tool.controller;

import com.agent.tool.audit.AuditEventPublisher;
import com.agent.tool.audit.ToolAuditLog;
import com.agent.tool.common.BizException;
import com.agent.tool.common.ErrorCode;
import com.agent.tool.exec.ToolExecutor;
import com.agent.tool.exec.ToolMetrics;
import com.agent.tool.model.ToolCallResult;
import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolDefinition;
import com.agent.tool.registry.ImpactReport;
import com.agent.tool.registry.ToolRegistry;
import com.agent.tool.registry.ToolVersionRecord;
import com.agent.tool.sandbox.SandboxExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四肢层对外 API（R5-01/R5-02/R5-07/R5-08 + IN-06）。
 *
 * <p>路由契约（与 {@code contracts/work-platform-bff-openapi.yaml} 的 /tools 段对齐）：
 * <ul>
 *   <li>{@code GET  /api/tool/list}              工具注册表清单（R5-01）</li>
 *   <li>{@code GET  /api/tool/registry}          注册表摘要 + 变更历史（IN-06）</li>
 *   <li>{@code GET  /api/tool/{name}}            单工具详情（含 JSON Schema）</li>
 *   <li>{@code GET  /api/tool/{name}/impact}     变更影响分析（IN-06）</li>
 *   <li>{@code DELETE /api/tool/{name}}          卸载工具（R5-01 验收：可卸载）</li>
 *   <li>{@code POST /api/tool/{name}/deprecate}  标记废弃（IN-06 废弃期）</li>
 *   <li>{@code POST /api/tool/execute}           执行工具（R5-02）</li>
 *   <li>{@code GET  /api/tool/audit}             审计明细（R5-08）</li>
 *   <li>{@code GET  /api/tool/audit/stats}       审计汇总</li>
 *   <li>{@code GET  /api/tool/metrics}           成功率 / P50·P95·P99 / 熔断状态</li>
 *   <li>{@code GET  /api/tool/sandbox}           沙箱状态（R-C05 执行视图数据源）</li>
 * </ul>
 *
 * <p>租户来源：网关注入的 {@code X-Tenant-Id} 头（**不信任请求体**）。
 */
@RestController
@RequestMapping("/api/tool")
public class ToolController {

    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ToolAuditLog auditLog;
    private final ToolMetrics metrics;
    private final SandboxExecutor sandbox;
    private final AuditEventPublisher publisher;

    public ToolController(ToolRegistry registry, ToolExecutor executor, ToolAuditLog auditLog,
                          ToolMetrics metrics, SandboxExecutor sandbox, AuditEventPublisher publisher) {
        this.registry = registry;
        this.executor = executor;
        this.auditLog = auditLog;
        this.metrics = metrics;
        this.sandbox = sandbox;
        this.publisher = publisher;
    }

    // ─────────── R5-01 注册表 ───────────
    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition definition : registry.list()) {
            tools.add(describe(definition));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", tools.size());
        result.put("registry_backend", "in-memory");
        result.put("tools", tools);
        return result;
    }

    @GetMapping("/registry")
    public Map<String, Object> registryOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", registry.summary());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (ToolVersionRecord record : registry.changeLog()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool_name", record.name());
            row.put("version", record.version());
            row.put("schema_hash", record.schemaHash());
            row.put("action", record.action());
            row.put("previous_version", record.previousVersion());
            row.put("changed_at", record.changedAt());
            row.put("changed_by", record.changedBy());
            row.put("impact", record.impact());
            changes.add(row);
        }
        result.put("change_log", changes);
        result.put("semver_policy", "工具接口 semver 版本化；schema 变更即触发 L1 契约测试（IN-06）");
        result.put("deprecation_policy", "废弃期 30 天，期间仍可调用并返回 deprecated=true");
        return result;
    }

    @GetMapping("/{name}")
    public Map<String, Object> detail(@PathVariable String name) {
        return describe(registry.require(name));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> unregister(@PathVariable String name) {
        boolean removed = registry.unregister(name);
        if (!removed) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND, "工具不存在：" + name, Map.of("tool_name", name));
        }
        return Map.of("tool_name", name, "unregistered", true, "remaining", registry.list().size());
    }

    @PostMapping("/{name}/deprecate")
    public Map<String, Object> deprecate(@PathVariable String name,
                                         @RequestBody(required = false) DeprecateRequest request) {
        String since = request == null || request.since() == null ? "2026-09-19" : request.since();
        String removedAfter = request == null || request.removed_after() == null
                ? "2026-10-19" : request.removed_after();
        ToolDefinition definition = registry.deprecate(name, since, removedAfter);
        return describe(definition);
    }

    // ─────────── IN-06 影响分析 ───────────
    @GetMapping("/{name}/impact")
    public Map<String, Object> impact(@PathVariable String name) {
        ImpactReport report = registry.impact(name);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tool_name", report.toolName());
        row.put("current_version", report.currentVersion());
        row.put("schema_hash", report.schemaHash());
        row.put("affected_agents", report.affectedAgents());
        row.put("affected_flows", report.affectedFlows());
        row.put("schema_changed", report.schemaChanged());
        row.put("contract_test_required", report.contractTestRequired());
        row.put("note", report.note());
        return row;
    }

    // ─────────── R5-02 执行 ───────────
    @PostMapping("/execute")
    public Map<String, Object> execute(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-Requested-By", defaultValue = "anonymous") String requestedBy,
            @RequestBody ExecuteRequest request) {
        if (request == null || request.tool_name() == null || request.tool_name().isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "缺少工具名 tool_name");
        }
        ToolContext context = new ToolContext(tenantId,
                request.call_id() == null ? ToolCallResult.newCallId() : request.call_id(),
                System.currentTimeMillis() + 60_000, requestedBy);

        ToolCallResult result = executor.execute(request.tool_name(), request.arguments(), context);
        if (!result.success()) {
            // 失败：走统一错误信封 + 真实状态码（details 带 call_id / audit_id / 字段级错误）
            Map<String, String> details = new LinkedHashMap<>(result.details());
            details.put("call_id", result.callId());
            if (result.auditId() != null) details.put("audit_id", result.auditId());
            details.put("latency_ms", String.valueOf(result.latencyMs()));
            throw new BizException(codeOf(result.errorCode()), result.errorMessage(), details);
        }
        Map<String, Object> row = new LinkedHashMap<>(result.toMap());
        row.put("tenant_id", tenantId);
        return row;
    }

    // ─────────── R5-08 审计 ───────────
    @GetMapping("/audit")
    public Map<String, Object> audit(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<Map<String, Object>> items = auditLog.list(tenantId, Math.min(Math.max(limit, 1), 500));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenantId);
        result.put("total", items.size());
        result.put("items", items);
        result.put("audit_backend", auditLog.health().get("backend"));
        result.put("degraded", auditLog.health().get("degraded"));
        return result;
    }

    @GetMapping("/audit/stats")
    public Map<String, Object> auditStats(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenantId);
        result.put("audit", auditLog.stats(tenantId));
        result.put("kafka", publisher.health());
        return result;
    }

    // ─────────── 视图辅助 ───────────
    @GetMapping("/metrics")
    public Map<String, Object> metricsSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metrics", metrics.snapshot());
        List<Map<String, Object>> breakers = new ArrayList<>();
        registry.list().forEach(definition -> breakers.add(executor.breakerState(definition.meta().name())));
        result.put("circuit_breakers", breakers);
        return result;
    }

    @GetMapping("/sandbox")
    public Map<String, Object> sandboxStatus() {
        return sandbox.status();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "tool-executor");
        result.put("status", "ok");
        result.put("tools", registry.list().size());
        result.put("registry_backend", "in-memory");
        result.put("audit", auditLog.health());
        result.put("sandbox", sandbox.status());
        return result;
    }

    // ─────────── 内部 ───────────
    private Map<String, Object> describe(ToolDefinition definition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", definition.meta().name());
        row.put("version", definition.meta().version());
        row.put("description", definition.meta().description());
        row.put("parameters_schema", definition.meta().parametersSchema());
        row.put("schema_hash", ToolRegistry.fingerprint(definition.meta().parametersSchema()));
        row.put("sandbox_required", definition.meta().sandboxRequired());
        row.put("timeout_ms", definition.meta().timeoutMs());
        row.put("whitelist_domains", definition.meta().whitelistDomains());
        row.put("deprecated", definition.meta().deprecated());
        row.put("deprecated_since", definition.meta().deprecatedSince());
        row.put("removed_after", definition.meta().removedAfter());
        row.put("owner", definition.meta().owner());
        row.put("registered_at", definition.registeredAt());
        row.put("circuit", executor.breakerState(definition.meta().name()));
        return row;
    }

    private static ErrorCode codeOf(String code) {
        if (code == null) return ErrorCode.AGENT_INTERNAL_ERROR;
        for (ErrorCode candidate : ErrorCode.values()) {
            if (candidate.code().equals(code)) return candidate;
        }
        return ErrorCode.AGENT_TOOL_EXEC_FAILED;
    }

    public record ExecuteRequest(String tool_name, Map<String, Object> arguments, String call_id) { }

    public record DeprecateRequest(String since, String removed_after) { }
}