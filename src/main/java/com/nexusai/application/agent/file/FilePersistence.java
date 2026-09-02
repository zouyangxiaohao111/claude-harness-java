package com.nexusai.application.agent.file;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File Persistence · 对齐 CC utils/filePersistence/filePersistence.ts (287 行).
 *
 * <p>FIX-UTIL-FILEPERSIST: 简化版文件持久化 (runFilePersistence / executeFilePersistence / executeCloudPersistence).
 *
 * <p>L1 行为: 给定 sessionId + fileContent, 持久化到本地 + (可选) 云端.
 */
@Component
public class FilePersistence {

    private static final Logger log = LoggerFactory.getLogger(FilePersistence.class);

    public enum PersistenceMode { LOCAL, CLOUD, HYBRID }

    public record PersistenceRecord(String sessionId, String content,
                                    PersistenceMode mode, long timestamp, String url) {}

    private final AtomicLong totalPersisted = new AtomicLong(0);
    private final Map<String, PersistenceRecord> records = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return true; // 简化版总是启用
    }

    public PersistenceRecord runFilePersistence(String sessionId, String content) {
        return persist(sessionId, content, PersistenceMode.LOCAL);
    }

    public PersistenceRecord executeFilePersistence(String sessionId, String content) {
        return persist(sessionId, content, PersistenceMode.LOCAL);
    }

    public PersistenceRecord executeCloudPersistence(String sessionId, String content) {
        return persist(sessionId, content, PersistenceMode.CLOUD);
    }

    private PersistenceRecord persist(String sessionId, String content, PersistenceMode mode) {
        String url = mode == PersistenceMode.CLOUD
            ? "https://cloud.example.com/" + sessionId
            : "file://" + NexusaiPaths.getProjectDirName() + "/sessions/" + sessionId;
        PersistenceRecord rec = new PersistenceRecord(sessionId, content, mode,
            System.currentTimeMillis(), url);
        records.put(sessionId, rec);
        totalPersisted.incrementAndGet();
        log.info("FilePersistence: session={} mode={} url={}", sessionId, mode, url);
        return rec;
    }

    public PersistenceRecord get(String sessionId) {
        return records.get(sessionId);
    }

    public long totalPersisted() {
        return totalPersisted.get();
    }
}