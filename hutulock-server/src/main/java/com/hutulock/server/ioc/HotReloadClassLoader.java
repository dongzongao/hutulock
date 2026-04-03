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

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 热加载类加载器（打破双亲委派）
 *
 * <p>标准双亲委派：子 → 父 → Bootstrap，父能加载就不让子加载。
 * 热加载需要反过来：<b>子优先</b>，让插件 jar 里的新版本类覆盖旧版本。
 *
 * <p>打破方式：重写 {@link #loadClass}，对插件包前缀的类跳过父委派，
 * 直接调用 {@link #findClass} 从本 ClassLoader 的 URL 里加载。
 * 框架核心类（{@code com.hutulock.spi.*}、JDK 类）仍走父委派，保证接口一致性。
 *
 * <pre>
 * 加载顺序：
 *   插件类（pluginPackagePrefix 匹配）→ 本 ClassLoader 优先
 *   框架 SPI / JDK 类              → 父 ClassLoader（双亲委派）
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HotReloadClassLoader extends URLClassLoader {

    static {
        // 注册为并行可用，提升多线程加载性能
        ClassLoader.registerAsParallelCapable();
    }

    /**
     * 插件类的包前缀，匹配的类由本 ClassLoader 优先加载。
     * 例如 {@code "com.hutulock.plugin."} 或 {@code "com.example.ext."}
     */
    private final String pluginPackagePrefix;

    /**
     * @param urls                插件 jar 的 URL 数组
     * @param parent              父 ClassLoader（通常是 AppClassLoader）
     * @param pluginPackagePrefix 插件类包前缀，该前缀下的类优先由本 ClassLoader 加载
     */
    public HotReloadClassLoader(URL[] urls, ClassLoader parent, String pluginPackagePrefix) {
        super(urls, parent);
        this.pluginPackagePrefix = pluginPackagePrefix;
    }

    /**
     * 重写 loadClass，对插件包前缀的类打破双亲委派。
     *
     * <p>流程：
     * <ol>
     *   <li>查缓存（已加载过直接返回）</li>
     *   <li>插件类 → 本 ClassLoader 的 {@link #findClass} 优先</li>
     *   <li>找不到或非插件类 → 回退到父 ClassLoader</li>
     * </ol>
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. 查缓存
            Class<?> cached = findLoadedClass(name);
            if (cached != null) {
                if (resolve) resolveClass(cached);
                return cached;
            }

            // 2. JDK 核心类必须走父委派（安全边界）
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("sun.") || name.startsWith("jdk.")) {
                return super.loadClass(name, resolve);
            }

            // 3. 插件类：本 ClassLoader 优先（打破双亲委派）
            if (name.startsWith(pluginPackagePrefix)) {
                try {
                    Class<?> clazz = findClass(name);
                    if (resolve) resolveClass(clazz);
                    return clazz;
                } catch (ClassNotFoundException ignored) {
                    // 本 ClassLoader 找不到，回退父委派
                }
            }

            // 4. 其余类（框架 SPI、第三方库）走标准双亲委派
            return super.loadClass(name, resolve);
        }
    }
}
