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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 轻量级 IoC 容器
 *
 * <p>核心职责：
 * <ol>
 *   <li>Bean 注册 — 通过 {@link BeanDefinition} 描述组件及其工厂方法</li>
 *   <li>延迟实例化 — 首次 {@link #getBean} 时调用工厂方法，结果缓存为单例</li>
 *   <li>生命周期管理 — {@link #start()} 按注册顺序启动，{@link #close()} 按逆序关闭</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>{@code
 *   ApplicationContext ctx = new ApplicationContext();
 *   ctx.register(BeanDefinition.of("metrics", MetricsCollector.class, () -> new PrometheusMetricsCollector("node1")));
 *   ctx.register(BeanDefinition.of("storage", ZNodeStorage.class,
 *       () -> new DefaultZNodeTree(ctx.getBean(WatcherRegistry.class), ctx.getBean(MetricsCollector.class), EventBus.noop())));
 *   ctx.start();
 *   // 使用
 *   ZNodeStorage storage = ctx.getBean(ZNodeStorage.class);
 *   // 关闭
 *   ctx.close();
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class ApplicationContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ApplicationContext.class);

    /** 按注册顺序保存 Bean 名称（用于生命周期顺序控制）*/
    private final List<String>                     registrationOrder = new ArrayList<>();
    /** name → BeanDefinition */
    private final Map<String, BeanDefinition<?>>   definitions       = new LinkedHashMap<>();
    /** name → 已实例化的单例 */
    private final Map<String, Object>              singletons        = new LinkedHashMap<>();
    /** type → name（用于按类型查找，取最后注册的同类型 Bean）*/
    private final Map<Class<?>, String>            typeIndex         = new LinkedHashMap<>();

    // ==================== 注册 ====================

    /**
     * 注册 Bean 定义。
     * 若同名 Bean 已存在则覆盖（支持测试替换）。
     *
     * @param definition Bean 定义
     * @param <T>        Bean 类型
     * @return this（链式调用）
     */
    public <T> ApplicationContext register(BeanDefinition<T> definition) {
        String name = definition.getName();
        if (!definitions.containsKey(name)) {
            registrationOrder.add(name);
        }
        definitions.put(name, definition);
        typeIndex.put(definition.getType(), name);
        log.debug("Registered bean: name={}, type={}", name, definition.getType().getSimpleName());
        return this;
    }

    // ==================== 获取 ====================

    /**
     * 按名称获取 Bean（延迟实例化）。
     *
     * @param name Bean 名称
     * @param <T>  Bean 类型
     * @return Bean 实例
     * @throws IllegalArgumentException 若 Bean 未注册
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        if (singletons.containsKey(name)) {
            return (T) singletons.get(name);
        }
        BeanDefinition<?> def = definitions.get(name);
        if (def == null) {
            throw new IllegalArgumentException("No bean registered with name: " + name);
        }
        T instance = (T) def.getFactory().get();
        singletons.put(name, instance);
        log.debug("Instantiated bean: name={}", name);
        return instance;
    }

    /**
     * 按类型获取 Bean（取最后注册的同类型 Bean）。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型
     * @return Bean 实例
     * @throws IllegalArgumentException 若该类型未注册
     */
    public <T> T getBean(Class<T> type) {
        String name = typeIndex.get(type);
        if (name == null) {
            throw new IllegalArgumentException("No bean registered for type: " + type.getName());
        }
        return getBean(name);
    }

    /**
     * 判断是否存在指定名称的 Bean。
     */
    public boolean containsBean(String name) {
        return definitions.containsKey(name);
    }

    // ==================== 生命周期 ====================

    /**
     * 按注册顺序启动所有实现了 {@link Lifecycle} 的 Bean。
     * 若任意 Bean 启动失败，回滚已启动的 Bean（逆序 shutdown），保证不留下半启动状态。
     *
     * @throws Exception 任意 Bean 启动失败时抛出
     */
    public void start() throws Exception {
        log.info("ApplicationContext starting — {} beans registered", definitions.size());
        List<String> started = new ArrayList<>();
        try {
            for (String name : registrationOrder) {
                Object bean = getBean(name);
                if (bean instanceof Lifecycle) {
                    log.debug("Starting lifecycle bean: {}", name);
                    ((Lifecycle) bean).start();
                    started.add(name);
                }
            }
        } catch (Exception e) {
            log.error("ApplicationContext start failed, rolling back {} started beans", started.size());
            // 逆序关闭已启动的 Bean，避免半启动状态
            List<String> toRollback = new ArrayList<>(started);
            Collections.reverse(toRollback);
            for (String name : toRollback) {
                Object bean = singletons.get(name);
                if (bean instanceof Lifecycle) {
                    try { ((Lifecycle) bean).shutdown(); }
                    catch (Exception ex) { log.warn("Rollback shutdown failed for [{}]: {}", name, ex.getMessage()); }
                }
            }
            throw e;
        }
        log.info("ApplicationContext started");
    }

    /**
     * 按注册逆序关闭所有实现了 {@link Lifecycle} 的 Bean。
     */
    @Override
    public void close() {
        log.info("ApplicationContext closing");
        List<String> reversed = new ArrayList<>(registrationOrder);
        Collections.reverse(reversed);
        for (String name : reversed) {
            Object bean = singletons.get(name);
            if (bean instanceof Lifecycle) {
                try {
                    log.debug("Shutting down lifecycle bean: {}", name);
                    ((Lifecycle) bean).shutdown();
                } catch (Exception e) {
                    log.warn("Error shutting down bean [{}]: {}", name, e.getMessage());
                }
            }
        }
        singletons.clear();
        log.info("ApplicationContext closed");
    }
}
