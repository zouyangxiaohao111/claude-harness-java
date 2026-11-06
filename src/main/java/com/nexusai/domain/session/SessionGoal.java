package com.nexusai.domain.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级目标状态 · 对齐 CC {@code activeGoal}（claude.exe v2.1.318 内
 * {@code activeGoal:{condition, iterations, setAt, tokensAtStart, lastReason, deferredSince, checkinCount}}）。
 *
 * <p><b>WHY 落列而不是纯内存</b>：CC 的 activeGoal 活在进程内存里（单进程 CLI）。
 * 本仓 Web 端每个 HTTP send 都是新的 {@code LlmAgentLoop}（prototype 作用域）
 * ⇒ 进程内存态活不过下一条用户消息 ⇒ 必须落到 {@code sessions.goal} 列
 * （multi-session-vs-cc-single-session 铁律：todos V43 / effort_level V31 /
 * disabled_tools V35 / team_context V40 / computer_use_grants V250 同款范式）。
 *
 * <p><b>字段语义（逐字对齐 CC v2.1.318）</b>：
 * <ul>
 *   <li>{@code condition} — 目标条件原文（CC 语义，上限 4000 字符）</li>
 *   <li>{@code phase} — active | paused | achieved | impossible。
 *       CC 只有「存在 / 不存在」两态（完成即清 activeGoal）—— 本仓额外留终态列是为了
 *       F5 后仍有权威状态可读（设计选择，非 bug）；{@code paused} 对齐 CC 撞 block-cap
 *       之后的 {@code kind:"pause"} 语义。</li>
 *   <li>{@code iterations} — 已评估轮数，<b>纯记账</b>。CC 的 goal 对象里<b>没有上限字段</b>
 *       （max-turns 是评估器的替代品不是补充）；我们同样不设轮数上限。</li>
 *   <li>{@code setAt} — 设定时刻（epoch 毫秒）。resume 恢复时<b>重置</b>（CC 同款：
 *       restoreGoalFromTranscript 重置 setAt / tokensAtStart 基线）。</li>
 *   <li>{@code tokensAtStart} — 设定时的<b>输出 token</b> 累计值。CC 口径是
 *       {@code DA()} = 各模型 outputTokens 之和，<b>不是总量</b>。</li>
 *   <li>{@code lastReason} — 最近一次评估器返回的 reason（CC 侧即注入下一轮的那句话）</li>
 *   <li>{@code deferredSince} — 后台任务门控开始延迟评估的时刻（epoch 毫秒）。
 *       CC original: {@code activeGoal.deferredSince}（Nn/Xs/Kp 三个定时器函数消费）。
 *       null = 当前未被延迟。</li>
 *   <li>{@code checkinCount} — 已注入的 check-in 次数（指数退避的指数）。
 *       CC original: {@code activeGoal.checkinCount}；退避 = {@code base * 2^min(count, 2)}。</li>
 *   <li>{@code lastDeferralPassAt} — 上次延迟判定通过的时刻，用于识别「后台任务换了一批」。
 *       CC original: {@code activeGoal.lastDeferralPassAt}。</li>
 *   <li>{@code retryAt} — 评估器报错后的下次重试时刻（epoch 毫秒）。
 *       CC original: 独立于 activeGoal 的 {@code goalInterruptionStreak} 排程
 *       （{@code $a()/Ka()} 用 setTimeout；本仓必须落列才跨请求存活）。
 *       null = 无待重试。</li>
 *   <li>{@code retryCount} — 已自动重试次数（决定 60/300/900 秒档位；上限 3）。
 *       CC original: {@code goalInterruptionStreak.retries}。</li>
 * </ul>
 *
 * <p>⚠️ <b>check-in 三字段与 retry 两字段刻意分开</b>：前者是「后台任务没跑完 → 定期
 * 提醒模型去看进度」，后者是「评估器自己炸了 → 稍后重判」。合并成一个计数器会让两种
 * 语义互相污染（CC 也是分开存的：前者在 {@code activeGoal}，后者在 sessionScratch）。
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
 * @param deferredSince  后台任务门控开始延迟评估的时刻（可 null）
 * @param checkinCount   check-in 注入次数（指数退避用）
 * @param lastDeferralPassAt 上次延迟判定时刻（可 null）
 * @param retryAt        评估器下次重试时刻（可 null）
 * @param retryCount     已自动重试次数（可 null）
 */
