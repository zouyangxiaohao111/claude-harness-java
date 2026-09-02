package com.nexusai.application.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * VSCode SDK MCP IPC bridge · 对齐 CC services/mcp/vscodeSdkMcp.ts.
 *
 * <p>L1 语义: 与 claude-vscode MCP server 的双向 IPC.
 *            - setupVscodeSdkMcp: 找到 'claude-vscode' connected client + 注册 log_event notification handler + 推送 experiment gates.
 *            - notifyVscodeFileUpdated: 文件编辑后推 file_updated 通知 (仅 ant + vscodeMcpClient 存在).
 *            - AutoModeEnabledState tri-state ('enabled'/'disabled'/'opt-in') 通过 getFeatureValue 读取.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: setupVscodeSdkMcp(sdkClients) → void;notifyVscodeFileUpdated(path, old, new) → void;
 *       AutoModeEnabledState tri-state;4 个 experiment gates + auto_mode_state (tri-state, omit if undefined).</li>
 *   <li><b>A2 Golden Trace</b>: setup: find 'claude-vscode' + type='connected' → register handler + push gates.
 *       notify: USER_TYPE='ant' + vscodeMcpClient 存在 → send notification + catch error (不抛).</li>
 *   <li><b>A3</b>: 状态: NOT_CONFIGURED (no claude-vscode client) / CONFIGURED (handler registered).
 *       AutoMode tri-state: undefined → omit (vscode fails closed);defined → include.</li>
 *   <li><b>A4</b>: USER_TYPE != 'ant' → notify skip;vscodeMcpClient null → notify skip;
 *       client.type != 'connected' → setup skip;notification 失败 → logDebug 不抛;
 *       AutoMode undefined → omit gates.tengu_auto_mode_state (fails closed).</li>
 *   <li><b>A5</b>: 真实场景 — ant user 编辑文件 → vscode 客户端已设置 → 推送 file_updated notification;
 *       non-ant → skip;auto_mode_state='enabled' → push.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `getFeatureValue_CACHED_MAY_BE_STALE` → 注入式 FeatureGateSupplier;
 *                    TS `checkStatsigFeatureGate_CACHED_MAY_BE_STALE` → 注入式 FeatureGateSupplier;
 *                    TS `client.notification()` → 注入式 McpClientSender;
 *                    TS `client.setNotificationHandler()` → 注入式 NotificationHandler.
 *
 * <p>[RES-07d] 注册 {@code @Component}（默认构造器装安全 no-op supplier + setter 接线）：
 * 生产链 consumer = 文件编辑工具（notifyVscodeFileUpdated，对齐 CC fileHistory.ts:1095）；
 * userTypeSupplier 默认读 {@code System.getenv("USER_TYPE")}（对齐 CC vscodeSdkMcp.ts:44
 * {@code process.env.USER_TYPE}）；env 未设 → null → 非 ant → notify 跳过（安全默认）。
 * <p>[CD-03 返工] 原默认构造器硬编码 "local" 导致 notify gate 恒 `!"ant".equals("local")`
 * 跳过、生产静默不发 —— 与 CC USER_TYPE==='ant' 场景会发 的差异。改为默认读 env 即最小接线，
 * 生产 USER_TYPE=ant + claude-vscode client 存在（依赖 CD-02 SdkMcpTransport 真实化）才会发送。
 */
@Component
public final class VscodeSdkMcp {

    private static final Logger log = LoggerFactory.getLogger(VscodeSdkMcp.class);

    private Supplier<String> userTypeSupplier;
    private Supplier<ConnectedMCPServer> clientRefSupplier;
    private ConnectedMCPServer storedClient;
    private FeatureGateSupplier featureValueSupplier;     // generic Object supplier
    private FeatureGateSupplier statsigGateSupplier;
    private NotificationHandlerRegistrar handlerRegistrar;
    private NotificationSender notificationSender;

