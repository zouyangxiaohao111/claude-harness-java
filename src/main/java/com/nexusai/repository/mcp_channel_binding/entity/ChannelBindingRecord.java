package com.nexusai.repository.mcp_channel_binding.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.mcp_channel_binding.ChannelBindingEntry;

/**
 * MyBatis-Flex 持久化记录：{@code mcp_channel_binding} 表行（外部渠道 ↔ 会话绑定）。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link ChannelBindingEntry}（domain POJO/record）通过 {@code toDomain()} 与
 * {@code fromDomain()} 互转（对齐 {@code ChannelAllowlistRecord}）。
 * 应用层（{@code ChannelBindingService}）应只持有 domain record。
 *
 * <p>命名：MyBatis-Flex camelCase→snake 精确映射（channelId → channel_id、
 * serverName → server_name、sessionId → session_id、boundAt → bound_at）。
 */
@Table("mcp_channel_binding")
public class ChannelBindingRecord {

    @Id private String id;
    private String channelId;
    private String serverName;
    private String sessionId;
    private String boundAt;

    // ============== domain 互转 ==============

    public ChannelBindingEntry toDomain() {
        return new ChannelBindingEntry(id, channelId, serverName, sessionId, boundAt);
    }

    public static ChannelBindingRecord fromDomain(ChannelBindingEntry e) {
        ChannelBindingRecord r = new ChannelBindingRecord();
        r.setId(e.id());
        r.setChannelId(e.channelId());
        r.setServerName(e.serverName());
        r.setSessionId(e.sessionId());
        r.setBoundAt(e.boundAt());
        return r;
    }

    // ============== getters/setters ==============

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getBoundAt() { return boundAt; }
    public void setBoundAt(String boundAt) { this.boundAt = boundAt; }
}
