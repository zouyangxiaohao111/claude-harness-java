package com.nexusai.kb.repo.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.kb.repo.entity.KbProcessingSpanRecord;

/**
 * {@code kb_processing_span} 表 mapper。V284 建表。
 *
 * <p>对应源站 {@code knowledge_span_repo.go}：本仓无需手工写 SQL ——
 * 按 knowledge_id + attempt 的查询用 {@code QueryWrapper} 拼条件即可；
 * upsert 语义（同一 span 覆盖自身）由 tracker 层「查到就 update、
 * 查不到就 insert」实现。
 */
public interface KbProcessingSpanMapper extends BaseMapper<KbProcessingSpanRecord> {
}
