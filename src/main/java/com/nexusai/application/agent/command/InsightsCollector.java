package com.nexusai.application.agent.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session insights collector · 对齐 CC commands/insights.ts.
 *
 * <p>L1 语义: session 日志收集 + facet extraction (turn count / tool usage / duration / model).
 *            本类只暴露核心统计 + log 解析,实际 LLM facet extraction 由 caller wired.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: collectSessionStats(jsonl) → SessionStats record;
 *       extractFacets(stats, logSupplier) → Facets record;
 *       formatHtmlReport(stats, facets) → String (markdown-style HTML).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 5 turn + Read×3 + Bash×2 → stats.turnCount=5 / toolCounts{Read:3,Bash:2};
 *       facets 主键 (topTools / duration / mostActiveProject) → 非空.</li>
 *   <li><b>A3</b>: 纯函数 — 同 jsonl 同 stats; jsonl=[] → empty stats.</li>
 *   <li><b>A4</b>: 边界 — empty → empty stats; 单 turn → counts=1; 缺失字段 → 默认.</li>
 *   <li><b>A5</b>: 真实场景 — 用户 `/insights` → 加载最近 7 天 session log → 统计工具使用排行.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/await → Java 同步 (异步由 caller wired);
 *                    TS Date → Java Instant;
 *                    TS Map → Java Map / LinkedHashMap (preserve insertion order).
 */
public final class InsightsCollector {

    private static final Logger log = LoggerFactory.getLogger(InsightsCollector.class);

    public record SessionStats(
        int turnCount,
        Map<String, Integer> toolCounts,
        long totalDurationMs,
        String firstTimestamp,
        String lastTimestamp,
        List<String> modelsUsed) {
        public static SessionStats empty() {
            return new SessionStats(0, Collections.emptyMap(), 0L, "", "",
                Collections.emptyList());
        }
    }

    public record Facets(
        List<Map.Entry<String, Integer>> topTools,
        long avgTurnDurationMs,
        String mostActiveProject,
        Map<String, Integer> modelUsage) {
    }

    public record HtmlReport(String title, String content) {}

    private final Supplier<List<String>> sessionLogSupplier;

    public InsightsCollector(Supplier<List<String>> sessionLogSupplier) {
        this.sessionLogSupplier = Objects.requireNonNull(sessionLogSupplier);
    }

    public InsightsCollector() {
        this(List::of);
    }

    /** CC collectSessionStats — 解析 JSONL 行, 提取 turn + tool + duration. */
    public SessionStats collectSessionStats(List<String> jsonlLines) {
        if (jsonlLines == null || jsonlLines.isEmpty()) return SessionStats.empty();
        Map<String, Integer> toolCounts = new HashMap<>();
        List<String> models = new ArrayList<>();
        String firstTs = null, lastTs = null;
        long durationMs = 0;
        int turnCount = 0;
        for (String line : jsonlLines) {
            if (line == null || line.isBlank()) continue;
            String ts = extractField(line, "timestamp");
            String type = extractField(line, "type");
            String tool = extractField(line, "name");
            String model = extractField(line, "model");
            if (firstTs == null) firstTs = ts;
            lastTs = ts;
            if ("assistant".equals(type)) {
                turnCount++;
                if (model != null && !models.contains(model)) models.add(model);
            }
            if (tool != null && !tool.isEmpty()) {
                toolCounts.merge(tool, 1, Integer::sum);
            }
        }
        if (firstTs != null && lastTs != null) {
            try {
                durationMs = Instant.parse(lastTs).toEpochMilli()
                    - Instant.parse(firstTs).toEpochMilli();
                if (durationMs < 0) durationMs = 0;
            } catch (Exception ignored) {
                durationMs = 0;
            }
        }
        return new SessionStats(turnCount, toolCounts, durationMs,
            firstTs == null ? "" : firstTs, lastTs == null ? "" : lastTs, models);
    }

    /** CC extractFacets — 计算 top tools + avg duration. */
    public Facets extractFacets(SessionStats stats, String projectRoot) {
        if (stats == null) stats = SessionStats.empty();
        List<Map.Entry<String, Integer>> top = new ArrayList<>(stats.toolCounts().entrySet());
        top.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        long avgDuration = stats.turnCount() > 0
            ? stats.totalDurationMs() / stats.turnCount() : 0L;
        Map<String, Integer> modelUsage = new HashMap<>();
        for (String m : stats.modelsUsed()) {
            modelUsage.merge(m, 1, Integer::sum);
        }
        return new Facets(top, avgDuration,
            projectRoot == null ? "" : projectRoot,
            modelUsage);
    }

    /** CC formatHtmlReport — Markdown-style HTML. */
    public HtmlReport formatHtmlReport(SessionStats stats, Facets facets) {
        if (stats == null) stats = SessionStats.empty();
        if (facets == null) facets = extractFacets(stats, "");
        StringBuilder sb = new StringBuilder();
        sb.append("# Session Insights\n\n");
        sb.append("## Summary\n\n");
        sb.append("- **Turn count**: ").append(stats.turnCount()).append("\n");
        sb.append("- **Total duration**: ").append(formatDuration(stats.totalDurationMs())).append("\n");
        sb.append("- **Models used**: ");
        if (stats.modelsUsed().isEmpty()) sb.append("(none)");
        else sb.append(String.join(", ", stats.modelsUsed()));
        sb.append("\n\n");
        sb.append("## Top Tools\n\n");
        if (facets.topTools().isEmpty()) {
            sb.append("(no tool calls)\n\n");
        } else {
            for (var e : facets.topTools()) {
                sb.append("- `").append(e.getKey()).append("`: ")
                  .append(e.getValue()).append(" calls\n");
            }
            sb.append("\n");
        }
        sb.append("## Avg turn duration\n\n");
        sb.append(formatDuration(facets.avgTurnDurationMs())).append("\n\n");
        if (!facets.mostActiveProject().isEmpty()) {
            sb.append("## Most active project\n\n");
            sb.append("`").append(facets.mostActiveProject()).append("`\n");
        }
        return new HtmlReport("Session Insights", sb.toString());
    }

    /** 主链 — 加载 session logs → collect stats → extract facets → format. */
    public HtmlReport generateReport(String projectRoot) {
        List<String> logs = sessionLogSupplier.get();
        SessionStats stats = collectSessionStats(logs);
        Facets facets = extractFacets(stats, projectRoot);
        return formatHtmlReport(stats, facets);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds % 60);
        return String.format("%ds", seconds);
    }

    /** 简化 JSON 字段提取 (提取 "field":"value" 形式). */
    private static String extractField(String json, String field) {
        if (json == null || field == null) return null;
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int qStart = json.indexOf('"', colon + 1);
        if (qStart < 0) return null;
        int qEnd = json.indexOf('"', qStart + 1);
        if (qEnd < 0) return null;
        return json.substring(qStart + 1, qEnd);
    }
}