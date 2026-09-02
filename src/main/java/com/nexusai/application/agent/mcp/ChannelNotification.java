package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP channel inbound notifications · 对齐 CC services/mcp/channelNotification.ts。
 *
 * <p>[impl-I-3 T2 / Q-35 / S07] 入站链路（useManageMCPConnections.ts:507-530）：
 * gateChannelServer 返回 register → 注册 {@code notifications/claude/channel} handler →
 * 收到 {@code {content, meta}} → {@link ChannelNotificationGate#wrapChannelMessage} 包裹
 * {@code <channel>} tag → 入队 {@link NotificationQueue}（<b>mode=prompt / priority=next /
 * isMeta=true / origin={kind:'channel',server} / skipSlashCommands=true</b>，对齐 CC enqueue
 * L523-530 origin L528）→ LlmAgentLoop drainForQuery（:2589）主线程消费，按 origin.kind
 * 判别注入 untrusted 分支（messages.ts:5505-5506，「非用户」语义，绝不落入 human 分支）。
 */
@Component
public class ChannelNotification {

    private static final Logger log = LoggerFactory.getLogger(ChannelNotification.class);

    /** CC CHANNEL_TAG = 'channel'（constants/xml.ts:56）。 */
    public static final String CHANNEL_TAG = "channel";
    /** CC ChannelMessageNotificationSchema.method（channelNotification.ts:39）。 */
    public static final String NOTIFICATION_METHOD = "notifications/claude/channel";

    private NotificationQueue queue;
    private ChannelNotificationGate gate;

    @Autowired
    public ChannelNotification(NotificationQueue queue, ChannelNotificationGate gate) {
        this.queue = queue;
        this.gate = gate;
    }

    /** 测试构造：queue/gate 可 null（gate null → 惰性兜底实例；queue null → 入队 no-op + warn）。 */
    ChannelNotification(NotificationQueue queue) {
        this.queue = queue;
        this.gate = null;
    }

    public void setQueue(NotificationQueue queue) {
        this.queue = queue;
    }

    public void setGate(ChannelNotificationGate gate) {
        this.gate = gate;
    }

    /**
     * 入站 channel 通知主链 · CC handler 真源（useManageMCPConnections.ts:518-520
     * {@code const { content, meta } = notification.params; enqueue({...})}）。
     *
     * <p>handler 注册仅针对 {@link #NOTIFICATION_METHOD}（method 不匹配天然不触发，故此处
     * 无显式 method 校验）；null/非文本 content → reject；否则 wrap + enqueue。
     *
     * @param serverName 通知来源 server（handler 注册时闭包捕获 · CC client.name）
     * @param params     notification.params（{@code {content, meta}}，meta 可选 opaque passthrough）
     * @return true = 已包裹并入队；false = reject（content 缺失 / 队列未接线丢弃）
     */
    public boolean receiveNotification(String serverName, JsonNode params) {
        // Phase 4 (cron-notify): 无 sessionId 的既有入口 → 委托 3 参（sessionId=null 回落全局）。
        return receiveNotification(serverName, params, null);
    }

    /**
     * 入站 channel 通知主链（带创建会话 sessionId）· Phase 4 (cron-notify)。
     *
     * <p><b>WHY（规则九）</b>: CC 中 channel 消息经 {@code enqueue({mode:'prompt', ...})} 注入
     * <b>当前会话</b>队列（useManageMCPConnections.ts:523-530，无 agentId=主会话 ambient）；
     * Java 多会话下 McpToolPool 连接建立于某会话上下文（doConnectTransport MDC 回放），
     * 该 sessionId 即「channel 关联会话」——入队时透传 {@code QueueItem.sessionId}，
     * drain 3a 注入对应会话回合（会话活跃时），空闲由 CronIdleExecutor 代跑，结束回落全局。
     *
     * @param serverName 通知来源 server（handler 注册时闭包捕获 · CC client.name）
     * @param params     notification.params（{@code {content, meta}}，meta 可选 opaque passthrough）
     * @param sessionId  创建 channel 会话 sessionId（handler 注册时捕获；null → 回落全局）
     * @return true = 已包裹并入队；false = reject（content 缺失 / 队列未接线丢弃）
     */
    public boolean receiveNotification(String serverName, JsonNode params, @Nullable String sessionId) {
        if (params == null || params.isNull() || !params.isObject()) {
            log.warn("[ChannelNotification] 入站通知参数为空/非对象, 丢弃 server={}", serverName);
            return false;
        }
        JsonNode contentNode = params.path("content");
        if (contentNode.isMissingNode() || contentNode.isNull() || !contentNode.isTextual()) {
            log.warn("[ChannelNotification] 入站通知 content 缺失或非字符串, 丢弃 server={}", serverName);
            return false;
        }
        String content = contentNode.asText();
        Map<String, String> meta = parseMeta(params.path("meta"));

        ChannelNotificationGate g = effectiveGate();
        String wrapped = g.wrapChannelMessage(serverName, content, meta);
        enqueue(wrapped, serverName, sessionId);
        if (log.isDebugEnabled()) {
            log.debug("[ChannelNotification] 入站 channel 消息已入队 server={} contentLen={} metaKeys={} sessionId={}",
                serverName, content.length(), meta.size(), sessionId);
        }
        return true;
    }

    /** 解析 {@code meta}（CC z.record(z.string(), z.string()).optional()，channelNotification.ts:44）。 */
    private static Map<String, String> parseMeta(JsonNode metaNode) {
        if (metaNode == null || !metaNode.isObject()) {
            return Map.of();
        }
        Map<String, String> meta = new LinkedHashMap<>();
        metaNode.fields().forEachRemaining(e -> {
            if (e.getValue() != null && e.getValue().isTextual()) {
                meta.put(e.getKey(), e.getValue().asText());
            }
        });
        return meta;
    }

    /** 兜底 gate：仅需要 wrapChannelMessage（SAFE_META_KEY + escapeXmlAttr），门控字段不参与。 */
    private ChannelNotificationGate effectiveGate() {
        if (gate != null) {
            return gate;
        }
        // 仅测试路径（构造未注入 gate）——生产 @Autowired 恒注入
        return new ChannelNotificationGate(() -> false, List::of, List::of, ChannelNotificationGate::escapeXmlAttr);
    }

    /**
     * 入队 · CC enqueue（useManageMCPConnections.ts:523-530）：
     * {@code {mode:'prompt', priority:'next', isMeta:true, origin:{kind:'channel',server}, skipSlashCommands:true}}。
     * [S07] origin 字段（{@link NotificationQueue.MessageOrigin}）承载来源 server —— CC
     * messages.ts:3742-3746 全链透传，消费侧 wrapCommandText（messages.ts:5505-5506）按
     * origin.kind 判别 channel 分支（untrusted 注入文本生成「A message arrived from {server}」）；
     * skipSlashCommands=true → 以 {@code /} 开头的 content 也按纯文本送模型（不触发 slash 命令）。
     */
    private void enqueue(String wrapped, String serverName) {
        // Phase 4 (cron-notify): 无 sessionId 的既有入口 → 委托 3 参（sessionId=null 回落全局）。
        enqueue(wrapped, serverName, null);
    }

    private void enqueue(String wrapped, String serverName, @Nullable String sessionId) {
        if (queue == null) {
            log.warn("[ChannelNotification] 未接线 NotificationQueue, 入站 channel 消息丢弃 wrappedLen={}", wrapped.length());
            return;
        }
        // Phase 4 (cron-notify): sessionId 透传（channel 关联会话，drain 3a 注入对应会话回合）。
        queue.enqueue(new NotificationQueue.QueueItem(
            wrapped, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, null, true, null, true,
            new NotificationQueue.MessageOrigin("channel", serverName), sessionId));
        if (log.isInfoEnabled()) {
            log.info("[ChannelNotification] 入站 channel 消息入队 NotificationQueue " +
                    "(mode=prompt, isMeta=true, origin={channel,server={}}, skipSlashCommands=true, sessionId={})",
                serverName, sessionId);
        }
    }
}
