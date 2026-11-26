package com.nexusai.kb.repo.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** {@code kb_ingest_task} 表 mapper。V260 建表。 */
public interface KbIngestTaskMapper extends BaseMapper<KbIngestTaskRecord> {

    /** 该文档该类型最新一行的 id（无行 = null）。 */
    @Select("SELECT id FROM kb_ingest_task WHERE knowledge_id = #{knowledgeId} "
        + "AND task_type = #{taskType} ORDER BY id DESC LIMIT 1")
    Long selectLatestTaskId(@Param("knowledgeId") String knowledgeId, @Param("taskType") String taskType);

    /**
     * 手动重试单个子任务：重置为 queued 并清空时间戳。
     *
     * <p>⭐ <b>不动 {@code attempt}</b>：同一 attempt 内重跑，span 树里会多一条同名子 span
     * （失败那条保留，正好用于对照）。{@code state='running'} 的行不重置，避免两个 worker 双跑。
     *
     * @return 1 = 已重置；0 = 行已在跑或不存在
     */
    @Update("UPDATE kb_ingest_task SET state = 'queued', retry_count = 0, error_message = '', "
        + "available_at = #{now}, started_at = NULL, finished_at = NULL "
        + "WHERE id = #{id} AND state <> 'running'")
    int requeueForRetry(@Param("id") long id, @Param("now") String now);
}