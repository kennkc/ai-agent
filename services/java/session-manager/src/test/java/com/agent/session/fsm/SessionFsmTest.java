package com.agent.session.fsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R4-01 会话状态机：逐条迁移 + 非法迁移 + 异常可恢复。
 */
class SessionFsmTest {

    @Test
    void createMovesNewToActive() {
        assertEquals(SessionFsm.State.ACTIVE,
                SessionFsm.transition(SessionFsm.State.NEW, SessionFsm.Event.CREATE));
    }

    @Test
    void messageKeepsActiveAndRevivesIdle() {
        assertEquals(SessionFsm.State.ACTIVE,
                SessionFsm.transition(SessionFsm.State.ACTIVE, SessionFsm.Event.MESSAGE));
        assertEquals(SessionFsm.State.ACTIVE,
                SessionFsm.transition(SessionFsm.State.IDLE, SessionFsm.Event.MESSAGE));
    }

    @Test
    void idleThenTimeoutIsReachable() {
        assertEquals(SessionFsm.State.IDLE,
                SessionFsm.transition(SessionFsm.State.ACTIVE, SessionFsm.Event.IDLE_TTL));
        assertEquals(SessionFsm.State.TIMEOUT,
                SessionFsm.transition(SessionFsm.State.IDLE, SessionFsm.Event.TIMEOUT_TTL));
    }

    @Test
    void timeoutCanRecoverByMessage() {
        // 「异常可恢复」：超时会话收到新消息后续期为 ACTIVE
        assertEquals(SessionFsm.State.ACTIVE,
                SessionFsm.transition(SessionFsm.State.TIMEOUT, SessionFsm.Event.MESSAGE));
    }

    @Test
    void closeIsTerminal() {
        assertEquals(SessionFsm.State.CLOSED,
                SessionFsm.transition(SessionFsm.State.ACTIVE, SessionFsm.Event.CLOSE));
        assertTrue(SessionFsm.isTerminal(SessionFsm.State.CLOSED));
        assertFalse(SessionFsm.isTerminal(SessionFsm.State.TIMEOUT));
    }

    @Test
    void closedRejectsAnyEvent() {
        for (SessionFsm.Event event : SessionFsm.Event.values()) {
            assertThrows(SessionFsm.IllegalTransitionException.class,
                    () -> SessionFsm.transition(SessionFsm.State.CLOSED, event));
        }
    }

    @Test
    void illegalTransitionCarriesContext() {
        SessionFsm.IllegalTransitionException ex = assertThrows(SessionFsm.IllegalTransitionException.class,
                () -> SessionFsm.transition(SessionFsm.State.NEW, SessionFsm.Event.MESSAGE));
        assertEquals(SessionFsm.State.NEW, ex.getFrom());
        assertEquals(SessionFsm.Event.MESSAGE, ex.getEvent());
        assertTrue(ex.getMessage().contains("NEW"));
    }

    @Test
    void nullInputIsRejected() {
        assertThrows(SessionFsm.IllegalTransitionException.class,
                () -> SessionFsm.transition(null, SessionFsm.Event.CREATE));
        assertThrows(SessionFsm.IllegalTransitionException.class,
                () -> SessionFsm.transition(SessionFsm.State.NEW, null));
    }

    @Test
    void canAcceptMessageCoversRecoverableStates() {
        assertTrue(SessionFsm.canAcceptMessage(SessionFsm.State.ACTIVE));
        assertTrue(SessionFsm.canAcceptMessage(SessionFsm.State.IDLE));
        assertTrue(SessionFsm.canAcceptMessage(SessionFsm.State.TIMEOUT));
        assertFalse(SessionFsm.canAcceptMessage(SessionFsm.State.CLOSED));
        assertFalse(SessionFsm.canAcceptMessage(SessionFsm.State.NEW));
    }
}
