package com.nexusai.repository.session.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.session.entity.WebSearchResultRecord;

/**
 * <b>2026-08-23 前端仅从工具输入输出（tool_result §33.2 outputShape）抽取 WebSearch 格式，
 * 本 Mapper 已停用（无写入/查询调用方）；保留接口供未来审计，不物理删除。</b>
 *
 * <p>WebSearch 详细搜索结果 MyBatis-Flex Mapper · 通道3 存库/查询。
 *
 * <p><b>WHY 放 {@code repository/session/}</b>：与既有 {@link ToolCallMapper} 同仓储包，
 * 两者均为「工具侧事件 → 会话关联表」落库（规则十一：规范一致性）。
 */
@Deprecated
public interface WebSearchResultMapper extends BaseMapper<WebSearchResultRecord> {

}
