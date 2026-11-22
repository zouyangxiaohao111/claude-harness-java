package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 跨会话入站策略（P0）：发送时 decide、消费前 revalidate，同一判据。
 *
 * <p><b>为什么 DONT_ASK 不能当 bypass</b>：DONT_ASK 的语义是「拒绝所有 ask」，不是「无需确认即可
 * 自动接受外部指令」；把它归到 NON_PROMPTING 会让 auto 设置下外部会话消息在无提示的情况下直接
 * 进入模型。故 DONT_ASK/BUBBLE/null/未知一律 UNKNOWN ⇒ hold。
 *
 * <p><b>模式类（按「接收方是否会弹权限」）</b>：
 * <ul>
 *   <li>NON_PROMPTING：{@code BYPASS_PERMISSIONS}</li>
 *   <li>PROMPTING：{@code DEFAULT}/{@code ACCEPT_EDITS}/{@code PLAN}/{@code AUTO}</li>
 *   <li>UNKNOWN：{@code DONT_ASK}/{@code BUBBLE}/null/无法解析</li>
 * </ul>
 *
 * <p>设置值每次 decide 实时读 {@link SettingsSource}（E 批安装），不缓存构造时值。
 */
@Component
public class PeerInboundPolicy {

    /** 用户设置值。 */
    public enum Setting { AUTO, ACCEPT, HOLD, REFUSE }

    /** 判定结果。 */
    public enum Decision { ACCEPT, HOLD, REFUSE }

    /** 判定理由（测试与日志用）。 */
    public enum Reason {
        SELF_SENT, MODE_MATCH, MODE_MISMATCH, NO_MODE_ASSERTED,
        MODE_UNKNOWN, EXPLICIT_ACCEPT, EXPLICIT_HOLD, EXPLICIT_REFUSE,
        INVALID_SETTING,
        /**
         * 消费前复核见到服务端一次性批准标记（{@link PeerEnvelope#preApproved()}）⇒
         * 跳过策略重判、直接放行（用户已显式批准，hold 设置不得再否决）。
         */
        PRE_APPROVED,
        /**
         * 已批准的投递在消费前不再满足「目标仍在线 + 代次一致」⇒ 本次不消费。
         *
         * <p>严格 CC 语义下批准即结算（行已 accepted），回写命中终态行只记 ERROR，⛔ 不复活 pending。
         */
        TARGET_NOT_LIVE
    }

    /** 权限模式类。 */
    public enum ModeClass { NON_PROMPTING, PROMPTING, UNKNOWN }

    /**
     * 判定输入。
     *
     * @param receiverSessionId 接收方会话
     * @param setting           设置值；null = 由 {@link SettingsSource} 实时读
     * @param fromMode          发送方 attest 的模式串（CC 字面量），null = 未 attest
     * @param receiverMode      接收方当前有效模式
     * @param selfSent          是否发给自己
     */
    public record Input(String receiverSessionId, String setting, String fromMode,
                        PermissionMode receiverMode, boolean selfSent) {}

    /** 判定输出。 */
    public record Outcome(Decision decision, Reason reason, String detail) {}

    /** 设置源（E 实现：每次实时读 SettingsService）。 */
    public interface SettingsSource {
        String crossSessionInbound(String receiverSessionId);
    }

    private volatile SettingsSource settingsSource;

    /** 安装设置源（每次 decide 实时读，不冻结值）。 */
    public void setSettingsSource(SettingsSource source) {
        this.settingsSource = source;
    }

    /** 发送时判定。 */
    public Outcome decide(Input input) {
        if (input == null) {
            return new Outcome(Decision.HOLD, Reason.INVALID_SETTING, "missing policy input");
        }
        if (input.selfSent()) {
            return new Outcome(Decision.ACCEPT, Reason.SELF_SENT, "message addressed to the sender's own session");
        }

        String rawSetting = input.setting();
        if (rawSetting == null) {
            SettingsSource source = settingsSource;
            rawSetting = source == null ? null : source.crossSessionInbound(input.receiverSessionId());
        }
        Setting setting = parseSetting(rawSetting);
        if (setting == null) {
            return new Outcome(Decision.HOLD, Reason.INVALID_SETTING,
                "unknown cross-session inbound setting: " + rawSetting);
        }
        switch (setting) {
            case ACCEPT:
                return new Outcome(Decision.ACCEPT, Reason.EXPLICIT_ACCEPT, "explicit accept");
            case HOLD:
                return new Outcome(Decision.HOLD, Reason.EXPLICIT_HOLD, "explicit hold");
            case REFUSE:
                return new Outcome(Decision.REFUSE, Reason.EXPLICIT_REFUSE, "explicit refuse");
            case AUTO:
            default:
                return decideAuto(input);
        }
    }

    /** 消费前复核：与 {@link #decide} 同一判据（设置/模式可能已变化）。 */
    public Outcome revalidate(Input input) {
        return decide(input);
    }

