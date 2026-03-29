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

/**
 * 代理配置策略（Strategy Pattern）
 *
 * <p>封装"是否启用 logging / metrics 代理"的决策，
 * 替代 {@code ServerBeanFactory} 中散落的多处 {@code if (proxyEnabled("logging"))} 判断。
 *
 * <p>使用示例：
 * <pre>{@code
 *   ProxyConfigurator cfg = ProxyConfigurator.from(System.getProperty("hutulock.proxy", ""));
 *
 *   // 替代：
 *   //   if (proxyEnabled("logging")) builder.withLogging();
 *   //   if (proxyEnabled("metrics")) builder.withMetrics();
 *   //   return (logging || metrics) ? builder.build() : original;
 *   ZNodeStorage storage = cfg.apply(ZNodeStorage.class, realTree);
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ProxyConfigurator {

    private final boolean logging;
    private final boolean metrics;

    private ProxyConfigurator(boolean logging, boolean metrics) {
        this.logging = logging;
        this.metrics = metrics;
    }

    /**
     * 从系统属性字符串解析配置。
     * 支持 "logging"、"metrics"、"all"（等价于两者都开）。
     */
    public static ProxyConfigurator from(String proxyProp) {
        String val = proxyProp == null ? "" : proxyProp.toLowerCase();
        boolean all = val.contains("all");
        return new ProxyConfigurator(all || val.contains("logging"), all || val.contains("metrics"));
    }

    /** 从系统属性 {@code hutulock.proxy} 自动读取。 */
    public static ProxyConfigurator fromSystemProperty() {
        return from(System.getProperty("hutulock.proxy", ""));
    }

    public boolean isLoggingEnabled() { return logging; }
    public boolean isMetricsEnabled() { return metrics; }
    public boolean isAnyEnabled()     { return logging || metrics; }

    /**
     * 对目标对象按配置应用代理，若无代理启用则直接返回原对象。
     *
     * @param interfaceType 目标接口
     * @param target        真实实现
     * @return 代理对象（或原对象）
     */
    public <T> T apply(Class<T> interfaceType, T target) {
        if (!isAnyEnabled()) return target;
        ProxyBuilder<T> builder = ProxyBuilder.wrap(interfaceType, target);
        if (logging) builder.withLogging();
        if (metrics) builder.withMetrics();
        return builder.build();
    }
}
