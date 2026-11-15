package com.nexusai.domain.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * MCP 启动异步预取 · 对齐 CC main.tsx bootstrap（启动即调 {@code prefetchAllMcpResources}，
 * client.ts:2408-2473，UI 不阻塞）。
 *
 * <p>[impl-I-4 T3] 语义：
 * <ul>
 *   <li>应用启动后异步预取 enabled MCP servers（{@link McpServerService#startEnabledBatch()}），
 *       不阻塞启动（{@code CompletableFuture.runAsync}）</li>
 *   <li>开关 {@code nexusai.mcp.prefetch-on-startup}（yml 默认 true，对齐 CC 启动即预取）；
 *       false → 维持 REST 手动 start</li>
 *   <li>fail-soft：单 server 失败仅记日志（startEnabledBatch 内部空回调），预取整体失败不阻断启动
 *       （对齐 CC prefetchAllMcpResources catch → resolve 空，client.ts:2460-2471）</li>
 * </ul>
 *
 * <p>用 {@link ApplicationRunner}（与 {@code BundledSkillsBootstrapper} 一致），非
 * {@code ApplicationReadyEvent}——Spring 中 Runner 在 ready 前 run()，异步预取本身不阻塞 ready。
 */
@Component
public class McpStartupPrefetcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStartupPrefetcher.class);

    private final McpServerService mcpServerService;
    /** [V295] 全局总闸真源 = settings 表；yml 仅作 DB 未配置时的兜底。 */
    private final com.nexusai.domain.settings.SettingsService settingsService;
    private final boolean prefetchOnStartupYmlDefault;

    @org.springframework.beans.factory.annotation.Autowired
    public McpStartupPrefetcher(McpServerService mcpServerService,
                                com.nexusai.domain.settings.SettingsService settingsService,
                                @Value("${nexusai.mcp.prefetch-on-startup:true}") boolean prefetchOnStartupYmlDefault) {
        this.mcpServerService = mcpServerService;
        this.settingsService = settingsService;
        this.prefetchOnStartupYmlDefault = prefetchOnStartupYmlDefault;
    }

    /** 测试/老装配兼容（无 settings 服务 → 只看 yml 值）。 */
    public McpStartupPrefetcher(McpServerService mcpServerService, boolean prefetchOnStartupYmlDefault) {
        this(mcpServerService, null, prefetchOnStartupYmlDefault);
    }

    @Override
    public void run(ApplicationArguments args) {
        // [V295] 配置全部入 SQL：settings.mcp_prefetch_on_startup 优先，yml 兜底
        Boolean dbValue = settingsService != null ? settingsService.readMcpPrefetchOnStartup() : null;
        boolean prefetchOnStartup = dbValue != null ? dbValue : prefetchOnStartupYmlDefault;
        if (!prefetchOnStartup) {
            log.info("[McpStartupPrefetcher] 启动预取关闭（source={} enabled=false），跳过",
                dbValue != null ? "db" : "yml");
            return;
        }
        // 异步预取：不阻塞启动（CC prefetchAllMcpResources 异步 pendingCount）
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[McpStartupPrefetcher] 启动异步预取 enabled MCP servers");
                mcpServerService.startEnabledBatch().join();
                log.info("[McpStartupPrefetcher] 启动预取完成");
            } catch (Exception e) {
                // fail-soft：对齐 CC prefetchAllMcpResources catch → resolve 空（client.ts:2460-2471）
                log.error("[McpStartupPrefetcher] 启动预取失败（fail-soft 不阻断启动）: {}", e.getMessage(), e);
            }
        });
    }
}
