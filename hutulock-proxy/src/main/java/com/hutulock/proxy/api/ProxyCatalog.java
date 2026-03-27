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

import com.hutulock.proxy.api.Proxiable.ProxyType;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.lock.LockService;
import com.hutulock.spi.session.SessionTracker;
import com.hutulock.spi.storage.WatcherRegistry;
import com.hutulock.spi.storage.ZNodeStorage;

import java.util.*;

/**
 * 代理目录 — 声明各 SPI 接口支持的代理类型
 *
 * <p>集中管理"哪些接口可以使用哪种代理"，避免在 SPI 模块引入 proxy 依赖。
 *
 * <pre>
 * ┌─────────────────────┬─────────┬─────────┬───────┐
 * │ SPI 接口             │ Logging │ Metrics │ Retry │
 * ├─────────────────────┼─────────┼─────────┼───────┤
 * │ ZNodeStorage        │   ✓     │   ✓     │       │
 * │ LockService         │   ✓     │   ✓     │   ✓   │
 * │ SessionTracker      │   ✓     │         │       │
 * │ EventBus            │   ✓     │         │       │
 * │ WatcherRegistry     │   ✓     │         │       │
 * └─────────────────────┴─────────┴─────────┴───────┘
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ProxyCatalog {

    private static final Map<Class<?>, EnumSet<ProxyType>> CATALOG = new LinkedHashMap<>();

    static {
        // ZNodeStorage：读写操作频繁，日志 + 指标均有价值
        CATALOG.put(ZNodeStorage.class,
            EnumSet.of(ProxyType.LOGGING, ProxyType.METRICS));

        // LockService：核心业务，全量支持（含重试）
        CATALOG.put(LockService.class,
            EnumSet.of(ProxyType.LOGGING, ProxyType.METRICS, ProxyType.RETRY));

        // SessionTracker：生命周期管理，日志追踪即可
        CATALOG.put(SessionTracker.class,
            EnumSet.of(ProxyType.LOGGING));

        // EventBus：事件流追踪，日志即可
        CATALOG.put(EventBus.class,
            EnumSet.of(ProxyType.LOGGING));

        // WatcherRegistry：注册/触发追踪，日志即可
        CATALOG.put(WatcherRegistry.class,
            EnumSet.of(ProxyType.LOGGING));
    }

    private ProxyCatalog() {}

    /**
     * 查询某接口支持的代理类型集合。
     *
     * @param interfaceType SPI 接口
     * @return 支持的代理类型，若未注册则返回空集合
     */
    public static EnumSet<ProxyType> supportedTypes(Class<?> interfaceType) {
        return CATALOG.getOrDefault(interfaceType, EnumSet.noneOf(ProxyType.class));
    }

    /**
     * 判断某接口是否支持指定代理类型。
     */
    public static boolean supports(Class<?> interfaceType, ProxyType type) {
        return supportedTypes(interfaceType).contains(type);
    }

    /**
     * 返回所有已注册的可代理接口。
     */
    public static Set<Class<?>> proxiableInterfaces() {
        return Collections.unmodifiableSet(CATALOG.keySet());
    }

    /**
     * 打印代理目录（用于启动日志）。
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder("ProxyCatalog:\n");
        CATALOG.forEach((iface, types) ->
            sb.append("  ").append(iface.getSimpleName())
              .append(" → ").append(types).append("\n"));
        return sb.toString();
    }
}
