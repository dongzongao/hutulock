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
    /** 预计算的序列化字符串，避免热路径上重复拼接 */
    private final String    serialized;
    /** 预计算的带换行序列化字符串，供 Netty writeAndFlush 直接使用 */
    private final String    serializedWithNewline;

    public WatchEvent(Type type, ZNodePath path) {
        this.type       = type;
        this.path       = path;
        this.timestamp  = System.currentTimeMillis();
        // 预计算：serialize() 在 Watcher 触发时被高频调用，结果固定
        this.serialized            = "WATCH_EVENT " + type.name() + " " + path.value();
        this.serializedWithNewline = this.serialized + "\n";
    }

    public Type      getType()      { return type;      }
    public ZNodePath getPath()      { return path;      }
    public long      getTimestamp() { return timestamp; }

    /** 返回预计算的序列化字符串，O(1)，无分配。 */
    public String serialize() {
        return serialized;
    }

    /**
     * 返回带换行符的序列化字符串（用于 Netty writeAndFlush），O(1)，无分配。
     * 避免调用方每次 serialize() + "\n" 产生额外字符串对象。
     */
    public String serializeWithNewline() {
        return serializedWithNewline;
    }

    /**
     * 解析 WatchEvent，使用手动 indexOf 替代 split 正则，避免每次调用编译正则。
     */
    public static WatchEvent parse(String line) {
        // 格式：WATCH_EVENT {type} {path}
        int i1 = line.indexOf(' ');          // "WATCH_EVENT" 后
        int i2 = i1 > 0 ? line.indexOf(' ', i1 + 1) : -1;  // type 后
        if (i1 < 0 || i2 < 0) {
            throw new IllegalArgumentException("Malformed WatchEvent: " + line);
        }
        Type type = Type.valueOf(line.substring(i1 + 1, i2));
        String pathStr = line.substring(i2 + 1).trim();
        
        // 特殊处理 SESSION_EXPIRED：路径可能为空，使用 ROOT 作为占位符
        ZNodePath path;
        if (type == Type.SESSION_EXPIRED && pathStr.isEmpty()) {
            path = ZNodePath.ROOT;
        } else {
            path = ZNodePath.of(pathStr);
        }
        
        return new WatchEvent(type, path);
    }

    @Override
    public String toString() {
        return "WatchEvent{type=" + type + ", path=" + path + "}";
    }
}
