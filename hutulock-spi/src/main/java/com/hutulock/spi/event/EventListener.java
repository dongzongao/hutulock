/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.spi.event;

/**
 * 事件监听器接口（函数式）
 *
 * @param <T> 监听的事件类型
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface EventListener<T extends HutuEvent> {
    void onEvent(T event);
}
