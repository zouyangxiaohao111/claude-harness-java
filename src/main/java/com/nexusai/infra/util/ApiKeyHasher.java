package com.nexusai.infra.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * API key 工具：SHA-256 hash + 脱敏显示
 *
 * <p>脱敏规则（v1）：
 * <ul>
 *   <li>key 长度 ≤ 7：只显示前 2 + **** + 后 1（如 "ab****x"）</li>
 *   <li>key 长度 > 7：显示前 3 + **** + 后 4（如 "sk-****4f2a"）</li>
 *   <li>key 长度 ≤ 4：全 ****（避免过短 key 完全暴露）</li>
 * </ul>
 *
 * <p>hash 用 SHA-256（无盐，因为 key 本身就是 secret，再加盐没用）。
 * 唯一目的是数据库泄露时不直接暴露明文 key。
 */
public final class ApiKeyHasher {

    /**
     * 掩码标记（4 个星号）。读接口只回 {@link #mask(String)} 的结果，写接口据此识别
     * 「前端把掩码原样回传」= 用户没改 ⇒ 不覆盖已存明文。
     *
     * <p>⛔ 这不是「长度像不像」的猜测：真实 API key 不含 4 连星号，而掩码必然含。
     */
    public static final String MASK_TOKEN = "****";

    private ApiKeyHasher() {}

    /**
     * 是否形如掩码（含 {@link #MASK_TOKEN}）。
     *
     * <p>用于写接口的<b>掩码哨兵</b>：GET 不回明文后，任何把 DTO 原样回传的调用方都会带上
     * {@code sk-****abcd}；若照单全收就把真 key 覆盖成掩码（历史 bug）。
     */
    public static boolean isMasked(String value) {
        return value != null && value.contains(MASK_TOKEN);
    }

