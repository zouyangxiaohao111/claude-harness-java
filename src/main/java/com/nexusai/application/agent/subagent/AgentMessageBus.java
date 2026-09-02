package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.infra.util.SwarmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * s15 Agent MessageBus · s17 P1-2 idle_poll 依赖 · 对齐 CC utils/swarm/inProcessRunner.ts
 *   (CC 无 services/team 目录下的消息总线文件, 原注释误述对齐对象, 见探查 mailbox-inprocess P3-3).
 *
 * <p>CC inProcessRunner 是进程内 teammate 轮询模型 (lead ↔ teammate). 教学版简化:
 * <ul>
 *   <li>单进程内 in-memory mailbox (无跨进程/网络)</li>
 *   <li>每个 agent 一条 inbox (Deque&lt;InboxMessage&gt;)</li>
 *   <li>send() 推入收件人 inbox, receive() 拉取</li>
 *   <li>poll() 带 timeout 非阻塞检查</li>
 * </ul>
 *
 * <p>本类解锁 s17 P1-2 (idle_poll 5s 轮询 inbox + task) + P1-5 (idle_notification 发送).
 *
 * <h2>CC inProcessRunner.ts 锚点</h2>
 * <pre>
 *   sendToAgent(targetId, message)        → inbox.push(message)   (inProcessRunner.ts:743 轮询模型)
 *   receiveFromAgent(agentId)             → inbox.shift() (FIFO)
 *   pollInbox(agentId, timeoutMs)         → await message 或 timeout
 *   broadcastToAll(message)                → 所有 inbox
 * </pre>
 */
