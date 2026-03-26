/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.config.impl;

import com.hutulock.config.api.ClientProperties;
import com.hutulock.config.api.ConfigProvider;
import com.hutulock.config.api.ServerProperties;

/**
 * 代码配置提供者（{@link ConfigProvider} 的代码配置实现）
 *
 * <p>直接通过代码传入配置对象，适用于：
 * <ul>
 *   <li>单元测试（精确控制配置参数）</li>
 *   <li>嵌入式部署（不需要配置文件）</li>
 *   <li>动态配置（运行时构建配置对象）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   ConfigProvider config = new CodeConfigProvider(
 *       ServerProperties.builder()
 *           .watchdogTtl(60_000)
 *           .metricsPort(9091)
 *           .build(),
 *       ClientProperties.defaults()
 *   );
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class CodeConfigProvider implements ConfigProvider {

    private final ServerProperties serverProperties;
    private final ClientProperties clientProperties;

    /**
     * 使用全部默认值创建配置提供者。
     */
    public CodeConfigProvider() {
        this(ServerProperties.defaults(), ClientProperties.defaults());
    }

    /**
     * 使用指定配置对象创建配置提供者。
     *
     * @param serverProperties 服务端配置
     * @param clientProperties 客户端配置
     */
    public CodeConfigProvider(ServerProperties serverProperties, ClientProperties clientProperties) {
        this.serverProperties = serverProperties;
        this.clientProperties = clientProperties;
    }

    @Override
    public ServerProperties getServerProperties() { return serverProperties; }

    @Override
    public ClientProperties getClientProperties() { return clientProperties; }
}
