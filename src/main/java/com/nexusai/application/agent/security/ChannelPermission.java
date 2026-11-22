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

    /** [P2] 随机短 ID 的熵源（会话内唯一性守卫见 {@link #shortRequestIdForSession}）。 */
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /**
     * [P2] 某会话**当前已占用**的短 ID（key=sessionId）。
     *
     * <p>WHY：短 ID 从确定性哈希改成随机后，同一会话在极低概率下可能连撞同一个号 ⇒
     * {@code pending} 表（按短 ID 键控）会被后来的请求覆盖，用户回执命中错的请求。
     * 这里按会话登记已发出的号，重试到不撞为止。
     */
    private final java.util.Map<String, java.util.Set<String>> issuedIdsBySession =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * [P2] 测试 seam：null ⇒ {@link #randomCandidate()}；非 null ⇒ 逐次取该 Supplier 的候选。
     *
     * <p>只用于把「随机撞号 → 重试 → 哈希回退」这条分支变成可确定性验证的用例。
     */
    private java.util.function.Supplier<String> randomCandidateSupplier;

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
            if (!isDirtyId(candidate)) return candidate;
        }
        return hashToId(toolUseId);
    }

    /** 脏词子串命中（对齐 CC blocklist 重 hash 判据）。 */
    private boolean isDirtyId(String candidate) {
        for (String bad : ID_AVOID_SUBSTRINGS) {
            if (candidate.contains(bad)) return true;
        }
        return false;
    }

    /**
     * [P2] 随机 5 位短 ID（同一字符集 a-z 去 {@code l}）；脏词命中重试，最多 10 次。
     *
     * @return 合法候选；连续 10 次都不合法（实际不可能）⇒ null，由调用方回落哈希路径
     */
    public String randomShortId() {
        for (int i = 0; i < 10; i++) {
            String candidate = randomCandidateSupplier != null
                ? randomCandidateSupplier.get() : randomCandidate();
            if (candidate == null) return null;
            if (candidate.length() != 5) continue;
            if (!isAllAlphabet(candidate)) continue;
            if (isDirtyId(candidate)) continue;
            return candidate;
        }
        return null;
    }

    private String randomCandidate() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static boolean isAllAlphabet(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (ID_ALPHABET.indexOf(s.charAt(i)) < 0) return false;
        }
        return true;
    }

    /**
     * [P2] 生成**会话内唯一**的短 ID：优先随机，撞号（含脏词）重试；随机失败回落确定性哈希
     * （带盐重 hash）—— <b>哈希回退路径保留</b>。
     *
     * @param sessionId 会话 id（会话内唯一性作用域）
     * @param toolUseId 工具调用 id（哈希回退路径的种子）
     */
    public String shortRequestIdForSession(String sessionId, String toolUseId) {
        String sid = sessionId == null ? "" : sessionId;
        for (int i = 0; i < 20; i++) {
            String candidate = randomShortId();
            if (candidate == null) break;
            if (claim(sid, candidate)) return candidate;
        }
        String fallback = shortRequestId(toolUseId);
        if (claim(sid, fallback)) return fallback;
        for (int salt = 1; salt <= 50; salt++) {
            String candidate = shortRequestId(toolUseId + ":fallback:" + salt);
            if (claim(sid, candidate)) return candidate;
        }
        // 极端情况（整个小字母空间被占满）⇒ 仍返回一个可用号，不阻断权限流程
        return fallback;
    }

    /** [P2] 结算/取消时释放会话内的短 ID（允许以后复用）。 */
    public void releaseShortId(String sessionId, String shortId) {
        if (sessionId == null || shortId == null) return;
        java.util.Set<String> set = issuedIdsBySession.get(sessionId);
        if (set != null) {
            set.remove(shortId);
            if (set.isEmpty()) issuedIdsBySession.remove(sessionId, set);
        }
    }

    /** [P2] 测试 seam：注入可复算的随机候选（null 恢复真实随机）。 */
    void setRandomCandidateSupplier(java.util.function.Supplier<String> supplier) {
        this.randomCandidateSupplier = supplier;
    }

    private boolean claim(String sid, String id) {
        java.util.Set<String> set = issuedIdsBySession.computeIfAbsent(
            sid, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        return set.add(id);
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
