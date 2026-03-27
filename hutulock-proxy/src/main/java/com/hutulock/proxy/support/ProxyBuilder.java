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
package com.hutulock.proxy.support;

import com.hutulock.proxy.handler.BackoffStrategy;
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
     * 添加重试代理层（自定义退避策略）。
     *
     * @param maxRetries     最大重试次数
     * @param backoff        退避策略（{@link BackoffStrategy#fixed}、{@link BackoffStrategy#exponential}、{@link BackoffStrategy#jitter}）
     * @param noRetryMethods 不参与重试的方法名（如 release、shutdown）
     * @param retryOn        可重试异常类型
     */
    @SafeVarargs
    public final ProxyBuilder<T> withRetry(int maxRetries, BackoffStrategy backoff,
                                            Set<String> noRetryMethods,
                                            Class<? extends Throwable>... retryOn) {
        Set<Class<? extends Throwable>> retrySet = new HashSet<>(Arrays.asList(retryOn));
        if (retrySet.isEmpty()) retrySet.add(RuntimeException.class);
        current = newProxy(new RetryHandler(current, maxRetries, backoff, retrySet, noRetryMethods));
        return this;
    }

    /**
     * 添加重试代理层（固定间隔退避，兼容旧 API）。
     *
     * @param maxRetries     最大重试次数
     * @param backoffMs      固定等待时间（ms）
     * @param noRetryMethods 不参与重试的方法名
     * @param retryOn        可重试异常类型
     */
    @SafeVarargs
    public final ProxyBuilder<T> withRetry(int maxRetries, long backoffMs,
                                            Set<String> noRetryMethods,
                                            Class<? extends Throwable>... retryOn) {
        return withRetry(maxRetries, BackoffStrategy.fixed(backoffMs), noRetryMethods, retryOn);
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
