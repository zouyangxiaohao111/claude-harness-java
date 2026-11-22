package com.nexusai.application.agent.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.session.SessionService;
import com.nexusai.eventbus.ws.PeerHeldNotifier;
import com.nexusai.eventbus.ws.PeerInboxAggregateEvent;
import com.nexusai.eventbus.ws.PeerInboxEventPublisher;
import com.nexusai.eventbus.ws.PeerInboxHoldEvent;
import com.nexusai.repository.peer.entity.PeerHeldMessageRecord;
import com.nexusai.repository.peer.entity.PeerHeldSessionCount;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQLite 版待收件存储（V322 + V323 + V332）· 重启后 pending / seen 状态不丢。
 *
 * <h2>状态机（批准即结算 · 严格 CC 2.1.328 语义）</h2>
 * <pre>
 *   pending --批准(take)--> [立即投递] --成功--> accepted
 *                                     --失败--> expired（终态，发送方收到失败通知）
 *   pending --reject----> rejected
 *   pending --超时/关闭/淘汰--> expired
 * </pre>
 * ⛔ <b>没有 approved 中间态</b>：批准 = 从 hold 缓冲一次性取出 + 同步投递；成功回执 delivered，
 * 失败即结算并回执发送方（refused / dropped / receiver-closed）。不能撤销、不能二次批准。
 * V332 已把历史 approved 行一次性转回 pending（让用户重新处理）。
 *
 * <h2>写入顺序（不可提前产生副作用）</h2>
 * <ol>
 *   <li>行已存在且仍 pending ⇒ 保持原状态（不再用 INSERT OR IGNORE 静默丢弃）；</li>
 *   <li>行已存在但是终态 ⇒ <b>不回退</b>，记 ERROR 并返回 false（可识别结果）；</li>
 *   <li>行不存在 ⇒ {@code INSERT OR IGNORE} 落库，本次<b>确实新插入</b>时才发布
 *       {@code peer.inbox.hold}（发布失败只记日志，不回滚）。</li>
 * </ol>
 *
 * <h2>并发</h2>
 * 批准的一次性由单条条件 DELETE（{@link PeerHeldMessageMapper#takePending}）决定胜负；
 * 拒绝由条件 UPDATE（{@link PeerHeldMessageMapper#rejectRow}）决定。
 *
 * <h2>重启与代次</h2>
 * 首次访问本实例时把遗留 pending 行的 {@code target_endpoint_epoch} 置 -1
 * （best-effort，失败下次重试）。router 侧对「存储代次 ≠ 当前代次」不再直接判死，而是按
 * sessionId 重新 resolve 当前代次继续校验；-1 只是显式哨兵，便于排查「这条是重启前留下的」。
 *
 * <h2>数据边界</h2>
 * body 是用户数据（与 messages 同一本机信任边界）；本类不读写任何 endpoint token，日志/事件也不含 token。
 */
@Component
public class DbHeldPeerStore implements PeerMessageRouter.HeldPeerStore {

    private static final Logger log = LoggerFactory.getLogger(DbHeldPeerStore.class);

    /** 本批 source_kind 恒为 peer（列预留 subagent/team）。 */
    public static final String SOURCE_KIND_PEER = "peer";

    /**
     * 待确认确认卡的默认对话框超时：<b>600000 ms（10 分钟）</b>。
     *
     * <p>对齐 CC 2.1.328 实测：hold 没有专用 TTL，用户看到的「过期」是通用 dialog 超时
     * （CC 默认 {@code aR=300000}，{@code CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS} 可覆盖）。
     * <b>本仓按用户 2026-11-20 裁定改为 10 分钟</b>（比 CC 的 5 分钟更宽，避免用户离开一会儿就丢件）；
     * 环境变量同名语义 {@link #ENV_USER_DIALOG_TIMEOUT_MS} 仍可覆盖，{@code <= 0} = 禁用。
     *
     * <p><b>计时口径</b>（2026-11-20 用户更正）：待确认卡片就嵌在<b>对话流里</b>、流被卡住直到用户
     * 处理 ⇒ 不存在「卡片被藏起来再重新可见」的模型。<b>没有续期入口</b>：到期时刻只在
     * 「第一次被展示」时写一次，之后轮询/切会话/重进页面一律不延长；到期即懒结算为 expired。
     */
    public static final long DEFAULT_DIALOG_TIMEOUT_MS = 600_000L;

    /** 覆盖确认卡超时的环境变量（毫秒；<= 0 = 禁用自动过期）。 */
    public static final String ENV_USER_DIALOG_TIMEOUT_MS = "NEXUSAI_USER_DIALOG_TIMEOUT_MS";

    /** 同一接收方 pending 上限（CC 实测：hold 缓冲 100 条，满则淘汰最旧为 expired）。 */
    public static final int MAX_PENDING_PER_RECEIVER = 100;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> HOP_TYPE = new TypeReference<>() { };

    private final PeerHeldMessageMapper mapper;

    @Autowired(required = false)
    private PeerHeldNotifier notifier;

    /**
     * 聚合通道发布器（{@code /topic/peer-inbox} + counts 对账锚点）。
     *
     * <p>null = 未接线（非 Spring 直构单测 / 旧测试只关心落库）：状态变化照常发生，只是不推聚合事件。
     * ⛔ 发布失败不得回滚落库、不得伪装已发布（DB + counts 接口才是真源）。
     */
    @Autowired(required = false)
    private PeerInboxEventPublisher inboxPublisher;

    @Autowired(required = false)
    private SessionService sessionService;

    /**
     * 失败通知出口（过期 / 会话关闭 / 队列淘汰）。
     *
     * <p>null = 未接线（非 Spring 直构单测）：结算照常生效，只记日志、不写通知。
     * ⛔ 通知失败不得回滚结算，也不得把结算伪装成成功/失败。
     */
    @Autowired(required = false)
    private PeerFailureNotifier failureNotifier;

    /** 测试覆盖（毫秒；null = 走环境变量/默认）。 */
    private volatile Long dialogTimeoutOverrideMs;

    private final AtomicBoolean epochResetDone = new AtomicBoolean(false);

    public DbHeldPeerStore(PeerHeldMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 测试/接线用 setter。 */
    public void setNotifier(PeerHeldNotifier notifier) {
        this.notifier = notifier;
    }

    /** 测试/接线用 setter（聚合通道发布器；null = 不推聚合事件）。 */
    public void setInboxPublisher(PeerInboxEventPublisher inboxPublisher) {
        this.inboxPublisher = inboxPublisher;
    }

    /** 测试/接线用 setter（失败通知出口；null = 只记日志）。 */
    public void setFailureNotifier(PeerFailureNotifier failureNotifier) {
        this.failureNotifier = failureNotifier;
    }

    /** 测试用：固定对话框超时（毫秒；<= 0 = 禁用自动过期；null = 恢复环境变量/默认）。 */
    public void setDialogTimeoutMsForTest(Long timeoutMs) {
        this.dialogTimeoutOverrideMs = timeoutMs;
    }

    /** 测试/接线用 setter（source_title 快照，best-effort；null = 不查）。 */
    public void setSessionService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    // ══════════════════════════ HeldPeerStore ══════════════════════════

    /**
     * 回写/新建待收件行（⛔ 不再用 INSERT OR IGNORE 静默丢弃）。
     *
     * <ol>
     *   <li>行已存在且为 pending：保持原状态（不改 reason/seen_at、不发事件），返回 true；</li>
     *   <li>行已存在但是终态（accepted/rejected/expired）：<b>不插入、不覆盖、不回退</b>，
     *       记 ERROR 并返回 false（可识别结果）；</li>
     *   <li>行不存在：插入 pending 并按首次落库发布 {@code peer.inbox.hold}。</li>
     * </ol>
     */
    @Override
    public boolean hold(PeerMessageRouter.HeldMessage message) {
        if (message == null || message.messageId() == null || message.messageId().isBlank()
            || message.targetSessionId() == null || message.targetSessionId().isBlank()) {
            return false;
        }
        ensureEpochReset();
        // 1) 已存在 pending → 保持原状态（回写不再产生第二行/第二种状态）。
        if (mapper.keepHeldRow(message.messageId()) == 1) {
            log.debug("PEER HELD 回写：行已存在且仍待处理，保持原状态 target={} messageId={}",
                message.targetSessionId(), message.messageId());
            return true;
        }
        // 2) 已存在终态 → 明确拒绝回退 + ERROR 留痕（可识别结果，不假成功）。
        PeerHeldMessageRecord existing = mapper.selectByMessageId(message.messageId());
        if (existing != null) {
            log.error("PEER HELD 回写被拒：行已是终态 {}，不回退（避免出现「已 accepted 却未投递」）"
                    + "target={} messageId={} reason={}",
                existing.getStatus(), message.targetSessionId(), message.messageId(),
                message.reason());
            return false;
        }
        // 3) 新行 → 插入 + 首次事件。
        PeerHeldMessageRecord record = toRecord(message);
        int inserted = mapper.insertIgnore(record);
        if (inserted != 1) {
            // 并发插入：另一路径先落了同一 messageId —— 不回滚对方，保持其状态。
            log.warn("PEER HELD 回写并发：同 messageId 已被其他路径插入，保持其状态 target={} messageId={}",
                message.targetSessionId(), message.messageId());
            return true;
        }
        // 旧通道：按会话的 peer.inbox.hold（保留，不删除）。
        PeerInboxHoldEvent holdEvent = publishHold(record);
        // 容量上限（CC：hold 缓冲 100 条，满则淘汰最旧为 expired 并通知发送方）。
        enforceQueueCap(message.targetSessionId());
        // 聚合通道：同一份快照 ⇒ 两路内容一致；计数取「淘汰之后」的最终值。
        publishAggregate(PeerInboxAggregateEvent.TYPE_HOLD, record.getTargetSessionId(),
            PeerInboxAggregateEvent.Item.of(holdEvent));
        return true;
    }

    @Override
    public List<PeerMessageRouter.HeldMessage> list(String targetSessionId) {
        if (targetSessionId == null) {
            return List.of();
        }
        ensureEpochReset();
        // 懒结算：到期未处理的先转 expired（⛔ 不依赖心跳/调度器；读路径即判定点）。
        settleExpired(targetSessionId);
        List<PeerMessageRouter.HeldMessage> out = new ArrayList<>();
        for (PeerHeldMessageRecord record : mapper.listPending(targetSessionId)) {
            PeerMessageRouter.HeldMessage held = toHeld(record);
            if (held != null) {
                out.add(held);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<PeerMessageRouter.HeldView> listViews(String targetSessionId) {
        if (targetSessionId == null) {
            return List.of();
        }
        ensureEpochReset();
        // ① 先结算「首次展示起算已超时」的行；② 再给「从未展示过」的行设一次到期时刻。
        // 顺序不可反：先设时会把已到期的行续命，过期永远不会发生。
        settleExpired(targetSessionId);
        markShownOnce(targetSessionId);
        List<PeerMessageRouter.HeldView> out = new ArrayList<>();
        // 展示口径：pending / rejected（已拒绝的卡片可回看）；accepted/expired 不出现在列表。
        for (PeerHeldMessageRecord record : mapper.listForDisplay(targetSessionId)) {
            out.add(new PeerMessageRouter.HeldView(
                record.getMessageId(),
                record.getTargetSessionId(),
                record.getSourceSessionId(),
                backfillTitle(record.getSourceTitle(), record.getSourceSessionId(),
                    record.getTargetSessionId()),
                record.getSourceKind() == null ? SOURCE_KIND_PEER : record.getSourceKind(),
                record.getFromName(),
                record.getFromAddress(),
                record.getFromMode(),
                record.getSummary(),
                record.getBody(),
                record.getReason(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getSeenAt(),
                record.getAdmission(),
                record.getAdmissionReason(),
                PeerMessageRouter.bodyLineCount(record.getBody()),
                PeerMessageRouter.bodyCharCount(record.getBody())));
        }
        return List.copyOf(out);
    }

    /**
     * 一次性取出（批准 = 立即投递）：{@code pending → 取出}，行从待收件列表消失。
     *
     * <p><b>⛔ 没有 approved 中间态</b>（CC 2.1.328：批准 = splice 出 hold 缓冲 + 同步投递）：
     * 取出后调用方<b>必须立即</b>用 {@link #settleAccepted}（成功）或 {@link #settleFailed}
     * （失败即终态 + 通知发送方）结算，不得留「已批准待投递」的行。
     *
     * <p>条件 DELETE 决定胜负：行已 accepted/rejected/expired 或错归属 ⇒ null。
     */
    @Override
    public PeerMessageRouter.HeldMessage take(String messageId, PeerMessageRouter.ApprovalPrincipal principal) {
        // 审批前先懒结算：已到期的行必须先转 expired，⛔ 不能被「过期后仍能批准」绕过。
        settleExpired(principal == null ? null : principal.sessionId());
        HeldLookup lookup = lookupOwned(messageId, principal);
        if (lookup == null) {
            return null;
        }
        int won = mapper.takePending(messageId, principal.sessionId());
        return won == 1 ? lookup.held() : null;
    }

    /**
     * 批准成功结算：取出的行回写为终态 {@code accepted}（用户批准时刻记入 approved_at）。
     *
     * <p>回写保留准入方式 {@code approved / user-approved}（审计：这条是用户手动放行的），
     * ⛔ 不回落成 auto。发布 {@code peer.inbox.removed}（用户视角：这件已从待办消失）。
     */
    @Override
    public void settleAccepted(PeerMessageRouter.HeldMessage taken) {
        if (taken == null || taken.messageId() == null) {
            return;
        }
        PeerHeldMessageRecord record = toRecord(taken, PeerMessageRouter.HeldMessage.STATUS_ACCEPTED);
        record.setApprovedAt(Instant.now().toString());
        record.setAdmission(PeerEnvelope.ADMISSION_APPROVED);
        record.setAdmissionReason(PeerEnvelope.REASON_USER_APPROVED);
        int inserted = mapper.insertIgnore(record);
        if (inserted != 1) {
            log.warn("PEER HELD 批准结算未落库（行已被其他路径重建）messageId={} target={}",
                taken.messageId(), taken.targetSessionId());
            return;
        }
        log.info("PEER HELD 批准即结算：row=accepted messageId={} target={}",
            taken.messageId(), taken.targetSessionId());
        publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, taken.targetSessionId(), null);
    }

    /**
     * 批准失败结算：取出的行回写为终态 {@code expired}（不展示、不可再批），并给发送方写一条
     * 失败通知（{@code reason} 由调用方给出：refused / dropped / receiver-closed / source-not-live）。
     *
     * <p><b>⛔ 不新增 failed/dropped 状态值</b>：状态集合收敛为 pending/accepted/rejected/expired；
     * 「为什么失败」由失败通知的 {@code reason} 与日志表达（CC 的 refused/dropped 回执语义）。
     *
     * <p>通知是 best-effort：结算已落库，通知失败不回滚、不改写结果。
     */
    @Override
    public void settleFailed(PeerMessageRouter.HeldMessage taken, String reason) {
        if (taken == null || taken.messageId() == null) {
            return;
        }
        PeerHeldMessageRecord record = toRecord(taken, PeerMessageRouter.HeldMessage.STATUS_EXPIRED);
        record.setApprovedAt(Instant.now().toString());
        record.setAdmission(PeerEnvelope.ADMISSION_APPROVED);
        record.setAdmissionReason(PeerEnvelope.REASON_USER_APPROVED);
        int inserted = mapper.insertIgnore(record);
        if (inserted != 1) {
            log.warn("PEER HELD 失败结算未落库（行已被其他路径重建）messageId={} target={} reason={}",
                taken.messageId(), taken.targetSessionId(), reason);
            return;
        }
        log.warn("PEER HELD 批准失败 → 终态 expired 并通知发送方 messageId={} target={} reason={}",
            taken.messageId(), taken.targetSessionId(), reason);
        publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, taken.targetSessionId(), null);
        notifyFailure(record, reason);
    }

    /**
     * 是否已消费落地（行状态 = accepted）。
     *
     * <p>[idle-then-drain 去重] 行查询用单条 SELECT（主键 message_id），不做锁、不开事务。
     * 严格 CC 语义下「批准即结算」⇒ 行在投递成功时已是 accepted，本判据同时覆盖
     * 「空闲路径已落库」与「刚批准」两种前态。读失败按「未消费」回落常规复核（不吞消息）。
     */
    @Override
    public boolean isConsumed(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        try {
            PeerHeldMessageRecord record = mapper.selectByMessageId(messageId);
            return record != null
                && PeerMessageRouter.HeldMessage.STATUS_ACCEPTED.equals(record.getStatus());
        } catch (RuntimeException e) {
            log.warn("PEER HELD 查询已消费状态失败（按未消费处理，回落常规消费前复核）"
                    + " messageId={} err={}", messageId, e.toString());
            return false;
        }
    }

    @Override
    public boolean reject(String messageId, PeerMessageRouter.ApprovalPrincipal principal) {
        // 拒绝前先懒结算（已到期行先转 expired，不得再被「拒绝」成用户决定）。
        settleExpired(principal == null ? null : principal.sessionId());
        HeldLookup lookup = lookupOwned(messageId, principal);
        if (lookup == null) {
            return false;
        }
        boolean rejected = mapper.rejectRow(messageId, principal.sessionId()) == 1;
        if (rejected) {
            publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, principal.sessionId(), null);
        }
        return rejected;
    }

    /**
     * 行当前状态（主键单条 SELECT；读失败按「未确认」返回 null，不抛）。
     *
     * <p>批量拒绝后据此确认「哪几行真的被本次改到」（并发 approve 抢走的行不得误报被拒）。
     */
    @Override
    public String statusOf(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        try {
            PeerHeldMessageRecord record = mapper.selectByMessageId(messageId);
            return record == null ? null : record.getStatus();
        } catch (RuntimeException e) {
            log.warn("PEER HELD 查询行状态失败（按未确认处理）messageId={} err={}",
                messageId, e.toString());
            return null;
        }
    }

    /**
     * 批量拒绝本会话全部 pending（单语句条件 UPDATE，无长事务）。
     *
     * <p><b>前置校验</b>（不通过 ⇒ 0，且<b>不执行任何写</b>）：主体非 null、{@code trustedUi}、
     * 且 {@code principal.sessionId()} 与 {@code targetSessionId} 完全相等（不跨会话）。
     * <b>只动 pending</b>：accepted/expired/rejected 行一律不变（终态保留审计）。
     *
     * @return 实际转 rejected 的行数；无 pending → 0（调用方返回 {@code {rejected:0}} 而非报错）
     */
    @Override
    public int rejectAll(String targetSessionId, PeerMessageRouter.ApprovalPrincipal principal) {
        if (targetSessionId == null || principal == null || !principal.trustedUi()
            || principal.sessionId() == null
            || !targetSessionId.equals(principal.sessionId())) {
            return 0;
        }
        // 批量拒绝前先懒结算：已到期的行归 expired（非用户决定），不得被记成 rejected。
        settleExpired(targetSessionId);
        int rejected = mapper.rejectAllPending(targetSessionId);
        log.info("PEER HELD 批量拒绝：session={} rejected={}", targetSessionId, rejected);
        if (rejected > 0) {
            publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, targetSessionId, null);
        }
        return rejected;
    }

    @Override
    public int markSeen(String targetSessionId, List<String> messageIds) {
        if (targetSessionId == null) {
            return 0;
        }
        ensureEpochReset();
        String now = Instant.now().toString();
        if (messageIds == null || messageIds.isEmpty()) {
            return mapper.markAllSeen(targetSessionId, now);
        }
        List<String> ids = messageIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return mapper.markAllSeen(targetSessionId, now);
        }
        return mapper.markSeenByIds(targetSessionId, ids, now);
    }

    // ══════════════════════════ 待处理计数（聚合事件 / counts 对账） ══════════════════════════

    /**
     * 该会话「仍需处理」的条数（聚合事件的 {@code pendingCount}、counts 接口同口径）。
     *
     * <p><b>口径</b> = {@code pending}（不含 rejected/accepted/expired）。严格 CC 语义下
     * 「批准即结算」：批准成功即时 accepted、失败即时 expired，两者都已离开待办 ⇒ 不需要
     * 按在途集合另减。
     *
     * <p>读失败按 0 处理（不阻断状态变更；事件宁可少报也不抛异常打断落库路径）。
     *
     * @param targetSessionId 目标会话
     * @return 待处理条数（&gt;= 0）
     */
    public long pendingCount(String targetSessionId) {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0L, mapper.countOpen(targetSessionId));
        } catch (RuntimeException e) {
            log.warn("PEER HELD 待处理计数失败（按 0 处理，不阻断状态变更）session={} err={}",
                targetSessionId, e.toString());
            return 0;
        }
    }

    /**
     * 按会话聚合的待处理条数（{@code GET /api/v1/peer-inbox/counts} 的数据源）。
     *
     * <p><b>只返回有待处理项的会话</b>（SQL 层 {@code GROUP BY}）⇒ 会话很多时也不会把全量会话
     * 拉进响应体。
     *
     * <p>读失败返回空 map（前端据此不从对账里删除任何东西；下次事件/counts 会纠正）。
     */
    public Map<String, Long> pendingCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            for (PeerHeldSessionCount row : mapper.countOpenGroupedBySession()) {
                if (row == null || row.getSessionId() == null) {
                    continue;
                }
                if (row.getTotal() > 0) {
                    out.put(row.getSessionId(), row.getTotal());
                }
            }
        } catch (RuntimeException e) {
            log.warn("PEER HELD 待处理计数聚合失败（返回空 map）err={}", e.toString());
            return Map.of();
        }
        return out;
    }

    /**
     * 按会话聚合的待处理条数 + 最早一条（rowid 为权威 FIFO，createdAt 仅展示/兜底）。
     *
     * <p>读失败返回空列表：调用方退回计数排序（⛔ 不伪造时间，也不把失败当「无待办」）。
     */
    public java.util.List<PeerMessageRouter.SessionPending> pendingSessionCounts() {
        java.util.List<PeerMessageRouter.SessionPending> out = new java.util.ArrayList<>();
        try {
            for (PeerHeldSessionCount row : mapper.countOpenGroupedBySession()) {
                if (row == null || row.getSessionId() == null || row.getTotal() <= 0) {
                    continue;
                }
                out.add(new PeerMessageRouter.SessionPending(
                    row.getSessionId(), row.getTotal(), row.getOldestRowId(), row.getOldestCreatedAt()));
            }
        } catch (RuntimeException e) {
            log.warn("PEER HELD 待处理 FIFO 聚合失败（返回空列表）err={}", e.toString());
            return java.util.List.of();
        }
        return out;
    }

    /**
     * 接收方会话关闭/删除 ⇒ 全部 pending 行结算为 {@code expired}，并逐条通知发送方。
     *
     * <p>对齐 CC 实测硬行为（{@code shutdown: settling N still-held peer message(s) as expired}）：
     * 只有一条 {@code sendPeerReceipt(s,"expired")}，没有「保留待投递」分支 —— 接收进程一退，
     * hold 消息即死。⛔ 模式变化不走这里（那是 revalidate，归 refused）。
     *
     * @param targetSessionId 接收方会话
     * @param reason          通知原因（receiver-closed / queue-full / expired）
     * @return 实际转 expired 的行数
     */
    @Override
    public int expireAll(String targetSessionId, String reason) {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return 0;
        }
        ensureEpochReset();
        int expired = 0;
        for (PeerHeldMessageRecord record : mapper.listPending(targetSessionId)) {
            if (mapper.expireRow(record.getMessageId()) == 1) {
                expired++;
                notifyFailure(record, reason);
            }
        }
        if (expired > 0) {
            log.info("PEER HELD 结转 expired：target={} count={} reason={}", targetSessionId, expired, reason);
            publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, targetSessionId, null);
        }
        return expired;
    }

    // ══════════════════════════ internal ══════════════════════════

    /**
     * 懒结算：把 {@code pending_expires_at <= now} 的未处理行转 expired 并通知。
     *
     * <p><b>为什么不用调度器</b>：确认卡的计时锚点是「首次被展示」，而「被展示」只由前端拉列表
     * 触发（本类无从得知卡片是否可见）。定时扫描在无人查看时会把「根本没展示过」的行也判过期，
     * 违背「卡片不可见时不计时」。因此读路径（列表 / 审批 / 拒绝 / 续期）即判定点，不依赖心跳。
     *
     * <p>只结算 pending：accepted/rejected/expired 已是终态；严格 CC 语义下不存在 approved 行。
     *
     * @return 本次结算条数
     */
    private int settleExpired(String targetSessionId) {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return 0;
        }
        long timeout = dialogTimeoutMs();
        if (timeout <= 0) {
            return 0; // 禁用自动过期（CC 的 dialogExpiry=never）
        }
        int expired = 0;
        for (PeerHeldMessageRecord record : mapper.listDueForExpiry(targetSessionId,
                System.currentTimeMillis())) {
            if (mapper.expireRow(record.getMessageId()) == 1) {
                expired++;
                notifyFailure(record, PeerFailureNotifier.REASON_EXPIRED);
            }
        }
        if (expired > 0) {
            log.info("PEER HELD 到期结算：target={} count={}（确认卡超时未处理）",
                targetSessionId, expired);
            publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, targetSessionId, null);
        }
        return expired;
    }

    /**
     * 列表拉取 = 这一行「第一次被展示」：{@code pending_expires_at} 只设一次。
     *
     * <p><b>⛔ 没有续期路径</b>（2026-11-20 用户更正）：待确认卡片就在对话流里、流被卡住直到用户
     * 处理，因此不存在「卡片重新可见」这回事。之后的轮询、切会话、重进页面都不延长到期时刻。
     */
    private void markShownOnce(String targetSessionId) {
        long timeout = dialogTimeoutMs();
        if (timeout <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int touched = mapper.markShownOnce(targetSessionId, now, now + timeout);
        if (touched > 0) {
            log.debug("PEER HELD 首次展示计时：target={} rows={} ttlMs={}", targetSessionId,
                touched, timeout);
        }
    }

    /**
     * 容量上限：同一接收方 pending 超过 {@value #MAX_PENDING_PER_RECEIVER} 条时淘汰最旧。
     *
     * <p>只数 pending（accepted/rejected/expired 已是终态，不占 hold 缓冲）。
     * 淘汰 = 条件 UPDATE 转 expired ⇒ 逐条通知发送方（各自一条，不合并成一条）。
     */
    private void enforceQueueCap(String targetSessionId) {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return;
        }
        List<PeerHeldMessageRecord> pending;
        try {
            pending = mapper.listPendingOnly(targetSessionId);
        } catch (RuntimeException e) {
            log.warn("PEER HELD 容量检查失败（不阻断 hold）target={} err={}",
                targetSessionId, e.toString());
            return;
        }
        int overflow = pending.size() - MAX_PENDING_PER_RECEIVER;
        if (overflow <= 0) {
            return;
        }
        int evicted = 0;
        for (PeerHeldMessageRecord oldest : pending) {
            if (evicted >= overflow) {
                break;
            }
            if (mapper.expireRow(oldest.getMessageId()) == 1) {
                evicted++;
                notifyFailure(oldest, PeerFailureNotifier.REASON_QUEUE_FULL);
            }
        }
        if (evicted > 0) {
            log.info("PEER HELD hold 缓冲已满（上限 {}）— 淘汰最旧为 expired：target={} count={}",
                MAX_PENDING_PER_RECEIVER, targetSessionId, evicted);
            publishAggregate(PeerInboxAggregateEvent.TYPE_REMOVED, targetSessionId, null);
        }
    }

    /**
     * 确认卡超时（毫秒）：设置覆盖值 → 环境变量 → 默认 600000；{@code <= 0} = 禁用。
     *
     * <p>本仓 settings 表当前<b>没有</b>对话框超时设置项（已核 V1..V326 的 settings 列），
     * 因此按用户裁定「没有设置项就用环境变量+默认」实现，不新造设置界面。
     */
    long dialogTimeoutMs() {
        return resolveTimeoutMs(dialogTimeoutOverrideMs, System.getenv(ENV_USER_DIALOG_TIMEOUT_MS));
    }

    /**
     * 超时解析的纯函数部分（便于单测覆盖环境变量分支：JVM 内无法改真实 env）。
     *
     * <p>优先级：override（测试/接线）→ env（合法整数，含 {@code <= 0} = 禁用）→ 默认值。
     * env 非法（非整数/空白）只告警并回落默认，⛔ 不抛异常（否则拉列表会 500）。
     */
    static long resolveTimeoutMs(Long override, String rawEnv) {
        if (override != null) {
            return override;
        }
        if (rawEnv != null && !rawEnv.isBlank()) {
            try {
                return Long.parseLong(rawEnv.trim());
            } catch (NumberFormatException e) {
                log.warn("PEER HELD 环境变量 {} 不是合法整数（按默认 {} ms）：{}",
                    ENV_USER_DIALOG_TIMEOUT_MS, DEFAULT_DIALOG_TIMEOUT_MS, rawEnv);
            }
        }
        return DEFAULT_DIALOG_TIMEOUT_MS;
    }

    /** 过期/淘汰通知（发送方会话落库；notifier 未接线只记日志，⛔ 不影响结算）。 */
    private void notifyFailure(PeerHeldMessageRecord record, String reason) {
        PeerFailureNotifier sink = failureNotifier;
        if (sink == null) {
            log.info("PEER HELD {} 通知未接线（无落库出口）：source={} messageId={}（结算已生效）",
                reason, record.getSourceSessionId(), record.getMessageId());
            return;
        }
        String sourceSessionId = record.getSourceSessionId();
        if (sourceSessionId == null || sourceSessionId.isBlank()) {
            log.info("PEER HELD {} 通知跳过：行没有来源会话 messageId={}（结算已生效）",
                reason, record.getMessageId());
            return;
        }
        String targetTitle = PeerSessionTitles.resolve(sessionService, record.getTargetSessionId());
        try {
            sink.notifyFailure(new PeerFailureNotifier.FailureNotice(
                record.getMessageId(), sourceSessionId, record.getTargetSessionId(),
                targetTitle, reason));
        } catch (RuntimeException e) {
            // 通知是 best-effort：结算已落库，不得因通知异常反向失败。
            log.warn("PEER HELD {} 通知异常（结算已生效）messageId={} err={}",
                reason, record.getMessageId(), e.toString());
        }
    }

    private record HeldLookup(PeerHeldMessageRecord record, PeerMessageRouter.HeldMessage held) {}

    /** 归属 + 未到终态校验（只读）；任一不符 → null（不消耗、不修改）。 */
    private HeldLookup lookupOwned(String messageId, PeerMessageRouter.ApprovalPrincipal principal) {
        if (messageId == null || principal == null || !principal.trustedUi()
            || principal.sessionId() == null) {
            return null;
        }
        ensureEpochReset();
        PeerHeldMessageRecord record = mapper.selectByMessageId(messageId);
        if (record == null || !isOpen(record.getStatus())
            || !principal.sessionId().equals(record.getTargetSessionId())) {
            return null;
        }
        PeerMessageRouter.HeldMessage held = toHeld(record);
        if (held == null) {
            return null;
        }
        return new HeldLookup(record, held);
    }

    /** 只有 pending 是「未到终态」；accepted/rejected/expired 不得再被批准。 */
    private static boolean isOpen(String status) {
        return PeerMessageRouter.HeldMessage.STATUS_PENDING.equals(status);
    }

    private PeerHeldMessageRecord toRecord(PeerMessageRouter.HeldMessage message) {
        return toRecord(message, PeerMessageRouter.HeldMessage.STATUS_PENDING);
    }

    /**
     * 行实体投影（{@code status} 由调用方给出：hold = pending；批准结算 = accepted / expired）。
     *
     * <p>准入方式默认按信封原样透传（pending 行通常为 null）；批准结算路径会另行覆盖为
     * {@code approved / user-approved}（审计用途，⛔ 不回落 auto）。
     */
    private PeerHeldMessageRecord toRecord(PeerMessageRouter.HeldMessage message, String status) {
        PeerEnvelope envelope = message.envelope();
        PeerHeldMessageRecord record = new PeerHeldMessageRecord();
        record.setMessageId(message.messageId());
        record.setTargetSessionId(message.targetSessionId());
        record.setSourceSessionId(envelope != null ? envelope.fromSessionId() : null);
        record.setSourceTitle(sourceTitle(envelope));
        record.setSourceKind(SOURCE_KIND_PEER);
        record.setFromName(envelope != null ? envelope.fromName() : null);
        record.setFromMode(envelope != null ? envelope.fromMode() : null);
        record.setFromAddress(envelope != null ? envelope.fromAddress() : null);
        record.setHopChain(toHopJson(envelope));
        record.setSummary(envelope != null ? envelope.summary() : null);
        record.setBody(message.body());
        record.setReason(message.reason() == null ? "INVALID_SETTING" : message.reason().name());
        record.setStatus(status);
        record.setSourceEndpointEpoch(envelope != null ? envelope.sourceEndpointEpoch() : 0L);
        record.setTargetEndpointEpoch(message.targetEndpointEpoch());
        record.setNotifyWhenIdle(envelope != null && envelope.notifyWhenIdle() ? 1 : 0);
        // 准入方式：pending 行通常为 null（还未准入）；回写保留信封上的值（若有）。
        record.setAdmission(envelope != null ? envelope.admission() : null);
        record.setAdmissionReason(envelope != null ? envelope.admissionReason() : null);
        Instant heldAt = message.heldAt() == null ? Instant.now() : message.heldAt();
        record.setCreatedAt(heldAt.toString());
        record.setSeenAt(null);
        return record;
    }

    /**
     * source_title 是展示快照（best-effort；查不到写 null，失败绝不阻断 hold）。
     *
     * <p>取值顺序：① 信封上已解析的标题（发送时快照）② 按 source 会话记录补解析。
     * ⛔ 不用 {@code fromName}（发送方展示名）冒充标题 —— 那是另一个字段。
     */
    private String sourceTitle(PeerEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        String fromEnvelope = PeerSessionTitles.normalize(envelope.sourceTitle());
        if (fromEnvelope != null) {
            return fromEnvelope;
        }
        return PeerSessionTitles.resolve(sessionService, envelope.fromSessionId());
    }

    /**
     * 历史行（V322 之前 / 旧写入路径）source_title 为空时的列表补解析。
     *
     * <p>先按 source 会话记录补；source 为空或已删除时再按 target 会话记录补。
     * 失败保持 null（⛔ 不阻断列表查询、不写回 DB、不伪造）。
     */
    private String backfillTitle(String storedTitle, String sourceSessionId, String targetSessionId) {
        String normalized = PeerSessionTitles.normalize(storedTitle);
        if (normalized != null) {
            return normalized;
        }
        SessionService svc = sessionService;
        if (svc == null) {
            return null;
        }
        String resolved = PeerSessionTitles.resolve(svc, sourceSessionId);
        if (resolved == null && (sourceSessionId == null || sourceSessionId.isBlank())) {
            resolved = PeerSessionTitles.resolve(svc, targetSessionId);
        }
        return resolved;
    }

    private PeerMessageRouter.HeldMessage toHeld(PeerHeldMessageRecord record) {
        try {
            PeerEnvelope envelope = new PeerEnvelope(
                record.getMessageId(),
                record.getFromAddress(),
                record.getSourceSessionId(),
                record.getFromName(),
                record.getFromMode(),
                parseHops(record.getHopChain()),
                record.getSourceEndpointEpoch() == null ? 0L : record.getSourceEndpointEpoch(),
                record.getSummary(),
                record.getNotifyWhenIdle() != null && record.getNotifyWhenIdle() != 0,
                null,
                // 历史行标题为空时同样补解析 ⇒ 批准重投 / 队列信封也不会丢标题。
                backfillTitle(record.getSourceTitle(), record.getSourceSessionId(),
                    record.getTargetSessionId()),
                // 准入方式按落库值原样重建（⛔ 不得回落成 auto：用户手动批准过的行仍是 approved）。
                record.getAdmission(),
                record.getAdmissionReason());
            return new PeerMessageRouter.HeldMessage(
                record.getMessageId(),
                record.getTargetSessionId(),
                record.getTargetEndpointEpoch() == null ? -1L : record.getTargetEndpointEpoch(),
                envelope,
                record.getBody(),
                parseReason(record.getReason()),
                parseInstantOrNow(record.getCreatedAt()),
                record.getStatus());
        } catch (RuntimeException e) {
            log.warn("PEER HELD 行无法重建信封（跳过，不伪造来源）messageId={} err={}",
                record.getMessageId(), e.toString());
            return null;
        }
    }

    /**
     * 旧通道（按会话 topic）发布 {@code peer.inbox.hold}，并返回同一份事件快照供聚合通道复用。
     *
     * <p>事件<b>总是构造</b>（即便旧通道 notifier 未接线）：聚合通道也靠它拿条目字段，
     * 两路内容因此一致。发布失败只记日志，不回滚已落库行。
     *
     * @param record 新插入的行
     * @return 事件快照（恒非 null）
     */
    private PeerInboxHoldEvent publishHold(PeerHeldMessageRecord record) {
        PeerInboxHoldEvent event = new PeerInboxHoldEvent(
            record.getTargetSessionId(),
            record.getMessageId(),
            record.getSourceSessionId(),
            record.getSourceTitle(),
            record.getSourceKind() == null ? SOURCE_KIND_PEER : record.getSourceKind(),
            record.getFromMode(),
            record.getSummary(),
            record.getBody(),
            record.getReason(),
            record.getStatus(),
            parseInstant(record.getCreatedAt()),
            parseInstant(record.getSeenAt()),
            record.getAdmission(),
            record.getAdmissionReason());
        PeerHeldNotifier sink = notifier;
        if (sink == null) {
            log.debug("PEER HELD 事件未发布：notifier 未接线 messageId={}（DB 已落库）",
                record.getMessageId());
            return event;
        }
        try {
            if (!sink.publish(event)) {
                log.debug("PEER HELD 事件未送达前端（列表兜底）messageId={}", record.getMessageId());
            }
        } catch (RuntimeException e) {
            // 发布异常不得反向影响已提交的落库。
            log.warn("PEER HELD 事件发布异常（已落库，不回滚）messageId={} err={}",
                record.getMessageId(), e.toString());
        }
        return event;
    }

    /**
     * 聚合通道（{@code /topic/peer-inbox}）发布一条事件，计数取发布时刻的权威值。
     *
     * <p>publisher 未接线 / 计数失败 ⇒ 只记日志（best-effort）：⛔ 不抛给调用方，
     * 因为本方法处在 hold/reject 等已落库之后的状态变更路径上。
     *
     * @param type            事件类型
     * @param targetSessionId 归属会话（null/空白 → 跳过）
     * @param item            仅 hold 携带
     */
    private void publishAggregate(String type, String targetSessionId,
                                  PeerInboxAggregateEvent.Item item) {
        PeerInboxEventPublisher publisher = this.inboxPublisher;
        if (publisher == null) {
            log.debug("PEER INBOX 聚合事件未发布：publisher 未接线 type={} target={}（DB/counts 是真源）",
                type, targetSessionId);
            return;
        }
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return;
        }
        try {
            long count = pendingCount(targetSessionId);
            publisher.publish(type, targetSessionId, count, item);
        } catch (RuntimeException e) {
            // 事件是「多快看到」的优化；失败不得反向影响已提交的状态变更。
            log.warn("PEER INBOX 聚合事件发布异常（状态变更已生效）type={} target={} err={}",
                type, targetSessionId, e.toString());
        }
    }

    /**
     * 重启后 pending 行的目标代次失效：置 -1（best-effort，只做一次；失败下次重试）。
     * 本方法本身不改变 seen_at / status。
     */
    private void ensureEpochReset() {
        if (epochResetDone.get()) {
            return;
        }
        if (!epochResetDone.compareAndSet(false, true)) {
            return;
        }
        try {
            int rows = mapper.expirePendingEpochs();
            if (rows > 0) {
                log.info("PEER HELD 启动：{} 条 pending 目标代次置 -1（重启后按 sessionId 重新 resolve）", rows);
            }
        } catch (RuntimeException e) {
            epochResetDone.set(false);
            log.debug("PEER HELD 启动代次重置失败（下次访问重试）err={}", e.toString());
        }
    }

    private static String toHopJson(PeerEnvelope envelope) {
        if (envelope == null || envelope.hopChain().isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(envelope.hopChain());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> parseHops(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> hops = JSON.readValue(json, HOP_TYPE);
            return hops == null ? List.of() : hops;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static PeerInboundPolicy.Reason parseReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return PeerInboundPolicy.Reason.INVALID_SETTING;
        }
        try {
            return PeerInboundPolicy.Reason.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return PeerInboundPolicy.Reason.INVALID_SETTING;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant parseInstantOrNow(String raw) {
        Instant parsed = parseInstant(raw);
        return parsed == null ? Instant.now() : parsed;
    }
}
