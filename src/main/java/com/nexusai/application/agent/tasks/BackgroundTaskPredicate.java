package com.nexusai.application.agent.tasks;

/**
 * BackgroundTaskPredicate · 对齐 CC tasks/types.ts:32-46 isBackgroundTask。
 *
 * <p>L1 语义: 判断一个任务是否应出现在"后台任务指示器"中。规则 (CC 注释):
 * <ol>
 *   <li>任务处于 running 或 pending 状态</li>
 *   <li>任务已被显式后台化 (isBackgrounded !== false) — 前台任务尚未成为"后台任务"</li>
 * </ol>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: static boolean isBackgroundTask(BackgroundTaskStatus, Boolean isBackgrounded)</li>
 *   <li><b>A2 Golden Trace</b>: running + backgrounded=true/null → true; completed → false</li>
 *   <li><b>A3 纯函数</b>: 无副作用, 仅依赖两参数</li>
 *   <li><b>A4 边界</b>: status=COMPLETED/FAILED/KILLED → false (先短路); isBackgrounded=false → false;
 *       isBackgrounded=null (字段缺失, CC {@code 'isBackgrounded' in task} 为 false) → true</li>
 *   <li><b>A5 业务场景</b>: running 但 isBackgrounded=false 的前台 bash → 不显示; 后台 agent → 显示</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS type guard {@code task is BackgroundTaskState} + {@code 'isBackgrounded' in task}
 * → Java 静态谓词, isBackgrounded 用 {@code Boolean} (null = CC 字段缺失语义)。
 */
public final class BackgroundTaskPredicate {

    private BackgroundTaskPredicate() {}

    /**
     * CC tasks/types.ts:41-48 —
     * <pre>
     * if (task.status !== 'running' &amp;&amp; task.status !== 'pending') return false
     * if ('isBackgrounded' in task &amp;&amp; task.isBackgrounded === false) return false
     * return true
     * </pre>
     *
     * @param status        任务状态
     * @param isBackgrounded 是否已后台化; {@code null} 表示 CC 任务对象无该字段 (等价于不排除)
     * @return 是否应显示在后台任务指示器中
     */
    public static boolean isBackgroundTask(BackgroundTaskStatus status, Boolean isBackgrounded) {
        if (status != BackgroundTaskStatus.RUNNING && status != BackgroundTaskStatus.PENDING) {
            return false;
        }
        if (isBackgrounded != null && !isBackgrounded) {
            return false;
        }
        return true;
    }
}
