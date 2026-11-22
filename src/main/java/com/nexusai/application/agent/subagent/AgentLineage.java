package com.nexusai.application.agent.subagent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 同一血统判定 · 对齐 CC 恢复门的 lineage（claude.exe 2.1.328 偏移 333967189）。
 *
 * <p>CC 真源（读字节）：{@code Gt({..., lineage:L})} 里 {@code let A = L !== void 0}
 * ⇒ 存在即真；值是 {@code lineage:new Set([...L??[],e])}（偏移 333985267，恢复 fork 父代理
 * 时把已走过的 agent 链传下去，并靠 {@code L?.has(Te)!==!0} 防环）。为真时 CC 连磁盘
 * stoppedByUser 探测都跳过，k9 不再抛。
 *
 * <p><b>语义收窄（对齐 CC 的唯一生产来源）</b>：lineage 只来自「恢复一个 fork 子代理时，
 * 先<b>递归恢复它的父</b>，把本次级联已走过的 agent 集合传下去」。即只有
 * <b>后代发起 → 恢复其祖先</b> 才是 lineage；<b>父恢复子不是 lineage</b>（CC 里父级恢复
 * 子代理时没有这条集合）。
 *
 * <p>本仓没有「fork 父代理递归恢复」这条链，故生产触发点等价取「目标在发起方的祖先链上」：
 * 子代理关系来自会话用量账本的 parentAgentId（spawn 写入、跨 turn 由 usage_tree_json 恢复），
 * teammate 关系来自 team config 的 leadAgentId（member 是后代、lead 是祖先）。
 * 工具入参一律不参与判定。
 */
public final class AgentLineage {

    /** 祖先链最大步数（防脏数据成环/超长链）。 */
    static final int MAX_DEPTH = 16;

    private AgentLineage() {
    }

    /**
     * 发起方是否目标的<b>后代</b>（目标在发起方的祖先链上）。
     *
     * <p>方向对齐 CC：只有「后代发起、恢复其祖先」为真；父恢复子 / 兄弟 / 无关方一律 false
     * （父级恢复子代理不是 lineage，不能借此旁路 k9）。
     *
     * @param initiatorAgentId 发起方 agentId（可为 null/空白 = 主会话 → 视作根，恒不是后代）
     * @param targetAgentId    被恢复的 agentId
     * @param parentOf         agentId -&gt; 父 agentId（无则 null）
     * @return true = 目标是发起方的祖先；自身对自身 / 任一侧空 / 反向 -&gt; false
     */
    public static boolean linked(String initiatorAgentId, String targetAgentId,
                                 Function<String, String> parentOf) {
        if (initiatorAgentId == null || initiatorAgentId.isBlank()
                || targetAgentId == null || targetAgentId.isBlank()
                || parentOf == null) {
            return false;
        }
        if (initiatorAgentId.equals(targetAgentId)) {
            // CC 递归点同款排除：Te!==e（自身不是自己的血统）
            return false;
        }
        return reaches(initiatorAgentId, targetAgentId, parentOf);
    }

    /** 从 from 沿父链上溯，能否到达 to。 */
    private static boolean reaches(String from, String to, Function<String, String> parentOf) {
        Set<String> seen = new HashSet<>();
        String cur = from;
        for (int i = 0; i < MAX_DEPTH; i++) {
            String parent = parentOf.apply(cur);
            if (parent == null || parent.isBlank() || !seen.add(parent)) {
                return false;
            }
            if (parent.equals(to)) {
                return true;
            }
            cur = parent;
        }
        return false;
    }

    /**
     * teammate 血统判定 · lead 是每个成员的父，因此<b>只有 member → lead（后代恢复祖先）为真</b>；
     * lead → member（父恢复子）与成员之间（兄弟）一律 false。
     *
     * @param initiatorAgentId 发起方完整 agentId（name@team）；null/空白 = 主会话（即 lead）
     * @param targetAgentId    目标完整 agentId（name@team）
     * @param leadAgentId      team config 的 lead agentId
     * @return true = 成员（后代）恢复 lead（祖先）
     */
    public static boolean teammateLinked(String initiatorAgentId, String targetAgentId,
                                         String leadAgentId) {
        if (targetAgentId == null || targetAgentId.isBlank()
                || leadAgentId == null || leadAgentId.isBlank()) {
            return false;
        }
        if (targetAgentId.equals(leadAgentId)) {
            // 目标是 lead：只有「有 teammate 身份且不是 lead 本人」的成员发起才算后代发起
            return initiatorAgentId != null && !initiatorAgentId.isBlank()
                && !initiatorAgentId.equals(leadAgentId);
        }
        // 目标不是 lead（成员）：lead / 主会话 / 兄弟发起都不是「后代发起祖先」
        return false;
    }
}
