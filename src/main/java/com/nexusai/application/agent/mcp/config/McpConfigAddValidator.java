package com.nexusai.application.agent.mcp.config;

import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * CC {@code claude mcp add} 校验链 a~g（addMcpConfig config.ts:625-761）落地。
 *
 * <p>校验链顺序（与 CC 严格一致）：
 * <ol>
 *   <li><b>a</b> 名字字符类拒绝（config.ts:630-634，原始名含 {@code [^a-zA-Z0-9_-]} 即拒）</li>
 *   <li><b>b</b> 保留名拦截（config.ts:637-648，基于归一化名；nexusai-in-chrome 恒拦，
 *       computer-use 受 CHICAGO_MCP feature 门控）</li>
 *   <li><b>c</b> enterprise 独占短路（config.ts:651-655，managed-mcp.json 存在即拒）</li>
 *   <li><b>d</b> schema 校验（config.ts:658-665，McpServerConfigSchema 8 变体 union）</li>
 *   <li><b>e</b> denylist（config.ts:668-672）</li>
 *   <li><b>f</b> allowlist（config.ts:675-679）</li>
 *   <li><b>g</b> 按 scope 重复检查（config.ts:681-710）+ Java 特有 DB 守卫（防 UNIQUE 500）</li>
 * </ol>
 *
 * <p>全部文案逐字对齐 §1-cc-spec §9 错误文案表。状态码映射：ValidationException → 400
 * （GlobalExceptionHandler.java:50-54）、ConflictException → 409（:41-48）。
 *
 * <p>保留名判定<b>基于归一化名</b>（CC normalization.ts:17-23 + isClaudeInChromeMCPServer
 * claudeInChrome/common.ts:411-413 + isComputerUseMCPServer computerUse/common.ts:65-67）：
 * {@code normalizeNameForMCP(name) === 保留名}。注意 normalize 把非法字符替换为 {@code _}，
 * 故 {@code "claude in chrome"}（空格）归一化为 {@code "claude_in_chrome"} <b>≠</b>
 * {@code "nexusai-in-chrome"}（连字符）→ <b>不命中</b>保留名（自验 CC 源码，计划 §2.3 的
 * 「claude in chrome 也命中」为误判，见 mcp-add-align E1 报告）。
 */
@Component
public class McpConfigAddValidator {

    private static final Logger log = LoggerFactory.getLogger(McpConfigAddValidator.class);

    /** ConfigScopeSchema 合法 7 值（types.ts:10-20）。 */
    public static final List<String> CONFIG_SCOPES =
        List.of("local", "user", "project", "dynamic", "enterprise", "claudeai", "managed");

    /** ensureTransport 合法 3 值（utils.ts:304-314）。 */
    public static final List<String> TRANSPORTS = List.of("stdio", "sse", "http");

    /** McpServerConfigSchema 8 传输 type（types.ts:124-135）。 */
    static final List<String> ALL_TRANSPORTS = List.of(
        "stdio", "sse", "sse-ide", "ws-ide", "http", "ws", "sdk", "claudeai-proxy");

    /** 保留名常量 · claudeInChrome/common.ts:12 + computerUse/common.ts:4（CC 原值 'claude-in-chrome'，品牌改名 → 'nexusai-in-chrome'）。 */
    static final String NEXUSAI_IN_CHROME_RESERVED = "nexusai-in-chrome";
    static final String COMPUTER_USE_RESERVED = "computer-use";

    private final McpEnterpriseConfig enterpriseConfig;
    private final McpProperties mcpProperties;

    /** 名字非法字符检测 · CC name.match(/[^a-zA-Z0-9_-]/)（config.ts:630）。 */
    private static final Pattern INVALID_NAME_CHAR = Pattern.compile("[^a-zA-Z0-9_-]");

    public McpConfigAddValidator(
            @Autowired(required = false) McpEnterpriseConfig enterpriseConfig,
            @Autowired(required = false) McpProperties mcpProperties) {
        this.enterpriseConfig = enterpriseConfig;
        this.mcpProperties = mcpProperties;
    }

