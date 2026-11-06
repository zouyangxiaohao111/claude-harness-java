package com.nexusai.application.agent.goal;

import com.nexusai.domain.session.SessionGoal;
import com.nexusai.domain.stats.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * [goal V253] 目标状态出站推送 · 会话级 STOMP topic
 * {@code /topic/sessions/{sessionId}/goal}。
 *
 * <p><b>形状照 {@code QueueEventPublisher}</b>（同款信封：{@code {type, sessionId, ...}}），
 * topic 命名照 {@code /topic/sessions/{sid}/queue} 的先例。
 *
 * <p><b>为什么前端不自己算</b>：目标的 phase / iterations / lastReason 全部由后端
 * （评估器 + GoalService）裁定，前端只负责展示。若前端根据「有没有收到 blocking」自己推断，
 * 就会重演 DSH 社区那个「activation 失真」的坑（进程内状态重启后 disarm，UI 直读会误判「在跑」）。
 *
 * @see GoalService
 */
@Component
public class GoalEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GoalEventPublisher.class);

    private final SimpMessagingTemplate wsTemplate;

    /**
     * 用量统计 · 只为算「目标期间真实消耗」（{@link StatsService#outputTokensSince}）。
     *
     * <p>{@code required=false}：非容器直构/单测未接线 → 载荷不带 {@code tokensUsed} 键
     * （前端据此不渲染该字段，⛔ 不显示 0）。
     */
    private final StatsService statsService;

    public GoalEventPublisher(SimpMessagingTemplate wsTemplate) {
        this(wsTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GoalEventPublisher(SimpMessagingTemplate wsTemplate,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              StatsService statsService) {
        this.wsTemplate = wsTemplate;
        this.statsService = statsService;
    }

    /**
     * 推送目标状态 · 载荷 = 整个 {@link SessionGoal}（前端据此直接渲染目标头）。
     *
     * @param sessionId 目标会话
     * @param goal      目标状态（null = 该会话无目标 → 前端清掉目标头）
     */
    public void emitGoal(String sessionId, SessionGoal goal) {
        if (wsTemplate == null || sessionId == null || sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("GoalEventPublisher.emitGoal: wsTemplate 缺失/sessionId 空，跳过 session={}", sessionId);
            }
            return;
        }
        if (goal == null) {
            wsTemplate.convertAndSend("/topic/sessions/" + sessionId + "/goal",
                java.util.Map.of("type", "goal.changed", "sessionId", sessionId, "goal", java.util.Map.of()));
            return;
        }
        // ⚠️ 用 HashMap 而不是 Map.of：tokensUsed 可能为 null（统计服务未接线 / 查询异常），
        //   Map.of 拒绝 null 值会直接抛 NPE ⇒ 整条推送丢失（前端永久停在旧态）。
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("condition", goal.condition());
        body.put("phase", goal.phase().name().toLowerCase());
        body.put("iterations", goal.iterations());
        body.put("setAt", goal.setAt());
        body.put("tokensAtStart", goal.tokensAtStart());
        body.put("lastReason", goal.lastReason() == null ? "" : goal.lastReason());
        // [B6 2026-11-04] 自动重试进度（纯展示）：已重试次数 + 上限。
        //   上限由后端常量给出，⛔ 前端不硬编码 3 —— 否则改常量时 UI 会骗人。
        body.put("retryCount", goal.retryCount() == null ? 0 : goal.retryCount());
        body.put("retryLimit", GoalCheckinConfig.MAX_AUTO_RETRIES);
        // 目标期间真实消耗的输出 token（与 GET /goal 同源同口径：llm_usage_calls 按 setAt 求和）。
        //   null ⇒ 不带该键 / 值为 null，前端不渲染（⛔ 不显示 0）。
        body.put("tokensUsed", tokensUsedSince(sessionId, goal));

        wsTemplate.convertAndSend("/topic/sessions/" + sessionId + "/goal",
            java.util.Map.of(
                "type", "goal.changed",
                "sessionId", sessionId,
                "goal", body));
        if (log.isDebugEnabled()) {
            log.debug("GoalEventPublisher.emitGoal: session={} phase={} iterations={} tokensUsed={}",
                sessionId, goal.phase(), goal.iterations(), body.get("tokensUsed"));
        }
    }

    /**
     * 目标期间真实消耗的输出 token · {@code null} = 取不到（未接线 / 异常）。
     *
     * <p>与 {@code SessionGoalController.tokensUsedSince} 同一口径、同一数据源 ——
     * 两条通道（REST 初始拉取 + STOMP 增量推送）必须给出同一个数字，否则前端会看到跳变。
     */
    private Long tokensUsedSince(String sessionId, SessionGoal goal) {
        if (statsService == null) {
            return null;
        }
        try {
            return statsService.outputTokensSince(sessionId, goal.setAt());
        } catch (Exception e) {
            log.warn("GoalEventPublisher.emitGoal: 目标消耗 token 聚合失败（该字段不产出）session={}: {}",
                sessionId, e.toString());
            return null;
        }
    }
}