@Component
public class AgentMessageBus {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageBus.class);

    /** 收件人 → 消息队列 (线程安全, ConcurrentHashMap + 内部锁) */
    private final Map<String, Deque<InboxMessage>> inboxes = new ConcurrentHashMap<>();

    /**
     * s17-P1-2/5: 发送消息到指定 agent inbox.
     *
     * <p>对齐 CC inProcessRunner.ts:743 poll-based readMailbox 的 Java 侧 inbox 模型
     * (send 写队列 + 阻塞 receive 消费).
     * inbox 不存在时自动创建.
     */
    public void sendToAgent(String agentId, InboxMessage message) {
        if (agentId == null || message == null) return;
        Deque<InboxMessage> inbox = inboxes.computeIfAbsent(agentId, k -> new ArrayDeque<>());
        synchronized (inbox) {
            inbox.addLast(message);
        }
        log.info("[AgentMessageBus] sendToAgent agent={} type={} payload={}",
            agentId, message.type(), truncate(message.payload(), 50));
    }

    /**
     * s17-P1-2: 非阻塞拉取一条消息 (FIFO).
     *
     * @return Optional.empty() 表示无消息
     */
    public Optional<InboxMessage> receiveFromAgent(String agentId) {
        if (agentId == null) return Optional.empty();
        Deque<InboxMessage> inbox = inboxes.get(agentId);
        if (inbox == null) return Optional.empty();
        synchronized (inbox) {
            return Optional.ofNullable(inbox.pollFirst());
        }
    }

    /**
     * s17-P1-2: 阻塞轮询 (直到有消息或 timeout).
     *
     * <p>实现: 每 100ms 检查一次 inbox, 最多 timeoutMs 毫秒.
     * 返回 Optional.empty() 表示 timeout 内无消息.
     */
    public Optional<InboxMessage> pollInbox(String agentId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<InboxMessage> msg = receiveFromAgent(agentId);
            if (msg.isPresent()) return msg;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * s17-P1-5: 广播消息到所有 inbox.
     */
    public void broadcastToAll(InboxMessage message) {
        if (message == null) return;
        for (String agentId : List.copyOf(inboxes.keySet())) {
            sendToAgent(agentId, message);
        }
    }

    /**
     * 当前 inbox 数量 (用于监控).
     */
    public int inboxCount() {
        return inboxes.size();
    }

    /**
     * 指定 agent 当前未读消息数.
     */
    public int size(String agentId) {
        if (agentId == null) return 0;
        Deque<InboxMessage> inbox = inboxes.get(agentId);
        if (inbox == null) return 0;
        synchronized (inbox) {
            return inbox.size();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * s15 inbox 消息 record · 对齐 CC teammateMailbox.ts 结构化消息 type + payload
     * (inProcessRunner.ts:743 轮询模型的 Java 侧简化).
     */
    public record InboxMessage(String type, String payload, long createdAtMs) {
        public InboxMessage(String type, String payload) {
            this(type, payload, System.currentTimeMillis());
        }

        /** 常用 type 常量 (CC inProcessRunner/teammateMailbox 结构化消息标准类型) */
        public static final String TYPE_IDLE_NOTIFICATION = "idle_notification";
        public static final String TYPE_TASK_ASSIGNED = "task_assigned";
        public static final String TYPE_USER_MESSAGE = "user_message";
        public static final String TYPE_SHUTDOWN = "shutdown";
    }

    /**
     * W8-01: waitForNextPromptOrShutdown 轮询间隔 · 对齐 CC inProcessRunner.ts:697
     * {@code const POLL_INTERVAL_MS = 500}.
     */
    public static final long POLL_INTERVAL_MS = 500;

    /**
     * W8-01: 文件型 mailbox 轮询结果 · 对齐 CC WaitResult (inProcessRunner.ts:662-680).
     *
     * @param type  'shutdown_request' | 'new_message'（CC :674-679）
     * @param from  发送方 agent 名
     * @param text  消息文本（结构化消息 JSON）
     * @param color 发送方颜色（可空）
     * @param index mailbox 中消息索引（已标已读）
     */
    public record MailboxPollResult(String type, String from, String text,
                                    String color, String summary, int index) {
        public static final String TYPE_SHUTDOWN_REQUEST = "shutdown_request";
        public static final String TYPE_NEW_MESSAGE = "new_message";
    }

    /**
     * W8-01: 文件型 mailbox 轮询 + 消息优先级 · 对齐 CC waitForNextPromptOrShutdown
     * (inProcessRunner.ts:689-868)。
     *
     * <p>优先级（CC :763-845，grep 自验）：
     * <ol>
     *   <li><b>shutdown_request 最高优先</b>（:763-804）——先扫未读，防 peer 消息洪泛饿死 shutdown</li>
     *   <li><b>team-lead 消息</b>（:812-819）——leader 代表用户意图，不被 peer 闲聊饿死</li>
     *   <li><b>FIFO 兜底</b>（:822-826）——首个未读（任意发送方）</li>
     * </ol>
     * 命中消息经 {@link TeammateMailbox#markMessageAsReadByIndex} 标已读（CC :791/:834/:839）。
     *
     * @param agentName 收件 agent 名（非 UUID）
     * @param teamName  team 名（空按 CC 回退链）
     * @return 优先级最高的未读消息；无未读返回 empty
     */
    public static Optional<MailboxPollResult> pollFileMailbox(String agentName, String teamName) {
        List<TeammateMailbox.TeammateMessage> all = TeammateMailbox.readMailbox(agentName, teamName);

        // 1. shutdown_request 最高优先（CC :763-804）
        for (int i = 0; i < all.size(); i++) {
            TeammateMailbox.TeammateMessage m = all.get(i);
            if (!m.read() && isShutdownRequest(m.text())) {
                TeammateMailbox.markMessageAsReadByIndex(agentName, teamName, i);
                return Optional.of(new MailboxPollResult(
                    MailboxPollResult.TYPE_SHUTDOWN_REQUEST, m.from(), m.text(),
                    m.color(), m.summary(), i));
            }
        }

        // 2. team-lead 消息（CC :812-819）
        for (int i = 0; i < all.size(); i++) {
            TeammateMailbox.TeammateMessage m = all.get(i);
            if (!m.read() && SwarmConstants.TEAM_LEAD_NAME.equals(m.from())) {
                TeammateMailbox.markMessageAsReadByIndex(agentName, teamName, i);
                return Optional.of(new MailboxPollResult(
                    MailboxPollResult.TYPE_NEW_MESSAGE, m.from(), m.text(),
                    m.color(), m.summary(), i));
            }
        }

        // 3. FIFO 兜底（CC :822-826）
        for (int i = 0; i < all.size(); i++) {
            TeammateMailbox.TeammateMessage m = all.get(i);
            if (!m.read()) {
                TeammateMailbox.markMessageAsReadByIndex(agentName, teamName, i);
                return Optional.of(new MailboxPollResult(
                    MailboxPollResult.TYPE_NEW_MESSAGE, m.from(), m.text(),
                    m.color(), m.summary(), i));
            }
        }
        return Optional.empty();
    }

    /**
     * W8-01: shutdown_request 轻量识别 · 对齐 CC teammateMailbox.ts:868-878 isShutdownRequest：
     * {@code jsonParse(text) && parsed.type === 'shutdown_request'}。
     *
     * <p>完整结构化 record 由 W8-03 协议层承载（ShutdownRequestMessage），此处仅做轮询
     * 优先级所需的 type 判定（确定性数据转换，规则五）。
     */
    private static boolean isShutdownRequest(String text) {
        if (text == null) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            return node != null && node.isObject()
                && "shutdown_request".equals(node.path("type").asText());
        } catch (Exception e) {
            return false;
        }
    }
}
