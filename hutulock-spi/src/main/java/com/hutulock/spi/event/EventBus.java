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
 * 事件总线接口（SPI 边界契约）
 *
 * <p>基于发布-订阅模式，实现组件间松耦合通信。
 * 与 WatcherRegistry 的区别：EventBus 面向内部组件（持久订阅），
 * WatcherRegistry 面向网络客户端（One-shot）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface EventBus {

    <T extends HutuEvent> void subscribe(Class<T> eventType, EventListener<T> listener);
    <T extends HutuEvent> void unsubscribe(Class<T> eventType, EventListener<T> listener);

    /** 异步发布（立即返回，后台线程分发）。 */
    void publish(HutuEvent event);

    /** 同步发布（阻塞直到所有订阅者处理完成，适用于测试）。 */
    void publishSync(HutuEvent event);

    void shutdown();

    /** 返回无操作实现（Null Object）。 */
    static EventBus noop() { return NoOpEventBus.INSTANCE; }
}
