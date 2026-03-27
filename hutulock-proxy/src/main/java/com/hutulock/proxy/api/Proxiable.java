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
package com.hutulock.proxy.api;

import java.lang.annotation.*;

/**
 * 标记注解：声明某个 SPI 接口支持代理增强。
 *
 * <p>被标记的接口可通过 {@link com.hutulock.proxy.support.ProxyBuilder}
 * 链式组合 {@code LoggingProxy}、{@code MetricsProxy}、{@code RetryProxy}。
 *
 * <p>当前支持代理的 SPI 接口：
 * <ul>
 *   <li>{@code ZNodeStorage}  — 日志 + 指标</li>
 *   <li>{@code LockService}   — 日志 + 指标 + 重试</li>
 *   <li>{@code SessionTracker}— 日志</li>
 *   <li>{@code EventBus}      — 日志</li>
 *   <li>{@code WatcherRegistry}— 日志</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Proxiable {

    /** 描述该接口支持的代理类型。 */
    ProxyType[] value() default {ProxyType.LOGGING};

    enum ProxyType {
        /** 方法调用日志（入参、耗时、异常）*/
        LOGGING,
        /** 指标采集（调用次数、耗时分布）*/
        METRICS,
        /** 失败自动重试 */
        RETRY
    }
}
