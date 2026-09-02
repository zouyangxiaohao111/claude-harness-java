package com.nexusai.application.agent.mcp.config;

import com.nexusai.application.agent.mcp.EnvExpansion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * .mcp.json 解析器 · 对齐 CC services/mcp/config.ts parseMcpConfig + parseMcpConfigFromFilePath.
 *
 * <p>L1 语义: 纯函数解析 .mcp.json 配置对象 → 逐 server 校验（8 传输 type 判别，
 *            字段契约对齐 types.ts:124-135）+ 可选 env 展开（复用 {@link EnvExpansion}，
 *            envExpansion.ts:10-38）+ missingVars warning + Windows npx warning。
 *            无 IO 依赖，IO（文件读取）由 caller 注入。
 *
 * <p>L2 契约:
 * <ul>
 *   <li><b>parseMcpConfig</b>: schema 校验失败 → config=null + fatal error
 *       （message "Does not adhere to MCP server configuration schema"，对齐 config.ts:1310-1320）；
 *       校验通过 → 逐 server env 展开 + missingVars warning + Windows npx warning
 *       （config.ts:1330-1368），返回 expanded servers。</li>
 *   <li><b>parseMcpConfigFromFilePath</b>: ENOENT → fatal「MCP config file not found」；
 *       读失败 → fatal「Failed to read file」；非法 JSON → fatal「MCP config is not a valid JSON」
 *       （三档分类对齐 config.ts:1395-1460）。</li>
 *   <li><b>8 传输 type</b>: stdio(undefined 兼容) / sse / sse-ide / ws-ide / http / ws / sdk /
 *       claudeai-proxy（types.ts:124-135 union）。</li>
 * </ul>
 */
public final class McpJsonConfigParser {

    private static final Logger log = LoggerFactory.getLogger(McpJsonConfigParser.class);

    /** 8 传输 type（types.ts:124-135）。 */
    public static final List<String> TRANSPORT_TYPES = List.of(
        "stdio", "sse", "sse-ide", "ws-ide", "http", "ws", "sdk", "claudeai-proxy");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJsonConfigParser() {
        // 工具类
    }

    /** 解析结果 record。 */
    public record ParseResult(
        Map<String, Map<String, Object>> servers,
        List<ParseError> errors) {
        public ParseResult {
            servers = servers == null ? Map.of() : servers;
            errors = errors == null ? List.of() : errors;
        }
    }

    /** 解析错误 record · 对齐 CC ValidationError（mcpErrorMetadata 扁平化）。 */
    public record ParseError(
        String path,
        String message,
        String suggestion,
        String severity,     // 'fatal' | 'warning'
        String scope,
        String serverName) {}

