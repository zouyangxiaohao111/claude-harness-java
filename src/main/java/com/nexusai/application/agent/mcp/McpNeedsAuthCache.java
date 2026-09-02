package com.nexusai.application.agent.mcp;

import com.nexusai.domain.mcp_oauth.McpNeedsAuthCacheStore;
import com.nexusai.model.mcp_oauth.McpNeedsAuthEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * needs-auth 缓存 · 对齐 CC client.ts:257-316 {@code isMcpAuthCached} /
 * {@code setMcpAuthCacheEntry} / {@code clearMcpAuthCache}。
 *
 * <p>L1 语义: MCP server 连接期返回 401（CC UnauthorizedError）→ {@link #setCached}；
 * 批连接缓存跳过路径（client.ts:2307-2322）用 {@link #isCached} 在 15min TTL 内跳过对该
 * server 的连接重试，避免每次批连接都重复网络往返（connect-401 + OAuth discovery 一轮）。
 * OAuth 认证成功（McpAuthTool 后台 reconnect 前，McpAuthTool.ts:139）→ {@link #clear} 全量清除。
 *
 * <p>[R2-3 共享存储兜底] CC 缓存为 config home 文件 mcp-needs-auth-cache.json（client.ts:262，
 * 单机多进程共享）；Java web 后端多实例部署无文件系统共享 → 新增 {@link McpNeedsAuthCacheStore}
 * 落 {@code mcp_needs_auth_cache} 表（V13）作为共享事实源：
 * <ul>
 *   <li><b>读共享源优先</b>：{@link #isCached} 在 store 注入时以 DB 为权威（实例 A 401 置位 →
 *       实例 B 命中；实例 A clear → 实例 B 立即失效），命中即回填内存镜像（cachedAt 取自共享
 *       时间戳而非 now，两端 TTL 窗口一致，对齐 CC 文件 entry.timestamp 判定 client.ts:286）。
 *       连接期判定非热路径（每批连接/装配一次），DB 主键查询开销可忽略。</li>
 *   <li><b>写穿</b>：{@link #setCached} 更新内存 + 写 DB（best-effort，DB 失败降级纯内存，
 *       对齐 CC setMcpAuthCacheEntry .catch 吞错 client.ts:306-308）</li>
 *   <li><b>clear</b>：清内存 + 清 DB（CC unlink 整文件 → 全量失效 client.ts:311-316）</li>
 * </ul>
 * store=null（测试 new 实例 / Spring 未注入）→ {@link #isCached} 回落纯内存 map TTL 判定
 * （R1 语义不变）；store 注入时内存 map 仅作写穿镜像，判定始终走共享源（保证跨实例一致）。
 *
 * <p>[S3 接线] 消费方：
 * <ol>
 *   <li>{@link McpToolPool#processBatchServer} 连接阶段捕获 {@link McpAuthError} → setCached</li>
 *   <li>{@link McpToolPool#processBatchServer} 连接前 isCached 判定 → 跳过连接产 authenticate 伪工具</li>
 *   <li>{@link McpToolPool#reconnectServerForAuth}（OAuth 成功）→ clear</li>
 * </ol>
 */
@Component
public final class McpNeedsAuthCache {

    private static final Logger log = LoggerFactory.getLogger(McpNeedsAuthCache.class);

    /** CC MCP_AUTH_CACHE_TTL_MS = 15 * 60 * 1000（client.ts:257）。 */
    public static final long TTL_MS = 15 * 60 * 1000L;

    private final Map<String, Long> timestamps = new ConcurrentHashMap<>();

    /**
     * [R2-3] 共享存储兜底（DB，跨实例一致）· Spring 注入；测试/未接线 → null → 纯内存行为。
     */
    @Autowired(required = false)
    private McpNeedsAuthCacheStore store;

    /** [R2-3] 测试/自定义接线入口（Spring 之外注入共享存储）。 */
    public void setStore(McpNeedsAuthCacheStore store) {
        this.store = store;
        if (log.isDebugEnabled()) {
            log.debug("[McpNeedsAuthCache] 注入共享存储 store={}", store != null);
        }
    }

    /**
     * CC isMcpAuthCached（client.ts:280-287）：TTL 内 → true；过期 / 从未缓存 → false。
     *
     * <p>[R2-3] store 注入时<b>读共享源优先</b>：以 DB 为权威判定（实例 A 401 置位 → 实例 B 命中；
     * 实例 A clear → 实例 B 立即失效），命中回填内存镜像。cachedAt 取自共享时间戳而非本机 now，
     * 保证跨实例 TTL 窗口一致（对齐 CC 文件 entry.timestamp 判定，client.ts:286）。DB 读失败
     * fail-open 视为未缓存（对齐 CC getMcpAuthCache readFile .catch(() => ({})) client.ts:273-275），
     * 不阻断连接流程。store=null（测试/未接线）→ 纯内存 map TTL 判定（R1 语义）。
     */
    public boolean isCached(String serverId) {
        if (serverId == null) {
            return false;
        }
        if (store != null) {
            try {
                Long shared = store.readCachedAt(serverId);
                boolean cached = shared != null && (System.currentTimeMillis() - shared) < TTL_MS;
                if (cached) {
                    timestamps.put(serverId, shared);
                    if (log.isDebugEnabled()) {
                        log.debug("[McpNeedsAuthCache] serverId={} 共享存储命中，回填内存镜像 cachedAt={}", serverId, shared);
                    }
                }
                return cached;
            } catch (Exception e) {
                log.warn("[McpNeedsAuthCache] needs-auth 共享存储读失败 serverId={}（fail-open 视为未缓存）: {}",
                    serverId, e.getMessage());
                return false;
            }
        }
        Long ts = timestamps.get(serverId);
        return ts != null && (System.currentTimeMillis() - ts) < TTL_MS;
    }

    /**
     * CC setMcpAuthCacheEntry（client.ts:293-309）：记录缓存时刻（并发安全）。
     *
     * <p>[R2-3] 写穿共享存储（best-effort）：DB 写入失败仅降级纯内存并 warn，不阻断 401
     * 处理路径（对齐 CC .catch 吞错 client.ts:306-308）。
     */
    public void setCached(String serverId) {
        if (serverId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        timestamps.put(serverId, now);
        if (store != null) {
            try {
                store.save(new McpNeedsAuthEntry(serverId, now));
            } catch (Exception e) {
                log.warn("[McpNeedsAuthCache] needs-auth 缓存写穿 DB 失败 serverId={}（降级内存）: {}",
                    serverId, e.getMessage());
            }
        }
    }

    /**
     * CC clearMcpAuthCache（client.ts:311-316）：OAuth 成功后全量清除（幂等）。
     *
     * <p>[R2-3] 同步清共享存储（CC unlink 整文件 → 全量失效），best-effort 降级内存。
     */
    public void clear() {
        timestamps.clear();
        if (store != null) {
            try {
                store.clearAll();
            } catch (Exception e) {
                log.warn("[McpNeedsAuthCache] needs-auth 缓存清空 DB 失败（降级内存）: {}", e.getMessage());
            }
        }
    }
}
