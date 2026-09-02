package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session MPL5 · 注册表层（installed_plugins.json V1/V2 持久化）。
 *
 * <p>验证意图（规则九）：文件为真相源 —— 安装/更新/删除即时写盘、重启读回一致；
 * V1 启动自动转 V2；legacy installed_plugins_v2.json 合并并清理非版本化 cache；
 * 后台更新只改盘不改内存（双态检测）。
 */
class InstalledPluginsManagerTest {

    @TempDir
    Path tempDir;

    private InstalledPluginsFileStore store;
    private InstalledPluginsManager manager;

    @BeforeEach
    void setUp() {
        // 覆写插件根目录到临时目录，避免污染真实用户目录
        PluginDirectories.setPluginCacheDirOverride(tempDir.toString());
        // 决策 D4 读回落：claude 配置根也覆写到临时目录，避免测试读真实 ~/.claude/plugins
        ClaudePaths.setConfigDirOverride(tempDir.toString());
        // G5：PluginFlagging 走 nexusai 自有根（PluginFlagging.java:65）→ 唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        store = new InstalledPluginsFileStore();
        manager = new InstalledPluginsManager();
        manager.setFileStore(store);
    }

    @AfterEach
    void tearDown() {
        PluginDirectories.setPluginCacheDirOverride(null);
        ClaudePaths.setConfigDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
    }

    private Path mainFile() {
        return Paths.get(InstalledPluginsFileStore.getInstalledPluginsFilePath());
    }

    private String readMain() throws Exception {
        return Files.readString(mainFile(), StandardCharsets.UTF_8);
    }

    // ── 验收 1：V2 落盘读回 roundtrip 一致，scope+projectPath 数组 ──

    @Test
    void installWritesV2FileAndRestartReadsBack() throws Exception {
        // GIVEN 空启动
        manager.initialize();
        // WHEN 安装一个插件
        manager.install("alpha", "1.0.0", "marketplace", tempDir.resolve("alpha-src"), null);

        // THEN 文件立即写盘，V2 结构含 scope 数组
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals(2, root.get("version").asInt(), "文件必须为 V2（version=2）");
        assertEquals("user", root.at("/plugins/alpha/0/scope").asText(),
            "Java 无 CC scope 层级 → 落盘统一 user scope（CC migrateV1ToV2 :294）");
        assertTrue(root.at("/plugins/alpha/0").has("sourcePath"),
            "Java ODF 特有字段序列化保留（非 CC 契约，§8）");

        // AND 重启（新 manager/新 store，同一目录）读回一致 —— 文件为真相源
        InstalledPluginsFileStore store2 = new InstalledPluginsFileStore();
        InstalledPluginsManager manager2 = new InstalledPluginsManager();
        manager2.setFileStore(store2);
        manager2.initialize();

        InstalledPluginsManager.InstalledRecord rec = manager2.get("alpha");
        assertNotNull(rec, "重启后必须从文件读回已装插件（验收 4：重启读回一致）");
        assertEquals("1.0.0", rec.version());
        assertEquals("marketplace", rec.scope(), "Java InstallSource 经非 CC source 字段持久化回读");
        assertTrue(rec.enabled());
        // sourcePath = installPath 回读（Java 安装目录）
        assertEquals(tempDir.resolve("alpha-src").toString(), rec.sourcePath().toString());
    }