    /**
     * CC parseMcpConfig (config.ts:1297-1377) — 校验 + env 展开.
     *
     * @param configObject 配置对象（Map 或 Jackson JsonNode）
     * @param expandVars    是否展开 ${VAR} / ${VAR:-default}
     * @param scope         scope 标签（project/user/local/...）
     * @param envExpansion  env 展开器（caller 注入，测试可控）
     * @return ParseResult（servers = name → validated config Map）
     */
    public static ParseResult parseMcpConfig(Object configObject, boolean expandVars,
                                             String scope, EnvExpansion envExpansion) {
        Map<String, Object> root = toObjectMap(configObject);
        Object mcpServersObj = root == null ? null : root.get("mcpServers");
        if (root == null || !(mcpServersObj instanceof Map<?, ?>)) {
            return new ParseResult(Map.of(), List.of(new ParseError(
                "mcpServers", "Does not adhere to MCP server configuration schema",
                null, "fatal", scope, null)));
        }
        Map<?, ?> mcpServers = (Map<?, ?>) mcpServersObj;

        // ── 阶段 1: 整对象 schema 预校验（CC config.ts:1307-1321 McpJsonConfigSchema().safeParse
        //    整对象 schema，types.ts:171-175 z.object({ mcpServers: z.record(...) })）──
        //    任一 server 不满足 union 任一分支 → 整个 config 失败: 返回空 servers + 全部 fatal
        //    （config:null 语义，message 恒 "Does not adhere to MCP server configuration schema"）。
        List<ParseError> fatalErrors = new ArrayList<>();
        for (Map.Entry<?, ?> e : mcpServers.entrySet()) {
            String name = String.valueOf(e.getKey());
            Map<String, Object> server = toObjectMap(e.getValue());
            if (server == null) {
                fatalErrors.add(new ParseError("mcpServers." + name,
                    "Does not adhere to MCP server configuration schema",
                    null, "fatal", scope, name));
                continue;
            }
            String schemaErr = validateServerSchema(server);
            if (schemaErr != null) {
                fatalErrors.add(new ParseError("mcpServers." + name + "." + schemaErr,
                    "Does not adhere to MCP server configuration schema",
                    null, "fatal", scope, name));
            }
        }
        if (!fatalErrors.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpJsonConfigParser] 整文件拒绝 scope={} 坏 server 数={}（CC safeParse config:null）",
                    scope, fatalErrors.size());
            }
            return new ParseResult(Map.of(), fatalErrors);
        }

        // ── 阶段 2: schema 全部通过 → 逐 server env 展开 + warning（config.ts:1323-1372，
        //    此循环只产生 warning，所有 server 全部进 validatedServers）──
        Map<String, Map<String, Object>> validated = new LinkedHashMap<>();
        List<ParseError> errors = new ArrayList<>();

        for (Map.Entry<?, ?> e : mcpServers.entrySet()) {
            String name = String.valueOf(e.getKey());
            Map<String, Object> server = toObjectMap(e.getValue());

            // env 展开（config.ts:1330-1348）
            Map<String, Object> configToCheck = server;
            if (expandVars && envExpansion != null) {
                configToCheck = expandEnvVars(server, envExpansion, name, scope, errors);
            }

            // Windows npx warning（config.ts:1351-1369）
            String windowsNpxErr = windowsNpxWarning(configToCheck);
            if (windowsNpxErr != null) {
                errors.add(new ParseError("mcpServers." + name,
                    windowsNpxErr,
                    "Change command to \"cmd\" with args [\"/c\", \"npx\", ...]. See: https://code.claude.com/docs/en/mcp#configure-mcp-servers",
                    "warning", scope, name));
            }

            validated.put(name, configToCheck);
        }

        if (log.isDebugEnabled()) {
            log.debug("[McpJsonConfigParser] parseMcpConfig scope={} servers={} errors={}",
                scope, validated.size(), errors.size());
        }
        return new ParseResult(validated, errors);
    }

    /**
     * CC parseMcpConfigFromFilePath (config.ts:1384-1468) — 读文件 + 三档 fatal 分类.
     *
     * @param filePath     文件路径（错误消息用）
     * @param expandVars   是否展开 env
     * @param scope        scope 标签
     * @param envExpansion env 展开器
     * @param fileReader   文件读取函数（caller 注入 IO）；ENOENT 抛 {@link NoSuchFileException}
     * @return ParseResult
     */
    public static ParseResult parseMcpConfigFromFilePath(String filePath, boolean expandVars,
            String scope, EnvExpansion envExpansion, McpFileReader fileReader) {
        String content;
        try {
            content = fileReader.read(filePath);
        } catch (NoSuchFileException e) {
            return new ParseResult(Map.of(), List.of(new ParseError("",
                "MCP config file not found: " + filePath,
                "Check that the file path is correct", "fatal", scope, null)));
        } catch (IOException e) {
            log.error("[McpJsonConfigParser] read failed file={} scope={}: {}", filePath, scope, e.getMessage());
            return new ParseResult(Map.of(), List.of(new ParseError("",
                "Failed to read file: " + e.getMessage(),
                "Check file permissions and ensure the file exists", "fatal", scope, null)));
        }

        Object parsedJson;
        try {
            parsedJson = MAPPER.readValue(content, Object.class);
        } catch (IOException e) {
            log.error("[McpJsonConfigParser] not valid JSON file={} scope={}", filePath, scope);
            return new ParseResult(Map.of(), List.of(new ParseError("",
                "MCP config is not a valid JSON",
                "Fix the JSON syntax errors in the file", "fatal", scope, null)));
        }

        return parseMcpConfig(parsedJson, expandVars, scope, envExpansion);
    }

    /** 逐 server schema 校验，返回错误字段路径；合法返回 null。 */
    private static String validateServerSchema(Map<String, Object> server) {
        String type = typeOf(server);
        switch (type) {
            case "stdio": {
                // command 非空（types.ts:31 McpStdioServerConfigSchema）
                if (!(server.get("command") instanceof String cmd) || cmd.isEmpty()) {
                    return "command";
                }
                if (server.containsKey("args") && !(server.get("args") instanceof List<?>)) {
                    return "args";
                }
                if (server.containsKey("env") && !(server.get("env") instanceof Map<?, ?>)) {
                    return "env";
                }
                return null;
            }
            case "sse":
            case "http":
            case "ws": {
                if (!(server.get("url") instanceof String url) || url.isEmpty()) {
                    return "url";
                }
                return null;
            }
            case "sse-ide":
            case "ws-ide": {
                if (!(server.get("url") instanceof String url) || url.isEmpty()) return "url";
                if (!(server.get("ideName") instanceof String ide) || ide.isEmpty()) return "ideName";
                return null;
            }
            case "sdk": {
                if (!(server.get("name") instanceof String name) || name.isEmpty()) return "name";
                return null;
            }
            case "claudeai-proxy": {
                if (!(server.get("url") instanceof String url) || url.isEmpty()) return "url";
                if (!(server.get("id") instanceof String id) || id.isEmpty()) return "id";
                return null;
            }
            default:
                // 未知 type → union 失败（zod 使整个 config fatal）
                return "type";
        }
    }

    /** 取 server type，缺省 stdio（types.ts:30 backwards compatibility）。 */
    private static String typeOf(Map<String, Object> server) {
        Object type = server.get("type");
        if (type == null) return "stdio";
        return String.valueOf(type);
    }

    /** 逐 server env 展开（config.ts:556-616 expandEnvVars）→ 展开后的 server。 */
    private static Map<String, Object> expandEnvVars(Map<String, Object> server,
            EnvExpansion envExpansion, String name, String scope, List<ParseError> errors) {
        Map<String, Object> expanded = new LinkedHashMap<>(server);
        String type = typeOf(server);
        List<String> missing = new ArrayList<>();

        java.util.function.Function<Object, Object> expandValue = v -> {
            if (v instanceof String s) {
                EnvExpansion.ExpansionResult r = envExpansion.expand(s);
                missing.addAll(r.missingVars());
                return r.expanded();
            }
            return v;
        };

        if ("stdio".equals(type)) {
            if (expanded.get("command") instanceof String c) {
                EnvExpansion.ExpansionResult r = envExpansion.expand(c);
                missing.addAll(r.missingVars());
                expanded.put("command", r.expanded());
            }
            Object args = expanded.get("args");
            if (args instanceof List<?> list) {
                List<Object> expandedArgs = new ArrayList<>();
                for (Object a : list) expandedArgs.add(expandValue.apply(a));
                expanded.put("args", expandedArgs);
            }
            Object env = expanded.get("env");
            if (env instanceof Map<?, ?> m) {
                Map<Object, Object> expandedEnv = new LinkedHashMap<>();
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    expandedEnv.put(en.getKey(), expandValue.apply(en.getValue()));
                }
                expanded.put("env", expandedEnv);
            }
        } else if ("sse".equals(type) || "http".equals(type) || "ws".equals(type)) {
            if (expanded.get("url") instanceof String u) {
                EnvExpansion.ExpansionResult r = envExpansion.expand(u);
                missing.addAll(r.missingVars());
                expanded.put("url", r.expanded());
            }
            Object headers = expanded.get("headers");
            if (headers instanceof Map<?, ?> m) {
                Map<Object, Object> expandedHeaders = new LinkedHashMap<>();
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    expandedHeaders.put(en.getKey(), expandValue.apply(en.getValue()));
                }
                expanded.put("headers", expandedHeaders);
            }
        }
        // sse-ide / ws-ide / sdk / claudeai-proxy: 不展开（config.ts:600-609 expanded = config）

        if (!missing.isEmpty()) {
            List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(missing));
            errors.add(new ParseError("mcpServers." + name,
                "Missing environment variables: " + String.join(", ", unique),
                "Set the following environment variables: " + String.join(", ", unique),
                "warning", scope, name));
        }
        return expanded;
    }

    /** Windows npx 无 cmd /c 包装 warning（config.ts:1351-1369）。 */
    private static String windowsNpxWarning(Map<String, Object> server) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return null;
        String type = typeOf(server);
        if (!"stdio".equals(type)) return null;
        if (!(server.get("command") instanceof String cmd)) return null;
        if ("npx".equals(cmd) || cmd.endsWith("\npx") || cmd.endsWith("/npx")) {
            return "Windows requires 'cmd /c' wrapper to execute npx";
        }
        return null;
    }

    /** 把 Object/JsonNode 转成 Map&lt;String,Object&gt;（保持 LinkedHashMap 顺序）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toObjectMap(Object obj) {
        if (obj instanceof Map<?, ?>) {
            return (Map<String, Object>) obj;
        }
        if (obj instanceof JsonNode node) {
            if (!node.isObject()) return null;
            Map<String, Object> map = new LinkedHashMap<>();
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String k = names.next();
                map.put(k, node.get(k));
            }
            return map;
        }
        return null;
    }
}

