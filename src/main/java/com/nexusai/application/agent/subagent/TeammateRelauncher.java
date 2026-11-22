package com.nexusai.application.agent.subagent;

import java.util.List;

/**
 * 逐出 teammate 的 in-process 重拉缝 · 对齐 CC {@code resumeInProcessTeammate}
 * （claude.exe 2.1.328 偏移 356099332：{@code rwe} 读 transcript → 同一变形链 →
 * {@code spawnInProcessTeammate({resumableAgentId, resumeMessages})} 重新拉起并回写 team 文件）。
 *
 * <p>CC 的 agent-evicted 分支先经 {@code Qt()}（偏移 356127769）分流：
 * <ul>
 *   <li>sidecar 读不到 → {@code "unreadable"}（拒，{@code cSa} 文案）；</li>
 *   <li>无 {@code agentType} → {@code "unknown"}（拒，{@code dSa} 文案）；</li>
 *   <li>{@code taskKind === undefined} → {@code "subagent"}（走 agent-stopped 同一条 resume）；</li>
 *   <li>有 {@code taskKind} → {@code "teammate"}（走本接口重拉）。</li>
 * </ul>
 *
 * <p><b>历史重放</b>：CC 的 {@code Ms(...)} 返回 {@code resumedMessageCount}，文案
 * {@code resumed it as an in-process teammate with N prior messages}（偏移 356176932）。
 * 本接口因此把 transcript 重放列表作为显式入参，并把实际条数随结果返回 —— 文案里的 N
 * 必须是<b>真正传下去的那一份</b>的长度，⛔ 不由调用方另行估算。
 */
public interface TeammateRelauncher {

    /**
     * 重拉结果。
     *
     * @param success             是否成功拉起
     * @param agentId             成功时的 agentId（{@code name@team}）
     * @param resumedMessageCount 实际重放的 transcript 消息条数（失败 → 0）
     * @param errorClass          失败分档：{@code session_full} / {@code resume_failed}（成功 → null）
     * @param errorMessage        失败原因（成功 → null）
     */
    record TeammateRelaunchResult(boolean success, String agentId, int resumedMessageCount,
                                  String errorClass, String errorMessage) {
        public static TeammateRelaunchResult resumed(String agentId, int resumedMessageCount) {
            return new TeammateRelaunchResult(true, agentId,
                Math.max(0, resumedMessageCount), null, null);
        }

        public static TeammateRelaunchResult failed(String errorClass, String errorMessage) {
            return new TeammateRelaunchResult(false, null, 0, errorClass, errorMessage);
        }
    }

    /**
     * 重新拉起一个 in-process teammate。
     *
     * @param agentName       原 teammate 名（{@code <agentName>@<teamName>} 的本地名部分）
     * @param teamName        team 名
     * @param parentSessionId Leader 会话 ID
     * @param agentType       原 agent 类型（可空）
     * @param prompt          本次要投递的消息（作为新一轮 prompt）
     * @param resumeMessages  从 transcript 读出的历史（可为空 = {@code (no prior transcript)}）
     * @return 重拉结果（含实际重放条数 / 失败分档）
     */
    TeammateRelaunchResult relaunch(String agentName, String teamName, String parentSessionId,
                                    String agentType, String prompt,
                                    List<AgentMessage> resumeMessages);
}
