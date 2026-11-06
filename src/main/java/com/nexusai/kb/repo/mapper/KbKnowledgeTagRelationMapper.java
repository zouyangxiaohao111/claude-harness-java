package com.nexusai.kb.repo.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.kb.repo.entity.KbKnowledgeTagRelationRecord;

/**
 * {@code kb_knowledge_tag_relation} 表 mapper。V269 建表。
 *
 * <p>⚠️ 复合主键 ⇒ 读写真源是 {@code KbTagService} 里的参数化原生 SQL，
 * 本 mapper 只用于查询。
 */
public interface KbKnowledgeTagRelationMapper extends BaseMapper<KbKnowledgeTagRelationRecord> {
}