    /**
     * 用户显式批准（「接受一次」）的复核 —— <b>不再按 auto/hold 重判一遍</b>。
     *
     * <p><b>为什么</b>：hold 设置下用户点「接受一次」，若这里复用 {@link #revalidate} 会拿
     * 「当前设置仍是 hold」再判一次 HELD ⇒ 审批永远投不出去（2026-11-20 跨会话 E2E 实测）。
     * 用户的显式批准是<b>一次性放行</b>决定；策略只用于「消息刚到达时决定进不进 hold」。
     *
     * <p>批准路径只保留一条策略否决：设置是 {@code REFUSE} ⇒ 仍拒绝（用户已声明不收）。
     * {@code ACCEPT/HOLD/AUTO/未设置/非法值} 一律放行（{@link Decision#ACCEPT}）。
     * 「一次性」「目标仍 live」「仍 pending」由调用方（router + store）保证。
     *
     * @param input 判定输入（与 {@link #decide} 同形）
     * @return ACCEPT（放行）或 REFUSE（设置=refuse）
     */
    public Outcome decideExplicitApproval(Input input) {
        if (input == null) {
            return new Outcome(Decision.ACCEPT, Reason.EXPLICIT_ACCEPT, "explicit user approval");
        }
        String rawSetting = input.setting();
        if (rawSetting == null) {
            SettingsSource source = settingsSource;
            rawSetting = source == null ? null : source.crossSessionInbound(input.receiverSessionId());
        }
        Setting setting = parseSetting(rawSetting);
        if (setting == Setting.REFUSE) {
            return new Outcome(Decision.REFUSE, Reason.EXPLICIT_REFUSE, "explicit refuse");
        }
        return new Outcome(Decision.ACCEPT, Reason.EXPLICIT_ACCEPT,
            "explicit user approval (setting=" + (setting == null ? "invalid:" + rawSetting : setting) + ")");
    }

    private Outcome decideAuto(Input input) {
        ModeClass receiverClass = modeClass(input.receiverMode());
        if (receiverClass == ModeClass.UNKNOWN) {
            return new Outcome(Decision.HOLD, Reason.MODE_UNKNOWN,
                "receiver permission mode is unknown; cannot auto-accept");
        }

        String fromMode = input.fromMode();
        if (fromMode == null || fromMode.isBlank()) {
            if (receiverClass == ModeClass.NON_PROMPTING) {
                return new Outcome(Decision.HOLD, Reason.NO_MODE_ASSERTED,
                    "sender did not attest a permission mode and receiver never prompts");
            }
            return new Outcome(Decision.ACCEPT, Reason.MODE_MATCH,
                "sender did not attest a mode; receiver is prompting so the user remains in the loop");
        }

        PermissionMode senderMode = parseMode(fromMode);
        if (senderMode == null) {
            return new Outcome(Decision.HOLD, Reason.MODE_UNKNOWN,
                "cannot parse sender permission mode: " + fromMode);
        }
        ModeClass senderClass = modeClass(senderMode);
        if (senderClass == ModeClass.UNKNOWN) {
            return new Outcome(Decision.HOLD, Reason.MODE_UNKNOWN,
                "sender permission mode class is unknown: " + fromMode);
        }
        if (senderClass != receiverClass) {
            return new Outcome(Decision.HOLD, Reason.MODE_MISMATCH,
                "sender=" + senderClass + " receiver=" + receiverClass);
        }
        return new Outcome(Decision.ACCEPT, Reason.MODE_MATCH,
            "sender=" + senderClass + " receiver=" + receiverClass);
    }

    /**
     * 模式类。
     *
     * @param mode 权限模式；null → UNKNOWN
     * @return 模式类
     */
    public static ModeClass modeClass(PermissionMode mode) {
        if (mode == null) {
            return ModeClass.UNKNOWN;
        }
        return switch (mode) {
            case BYPASS_PERMISSIONS -> ModeClass.NON_PROMPTING;
            case DEFAULT, ACCEPT_EDITS, PLAN, AUTO -> ModeClass.PROMPTING;
            // DONT_ASK（只拒绝 ask）与 BUBBLE（子代理冒泡）都不能证明「外部消息可无提示进入」
            case DONT_ASK, BUBBLE -> ModeClass.UNKNOWN;
        };
    }

    /** 设置串解析：null/blank/auto → AUTO；accept/hold/refuse；其他 → null（非法）。 */
    static Setting parseSetting(String raw) {
        if (raw == null || raw.isBlank()) {
            return Setting.AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> Setting.AUTO;
            case "accept" -> Setting.ACCEPT;
            case "hold" -> Setting.HOLD;
            case "refuse" -> Setting.REFUSE;
            default -> null;
        };
    }

    /**
     * 发送方模式串严格解析（未知 → null，绝不回落到 DEFAULT）。
     * 接受 CC 字面量与枚举名。
     */
    static PermissionMode parseMode(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "default" -> PermissionMode.DEFAULT;
            case "acceptedits", "accept_edits" -> PermissionMode.ACCEPT_EDITS;
            case "bypasspermissions", "bypass_permissions" -> PermissionMode.BYPASS_PERMISSIONS;
            case "dontask", "dont_ask" -> PermissionMode.DONT_ASK;
            case "plan" -> PermissionMode.PLAN;
            case "auto" -> PermissionMode.AUTO;
            case "bubble" -> PermissionMode.BUBBLE;
            default -> null;
        };
    }
}
