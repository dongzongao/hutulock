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
package com.hutulock.server.raft;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RaftLog WAL 持久化单元测试
 *
 * 覆盖：
 *   - 内存模式基本操作
 *   - WAL 写入后重启恢复
 *   - truncateFrom 后重启恢复
 *   - command 特殊字符转义/反转义（TAB、换行、反斜杠）
 *   - WAL 损坏行跳过（容错）
 *   - index 不连续时截断（数据完整性）
 *   - 空 WAL 文件（首次启动）
 *   - Lifecycle start/shutdown 正常关闭流
 */
class RaftLogWalTest {

    @TempDir
    Path tempDir;

    // ==================== 内存模式 ====================

    @Test
    void memoryMode_appendAndGet() {
        RaftLog log = new RaftLog();
        log.append(new RaftLog.Entry(1, 1, "CMD:A"));
        log.append(new RaftLog.Entry(1, 2, "CMD:B"));

        assertEquals(2, log.lastIndex());
        assertEquals(1, log.lastTerm());
        assertEquals("CMD:A", log.get(1).command);
        assertEquals("CMD:B", log.get(2).command);
    }

    @Test
    void memoryMode_truncateFrom() {
        RaftLog log = new RaftLog();
        log.append(new RaftLog.Entry(1, 1, "A"));
        log.append(new RaftLog.Entry(1, 2, "B"));
        log.append(new RaftLog.Entry(2, 3, "C"));

        log.truncateFrom(2);

        assertEquals(1, log.lastIndex());
        assertNull(log.get(2));
        assertNull(log.get(3));
    }

    @Test
    void memoryMode_getFrom() {
        RaftLog log = new RaftLog();
        log.append(new RaftLog.Entry(1, 1, "A"));
        log.append(new RaftLog.Entry(1, 2, "B"));
        log.append(new RaftLog.Entry(2, 3, "C"));

        List<RaftLog.Entry> entries = log.getFrom(2);
        assertEquals(2, entries.size());
        assertEquals(2, entries.get(0).index);
        assertEquals(3, entries.get(1).index);
    }

    @Test
    void memoryMode_termAt_outOfBounds_returnsZero() {
        RaftLog log = new RaftLog();
        assertEquals(0, log.termAt(0));
        assertEquals(0, log.termAt(999));
    }

    @Test
    void memoryMode_firstIndexOfTerm() {
        RaftLog log = new RaftLog();
        log.append(new RaftLog.Entry(1, 1, "A"));
        log.append(new RaftLog.Entry(1, 2, "B"));
        log.append(new RaftLog.Entry(2, 3, "C"));

        assertEquals(1, log.firstIndexOfTerm(1));
        assertEquals(3, log.firstIndexOfTerm(2));
        assertEquals(-1, log.firstIndexOfTerm(99));
    }

    // ==================== WAL 持久化：写入后重启恢复 ====================

