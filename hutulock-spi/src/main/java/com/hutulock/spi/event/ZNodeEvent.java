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

import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;

/**
 * ZNode 变更事件（内部总线版本）
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ZNodeEvent extends HutuEvent {

    public enum Type { CREATED, DELETED, DATA_CHANGED }

    private final Type      type;
    private final ZNodePath path;
    private final ZNodeType nodeType;
    private final String    sessionId;

    private ZNodeEvent(Builder b) {
        super(b.sourceNodeId);
        this.type      = b.type;
        this.path      = b.path;
        this.nodeType  = b.nodeType;
        this.sessionId = b.sessionId;
    }

    @Override public String getEventType() { return "ZNODE_" + type.name(); }

    public Type      getType()      { return type;      }
    public ZNodePath getPath()      { return path;      }
    public ZNodeType getNodeType()  { return nodeType;  }
    public String    getSessionId() { return sessionId; }

    public static Builder builder(Type type, ZNodePath path, String sourceNodeId) {
        return new Builder(type, path, sourceNodeId);
    }

    public static final class Builder {
        private final Type      type;
        private final ZNodePath path;
        private final String    sourceNodeId;
        private ZNodeType nodeType  = null;
        private String    sessionId = null;

        private Builder(Type type, ZNodePath path, String sourceNodeId) {
            this.type = type; this.path = path; this.sourceNodeId = sourceNodeId;
        }

        public Builder nodeType(ZNodeType t)  { nodeType  = t;   return this; }
        public Builder sessionId(String sid)  { sessionId = sid; return this; }
        public ZNodeEvent build()             { return new ZNodeEvent(this); }
    }
}
