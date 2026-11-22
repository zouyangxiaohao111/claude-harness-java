package com.nexusai.wechatlog.key;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 微信版本 → 偏移链的映射表。
 *
 * <p>旧实现把 4.1.11 的整套偏移写死在扫描代码里。微信升级后（本机 4.2.0.53）
 * 只能静默 0 候选。把偏移变成数据后：已验证版本用对应链；未验证版本允许
 * best-effort 尝试最近的已验证链，但失败时明确报「版本未适配 + 版本号」；
 * 新版本偏移确认后写一份 properties 即可，无需改代码。
 *
 * <p>可配置文件：{@code ~/.nexusai/wechat-log/wechat-offsets.properties}，
 * 形如 {@code 4.2.0.53.nodeBack=16}；数字支持十进制或 {@code 0x}。
 */
public final class WeChatOffsetTable {

    private static final Logger log = LoggerFactory.getLogger(WeChatOffsetTable.class);

    /** 已验证的 4.1.11 偏移链（原实现里的魔数，逐字节保留）。 */
    public static final String DEFAULT_VERSION = "4.1.11";

    private static final String DEFAULT_XOR_MASK =
            "d2c7442458020000004889442450488b450048844c2448488944254048584c24";

    private static final String[] FIELD_NAMES = {
            "verified", "nodeBack", "nodeNeedleOffset", "nodeLengthOffset",
            "nodeConfigOffset", "configBlobOffset", "dataPtrOffset", "dataLenOffset", "xorMask"
    };

    /** 一次解析结果。 */
    public record Resolution(WeChatOffsetChain chain, boolean verified, String matchedKey) {
    }

    private final Map<String, WeChatOffsetChain> chains;

    public WeChatOffsetTable(Map<String, WeChatOffsetChain> chains) {
        this.chains = new LinkedHashMap<>(chains);
    }

    /** 默认表：内置 4.1.11 + 可选的用户配置。 */
    public static WeChatOffsetTable defaults() {
        Map<String, WeChatOffsetChain> map = new LinkedHashMap<>();
        map.put(DEFAULT_VERSION, builtin411());
        Path props = defaultPropertiesFile();
        if (Files.isRegularFile(props)) {
            try (InputStream in = Files.newInputStream(props)) {
                Properties p = new Properties();
                p.load(in);
                map.putAll(parseProperties(p));
            } catch (Exception e) {
                log.warn("读取微信偏移配置失败（忽略，继续用内置表）: {}", props, e);
            }
        }
        return new WeChatOffsetTable(map);
    }

    public static Path defaultPropertiesFile() {
        return Paths.get(System.getProperty("user.home"), ".nexusai", "wechat-log",
                "wechat-offsets.properties");
    }

    /** 内置的 4.1.11 链（与旧实现逐字节一致）。 */
    public static WeChatOffsetChain builtin411() {
        return new WeChatOffsetChain(
                DEFAULT_VERSION, true,
                0x10, 0x10, 0x18, 0x28, 0x88, 0x08, 0x10,
                hexToBytes(DEFAULT_XOR_MASK));
    }

    /**
     * 从 properties 解析额外版本。
     *
     * <p>只接受字段齐全（地址偏移 + xorMask）的条目；缺项跳过并告警，
     * 避免半份配置导致更难排查的「扫不到」。
     */
    static Map<String, WeChatOffsetChain> parseProperties(Properties props) {
        Map<String, Map<String, String>> byVersion = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            for (String field : FIELD_NAMES) {
                String suffix = "." + field;
                if (!key.endsWith(suffix)) {
                    continue;
                }
                String version = key.substring(0, key.length() - suffix.length());
                byVersion.computeIfAbsent(version, k -> new LinkedHashMap<>())
                        .put(field, props.getProperty(key));
                break;
            }
        }

        Map<String, WeChatOffsetChain> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : byVersion.entrySet()) {
            Map<String, String> f = e.getValue();
            try {
                out.put(e.getKey(), new WeChatOffsetChain(
                        e.getKey(),
                        Boolean.parseBoolean(f.getOrDefault("verified", "false")),
                        number(f, "nodeBack"),
                        number(f, "nodeNeedleOffset"),
                        number(f, "nodeLengthOffset"),
                        number(f, "nodeConfigOffset"),
                        number(f, "configBlobOffset"),
                        number(f, "dataPtrOffset"),
                        number(f, "dataLenOffset"),
                        hexToBytes(required(f, "xorMask"))));
            } catch (RuntimeException ex) {
                log.warn("微信偏移配置条目不完整，已跳过: {} ({})", e.getKey(), ex.getMessage());
            }
        }
        return out;
    }

    private static String required(Map<String, String> f, String key) {
        String v = f.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        return v.trim();
    }

    private static int number(Map<String, String> f, String key) {
        String v = required(f, key);
        if (v.toLowerCase(Locale.ROOT).startsWith("0x")) {
            return Integer.parseInt(v.substring(2), 16);
        }
        return Integer.parseInt(v);
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.trim();
        if (h.length() % 2 != 0) {
            throw new IllegalArgumentException("xorMask 必须是偶数个十六进制字符");
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * 解析某个微信版本应使用的链。
     *
     * <ul>
     *   <li>精确命中或前缀命中（{@code 4.1.11.15} 命中 {@code 4.1.11}）⇒ verified=true；</li>
     *   <li>未命中 ⇒ 取「不大于该版本的最大已知版本」做 best-effort，verified=false；</li>
     *   <li>比所有已知版本都老，或版本为空且表为空 ⇒ empty。</li>
     * </ul>
     */
    public Optional<Resolution> resolve(String version) {
        if (version == null || version.isBlank()) {
            return bestEffortBelow(null);
        }
        String v = version.trim();
        // 取最长的前缀匹配：若同时配置了 4.1 与 4.1.11，4.1.11.x 必须命中更具体的 4.1.11
        String best = null;
        for (String key : chains.keySet()) {
            if ((v.equals(key) || v.startsWith(key + "."))
                    && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        if (best != null) {
            WeChatOffsetChain chain = chains.get(best);
            return Optional.of(new Resolution(chain, chain.verified(), best));
        }
        return bestEffortBelow(v);
    }

    private Optional<Resolution> bestEffortBelow(String version) {
        List<String> keys = new ArrayList<>(chains.keySet());
        keys.sort((a, b) -> compareVersion(parseVersion(a), parseVersion(b)));
        String chosen = null;
        for (String key : keys) {
            if (version == null || compareVersion(parseVersion(key), parseVersion(version)) <= 0) {
                chosen = key;
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        WeChatOffsetChain chain = chains.get(chosen);
        return Optional.of(new Resolution(chain, false, chosen));
    }

    static int[] parseVersion(String v) {
        String[] parts = v == null ? new String[0] : v.trim().split("\\.");
        int[] out = new int[Math.max(4, parts.length)];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    static int compareVersion(int[] a, int[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
