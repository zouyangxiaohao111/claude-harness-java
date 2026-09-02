package com.nexusai.application.agent.mcp.config;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server 去重 + 签名 · 对齐 CC services/mcp/config.ts getMcpServerSignature /
 * unwrapCcrProxyUrl / dedupPluginMcpServers / dedupClaudeAiMcpServers（config.ts:171-310）。
 *
 * <p>L1 语义: 两个签名相同的 server 视为"同一个底层进程/连接"。plugin server 键是
 * {@code plugin:name:server}，永远不会与 manual server 键冲突——本类用内容签名
 * 捕获"二者实际启动同一进程/连接"的情况。claude.ai connector 键是
 * {@code claude.ai &lt;DisplayName&gt;}，同理不冲突，靠 URL 签名去重。
 *
 * <p>L2 契约:
 * <ul>
 *   <li><b>getMcpServerSignature</b>: stdio → {@code stdio:[command,...args]}（jsonStringify 无空格）；
 *       url → {@code url:unwrapCcrProxyUrl(url)}；sdk（无 command 无 url）→ null。</li>
 *   <li><b>unwrapCcrProxyUrl</b>: URL 含 {@code /v2/session_ingress/shttp/mcp/} 或
 *       {@code /v2/ccr-sessions/} → 取 {@code mcp_url} query 还原原 vendor URL；否则原样返回。</li>
 *   <li><b>dedupPluginMcpServers</b>: manual 优先于 plugin；plugin 间 first-loaded 优先；
 *       sdk（签名 null）恒保留。</li>
 *   <li><b>dedupClaudeAiMcpServers</b>: 去重目标仅 enabled manual（disabled manual 不抑制
 *       connector，config.ts:278-280）。</li>
 * </ul>
 */
public final class McpConfigDedup {

    private static final Logger log = LoggerFactory.getLogger(McpConfigDedup.class);

    /** CCR 代理 URL path markers · CC config.ts:171-174. */
    public static final List<String> CCR_PROXY_PATH_MARKERS = List.of(
        "/v2/session_ingress/shttp/mcp/", "/v2/ccr-sessions/");

    private McpConfigDedup() {
        // 工具类
    }

    /** 被抑制的 server 记录（name = 被抑制者，duplicateOf = 它重复谁）. */
    public record Suppressed(String name, String duplicateOf) {}

    /** 去重结果 record. */
    public record DedupResult(
        Map<String, Map<String, Object>> servers,
        List<Suppressed> suppressed) {
        public DedupResult {
            servers = servers == null ? Map.of() : servers;
            suppressed = suppressed == null ? List.of() : suppressed;
        }
    }

    /**
     * CC getMcpServerSignature (config.ts:202-212). 忽略 env/headers（同 URL = 同 server）。
     * 仅当既无 command 又无 url（sdk 类型）时返回 null。
     */
    public static String getMcpServerSignature(Map<String, Object> config) {
        List<String> cmd = getServerCommandArray(config);
        if (cmd != null) {
            return "stdio:" + jsonStringifyArray(cmd);
        }
        String url = getServerUrl(config);
        if (url != null) {
            return "url:" + unwrapCcrProxyUrl(url);
        }
        return null;
    }