    @Test
    void multiScopeArrayPreservedAtFileLayer() throws Exception {
        // GIVEN V2 文件含多 scope 数组（CC schemas.ts:1551-1560 示例结构）
        Path src = tempDir.resolve("src.json");
        Files.writeString(src, """
            {
              "version": 2,
              "plugins": {
                "code-formatter@anthropic-tools": [
                  { "scope": "user", "installPath": "/u/cache/1.0.0", "version": "1.0.0" },
                  { "scope": "project", "projectPath": "/proj", "installPath": "/u/cache/1.1.0", "version": "1.1.0" }
                ]
              }
            }
            """);
        Files.createDirectories(mainFile().getParent());
        Files.copy(src, mainFile());

        // WHEN 文件层读盘
        InstalledPluginsFileStore.InstalledPluginsFileV2 data = store.loadFromDisk();

        // THEN 文件层必须保留 V2 数组（roundtrip 一致，验收 1）—— 文件为真相源
        List<InstalledPluginsFileStore.PluginInstallation> installs =
            data.plugins().get("code-formatter@anthropic-tools");
        assertEquals(2, installs.size(), "同一插件多 scope 必须并存（V2 数组语义，schemas.ts:1547-1549）");
        assertEquals("/proj", installs.get(1).projectPath(), "project scope 条目 projectPath 必须回读");
        assertEquals("1.1.0", installs.get(1).version());
        // AND 写回不丢（save→load roundtrip）
        store.save(data);
        assertEquals(2, store.loadFromDisk().plugins().get("code-formatter@anthropic-tools").size(),
            "save→load roundtrip 不得丢数组条目");

        // AND Java 内存投影为单记录（scope 语义差异 §8：Java 无 CC scope 层级，Map 按插件名单键，
        // 多 scope 条目 last-wins）—— 文件层才保有完整 V2 语义
        manager.initialize();
        assertEquals(1, manager.list().size(), "Java 内存投影单记录（文档化差异，feed 按插件名枚举）");
        assertNotNull(manager.get("code-formatter@anthropic-tools"));
    }

    // ── 验收 2：V1 文件启动自动转 V2 ──

    @Test
    void v1FileAutoMigratesToV2OnStartup() throws Exception {
        // GIVEN 旧 V1 文件（CC InstalledPluginsFileSchemaV1 schemas.ts:1482-1491 结构）
        Path src = tempDir.resolve("v1.json");
        Files.writeString(src, """
            {
              "version": 1,
              "plugins": {
                "demo@market": {
                  "version": "1.2.0",
                  "installedAt": "2024-01-15T10:30:00Z",
                  "lastUpdated": "2024-01-15T10:30:00Z",
                  "installPath": "/old/path",
                  "gitCommitSha": "abc123"
                }
              }
            }
            """);
        Files.createDirectories(mainFile().getParent());
        Files.copy(src, mainFile());

        // WHEN 启动初始化
        manager.initialize();

        // THEN 文件就地转 V2：version=2、全 user scope、versioned cache path
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals(2, root.get("version").asInt(), "V1 必须就地转为 V2");
        JsonNode entry = root.at("/plugins/demo@market/0");
        assertEquals("user", entry.get("scope").asText(), "V1 无 scope 概念 → 全 user scope（CC migrateV1ToV2 :294）");
        // versioned cache path: pluginsDir/cache/market/demo/1.2.0
        String expected = Paths.get(tempDir.toString(), "cache", "market", "demo", "1.2.0").toString();
        assertEquals(expected, entry.get("installPath").asText(),
            "installPath 应重算为 versioned cache path（CC :290）");

        // AND 内存已载入（文件为真相源）
        InstalledPluginsManager.InstalledRecord rec = manager.get("demo@market");
        assertNotNull(rec);
        assertEquals("1.2.0", rec.version());
        assertEquals("abc123", rec.gitCommitSha());
    }

    // ── 验收 3：installed_plugins_v2.json rename 合并 + 清理 legacy cache ──

