package com.nexusai.application.agent.tasks;

/**
 * LocalShellTask 类型守卫 · 对齐 CC tasks/LocalShellTask/guards.ts:34-41 isLocalShellTask.
 *
 * <p>CC 注释说明: "Extracted from LocalShellTask.tsx so non-React consumers (stopTask.ts via
 * print.ts) don't pull React/ink into the module graph." Java 端无 React/ink 概念, 但同样需要
 * 把类型守卫从 BackgroundTaskRunner 抽出避免循环依赖.
 *
 * <p>L1 语义: 给定 unknown 对象, 判断是否为 LocalShellTask (即 BackgroundTask 且 type==LOCAL_BASH).
 *            对应 CC `task.type === 'local_bash'` (CC 字符串 'local_bash' 对齐 TaskType.LOCAL_BASH).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 静态方法 `isLocalShellTask(Object) → boolean` 签名与 CC 函数一致</li>
 *   <li><b>A2 Golden Trace</b>: null → false; 非 BackgroundTask → false; 非 LOCAL_BASH → false; LOCAL_BASH → true</li>
 *   <li><b>A3</b>: 纯函数, 无内部状态; 相同输入 → 相同输出</li>
 *   <li><b>A4</b>: 5 种 BackgroundTaskType 中仅 LOCAL_BASH 返回 true (其余 6 种返回 false)</li>
 *   <li><b>A5</b>: 真实 BackgroundTask 实例 (status=RUNNING) 与 stub (status=COMPLETED) 均返回 true (类型由 type 决定, 与 status 无关)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): `instanceof BackgroundTask` + `task.type() == TaskType.LOCAL_BASH` pattern matching
 *                    替代 CC `typeof task === 'object' && task.type === 'local_bash'`.
 */
public final class LocalShellTaskGuards {

    private LocalShellTaskGuards() {
        // utility class — no instances
    }

    /**
     * 类型守卫: 判断给定对象是否为 LocalShellTask (BackgroundTask + type==LOCAL_BASH).
     *
     * @param task 待判断的对象 (允许 null / 任意类型)
     * @return true 当且仅当 task 是 BackgroundTask 且 type 字段等于 TaskType.LOCAL_BASH
     */
    public static boolean isLocalShellTask(Object task) {
        return task instanceof BackgroundTask bt && bt.type() == TaskType.LOCAL_BASH;
    }
}