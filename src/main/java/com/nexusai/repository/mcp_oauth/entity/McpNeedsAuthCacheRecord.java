package com.nexusai.repository.mcp_oauth.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.mcp_oauth.McpNeedsAuthEntry;

/**
 * MyBatis-Flex 持久化记录：{@code mcp_needs_auth_cache} 表行。
 *
 * <p>对齐 CC mcp-needs-auth-cache.json {@code Record<string, {timestamp: number}>}
 * （client.ts:259）——serverName 为缓存键、cachedAt 为缓存时间戳（epoch millis）。CC 文件在
 * config home 目录跨进程共享，Java 落共享表（V13）供多实例部署 needs-auth 判定一致。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link com.nexusai.model.mcp_oauth.McpNeedsAuthEntry}（domain POJO）通过
 * {@link #toDomain()} 与 {@link #fromDomain(McpNeedsAuthEntry)} 互转。
 * 应用层（{@code McpNeedsAuthCacheStore}）应只持有 {@link McpNeedsAuthEntry}，不直接依赖 Record。
 */
@Table("mcp_needs_auth_cache")
public class McpNeedsAuthCacheRecord {
    @Id private String serverName;
    private Long cachedAt;
    private String createdAt;
    private String updatedAt;

    // ============== domain 互转 ==============

    public McpNeedsAuthEntry toDomain() {
        McpNeedsAuthEntry e = new McpNeedsAuthEntry();
        e.setServerName(serverName);
        e.setCachedAt(cachedAt);
        return e;
    }

    public static McpNeedsAuthCacheRecord fromDomain(McpNeedsAuthEntry e) {
        McpNeedsAuthCacheRecord r = new McpNeedsAuthCacheRecord();
        r.setServerName(e.getServerName());
        r.setCachedAt(e.getCachedAt());
        return r;
    }

    // ============== getters/setters ==============

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public Long getCachedAt() { return cachedAt; }
    public void setCachedAt(Long cachedAt) { this.cachedAt = cachedAt; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