    @Test
    void wal_appendAndReload() {
        String dir = tempDir.toString();

        // 写入 3 条
        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, "SET:x=1"));
        log1.append(new RaftLog.Entry(1, 2, "SET:y=2"));
        log1.append(new RaftLog.Entry(2, 3, "DEL:x"));
        log1.shutdown();

        // 重启恢复
        RaftLog log2 = new RaftLog(dir);
        assertEquals(3, log2.lastIndex());
        assertEquals(2, log2.lastTerm());
        assertEquals("SET:x=1", log2.get(1).command);
        assertEquals("SET:y=2", log2.get(2).command);
        assertEquals("DEL:x",   log2.get(3).command);
        assertEquals(1, log2.get(1).term);
        assertEquals(2, log2.get(3).term);
        log2.shutdown();
    }

    @Test
    void wal_appendAfterReload_continuousIndex() {
        String dir = tempDir.toString();

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, "A"));
        log1.append(new RaftLog.Entry(1, 2, "B"));
        log1.shutdown();

        // 重启后继续追加
        RaftLog log2 = new RaftLog(dir);
        log2.append(new RaftLog.Entry(2, 3, "C"));
        log2.shutdown();

        // 再次重启验证
        RaftLog log3 = new RaftLog(dir);
        assertEquals(3, log3.lastIndex());
        assertEquals("C", log3.get(3).command);
        log3.shutdown();
    }

    // ==================== WAL 持久化：truncate 后重启恢复 ====================

    @Test
    void wal_truncateAndReload() {
        String dir = tempDir.toString();

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, "A"));
        log1.append(new RaftLog.Entry(1, 2, "B"));
        log1.append(new RaftLog.Entry(1, 3, "C"));
        log1.truncateFrom(2); // 截断 index 2 及之后
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(1, log2.lastIndex(), "截断后只剩 index=1");
        assertEquals("A", log2.get(1).command);
        assertNull(log2.get(2));
        log2.shutdown();
    }

    @Test
    void wal_truncateAndAppendNewEntries() {
        String dir = tempDir.toString();

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, "OLD:A"));
        log1.append(new RaftLog.Entry(1, 2, "OLD:B"));
        log1.append(new RaftLog.Entry(1, 3, "OLD:C"));
        log1.truncateFrom(2);
        log1.append(new RaftLog.Entry(2, 2, "NEW:B")); // 新 term 的 index=2
        log1.append(new RaftLog.Entry(2, 3, "NEW:C"));
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(3, log2.lastIndex());
        assertEquals("NEW:B", log2.get(2).command);
        assertEquals("NEW:C", log2.get(3).command);
        assertEquals(2, log2.get(2).term);
        log2.shutdown();
    }

    // ==================== 特殊字符转义 ====================

    @Test
    void wal_commandWithTab_escapedAndRestored() {
        String dir = tempDir.toString();
        String cmdWithTab = "SET:key\tvalue";

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, cmdWithTab));
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(cmdWithTab, log2.get(1).command, "TAB 字符应正确转义/反转义");
        log2.shutdown();
    }

    @Test
    void wal_commandWithNewline_escapedAndRestored() {
        String dir = tempDir.toString();
        String cmdWithNewline = "SET:key\nvalue";

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, cmdWithNewline));
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(cmdWithNewline, log2.get(1).command, "换行符应正确转义/反转义");
        log2.shutdown();
    }

    @Test
    void wal_commandWithBackslash_escapedAndRestored() {
        String dir = tempDir.toString();
        String cmdWithBackslash = "SET:path\\value\\end";

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, cmdWithBackslash));
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(cmdWithBackslash, log2.get(1).command, "反斜杠应正确转义/反转义");
        log2.shutdown();
    }

    @Test
    void wal_commandWithAllSpecialChars() {
        String dir = tempDir.toString();
        String complex = "CMD\t/path\\to\nvalue\t\n\\end";

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, complex));
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(complex, log2.get(1).command, "混合特殊字符应完整保留");
        log2.shutdown();
    }

    // ==================== WAL 容错：损坏行跳过 ====================

    @Test
    void wal_corruptedLine_skippedGracefully() throws IOException {
        String dir = tempDir.toString();

        // 先写入 2 条正常日志
        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, "A"));
        log1.append(new RaftLog.Entry(1, 2, "B"));
        log1.shutdown();

        // 手动在 WAL 末尾追加一行损坏数据（缺少 TAB 分隔符）
        Path walFile = tempDir.resolve("raft-log.wal");
        String corrupt = "CORRUPTED_LINE_NO_TABS\n";
        Files.write(walFile, corrupt.getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.APPEND);

        // 重启：损坏行应被跳过，不影响已有数据
        // 注意：损坏行的 index 不连续（期望 3，但解析失败），会触发截断
        RaftLog log2 = new RaftLog(dir);
        // 已有 2 条正常数据应完整加载
        assertEquals(2, log2.lastIndex());
        assertEquals("A", log2.get(1).command);
        assertEquals("B", log2.get(2).command);
        log2.shutdown();
    }

    @Test
    void wal_indexDiscontinuity_truncatesAtGap() throws IOException {
        String dir = tempDir.toString();

        // 手动构造一个 index 跳跃的 WAL（1, 2, 4 — 缺少 3）
        Path walFile = tempDir.resolve("raft-log.wal");
        String content = "1\t1\tA\n2\t1\tB\n4\t1\tD\n"; // index 跳跃
        Files.write(walFile, content.getBytes(StandardCharsets.UTF_8));

        RaftLog log = new RaftLog(dir);
        // 应在 index=4 处截断，只加载 1 和 2
        assertEquals(2, log.lastIndex(), "index 不连续时应截断");
        assertEquals("A", log.get(1).command);
        assertEquals("B", log.get(2).command);
        assertNull(log.get(4));
        log.shutdown();
    }

    // ==================== 边界情况 ====================

    @Test
    void wal_emptyFile_startsFromScratch() {
        String dir = tempDir.toString();
        RaftLog log = new RaftLog(dir);
        assertEquals(0, log.lastIndex(), "空目录应从 index=0 开始");
        log.shutdown();
    }

    @Test
    void wal_singleEntry_reloadCorrect() {
        String dir = tempDir.toString();

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(3, 1, "ONLY"));
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(1, log2.lastIndex());
        assertEquals(3, log2.get(1).term);
        assertEquals("ONLY", log2.get(1).command);
        log2.shutdown();
    }

    @Test
    void wal_truncateAll_reloadEmpty() {
        String dir = tempDir.toString();

        RaftLog log1 = new RaftLog(dir);
        log1.append(new RaftLog.Entry(1, 1, "A"));
        log1.append(new RaftLog.Entry(1, 2, "B"));
        log1.truncateFrom(1); // 截断所有
        log1.shutdown();

        RaftLog log2 = new RaftLog(dir);
        assertEquals(0, log2.lastIndex(), "全部截断后重启应为空");
        log2.shutdown();
    }

    @Test
    void wal_getFrom_emptyRange_returnsEmptyList() {
        RaftLog log = new RaftLog();
        log.append(new RaftLog.Entry(1, 1, "A"));
        List<RaftLog.Entry> result = log.getFrom(999);
        assertTrue(result.isEmpty());
    }

    // ==================== Lifecycle ====================

    @Test
    void lifecycle_shutdownIdempotent() {
        String dir = tempDir.toString();
        RaftLog log = new RaftLog(dir);
        log.append(new RaftLog.Entry(1, 1, "A"));
        // 多次 shutdown 不应抛异常
        assertDoesNotThrow(() -> {
            log.shutdown();
            log.shutdown();
        });
    }

    @Test
    void lifecycle_startDoesNotThrow() {
        String dir = tempDir.toString();
        RaftLog log = new RaftLog(dir);
        assertDoesNotThrow(log::start);
        log.shutdown();
    }
}