    @Test
    void legacyV2FileRenamedAndLegacyCacheCleaned() throws Exception {
        // GIVEN legacy installed_plugins_v2.json + 未被引用的扁平 legacy cache 目录
        Path pluginsDir = tempDir;
        Files.createDirectories(pluginsDir);
        Files.writeString(pluginsDir.resolve("installed_plugins_v2.json"), """
            {
              "version": 2,
              "plugins": {
                "kept@mkt": [
                  { "scope": "user", "installPath": "%s", "version": "1.0.0" }
                ]
              }
            }
            """.formatted(Paths.get(tempDir.toString(), "cache", "mkt", "kept", "1.0.0").toString().replace("\\", "\\\\")));
        // 被引用的版本化目录（应保留）
        Files.createDirectories(pluginsDir.resolve("cache").resolve("mkt").resolve("kept").resolve("1.0.0"));
        // 未被引用的扁平 legacy 目录（应删除）
        Files.createDirectories(pluginsDir.resolve("cache").resolve("legacyflat"));

        // WHEN 启动初始化
        manager.initialize();

        // THEN v2 文件已 rename 合并进主文件（v2 文件消失）
        assertFalse(Files.exists(pluginsDir.resolve("installed_plugins_v2.json")),
            "legacy installed_plugins_v2.json 必须 rename 合并并删除（CC :125-138）");
        assertTrue(Files.exists(mainFile()), "主文件必须存在且为合并后的 V2");
        String json = readMain();
        assertTrue(json.contains("\"kept@mkt\""), "合并后主文件含 v2 数据");
        // AND legacy 扁平 cache 已清理、版本化目录保留
        assertFalse(Files.exists(pluginsDir.resolve("cache").resolve("legacyflat")),
            "未被引用的 legacy 扁平 cache 目录必须清理（CC cleanupLegacyCache :192-245）");
        assertTrue(Files.exists(pluginsDir.resolve("cache").resolve("mkt").resolve("kept").resolve("1.0.0")),
            "版本化 cache 目录必须保留");
        // AND 内存载入
        assertNotNull(manager.get("kept@mkt"), "rename 合并后内存必须读到插件");
    }

    // ── 验收 4/5：写穿即时 + updateInstallationPathOnDisk 只改盘 + hasPendingUpdates ──

    @Test
    void uninstallRemovesFromDisk() throws Exception {
        manager.initialize();
        manager.install("alpha", "1.0.0", "marketplace", tempDir.resolve("alpha-src"), null);
        assertTrue(readMain().contains("\"alpha\""), "安装后文件必须含该插件");

        manager.uninstall("alpha");

        assertFalse(readMain().contains("\"alpha\""), "删除后文件必须移除该插件（写穿，CC removePluginInstallation :452-475）");
        assertNull(manager.get("alpha"));
    }

    @Test
    void updateInstallationPathOnDiskChangesOnlyDiskAndPendingDetected() throws Exception {
        manager.initialize();
        manager.install("alpha", "1.0.0", "marketplace", tempDir.resolve("v1-dir"), null);

        // WHEN 后台更新器：磁盘新版本目录，会话内存仍旧版本
        Path newPath = tempDir.resolve("v2-dir");
        manager.updateInstallationPathOnDisk("alpha", newPath.toString(), "2.0.0", "sha999");

        // THEN 内存不变（session 继续用旧版本）
        InstalledPluginsManager.InstalledRecord mem = manager.get("alpha");
        assertEquals("1.0.0", mem.version(), "内存必须保持旧版本（CC :586 不动 inMemory）");
        assertEquals(tempDir.resolve("v1-dir").toString(), mem.installPath(),
            "内存 installPath 必须仍指向旧版本目录");
        // 磁盘已更新
        assertTrue(readMain().contains(newPath.toString().replace("\\", "\\\\")),
            "磁盘必须指向新版本目录（CC :560-563）");
        assertTrue(readMain().contains("2.0.0"), "磁盘版本必须更新");
        // AND 双态检测：磁盘 ≠ 内存 → hasPendingUpdates true
        assertTrue(manager.hasPendingUpdates(), "磁盘 installPath 与内存不同必须检出待处理更新（CC :595-618）");
    }

    @Test
    void noPendingUpdatesWhenDiskAndMemoryInSync() throws Exception {
        manager.initialize();
        manager.install("alpha", "1.0.0", "marketplace", tempDir.resolve("v1-dir"), null);
        assertFalse(manager.hasPendingUpdates(), "磁盘与内存同步时不得报告待处理更新");
    }

    // ── 验收 6：PluginLoader 生产 feed 仍枚举 enabled 记录（回归） ──

    @Test
    void pluginLoaderFeedStillEnumeratesEnabledRecords() throws Exception {
        manager.initialize();
        manager.install("feed-plugin", "1.0.0", "marketplace", tempDir.resolve("fp-src"), tempDir.resolve("fp-agents"));
        manager.install("disabled-plugin", "1.0.0", "path", tempDir.resolve("dp-src"), null);
        manager.setEnabled("disabled-plugin", false);

        PluginLoader loader = new PluginLoader();
        loader.setInstalledPluginsManager(manager);
        loader.loadInstalledEnabledPlugins();

        assertNotNull(loader.get("feed-plugin"), "feed 必须注册 enabled 且带 sourcePath 的插件");
        assertEquals(tempDir.resolve("fp-agents").toString(), loader.get("feed-plugin").agentsPath().toString(),
            "feed 4 参 load 必须保留 agentsPath（ODF-C3R 契约）");
        assertNull(loader.get("disabled-plugin"), "disabled 插件不得进入 feed");
    }

