package com.nexusai.application.agent.mcp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * RES-07d · OfficialMcpRegistry prefetch/isOfficial 接线测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC officialRegistry.ts（prefetchOfficialMcpUrls :33-60 +
 * isOfficialMcpUrl :66-68）——启动 prefetch 填充 official URL set，isOfficialMcpUrl 消费
 * （telemetry 脱敏决策 metadata.ts:112）。未 prefetch → false（fail-closed）；prefetch 失败
 * → set 空不抛（CC :55-59）。启动 runner（McpOfficialRegistryPrefetcher）触发 prefetch。
 */
class OfficialMcpRegistryWiringTest {

    @Test
    @DisplayName("prefetch 后 isOfficial 命中官方 URL（含 query string → 规范化）")
    void prefetchMarksOfficialUrl() {
        OfficialMcpRegistry registry = new OfficialMcpRegistry();
        registry.prefetch(
            () -> List.of(OfficialMcpRegistry.RegistryServer.of("https://api.example.com/mcp?foo=1")),
            Runnable::run).join();

        assertThat(registry.isOfficial(
            OfficialMcpRegistry.normalizeUrl("https://api.example.com/mcp?foo=1")))
            .as("prefetch 填充后，规范化官方 URL 必须命中")
            .isTrue();
        assertThat(registry.isOfficial(
            OfficialMcpRegistry.normalizeUrl("https://api.example.com/mcp")))
            .as("去 query 后同源 URL 也必须命中（规范化集合）")
            .isTrue();
    }

    @Test
    @DisplayName("未命中官方 set → false")
    void customUrlNotOfficial() {
        OfficialMcpRegistry registry = new OfficialMcpRegistry();
        registry.prefetch(
            () -> List.of(OfficialMcpRegistry.RegistryServer.of("https://official.example.com/mcp")),
            Runnable::run).join();

        assertThat(registry.isOfficial(
            OfficialMcpRegistry.normalizeUrl("https://custom.example.com/mcp")))
            .as("custom URL 不在官方 set → false")
            .isFalse();
    }

    @Test
    @DisplayName("未 prefetch → false（fail-closed）")
    void unprefetchedFailsClosed() {
        OfficialMcpRegistry registry = new OfficialMcpRegistry();
        assertThat(registry.isOfficial("https://api.example.com/mcp"))
            .as("未 prefetch → officialUrls 未初始化 → false（CC isOfficialMcpUrl ?? false）")
            .isFalse();
    }

    @Test
    @DisplayName("prefetch 失败 → set 空不抛（fail-closed，CC :55-59）")
    void prefetchFailureLeavesEmptySet() {
        OfficialMcpRegistry registry = new OfficialMcpRegistry();
        CompletableFuture<Integer> f = registry.prefetch(() -> {
            throw new IllegalStateException("network down");
        }, Runnable::run);

        assertThatCode(f::join)
            .as("prefetch 内部 catch 吞异常 → future 正常完成返回 0")
            .doesNotThrowAnyException();
        assertThat(f.join()).isEqualTo(0);
        assertThat(registry.isOfficial("https://api.example.com/mcp"))
            .as("失败后 set 保持空 → 任何 URL 都 false")
            .isFalse();
    }

    @Test
    @DisplayName("启动 runner 触发 prefetch → isOfficial 命中官方 URL（stub fetcher 注入）")
    void startupRunnerTriggersPrefetch() {
        // WHY: McpOfficialRegistryPrefetcher（ApplicationRunner）在启动时调 prefetchSync →
        // registry.prefetch（对齐 CC main.tsx:418 bootstrap prefetchOfficialMcpUrls）。
        OfficialMcpRegistry registry = new OfficialMcpRegistry();
        McpOfficialRegistryPrefetcher prefetcher =
            new McpOfficialRegistryPrefetcher(registry, true);
        prefetcher.setRegistryFetcherForTesting(
            () -> List.of(OfficialMcpRegistry.RegistryServer.of("https://api.example.com/mcp")));

        prefetcher.prefetchSync();

        assertThat(registry.isOfficial(
            OfficialMcpRegistry.normalizeUrl("https://api.example.com/mcp")))
            .as("启动 runner 触发 prefetch 后官方 URL 命中")
            .isTrue();
    }

    @Test
    @DisplayName("prefetch 开关 false → run() 跳过（不触发 fetcher）")
    void disabledSwitchSkips() {
        OfficialMcpRegistry registry = new OfficialMcpRegistry();
        McpOfficialRegistryPrefetcher prefetcher =
            new McpOfficialRegistryPrefetcher(registry, false);
        prefetcher.setRegistryFetcherForTesting(() -> {
            throw new AssertionError("fetcher 不应被调用");
        });

        prefetcher.run(null);

        assertThat(registry.isOfficial("https://api.example.com/mcp"))
            .as("开关 false → 跳过 prefetch，set 空 → false")
            .isFalse();
    }
}
