package com.agent.tool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 四肢层入口（Phase 5 四肢期）。
 *
 * <p>四件套职责：
 * <ul>
 *   <li>{@code ToolRegistry}   工具注册表（R5-01）+ 契约版本治理（IN-06）</li>
 *   <li>{@code ToolGuard}      白名单 / 参数 Schema / 敏感参数拦截（R5-07）</li>
 *   <li>{@code ToolExecutor}   执行引擎：校验 → 执行 → 超时熔断 → 结果标准化（R5-02）</li>
 *   <li>{@code SandboxExecutor} 沙箱隔离（R5-03/R5-05）</li>
 *   <li>{@code ToolAuditLog}   全量调用审计（R5-08）</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ToolExecutorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToolExecutorApplication.class, args);
    }
}