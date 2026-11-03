package com.nexusai.application.agent.computeruse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感动作的<b>一次性确认令牌</b>存储 · 对齐 dsh
 * {@code dsh-computer-use/src/confirmations.ts} 的 {@code ComputerConfirmationManager}。
 *
 * <h2>dsh 的原始语义（本类逐条照做）</h2>
 * <ol>
 *   <li><b>令牌绑定三元组</b>：{@code app(bundleId+pid) + observationId + actionHash}
 *       （{@code confirmations.ts:78-86} 存 {@code app/observationId/actionHash/expiresAt}）；</li>
 *   <li><b>动作哈希</b> = 去掉 {@code confirmationToken} 后的动作参数的<b>稳定序列化</b>的 SHA-256
 *       （{@code confirmations.ts:26-34} 的 {@code stable()} + {@code actionHash()}）——
 *       键按字典序排序 ⇒ 与键序无关；</li>
 *   <li><b>一次性消费</b>：{@code consume} 先删后校验（{@code confirmations.ts:104-106}
 *       {@code agentRecords?.delete(token)} 在过期检查<b>之前</b>⇒ 失败的尝试也消耗令牌）；</li>
 *   <li><b>过期</b>：{@code confirmationTtlMs}（dsh 默认 300000 = 5 分钟）；</li>
 *   <li><b>绑定不符</b> ⇒ {@code COMPUTER_CONFIRMATION_REQUIRED}
 *       （"does not match this app, observation, or action"）；</li>
 *   <li><b>目标重绑后失效</b>：{@code invalidate(token)}（{@code confirmations.ts:115-118}）。</li>
 * </ol>
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li><b>作用域</b>：dsh 按 {@code Agent} 分桶；本仓按 {@code sessionId} 分桶
 *       （与 {@link ComputerUseGeometryStore} / {@link ComputerUseUiSnapshotStore} 同款约定）。</li>
 *   <li><b>持久化</b>：dsh 的令牌<b>只在进程内</b>（{@code Map<Agent, ...>}）⇒ 进程重启即失效。
 *       本类同款（进程内 ConcurrentHashMap）—— 令牌本就是「当次操作」的一次性凭据。</li>
 * </ul>
 */
public final class ComputerUseConfirmationStore {

    /** 令牌有效期（毫秒）· dsh {@code config.ts:88} {@code confirmationTtlMs: 300000}。 */
    public static final long DEFAULT_TTL_MS = 300_000L;

    /** 确认被要求但未获得令牌时的错误码。 */
    public static final String ERR_CONFIRMATION_REQUIRED = "COMPUTER_CONFIRMATION_REQUIRED";

    /**
     * 一条令牌记录。
     *
     * @param appKey        应用标识（{@code bundleId:pid}）
     * @param observationId 绑定的观察 id
     * @param actionHash    绑定的动作哈希
     * @param expiresAt     过期时刻（epoch 毫秒）
     */
    public record Confirmation(String appKey,
                               String observationId,
                               String actionHash,
                               long expiresAt) {
    }

    /** 发放的令牌。 */
    public record Issued(String token,
                         String observationId,
                         String appKey,
                         long expiresAt) {
    }

    private final Map<String, Map<String, Confirmation>> bySession = new ConcurrentHashMap<>();
    private final long ttlMs;

    /** 生产构造器：TTL = dsh 默认 5 分钟。 */
    public ComputerUseConfirmationStore() {
        this(DEFAULT_TTL_MS);
    }

    /**
     * @param ttlMs 令牌有效期毫秒（&lt;=0 ⇒ 永不过期）
     */
    public ComputerUseConfirmationStore(long ttlMs) {
        this.ttlMs = ttlMs <= 0 ? Long.MAX_VALUE : ttlMs;
    }

    /**
     * 发放令牌（对齐 dsh {@code confirm()} 的落库段）。
     *
     * @param sessionId     会话 id
     * @param appKey        应用标识（{@code bundleId:pid}）
     * @param observationId 绑定的观察 id
     * @param actionHash    绑定的动作哈希
     * @return 发放结果
     */
    public Issued issue(String sessionId, String appKey, String observationId, String actionHash) {
        String token = UUID.randomUUID().toString();
        long expiresAt = ttlMs == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + ttlMs;
        bySession.computeIfAbsent(key(sessionId), k -> new ConcurrentHashMap<>())
                .put(token, new Confirmation(appKey, observationId, actionHash, expiresAt));
        return new Issued(token, observationId, appKey, expiresAt);
    }

