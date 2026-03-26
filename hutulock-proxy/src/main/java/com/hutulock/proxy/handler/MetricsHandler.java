/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.proxy.handler;

import com.hutulock.spi.metrics.MetricsCollector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 指标代理处理器
 *
 * <p>统计每个方法的：调用次数、失败次数、累计耗时（ms）。
 * 数据通过 {@link #snapshot()} 获取，也可接入 {@link MetricsCollector} 上报。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class MetricsHandler implements InvocationHandler {

    private final Object delegate;
    private final String prefix;

    /** 方法名 → 调用次数 */
    private final ConcurrentHashMap<String, LongAdder> callCount  = new ConcurrentHashMap<>();
    /** 方法名 → 失败次数 */
    private final ConcurrentHashMap<String, LongAdder> errorCount = new ConcurrentHashMap<>();
    /** 方法名 → 累计耗时 ms */
    private final ConcurrentHashMap<String, LongAdder> totalMs    = new ConcurrentHashMap<>();

    public MetricsHandler(Object delegate, Class<?> interfaceType) {
        this.delegate = delegate;
        this.prefix   = interfaceType.getSimpleName();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(delegate, args);
        }

        String key   = method.getName();
        long   start = System.currentTimeMillis();
        callCount.computeIfAbsent(key, k -> new LongAdder()).increment();

        try {
            Object result = method.invoke(delegate, args);
            totalMs.computeIfAbsent(key, k -> new LongAdder()).add(System.currentTimeMillis() - start);
            return result;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            errorCount.computeIfAbsent(key, k -> new LongAdder()).increment();
            totalMs.computeIfAbsent(key, k -> new LongAdder()).add(System.currentTimeMillis() - start);
            throw ite.getCause();
        }
    }

    /**
     * 返回当前统计快照（方法名 → "calls=N errors=E avgMs=X"）。
     */
    public java.util.Map<String, String> snapshot() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        callCount.forEach((method, calls) -> {
            long c   = calls.sum();
            long e   = errorCount.getOrDefault(method, new LongAdder()).sum();
            long ms  = totalMs.getOrDefault(method, new LongAdder()).sum();
            long avg = c == 0 ? 0 : ms / c;
            result.put(prefix + "." + method,
                String.format("calls=%d errors=%d avgMs=%d", c, e, avg));
        });
        return result;
    }
}
