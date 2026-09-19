package com.agent.body.common;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 端到端预算执行器（2026-09-19 · GAP-05 闭合 · 与 REC-01 同路线）。
 *
 * <h2>为什么需要它</h2>
 *
 * 检索管线是**同步串行**的：向量化（nlp，预算 12s）→ Qdrant（实测 P99 &lt; 100ms）
 * → 重排（nlp，读超时 10s）。各段超时互相独立时，端点最坏耗时是**各段之和**
 * （≈ 24s+）—— 而 `contracts/timeout-budget.yaml` TB-16 曾把下游最坏登记为
 * 「body 自己的出站读超时 10s」，**把单个出站超时误当成了端到端最坏耗时**，
 * 上游（nlp-service）8s 预算对真实最坏的余量比远比表上难看。
 *
 * <p>教训与 wp-bff / session-manager 侧同源：<b>"下游最坏耗时"必须是端到端的最坏，
 * 不能拿某一跳的读超时冒充</b>。修法不是调大上游，而是给下游一个**共享总预算**，
 * 让"最坏耗时"从一串加法变成一个常量 —— 本类就是那个常量的执行者。
 *
 * <h2>语义（与 Python 侧 {@code app/budget.py} 的 run_with_budget 对齐）</h2>
 * <ul>
 *   <li>预算内未完成 → 抛 {@link ErrorCode#AGENT_TIMEOUT}（→ 504 AGENT_TIMEOUT），
 *       **绝不静默返回空结果冒充"没有数据"** —— 检索是 Must 项，假结果比慢更危险；</li>
 *   <li>{@code budgetMs <= 0} 表示**显式不限**（测试与"未配置"情形），不是"立即失败"；</li>
 *   <li>业务异常原样向上传播 —— 本类只负责**时间**，不改写业务错误语义。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>：Java 虽可 {@code cancel(true)} 中断工作线程，但底层 HTTP 调用
 * 不一定响应中断 —— 超时后后端调用可能仍在跑，结果被丢弃。这是**有意的取舍**：
 * 保证调用方按时拿到 504 并如实告知，代价是后端可能空转。
 * 真正需要强杀的场景由 tool-executor 的沙箱子进程承担。
 */
public final class BudgetGuard {

    private BudgetGuard() {
    }

    /**
     * 在预算内执行 {@code fn}；超时抛 {@link BizException}（AGENT_TIMEOUT）。
     *
     * @param budgetMs 端到端预算（毫秒）；&lt;= 0 表示显式不限
     * @param operation 操作名（进入异常 details 与线程名，便于定位是哪条链路慢）
     */
    public static <T> T runWithBudget(Callable<T> fn, long budgetMs, String operation) {
        if (budgetMs <= 0) {
            try {
                return fn.call();
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ErrorCode.AGENT_INTERNAL_ERROR,
                        operation + " 执行失败：" + e.getMessage(), e);
            }
        }

        long started = System.nanoTime();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "budget-" + operation);
            thread.setDaemon(true);
            return thread;
        });
        try {
            return executor.submit(fn).get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            throw new BizException(ErrorCode.AGENT_TIMEOUT,
                    operation + " 超出端到端预算 " + budgetMs + "ms（实际 " + elapsed
                            + "ms）—— 这是「慢」而不是「无结果」");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_INTERNAL_ERROR, operation + " 等待被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BizException(ErrorCode.AGENT_INTERNAL_ERROR,
                    operation + " 执行失败：" + cause.getMessage(), cause);
        } finally {
            // 不等待：工作线程可能仍在跑（见类注释「诚实边界」），等待只会把超时重新变成阻塞。
            executor.shutdownNow();
        }
    }
}
