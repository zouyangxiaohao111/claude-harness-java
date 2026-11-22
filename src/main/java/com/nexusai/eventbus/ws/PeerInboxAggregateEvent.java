package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 跨会话待收件 · <b>聚合通道</b>事件（单一 topic {@code /topic/peer-inbox} 推所有会话的变动）。
 *
 * <h2>为什么再开一条通道</h2>
 * 既有的 {@link PeerInboxHoldEvent} 只发到 {@code /topic/sessions/{targetSessionId}/peer-inbox}
 * （按目标会话隔离、只有 hold 一种事件）。前端要显示「每个会话还有几件待人处理」时，按会话订阅
 * 意味着要订阅 N 个 topic、且不知道哪些会话有待处理项。本通道把<b>所有会话</b>的变动汇总到一个 topic，
 * 并带上 {@link #getPendingCount()}，前端据此直接更新角标/计数，不必逐会话轮询列表。
 *
 * <p><b>与旧通道的关系</b>：旧通道<b>保留</b>（不删除）；本通道是增量。同一次 hold 两边都发，
 * {@link #getItem()} 的字段就是旧事件里的那一条（同源构造 ⇒ 内容一致）。
 *
 * <h2>载荷契约（前后端共用，⛔ 字段名不可漂移）</h2>
 * <pre>{ "type":"peer.inbox.hold|peer.inbox.updated|peer.inbox.removed",
 *   "seq": 123,                 // 本后端实例内单调递增（进程重启归零，前端据此触发一次对账）
 *   "targetSessionId":"sess-…", // 事件归属会话
 *   "pendingCount": 2,          // 该会话「仍需处理」的条数（口径见下）
 *   "item": { … } }             // 仅 hold 携带；updated/removed 为 null（不序列化）
 * </pre>
 *
 * <h2>pendingCount 口径</h2>
 * 只数 {@code pending}。<b>批准即结算</b>（严格 CC 语义）：成功 → accepted、失败 → expired，
 * 两者都即时离开待办，因此不存在「已批准待消费」需要另减的部分；
 * {@code rejected / accepted / expired} 一律不计。hold → +1，批准（无论成败）/ 拒绝 / 过期 → −1。
 *
 * <p><b>seq 语义</b>：后端实例内自增（1 起），<b>不是</b>会话内序号（那个是 {@link StreamEventSeq}）。
 * 进程重启后重置 ⇒ 前端发现 seq 比记忆值小 ⇒ 调 {@code GET /api/v1/peer-inbox/counts} 对账，
 * 属预期行为。
 *
 * <p><b>失败不伪装</b>：事件是「多快看到」的优化，不是真源。发布失败/无订阅者只影响实时性 ——
 * DB 与 counts 接口始终是真源。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PeerInboxAggregateEvent {

    /** 新条目到达（与旧通道 {@link PeerInboxHoldEvent} 同一次发布，item 携带该条）。 */
    public static final String TYPE_HOLD = "peer.inbox.hold";

    /** 条目状态变了但仍需处理（如投递失败退回可再次批准）：计数可能不变或变大。 */
    public static final String TYPE_UPDATED = "peer.inbox.updated";

    /** 条目不再需要处理（接受/拒绝/过期/会话关闭）：计数只减不增。 */
    public static final String TYPE_REMOVED = "peer.inbox.removed";

    private final long seq;
    private final String type;
    private final String targetSessionId;
    private final long pendingCount;
    private final Item item;

    public PeerInboxAggregateEvent(long seq, String type, String targetSessionId,
                                   long pendingCount, Item item) {
        this.seq = seq;
        this.type = type;
        this.targetSessionId = targetSessionId;
        this.pendingCount = pendingCount;
        this.item = item;
    }

    public long getSeq() {
        return seq;
    }

    public String getType() {
        return type;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public long getPendingCount() {
        return pendingCount;
    }

    /** 仅 hold 事件携带；其余为 null（{@code @JsonInclude(NON_NULL)} ⇒ 不输出该键）。 */
    public Item getItem() {
        return item;
    }

    /**
     * 条目快照 · 字段与 {@link PeerInboxHoldEvent} 的条目部分<b>逐个同名</b>（内容一致）。
     *
     * <p>{@code targetSessionId} 不在条目里 —— 它由外层事件给出，避免同一事实两处表达。
     */
    public record Item(String messageId, String sourceSessionId, String sourceTitle,
                       String sourceKind, String fromMode, String summary, String body,
                       String reason, String status, Instant createdAt, Instant seenAt,
                       String admission, String admissionReason) {

        /**
         * 从旧通道事件投影（同一份快照 ⇒ 两次发布内容一致；⛔ 不重新查库，避免两路看到不同快照）。
         *
         * @param event 旧通道事件（null → null）
         * @return 条目快照
         */
        public static Item of(PeerInboxHoldEvent event) {
            if (event == null) {
                return null;
            }
            return new Item(
                event.getMessageId(),
                event.getSourceSessionId(),
                event.getSourceTitle(),
                event.getSourceKind(),
                event.getFromMode(),
                event.getSummary(),
                event.getBody(),
                event.getReason(),
                event.getStatus(),
                event.getCreatedAt(),
                event.getSeenAt(),
                event.getAdmission(),
                event.getAdmissionReason());
        }
    }
}
