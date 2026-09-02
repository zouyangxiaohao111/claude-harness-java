package com.nexusai.infra.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * DiagnosticLogWriter · 对齐 CC utils/diagLogs.ts.
 *
 * <p>L1 语义: 诊断日志记录到文件 (CLAUDE_CODE_DIAGNOSTICS_FILE env var) +
 * 异步 timing wrapper。
 * <ul>
 *   <li>{@link #logForDiagnosticsNoPII} — sync file append (JSONL)</li>
 *   <li>{@link #withDiagnosticsTiming} — async timing wrapper {event}_started + {event}_completed</li>
 * </ul>
 * 必须 no PII (无 file path / project / prompt) — 仅 event name + data。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + LogFileSupplier/LogAppender 注入式 + DiagnosticLevel enum</li>
 *   <li><b>A2 Golden Trace</b>: env CLAUDE_CODE_DIAGNOSTICS_FILE→append;无 file→no-op;append fail→mkdir + retry;retry fail→silently fail</li>
 *   <li><b>A3 副作用</b>: file append + JSON line;withDiagnosticsTiming 记 timing_ms</li>
 *   <li><b>A4 边界</b>: null env→no-op;append fail→catch silently;fn throws→log error + re-throw</li>
 *   <li><b>A5 业务场景</b>: monitor issues from within container;init period diagnostics</li>
 * </ul>
 *
 * <p>L3 升级: TS process.env → Java Supplier 注入式;
 * TS fs.appendFileSync → Java Files.writeString APPEND;
 * TS jsonStringify → Java 自实现 JSON 序列化.
 */
public final class DiagnosticLogWriter {

    public enum Level { debug, info, warn, error }

    /** Supplier returning the diagnostics log file path, or null to disable. */
    public interface LogFileSupplier extends Supplier<String> {}

    /** Appender that writes a single JSON line to the file. */
    public interface LogAppender {
        void append(String path, String line);
    }

    private DiagnosticLogWriter() {}

    /**
     * Logs diagnostic info to the file from LogFileSupplier.
     * Sync I/O — must NOT be called with PII (file paths, project names, prompts, etc.).
     */
    public static void logForDiagnosticsNoPII(
        Level level,
        String event,
        Map<String, Object> data,
        LogFileSupplier logFileSupplier,
        LogAppender logAppender) {
        String logFile = logFileSupplier == null ? null : logFileSupplier.get();
        if (logFile == null) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("level", level.name());
        entry.put("event", event);
        entry.put("data", data == null ? Map.of() : data);

        String line = serializeJson(entry) + "\n";
        LogAppender appender = logAppender == null ? SimpleAppender.INSTANCE : logAppender;
        try {
            appender.append(logFile, line);
        } catch (Exception first) {
            // Try creating parent directory
            try {
                Path parent = Paths.get(logFile).getParent();
                if (parent != null) Files.createDirectories(parent);
                appender.append(logFile, line);
            } catch (Exception ignored) {
                // Silently fail
            }
        }
    }

    /**
     * Async timing wrapper. Logs {event}_started then {event}_completed (or {event}_failed) with duration_ms.
     */
    public static <T> java.util.concurrent.CompletableFuture<T> withDiagnosticsTiming(
        String event,
        java.util.concurrent.CompletableFuture<T> future,
        BiConsumer<T, Map<String, Object>> additionalDataBuilder,
        LogFileSupplier logFileSupplier,
        LogAppender logAppender) {
        long start = System.currentTimeMillis();
        logForDiagnosticsNoPII(Level.info, event + "_started", null, logFileSupplier, logAppender);
        return future.whenComplete((result, error) -> {
            if (error != null) {
                logForDiagnosticsNoPII(Level.error, event + "_failed",
                    Map.of("duration_ms", System.currentTimeMillis() - start),
                    logFileSupplier, logAppender);
            } else {
                Map<String, Object> data = new java.util.HashMap<>();
                data.put("duration_ms", System.currentTimeMillis() - start);
                if (additionalDataBuilder != null) {
                    additionalDataBuilder.accept(result, data);
                }
                logForDiagnosticsNoPII(Level.info, event + "_completed", data, logFileSupplier, logAppender);
            }
        });
    }

    /** Simple JSON serializer for our flat structure (string keys, primitive/object values). */
    private static String serializeJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            serializeValue(sb, e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static void serializeValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean) sb.append(v);
        else if (v instanceof Number) sb.append(v);
        else if (v instanceof Map) {
            sb.append("{");
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) sb.append(",");
                f = false;
                sb.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":");
                serializeValue(sb, e.getValue());
            }
            sb.append("}");
        }
        else if (v instanceof Iterable) {
            sb.append("[");
            boolean f = true;
            for (Object item : (Iterable<?>) v) {
                if (!f) sb.append(",");
                f = false;
                serializeValue(sb, item);
            }
            sb.append("]");
        }
        else sb.append("\"").append(escape(String.valueOf(v))).append("\"");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Default appender that uses {@link Files}. */
    public enum SimpleAppender implements LogAppender {
        INSTANCE;
        @Override public void append(String path, String line) {
            try {
                Files.writeString(Paths.get(path), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
