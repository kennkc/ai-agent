package com.agent.gateway;

// DEBT-005: 最小 JWT 鉴权（VS1 简化版）— 触发点: P7 演进 OAuth2+RBAC（见 D5-3 安全演进路径）

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API 网关服务入口
 * Phase 0: 基础启动 + 健康检查
 * Phase 1: 路由分发 + JWT 鉴权 + 链路追踪
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
