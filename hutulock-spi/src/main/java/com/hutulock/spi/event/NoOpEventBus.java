/*
 * Copyright 2026 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
