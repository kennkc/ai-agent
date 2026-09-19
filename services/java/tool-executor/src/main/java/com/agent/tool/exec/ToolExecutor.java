package com.agent.tool.exec;

import com.agent.tool.audit.AuditEventPublisher;
import com.agent.tool.audit.ToolAuditLog;
import com.agent.tool.common.ErrorCode;
import com.agent.tool.guard.ToolGuard;
import com.agent.tool.model.ToolCallResult;
import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolDefinition;
import com.agent.tool.model.ToolOutcome;
import com.agent.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行引擎（R5-02）。
 *
 * <p>固定顺序（对应需求文档 §4/§6 流程图）：
 * <pre>
 *   解析 → 白名单 → 参数 Schema → 敏感参数 → 熔断检查 → 带超时执行 → 结果标准化 → 审计
 * </pre>
 *
 * <p><b>两条不变量</b>：
 * <ol>
 *   <li><b>审计不丢</b>：无论成功、被拦、超时还是熔断，**都写一条审计**（R5-08「全量记录」）；
 *       因此本方法**不抛异常**——失败也返回标准化结果，由 Controller 决定 HTTP 语义。</li>
 *   <li><b>超时不悬挂</b>：工具在独立线程池执行并带硬超时；超时后置中断标志并计一次失败，
 *       达到阈值触发熔断，后续调用快速失败（R5-02「超时熔断 &lt; 5s」）。</li>
 * </ol>
 */
