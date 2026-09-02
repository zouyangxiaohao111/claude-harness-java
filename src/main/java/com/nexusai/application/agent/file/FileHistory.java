package com.nexusai.application.agent.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * File History · 对齐 CC utils/fileHistory.ts (1115 行 edit snapshots/diff/rewind/restore).
 *
 * <p>FIX-UTIL-6: 极简版实现, 覆盖 /rewind 命令核心 L1 行为.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>{@link #snapshot(Path, String)}: 文件编辑前快照 (path + pre-edit content)</li>
 *   <li>{@link #listSnapshots(Path)}: 列出文件的所有快照 (按时间倒序)</li>
 *   <li>{@link #rewind(Path, int)}: 恢复到第 N 个快照 (返回 restored content)</li>
 * </ul>
 *
 * <p>LIMIT: 内存存储; 重启丢失; 无 diff 详细分析 (仅整文件 snapshot).
 */
@Component
public class FileHistory {

    private static final Logger log = LoggerFactory.getLogger(FileHistory.class);
    private static final int MAX_SNAPSHOTS_PER_FILE = 50;

    /** path → list of snapshots (oldest first) */
    private final Map<String, Deque<Snapshot>> history = new HashMap<>();

    public synchronized void snapshot(Path file, String content) {
        if (file == null) return;
        String key = file.toString();
        Deque<Snapshot> deque = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        deque.addLast(new Snapshot(content, System.currentTimeMillis()));
        while (deque.size() > MAX_SNAPSHOTS_PER_FILE) {
            deque.pollFirst();
        }
        log.debug("FileHistory: snapshot file={} size={}", file, content != null ? content.length() : 0);
    }

    public synchronized List<Snapshot> listSnapshots(Path file) {
        if (file == null) return List.of();
        Deque<Snapshot> deque = history.get(file.toString());
        if (deque == null) return List.of();
        return List.copyOf(deque);
    }

    public synchronized Optional<Snapshot> rewind(Path file, int index) {
        Deque<Snapshot> deque = history.get(file.toString());
        if (deque == null || index < 0 || index >= deque.size()) return Optional.empty();
        Snapshot[] arr = deque.toArray(new Snapshot[0]);
        return Optional.of(arr[index]);
    }

    public synchronized void clear(Path file) {
        if (file != null) history.remove(file.toString());
    }

    public record Snapshot(String content, long timestamp) {}
}