    // ── 决策 D4：claude ~/.claude/plugins 兼容读回落（反思偏差修正：读侧接线）──

    @Test
    void claudeInstalledPluginsFallbackReadWhenNexusaiEmpty() throws Exception {
        // GIVEN nexusai installed_plugins.json 不存在（全新）+ claude ~/.claude/plugins/installed_plugins.json 已有插件
        //      —— 意图（规则九）：D4『claude 已装 ~/.claude/plugins 兼容读』必须真实生效——
        //      nexusai 无自有插件时 claude 已装插件经读回落可枚举（先前 getClaudePluginsDirectory()
        //      仅暴露路径、加载侧零接线，claude 插件实际不可枚举——反思偏差）。
        Path claudeHome = tempDir.resolve("claude-home");
        Path claudePlugins = claudeHome.resolve("plugins");
        Files.createDirectories(claudePlugins);
        // 真实安装路径（JSON 内需转义为 \\，读回为单反斜杠，断言用真实路径）
        String claudeInstallPathReal = Paths.get(claudePlugins.toString(),
            "cache", "anthropic", "claude-plugin", "1.0.0").toString();
        String claudeInstallPathEscaped = claudeInstallPathReal.replace("\\", "\\\\");
        Files.writeString(claudePlugins.resolve("installed_plugins.json"), """
            {
              "version": 2,
              "plugins": {
                "claude-plugin@anthropic": [
                  { "scope": "user", "installPath": "%s", "version": "1.0.0" }
                ]
              }
            }
            """.formatted(claudeInstallPathEscaped), StandardCharsets.UTF_8);
        ClaudePaths.setConfigDirOverride(claudeHome.toString());

        // WHEN 启动初始化（Step3 载盘带 D4 claude 读回落）
        manager.initialize();

        // THEN claude 已装插件进入已装枚举（D4 兼容读生效）
        InstalledPluginsManager.InstalledRecord rec = manager.get("claude-plugin@anthropic");
        assertNotNull(rec, "claude 已装插件必须经 D4 读回落进入枚举（读侧未接线前不可枚举）");
        assertEquals("1.0.0", rec.version());
        assertEquals(claudeInstallPathReal, rec.installPath(), "claude 插件代码路径直引 claude cache（不迁移文件）");
        // AND 只读回落不写 nexusai 文件（不迁移，claude 插件仍在 claude 侧）
        assertFalse(Files.exists(mainFile()), "claude 兼容读不得创建/迁移 nexusai installed_plugins.json（D4 不迁移文件）");
    }

    @Test
    void nexusaiInstalledPluginWinsOverSameNameClaudeFallback() throws Exception {
        // GIVEN nexusai 与 claude 均装同名插件 —— 意图（规则九）：读兼容去重『同 name first-wins，
        //      nexusai 赢』，读侧合并不得以 claude 回落覆盖 nexusai 自有安装。
        Files.createDirectories(mainFile().getParent());
        Files.writeString(mainFile(), """
            {
              "version": 2,
              "plugins": {
                "shared@mkt": [
                  { "scope": "user", "installPath": "%s", "version": "2.0.0" }
                ]
              }
            }
            """.formatted(Paths.get(tempDir.toString(), "cache", "mkt", "shared", "2.0.0").toString().replace("\\", "\\\\")),
            StandardCharsets.UTF_8);
        Path claudeHome = tempDir.resolve("claude-home2");
        Files.createDirectories(claudeHome.resolve("plugins"));
        Files.writeString(claudeHome.resolve("plugins").resolve("installed_plugins.json"), """
            {
              "version": 2,
              "plugins": {
                "shared@mkt": [
                  { "scope": "user", "installPath": "/claude/cache/shared/1.0.0", "version": "1.0.0" }
                ]
              }
            }
            """, StandardCharsets.UTF_8);
        ClaudePaths.setConfigDirOverride(claudeHome.toString());

        // WHEN 读侧合并（D4 读回落）
        InstalledPluginsFileStore.InstalledPluginsFileV2 merged =
            store.loadInstalledPluginsWithClaudeFallback();

        // THEN nexusai 同名条目胜出（version=2.0.0），claude 回落不覆盖（first-wins nexusai 赢）
        List<InstalledPluginsFileStore.PluginInstallation> installs =
            merged.plugins().get("shared@mkt");
        assertNotNull(installs, "合并必须含同名插件");
        assertEquals(1, installs.size(), "同 name first-wins：仅保留 nexusai 条目，claude 回落被去重");
        assertEquals("2.0.0", installs.get(0).version(), "同 name 必须 nexusai 优先（first-wins nexusai 赢）");
    }

