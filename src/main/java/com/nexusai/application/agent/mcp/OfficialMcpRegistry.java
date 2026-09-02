package com.nexusai.application.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Official MCP registry URL 缓存 · 对齐 CC services/mcp/officialRegistry.ts.
 *
 * <p>L1 语义: fire-and-forget 抓取 Anthropic MCP registry → 规范化 URL set (无 query string + 无 trailing /).
 *            提供 isOfficialMcpUrl 判断 + resetForTesting.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `prefetch(Supplier&lt;List&lt;RegistryEntry&gt;&gt;)` + `isOfficial(String)` + `resetForTesting()` 签名</li>
 *   <li><b>A2 Golden Trace</b>: 未抓取 → isOfficial=false (fail-closed); 抓取后命中 set → true</li>
 *   <li><b>A3</b>: 抓取失败 → set 保持空 (CC fail-closed, 不抛)</li>
 *   <li><b>A4</b>: URL 规范化 — 去除 query string + 去除 trailing slash + 解析失败 URL 跳过</li>
 *   <li><b>A5</b>: 真实 registry 数据 — [{server:{remotes:[{url:"https://x.com/mcp?foo=1"]}},...]</li>
 * </ul>
 *
 * <p>L3 (Java idiom): `URI` 替代 JS `new URL`; Executor 注入测试可控; Set.of 不可变.
 *
 * <p>[RES-07d] 注册 {@code @Component}（无参默认构造器），启动 prefetch 由
 * {@link McpOfficialRegistryPrefetcher}（ApplicationRunner）触发；isOfficial 消费方 =
 * {@code PermissionDecisionLogger}（MCP 工具 telemetry 脱敏决策，对齐 CC metadata.ts:112）。
 */
@Component
public class OfficialMcpRegistry {

    private static final Logger log = LoggerFactory.getLogger(OfficialMcpRegistry.class);

    /** CC registry 服务器条目 (slim). */
    public record RegistryServer(List<Remote> remotes) {
        public record Remote(String url) {}
        public static RegistryServer of(String... urls) {
            return new RegistryServer(java.util.Arrays.stream(urls)
                .map(Remote::new).toList());
        }
    }

    private Set<String> officialUrls;

    /**
     * Fire-and-forget prefetch.
     *
     * @param fetcher  返回 list of RegistryServer 的函数 (CC axios.get 替代)
     * @param executor 异步执行 executor
     * @return CompletableFuture&lt;Integer&gt; 完成的 URL 数
     */
    public CompletableFuture<Integer> prefetch(Supplier<List<RegistryServer>> fetcher, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<RegistryServer> servers = fetcher.get();
                Set<String> urls = new HashSet<>();
                for (RegistryServer server : servers) {
                    if (server.remotes() == null) continue;
                    for (var remote : server.remotes()) {
                        String normalized = normalizeUrl(remote.url());
                        if (normalized != null) urls.add(normalized);
                    }
                }
                this.officialUrls = Set.copyOf(urls);
                log.info("[OfficialMcpRegistry] loaded {} official URLs", urls.size());
                return urls.size();
            } catch (Exception e) {
                log.warn("[OfficialMcpRegistry] prefetch failed (fail-closed): {}", e.getMessage());
                this.officialUrls = Set.of();
                return 0;
            }
        }, executor);
    }

    /** CC isOfficialMcpUrl: 规范化 URL 在 set 中 → true; 未抓取 → false (fail-closed). */
    public boolean isOfficial(String normalizedUrl) {
        if (normalizedUrl == null || officialUrls == null) return false;
        return officialUrls.contains(normalizedUrl);
    }

    /** 测试用 — 重置 set. */
    public void resetForTesting() {
        this.officialUrls = null;
    }

    /** 测试用 — 显式注入 set (跳过 prefetch). */
    public void setUrlsForTesting(Set<String> urls) {
        this.officialUrls = urls == null ? null : Set.copyOf(urls);
    }

    /**
     * CC normalizeUrl: 去 query string + 去 trailing slash; 解析失败 → null.
     *
     * <p>[impl-I-4 T8] 对齐 CC {@code officialRegistry.ts:19-27}（JS URL.href 语义：
     * 小写 host + 剥默认端口 + 保留 userinfo/fragment + strip 尾 {@code /}）——旧实现
     * （URI 重建）保留大小写 + 保留 :443 + 丢弃 userinfo，方向与 CC 相反（MCP-08 D-1）。
     */
    public static String normalizeUrl(String url) {
        return McpUrlNormalizer.normalizeOfficial(url);
    }
}