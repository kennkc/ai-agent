package com.agent.sense.dynamic;

import java.util.List;
import java.util.Map;

/**
 * R1 动态采集指令（BrainCommand 雏形 · R2-06）
 * 由大脑（或 Console / 运维指令）发起，指定渠道与数据源触发一次定向采集。
 */
public record SenseCommand(
        String commandId,
        List<String> channels,
        String dataSource,
        String query,
        String tenantId,
        Map<String, String> params
) {
    public static SenseCommand of(String channel, String dataSource, String query, String tenantId) {
        return new SenseCommand(null,
                channel == null || channel.isBlank() ? List.of() : List.of(channel),
                dataSource, query, tenantId, Map.of());
    }
}
