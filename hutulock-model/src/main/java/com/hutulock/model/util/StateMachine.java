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
package com.hutulock.model.util;

import com.hutulock.model.exception.HutuLockException;
import com.hutulock.model.exception.ErrorCode;

/**
 * 通用状态机接口
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #canTransit(Enum, Enum)} — 判断转换是否合法（子类实现）</li>
 *   <li>{@link #transit(Enum, Enum)} — 执行转换，非法时抛出 {@link HutuLockException}</li>
 * </ul>
 *
 * @param <S> 状态枚举类型
 * @author HutuLock Authors
 * @since 1.0.0
 */
public interface StateMachine<S extends Enum<S>> {

    /**
     * 判断从 {@code from} 到 {@code to} 的转换是否合法。
     */
    boolean canTransit(S from, S to);

    /**
     * 执行状态转换。
     *
     * @throws HutuLockException 若转换非法
     */
    default void transit(S from, S to) {
        if (!canTransit(from, to)) {
            throw new HutuLockException(ErrorCode.INVALID_STATE,
                "Illegal state transition: " + from + " -> " + to);
        }
    }
}
