package com.nexusai.domain.mcp_oauth;

import com.nexusai.model.mcp_oauth.McpNeedsAuthEntry;
import com.nexusai.repository.mcp_oauth.entity.McpNeedsAuthCacheRecord;
import com.nexusai.repository.mcp_oauth.mapper.McpNeedsAuthCacheMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * MCP needs-auth 缓存持久化服务（对齐 CC mcp-needs-auth-cache.json，Java 用 DB）。
 *
 * <p>CC 把 needs-auth 缓存存 config home 文件（client.ts:261-316 getMcpAuthCachePath /
 * setMcpAuthCacheEntry / clearMcpAuthCache，键 = server name，TTL 15min）；Java 对齐语义落
 * {@code mcp_needs_auth_cache} 表（V13），供多实例部署共享 needs-auth 判定：实例 A 连接期
 * 401 置位 → 实例 B {@code isCached} 判定命中（读共享源优先），两端 TTL 判定用同一 cachedAt
 * 时间戳（CC 文件语义 = entry.timestamp，client.ts:286）。
 *
 * <p>null-safe：读不存在的 serverName 返回 null；clearAll 对空表静默成功（不抛）。
 * 写入失败由调用方（{@code McpNeedsAuthCache}）吞掉降级为纯内存（对齐 CC setMcpAuthCacheEntry
 * 的 best-effort .catch，client.ts:306-308）。
 */
@Service
public class McpNeedsAuthCacheStore {

    private static final Logger log = LoggerFactory.getLogger(McpNeedsAuthCacheStore.class);

    @Autowired private McpNeedsAuthCacheMapper mcpNeedsAuthCacheMapper;

    /** 读取指定 serverName 的缓存时间戳（epoch millis）；不存在返回 null。 */
    public Long readCachedAt(String serverName) {
        if (serverName == null) {
            return null;
        }
        McpNeedsAuthCacheRecord r = mcpNeedsAuthCacheMapper.selectOneById(serverName);
        if (r == null) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpNeedsAuthCacheStore] read needs-auth serverName={} 命中 cachedAt={}", serverName, r.getCachedAt());
        }
        return r.getCachedAt();
    }

    /** 保存缓存条目（insert 或 update，幂等）。 */
    public void save(McpNeedsAuthEntry entry) {
        if (entry == null || entry.getServerName() == null) {
            return;
        }
        McpNeedsAuthCacheRecord r = McpNeedsAuthCacheRecord.fromDomain(entry);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (mcpNeedsAuthCacheMapper.selectOneById(entry.getServerName()) != null) {
            r.setUpdatedAt(now);
            mcpNeedsAuthCacheMapper.update(r);
        } else {
            // 对齐 McpServerService:createdAt 显式填充（MyBatis-Flex insert 会带 NULL 覆盖 DB DEFAULT）
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            mcpNeedsAuthCacheMapper.insert(r);
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpNeedsAuthCacheStore] save needs-auth serverName={} cachedAt={}", entry.getServerName(), entry.getCachedAt());
        }
    }

    /**
     * 清空全部 needs-auth 缓存。
     *
     * <p>对齐 CC clearMcpAuthCache unlink 整文件 → 全量清除（client.ts:311-316），
     * OAuth 认证成功（McpAuthTool.ts:139）后所有 server 的 needs-auth 缓存一并失效。
     */
    public void clearAll() {
        mcpNeedsAuthCacheMapper.clearAll();
        if (log.isDebugEnabled()) {
            log.debug("[McpNeedsAuthCacheStore] clearAll needs-auth 缓存（全量）");
        }
    }
}
