package com.nexusai.repository.project.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.project.entity.ClaudeMdIncludeApprovalRecord;

/**
 * MyBatis-Flex mapper：{@code claude_md_include_approval} 表（V74）。
 *
 * <p>对齐 {@link ProjectMapper} 模式：BaseMapper 提供按 configKey 主键的增删改查（selectOneById /
 * insert / update），本项目键下只有一行，无需额外自定义 SQL。
 *
 * <p>由 {@code @MapperScan("com.nexusai.repository.*.mapper")}（NexusAiApplication:11）通配自动注册。
 */
public interface ClaudeMdIncludeApprovalMapper extends BaseMapper<ClaudeMdIncludeApprovalRecord> {
}
