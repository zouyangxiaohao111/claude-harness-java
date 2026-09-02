package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flagged plugin 追踪 · 对齐 CC {@code utils/plugins/pluginFlagging.ts}（209 行）。
 *
 * <p>追踪因 marketplace 下架被自动移除的插件。数据存于 {@code ~/.{appName}/plugins/flagged-plugins.json}
 * （决策 D4：写隔离到 nexusai 自有根，不落 claude），结构 {@code {plugins: {id: {flaggedAt, seenAt?}}}}。
 * flagged 插件在 /plugins 显示 "Flagged" 段，直到用户 dismiss。
 *
 * <p><b>内存缓存</b>（:9-13/:34）：模块级缓存使 {@link #getFlaggedPlugins()} 可同步调用；
 * 首次异步调用（load/add）填充并随写保持同步。{@code seenAt} 超过 48h 的条目下次 load 自动清除
 * （SEEN_EXPIRY_MS，:31）。
 *
 * <p><b>原子写</b>（:86-110）：temp 文件 + rename（随机后缀防并发冲突）；写失败清理 temp。
 */
@Component
public class PluginFlagging {

    private static final Logger log = LoggerFactory.getLogger(PluginFlagging.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC original: {@code FLAGGED_PLUGINS_FILENAME}（pluginFlagging.ts:24）。 */
    public static final String FLAGGED_PLUGINS_FILENAME = "flagged-plugins.json";

    /** CC original: {@code SEEN_EXPIRY_MS}（pluginFlagging.ts:31）= 48 小时。 */
    public static final long SEEN_EXPIRY_MS = 48L * 60 * 60 * 1000;

    /** CC original: {@code FlaggedPlugin}（pluginFlagging.ts:26-29）。 */
    public record FlaggedPlugin(String flaggedAt, String seenAt) {
    }

    /** 插件目录覆写（测试注入）；null → PluginDirectories.getPluginsDirectory()。 */
    private Path pluginsDirectoryOverride;

    /** 模块级缓存 · CC {@code let cache}（:34）。null = 尚未 load（getFlaggedPlugins 返回空 Map）。 */
    private Map<String, FlaggedPlugin> cache;

    public PluginFlagging() {
    }

    /** 测试覆写：指向临时插件目录（替代 PluginDirectories.getPluginsDirectory()）。 */
    public PluginFlagging(Path pluginsDirectoryOverride) {
        this.pluginsDirectoryOverride = pluginsDirectoryOverride;
    }

    private Path getFlaggedPluginsPath() {
        if (pluginsDirectoryOverride != null) {
            return pluginsDirectoryOverride.resolve(FLAGGED_PLUGINS_FILENAME);
        }
        // 决策 D4：flagged-plugins.json 恒落 nexusai home/plugins（写隔离，不读 claude）
        return NexusaiPaths.getAppConfigHomePath().resolve("plugins").resolve(FLAGGED_PLUGINS_FILENAME);
    }

    /** 解析 flagged-plugins.json 内容 · CC {@code parsePluginsData}（:40-73）。 */
    private static Map<String, FlaggedPlugin> parsePluginsData(String content) {
        Map<String, FlaggedPlugin> result = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return result;
        }
        try {
            var root = JSON.readTree(content);
            if (root == null || !root.isObject() || !root.has("plugins")
                    || !root.get("plugins").isObject()) {
                return result;
            }
            var plugins = root.get("plugins");
            plugins.fields().forEachRemaining(entry -> {
                var node = entry.getValue();
                if (node != null && node.isObject() && node.has("flaggedAt")
                        && node.get("flaggedAt").isTextual()) {
                    String seenAt = node.has("seenAt") && node.get("seenAt").isTextual()
                        ? node.get("seenAt").asText() : null;
                    result.put(entry.getKey(), new FlaggedPlugin(node.get("flaggedAt").asText(), seenAt));
                }
            });
        } catch (IOException e) {
            log.warn("解析 flagged-plugins.json 失败，返回空：{}", e.getMessage());
            return result;
        }
        return result;
    }

    /** 从磁盘读（缺文件/损坏 → 空 Map 不抛）· CC {@code readFromDisk}（:75-84）。 */
    private Map<String, FlaggedPlugin> readFromDisk() {
        try {
            String content = Files.readString(getFlaggedPluginsPath(), StandardCharsets.UTF_8);
            return parsePluginsData(content);
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /** 原子写盘（temp + rename；失败清理 temp）· CC {@code writeToDisk}（:86-110）。 */
    private void writeToDisk(Map<String, FlaggedPlugin> plugins) {
        Path filePath = getFlaggedPluginsPath();
        Path tempPath = filePath.resolveSibling(filePath.getFileName() + "."
            + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            Path dir = filePath.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            String content = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("plugins", plugins));
            Files.writeString(tempPath, content, StandardCharsets.UTF_8);
            // Node fs.rename 原子覆盖目标；Java Files.move 需 REPLACE_EXISTING（否则 FileAlreadyExists）
            Files.move(tempPath, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            cache = new LinkedHashMap<>(plugins);
        } catch (IOException e) {
            log.error("写 flagged-plugins.json 失败：{}", e.getMessage());
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException cleanup) {
                // 清理失败忽略（CC :104-109）
            }
        }
    }

    /**
     * 从磁盘加载 flagged 插件到缓存 · CC {@code loadFlaggedPlugins}（:117-136）。
     * 超过 SEEN_EXPIRY_MS（48h）的 seenAt 条目自动清除并回写。
     */
    public void loadFlaggedPlugins() {
        Map<String, FlaggedPlugin> all = readFromDisk();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, FlaggedPlugin> e : new LinkedHashMap<>(all).entrySet()) {
            if (e.getValue().seenAt() != null) {
                long seenAtMillis;
                try {
                    seenAtMillis = java.time.Instant.parse(e.getValue().seenAt()).toEpochMilli();
                } catch (Exception ex) {
                    continue;
                }
                if (now - seenAtMillis >= SEEN_EXPIRY_MS) {
                    all.remove(e.getKey());
                    changed = true;
                }
            }
        }
        cache = new LinkedHashMap<>(all);
        if (changed) {
            writeToDisk(all);
        }
    }

    /** 获取全部 flagged 插件 · CC {@code getFlaggedPlugins}（:142-144）。未 load → 空 Map。 */
    public Map<String, FlaggedPlugin> getFlaggedPlugins() {
        return cache == null ? Map.of() : Map.copyOf(cache);
    }

    /** 添加插件到 flagged 列表 · CC {@code addFlaggedPlugin}（:151-165）。 */
    public void addFlaggedPlugin(String pluginId) {
        if (cache == null) {
            cache = readFromDisk();
        }
        Map<String, FlaggedPlugin> updated = new LinkedHashMap<>(cache);
        updated.put(pluginId, new FlaggedPlugin(java.time.Instant.now().toString(), null));
        writeToDisk(updated);
        if (log.isDebugEnabled()) {
            log.debug("Flagged plugin: {}", pluginId);
        }
    }

    /** 标记 flagged 插件为 seen · CC {@code markFlaggedPluginsSeen}（:172-193）。 */
    public void markFlaggedPluginsSeen(java.util.List<String> pluginIds) {
        if (cache == null) {
            cache = readFromDisk();
        }
        String now = java.time.Instant.now().toString();
        boolean changed = false;
        Map<String, FlaggedPlugin> updated = new LinkedHashMap<>(cache);
        for (String id : pluginIds) {
            FlaggedPlugin entry = updated.get(id);
            if (entry != null && entry.seenAt() == null) {
                updated.put(id, new FlaggedPlugin(entry.flaggedAt(), now));
                changed = true;
            }
        }
        if (changed) {
            writeToDisk(updated);
        }
    }

    /** 从 flagged 列表移除 · CC {@code removeFlaggedPlugin}（:199-208）。用户 dismiss 调用。 */
    public void removeFlaggedPlugin(String pluginId) {
        if (cache == null) {
            cache = readFromDisk();
        }
        if (!cache.containsKey(pluginId)) {
            return;
        }
        Map<String, FlaggedPlugin> rest = new LinkedHashMap<>(cache);
        rest.remove(pluginId);
        cache = rest;
        writeToDisk(rest);
    }
}
