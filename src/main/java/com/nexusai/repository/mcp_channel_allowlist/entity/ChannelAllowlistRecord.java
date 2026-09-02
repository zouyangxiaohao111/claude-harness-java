package com.nexusai.repository.mcp_channel_allowlist.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;

/**
 * MyBatis-Flex 持久化记录：{@code mcp_channel_allowlist} 表行（Q-37 ledger DB 表）。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link ChannelAllowlistEntry}（domain POJO/record）通过 {@link #toDomain()}
 * 与 {@link #fromDomain(ChannelAllowlistEntry)} 互转（对齐 {@code McpServerRecord}）。
 * 应用层（{@code ChannelAllowlistService}）应只持有 {@link ChannelAllowlistEntry}。
 */
@Table("mcp_channel_allowlist")
public class ChannelAllowlistRecord {
    @Id private String id;
    private String marketplace;
    private String plugin;
    private String createdAt;

    // ============== domain 互转 ==============

    public ChannelAllowlistEntry toDomain() {
        return new ChannelAllowlistEntry(marketplace, plugin, createdAt);
    }

    public static ChannelAllowlistRecord fromDomain(ChannelAllowlistEntry e) {
        ChannelAllowlistRecord r = new ChannelAllowlistRecord();
        r.setMarketplace(e.marketplace());
        r.setPlugin(e.plugin());
        r.setCreatedAt(e.createdAt());
        return r;
    }

    // ============== getters/setters ==============

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMarketplace() { return marketplace; }
    public void setMarketplace(String marketplace) { this.marketplace = marketplace; }
    public String getPlugin() { return plugin; }
    public void setPlugin(String plugin) { this.plugin = plugin; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