    /**
     * 消费令牌（对齐 dsh {@code consume()}）。
     *
     * <p><b>顺序与 dsh 一致</b>：<b>先删除再校验</b> ⇒ 一次失败的尝试也消耗令牌
     * （防止「拿一个错配的令牌反复试」）。
     *
     * @param sessionId     会话 id
     * @param token         令牌
     * @param appKey        当前应用标识
     * @param observationId 当前观察 id
     * @param actionHash    当前动作哈希
     * @return null = 校验通过；非 null = 拒绝原因
     */
    public String consume(String sessionId,
                          String token,
                          String appKey,
                          String observationId,
                          String actionHash) {
        if (token == null || token.isEmpty()) {
            return "sensitive action requires a token from computer_confirm";
        }
        Map<String, Confirmation> records = bySession.get(key(sessionId));
        if (records == null) {
            return "confirmation token is unknown, expired, or already consumed";
        }
        Confirmation record = records.remove(token);
        if (record == null) {
            return "confirmation token is unknown, expired, or already consumed";
        }
        if (record.expiresAt() != Long.MAX_VALUE && record.expiresAt() < System.currentTimeMillis()) {
            return "confirmation token expired";
        }
        if (!java.util.Objects.equals(record.appKey(), appKey)
                || !java.util.Objects.equals(record.observationId(), observationId)
                || !java.util.Objects.equals(record.actionHash(), actionHash)) {
            return "confirmation token does not match this app, observation, or action";
        }
        return null;
    }

    /**
     * 使一个令牌失效（对齐 dsh {@code invalidate()}：目标重绑后调用）。
     *
     * @param sessionId 会话 id
     * @param token     令牌（null / 空 ⇒ 无操作）
     */
    public void invalidate(String sessionId, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        Map<String, Confirmation> records = bySession.get(key(sessionId));
        if (records != null) {
            records.remove(token);
        }
    }

    /**
     * 释放某会话的全部令牌（对齐 dsh {@code releaseAgent()}）。
     *
     * @param sessionId 会话 id
     */
    public void release(String sessionId) {
        bySession.remove(key(sessionId));
    }

    /**
     * 动作参数的<b>稳定哈希</b>（对齐 dsh {@code actionHash()}）。
     *
     * <p>去掉 {@code confirmationToken} 自身 ⇒ 令牌不参与自己的哈希。
     * <p>键按<b>字典序</b>排序 ⇒ 与参数书写顺序无关。
     *
     * @param params 动作参数（键值对）
     * @return SHA-256 十六进制
     */
    public static String actionHash(Map<String, String> params) {
        Map<String, String> sorted = new TreeMapSafe(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append('{').append(entry.getKey()).append(':').append(entry.getValue()).append('}');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "len:" + sb.length();
        }
    }


    /**
     * 构造<b>两侧一致</b>的规范参数（{@code computer_confirm} 与动作工具共用）。
     *
     * <h2>WHY 需要它</h2>
     * <p>dsh 的 {@code actionHash} 作用于「动作请求对象」本身 —— 同一份对象既用于发放令牌
     * 也用于消费令牌，所以两侧天然一致（同一个 JS 对象）。
     * <br>本仓两侧是<b>两次独立的工具调用</b>（模型先调 {@code computer_confirm}，
     * 再调动作工具）⇒ 必须约定一套<b>规范形</b>，否则哈希对不上 ⇒ 令牌永远不匹配。
     * <p>⇒ 规范形 = {@code (observationId, index, targetHandle, action, payload)}，
     * 五元组；两侧都调本方法生成。
     *
     * @param observationId 观察 id（可空）
     * @param index         元素序号（-1 = 无）
     * @param targetHandle  元素句柄（可空）
     * @param action        动作种类（如 {@code click} / {@code type}）
     * @param payload       动作载荷（如 type 的文本；可空）
     * @return 规范参数表（可直接喂 {@link #actionHash(Map)}）
     */
    public static Map<String, String> canonicalParams(String observationId,
                                                       int index,
                                                       String targetHandle,
                                                       String action,
                                                       String payload) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("observationId", observationId == null ? "" : observationId);
        params.put("index", index < 0 ? "" : String.valueOf(index));
        params.put("targetHandle", targetHandle == null ? "" : targetHandle);
        params.put("action", action == null ? "" : action);
        params.put("payload", payload == null ? "" : payload);
        return params;
    }

    /** 简单包装以避免额外 import（用 {@code java.util.TreeMap} 的语义）。 */
    private static final class TreeMapSafe extends LinkedHashMap<String, String> {
        TreeMapSafe(Map<String, String> source) {
            super(source);
            List<String> keys = new ArrayList<>(source.keySet());
            keys.sort(String::compareTo);
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String key : keys) {
                ordered.put(key, source.get(key));
            }
            clear();
            putAll(ordered);
        }
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank()
                ? ComputerUseUiSnapshotStore.GLOBAL_KEY
                : sessionId;
    }
}
