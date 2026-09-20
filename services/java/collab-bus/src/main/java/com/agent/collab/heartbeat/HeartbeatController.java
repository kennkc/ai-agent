package com.agent.collab.heartbeat;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 心跳上报与成员查询端点（R-MC01-03）。 */
@RestController
@RequestMapping("/api/collab/domains/{domainId}")
public class HeartbeatController {

    private final HeartbeatService service;

    public HeartbeatController(HeartbeatService service) {
        this.service = service;
    }

    @PostMapping("/heartbeats")
    public Map<String, Object> report(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String domainId,
            @RequestBody(required = false) HeartbeatRequest request) {
        if (request == null) {
            return wrap(service.report(tenantId, domainId, null, null, null, null));
        }
        return wrap(service.report(tenantId, domainId, request.memberId(), request.progress(),
                request.state(), request.weight()));
    }

    @GetMapping("/members")
    public Map<String, Object> members(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String domainId) {
        return service.members(tenantId, domainId);
    }

    private Map<String, Object> wrap(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        return body;
    }

    /** 上报请求体；weight 省略时按等权 1.0 处理。 */
    public record HeartbeatRequest(
            @JsonProperty("member_id") String memberId,
            Double progress,
            String state,
            Double weight) { }
}