    /** 取 stdio command 数组（非 stdio 类型返回 null）· CC config.ts:137-144. */
    private static List<String> getServerCommandArray(Map<String, Object> config) {
        if (config == null) return null;
        Object type = config.get("type");
        if (type != null && !"stdio".equals(String.valueOf(type))) {
            return null;
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

    /** 取 server url（远程类型）· CC config.ts:160-162 getServerUrl. */
    private static String getServerUrl(Map<String, Object> config) {
        if (config == null || !config.containsKey("url")) return null;
        Object url = config.get("url");
        return url instanceof String s ? s : null;
    }

    /**
     * CC unwrapCcrProxyUrl (config.ts:182-193). CCR 代理 URL → 从 mcp_url query 还原
     * 原 vendor URL；非代理 URL 原样返回。
     */
    public static String unwrapCcrProxyUrl(String url) {
        if (url == null) return null;
        boolean isProxy = CCR_PROXY_PATH_MARKERS.stream().anyMatch(url::contains);
        if (!isProxy) return url;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx <= 0) continue;
                    String key = pair.substring(0, idx);
                    if ("mcp_url".equals(key)) {
                        return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name());
                    }
                }
            }
            return url;
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            log.warn("[McpConfigDedup] unwrapCcrProxyUrl failed url={}: {}", url, e.getMessage());
            return url;
        }
    }

    /**
     * CC dedupPluginMcpServers (config.ts:223-266). Manual 优先于 plugin；plugin 间
     * first-loaded 优先；被抑制的 server 从结果中剔除并记录。
     */
    public static DedupResult dedupPluginMcpServers(
            Map<String, Map<String, Object>> pluginServers,
            Map<String, Map<String, Object>> manualServers) {
        Map<String, String> manualSigs = new LinkedHashMap<>();
        if (manualServers != null) {
            for (Map.Entry<String, Map<String, Object>> e : manualServers.entrySet()) {
                String sig = getMcpServerSignature(e.getValue());
                if (sig != null && !manualSigs.containsKey(sig)) {
                    manualSigs.put(sig, e.getKey());
                }
            }
        }

        Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
        List<Suppressed> suppressed = new ArrayList<>();
        Map<String, String> seenPluginSigs = new LinkedHashMap<>();

        if (pluginServers != null) {
            for (Map.Entry<String, Map<String, Object>> e : pluginServers.entrySet()) {
                String name = e.getKey();
                String sig = getMcpServerSignature(e.getValue());
                if (sig == null) {
                    servers.put(name, e.getValue());
                    continue;
                }
                String manualDup = manualSigs.get(sig);
                if (manualDup != null) {
                    log.debug("[McpConfigDedup] 抑制 plugin server \"{}\": 重复 manual \"{}\"", name, manualDup);
                    suppressed.add(new Suppressed(name, manualDup));
                    continue;
                }
                String pluginDup = seenPluginSigs.get(sig);
                if (pluginDup != null) {
                    log.debug("[McpConfigDedup] 抑制 plugin server \"{}\": 重复更早 plugin \"{}\"", name, pluginDup);
                    suppressed.add(new Suppressed(name, pluginDup));
                    continue;
                }
                seenPluginSigs.put(sig, name);
                servers.put(name, e.getValue());
            }
        }
        return new DedupResult(servers, suppressed);
    }

    /**
     * CC dedupClaudeAiMcpServers (config.ts:281-310). 去重目标仅 enabled manual server
     * （disabled manual 不抑制 connector，否则两边都不跑）。
     *
     * @param isMcpServerDisabled 启停判定（对齐 CC isMcpServerDisabled config.ts:1528-1536）
     */
    public static DedupResult dedupClaudeAiMcpServers(
            Map<String, Map<String, Object>> claudeAiServers,
            Map<String, Map<String, Object>> manualServers,
            Predicate<String> isMcpServerDisabled) {
        Map<String, String> manualSigs = new LinkedHashMap<>();
        if (manualServers != null) {
            for (Map.Entry<String, Map<String, Object>> e : manualServers.entrySet()) {
                if (isMcpServerDisabled != null && isMcpServerDisabled.test(e.getKey())) {
                    continue;
                }
                String sig = getMcpServerSignature(e.getValue());
                if (sig != null && !manualSigs.containsKey(sig)) {
                    manualSigs.put(sig, e.getKey());
                }
            }
        }

        Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
        List<Suppressed> suppressed = new ArrayList<>();
        if (claudeAiServers != null) {
            for (Map.Entry<String, Map<String, Object>> e : claudeAiServers.entrySet()) {
                String sig = getMcpServerSignature(e.getValue());
                String manualDup = sig != null ? manualSigs.get(sig) : null;
                if (manualDup != null) {
                    log.debug("[McpConfigDedup] 抑制 claude.ai connector \"{}\": 重复 manual \"{}\"", e.getKey(), manualDup);
                    suppressed.add(new Suppressed(e.getKey(), manualDup));
                    continue;
                }
                servers.put(e.getKey(), e.getValue());
            }
        }
        return new DedupResult(servers, suppressed);
    }

    /** JSON stringify 字符串数组（无空格）· 对齐 CC jsonStringify(cmd). */
    private static String jsonStringifyArray(List<String> cmd) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonString(cmd.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