    // ── MPL5-MIGRATE：Step2 从 enabledPlugins 同步（CC migrateFromEnabledPlugins :1048-1268）──

    @Test
    void resolvableEnabledPluginSyncedWithEntryVersion() throws Exception {
        // GIVEN enabledPlugins 含未安装插件 + 解析器命中（entry.version/marketplaceInstallLocation，CC :1187）
        manager.setEnabledPluginsSupplier(() -> Map.of("code-formatter@anthropic-tools", true));
        manager.setPluginResolver(id -> new PluginMarketplace.LookupResult(
            new PluginMarketplace.Entry("code-formatter", new TextNode("source"), null, null, null, "2.3.1", null),
            tempDir.resolve("mkt-install").toString()));

        // WHEN 启动初始化（Step1 migrate + Step2 同步 + Step3 载盘）
        manager.initialize();

        // THEN 以 entry.version 替换硬编码 unknown 落盘（CC :1231-1233 version===unknown && entry.version）
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals("2.3.1", root.at("/plugins/code-formatter@anthropic-tools/0/version").asText(),
            "版本必须取 entry.version（CC :1231-1233），不得硬编码 unknown");
        String expected = Paths.get(tempDir.toString(), "cache", "anthropic-tools", "code-formatter", "2.3.1").toString();
        assertEquals(expected, root.at("/plugins/code-formatter@anthropic-tools/0/installPath").asText(),
            "installPath 应为真实版本 versioned cache path（CC :1241）");
        assertEquals("user", root.at("/plugins/code-formatter@anthropic-tools/0/scope").asText(),
            "同步条目 scope 统一 user（Java 无 CC scope 层级，§8）");
        // AND 插件本地安装目录（marketplaceInstallLocation + entry.source，CC :1194）经 sourcePath 回读（ODF-C3R feed）
        assertEquals(tempDir.resolve("mkt-install").resolve("source").toString(),
            root.at("/plugins/code-formatter@anthropic-tools/0/sourcePath").asText(),
            "sourcePath 应为 join(marketplaceInstallLocation, entry.source)（CC :1194）");
        // AND Step3 载盘 → 内存可见
        assertNotNull(manager.get("code-formatter@anthropic-tools"),
            "Step2 写盘后 Step3 载盘进内存（CC :727-730）");
    }

    @Test
    void unresolvableEnabledPluginIsSkippedNotGhost() throws Exception {
        // GIVEN enabledPlugins 含未安装插件 + 解析器 stub 返回 null（CC :1179 getPluginById null）
        manager.setEnabledPluginsSupplier(() -> Map.of("ghost@mkt", true));
        manager.setPluginResolver(id -> null);

        // WHEN 启动初始化
        manager.initialize();

        // THEN 市场未命中 → 跳过，绝不写 CC 不会写的幽灵记录（CC :1180-1184 not found skipping）
        assertFalse(Files.exists(mainFile()), "市场未命中插件不得落盘（CC :1180-1184）");
        assertNull(manager.get("ghost@mkt"), "不得注册市场不存在的插件");
    }

