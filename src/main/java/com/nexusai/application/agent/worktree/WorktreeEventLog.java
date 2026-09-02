package com.nexusai.application.agent.worktree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * s18 worktree 生命周期事件日志 · Java 独有审计日志（CC 无 log_event / events.jsonl 对应物）。
 *
 * <p>L1 行为: 每条事件一行 JSON (jsonl 格式), 字段含 timestamp/event/slug/path/branch/result/exitCode.
 * <br>追加写, 不修改历史 (audit trail).
 */
public final class WorktreeEventLog {

    private static final Logger log = LoggerFactory.getLogger(WorktreeEventLog.class);

    /** events.jsonl 文件名 (CC 默认) */
    public static final String EVENTS_FILE = "events.jsonl";

    private final Path eventsFile;

    public WorktreeEventLog(Path eventsFile) {
        if (eventsFile == null) {
            throw new IllegalArgumentException("eventsFile must not be null");
        }
        this.eventsFile = eventsFile;
    }

    /**
     * 默认事件日志位置: {@code <gitRoot>/.nexusai/worktrees/<events.jsonl>}（决策 D7）。
     */
    public static WorktreeEventLog defaultFor(Path gitRoot) {
        Path dir = WorktreePaths.worktreesDir(gitRoot);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("WorktreeEventLog: failed to create dir {}: {}", dir, e.getMessage());
        }
        return new WorktreeEventLog(dir.resolve(EVENTS_FILE));
    }

    /**
     * 记录事件 · Java 独有审计日志（CC 无对应物）。
     *
     * @param event 事件类型 (例如 "create", "remove", "keep", "create_agent", "remove_agent")
     * @param slug  worktree slug
     * @param extra 附加字段 (例如 branch / exitCode / error 等)
     */
    public void log(String event, String slug, Map<String, String> extra) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("event", event);
        if (slug != null) {
            record.put("slug", slug);
        }
        if (extra != null) {
            record.putAll(extra);
        }
        String line = toJsonLine(record);
        try {
            Files.createDirectories(eventsFile.getParent());
            Files.writeString(eventsFile, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (log.isDebugEnabled()) {
                log.debug("WorktreeEventLog: wrote {} for slug={}", event, slug);
            }
        } catch (IOException e) {
            log.warn("WorktreeEventLog: failed to append event {}: {}", event, e.getMessage());
        }
    }

    /** 便捷重载: 无 extra 字段 */
    public void log(String event, String slug) {
        log(event, slug, null);
    }

    /**
     * 极简 JSON 序列化 (避免引入 Jackson 依赖), 仅 escape 控制字符与引号.
     * 单行 JSON (jsonl), 无外部依赖.
     */
    private static String toJsonLine(Map<String, String> record) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : record.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            sb.append('"').append(escapeJson(e.getValue())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** 获取 events 文件路径 (供测试用) */
    public Path getEventsFile() {
        return eventsFile;
    }
}