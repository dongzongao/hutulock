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
package com.hutulock.model.watcher;

import com.hutulock.model.znode.ZNodePath;

/**
 * Watcher 事件（不可变值对象）
 *
 * <p>服务端推送给注册了 Watcher 的客户端。
 *
 * <p>网络传输格式：{@code WATCH_EVENT {type} {path}}
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class WatchEvent {

    public enum Type {
        NODE_CREATED,
        NODE_DELETED,
        NODE_DATA_CHANGED,
        /** 子节点列表变化（增删子节点时触发父节点的 watcher，参考 ZooKeeper NodeChildrenChanged）。 */
        CHILDREN_CHANGED,
        SESSION_EXPIRED
    }

    private final Type      type;
    private final ZNodePath path;
    private final long      timestamp;

    public WatchEvent(Type type, ZNodePath path) {
        this.type      = type;
        this.path      = path;
        this.timestamp = System.currentTimeMillis();
    }

    public Type      getType()      { return type;      }
    public ZNodePath getPath()      { return path;      }
    public long      getTimestamp() { return timestamp; }

    public String serialize() {
        return "WATCH_EVENT " + type.name() + " " + path.value();
    }

    public static WatchEvent parse(String line) {
        String[] parts = line.trim().split("\\s+", 3);
        return new WatchEvent(Type.valueOf(parts[1]), ZNodePath.of(parts[2]));
    }

    @Override
    public String toString() {
        return "WatchEvent{type=" + type + ", path=" + path + "}";
    }
}
