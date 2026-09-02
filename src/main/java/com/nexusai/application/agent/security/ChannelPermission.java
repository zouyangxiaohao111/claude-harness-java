package com.nexusai.application.agent.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;


/**
 * Channel permission relay (Telegram/iMessage/Discord) · 对齐 CC services/mcp/channelPermissions.ts.
 *
 * <p>CC source: services/mcp/channelPermissions.ts (240 LOC).
 * 保留面: shortRequestId (5-letter FNV-1a hash) + hashToId + filterPermissionRelayClients
 * (3-condition gate) + isChannelPermissionRelayEnabled feature flag。
 * 已删面（D-B10-06，0 调用方，EV-R2-41）: callbacks 工厂与 JSON 预览截断 —— 生产分别由
 * StompChannelPermissionCallbacks 自实现 pending map + resolve（:64 复用 shortRequestId）
 * 与 Stomp 私有实现承担。
 *
 * <p>L1 语义: 通道 (Telegram/iMessage/Discord) 上处理 permission dialog.
 *            - shortRequestId(toolUseID) → 5-letter ID (a-z minus 'l') + blocklist rehash.
 *            - filterPermissionRelayClients → connected + allowlist + both capabilities.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 公开 API (isEnabled/shortRequestId/filterPermissionRelayClients);
 *       ID_ALPHABET 25 chars;blocklist 24+ dirty words;FNV-1a hash.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — shortRequestId 5 chars + retry on blocklist;
 *       filterPermissionRelayClients 3 condition.</li>
 *   <li><b>A3</b>: 状态: IDLE / PENDING (resolver 注册) / RESOLVED (handler 触发)
 *       —— 该状态机现由 StompChannelPermissionCallbacks 承担（Java 生产实现）。</li>
 *   <li><b>A4</b>: ID 含 blocklist → 重 hash (最多 10 次);
 *       filter 3 条件 (connected + allowlist + 2 capabilities) ALL required.</li>
 *   <li><b>A5</b>: 真实场景 — 通道 server 解析用户 "yes tbxkq" → 推 structured event → resolve.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS FNV-1a hash → Java int + bit ops;
 *                    TS `process.env.USER_TYPE` → 注入式 Supplier;
 *                    TS `regex.test` → Java Pattern.
 */
public final class ChannelPermission {

    public static final String ID_ALPHABET = "abcdefghijkmnopqrstuvwxyz";
    public static final java.util.List<String> ID_AVOID_SUBSTRINGS = List.of(
        "fuck", "shit", "cunt", "cock", "dick", "twat", "piss", "crap",
        "bitch", "whore", "ass", "tit", "cum", "fag", "dyke", "nig",
        "kike", "rape", "nazi", "damn", "poo", "pee", "wank", "anus"
    );
    public static final String PERMISSION_REPLY_RE = "^\\s*(y|yes|n|no)\\s+([a-km-z]{5})\\s*$";

    private final Supplier<Boolean> featureFlagSupplier;

    public ChannelPermission(Supplier<Boolean> featureFlagSupplier) {
        this.featureFlagSupplier = Objects.requireNonNull(featureFlagSupplier);
    }

    /** CC isChannelPermissionRelayEnabled. */
    public boolean isEnabled() {
        Boolean v = featureFlagSupplier.get();
        return v != null && v;
    }

    /** CC shortRequestId — 5-letter ID from toolUseID. */
    public String shortRequestId(String toolUseId) {
        if (toolUseId == null) return "aaaaa";
        for (int salt = 0; salt < 10; salt++) {
            String input = salt == 0 ? toolUseId : (toolUseId + ":" + salt);
            String candidate = hashToId(input);
            boolean dirty = false;
            for (String bad : ID_AVOID_SUBSTRINGS) {
                if (candidate.contains(bad)) { dirty = true; break; }
            }
            if (!dirty) return candidate;
        }
        return hashToId(toolUseId);
    }

    /** CC hashToId — FNV-1a 32-bit hash → base-25 encode 5 chars. */
    String hashToId(String input) {
        long h = 0x811c9dc5L;
        for (int i = 0; i < input.length(); i++) {
            h ^= input.charAt(i);
            h = (h * 0x01000193L) & 0xFFFFFFFFL;
        }
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(ID_ALPHABET.charAt((int) (h % 25)));
            h = h / 25;
        }
        return sb.toString();
    }

    /** CC filterPermissionRelayClients — 3-condition gate. */
    public <T> java.util.List<T> filterPermissionRelayClients(
        java.util.List<T> clients,
        Function<T, String> nameFn,
        Function<T, String> typeFn,
        Function<T, Map<String, Object>> capabilitiesFn,
        Function<String, Boolean> isInAllowlist) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (T c : clients) {
            if (!"connected".equals(typeFn.apply(c))) continue;
            if (!isInAllowlist.apply(nameFn.apply(c))) continue;
            Map<String, Object> caps = capabilitiesFn.apply(c);
            if (caps == null) continue;
            Object experimental = caps.get("experimental");
            if (!(experimental instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> exp = (Map<String, Object>) experimental;
            if (!exp.containsKey("claude/channel")) continue;
            if (!exp.containsKey("claude/channel/permission")) continue;
            result.add(c);
        }
        return result;
    }
}
