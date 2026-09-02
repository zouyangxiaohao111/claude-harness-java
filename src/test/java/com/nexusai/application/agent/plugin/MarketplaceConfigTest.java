package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.application.agent.settings.storage.ConfigStorageProperties;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [MPL1] Marketplace 配置层 · 对齐 CC marketplaceManager.ts:102-434 / schemas.ts:1592-1629.
 *
 * <p>WHY（规则九 · 测试验证意图）：CC 的 known_marketplaces.json 是<b>文件态</b>配置
 * （load 缺失→{}、损坏→ConfigParseError 不覆盖；save 缩进 2；seed 注册幂等 first-seed-wins；
 * settings 意图层写 extraKnownMarketplaces）。旧 Java MarketplaceManager 是纯内存 Map 孤儿
 * （add/remove/update/get/list 零调用方），重启即失忆且无法被 seed 预置。本测试锁定：文件读写 +
 * schema 校验 + seed 注册幂等 + settings 写入五组契约。
 */
@DisplayName("[MPL1] marketplace 配置层（known_marketplaces.json + seed + settings）对齐 CC")
class MarketplaceConfigTest {

    @TempDir
    Path tempDir; // plugin cache dir override

    /** 内存 ConfigStorage fake · 记录 extraKnownMarketplaces 写入（对齐 CC updateSettingsForSource）。 */
    private static final class FakeConfigStorage implements ConfigStorage {
        final Map<String, Object> settings = new LinkedHashMap<>();
        List<String> lastWrittenPath;
        Object lastWrittenValue;

        @Override
        public Object readGlobal(String key) { return settings.get(key); }
        @Override
        public void writeGlobal(String key, Object value) { settings.put(key, value); }
        @Override
        public void unsetGlobal(String key) { settings.remove(key); }

        @Override
        public Object readSettings(List<String> path) {
            if (path.isEmpty()) { return settings.get(path); }
            Object cur = settings.get(path.get(0));
            for (int i = 1; i < path.size(); i++) {
                if (!(cur instanceof Map)) { return null; }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) cur;
                cur = m.get(path.get(i));
            }
            return cur;
        }

