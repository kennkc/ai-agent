package com.agent.sense.controller;

import com.agent.sense.channel.ChannelRegistry;
import com.agent.sense.channel.SenseChannel;
import com.agent.sense.collect.CollectPipeline;
import com.agent.sense.common.BizException;
import com.agent.sense.common.ErrorCode;
import com.agent.sense.dynamic.R1DynamicHandler;
import com.agent.sense.dynamic.SenseCommand;
import com.agent.sense.health.ChannelHealthMonitor;
import com.agent.sense.model.CollectedData;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 感官服务采集入口（R2-02/R2-06）
 */
@RestController
@RequestMapping("/api/sense")
public class SenseController {

    private final CollectPipeline pipeline;
    private final R1DynamicHandler dynamicHandler;
    private final ChannelRegistry registry;
    private final ChannelHealthMonitor healthMonitor;

    public SenseController(CollectPipeline pipeline, R1DynamicHandler dynamicHandler,
                           ChannelRegistry registry, ChannelHealthMonitor healthMonitor) {
        this.pipeline = pipeline;
        this.dynamicHandler = dynamicHandler;
        this.registry = registry;
        this.healthMonitor = healthMonitor;
    }

    /** 单渠道采集（兼容 Phase 1 调用方式，新增 channel 字段） */
    @PostMapping("/collect")
    public Map<String, Object> collect(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                       @RequestBody Map<String, String> request) {
        SenseChannel.CollectRequest req = new SenseChannel.CollectRequest();
        req.setDataSource(request.getOrDefault("data_source", ""));
        req.setQuery(request.getOrDefault("query", ""));
        req.setTenantId(tenantId);
        req.setMode(request.getOrDefault("mode", "R1_DYNAMIC"));
        req.setParams(new LinkedHashMap<>(request));
        SenseChannel.ChannelType channel = parseChannel(request.get("channel"));
        CollectedData data = pipeline.collect(channel, req);
        Map<String, Object> response = new LinkedHashMap<>(data.toMap());
        response.remove("content");
        response.put("content_preview", preview(data.getContent()));
        response.put("five_fields_complete", data.fiveFieldsComplete());
        return response;
    }

    /** R1 动态采集指令（BrainCommand 雏形） */
    @PostMapping("/command")
    public Map<String, Object> command(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                       @RequestBody Map<String, Object> request) {
        Object rawChannels = request.get("channels");
        List<String> channels = new ArrayList<>();
        if (rawChannels instanceof List<?> list) {
            for (Object item : list) if (item != null) channels.add(String.valueOf(item));
        } else if (request.get("channel") != null) {
            channels.add(String.valueOf(request.get("channel")));
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : request.entrySet()) {
            if (e.getValue() instanceof String s && !List.of("data_source", "query", "channel", "tenant_id").contains(e.getKey())) {
                params.put(e.getKey(), s);
            }
        }
        SenseCommand command = new SenseCommand(
                str(request.get("command_id")),
                channels,
                str(request.get("data_source")),
                str(request.get("query")),
                tenantId,
                params);
        return dynamicHandler.dispatch(command);
    }

    /** 渠道健康总览（Phase 1 兼容 + Phase 2 五渠道扩展） */
    @GetMapping("/health")
    public Map<String, Object> health() {
        List<Map<String, Object>> channels = new ArrayList<>();
        boolean allUp = true;
        for (SenseChannel channel : registry.all()) {
            ChannelHealthMonitor.Status status = healthMonitor.status(channel.type());
            if (status != ChannelHealthMonitor.Status.UP) allUp = false;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel", channel.type().name());
            item.put("healthy", status == ChannelHealthMonitor.Status.UP);
            item.put("status", status.name());
            channels.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "sense-service");
        response.put("healthy", allUp);
        response.put("status", allUp ? "UP" : "DEGRADED");
        response.put("channel", "TOUCH");
        response.put("channels", channels);
        response.put("request_id", UUID.randomUUID().toString());
        return response;
    }

    private SenseChannel.ChannelType parseChannel(String raw) {
        try {
            return SenseChannel.ChannelType.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, e.getMessage());
        }
    }

    private static String str(Object value) { return value == null ? null : String.valueOf(value); }

    private String preview(String content) {
        if (content == null || content.isEmpty()) return "";
        return content.length() > 200 ? content.substring(0, 200) + "..." : content;
    }
}
