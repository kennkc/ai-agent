package com.agent.tool.registry;

import com.agent.tool.common.BizException;
import com.agent.tool.common.ErrorCode;
import com.agent.tool.model.ToolDefinition;
import com.agent.tool.model.ToolHandler;
import com.agent.tool.model.ToolMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（R5-01）+ 契约版本治理（IN-06）。
 *
 * <p><b>存储口径（如实）</b>：注册表本体为**进程内注册 + 启动时内置工具自注册**；
 * 版本与变更历史同进程内维护（{@code ToolRegistry} 内存结构），**未接 etcd**——
 * 设计文档写 "etcd/内存"，本阶段取"内存"一侧。跨实例共享与持久化留待后续（DEBT-019）。
 *
 * <p><b>IN-06 三件事</b>：
 * <ol>
 *   <li>semver 版本化：每次注册记录 {@code name@version} 与 schema 指纹；</li>
 *   <li>变更影响分析：schema/版本变化时列出**登记过的**受影响 Agent 与流程；</li>
 *   <li>契约测试触发：schema 变更时置 {@code contractTestRequired=true}（由契约门禁消费）。</li>
 * </ol>
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Deque<ToolVersionRecord>> history = new ConcurrentHashMap<>();
    /** tool → 声明使用该工具的 Agent / 流程标识（IN-06 影响分析的数据源） */
    private final Map<String, Set<String>> agentConsumers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> flowConsumers = new ConcurrentHashMap<>();

    // ─────────── R5-01 注册 / 列出 / 卸载 ───────────

    /** 注册（幂等：同名重复注册视为版本升级，自动记录变更并做影响分析） */
    public synchronized ToolDefinition register(ToolMeta meta, ToolHandler handler) {
        if (meta == null || meta.name() == null || meta.name().isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "工具名不能为空");
        }
        if (handler != null && !meta.name().equals(handler.name())) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST,
                    "工具名与实现不一致：" + meta.name() + " vs " + handler.name());
        }
        ToolDefinition previous = definitions.get(meta.name());
        String schemaHash = fingerprint(meta.parametersSchema());
        String action;
        String previousVersion = null;

        if (previous == null) {
            action = "REGISTER";
        } else {
            previousVersion = previous.meta().version();
            String previousHash = fingerprint(previous.meta().parametersSchema());
            if (!schemaHash.equals(previousHash)) {
                action = "SCHEMA_CHANGE";
            } else if (!meta.version().equals(previousVersion)) {
                action = "VERSION_UPGRADE";
            } else {
                action = "REREGISTER";
            }
        }

        ToolDefinition definition = ToolDefinition.bind(meta, handler);
        definitions.put(meta.name(), definition);

        List<String> impact = affected(meta.name());
        ToolVersionRecord record = new ToolVersionRecord(meta.name(), meta.version(), schemaHash, action,
                previousVersion, System.currentTimeMillis(), meta.owner(), impact);
        history.computeIfAbsent(meta.name(), key -> new ArrayDeque<>()).addLast(record);
        if (!"REGISTER".equals(action)) {
            log.info("工具契约变更 name={} {}→{} action={} 受影响={}", meta.name(), previousVersion,
                    meta.version(), action, impact);
        }
        return definition;
    }

    /** 卸载；返回是否真的移除了条目 */
    public synchronized boolean unregister(String name) {
        ToolDefinition removed = definitions.remove(name);
        if (removed == null) return false;
        List<String> impact = affected(name);
        history.computeIfAbsent(name, key -> new ArrayDeque<>())
                .addLast(new ToolVersionRecord(name, removed.meta().version(),
                        fingerprint(removed.meta().parametersSchema()), "UNREGISTER",
                        removed.meta().version(), System.currentTimeMillis(), "api", impact));
        return true;
    }

    /** 标记废弃（IN-06：废弃期 30 天，期间仍可调用但返回 deprecated=true） */
    public synchronized ToolDefinition deprecate(String name, String since, String removedAfter) {
        ToolDefinition current = require(name);
        ToolMeta meta = current.meta();
        ToolMeta deprecated = new ToolMeta(meta.name(), meta.version(), meta.description(),
                meta.parametersSchema(), meta.sandboxRequired(), meta.timeoutMs(), meta.whitelistDomains(),
                meta.sensitivePatterns(), true, since, removedAfter, meta.owner());
        definitions.put(name, new ToolDefinition(deprecated, current.handler(), current.registeredAt()));
        history.computeIfAbsent(name, key -> new ArrayDeque<>())
                .addLast(new ToolVersionRecord(name, meta.version(), fingerprint(meta.parametersSchema()),
                        "DEPRECATE", meta.version(), System.currentTimeMillis(), "api", affected(name)));
        return definitions.get(name);
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public ToolDefinition require(String name) {
        ToolDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new BizException(ErrorCode.AGENT_TOOL_NOT_ALLOWED, "未注册的工具：" + name)
                    .with("tool_name", name);
        }
        return definition;
    }

    public List<ToolDefinition> list() {
        List<ToolDefinition> all = new ArrayList<>(definitions.values());
        all.sort(Comparator.comparing(item -> item.meta().name()));
        return all;
    }

    public List<ToolVersionRecord> versions(String name) {
        Deque<ToolVersionRecord> records = history.get(name);
        return records == null ? List.of() : List.copyOf(records);
    }

    public List<ToolVersionRecord> changeLog() {
        List<ToolVersionRecord> all = new ArrayList<>();
        history.values().forEach(all::addAll);
        all.sort(Comparator.comparingLong(ToolVersionRecord::changedAt).reversed());
        return all;
    }

    // ─────────── IN-06 影响分析 ───────────

    /** 登记「某 Agent / 流程依赖某工具」——影响分析据此给出**确定的**受影响清单 */
    public void registerConsumer(String toolName, String agentId, String flowId) {
        if (agentId != null && !agentId.isBlank()) {
            agentConsumers.computeIfAbsent(toolName, key -> ConcurrentHashMap.newKeySet()).add(agentId);
        }
        if (flowId != null && !flowId.isBlank()) {
            flowConsumers.computeIfAbsent(toolName, key -> ConcurrentHashMap.newKeySet()).add(flowId);
        }
    }

    public ImpactReport impact(String name) {
        ToolDefinition definition = find(name).orElse(null);
        String version = definition == null ? null : definition.meta().version();
        String hash = definition == null ? fingerprint("") : fingerprint(definition.meta().parametersSchema());
        boolean schemaChanged = versions(name).stream()
                .anyMatch(record -> "SCHEMA_CHANGE".equals(record.action()));

        List<String> agents = sorted(agentConsumers.get(name));
        List<String> flows = sorted(flowConsumers.get(name));
        boolean contractTestRequired = schemaChanged && (!agents.isEmpty() || !flows.isEmpty());

        String note = definition == null
                ? "工具未注册"
                : (agents.isEmpty() && flows.isEmpty()
                    ? "无消费方登记——影响分析只覆盖**显式登记过**的 Agent/流程；硬编码调用方不会出现在此列表中"
                    : "以上为注册表登记的消费方，变更后需回归其契约测试");
        return new ImpactReport(name, version, hash, agents, flows, schemaChanged,
                contractTestRequired, note);
    }

    private List<String> affected(String name) {
        List<String> combined = new ArrayList<>(sorted(agentConsumers.get(name)));
        combined.addAll(sorted(flowConsumers.get(name)));
        return combined;
    }

    private static List<String> sorted(Set<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<String> list = new ArrayList<>(source);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /** schema 指纹：SHA-256 前 16 位十六进制（判"schema 是否变了"的稳定依据） */
    public static String fingerprint(String schema) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(schema).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) builder.append(String.format("%02x", hash[i]));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(schema).hashCode());
        }
    }

    /** 供视图层用的注册表摘要 */
    public Map<String, Object> summary() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tool_count", definitions.size());
        row.put("deprecated_count", definitions.values().stream().filter(d -> d.meta().deprecated()).count());
        row.put("change_count", changeLog().size());
        row.put("storage", "in-memory");
        row.put("registry_backend", "in-memory");
        return row;
    }
}