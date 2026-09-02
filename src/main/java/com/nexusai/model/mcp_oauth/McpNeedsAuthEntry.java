package com.nexusai.model.mcp_oauth;

/**
 * Domain entity: MCP needs-auth 缓存条目。
 *
 * <p>对齐 CC mcp-needs-auth-cache.json 单条目 {@code McpAuthCacheData = Record<string,
 * {timestamp: number}>}（client.ts:259），键 = server name（setMcpAuthCacheEntry(name)
 * client.ts:359，isMcpAuthCached(name) client.ts:280-287）。CC 存 config home 文件，Java
 * 落 DB（R2-3 受控偏差：文件 → 共享表，多实例一致）。内存 ConcurrentHashMap 保留为本地
 * 快路径，DB 为共享兜底（读共享源优先 / 写穿）。
 *
 * <p>DDD 分层：纯 POJO，无 {@code @Table} 注解，持久化由
 * {@link com.nexusai.repository.mcp_oauth.entity.McpNeedsAuthCacheRecord} 负责。
 */
public class McpNeedsAuthEntry {
    private String serverName;  // 缓存键 = server name（CC setMcpAuthCacheEntry(name) client.ts:359）
    private Long cachedAt;      // epoch millis（CC entry.timestamp = Date.now() client.ts:297）

    public McpNeedsAuthEntry() {
    }

    public McpNeedsAuthEntry(String serverName, Long cachedAt) {
        this.serverName = serverName;
        this.cachedAt = cachedAt;
    }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public Long getCachedAt() { return cachedAt; }
    public void setCachedAt(Long cachedAt) { this.cachedAt = cachedAt; }
}
