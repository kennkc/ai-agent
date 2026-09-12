package com.agent.sense.dynamic;

import com.agent.sense.channel.SenseChannel;
import com.agent.sense.collect.CollectPipeline;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * R1 动态采集处理器（R2-06）
 * 指令 → 渠道选择（单轮渠道数 ≤ maxChannelsPerRound，防采集风暴）→ 采集 → 回传批次摘要。
 */
@Slf4j
@Component
public class R1DynamicHandler {

    private final CollectPipeline pipeline;
    private final SenseProperties properties;
    private final ObjectMapper objectMapper;

    public R1DynamicHandler(CollectPipeline pipeline, SenseProperties properties, ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> dispatch(SenseCommand command) {
        String commandId = command.commandId() == null || command.commandId().isBlank()
                ? UUID.randomUUID().toString() : command.commandId();
        String tenantId = command.tenantId() == null || command.tenantId().isBlank() ? "default" : command.tenantId();
        Set<SenseChannel.ChannelType> targets = resolveChannels(command);
        List<Map<String, Object>> results = new ArrayList<>();
        long started = System.currentTimeMillis();
        for (SenseChannel.ChannelType type : targets) {
            SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
            request.setDataSource(command.dataSource());
            request.setQuery(command.query());
            request.setTenantId(tenantId);
            request.setMode("R1_DYNAMIC");
            Map<String, String> params = new LinkedHashMap<>(command.params() == null ? Map.of() : command.params());
            params.put("command_id", commandId);
            request.setParams(params);
            CollectedData data = pipeline.collect(type, request);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel", type.name());
            item.put("batch_id", data.getBatchId());
            item.put("source_channel", data.getSourceChannel());
            item.put("staging_status", data.getStagingStatus() == null ? null : data.getStagingStatus().name());
            item.put("item_count", data.getItemCount());
            item.put("quality_score", data.getQualityScore());
            item.put("confidence", data.getConfidence());
            item.put("degraded", data.isDegraded());
            item.put("reject_reason", data.getRejectReason());
            results.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("command_id", commandId);
        response.put("tenant_id", tenantId);
        response.put("channel_count", results.size());
        response.put("batch_ids", results.stream().map(r -> r.get("batch_id")).toList());
        response.put("results", results);
        response.put("duration_ms", System.currentTimeMillis() - started);
        response.put("mode", "R1_DYNAMIC");
        log.info("R1 command {} dispatched to {} channel(s) in {}ms", commandId, results.size(), response.get("duration_ms"));
        return response;
    }

    /** 从 NATS 总线收到的 JSON 指令执行采集（回传 JSON 字符串） */
    public String dispatchJson(String payload) {
        try {
            SenseCommand command = objectMapper.readValue(payload, SenseCommand.class);
            return objectMapper.writeValueAsString(dispatch(command));
        } catch (Exception e) {
            log.warn("R1 command parse failed: {}", e.getMessage());
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /** 渠道选择：显式指定优先；未指定默认触觉渠道；单轮上限保护 */
    Set<SenseChannel.ChannelType> resolveChannels(SenseCommand command) {
        Set<SenseChannel.ChannelType> targets = new LinkedHashSet<>();
        List<String> requested = command.channels();
        if (requested != null) {
            for (String raw : requested) {
                if (raw == null || raw.isBlank()) continue;
                try {
                    targets.add(SenseChannel.ChannelType.parse(raw));
                } catch (IllegalArgumentException e) {
                    log.warn("unknown channel in command, skipped: {}", raw);
                }
            }
        }
        if (targets.isEmpty()) targets.add(SenseChannel.ChannelType.TOUCH);
        int limit = Math.max(1, properties.getR1().getMaxChannelsPerRound());
        if (targets.size() > limit) {
            log.warn("R1 command requested {} channels, truncated to {}", targets.size(), limit);
            targets = new LinkedHashSet<>(new ArrayList<>(targets).subList(0, limit));
        }
        return targets;
    }
}
