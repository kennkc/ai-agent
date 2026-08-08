package com.agent.sense.controller;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.channel.TouchChannel;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 感官采集接口（Phase 0 最小可用）
 */
@RestController
@RequestMapping("/api/sense")
public class SenseController {

    private final TouchChannel touchChannel;

    public SenseController(TouchChannel touchChannel) {
        this.touchChannel = touchChannel;
    }

    /** 采集接口：传入 URL 或文本，返回采集结果 */
    @PostMapping("/collect")
    public Map<String, Object> collect(@RequestBody Map<String, String> request) {
        SenseChannel.CollectRequest req = new SenseChannel.CollectRequest();
        req.setDataSource(request.getOrDefault("data_source", ""));
        req.setQuery(request.getOrDefault("query", ""));
        req.setTenantId(request.getOrDefault("tenant_id", "default"));
        req.setMode(request.getOrDefault("mode", "R1_DYNAMIC"));

        SenseChannel.CollectResult result = touchChannel.collect(req);
        return Map.of(
                "batch_id", result.getBatchId(),
                "source_channel", result.getSourceChannel(),
                "item_count", result.getItemCount(),
                "quality_score", result.getQualityScore(),
                "accepted", result.isAccepted(),
                "content_preview", preview(result.getContent())
        );
    }

    /** 渠道健康检查 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "channel", "TOUCH",
                "healthy", touchChannel.healthy(),
                "request_id", UUID.randomUUID().toString()
        );
    }

    private String preview(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.length() > 200 ? content.substring(0, 200) + "..." : content;
    }
}
