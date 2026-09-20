package com.agent.collab.controller;

import com.agent.collab.nats.NatsConnection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 协作总线端点（MC-01）。
 *
 * <p>本阶段（骨架）仅提供健康端点；域管理 / 投递 / 心跳聚合随后续提交补齐。
 */
@RestController
@RequestMapping("/api/collab")
public class CollabController {

    private final NatsConnection nats;

    public CollabController(NatsConnection nats) {
        this.nats = nats;
    }

    /** 服务与总线状态：如实暴露 JetStream 可用性与降级原因。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "collab-bus");
        body.put("status", "ok");
        body.put("checked_at", OffsetDateTime.now().toString());
        body.put("bus", nats.status());
        return body;
    }
}
