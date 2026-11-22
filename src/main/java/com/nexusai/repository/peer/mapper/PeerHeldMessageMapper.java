package com.nexusai.repository.peer.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.peer.entity.PeerHeldMessageRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * peer_held_message mapper · 写路径全部是<b>单语句短写 + 条件 UPDATE</b>（SQLite 单写锁友好）。
 *
 * <p>并发语义由 affected rows 决定：
 * <ul>
 *   <li>{@link #claim} / {@link #rejectRow} 都带 {@code status='pending'} 条件 ⇒ 同一行只有一个调用返回 1；</li>
 *   <li>{@link #markAllSeen} / {@link #markSeenByIds} 带 {@code seen_at IS NULL} ⇒ 幂等（第二次 0 行）。</li>
 * </ul>
 * ⛔ 不加长事务；调用方按返回值判定胜负，不靠「先 select 再无条件 update」。
 */
public interface PeerHeldMessageMapper extends BaseMapper<PeerHeldMessageRecord> {

    /**
     * 自定义 @Select 走原生 MyBatis 自动映射（本仓未全局开启 mapUnderscoreToCamelCase），
     * 因此显式列别名，避免 snake_case 列映射不进来（否则实体会是空对象）。
     */
    String COLUMNS = "message_id AS messageId, target_session_id AS targetSessionId, "
        + "source_session_id AS sourceSessionId, source_title AS sourceTitle, "
        + "source_kind AS sourceKind, from_name AS fromName, from_mode AS fromMode, "
        + "from_address AS fromAddress, hop_chain AS hopChain, summary AS summary, "
        + "body AS body, reason AS reason, status AS status, "
        + "source_endpoint_epoch AS sourceEndpointEpoch, "
        + "target_endpoint_epoch AS targetEndpointEpoch, "
        + "notify_when_idle AS notifyWhenIdle, created_at AS createdAt, seen_at AS seenAt, "
        + "approved_at AS approvedAt, admission AS admission, admission_reason AS admissionReason, "
        + "last_shown_at AS lastShownAt, pending_expires_at AS pendingExpiresAt";

    /** 幂等插入：message_id 已存在则整行保持原样（不改 status/seen_at）。 */
    @Insert("INSERT OR IGNORE INTO peer_held_message ("
        + "message_id, target_session_id, source_session_id, source_title, source_kind, "
        + "from_name, from_mode, from_address, hop_chain, summary, body, reason, status, "
        + "source_endpoint_epoch, target_endpoint_epoch, notify_when_idle, created_at, seen_at, "
        + "approved_at, admission, admission_reason, last_shown_at, pending_expires_at"
        + ") VALUES ("
        + "#{r.messageId}, #{r.targetSessionId}, #{r.sourceSessionId}, #{r.sourceTitle}, #{r.sourceKind}, "
        + "#{r.fromName}, #{r.fromMode}, #{r.fromAddress}, #{r.hopChain}, #{r.summary}, #{r.body}, "
        + "#{r.reason}, #{r.status}, #{r.sourceEndpointEpoch}, #{r.targetEndpointEpoch}, "
        + "#{r.notifyWhenIdle}, #{r.createdAt}, #{r.seenAt}, "
        + "#{r.approvedAt}, #{r.admission}, #{r.admissionReason}, "
        + "#{r.lastShownAt}, #{r.pendingExpiresAt}"
        + ")")
    int insertIgnore(@Param("r") PeerHeldMessageRecord record);

    /**
     * 回写探测：行已存在且仍待处理（pending）⇒ 命中 1 行，保持原状态。
     *
     * <p>{@code SET status = status} 是 SQLite 合法的显式 no-op（按匹配行计数）—— 用于替代
     * 会静默丢弃的 {@code INSERT OR IGNORE}（2026-11-20 丢件根因）。终态行不命中（0），
     * 调用方据此记 ERROR 并返回「不可回退」。
     */
    @Update("UPDATE peer_held_message SET status = status "
        + "WHERE message_id = #{id} AND status = 'pending'")
    int keepHeldRow(@Param("id") String messageId);

    /**
     * 审批一次性取出（严格 CC：没有 approved 中间态）：{@code pending → 行被取走}。
     *
     * <p>返回 1 = 本次调用胜出（随后必须立即投递并结算为 accepted / 终态）；
     * 已 accepted/rejected/expired 或错归属 ⇒ 0（并发/重复批准返回 900）。
     *
     * <p><b>WHY DELETE</b>：CC 批准 = 从 hold 缓冲 splice 出来 + 同步投递，成功回执 delivered、
     * 失败即结算（refused/dropped），⛔ 不存在「已批准待投递」的行。SQLite 单语句删除天然互斥。
     */
    @Delete("DELETE FROM peer_held_message "
        + "WHERE message_id = #{id} AND target_session_id = #{sid} AND status = 'pending'")
    int takePending(@Param("id") String messageId, @Param("sid") String targetSessionId);

    /**
     * 可操作行：只有 pending（内部用；accepted/rejected/expired 终态不参与批准/拒绝/续期）。
     *
     * <p>⛔ 内部动作判定<b>不</b>用 {@link #listForDisplay}：已拒绝行只用于展示，不可再操作。
     */
    @Select("SELECT " + COLUMNS + " FROM peer_held_message "
        + "WHERE target_session_id = #{sid} AND status = 'pending' "
        + "ORDER BY created_at ASC, message_id ASC")
    List<PeerHeldMessageRecord> listPending(@Param("sid") String targetSessionId);

    /**
     * 待收件<b>展示</b>列表：pending / rejected。
     *
     * <p><b>WHY 含 rejected</b>（2026-11-20 用户裁定）：前端已支持渲染「已拒绝」卡片，但列表只返回
     * pending ⇒ 拒绝过的件刷新后凭空消失。把行透出后前端可按 {@code status} 显示为
     * 不可操作卡片；⛔ 计数口径在前端按 {@code status==='pending'} 计算，后端不提供 total/unread。
     *
     * <p><b>⛔ 不含 expired</b>：过期是「非人工决定的静默终结」（超时/会话关闭/队列淘汰/批准投递失败），
     * CC 也不会在待收件面板里回放它；发送方已另外收到落库的失败通知。且返回 expired 会让前端
     * 多出一种既不可批准也不可拒绝的卡片，收益低于噪音。
     *
     * <p><b>⛔ 不含 approved</b>（2026-11-21 严格 CC 语义）：批准即结算，不再有「已批准待投递」
     * 的行；成功直接 accepted、失败直接 expired，均不在展示列表里。
     */
    @Select("SELECT " + COLUMNS + " FROM peer_held_message "
        + "WHERE target_session_id = #{sid} AND status IN ('pending', 'rejected') "
        + "ORDER BY created_at ASC, message_id ASC")
    List<PeerHeldMessageRecord> listForDisplay(@Param("sid") String targetSessionId);

    /**
     * 只查 pending（容量淘汰口径：CC 的 hold 缓冲计数只算「尚未处理」的项）。
     *
     * <p><b>按 rowid 排序</b>（= 插入顺序）：created_at 是 ISO-8601 文本，整秒时刻不带小数秒、
     * 其余带 `.xxx`，字典序会把「整秒」排到后面（例如 00Z 排在 00.123Z 之后）。淘汰最旧必须用
     * 真正的到达顺序，不用可读时间串。
     */
    @Select("SELECT " + COLUMNS + " FROM peer_held_message "
        + "WHERE target_session_id = #{sid} AND status = 'pending' "
        + "ORDER BY rowid ASC")
    List<PeerHeldMessageRecord> listPendingOnly(@Param("sid") String targetSessionId);

    /**
     * 到期结算候选：待展示超时（{@code pending_expires_at <= now}）且仍未处理（pending）的行。
     *
     * <p>旧行（V327 之前）该列为 NULL ⇒ 不命中（未展示不累计）。
     */
    @Select("SELECT " + COLUMNS + " FROM peer_held_message "
        + "WHERE target_session_id = #{sid} AND status = 'pending' "
        + "AND pending_expires_at IS NOT NULL AND pending_expires_at <= #{now} "
        + "ORDER BY created_at ASC, message_id ASC")
    List<PeerHeldMessageRecord> listDueForExpiry(@Param("sid") String targetSessionId,
                                                 @Param("now") long now);

    /**
     * 到期结算写入：pending → expired（终态）。
     *
     * <p>单条条件 UPDATE 决定胜负：并发的批准/拒绝先改到 ⇒ 0（调用方据此不重复通知）。
     */
    @Update("UPDATE peer_held_message SET status = 'expired' "
        + "WHERE message_id = #{id} AND status = 'pending'")
    int expireRow(@Param("id") String messageId);

    /**
     * 「首次展示」打点：{@code pending_expires_at} <b>只在为 NULL 时设一次</b>，之后任何拉取都不再改变。
     *
     * <p><b>WHY COALESCE</b>（2026-11-20 用户更正）：待确认卡片就嵌在<b>对话流里</b>，流被卡住直到
     * 用户处理 —— 不存在「卡片被藏起来再重新可见」的模型，因此<b>没有续期入口</b>。
     * 计时只以「第一次被展示」为起点；轮询、切会话、重进页面一律不顺延。
     * {@code last_shown_at} 每次刷新（仅记录最近一次展示，不参与到期判定）。
     *
     * <p>只动未到终态的行；ttl <= 0（禁用过期）时调用方根本不会调本方法。
     */
    @Update("UPDATE peer_held_message SET last_shown_at = #{now}, "
        + "pending_expires_at = COALESCE(pending_expires_at, #{expiresAt}) "
        + "WHERE target_session_id = #{sid} AND status = 'pending'")
    int markShownOnce(@Param("sid") String targetSessionId, @Param("now") long now,
                      @Param("expiresAt") long expiresAt);

    /** 测试/运维：直接把某行到期时刻改成给定 epoch millis（NULL 清空 = 不再计时）。 */
    @Update("UPDATE peer_held_message SET pending_expires_at = #{expiresAt} WHERE message_id = #{id}")
    int setPendingExpiresAt(@Param("id") String messageId, @Param("expiresAt") Long expiresAt);

    @Select("SELECT " + COLUMNS + " FROM peer_held_message WHERE message_id = #{id}")
    PeerHeldMessageRecord selectByMessageId(@Param("id") String messageId);

    /** 拒绝：pending → rejected。返回 1 = 本次调用胜出。 */
    @Update("UPDATE peer_held_message SET status = 'rejected' "
        + "WHERE message_id = #{id} AND target_session_id = #{sid} AND status = 'pending'")
    int rejectRow(@Param("id") String messageId, @Param("sid") String targetSessionId);

    /**
     * 批量拒绝：把该会话<b>全部 pending</b> 一次置 rejected（单语句，SQLite 单写锁友好）。
     *
     * <p>条件只有 {@code status='pending'} ⇒ 终态行（accepted/rejected/expired）一律不动；
     * 与 {@link #claim} 并发时同一行只会有一个 UPDATE 命中，因此「要么 accepted 要么 rejected」。
     *
     * @return 实际转终态的行数（无 pending → 0）
     */
    @Update("UPDATE peer_held_message SET status = 'rejected' "
        + "WHERE target_session_id = #{sid} AND status = 'pending'")
    int rejectAllPending(@Param("sid") String targetSessionId);

    /** 重启后 pending 行的目标代次失效：置 -1（读侧按 sessionId 重新 resolve）。 */
    @Update("UPDATE peer_held_message SET target_endpoint_epoch = -1 "
        + "WHERE status = 'pending'")
    int expirePendingEpochs();

    /** 空 messageIds = 该会话全部未读 pending。 */
    @Update("UPDATE peer_held_message SET seen_at = #{now} "
        + "WHERE target_session_id = #{sid} AND status = 'pending' "
        + "AND seen_at IS NULL")
    int markAllSeen(@Param("sid") String targetSessionId, @Param("now") String now);

    /** 显式 messageIds：只标记属于该会话且仍为 pending 的未读行。 */
    @Update({"<script>UPDATE peer_held_message SET seen_at = #{now} "
        + "WHERE target_session_id = #{sid} AND status = 'pending' "
        + "AND seen_at IS NULL "
        + "AND message_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>"})
    int markSeenByIds(@Param("sid") String targetSessionId, @Param("ids") List<String> messageIds,
                      @Param("now") String now);

    // ─────────────── 待处理计数（聚合事件 / GET /api/v1/peer-inbox/counts） ───────────────

    /**
     * 单会话「仍需处理」行数 = {@code pending}。
     *
     * <p><b>口径</b>：⛔ 不含 {@code rejected / accepted / expired}（终态）。批准即结算 ⇒ 批准过的
     * 行已不在 pending，不存在「已批准待消费」需要另减的部分。
     */
    @Select("SELECT COUNT(*) FROM peer_held_message "
        + "WHERE target_session_id = #{sid} AND status = 'pending'")
    long countOpen(@Param("sid") String targetSessionId);

    /**
     * 按会话聚合的「仍需处理」行数（只返回有待处理行的会话，避免把全量会话拉进结果）。
     *
     * <p>顺序不保证；调用方按需排序。空结果 = 当前没有任何会话有待处理项。
     */
    @Select("SELECT p.target_session_id AS sessionId, COUNT(*) AS total, "
        + "MIN(p.rowid) AS oldestRowId, "
        + "(SELECT p2.created_at FROM peer_held_message p2 "
        + " WHERE p2.target_session_id = p.target_session_id AND p2.status = 'pending' "
        + " ORDER BY p2.rowid ASC LIMIT 1) AS oldestCreatedAt "
        + "FROM peer_held_message p "
        + "WHERE p.status = 'pending' GROUP BY p.target_session_id")
    List<com.nexusai.repository.peer.entity.PeerHeldSessionCount> countOpenGroupedBySession();
}
