package com.nexusai.application.agent.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 真实 UI 批准能力适配 · 跨会话 peer 权限/待收件的<b>可信主体边界</b>（C 批）。
 *
 * <h2>默认本地用户（用户裁定 2026-11-19：不要「宁可不放行」）</h2>
 * <p>当前桌面形态就<b>当已经有 UI</b>：默认安装一个 local-user 验证器，
 * {@link #getUserId()} 缺省返回 {@value #DEFAULT_LOCAL_USER_ID}；
 * {@link #isTrustedUi} 在默认桌面部署下返回 {@code true}，并在准入结果/日志里携带该 userId。
 * 后期接真实认证（Tauri IPC 签名 / OS 对话框 / WebView 通道）只需替换
 * {@link #installVerifier} / {@link #installUserIdSource}，<b>不改调用方</b>。
 * <ul>
 *   <li>⛔ 不因为「真实认证尚未接入」返回 403 或全局拒绝；</li>
 *   <li>⛔ 也不把默认用户身份当成「peer 可以自证」：peer 来源始终由服务端信封
 *       （{@link PeerExecutionOrigin} / {@code PeerEnvelope}）决定，不是请求体自报；</li>
 *   <li>审批归属 = {@code sessionId + requestId + userId}，便于以后升级认证而不改调用点。</li>
 * </ul>
 *
 * <h2>两个不同判据</h2>
 * <ul>
 *   <li>{@link Verifier} = 「这是真实用户批准」的正向证明。默认 local-user 验证器已安装；
 *       需要更严的环境可安装严格验证器（例如只认带有 IPC 签名的请求）。</li>
 *   <li>{@link Attributor} = 「这个请求可归因为 peer / 非可信主体」的归因（服务端可验证通道）。
 *       未安装 ⇒ 无法归因 ⇒ 按正常用户处理；可归因 ⇒ 审批端点不消耗、不解除 pending。</li>
 * </ul>
 *
 * <p><b>残余风险照实说</b>：默认用户 ≈ 本地单用户信任边界；真实认证接入前，
 * 任何能访问本机 API 的进程与 UI 在服务端看来是同一主体。
 */
public final class PeerUiApprovalAuthority {

    private static final Logger log = LoggerFactory.getLogger(PeerUiApprovalAuthority.class);

    /** 默认本地用户 id（可被 {@link #installUserIdSource} 替换）。 */
    public static final String DEFAULT_LOCAL_USER_ID = "default";

    /** 请求来源通道（仅用于归因/日志，不是信任判据本身）。 */
    public enum Source {
        /** 普通权限响应 STOMP 帧 {@code /sessions/{id}/permission-response}。 */
        STOMP_MESSAGE,
        /** bridge 权限响应 {@code /sessions/{id}/permission-bridge-response}。 */
        STOMP_BRIDGE,
        /** channel 权限响应 {@code /sessions/{id}/permission-channel-response}。 */
        STOMP_CHANNEL,
        /** HTTP {@code POST /sessions/{id}/plan/exit}。 */
        HTTP_PLAN_EXIT,
        /** HTTP 待收件列表/批准端点。 */
        HTTP_PEER_INBOX
    }

    /**
     * 一次待判定请求。
     *
     * @param source     来源通道
     * @param sessionId  目标会话（服务端从请求路径/归属得出，非 body 自报）
     * @param requestId  权限 requestId / held messageId（可为 null：例如 plan/exit 无请求 id）
     * @param attributes 服务端可得的通道属性（如 STOMP native headers；无认证机制时为空 map）
     */
    public record Claim(Source source, String sessionId, String requestId,
                        Map<String, Object> attributes) {
        public Claim {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /**
     * 准入结论。
     *
     * @param trusted 是否可信 UI（默认 local user ⇒ true）
     * @param userId  审批主体 id（默认 {@value #DEFAULT_LOCAL_USER_ID}）
     * @param detail  可读说明（拒绝原因/降级说明；成功时为 null）
     */
    public record Admission(boolean trusted, String userId, String detail) {
        public static Admission trusted(String userId) {
            return new Admission(true, userId, null);
        }

        public static Admission rejected(String userId, String detail) {
            return new Admission(false, userId, detail);
        }
    }

    /** 真实 UI 验证器（默认 = local-user；可替换为真实认证）。 */
    @FunctionalInterface
    public interface Verifier {
        /**
         * @return true 仅当该请求确实来自可信 UI（服务端可验证的凭据/通道），否则 false
         */
        boolean isTrustedUi(Claim claim);
    }

    /**
     * 请求来源归因：服务端能否证明「这个请求由 peer 回合发起」。
     *
     * <p>与 {@link Verifier}（真实 UI 批准）是<b>两个不同的判据</b>。
     * ⛔ 归因不信任请求头/正文自报；实现必须校验服务端可验证的通道。
     */
    @FunctionalInterface
    public interface Attributor {
        /** @return true 仅当该请求可归因为 peer/非可信主体 */
        boolean isPeerAttributed(Claim claim);
    }

    /**
     * 默认 local-user 验证器：当前桌面部署即「有 UI」。
     * {@link #isTrustedUi} 在调用前已校验 source/sessionId，因此这里不再重复。
     */
    private static final Verifier DEFAULT_LOCAL_USER_VERIFIER = claim -> true;

    private static volatile Verifier verifier = DEFAULT_LOCAL_USER_VERIFIER;
    private static volatile Attributor attributor;
    private static volatile Supplier<String> userIdSource = () -> DEFAULT_LOCAL_USER_ID;

    private PeerUiApprovalAuthority() {
        // 工具类
    }

    /** 当前审批主体 id（默认 {@value #DEFAULT_LOCAL_USER_ID}）。 */
    public static String getUserId() {
        Supplier<String> src = userIdSource;
        String id = null;
        try {
            id = src == null ? null : src.get();
        } catch (Throwable th) {
            log.warn("PEER UI authority: userId 源异常，回落默认用户 err={}", th.toString());
        }
        return id == null || id.isBlank() ? DEFAULT_LOCAL_USER_ID : id.trim();
    }

    /** 安装审批主体 id 源（后期接真实认证）。null = 恢复默认 {@value #DEFAULT_LOCAL_USER_ID}。 */
    public static void installUserIdSource(Supplier<String> source) {
        userIdSource = source == null ? () -> DEFAULT_LOCAL_USER_ID : source;
        log.info("PEER UI authority: userId 源 {} → user={}",
            source == null ? "已恢复默认" : "已安装", getUserId());
    }

    /** 是否已安装可信 UI 验证器（默认 local-user 验证器视为已安装）。 */
    public static boolean isConfigured() {
        return verifier != null;
    }

    /** 是否处于默认 local-user 信任（仅供日志/诊断）。 */
    public static boolean isDefaultLocalUser() {
        return Objects.equals(verifier, DEFAULT_LOCAL_USER_VERIFIER);
    }

    /** 是否已安装 peer 归因器（未安装 ⇒ 无法归因 ⇒ 按正常用户处理）。 */
    public static boolean isPeerAttributionConfigured() {
        return attributor != null;
    }

    /**
     * 安装验证器（生产接线/测试）。null = 恢复默认 local-user 验证器。
     * 安装严格验证器后，{@link #isTrustedUi} 以该验证器结果为准。
     */
    public static void installVerifier(Verifier verifier) {
        PeerUiApprovalAuthority.verifier =
            verifier == null ? DEFAULT_LOCAL_USER_VERIFIER : verifier;
        log.info("PEER UI authority: verifier {} (defaultLocalUser={})",
            verifier == null ? "已恢复默认 local-user" : "已安装", isDefaultLocalUser());
    }

    /** 安装 peer 归因器（生产接线/测试）。null = 清除（无法归因）。 */
    public static void installAttributor(Attributor attributor) {
        PeerUiApprovalAuthority.attributor = attributor;
        log.info("PEER UI authority: attributor {}",
            attributor == null ? "已清除（无法归因 peer 请求）" : "已安装");
    }

    /**
     * 判定某请求是否可归因为 peer / 非可信主体。
     *
     * @return 未安装归因器 → false（无法归因 = 按正常用户处理）；归因器异常 → false + warn
     */
    public static boolean isPeerAttributed(Source source, String sessionId, String requestId) {
        return isPeerAttributed(source, sessionId, requestId, Map.of());
    }

    public static boolean isPeerAttributed(Source source, String sessionId, String requestId,
                                           Map<String, Object> attributes) {
        Attributor a = attributor;
        if (a == null) {
            return false;
        }
        try {
            boolean peerAttributed = a.isPeerAttributed(new Claim(source, sessionId, requestId, attributes));
            if (peerAttributed) {
                log.warn("PEER UI authority: 请求可归因为 peer/非可信主体 source={} session={} requestId={} user={}",
                    source, sessionId, requestId, getUserId());
            }
            return peerAttributed;
        } catch (Throwable th) {
            log.warn("PEER UI authority: 归因器异常，按无法归因处理 source={} session={} requestId={} err={}",
                source, sessionId, requestId, th.toString());
            return false;
        }
    }

    /** 测试清理（验证器恢复默认 local-user + 清除归因器 + 恢复默认 userId）。 */
    public static void clearForTest() {
        installVerifier(null);
        installAttributor(null);
        installUserIdSource(null);
    }

    /**
     * 判定某次请求是否来自可信 UI。
     *
     * @return 验证器异常 / 参数不合法 → false；默认 local-user → true
     */
    public static boolean isTrustedUi(Source source, String sessionId, String requestId) {
        return isTrustedUi(source, sessionId, requestId, Map.of());
    }

    public static boolean isTrustedUi(Source source, String sessionId, String requestId,
                                      Map<String, Object> attributes) {
        return admit(source, sessionId, requestId, attributes).trusted();
    }

    /**
     * 准入判定（带 userId）：默认 local-user 部署下返回 {@code trusted=true} 且 userId 非空。
     * 调用方（审批端点/控制器）应把 userId 写入日志与归属记录。
     */
    public static Admission admit(Source source, String sessionId, String requestId) {
        return admit(source, sessionId, requestId, Map.of());
    }

    public static Admission admit(Source source, String sessionId, String requestId,
                                  Map<String, Object> attributes) {
        String userId = getUserId();
        Verifier v = verifier;
        if (v == null) {
            // 理论上 installVerifier(null) 会恢复默认；这里仅防御反射/初始化竞态。
            return Admission.rejected(userId, "no trusted UI authority configured");
        }
        if (source == null || sessionId == null || sessionId.isBlank()) {
            log.warn("PEER UI authority: 判定参数缺失 source={} session={} requestId={} user={} → 拒绝",
                source, sessionId, requestId, userId);
            return Admission.rejected(userId, "missing source/sessionId");
        }
        try {
            boolean trusted = v.isTrustedUi(new Claim(source, sessionId, requestId, attributes));
            if (log.isDebugEnabled()) {
                log.debug("PEER UI authority: source={} session={} requestId={} user={} trustedUi={}",
                    source, sessionId, requestId, userId, trusted);
            }
            return trusted
                ? Admission.trusted(userId)
                : Admission.rejected(userId, "request rejected by configured verifier");
        } catch (Throwable th) {
            log.warn("PEER UI authority: 验证器异常 → 拒绝 source={} session={} requestId={} user={} err={}",
                source, sessionId, requestId, userId, th.toString());
            return Admission.rejected(userId, "verifier error: " + th);
        }
    }

    /** 测试/日志用：验证器是否同一实例。 */
    static boolean isInstalled(Verifier expected) {
        return Objects.equals(verifier, expected);
    }
}
