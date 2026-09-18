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
 *   <li>{@code queue.drained}：{type, sessionId, drained:[{uuid, content, mode, streamTopic,
 *       userAttachments?}], commands:[...剩余]} —— 消费 busy-queued 项时推（[streamTopic-session-level]
 *       drained[].streamTopic 恒为会话级 {@code /topic/sessions/{sid}/stream}，前端已在会话 topic
 *       单一订阅，uuid+content 供渲染 queued-user 气泡）
 *       <p>[busy 气泡附件胶囊] {@code drained[].userAttachments} = 该排队消息的<b>非图片附件快照</b>
 *       （{type, filename, mediaType, contentId, url}），仅在队列项携带快照时出站（key 缺失 = 无附件）。
 *       <b>WHY</b>：busy 消息的气泡由前端在 drained 时点 append —— 原载荷只有 uuid+content ⇒ 气泡
 *       <b>没有附件胶囊</b>，用户必须按 F5（读侧 {@code GET /messages} 出站 user_attachments）才看得到
 *       刚发的 Word/Excel/视频。数据一直在队列项里，只是没出站 ⇒ 本字段把它带上。
 *       ⛔ 权威源必须是后端快照（{@code QueueItem.userAttachments}，即 resolveAttachments 之后的
 *       <b>已解析列表</b>）：path 附件在<b>原始请求体</b>里只有 path、没有 contentId ⇒ 前端自算必得
 *       url=null（点了没反应），且与 F5 后两态不一致。</li>
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
            .map(c -> {
                // [busy 气泡附件胶囊] 用 LinkedHashMap 而非 Map.of：userAttachments 可空
                //   （纯文本 busy / 非 busy workload）⇒ Map.of 不收 null 值（NPE）。恒带四键；
                //   快照仅在非空时出站（前端「缺键 = 无快照」，不伪造空数组）。
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("uuid", c.uuid() != null ? c.uuid() : "");
                m.put("content", c.value());
                m.put("mode", c.mode());
                m.put("streamTopic", sessionStreamTopic);
                // ⭐ 权威源：队列项里**已有**非图片附件快照（ChatService.enqueueBusyPrompt 第 14 参，
                //   取自 resolveAttachments 之后的**已解析列表** ⇒ path 附件已带 contentId）。
                //   url 复用 MessageService.resolveAttachmentUrls 这**唯一投影**（与 GET /messages 读侧
                //   同规则、同形状）⇒ live 气泡胶囊与 F5 重拉后的胶囊**逐字段一致**（含可点预览 url）。
                //   ⛔ 曾评估「前端按原始请求体自算」方案：path 附件在请求体里只有 path、无 contentId
                //   ⇒ 自算必得 contentId=null/url=null（点了没反应），且与 F5 后两态不一致 —— 已否决。
                if (c.userAttachments() != null && !c.userAttachments().isEmpty()) {
                    m.put("userAttachments",
                        com.nexusai.domain.session.MessageService.resolveAttachmentUrls(
                            c.userAttachments(), sessionId));
                }
                return m;
            })
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
            // [busy 气泡附件胶囊] 数据流观测：出站了几条快照、每条几个附件（前端 live 气泡胶囊的来源）
            log.debug("QueueEventPublisher.emitDrained: session={} drained={} 附件快照条数={} 明细={}",
                sessionId, drained.size(),
                drainedPayload.stream().filter(m -> m.containsKey("userAttachments")).count(),
                drainedPayload.stream()
                    .map(m -> m.get("uuid") + ":"
                        + (m.containsKey("userAttachments") ? "有附件" : "无附件"))
                    .collect(java.util.stream.Collectors.joining(",")));
        }
    }
}
