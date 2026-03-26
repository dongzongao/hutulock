package com.hutulock.server.event;

import com.hutulock.spi.event.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultEventBus 单元测试
 */
class DefaultEventBusTest {

    private DefaultEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new DefaultEventBus(1);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void subscribeAndReceiveEvent() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<LockEvent> received = new CopyOnWriteArrayList<>();

        eventBus.subscribe(LockEvent.class, event -> {
            received.add(event);
            latch.countDown();
        });

        LockEvent event = LockEvent.builder(LockEvent.Type.ACQUIRED, "order-lock", "session-1")
            .sourceNode("node1").build();
        eventBus.publish(event);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals(LockEvent.Type.ACQUIRED, received.get(0).getType());
    }

    @Test
    void parentClassSubscriptionReceivesAllEvents() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        CopyOnWriteArrayList<HutuEvent> received = new CopyOnWriteArrayList<>();

        eventBus.subscribe(HutuEvent.class, event -> {
            received.add(event);
            latch.countDown();
        });

        eventBus.publish(LockEvent.builder(LockEvent.Type.ACQUIRED, "lock", "s1")
            .sourceNode("n1").build());
        eventBus.publish(SessionEvent.builder(SessionEvent.Type.CREATED, "s1", "c1")
            .sourceNode("n1").build());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, received.size());
    }

    @Test
    void subscriberExceptionDoesNotAffectOthers() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // 第一个订阅者抛出异常
        eventBus.subscribe(LockEvent.class, event -> {
            throw new RuntimeException("intentional error");
        });

        // 第二个订阅者正常工作
        eventBus.subscribe(LockEvent.class, event -> latch.countDown());

        eventBus.publish(LockEvent.builder(LockEvent.Type.ACQUIRED, "lock", "s1")
            .sourceNode("n1").build());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(eventBus.getFailedCount() > 0);
    }

    @Test
    void unsubscribeStopsReceiving() throws InterruptedException {
        CopyOnWriteArrayList<LockEvent> received = new CopyOnWriteArrayList<>();
        EventListener<LockEvent> listener = received::add;

        eventBus.subscribe(LockEvent.class, listener);
        eventBus.unsubscribe(LockEvent.class, listener);

        eventBus.publishSync(LockEvent.builder(LockEvent.Type.ACQUIRED, "lock", "s1")
            .sourceNode("n1").build());

        Thread.sleep(100);
        assertEquals(0, received.size());
    }

    @Test
    void publishSyncIsImmediate() {
        CopyOnWriteArrayList<LockEvent> received = new CopyOnWriteArrayList<>();
        eventBus.subscribe(LockEvent.class, received::add);

        eventBus.publishSync(LockEvent.builder(LockEvent.Type.RELEASED, "lock", "s1")
            .sourceNode("n1").build());

        // publishSync 是同步的，不需要等待
        assertEquals(1, received.size());
    }

    @Test
    void statsTracking() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        eventBus.subscribe(LockEvent.class, e -> latch.countDown());

        for (int i = 0; i < 3; i++) {
            eventBus.publish(LockEvent.builder(LockEvent.Type.ACQUIRED, "lock", "s" + i)
                .sourceNode("n1").build());
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(3, eventBus.getPublishedCount());
        assertEquals(3, eventBus.getDispatchedCount());
    }
}
