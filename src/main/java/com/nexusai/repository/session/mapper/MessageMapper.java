package com.nexusai.repository.session.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.session.entity.MessageRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MessageMapper extends BaseMapper<MessageRecord> {

    /**
     * [held-state V326] 待确认来信状态迁移 · <b>单条条件 UPDATE</b>（应用层枚举，不落 CHECK）。
     *
     * <p>只改 {@code held_state='pending'} 的行 ⇒ 幂等 + 并发安全：
     * 第二次调用 / 已被拒绝 / 已投递的行 ⇒ 0（调用方不得把 0 当失败重试，最多记日志）。
     *
     * @param id          消息 id（= PeerEnvelope.messageId）
     * @param sessionId   目标会话（含在 WHERE 内，防跨会话误改）
     * @param heldState   目标状态（{@link com.nexusai.domain.session.MessageService#HELD_STATE_DELIVERED}
     *                    或 {@link com.nexusai.domain.session.MessageService#HELD_STATE_DECLINED}）
     * @return 受影响行数（1 = 本次迁移成功；0 = 行不存在 / 不是 pending）
     */
    @Update("UPDATE messages SET held_state = #{heldState} "
        + "WHERE id = #{id} AND session_id = #{sessionId} AND held_state = 'pending'")
    int transitionHeldState(@Param("id") String id, @Param("sessionId") String sessionId,
                            @Param("heldState") String heldState);

    /**
     * [held-state V326] 批准后消费落地：把 hold 时写入的 pending 行<b>原地</b>升级为 delivered，
     * 并用投递侧的权威投影覆盖来源字段（同 id 幂等，⛔ 不插入第二行）。
     *
     * <p>只命中 {@code held_state='pending'} 的行 ⇒ 已 delivered / declined / 普通消息（NULL）均 0。
     *
     * @param id               消息 id（= PeerEnvelope.messageId）
     * @param sessionId        目标会话
     * @param author           user 行作者（peer = 来源 sessionId）
     * @param subtype          行 subtype（peer = agent_message）
     * @param structuredOutput 结构化载荷 JSON（peer_source 投影；按调用方给定值原样覆盖）
     * @param queuedOrigin     排队来源标记（peer）
     * @return 受影响行数（1 = 本次升级成功；0 = 行不存在 / 不是 pending）
     */
    @Update("UPDATE messages SET held_state = 'delivered', author = COALESCE(#{author}, author), "
        + "subtype = COALESCE(#{subtype}, subtype), "
        + "structured_output = COALESCE(#{structuredOutput}, structured_output), "
        + "queued_origin = COALESCE(#{queuedOrigin}, queued_origin), is_meta = 1 "
        + "WHERE id = #{id} AND session_id = #{sessionId} AND held_state = 'pending'")
    int deliverHeldRow(@Param("id") String id, @Param("sessionId") String sessionId,
                       @Param("author") String author, @Param("subtype") String subtype,
                       @Param("structuredOutput") String structuredOutput,
                       @Param("queuedOrigin") String queuedOrigin);
}
