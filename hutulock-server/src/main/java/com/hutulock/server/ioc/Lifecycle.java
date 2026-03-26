/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.ioc;

/**
 * 生命周期接口
 *
 * <p>容器管理的 Bean 可选择实现此接口，
 * 容器在启动/关闭时会按依赖顺序调用对应方法。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface Lifecycle {

    /** 启动 Bean（容器 start 时按注册顺序调用）。 */
    default void start() throws Exception {}

    /** 关闭 Bean（容器 close 时按注册逆序调用）。 */
    default void shutdown() {}
}
