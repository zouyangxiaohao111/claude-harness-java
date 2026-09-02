package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.EnvExpansion;
import com.nexusai.application.agent.mcp.McpTypesRegistry;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ConfigScope;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpHTTPServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpOAuthConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpSSEServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpSdkServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpStdioServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpWebSocketServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ScopedMcpServerConfig;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件 MCP 加载全链 F1-F5 · 对齐 CC utils/plugins/mcpPluginIntegration.ts (634 行，S08 全读实证)。
 *
 * <p>F1 {@link #loadPluginMcpServers}（:131-212）：.mcp.json 最低优先合并 → manifest mcpServers
 * 三态（string：isMcpbSource → {@code loadMcpServersFromMcpb} 否则文件路径；array：按序合并
 * last-wins、单 spec 失败防御性 catch 不丢其它结果；object：直接合并）；空结果返 null。
 * <p>F2 {@link #addPluginScopeToServers}（:341-360）：scopedName = {@code plugin:name:server} +
 * scope='dynamic' + pluginSource 透传 —— gate 门序[4] 消费的 name@marketplace 注入点。
 * <p>F3 {@link #extractMcpServersFromPlugins}（:366-429）：enabled 过滤；逐 server env 解析
 * try/catch → generic-error（坏配置不炸全插件加载）；未解析 servers 缓存到 PluginView.mcpServers；
 * plugin.source 作用域。
 * <p>F4 {@link #resolvePluginMcpEnvironment}（:465-582）：${CLAUDE_PLUGIN_ROOT}/
 * ${CLAUDE_PLUGIN_DATA} 替换 → ${user_config.X} 替换（缺失 throw）→ 通用 ${VAR}/${VAR:-default}
 * 展开（复用 {@link EnvExpansion}）；缺变量 → mcp-config-invalid 入列；stdio 分支注入
 * CLAUDE_PLUGIN_ROOT/DATA env + 解析 args/env；sse/http/ws 解析 url+headers；其余透传。
 * <p>F5 {@link #getUnconfiguredChannels}（:290-318）：channel 无 userConfig schema 跳过；
 * 已保存配置满足 validateUserConfig 跳过；未配置 → UnconfiguredChannel(server, displayName ?? server,
 * configSchema)。
 *
 * <p>pluginSource 派生（Q-09-R2-4 边界 B）：默认经 enabledPlugins settings（键 = name@marketplace）
 * 匹配插件名；无 @ 裸名回退插件名自身。运行期注册表 {@link #pluginSourceFor} 供
 * McpServerService resolver → McpToolPool:1359 门序[4] 消费。
 */
@Component
public class PluginMcpIntegration {

    private static final Logger log = LoggerFactory.getLogger(PluginMcpIntegration.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC pluginOptionsStorage.ts:326-370 替换模式。 */
    private static final Pattern USER_CONFIG_VAR = Pattern.compile("\\$\\{user_config\\.([^}]+)}");
    private static final Pattern PLUGIN_ROOT_VAR = Pattern.compile("\\$\\{CLAUDE_PLUGIN_ROOT}");
    private static final Pattern PLUGIN_DATA_VAR = Pattern.compile("\\$\\{CLAUDE_PLUGIN_DATA}");

    /**
     * 插件最小视图 · CC LoadedPlugin（mcpPluginIntegration.ts 消费字段裁剪）。
     *
     * <p>{@code source} = 插件来源标识（marketplace 安装 = name@marketplace，对齐 CC
     * pluginLoader.ts:1368-1369 source/repository）；{@code repository} = 同源（CC backward-compat）；
     * {@code manifest} = 已加载插件清单 JsonNode；{@code mcpServers} = 未解析 servers 缓存
     * （F3 写入，对齐 CC plugin.mcpServers :408）。
     */
    public record PluginView(String name, Path localPath, String source, boolean enabled,
                             JsonNode manifest, String repository,
                             Map<String, McpServerConfig> mcpServers) {
        public PluginView {
            mcpServers = mcpServers == null ? new LinkedHashMap<>() : mcpServers;
        }

        /** 便捷构造（测试）：无 repository/无缓存。 */
        public PluginView(String name, Path localPath, String source, boolean enabled, JsonNode manifest) {
            this(name, localPath, source, enabled, manifest, null, new LinkedHashMap<>());
        }

        /** 便捷构造（测试/接线）：含 repository（CC plugin.repository 同源键）。 */
        public PluginView(String name, Path localPath, String source, boolean enabled,
                          JsonNode manifest, String repository) {
            this(name, localPath, source, enabled, manifest, repository, new LinkedHashMap<>());
        }
    }

    /**
     * 插件错误 · CC PluginError union（pluginLoader.ts:2118-2122 等，全字段可空）。
     * 字段对应 CC：type/source/plugin/url/mcpbPath/validationError/reason/error/serverName。
     */
    public record PluginError(String type, String source, String plugin, String url,
                              String mcpbPath, String validationError, String reason,
                              String error, String serverName) {}

    /** 未配置 channel · CC UnconfiguredChannel（:272-276）。 */
    public record UnconfiguredChannel(String server, String displayName,
                                      Map<String, Object> configSchema) {}

    /** scoped server 落库信息（McpServerService.upsertPluginMcpServers 消费）。 */
    public record ScopedMcpServerInfo(String name, ConfigScope scope,
                                      Map<String, Object> config, String pluginSource) {}

    /** 透传型 server 配置（sse-ide/ws-ide/claudeai-proxy）· Java McpTypesRegistry 无对应
     * 实现 McpServerConfig 的 record，故以原样 Map 承载（F4 透传不改）。 */
    record GenericMcpServerConfig(String type, Map<String, Object> raw) implements McpServerConfig {}

    private final McpbHandler mcpbHandler;
    private final EnvExpansion envExpansion;

    /** 运行期注册表：scoped server 名 → pluginSource（name@marketplace）。 */
    private final Map<String, String> pluginSourceRegistry = new ConcurrentHashMap<>();

    /** enabledPlugins settings 源 · CC getSettings_DEPRECATED().enabledPlugins（键 = name@marketplace）。 */
    private volatile Supplier<Map<String, Boolean>> enabledPluginsSupplier = Map::of;
    private volatile boolean enabledPluginsSourceSet;

    /** 注入式构造（Spring 生产）：EnvExpansion 非容器 bean，生产自建默认实现。 */
    @Autowired(required = false)
    public PluginMcpIntegration(McpbHandler mcpbHandler) {
        this(mcpbHandler, new EnvExpansion());
    }

    /** 注入式构造（测试）：可注入 EnvExpansion（env 查找函数可控）。 */
    public PluginMcpIntegration(McpbHandler mcpbHandler, EnvExpansion envExpansion) {
        this.mcpbHandler = mcpbHandler == null ? new McpbHandler() : mcpbHandler;
        this.envExpansion = envExpansion == null ? new EnvExpansion() : envExpansion;
    }

    /** 生产 enabledPlugins 设置源接线 · 镜像 InstalledPluginsManager.setConfigStorage 模式。 */
    @Autowired(required = false)
    public void setConfigStorage(ConfigStorage configStorage) {
        if (configStorage == null || enabledPluginsSourceSet) {
            return;
        }
        setEnabledPluginsSupplier(() -> readEnabledPlugins(configStorage));
        if (log.isDebugEnabled()) {
            log.debug("[PluginMcpIntegration] enabledPlugins 设置源注入（ConfigStorage.readSettings）");
        }
    }

    /** 注入 enabledPlugins 源（测试 stub 优先，防止生产装配覆盖）。 */
    public void setEnabledPluginsSupplier(Supplier<Map<String, Boolean>> supplier) {
        if (supplier != null) {
            this.enabledPluginsSupplier = supplier;
            this.enabledPluginsSourceSet = true;
        }
    }

    /** 从 ConfigStorage 读 enabledPlugins 键转 Map；无键/异常 → 空 Map（CC :1051 || {}）。 */
    private static Map<String, Boolean> readEnabledPlugins(ConfigStorage configStorage) {
        Object raw;
        try {
            raw = configStorage.readSettings(List.of("enabledPlugins"));
        } catch (Exception e) {
            log.warn("[PluginMcpIntegration] 读取 settings.enabledPlugins 失败，视为空 Map：{}", e.getMessage());
            return Map.of();
        }
        if (raw == null || raw == ConfigStorage.NullMarker || !(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue() instanceof Boolean b ? b : Boolean.TRUE);
        }
        return out;
    }

    // ============== F1 loadPluginMcpServers（CC :131-212） ==============

    /**
     * 从插件加载 MCP servers · CC mcpPluginIntegration.ts:131-212 loadPluginMcpServers。
     *
     * <p>.mcp.json（最低优先）→ manifest.mcpServers 三态：string（isMcpbSource → MCPB 加载，
     * 否则文件路径）/ array（按序合并 last-wins，单 spec 失败防御性 catch 不丢其它结果；
     * CC 用 Promise.all 并行 —— Java 顺序处理保持契约等价，并行仅是性能细节）/
     * object（直接合并）。空结果返回 null。
     */
    public Map<String, McpServerConfig> loadPluginMcpServers(PluginView plugin, List<PluginError> errors) {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();

        // .mcp.json 最低优先（:137-144）
        Map<String, McpServerConfig> defaultMcpServers = loadMcpServersFromFile(plugin.localPath(), ".mcp.json");
        if (defaultMcpServers != null) {
            servers.putAll(defaultMcpServers);
        }

        // manifest mcpServers（:146-209）
        JsonNode mcpServersSpec = plugin.manifest().path("mcpServers");
        if (!mcpServersSpec.isMissingNode()) {
            if (mcpServersSpec.isTextual()) {
                String spec = mcpServersSpec.asText();
                if (McpbHandler.isMcpbSource(spec)) {
                    Map<String, McpServerConfig> mcpbServers = loadMcpServersFromMcpb(plugin, spec, errors);
                    if (mcpbServers != null) {
                        servers.putAll(mcpbServers);
                    }
                } else {
                    Map<String, McpServerConfig> fromFile = loadMcpServersFromFile(plugin.localPath(), spec);
                    if (fromFile != null) {
                        servers.putAll(fromFile);
                    }
                }
            } else if (mcpServersSpec.isArray()) {
                List<Map<String, McpServerConfig>> results = new ArrayList<>(mcpServersSpec.size());
                for (JsonNode spec : mcpServersSpec) {
                    try {
                        if (spec.isTextual()) {
                            String s = spec.asText();
                            if (McpbHandler.isMcpbSource(s)) {
                                results.add(loadMcpServersFromMcpb(plugin, s, errors));
                            } else {
                                results.add(loadMcpServersFromFile(plugin.localPath(), s));
                            }
                        } else if (spec.isObject()) {
                            // 内联 MCP server 配置（同步，:188）
                            results.add(parseServerConfigs(spec));
                        } else {
                            results.add(null);
                        }
                    } catch (Exception e) {
                        // 防御性：单 spec 失败不丢其它结果（:189-197）
                        if (log.isDebugEnabled()) {
                            log.debug("[PluginMcpIntegration] 插件 {} 的 mcpServers spec 加载失败: {}",
                                plugin.name(), e.getMessage());
                        }
                        results.add(null);
                    }
                }
                // 按原序合并，last-wins（:200-204）
                for (Map<String, McpServerConfig> result : results) {
                    if (result != null) {
                        servers.putAll(result);
                    }
                }
            } else if (mcpServersSpec.isObject()) {
                // 直接 MCP server 配置（:205-208）
                servers.putAll(parseServerConfigs(mcpServersSpec));
            }
        }

        return servers.isEmpty() ? null : servers;
    }

    /** 便捷重载（无 errors 收集）· CC 缺省参数 errors = []。 */
    public Map<String, McpServerConfig> loadPluginMcpServers(PluginView plugin) {
        return loadPluginMcpServers(plugin, null);
    }

    /**
     * 从 MCPB 文件加载 servers · CC :34-124 loadMcpServersFromMcpb。
     *
     * <p>needs-config → null（正常未配置态，非错误，:55-64）；错误分类
     * mcpb-download-failed（URL + download/network 消息）/ mcpb-invalid-manifest
     * （manifest / user configuration 消息）/ mcpb-extract-failed（其余）。
     */
    Map<String, McpServerConfig> loadMcpServersFromMcpb(PluginView plugin, String mcpbPath, List<PluginError> errors) {
        try {
            // CC :43 直接用 plugin.repository（已是 "plugin@marketplace" 格式）
            String pluginId = plugin.repository() != null ? plugin.repository() : plugin.name();
            McpbHandler.McpbLoadOutcome result = mcpbHandler.loadMcpbFile(
                mcpbPath, plugin.localPath(), pluginId,
                status -> logForMcpbProgress(plugin.name(), status),
                null, false);

            if (result instanceof McpbHandler.McpbNeedsConfigResult) {
                // 需要用户配置 → 暂不加载（/plugin → Configure 后加载），非错误（:55-64）
                if (log.isDebugEnabled()) {
                    log.debug("[PluginMcpIntegration] MCPB {} 需要用户配置，跳过加载（/plugin → Configure）", mcpbPath);
                }
                return null;
            }

            McpbHandler.McpbLoadResult success = (McpbHandler.McpbLoadResult) result;
            String serverName = success.manifest().path("name").asText(null);
            if (serverName == null || serverName.isBlank()) {
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("[PluginMcpIntegration] 从 MCPB 加载 server \"{}\"（提取到 {}）",
                    serverName, success.extractedPath());
            }
            return Map.of(serverName, success.mcpConfig());
        } catch (Exception e) {
            String errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
            // CC :86 source = `${plugin.name}@${plugin.repository}`
            String source = plugin.name() + "@" + (plugin.repository() != null ? plugin.repository() : plugin.name());
            boolean isUrl = mcpbPath.startsWith("http");
            if (isUrl && (errorMsg.contains("download") || errorMsg.contains("network"))) {
                if (errors != null) {
                    errors.add(new PluginError("mcpb-download-failed", source, plugin.name(), mcpbPath,
                        null, null, errorMsg, null, null));
                }
            } else if (errorMsg.contains("manifest") || errorMsg.contains("user configuration")) {
                if (errors != null) {
                    errors.add(new PluginError("mcpb-invalid-manifest", source, plugin.name(), null,
                        mcpbPath, errorMsg, null, null, null));
                }
            } else {
                if (errors != null) {
                    errors.add(new PluginError("mcpb-extract-failed", source, plugin.name(), null,
                        mcpbPath, null, errorMsg, null, null));
                }
            }
            return null;
        }
    }

    private static void logForMcpbProgress(String pluginName, String status) {
        if (log.isDebugEnabled()) {
            log.debug("[MCPB {}]: {}", pluginName, status);
        }
    }

    /**
     * 从插件目录内 JSON 文件加载 servers · CC :219-266 loadMcpServersFromFile。
     *
     * <p>ENOENT → null；无效 JSON → log + null；逐 server McpServerConfigSchema 校验，
     * 无效项跳过并 log（不炸整体）。{@code mcpServers} 键存在时取之，否则整个文件即 servers map。
     */
    Map<String, McpServerConfig> loadMcpServersFromFile(Path pluginPath, String relativePath) {
        Path filePath = pluginPath.resolve(relativePath);
        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("[PluginMcpIntegration] 读取 MCP servers 文件失败 {}: {}", filePath, e.getMessage());
            }
            return null;
        }

        try {
            JsonNode parsed = JSON.readTree(content);
            // .mcp.json 格式带 mcpServers 键则取之，否则整个文件即 servers map（:243）
            JsonNode mcpServers = parsed.path("mcpServers");
            if (mcpServers.isMissingNode() || !mcpServers.isObject()) {
                mcpServers = parsed;
            }
            return parseServerConfigs(mcpServers);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[PluginMcpIntegration] 解析 MCP servers 文件失败 {}: {}", filePath, e.getMessage());
            }
            return null;
        }
    }

    /** 对象 → string map；缺失/null（可选字段）→ 空 Map（有效）；非对象/含非字符串值 → null（校验失败）。 */
    private static Map<String, String> stringMap(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isTextual()) {
                return null;
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    /**
     * 逐 server 校验（对齐 CC McpServerConfigSchema，types.ts:28-135）· 无效项跳过并 log。
     *
     * <p>stdio：type 可选（向后兼容），command 必填非空，args 默认 []，env 可选 string map；
     * sse/http/ws：url 必填，headers/headersHelper/oauth 可选；sse-ide/ws-ide：url+ideName 必填；
     * sdk：name 必填；claudeai-proxy：url+id 必填；未知 type → 无效。
     */
    Map<String, McpServerConfig> parseServerConfigs(JsonNode mcpServers) {
        Map<String, McpServerConfig> validated = new LinkedHashMap<>();
        if (mcpServers == null || !mcpServers.isObject()) {
            return validated;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = mcpServers.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode configNode = e.getValue();
            McpServerConfig config = configNode.isObject() ? validateServerConfig(configNode) : null;
            if (config == null) {
                log.error("[PluginMcpIntegration] 无效 MCP server 配置 {}: {}", name,
                    configNode == null ? "非对象" : configNode.toString());
            } else {
                validated.put(name, config);
            }
        }
        return validated;
    }

    /** 单 server 校验 → 类型化配置；无效 → null。 */
    static McpServerConfig validateServerConfig(JsonNode node) {
        JsonNode typeNode = node.path("type");
        String type = typeNode.isTextual() ? typeNode.asText() : "stdio";
        switch (type) {
            case "stdio" -> {
                JsonNode commandNode = node.path("command");
                if (!commandNode.isTextual() || commandNode.asText().isBlank()) {
                    return null; // command 必填非空（types.ts:31）
                }
                List<String> args = new ArrayList<>();
                JsonNode argsNode = node.path("args");
                if (!argsNode.isMissingNode()) {
                    if (!argsNode.isArray()) {
                        return null;
                    }
                    for (JsonNode a : argsNode) {
                        if (!a.isTextual()) {
                            return null;
                        }
                        args.add(a.asText());
                    }
                }
                Map<String, String> env = stringMap(node.path("env"));
                if (env == null) {
                    return null;
                }
                return new McpStdioServerConfig(commandNode.asText(), List.copyOf(args), env);
            }
            case "sse", "http", "ws" -> {
                JsonNode urlNode = node.path("url");
                if (!urlNode.isTextual()) {
                    return null;
                }
                Map<String, String> headers = stringMap(node.path("headers"));
                if (headers == null) {
                    return null;
                }
                String headersHelper = node.path("headersHelper").isTextual()
                    ? node.path("headersHelper").asText() : null;
                McpOAuthConfig oauth = parseOAuth(node.path("oauth"));
                return switch (type) {
                    case "sse" -> new McpSSEServerConfig(urlNode.asText(), headers, headersHelper, oauth);
                    case "http" -> new McpHTTPServerConfig(urlNode.asText(), headers, headersHelper, oauth);
                    default -> new McpWebSocketServerConfig(urlNode.asText(), headers, headersHelper);
                };
            }
            case "sse-ide" -> {
                if (!node.path("url").isTextual() || !node.path("ideName").isTextual()) {
                    return null;
                }
                return new GenericMcpServerConfig("sse-ide", rawObject(node));
            }
            case "ws-ide" -> {
                if (!node.path("url").isTextual() || !node.path("ideName").isTextual()) {
                    return null;
                }
                return new GenericMcpServerConfig("ws-ide", rawObject(node));
            }
            case "sdk" -> {
                if (!node.path("name").isTextual()) {
                    return null;
                }
                return new McpSdkServerConfig(node.path("name").asText());
            }
            case "claudeai-proxy" -> {
                if (!node.path("url").isTextual() || !node.path("id").isTextual()) {
                    return null;
                }
                return new GenericMcpServerConfig("claudeai-proxy", rawObject(node));
            }
            default -> {
                return null;
            }
        }
    }


    /** oauth 对象 → McpOAuthConfig（缺失 → empty）；xaa 仅结构镜像。 */
    private static McpOAuthConfig parseOAuth(JsonNode node) {
        if (!node.isObject()) {
            return McpOAuthConfig.empty();
        }
        String clientId = node.path("clientId").isTextual() ? node.path("clientId").asText() : null;
        Integer callbackPort = node.path("callbackPort").isIntegralNumber()
            ? node.path("callbackPort").asInt() : null;
        String authServerMetadataUrl = node.path("authServerMetadataUrl").isTextual()
            ? node.path("authServerMetadataUrl").asText() : null;
        boolean xaa = node.path("xaa").asBoolean(false);
        return new McpOAuthConfig(clientId, callbackPort, authServerMetadataUrl,
            new McpTypesRegistry.McpXaaConfig(xaa));
    }

    /** 原样对象（透传型 server 的 raw 承载）。 */
    private static Map<String, Object> rawObject(JsonNode node) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            raw.put(e.getKey(), McpbHandler.jsonNodeToJavaValue(e.getValue()));
        }
        return raw;
    }

    // ============== F2 addPluginScopeToServers（CC :341-360） ==============

    /**
     * 给 MCP server 配置加插件作用域 · CC mcpPluginIntegration.ts:341-360。
     *
     * <p>scopedName = {@code plugin:name:server}（:350）；scope = dynamic（:353）；
     * pluginSource 透传（:354）——gate 门序[4] marketplace 校验的注入点。同时登记运行期
     * 注册表（{@link #pluginSourceFor} 消费）。
     */
    public Map<String, ScopedMcpServerConfig> addPluginScopeToServers(
            Map<String, McpServerConfig> servers, String pluginName, String pluginSource) {
        Map<String, ScopedMcpServerConfig> scopedServers = new LinkedHashMap<>();
        if (servers == null) {
            return scopedServers;
        }
        String effectiveSource = pluginSource != null ? pluginSource : derivePluginSource(pluginName);
        for (Map.Entry<String, McpServerConfig> e : servers.entrySet()) {
            String scopedName = "plugin:" + pluginName + ":" + e.getKey();
            scopedServers.put(scopedName,
                new ScopedMcpServerConfig(e.getValue(), ConfigScope.DYNAMIC, effectiveSource));
            pluginSourceRegistry.put(scopedName, effectiveSource);
        }
        return scopedServers;
    }

    // ============== F3 extractMcpServersFromPlugins（CC :366-429） ==============

    /**
     * 从已加载插件提取全部 MCP servers（含 env 解析 + 作用域）· CC :366-429。
     *
     * <p>enabled 过滤（:374）；逐 server 解析 env，单 server 坏配置 try/catch →
     * generic-error（:396-403，不炸全插件加载）；未解析 servers 缓存到
     * {@code plugin.mcpServers}（:408）；plugin.source 作用域（:414-418）。
     */
    public Map<String, ScopedMcpServerConfig> extractMcpServersFromPlugins(
            List<PluginView> plugins, List<PluginError> errors) {
        Map<String, ScopedMcpServerConfig> allServers = new LinkedHashMap<>();
        if (plugins == null) {
            return allServers;
        }
        for (PluginView plugin : plugins) {
            if (!plugin.enabled()) {
                continue;
            }
            Map<String, McpServerConfig> servers = loadPluginMcpServers(plugin, errors);
            if (servers == null) {
                continue;
            }
            // 逐 server 解析（:385-404）
            Map<String, McpServerConfig> resolvedServers = new LinkedHashMap<>();
            for (Map.Entry<String, McpServerConfig> e : servers.entrySet()) {
                String name = e.getKey();
                Map<String, Object> userConfig = buildMcpUserConfig(plugin, name);
                try {
                    resolvedServers.put(name,
                        resolvePluginMcpEnvironment(e.getValue(), plugin, userConfig, errors, plugin.name(), name));
                } catch (Exception err) {
                    if (errors != null) {
                        errors.add(new PluginError("generic-error", name, plugin.name(),
                            null, null, null, null, errMessage(err), null));
                    }
                }
            }
            // 未解析 servers 缓存（env 每次需要时重新解析，:406-408）
            plugin.mcpServers().clear();
            plugin.mcpServers().putAll(servers);
            if (log.isDebugEnabled()) {
                log.debug("[PluginMcpIntegration] 从插件 {} 加载 {} 个 MCP servers",
                    plugin.name(), servers.size());
            }
            allServers.putAll(addPluginScopeToServers(resolvedServers, plugin.name(), plugin.source()));
        }
        return allServers;
    }

    // ============== F4 resolvePluginMcpEnvironment（CC :465-582） ==============

    /**
     * 解析插件 MCP server 环境变量 · CC mcpPluginIntegration.ts:465-582。
     *
     * <p>resolveValue = substitutePluginVariables（${CLAUDE_PLUGIN_ROOT} → plugin.path、
     * ${CLAUDE_PLUGIN_DATA} → getPluginDataDir(source)，win32 反斜杠归一 :326-344）→
     * substituteUserConfigVariables（${user_config.X} 缺失即 throw :356-370）→
     * expandEnvVarsInString（${VAR}/${VAR:-default}，缺失保留字面量+记 missing :475-490）。
     * stdio 分支 env={CLAUDE_PLUGIN_ROOT, CLAUDE_PLUGIN_DATA, ...env 解析}（:510-521）；
     * sse/http/ws 分支 url+headers 解析（:527-548）；sse-ide/ws-ide/sdk/claudeai-proxy
     * 透传（:550-556）；缺失变量 → warn + errors.push mcp-config-invalid
     * （source=plugin:name，:559-579）。
     *
     * @param userConfig 已保存用户配置（null = 无 → 跳过 ${user_config.X} 替换）
     */
    public McpServerConfig resolvePluginMcpEnvironment(McpServerConfig config, PluginView plugin,
                                                       Map<String, Object> userConfig,
                                                       List<PluginError> errors,
                                                       String pluginName, String serverName) {
        List<String> allMissingVars = new ArrayList<>();

        Function<String, String> resolveValue = value -> {
            String resolved = substitutePluginVariables(value, plugin.localPath(), plugin.source());
            if (userConfig != null) {
                resolved = substituteUserConfigVariables(resolved, userConfig);
            }
            EnvExpansion.ExpansionResult expansion = envExpansion.expand(resolved);
            allMissingVars.addAll(expansion.missingVars());
            return expansion.expanded();
        };

        McpServerConfig resolved;
        switch (config.type()) {
            case "stdio" -> {
                McpStdioServerConfig stdio = (McpStdioServerConfig) config;
                String command = stdio.command();
                if (command != null) {
                    command = resolveValue.apply(command);
                }
                List<String> args = null;
                if (stdio.args() != null) {
                    args = new ArrayList<>();
                    for (String arg : stdio.args()) {
                        args.add(resolveValue.apply(arg));
                    }
                }
                // env：固定注入 ROOT/DATA（不解析自身），其余逐项解析（:510-521）
                Map<String, String> resolvedEnv = new LinkedHashMap<>();
                resolvedEnv.put("CLAUDE_PLUGIN_ROOT", plugin.localPath().toString());
                resolvedEnv.put("CLAUDE_PLUGIN_DATA", getPluginDataDir(plugin.source()));
                if (stdio.env() != null) {
                    resolvedEnv.putAll(stdio.env());
                }
                for (Map.Entry<String, String> e : new ArrayList<>(resolvedEnv.entrySet())) {
                    if (!"CLAUDE_PLUGIN_ROOT".equals(e.getKey()) && !"CLAUDE_PLUGIN_DATA".equals(e.getKey())) {
                        resolvedEnv.put(e.getKey(), resolveValue.apply(e.getValue()));
                    }
                }
                resolved = new McpStdioServerConfig(command,
                    args == null ? null : List.copyOf(args), resolvedEnv);
            }
            case "sse", "http", "ws" -> {
                String url = switch (config) {
                    case McpSSEServerConfig c -> c.url();
                    case McpHTTPServerConfig c -> c.url();
                    case McpWebSocketServerConfig c -> c.url();
                    default -> null;
                };
                Map<String, String> headers = switch (config) {
                    case McpSSEServerConfig c -> c.headers();
                    case McpHTTPServerConfig c -> c.headers();
                    case McpWebSocketServerConfig c -> c.headers();
                    default -> null;
                };
                if (url != null) {
                    url = resolveValue.apply(url);
                }
                Map<String, String> resolvedHeaders = null;
                if (headers != null) {
                    resolvedHeaders = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        resolvedHeaders.put(e.getKey(), resolveValue.apply(e.getValue()));
                    }
                }
                resolved = switch (config) {
                    case McpSSEServerConfig c -> new McpSSEServerConfig(url, resolvedHeaders, c.headersHelper(), c.oauth());
                    case McpHTTPServerConfig c -> new McpHTTPServerConfig(url, resolvedHeaders, c.headersHelper(), c.oauth());
                    case McpWebSocketServerConfig c -> new McpWebSocketServerConfig(url, resolvedHeaders, c.headersHelper());
                    default -> config;
                };
            }
            default -> {
                // sse-ide / ws-ide / sdk / claudeai-proxy 透传（:550-556）
                resolved = config;
            }
        }

        // 缺失变量 → warn + 错误入列（:559-579）
        if (errors != null && !allMissingVars.isEmpty()) {
            List<String> uniqueMissingVars = allMissingVars.stream().distinct().toList();
            String varList = String.join(", ", uniqueMissingVars);
            log.warn("[PluginMcpIntegration] 插件 MCP 配置缺失环境变量: {}", varList);
            if (pluginName != null && serverName != null) {
                errors.add(new PluginError("mcp-config-invalid", "plugin:" + pluginName,
                    pluginName, null, null,
                    "Missing environment variables: " + varList, null, null, serverName));
            }
        }

        return resolved;
    }

    // ============== F5 getUnconfiguredChannels（CC :290-318） ==============

    /**
     * 找出尚未配置的 channel 条目 · CC mcpPluginIntegration.ts:290-318。
     *
     * <p>channels 空 → []；无 userConfig schema 的 channel 跳过（:304-306）；已保存配置满足
     * validateUserConfig → 跳过；未配置 → {server, displayName ?? server, configSchema}（:303-316）。
     */
    public List<UnconfiguredChannel> getUnconfiguredChannels(PluginView plugin) {
        JsonNode channels = plugin.manifest().path("channels");
        if (channels.isMissingNode() || !channels.isArray() || channels.isEmpty()) {
            return List.of();
        }
        // CC :300 直接用 plugin.repository（loadMcpServerUserConfig 同键）
        String pluginId = plugin.repository() != null ? plugin.repository() : plugin.name();
        List<UnconfiguredChannel> unconfigured = new ArrayList<>();
        for (JsonNode channel : channels) {
            JsonNode userConfigNode = channel.path("userConfig");
            if (userConfigNode.isMissingNode() || !userConfigNode.isObject() || userConfigNode.isEmpty()) {
                continue;
            }
            String server = channel.path("server").isTextual() ? channel.path("server").asText() : null;
            if (server == null || server.isBlank()) {
                continue;
            }
            Map<String, Object> configSchema = McpbHandler.jsonNodeToObjectMap(userConfigNode);
            Map<String, Object> saved = mcpbHandler.loadMcpServerUserConfig(pluginId, server);
            McpbHandler.ValidationResult validation =
                mcpbHandler.validateUserConfig(saved == null ? Map.of() : saved, configSchema);
            if (!validation.valid()) {
                String displayName = channel.path("displayName").isTextual()
                    ? channel.path("displayName").asText() : null;
                unconfigured.add(new UnconfiguredChannel(server,
                    displayName != null ? displayName : server, configSchema));
            }
        }
        return unconfigured;
    }

    // ============== 内部辅助 ==============

    /**
     * 构建单 server 的 userConfig · CC :440-458 buildMcpUserConfig。
     *
     * <p>manifest.userConfig 顶层（loadPluginOptions）+ channel 级 per-server 配置合并，
     * channel 级胜出（:457）。两源皆无 → null（F4 跳过 ${user_config.X} 替换）。
     */
    Map<String, Object> buildMcpUserConfig(PluginView plugin, String serverName) {
        // CC :451-453 gate on manifest.userConfig（loadPluginOptions 恒返回 ≥{} → 无此 gate
        // 则未配置插件也返回 {} 并对任何 ${user_config.X} 引用 throw）
        JsonNode manifestUserConfig = plugin.manifest().path("userConfig");
        Map<String, Object> topLevel = (manifestUserConfig.isObject() && !manifestUserConfig.isEmpty())
            ? mcpbHandler.loadPluginOptions(pluginIdForStorage(plugin))
            : null;
        Map<String, Object> channelSpecific = loadChannelUserConfig(plugin, serverName);
        if (topLevel == null && channelSpecific == null) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (topLevel != null) {
            merged.putAll(topLevel);
        }
        if (channelSpecific != null) {
            merged.putAll(channelSpecific);
        }
        return merged;
    }

    /** CC :326-335 loadChannelUserConfig：channel.userConfig schema 存在才读已保存配置。 */
    private Map<String, Object> loadChannelUserConfig(PluginView plugin, String serverName) {
        JsonNode channels = plugin.manifest().path("channels");
        if (channels.isArray()) {
            for (JsonNode channel : channels) {
                JsonNode userConfigNode = channel.path("userConfig");
                if (channel.path("server").asText("").equals(serverName)
                    && userConfigNode.isObject() && !userConfigNode.isEmpty()) {
                    Map<String, Object> saved = mcpbHandler.loadMcpServerUserConfig(
                        pluginIdForStorage(plugin), serverName);
                    return saved == null ? null : saved;
                }
            }
        }
        return null;
    }

    /** 存储键 · CC getPluginStorageId = plugin.source（name@marketplace）；repository 回退。 */
    private static String pluginIdForStorage(PluginView plugin) {
        if (plugin.source() != null && !plugin.source().isBlank()) {
            return plugin.source();
        }
        return plugin.repository() != null ? plugin.repository() : plugin.name();
    }

    /** 运行期注册表查询：scoped server 名 → pluginSource（McpServerService resolver 消费）。 */
    public String pluginSourceFor(String scopedServerName) {
        if (scopedServerName == null) {
            return null;
        }
        String registered = pluginSourceRegistry.get(scopedServerName);
        if (registered != null) {
            return registered;
        }
        // 未登记（extract 前 gate 触发等）→ 派生：plugin:NAME:SERVER → enabledPlugins 键
        String[] parts = scopedServerName.split(":");
        if (parts.length >= 2 && "plugin".equals(parts[0])) {
            return derivePluginSource(parts[1]);
        }
        return null;
    }

    /**
     * 派生插件来源 · Q-09-R2-4 边界 B：enabledPlugins settings 键（name@marketplace）匹配；
     * 无匹配 → 裸名回退（CC plugin.source 缺省即 source 标识符）。
     */
    public String derivePluginSource(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }
        Map<String, Boolean> enabledPlugins = enabledPluginsSupplier.get();
        if (enabledPlugins != null) {
            for (String key : enabledPlugins.keySet()) {
                if (key.equals(pluginName) || key.startsWith(pluginName + "@")) {
                    return key;
                }
            }
        }
        return pluginName;
    }

    /**
     * 类型化配置 → DB 落库 Map（McpServerService.upsertServer 消费形态：
     * stdio → type/command/args/env；远程 → type/url/headers）。
     */
    public static Map<String, Object> toServerConfigMap(McpServerConfig config) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        switch (config.type()) {
            case "stdio" -> {
                McpStdioServerConfig s = (McpStdioServerConfig) config;
                cfg.put("type", "stdio");
                if (s.command() != null) {
                    cfg.put("command", s.command());
                }
                if (s.args() != null && !s.args().isEmpty()) {
                    cfg.put("args", s.args());
                }
                if (s.env() != null && !s.env().isEmpty()) {
                    cfg.put("env", s.env());
                }
            }
            case "sse", "http", "ws" -> {
                String type = config.type();
                String url = switch (config) {
                    case McpSSEServerConfig c -> c.url();
                    case McpHTTPServerConfig c -> c.url();
                    case McpWebSocketServerConfig c -> c.url();
                    default -> null;
                };
                Map<String, String> headers = switch (config) {
                    case McpSSEServerConfig c -> c.headers();
                    case McpHTTPServerConfig c -> c.headers();
                    case McpWebSocketServerConfig c -> c.headers();
                    default -> null;
                };
                cfg.put("type", type);
                if (url != null) {
                    cfg.put("url", url);
                }
                if (headers != null && !headers.isEmpty()) {
                    cfg.put("headers", headers);
                }
            }
            case "sdk" -> {
                cfg.put("type", "sdk");
                if (((McpSdkServerConfig) config).name() != null) {
                    cfg.put("name", ((McpSdkServerConfig) config).name());
                }
            }
            default -> {
                // sse-ide / ws-ide / claudeai-proxy 原样
                if (config instanceof GenericMcpServerConfig g) {
                    cfg.putAll(g.raw());
                } else {
                    cfg.put("type", config.type());
                }
            }
        }
        return cfg;
    }

    /**
     * 替换 ${CLAUDE_PLUGIN_ROOT} / ${CLAUDE_PLUGIN_DATA} · CC pluginOptionsStorage.ts:326-344
     * substitutePluginVariables。win32 反斜杠归一（:330-331）。source 缺失 → DATA 留字面量。
     */
    static String substitutePluginVariables(String value, Path pluginPath, String source) {
        String out = PLUGIN_ROOT_VAR.matcher(value)
            .replaceAll(Matcher.quoteReplacement(normalizeForPlatform(pluginPath.toString())));
        if (source != null && !source.isBlank()) {
            String dataDir = getPluginDataDir(source);
            out = PLUGIN_DATA_VAR.matcher(out)
                .replaceAll(Matcher.quoteReplacement(normalizeForPlatform(dataDir)));
        }
        return out;
    }

    /**
     * 替换 ${user_config.KEY} · CC pluginOptionsStorage.ts:356-370。
     * 缺失键 throw（调用方已过 validateUserConfig —— 命中说明插件引用了未声明键，作者 bug 响亮失败）。
     */
    static String substituteUserConfigVariables(String value, Map<String, Object> userConfig) {
        Matcher m = USER_CONFIG_VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object configValue = userConfig.get(key);
            if (configValue == null) {
                throw new IllegalArgumentException(
                    "Missing required user configuration value: " + key
                        + ". This should have been validated before variable substitution.");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(configValue)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalizeForPlatform(String path) {
        return File.separatorChar == '\\' ? path.replace('\\', '/') : path;
    }

    /**
     * 插件持久化数据目录 · CC pluginDirectories.ts:98-123 pluginDataDirPath/getPluginDataDir
     * = join(getPluginsDirectory(), 'data', sanitize(pluginId)) + mkdir（惰性在替换调用点）。
     */
    static String getPluginDataDir(String pluginId) {
        String dir = PluginDirectories.getPluginsDirectory() + File.separator
            + "data" + File.separator + sanitizePluginId(pluginId);
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException e) {
            log.warn("[PluginMcpIntegration] 创建插件数据目录失败 {}: {}", dir, e.getMessage());
        }
        return dir;
    }

    /** CC pluginDirectories.ts:92-95 sanitizePluginId：非 [a-zA-Z0-9\-_] → '-'。 */
    static String sanitizePluginId(String pluginId) {
        return pluginId.replaceAll("[^a-zA-Z0-9\\-_]", "-");
    }

    /** 异常消息（CC errorMessage）。 */
    static String errMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
