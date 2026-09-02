package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpTypesRegistry;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MCPB Handler · 对齐 CC utils/plugins/mcpbHandler.ts (968 行，S08 全读实证)。
 *
 * <p>生产 MCPB bundle 加载器：URL/本地文件 → 下载/读取 → sha256 内容哈希 → zip 解压
 * （目录项过滤 + central-directory external attrs 解析 exec bit，对齐 CC parseZipModes）
 * → manifest.json 校验（缺失 throw / server 缺失 throw）→ user_config 分支
 * （needs-config / providedUserConfig 保存 / generateMcpConfig）→ 缓存元数据落盘
 * （.mcpb-cache/{md5(source)8}.metadata.json）。
 *
 * <p>缓存链（对齐 CC :622-686 checkMcpbChanged）：无 metadata → changed；提取目录丢失 →
 * changed；本地源 mtime 下取整 &gt; cachedAt → changed；URL 源 → 恒不自动重检（显式更新另行处理）。
 *
 * <p>用户配置存储（对齐 CC :141-341）：sensitive 值走注入式 {@link SecureValueStore}
 * （默认落 settings.json {@code pluginSecrets} 段；CC 走 keychain/.credentials.json 0600，
 * Java 无键链设施 → Q-09-R2-4 边界 B 登记）；非敏感值落 settings.json
 * {@code pluginConfigs[pluginId].mcpServers[serverName]}（与 UpdateConfigSkillRegistrar:278
 * schema 声明衔接）。合并语义：secure 胜出（:162）。
 *
 * <p>用户配置 schema 逐字段校验（:346-408）：required 空值 / string（含 multiple 数组）/
 * number / boolean / file / directory / number min-max 范围。
 *
 * <p>generateMcpConfig（:413-438）在 CC 委托外部 npm 包 @anthropic-ai/mcpb
 * getMcpConfigForManifest（包源码不在仓库无法对照）→ Java 自研等价转换
 * （manifest.server.entrypoint.command/args → command/args、server.environment → env，
 * 范围以 CC 消费字段为限，见 Q-09-R2-4 登记）；user_config 值不在此转换内替换
 * （下游 resolvePluginMcpEnvironment 的 ${user_config.X} 替换链覆盖，避免臆造 npm 包行为）。
 */
@Component
public class McpbHandler {

