package com.nexusai.kb.repo.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;

/**
 * {@code kb_knowledge_base} 表 mapper（MyBatis-Flex 泛型 BaseMapper）。
 *
 * <p>V256 建表；业务入口 {@code com.nexusai.kb.domain.KnowledgeBaseService}。
 * <p>⛔ 不在此定义查询方法 —— 查询条件统一在 Service 用 Flex 的 {@code QueryWrapper} 构造，
 * 与 NexusAI 现有 mapper（如 {@code AttachmentMapper}）的极简风格一致。
 */
public interface KbKnowledgeBaseMapper extends BaseMapper<KbKnowledgeBaseRecord> {
}
