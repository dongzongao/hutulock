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
import java.util.Arrays;

/**
 * 日志代理处理器
 *
 * <p>在每次方法调用前后记录：方法名、入参、耗时（ms）、返回值或异常。
 * 日志级别：正常调用 DEBUG，异常 WARN。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class LoggingHandler implements InvocationHandler {

    private final Object  delegate;
    private final Logger  log;

    public LoggingHandler(Object delegate, Class<?> interfaceType) {
        this.delegate = delegate;
        this.log      = LoggerFactory.getLogger("hutulock.proxy." + interfaceType.getSimpleName());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 跳过 Object 方法（toString / hashCode / equals）
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(delegate, args);
        }

        long start = System.currentTimeMillis();
        try {
            Object result = method.invoke(delegate, args);
            if (log.isDebugEnabled()) {
                log.debug("[{}] args={} → {} ({}ms)",
                    method.getName(),
                    args == null ? "[]" : Arrays.toString(args),
                    result,
                    System.currentTimeMillis() - start);
            }
            return result;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            log.warn("[{}] args={} threw {} ({}ms): {}",
                method.getName(),
                args == null ? "[]" : Arrays.toString(args),
                cause.getClass().getSimpleName(),
                System.currentTimeMillis() - start,
                cause.getMessage());
            throw cause;
        }
    }
}
