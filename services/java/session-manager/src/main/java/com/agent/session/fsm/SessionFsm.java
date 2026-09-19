package com.agent.session.fsm;

/**
 * R4-01 会话状态机（FSM）。
 *
 * <p>状态：{@code NEW → ACTIVE ⇄ IDLE → TIMEOUT/CLOSED}；事件：create / message / idle_ttl /
 * timeout_ttl / close。设计文档 §3.1 要求「状态流转正确 + 异常可恢复」，因此
 * {@code TIMEOUT} 允许经 {@link Event#MESSAGE} 恢复为 {@code ACTIVE}（会话续期）。
 *
 * <p><b>约定</b>：本类是**纯状态机**，不碰 Redis、不做 IO，便于单测穷举所有迁移；
 * 存储与 TTL 由 {@code SessionStore} 负责（R4-02）。
 */
public final class SessionFsm {

    public enum State {
        NEW, ACTIVE, IDLE, TIMEOUT, CLOSED
    }

    public enum Event {
        CREATE, MESSAGE, IDLE_TTL, TIMEOUT_TTL, CLOSE
    }

    private SessionFsm() {
    }

    /**
     * 状态迁移。
     *
     * @throws IllegalTransitionException 非法迁移（终态不可复活、事件与当前状态不匹配）
     */
    public static State transition(State current, Event event) {
        if (current == null || event == null) {
            throw new IllegalTransitionException(null, event, "state/event must not be null");
        }
        State next = switch (current) {
            case NEW -> switch (event) {
                case CREATE -> State.ACTIVE;
                case CLOSE -> State.CLOSED;
                default -> null;
            };
            case ACTIVE -> switch (event) {
                case MESSAGE -> State.ACTIVE;      // 自环：刷新活跃
                case IDLE_TTL -> State.IDLE;
                case CLOSE -> State.CLOSED;
                default -> null;
            };
            case IDLE -> switch (event) {
                case MESSAGE -> State.ACTIVE;      // 从空闲回到活跃
                case TIMEOUT_TTL -> State.TIMEOUT;
                case CLOSE -> State.CLOSED;
                default -> null;
            };
            case TIMEOUT -> switch (event) {
                case MESSAGE -> State.ACTIVE;      // 异常可恢复：超时后仍有消息则续期
                case CLOSE -> State.CLOSED;
                default -> null;
            };
            case CLOSED -> null;                   // 终态：任何事件都不再迁移
        };
        if (next == null) {
            throw new IllegalTransitionException(current, event, "transition not allowed");
        }
        return next;
    }

    /** 终态判定：终态不再接受任何事件（除 close 幂等返回自身）。 */
    public static boolean isTerminal(State state) {
        return state == State.CLOSED;
    }

    /** 该状态是否仍可接收用户消息（用于 /ask 前置校验）。 */
    public static boolean canAcceptMessage(State state) {
        return state == State.ACTIVE || state == State.IDLE || state == State.TIMEOUT;
    }

    public static class IllegalTransitionException extends RuntimeException {
        private final State from;
        private final Event event;

        public IllegalTransitionException(State from, Event event, String reason) {
            super("illegal transition: " + from + " --" + event + "--> (" + reason + ")");
            this.from = from;
            this.event = event;
        }

        public State getFrom() {
            return from;
        }

        public Event getEvent() {
            return event;
        }
    }
}
