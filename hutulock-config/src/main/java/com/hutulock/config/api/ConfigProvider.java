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
package com.hutulock.config.api;

/**
 * 配置提供者接口（边界契约）
 *
 * <p>抽象配置的读取方式，支持多种配置源：
 * <ul>
 *   <li>{@link com.hutulock.config.impl.YamlConfigProvider} — YAML 文件</li>
 *   <li>{@link com.hutulock.config.impl.PropertiesConfigProvider} — Properties 文件</li>
 *   <li>{@link com.hutulock.config.impl.CodeConfigProvider} — 代码直接配置（Builder）</li>
 * </ul>
 *
 * <p>配置优先级（高 → 低）：
 * <pre>
 *   系统属性（-D）> 环境变量 > 配置文件 > 默认值
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface ConfigProvider {

    /**
     * 获取服务端配置。
     *
     * @return 服务端配置对象（不可变）
     */
    ServerProperties getServerProperties();

    /**
     * 获取客户端配置。
     *
     * @return 客户端配置对象（不可变）
     */
    ClientProperties getClientProperties();

    /**
     * 重新加载配置（支持热更新的实现可覆盖此方法）。
     * 默认实现为空操作。
     */
    default void reload() {}
}