    @Test
    void existingEntryScopeSyncedFromSettingsAsTruth() throws Exception {
        // GIVEN 文件已含 plugin@mkt 但 scope=project + projectPath 残留（settings 为真相源，CC :1149-1164）
        Path src = tempDir.resolve("proj.json");
        Files.writeString(src, """
            {
              "version": 2,
              "plugins": {
                "demo@mkt": [
                  { "scope": "project", "projectPath": "/proj", "installPath": "/u/cache/demo@mkt/1.0.0", "version": "1.0.0" }
                ]
              }
            }
            """);
        Files.createDirectories(mainFile().getParent());
        Files.copy(src, mainFile());

        // WHEN enabledPlugins 含该插件（settings 出现即视为已装，CC :1045-1046）+ 解析器 null（既有条目无需解析）
        manager.setEnabledPluginsSupplier(() -> Map.of("demo@mkt", true));
        manager.initialize();

        // THEN 既有条目 scope 以 settings 为真相源纠正为 user、projectPath 清除（CC :1155/:1158-1160）
        JsonNode root = new ObjectMapper().readTree(readMain());
        JsonNode entry = root.at("/plugins/demo@mkt/0");
        assertEquals("user", entry.get("scope").asText(), "scope 必须以 settings 为真相源纠正（CC :1155）");
        assertFalse(entry.has("projectPath"), "user scope 无 projectPath（CC :1158-1160 delete）");
        assertTrue(entry.has("lastUpdated"), "scope 更新必须刷新 lastUpdated（CC :1161）");
    }

    @Test
    void migrateIsIdempotentAcrossRestarts() throws Exception {
        // GIVEN 首启从 enabledPlugins 同步（解析器命中，CC :1179）
        manager.setEnabledPluginsSupplier(() -> Map.of("sync@mkt", false));
        manager.setPluginResolver(id -> new PluginMarketplace.LookupResult(
            new PluginMarketplace.Entry("sync", null, null, null, null, "1.0.0", null),
            tempDir.resolve("mi").toString()));
        manager.initialize();
        assertEquals(1, manager.list().size(), "首启必须同步 1 个插件");

        // WHEN 重启（新 store/新 manager，同一目录 + 同一设置）
        InstalledPluginsFileStore store2 = new InstalledPluginsFileStore();
        InstalledPluginsManager manager2 = new InstalledPluginsManager();
        manager2.setFileStore(store2);
        manager2.setEnabledPluginsSupplier(() -> Map.of("sync@mkt", false));
        manager2.setPluginResolver(id -> new PluginMarketplace.LookupResult(
            new PluginMarketplace.Entry("sync", null, null, null, null, "1.0.0", null),
            tempDir.resolve("mi").toString()));
        manager2.initialize();

        // THEN 不重复添加（幂等：文件已含该插件 → 仅 scope 同步 no-op，CC :1071-1083）
        assertEquals(1, manager2.list().size(), "重启不得重复同步（幂等）");
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals(1, root.at("/plugins/sync@mkt").size(), "文件数组仅一条安装记录，不得叠加");
    }

    @Test
    void emptyEnabledPluginsIsNoOp() throws Exception {
        // GIVEN 空 enabledPlugins（CC :1054 直接 return）
        manager.setEnabledPluginsSupplier(Map::of);

        // WHEN 启动初始化
        manager.initialize();

        // THEN 无 enabledPlugins 且无既有文件 → 不得创建 installed_plugins.json
        assertFalse(Files.exists(mainFile()), "空 enabledPlugins 必须直接 return，不得创建文件（CC :1054-1056）");
    }