    public static String hash(String plainKey) {
        if (plainKey == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(plainKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 只保留末四位的统一掩码（{@code ****abcd}）· <b>不保留任何前缀</b>。
     *
     * <p>⛔ 不要用 {@link #mask(String)} 做「响应体不得出现 {@code sk-}」的场景：那个保留前 3 位，
     * {@code sk-} 开头的 key 等于把前缀回传出去。
     *
     * <p>长度 &lt; 8 → 整体 {@code ****}（末四位占 key 太大比例时等于没脱敏）。
     */
    public static String maskTail(String plainKey) {
        return maskSecret(plainKey);
    }

    /**
     * 统一标量掩码：保留末四位（{@code ****abcd}）；长度 &lt; 8 → 整体 {@code ****}。
     *
     * <p>provider 自定义 header / MCP env/headers / settings.proxy 口令 / settings.apiKey
     * 共用这一条，⛔ 不要各写一套。
     */
    public static String maskSecret(String plainValue) {
        if (plainValue == null || plainValue.isEmpty()) return MASK_TOKEN;
        int len = plainValue.length();
        if (len < 8) return MASK_TOKEN;
        return MASK_TOKEN + plainValue.substring(len - 4);
    }

    /**
     * 该名字是否属于「敏感键」（大小写不敏感）。
     *
     * <p>规则（用户 2026-11-22 裁定）：后缀 {@code KEY} / {@code TOKEN} / {@code SECRET} /
     * {@code PASSWORD}，或精确等于 {@code AUTHORIZATION}。{@code X-API-KEY}、
     * {@code ACCESS_TOKEN}、{@code CLIENT_SECRET}、{@code DB_PASSWORD} 都由后缀命中。
     *
     * <p>只按名字判：名字不敏感的键（如 {@code LOG_LEVEL}）<b>原样返回</b>，
     * 避免把 MCP env 的普通配置也糊成掩码导致无法编辑。
     */
    public static boolean isSensitiveName(String name) {
        if (name == null) return false;
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.isEmpty()) return false;
        if ("AUTHORIZATION".equals(n)) return true;
        return n.endsWith("KEY")
            || n.endsWith("TOKEN")
            || n.endsWith("SECRET")
            || n.endsWith("PASSWORD");
    }

    /**
     * 读侧：只掩密码敏感键的值，其余键原样。
     *
     * <p>空值保持空（界面能看出「没配」）；null map → null；顺序保持不变（LinkedHashMap）。
     */
    public static Map<String, String> maskSensitiveMap(Map<String, String> entries) {
        if (entries == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (isSensitiveName(name) && value != null && !value.isEmpty()) {
                out.put(name, maskSecret(value));
            } else {
                out.put(name, value);
            }
        }
        return out;
    }

    /**
     * 写侧哨兵：入参是否「掩码回传」（= 用户没改这一项）。
     *
     * <p>两个显式判据：①含 {@link #MASK_TOKEN}；②等于「当前库中值的统一掩码」。
     * ⛔ 不看长度/字符集。
     */
    public static boolean isMaskedSentinel(String incoming, String storedPlain) {
        if (incoming == null) return false;
        if (isMasked(incoming)) return true;
        return storedPlain != null && !storedPlain.isBlank()
            && incoming.equals(maskSecret(storedPlain));
    }

    /**
     * 写侧：map 级哨兵合并（掩码进来 → 保留库中原值），其余键按入参写。
     *
     * <p>⚠️ 掩码回传但库里本来就没这个键 → <b>丢弃该键</b>，绝不把掩码当值存进库。
     * 返回 {@code null} 表示调用方传了 null（保持既有 null 语义）；入参空 map → 空 map。
     */
    public static Map<String, String> mergeMaskedMap(Map<String, String> incoming,
                                                     Map<String, String> storedPlain) {
        if (incoming == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            String stored = storedPlain == null ? null : storedPlain.get(name);
            if (isMaskedSentinel(value, stored)) {
                if (stored != null && !stored.isBlank()) {
                    out.put(name, stored);
                }
                continue;
            }
            out.put(name, value);
        }
        return out;
    }

    /**
     * 读侧：只掩代理 URL 里的口令段，主机端口保持可读。
     *
     * <p>{@code http://user:secret@host:8080} → {@code http://user:****@host:8080}。
     * 没有 {@code user:pass@} 形态 / 没口令 / null → 原样返回。
     */
    public static String maskProxyPassword(String proxy) {
        if (proxy == null || proxy.isEmpty()) return proxy;
        int schemeEnd = proxy.indexOf("://");
        int userInfoStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int at = proxy.indexOf('@', userInfoStart);
        if (at < 0) return proxy;
        String userInfo = proxy.substring(userInfoStart, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0 || colon == userInfo.length() - 1) return proxy;
        String user = userInfo.substring(0, colon);
        return proxy.substring(0, userInfoStart) + user + ":" + MASK_TOKEN + proxy.substring(at);
    }

    /**
     * 写侧：如果入参口令段是掩码，则替换回库中的真口令（允许用户只改主机端口）。
     *
     * <p>入参无口令掩码 → 原样；库中无口令可换 → 原样（不把掩码存进库）。
     */
    public static String unmaskProxyPassword(String incoming, String storedPlain) {
        if (incoming == null || incoming.isEmpty()) return incoming;
        int schemeEnd = incoming.indexOf("://");
        int userInfoStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int at = incoming.indexOf('@', userInfoStart);
        if (at < 0) return incoming;
        String userInfo = incoming.substring(userInfoStart, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0) return incoming;
        String user = userInfo.substring(0, colon);
        String password = userInfo.substring(colon + 1);
        if (!MASK_TOKEN.equals(password)) return incoming;

        String storedPassword = proxyPasswordOf(storedPlain);
        if (storedPassword == null || storedPassword.isEmpty()) return incoming;
        return incoming.substring(0, userInfoStart) + user + ":" + storedPassword
            + incoming.substring(at);
    }

    /** 取代理 URL 的口令段（无 → null）。 */
    private static String proxyPasswordOf(String proxy) {
        if (proxy == null || proxy.isEmpty()) return null;
        int schemeEnd = proxy.indexOf("://");
        int userInfoStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int at = proxy.indexOf('@', userInfoStart);
        if (at < 0) return null;
        String userInfo = proxy.substring(userInfoStart, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0) return null;
        return userInfo.substring(colon + 1);
    }

    /**
     * <b>旧格式</b>脱敏：保留前 3 + 末 4（{@code sk-****4f2a}）。
     *
     * <p>⛔ 新写入一律用 {@link #maskSecret(String)}（只留末四位，响应体不出现 {@code sk-}）。
     * 本方法保留只为识别历史库里的旧掩码（哨兵/兼容测试）。
     */
    public static String mask(String plainKey) {
        if (plainKey == null || plainKey.isEmpty()) return "****";
        int len = plainKey.length();
        if (len <= 4) return "****";
        if (len <= 7) {
            return plainKey.substring(0, 2) + "****" + plainKey.substring(len - 1);
        }
        return plainKey.substring(0, 3) + "****" + plainKey.substring(len - 4);
    }

    /** 测试用入口 */
    public static void main(String[] args) {
        String[] keys = {
            "sk-abc123def456",
            "short",
            "ab",
            "verylongapikeywithmanycharacters"
        };
        for (String k : keys) {
            System.out.println(k + " -> mask: " + mask(k) + ", hash: " + hash(k).substring(0, 16) + "...");
        }
    }
}