    // ── scope / transport 解析（CC ensureConfigScope / ensureTransport） ──

    /**
     * CC ensureConfigScope（utils.ts:292-302）。REST 缺省 <b>project</b>（AC-1.3 显式偏离
     * CC CLI 缺省 local），非法值 → 400 文案逐字对齐。
     */
    public String ensureConfigScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        if (!CONFIG_SCOPES.contains(scope)) {
            throw new ValidationException(
                "Invalid scope: " + scope + ". Must be one of: " + String.join(", ", CONFIG_SCOPES));
        }
        return scope;
    }

    /** CC ensureTransport（utils.ts:304-314）。缺省 stdio，非法 → 400 文案逐字对齐。 */
    public String ensureTransport(String type) {
        if (type == null || type.isBlank()) {
            return "stdio";
        }
        if (!TRANSPORTS.contains(type)) {
            throw new ValidationException(
                "Invalid transport type: " + type + ". Must be one of: stdio, sse, http");
        }
        return type;
    }

    // ── XAA add-time fail-fast（addCommand.ts:103-122） ──

    /**
     * CC add-time XAA fail-fast（addCommand.ts:103-122）：
     * {@code if (options.xaa && !isXaaEnabled()) cliError('Error: --xaa requires CLAUDE_CODE_ENABLE_XAA=1 in your environment')}，
     * 随后 {@code if (xaa)} 逐项查缺 {@code --client-id} / {@code --client-secret} /
     * {@code settings.xaaIdp}（getXaaIdpSettings）。
     *
     * <p>缺省生产 feature 关（CC isXaaEnabled = isEnvTruthy(CLAUDE_CODE_ENABLE_XAA)，
     * env 未设 → false）→ xaa=true 一律 400（文案对齐）。Java 无 settings.xaaIdp
     * 基础设施（Q-07 已删 Xaa/XaaIdpLogin，McpTypesRegistry 声明恒 unused）→
     * {@code xaaIdpConfigured} 恒 false → feature 开时仍会因「xaa setup 未配置」拒绝。
     *
     * @param xaa              oauth.xaa 值（CC options.xaa）
     * @param xaaEnabled       isXaaEnabled() 等价（CLAUDE_CODE_ENABLE_XAA env truthy，生产缺省 false）
     * @param hasClientId      --client-id 是否提供
     * @param hasClientSecret  --client-secret 是否提供
     * @param xaaIdpConfigured settings.xaaIdp 是否已配置（Java 无此设置 → 调用方传恒 false）
     */
    public void validateXaaFailFast(Boolean xaa, boolean xaaEnabled, boolean hasClientId,
            boolean hasClientSecret, boolean xaaIdpConfigured) {
        if (!Boolean.TRUE.equals(xaa)) {
            return;
        }
        if (!xaaEnabled) {
            throw new ValidationException(
                "Error: --xaa requires CLAUDE_CODE_ENABLE_XAA=1 in your environment");
        }
        List<String> missing = new ArrayList<>();
        if (!hasClientId) {
            missing.add("--client-id");
        }
        if (!hasClientSecret) {
            missing.add("--client-secret");
        }
        if (!xaaIdpConfigured) {
            missing.add("'claude mcp xaa setup' (settings.xaaIdp not configured)");
        }
        if (!missing.isEmpty()) {
            throw new ValidationException("Error: --xaa requires: " + String.join(", ", missing));
        }
    }

    // ── a. 名字字符类拒绝（config.ts:630-634） ──

    /** 原始名（非归一化）含非 [A-Za-z0-9_-] 字符即拒绝 · 文案逐字对齐。 */
    public void validateName(String name) {
        if (name == null || INVALID_NAME_CHAR.matcher(name).find()) {
            throw new ValidationException(
                "Invalid name " + name + ". Names can only contain letters, numbers, hyphens, and underscores.");
        }
    }

    // ── b. 保留名拦截（config.ts:637-648） ──

    /**
     * 保留名判定基于归一化名（normalization.ts:17-23）。nexusai-in-chrome 恒拦；
     * computer-use 受 CHICAGO_MCP feature 门控（生产默认 false，Java 由调用方注入 gate）。
     */
    public void validateReserved(String name, boolean computerUseReservedEnabled) {
        if (name == null) {
            return;
        }
        String normalized = McpStringUtils.normalizeNameForMCP(name);
        if (NEXUSAI_IN_CHROME_RESERVED.equals(normalized)) {
            throw new ConflictException("Cannot add MCP server \"" + name + "\": this name is reserved.");
        }
        if (computerUseReservedEnabled && COMPUTER_USE_RESERVED.equals(normalized)) {
            throw new ConflictException("Cannot add MCP server \"" + name + "\": this name is reserved.");
        }
    }

    // ── c. enterprise 独占短路（config.ts:651-655） ──

    /** managed-mcp.json 存在（config !== null）→ 409 + CC 文案。 */
    public void validateEnterprise() {
        if (enterpriseConfig != null && enterpriseConfig.doesEnterpriseMcpConfigExist()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpConfigAddValidator] enterprise MCP 独占 → 拒绝 add（CC config.ts:651-655）");
            }
            throw new ConflictException(
                "Cannot add MCP server: enterprise MCP configuration is active and has exclusive control over MCP servers");
        }
    }

    // ── d. schema 校验（config.ts:658-665 + types.ts:124-135） ──

    /**
     * McpServerConfigSchema().safeParse 对齐。失败抛 {@code Invalid configuration: <issues>}，
     * issues = {@code issues.map(e => e.path.join('.') + ': ' + e.message).join(', ')}。
     *
     * <p>zod 缺省「strip 未知键」：返回归一化后的 config（仅含匹配分支的键；stdio args 缺省
     * {@code []}），与 CC {@code validatedConfig = result.data} 一致。
     *
     * <p>callbackPort NaN 丢弃发生在 Service 层（DTO→config 构造时 parseInt 失败静默置空，
     * 门禁修正 1），本方法只见已归一化数值；非正整数（-5/3.5）在此报 schema 错。
     */
    public Map<String, Object> validateSchema(Map<String, Object> config) {
        if (config == null) {
            throw new ValidationException("Invalid configuration: ");
        }
        String type = config.get("type") == null ? "stdio" : String.valueOf(config.get("type"));
        List<String> issues = new ArrayList<>();
        switch (type) {
            case "stdio" -> {
                Object cmd = config.get("command");
                if (!config.containsKey("command")) {
                    issues.add("command: Required");
                } else if (!(cmd instanceof String s)) {
                    issues.add("command: Expected string, received " + describe(cmd));
                } else if (s.isEmpty()) {
                    issues.add("command: Command cannot be empty");
                }
                Object args = config.get("args");
                if (args != null && !(args instanceof List<?>)) {
                    issues.add("args: Expected array, received " + describe(args));
                }
                Object env = config.get("env");
                if (env != null && !(env instanceof Map<?, ?>)) {
                    issues.add("env: Expected record, received " + describe(env));
                }
            }
            case "sse", "http", "ws" -> {
                if (!config.containsKey("url")) {
                    issues.add("url: Required");
                } else if (!(config.get("url") instanceof String)) {
                    issues.add("url: Expected string, received " + describe(config.get("url")));
                }
                Object headers = config.get("headers");
                if (headers != null && !(headers instanceof Map<?, ?>)) {
                    issues.add("headers: Expected record, received " + describe(headers));
                }
                Object headersHelper = config.get("headersHelper");
                if (headersHelper != null && !(headersHelper instanceof String)) {
                    issues.add("headersHelper: Expected string, received " + describe(headersHelper));
                }
                validateOauth(config.get("oauth"), issues);
            }
            case "sse-ide", "ws-ide" -> {
                if (!config.containsKey("url")) {
                    issues.add("url: Required");
                } else if (!(config.get("url") instanceof String)) {
                    issues.add("url: Expected string, received " + describe(config.get("url")));
                }
                if (!(config.get("ideName") instanceof String ide) || ide.isEmpty()) {
                    issues.add("ideName: Required");
                }
            }
            case "sdk" -> {
                if (!(config.get("name") instanceof String n) || n.isEmpty()) {
                    issues.add("name: Required");
                }
            }
            case "claudeai-proxy" -> {
                if (!config.containsKey("url")) {
                    issues.add("url: Required");
                } else if (!(config.get("url") instanceof String)) {
                    issues.add("url: Expected string, received " + describe(config.get("url")));
                }
                if (!(config.get("id") instanceof String id) || id.isEmpty()) {
                    issues.add("id: Required");
                }
            }
            default -> issues.add("type: Invalid enum value. Expected 'stdio' | 'sse' | 'sse-ide' | "
                + "'ws-ide' | 'http' | 'ws' | 'sdk' | 'claudeai-proxy', received " + type);
        }
        if (!issues.isEmpty()) {
            throw new ValidationException("Invalid configuration: " + String.join(", ", issues));
        }
        // zod strip 未知键 + stdio args 缺省 []（types.ts:31-33）
        Map<String, Object> validated = new LinkedHashMap<>();
        switch (type) {
            case "stdio" -> {
                if (config.containsKey("type")) validated.put("type", "stdio");
                validated.put("command", config.get("command"));
                Object args = config.get("args");
                validated.put("args", args == null ? List.of() : args);
                if (config.containsKey("env")) validated.put("env", config.get("env"));
            }
            case "sse", "http", "ws" -> {
                validated.put("type", type);
                validated.put("url", config.get("url"));
                if (config.containsKey("headers")) validated.put("headers", config.get("headers"));
                if (config.containsKey("headersHelper")) validated.put("headersHelper", config.get("headersHelper"));
                if (config.containsKey("oauth")) validated.put("oauth", config.get("oauth"));
            }
            case "sse-ide", "ws-ide" -> {
                validated.put("type", type);
                validated.put("url", config.get("url"));
                validated.put("ideName", config.get("ideName"));
                if (config.containsKey("ideRunningInWindows")) {
                    validated.put("ideRunningInWindows", config.get("ideRunningInWindows"));
                }
            }
            case "sdk" -> {
                validated.put("type", "sdk");
                validated.put("name", config.get("name"));
            }
            case "claudeai-proxy" -> {
                validated.put("type", "claudeai-proxy");
                validated.put("url", config.get("url"));
                validated.put("id", config.get("id"));
            }
            default -> { /* unreachable（上面已 throw） */ }
        }
        return validated;
    }

    /** McpOAuthConfigSchema（types.ts:43-56）：clientId?/callbackPort?(int>0)/authServerMetadataUrl?(https)/xaa?(bool)。 */
    private void validateOauth(Object oauthObj, List<String> issues) {
        if (oauthObj == null) {
            return;
        }
        if (!(oauthObj instanceof Map<?, ?> oauth)) {
            issues.add("oauth: Expected object, received " + describe(oauthObj));
            return;
        }
        Object cb = oauth.get("callbackPort");
        if (cb != null) {
            if (!(cb instanceof Number n)) {
                issues.add("oauth.callbackPort: Expected number, received " + describe(cb));
            } else {
                double d = n.doubleValue();
                if (d != Math.floor(d)) {
                    issues.add("oauth.callbackPort: Expected integer, received " + trimNum(d));
                } else if (d <= 0) {
                    issues.add("oauth.callbackPort: Number must be greater than 0");
                }
            }
        }
        Object authUrl = oauth.get("authServerMetadataUrl");
        if (authUrl != null) {
            if (!(authUrl instanceof String s)) {
                issues.add("oauth.authServerMetadataUrl: Invalid url");
            } else if (!s.startsWith("https://")) {
                issues.add("oauth.authServerMetadataUrl: authServerMetadataUrl must use https://");
            } else if (!isValidUrl(s)) {
                issues.add("oauth.authServerMetadataUrl: Invalid url");
            }
        }
        Object xaa = oauth.get("xaa");
        if (xaa != null && !(xaa instanceof Boolean)) {
            issues.add("oauth.xaa: Expected boolean, received " + describe(xaa));
        }
    }

    // ── e/f. denylist / allowlist（config.ts:668-679，判据复用 McpConfigPolicy） ──

    /**
     * denylist（e）→ allowlist（f）严格顺序（config.ts:658-679）。策略源 =
     * {@code mcpProperties.policy()}（缺省 null = 全放行，对齐 CC config.ts:427-429；
     * 空 allowlist = 全拦 :432-434，判据已在 McpConfigPolicy）。
     */
    public void validatePolicy(String name, Map<String, Object> config) {
        McpProperties.Policy policy = mcpProperties == null ? null : mcpProperties.policy();
        List<McpProperties.Entry> denied = policy == null ? null : policy.deniedMcpServers();
        if (McpConfigPolicy.isMcpServerDenied(name, config, denied)) {
            throw new ConflictException(
                "Cannot add MCP server \"" + name + "\": server is explicitly blocked by enterprise policy");
        }
        if (!McpConfigPolicy.isMcpServerAllowedByPolicy(name, config, policy)) {
            throw new ConflictException(
                "Cannot add MCP server \"" + name + "\": not allowed by enterprise policy");
        }
    }

    // ── g. 按 scope 重复检查（config.ts:681-710）+ DB 守卫 ──

    /**
     * 按 scope 重复检查 + Java 特有 DB 守卫。
     *
     * @param name             server 名
     * @param scope            写目标 scope
     * @param targetFileServers 目标 scope 文件已有 servers（project → .mcp.json；user → .nexusai.json 顶层）
     * @param existingDbRow    DB 中同名行（null = 无）；update 排除自身时传 null
     */
    public void checkDuplicate(String name, String scope, Map<String, Map<String, Object>> targetFileServers,
            McpServerRecord existingDbRow) {
        switch (scope) {
            case "project" -> {
                if (targetFileServers != null && targetFileServers.containsKey(name)) {
                    throw new ConflictException("MCP server " + name + " already exists in .mcp.json");
                }
            }
            case "user" -> {
                if (targetFileServers != null && targetFileServers.containsKey(name)) {
                    throw new ConflictException("MCP server " + name + " already exists in user config");
                }
            }
            case "local" -> {
                if (targetFileServers != null && targetFileServers.containsKey(name)) {
                    throw new ConflictException("MCP server " + name + " already exists in local config");
                }
            }
            case "dynamic" -> throw new ConflictException("Cannot add MCP server to scope: dynamic");
            case "enterprise" -> throw new ConflictException("Cannot add MCP server to scope: enterprise");
            case "claudeai" -> throw new ConflictException("Cannot add MCP server to scope: claudeai");
            default -> throw new ConflictException("Cannot add MCP server to scope: " + scope); // managed
        }
        // Java 特有守卫：DB name UNIQUE（V1__init_schema.sql:88）→ 防 DataIntegrityViolation 500
        if (existingDbRow != null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpConfigAddValidator] DB 已有同名 server={} → 409（防 UNIQUE 500）", name);
            }
            throw new ConflictException("MCP server " + name + " already exists in DB");
        }
    }

    // ── helpers ──

    private static String describe(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return "\"" + s + "\"";
        }
        return o.getClass().getSimpleName();
    }

    private static String trimNum(double d) {
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static boolean isValidUrl(String s) {
        try {
            java.net.URI uri = java.net.URI.create(s);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
