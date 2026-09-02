package com.nexusai.application.agent.mcp;

import java.net.URI;

/**
 * MCP URL 规范化工具 · 对齐 CC JS {@code URL.href} 语义（RFC 3986 §6.2.2 syntax-based normalization）。
 *
 * <p>[impl-I-4 T8] 两份 CC 副本各自 normalizeUrl（官方 registry 与 XAA）：
 * <ul>
 *   <li>JS {@code new URL().href} 语义：scheme+host 小写 + 剥默认端口(:80/:443) +
 *       保留 userinfo + 保留 fragment；路径以 {@code /} 起始（空路径也补根斜杠）。</li>
 *   <li>{@code officialRegistry.ts:19-27}：{@code u.search=''}（去 query）+ {@code .replace(/\/$/,'')}
 *       （strip 尾 {@code /}）；解析失败 → undefined（Java null）。</li>
 *   <li>{@code xaa.ts:61-67}：{@code new URL(url).href.replace(/\/$/,'')}（<b>不去 query</b>）；
 *       解析失败 → {@code url.replace(/\/$/,'')}（原样 strip 尾 {@code /}）。</li>
 * </ul>
 * 两语义<b>不强行合一</b>（规则七）：失败分支差异由调用方各自选择方法实现。
 *
 * <p>Java 端 {@link URI} 不提供 JS URL.href 的自动规范化 → 手工重建
 * {@code scheme://userinfo@host[:port]/path[?query][#fragment]}。
 */
public final class McpUrlNormalizer {

    private McpUrlNormalizer() {
        // 工具类
    }

    /**
     * Official MCP registry 语义 · 对齐 CC {@code officialRegistry.ts:19-27 normalizeUrl}：
     * 去 query + strip 尾 {@code /}；解析失败 → null。
     *
     * @param url 原始 URL
     * @return 规范化 URL；解析失败 / null 输入返回 null
     */
    public static String normalizeOfficial(String url) {
        if (url == null) {
            return null;
        }
        try {
            return stripTrailingSlash(normalizeInternal(url, true));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * XAA 语义 · 对齐 CC {@code xaa.ts:61-67 normalizeUrl}：
     * {@code new URL(url).href.replace(/\/$/,'')}（保留 query）；解析失败 →
     * {@code url.replace(/\/$/,'')}（原样 strip 尾 {@code /}）。
     *
     * @param url 原始 URL
     * @return 规范化 URL；解析失败返回 strip 尾 {@code /} 的原串
     */
    public static String normalizeXaa(String url) {
        if (url == null) {
            return "";
        }
        try {
            return stripTrailingSlash(normalizeInternal(url, false));
        } catch (Exception e) {
            return stripTrailingSlash(url);
        }
    }

    /**
     * 重建 JS {@code URL.href} 语义（RFC 3986 §6.2.2）：scheme+host 小写 + 剥默认端口 +
     * 保留 userinfo + 路径补根斜杠 + 可选去 query + 保留 fragment。
     *
     * @param url       原始 URL
     * @param stripQuery true = 去 query（officialRegistry {@code u.search=''}）；false = 保留（xaa）
     * @return 规范化 URL（未 strip 尾斜杠，由调用方各自处理）
     */
    private static String normalizeInternal(String url, boolean stripQuery) {
        URI u = URI.create(url);
        String scheme = u.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("URL has no scheme: " + url);
        }
        String schemeLower = scheme.toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append(schemeLower).append("://");
        if (u.getRawUserInfo() != null) {
            sb.append(u.getRawUserInfo()).append('@');
        }
        if (u.getHost() != null) {
            sb.append(u.getHost().toLowerCase());
            int port = u.getPort();
            if (port != -1 && !isDefaultPort(schemeLower, port)) {
                sb.append(':').append(port);
            }
        }
        // JS URL.href 空路径补根斜杠
        String rawPath = u.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            sb.append('/');
        } else {
            sb.append(rawPath);
        }
        if (!stripQuery && u.getRawQuery() != null) {
            sb.append('?').append(u.getRawQuery());
        }
        if (u.getRawFragment() != null) {
            sb.append('#').append(u.getRawFragment());
        }
        return sb.toString();
    }

    /** 默认端口判定（JS URL 剥 http:80 / https:443）。 */
    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    /** CC {@code .replace(/\/$/,'')} 等价 — 仅剥单个尾斜杠。 */
    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
