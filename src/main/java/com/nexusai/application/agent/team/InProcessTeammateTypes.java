package com.nexusai.application.agent.team;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * InProcessTeammateTypes · 对齐 CC tasks/InProcessTeammateTask/types.ts (核心 helper + 类型).
 *
 * <p>L1 语义: 进程内 teammate 子任务的核心常量 + 类型守卫 + 数组裁剪 helper。
 * <ul>
 *   <li>{@link #TEAMMATE_MESSAGES_UI_CAP} = 50 — UI 镜像 messages 数组的容量 (CC: BQ 数据 ~20MB/agent @ 500+ turns)</li>
 *   <li>{@link #appendCappedMessage} — 不可变追加,达 cap 时丢弃最早;return 新数组</li>
 *   <li>{@link #isInProcessTeammateTask} — type guard 检查 {@code type === 'in_process_teammate'}</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: TEAMMATE_MESSAGES_UI_CAP=50 + appendCappedMessage 静态泛型方法 + isInProcessTeammateTask type guard</li>
 *   <li><b>A2 Golden Trace</b>: prev=undefined/empty→[item];prev.length < cap→[...prev, item];prev.length >= cap→slice后 push (保持 cap)</li>
 *   <li><b>A3 不可变</b>: 总是返回新数组;原数组不动</li>
 *   <li><b>A4 边界</b>: prev null/empty→[item];cap=0→[item] (CC 等价)</li>
 *   <li><b>A5 业务场景</b>: 500+ turn 会话中 UI 镜像 messages 始终保持 50 条,防止 RSS 飙升 (whale 9a990de8 实例 36.8GB)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS generic `appendCappedMessage<T>` → Java 静态泛型方法
 * 接受 Function<{@code Stream<T>},{@code T}> 不可变;TS readonly tuple →
 * Java record/test 通过 assert。
 *
 * <p><b>生产化状态（2026-08-14 用户裁定 DEC-31/32 撤销，team 运行时归 task 模块对齐度）</b>：
 * {@link #appendCappedMessage} 与 {@link #TEAMMATE_MESSAGES_UI_CAP} 被生产运行时
 * {@code AutonomousAgentLoop#appendMessage} 调用（cap 50 防 UI 镜像 RSS 膨胀）。原「本期未启用」标注已移除。
 */
public final class InProcessTeammateTypes {

    /** Mirrors CC TEAMMATE_MESSAGES_UI_CAP=50. Pure-data cap on the AppState UI mirror. */
    public static final int TEAMMATE_MESSAGES_UI_CAP = 50;

    private InProcessTeammateTypes() {}

    /**
     * Append {@code item} to the message array, capping at TEAMMATE_MESSAGES_UI_CAP
     * by dropping the oldest. Always returns a new array (AppState immutability).
     */
    public static <T> List<T> appendCappedMessage(List<T> prev, T item) {
        if (prev == null || prev.isEmpty()) {
            return new ArrayList<>(List.of(item));
        }
        if (prev.size() >= TEAMMATE_MESSAGES_UI_CAP) {
            List<T> next = new ArrayList<>(prev.subList(
                prev.size() - (TEAMMATE_MESSAGES_UI_CAP - 1), prev.size()));
            next.add(item);
            return next;
        }
        List<T> next = new ArrayList<>(prev);
        next.add(item);
        return next;
    }

    /**
     * Returns true iff the task is an in-process teammate task (CC
     * isInProcessTeammateTask type guard).
     *
     * @param task any task-like object (need only 'type' field)
     */
    public static boolean isInProcessTeammateTask(Object task) {
        if (task == null || !(task instanceof java.util.Map<?, ?> map)) {
            return false;
        }
        Object type = map.get("type");
        return "in_process_teammate".equals(type);
    }

    /** Test-friendly wrapper: accepts any record-like POJO with 'type' field via Function accessor. */
    public static <T> boolean isInProcessTeammateTask(T task, Function<T, Object> typeAccessor) {
        if (task == null || typeAccessor == null) return false;
        try {
            return "in_process_teammate".equals(typeAccessor.apply(task));
        } catch (Exception e) {
            return false;
        }
    }
}
