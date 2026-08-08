package com.agent.sense.channel;

// DEBT-006: 渠道抽象仅 TOUCH 实现（简化）— 触发点: P5 五感官渠道齐备

import lombok.Data;

import java.util.Map;

/**
 * 感官渠道抽象接口（R2-01）
 * 五感官渠道：视觉/听觉/触觉/嗅觉/味觉
 */
public interface SenseChannel {

    /** 渠道类型 */
    ChannelType type();

    /** 渠道注册 */
    boolean register(Map<String, String> config);

    /** 执行采集 */
    CollectResult collect(CollectRequest request);

    /** 渠道健康检查 */
    boolean healthy();

    /** 关闭渠道 */
    void close();

    enum ChannelType {
        VISUAL, AUDIO, TOUCH, NOSE, TASTE
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
    }
}
