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

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件热加载管理器
 *
 * <p>监听插件目录下的 {@code .jar} 文件变化（CREATE / MODIFY），
 * 检测到更新后：
 * <ol>
 *   <li>关闭旧 {@link HotReloadClassLoader}（释放 jar 文件句柄）</li>
 *   <li>用新 ClassLoader 重新加载 {@link BeanModule} 实现类</li>
 *   <li>调用 {@link #reloadCallback} 通知上层重新注册 Bean</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>{@code
 *   PluginManager pm = PluginManager.builder()
 *       .pluginDir(Paths.get("plugins"))
 *       .moduleClassName("com.example.plugin.MyBeanModule")
 *       .pluginPackagePrefix("com.example.plugin.")
 *       .onReload(module -> {
 *           ctx.install(module);
 *           log.info("Plugin reloaded");
 *       })
 *       .build();
 *   pm.start();
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class PluginManager implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Path                       pluginDir;
    private final String                     moduleClassName;
    private final String                     pluginPackagePrefix;
    private final ReloadCallback             reloadCallback;

    /** 当前活跃的 ClassLoader，热重载时原子替换 */
    private final AtomicReference<HotReloadClassLoader> activeLoader = new AtomicReference<>();

    private WatchService  watchService;
    private ExecutorService watchThread;

    private PluginManager(Builder b) {
        this.pluginDir           = b.pluginDir;
        this.moduleClassName     = b.moduleClassName;
        this.pluginPackagePrefix = b.pluginPackagePrefix;
        this.reloadCallback      = b.reloadCallback;
    }

    // ==================== Lifecycle ====================

    @Override
    public void start() throws Exception {
        // 初次加载
        reload();

        // 启动文件监听
        watchService = FileSystems.getDefault().newWatchService();
        pluginDir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);

        watchThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hutulock-plugin-watcher");
            t.setDaemon(true);
            return t;
        });
        watchThread.submit(this::watchLoop);
        log.info("PluginManager started, watching: {}", pluginDir.toAbsolutePath());
    }

    @Override
    public void shutdown() {
        if (watchThread != null) watchThread.shutdownNow();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException e) {
            log.warn("WatchService close error", e);
        }
        closeLoader(activeLoader.getAndSet(null));
        log.info("PluginManager stopped");
    }

    // ==================== 核心：热重载 ====================

    /**
     * 执行一次热重载：
     * 1. 扫描 pluginDir 下所有 .jar
     * 2. 创建新 HotReloadClassLoader（打破双亲委派）
     * 3. 加载 BeanModule 实现类并实例化
     * 4. 关闭旧 ClassLoader，通知回调
     */
    private void reload() {
        try {
            URL[] jars = Files.list(pluginDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .map(p -> {
                    try { return p.toUri().toURL(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toArray(URL[]::new);

            if (jars.length == 0) {
                log.warn("No plugin jars found in {}", pluginDir);
                return;
            }

            // 新 ClassLoader，父为当前线程的 ContextClassLoader（AppClassLoader）
            HotReloadClassLoader newLoader = new HotReloadClassLoader(
                jars,
                Thread.currentThread().getContextClassLoader(),
                pluginPackagePrefix
            );

            // 加载 BeanModule 实现类（由新 ClassLoader 加载，打破双亲委派）
            Class<?> moduleClass = newLoader.loadClass(moduleClassName);
            BeanModule module = (BeanModule) moduleClass.getDeclaredConstructor().newInstance();

            // 原子替换，关闭旧 ClassLoader
            HotReloadClassLoader old = activeLoader.getAndSet(newLoader);
            closeLoader(old);

            // 通知上层重新注册 Bean
            reloadCallback.onReload(module);
            log.info("Plugin reloaded: class={}, jars={}", moduleClassName, jars.length);

        } catch (Exception e) {
            log.error("Plugin reload failed", e);
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take(); // 阻塞等待事件
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed.toString().endsWith(".jar")) {
                        log.info("Plugin jar changed: {}, triggering reload", changed);
                        // 稍作延迟，等待文件写入完成
                        Thread.sleep(200);
                        reload();
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void closeLoader(HotReloadClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (IOException e) {
            log.warn("Failed to close old ClassLoader", e);
        }
    }

    // ==================== 回调接口 ====================

    @FunctionalInterface
    public interface ReloadCallback {
        void onReload(BeanModule newModule) throws Exception;
    }

    // ==================== Builder ====================

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Path             pluginDir;
        private String           moduleClassName;
        private String           pluginPackagePrefix;
        private ReloadCallback   reloadCallback;

        public Builder pluginDir(Path dir)                    { this.pluginDir = dir;                    return this; }
        public Builder moduleClassName(String cls)            { this.moduleClassName = cls;              return this; }
        public Builder pluginPackagePrefix(String prefix)     { this.pluginPackagePrefix = prefix;       return this; }
        public Builder onReload(ReloadCallback cb)            { this.reloadCallback = cb;                return this; }

        public PluginManager build() {
            if (pluginDir == null)           throw new IllegalStateException("pluginDir required");
            if (moduleClassName == null)     throw new IllegalStateException("moduleClassName required");
            if (pluginPackagePrefix == null) throw new IllegalStateException("pluginPackagePrefix required");
            if (reloadCallback == null)      throw new IllegalStateException("onReload callback required");
            return new PluginManager(this);
        }
    }
}
