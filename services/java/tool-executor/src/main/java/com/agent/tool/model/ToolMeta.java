package com.agent.tool.model;

import java.util.List;

/**
 * 工具元数据（R5-01 + IN-06 工具契约治理）。
 *
 * <p>{@code parametersSchema} 为 **JSON Schema Draft-07 字符串**（R5-07 的校验依据，
 * 同时是喂给 LLM 的工具描述来源）。{@code version} 为 **semver**：IN-06 要求
 * "工具接口 semver 版本化 + 变更影响分析"，故版本与 schema 指纹一并登记。
 *
 * <p>{@code sensitivePatterns} 为工具级敏感参数正则（叠加在 {@code ToolGuard} 全局
 * 拦截规则之上），命中即拒（R5-07「敏感参数拦截」）。
 */
public record ToolMeta(
        String name,
        String version,
        String description,
        String parametersSchema,
        boolean sandboxRequired,
        int timeoutMs,
        List<String> whitelistDomains,
        List<String> sensitivePatterns,
        boolean deprecated,
        String deprecatedSince,
        String removedAfter,
        String owner
) {
    public ToolMeta {
        whitelistDomains = whitelistDomains == null ? List.of() : List.copyOf(whitelistDomains);
        sensitivePatterns = sensitivePatterns == null ? List.of() : List.copyOf(sensitivePatterns);
        if (timeoutMs <= 0) timeoutMs = 10_000;
    }

    /** 便捷构造：非废弃、无域名白名单的普通工具 */
    public static ToolMeta of(String name, String version, String description, String schema,
                              boolean sandboxRequired, int timeoutMs) {
        return new ToolMeta(name, version, description, schema, sandboxRequired, timeoutMs,
                List.of(), List.of(), false, null, null, "lifeform-core");
    }

    public ToolMeta withSensitive(List<String> patterns) {
        return new ToolMeta(name, version, description, parametersSchema, sandboxRequired, timeoutMs,
                whitelistDomains, patterns, deprecated, deprecatedSince, removedAfter, owner);
    }
}