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
package com.hutulock.spi.event;

/**
 * HutuLock 内部事件基类
 *
 * <p>所有内部事件均继承此类，携带来源节点和时间戳。
 * 子类：{@link LockEvent}、{@link SessionEvent}、{@link RaftEvent}、{@link ZNodeEvent}
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public abstract class HutuEvent {

    private final String sourceNodeId;
    private final long   timestamp;

    protected HutuEvent(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
        this.timestamp    = System.currentTimeMillis();
    }

    /** 事件类型标识，如 {@code "LOCK_ACQUIRED"}。 */
    public abstract String getEventType();

    public String getSourceNodeId() { return sourceNodeId; }
    public long   getTimestamp()    { return timestamp;    }

    @Override
    public String toString() {
        return String.format("%s{source=%s, ts=%d}", getEventType(), sourceNodeId, timestamp);
    }
}