    public VscodeSdkMcp(Supplier<String> userTypeSupplier,
                          Supplier<ConnectedMCPServer> clientRefSupplier,
                          FeatureGateSupplier featureValueSupplier,
                          FeatureGateSupplier statsigGateSupplier,
                          NotificationHandlerRegistrar handlerRegistrar,
                          NotificationSender notificationSender) {
        this.userTypeSupplier = Objects.requireNonNull(userTypeSupplier);
        this.clientRefSupplier = Objects.requireNonNull(clientRefSupplier);
        this.featureValueSupplier = Objects.requireNonNull(featureValueSupplier);
        this.statsigGateSupplier = Objects.requireNonNull(statsigGateSupplier);
        this.handlerRegistrar = Objects.requireNonNull(handlerRegistrar);
        this.notificationSender = Objects.requireNonNull(notificationSender);
    }

    /**
     * Spring 默认构造器（RES-07d）· 安全 no-op supplier。
     *
     * <p>[CD-03 返工] userTypeSupplier 默认读 {@code System.getenv("USER_TYPE")}（对齐 CC
     * vscodeSdkMcp.ts:44 {@code process.env.USER_TYPE}）—— 替代原硬编码 "local"（该值导致 notify
     * gate 恒跳过、生产静默不发）。env 未设 → null → 非 ant → notify 跳过（安全默认不变）；
     * env=ant + client 存在 → 发送（CC 对齐）。clientRefSupplier 默认 null = CD-02（claude-vscode
     * client 依赖 SdkMcpTransport 真实化后接线）。
     */
    public VscodeSdkMcp() {
        this(() -> System.getenv("USER_TYPE"), () -> null, (n, d) -> d, (n, d) -> d, (s, h) -> {}, p -> {});
    }

    /** 注入 userType supplier（生产链 / 测试）· CC original: getUserType()。 */
    public void setUserTypeSupplier(Supplier<String> userTypeSupplier) {
        this.userTypeSupplier = Objects.requireNonNull(userTypeSupplier);
    }

    /** 当前 userType 值（package-private 测试缝，仿 McpOfficialRegistryPrefetcher 测试缝惯例）。 */
    String currentUserType() {
        return userTypeSupplier.get();
    }

    /** 注入 client ref supplier（claude-vscode 连接持有者）· CC original: vscodeMcpClient。 */
    public void setClientRefSupplier(Supplier<ConnectedMCPServer> clientRefSupplier) {
        this.clientRefSupplier = Objects.requireNonNull(clientRefSupplier);
    }

    /** 设置 client ref (在 setupVscodeSdkMcp 内部调用). */
    public void setClient(ConnectedMCPServer client) {
        this.storedClient = client;
    }

    /** Auto-mode enabled state tri-state. */
    public enum AutoModeEnabledState { ENABLED("enabled"), DISABLED("disabled"), OPT_IN("opt-in");
        private final String value;
        AutoModeEnabledState(String v) { this.value = v; }
        public String getValue() { return value; }
        public static AutoModeEnabledState fromValue(String v) {
            for (AutoModeEnabledState s : values()) if (s.value.equals(v)) return s;
            return null;
        }
    }

    /** VSCode MCP client ref (注入). */
    public interface VscodeMcpClientRef {
        ConnectedMCPServer get();
        void set(ConnectedMCPServer client);
    }

    public interface McpClient {
        void notification(NotificationPayload payload);
        void setNotificationHandler(String schemaName, java.util.function.Consumer<NotificationPayload> handler);
    }

    public interface ConnectedMCPServer {
        String name();
        String type();
        McpClient client();
    }

    public record NotificationPayload(String method, Map<String, Object> params) {}

