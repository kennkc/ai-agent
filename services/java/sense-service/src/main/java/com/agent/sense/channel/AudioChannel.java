package com.agent.sense.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 听觉渠道（R2-03 预留）：语音 → 转写。
 * Phase 2 只保留接口与注册位，采集能力在 Phase 3 接入 ASR。
 */
@Slf4j
@Component
public class AudioChannel implements SenseChannel {

    @Override public ChannelType type() { return ChannelType.AUDIO; }
    @Override public boolean register(Map<String, String> config) { return true; }

    /** 预留渠道：尚未实现，健康探针返回 false，由健康监控标记为 DOWN 并自动隔离 */
    @Override public boolean healthy() { return false; }

    @Override public boolean available() { return false; }

    @Override public void close() {}

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel(ChannelType.AUDIO.name());
        result.setAccepted(false);
        result.setItemCount(0);
        result.setQualityScore(0);
        result.setError("AUDIO channel is reserved for Phase 3 (ASR not wired)");
        log.debug("audio channel invoked while reserved: {}", request.getDataSource());
        return result;
    }
}
