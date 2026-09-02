package com.nexusai.application.agent.mcp;

import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.mcp.McpStartupPrefetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [impl-I-4 T3] 启动异步预取测试（McpStartupPrefetcher · 对齐 CC main.tsx bootstrap prefetch）。
 *
 * <p>WHY（规则九）：旧实现无启动预取——应用启动后 getCurrentTools 空，需手动 REST start；
 * 或预取同步阻塞启动。CC prefetchAllMcpResources（client.ts:2408-2473）异步预取 enabled servers，
 * 不阻塞启动；预取失败 fail-soft 不阻断（:2460-2471）。
 */
@DisplayName("[impl-I-4 T3] 启动异步预取")
class McpBootstrapPrefetchTest {

    @Test
    @DisplayName("prefetch-on-startup=false → 跳过预取（不调用 startEnabledBatch）")
    void disabled_skipsPrefetch() throws Exception {
        McpServerService svc = mock(McpServerService.class);
        McpStartupPrefetcher prefetcher = new McpStartupPrefetcher(svc, false);
        prefetcher.run(null);
        verify(svc, never()).startEnabledBatch();
    }

    @Test
    @DisplayName("prefetch-on-startup=true → 异步预取 enabled servers")
    void enabled_asyncPrefetches() throws Exception {
        McpServerService svc = mock(McpServerService.class);
        when(svc.startEnabledBatch()).thenReturn(CompletableFuture.completedFuture(null));
        McpStartupPrefetcher prefetcher = new McpStartupPrefetcher(svc, true);
        prefetcher.run(null);
        verify(svc, timeout(2000)).startEnabledBatch();
    }

    @Test
    @DisplayName("预取失败 fail-soft → 不抛（对齐 CC catch → resolve 空，client.ts:2460-2471）")
    void enabled_failure_doesNotThrow() throws Exception {
        McpServerService svc = mock(McpServerService.class);
        when(svc.startEnabledBatch()).thenThrow(new RuntimeException("mock connect boom"));
        McpStartupPrefetcher prefetcher = new McpStartupPrefetcher(svc, true);
        prefetcher.run(null);   // 不应同步抛
        verify(svc, timeout(2000)).startEnabledBatch();
        Thread.sleep(50);       // 让异步 fail-soft 日志跑完（不抛异常即通过）
    }
}
