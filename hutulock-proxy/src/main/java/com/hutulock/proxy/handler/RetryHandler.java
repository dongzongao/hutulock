/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.proxy.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 重试代理处理器
 *
 * <p>当方法抛出指定异常时自动重试，支持：
 * <ul>
 *   <li>最大重试次数</li>
 *   <li>固定退避间隔（ms）</li>
 *   <li>可重试异常类型白名单（默认 RuntimeException）</li>
 *   <li>不可重试方法黑名单（如 release / shutdown）</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RetryHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);

    private final Object              delegate;
    private final int                 maxRetries;
    private final long                backoffMs;
    private final Set<Class<? extends Throwable>> retryOn;
    /** 不参与重试的方法名（幂等性不确定的操作）*/
    private final Set<String>         noRetryMethods;

    public RetryHandler(Object delegate, int maxRetries, long backoffMs,
                        Set<Class<? extends Throwable>> retryOn,
                        Set<String> noRetryMethods) {
        this.delegate       = delegate;
        this.maxRetries     = maxRetries;
        this.backoffMs      = backoffMs;
        this.retryOn        = retryOn;
        this.noRetryMethods = noRetryMethods;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class
                || noRetryMethods.contains(method.getName())) {
            return method.invoke(delegate, args);
        }

        Throwable lastEx = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                lastEx = ite.getCause();
                if (!isRetryable(lastEx)) throw lastEx;
                if (attempt < maxRetries) {
                    log.warn("[retry {}/{}] {} threw {}: {}",
                        attempt + 1, maxRetries, method.getName(),
                        lastEx.getClass().getSimpleName(), lastEx.getMessage());
                    TimeUnit.MILLISECONDS.sleep(backoffMs);
                }
            }
        }
        throw lastEx;
    }

    private boolean isRetryable(Throwable t) {
        return retryOn.stream().anyMatch(c -> c.isInstance(t));
    }
}
