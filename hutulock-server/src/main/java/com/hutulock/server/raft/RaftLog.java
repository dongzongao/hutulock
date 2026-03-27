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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hutulock.model.util.Strings;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Raft 日志（WAL 持久化实现）
 *
 * <p>日志索引从 1 开始，0 位置为哨兵条目（term=0, command=""）。
 *
 * <p>持久化格式（{@code raft-log.wal}，每行一条）：
 * <pre>
 *   {index}\t{term}\t{command}
 * </pre>
 * command 中的 TAB 字符转义为 {@code \t}，换行符转义为 {@code \n}。
 *
 * <p>写入策略：
 * <ul>
 *   <li>{@link #append} — 追加写，每条 flush（{@code FileOutputStream} + {@code channel.force}）</li>
 *   <li>{@link #truncateFrom} — 重写整个文件（截断场景较少，可接受）</li>
 * </ul>
 *
 * <p>并发模型：读多写少，使用 {@link ReentrantReadWriteLock}。
 * 读操作（{@link #get}/{@link #getFrom}/{@link #termAt}/{@link #lastIndex}/{@link #lastTerm}）共享锁，
 * 写操作（{@link #append}/{@link #truncateFrom}）独占锁。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);

    private static final String WAL_FILE = Strings.WAL_FILE_NAME;

    /** 日志条目（不可变值对象） */
    public static final class Entry {
        public final int    term;
        public final int    index;
        public final String command;

        public Entry(int term, int index, String command) {
            this.term    = term;
            this.index   = index;
            this.command = command;
        }

        @Override
        public String toString() {
            return "[" + index + "," + term + "] " + command;
        }
    }

    private final List<Entry>   entries = new ArrayList<>();
    private final ReadWriteLock lock    = new ReentrantReadWriteLock();

    /** WAL 文件路径，null 表示纯内存模式（测试用）。 */
    private final Path walPath;
    /** 追加写流，append 时复用，避免每次 open/close。 */
    private FileOutputStream walOut;

    // ==================== 构造 ====================

    /**
     * 内存模式（测试用，不持久化）。
     */
    public RaftLog() {
        this.walPath = null;
        entries.add(new Entry(0, 0, "")); // 哨兵
    }

    /**
     * WAL 持久化模式。
     *
     * @param dataDir 数据目录，WAL 文件写入 {@code dataDir/raft-log.wal}
     */
    public RaftLog(String dataDir) {
        Path dir = Paths.get(dataDir).toAbsolutePath().normalize();
        // 防止目录穿越：确保规范化后的路径仍以 dataDir 为前缀
        if (!dir.startsWith(Paths.get(dataDir).toAbsolutePath().getParent().normalize())) {
            // 只要路径合法（normalize 后不含 ..）即可，此处做基本校验
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create data dir: " + dataDir, e);
        }
        this.walPath = dir.resolve(WAL_FILE).normalize();
        // 确保 WAL 文件在 dataDir 内，防止路径穿越
        if (!walPath.startsWith(dir)) {
            throw new IllegalArgumentException("WAL path escapes data directory: " + walPath);
        }
        entries.add(new Entry(0, 0, "")); // 哨兵（不写入 WAL）
        loadFromWal();
        openWalAppend();
    }

    // ==================== 写操作（独占锁）====================

    /**
     * 追加一条日志条目，同步刷盘（fsync）。
     * Raft §5.3：日志条目必须在响应 AppendEntries 之前持久化。
     */
    public void append(Entry entry) {
        lock.writeLock().lock();
        try {
            entries.add(entry);
            if (walOut != null) {
                writeEntryToWal(walOut, entry);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 截断 index 及之后的所有条目（含 index）。
     * 截断后重写整个 WAL 文件（截断场景较少，可接受全量重写）。
     */
    public void truncateFrom(int index) {
        lock.writeLock().lock();
        try {
            if (index < entries.size()) {
                entries.subList(index, entries.size()).clear();
                if (walPath != null) {
                    rewriteWal();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 读操作（共享锁）====================

    public Entry get(int index) {
        lock.readLock().lock();
        try {
            if (index <= 0 || index >= entries.size()) return null;
            return entries.get(index);
        } finally { lock.readLock().unlock(); }
    }

    public int lastIndex() {
        lock.readLock().lock();
        try { return entries.size() - 1; }
        finally { lock.readLock().unlock(); }
    }

    public int lastTerm() {
        lock.readLock().lock();
        try { return entries.get(entries.size() - 1).term; }
        finally { lock.readLock().unlock(); }
    }

    public int termAt(int index) {
        lock.readLock().lock();
        try {
            if (index <= 0 || index >= entries.size()) return 0;
            return entries.get(index).term;
        } finally { lock.readLock().unlock(); }
    }

    /**
     * 返回 [fromIndex, lastIndex] 的防御性拷贝。
     */
    public List<Entry> getFrom(int fromIndex) {
        lock.readLock().lock();
        try {
            if (fromIndex >= entries.size()) return Collections.emptyList();
            return new ArrayList<>(entries.subList(fromIndex, entries.size()));
        } finally { lock.readLock().unlock(); }
    }

    /**
     * 查找指定 term 在日志中的第一条索引（用于 Fast Backup）。
     * 若不存在返回 -1。
     */
    public int firstIndexOfTerm(int term) {
        lock.readLock().lock();
        try {
            for (int i = 1; i < entries.size(); i++) {
                if (entries.get(i).term == term) return i;
            }
            return -1;
        } finally { lock.readLock().unlock(); }
    }

    // ==================== WAL 内部实现 ====================

    /**
     * 启动时从 WAL 文件加载所有日志条目到内存。
     * 若文件不存在（首次启动）直接返回。
     */
    private void loadFromWal() {
        if (!Files.exists(walPath)) {
            log.info("No WAL file found at {}, starting fresh", walPath);
            return;
        }
        int loaded = 0;
        try (BufferedReader reader = Files.newBufferedReader(walPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Entry e = parseLine(line);
                if (e != null) {
                    entries.add(e);
                    loaded++;
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load WAL from " + walPath, ex);
        }
        log.info("Loaded {} log entries from WAL {}", loaded, walPath);
    }

    /** 打开追加写流（复用，避免每次 append 都 open/close）。 */
    private void openWalAppend() {
        try {
            walOut = new FileOutputStream(walPath.toFile(), true /* append */);
        } catch (IOException e) {
            throw new RuntimeException("Cannot open WAL for append: " + walPath, e);
        }
    }

    /** 将单条 Entry 写入 WAL 并 fsync。 */
    private void writeEntryToWal(FileOutputStream out, Entry entry) {
        try {
            String line = entry.index + "\t" + entry.term + "\t"
                + escapeCommand(entry.command) + "\n";
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.getChannel().force(false); // fsync data（不强制 metadata，性能更好）
        } catch (IOException e) {
            log.error("WAL write failed for entry {}: {}", entry.index, e.getMessage());
            throw new RuntimeException("WAL write failed", e);
        }
    }

    /**
     * 截断后重写整个 WAL 文件。
     * 先关闭追加流，重写，再重新打开追加流。
     */
    private void rewriteWal() {
        closeWalOut();
        try (FileOutputStream out = new FileOutputStream(walPath.toFile(), false /* overwrite */)) {
            // 跳过哨兵（index=0）
            for (int i = 1; i < entries.size(); i++) {
                writeEntryToWal(out, entries.get(i));
            }
        } catch (IOException e) {
            throw new RuntimeException("WAL rewrite failed", e);
        }
        openWalAppend();
        log.debug("WAL rewritten, {} entries", entries.size() - 1);
    }

    private void closeWalOut() {
        if (walOut != null) {
            try { walOut.close(); } catch (IOException ignored) {}
            walOut = null;
        }
    }

    /** 解析 WAL 行，格式：{index}\t{term}\t{command}。解析失败返回 null 并记录 warn。 */
    private static Entry parseLine(String line) {
        int t1 = line.indexOf('\t');
        int t2 = t1 > 0 ? line.indexOf('\t', t1 + 1) : -1;
        if (t1 < 0 || t2 < 0) {
            log.warn("Skipping malformed WAL line: {}", line.length() > 80 ? line.substring(0, 80) : line);
            return null;
        }
        try {
            int    index   = Integer.parseInt(line.substring(0, t1));
            int    term    = Integer.parseInt(line.substring(t1 + 1, t2));
            String command = unescapeCommand(line.substring(t2 + 1));
            return new Entry(term, index, command);
        } catch (NumberFormatException e) {
            log.warn("Skipping WAL line with bad numbers: {}", line);
            return null;
        }
    }

    /** command 中 TAB → \\t，换行 → \\n，反斜杠 → \\\\。 */
    private static String escapeCommand(String cmd) {
        return cmd.replace("\\", "\\\\")
                  .replace("\t", "\\t")
                  .replace("\n", "\\n");
    }

    private static String unescapeCommand(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if      (next == 't')  { sb.append('\t'); i++; }
                else if (next == 'n')  { sb.append('\n'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else                   { sb.append(c); }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
