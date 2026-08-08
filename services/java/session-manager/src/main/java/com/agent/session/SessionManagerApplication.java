package com.agent.session;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 会话管理服务入口
 * Phase 0: 基础启动 + 健康检查
 * Phase 4: 会话状态机 FSM + Redis 持久化
 */
@SpringBootApplication
@EnableDiscoveryClient
public class SessionManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SessionManagerApplication.class, args);
    }
}
