package com.nexusai.application.agent.messaging;

/**
 * 跨会话消息的限速/去重/队容/转发预算常量 · 逐字对齐 CC 2.1.328
 * （{@code claude.exe} 偏移 324034010 / 324038263 / 324038346，Python 按字节读出）。
 *
 * <p><b>CC 原文（入站准入配置对象，偏移 324034010）</b>：
 * <pre>
 * var q={bucketCapacity:30,refillPerSecond:0.5,dedupWindowMs:30000,
 *        maxSelfHops:10,maxChainLength:28,maxTrackedSenders:256};
 * </pre>
 *
 * <p><b>CC 原文（收件队列 + 转发预算默认值，偏移 324038263 / 324038346）</b>：
 * <pre>
 * var Ue=50, oe={...q,maxQueuedPeerMessages:Ue,maxDesktopForwards:1e5,maxLocalRelays:1e5,
 *                countLocalHops:!1},
 *     W={maxDesktopForwards:20,maxLocalRelays:20,countLocalHops:!0}, L=oe, ...
 * maxDesktopForwards: v().int().min(1).max(L.maxDesktopForwards)
 *     .default(L.maxDesktopForwards).catch(W.maxDesktopForwards),
 * maxLocalRelays:    v().int().min(1).max(L.maxLocalRelays)
 *     .default(L.maxLocalRelays).catch(W.maxLocalRelays),
 * </pre>
 * 即：<b>缺省 = 100000；配置存在但非法/越界 ⇒ 回退 20</b>（{@code .default()} 在
 * {@code .catch()} 之前生效 —— 只有「缺失」走 100000，「给了但不合法」走 20）。
 *
 * <p><b>CC 原文（丢弃原因的发送方文案，偏移 324034215 / 324035701 / 325455402）</b>：
 * <pre>
 * var Ce={"rate-limited":"sender exceeded the peer message rate limit",
 *         duplicate:"identical to the previous message from this sender",
 *         "hop-loop":"message has already passed through this session (a peer messaging loop)",
 *         "hop-runaway":"peer relay chain is too long (runaway forwarding)",
 *         "queue-full":"this session has too many undelivered peer messages queued"};
 * function KBa(e){let{from:n}=ee(e),{name:r}=ee(e),i=r?`@${r} (${n})`:n,
 *   d=e.suppressed>0?` (+${e.suppressed} similar ${I(e.suppressed,"drop")} suppressed)`:"";
 *   return `Dropped a peer message from ${i}: ${Ce[e.reason]}.${d}`}
 * </pre>
 *
 * <p><b>CC 原文（发送侧 pacer 失败文案，偏移 324044952）</b>：
 * <pre>
 * `Too many messages to this session just now: ${e} were sent recently and more would be
 *  dropped by its rate limit, so this one was not sent. Batch what remains into one message,
 *  or wait a little before sending more.`   // errorClass = "sender_paced"
 * </pre>
 */
public final class PeerRateLimits {

    private PeerRateLimits() {
    }

    /** 令牌桶容量（CC {@code bucketCapacity:30}）。 */
    public static final int BUCKET_CAPACITY = 30;

    /** 每秒回填令牌数（CC {@code refillPerSecond:0.5}；满血约 60s）。 */
    public static final double REFILL_PER_SECOND = 0.5;

    /** 同文去重窗口（CC {@code dedupWindowMs:30000}）。 */
    public static final long DEDUP_WINDOW_MS = 30_000L;

    /** 最多跟踪的发送方（CC {@code maxTrackedSenders:256}；超出按 LRU 逐出）。 */
    public static final int MAX_TRACKED_SENDERS = 256;

    /** 收件队列上限（CC {@code maxQueuedPeerMessages:50}；拒新、不淘汰旧）。 */
    public static final int MAX_QUEUED_PEER_MESSAGES = 50;

    /** 转发预算缺省值（CC {@code maxLocalRelays/maxDesktopForwards:1e5}）。 */
    public static final int DEFAULT_FORWARD_BUDGET = 100_000;

