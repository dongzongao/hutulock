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
package com.hutulock.model.session;

import com.hutulock.model.util.StateMachine;

/**
 * 会话状态机
 *
 * <p>合法转换：
 * <pre>
 *   CONNECTING  → CONNECTED
 *   CONNECTED   → RECONNECTING | EXPIRED | CLOSED
 *   RECONNECTING→ CONNECTED | EXPIRED | CLOSED
 * </pre>
 *
 * <p>终态 EXPIRED / CLOSED 不允许再转换。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class SessionStateMachine implements StateMachine<Session.State> {

    public static final SessionStateMachine INSTANCE = new SessionStateMachine();

    private SessionStateMachine() {}

    @Override
    public boolean canTransit(Session.State from, Session.State to) {
        switch (from) {
            case CONNECTING:   return to == Session.State.CONNECTED;
            case CONNECTED:    return to == Session.State.RECONNECTING
                                   || to == Session.State.EXPIRED
                                   || to == Session.State.CLOSED;
            case RECONNECTING: return to == Session.State.CONNECTED
                                   || to == Session.State.EXPIRED
                                   || to == Session.State.CLOSED;
            default:           return false; // EXPIRED / CLOSED 为终态
        }
    }
}
