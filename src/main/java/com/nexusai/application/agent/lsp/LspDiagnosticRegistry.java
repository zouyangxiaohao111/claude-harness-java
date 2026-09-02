package com.nexusai.application.agent.lsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LSP 诊断注册中心 · 对齐 CC services/lsp/LSPDiagnosticRegistry.ts.
 *
 * <p>L1 语义: 暂存 LSP server 异步推送的 publishDiagnostics 通知, 按 server name + file URI 去重,
 *            控制每次 attach 的诊断数 (per file 10 / total 30), 然后转换为附件推送给 conversation.
 *            内部双层 dedup: 同一批内 (per-file Map) + 跨 turn (LRU).
 *            Severity 排序: Error(1) < Warning(2) < Info(3) < Hint(4); 优先输出 Error.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: register(serverName, files) → void; checkForLSPDiagnostics() → List;
 *       MAX_DIAGNOSTICS_PER_FILE=10; MAX_TOTAL_DIAGNOSTICS=30; MAX_DELIVERED_FILES=500.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — register() × N → checkForLSPDiagnostics() →
 *       返回 deduped + volume-limited + severity-sorted diagnostics; pending map 清空,
 *       delivered map 累积.</li>
 *   <li><b>A3</b>: 状态 — pending (Map) + delivered (LRU); attachmentSent 标记 →
 *       checkForLSPDiagnostics() 后从 pending 移除.</li>
 *   <li><b>A4</b>: 边界 — files=[] → 返回 []; severity=null → 4 (Hint, 默认);
 *       deliveredDiagnostics LRU 满 → 旧条目淘汰; clearDeliveredDiagnosticsForFile 仅清该文件.</li>
 *   <li><b>A5</b>: 真实场景 — 同 file URI + same msg/severity/range 在不同 turn 推送 →
 *       只第一次返回, 后续 dedup; cross-turn edit → clearDeliveredDiagnosticsForFile 重新触发.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC `LRUCache` → 简单 LinkedHashMap accessOrder (单线程注册中心,
 *                    测试时 resetAll 强制清理); UUID → {@link UUID#randomUUID()};
 *                    Map/Set → ConcurrentHashMap/newLinkedHashSet (preserves insertion order).
 */
public final class LspDiagnosticRegistry {

    private static final Logger log = LoggerFactory.getLogger(LspDiagnosticRegistry.class);

    /** 单文件诊断上限. CC MAX_DIAGNOSTICS_PER_FILE. */
    public static final int MAX_DIAGNOSTICS_PER_FILE = 10;
    /** 全部诊断上限. CC MAX_TOTAL_DIAGNOSTICS. */
    public static final int MAX_TOTAL_DIAGNOSTICS = 30;
    /** 跨 turn 去重最大文件数. CC MAX_DELIVERED_FILES. */
    public static final int MAX_DELIVERED_FILES = 500;

    /** 单个文件 + 单个诊断. CC DiagnosticFile + diagnostic item. */
    public record DiagnosticItem(
        String message,
        String severity,
        Object range,
        String source,
        Object code) {}

    /** 文件 + 该文件下诊断列表. CC DiagnosticFile. */
    public record DiagnosticFile(String uri, List<DiagnosticItem> diagnostics) {
        public DiagnosticFile {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** checkForLSPDiagnostics 返回的 deduped batch. */
    public record DiagnosticBatch(String serverName, List<DiagnosticFile> files) {
        public DiagnosticBatch {
            files = List.copyOf(files);
        }
    }

    /** 单条 pending 诊断. CC PendingLSPDiagnostic. */
    private static final class PendingDiagnostic {
        final String serverName;
        final List<DiagnosticFile> files;
        final long timestamp;
        boolean attachmentSent;

        PendingDiagnostic(String serverName, List<DiagnosticFile> files) {
            this.serverName = serverName;
            this.files = List.copyOf(files);
            this.timestamp = System.currentTimeMillis();
            this.attachmentSent = false;
        }
    }

    // 全部静态 — 对齐 CC 模块级全局状态.
    private static final Map<String, PendingDiagnostic> PENDING = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, Set<String>> DELIVERED =
        new LinkedHashMap<>(MAX_DELIVERED_FILES + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                return size() > MAX_DELIVERED_FILES;
            }
        };

    private LspDiagnosticRegistry() {}

    /** CC registerPendingLSPDiagnostic — UUID 保证并发注册唯一. */
    public static void register(String serverName, List<DiagnosticFile> files) {
        if (serverName == null || files == null) return;
        String id = UUID.randomUUID().toString();
        if (log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Registering {} file(s) from {} (ID: {})",
                files.size(), serverName, id);
        }
        PENDING.put(id, new PendingDiagnostic(serverName, files));
    }

    /** CC checkForLSPDiagnostics — dedup + volume limiting + severity sort. */
    public static List<DiagnosticBatch> checkForLSPDiagnostics() {
        if (log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Checking registry - {} pending", PENDING.size());
        }
        List<DiagnosticFile> allFiles = new ArrayList<>();
        Set<String> serverNames = new LinkedHashSet<>();
        List<PendingDiagnostic> toMark = new ArrayList<>();
        for (PendingDiagnostic p : PENDING.values()) {
            if (!p.attachmentSent) {
                allFiles.addAll(p.files);
                serverNames.add(p.serverName);
                toMark.add(p);
            }
        }
        if (allFiles.isEmpty()) return Collections.emptyList();

        List<DiagnosticFile> deduped;
        try {
            deduped = deduplicate(allFiles);
        } catch (Exception ex) {
            log.warn("Failed to deduplicate LSP diagnostics: {}", ex.getMessage());
            deduped = allFiles;
        }

        // 标记 + 删除已发送条目.
        for (PendingDiagnostic p : toMark) {
            p.attachmentSent = true;
        }
        PENDING.entrySet().removeIf(e -> e.getValue().attachmentSent);

        // Severity 排序 + volume limiting.
        int total = 0;
        int truncated = 0;
        List<DiagnosticFile> kept = new ArrayList<>();
        for (DiagnosticFile f : deduped) {
            List<DiagnosticItem> sorted = new ArrayList<>(f.diagnostics);
            sorted.sort((a, b) -> severityNumber(a.severity) - severityNumber(b.severity));
            if (sorted.size() > MAX_DIAGNOSTICS_PER_FILE) {
                truncated += sorted.size() - MAX_DIAGNOSTICS_PER_FILE;
                sorted = sorted.subList(0, MAX_DIAGNOSTICS_PER_FILE);
            }
            int remaining = MAX_TOTAL_DIAGNOSTICS - total;
            if (sorted.size() > remaining) {
                truncated += sorted.size() - remaining;
                if (remaining > 0) {
                    sorted = sorted.subList(0, remaining);
                } else {
                    sorted = Collections.emptyList();
                }
            }
            total += sorted.size();
            if (!sorted.isEmpty()) {
                kept.add(new DiagnosticFile(f.uri, sorted));
            }
        }

        if (truncated > 0 && log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Volume limiting removed {} diagnostic(s) "
                + "(max {}/file, {} total)", truncated, MAX_DIAGNOSTICS_PER_FILE, MAX_TOTAL_DIAGNOSTICS);
        }

        // 跨 turn 去重跟踪.
        for (DiagnosticFile f : kept) {
            Set<String> delivered = DELIVERED.computeIfAbsent(f.uri, k -> new LinkedHashSet<>());
            for (DiagnosticItem d : f.diagnostics) {
                try {
                    delivered.add(createDiagnosticKey(d));
                } catch (Exception ex) {
                    log.warn("Failed to track delivered diagnostic in {}: {}", f.uri, ex.getMessage());
                }
            }
        }

        if (kept.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("LSP Diagnostics: No new diagnostics to deliver (all filtered by deduplication)");
            }
            return Collections.emptyList();
        }

        if (log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Delivering {} file(s) from {} server(s)",
                kept.size(), serverNames.size());
        }
        return List.of(new DiagnosticBatch(String.join(", ", serverNames), kept));
    }

    /** CC clearAllLSPDiagnostics — 仅清 pending, 不动 delivered. */
    public static void clearAllLSPDiagnostics() {
        if (log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Clearing {} pending diagnostic(s)", PENDING.size());
        }
        PENDING.clear();
    }

    /** CC resetAllLSPDiagnosticState — 清 pending + delivered. */
    public static void resetAllLSPDiagnosticState() {
        if (log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Resetting all state ({} pending, {} files tracked)",
                PENDING.size(), DELIVERED.size());
        }
        PENDING.clear();
        DELIVERED.clear();
    }

    /** CC clearDeliveredDiagnosticsForFile — 编辑文件后调用, 重新触发该文件诊断. */
    public static void clearDeliveredDiagnosticsForFile(String fileUri) {
        if (fileUri != null && DELIVERED.remove(fileUri) != null && log.isDebugEnabled()) {
            log.debug("LSP Diagnostics: Clearing delivered diagnostics for {}", fileUri);
        }
    }

    /** CC getPendingLSPDiagnosticCount. */
    public static int getPendingLSPDiagnosticCount() {
        return PENDING.size();
    }

    // ---- internals ----

    private static List<DiagnosticFile> deduplicate(List<DiagnosticFile> allFiles) {
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        List<DiagnosticFile> deduped = new ArrayList<>();
        for (DiagnosticFile f : allFiles) {
            Set<String> seenKeys = seen.computeIfAbsent(f.uri, k -> new LinkedHashSet<>());
            List<DiagnosticItem> items = new ArrayList<>();
            Set<String> prev = DELIVERED.getOrDefault(f.uri, Collections.emptySet());
            for (DiagnosticItem d : f.diagnostics) {
                String key;
                try {
                    key = createDiagnosticKey(d);
                } catch (Exception ex) {
                    items.add(d); // fallback: 保留避免丢信息
                    continue;
                }
                if (seenKeys.contains(key) || prev.contains(key)) continue;
                seenKeys.add(key);
                items.add(d);
            }
            if (!items.isEmpty()) {
                deduped.add(new DiagnosticFile(f.uri, items));
            }
        }
        return deduped;
    }

    private static int severityNumber(String severity) {
        if (severity == null) return 4;
        return switch (severity) {
            case "Error" -> 1;
            case "Warning" -> 2;
            case "Info" -> 3;
            case "Hint" -> 4;
            default -> 4;
        };
    }

    private static String createDiagnosticKey(DiagnosticItem d) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"message\":\"").append(escape(d.message())).append("\",");
        sb.append("\"severity\":\"").append(escape(d.severity())).append("\",");
        sb.append("\"range\":\"").append(String.valueOf(d.range())).append("\",");
        sb.append("\"source\":\"").append(escape(d.source())).append("\",");
        sb.append("\"code\":\"").append(String.valueOf(d.code())).append("\"}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}