public record SessionGoal(
    String condition,
    Phase phase,
    int iterations,
    long setAt,
    long tokensAtStart,
    String lastReason,
    Long deferredSince,
    Integer checkinCount,
    Long lastDeferralPassAt,
    Long retryAt,
    Integer retryCount
) {

    /**
     * 6 参兼容构造器 · 承接「加了 check-in 三字段之前」的全部调用点。
     *
     * <p><b>WHY 用转发构造器而不是改所有调用点</b>：与 {@code GenericHook.HookResult}
     * 的 18 参转发构造器同一范式 —— 唯一真源仍是 record 的全部 11 个字段，转发构造器
     * 只让旧调用点不必立刻补五个 null（本仓 + 测试侧十余处）。这是纯追加，不是
     * 「破约留兼容壳」。
     *
     * @param condition     目标条件原文
     * @param phase         目标阶段
     * @param iterations    已评估轮数
     * @param setAt         设定时刻
     * @param tokensAtStart 设定时的输出 token 累计值
     * @param lastReason    最近一次评估 reason
     */
    public SessionGoal(String condition, Phase phase, int iterations, long setAt,
                       long tokensAtStart, String lastReason) {
        this(condition, phase, iterations, setAt, tokensAtStart, lastReason,
            null, null, null, null, null);
    }

    private static final Logger log = LoggerFactory.getLogger(SessionGoal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 目标阶段 · 4 态。
     *
     * <p>CC v2.1.318 侧 {@code activeGoal} 只有存在/不存在两态，paused 是通过
     * {@code kg()→bg("not_met")→{kind:"pause"}} 表达的行为（目标仍在，只是不再自动续跑）。
     * 本仓把它显式化为 phase 值，让前端能直接显示「已暂停 · 发消息继续」。
     */
    public enum Phase {
        /** 追之中 —— 挂着 Stop hook，评估器每轮判一次。 */
        ACTIVE,
        /** 已暂停 —— 评估器撞 block-cap / 遇到不可继续的外部状况；发消息即恢复。 */
        PAUSED,
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

    /** 是否处于暂停态（发消息可恢复 · 对齐 CC 撞 block-cap 后的 pause 语义）。 */
    public boolean isPaused() {
        return phase == Phase.PAUSED;
    }

    /** 是否仍在「可继续」的集合里（active 或 paused —— 两者都需要 Stop hook 参与后续判定）。 */
    public boolean isLive() {
        return phase == Phase.ACTIVE || phase == Phase.PAUSED;
    }

    /**
     * 序列化到 {@code sessions.goal} 列（JSON 串）。
     *
     * <p>check-in 三字段仅在非默认值时写出 —— 绝大多数目标没有后台延迟，
     * 无谓写三个 null/0 只会让列变长且难读。
     *
     * <p><b>⚠️ 写出的键必须覆盖 {@link #fromJson} 读取的全部键</b>（往返对称）。本方法曾漏写
     * {@code retryAt}/{@code retryCount}，而 {@code fromJson} 会读它们 ⇒
     * {@code GoalService.scheduleEvaluatorRetry} 写进列的排程<b>落盘即丢</b>，
     * 唯一消费者 {@code GoalCheckinScheduler} 读 DB 恒拿 null ⇒
     * 「评估器失败后 60/300/900 秒自动重试」整条链路从未生效过。
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
        if (goal.deferredSince() != null) {
            root.put("deferredSince", goal.deferredSince());
        }
        if (goal.checkinCount() != null && goal.checkinCount() > 0) {
            root.put("checkinCount", goal.checkinCount());
        }
        if (goal.lastDeferralPassAt() != null) {
            root.put("lastDeferralPassAt", goal.lastDeferralPassAt());
        }
        // 评估器重试排程 —— fromJson 会读这两个键，必须对称写出（见方法 javadoc 的缺口说明）。
        if (goal.retryAt() != null) {
            root.put("retryAt", goal.retryAt());
        }
        if (goal.retryCount() != null) {
            root.put("retryCount", goal.retryCount());
        }
        return root.toString();
    }

    /**
     * 解析 {@code sessions.goal} 列（fail-soft，对齐
     * {@code SessionService.getDisabledTools} / {@code TodoWriteTool.todosJsonToMap}
     * 的降级读法）。
     *
     * <p>null / 空白 / 解析失败 / 非对象 / 缺 condition → <b>null（= 无目标）</b>。
     * 缺 phase 视为 ACTIVE；缺 iterations/setAt/tokensAtStart 取默认值；
     * 缺 check-in 三字段视为「未被延迟」。
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
            Long deferredSince = root.hasNonNull("deferredSince")
                ? root.path("deferredSince").asLong() : null;
            Integer checkinCount = root.hasNonNull("checkinCount")
                ? root.path("checkinCount").asInt() : null;
            Long lastDeferralPassAt = root.hasNonNull("lastDeferralPassAt")
                ? root.path("lastDeferralPassAt").asLong() : null;
            Long retryAt = root.hasNonNull("retryAt")
                ? root.path("retryAt").asLong() : null;
            Integer retryCount = root.hasNonNull("retryCount")
                ? root.path("retryCount").asInt() : null;
            return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason,
                deferredSince, checkinCount, lastDeferralPassAt, retryAt, retryCount);
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
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, reason,
            deferredSince, checkinCount, lastDeferralPassAt, retryAt, retryCount);
    }

    /** 带新 phase 的副本。 */
    public SessionGoal withPhase(Phase newPhase) {
        return new SessionGoal(condition, newPhase, iterations, setAt, tokensAtStart, lastReason,
            deferredSince, checkinCount, lastDeferralPassAt, retryAt, retryCount);
    }

    /** 带新 iterations 的副本。 */
    public SessionGoal withIterations(int newIterations) {
        return new SessionGoal(condition, phase, newIterations, setAt, tokensAtStart, lastReason,
            deferredSince, checkinCount, lastDeferralPassAt, retryAt, retryCount);
    }

    /**
     * 带 check-in 状态的副本 · 对齐 CC {@code activeGoal} 的延迟评估三字段。
     *
     * <p><b>WHY 三个字段一起改</b>：CC 的 {@code Qa()} / {@code Wa()} 总是同时产出
     * deferredSince + checkinCount + lastDeferralPassAt 三件套（退避判定要同时读三者），
     * 分开 wither 迟早漏一个。
     *
     * @param newDeferredSince      延迟起点（null = 清除延迟态）
     * @param newCheckinCount       check-in 计数（null = 0）
     * @param newLastDeferralPassAt 上次延迟判定时刻（null = 无记录）
     */
    public SessionGoal withDeferral(Long newDeferredSince, Integer newCheckinCount,
                                    Long newLastDeferralPassAt) {
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason,
            newDeferredSince, newCheckinCount, newLastDeferralPassAt, retryAt, retryCount);
    }

    /**
     * 清除延迟态（后台工作归位 / 目标恢复推进时用）。
     *
     * <p>对齐 CC：{@code else if(j.deferredSince !== void 0){ let{deferredSince:Oe,
     * checkinCount:ve, lastDeferralPassAt:tt,...uo}=j; yield{type:"active_goal",value:uo} }}
     * —— 后台任务没了就把三字段整体摘掉。
     */
    public SessionGoal clearDeferral() {
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason,
            null, null, null, retryAt, retryCount);
    }
    /**
     * 带评估器重试排程的副本 · 对齐 CC 的 {@code goalInterruptionStreak}
     * （{@code $a()/Ka()} 用 setTimeout 排下一次；本仓落列才跨请求存活）。
     *
     * <p>⚠️ <b>与 {@link #withDeferral} 是两个独立语义</b>：check-in 是「后台任务没跑完 →
     * 定期提醒模型去看进度」；重试是「评估器自己炸了 → 稍后重判」。合并成一个计数器会让
     * 两种语义互相污染（CC 也是分开存的）。
     *
     * @param newRetryAt    下次重试时刻（null = 无待重试）
     * @param newRetryCount 已重试次数（null = 0）
     */
    public SessionGoal withRetry(Long newRetryAt, Integer newRetryCount) {
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason,
            deferredSince, checkinCount, lastDeferralPassAt, newRetryAt, newRetryCount);
    }

    /**
     * 消费一次重试排程：<b>只清 {@code retryAt}，保留 {@code retryCount}</b>。
     *
     * <p>⚠️ <b>绝不能把 retryCount 一起清掉</b>（本方法前一版就是错的）：
     * <pre>
     * 评估器失败 → retryAt=now+60s, retryCount=1
     *   ↓ 60 秒后定时器到点
     * 消费 → 若 retryCount 归 null ⇒ 下次失败读 attempts=0
     *   ↓
     * 永远到不了上限 3 ⇒ <b>无限重试</b>，且每次都从 60 秒档重来
     * （即「永久性失败 + 守卫复位 = 重试风暴」）
     * </pre>
     *
     * <p>{@code retryCount} 的归零点只有一个：<b>评估器终于成功判定了</b>
     * （{@code recordAchieved} / {@code recordImpossible} / {@code recordNotMet} 都会
     * 走到 {@link #clearRetryFully()}）。
     *
     * @return 已清 retryAt、保留 retryCount 的副本
     */
    public SessionGoal consumeRetry() {
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason,
            deferredSince, checkinCount, lastDeferralPassAt, null, retryCount);
    }

    /**
     * 清除重试排程与计数 —— <b>只在「这次判定真的出了结果」时调用</b>。
     *
     * <p>调用点（都是「评估器成功给出 met / not_met / impossible」的路径）：
     * {@link #withReason} 等的上游由 {@code GoalService.consumeStopHookResult} 统一调；
     * 以及目标被清空 / 用户手动改目标时。
     */
    public SessionGoal clearRetryFully() {
        return new SessionGoal(condition, phase, iterations, setAt, tokensAtStart, lastReason,
            deferredSince, checkinCount, lastDeferralPassAt, null, null);
    }
}
