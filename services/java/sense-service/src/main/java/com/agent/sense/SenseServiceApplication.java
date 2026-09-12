package com.agent.sense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 感官渠道服务入口
 * Phase 0: 基础启动 + 健康检查
 * Phase 2: 五感官渠道 + R0/R1 采集
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@ConfigurationPropertiesScan
public class SenseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SenseServiceApplication.class, args);
    }
}