    private static final Logger log = LoggerFactory.getLogger(McpbHandler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** URL 下载总超时 · CC mcpbHandler.ts:496 axios timeout 120000（2 分钟）。 */
    static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

    /** URL 下载最大重定向次数 · CC :498 maxRedirects: 5（curl -L 语义）。 */
    static final int MAX_REDIRECTS = 5;

    /** sha256 内容哈希截断长度 · CC :93-95 digest 前 16 hex。 */
    static final int CONTENT_HASH_LEN = 16;

    /** metadata 文件名 = md5(source) 前 8 hex + '.metadata.json' · CC :107-113。 */
    static final int METADATA_SOURCE_HASH_LEN = 8;

    /** 进度回调 · CC :74 ProgressCallback = (status: string) => void。 */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String status);
    }

    /** 加载成功结果 · CC :40-45 McpbLoadResult。 */
    public record McpbLoadResult(JsonNode manifest,
                                 McpTypesRegistry.McpServerConfig mcpConfig,
                                 String extractedPath, String contentHash) implements McpbLoadOutcome {}

    /** 需要用户配置结果 · CC :50-58 McpbNeedsConfigResult（status 恒 'needs-config'）。 */
    public record McpbNeedsConfigResult(String status, JsonNode manifest,
                                        String extractedPath, String contentHash,
                                        Map<String, Object> configSchema,
                                        Map<String, Object> existingConfig,
                                        List<String> validationErrors) implements McpbLoadOutcome {
        public McpbNeedsConfigResult {
            status = "needs-config";
            configSchema = configSchema == null ? Map.of() : Map.copyOf(configSchema);
            existingConfig = existingConfig == null ? Map.of() : Map.copyOf(existingConfig);
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }
    }

    /** loadMcpbFile 判别联合 · CC McpbLoadResult | McpbNeedsConfigResult（'status' in result 判别）。 */
    public sealed interface McpbLoadOutcome permits McpbLoadResult, McpbNeedsConfigResult {}

    /** 缓存元数据 · CC :63-69 McpbCacheMetadata。 */
    public record McpbCacheMetadata(String source, String contentHash,
                                    String extractedPath, String cachedAt, String lastChecked) {}

    /** 校验结果 · CC validateUserConfig 返回 { valid, errors }。 */
    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    /**
     * 敏感值存储 · CC getSecureStorage()（keychain / .credentials.json 0600，:141-341）。
     * Java 无键链设施 → 注入式接口；默认实现落 settings.json {@code pluginSecrets} 段
     * （Q-09-R2-4 边界 B 登记，存储落点待 owner 认可）。
     */
    public interface SecureValueStore {
        /** 读 bucket（无 → null）。CC storage.read()?.pluginSecrets?.[key]。 */
        Map<String, Object> read(String bucketKey);

        /** 全量替换写 bucket；失败返回 false（CC storage.update 返回 success）。 */
        boolean write(String bucketKey, Map<String, Object> values);
    }

    /** MCPB 文件下载器 · CC downloadMcpb（:482-542，axios）。注入式便于测试。 */
    @FunctionalInterface
    public interface McpbDownloader {
        /** 下载 URL → 字节并落盘 destPath；失败抛 IOException（消息含 download 供错误分类）。 */
        byte[] download(String url, Path destPath, ProgressCallback onProgress) throws IOException;
    }

    /** 默认下载器 · java.net.http.HttpClient，120s 总超时 + 手动重定向 ≤5（对齐 axios 配置）。 */
    private final McpbDownloader defaultDownloader = new DefaultMcpbDownloader();

    private final McpbDownloader downloader;

    private ConfigStorage configStorage;

    /** 显式注入的敏感值存储（测试 stub 优先）；null → 默认 ConfigStorage 落点。 */
    private volatile SecureValueStore secureStore;

    private volatile SecureValueStore resolvedSecureStore;

    public McpbHandler() {
        this.downloader = null; // 未注入 → 用默认下载器
    }

    /** 注入式构造（测试）· downloader null → 默认 HttpClient 下载器。 */
    public McpbHandler(McpbDownloader downloader) {
        this.downloader = downloader;
    }

    /** 注入 settings 存储（测试 stub / 生产 FileConfigStorage）。 */
    @Autowired(required = false)
    public void setConfigStorage(ConfigStorage configStorage) {
        if (configStorage != null) {
            this.configStorage = configStorage;
        }
    }

    /** 注入敏感值存储（测试 stub / 未来 keychain 实现）。 */
    @Autowired(required = false)
    public void setSecureValueStore(SecureValueStore store) {
        if (store != null) {
            this.secureStore = store;
        }
    }

    /**
     * 判定 source 是否为 MCPB 文件引用 · CC mcpbHandler.ts:79-81 isMcpbSource。
     */
    public static boolean isMcpbSource(String source) {
        return source != null && (source.endsWith(".mcpb") || source.endsWith(".dxt"));
    }

    /** source 是否为 URL · CC :86-88 isUrl。 */
    private static boolean isUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    /**
     * 校验用户配置值 · CC mcpbHandler.ts:346-408 validateUserConfig。
     *
     * <p>required 且 undefined/'' → error；string 类型含 multiple 数组校验；number/boolean/
     * file/directory 类型校验；number min/max 范围校验（:353-405）。
     *
     * @param values 用户配置值（CC UserConfigValues，Map 表达）
     * @param schema 用户配置 schema（CC UserConfigSchema = Record&lt;String, McpbUserConfigurationOption&gt;）
     */
    public ValidationResult validateUserConfig(Map<String, Object> values, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> safeValues = values == null ? Map.of() : values;
        for (Map.Entry<String, Object> fieldEntry : schema.entrySet()) {
            String key = fieldEntry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> fieldSchema = fieldEntry.getValue() instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            Object value = safeValues.get(key);
            String title = fieldSchema.get("title") instanceof String t ? t : key;
            boolean required = Boolean.TRUE.equals(fieldSchema.get("required"));
            String type = fieldSchema.get("type") instanceof String t ? t : null;

            // required 校验（:357-360）
            if (required && (value == null || "".equals(value))) {
                errors.add(title + " is required but not provided");
                continue;
            }
            // 可选字段未提供 → 跳过（:363-365）
            if (value == null || "".equals(value)) {
                continue;
            }

            // 类型校验（:368-390）
            if ("string".equals(type)) {
                if (value instanceof List<?> list) {
                    // multiple: true 时允许字符串数组（:369-377）
                    if (!Boolean.TRUE.equals(fieldSchema.get("multiple"))) {
                        errors.add(title + " must be a string, not an array");
                    } else if (!list.stream().allMatch(v -> v instanceof String)) {
                        errors.add(title + " must be an array of strings");
                    }
                } else if (!(value instanceof String)) {
                    errors.add(title + " must be a string");
                }
            } else if ("number".equals(type) && !(value instanceof Number)) {
                errors.add(title + " must be a number");
            } else if ("boolean".equals(type) && !(value instanceof Boolean)) {
                errors.add(title + " must be a boolean");
            } else if (("file".equals(type) || "directory".equals(type)) && !(value instanceof String)) {
                errors.add(title + " must be a path string");
            }

            // number 范围校验（:393-404）
            if ("number".equals(type) && value instanceof Number n) {
                if (fieldSchema.get("min") instanceof Number min && n.doubleValue() < min.doubleValue()) {
                    errors.add(title + " must be at least " + min);
                }
                if (fieldSchema.get("max") instanceof Number max && n.doubleValue() > max.doubleValue()) {
                    errors.add(title + " must be at most " + max);
                }
            }
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 读取 MCP server 用户配置 · CC mcpbHandler.ts:141-172 loadMcpServerUserConfig。
     *
     * <p>非敏感值（settings.json {@code pluginConfigs[pluginId].mcpServers[serverName]}）与
     * 敏感值（secureStorage {@code pluginId/serverName} bucket）合并；secure 胜出（:162）。
     * 两源皆空 → null（调用方跳过 ${user_config.X} 替换，:135-136）。
     *
     * @param pluginId   插件标识（CC "plugin@marketplace" 格式，plugin.repository）
     * @param serverName DXT manifest 的 server 名
     */
    public Map<String, Object> loadMcpServerUserConfig(String pluginId, String serverName) {
        try {
            Map<String, Object> nonSensitive = readSettingsMap(
                List.of("pluginConfigs", pluginId, "mcpServers", serverName));
            Map<String, Object> sensitive = readSecureBucket(serverSecretsKey(pluginId, serverName));
            if (isEmptyOrNull(nonSensitive) && isEmptyOrNull(sensitive)) {
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] 加载用户配置 plugin={} server={}（settings + secureStorage）", pluginId, serverName);
            }
            Map<String, Object> merged = new LinkedHashMap<>();
            if (nonSensitive != null) merged.putAll(nonSensitive);
            if (sensitive != null) merged.putAll(sensitive);
            return merged;
        } catch (Exception e) {
            log.error("[McpbHandler] 加载用户配置失败 plugin={} server={}: {}", pluginId, serverName, e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] 加载用户配置失败堆栈", e);
            }
            return null;
        }
    }

    /**
     * 保存 MCP server 用户配置 · CC mcpbHandler.ts:193-341 saveMcpServerUserConfig。
     *
     * <p>按 {@code schema[key].sensitive} 拆分：sensitive → secureStorage（先写，失败 throw 不碰
     * settings —— 旧明文保留兜底，:222-229）；其余 → settings.json。双向 scrub 语义（:211-218）：
     * 仅清理本次保存涉及的键（sensitive→secure 时 settings 旧明文清除；nonSensitive→settings 时
     * secure 陈旧值清除），部分重配不丢其它字段。
     *
     * @param pluginId   插件标识（"plugin@marketplace" 格式）
     * @param serverName MCP server 名
     * @param config     用户配置值
     * @param schema     该 server 的 userConfig schema（驱动 sensitive 拆分）
     */
    public void saveMcpServerUserConfig(String pluginId, String serverName,
                                        Map<String, Object> config, Map<String, Object> schema) {
        try {
            Map<String, Object> nonSensitive = new LinkedHashMap<>();
            Map<String, Object> sensitive = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : config.entrySet()) {
                Object fieldSchema = schema.get(e.getKey());
                boolean isSensitive = fieldSchema instanceof Map<?, ?> m
                    && Boolean.TRUE.equals(m.get("sensitive"));
                if (isSensitive) {
                    sensitive.put(e.getKey(), String.valueOf(e.getValue()));
                } else {
                    nonSensitive.put(e.getKey(), e.getValue());
                }
            }

            Set<String> sensitiveKeysInThisSave = sensitive.keySet();
            Set<String> nonSensitiveKeysInThisSave = nonSensitive.keySet();
            String k = serverSecretsKey(pluginId, serverName);

            // 1. sensitive → secureStorage FIRST（:222-276）· 失败 throw，settings 保持旧明文兜底
            SecureValueStore store = effectiveSecureStore();
            Map<String, Object> existingInSecure = store == null ? null : store.read(k);
            Map<String, Object> secureScrubbed = null;
            if (existingInSecure != null) {
                secureScrubbed = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : existingInSecure.entrySet()) {
                    if (!nonSensitiveKeysInThisSave.contains(e.getKey())) {
                        secureScrubbed.put(e.getKey(), e.getValue());
                    }
                }
            }
            boolean needSecureScrub = secureScrubbed != null && existingInSecure != null
                && secureScrubbed.size() != existingInSecure.size();
            if (!sensitive.isEmpty() || needSecureScrub) {
                if (store == null) {
                    throw new IllegalStateException(
                        "Failed to save sensitive config to secure storage for " + k
                            + "（未注入 SecureValueStore）");
                }
                Map<String, Object> combined = new LinkedHashMap<>(
                    secureScrubbed == null ? Map.of() : secureScrubbed);
                combined.putAll(sensitive);
                if (!store.write(k, combined)) {
                    throw new IllegalStateException(
                        "Failed to save sensitive config to secure storage for " + k);
                }
                if (needSecureScrub && log.isDebugEnabled()) {
                    log.debug("[McpbHandler] saveMcpServerUserConfig: 从 secureStorage 清除 {} 个陈旧非敏感键（{}）",
                        existingInSecure.size() - secureScrubbed.size(), k);
                }
            }

            // 2. nonSensitive → settings.json AFTER（:278-329）· leaf 整体替换天然 scrub 敏感键
            //    （FileConfigStorage 写 leaf 为全替换；先合并现有键保留部分重配的其它字段）
            Map<String, Object> existingLeaf = readSettingsMap(
                List.of("pluginConfigs", pluginId, "mcpServers", serverName));
            boolean keysToScrubFromSettings = existingLeaf != null
                && existingLeaf.keySet().stream().anyMatch(sensitiveKeysInThisSave::contains);
            if (!nonSensitive.isEmpty() || keysToScrubFromSettings) {
                Map<String, Object> merged = new LinkedHashMap<>(
                    existingLeaf == null ? Map.of() : existingLeaf);
                merged.putAll(nonSensitive);
                merged.keySet().removeAll(sensitiveKeysInThisSave);
                writeSettingsMap(List.of("pluginConfigs", pluginId, "mcpServers", serverName), merged);
                if (keysToScrubFromSettings && log.isDebugEnabled()) {
                    log.debug("[McpbHandler] saveMcpServerUserConfig: 从 settings.json 清除明文敏感键（{}/{}）",
                        pluginId, serverName);
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] 已保存用户配置 {}/{}（{} 非敏感, {} 敏感）",
                    pluginId, serverName, nonSensitive.size(), sensitive.size());
            }
        } catch (Exception e) {
            log.error("[McpbHandler] 保存用户配置失败 {}/{}: {}", pluginId, serverName, e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] 保存用户配置失败堆栈", e);
            }
            throw new IllegalStateException(
                "Failed to save user configuration for " + pluginId + "/" + serverName + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * 读取插件顶层选项 · CC pluginOptionsStorage.ts:56-77 loadPluginOptions
     * （settings.pluginConfigs[pluginId].options + secureStorage.pluginSecrets[pluginId] 合并，
     * secure 胜出）。buildMcpUserConfig 消费（mcpPluginIntegration.ts:452）。
     *
     * @param pluginId 插件存储键（CC plugin.source = "name@marketplace"）
     */
    public Map<String, Object> loadPluginOptions(String pluginId) {
        try {
            Map<String, Object> nonSensitive = readSettingsMap(List.of("pluginConfigs", pluginId, "options"));
            Map<String, Object> sensitive = readSecureBucket(pluginId);
            if (isEmptyOrNull(nonSensitive) && isEmptyOrNull(sensitive)) {
                return Map.of();
            }
            Map<String, Object> merged = new LinkedHashMap<>();
            if (nonSensitive != null) merged.putAll(nonSensitive);
            if (sensitive != null) merged.putAll(sensitive);
            return merged;
        } catch (Exception e) {
            log.warn("[McpbHandler] 读取插件顶层选项失败 plugin={}: {}", pluginId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 判定 MCPB source 是否已变更需重载 · CC mcpbHandler.ts:622-686 checkMcpbChanged。
     *
     * <p>无 metadata → true；提取目录丢失 → true；本地文件 mtime 下取整 &gt; cachedAt → true
     * （sub-ms 精度与 ISO cachedAt 的 ms 精度对齐，:671-674）；URL → 恒 false（显式更新另行处理）。
     */
    public boolean checkMcpbChanged(String source, Path pluginPath) {
        Path cacheDir = getMcpbCacheDir(pluginPath);
        McpbCacheMetadata metadata = loadCacheMetadata(cacheDir, source);
        if (metadata == null) {
            return true;
        }
        // 提取目录是否存在（:636-649）
        if (metadata.extractedPath() == null || !Files.exists(Paths.get(metadata.extractedPath()))) {
            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] MCPB 提取目录丢失: {}", metadata.extractedPath());
            }
            return true;
        }
        // 本地文件 mtime 检查（:651-682）
        if (!isUrl(source)) {
            Path localPath = pluginPath.resolve(source);
            try {
                long fileTime = Files.getLastModifiedTime(localPath).toMillis();
                long cachedTime;
                try {
                    cachedTime = Instant.parse(metadata.cachedAt()).toEpochMilli();
                } catch (Exception e) {
                    cachedTime = 0;
                }
                if (fileTime > cachedTime) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpbHandler] MCPB 文件已修改: {} > {}", fileTime, cachedTime);
                    }
                    return true;
                }
            } catch (NoSuchFileException e) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpbHandler] MCPB 源文件缺失: {}", localPath);
                }
                return true;
            } catch (IOException e) {
                log.warn("[McpbHandler] MCPB 源文件不可访问 {}: {}", localPath, e.getMessage());
                return true;
            }
        }
        return false;
    }

    /**
     * 加载并解压 MCPB 文件（带缓存 + 用户配置支持）· CC mcpbHandler.ts:698-968 loadMcpbFile。
     *
     * <p>返回 {@link McpbLoadResult}（成功）或 {@link McpbNeedsConfigResult}（status='needs-config'）。
     * 缓存命中且未变更 → 直接读缓存 manifest；否则 URL 下载（120s/5 重定向）或本地读字节；
     * sha256 前 16 hex 作内容哈希；java.util.zip 解压（目录项过滤 + central-directory
     * external attrs 解析 exec bit，对齐 parseZipModes :550-617）；manifest.json 缺失 throw、
     * manifest.server 缺失 throw；user_config 分支校验 + needs-config 或 providedUserConfig
     * 保存后生成配置；缓存元数据写回。
     *
     * @param source             MCPB 文件路径或 URL
     * @param pluginPath         插件目录
     * @param pluginId           插件标识（"plugin@marketplace" 格式，用于配置存储）
     * @param onProgress         进度回调（可 null）
     * @param providedUserConfig 用户配置值（初次配置/重配，可 null）
     * @param forceConfigDialog  强制返回 needs-config（重配场景）
     */
    public McpbLoadOutcome loadMcpbFile(String source, Path pluginPath, String pluginId,
                                        ProgressCallback onProgress,
                                        Map<String, Object> providedUserConfig,
                                        boolean forceConfigDialog) throws IOException {
        Path cacheDir = getMcpbCacheDir(pluginPath);
        Files.createDirectories(cacheDir);
        if (log.isDebugEnabled()) {
            log.debug("[McpbHandler] 加载 MCPB source: {}", source);
        }

        // 缓存命中检查（:712-795）
        McpbCacheMetadata metadata = loadCacheMetadata(cacheDir, source);
        if (metadata != null && !checkMcpbChanged(source, pluginPath)) {
            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] 使用缓存 MCPB {}（hash: {}）", metadata.extractedPath(), metadata.contentHash());
            }
            Path manifestPath = Paths.get(metadata.extractedPath(), "manifest.json");
            String manifestContent;
            try {
                manifestContent = Files.readString(manifestPath, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                throw new IOException("Cached manifest not found: " + manifestPath, e);
            }
            JsonNode manifest = parseManifest(manifestContent);
            return handleUserConfigBranch(source, manifest, metadata.extractedPath(), metadata.contentHash(),
                pluginId, providedUserConfig, forceConfigDialog, null, onProgress);
        }

        // 未缓存/已变更 → 下载或本地读取（:797-828）
        byte[] mcpbData;
        if (isUrl(source)) {
            String sourceHash = md5Hex(source);
            Path mcpbFilePath = cacheDir.resolve(sourceHash + ".mcpb");
            mcpbData = effectiveDownloader().download(source, mcpbFilePath, onProgress);
        } else {
            Path localPath = pluginPath.resolve(source);
            if (onProgress != null) {
                onProgress.onProgress("Loading " + source + "...");
            }
            try {
                mcpbData = Files.readAllBytes(localPath);
            } catch (NoSuchFileException e) {
                throw new IOException("MCPB file not found: " + localPath, e);
            }
        }

        String contentHash = generateContentHash(mcpbData);
        if (log.isDebugEnabled()) {
            log.debug("[McpbHandler] MCPB content hash: {}", contentHash);
        }

        if (onProgress != null) {
            onProgress.onProgress("Extracting MCPB archive...");
        }
        Map<String, byte[]> unzipped = unzipMcpb(mcpbData);
        Map<String, Integer> modes = parseZipModes(mcpbData);

        // manifest.json 存在性（:844-850）
        byte[] manifestData = unzipped.get("manifest.json");
        if (manifestData == null) {
            throw new IOException("No manifest.json found in MCPB file");
        }
        JsonNode manifest = parseManifest(new String(manifestData, StandardCharsets.UTF_8));
        String manifestName = manifest.path("name").asText("(unnamed)");
        if (log.isDebugEnabled()) {
            log.debug("[McpbHandler] MCPB manifest: {} v{} by {}",
                manifestName, manifest.path("version").asText("?"),
                manifest.path("author").path("name").asText("?"));
        }

        // manifest.server 存在性（:858-865）
        if (!manifest.hasNonNull("server")) {
            throw new IOException(
                "MCPB manifest for \"" + manifestName + "\" does not define a server configuration");
        }

        // 提取到缓存目录（:867-869）
        Path extractPath = cacheDir.resolve(contentHash);
        extractMcpbContents(unzipped, extractPath, modes, onProgress);

        return handleUserConfigBranch(source, manifest, extractPath.toString(), contentHash,
            pluginId, providedUserConfig, forceConfigDialog, cacheDir, onProgress);
    }

    /** 便捷重载：无进度/无提供配置/非强制（CC 调用点传 status 回调，Java 端日志回调在 F1 提供）。 */
    public McpbLoadOutcome loadMcpbFile(String source, Path pluginPath, String pluginId) throws IOException {
        return loadMcpbFile(source, pluginPath, pluginId, null, null, false);
    }

    /**
     * user_config 分支（缓存命中与全新加载共用）· CC :737-784 / :871-939。
     *
     * @param cacheDir 全新加载路径传 cacheDir（needs-config 时先落缓存元数据 :883-892）；
     *                 缓存命中传 null（元数据已存在，不重复写）
     */
    private McpbLoadOutcome handleUserConfigBranch(String source, JsonNode manifest, String extractedPath,
                                                   String contentHash, String pluginId,
                                                   Map<String, Object> providedUserConfig,
                                                   boolean forceConfigDialog, Path cacheDir,
                                                   ProgressCallback onProgress)
            throws IOException {
        Map<String, Object> userConfigSchema = jsonNodeToObjectMap(manifest.path("user_config"));
        if (userConfigSchema != null && !userConfigSchema.isEmpty()) {
            String serverName = manifest.path("name").asText(null);
            if (serverName == null || serverName.isBlank()) {
                throw new IOException("MCPB manifest user_config 分支缺 name（server 名）");
            }
            Map<String, Object> savedConfig = loadMcpServerUserConfig(pluginId, serverName);
            Map<String, Object> userConfig = providedUserConfig != null
                ? providedUserConfig : (savedConfig != null ? savedConfig : Map.of());

            ValidationResult validation = validateUserConfig(userConfig, userConfigSchema);

            if (forceConfigDialog || !validation.valid()) {
                // 全新加载路径：即使配置不完整也保存缓存元数据（:883-893）
                if (cacheDir != null) {
                    saveCacheMetadata(cacheDir, source, extractedPath, contentHash);
                }
                return new McpbNeedsConfigResult("needs-config", manifest, extractedPath, contentHash,
                    userConfigSchema, savedConfig != null ? savedConfig : Map.of(),
                    validation.valid() ? List.of() : validation.errors());
            }

            // 提供配置 → 保存（:906-914）
            if (providedUserConfig != null) {
                saveMcpServerUserConfig(pluginId, serverName, providedUserConfig, userConfigSchema);
            }

            // 生成配置（:917-919）
            if (onProgress != null) {
                onProgress.onProgress("Generating MCP server configuration...");
            }
            McpTypesRegistry.McpServerConfig mcpConfig = generateMcpConfig(manifest, extractedPath);
            if (cacheDir != null) {
                saveCacheMetadata(cacheDir, source, extractedPath, contentHash);
            }
            return new McpbLoadResult(manifest, mcpConfig, extractedPath, contentHash);
        }

        // 无 user_config 要求 → 直接生成（:941-956）
        if (onProgress != null) {
            onProgress.onProgress("Generating MCP server configuration...");
        }
        McpTypesRegistry.McpServerConfig mcpConfig = generateMcpConfig(manifest, extractedPath);
        if (cacheDir != null) {
            saveCacheMetadata(cacheDir, source, extractedPath, contentHash);
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpbHandler] 成功加载 MCPB: {}（提取到 {}）", manifest.path("name").asText("?"), extractedPath);
        }
        return new McpbLoadResult(manifest, mcpConfig, extractedPath, contentHash);
    }

    /**
     * 生成 MCP server 配置 · CC mcpbHandler.ts:413-438 generateMcpConfig。
     *
     * <p>CC 委托外部 npm @anthropic-ai/mcpb getMcpConfigForManifest（:420-427）；包源码不在仓库
     * → Java 自研等价：manifest.server.entrypoint.command → command、entrypoint.args → args、
     * server.environment → env（Q-09-R2-4 边界 A 登记，范围以 CC 消费字段为限）。
     * server 缺失 → throw（对齐 CC getMcpConfigForManifest 返 null → "Failed to generate..."）。
     */
    private McpTypesRegistry.McpServerConfig generateMcpConfig(JsonNode manifest, String extractedPath)
            throws IOException {
        JsonNode server = manifest.path("server");
        if (!server.isObject()) {
            throw new IOException(
                "Failed to generate MCP server configuration from manifest \""
                    + manifest.path("name").asText("?") + "\"");
        }
        JsonNode entrypoint = server.path("entrypoint");
        String command = null;
        List<String> args = new ArrayList<>();
        if (entrypoint.isTextual()) {
            command = entrypoint.asText();
        } else if (entrypoint.isObject()) {
            if (entrypoint.path("command").isTextual()) {
                command = entrypoint.path("command").asText();
            }
            JsonNode argsNode = entrypoint.path("args");
            if (argsNode.isArray()) {
                for (JsonNode a : argsNode) {
                    if (a.isTextual()) {
                        args.add(a.asText());
                    }
                }
            }
        }
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = server.path("environment");
        if (envNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = envNode.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                env.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
            }
        }
        return new McpTypesRegistry.McpStdioServerConfig(command, List.copyOf(args), env);
    }

    // ============== 缓存 / 哈希 / 下载 / 解压 内部实现 ==============

    /** 缓存目录 · CC :100-102 getMcpbCacheDir = join(pluginPath, '.mcpb-cache')。 */
    private static Path getMcpbCacheDir(Path pluginPath) {
        return pluginPath.resolve(".mcpb-cache");
    }

    /** metadata 文件路径 · CC :107-113 = md5(source) 前 8 hex + '.metadata.json'。 */
    private static Path getMetadataPath(Path cacheDir, String source) {
        return cacheDir.resolve(md5Hex(source) + ".metadata.json");
    }

    /** 内容哈希 · CC :93-95 sha256 前 16 hex。 */
    static String generateContentHash(byte[] data) {
        return sha256Hex(data).substring(0, CONTENT_HASH_LEN);
    }

    /** secureStorage bucket 复合键 · CC :124-126 serverSecretsKey = `${pluginId}/${serverName}`。 */
    private static String serverSecretsKey(String pluginId, String serverName) {
        return pluginId + "/" + serverName;
    }

    private static String md5Hex(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(source.getBytes(StandardCharsets.UTF_8)))
                .substring(0, METADATA_SOURCE_HASH_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 读缓存元数据 · CC :443-463 loadCacheMetadata（ENOENT → null；其它错误 log + null）。 */
    private McpbCacheMetadata loadCacheMetadata(Path cacheDir, String source) {
        Path metadataPath = getMetadataPath(cacheDir, source);
        try {
            String content = Files.readString(metadataPath, StandardCharsets.UTF_8);
            JsonNode node = JSON.readTree(content);
            return new McpbCacheMetadata(
                node.path("source").asText(null),
                node.path("contentHash").asText(null),
                node.path("extractedPath").asText(null),
                node.path("cachedAt").asText(null),
                node.path("lastChecked").asText(null));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.warn("[McpbHandler] 读取 MCPB 缓存元数据失败: {}", e.getMessage());
            return null;
        }
    }

    /** 写缓存元数据 · CC :468-477 saveCacheMetadata（mkdir + 2 空格缩进 JSON）。 */
    private void saveCacheMetadata(Path cacheDir, String source, String extractedPath, String contentHash)
            throws IOException {
        Path metadataPath = getMetadataPath(cacheDir, source);
        String now = Instant.now().toString();
        McpbCacheMetadata metadata = new McpbCacheMetadata(source, contentHash, extractedPath, now, now);
        Files.writeString(metadataPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
            StandardCharsets.UTF_8);
    }

    /** 解析 manifest JSON（无效 JSON → IOException 消息含 manifest 供错误分类）。 */
    private static JsonNode parseManifest(String content) throws IOException {
        try {
            return JSON.readTree(content);
        } catch (IOException e) {
            throw new IOException("Invalid manifest.json: " + e.getMessage(), e);
        }
    }

    /** 解压 MCPB zip · CC unzipFile（dxt/zip.js）+ 目录项过滤（:550-617）。 */
    static Map<String, byte[]> unzipMcpb(byte[] data) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 目录项过滤（:566）：zip -r / Python zipfile / Java ZipOutputStream 常见；
                // 写文件路径前 mkdir(dirname) 隐式建父目录
                if (entry.isDirectory() || entry.getName().endsWith("/")) {
                    continue;
                }
                byte[] content = readAll(zis);
                out.put(entry.getName(), content);
                zis.closeEntry();
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 解析 central directory external attrs（exec bit）· 对齐 CC parseZipModes（dxt/zip.js）。
     *
     * <p>fflate 不透出 external_attr → 解析 central directory；unix mode = external attr 高 16 位，
     * 与 0o111 与运算判定 exec bit（MCPB 可携带原生 MCP server 二进制，:548-549）。
     *
     * @return name → unix mode（无 mode 信息 → 不含该条目）
     */
    static Map<String, Integer> parseZipModes(byte[] data) {
        Map<String, Integer> modes = new LinkedHashMap<>();
        // 从尾部找 EOCD（0x06054b50）
        int eocd = -1;
        for (int i = data.length - 22; i >= 0; i--) {
            if (data[i] == 0x50 && i + 3 < data.length
                && (data[i + 1] & 0xFF) == 0x4b && (data[i + 2] & 0xFF) == 0x05
                && (data[i + 3] & 0xFF) == 0x06) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return modes;
        }
        int count = u16(data, eocd + 10);
        int cdOffset = u32(data, eocd + 16);
        int pos = cdOffset;
        for (int n = 0; n < count && pos + 46 <= data.length; n++) {
            if (u32(data, pos) != 0x02014b50) {
                break;
            }
            int nameLen = u16(data, pos + 28);
            int extraLen = u16(data, pos + 30);
            int commentLen = u16(data, pos + 32);
            long externalAttrs = u32(data, pos + 38);
            if (pos + 46 + nameLen > data.length) {
                break;
            }
            String name = new String(data, pos + 46, nameLen, StandardCharsets.UTF_8);
            int mode = (int) (externalAttrs >> 16);
            if (mode != 0) {
                modes.put(name, mode);
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return modes;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * 提取 MCPB 内容到目录 · CC :550-617 extractMcpbContents。
     *
     * <p>目录项已在 unzipMcpb 过滤；写文件前 mkdir 父目录；exec bit 经 chmod 保留
     * （EPERM/ENOTSUP 吞掉，对齐 :601-604 注释）；进度每 10 文件回报一次。
     */
    private void extractMcpbContents(Map<String, byte[]> unzipped, Path extractPath,
                                     Map<String, Integer> modes, ProgressCallback onProgress)
            throws IOException {
        if (onProgress != null) {
            onProgress.onProgress("Extracting files...");
        }
        Files.createDirectories(extractPath);
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>(unzipped.entrySet());
        int totalFiles = entries.size();
        int filesWritten = 0;
        for (Map.Entry<String, byte[]> e : entries) {
            Path fullPath = extractPath.resolve(e.getKey());
            Path dir = fullPath.getParent();
            if (dir != null && !dir.equals(extractPath)) {
                Files.createDirectories(dir);
            }
            Files.write(fullPath, e.getValue());
            Integer mode = modes.get(e.getKey());
            if (mode != null && (mode & 73) != 0) { // 73 = 0o111（exec 任一 bit）
                try {
                    Files.setPosixFilePermissions(fullPath, parsePosixMode(mode));
                } catch (UnsupportedOperationException | IOException ex) {
                    // 非 POSIX 文件系统 / 权限不足 → 吞掉（对齐 CC :601-604 注释）
                    if (log.isDebugEnabled()) {
                        log.debug("[McpbHandler] 设置 exec bit 失败 {}: {}", fullPath, ex.getMessage());
                    }
                }
            }
            filesWritten++;
            if (onProgress != null && filesWritten % 10 == 0) {
                onProgress.onProgress("Extracted " + filesWritten + "/" + totalFiles + " files");
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpbHandler] 已提取 {} 个文件到 {}", filesWritten, extractPath);
        }
        if (onProgress != null) {
            onProgress.onProgress("Extraction complete (" + filesWritten + " files)");
        }
    }

    /** unix mode → POSIX 权限集（owner/group/other 的 rwx）。 */
    private static Set<PosixFilePermission> parsePosixMode(int mode) {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        // 0o400/0o200/0o100/0o040/0o020/0o010/0o004/0o002/0o001 的十进制等价（本仓 javac 不支持 0o 字面量）
        int[] bits = {256, 128, 64, 32, 16, 8, 4, 2, 1};
        PosixFilePermission[] names = {
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        };
        for (int i = 0; i < bits.length; i++) {
            if ((mode & bits[i]) != 0) {
                perms.add(names[i]);
            }
        }
        return perms;
    }

    // ============== 存储辅助 ==============

    /** 默认下载器（HttpClient + 手动重定向 ≤5）。 */
    static final class DefaultMcpbDownloader implements McpbDownloader {
        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        @Override
        public byte[] download(String url, Path destPath, ProgressCallback onProgress) throws IOException {
            if (log.isDebugEnabled()) {
                log.debug("[McpbHandler] 从 {} 下载 MCPB", url);
            }
            if (onProgress != null) {
                onProgress.onProgress("Downloading " + url + "...");
            }
            String current = url;
            try {
                for (int redirects = 0; ; redirects++) {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(current))
                        .timeout(DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();
                    HttpResponse<byte[]> resp;
                    try {
                        resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Failed to download MCPB file from " + url + ": interrupted", e);
                    }
                    int status = resp.statusCode();
                    if (status >= 300 && status < 400) {
                        String location = resp.headers().firstValue("Location").orElse(null);
                        if (location == null || redirects >= MAX_REDIRECTS) {
                            throw new IOException(
                                "Failed to download MCPB file from " + url + ": too many redirects (max "
                                    + MAX_REDIRECTS + ")");
                        }
                        current = location.startsWith("http") ? location : resolveUrl(current, location);
                        continue;
                    }
                    if (status != 200) {
                        throw new IOException(
                            "Failed to download MCPB file from " + url + ": HTTP " + status);
                    }
                    byte[] data = resp.body();
                    Files.write(destPath, data);
                    if (log.isDebugEnabled()) {
                        log.debug("[McpbHandler] 已下载 {} 字节到 {}", data.length, destPath);
                    }
                    if (onProgress != null) {
                        onProgress.onProgress("Download complete");
                    }
                    return data;
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to download MCPB file from " + url + ": " + e.getMessage(), e);
            }
        }

        private static String resolveUrl(String base, String location) {
            try {
                return URI.create(base).resolve(location).toString();
            } catch (Exception e) {
                return location;
            }
        }
    }

    private McpbDownloader effectiveDownloader() {
        return downloader != null ? downloader : defaultDownloader;
    }

    private SecureValueStore effectiveSecureStore() {
        SecureValueStore s = resolvedSecureStore;
        if (s == null) {
            if (secureStore != null) {
                s = secureStore;
            } else if (configStorage != null) {
                s = new ConfigStorageSecureValueStore(configStorage);
            } else {
                return null;
            }
            resolvedSecureStore = s;
        }
        return s;
    }

    private Map<String, Object> readSecureBucket(String bucketKey) {
        SecureValueStore store = effectiveSecureStore();
        if (store == null) {
            return null;
        }
        try {
            return store.read(bucketKey);
        } catch (Exception e) {
            log.warn("[McpbHandler] 读取敏感存储失败 {}: {}", bucketKey, e.getMessage());
            return null;
        }
    }

    private static boolean isEmptyOrNull(Map<String, Object> m) {
        return m == null || m.isEmpty();
    }

    /** settings.json 嵌套读（absent/JSON null/异常 → null）。 */
    private Map<String, Object> readSettingsMap(List<String> path) {
        ConfigStorage cs = configStorage;
        if (cs == null) {
            return null;
        }
        try {
            Object v = cs.readSettings(path);
            if (v == null || v == ConfigStorage.NullMarker) {
                return null;
            }
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
            return null;
        } catch (Exception e) {
            log.warn("[McpbHandler] 读取 settings {} 失败: {}", path, e.getMessage());
            return null;
        }
    }

    /** settings.json 嵌套写（merge 语义由 FileConfigStorage 保证）。 */
    private void writeSettingsMap(List<String> path, Map<String, Object> value) {
        ConfigStorage cs = configStorage;
        if (cs == null) {
            throw new IllegalStateException("未注入 ConfigStorage，无法保存用户配置 " + path);
        }
        cs.writeSettings(path, value);
    }

    /** JsonNode 对象 → Map（值转 Java 原生类型）；非对象/缺失 → null。 */
    static Map<String, Object> jsonNodeToObjectMap(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), jsonNodeToJavaValue(e.getValue()));
        }
        return out;
    }

    /** JsonNode → Java 原生值（对齐 FileConfigStorage.jsonNodeToJavaValue 形状）。 */
    static Object jsonNodeToJavaValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(jsonNodeToJavaValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            return jsonNodeToObjectMap(node);
        }
        return node.asText();
    }

    /** 默认敏感值存储 · 落 settings.json {@code pluginSecrets} 段（Q-09-R2-4 边界 B 登记落点）。 */
    static final class ConfigStorageSecureValueStore implements SecureValueStore {
        private final ConfigStorage storage;

        ConfigStorageSecureValueStore(ConfigStorage storage) {
            this.storage = storage;
        }

        @Override
        public Map<String, Object> read(String bucketKey) {
            try {
                Object v = storage.readSettings(List.of("pluginSecrets", bucketKey));
                if (v == null || v == ConfigStorage.NullMarker || !(v instanceof Map<?, ?> m)) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            } catch (Exception e) {
                log.warn("[McpbHandler] 读取 pluginSecrets {} 失败: {}", bucketKey, e.getMessage());
                return null;
            }
        }

        @Override
        public boolean write(String bucketKey, Map<String, Object> values) {
            try {
                storage.writeSettings(List.of("pluginSecrets", bucketKey), values);
                return true;
            } catch (Exception e) {
                log.error("[McpbHandler] 写入 pluginSecrets {} 失败: {}", bucketKey, e.getMessage());
                return false;
            }
        }
    }
}