@Component
public class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolGuard guard;
    private final ToolAuditLog auditLog;
    private final AuditEventPublisher publisher;
    private final ToolMetrics metrics;
    private final ExecutorService pool;
    private final int failureThreshold;
    private final long circuitOpenMs;

    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    /** 简单熔断器：连续失败达阈值 → 打开 {@code circuitOpenMs}，期间快速失败 */
    static final class Breaker {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicLong openUntil = new AtomicLong(0);

        boolean open() { return System.currentTimeMillis() < openUntil.get(); }
    }

    public ToolExecutor(ToolRegistry registry, ToolGuard guard, ToolAuditLog auditLog,
                        AuditEventPublisher publisher, ToolMetrics metrics,
                        @Value("${app.tool.circuit.failure-threshold:3}") int failureThreshold,
                        @Value("${app.tool.circuit.open-ms:30000}") long circuitOpenMs) {
        this.registry = registry;
        this.guard = guard;
        this.auditLog = auditLog;
        this.publisher = publisher;
        this.metrics = metrics;
        this.failureThreshold = failureThreshold;
        this.circuitOpenMs = circuitOpenMs;
        this.pool = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "tool-exec");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ToolCallResult execute(String toolName, Map<String, Object> arguments, ToolContext context) {
        String callId = context != null && context.callId() != null ? context.callId() : ToolCallResult.newCallId();
        long started = System.currentTimeMillis();
        ToolDefinition definition = registry.find(toolName).orElse(null);
        String version = definition == null ? null : definition.meta().version();

        try {
            // ① 白名单（未注册/未授权 → 不触达执行器）
            guard.checkWhitelist(toolName);
            if (definition == null) {
                throw new com.agent.tool.common.BizException(ErrorCode.AGENT_TOOL_NOT_ALLOWED,
                        "未注册的工具：" + toolName, Map.of("tool_name", toolName));
            }
            // ② 参数 Schema ③ 敏感参数
            guard.checkSchema(definition, arguments);
            guard.checkSensitive(definition, arguments);

            // ④ 熔断
            Breaker breaker = breakers.computeIfAbsent(toolName, key -> new Breaker());
            if (breaker.open()) {
                long remaining = breaker.openUntil.get() - System.currentTimeMillis();
                return finish(context, arguments, ToolCallResult.failure(callId, toolName, version,
                        ErrorCode.AGENT_TOOL_CIRCUIT_OPEN.code(),
                        "工具连续失败触发熔断，约 " + Math.max(0, remaining / 1000) + "s 后重试", 
                        System.currentTimeMillis() - started), started);
            }

            // ⑤ 带超时执行
            long timeoutMs = definition.meta().timeoutMs() > 0 ? definition.meta().timeoutMs() : 10_000;
            Future<ToolOutcome> future = pool.submit(() -> definition.handler().execute(arguments, context));
            ToolOutcome outcome;
            try {
                outcome = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                breaker.consecutiveFailures.incrementAndGet();
                maybeOpen(breaker);
                return finish(context, arguments, ToolCallResult.failure(callId, toolName, version,
                        ErrorCode.AGENT_TIMEOUT.code(), "工具执行超时（" + timeoutMs + "ms）",
                        System.currentTimeMillis() - started), started);
            }

            long latency = System.currentTimeMillis() - started;
            if (outcome == null) {
                breaker.consecutiveFailures.incrementAndGet();
                maybeOpen(breaker);
                return finish(context, arguments, ToolCallResult.failure(callId, toolName, version,
                        ErrorCode.AGENT_TOOL_EXEC_FAILED.code(), "工具无返回", latency), started);
            }
            if (!outcome.success()) {
                // 业务失败也计入熔断器：连续失败说明工具本身不可靠
                breaker.consecutiveFailures.incrementAndGet();
                maybeOpen(breaker);
                // 失败路径**必须**把沙箱侧 stdout/stderr/exit_code 带回去：
                // R5-05 验收要求"代码运行返回 stdout/stderr"（失败是最常见的运行结果），
                // R5-02 验收要求"错误友好"（只说"非零退出码 1"等于没说原因）。
                ToolCallResult failed = ToolCallResult.failure(callId, toolName, version,
                        outcome.errorCode(), outcome.errorMessage(), latency, failureDetails(outcome.data()));
                if (outcome.data().containsKey("sandbox_backend")) {
                    failed = failed.withSandbox(true,
                            String.valueOf(outcome.data().get("sandbox_backend")),
                            Boolean.TRUE.equals(outcome.data().get("sandbox_degraded")));
                }
                return finish(context, arguments, failed, started);
            }

            breaker.consecutiveFailures.set(0);
            ToolCallResult success = ToolCallResult.success(callId, definition, outcome.output(), latency,
                    false, null, false);
            // 沙箱类工具把实际后端透传到结果上
            if (outcome.data().containsKey("sandbox_backend")) {
                success = success.withSandbox(true,
                        String.valueOf(outcome.data().get("sandbox_backend")),
                        Boolean.TRUE.equals(outcome.data().get("sandbox_degraded")));
            }
            return finish(context, arguments, success, started);
        } catch (com.agent.tool.common.BizException e) {
            if (e.errorCode() == ErrorCode.AGENT_TOOL_ARGS_BLOCKED
                    || e.errorCode() == ErrorCode.AGENT_SANDBOX_REJECTED) {
                log.warn("工具调用被安全拦截 tool={} code={} reason={}", toolName, e.errorCode().code(), e.getMessage());
                Breaker breaker = breakers.computeIfAbsent(toolName, key -> new Breaker());
                breaker.consecutiveFailures.incrementAndGet();
                maybeOpen(breaker);
            }
            long latency = System.currentTimeMillis() - started;
            return finish(context, arguments, ToolCallResult.failure(callId, toolName, version,
                    e.errorCode().code(), e.getMessage(), latency, e.details()), started);
        } catch (Exception e) {
            log.error("工具执行未预期异常 tool={}", toolName, e);
            return finish(context, arguments, ToolCallResult.failure(callId, toolName, version,
                    ErrorCode.AGENT_INTERNAL_ERROR.code(), "工具执行内部错误",
                    System.currentTimeMillis() - started), started);
        }
    }

    /**
     * 从工具返回的 data 里挑出**失败时仍需回传**的沙箱事实，转成统一信封的 {@code details}。
     *
     * <p>为什么单独做一步：{@code ToolOutcome.data()} 是 {@code Map<String,Object>}，
     * 而 {@code ToolCallResult.details} 是 {@code Map<String,String>}；此前直接把 data 丢掉，
     * 于是"沙箱执行返回非零退出码 1"成了调用方唯一能看到的线索。
     * stdout/stderr 截断到 2000 字符，避免错误信封被大段日志撑爆。
     */
    private static Map<String, String> failureDetails(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return Map.of();
        Map<String, String> details = new LinkedHashMap<>();
        for (String key : List.of("exit_code", "sandbox_backend", "stdout", "stderr")) {
            Object value = data.get(key);
            if (value == null) continue;
            String text = String.valueOf(value);
            if (text.isBlank() && !"exit_code".equals(key)) continue;
            details.put(key, ("stdout".equals(key) || "stderr".equals(key)) ? clip(text, 2000) : text);
        }
        return details;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…（已截断）";
    }

    /** 收口：审计（必写）+ 指标 + Kafka 旁路，然后返回标准化结果 */
    private ToolCallResult finish(ToolContext context, Map<String, Object> arguments,
                                  ToolCallResult result, long started) {
        String auditId = auditLog.record(context, result, arguments);
        ToolCallResult withAudit = result.withAudit(auditId);
        metrics.record(result.toolName(), result.success(), result.latencyMs(), result.errorCode());
        publisher.publish(withAudit.callId(), withAudit.toMap());
        return withAudit;
    }

    private void maybeOpen(Breaker breaker) {
        if (breaker.consecutiveFailures.get() >= failureThreshold) {
            breaker.openUntil.set(System.currentTimeMillis() + circuitOpenMs);
            breaker.consecutiveFailures.set(0);
        }
    }

    /** 熔断器状态（视图层"工具健康度"数据源） */
    public Map<String, Object> breakerState(String toolName) {
        Breaker breaker = breakers.get(toolName);
        if (breaker == null) return Map.of("tool_name", toolName, "state", "closed", "consecutive_failures", 0);
        return Map.of(
                "tool_name", toolName,
                "state", breaker.open() ? "open" : "closed",
                "consecutive_failures", breaker.consecutiveFailures.get(),
                "open_until", breaker.openUntil.get());
    }
}