        @Override
        public void writeSettings(List<String> path, Object value) {
            lastWrittenPath = path;
            lastWrittenValue = value;
            if (path.isEmpty()) { settings.put(path.get(0), value); return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) settings.computeIfAbsent(path.get(0), k -> new LinkedHashMap<>());
            for (int i = 1; i < path.size() - 1; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) root.computeIfAbsent(path.get(i), k -> new LinkedHashMap<>());
                root = next;
            }
            root.put(path.get(path.size() - 1), value);
        }

        @Override
        public void unsetSettings(List<String> path) { settings.clear(); }
        @Override
        public void addChangeListener(ConfigChangeListener listener) { }
        @Override
        public void removeChangeListener(ConfigChangeListener listener) { }
    }

    @BeforeEach
    void setUp() {
        PluginDirectories.setPluginCacheDirOverride(tempDir.toString());
        PluginDirectories.setPluginSeedDirOverride(null);
    }

    @AfterEach
    void tearDown() {
        // 静态 holder 重置，防测试间污染（MPL 后续 session 与主流水线共享同一 JVM）
        PluginDirectories.setPluginCacheDirOverride(null);
        PluginDirectories.setPluginSeedDirOverride(null);
        PluginDirectories.setUseCoworkPluginsOverride(null);
    }

    private MarketplaceManager manager() {
        return new MarketplaceManager();
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. load：缺失 → {} / 损坏 → 抛错不覆盖
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("known_marketplaces.json 缺失 → load 返回空 Map（marketplaceManager.ts:284-285 isENOENT→{}）")
    void load_missingFile_returnsEmpty() {
        // WHY: CC :284-285 —— 首次启动无配置文件时不能抛错，否则一切 marketplace 读路径全崩。
        Map<String, KnownMarketplace> config = manager().loadKnownMarketplacesConfig();
        assertThat(config).isEmpty();
    }

    @Test
    @DisplayName("JSON 语法损坏 → 抛普通运行时异常（非 ConfigParseError）且文件不被动（:292-296）")
    void load_corruptedJson_throwsRuntimeException_fileUntouched() throws Exception {
        // WHY: CC :291-296 —— JSON parse error 走通用分支 throw new Error，不是 ConfigParseError；
        //      区分两者是为了"损坏但结构合法"的配置文件报 ConfigParseError（可恢复），
        //      语法错误则只是无法读取。
        Path file = tempDir.resolve("known_marketplaces.json");
        Files.writeString(file, "not-json{");

        assertThatThrownBy(() -> manager().loadKnownMarketplacesConfig())
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(ConfigParseError.class);
        assertThat(Files.readString(file)).isEqualTo("not-json{");
    }

    @Test
    @DisplayName("结构损坏（缺 installLocation）→ ConfigParseError 且不覆盖文件（:275-281 + :301-308）")
    void load_schemaInvalid_throwsConfigParseError_fileUntouched() throws Exception {
        // WHY: 文件损坏（结构合法但 schema 不合法）必须抛 ConfigParseError（:280）——
        //      Safe 版返回 {} 会让 load→mutate→save 把损坏文件覆盖成仅剩新条目，永久丢用户其他条目。
        Path file = tempDir.resolve("known_marketplaces.json");
        String corrupted = "{\"foo\":{\"source\":{\"source\":\"url\",\"url\":\"https://example.com/marketplace.json\"},\"lastUpdated\":\"2024-01-01T00:00:00.000Z\"}}";
        Files.writeString(file, corrupted);

        assertThatThrownBy(() -> manager().loadKnownMarketplacesConfig())
            .isInstanceOf(ConfigParseError.class)
            .hasMessageContaining("corrupted");
        assertThat(Files.readString(file)).isEqualTo(corrupted);
    }

    @Test
    @DisplayName("Safe 版：损坏 → 返回空 Map 不抛（:309-317，仅限只读路径）")
    void loadSafe_corrupted_returnsEmpty() throws Exception {
        Files.writeString(tempDir.resolve("known_marketplaces.json"), "{\"foo\":{\"source\":{}}}");
        assertThat(manager().loadKnownMarketplacesConfigSafe()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. save：缩进 2 + 字段逐项对齐 CC schema + round-trip
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("save → 缩进 2 JSON + source/installLocation/lastUpdated/autoUpdate 字段齐（schemas.ts:1592-1629 + :327-350）")
    void save_writesIndent2FieldsMatchSchema() throws Exception {
        // WHY: 文件态配置必须可被 CC 等价读取：缩进 2（jsonStringify(data,null,2)）+ 字段名逐项一致
        //      （source 判别联合 / installLocation / lastUpdated ISO / autoUpdate 可选）。
        KnownMarketplace entry = new KnownMarketplace(
            new MarketplaceSource.Github("mycompany/plugins", "main", ".claude-plugin", null),
            "C:/marketplaces/company-plugins",
            "2024-01-14T15:45:00.000Z",
            true);
        manager().saveKnownMarketplacesConfig(Map.of("company-plugins", entry));

        String content = Files.readString(tempDir.resolve("known_marketplaces.json"));
        assertThat(content)
            .as("顶层条目缩进 2 空格（CC jsonStringify space=2）")
            .startsWith("{\n  \"company-plugins\"")
            .contains("\"source\" : \"github\"")
            .contains("\"repo\" : \"mycompany/plugins\"")
            .contains("\"installLocation\" : \"C:/marketplaces/company-plugins\"")
            .contains("\"lastUpdated\" : \"2024-01-14T15:45:00.000Z\"")
            .contains("\"autoUpdate\" : true");
    }

    @Test
    @DisplayName("save → load round-trip 等价（文件态持久化非内存孤儿）")
    void save_thenLoad_roundTripEqual() {
        // WHY: 验收标准 6 —— MarketplaceManager 复用文件层；save 后重新 load 必须拿到等价配置，
        //      而非旧实现那种只活在内存 Map、重启即失忆的孤儿。
        KnownMarketplace entry = new KnownMarketplace(
            new MarketplaceSource.Url("https://example.com/marketplace.json", null),
            "/home/u/.claude/plugins/marketplaces/example",
            "2024-01-15T10:30:00.000Z",
            null);
        MarketplaceManager m = manager();
        m.saveKnownMarketplacesConfig(Map.of("example", entry));

        Map<String, KnownMarketplace> loaded = manager().loadKnownMarketplacesConfig();
        assertThat(loaded).containsEntry("example", entry);
    }

    @Test
    @DisplayName("save 非法配置 → ConfigParseError（:334-339 写前校验）")
    void save_invalidConfig_throwsConfigParseError() {
        // WHY: CC saveKnownMarketplacesConfig 写前必须过 schema（:331-339），否则损坏/非法条目会
        //      被直接落盘污染文件态。Java 端 KnownMarketplace record 构造器已拦空字段（构造即校验），
        //      save 门再拦 null 条目——两层校验缺一不可。
        assertThatThrownBy(() -> new KnownMarketplace(
            new MarketplaceSource.File("/tmp/m.json"), "", "2024-01-01T00:00:00.000Z", null))
            .as("record 构造器拦截空 installLocation")
            .isInstanceOf(IllegalArgumentException.class);

        Map<String, KnownMarketplace> config = new LinkedHashMap<>();
        config.put("bad", null);
        assertThatThrownBy(() -> manager().saveKnownMarketplacesConfig(config))
            .as("save 前校验门拦截 null 条目")
            .isInstanceOf(ConfigParseError.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3/4. seed 注册：幂等 + first-seed-wins + installLocation 运行时重算
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("seed 注册：首次 true 写 primary（installLocation 运行时重算 + autoUpdate=false），二次 false（幂等 :380-434）")
    void registerSeedMarketplaces_firstWrites_secondIdempotent() throws Exception {
        // WHY: seed 是 admin 受管只读层（:362-372）——installLocation 必须按运行时 seedDir 重算
        //      （不信构建期 JSON 里的 stale 路径），autoUpdate 强制 false（只读不可 git-pull）；
        //      幂等（:374 注释 "second call with unchanged seed writes nothing"）避免每次启动重写文件。
        Path seed = Files.createDirectories(tempDir.resolve("seed1"));
        Files.createDirectories(seed.resolve("marketplaces").resolve("foo"));
        Files.writeString(seed.resolve("known_marketplaces.json"),
            "{\"foo\":{\"source\":{\"source\":\"url\",\"url\":\"https://example.com/foo.json\"},"
                + "\"installLocation\":\"/stale/seed1/foo\",\"lastUpdated\":\"2024-01-01T00:00:00.000Z\"}}");
        PluginDirectories.setPluginSeedDirOverride(seed.toString());

        MarketplaceManager m = manager();
        assertThat(m.registerSeedMarketplaces()).as("首次注册有变更 → true").isTrue();

        Map<String, KnownMarketplace> primary = manager().loadKnownMarketplacesConfig();
        KnownMarketplace foo = primary.get("foo");
        assertThat(foo).isNotNull();
        assertThat(foo.installLocation())
            .as("installLocation 按运行时 seedDir 重算，不信 seed JSON 内 /stale/ 路径")
            .isEqualTo(seed.resolve("marketplaces").resolve("foo").toString());
        assertThat(foo.autoUpdate()).as("seed 只读 → autoUpdate 强制 false").isFalse();
        assertThat(foo.source()).isEqualTo(new MarketplaceSource.Url("https://example.com/foo.json", null));

        assertThat(m.registerSeedMarketplaces()).as("二次无变化 → false（幂等）").isFalse();
    }

    @Test
    @DisplayName("多 seed：同名 first-seed-wins，后 seed 认领未占用名（:386-410）")
    void registerSeedMarketplaces_firstSeedWins_acrossSeeds() throws Exception {
        // WHY: CC :386-387 "first-seed-wins ... a marketplace name claimed by an earlier seed
        //      is skipped by later seeds" —— 同一名字后 seed 不得覆盖前 seed（admin 序即优先级）。
        Path seed1 = Files.createDirectories(tempDir.resolve("seed1"));
        Path seed2 = Files.createDirectories(tempDir.resolve("seed2"));
        Files.createDirectories(seed1.resolve("marketplaces").resolve("foo"));
        Files.createDirectories(seed2.resolve("marketplaces").resolve("foo"));
        Files.createDirectories(seed2.resolve("marketplaces").resolve("bar"));
        Files.writeString(seed1.resolve("known_marketplaces.json"),
            "{\"foo\":{\"source\":{\"source\":\"github\",\"repo\":\"acme/plugins\"},"
                + "\"installLocation\":\"/s1/foo\",\"lastUpdated\":\"2024-01-01T00:00:00.000Z\"}}");
        Files.writeString(seed2.resolve("known_marketplaces.json"),
            "{\"foo\":{\"source\":{\"source\":\"url\",\"url\":\"https://evil.com/foo.json\"},"
                + "\"installLocation\":\"/s2/foo\",\"lastUpdated\":\"2024-01-02T00:00:00.000Z\"},"
                + "\"bar\":{\"source\":{\"source\":\"url\",\"url\":\"https://example.com/bar.json\"},"
                + "\"installLocation\":\"/s2/bar\",\"lastUpdated\":\"2024-01-02T00:00:00.000Z\"}}");
        PluginDirectories.setPluginSeedDirOverride(seed1 + java.io.File.pathSeparator + seed2);

        manager().registerSeedMarketplaces();

        Map<String, KnownMarketplace> primary = manager().loadKnownMarketplacesConfig();
        assertThat(primary.get("foo").source())
            .as("同名 foo：first-seed-wins → seed1 的 github 来源")
            .isEqualTo(new MarketplaceSource.Github("acme/plugins", null, null, null));
        assertThat(primary.get("foo").installLocation())
            .isEqualTo(seed1.resolve("marketplaces").resolve("foo").toString());
        assertThat(primary.get("bar").source())
            .as("后 seed 认领未占用名 bar")
            .isEqualTo(new MarketplaceSource.Url("https://example.com/bar.json", null));
    }

    @Test
    @DisplayName("未配置 seed dir → register 直接 false（:381-382）")
    void registerSeedMarketplaces_noSeedDirs_returnsFalse() {
        assertThat(manager().registerSeedMarketplaces()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. settings 意图层
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("saveMarketplaceToSettings 默认写 settings.extraKnownMarketplaces（:226-238 默认 userSettings）")
    void saveMarketplaceToSettings_defaultWritesExtraKnownMarketplaces() {
        // WHY: CC :234-237 —— existing.extraKnownMarketplaces[name] = entry 后整键写回；
        //      意图层与文件态（known_marketplaces.json）互不污染（:220 "Does NOT touch known_marketplaces.json"）。
        FakeConfigStorage store = new FakeConfigStorage();
        MarketplaceManager m = manager();
        m.setConfigStorage(store);
        KnownMarketplace entry = new KnownMarketplace(
            new MarketplaceSource.Url("https://example.com/marketplace.json", null),
            "/home/u/.claude/plugins/marketplaces/example",
            "2024-01-15T10:30:00.000Z",
            true);

        m.saveMarketplaceToSettings("example", entry);

        assertThat(store.lastWrittenPath).isEqualTo(List.of("extraKnownMarketplaces"));
        @SuppressWarnings("unchecked")
        Map<String, Object> written = (Map<String, Object>) store.lastWrittenValue;
        assertThat(written).containsKey("example");
        assertThat(written.get("example")).isEqualTo(entry);
    }

    @Test
    @DisplayName("saveMarketplaceToSettings 未接线 ConfigStorage → 日志降级不抛（可空注入）")
    void saveMarketplaceToSettings_noConfigStorage_skipsWithoutError() {
        KnownMarketplace entry = new KnownMarketplace(
            new MarketplaceSource.Url("https://example.com/marketplace.json", null),
            "/home/u/.claude/plugins/marketplaces/example",
            "2024-01-15T10:30:00.000Z",
            null);
        manager().saveMarketplaceToSettings("example", entry); // 不抛
    }

    @Test
    @DisplayName("[MPL8-D6e] 真实 FileConfigStorage 写侧：KnownMarketplace 为对象 JSON 非字符串，读回 source 可解析")
    void saveMarketplaceToSettings_realFileConfigStorage_roundTripObject() {
        // WHY: FileConfigStorage.toJsonNode 对 record 走 textNode(String.valueOf(value)) 兜底
        //      （FileConfigStorage.java:396 旧版）→ KnownMarketplace 落盘成字符串而非对象 JSON，
        //      MarketplaceConfigStore.getDeclaredMarketplaces 读侧（D6 已兼容 ObjectNode）将无法解析
        //      source → declared 缺失 → reconcile 永不触发。本测试锁死：写侧必须为对象，读回字段可解析。
        FileConfigStorage store = new FileConfigStorage(
            new ConfigStorageProperties("config.json",
                new ConfigStorageProperties.SettingsFile("settings.json")));
        MarketplaceManager m = manager();
        m.setConfigStorage(store);
        KnownMarketplace entry = new KnownMarketplace(
            new MarketplaceSource.Url("https://example.com/marketplace.json", null),
            "/home/u/.claude/plugins/marketplaces/example",
            "2024-01-15T10:30:00.000Z",
            true);

        m.saveMarketplaceToSettings("example", entry);

        Object raw = store.readSettings(List.of("extraKnownMarketplaces"));
        assertThat(raw).as("生产 ConfigStorage 嵌套对象读回为 JsonNode").isInstanceOf(JsonNode.class);
        JsonNode example = ((JsonNode) raw).path("example");
        assertThat(example.isObject())
            .as("KnownMarketplace 写侧应为对象 JSON（source/installLocation/lastUpdated 字段），非 record.toString 字符串")
            .isTrue();
        assertThat(example.path("source").path("source").asText())
            .as("source 判别键（@JsonTypeInfo property=source）可解析 → Url")
            .isEqualTo("url");
        assertThat(example.path("source").path("url").asText())
            .isEqualTo("https://example.com/marketplace.json");
        assertThat(example.path("installLocation").asText())
            .isEqualTo("/home/u/.claude/plugins/marketplaces/example");
    }
}
