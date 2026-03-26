/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * Raft 日志（内存实现）
 *
 * <p>日志索引从 1 开始，0 位置为哨兵条目（term=0, command=""）。
 * 生产环境应替换为持久化实现（如 WAL 文件）。
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

    private final List<Entry> entries = new ArrayList<>();

    public RaftLog() {
        entries.add(new Entry(0, 0, "")); // 哨兵
    }

    public synchronized void append(Entry entry)    { entries.add(entry); }

    public synchronized Entry get(int index) {
        if (index <= 0 || index >= entries.size()) return null;
        return entries.get(index);
    }

    public synchronized int lastIndex() { return entries.size() - 1; }

    public synchronized int lastTerm()  { return entries.get(entries.size() - 1).term; }

    public synchronized int termAt(int index) {
        if (index <= 0 || index >= entries.size()) return 0;
        return entries.get(index).term;
    }

    public synchronized void truncateFrom(int index) {
        while (entries.size() > index) entries.remove(entries.size() - 1);
    }

    public synchronized List<Entry> getFrom(int fromIndex) {
        List<Entry> result = new ArrayList<>();
        for (int i = fromIndex; i < entries.size(); i++) result.add(entries.get(i));
        return result;
    }
}
