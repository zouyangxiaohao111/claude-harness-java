package com.nexusai.model.mcp_channel_allowlist;

/**
 * Domain entity: channel allowlist 条目（Q-37 ledger 白名单 DB 表行）。
 *
 * <p>CC original: {@code ChannelAllowlistEntry = { marketplace, plugin }}
 * （services/mcp/channelAllowlist.ts:23-26）— GrowthBook 'tengu_harbor_ledger'
 * 返回的 {marketplace, plugin} 列表项，插件级粒度（插件批准 = 其全部 channel server 批准）。
 *
 * <p>DDD 分层：纯 domain POJO（record），持久化由
 * {@link com.nexusai.repository.mcp_channel_allowlist.entity.ChannelAllowlistRecord} 负责。
 *
 * @param marketplace CC original: marketplace（channelAllowlist.ts:24）— 插件来源市场名
 * @param plugin      CC original: plugin（channelAllowlist.ts:25）— 插件名
 * @param createdAt   落库时间（SQLite datetime('now')，Java 侧未显式设置时为 null）
 */
public record ChannelAllowlistEntry(String marketplace, String plugin, String createdAt) {

    /** 2-arg 便捷构造（gate/allowlist 纯比对场景，createdAt 未知） */
    public ChannelAllowlistEntry(String marketplace, String plugin) {
        this(marketplace, plugin, null);
    }
}
