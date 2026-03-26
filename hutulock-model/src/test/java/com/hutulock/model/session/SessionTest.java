package com.hutulock.model.session;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 状态机单元测试
 */
class SessionTest {

    @Test
    void initialState() {
        Session session = new Session("client-1", 30_000);
        assertEquals(Session.State.CONNECTING, session.getState());
        assertNotNull(session.getSessionId());
        assertEquals(16, session.getSessionId().length());
    }

    @Test
    void heartbeatUpdatesTimestamp() throws InterruptedException {
        Session session = new Session("client-1", 30_000);
        session.transitionTo(Session.State.CONNECTED);
        long before = session.getLastHeartbeat();
        Thread.sleep(10);
        session.heartbeat();
        assertTrue(session.getLastHeartbeat() > before);
    }

    @Test
    void expiredWhenNoHeartbeat() throws InterruptedException {
        Session session = new Session("client-1", 50); // 50ms TTL
        session.transitionTo(Session.State.CONNECTED);
        assertFalse(session.isExpired());
        Thread.sleep(100);
        assertTrue(session.isExpired());
    }

    @Test
    void notExpiredAfterHeartbeat() throws InterruptedException {
        Session session = new Session("client-1", 100);
        session.transitionTo(Session.State.CONNECTED);
        Thread.sleep(60);
        session.heartbeat(); // 续期
        Thread.sleep(60);
        assertFalse(session.isExpired()); // 从最后一次心跳算，还没到 100ms
    }

    @Test
    void isAlive() {
        Session session = new Session("client-1", 30_000);
        assertFalse(session.isAlive()); // CONNECTING

        session.transitionTo(Session.State.CONNECTED);
        assertTrue(session.isAlive());

        session.transitionTo(Session.State.RECONNECTING);
        assertTrue(session.isAlive());

        session.transitionTo(Session.State.EXPIRED);
        assertFalse(session.isAlive());
    }

    @Test
    void heartbeatIgnoredWhenExpired() throws InterruptedException {
        Session session = new Session("client-1", 50);
        session.transitionTo(Session.State.EXPIRED);
        long ts = session.getLastHeartbeat();
        Thread.sleep(10);
        session.heartbeat(); // 应该被忽略
        assertEquals(ts, session.getLastHeartbeat());
    }
}
