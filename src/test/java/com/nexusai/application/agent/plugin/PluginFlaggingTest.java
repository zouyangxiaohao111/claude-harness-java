package com.nexusai.application.agent.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL9] PluginFlagging · 对齐 CC utils/plugins/pluginFlagging.ts（209 行）。
 *
 * <p>WHY（规则九）：CC 的 flagged 数据存于 plugins 目录 flagged-plugins.json（磁盘真相源 +
 * 内存缓存同步），seenAt 超过 48h 自动清除。本测试锁定持久化 + 缓存 + 过期清除三组契约。
 */
@DisplayName("[MPL9] PluginFlagging 对齐 CC pluginFlagging.ts")
class PluginFlaggingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("add + get：写入内存缓存并落盘")
    void addAndGet() {
        PluginFlagging f = new PluginFlagging(tempDir);
        f.addFlaggedPlugin("format@mkt");

        assertThat(f.getFlaggedPlugins()).containsKeys("format@mkt");
        assertThat(f.getFlaggedPlugins().get("format@mkt").flaggedAt()).isNotBlank();
    }

    @Test
    @DisplayName("跨实例持久化：新实例 load 后可见（磁盘真相源）")
    void persistsToDiskAcrossInstances() {
        new PluginFlagging(tempDir).addFlaggedPlugin("format@mkt");

        PluginFlagging f2 = new PluginFlagging(tempDir);
        f2.loadFlaggedPlugins();

        assertThat(f2.getFlaggedPlugins()).containsKey("format@mkt");
    }

    @Test
    @DisplayName("remove：dismiss 后从缓存与磁盘移除")
    void remove() {
        PluginFlagging f = new PluginFlagging(tempDir);
        f.addFlaggedPlugin("format@mkt");
        f.removeFlaggedPlugin("format@mkt");

        assertThat(f.getFlaggedPlugins()).isEmpty();

        PluginFlagging f2 = new PluginFlagging(tempDir);
        f2.loadFlaggedPlugins();
        assertThat(f2.getFlaggedPlugins()).isEmpty();
    }

    @Test
    @DisplayName("markFlaggedPluginsSeen：补 seenAt 且幂等（已 seen 不重复写）")
    void markSeen() {
        PluginFlagging f = new PluginFlagging(tempDir);
        f.addFlaggedPlugin("format@mkt");
        f.markFlaggedPluginsSeen(java.util.List.of("format@mkt"));

        assertThat(f.getFlaggedPlugins().get("format@mkt").seenAt()).isNotBlank();
    }

    @Test
    @DisplayName("load：seenAt 超过 48h 的条目自动清除（SEEN_EXPIRY_MS）")
    void loadClearsExpiredSeenEntries() throws Exception {
        String past = Instant.now().minus(50, ChronoUnit.HOURS).toString();
        String now = Instant.now().toString();
        Files.writeString(tempDir.resolve(PluginFlagging.FLAGGED_PLUGINS_FILENAME),
            "{\"plugins\":{"
                + "\"old@mkt\":{\"flaggedAt\":\"" + past + "\",\"seenAt\":\"" + past + "\"},"
                + "\"new@mkt\":{\"flaggedAt\":\"" + now + "\"}"
                + "}}");

        PluginFlagging f = new PluginFlagging(tempDir);
        f.loadFlaggedPlugins();

        assertThat(f.getFlaggedPlugins()).doesNotContainKey("old@mkt").containsKey("new@mkt");
    }
}
