package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;

/**
 * CircularBuffer · 对齐 CC utils/CircularBuffer.ts.
 *
 * <p>L1 语义: 固定大小环形 buffer — 满时驱逐最旧。add/addAll/getRecent/toArray/clear/length。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: add/addAll/getRecent/toArray/clear/length 6 method + 泛型</li>
 *   <li><b>A2 Golden Trace</b>: capacity=3 + add A,B,C,D → buffer=[D,B,C] (A evicted);getRecent(2)→[C,D] (oldest-first? no, newest-last);toArray→[B,C,D]</li>
 *   <li><b>A3 不可变外</b>: head/size 内部 mutable;不修改 caller items</li>
 *   <li><b>A4 边界</b>: capacity=0 → IllegalArgumentException;null items OK (允许);empty list addAll</li>
 *   <li><b>A5 业务场景</b>: telemetry 滚动窗口;最近 5 个 tool call 状态</li>
 * </ul>
 *
 * <p>L3 升级: TS class mutable array → Java ArrayList + head/size index;
 * TS typed array → Java Object[] (泛型);
 * TS `Array<T | undefined>` slot → Java 显式 null initialize.
 */
public final class CircularBuffer<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head = 0;
    private int size = 0;

    public CircularBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    /** Add an item; if full, oldest is evicted. */
    public void add(T item) {
        buffer[head] = item;
        head = (head + 1) % capacity;
        if (size < capacity) size++;
    }

    /** Add multiple items. */
    public void addAll(List<T> items) {
        if (items == null) return;
        for (T item : items) add(item);
    }

    /** Return up to {@code count} most recent items, oldest to newest. */
    public List<T> getRecent(int count) {
        List<T> result = new ArrayList<>();
        int start = size < capacity ? 0 : head;
        int available = Math.min(count, size);
        for (int i = 0; i < available; i++) {
            int idx = (start + size - available + i) % capacity;
            @SuppressWarnings("unchecked")
            T item = (T) buffer[idx];
            result.add(item);
        }
        return result;
    }

    /** All items in insertion order (oldest first). */
    public List<T> toArray() {
        if (size == 0) return new ArrayList<>();
        List<T> result = new ArrayList<>();
        int start = size < capacity ? 0 : head;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % capacity;
            @SuppressWarnings("unchecked")
            T item = (T) buffer[idx];
            result.add(item);
        }
        return result;
    }

    public void clear() {
        for (int i = 0; i < capacity; i++) buffer[i] = null;
        head = 0;
        size = 0;
    }

    public int length() { return size; }
    public int capacity() { return capacity; }
}