    @Test
    void resolverExceptionIsCaughtAndOtherPluginsSynced() throws Exception {
        // GIVEN 两插件，首插件 resolver.apply 抛异常（CC :1175-1255 添加分支 try/catch）
        //      —— 意图（规则九）：单插件失败必须记录继续，不得沿 @PostConstruct 传导致启动级失败
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        enabled.put("boom@mkt", true); // 首个抛异常
        enabled.put("ok@mkt", true);   // 后续正常命中
        manager.setEnabledPluginsSupplier(() -> enabled);
        manager.setPluginResolver(id -> {
            if ("boom@mkt".equals(id)) {
                throw new IllegalStateException("模拟市场查询/解析异常");
            }
            return new PluginMarketplace.LookupResult(
                new PluginMarketplace.Entry("ok", new TextNode("source"), null, null, null, "1.0.0", null),
                tempDir.resolve("re2").toString());
        });

        // WHEN 启动初始化（@PostConstruct 路径）
        manager.initialize();

        // THEN 启动不抛（异常被 catch，CC :1254-1255）；boom 跳过未落盘，ok 正常同步
        assertNotNull(manager.get("ok@mkt"), "抛异常插件之后的正常插件必须继续同步（CC catch :1254-1255 继续下个插件）");
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals("1.0.0", root.at("/plugins/ok@mkt/0/version").asText(), "正常插件必须照常落盘");
        assertFalse(root.path("plugins").has("boom@mkt"), "抛异常的插件必须跳过，不得落盘幽灵记录");
    }

    // ── MPL5-MIGRATE-WIRE：生产装配 ConfigStorage → enabledPlugins 设置源（CC :1050-1051）──

    @Test
    void productionConfigStorageWiresEnabledPluginsSource() throws Exception {
        // GIVEN settings.json 含 enabledPlugins（CC installedPluginsManager.ts:1050-1051 真源）
        // —— 意图（规则九）：生产装配后 migrateFromEnabledPlugins 必须读真实 settings，
        //    而非 MPL5 缺省 Map::of（no-op）。FileConfigStorage 对嵌套对象返回原始 JsonNode
        //    （FileConfigStorage.java:447 jsonNodeToJavaValue），supplier 必须兼容。
        Path nexusaiHome = tempDir.resolve("nexusai-home");
        Path settingsFile = nexusaiHome.resolve(".nexusai").resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, """
            {
              "enabledPlugins": { "code-formatter@anthropic-tools": true }
            }
            """, StandardCharsets.UTF_8);
        manager.setConfigStorage(new FileConfigStorage(new com.nexusai.application.agent.settings.storage.ConfigStorageProperties(
            null, new com.nexusai.application.agent.settings.storage.ConfigStorageProperties.SettingsFile(settingsFile.toString()))));
        manager.setPluginResolver(id -> new PluginMarketplace.LookupResult(
            new PluginMarketplace.Entry("code-formatter", new TextNode("source"), null, null, null, "2.3.1", null),
            tempDir.resolve("mkt-install").toString()));

        // WHEN 启动初始化（生产装配路径：setConfigStorage 注入 supplier → @PostConstruct initialize）
        manager.initialize();

