package com.nexusai.repository.mcp_oauth.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.mcp_oauth.entity.McpNeedsAuthCacheRecord;
import org.apache.ibatis.annotations.Delete;

/**
 * MyBatis-Flex mapper：{@code mcp_needs_auth_cache} 表（V13）。
 * 对齐 {@link McpOAuthTokenMapper} 模式：BaseMapper 提供按 serverName 主键增删改查。
 */
public interface McpNeedsAuthCacheMapper extends BaseMapper<McpNeedsAuthCacheRecord> {

    /**
     * 清空整表（对齐 CC clearMcpAuthCache unlink 整个缓存文件，client.ts:311-316）。
     *
     * <p>WHY 裸 SQL 而非 {@code deleteByQuery(QueryWrapper.create())}：MyBatis-Flex 对无 where
     * 的 deleteByQuery 做安全拦截（MybatisFlexException「需要带 where 条件」）——批量清空是
     * 明确意图，用 @Delete 显式表达（V13 表仅缓存用途，整表清空语义即 CC 全量失效）。
     */
    @Delete("DELETE FROM mcp_needs_auth_cache")
    int clearAll();
}
