package com.agent.sense.channel;

import com.agent.sense.config.SenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 嗅觉渠道：舆情 / 情报采集。
 * 对配置的情报源列表（RSS / 公开页面 / API）批量抓取并汇总为一条采集结果；
 * 未配置任何情报源时渠道不可用（DEGRADED），由 Console 五感矩阵以琥珀色警示。
 */
@Slf4j
@Component
public class NoseChannel implements SenseChannel {

    private final TouchChannel touchChannel;
    private final SenseProperties properties;

    public NoseChannel(TouchChannel touchChannel, SenseProperties properties) {
        this.touchChannel = touchChannel;
        this.properties = properties;
    }

    @Override public ChannelType type() { return ChannelType.NOSE; }
    @Override public boolean register(Map<String, String> config) { return true; }

    /** 无情报源配置 → DEGRADED */
    @Override public boolean healthy() { return !feeds().isEmpty(); }

    @Override public boolean available() { return !feeds().isEmpty(); }

    @Override public void close() {}

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel(ChannelType.NOSE.name());
        result.setFreshness("NEAR_REALTIME");
        List<String> sources = new ArrayList<>();
        if (request.getDataSource() != null && !request.getDataSource().isBlank()) {
            sources.add(request.getDataSource().strip());
        } else {
            sources.addAll(feeds());
        }
        if (sources.isEmpty()) {
            result.setAccepted(false);
            result.setItemCount(0);
            result.setQualityScore(0);
            result.setError("no intelligence feed configured (SENSE_INTEL_FEEDS)");
            return result;
        }
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        int failed = 0;
        for (String url : sources) {
            try {
                String content = touchChannel.fetchUrlContent(url);
                if (!content.isBlank()) {
                    sb.append(content).append("\n");
                    ok++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("nose feed failed {}: {}", url, e.getMessage());
            }
        }
        String content = sb.toString().strip();
        result.setContent(content);
        result.setItemCount(ok);
        result.setQualityScore(sources.isEmpty() ? 0 : (double) ok / sources.size());
        result.setAccepted(ok > 0);
        result.setConfidence(ok > 0 ? 0.75 : 0);
        if (ok == 0) result.setError("all intelligence feeds failed (" + failed + " failed)");
        return result;
    }

    private List<String> feeds() {
        String raw = System.getenv().getOrDefault("SENSE_INTEL_FEEDS", "");
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String v = part.strip();
            if (!v.isBlank()) list.add(v);
        }
        if (list.isEmpty()) {
            for (SenseProperties.R0.Rule rule : properties.getR0().getRules()) {
                if ("NOSE".equalsIgnoreCase(rule.getChannel()) && rule.isEnabled()
                        && rule.getDataSource() != null && !rule.getDataSource().isBlank()) {
                    list.add(rule.getDataSource().strip());
                }
            }
        }
        return list;
    }
}
