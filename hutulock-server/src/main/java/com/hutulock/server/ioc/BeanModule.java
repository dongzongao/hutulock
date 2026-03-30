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
package com.hutulock.server.ioc;

/**
 * Bean 模块接口
 *
 * <p>将相关 Bean 的注册逻辑封装为一个独立模块，通过
 * {@link ApplicationContext#install(BeanModule)} 批量注册。
 *
 * <p>每个模块只负责自己所在层的 Bean，跨层依赖通过
 * {@link ApplicationContext#getBean} 延迟获取，保持模块间解耦。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface BeanModule {

    /**
     * 向容器注册本模块的所有 Bean。
     *
     * @param ctx IoC 容器
     */
    void register(ApplicationContext ctx);
}
