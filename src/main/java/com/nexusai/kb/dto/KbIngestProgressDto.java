package com.nexusai.kb.dto;

/**
 * 库级入库进度（一次聚合查询，避免前端逐行拉 span）。
 *
 * <p><b>⭐ 为什么「就绪」要单独一档</b>：后处理子任务在跑时
 * {@code parse_status} 已经是 {@code completed}，但 {@code pending_subtasks_count > 0}
 * ⇒ 只看状态会把「还在收尾」的文档显示成已完成。就绪 = completed 且计数归零。
 *
 * <p>字段 public 供 MyBatis 直接写入 + Jackson 直接序列化（本 DTO 没有行为，不需要 getter）。
 */
public class KbIngestProgressDto {

    /** 未删除文档总数（进度条分母）。 */
    public long total;

    /** 排队中：pending / unprocessed。 */
    public long queued;

    /** 处理中：processing / finalizing。 */
    public long processing;

    /** 就绪：completed 且 pending_subtasks_count = 0。 */
    public long ready;

    /** 待后处理：completed 但 pending_subtasks_count > 0。 */
    public long waitingPost;

    /** 失败：failed。 */
    public long failed;

    /** 已取消：cancelled。 */
    public long cancelled;

    /** 全部文档剩余子任务总数（后处理池压力的粗略指标）。 */
    public long pendingSubtasks;
}