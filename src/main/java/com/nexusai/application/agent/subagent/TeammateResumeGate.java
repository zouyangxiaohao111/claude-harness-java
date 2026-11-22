package com.nexusai.application.agent.subagent;

import java.nio.file.Path;

/**
 * teammate 恢复的「用户已停止」门 · 对齐 CC 恢复门 A（claude.exe 2.1.328 偏移 333967189 /
 * 356171000 区）：
 *
 * <pre>(meta.stoppedByUser || 伴生文件 found || task.stoppedByUser) &amp;&amp; !userInitiated &amp;&amp; !lineage → k9</pre>
 *
 * <p>四变量在 CC 里的归属：
 * <ul>
 *   <li>{@code meta.stoppedByUser} —— {@code <agentId>.meta.json} 的字段（主 sidecar 回填）；</li>
 *   <li>{@strong 伴生文件} —— {@code <agentId>.stopped-by-user}（本仓 {@link AgentTranscript});</li>
 *   <li>{@code userInitiated} —— 用户本人经 REST /resume 发起（模型经 SendMessage 一律 false）；</li>
 *   <li>{@code lineage} —— 同一血统的续跑；CC {@code A = lineage !== undefined} 时<b>连伴生探测都跳过</b>
 *       （{@code he = ... {found:false}}），故本类在 lineage=true 时直接放行、不读盘。</li>
 * </ul>
 *
 * <p>被拒绝时的文案 = SendMessage 侧 k9 原文（{@link ResumeErrorCode#stoppedByUserSendText} /
 * {@link ResumeErrorCode#stoppedByUserText}），⛔ 不另行拼凑。
 *
 * <p>纯静态 + 无状态：便于用例直接锁「用户停过 → 拒」「lineage → 跳过探测」两条判据，
 * 不必启动 teammate 线程或 LLM。
 */
public final class TeammateResumeGate {

    private TeammateResumeGate() {
    }

    /**
     * 是否应拒绝本次恢复。
     *
     * @param sessionDir    会话目录根（可 null → 只查内存标记）
     * @param sessionId     会话 ID
     * @param agentId       teammate agentId（{@code name@team}）
     * @param userInitiated 用户显式发起（REST /resume）→ 放行
     * @param lineage       同一血统续跑 → 跳过探测（CC 连伴生文件都不读）
     * @param memoryMarkers 内存停止标记（{@link StopMarkers}，可 null）
     * @return true = 用户停过且本次不是用户/血统发起 ⇒ 拒绝
     */
    public static boolean refused(Path sessionDir, String sessionId, String agentId,
                                  boolean userInitiated, boolean lineage,
                                  StopMarkers memoryMarkers) {
        if (userInitiated || lineage) {
            return false;
        }
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        if (memoryMarkers != null && memoryMarkers.isStopped(agentId)) {
            return true;
        }
        return AgentTranscript.isStoppedByUserOrMeta(sessionDir, sessionId, agentId);
    }

    /**
     * 拒绝文案（允许 → null）。
     *
     * @return k9 原文（{@code Agent "x" was stopped by the user and was not resumed...}）或 null
     */
    public static String refusalText(Path sessionDir, String sessionId, String agentId,
                                     boolean userInitiated, boolean lineage,
                                     StopMarkers memoryMarkers) {
        return refusalText(sessionDir, sessionId, agentId, agentId, userInitiated, lineage,
            memoryMarkers);
    }

    /**
     * 拒绝文案（带独立显示名）· CC 的 {@code Ns(name)} 填的是<b>代理名</b>（模型写的收件名），
     * ⛔ 不是 {@code name@team} 内部 ID。
     *
     * @param displayName 文案中的名字（SendMessage 的 {@code to} 或 meta.name）
     */
    public static String refusalText(Path sessionDir, String sessionId, String agentId,
                                     String displayName, boolean userInitiated, boolean lineage,
                                     StopMarkers memoryMarkers) {
        return refused(sessionDir, sessionId, agentId, userInitiated, lineage, memoryMarkers)
            ? ResumeErrorCode.stoppedByUserSendText(
                displayName == null || displayName.isBlank() ? agentId : displayName)
            : null;
    }

    /**
     * 恢复成功后的<b>双清</b>：内存停止标记 + 磁盘伴生文件 + 主 meta 的 {@code stoppedByUser}
     * （对齐 CC {@code eKt()}：恢复成功后删除停止标记，否则之后的任何自动 resume 都会被同一个标记拦住）。
     *
     * @param sessionDir 会话目录根（可 null → 只清内存）
     * @param sessionId  会话 ID
     * @param agentId    teammate agentId（{@code name@team}）
     * @param markers    内存停止标记（可 null）
     */
    public static void clearAfterResume(Path sessionDir, String sessionId, String agentId,
                                        StopMarkers markers) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        if (markers != null) {
            markers.clear(agentId);
        }
        if (sessionDir != null && sessionId != null) {
            try {
                AgentTranscript.clearStoppedByUser(sessionDir, sessionId, agentId);
            } catch (Exception ignored) {
                // 清除失败不阻断本次成功投递（下次 resume 仍会被同一标记挡回，行为可见）
            }
        }
    }
}
