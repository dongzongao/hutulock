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
 *   <li>可插拔退避策略（{@link BackoffStrategy}：固定/指数/抖动）</li>
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
    private final BackoffStrategy     backoff;
    private final Set<Class<? extends Throwable>> retryOn;
    /** 不参与重试的方法名（幂等性不确定的操作）*/
    private final Set<String>         noRetryMethods;

    public RetryHandler(Object delegate, int maxRetries, BackoffStrategy backoff,
                        Set<Class<? extends Throwable>> retryOn,
                        Set<String> noRetryMethods) {
        this.delegate       = delegate;
        this.maxRetries     = maxRetries;
        this.backoff        = backoff;
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
                    long waitMs = backoff.delayMs(attempt + 1);
                    log.warn("[retry {}/{}] {} threw {}: {}, waiting {}ms",
                        attempt + 1, maxRetries, method.getName(),
                        lastEx.getClass().getSimpleName(), lastEx.getMessage(), waitMs);
                    if (waitMs > 0) TimeUnit.MILLISECONDS.sleep(waitMs);
                }
            }
        }
        throw lastEx;
    }

    private boolean isRetryable(Throwable t) {
        return retryOn.stream().anyMatch(c -> c.isInstance(t));
    }
}
