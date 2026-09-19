package com.agent.tool.sandbox;

import com.agent.tool.common.BizException;
import com.agent.tool.common.ErrorCode;
import com.agent.tool.guard.ToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱执行器（R5-03 / R5-05）——沙箱的**唯一入口**。
 *
 * <p>决策顺序（对应需求文档 §6 执行流程图）：
 * <pre>
 *   静态预检（危险代码？）── 命中 → 拒绝（AGENT_SANDBOX_REJECTED，告警 + 审计）
 *     └─ 通过 → 选后端：Docker 可用? ── 是 → docker 真隔离（degraded=false）
 *                                   └─ 否 → 受限子进程（degraded=true，显式标注）
 *          → 执行（硬超时）→ 超时? → 强制终止 + 熔断信号
 * </pre>
 *
 * <p>沙箱总开关 {@code SANDBOX_ENABLED=false} 时直接拒绝代码执行（部署文档 §8 回滚路径）。
 */
@Component
public class SandboxExecutor {
    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    private final ToolGuard guard;
    private final DockerSandboxBackend dockerBackend;
    private final RestrictedProcessBackend processBackend;
    private final boolean enabled;
    private final int defaultTimeoutMs;
    private final int defaultMemoryMb;

    public SandboxExecutor(ToolGuard guard,
                           DockerSandboxBackend dockerBackend,
                           RestrictedProcessBackend processBackend,
                           @Value("${app.tool.sandbox.enabled:true}") boolean enabled,
                           @Value("${app.tool.sandbox.timeout-ms:10000}") int defaultTimeoutMs,
                           @Value("${app.tool.sandbox.memory-mb:256}") int defaultMemoryMb) {
        this.guard = guard;
        this.dockerBackend = dockerBackend;
        this.processBackend = processBackend;
        this.enabled = enabled;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.defaultMemoryMb = defaultMemoryMb;
    }

    public boolean enabled() { return enabled; }

    /** 当前生效的后端（Docker 优先） */
    public SandboxBackend activeBackend() {
        if (dockerBackend.available()) return dockerBackend;
        return processBackend;
    }

    public SandboxResult execute(SandboxSpec spec) {
        if (!enabled) {
            throw new BizException(ErrorCode.AGENT_SANDBOX_REJECTED,
                    "沙箱已禁用（SANDBOX_ENABLED=false），代码执行不可用");
        }
        // 第一道闸：静态预检（危险操作在进入任何后端前就被拦）
        List<String> hits = guard.staticCodeScan(spec.code());
        if (!hits.isEmpty()) {
            log.warn("沙箱静态预检拦截：hit_count={} code_prefix={}", hits.size(),
                    spec.code() == null ? "" : spec.code().substring(0, Math.min(48, spec.code().length())));
            return SandboxResult.rejected("代码命中危险操作规则，已拦截（" + hits.size() + " 条规则），未进入沙箱执行");
        }

        SandboxBackend backend = activeBackend();
        SandboxSpec effective = new SandboxSpec(spec.language(),
                spec.code(),
                spec.timeoutMs() > 0 ? spec.timeoutMs() : defaultTimeoutMs,
                spec.memoryMb() > 0 ? spec.memoryMb() : defaultMemoryMb,
                spec.networkEnabled());
        SandboxResult result = backend.run(effective);
        // 降级后端必须把 degraded 标出来，任何情况下都不隐瞒
        if (!backend.isolated() && !result.degraded()) {
            result = new SandboxResult(result.success(), result.stdout(), result.stderr(), result.exitCode(),
                    result.backend(), true, result.rejected(), result.note());
        }
        return result;
    }

    /** 沙箱状态（工作平台执行视图的"沙箱状态"数据源，R-C05） */
    public Map<String, Object> status() {
        SandboxBackend active = activeBackend();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", enabled);
        row.put("active_backend", active.name());
        row.put("isolated", active.isolated());
        row.put("degraded", !active.isolated());
        row.put("docker_available", dockerBackend.available());
        row.put("process_fallback_available", processBackend.available());
        row.put("timeout_ms", defaultTimeoutMs);
        row.put("memory_mb", defaultMemoryMb);
        row.put("note", active.isolated()
                ? "Docker 真隔离：网络禁用 / 只读 rootfs / 去能力 / 资源限"
                : "未检测到可用沙箱镜像 —— 已降级为受限子进程（非隔离边界），仅静态预检 + 硬超时");
        return row;
    }
}