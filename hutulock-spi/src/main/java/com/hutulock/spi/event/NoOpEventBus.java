/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.event;

/**
 * 无操作 EventBus（Null Object 模式）
 *
 * <p>通过 {@link EventBus#noop()} 获取单例。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
final class NoOpEventBus implements EventBus {

    static final NoOpEventBus INSTANCE = new NoOpEventBus();

    private NoOpEventBus() {}

    @Override public <T extends HutuEvent> void subscribe(Class<T> t, EventListener<T> l) {}
    @Override public <T extends HutuEvent> void unsubscribe(Class<T> t, EventListener<T> l) {}
    @Override public void publish(HutuEvent e)     {}
    @Override public void publishSync(HutuEvent e) {}
    @Override public void shutdown()               {}
}