    /** Feature gate supplier (注入). */
    @FunctionalInterface
    public interface FeatureGateSupplier {
        Object get(String name, Object defaultValue);
        default boolean getBool(String name) {
            Object v = get(name, false);
            return v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v));
        }
    }

    /** Notification handler registrar (注入). */
    @FunctionalInterface
    public interface NotificationHandlerRegistrar {
        void register(String schemaName, java.util.function.Consumer<NotificationPayload> handler);
    }

    /** Notification sender (注入). */
    @FunctionalInterface
    public interface NotificationSender {
        void send(NotificationPayload payload);
    }

    /** 5 个 experiment gates 常量. */
    public static final String GATE_REVIEW_UPSELL = "tengu_vscode_review_upsell";
    public static final String GATE_ONBOARDING = "tengu_vscode_onboarding";
    public static final String GATE_QUIET_FERN = "tengu_quiet_fern";
    public static final String GATE_VSCODE_CC_AUTH = "tengu_vscode_cc_auth";
    public static final String GATE_AUTO_MODE_STATE = "tengu_auto_mode_state";

    /** Read auto-mode state tri-state. */
    public AutoModeEnabledState readAutoModeEnabledState() {
        Object v = featureValueSupplier.get("tengu_auto_mode_config", new LinkedHashMap<>());
        if (v instanceof Map) {
            Object enabled = ((Map<?, ?>) v).get("enabled");
            if (enabled != null) {
                return AutoModeEnabledState.fromValue(enabled.toString());
            }
        }
        return null;
    }

    /** CC notifyVscodeFileUpdated — 文件编辑通知. */
    public void notifyVscodeFileUpdated(String filePath, String oldContent, String newContent) {
        if (!"ant".equals(userTypeSupplier.get())) {
            // [CD-03 返工] 显式披露：非 ant 跳过。CC vscodeSdkMcp.ts:44 同 gate；生产默认读
            // env USER_TYPE，未设/非 ant → 此处恒跳过（非静默，debug 可见）。
            if (log.isDebugEnabled()) {
                log.debug("[VSCode] notifyVscodeFileUpdated 跳过（USER_TYPE={} 非 ant，CC vscodeSdkMcp.ts:44 门控）: {}",
                    userTypeSupplier.get(), filePath);
            }
            return;
        }
        ConnectedMCPServer vscode = storedClient != null ? storedClient : clientRefSupplier.get();
        if (vscode == null) {
            // CD-02：claude-vscode client 依赖 SdkMcpTransport 真实化后接线，当前生产恒 null。
            if (log.isDebugEnabled()) {
                log.debug("[VSCode] notifyVscodeFileUpdated 跳过（claude-vscode client 不存在，CD-02 待接线）: {}", filePath);
            }
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("filePath", filePath);
        params.put("oldContent", oldContent);
        params.put("newContent", newContent);
        try {
            notificationSender.send(new NotificationPayload("file_updated", params));
            if (log.isInfoEnabled()) {
                log.info("[VSCode] notifyVscodeFileUpdated 已发送 file_updated: {}", filePath);
            }
        } catch (Exception e) {
            log.debug("[VSCode] Failed to send file_updated notification: {}", e.getMessage());
        }
    }

    /** CC setupVscodeSdkMcp — 注册 handler + 推送 experiment gates. */
    public void setupVscodeSdkMcp(java.util.List<? extends ConnectedMCPServer> sdkClients) {
        ConnectedMCPServer client = null;
        for (ConnectedMCPServer c : sdkClients) {
            if ("claude-vscode".equals(c.name())) {
                client = c;
                break;
            }
        }
        if (client == null || !"connected".equals(client.type())) {
            return;
        }
        setClient(client);

        // Register log_event handler
        handlerRegistrar.register("LogEventNotificationSchema",
            notification -> {
                Map<String, Object> params = notification.params();
                Object eventName = params.get("eventName");
                Object eventData = params.get("eventData");
                if (eventName != null) {
                    log.debug("[VSCode] log_event: {} data={}", eventName, eventData);
                    // CC logEvent(`tengu_vscode_${eventName}`, eventData)
                }
            });

        // Push experiment gates
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put(GATE_REVIEW_UPSELL, statsigGateSupplier.getBool(GATE_REVIEW_UPSELL));
        gates.put(GATE_ONBOARDING, statsigGateSupplier.getBool(GATE_ONBOARDING));
        gates.put(GATE_QUIET_FERN, featureValueSupplier.get(GATE_QUIET_FERN, false));
        gates.put(GATE_VSCODE_CC_AUTH, featureValueSupplier.get(GATE_VSCODE_CC_AUTH, false));
        AutoModeEnabledState state = readAutoModeEnabledState();
        if (state != null) {
            gates.put(GATE_AUTO_MODE_STATE, state.getValue());
        }
        notificationSender.send(new NotificationPayload("experiment_gates",
            Map.of("gates", gates)));
    }
}
