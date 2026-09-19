package com.agent.session.orchestration;

/**
 * 一次请求的**总预算（deadline）** —— 调用链上的每一跳都只能在剩余预算内发起。
 *
 * <p><b>为什么需要它</b>（登记表 REC-01）：若每一跳各自用「自己的档位超时」，链路最坏耗时就是
 * <b>各跳超时之和</b>，叠加重试后是乘积。后果有两个：
 * <ul>
 *   <li>上游无论把预算调多大都可能不够 —— 因为下游会自己膨胀；</li>
 *   <li>「快速失败并降级」被变成「长时间等待后仍然降级」，既伤体验又掩盖故障。</li>
 * </ul>
 *
 * <p><b>做法</b>：入口处创建一个预算并往下传；每一跳的读超时取
 * {@code min(该跳档位上限, 剩余预算)}，剩余为 0 时**不再发起调用**，
 * 直接如实降级并标注 {@code budget_exhausted}。
 *
 * <p><b>关键约束</b>：各跳档位上限之和必须 {@code <=} 总预算 —— 否则「总预算」只是装饰。
 * 本类的存在使登记表里可以登记**一个常量**作为「下游最坏耗时」，而不是一串加法。
 */
public final class RequestBudget {

    private final long totalMs;
    private final long startedNanos;

    private RequestBudget(long totalMs) {
        this.totalMs = Math.max(0, totalMs);
        this.startedNanos = System.nanoTime();
    }

    /** 以「总预算（毫秒）」起算，计时从此刻开始。 */
    public static RequestBudget of(long totalMs) {
        return new RequestBudget(totalMs);
    }

    public long totalMs() {
        return totalMs;
    }

    public long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** 剩余预算；已耗尽返回 0（不返回负数，避免被误当成「无限」）。 */
    public long remainingMs() {
        return Math.max(0, totalMs - elapsedMs());
    }

    public boolean exhausted() {
        return remainingMs() <= 0;
    }

    /**
     * 本跳实际可用的读超时（毫秒）：不超过该跳档位上限，也不超过剩余预算。
     *
     * <p>返回 0 表示**不应发起**该跳调用 —— 调用方须如实降级，不得改用「无超时」硬发。
     */
    public long clamp(long ceilingMs) {
        return Math.min(Math.max(0, ceilingMs), remainingMs());
    }
}
