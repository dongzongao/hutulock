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
package com.hutulock.server.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Raft 持久化元数据存储（currentTerm + votedFor）
 *
 * <p>Raft §5.4 要求 currentTerm 和 votedFor 在响应 RPC 之前必须持久化到稳定存储，
 * 否则节点重启后可能在同一 term 内重复投票，破坏选举安全性。
 *
 * <p>存储格式（{@code raft-meta.properties}）：
 * <pre>
 *   currentTerm=5
 *   votedFor=node2
 * </pre>
 *
 * <p>写入策略：原子替换（写临时文件 → fsync → rename），防止写入中途崩溃导致文件损坏。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class RaftMetaStore {

    private static final Logger log = LoggerFactory.getLogger(RaftMetaStore.class);

    private static final String META_FILE  = "raft-meta.properties";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path metaPath;
    private final Path tmpPath;

    public RaftMetaStore(String dataDir) {
        Path dir = Paths.get(dataDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create data dir: " + dataDir, e);
        }
        this.metaPath = dir.resolve(META_FILE);
        this.tmpPath  = dir.resolve(META_FILE + TMP_SUFFIX);
    }

    /**
     * 持久化 currentTerm 和 votedFor。
     * 使用原子替换：写临时文件 → fsync → rename，防止半写损坏。
     */
    public synchronized void persist(int currentTerm, String votedFor) {
        try {
            String content = "currentTerm=" + currentTerm + "\n"
                + "votedFor=" + (votedFor == null ? "" : votedFor) + "\n";
            Files.write(tmpPath, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
            Files.move(tmpPath, metaPath,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist Raft meta (term={}, votedFor={}): {}",
                currentTerm, votedFor, e.getMessage());
            throw new RuntimeException("Raft meta persist failed", e);
        }
    }

    /**
     * 加载持久化的元数据。若文件不存在（首次启动）返回默认值。
     */
    public Meta load() {
        if (!Files.exists(metaPath)) {
            log.info("No Raft meta file found at {}, starting fresh", metaPath);
            return new Meta(0, null);
        }
        try {
            java.util.Properties props = new java.util.Properties();
            try (InputStream is = Files.newInputStream(metaPath)) {
                props.load(is);
            }
            int    term     = Integer.parseInt(props.getProperty("currentTerm", "0"));
            String votedFor = props.getProperty("votedFor", "");
            String vf       = votedFor.isEmpty() ? null : votedFor;
            log.info("Loaded Raft meta: term={}, votedFor={}", term, vf);
            return new Meta(term, vf);
        } catch (Exception e) {
            log.error("Failed to load Raft meta from {}: {}", metaPath, e.getMessage());
            throw new RuntimeException("Raft meta load failed", e);
        }
    }

    /** 持久化元数据值对象。 */
    public static final class Meta {
        public final int    currentTerm;
        public final String votedFor;

        public Meta(int currentTerm, String votedFor) {
            this.currentTerm = currentTerm;
            this.votedFor    = votedFor;
        }
    }
}
