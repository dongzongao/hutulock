/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.proxy.support;

import com.hutulock.proxy.handler.LoggingHandler;
import com.hutulock.proxy.handler.MetricsHandler;
import com.hutulock.proxy.handler.RetryHandler;

import java.lang.reflect.Proxy;
import java.util.*;

/**
 * 代理构建器（链式 API）
 *
 * <p>支持对任意接口实现叠加多层代理，执行顺序为注册顺序（外层先执行）：
 * <pre>
 *   ProxyBuilder.wrap(ZNodeStorage.class, realImpl)
 *       .withLogging()
 *       .withMetrics()
 *       .build();
 *   // 调用链：LoggingProxy → MetricsProxy → realImpl
 * </pre>
 *
 * @param <T> 目标接口类型
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ProxyBuilder<T> {

    private final Class<T> interfaceType;
    private Object         current;   // 当前最内层实现（逐层包装）

    private ProxyBuilder(Class<T> interfaceType, T delegate) {
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(interfaceType.getName() + " is not an interface");
        }
        this.interfaceType = interfaceType;
        this.current       = delegate;
    }

    /**
     * 入口：指定目标接口和真实实现。
     *
     * @param interfaceType 目标接口（必须是 interface）
     * @param delegate      真实实现
     */
    public static <T> ProxyBuilder<T> wrap(Class<T> interfaceType, T delegate) {
        return new ProxyBuilder<>(interfaceType, delegate);
    }

    /**
     * 添加日志代理层（记录方法名、入参、耗时、异常）。
     */
    public ProxyBuilder<T> withLogging() {
        current = newProxy(new LoggingHandler(current, interfaceType));
        return this;
    }

    /**
     * 添加指标代理层（统计调用次数、失败次数、平均耗时）。
     *
     * @param holder 用于接收 MetricsHandler 引用，以便后续调用 snapshot()
     */
    public ProxyBuilder<T> withMetrics(MetricsHolder<T> holder) {
        MetricsHandler handler = new MetricsHandler(current, interfaceType);
        if (holder != null) holder.handler = handler;
        current = newProxy(handler);
        return this;
    }

    /** 添加指标代理层（不需要 snapshot 时使用）。 */
    public ProxyBuilder<T> withMetrics() {
        return withMetrics(null);
    }

    /**
     * 添加重试代理层。
     *
     * @param maxRetries    最大重试次数
     * @param backoffMs     每次重试前等待时间（ms）
     * @param retryOn       可重试异常类型
     * @param noRetryMethods 不参与重试的方法名（如 release、shutdown）
     */
    @SafeVarargs
    public final ProxyBuilder<T> withRetry(int maxRetries, long backoffMs,
                                            Set<String> noRetryMethods,
                                            Class<? extends Throwable>... retryOn) {
        Set<Class<? extends Throwable>> retrySet = new HashSet<>(Arrays.asList(retryOn));
        if (retrySet.isEmpty()) retrySet.add(RuntimeException.class);
        current = newProxy(new RetryHandler(current, maxRetries, backoffMs, retrySet, noRetryMethods));
        return this;
    }

    /**
     * 构建最终代理实例。
     */
    @SuppressWarnings("unchecked")
    public T build() {
        // 如果 current 已经是代理，直接返回
        if (Proxy.isProxyClass(current.getClass())) {
            return (T) current;
        }
        return interfaceType.cast(current);
    }

    @SuppressWarnings("unchecked")
    private T newProxy(java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[]{ interfaceType },
            handler);
    }

    /**
     * MetricsHandler 引用持有者，用于在构建后访问统计快照。
     */
    public static final class MetricsHolder<T> {
        public MetricsHandler handler;

        public Map<String, String> snapshot() {
            return handler == null ? Collections.emptyMap() : handler.snapshot();
        }
    }
}
