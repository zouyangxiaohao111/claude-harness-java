package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpTypesRegistry;
import com.nexusai.application.agent.plugin.McpbHandler.McpbLoadOutcome;
import com.nexusai.application.agent.plugin.McpbHandler.McpbLoadResult;
import com.nexusai.application.agent.plugin.McpbHandler.McpbNeedsConfigResult;
import com.nexusai.application.agent.plugin.McpbHandler.SecureValueStore;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * McpbHandler 真实现测试（S08 F5/F6）· 对齐 CC mcpbHandler.ts:346-968。
 *
 * <p>WHY（规则九 · 验证意图）：McpbHandler 由 40 行内存壳改写为生产加载器 —— 缓存命中/
 * mtime 变更重载/needs-config 分支/错误分类/元数据落盘是加载链正确性的核心不变量，
 * 且是 F1（loadMcpServersFromMcpb）的下游依赖。
 */
class McpbHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path pluginPath;
    private MapConfigStorage settings;
    private MapSecureStore secureStore;
    private McpbHandler handler;

    @BeforeEach
    void setUp() {
        pluginPath = tempDir.resolve("plugin");
        settings = new MapConfigStorage();
        secureStore = new MapSecureStore();
        handler = new McpbHandler();
        handler.setConfigStorage(settings);
        handler.setSecureValueStore(secureStore);
    }

    @AfterEach
    void tearDown() {
        PluginDirectories.setPluginCacheDirOverride(null);
    }

    // ============== isMcpbSource（CC :79-81） ==============

    @Test
    @DisplayName("isMcpbSource：.mcpb/.dxt 后缀判定")
    void isMcpbSource_suffixes() {
        assertThat(McpbHandler.isMcpbSource("bundle.mcpb")).isTrue();
        assertThat(McpbHandler.isMcpbSource("bundle.dxt")).isTrue();
        assertThat(McpbHandler.isMcpbSource("servers.json")).isFalse();
        assertThat(McpbHandler.isMcpbSource("http://x/b.mcpb")).isTrue();
        assertThat(McpbHandler.isMcpbSource(null)).isFalse();
    }

    // ============== validateUserConfig（CC :346-408） ==============

    @Test
    @DisplayName("validateUserConfig：required 缺失/空串 → error")
    void validate_requiredMissing() {
        Map<String, Object> schema = Map.of("apiKey", Map.of("type", "string", "required", true, "title", "API Key"));
        McpbHandler.ValidationResult r = handler.validateUserConfig(Map.of(), schema);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).containsExactly("API Key is required but not provided");
        // 空串同样视为未提供（:357）
        McpbHandler.ValidationResult r2 = handler.validateUserConfig(Map.of("apiKey", ""), schema);
        assertThat(r2.valid()).isFalse();
        // 提供 → 通过
        McpbHandler.ValidationResult r3 = handler.validateUserConfig(Map.of("apiKey", "k"), schema);
        assertThat(r3.valid()).isTrue();
    }

    @Test
    @DisplayName("validateUserConfig：string 类型含 multiple 数组校验（:368-380）")
    void validate_stringTypes() {
        Map<String, Object> schema = Map.of(
            "plain", Map.of("type", "string"),
            "multi", Map.of("type", "string", "multiple", true));
        // 数组对非 multiple string → error
        McpbHandler.ValidationResult r = handler.validateUserConfig(Map.of("plain", List.of("a")), schema);
        assertThat(r.errors()).contains("plain must be a string, not an array");
        // multiple string 数组全字符串 → ok
        McpbHandler.ValidationResult r2 = handler.validateUserConfig(Map.of("multi", List.of("a", "b")), schema);
        assertThat(r2.valid()).isTrue();
        // multiple 数组含非字符串 → error
        McpbHandler.ValidationResult r3 = handler.validateUserConfig(Map.of("multi", List.of("a", 1)), schema);
        assertThat(r3.errors()).contains("multi must be an array of strings");
        // 数字对 string → error
        McpbHandler.ValidationResult r4 = handler.validateUserConfig(Map.of("plain", 42), schema);
        assertThat(r4.errors()).contains("plain must be a string");
    }

    @Test
    @DisplayName("validateUserConfig：number/boolean/file/directory 类型 + min/max 范围（:381-404）")
    void validate_typesAndRanges() {
        Map<String, Object> schema = Map.of(
            "port", Map.of("type", "number", "min", 1, "max", 65535),
            "flag", Map.of("type", "boolean"),
            "cert", Map.of("type", "file"),
            "dataDir", Map.of("type", "directory"));
        assertThat(handler.validateUserConfig(Map.of("port", "x"), schema).errors())
            .contains("port must be a number");
        assertThat(handler.validateUserConfig(Map.of("port", 0), schema).errors())
            .contains("port must be at least 1");
        assertThat(handler.validateUserConfig(Map.of("port", 70000), schema).errors())
            .contains("port must be at most 65535");
        assertThat(handler.validateUserConfig(Map.of("port", 8080), schema).valid()).isTrue();
        assertThat(handler.validateUserConfig(Map.of("flag", "true"), schema).errors())
            .contains("flag must be a boolean");
        assertThat(handler.validateUserConfig(Map.of("flag", true), schema).valid()).isTrue();
        assertThat(handler.validateUserConfig(Map.of("cert", 1), schema).errors())
            .contains("cert must be a path string");
        assertThat(handler.validateUserConfig(Map.of("cert", "/a.pem", "dataDir", "/d"), schema).valid()).isTrue();
    }

    // ============== loadMcpServerUserConfig / saveMcpServerUserConfig（CC :141-341） ==============

    @Test
    @DisplayName("save/loadMcpServerUserConfig：sensitive 拆分 + secure 胜出合并")
    void userConfig_roundTrip() {
        Map<String, Object> schema = Map.of(
            "token", Map.of("type", "string", "sensitive", true),
            "channel", Map.of("type", "string"));
        handler.saveMcpServerUserConfig("plug@market", "srv1",
            Map.of("token", "SECRET", "channel", "general"), schema);
        // 非敏感 → settings pluginConfigs 段；敏感 → secure bucket（pluginId/serverName）
        assertThat(settings.readSettings(List.of("pluginConfigs", "plug@market", "mcpServers", "srv1")))
            .isEqualTo(Map.of("channel", "general"));
        assertThat(secureStore.buckets.get("plug@market/srv1")).isEqualTo(Map.of("token", "SECRET"));
        // 合并读取，secure 胜出
        assertThat(handler.loadMcpServerUserConfig("plug@market", "srv1"))
            .isEqualTo(Map.of("channel", "general", "token", "SECRET"));
        // 未配置 server → null（两源皆空）
        assertThat(handler.loadMcpServerUserConfig("plug@market", "nope")).isNull();
    }

    @Test
    @DisplayName("saveMcpServerUserConfig：sensitive→secure 后 schema 翻转 → 双向 scrub（:211-218）")
    void userConfig_scrubBothDirections() {
        Map<String, Object> sensitiveSchema = Map.of("token", Map.of("type", "string", "sensitive", true));
        handler.saveMcpServerUserConfig("plug@market", "s1", Map.of("token", "S"), sensitiveSchema);
        assertThat(secureStore.buckets.get("plug@market/s1")).isEqualTo(Map.of("token", "S"));
        assertThat(settings.readSettings(List.of("pluginConfigs", "plug@market", "mcpServers", "s1"))).isNull();

        // 同一键翻转为非敏感保存 → settings 写入 + secure 陈旧值清除
        Map<String, Object> plainSchema = Map.of("token", Map.of("type", "string"));
        handler.saveMcpServerUserConfig("plug@market", "s1", Map.of("token", "plain"), plainSchema);
        assertThat(settings.readSettings(List.of("pluginConfigs", "plug@market", "mcpServers", "s1")))
            .isEqualTo(Map.of("token", "plain"));
        assertThat(secureStore.buckets.get("plug@market/s1")).isNull(); // scrubbed
        assertThat(handler.loadMcpServerUserConfig("plug@market", "s1")).isEqualTo(Map.of("token", "plain"));
    }

    @Test
    @DisplayName("saveMcpServerUserConfig：部分重配不丢其它字段（CC :217-218 防御）")
    void userConfig_partialKeepsOthers() {
        Map<String, Object> schema = Map.of("a", Map.of("type", "string"), "b", Map.of("type", "string"));
        handler.saveMcpServerUserConfig("p@m", "s", Map.of("a", "1", "b", "2"), schema);
        handler.saveMcpServerUserConfig("p@m", "s", Map.of("a", "one"), schema);
        assertThat(handler.loadMcpServerUserConfig("p@m", "s")).isEqualTo(Map.of("a", "one", "b", "2"));
    }

    @Test
    @DisplayName("loadPluginOptions：顶层选项（pluginConfigs[pluginId].options + secure bucket）")
    void pluginOptions_load() {
        settings.writeSettings(List.of("pluginConfigs", "plug@market", "options"), Map.of("opt1", "v1"));
        secureStore.buckets.put("plug@market", Map.of("secret", "s1"));
        assertThat(handler.loadPluginOptions("plug@market"))
            .isEqualTo(Map.of("opt1", "v1", "secret", "s1"));
        assertThat(handler.loadPluginOptions("other")).isEmpty();
    }

    // ============== loadMcpbFile / checkMcpbChanged（CC :622-968） ==============

    @Test
    @DisplayName("loadMcpbFile：本地首次加载 → 提取 + 元数据落盘 + 配置生成")
    void loadMcpbFile_freshLocal() throws Exception {
        Files.createDirectories(pluginPath);
        byte[] zip = buildZip(Map.of(
            "manifest.json", manifestJson("srvA", null, null).toString().getBytes(StandardCharsets.UTF_8),
            "bin/server", "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8)));
        Files.write(pluginPath.resolve("bundle.mcpb"), zip);

        List<String> progress = new ArrayList<>();
        McpbLoadOutcome outcome = handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market",
            progress::add, null, false);

        assertThat(outcome).isInstanceOf(McpbLoadResult.class);
        McpbLoadResult result = (McpbLoadResult) outcome;
        assertThat(result.manifest().path("name").asText()).isEqualTo("srvA");
        McpTypesRegistry.McpStdioServerConfig cfg = (McpTypesRegistry.McpStdioServerConfig) result.mcpConfig();
        assertThat(cfg.command()).isEqualTo("node");
        assertThat(cfg.args()).containsExactly("server.js");
        assertThat(cfg.env()).isEqualTo(Map.of("PORT", "8080"));
        // 提取目录存在（bin/server 已写出）
        assertThat(Files.exists(Path.of(result.extractedPath(), "bin/server"))).isTrue();
        // 内容哈希 = sha256 前 16 hex
        assertThat(result.contentHash()).hasSize(16);
        // 元数据落盘格式（.mcpb-cache/{md5(source)8}.metadata.json）
        Path metadataPath = pluginPath.resolve(".mcpb-cache")
            .resolve(md5Hex("bundle.mcpb") + ".metadata.json");
        assertThat(Files.exists(metadataPath)).isTrue();
        JsonNode meta = JSON.readTree(Files.readString(metadataPath));
        assertThat(meta.path("source").asText()).isEqualTo("bundle.mcpb");
        assertThat(meta.path("contentHash").asText()).isEqualTo(result.contentHash());
        assertThat(meta.path("extractedPath").asText()).isEqualTo(result.extractedPath());
        assertThat(meta.path("cachedAt").isTextual()).isTrue();
        assertThat(meta.path("lastChecked").isTextual()).isTrue();
        // 进度回调链
        assertThat(progress).contains("Loading bundle.mcpb...", "Extracting MCPB archive...",
            "Generating MCP server configuration...");
    }

    @Test
    @DisplayName("loadMcpbFile：缓存命中复用 + 本地 mtime 变更重载")
    void loadMcpbFile_cacheHitAndMtimeReload() throws Exception {
        Files.createDirectories(pluginPath);
        byte[] zip = buildZip(Map.of("manifest.json",
            manifestJson("srvA", null, null).toString().getBytes(StandardCharsets.UTF_8)));
        Path mcpb = pluginPath.resolve("bundle.mcpb");
        Files.write(mcpb, zip);

        McpbLoadResult first = (McpbLoadResult) handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market");
        Path metadataPath = pluginPath.resolve(".mcpb-cache").resolve(md5Hex("bundle.mcpb") + ".metadata.json");
        String cachedAtBefore = JSON.readTree(Files.readString(metadataPath)).path("cachedAt").asText();

        // 第二次：缓存命中 → 相同 extractedPath，元数据不重写（cachedAt 不变）
        McpbLoadResult second = (McpbLoadResult) handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market");
        assertThat(second.extractedPath()).isEqualTo(first.extractedPath());
        String cachedAtAfter = JSON.readTree(Files.readString(metadataPath)).path("cachedAt").asText();
        assertThat(cachedAtAfter).isEqualTo(cachedAtBefore);
        // checkMcpbChanged 直接断言：未变更 → false
        assertThat(handler.checkMcpbChanged("bundle.mcpb", pluginPath)).isFalse();

        // 本地 mtime 前移 → changed → 重载（元数据重写，cachedAt 更新）
        Files.setLastModifiedTime(mcpb, FileTime.from(Instant.now().plus(2, ChronoUnit.SECONDS)));
        assertThat(handler.checkMcpbChanged("bundle.mcpb", pluginPath)).isTrue();
        McpbLoadResult third = (McpbLoadResult) handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market");
        assertThat(third.extractedPath()).isEqualTo(first.extractedPath()); // 内容未变 → 同哈希
        String cachedAtThird = JSON.readTree(Files.readString(metadataPath)).path("cachedAt").asText();
        assertThat(cachedAtThird).isNotEqualTo(cachedAtBefore);
    }

    @Test
    @DisplayName("checkMcpbChanged：提取目录丢失 → true（需重载）")
    void checkMcpbChanged_missingExtractionDir() throws Exception {
        Files.createDirectories(pluginPath);
        byte[] zip = buildZip(Map.of("manifest.json",
            manifestJson("srvA", null, null).toString().getBytes(StandardCharsets.UTF_8)));
        Files.write(pluginPath.resolve("bundle.mcpb"), zip);
        McpbLoadResult first = (McpbLoadResult) handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market");
        // 删除提取目录（模拟缓存 GC）
        Files.walk(Path.of(first.extractedPath())).sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        assertThat(handler.checkMcpbChanged("bundle.mcpb", pluginPath)).isTrue();
    }

    @Test
    @DisplayName("parseZipModes：central-directory external attrs 解析 exec bit")
    void parseZipModes_execBit() throws Exception {
        byte[] zip = buildZipWithMode("bin/server", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), 493); // 0o755
        Map<String, Integer> modes = McpbHandler.parseZipModes(zip);
        assertThat(modes).containsEntry("bin/server", 493);
        // 普通 zip（无 mode 信息）→ 空
        byte[] plain = buildZip(Map.of("a.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertThat(McpbHandler.parseZipModes(plain)).isEmpty();
        // 解压可读
        assertThat(McpbHandler.unzipMcpb(zip)).containsKey("bin/server");
    }
    @Test
    @DisplayName("loadMcpbFile：needs-config 分支（校验失败 + validationErrors + 元数据仍落盘）")
    void loadMcpbFile_needsConfig() throws Exception {
        Files.createDirectories(pluginPath);
        ObjectNode manifest = manifestJson("srvA", Map.of(
            "apiKey", Map.of("type", "string", "required", true, "title", "API Key")), null);
        byte[] zip = buildZip(Map.of("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8)));
        Files.write(pluginPath.resolve("bundle.mcpb"), zip);

        McpbLoadOutcome outcome = handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market");
        assertThat(outcome).isInstanceOf(McpbNeedsConfigResult.class);
        McpbNeedsConfigResult needs = (McpbNeedsConfigResult) outcome;
        assertThat(needs.status()).isEqualTo("needs-config");
        assertThat(needs.configSchema()).containsKey("apiKey");
        assertThat(needs.existingConfig()).isEmpty();
        assertThat(needs.validationErrors()).contains("API Key is required but not provided");
        // 即使配置不完整也保存缓存元数据（CC :883-893）
        Path metadataPath = pluginPath.resolve(".mcpb-cache").resolve(md5Hex("bundle.mcpb") + ".metadata.json");
        assertThat(Files.exists(metadataPath)).isTrue();

        // 提供有效配置 → 成功 + 配置保存（CC :906-914）
        McpbLoadOutcome outcome2 = handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market",
            null, Map.of("apiKey", "k123"), false);
        assertThat(outcome2).isInstanceOf(McpbLoadResult.class);
        assertThat(handler.loadMcpServerUserConfig("plug@market", "srvA"))
            .isEqualTo(Map.of("apiKey", "k123"));

        // forceConfigDialog → 即使有效也 needs-config（重配场景，CC :749）
        McpbLoadOutcome outcome3 = handler.loadMcpbFile("bundle.mcpb", pluginPath, "plug@market",
            null, Map.of("apiKey", "k123"), true);
        assertThat(outcome3).isInstanceOf(McpbNeedsConfigResult.class);
    }

    @Test
    @DisplayName("loadMcpbFile：URL 下载（注入式下载器）+ 缓存落盘")
    void loadMcpbFile_urlDownload() throws Exception {
        Files.createDirectories(pluginPath);
        byte[] zip = buildZip(Map.of("manifest.json",
            manifestJson("urlSrv", null, null).toString().getBytes(StandardCharsets.UTF_8)));
        AtomicInteger downloads = new AtomicInteger();
        McpbHandler handlerWithDownloader = new McpbHandler((url, dest, progress) -> {
            downloads.incrementAndGet();
            Files.createDirectories(dest.getParent());
            Files.write(dest, zip);
            return zip;
        });
        McpbLoadResult result = (McpbLoadResult) handlerWithDownloader.loadMcpbFile(
            "https://example.com/bundle.mcpb", pluginPath, "plug@market");
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(result.manifest().path("name").asText()).isEqualTo("urlSrv");
        // 下载缓存文件落盘（{md5(source)8}.mcpb）
        assertThat(Files.exists(pluginPath.resolve(".mcpb-cache")
            .resolve(md5Hex("https://example.com/bundle.mcpb") + ".mcpb"))).isTrue();
        // 二次调用缓存命中 → 不重新下载
        handlerWithDownloader.loadMcpbFile("https://example.com/bundle.mcpb", pluginPath, "plug@market");
        assertThat(downloads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("loadMcpbFile：错误路径 throw（manifest 缺失 / server 缺失 / 本地缺失 / 无效 JSON）")
    void loadMcpbFile_errorThrows() throws Exception {
        Files.createDirectories(pluginPath);
        // manifest.json 缺失
        byte[] noManifest = buildZip(Map.of("foo.txt", "x".getBytes(StandardCharsets.UTF_8)));
        Files.write(pluginPath.resolve("a.mcpb"), noManifest);
        assertThatThrownBy(() -> handler.loadMcpbFile("a.mcpb", pluginPath, "plug@market"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("No manifest.json found in MCPB file");

        // manifest.server 缺失
        ObjectNode noServer = manifestJson("srvB", null, null);
        noServer.remove("server");
        Files.write(pluginPath.resolve("b.mcpb"),
            buildZip(Map.of("manifest.json", noServer.toString().getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> handler.loadMcpbFile("b.mcpb", pluginPath, "plug@market"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("does not define a server configuration");

        // 本地文件不存在
        assertThatThrownBy(() -> handler.loadMcpbFile("missing.mcpb", pluginPath, "plug@market"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("MCPB file not found");

        // 无效 JSON manifest
        Files.write(pluginPath.resolve("c.mcpb"),
            buildZip(Map.of("manifest.json", "{not json".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> handler.loadMcpbFile("c.mcpb", pluginPath, "plug@market"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Invalid manifest.json");
    }


    // ============== 测试辅助 ==============

    private static ObjectNode manifestJson(String name, Map<String, Object> userConfig, String entrypointCommand) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("name", name);
        manifest.put("version", "1.0.0");
        ObjectNode author = manifest.putObject("author");
        author.put("name", "tester");
        if (userConfig != null) {
            manifest.set("user_config", JSON.valueToTree(userConfig));
        }
        ObjectNode server = manifest.putObject("server");
        ObjectNode entrypoint = server.putObject("entrypoint");
        entrypoint.put("command", entrypointCommand != null ? entrypointCommand : "node");
        entrypoint.putArray("args").add("server.js");
        server.putObject("environment").put("PORT", "8080");
        return manifest;
    }

    /** 构建简单 zip（stored，无 mode）。 */
    static byte[] buildZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 手工构建带 unix mode external attrs 的最小 zip（单条目，stored）。 */
    static byte[] buildZipWithMode(String name, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        crc.update(content);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int dataOffset = 30 + nameBytes.length;
        // local file header
        putU32(bos, 0x04034b50);
        putU16(bos, 20);
        putU16(bos, 0);
        putU16(bos, 0); // stored
        putU16(bos, 0);
        putU16(bos, 0x21);
        putU32(bos, crc.getValue());
        putU32(bos, content.length);
        putU32(bos, content.length);
        putU16(bos, nameBytes.length);
        putU16(bos, 0);
        bos.writeBytes(nameBytes);
        bos.writeBytes(content);
        int cdOffset = dataOffset + content.length;
        // central directory header
        putU32(bos, 0x02014b50);
        putU16(bos, 0x031E); // version made by: unix (3 << 8) | 30
        putU16(bos, 20);
        putU16(bos, 0);
        putU16(bos, 0);
        putU16(bos, 0);
        putU16(bos, 0x21);
        putU32(bos, crc.getValue());
        putU32(bos, content.length);
        putU32(bos, content.length);
        putU16(bos, nameBytes.length);
        putU16(bos, 0);
        putU16(bos, 0);
        putU16(bos, 0);
        putU16(bos, 0);
        putU32(bos, mode << 16); // external attrs（unix mode 高 16 位）
        putU32(bos, dataOffset);
        bos.writeBytes(nameBytes);
        int cdSize = 46 + nameBytes.length;
        // EOCD
        putU32(bos, 0x06054b50);
        putU16(bos, 0);
        putU16(bos, 0);
        putU16(bos, 1);
        putU16(bos, 1);
        putU32(bos, cdSize);
        putU32(bos, cdOffset);
        putU16(bos, 0);
        return bos.toByteArray();
    }

    private static void putU16(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >>> 8) & 0xFF);
    }

    private static void putU32(ByteArrayOutputStream bos, long v) {
        bos.write((int) (v & 0xFF));
        bos.write((int) ((v >>> 8) & 0xFF));
        bos.write((int) ((v >>> 16) & 0xFF));
        bos.write((int) ((v >>> 24) & 0xFF));
    }

    static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 内存版 ConfigStorage（嵌套 Map；leaf 替换写）。 */
    static final class MapConfigStorage implements ConfigStorage {
        final Map<String, Object> root = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public Object readSettings(List<String> path) {
            Object cursor = root;
            for (String seg : path) {
                if (!(cursor instanceof Map<?, ?> m) || !m.containsKey(seg)) {
                    return null;
                }
                cursor = ((Map<String, Object>) m).get(seg);
            }
            return cursor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void writeSettings(List<String> path, Object value) {
            Map<String, Object> cursor = root;
            for (int i = 0; i < path.size() - 1; i++) {
                Object next = cursor.get(path.get(i));
                if (!(next instanceof Map)) {
                    Map<String, Object> created = new LinkedHashMap<>();
                    cursor.put(path.get(i), created);
                    next = created;
                }
                cursor = (Map<String, Object>) next;
            }
            cursor.put(path.get(path.size() - 1), value);
        }

        @Override
        public Object readGlobal(String key) { return null; }

        @Override
        public void writeGlobal(String key, Object value) { }

        @Override
        public void unsetGlobal(String key) { }

        @Override
        public void unsetSettings(List<String> path) { }

        @Override
        public void addChangeListener(ConfigChangeListener listener) { }

        @Override
        public void removeChangeListener(ConfigChangeListener listener) { }
    }

    /** 内存版 SecureValueStore。 */
    static final class MapSecureStore implements SecureValueStore {
        final Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();

        @Override
        public Map<String, Object> read(String bucketKey) {
            return buckets.get(bucketKey);
        }

        @Override
        public boolean write(String bucketKey, Map<String, Object> values) {
            if (values == null || values.isEmpty()) {
                buckets.remove(bucketKey);
            } else {
                buckets.put(bucketKey, new LinkedHashMap<>(values));
            }
            return true;
        }
    }
}
