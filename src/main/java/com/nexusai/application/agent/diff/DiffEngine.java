package com.nexusai.application.agent.diff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * DiffEngine · Diff Engine 的比对端 · 对齐 skill differential-testing.md Diff Engine.
 *
 * <p>5 Release Gate 中 A2 (Golden Trace) + A4 (Tool Sequence) 都用本类比对:
 * <ul>
 *   <li>A2: 比对 golden trace 与 actual trace 的 <b>关键步骤序列</b> (Kind + name 一致)</li>
 *   <li>A4: 比对工具调用顺序 (LLM_TOOL_CALL name + id 序列)</li>
 * </ul>
 *
 * <p>L1/L2 等价性靠 5 道门禁同时过 (A1 + A2 + A3 + A4 + A5) 才能定性.
 */
public class DiffEngine {

    private static final Logger log = LoggerFactory.getLogger(DiffEngine.class);

    /**
     * A2: Golden Trace 比对 · 严格序列比对 (忽略 LLM_CHUNK + timestamp)
     *
     * @param name     测试名 (用于日志)
     * @param golden   期望的事件序列
     * @param actual   实际的事件序列
     * @return DiffResult 含 passed + 详细差异
     */
    public static DiffResult compareA2(String name, List<TraceEvent> golden, List<TraceEvent> actual) {
        // 过滤 LLM_CHUNK (per-character 流式, 不参与 sequence 比对)
        List<TraceEvent> goldenFiltered = filterChunks(golden);
        List<TraceEvent> actualFiltered = filterChunks(actual);

        List<String> diffs = new ArrayList<>();
        int max = Math.max(goldenFiltered.size(), actualFiltered.size());
        int matched = 0;
        for (int i = 0; i < max; i++) {
            TraceEvent g = i < goldenFiltered.size() ? goldenFiltered.get(i) : null;
            TraceEvent a = i < actualFiltered.size() ? actualFiltered.get(i) : null;
            if (g == null) {
                diffs.add("step " + i + ": GOLDEN exhausted, ACTUAL=" + a);
            } else if (a == null) {
                diffs.add("step " + i + ": GOLDEN=" + g + " but ACTUAL exhausted");
            } else if (!matches(g, a)) {
                diffs.add("step " + i + ": GOLDEN=" + g + " vs ACTUAL=" + a);
            } else {
                matched++;
            }
        }
        boolean passed = diffs.isEmpty();
        DiffResult result = new DiffResult(name, "A2 Golden Trace", passed, matched, max, diffs);
        log.info("DiffEngine A2 [{}]: {} ({} / {} steps matched)",
            name, passed ? "PASS" : "FAIL", matched, max);
        if (!passed) {
            for (String d : diffs) {
                log.warn("  diff: {}", d);
            }
        }
        return result;
    }

    /**
     * A4: Tool Sequence 比对 · 仅看 LLM_TOOL_CALL + TOOL_RESULT 的 (name, id) 序列.
     * <p>跳过 chunk/reasoning/notification 等其他事件类型, 专注工具调用顺序.
     */
    public static DiffResult compareA4(String name, List<TraceEvent> golden, List<TraceEvent> actual) {
        List<TraceEvent> goldenTools = filterTools(golden);
        List<TraceEvent> actualTools = filterTools(actual);

        List<String> diffs = new ArrayList<>();
        int max = Math.max(goldenTools.size(), actualTools.size());
        int matched = 0;
        for (int i = 0; i < max; i++) {
            TraceEvent g = i < goldenTools.size() ? goldenTools.get(i) : null;
            TraceEvent a = i < actualTools.size() ? actualTools.get(i) : null;
            if (g == null) {
                diffs.add("tool " + i + ": GOLDEN exhausted, ACTUAL=" + a);
            } else if (a == null) {
                diffs.add("tool " + i + ": GOLDEN=" + g + " but ACTUAL exhausted");
            } else if (!g.name().equals(a.name())) {
                diffs.add("tool " + i + ": GOLDEN name=" + g.name() + " vs ACTUAL name=" + a.name());
            } else {
                matched++;
            }
        }
        boolean passed = diffs.isEmpty();
        DiffResult result = new DiffResult(name, "A4 Tool Sequence", passed, matched, max, diffs);
        log.info("DiffEngine A4 [{}]: {} ({} / {} tools matched)",
            name, passed ? "PASS" : "FAIL", matched, max);
        return result;
    }

    /** LLM_CHUNK 忽略 (per-character 流式) */
    private static List<TraceEvent> filterChunks(List<TraceEvent> events) {
        return events.stream()
            .filter(e -> e.kind() != TraceEvent.Kind.LLM_CHUNK)
            .toList();
    }

    /** 只保留 LLM_TOOL_CALL + TOOL_RESULT */
    private static List<TraceEvent> filterTools(List<TraceEvent> events) {
        return events.stream()
            .filter(e -> e.kind() == TraceEvent.Kind.LLM_TOOL_CALL
                      || e.kind() == TraceEvent.Kind.TOOL_RESULT)
            .toList();
    }

    /** 两个事件是否"语义一致" (kind + name 相同, timestamp 忽略) */
    private static boolean matches(TraceEvent g, TraceEvent a) {
        return g.kind() == a.kind() && g.name().equals(a.name());
    }

    /** Diff 结果 */
    public record DiffResult(
        String testName,
        String gate,
        boolean passed,
        int matchedSteps,
        int totalSteps,
        List<String> diffs) {

        public double similarity() {
            return totalSteps == 0 ? 1.0 : (double) matchedSteps / totalSteps;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s (%d/%d matched, %.1f%% similarity)",
                testName, gate, passed ? "PASS" : "FAIL",
                matchedSteps, totalSteps, similarity() * 100);
        }
    }
}