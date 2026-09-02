package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * BufferedWriterTracker · 对齐 CC utils/bufferedWriter.ts.
 *
 * <p>L1 语义: 简单 in-memory buffer with optional flush listener。
 * 类似 BufferedReader 角色但用于输出流。
 * <ul>
 *   <li>{@link #write(String)} — append to buffer</li>
 *   <li>{@link #flush()} — emit via onFlush listener (or no-op if null)</li>
 *   <li>{@link #toString()} — current buffer content</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 method (write/flush/toString) + onFlush consumer 注入式</li>
 *   <li><b>A2 Golden Trace</b>: write accumulates;flush invokes consumer with full content;flush without listener→no-op;toString returns accumulated</li>
 *   <li><b>A3 副作用</b>: 内部 mutable buffer;flush consumer side-effect</li>
 *   <li><b>A4 边界</b>: write(null)→空字符串追加;flush before write→"" emitted</li>
 *   <li><b>A5 业务场景</b>: stream log lines → 累积到阈值 → flush 给 logger</li>
 * </ul>
 *
 * <p>L3 升级: TS class mutable array → Java ArrayList + StringBuilder;
 * TS function 注入式 → Java Consumer;
 * TS toString() → Java String concatenation.
 */
public final class BufferedWriterTracker {

    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<String> onFlush;

    public BufferedWriterTracker(Consumer<String> onFlush) {
        this.onFlush = onFlush;
    }

    public void write(String text) {
        if (text != null) buffer.append(text);
    }

    public void flush() {
        if (onFlush != null) {
            String current = buffer.toString();
            if (!current.isEmpty()) onFlush.accept(current);
            buffer.setLength(0);
        }
    }

    public int length() { return buffer.length(); }

    @Override
    public String toString() {
        return buffer.toString();
    }
}
