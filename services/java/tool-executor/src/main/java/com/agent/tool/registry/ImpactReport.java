package com.agent.tool.registry;

import java.util.List;

/**
 * 影响分析报告（IN-06 验收：「变更时自动列出受影响 Agent/流程；schema 变更触发 L1 契约测试」）。
 *
 * <p>数据来源是**注册表登记的消费方**（{@code ToolRegistry.registerConsumer}），
 * 因此它反映的是"声明过的调用方"，而非猜测。若某 Agent 直接硬编码调用而未登记，
 * 本报告**查不到它** —— 这一点在《技术债台账》DEBT-019 中如实登记。
 */
public record ImpactReport(
        String toolName,
        String currentVersion,
        String schemaHash,
        List<String> affectedAgents,
        List<String> affectedFlows,
        boolean schemaChanged,
        boolean contractTestRequired,
        String note
) {
    public ImpactReport {
        affectedAgents = affectedAgents == null ? List.of() : List.copyOf(affectedAgents);
        affectedFlows = affectedFlows == null ? List.of() : List.copyOf(affectedFlows);
    }
}