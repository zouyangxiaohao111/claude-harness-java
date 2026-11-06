package com.nexusai.repository.mcp_channel_binding.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.mcp_channel_binding.entity.ChannelBindingRecord;

/**
 * MCP channel 绑定表 mapper（对齐 {@code McpServerMapper}）。
 *
 * <p>「同渠道全局唯一」由 DB 的 {@code UNIQUE(channel_id)} 保证，
 * 故本 mapper 只提供按单列查询，不提供 upsert（避免绕过唯一约束）。
 */
public interface ChannelBindingMapper extends BaseMapper<ChannelBindingRecord> {

    /** 按渠道 id 查询（channel_id UNIQUE ⇒ 至多一行）· 互斥判定的唯一读入口。 */
    default ChannelBindingRecord selectOneByChannelId(String channelId) {
        return selectOneByQuery(QueryWrapper.create().where("channel_id = ?", channelId));
    }

    /** 按会话 id 查询该会话已绑定的全部渠道。 */
    default java.util.List<ChannelBindingRecord> selectListBySessionId(String sessionId) {
        return selectListByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
    }

    /** 按 server 名查询（门序[3] 的消费入口：连接时手里只有 serverName）。 */
    default java.util.List<ChannelBindingRecord> selectListByServerName(String serverName) {
        return selectListByQuery(QueryWrapper.create().where("server_name = ?", serverName));
    }
}
