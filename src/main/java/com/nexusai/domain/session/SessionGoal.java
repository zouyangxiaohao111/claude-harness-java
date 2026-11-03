package com.nexusai.domain.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级目标状态 · 对齐 CC {@code activeGoal}（claude.exe v2.1.212 内
 * {@code activeGoal:{condition, iterations, setAt, tokensAtStart}}）。
 *
 * <p><b>WHY 落列而不是纯内存</b>：CC 的 activeGoal 活在进程内存里（单进程 CLI）。
 * 本仓 Web 端每个 HTTP send 都是新的 {@code LlmAgentLoop}（prototype 作用域）
 * ⇒ 进程内存态活不过下一条用户消息 ⇒ 必须落到 {@code sessions.goal} 列
 * （multi-session-vs-cc-single-session 铁律：todos V43 / effort_level V31 /
 * disabled_tools V35 / team_context V40 / computer_use_grants V250 同款范式）。
 *
 * <p><b>字段语义（逐字对齐 CC）</b>：
 * <ul>
 *   <li>{@code condition} — 目标条件原文（CC 语义，上限 4000 字符）</li>
 *   <li>{@code phase} — active | achieved | impossible。
 *       ⚠️ <b>没有 DSH 的 paused/blocked</b>：DSH 有 5 态是因为它**没有评估器**，
 *       只能靠机械状态机判断「这轮该不该跑」；我们照 CC 用独立评估器判停，
 *       结局只有 met / not-met / impossible 三种。</li>
 *   <li>{@code iterations} — 已评估轮数，<b>纯记账</b>。CC 的 goal 对象里**没有上限字段**
 *       （max-turns 是评估器的替代品不是补充）；我们同样不设轮数上限。</li>
 *   <li>{@code setAt} — 设定时刻（epoch 毫秒）。resume 恢复时<b>重置</b>（CC 同款：
 *       restoreGoalFromTranscript 重置 setAt / tokensAtStart 基线）。</li>
 *   <li>{@code tokensAtStart} — 设定时的<b>输出 token</b> 累计值。CC 口径是
 *       {@code DA()} = 各模型 outputTokens 之和，<b>不是总量</b>。</li>
 *   <li>{@code lastReason} — 最近一次评估器返回的 reason（CC 侧即注入下一轮的那句话）</li>
 * </ul>
 *
 * <p><b>本 record 只存「当前快照」，不存历史</b>。历史/审计在 messages 表的
 * {@code goal_status} 附件里（transcript 本来就 append-only）。
 *
 * @param condition      目标条件原文
 * @param phase          目标阶段
 * @param iterations     已评估轮数（纯记账）
 * @param setAt          设定时刻（epoch 毫秒）
 * @param tokensAtStart  设定时的输出 token 累计值
 * @param lastReason     最近一次评估 reason（可 null）
 */
public record SessionGoal(
    String condition,
    Phase phase,
    int iterations,
    long setAt,
    long tokensAtStart,
    String lastReason
) {

    private static final Logger log = LoggerFactory.getLogger(SessionGoal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 目标阶段 · 3 态（照 CC 的三种结局，不引入 DSH 的 paused/blocked）。
     */
    public enum Phase {
        /** 追之中 —— 挂着 Stop hook，评估器每轮判一次。 */
        ACTIVE,
        /** 已达成 —— 评估器判 met，目标自动清除（此态仅用于「刚达成」的那一次推送/落档）。 */
        ACHIEVED,
        /** 无法达成 —— 评估器判 impossible，目标停止推进，等用户处理（不自动消失）。 */
        IMPOSSIBLE
    }

    /**
     * 条件长度上限 · 对齐 CC {@code bor=4000}（claude.exe v2.1.212
     * {@code var bor=4000}，超长直接拒绝设置并回报实际长度）。
     */
    public static final int MAX_CONDITION_LENGTH = 4000;

    public SessionGoal {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("goal condition 不能为空");
        }
        if (phase == null) {
            phase = Phase.ACTIVE;
        }
        if (iterations < 0) {
            iterations = 0;
        }
    }

    /** 是否仍在推进中。 */
    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    /**
     * 序列化到 {@code sessions.goal} 列（JSON 串）。
     *
     * @return JSON 串；null 入参 → null（列存 null = 该会话无目标）
     */
    public static String toJson(SessionGoal goal) {
        if (goal == null) {
            return null;
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("condition", goal.condition());
        root.put("phase", goal.phase().name().toLowerCase());
        root.put("iterations", goal.iterations());
        root.put("setAt", goal.setAt());
        root.put("tokensAtStart", goal.tokensAtStart());
        if (goal.lastReason() != null) {
            root.put("lastReason", goal.lastReason());
        }
        return root.toString();
    }

    /**
     * 解析 {@code sessions.goal} 列（fail-soft，对齐
     * {@code SessionService.getDisabledTools} / {@code TodoWriteTool.todosJsonToMap}
     * 的降级读法）。
     *
     * <p>null / 空白 / 解析失败 / 非对象 / 缺 condition → <b>null（= 无目标）</b>。
     * 缺 phase 视为 ACTIVE；缺 iterations/setAt/tokensAtStart 取默认值。
     *
     * @param json sessions.goal 列 JSON 串（可 null/空白）
     * @return 目标状态；无效 → null
     */
    public static SessionGoal fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            String condition = root.path("condition").asText(null);
            if (condition == null || condition.isBlank()) {
                return null;
            }
            Phase phase = parsePhase(root.path("phase").asText(null));
            int iterations = root.path("iterations").asInt(0);
            long setAt = root.path("setAt").asLong(0L);
            long tokensAtStart = root.path("tokensAtStart").asLong(0L);
            String lastReason = root.path("lastReason").asText(null);
            return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason);
        } catch (Exception e) {
            log.warn("SessionGoal.fromJson 解析失败（fail-soft 视为无目标）: {}", e.getMessage());
            return null;
        }
    }

    /** phase 字符串 → 枚举（未知值 → ACTIVE，fail-soft）。 */
    private static Phase parsePhase(String raw) {
        if (raw == null || raw.isBlank()) {
            return Phase.ACTIVE;
        }
        try {
            return Phase.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("SessionGoal 未知 phase '{}'，回落 ACTIVE", raw);
            return Phase.ACTIVE;
        }
    }

    /** 带新 reason 的副本（评估未达成时更新 lastReason）。 */
    public SessionGoal withReason(String reason) {
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, reason);
    }

    /** 带新 phase 的副本。 */
    public SessionGoal withPhase(Phase newPhase) {
        return new SessionGoal(condition, newPhase, iterations, setAt, tokensAtStart, lastReason);
    }

    /** 带新 iterations 的副本。 */
    public SessionGoal withIterations(int newIterations) {
        return new SessionGoal(condition, phase, newIterations, setAt, tokensAtStart, lastReason);
    }
}
