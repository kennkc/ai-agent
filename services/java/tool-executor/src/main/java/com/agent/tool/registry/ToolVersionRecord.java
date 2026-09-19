package com.agent.tool.registry;

import java.util.List;

/**
 * 工具版本登记记录（IN-06 工具契约治理）。
 *
 * @param name          工具名
 * @param version       semver
 * @param schemaHash    parametersSchema 的 SHA-256 前 16 位（schema 变更的判据）
 * @param action        REGISTER / VERSION_UPGRADE / SCHEMA_CHANGE / DEPRECATE / UNREGISTER
 * @param previousVersion 变更前版本（首次注册为 null）
 * @param changedAt     毫秒时间戳
 * @param changedBy     变更人 / 来源
 * @param impact        本次变更的影响面（受影响 Agent/流程）
 */
public record ToolVersionRecord(
        String name,
        String version,
        String schemaHash,
        String action,
        String previousVersion,
        long changedAt,
        String changedBy,
        List<String> impact
) {
    public ToolVersionRecord {
        impact = impact == null ? List.of() : List.copyOf(impact);
    }
}