package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Official MCP registry 启动 prefetch · 对齐 CC main.tsx:418 bootstrap 调
 * {@code prefetchOfficialMcpUrls}（officialRegistry.ts:33-60）.
 *
 * <p>L1 语义: 应用启动后 fire-and-forget 抓取 Anthropic MCP registry
 * （{@code https://api.anthropic.com/mcp-registry/v0/servers?version=latest&visibility=commercial}），
 * 填充 {@link OfficialMcpRegistry} 的 official URL set，供 isOfficialMcpUrl 判定
 * （telemetry 脱敏决策，对齐 CC metadata.ts:112）。
 *
 * <p>L2 契约:
 * <ul>
 *   <li><b>开关</b> {@code nexusai.mcp.prefetch-official-registry}（yml 默认 true）；false →
 *       跳过，不产生网络请求。对齐 CC {@code CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC}
 *       门控语义（officialRegistry.ts:34-36）。</li>
 *   <li><b>超时</b> HTTP GET timeout 5s（对齐 CC axios timeout:5000，officialRegistry.ts:41）。</li>
 *   <li><b>fail-closed</b> 抓取/解析失败 → 记 warn，set 保持空，不抛不阻断启动
 *       （对齐 CC catch → logError 不抛，officialRegistry.ts:55-59）。</li>
 *   <li><b>异步</b> CompletableFuture.runAsync 不阻塞启动（仿 {@link McpStartupPrefetcher} 模式）。</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC axios.get → java.net.http.HttpClient; CC fire-and-forget →
 * CompletableFuture.runAsync; CC CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC → yml 开关。
 */
@Component
public class McpOfficialRegistryPrefetcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpOfficialRegistryPrefetcher.class);

    private static final String REGISTRY_URL =
        "https://api.anthropic.com/mcp-registry/v0/servers?version=latest&visibility=commercial";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** registry fetcher（默认真实 HTTP；测试注入 stub）· CC original: axios.get officialRegistry.ts:39。 */
    @FunctionalInterface
    interface RegistryFetcher {
        List<OfficialMcpRegistry.RegistryServer> fetch() throws Exception;
    }

    private final OfficialMcpRegistry registry;
    private final boolean prefetchEnabled;
    private RegistryFetcher fetcher = this::fetchRegistry;

    public McpOfficialRegistryPrefetcher(
            OfficialMcpRegistry registry,
            @Value("${nexusai.mcp.prefetch-official-registry:true}") boolean prefetchEnabled) {
        this.registry = registry;
        this.prefetchEnabled = prefetchEnabled;
    }

    /** 测试注入 stub fetcher（避免真实网络请求）。 */
    void setRegistryFetcherForTesting(RegistryFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!prefetchEnabled) {
            log.info("[McpOfficialRegistryPrefetcher] prefetch 关闭（nexusai.mcp.prefetch-official-registry=false），跳过");
            return;
        }
        // fire-and-forget 异步 prefetch：不阻塞启动（CC prefetchOfficialMcpUrls 无 await 于 bootstrap）
        CompletableFuture.runAsync(this::prefetchSync);
    }

    /** prefetch 同步主体（run() 异步包装内调用；测试可直接调 package-private 版本）。 */
    void prefetchSync() {
        try {
            List<OfficialMcpRegistry.RegistryServer> servers = fetcher.fetch();
            int loaded = registry.prefetch(() -> servers, directExecutor()).join();
            log.info("[McpOfficialRegistryPrefetcher] official MCP registry prefetch 完成，加载 {} 个官方 URL", loaded);
        } catch (Exception e) {
            // fail-closed：对齐 CC catch → logError 不抛（officialRegistry.ts:55-59），set 保持空
            log.error("[McpOfficialRegistryPrefetcher] official registry prefetch 失败（fail-closed 不阻断启动）: {}", e.getMessage(), e);
        }
    }

    /** 真实 HTTP fetcher：GET registry，解析 {@code {servers:[{server:{remotes:[{url}]}}]}}。 */
    private List<OfficialMcpRegistry.RegistryServer> fetchRegistry() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(REGISTRY_URL))
            .timeout(TIMEOUT)
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("registry HTTP " + response.statusCode());
        }
        JsonNode root = MAPPER.readTree(response.body());
        List<OfficialMcpRegistry.RegistryServer> servers = new ArrayList<>();
        JsonNode serversNode = root.path("servers");
        if (serversNode.isArray()) {
            for (JsonNode entry : serversNode) {
                JsonNode remotes = entry.path("server").path("remotes");
                if (!remotes.isArray()) {
                    continue;
                }
                List<String> urls = new ArrayList<>();
                for (JsonNode remote : remotes) {
                    if (remote.path("url").isTextual()) {
                        urls.add(remote.path("url").asText());
                    }
                }
                servers.add(OfficialMcpRegistry.RegistryServer.of(urls.toArray(new String[0])));
            }
        }
        return servers;
    }

    /** prefetch 用直连 executor（单次启动任务，用完即弃，不占全局池）。 */
    private ExecutorService directExecutor() {
        return Executors.newSingleThreadExecutor();
    }
}
