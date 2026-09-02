package com.nexusai.application.agent.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * CollapseHookSummaries · 对齐 CC utils/collapseHookSummaries.ts.
 *
 * <p>L1 语义: 合并连续相同 hookLabel 的 hook summary 消息(例如并行 tool calls 各发一条)。
 * 用 max totalDuration / 多 hookInfos / 合并 errors / OR preventedContinuation + hasOutput。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: collapseHookSummaries(List&lt;HookMessage&gt;)→List&lt;HookMessage&gt;</li>
 *   <li><b>A2 Golden Trace</b>: 同 label 连续 group 合并 → 单条 summary (max duration + flatten + OR)</li>
 *   <li><b>A3 纯函数</b>: 不修改原 list</li>
 *   <li><b>A4 边界</b>: null/empty → [];non-hook msg → 原样;单条 hook → 原样</li>
 *   <li><b>A5 业务场景</b>: 并行 tool call 同时触发 PostToolUse → UI 合并为 1 个 summary</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS type guard → Java instanceof check;array.flatMap + reduce →
 * Java stream collect;Math.max(...) → Java Streams.max。
 */
public final class CollapseHookSummaries {

    public interface HookMessage {
        String type();
        String subtype();
        String hookLabel();
        int hookCount();
        List<String> hookInfos();
        List<String> hookErrors();
        boolean preventedContinuation();
        boolean hasOutput();
        Long totalDurationMs();

        /**
         * [R6-IMP] 阻止继续执行的语义化原因 · CC original: {@code stopReason}
         * (SystemStopHookSummaryMessage, messages.ts:4407)。
         *
         * <p>WHY (DEL-TH-06 恢复): CC createStopHookSummaryMessage 携带 stopReason
         * (stopHooks.ts:253-256 {@code stopReason = result.stopReason || 'Stop hook prevented continuation'})，
         * Java {@link #SimpleHookMsg} 此前无该字段 → preventContinuation 语义只有布尔无原因。
         * default null 保持既有实现者兼容（CC stopReason 为 optional string|undefined）。
         *
         * @return 阻止原因文本（未阻止 / 无原因 → null）
         */
        default String stopReason() {
            return null;
        }
    }

    public static final class SimpleHookMsg implements HookMessage {
        private final String hookLabel;
        private final int hookCount;
        private final List<String> infos;
        private final List<String> errors;
        private final boolean prevented;
        private final boolean hasOutput;
        private final Long totalDurationMs;
        private final String stopReason;

        /** [R6-IMP] 兼容构造器：stopReason 缺省 null（既有 7 参调用方不变）。 */
        public SimpleHookMsg(String hookLabel, int hookCount,
                             List<String> infos, List<String> errors,
                             boolean prevented, boolean hasOutput, Long totalDurationMs) {
            this(hookLabel, hookCount, infos, errors, prevented, hasOutput, totalDurationMs, null);
        }

        /** [R6-IMP] 8 参完整构造器 · stopReason 对齐 CC SystemStopHookSummaryMessage.stopReason。 */
        public SimpleHookMsg(String hookLabel, int hookCount,
                             List<String> infos, List<String> errors,
                             boolean prevented, boolean hasOutput, Long totalDurationMs,
                             String stopReason) {
            this.hookLabel = hookLabel;
            this.hookCount = hookCount;
            this.infos = infos;
            this.errors = errors;
            this.prevented = prevented;
            this.hasOutput = hasOutput;
            this.totalDurationMs = totalDurationMs;
            this.stopReason = stopReason;
        }

        @Override public String type() { return "system"; }
        @Override public String subtype() { return "stop_hook_summary"; }
        @Override public String hookLabel() { return hookLabel; }
        @Override public int hookCount() { return hookCount; }
        @Override public List<String> hookInfos() { return infos; }
        @Override public List<String> hookErrors() { return errors; }
        @Override public boolean preventedContinuation() { return prevented; }
        @Override public boolean hasOutput() { return hasOutput; }
        @Override public Long totalDurationMs() { return totalDurationMs; }
        @Override public String stopReason() { return stopReason; }
    }

    private static boolean isHookSummary(HookMessage m) {
        if (m == null) return false;
        if (!"system".equals(m.type())) return false;
        if (!"stop_hook_summary".equals(m.subtype())) return false;
        return m.hookLabel() != null;
    }

    public static List<HookMessage> collapse(List<HookMessage> messages) {
        if (messages == null || messages.isEmpty()) return new ArrayList<>();
        List<HookMessage> result = new ArrayList<>();
        int i = 0;
        while (i < messages.size()) {
            HookMessage msg = messages.get(i);
            if (isHookSummary(msg)) {
                String label = msg.hookLabel();
                List<HookMessage> group = new ArrayList<>();
                while (i < messages.size()) {
                    HookMessage next = messages.get(i);
                    if (!isHookSummary(next) || !label.equals(next.hookLabel())) break;
                    group.add(next);
                    i++;
                }
                if (group.size() == 1) {
                    result.add(msg);
                } else {
                    result.add(combine(label, group));
                }
            } else {
                result.add(msg);
                i++;
            }
        }
        return result;
    }

    private static HookMessage combine(String label, List<HookMessage> group) {
        int totalCount = group.stream().mapToInt(HookMessage::hookCount).sum();
        List<String> infos = new ArrayList<>();
        for (HookMessage m : group) {
            if (m.hookInfos() != null) infos.addAll(m.hookInfos());
        }
        List<String> errors = new ArrayList<>();
        for (HookMessage m : group) {
            if (m.hookErrors() != null) errors.addAll(m.hookErrors());
        }
        boolean prevented = group.stream().anyMatch(HookMessage::preventedContinuation);
        boolean hasOutput = group.stream().anyMatch(HookMessage::hasOutput);
        long maxMs = group.stream().mapToLong(m -> m.totalDurationMs() == null ? 0 : m.totalDurationMs()).max().orElse(0);
        // [IMP-HOOKS-S7 D6] CC collapseHookSummaries.ts:41-50 合并 = {...group[0], ...覆盖字段} ——
        //   spread 保留组首全部未覆盖字段（含 stopReason 原文，即使为空串）。Java 端无 spread，
    //   等价表达 = group.get(0).stopReason() 原样保留（null-safe）。旧"组内取第一个非空值"
    //   循环与"CC 折叠输出无 stopReason"注释为误读（实测 CC spread 保留），已删除。
        return new SimpleHookMsg(label, totalCount, infos, errors, prevented, hasOutput, maxMs,
            group.get(0).stopReason());
    }
}
