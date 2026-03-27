/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Raft 日志（内存实现）
 *
 * <p>日志索引从 1 开始，0 位置为哨兵条目（term=0, command=""）。
 * 生产环境应替换为持久化实现（如 WAL 文件）。
 *
 * <p>并发模型：读多写少（多个 Follower 并发读，append/truncate 是写）。
 * 使用 {@link ReentrantReadWriteLock} 替代全局 {@code synchronized}，
 * 允许多个 Follower 并发调用 {@link #getFrom}/{@link #termAt}/{@link #lastIndex}，
 * 写操作（{@link #append}/{@link #truncateFrom}）独占。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class RaftLog {

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

    public RaftLog() {
        entries.add(new Entry(0, 0, "")); // 哨兵
    }

    // ==================== 写操作（独占锁）====================

    public void append(Entry entry) {
        lock.writeLock().lock();
        try { entries.add(entry); }
        finally { lock.writeLock().unlock(); }
    }

    /**
     * 截断 index 及之后的所有条目（含 index）。
     * {@code subList().clear()} 是单次 O(n) 操作，比循环 remove 少一次边界检查。
     */
    public void truncateFrom(int index) {
        lock.writeLock().lock();
        try {
            if (index < entries.size()) {
                entries.subList(index, entries.size()).clear();
            }
        } finally { lock.writeLock().unlock(); }
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
     * 持读锁期间完成拷贝，调用方可在锁外安全持有引用。
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
}
