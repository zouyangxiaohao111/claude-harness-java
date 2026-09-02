package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 队列出站事件（B5 · 对齐 CC 排队条 UI 驱动，queue-first 替代 cancel-first）。
 *
 * <p><b>STOMP topic</b>：{@code /topic/sessions/{sessionId}/queue}，前端排队条（QueuedCommandsBar）
 * 订阅。对齐 CC：排队消息立即显示为 composer 上方暗色排队条，注入成功后才转正式气泡。
 *
 * <p><b>载荷</b>：
 * <ul>
 *   <li>{@code queue.changed}：{type, sessionId, commands:[{uuid, content, mode, priority, isMeta}]}
 *       —— 入队/出队/清空后推快照（仅 busy 排队路径 + pop/cancel 时调用，空闲发送不闪排队框）</li>
 *   <li>{@code queue.drained}：{type, sessionId, drained:[{uuid, content, mode, streamTopic}],
 *       commands:[...剩余]} —— 消费 busy-queued 项时推（[streamTopic-session-level] drained[].streamTopic
 *       恒为会话级 {@code /topic/sessions/{sid}/stream}，前端已在会话 topic 单一订阅，仅 uuid+content
 *       供渲染 queued-user 气泡）</li>
 * </ul>
 *
 * <p>fail-soft：wsTemplate 缺失（无 WebSocket 场景）→ 跳过推送；命令过滤按 sessionId 精确匹配
 * （多会话隔离，防跨会话泄漏）。
 */
@Component
public class QueueEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(QueueEventPublisher.class);

    private final SimpMessagingTemplate wsTemplate;
    private final NotificationQueue notificationQueue;

    public QueueEventPublisher(SimpMessagingTemplate wsTemplate, NotificationQueue notificationQueue) {
        this.wsTemplate = wsTemplate;
        this.notificationQueue = notificationQueue;
    }

    /**
     * 入队/出队/清空后推快照（session 级可见）· 仅 B1 busy 路径 + pop/cancel 时调用，空闲发送不闪排队框。
     *
     * @param sessionId 目标会话（命令按 sessionId 精确过滤）
     */
    public void emitChanged(String sessionId) {
        if (wsTemplate == null || sessionId == null || sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("QueueEventPublisher.emitChanged: wsTemplate 缺失/sessionId 空，跳过 session={}", sessionId);
            }
            return;
        }
        List<Map<String, Object>> commands = notificationQueue.getCommandsByMaxPriority(null).stream()
            .filter(c -> sessionId.equals(c.sessionId()))
            .map(c -> Map.<String, Object>of(
                "uuid", c.uuid() != null ? c.uuid() : "",
                "content", c.value(),
                "mode", c.mode(),
                "priority", c.priority() != null ? c.priority().name().toLowerCase() : "next",
                "isMeta", c.isMeta()))
            .toList();
        wsTemplate.convertAndSend("/topic/sessions/" + sessionId + "/queue",
            Map.of("type", "queue.changed", "sessionId", sessionId, "commands", commands));
        if (log.isDebugEnabled()) {
            log.debug("QueueEventPublisher.emitChanged: session={} commands={}", sessionId, commands.size());
        }
    }

    /**
     * 消费 busy-queued 项时推 queue.drained（mid-turn drain / CronIdleExecutor turn 结束兜底路径共用）。
     *
     * <p>[streamTopic-session-level] drained[].streamTopic 恒为会话级 {@code /topic/sessions/{sid}/stream}
     * （对齐 CC 会话单一事件流；CC 无 queue.drained 订阅切换概念——排队命令消费后同会话单流继续，
     * queueProcessor.ts:52-87 仅处理队列生命周期，不产生新 topic）。前端已在会话 topic 单一订阅，
     * 无需携带 per-message 新订阅地址；drained[].uuid+content 仍用于渲染 queued-user 气泡。
     *
     * @param sessionId 目标会话
     * @param drained   已消费（出队）的 busy-queued 命令列表
     */
    public void emitDrained(String sessionId, List<NotificationQueue.QueueItem> drained) {
        if (wsTemplate == null || sessionId == null || sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("QueueEventPublisher.emitDrained: wsTemplate 缺失/sessionId 空，跳过 session={}", sessionId);
            }
            return;
        }
        String sessionStreamTopic = "/topic/sessions/" + sessionId + "/stream";
        List<Map<String, Object>> drainedPayload = drained.stream()
            .map(c -> Map.<String, Object>of(
                "uuid", c.uuid() != null ? c.uuid() : "",
                "content", c.value(),
                "mode", c.mode(),
                "streamTopic", sessionStreamTopic))
            .toList();
        List<Map<String, Object>> remaining = notificationQueue.getCommandsByMaxPriority(null).stream()
            .filter(c -> sessionId.equals(c.sessionId()))
            .map(c -> Map.<String, Object>of(
                "uuid", c.uuid() != null ? c.uuid() : "",
                "content", c.value(),
                "mode", c.mode(),
                "priority", c.priority() != null ? c.priority().name().toLowerCase() : "next",
                "isMeta", c.isMeta()))
            .toList();
        wsTemplate.convertAndSend("/topic/sessions/" + sessionId + "/queue",
            Map.of("type", "queue.drained", "sessionId", sessionId,
                "drained", drainedPayload, "commands", remaining));
        if (log.isDebugEnabled()) {
            log.debug("QueueEventPublisher.emitDrained: session={} drained={}", sessionId, drained.size());
        }
    }
}
