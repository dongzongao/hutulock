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
package com.hutulock.client;

import com.hutulock.model.util.StateMachine;

/**
 * 客户端锁状态机
 *
 * <p>合法转换：
 * <pre>
 *   IDLE     → HELD
 *   HELD     → EXPIRED | RELEASED
 * </pre>
 *
 * <p>终态 EXPIRED / RELEASED 不允许再转换。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class LockStateMachine implements StateMachine<LockContext.State> {

    public static final LockStateMachine INSTANCE = new LockStateMachine();

    private LockStateMachine() {}

    @Override
    public boolean canTransit(LockContext.State from, LockContext.State to) {
        switch (from) {
            case IDLE: return to == LockContext.State.HELD;
            case HELD: return to == LockContext.State.EXPIRED
                           || to == LockContext.State.RELEASED;
            default:   return false; // EXPIRED / RELEASED 为终态
        }
    }
}
