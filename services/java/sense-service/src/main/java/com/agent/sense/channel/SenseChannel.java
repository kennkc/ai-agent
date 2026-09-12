package com.agent.sense.channel;

import lombok.Data;

import java.util.Map;

/**
 * 感官渠道抽象接口（R2-01）
 * 五感官渠道：视觉/听觉/触觉/嗅觉/味觉
 * 契约：Register / Collect / Healthy / Close —— 渠道可插拔，新增渠道不改核心代码。
 */
public interface SenseChannel {

    /** 渠道类型 */
    ChannelType type();

    /** 渠道注册（幂等，返回是否注册成功） */
    boolean register(Map<String, String> config);

    /** 执行采集 */
    CollectResult collect(CollectRequest request);

    /** 渠道健康检查 */
    boolean healthy();

    /** 关闭渠道 */
    void close();

    /** 渠道是否可用（未实现 / 已停用时为 false，路由层据此降级） */
    default boolean available() { return true; }

    enum ChannelType {
        VISUAL("视觉", "👁️"),
        AUDIO("听觉", "👂"),
        TOUCH("触觉", "✋"),
        NOSE("嗅觉", "👃"),
        TASTE("味觉", "👅");

        private final String label;
        private final String icon;

        ChannelType(String label, String icon) { this.label = label; this.icon = icon; }

        public String label() { return label; }
        public String icon() { return icon; }

        public static ChannelType parse(String raw) {
            if (raw == null || raw.isBlank()) return TOUCH;
            String v = raw.trim().toUpperCase();
            for (ChannelType t : values()) if (t.name().equals(v)) return t;
            // 兼容中文名称
            for (ChannelType t : values()) if (t.label.equals(raw.trim())) return t;
            throw new IllegalArgumentException("unknown channel: " + raw);
        }
    }

    @Data
    class CollectRequest {
        private String dataSource;
        private String query;
        private Map<String, String> params;
        private String tenantId;
        private String mode; // R0_DEFAULT / R1_DYNAMIC
    }

    @Data
    class CollectResult {
        private String batchId;
        private String sourceChannel;
        private int itemCount;
        private double qualityScore;
        private boolean accepted;
        private String content;
        /** 渠道自报置信度（0-1），标准化层据此打标 */
        private double confidence = -1;
        /** 采集失败原因（供死信与降级判断） */
        private String error;
        /** 渠道自报时效标记（可空，标准化层按模式推断） */
        private String freshness;

        public boolean failed() { return !accepted; }
    }
}