        // THEN settings.enabledPlugins 同步未注册插件（验收 2），版本取 entry.version
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals("2.3.1", root.at("/plugins/code-formatter@anthropic-tools/0/version").asText(),
            "生产 settings.enabledPlugins 必须同步未注册插件（CC :1048-1051），不得 no-op");
        assertNotNull(manager.get("code-formatter@anthropic-tools"), "同步后内存可见（Step3 载盘）");
    }

    @Test
    void configStorageWithoutEnabledPluginsIsNoOp() throws Exception {
        // GIVEN settings.json 无 enabledPlugins 键（CC :1051 enabledPlugins || {} → 空）
        // —— 意图：装配 ConfigStorage 后，settings 无键时行为必须与未接线一致（空 Map，无副作用）
        Path nexusaiHome = tempDir.resolve("nexusai-home2");
        Path settingsFile = nexusaiHome.resolve(".nexusai").resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{ \"model\": \"gpt\" }", StandardCharsets.UTF_8);
        manager.setConfigStorage(new FileConfigStorage(new com.nexusai.application.agent.settings.storage.ConfigStorageProperties(
            null, new com.nexusai.application.agent.settings.storage.ConfigStorageProperties.SettingsFile(settingsFile.toString()))));

        // WHEN 启动初始化
        manager.initialize();

        // THEN 无 enabledPlugins → 直接 return，不得创建 installed_plugins.json（CC :1054-1056）
        assertFalse(Files.exists(mainFile()), "settings 无 enabledPlugins 必须 no-op，不得创建文件");
    }

    // ── V61 插件配置 DB 化：enabledPlugins 读链 DB 优先（settings.enabled_plugins 前端写）──

    @Test
    void dbEnabledPluginsSyncedOnStartup() throws Exception {
        // GIVEN DB settings.enabled_plugins 含未安装插件（V61 前端插件管理页写）+ 解析器命中；
        //      ConfigStorage（settings.json）无 enabledPlugins —— 意图（规则九）：V61 DB 化后
        //      enabledPlugins 读链 DB 优先，启动同步必须从 DB 列读取（不得回落到 settings.json）。
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord s = new SettingsRecord();
        s.setEnabledPlugins("{\"db-plugin@mkt\":true}");
        when(mapper.selectOneById(1)).thenReturn(s);
        ReflectionTestUtils.setField(manager, "settingsMapper", mapper);
        manager.setPluginResolver(id -> new PluginMarketplace.LookupResult(
            new PluginMarketplace.Entry("db-plugin", new TextNode("source"), null, null, null, "1.0.0", null),
            tempDir.resolve("db-install").toString()));
        // settings.json 无 enabledPlugins 键 → 验证 DB 优先 + 回落空
        Path settingsFile = tempDir.resolve("db-empty-settings.json");
        Files.writeString(settingsFile, "{ }", StandardCharsets.UTF_8);
        manager.setConfigStorage(new FileConfigStorage(new com.nexusai.application.agent.settings.storage.ConfigStorageProperties(
            null, new com.nexusai.application.agent.settings.storage.ConfigStorageProperties.SettingsFile(settingsFile.toString()))));

        // WHEN 启动初始化（生产装配路径：setConfigStorage 注入 supplier + DB mapper 注入）
        manager.initialize();

        // THEN DB enabled_plugins 同步未注册插件（V61 DB-first 读链，CC :1048-1051 语义保留）
        JsonNode root = new ObjectMapper().readTree(readMain());
        assertEquals("1.0.0", root.at("/plugins/db-plugin@mkt/0/version").asText(),
            "DB settings.enabled_plugins 必须同步未注册插件（V61 DB-first，不得回落 ConfigStorage no-op）");
        assertNotNull(manager.get("db-plugin@mkt"), "同步后内存可见（Step3 载盘）");
    }

    // ── V61 插件配置 DB 化：pluginClaudeFallback 开关读 DB 列（DB false → 关掉 CC 回落）──

    @Test
    void claudeFallbackDisabledWhenDbSwitchFalse() throws Exception {
        // GIVEN DB settings.plugin_claude_fallback=false（V61 前端插件设置页关掉双读）
        //      —— 意图（规则九）：开关关 = 只读 nexusai 安装记录，claude ~/.claude/plugins
        //      不得回落（原 yml nexusai.feature.plugin-claude-fallback 迁移 DB 后语义保留）。
        Path claudeHome = tempDir.resolve("claude-home-off");
        Path claudePlugins = claudeHome.resolve("plugins");
        Files.createDirectories(claudePlugins);
        String claudeInstallPathReal = Paths.get(claudePlugins.toString(),
            "cache", "anthropic", "cc-only", "1.0.0").toString();
        Files.writeString(claudePlugins.resolve("installed_plugins.json"), """
            {
              "version": 2,
              "plugins": {
                "cc-only@anthropic": [
                  { "scope": "user", "installPath": "%s", "version": "1.0.0" }
                ]
              }
            }
            """.formatted(claudeInstallPathReal.replace("\\", "\\\\")), StandardCharsets.UTF_8);
        ClaudePaths.setConfigDirOverride(claudeHome.toString());

        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord s = new SettingsRecord();
        s.setPluginClaudeFallback(false);
        when(mapper.selectOneById(1)).thenReturn(s);
        ReflectionTestUtils.setField(store, "settingsMapper", mapper);

        // WHEN 读侧合并（D4 读回落，DB 开关 false）
        InstalledPluginsFileStore.InstalledPluginsFileV2 merged =
            store.loadInstalledPluginsWithClaudeFallback();

        // THEN claude 插件不进入合并（DB plugin_claude_fallback=false → 只读 nexusai）
        assertFalse(merged.plugins().containsKey("cc-only@anthropic"),
            "DB plugin_claude_fallback=false → 不得回落实 CC 安装记录（V61 开关迁移 DB 后语义保留）");
    }
}
