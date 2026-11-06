package com.nexusai.kb.repo.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code kb_knowledge} 表 mapper。V257 建表。
 *
 * <p><b>⭐ 为什么状态机操作用注解原生 SQL 而不是 Flex 的 {@code UpdateChain}</b>
 * （实测踩过，不是风格偏好）：
 * <ol>
 *   <li>{@code UpdateChain} 对<b>带逻辑删除字段</b>的表会自动追加 {@code AND deleted = 0}，
 *       但用 {@code .where("id = ?")} 这种原生条件时，框架拼出的参数个数与实际绑定值错位
 *       ⇒ 实测报 {@code ArrayIndexOutOfBoundsException}（参数 #8 无值）。</li>
 *   <li>{@code UpdateChain.set(column, value)} 的参数类型由<b>实体字段类型</b>决定。
 *       {@code update_time} 是 {@code LocalDateTime} ⇒ 传格式化后的 String 会
 *       在 TypeHandler 里 {@code ClassCastException}。</li>
 *   <li>这组 SQL 是<b>并发正确性核心</b>（条件更新 + 看影响行数），必须能被逐字审阅。
 *       原生 SQL 与源侧 {@code knowledge.go} 的语句一一对应，审查成本最低。</li>
 * </ol>
 *
 * <p><b>⛔ 所有方法都遵循「条件写进 WHERE，用返回的影响行数判断是否成功」</b>，
 * 严禁改成「先 SELECT 再 UPDATE」——那会让 {@code finalizing} 行永久卡死
 * （源侧注释记录的真实事故）。
 */
public interface KbKnowledgeMapper extends BaseMapper<KbKnowledgeRecord> {

    /**
     * 进入 finalizing 并播种槽位数（源侧 {@code SetFinalizing}）。
     *
     * @return 1 = 真的从 processing 转移；0 = 行已不在 processing
     */
    @Update("UPDATE kb_knowledge SET parse_status = 'finalizing', "
        + "pending_subtasks_count = #{expectedSubtasks}, error_message = '', "
        + "update_time = #{now} "
        + "WHERE id = #{id} AND parse_status = 'processing' AND deleted = 0")
    int setFinalizing(@Param("id") String id,
                      @Param("expectedSubtasks") int expectedSubtasks,
                      @Param("now") String now);

    /**
     * 原子递减一个槽位（clamp 在 0）。
     *
     * @return 1 = 成功递减；0 = 计数已为 0
     */
    @Update("UPDATE kb_knowledge SET pending_subtasks_count = pending_subtasks_count - 1, "
        + "update_time = #{now} "
        + "WHERE id = #{id} AND pending_subtasks_count > 0 AND deleted = 0")
    int decrementSubtask(@Param("id") String id, @Param("now") String now);

    /**
     * 计数归零且仍处 finalizing 时提升为 completed。
     *
     * <p>⭐ 每次都无条件执行（即使上一步没递减成功）——「计数已 0 但状态还停在
     * finalizing」的残留也必须有人收尾。
     *
     * @return 1 = 我是那个翻转状态的人；0 = 不满足条件
     */
    @Update("UPDATE kb_knowledge SET parse_status = 'completed', error_message = '', "
        + "processed_at = #{now}, update_time = #{now} "
        + "WHERE id = #{id} AND parse_status = 'finalizing' "
        + "AND pending_subtasks_count = 0 AND deleted = 0")
    int promoteIfDrained(@Param("id") String id, @Param("now") String now);

    /**
     * 无后处理子任务时直接完成（源侧 {@code CompleteProcessingWithoutSubtasks}）。
     *
     * @return 1 = 真的完成
     */
    @Update("UPDATE kb_knowledge SET parse_status = 'completed', pending_subtasks_count = 0, "
        + "error_message = '', processed_at = #{now}, update_time = #{now} "
        + "WHERE id = #{id} AND parse_status = 'processing' AND deleted = 0")
    int completeWithoutSubtasks(@Param("id") String id, @Param("now") String now);

    /**
     * 带守卫地进入 processing（允许从 pending / unprocessed / failed 重试）。
     *
     * @return 1 = 成功
     */
    @Update("UPDATE kb_knowledge SET parse_status = 'processing', error_message = '', "
        + "update_time = #{now} "
        + "WHERE id = #{id} AND parse_status IN ('pending', 'unprocessed', 'failed') "
        + "AND deleted = 0")
    int markProcessing(@Param("id") String id, @Param("now") String now);

    /**
     * 带守卫地标记失败（只在 processing 时生效）。
     *
     * @return 1 = 真的标了失败
     */
    @Update("UPDATE kb_knowledge SET parse_status = 'failed', error_message = #{message}, "
        + "update_time = #{now} "
        + "WHERE id = #{id} AND parse_status = 'processing' AND deleted = 0")
    int markFailed(@Param("id") String id,
                   @Param("message") String message,
                   @Param("now") String now);

    /**
     * 用户取消（保留行与已生成分块）。
     *
     * @return 1 = 真的取消了
     */
    @Update("UPDATE kb_knowledge SET parse_status = 'cancelled', update_time = #{now} "
        + "WHERE id = #{id} "
        + "AND parse_status IN ('pending', 'unprocessed', 'processing', 'finalizing') "
        + "AND deleted = 0")
    int markCancelled(@Param("id") String id, @Param("now") String now);

    /**
     * 开始新一轮尝试：attempt + 1 并置 pending（源侧 attempt 语义的落库版）。
     *
     * @return 1 = 成功
     */
    @Update("UPDATE kb_knowledge SET parse_attempt = parse_attempt + 1, "
        + "parse_status = 'pending', error_message = '', pending_subtasks_count = 0, "
        + "update_time = #{now} "
        + "WHERE id = #{id} AND parse_status <> 'deleting' AND deleted = 0")
    int beginAttempt(@Param("id") String id, @Param("now") String now);

    /**
     * 索引落库完成 · 对齐源侧 {@code finalizeIndexedKnowledgeState}（{@code knowledge_process.go:254-287}）。
     *
     * <p><b>⭐⭐ 为什么必须单独一步、且必须在写分块之后</b>：
     * 检索链路只查 {@code enable_status = 'enabled'} 的文档
     * （源侧 {@code graph_search.go:44} / {@code repository/knowledge.go:116}）。
     * 创建时置 {@code disabled}，**只有分块与索引真正写完**才转 {@code enabled}
     * —— 否则解析还没落库的文档就会出现在检索结果里（召回空块）。
     *
     * <p>⛔ 不要在创建时直接写 {@code enabled}，也不要在解析失败时保留 {@code enabled}。
     *
     * @return 1 = 真的置了
     */
    @Update("UPDATE kb_knowledge SET enable_status = 'enabled', storage_size = #{storageSize}, "
        + "processed_at = #{now}, update_time = #{now} "
        + "WHERE id = #{id} AND parse_status = 'processing' AND deleted = 0")
    int markIndexed(@Param("id") String id,
                    @Param("storageSize") long storageSize,
                    @Param("now") String now);

    /**
     * 读当前 attempt。
     *
     * @return 当前值；行不存在时 null
     */
    @Select("SELECT parse_attempt FROM kb_knowledge WHERE id = #{id} AND deleted = 0")
    Integer selectParseAttempt(@Param("id") String id);
}