    /** 转发预算「配置非法」回退值（CC {@code W={...:20,...}}）。 */
    public static final int FALLBACK_FORWARD_BUDGET = 20;

    /** 发送侧 pacer 的错误类名（CC {@code errorClass="sender_paced"}）。 */
    public static final String ERROR_CLASS_SENDER_PACED = "sender_paced";

    /** 丢弃原因（CC {@code Le} 的五个键）。 */
    public enum Reason {
        RATE_LIMITED("rate-limited"),
        DUPLICATE("duplicate"),
        HOP_LOOP("hop-loop"),
        HOP_RUNAWAY("hop-runaway"),
        QUEUE_FULL("queue-full"),
        /** 本地转发预算 / 自动循环刹车（CC {@code aVn/lVn} 的 loop-paused）。 */
        RELAY_BUDGET("relay-budget");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        /** 线上原因串（= CC 的 reason 字面量）。 */
        public String wire() {
            return wire;
        }
    }

    /**
     * 原因 → 发送方可见原文（CC {@code Ce} 逐字）。
     *
     * @param reason 原因（null → 空串，不伪造）
     * @return 英文原文
     */
    public static String textOf(Reason reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case RATE_LIMITED -> "sender exceeded the peer message rate limit";
            case DUPLICATE -> "identical to the previous message from this sender";
            case HOP_LOOP -> "message has already passed through this session (a peer messaging loop)";
            case HOP_RUNAWAY -> "peer relay chain is too long (runaway forwarding)";
            case QUEUE_FULL -> "this session has too many undelivered peer messages queued";
            case RELAY_BUDGET -> "this session keeps answering other sessions, which looks like "
                + "an automatic loop";
        };
    }

    /**
     * 异步丢弃回执文案（CC {@code KBa} 逐字）：
     * {@code Dropped a peer message from @name (from): <reason text>.}
     *
     * @param from       发送方可回信地址（服务端生成；null → 空串）
     * @param name       发送方展示名（可 null/空 = 不带 {@code @name} 前缀）
     * @param reason     丢弃原因
     * @param suppressed 同批被合并抑制的条数（0 = 不追加）
     * @return 发送方可见的英文原文
     */
    public static String dropNotice(String from, String name, Reason reason, int suppressed) {
        String sender = from == null ? "" : from;
        String display = name == null || name.isBlank() ? null : name;
        String who = display == null ? sender : "@" + display + " (" + sender + ")";
        String extra = suppressed > 0 ? " (+" + suppressed + " similar drop suppressed)" : "";
        return "Dropped a peer message from " + who + ": " + textOf(reason) + "." + extra;
    }

    /**
     * 发送侧 pacer 失败文案（CC 逐字，偏移 324044952）。
     *
     * @param sentInBurst 本轮已发条数（CC {@code H.sentInBurst}）
     * @return 英文原文
     */
    public static String pacedMessage(int sentInBurst) {
        return "Too many messages to this session just now: " + sentInBurst
            + " were sent recently and more would be dropped by its rate limit, so this one was "
            + "not sent. Batch what remains into one message, or wait a little before sending more.";
    }

    /**
     * 解析转发预算（CC zod：缺失 ⇒ 100000；给了但非法/越界 ⇒ 20）。
     *
     * @param raw 配置原值（null/空白 = 缺失）
     * @return 生效预算
     */
    public static int resolveForwardBudget(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_FORWARD_BUDGET;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > DEFAULT_FORWARD_BUDGET) {
                return FALLBACK_FORWARD_BUDGET;
            }
            return value;
        } catch (NumberFormatException e) {
            return FALLBACK_FORWARD_BUDGET;
        }
    }

    /**
     * 由原因原文反查原因（路由回执 detail 用的就是 {@link #textOf(Reason)} 的英文原文）。
     *
     * @param text 原因原文（null/未知 → null，不猜）
     * @return 原因
     */
    public static Reason reasonOfText(String text) {
        if (text == null) {
            return null;
        }
        for (Reason reason : Reason.values()) {
            if (textOf(reason).equals(text)) {
                return reason;
            }
        }
        return null;
    }
}
