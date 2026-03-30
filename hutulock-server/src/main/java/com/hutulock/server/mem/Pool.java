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
package com.hutulock.server.mem;

/**
 * 对象池接口
 *
 * <p>抽象借出/归还语义，使调用方依赖接口而非具体实现，
 * 便于测试替换和未来扩展（如 Netty PooledByteBufAllocator 风格的实现）。
 *
 * @param <T> 池化对象类型，必须实现 {@link ObjectPool.Pooled}
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface Pool<T extends ObjectPool.Pooled> {

    /**
     * 借出一个对象。
     * 优先从本地池/全局池取，池空时新建。
     *
     * @return 非 null 的可用对象
     */
    T borrow();

    /**
     * 归还对象。
     * 实现必须在归还前调用 {@link ObjectPool.Pooled#reset()}。
     *
     * @param obj 待归还的对象
     */
    void release(T obj);

    /**
     * 返回当前对象池的运行时统计快照。
     */
    PoolStats stats();
}
