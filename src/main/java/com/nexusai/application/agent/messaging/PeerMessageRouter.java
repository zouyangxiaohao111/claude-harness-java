package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨会话消息路由（A 批核心）。
 *
 * <p><b>为什么不直接入队</b>：路由负责「目标还活着吗 / 发件人还活着吗 / 会不会形成环 /
 * 入站策略允许吗」四道判定；任何一道不通过都不得产生模型可见消息（hold 只写 HeldPeerStore，
 * 不写任何可消费队列、不唤醒）。
 *
 * <p><b>未接线</b>：B 未注入 {@link DeliveryPort} 时返回 {@link Status#NOT_WIRED}，
 * 工具侧必须回 {@code Error: ...}，绝不假装成功。{@link Status#ACCEPTED} 只表示「本进程已接受」。
 *
 * <p><b>hold 审批</b>：{@link #listHeld}/{@link #rejectHeld}/{@link #approveHeld} 前必须校验
 * {@link ApprovalPrincipal} 的 {@code trustedUi} 与 {@code sessionId} 归属；不可信主体一律拒绝，
 * 且不改变 held 状态（避免恶意调用取消合法审批）。
 */
@Component
public class PeerMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(PeerMessageRouter.class);

    /** 发送结果状态。 */
    public enum Status {
        ACCEPTED,
        HELD,
        REFUSED,
        NO_LIVE_SESSION,
        SOURCE_NOT_LIVE,
        NOT_DELIVERED,
        DELIVERY_UNCONFIRMED,
        NOT_WIRED,
        INVALID_TARGET,
        /** hop 链自环（同一会话出现 ≥ {@link PeerEnvelope#MAX_SELF_HOPS} 次）· CC {@code hop-loop}。 */
        LOOP_REJECTED,
        /** hop 链过长（> {@link PeerEnvelope#MAX_CHAIN_LENGTH} 段）· CC {@code hop-runaway}。 */
        HOP_RUNAWAY,
        APPROVAL_REJECTED,
        /**
         * 发送侧 pacer 拒发（CC {@code errorClass="sender_paced"}）：目标会话的<b>入站</b>桶
         * 按 30 容量 / 0.5 每秒回填，本进程本轮已发太多（发前预留失败）。
         */
        SENDER_PACED,
        /**
         * 接收侧准入丢弃（CC 异步 {@code dropped} 回执）：令牌桶 / 同文去重 / 队列满。
         *
         * <p>CC 里发送方先拿到成功 + message_id，随后由接收侧回执告知 dropped；本仓进程内
         * 同步可见，故直接返回本状态，并由 {@link PeerFailureNotifier} 落一条失败通知。
         */
        DROPPED,
        /**
         * 自动循环刹车暂停（CC {@code aVn/lVn} 的 {@code loop-paused}）：本会话在无用户输入
         * 的情况下持续回复其他会话，看起来像自动循环 —— 发送被暂停到用户下一次输入。
         */
        PAUSED
    }

    /**
     * 发送请求。
     *
     * @param targetSessionId     目标会话
     * @param targetEndpointEpoch 目标代次（-1 = 不校验收件方快照）
     * @param targetDisplayName   目标展示名
     * @param sourceSessionId     来源会话
     * @param sourceName          来源展示名（<b>仅参考</b>：信封 from-name 由本类按 live 来源的
     *                            ListAgents 展示名重算，见 send 的 3.5 段 —— 两者不一致时以本类为准）
     * @param sourceAddress       可回信地址（同上：仅参考，信封 from 也由本类重算）
     * @param body                正文
     * @param summary             UI 摘要
     * @param notifyWhenIdle      是否登记一次性 idle 订阅
     * @param sameSessionDelivery 是否投往<b>发送方自己的会话记录</b>（子代理 / teammate → 主会话：
     *                            两者 sessionId 相同）。⛔ 不是「主会话发给自己」——那种请求在
     *                            SendMessageTool 已被 CC 原文拒绝，永不进入本方法。本标志只用于
     *                            跳过刹车 / 限速 / hop 自环 / 入站 hold（这不是「别人发来的消息」）。
     * @param inboundHopChain     入站带来的历史 hop 链
     */
    public record SendRequest(String targetSessionId, long targetEndpointEpoch,
                              String targetDisplayName, String sourceSessionId, String sourceName,
                              String sourceAddress, String body, String summary,
                              boolean notifyWhenIdle, boolean sameSessionDelivery,
                              List<String> inboundHopChain) {}

    /**
     * 发送回执。
     *
     * @param status           状态
     * @param messageId        稳定消息 id
     * @param detail           细节
     * @param idleSubscription subscribed / not_subscribed:&lt;reason&gt; / null
     */
    public record Receipt(Status status, String messageId, String detail, String idleSubscription) {

        static Receipt of(Status status, String messageId, String detail) {
            return new Receipt(status, messageId, detail, null);
        }
    }

    /** 投递端口（B 实现：接 NotificationQueue）。 */
    public interface DeliveryPort {
        Receipt deliver(String targetSessionId, long targetEndpointEpoch,
                        PeerEnvelope envelope, String body);
    }

    /** 审批主体（C/B 提供，来自真实 UI，不是 peer 文本）。 */
    public interface ApprovalPrincipal {
        String sessionId();

        String requestId();

        boolean trustedUi();
    }

    /** 拒绝通知原因（发送方可见的简短原因；用户手动拒绝 / 批量拒绝同值）。 */
    public static final String REJECT_REASON_USER_DECLINED = "user-declined";

    /**
     * 拒绝通知（投给<b>发送方会话</b>）。
     *
     * @param messageId           被拒消息 id
     * @param targetSessionId     拒绝方（接收方）会话 id
     * @param targetTitle         接收方会话标题快照（可 null，⛔ 不用会话 id 冒充）
     * @param sourceSessionId     发送方会话 id（通知投递目标）
     * @param sourceEndpointEpoch 发送时快照的发送方代次（仅供诊断；投递按当前解析）
     * @param reason              简短原因（user-declined）
     * @param rejectedAt          拒绝时刻
     */
    public record RejectionNotice(String messageId, String targetSessionId, String targetTitle,
                                  String sourceSessionId, long sourceEndpointEpoch,
                                  String reason, Instant rejectedAt) {}

    /**
     * 拒绝通知出口（B 实现：UI 事件）。
     *
     * <p><b>契约</b>：只在发送方会话仍可达时被调用；实现必须<b>不触发模型推理</b>
     * （UI 推送，不入队、不写会话历史）。返回 false = 未送达（调用方只记日志）。
     */
    public interface RejectionNotifier {
        boolean notifyRejected(RejectionNotice notice);
    }

    /**
     * 被 hold 的消息。
     *
     * @param status 行状态：只有 {@code pending}（7 参兼容构造同值）；批准后不保留行状态
     */
    public record HeldMessage(String messageId, String targetSessionId, long targetEndpointEpoch,
                              PeerEnvelope envelope, String body,
                              PeerInboundPolicy.Reason reason, Instant heldAt, String status) {

        /** 待处理（策略判定为 hold，尚未被用户处理）—— 唯一可操作状态。 */
        public static final String STATUS_PENDING = "pending";
        /** 已投递（终态；严格 CC 语义下批准成功即落此状态，不等消费落地）。 */
        public static final String STATUS_ACCEPTED = "accepted";
        /** 用户拒绝（终态；不写会话历史、不唤醒目标；发送方会收到一条拒绝通知）。 */
        public static final String STATUS_REJECTED = "rejected";
        /**
         * 终态且未投递：待确认超时 / 接收方会话关闭 / 队列满被淘汰 / <b>批准时投递失败</b>
         * （refused / dropped 语义由失败通知的 reason 表达）。
         *
         * <p>与 {@link #STATUS_REJECTED} 不同：这通常不是用户显式拒绝，而是「确认卡过期」「接收方
         * 关闭」或「批准后投递未达成」这类结果。⛔ 模式变化不走本状态（那是 revalidate，归 refused）。
         */
        public static final String STATUS_EXPIRED = "expired";

        /** 7 参兼容构造（status=pending）—— 既有发送/测试调用零改动。 */
        public HeldMessage(String messageId, String targetSessionId, long targetEndpointEpoch,
                           PeerEnvelope envelope, String body,
                           PeerInboundPolicy.Reason reason, Instant heldAt) {
            this(messageId, targetSessionId, targetEndpointEpoch, envelope, body, reason, heldAt,
                STATUS_PENDING);
        }

        /** 归一化状态（null/空白 → pending）。 */
        public String effectiveStatus() {
            return status == null || status.isBlank() ? STATUS_PENDING : status;
        }
    }

    /**
     * 列表/事件同形的待收件视图（含 status / seenAt）。
     *
     * <p>字段名与 {@code peer.inbox.hold} 事件、前端 {@code PeerHeldItemDto} 对齐：
     * {@code status} = pending/rejected（⛔ 不再有 approved；accepted/expired 不出现在列表），
     * {@code seenAt} = null 表示未读。时间字段为 ISO-8601 字符串（与 HTTP 列表接口一致）。
     *
     * @param admission       入站准入方式（auto / approved；null = 未准入/未知 —— pending 行恒 null）
     * @param admissionReason 准入理由（mode-match / self-sent / user-approved / explicit-accept；可 null）
     * @param bodyLines       正文总行数（供前端显示预览规模；空正文 = 0）
     * @param bodyChars       正文总字数（Unicode 码点计数，CJK/emoji 均按 1 计；空正文 = 0）
     */
    /**
     * 一个会话的待处理聚合（counts 接口的 FIFO 附加信息）。
     *
     * <p>{@code oldestRowId} 是 SQLite 插入顺序（权威 FIFO）；{@code oldestCreatedAt} 是
     * 按 rowid 选出的最早一行的 created_at（仅展示/兜底，⛔ 不参与 SQL 排序）。
     */
    public record SessionPending(String sessionId, long count, Long oldestRowId, String oldestCreatedAt) {}

    public record HeldView(String messageId, String targetSessionId, String sourceSessionId,
                           String sourceTitle, String sourceKind, String fromName,
                           String fromAddress, String fromMode, String summary, String body,
                           String reason, String status, String createdAt, String seenAt,
                           String admission, String admissionReason,
                           int bodyLines, int bodyChars) {

        /** 从纯内存 HeldMessage 投影（status=pending、seenAt=null）。 */
        static HeldView of(HeldMessage m) {
            PeerEnvelope e = m.envelope();
            return new HeldView(
                m.messageId(),
                m.targetSessionId(),
                e != null ? e.fromSessionId() : null,
                e != null ? e.sourceTitle() : null,
                "peer",
                e != null ? e.fromName() : null,
                e != null ? e.fromAddress() : null,
                e != null ? e.fromMode() : null,
                e != null ? e.summary() : null,
                m.body(),
                m.reason() != null ? m.reason().name() : null,
                m.effectiveStatus(),
                m.heldAt() != null ? m.heldAt().toString() : null,
                null,
                e != null ? e.admission() : null,
                e != null ? e.admissionReason() : null,
                bodyLineCount(m.body()),
                bodyCharCount(m.body()));
        }
    }

    /**
     * held 存储。
     *
     * <p>默认方法保证内存实现与 DB 实现可互换：
     * <ul>
     *   <li>{@link #listViews}：默认由 {@link #list} 投影；</li>
     *   <li>{@link #reject}：默认用 {@link #take}（内存实现=删除）；DB 实现是条件 UPDATE pending→rejected；</li>
     *   <li>{@link #markSeen}：默认 0（内存实现无已读语义）；DB 实现落 seen_at。</li>
     * </ul>
     *
     * <h2>状态机（批准即结算 · 严格 CC 2.1.328 语义）</h2>
     * <pre>
     *   pending --批准(take)--> [立即投递] --成功--> accepted
     *                                     --失败--> expired（终态 + 通知发送方）
     *   pending --reject----> rejected
     *   pending --超时/关闭/淘汰--> expired
     * </pre>
     * ⛔ <b>没有 approved 中间态</b>：批准 = 一次性取出 + 同步投递；不能撤销、不能二次批准。
     * 投递失败立即结算（refused / dropped / receiver-closed），⛔ 不得留「已批准待投递」的行。
     */
    public interface HeldPeerStore {

        /**
         * 回写/新建待收件行。
         *
         * <p><b>⛔ 不得用 INSERT OR IGNORE 静默丢弃</b>（2026-11-20 E2E 丢件根因）：行已存在时
         * 保持原状态（pending 不回退、不发事件）；行已是终态（accepted/rejected/expired）时
         * <b>不插入、不覆盖</b>，记 ERROR 并返回 {@code false}（可识别结果），绝不假装成功。
         *
         * @param message 待收件消息
         * @return true = 已存在 pending 或被成功插入；false = 已是终态 / 参数非法（丢弃，调用方须记错误）
         */
        boolean hold(HeldMessage message);

        List<HeldMessage> list(String targetSessionId);

        /**
         * 一次性取出（批准 = 立即投递）：{@code pending → 移除}。
         *
         * <p>返回非 null = 本次调用胜出（调用方<b>必须立即</b>投递，然后调用
         * {@link #settleAccepted(HeldMessage)} 或 {@link #settleFailed(HeldMessage, String)} 结算）；
         * null = 不存在 / 错归属 / 已 accepted / 已 rejected / 已 expired。
         */
        HeldMessage take(String messageId, ApprovalPrincipal principal);

        /** 批准成功结算：取出后的行落为终态 {@code accepted}。默认 no-op（内存实现可覆盖）。 */
        default void settleAccepted(HeldMessage taken) {
        }

        /**
         * 批准失败结算：取出后的行落为终态 {@code expired} 并通知发送方（reason = refused / dropped /
         * receiver-closed / source-not-live）。默认 no-op（内存实现可覆盖）。
         */
        default void settleFailed(HeldMessage taken, String reason) {
        }

        /**
         * 是否已投递落地（行状态 = accepted）。
         *
         * <p><b>WHY（idle-then-drain 去重，2026-11-20 E2E 第三轮）</b>：空闲路径
         * （{@code CronIdleExecutor.handlePeerQueuedItem}）消费一条 peer 项时先落库并置 accepted，
         * 随后 {@code LlmAgentLoop.doRun} 会把同一条消息<b>重建成本回合首条 prompt 重新入队</b>
         * （{@code PeerExecutionOrigin} 不携带一次性批准标记 ⇒ 重入队信封 {@code preApproved=false}）。
         * 运行中 drain 再看到它时若按策略重判，hold 设置下会误判 HOLD → 回写命中 accepted 终态行
         * → 误报 ERROR。本判据让 drain 用<b>权威落库状态</b>识别「已消费」，跳过策略重判。
         *
         * <p>严格 CC 语义下「批准即结算」⇒ 行在投递成功时已是 accepted，本判据亦覆盖刚批准的场景。
         *
         * @param messageId 消息 id（= PeerEnvelope.messageId）
         * @return true = 已 accepted；false = 未投递 / 不存在 / 实现不支持
         */
        default boolean isConsumed(String messageId) {
            return false;
        }

        default List<HeldView> listViews(String targetSessionId) {
            List<HeldView> out = new ArrayList<>();
            for (HeldMessage m : list(targetSessionId)) {
                out.add(HeldView.of(m));
            }
            return List.copyOf(out);
        }

        /** 拒绝一条 pending；返回 true = 本次调用把它转成终态。 */
        default boolean reject(String messageId, ApprovalPrincipal principal) {
            return take(messageId, principal) != null;
        }

        /** 标记已读；messageIds 为空 = 该会话全部未读 pending。返回实际更新行数（幂等）。 */
        default int markSeen(String targetSessionId, List<String> messageIds) {
            return 0;
        }

        /**
         * 按会话聚合的「仍需处理」条数（{@code GET /api/v1/peer-inbox/counts} 的数据源）。
         *
         * <p>口径与 {@code DbHeldPeerStore#pendingCount} 一致：只数 {@code pending}。只返回 &gt; 0 的会话。
         * 默认空 map = 实现不支持（调用方按「无待处理」处理）。
         *
         * @return 会话 id → 待处理条数
         */
        default java.util.Map<String, Long> pendingCounts() {
            return java.util.Map.of();
        }

        /**
         * 按会话聚合的待处理条数 + 最早一条（rowid/createdAt），供前端 FIFO 选目。
         *
         * <p>默认空列表 = 实现不支持（调用方退回 {@link #pendingCounts()} 的计数排序）。
         */
        default java.util.List<SessionPending> pendingSessionCounts() {
            return java.util.List.of();
        }

        /**
         * 行当前状态（{@code pending} / {@code accepted} / {@code rejected} / {@code expired}）。
         *
         * <p><b>WHY</b>：批量拒绝是单语句条件 UPDATE，调用方拿不到「究竟哪几行真的被本次改到」；
         * 拒绝后要逐条给发送方发通知，就必须用<b>权威落库状态</b>确认（并发批准抢走的行
         * 不得误报「被拒」）。默认 null = 实现不提供（调用方按「未确认」处理，不通知）。
         *
         * @param messageId 消息 id
         * @return 状态字符串；行不存在 / 实现不支持 → null
         */
        default String statusOf(String messageId) {
            return null;
        }

        /**
         * 批量拒绝该会话的全部 pending（终态，不可恢复；不动已 accepted/expired/rejected 的行）。
         *
         * <p>默认 0（内存实现无 bulk 语义时的保守回落）；DB 实现是单语句条件 UPDATE，
         * 与批准并发时同一行只有一个能改到。
         *
         * @param targetSessionId 目标会话
         * @param principal       审批主体（不可信/错归属 → 0，且不改任何行）
         * @return 实际拒绝条数
         */
        default int rejectAll(String targetSessionId, ApprovalPrincipal principal) {
            return 0;
        }

        /**
         * 把该会话全部 pending 结算为 {@code expired} 并逐条通知发送方。
         *
         * <p>用于接收方 close/delete（CC 实测硬行为：会话一退，hold 消息即死）。默认 0 =
         * 实现不支持（内存实现没有跨重启语义，也不负责通知）。
         *
         * @param targetSessionId 接收方会话
         * @param reason          通知原因（expired / receiver-closed / queue-full）
         * @return 实际转 expired 的行数
         */
        default int expireAll(String targetSessionId, String reason) {
            return 0;
        }
    }

    /** 默认内存实现（A 提供；C/B 可替换）。 */
    public static class InMemoryHeldPeerStore implements HeldPeerStore {

        private final Map<String, HeldMessage> held = new ConcurrentHashMap<>();

        /** 行状态：只有 pending 会留在 {@link #held}；终态另行记在下方三个集合里。 */
        private final Map<String, String> statusById = new ConcurrentHashMap<>();

        /**
         * 已消费落地（accepted）的 messageId。行本身按既有语义移除，但「已消费」这个事实要留下：
         * 空闲消费后 doRun 会把同一 messageId 重新入队，运行中 drain 需据此跳过策略重判
         * （{@link #isConsumed}）；同时阻止 {@link #hold} 把终态行复活成 pending。
         */
        private final java.util.Set<String> consumedIds = ConcurrentHashMap.newKeySet();

        /**
         * 已被用户拒绝（rejected，终态）的 messageId。
         *
         * <p>内存实现按既有语义移除行，但「已拒绝」这个事实要留下：① 阻止 {@link #hold} 把终态行
         * 复活成 pending（与 DB 实现 keepHeldRow 终态不命中的语义一致）；② 供 {@link #statusOf}
         * 在批量拒绝后确认「哪几行真的被本次改到」（与 DB 实现同判据）。
         */
        private final java.util.Set<String> rejectedIds = ConcurrentHashMap.newKeySet();

        /** 批准失败结算（expired，终态）的 messageId：不可再批、不可复活。 */
        private final java.util.Set<String> expiredIds = ConcurrentHashMap.newKeySet();

        @Override
        public synchronized boolean hold(HeldMessage message) {
            if (message == null || message.messageId() == null) {
                return false;
            }
            // accepted / rejected / expired 都是终态：不得复活成 pending
            // （与 DB 实现 keepHeldRow 终态不命中的语义一致）。
            if (consumedIds.contains(message.messageId())
                || rejectedIds.contains(message.messageId())
                || expiredIds.contains(message.messageId())) {
                return false;
            }
            if (statusById.get(message.messageId()) == null) {
                held.put(message.messageId(), message);
                statusById.put(message.messageId(), HeldMessage.STATUS_PENDING);
                return true;
            }
            // 已有 pending：保持原状态（回写不产生第二行）。
            return HeldMessage.STATUS_PENDING.equals(statusById.get(message.messageId()));
        }

        @Override
        public synchronized List<HeldMessage> list(String targetSessionId) {
            if (targetSessionId == null) {
                return List.of();
            }
            List<HeldMessage> out = new ArrayList<>();
            for (HeldMessage m : held.values()) {
                if (!targetSessionId.equals(m.targetSessionId())) {
                    continue;
                }
                out.add(withStatus(m));
            }
            out.sort(Comparator.comparing(HeldMessage::heldAt));
            return List.copyOf(out);
        }

        @Override
        public synchronized HeldMessage take(String messageId, ApprovalPrincipal principal) {
            if (!isTrustedFor(messageId, principal)) {
                return null;
            }
            if (!HeldMessage.STATUS_PENDING.equals(statusById.get(messageId))) {
                return null;
            }
            // 一次性取出：行离开 store；调用方必须立即 settleAccepted / settleFailed。
            statusById.remove(messageId);
            HeldMessage taken = held.remove(messageId);
            return taken == null ? null : withStatus(taken);
        }

        @Override
        public synchronized void settleAccepted(HeldMessage taken) {
            if (taken == null || taken.messageId() == null) {
                return;
            }
            consumedIds.add(taken.messageId());
            expiredIds.remove(taken.messageId());
        }

        @Override
        public synchronized void settleFailed(HeldMessage taken, String reason) {
            if (taken == null || taken.messageId() == null) {
                return;
            }
            expiredIds.add(taken.messageId());
        }

        @Override
        public boolean isConsumed(String messageId) {
            return messageId != null && consumedIds.contains(messageId);
        }

        @Override
        public synchronized boolean reject(String messageId, ApprovalPrincipal principal) {
            if (!isTrustedFor(messageId, principal)) {
                return false;
            }
            if (!HeldMessage.STATUS_PENDING.equals(statusById.get(messageId))) {
                return false;
            }
            statusById.remove(messageId);
            held.remove(messageId);
            rejectedIds.add(messageId);
            return true;
        }

        @Override
        public String statusOf(String messageId) {
            if (messageId == null) {
                return null;
            }
            String status = statusById.get(messageId);
            if (status != null) {
                return status;
            }
            if (consumedIds.contains(messageId)) {
                return HeldMessage.STATUS_ACCEPTED;
            }
            if (rejectedIds.contains(messageId)) {
                return HeldMessage.STATUS_REJECTED;
            }
            if (expiredIds.contains(messageId)) {
                return HeldMessage.STATUS_EXPIRED;
            }
            return null;
        }

        private HeldMessage withStatus(HeldMessage m) {
            String status = statusById.getOrDefault(m.messageId(), HeldMessage.STATUS_PENDING);
            if (status.equals(m.status())) {
                return m;
            }
            return new HeldMessage(m.messageId(), m.targetSessionId(), m.targetEndpointEpoch(),
                m.envelope(), m.body(), m.reason(), m.heldAt(), status);
        }

        private boolean isTrustedFor(String messageId, ApprovalPrincipal principal) {
            if (messageId == null || principal == null || !principal.trustedUi()
                || principal.sessionId() == null) {
                return false;
            }
            HeldMessage m = held.get(messageId);
            return m != null && principal.sessionId().equals(m.targetSessionId());
        }

        /**
         * 内存版批量拒绝：只移除本会话的 pending 项（终态行不可再拒绝）。
         *
         * <p>{@code synchronized} + 状态条件 ⇒ 与 {@link #take} 并发时同一项只有一个能改成功。
         */
        @Override
        public synchronized int rejectAll(String targetSessionId, ApprovalPrincipal principal) {
            if (targetSessionId == null || principal == null || !principal.trustedUi()
                || !targetSessionId.equals(principal.sessionId())) {
                return 0;
            }
            int rejected = 0;
            for (HeldMessage m : List.copyOf(held.values())) {
                if (targetSessionId.equals(m.targetSessionId())
                    && HeldMessage.STATUS_PENDING.equals(statusById.get(m.messageId()))) {
                    statusById.remove(m.messageId());
                    held.remove(m.messageId());
                    rejectedIds.add(m.messageId());
                    rejected++;
                }
            }
            return rejected;
        }

        /**
         * 内存版按会话计数（与 DB 实现同口径）：只剩 pending 的条数；只返回 &gt; 0 的会话。
         */
        @Override
        public synchronized java.util.Map<String, Long> pendingCounts() {
            java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
            for (HeldMessage m : held.values()) {
                if (!HeldMessage.STATUS_PENDING.equals(statusById.get(m.messageId()))) {
                    continue;
                }
                out.merge(m.targetSessionId(), 1L, Long::sum);
            }
            return out;
        }
    }

    @Autowired
    private PeerSessionRegistry peerSessionRegistry;

    @Autowired
    private PeerInboundPolicy peerInboundPolicy;

    @Autowired(required = false)
    private PeerIdleSubscriptions idleSubscriptions;

    /**
     * 会话服务（只读标题快照；required=false ⇒ 非 Spring 单测 / 无 DB 部署保持 null）。
     * ⛔ 只用于展示快照，不参与任何路由/授权判定。
     */
    @Autowired(required = false)
    private com.nexusai.domain.session.SessionService sessionService;

    /**
     * 拒绝通知出口（B 注入；null = 未接线 ⇒ 只记日志，拒绝本身照常生效）。
     *
     * <p>出口必须是<b>不触发模型推理的 UI 通道</b>（STOMP 事件），⛔ 不得入 NotificationQueue
     * —— 入队会在发送方空闲时拉起一轮模型推理，等于「唤醒」已退出的会话。
     */
    @Autowired(required = false)
    private RejectionNotifier rejectionNotifier;

    /**
     * 失败通知落库出口（拒绝 → 发送方会话记录；离线也写）。
     *
     * <p>null = 未接线：拒绝本身照常生效，只是不写通知（只记日志）。
     */
    @Autowired(required = false)
    private PeerFailureNotifier failureNotifier;

    /** 测试/接线用 setter（失败通知落库出口）。 */
    public void setFailureNotifier(PeerFailureNotifier failureNotifier) {
        this.failureNotifier = failureNotifier;
    }

    /** 已发过拒绝通知的 messageId（一次性；同一 messageId 不重复通知）。 */
    private final java.util.Set<String> rejectionNotified = ConcurrentHashMap.newKeySet();

    /**
     * 发送侧 pacer（按目标会话 × 代次；30 容量 / 0.5 每秒回填）。
     *
     * <p>null = 未接线（非 Spring 直构）：不做发送侧预留，行为与改动前一致。
     */
    @Autowired(required = false)
    private PeerSendPacer peerSendPacer;

    /**
     * 自动循环刹车（CC {@code aVn/lVn}）；null = 未接线 ⇒ 不刹车。
     */
    @Autowired(required = false)
    private PeerLoopBrake peerLoopBrake;

    /** 测试/接线用 setter（发送侧 pacer；null = 不预留）。 */
    public void setPeerSendPacer(PeerSendPacer pacer) {
        this.peerSendPacer = pacer;
    }

    /** 测试/接线用 setter（自动循环刹车；null = 不刹车）。 */
    public void setPeerLoopBrake(PeerLoopBrake brake) {
        this.peerLoopBrake = brake;
    }

    private volatile DeliveryPort deliveryPort;
    private volatile HeldPeerStore heldStore = new InMemoryHeldPeerStore();

    /**
     * 容器注入的持久化 store（{@code DbHeldPeerStore}）；null = 无 DB，保留内存默认。
     * 用 setter 注入而不是字段注入，便于测试直接 new router。
     */
    private volatile HeldPeerStore injectedHeldStore;

    /** 测试/接线用 setter。 */
    public void setPeerSessionRegistry(PeerSessionRegistry registry) {
        this.peerSessionRegistry = registry;
    }

    /** 测试/接线用 setter。 */
    public void setPeerInboundPolicy(PeerInboundPolicy policy) {
        this.peerInboundPolicy = policy;
    }

    /** 测试/接线用 setter。 */
    public void setIdleSubscriptions(PeerIdleSubscriptions subscriptions) {
        this.idleSubscriptions = subscriptions;
    }

    /** B 注入拒绝通知出口（null = 不通知，只记日志）。 */
    public void setRejectionNotifier(RejectionNotifier notifier) {
        this.rejectionNotifier = notifier;
    }

    /** 测试/接线用 setter（来源会话标题快照；null = 不解析 ⇒ 标题恒 null，不伪造）。 */
    public void setSessionService(com.nexusai.domain.session.SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** B 注入投递端口；未注入 = NOT_WIRED。 */
    public void setDeliveryPort(DeliveryPort port) {
        this.deliveryPort = port;
    }

    /**
     * 容器注入持久化 store（Spring 自动调用；无 bean 时不调用）。
     *
     * <p>注入成功后 {@link #setHeldStore} 仍允许<b>非内存</b>实现覆盖（测试直构自定义 store），
     * 但会忽略 {@link InMemoryHeldPeerStore} —— 因为 {@code PeerRuntimeAdapter} 在
     * {@code @PostConstruct} 里会 set 一个内存实例，若照单全收会把 DB store 顶掉（重启丢件）。
     */
    @Autowired(required = false)
    public void setInjectedHeldStore(HeldPeerStore store) {
        if (store == null) {
            return;
        }
        this.injectedHeldStore = store;
        this.heldStore = store;
        log.info("PeerMessageRouter: 已启用容器注入的 HeldPeerStore（持久化）type={}",
            store.getClass().getSimpleName());
    }

    /**
     * 替换 held 存储（测试/接线兼容旧 API）。
     *
     * <p>已有容器注入的持久化 store 时，内存实现不再覆盖它（避免重启丢件）；其它实现仍可覆盖。
     */
    public void setHeldStore(HeldPeerStore store) {
        if (store == null) {
            return;
        }
        HeldPeerStore injected = injectedHeldStore;
        if (injected != null && store instanceof InMemoryHeldPeerStore) {
            log.debug("PeerMessageRouter: 忽略内存 HeldPeerStore（已注入 {}）",
                injected.getClass().getSimpleName());
            return;
        }
        this.heldStore = store;
    }

    /**
     * 发送（从 registry 读发送方 attest 的模式）。
     *
     * @param request 请求
     * @return 回执（永不 null）
     */
    public Receipt send(SendRequest request) {
        return send(request, null, null);
    }

    /**
     * 发送（显式发送方模式；工具侧用 ctx 当前有效模式）。
     *
     * @param request    请求
     * @param sourceMode 发送方 attest 的模式；null = 从 registry 当前值取
     * @return 回执（永不 null）
     */
    public Receipt send(SendRequest request, PermissionMode sourceMode) {
        return send(request, sourceMode, null);
    }

    /**
     * 发送（可显式指定 messageId，用于幂等重试/测试）。
     *
     * <p>同 messageId 重复 send 到同一目标时，HOLD 分支由 store 的 INSERT OR IGNORE 保证<b>只一行</b>，
     * 且只有第一次真正插入的那次发布事件；ACCEPT 分支的重投由调用方/队列 uuid 幂等承担。
     *
     * @param request          请求
     * @param sourceMode       发送方 attest 的模式；null = 从 registry 当前值取
     * @param explicitMessageId 稳定 id；null/空白 = 新生成 {@code peer-<uuid>}
     * @return 回执（永不 null）
     */
    public Receipt send(SendRequest request, PermissionMode sourceMode, String explicitMessageId) {
        return send(request, sourceMode, explicitMessageId, HoldExemption.NONE);
    }

    /**
     * 创建者向它刚创建的<b>平级</b>会话投递首条任务（{@code derived} 窄豁免：只把 HOLD 转 accept）。
     *
     * <p>只给 SessionCreate 的首条任务用；要求被创建会话的
     * {@code derived_from_session_id == 创建者会话}。两者是平级 peer，
     * 该字段只表示创建来源，不表示父子管辖。不绕 REFUSE / 权限 / 来源校验。
     *
     * @param request         投递请求（target=被创建会话，source=创建者会话）
     * @param sourceMode      发送方权限模式
     * @param launchMessageId 首条任务的稳定 id（= 队列 uuid，用于幂等）
     * @return 投递回执
     */
    public Receipt sendSessionCreateLaunch(SendRequest request, PermissionMode sourceMode, String launchMessageId) {
        return send(request, sourceMode, launchMessageId, HoldExemption.DERIVED_LAUNCH);
    }

    /** 投递窄豁免（只影响 HOLD 分支；REFUSE / 权限 / 来源校验不变）。 */
    enum HoldExemption { NONE, DERIVED_LAUNCH }

    private Receipt send(SendRequest request, PermissionMode sourceMode, String explicitMessageId,
                         HoldExemption exemption) {
        String messageId = explicitMessageId == null || explicitMessageId.isBlank()
            ? "peer-" + UUID.randomUUID() : explicitMessageId;
        if (request == null || request.targetSessionId() == null || request.targetSessionId().isBlank()) {
            return Receipt.of(Status.INVALID_TARGET, messageId, "missing target session");
        }
        if (request.sourceSessionId() == null || request.sourceSessionId().isBlank()) {
            return Receipt.of(Status.INVALID_TARGET, messageId, "missing source session");
        }
        if (request.body() == null || request.body().isBlank()) {
            return Receipt.of(Status.INVALID_TARGET, messageId, "empty message body");
        }
        if (peerSessionRegistry == null || peerInboundPolicy == null) {
            return Receipt.of(Status.NOT_WIRED, messageId, "peer routing is not wired");
        }

        // 1. 目标必须 live 且代次匹配
        PeerSessionRegistry.LiveSession target = peerSessionRegistry
            .resolve(request.targetSessionId()).orElse(null);
        if (target == null || !target.accepting() || target.closing()) {
            return Receipt.of(Status.NO_LIVE_SESSION, messageId,
                "no live session " + request.targetSessionId());
        }
        if (request.targetEndpointEpoch() >= 0
            && request.targetEndpointEpoch() != target.endpointEpoch()) {
            return Receipt.of(Status.NO_LIVE_SESSION, messageId,
                "endpoint generation changed for " + request.targetSessionId());
        }

        // 2. 来源必须 live；来源 epoch 写入信封（服务端真源）
        PeerSessionRegistry.LiveSession source = peerSessionRegistry
            .resolve(request.sourceSessionId()).orElse(null);
        if (source == null || !source.accepting() || source.closing()) {
            return Receipt.of(Status.SOURCE_NOT_LIVE, messageId,
                "source session is not live: " + request.sourceSessionId());
        }
        PermissionMode fromMode = sourceMode != null ? sourceMode : source.permissionMode();

        // 2.5 自动循环刹车（CC aVn，偏移 336254506）：本会话在无用户输入的情况下持续回复其他会话
        //     ⇒ 发送被暂停到用户下一次输入。判定 = starter 存在（已证同机）且
        //     localSends > maxLocalRelays 且 starter 链带循环信号（链 > 28 段或自身 token ≥ 10）。
        PeerLoopBrake brake = this.peerLoopBrake;
        if (brake != null && !request.sameSessionDelivery()) {
            PeerLoopBrake.Outcome brakeOutcome = brake.beginLocalSend(request.sourceSessionId());
            if (brakeOutcome.kind() == PeerLoopBrake.Kind.PAUSED) {
                return Receipt.of(Status.PAUSED, messageId, brakeOutcome.message());
            }
        }

        // 3. 来源会话标题快照（展示用；会话不存在/无标题 ⇒ null，⛔ 不阻断发送、不伪造）
        String sourceTitle = PeerSessionTitles.resolve(sessionService, request.sourceSessionId());

        // 3.5 [peer-name-parity · 2026-11-20 E2E 缺陷 3] 信封 from / from-name 的唯一来源 =
        //     ListAgents 打印的展示名（registry addressName）。旧逻辑 from-address 用
        //     SendMessageTool 的 `team-lead`、turn-0 重建又把 from-name 换成会话标题 ⇒
        //     接收方看到 from="team-lead [c1c93c]" 但 from-name="alpha-peer"（同一来源两个名字）。
        //     优先级（服务端数据，非模型自报）：ListAgents 展示名 → 会话标题 → 来源 session id。
        String sourceDisplayName = source.addressName();
        if (sourceDisplayName == null || sourceDisplayName.isBlank()) {
            sourceDisplayName = sourceTitle;
        }
        if (sourceDisplayName == null || sourceDisplayName.isBlank()) {
            sourceDisplayName = request.sourceSessionId();
        }
        String sourceAddress = PeerAddressing.formatAddress(sourceDisplayName, request.sourceSessionId());

        // 4. 信封（hop 链追加来源；标题随信封进队列 / held 行 / 事件）
        PeerEnvelope envelope;
        try {
            envelope = PeerEnvelope.create(messageId, sourceAddress,
                request.sourceSessionId(), sourceDisplayName, fromMode,
                request.inboundHopChain(), source.endpointEpoch(), request.summary(),
                request.notifyWhenIdle(), sourceTitle);
        } catch (IllegalArgumentException e) {
            return Receipt.of(Status.INVALID_TARGET, messageId, "invalid envelope: " + e.getMessage());
        }

        // 5. 环检测 / 链长（判定顺序对齐 CC：先 selfHops 再 chainLength；同会话记录投递不算环）
        if (!request.sameSessionDelivery()) {
            int selfHops = envelope.countHops(request.targetSessionId());
            if (selfHops >= PeerEnvelope.MAX_SELF_HOPS) {
                return Receipt.of(Status.LOOP_REJECTED, messageId,
                    "hop-loop: message has already passed through this session " + selfHops
                        + " times (max " + PeerEnvelope.MAX_SELF_HOPS
                        + "; a peer messaging loop)");
            }
        }
        if (envelope.hopChain().size() > PeerEnvelope.MAX_CHAIN_LENGTH) {
            return Receipt.of(Status.HOP_RUNAWAY, messageId,
                "hop-runaway: peer relay chain is too long (" + envelope.hopChain().size()
                    + " > " + PeerEnvelope.MAX_CHAIN_LENGTH + "; runaway forwarding)");
        }

        // 5.5 发送侧 pacer（CC Je().reserve()，偏移 324044952）：发前预留，失败立即拒
        //     errorClass=sender_paced。维度 = 目标会话 × 代次（重注册换代次 = 新桶）。
        //     同会话记录投递（子代理/teammate → 主会话）不占桶：目标就是本会话，没有「对方收件队列」。
        PeerSendPacer pacer = this.peerSendPacer;
        PeerSendPacer.Reservation reservation = null;
        String pacerKey = request.targetSessionId() + "#" + target.endpointEpoch();
        if (pacer != null && !request.sameSessionDelivery()) {
            reservation = pacer.reserve(pacerKey);
            if (reservation == null || !reservation.ok()) {
                int sentInBurst = reservation == null ? 0 : reservation.sentInBurst();
                return Receipt.of(Status.SENDER_PACED, messageId,
                    PeerRateLimits.pacedMessage(sentInBurst));
            }
        }

        // 6. 入站策略
        PeerInboundPolicy.Outcome outcome = peerInboundPolicy.decide(
            new PeerInboundPolicy.Input(request.targetSessionId(), null,
                envelope.fromMode(), target.permissionMode(), request.sameSessionDelivery()));
        if (outcome.decision() == PeerInboundPolicy.Decision.REFUSE) {
            refund(reservation, Status.REFUSED);
            return Receipt.of(Status.REFUSED, messageId, outcome.detail());
        }
        boolean derivedExempted = false;
        if (outcome.decision() == PeerInboundPolicy.Decision.HOLD) {
            // [derived 窄豁免 2026-11-22] 只对「创建者→刚创建的同级会话」的首条任务把 HOLD 转 accept。
            // 校验读 DB 的创建来源（derived_from_session_id），⛔ 不读模型正文/工具输入；
            // REFUSE / 权限 / 来源校验不受影响。
            if (exemption != HoldExemption.NONE && exemptionVerified(exemption, request)) {
                derivedExempted = true;
                log.info("PeerMessageRouter: derived 窄豁免 HOLD→ACCEPT source={} target={} exemption={} messageId={}",
                    request.sourceSessionId(), request.targetSessionId(), exemption, messageId);
                // 不 refund：本次仍要投递。
            } else {
                refund(reservation, Status.HELD);
                heldStore.hold(new HeldMessage(messageId, request.targetSessionId(), target.endpointEpoch(),
                    envelope, request.body(), outcome.reason(), Instant.now()));
                return Receipt.of(Status.HELD, messageId, outcome.reason() + ": " + outcome.detail());
            }
        }

        // 7. 投递（准入方式 = 服务端策略结论；derived 豁免时用固定 reason，⛔ 不由调用方指定）
        Admission admission = derivedExempted
            ? new Admission(PeerEnvelope.ADMISSION_AUTO, exemptionReason(exemption))
            : admissionOf(outcome);
        PeerEnvelope deliverEnvelope = admission.value() == null
            ? envelope
            : envelope.withAdmission(admission.value(), admission.reason());
        Receipt result = deliver(messageId, request.targetSessionId(), target.endpointEpoch(),
            deliverEnvelope, request.body(), request.notifyWhenIdle());
        if (result.status() != Status.ACCEPTED && result.status() != Status.DROPPED) {
            refund(reservation, result.status());
        }
        return result;
    }

    /**
     * 归还发送侧令牌（CC {@code refund}）：只有「消息确实没发出去」才归还；
     * {@link Status#DROPPED} 是接收侧已收到后丢弃，不归还（与 CC 一致）。
     */
    private static void refund(PeerSendPacer.Reservation reservation, Status status) {
        if (reservation != null) {
            log.debug("PeerMessageRouter: 发送侧 pacer 归还令牌（status={}）", status);
            reservation.refund();
        }
    }

    /**
     * 窄豁免校验：读数据库里的<b>创建来源</b>（{@code sessions.derived_from_session_id}）。
     *
     * <p>信任根只有服务端字段与数据库行，⛔ 不读模型正文 / 工具输入 / structuredOutput。
     * 校验失败一律按「不豁免」处理（回落 HOLD），并记 WARN。
     *
     * <p>语义：被创建会话的创建者 == 来源会话（两者是平级 peer，不是父子）。
     *
     * @param exemption 豁免类型
     * @param request   投递请求
     * @return true = 创建来源成立，可把 HOLD 转 accept
     */
    private boolean exemptionVerified(HoldExemption exemption, SendRequest request) {
        if (exemption == HoldExemption.NONE || sessionService == null
            || request == null || request.sourceSessionId() == null || request.targetSessionId() == null) {
            return false;
        }
        try {
            com.nexusai.repository.session.entity.SessionRecord target =
                sessionService.getRecord(request.targetSessionId());
            return target != null
                && request.sourceSessionId().equals(target.getDerivedFromSessionId());
        } catch (RuntimeException e) {
            log.warn("PeerMessageRouter: derived 豁免校验失败（按不豁免处理）source={} target={} err={}",
                request.sourceSessionId(), request.targetSessionId(), e.toString());
            return false;
        }
    }

    /** 豁免对应的准入理由（服务端审计/展示用）。 */
    private static String exemptionReason(HoldExemption exemption) {
        return exemption == HoldExemption.DERIVED_LAUNCH ? PeerEnvelope.REASON_DERIVED : null;
    }

    /**
     * 准入方式（服务端生成值 + 理由）：只用于<b>自动通过</b>的 ACCEPT 分支。
     *
     * <ul>
     *   <li>{@code SELF_SENT} → {@code auto / self-sent}；</li>
     *   <li>{@code MODE_MATCH} → {@code auto / mode-match}（含「发送方未声明模式但接收方仍会提示」）；</li>
     *   <li>{@code EXPLICIT_ACCEPT} → {@code auto / explicit-accept}；</li>
     *   <li>其它 reason（理论上到不了这里）→ {@code auto} + 枚举名小写（不伪造、不叫 default）。</li>
     * </ul>
     *
     * <p>⛔ 只在 {@link #send} 的 ACCEPT 分支调用；HOLD/REFUSE 不产生准入值（待用户处理）。
     */
    static Admission admissionOf(PeerInboundPolicy.Outcome outcome) {
        if (outcome == null || outcome.decision() != PeerInboundPolicy.Decision.ACCEPT) {
            return new Admission(null, null);
        }
        String reason = switch (outcome.reason()) {
            case SELF_SENT -> PeerEnvelope.REASON_SELF_SENT;
            case MODE_MATCH -> PeerEnvelope.REASON_MODE_MATCH;
            case EXPLICIT_ACCEPT -> PeerEnvelope.REASON_EXPLICIT_ACCEPT;
            default -> outcome.reason().name().toLowerCase(java.util.Locale.ROOT);
        };
        return new Admission(PeerEnvelope.ADMISSION_AUTO, reason);
    }

    /** 准入结论（值 + 理由）；null 值 = 未知（不写）。 */
    record Admission(String value, String reason) {}

    /**
     * 批准一条 held 消息（<b>批准即结算</b> · 严格 CC 2.1.328 语义）。
     *
     * <p><b>一次性取出 → 立即投递 → 成功/失败即结算</b>：
     * <ol>
     *   <li>{@link HeldPeerStore#take} 一次性把 pending 行取出（并发/重复批准只有一个胜出）；</li>
     *   <li>立即重新校验（目标/来源仍 live、设置不是 refuse）—— <b>不通过也已是终态</b>：
     *       调用 {@link HeldPeerStore#settleFailed} 结算（refused / receiver-closed /
     *       source-not-live）并给发送方落一条失败通知；</li>
     *   <li>投递成功 ⇒ {@link HeldPeerStore#settleAccepted}（行 accepted）；
     *       投递失败 ⇒ {@link HeldPeerStore#settleFailed}（行 expired + 失败通知）——
     *       ⛔ 不留「已批准待投递」的行，⛔ 不能撤销、不能二次批准。</li>
     * </ol>
     *
     * <p><b>服务端一次性放行标记</b>：投递时用 {@link PeerEnvelope#withPreApproval(long)} 派生带
     * 批准标记的信封（只有本方法能设置）；消费前复核见到标记时<b>跳过入站策略重判</b>，只校验
     * 目标在线与代次 —— 否则 hold 设置下批准后又被判 HOLD，消息静默丢失（2026-11-20 E2E）。
     * 该标记的新用途是「消费期不重判策略」，<b>不再是</b>「待消费再复核」的中间态。
     *
     * <p><b>重启后的代次规则（与发送时的严格 epoch 不同）</b>：
     * <ol>
     *   <li>存储行里的 {@code targetEndpointEpoch} 只是<b>发送时快照</b>，不是授权凭据；</li>
     *   <li>目标/来源都<b>按 sessionId 重新 resolve 当前 live 记录</b>；目标未 live → NO_LIVE_SESSION，
     *       来源未 live → SOURCE_NOT_LIVE；</li>
     *   <li>快照代次与当前代次不符（含重启后 store 置 -1 的行）时<b>不判死</b>，记日志后继续，
     *       投递使用<b>当前 resolve 到的</b> target epoch（并把它绑定进批准标记）；</li>
     *   <li>策略只保留「设置=refuse」这一条否决（{@link PeerInboundPolicy#decideExplicitApproval}）。</li>
     * </ol>
     *
     * @param messageId 消息 id
     * @param principal 审批主体
     * @return 回执（NO_LIVE_SESSION / SOURCE_NOT_LIVE / REFUSED / APPROVAL_REJECTED / 投递状态；
     *         ⛔ 不再返回 HELD —— 显式批准不再被 hold 设置否决）。
     *         <b>除 APPROVAL_REJECTED 外（行不存在/已被处理），任何结果都已结算为终态。</b>
     */
    public Receipt approveHeld(String messageId, ApprovalPrincipal principal) {
        String approvalMessageId = messageId == null ? "" : messageId;
        if (heldStore == null || peerSessionRegistry == null || peerInboundPolicy == null) {
            return Receipt.of(Status.NOT_WIRED, approvalMessageId, "peer routing is not wired");
        }
        if (!isTrusted(principal)) {
            return Receipt.of(Status.APPROVAL_REJECTED, approvalMessageId,
                "approval requires a trusted UI principal");
        }

        HeldMessage taken;
        // 一次性取出在同一临界区内：并发/连续第二次批准看不到该行 → 900（不重复投递）。
        // 投递在临界区外，避免慢投递阻塞同会话其它审批。
        synchronized (heldStore) {
            if (findHeld(principal.sessionId(), approvalMessageId) == null) {
                return Receipt.of(Status.APPROVAL_REJECTED, approvalMessageId,
                    "no pending held message with this id for the requesting session "
                        + "(or it is already accepted/rejected/expired)");
            }
            taken = heldStore.take(approvalMessageId, principal);
            if (taken == null) {
                return Receipt.of(Status.APPROVAL_REJECTED, approvalMessageId,
                    "held message ownership check failed or it is already accepted");
            }
        }

        Validation validation = revalidateForApproval(taken);
        if (validation.refusal() != null) {
            // 已取出 ⇒ 立即结算（CC：批准失败 = 结算并回执发送方），⛔ 不回 pending。
            settleRejectedApproval(taken, validation.refusal().status(), validation.refusal().detail());
            return validation.refusal();
        }
        long effectiveTargetEpoch = validation.targetEndpointEpoch();

        // 服务端一次性批准标记：只有本成功路径派生（消费侧据此跳过策略重判）。
        // 准入方式 = approved / user-approved（用户显式接受；⛔ 回落成 auto 就是伪造来源）。
        PeerEnvelope approvedEnvelope = taken.envelope()
            .withPreApproval(effectiveTargetEpoch)
            .withAdmission(PeerEnvelope.ADMISSION_APPROVED, PeerEnvelope.REASON_USER_APPROVED);
        Receipt result = deliver(approvalMessageId, taken.targetSessionId(),
            effectiveTargetEpoch, approvedEnvelope, taken.body(),
            taken.envelope().notifyWhenIdle());
        if (result.status() == Status.ACCEPTED) {
            heldStore.settleAccepted(taken);
            log.info("PEER HELD approve: 批准即结算 → accepted messageId={} target={} epoch={}",
                approvalMessageId, taken.targetSessionId(), effectiveTargetEpoch);
        } else {
            settleRejectedApproval(taken, result.status(), result.detail());
        }
        return result;
    }

    /**
     * 批准失败结算：行落终态 {@code expired}，并让 store 给发送方写失败通知。
     *
     * <p>reason 映射（CC 回执语义）：{@code REFUSED → refused}；目标/来源不在线 →
     * {@code receiver-closed} / {@code source-not-live}；其余投递失败（限速/队满/未接线等）→
     * {@code dropped}。⛔ 不新增 dropped/failed 状态值（状态集合仍只有 pending/accepted/rejected/expired）。
     */
    private void settleRejectedApproval(HeldMessage taken, Status status, String detail) {
        String reason = switch (status) {
            case REFUSED -> PeerFailureNotifier.REASON_REFUSED;
            case NO_LIVE_SESSION -> PeerFailureNotifier.REASON_RECEIVER_CLOSED;
            case SOURCE_NOT_LIVE -> PeerFailureNotifier.REASON_SOURCE_NOT_LIVE;
            default -> PeerFailureNotifier.REASON_DROPPED;
        };
        try {
            heldStore.settleFailed(taken, reason);
        } catch (RuntimeException e) {
            log.warn("PEER HELD approve: 失败结算异常（消息未投递，按终态处理）messageId={} status={} err={}",
                taken.messageId(), status, e.toString());
            return;
        }
        log.warn("PEER HELD approve 未达成 → 终态 expired 并通知发送方 messageId={} target={} status={} reason={} detail={}",
            taken.messageId(), taken.targetSessionId(), status, reason, detail);
    }

    /** 在 store 中查本会话的某条 held（不移除）。 */
    private HeldMessage findHeld(String targetSessionId, String messageId) {
        for (HeldMessage m : heldStore.list(targetSessionId)) {
            if (messageId.equals(m.messageId())) {
                return m;
            }
        }
        return null;
    }

    /** 审批前校验结果：refusal 非 null = 失败；否则 targetEndpointEpoch = 投递应使用的当前代次。 */
    private record Validation(Receipt refusal, long targetEndpointEpoch) {

        static Validation ok(long targetEndpointEpoch) {
            return new Validation(null, targetEndpointEpoch);
        }

        static Validation fail(Receipt refusal) {
            return new Validation(refusal, -1L);
        }
    }

    /**
     * 批准前的重新校验（只读，不抢占）。
     *
     * <p><b>代次规则</b>：存储代次是发送时快照，重启后必然失效。这里按 sessionId 重新 resolve
     * 当前 live 记录；快照代次（含 -1）与当前不符只记日志，<b>不判死</b>；投递使用当前代次。
     * 来源同理（来源只需仍 live，不要求代次相等）。
     *
     * <p><b>策略规则</b>：用户的显式批准 = 一次性放行，只重查「设置不是 refuse」
     * （{@link PeerInboundPolicy#decideExplicitApproval}）；⛔ 不再按 auto/hold 重判 —— 否则
     * hold 设置下批准永远 HELD，功能死锁。
     *
     * @return 失败回执或「通过 + 当前目标代次」（调用方必须保持 held 不变）
     */
    private Validation revalidateForApproval(HeldMessage taken) {
        if (taken.envelope() == null || taken.envelope().fromSessionId() == null) {
            return Validation.fail(Receipt.of(Status.APPROVAL_REJECTED, taken.messageId(),
                "held message has no source envelope"));
        }
        PeerSessionRegistry.LiveSession target = peerSessionRegistry
            .resolve(taken.targetSessionId()).orElse(null);
        if (target == null || !target.accepting() || target.closing()) {
            return Validation.fail(Receipt.of(Status.NO_LIVE_SESSION, taken.messageId(),
                "target is no longer live: " + taken.targetSessionId()));
        }
        long currentTargetEpoch = target.endpointEpoch();
        if (taken.targetEndpointEpoch() >= 0
            && taken.targetEndpointEpoch() != currentTargetEpoch) {
            log.info("PEER HELD 审批：存储目标代次={} 与当前代次={} 不符（重启/重注册）→ "
                    + "按 sessionId 重新 resolve 后继续 messageId={} target={}",
                taken.targetEndpointEpoch(), currentTargetEpoch, taken.messageId(),
                taken.targetSessionId());
        }

        PeerSessionRegistry.LiveSession source = peerSessionRegistry
            .resolve(taken.envelope().fromSessionId()).orElse(null);
        if (source == null || !source.accepting() || source.closing()) {
            return Validation.fail(Receipt.of(Status.SOURCE_NOT_LIVE, taken.messageId(),
                "source is no longer live: " + taken.envelope().fromSessionId()));
        }
        if (taken.envelope().sourceEndpointEpoch() != source.endpointEpoch()) {
            log.debug("PEER HELD 审批：来源代次快照={} 与当前={} 不符（来源仍 live，继续）messageId={}",
                taken.envelope().sourceEndpointEpoch(), source.endpointEpoch(), taken.messageId());
        }

        boolean selfSent = taken.envelope().fromSessionId().equals(taken.targetSessionId());
        // 只有「设置=refuse」这一条否决 —— ⛔ 不按 auto/hold 重判（hold 用户显式批准是一次性放行）。
        // 严格 CC 语义下没有 approved 行，因此不再存在「已批准不受设置变化影响」的分支。
        PeerInboundPolicy.Outcome outcome = peerInboundPolicy.decideExplicitApproval(
            new PeerInboundPolicy.Input(taken.targetSessionId(), null,
                taken.envelope().fromMode(), target.permissionMode(), selfSent));
        if (outcome.decision() == PeerInboundPolicy.Decision.REFUSE) {
            return Validation.fail(Receipt.of(Status.REFUSED, taken.messageId(), outcome.detail()));
        }
        return Validation.ok(currentTargetEpoch);
    }

    /**
     * 列出该审批主体所属会话的 held 消息。
     *
     * @param targetSessionId 目标会话
     * @param principal       审批主体
     * @return held 列表；主体不可信/归属不符 → 空列表
     */
    public List<HeldMessage> listHeld(String targetSessionId, ApprovalPrincipal principal) {
        if (!isTrusted(principal) || targetSessionId == null
            || !targetSessionId.equals(principal.sessionId())) {
            return List.of();
        }
        return heldStore.list(targetSessionId);
    }

    /**
     * 列出本会话的 held 视图（含 status / seenAt）。
     *
     * <p>展示口径 = {@code pending / rejected}（已拒绝卡片可回看，不可操作）；
     * {@code accepted / expired} 不出现在列表（⛔ 不再有 approved）。计数口径由前端按
     * {@code status==='pending'} 计算。
     *
     * @param targetSessionId 目标会话
     * @param principal       审批主体（不可信/归属不符 → 空列表）
     * @return 视图列表（无 DB 时由内存项投影，status=pending、seenAt=null）
     */
    public List<HeldView> listHeldViews(String targetSessionId, ApprovalPrincipal principal) {
        if (!isTrusted(principal) || targetSessionId == null
            || !targetSessionId.equals(principal.sessionId())) {
            return List.of();
        }
        return heldStore.listViews(targetSessionId);
    }

    /**
     * 标记已读（幂等）。
     *
     * @param targetSessionId 目标会话（必须等于主体会话，防跨会话改他人已读态）
     * @param messageIds      为空 = 该会话全部未读 pending
     * @param principal       审批主体
     * @return 实际更新行数（重复调用返回 0）
     */
    public int markSeen(String targetSessionId, List<String> messageIds, ApprovalPrincipal principal) {
        if (!isTrusted(principal) || targetSessionId == null
            || !targetSessionId.equals(principal.sessionId())) {
            return 0;
        }
        return heldStore.markSeen(targetSessionId, messageIds);
    }

    /**
     * 按会话聚合的待处理条数（{@code GET /api/v1/peer-inbox/counts} 与聚合事件同源同口径）。
     *
     * <p>只返回有待处理项的会话；存储未接线 → 空 map（⛔ 不造「全量会话 0 条」的假数据）。
     *
     * @return 会话 id → 待处理条数
     */
    public java.util.Map<String, Long> pendingCounts() {
        HeldPeerStore store = heldStore;
        if (store == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> counts = store.pendingCounts();
        return counts == null ? java.util.Map.of() : counts;
    }

    /**
     * 按会话聚合的待处理条数 + 最早一条（FIFO 选目用；只返回 &gt; 0 的会话）。
     *
     * <p>存储未接线 / 实现不支持 → 空列表（前端退回计数排序，⛔ 不伪造时间）。
     */
    public java.util.List<SessionPending> pendingSessionCounts() {
        HeldPeerStore store = heldStore;
        if (store == null) {
            return java.util.List.of();
        }
        java.util.List<SessionPending> rows = store.pendingSessionCounts();
        return rows == null ? java.util.List.of() : rows;
    }

    /**
     * 拒绝（丢弃）一条 held 消息。
     *
     * <p>归属由 store 内条件写校验（DB: {@code WHERE message_id=? AND target_session_id=? AND
     * status='pending'}）；并发 approve 只有一个能改到这一行。
     *
     * @param messageId 消息 id
     * @param principal 审批主体
     * @return true = 本次调用把它转为 rejected；false = 主体不可信 / 归属不符 / 不存在 / 已被处理
     */
    public boolean rejectHeld(String messageId, ApprovalPrincipal principal) {
        if (!isTrusted(principal) || messageId == null) {
            return false;
        }
        HeldPeerStore store = heldStore;
        HeldMessage candidate = store.list(principal.sessionId()).stream()
            .filter(m -> messageId.equals(m.messageId()))
            .findFirst().orElse(null);
        if (candidate == null) {
            return false;
        }
        boolean rejected = store.reject(messageId, principal);
        if (rejected) {
            // 拒绝已生效后才通知；通知失败/发送方不可达只记日志（⛔ 不得反过来影响拒绝结果）。
            notifyRejection(candidate, REJECT_REASON_USER_DECLINED);
        }
        return rejected;
    }

    /**
     * 批量拒绝本会话的全部 pending（用户已裁定：允许批量拒绝，前端做二次确认）。
     *
     * <p><b>归属</b>：{@code targetSessionId} 必须等于主体的 {@code sessionId}（路径值），
     * 不可信主体 / 错归属 → 0 且不改任何行；<b>不跨会话</b>。
     * <b>终态</b>：rejected 不可恢复，不写会话历史、不唤醒目标；不提供「稍后/忽略」状态。
     * <b>并发</b>：DB 实现是单语句 {@code UPDATE ... WHERE status='pending'}，
     * 与 {@link #approveHeld} 竞争同一行时只有一个能改到（不会既 accepted 又 rejected）。
     *
     * @param targetSessionId 目标会话
     * @param principal       审批主体
     * @return 实际拒绝条数（无 pending → 0）
     */
    public int rejectAll(String targetSessionId, ApprovalPrincipal principal) {
        if (!isTrusted(principal) || targetSessionId == null
            || !targetSessionId.equals(principal.sessionId())) {
            return 0;
        }
        HeldPeerStore store = heldStore;
        // 先取 pending 候选（含信封；通知需要发送方会话），再执行单语句条件 UPDATE。
        List<HeldMessage> candidates = store.list(targetSessionId).stream()
            .filter(m -> HeldMessage.STATUS_PENDING.equals(m.effectiveStatus()))
            .toList();
        int rejected = store.rejectAll(targetSessionId, principal);
        if (rejected <= 0 || candidates.isEmpty()) {
            return rejected;
        }
        // 并发 approve 可能抢走其中某行 ⇒ 逐条按「权威落库状态」确认后才通知（不得误报被拒）。
        // [通知口径] 每条被拒消息各发一条（各自独立 messageId；批量拒绝不合并成一条）。
        for (HeldMessage candidate : candidates) {
            if (HeldMessage.STATUS_REJECTED.equals(store.statusOf(candidate.messageId()))) {
                notifyRejection(candidate, REJECT_REASON_USER_DECLINED);
            }
        }
        return rejected;
    }

    /**
     * 结算本会话全部待收件为 expired（只供「会话关闭/删除」路径内部调用）。
     *
     * <p><b>WHY 不暴露为 HTTP 端点</b>：这是接收方生命周期事件（close/delete）的副作用，
     * 不是用户可主动触发的操作。前端能做的只有批准/拒绝。
     *
     * @param targetSessionId 接收方会话
     * @param reason          通知原因（{@code receiver-closed}）
     * @return 实际转 expired 的行数
     */
    public int expireAllHeld(String targetSessionId, String reason) {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return 0;
        }
        HeldPeerStore store = heldStore;
        if (store == null) {
            return 0;
        }
        return store.expireAll(targetSessionId, reason);
    }

    /**
     * 拒绝成功后通知<b>发送方会话</b>（best-effort；不影响拒绝结果）。
     *
     * <p><b>通道</b>：{@link RejectionNotifier}（B = STOMP UI 事件）——⛔ 不入 NotificationQueue、
     * 不写会话历史、不产生模型可见消息，因此<b>不会唤醒</b>已退出/已删除的发送方会话。
     * <b>可达性</b>：以模块内唯一 live 真源（{@link PeerSessionRegistry}）判定；发送方未登记/
     * 正在关闭 ⇒ 只记日志。<b>一次性</b>：本地按 messageId 去重（拒绝本身也是 pending→rejected 的
     * 终态转换，同一消息不可能被拒两次）。
     *
     * @param message 被拒行（含发送方信封；可 null）
     * @param reason  简短原因
     */
    private void notifyRejection(HeldMessage message, String reason) {
        if (message == null || message.messageId() == null || message.messageId().isBlank()) {
            return;
        }
        PeerEnvelope envelope = message.envelope();
        String sourceSessionId = envelope == null ? null : envelope.fromSessionId();
        if (sourceSessionId == null || sourceSessionId.isBlank()) {
            log.info("PEER REJECT 通知跳过：被拒消息没有来源会话 messageId={}（拒绝已生效）",
                message.messageId());
            return;
        }
        // ① 落库通知（真实记录）：不依赖发送方是否在线 —— 离线/重启后仍能在发送方会话里看到。
        //    发送方已删除 ⇒ notifier 只记日志（⛔ 不阻断拒绝结果）。notifier 内按 messageId 去重，
        //    落库失败会释放去重键，所以这里每次调用都尝试（可重试）。
        persistRejectionNotice(message, sourceSessionId, reason);
        // ② 即时提示（STOMP）：只在发送方仍 live 时推，已退出者不唤醒（原有契约不变）。
        if (!rejectionNotified.add(message.messageId())) {
            return;
        }
        if (!isSourceReachable(sourceSessionId)) {
            log.info("PEER REJECT 实时提示跳过：发送方会话不可达/已退出 source={} messageId={}"
                + "（通知已落库，拒绝已生效）", sourceSessionId, message.messageId());
            return;
        }
        RejectionNotifier notifier = this.rejectionNotifier;
        if (notifier == null) {
            log.info("PEER REJECT 通知未接线（无 UI 出口）：source={} messageId={}（拒绝已生效）",
                sourceSessionId, message.messageId());
            return;
        }
        RejectionNotice notice = new RejectionNotice(
            message.messageId(), message.targetSessionId(),
            PeerSessionTitles.resolve(sessionService, message.targetSessionId()),
            sourceSessionId,
            envelope.sourceEndpointEpoch(), reason, Instant.now());
        try {
            if (!notifier.notifyRejected(notice)) {
                log.info("PEER REJECT 通知未送达（best-effort）：source={} messageId={}（拒绝已生效）",
                    sourceSessionId, message.messageId());
            }
        } catch (RuntimeException e) {
            log.warn("PEER REJECT 通知异常（忽略，不影响拒绝结果）：messageId={} err={}",
                message.messageId(), e.toString());
        }
    }

    /**
     * 把拒绝写成发送方会话记录里的一条 meta 通知（best-effort；不影响拒绝结果）。
     *
     * <p><b>WHY 不走 {@link RejectionNotifier}</b>：那个出口只在发送方 live 时被调用（UI 即时提示），
     * 而「通知」必须落库 —— 发送方离线/重启后仍要能看到。已删除 ⇒ notifier 判掉并只记日志。
     */
    private void persistRejectionNotice(HeldMessage message, String sourceSessionId, String reason) {
        PeerFailureNotifier sink = failureNotifier;
        if (sink == null) {
            log.debug("PEER REJECT 通知未落库：PeerFailureNotifier 未接线 messageId={}", message.messageId());
            return;
        }
        try {
            sink.persistFailure(new PeerFailureNotifier.FailureNotice(
                message.messageId(), sourceSessionId, message.targetSessionId(),
                PeerSessionTitles.resolve(sessionService, message.targetSessionId()),
                reason));
        } catch (RuntimeException e) {
            log.warn("PEER REJECT 通知落库异常（拒绝已生效，仅记日志）messageId={} err={}",
                message.messageId(), e.toString());
        }
    }

    /**
     * 发送方是否仍可达：模块内唯一真源 = live endpoint 注册表。
     *
     * <p>未登记 / {@code closing} / {@code accepting=false} 一律视为不可达（与 peer 投递同一判据）。
     * 注册表缺失（非 Spring 直构）⇒ 无法证明可达 ⇒ 不投递（只记日志）。
     */
    private boolean isSourceReachable(String sourceSessionId) {
        PeerSessionRegistry registry = peerSessionRegistry;
        if (registry == null || sourceSessionId == null || sourceSessionId.isBlank()) {
            return false;
        }
        PeerSessionRegistry.LiveSession live = registry.resolve(sourceSessionId).orElse(null);
        return live != null && live.accepting() && !live.closing();
    }

    /** 正文总行数（'\n' 分隔；空正文 = 0；CRLF 只算一次换行）。 */
    public static int bodyLineCount(String body) {
        if (body == null || body.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 正文总字数（Unicode 码点计数；CJK/emoji 均按 1 计）。 */
    public static int bodyCharCount(String body) {
        return body == null ? 0 : body.codePointCount(0, body.length());
    }

    private Receipt deliver(String messageId, String targetSessionId, long targetEndpointEpoch,
                            PeerEnvelope envelope, String body, boolean notifyWhenIdle) {
        DeliveryPort port = deliveryPort;
        if (port == null) {
            return Receipt.of(Status.NOT_WIRED, messageId, "peer delivery port is not wired");
        }
        Receipt delivered;
        try {
            delivered = port.deliver(targetSessionId, targetEndpointEpoch, envelope, body);
        } catch (RuntimeException e) {
            return Receipt.of(Status.DELIVERY_UNCONFIRMED, messageId, "delivery failed: " + e.getMessage());
        }
        if (delivered == null) {
            return Receipt.of(Status.DELIVERY_UNCONFIRMED, messageId, "delivery port returned no receipt");
        }
        if (delivered.status() != Status.ACCEPTED) {
            Status status = delivered.status();
            if (status == Status.NOT_WIRED || status == Status.NOT_DELIVERED
                || status == Status.DROPPED || status == Status.PAUSED
                || status == Status.SENDER_PACED) {
                // 接收侧准入丢弃（CC 异步 dropped 回执）必须原样上抛：detail 带 CC 原文原因，
                // 调用方据此给出「dropped」而不是「unconfirmed」（不得把明确拒绝说成未确认）。
                return new Receipt(status, messageId, delivered.detail(), null);
            }
            return Receipt.of(Status.DELIVERY_UNCONFIRMED, messageId,
                "delivery port status=" + status + " detail=" + delivered.detail());
        }

        String idleSubscription = null;
        if (notifyWhenIdle) {
            PeerIdleSubscriptions subs = idleSubscriptions;
            if (subs == null) {
                idleSubscription = "not_subscribed:no-subscription-service";
            } else if (subs.subscribe(envelope, targetSessionId, targetEndpointEpoch)) {
                idleSubscription = "subscribed";
            } else {
                idleSubscription = "not_subscribed:subscription-rejected";
            }
        }
        return new Receipt(Status.ACCEPTED, messageId,
            delivered.detail() == null ? "accepted by this process" : delivered.detail(),
            idleSubscription);
    }

    private static boolean isTrusted(ApprovalPrincipal principal) {
        return principal != null && principal.trustedUi() && principal.sessionId() != null;
    }
}
