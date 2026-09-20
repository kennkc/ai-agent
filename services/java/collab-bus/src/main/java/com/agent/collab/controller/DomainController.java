package com.agent.collab.controller;

import com.agent.collab.domain.CollabDomain;
import com.agent.collab.domain.DomainService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协作域端点（R-MC01-01）。
 *
 * <p>租户一律取自 {@code X-Tenant-Id}（由网关写入），不接受请求体中的租户字段
 * —— 与全平台多租户约定一致。跨租户查询返回 404（不泄露存在性）。
 */
@RestController
@RequestMapping("/api/collab/domains")
public class DomainController {

    private final DomainService service;

    public DomainController(DomainService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody(required = false) CreateRequest request) {
        String name = request == null ? "" : request.name();
        return wrap(service.summary(tenantId, service.create(tenantId, name).domainId()));
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        List<Map<String, Object>> items = service.list(tenantId).stream()
                .map(d -> service.summary(tenantId, d.domainId()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    @GetMapping("/{domainId}")
    public Map<String, Object> detail(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String domainId) {
        return wrap(service.summary(tenantId, domainId));
    }

    /** 关闭域：幂等；此后该域消息一律被拒（409），已在 MC-P3 需求中冻结。 */
    @DeleteMapping("/{domainId}")
    public Map<String, Object> close(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String domainId) {
        CollabDomain domain = service.close(tenantId, domainId);
        return wrap(service.summary(tenantId, domain.domainId()));
    }

    private Map<String, Object> wrap(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        return body;
    }

    /** 创建请求体：仅接受显示名 —— 租户来自请求头，拒绝客户端传租户（防越权）。 */
    public record CreateRequest(String name) { }
}
