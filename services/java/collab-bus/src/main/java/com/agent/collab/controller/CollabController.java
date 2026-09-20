package com.agent.collab.controller;

import com.agent.collab.delivery.ReliableConsumer;
import com.agent.collab.heartbeat.HeartbeatService;
import com.agent.collab.nats.NatsConnection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 协作总线健康与运行态端点（MC-01）。 */
@RestController
@RequestMapping("/api/collab")
public class CollabController {

    private final NatsConnection nats;
    private final ReliableConsumer consumer;
    private final HeartbeatService heartbeat;

    public CollabController(NatsConnection nats, ReliableConsumer consumer, HeartbeatService heartbeat) {
        this.nats = nats;
        this.consumer = consumer;
        this.heartbeat = heartbeat;
    }

    /** 服务、总线、consumer 与心跳合并状态：如实暴露能力与降级原因。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "collab-bus");
        body.put("status", "ok");
        body.put("checked_at", OffsetDateTime.now().toString());
        body.put("bus", nats.status());
        body.put("consumer", consumer.status());
        body.put("heartbeat", heartbeat.status());
        return body;
    }
}