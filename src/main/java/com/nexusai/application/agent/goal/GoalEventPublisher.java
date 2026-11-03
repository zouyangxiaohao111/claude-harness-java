package com.nexusai.application.agent.goal;

import com.nexusai.domain.session.SessionGoal;
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

    public GoalEventPublisher(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
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
        wsTemplate.convertAndSend("/topic/sessions/" + sessionId + "/goal",
            java.util.Map.of(
                "type", "goal.changed",
                "sessionId", sessionId,
                "goal", java.util.Map.of(
                    "condition", goal.condition(),
                    "phase", goal.phase().name().toLowerCase(),
                    "iterations", goal.iterations(),
                    "setAt", goal.setAt(),
                    "tokensAtStart", goal.tokensAtStart(),
                    "lastReason", goal.lastReason() == null ? "" : goal.lastReason())));
        if (log.isDebugEnabled()) {
            log.debug("GoalEventPublisher.emitGoal: session={} phase={} iterations={}",
                sessionId, goal.phase(), goal.iterations());
        }
    }
}
