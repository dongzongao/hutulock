/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.ioc;

import java.util.function.Supplier;

/**
 * Bean 定义元数据
 *
 * <p>描述一个受容器管理的组件：名称、类型、工厂方法。
 * 所有 Bean 默认为单例（Singleton）。
 *
 * @param <T> Bean 类型
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class BeanDefinition<T> {

    private final String      name;
    private final Class<T>    type;
    private final Supplier<T> factory;

    private BeanDefinition(String name, Class<T> type, Supplier<T> factory) {
        this.name    = name;
        this.type    = type;
        this.factory = factory;
    }

    /**
     * 创建 Bean 定义。
     *
     * @param name    Bean 名称（容器内唯一）
     * @param type    Bean 类型
     * @param factory 工厂方法（由容器在首次获取时调用）
     */
    public static <T> BeanDefinition<T> of(String name, Class<T> type, Supplier<T> factory) {
        return new BeanDefinition<>(name, type, factory);
    }

    public String      getName()    { return name;    }
    public Class<T>    getType()    { return type;    }
    public Supplier<T> getFactory() { return factory; }
}
