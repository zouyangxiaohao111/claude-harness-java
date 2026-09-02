package com.nexusai.application.agent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * remote-agent sidecar 持久化 · 对齐 CC sessionStorage.ts:320-399。
 *
 * <p>CC 路径（sessionStorage.ts:320-329）：{projectDir}/{sessionId}/remote-agents/
 * remote-agent-{taskId}.meta.json。Java 侧 {@code sessionDir} = {workspaceDir}/{sessionId}
 * （本地会话目录，由 {@link RemoteAgentTaskService} 注入）。
 *
 * <p><b>WHY（规则九）</b>:
 * <ul>
 *   <li>write fire-and-forget（persistRemoteAgentMetadata 失败仅 log，RemoteAgentTask.tsx:92-98）</li>
 *   <li>delete 任务完成/kill 时移除（:105-111），否则 --resume 复活已结束任务</li>
 *   <li>list 跳过损坏文件（:390-396），crash 部分写入不拖垮 restore</li>
 * </ul>
 */
public final class RemoteAgentMetadataStore {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentMetadataStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC original: 'remote-agents' 目录名（sessionStorage.ts:324） */
    public static final String REMOTE_AGENTS_SUBDIR = "remote-agents";

    /** CC original: 'remote-agent-{taskId}.meta.json' 文件前缀（:328） */
    public static final String META_FILE_PREFIX = "remote-agent-";

    /** CC original: '.meta.json' 后缀（:328） */
    public static final String META_FILE_SUFFIX = ".meta.json";

    private RemoteAgentMetadataStore() { /* utility class */ }

    /** CC getRemoteAgentsDir（:320-325）— {sessionDir}/remote-agents */
    public static Path getRemoteAgentsDir(Path sessionDir) {
        return sessionDir.resolve(REMOTE_AGENTS_SUBDIR);
    }

    /** CC getRemoteAgentMetadataPath（:327-329）— remote-agent-{taskId}.meta.json */
    public static Path getRemoteAgentMetadataPath(Path sessionDir, String taskId) {
        return getRemoteAgentsDir(sessionDir).resolve(META_FILE_PREFIX + taskId + META_FILE_SUFFIX);
    }

    /** CC writeRemoteAgentMetadata（:337-344）— 目录递归创建 + 写 JSON。 */
    public static void write(Path sessionDir, RemoteAgentMetadata metadata) {
        if (sessionDir == null || metadata == null) {
            return;
        }
        try {
            Path path = getRemoteAgentMetadataPath(sessionDir, metadata.taskId());
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.writeValueAsString(metadata), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (log.isDebugEnabled()) {
                log.debug("RemoteAgentMetadataStore.write: taskId={} path={}", metadata.taskId(), path);
            }
        } catch (IOException e) {
            log.warn("RemoteAgentMetadataStore.write 失败: taskId={} 错误={}", metadata.taskId(), e.getMessage());
        }
    }

    /** CC readRemoteAgentMetadata（:346-357）— 不存在/损坏返回 null。 */
    public static RemoteAgentMetadata read(Path sessionDir, String taskId) {
        if (sessionDir == null || taskId == null) {
            return null;
        }
        Path path = getRemoteAgentMetadataPath(sessionDir, taskId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), RemoteAgentMetadata.class);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteAgentMetadataStore.read 失败: taskId={} 错误={}", taskId, e.getMessage());
            }
            return null;
        }
    }

    /** CC deleteRemoteAgentMetadata（:359-367）— 不存在静默跳过。 */
    public static void delete(Path sessionDir, String taskId) {
        if (sessionDir == null || taskId == null) {
            return;
        }
        try {
            Files.deleteIfExists(getRemoteAgentMetadataPath(sessionDir, taskId));
            if (log.isDebugEnabled()) {
                log.debug("RemoteAgentMetadataStore.delete: taskId={}", taskId);
            }
        } catch (IOException e) {
            log.warn("RemoteAgentMetadataStore.delete 失败: taskId={} 错误={}", taskId, e.getMessage());
        }
    }

    /** CC listRemoteAgentMetadata（:373-399）— 扫描 .meta.json；损坏跳过。 */
    public static List<RemoteAgentMetadata> list(Path sessionDir) {
        List<RemoteAgentMetadata> results = new ArrayList<>();
        if (sessionDir == null) {
            return results;
        }
        Path dir = getRemoteAgentsDir(sessionDir);
        if (!Files.isDirectory(dir)) {
            return results;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(META_FILE_SUFFIX))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(p -> {
                    try {
                        results.add(JSON.readValue(Files.readString(p, StandardCharsets.UTF_8),
                            RemoteAgentMetadata.class));
                    } catch (IOException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("RemoteAgentMetadataStore.list: 跳过损坏文件 {}: {}", p.getFileName(), e.getMessage());
                        }
                    }
                });
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteAgentMetadataStore.list: 扫描目录失败 {}: {}", dir, e.getMessage());
            }
        }
        return results;
    }
}
