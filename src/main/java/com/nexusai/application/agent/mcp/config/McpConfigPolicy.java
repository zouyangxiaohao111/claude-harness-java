package com.nexusai.application.agent.mcp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 策略过滤 · 对齐 CC services/mcp/config.ts isMcpServerDenied /
 * isMcpServerAllowedByPolicy / filterMcpServersByPolicy / shouldAllowManagedMcpServersOnly
 * （config.ts:320-551）。
 *
 * <p>L1 语义: 纯函数，从策略条目（name / command 精确 / url wildcard）判断 server 是否
 * 被策略允许/拒绝。denylist 绝对优先（config.ts:421-424）；空 allowlist = 全拦
 * （config.ts:432-434）；command/url entry 存在时强制匹配（stdio 必须命中 command entry、
 * remote 必须命中 url entry，config.ts:446-489）；无 allowlist（null）= 全放行
 * （config.ts:427-429）。
 *
 * <p>L2 契约: filterMcpServersByPolicy 对 sdk 类型豁免（config.ts:544，SDK-managed
 * transport placeholder 不适用 URL/command 匹配）。
 */
public final class McpConfigPolicy {

    private static final Logger log = LoggerFactory.getLogger(McpConfigPolicy.class);

    private McpConfigPolicy() {
        // 工具类
    }

    /** 过滤结果 record. */
    public record FilterResult(
        Map<String, Map<String, Object>> allowed,
        List<String> blocked) {}

    /**
     * CC isMcpServerDenied (config.ts:364-408). name 精确 / command 数组精确 / url wildcard。
     * 无 deniedMcpServers（null）→ 不拒绝。
     */
    public static boolean isMcpServerDenied(String serverName,
            Map<String, Object> config, List<McpProperties.Entry> deniedList) {
        if (deniedList == null || deniedList.isEmpty()) {
            return false;
        }
        // name-based denial
        for (McpProperties.Entry entry : deniedList) {
            if (entry.serverName() != null && entry.serverName().equals(serverName)) {
                return true;
            }
        }
        if (config != null) {
            List<String> serverCommand = getServerCommandArray(config);
            if (serverCommand != null) {
                for (McpProperties.Entry entry : deniedList) {
                    if (entry.serverCommand() != null
                            && commandArraysMatch(entry.serverCommand(), serverCommand)) {
                        return true;
                    }
                }
            }
            String serverUrl = getServerUrl(config);
            if (serverUrl != null) {
                for (McpProperties.Entry entry : deniedList) {
                    if (entry.serverUrl() != null
                            && urlMatchesPattern(serverUrl, entry.serverUrl())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * CC isMcpServerAllowedByPolicy (config.ts:417-508). denylist 绝对优先；allowlist
     * null → 允许；空数组 → 全拦；有 command/url entry → 强制匹配，否则 name 匹配。
     */
    public static boolean isMcpServerAllowedByPolicy(String serverName,
            Map<String, Object> config, McpProperties.Policy policy) {
        List<McpProperties.Entry> denied = policy == null ? null : policy.deniedMcpServers();
        if (isMcpServerDenied(serverName, config, denied)) {
            return false;
        }
        List<McpProperties.Entry> allowed = policy == null ? null : policy.allowedMcpServers();
        if (allowed == null) {
            return true; // 无 allowlist 限制（config.ts:427-429）
        }
        if (allowed.isEmpty()) {
            return false; // 空 allowlist 全拦（config.ts:432-434）
        }
        boolean hasCommandEntries = allowed.stream()
            .anyMatch(e -> e.serverCommand() != null);
        boolean hasUrlEntries = allowed.stream()
            .anyMatch(e -> e.serverUrl() != null);

        if (config != null) {
            List<String> serverCommand = getServerCommandArray(config);
            String serverUrl = getServerUrl(config);
            if (serverCommand != null) {
                if (hasCommandEntries) {
                    // stdio server 必须命中任一 command entry（config.ts:448-458）
                    for (McpProperties.Entry entry : allowed) {
                        if (entry.serverCommand() != null
                                && commandArraysMatch(entry.serverCommand(), serverCommand)) {
                            return true;
                        }
                    }
                    return false;
                } else {
                    return nameMatch(allowed, serverName);
                }
            } else if (serverUrl != null) {
                if (hasUrlEntries) {
                    // remote server 必须命中任一 url entry（config.ts:470-480）
                    for (McpProperties.Entry entry : allowed) {
                        if (entry.serverUrl() != null
                                && urlMatchesPattern(serverUrl, entry.serverUrl())) {
                            return true;
                        }
                    }
                    return false;
                } else {
                    return nameMatch(allowed, serverName);
                }
            } else {
                return nameMatch(allowed, serverName);
            }
        }
        return nameMatch(allowed, serverName);
    }

    /**
     * CC filterMcpServersByPolicy (config.ts:536-551). sdk 类型豁免；其余按
     * isMcpServerAllowedByPolicy 过滤，被拦的返回 blocked 名单供告警。
     */
    public static FilterResult filterMcpServersByPolicy(
            Map<String, Map<String, Object>> configs, McpProperties.Policy policy) {
        Map<String, Map<String, Object>> allowed = new LinkedHashMap<>();
        List<String> blocked = new ArrayList<>();
        if (configs == null) {
            return new FilterResult(allowed, blocked);
        }
        for (Map.Entry<String, Map<String, Object>> e : configs.entrySet()) {
            Map<String, Object> config = e.getValue();
            Object type = config == null ? null : config.get("type");
            boolean isSdk = "sdk".equals(String.valueOf(type));
            if (isSdk || isMcpServerAllowedByPolicy(e.getKey(), config, policy)) {
                allowed.put(e.getKey(), config);
            } else {
                blocked.add(e.getKey());
            }
        }
        return new FilterResult(allowed, blocked);
    }

    /** CC shouldAllowManagedMcpServersOnly (config.ts:1485-1489). */
    public static boolean shouldAllowManagedMcpServersOnly(McpProperties.Policy policy) {
        return policy != null && policy.allowManagedMcpServersOnly();
    }

    // ---- 匹配 helpers（对齐 config.ts:137-162 / 320-334）----

    private static List<String> getServerCommandArray(Map<String, Object> config) {
        if (config == null) return null;
        Object type = config.get("type");
        if (type != null && !"stdio".equals(String.valueOf(type))) {
            return null; // 非 stdio 无 command
        }
        Object command = config.get("command");
        if (!(command instanceof String cmd)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        result.add(cmd);
        if (config.get("args") instanceof List<?> args) {
            for (Object a : args) result.add(String.valueOf(a));
        }
        return result;
    }

    private static String getServerUrl(Map<String, Object> config) {
        if (config == null || !config.containsKey("url")) return null;
        Object url = config.get("url");
        return url instanceof String s ? s : null;
    }

    private static boolean commandArraysMatch(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    /** URL wildcard pattern → RegExp（* → .*，其余转义）· config.ts:320-326. */
    static Pattern urlPatternToRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (SPECIAL_CHARS.indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    /** 需转义的正则特殊字符（config.ts:322 escaped set 减 *）. */
    private static final String SPECIAL_CHARS = ".+?^${}()|[]\\";

    private static boolean urlMatchesPattern(String url, String pattern) {
        return urlPatternToRegex(pattern).matcher(url).matches();
    }

    private static boolean nameMatch(List<McpProperties.Entry> entries, String serverName) {
        for (McpProperties.Entry entry : entries) {
            if (entry.serverName() != null && entry.serverName().equals(serverName)) {
                return true;
            }
        }
        return false;
    }
}
