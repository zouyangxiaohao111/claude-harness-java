package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * CollapseBackgroundBashNotifications · 对齐 CC utils/collapseBackgroundBashNotifications.ts.
 *
 * <p>L1 语义: 合并连续 completed background bash notifications 为单条 "N background commands completed"。
 * verbose 模式透传;non-fullscreen 模式透传;只合并 status=completed + 特定 prefix 的 bash task。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: collapse(messages, verbose, isFullscreenEnabled, isCompletedPredicate)→List&lt;Message&gt;</li>
 *   <li><b>A2 Golden Trace</b>: 3 consecutive completed → 1 batch(count=3);单条→保留;失败/非 fullscreen/verbose→原样</li>
 *   <li><b>A3 纯函数</b>: 注入式 predicates (testable);无副作用</li>
 *   <li><b>A4 边界</b>: null messages→[];empty→[];non-completed→保留</li>
 *   <li><b>A5 业务场景</b>: 后台 3 个并行 shell task 完成 → UI 显示 1 条 "3 background commands completed"</li>
 * </ul>
 *
 * <p>L3 升级: TS inline extractTag / startWith → Java injected Predicate;
 * TS object spread → Java messageFactory Supplier;
 * TS mutable array → Java ArrayList 累积.
 */
public final class CollapseBackgroundBashNotifications {

    public static final String SUMMARY_PREFIX = "[bg-bash]";
    public static final String STATUS_COMPLETED = "completed";
    // CC utils/collapseBackgroundBashNotifications.ts:4,20 用 TASK_NOTIFICATION_TAG 常量 ('task-notification')
    //   检测 `<task-notification>` 开头 — 连字符 (OPD-TS-20 D1); 下划线检测串 match 不到 → collapse 失效.
    public static final String TASK_NOTIFICATION_TAG = "task-notification";

    public interface Message {
        String type();
        String status();
        String summary();
        Message withSummary(String newSummary);
    }

    private CollapseBackgroundBashNotifications() {}

    /**
     * Collapse consecutive completed background-bash notifications into a single batch.
     *
     * @param messages              input message list
     * @param verbose              if true, pass-through (no collapsing)
     * @param isFullscreenEnabled if false, pass-through
     * @param isCompletedPredicate injected matcher: msg is completed background-bash?
     * @param summaryFactory       takes (firstMsg, count) → batch message
     */
    public static List<Message> collapse(
        List<Message> messages,
        boolean verbose,
        boolean isFullscreenEnabled,
        Predicate<Message> isCompletedPredicate,
        java.util.function.BiFunction<Message, Integer, Message> summaryFactory) {
        if (messages == null || messages.isEmpty()) return new ArrayList<>();
        if (verbose || !isFullscreenEnabled) return new ArrayList<>(messages);

        List<Message> result = new ArrayList<>();
        int i = 0;
        while (i < messages.size()) {
            Message msg = messages.get(i);
            if (isCompletedPredicate.test(msg)) {
                int count = 0;
                while (i < messages.size() && isCompletedPredicate.test(messages.get(i))) {
                    count++;
                    i++;
                }
                if (count == 1) {
                    result.add(msg);
                } else {
                    result.add(summaryFactory.apply(msg, count));
                }
            } else {
                result.add(msg);
                i++;
            }
        }
        return result;
    }
}
