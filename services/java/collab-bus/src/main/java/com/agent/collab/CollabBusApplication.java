package com.agent.collab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 协作总线服务（C26 · MC-01 独立切片，Phase 6 前置）。
 *
 * <p>职责：
 * <ul>
 *   <li>主题域管理 —— 协作域生命周期 + 按域隔离的 JetStream stream（R-MC01-01）</li>
 *   <li>可靠投递 —— JetStream 持久化 + request_id 幂等 + 死信（R-MC01-02）</li>
 *   <li>心跳聚合 —— 降频 / 失联标记 / 域进度聚合（R-MC01-03）</li>
 * </ul>
 *
 * <p>接口冻结（供 Phase 6 依赖）：planner 只调 {@code publish(domain, type, member, request_id, payload)}，
 * 进度只读聚合端点，不自行维护进度状态 —— 见 MC-P3-开发设计文档 §4。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class CollabBusApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollabBusApplication.class, args);
    }
}
