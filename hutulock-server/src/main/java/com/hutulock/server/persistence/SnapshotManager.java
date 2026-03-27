/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.persistence;

import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.model.znode.ZNodeType;
import com.hutulock.server.impl.DefaultZNodeTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.List;

/**
 * ZNode 树快照管理器
 *
 * <p>快照解决两个问题：
 * <ol>
 *   <li>灾难重启后状态机恢复：无需重放全量 Raft 日志，直接加载快照 + 重放快照后的增量日志</li>
 *   <li>Raft 日志压缩：快照点之前的日志可以安全删除，防止日志无限增长</li>
 * </ol>
 *
 * <p>快照格式（{@code snapshot-{lastApplied}.snap}，每行一条 ZNode）：
 * <pre>
 *   # HEADER lastApplied={n} lastTerm={t}
 *   {path}\t{type}\t{sessionId}\t{seqNum}\t{dataBase64}
 * </pre>
 *
 * <p>写入策略：写临时文件 → fsync → rename（原子替换），防止写入中途崩溃。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    private static final String SNAP_EXT      = ".snap";
    private static final String HEADER_PREFIX = "# HEADER ";

    private final Path dataDir;

    public SnapshotManager(String dataDir) {
        this.dataDir = Paths.get(dataDir);
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create snapshot dir: " + dataDir, e);
        }
    }

    // ==================== 保存快照 ====================

    /**
     * 将 ZNode 树当前状态序列化为快照文件。
     *
     * @param tree        ZNode 树
     * @param lastApplied 快照对应的 Raft lastApplied 索引
     * @param lastTerm    快照对应的 Raft term
     */
    public void save(DefaultZNodeTree tree, int lastApplied, int lastTerm) {
        Path snapPath = dataDir.resolve("snapshot-" + lastApplied + SNAP_EXT);
        Path tmpPath  = dataDir.resolve("snapshot-" + lastApplied + SNAP_EXT + ".tmp");

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(tmpPath.toFile()), StandardCharsets.UTF_8))) {
            writer.write(HEADER_PREFIX + "lastApplied=" + lastApplied + " lastTerm=" + lastTerm);
            writer.newLine();
            writeSubtree(writer, tree, ZNodePath.ROOT);
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to write snapshot to {}: {}", tmpPath, e.getMessage());
            throw new RuntimeException("Snapshot save failed", e);
        }

        try {
            Files.move(tmpPath, snapPath,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Snapshot atomic rename failed", e);
        }

        log.info("Snapshot saved: lastApplied={}, lastTerm={}, path={}", lastApplied, lastTerm, snapPath);
        deleteOldSnapshots(lastApplied);
    }

    private void writeSubtree(BufferedWriter writer, DefaultZNodeTree tree, ZNodePath path) throws IOException {
        ZNode node = tree.get(path);
        if (node != null && !path.isRoot()) {
            String sessionId = node.getSessionId() == null ? "" : node.getSessionId();
            String dataB64   = node.getData() == null || node.getData().length == 0
                ? "" : Base64.getEncoder().encodeToString(node.getData());
            writer.write(path.value() + "\t"
                + node.getType().name() + "\t"
                + sessionId + "\t"
                + node.getSequenceNum() + "\t"
                + dataB64);
            writer.newLine();
        }
        List<ZNodePath> children = tree.getChildren(path);
        for (ZNodePath child : children) {
            writeSubtree(writer, tree, child);
        }
    }

    // ==================== 加载快照 ====================

    /**
     * 加载最新快照并恢复到 ZNode 树。
     *
     * @param tree 目标 ZNode 树（应为空树）
     * @return 快照元数据，若无快照返回 {@link SnapshotMeta#EMPTY}
     */
    public SnapshotMeta load(DefaultZNodeTree tree) {
        Path latest = findLatestSnapshot();
        if (latest == null) {
            log.info("No snapshot found in {}, starting from empty state", dataDir);
            return SnapshotMeta.EMPTY;
        }

        log.info("Loading snapshot from {}", latest);
        SnapshotMeta meta = SnapshotMeta.EMPTY;
        int restored = 0;

        try (BufferedReader reader = Files.newBufferedReader(latest, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith(HEADER_PREFIX)) {
                    meta = parseHeader(line);
                    continue;
                }
                String[] parts = line.split("\t", 5);
                if (parts.length < 5) {
                    log.warn("Skipping malformed snapshot line: {}", line);
                    continue;
                }
                try {
                    ZNodePath path      = ZNodePath.of(parts[0]);
                    ZNodeType type      = ZNodeType.valueOf(parts[1]);
                    String    sessionId = parts[2].isEmpty() ? null : parts[2];
                    byte[]    data      = parts[4].isEmpty() ? new byte[0]
                        : Base64.getDecoder().decode(parts[4]);
                    tree.restoreNode(path, type, data, sessionId);
                    restored++;
                } catch (Exception e) {
                    log.warn("Failed to restore ZNode from line '{}': {}", line, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load snapshot from " + latest, e);
        }

        log.info("Snapshot loaded: lastApplied={}, lastTerm={}, restoredNodes={}",
            meta.lastApplied, meta.lastTerm, restored);
        return meta;
    }

    // ==================== 工具 ====================

    private Path findLatestSnapshot() {
        try {
            return Files.list(dataDir)
                .filter(p -> p.getFileName().toString().endsWith(SNAP_EXT))
                .max((a, b) -> Integer.compare(
                    extractIndex(a.getFileName().toString()),
                    extractIndex(b.getFileName().toString())))
                .orElse(null);
        } catch (IOException e) {
            log.warn("Cannot list snapshot dir {}: {}", dataDir, e.getMessage());
            return null;
        }
    }

    private static int extractIndex(String filename) {
        try {
            return Integer.parseInt(filename.replace("snapshot-", "").replace(SNAP_EXT, ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static SnapshotMeta parseHeader(String line) {
        String body = line.substring(HEADER_PREFIX.length()).trim();
        int lastApplied = 0, lastTerm = 0;
        for (String kv : body.split(" ")) {
            String[] pair = kv.split("=", 2);
            if (pair.length == 2) {
                if ("lastApplied".equals(pair[0])) lastApplied = Integer.parseInt(pair[1]);
                if ("lastTerm".equals(pair[0]))    lastTerm    = Integer.parseInt(pair[1]);
            }
        }
        return new SnapshotMeta(lastApplied, lastTerm);
    }

    private void deleteOldSnapshots(int keepIndex) {
        try {
            Files.list(dataDir)
                .filter(p -> p.getFileName().toString().endsWith(SNAP_EXT))
                .filter(p -> extractIndex(p.getFileName().toString()) < keepIndex)
                .forEach(p -> {
                    try { Files.deleteIfExists(p); log.debug("Deleted old snapshot: {}", p); }
                    catch (IOException e) { log.warn("Cannot delete old snapshot {}: {}", p, e.getMessage()); }
                });
        } catch (IOException e) {
            log.warn("Cannot list snapshots for cleanup: {}", e.getMessage());
        }
    }

    /** 快照元数据。 */
    public static final class SnapshotMeta {
        public static final SnapshotMeta EMPTY = new SnapshotMeta(0, 0);

        public final int lastApplied;
        public final int lastTerm;

        public SnapshotMeta(int lastApplied, int lastTerm) {
            this.lastApplied = lastApplied;
            this.lastTerm    = lastTerm;
        }

        public boolean isEmpty() { return lastApplied == 0; }
    }
}
