package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站策略矩阵：selfSent / 显式三值 / auto 模式类 / DONT_ASK 不得当 bypass / 设置实时读。
 */
@DisplayName("PeerInboundPolicy · 模式矩阵")
class PeerInboundPolicyTest {

    private final PeerInboundPolicy policy = new PeerInboundPolicy();

    private PeerInboundPolicy.Outcome decide(String setting, String fromMode,
                                             PermissionMode receiverMode, boolean selfSent) {
        return policy.decide(new PeerInboundPolicy.Input(
            "sess-bbbbbbb2", setting, fromMode, receiverMode, selfSent));
    }

    @Test
    @DisplayName("selfSent 恒 ACCEPT（不受设置影响）")
    void selfSentAlwaysAccept() {
        PeerInboundPolicy.Outcome outcome = decide("refuse", null, PermissionMode.DEFAULT, true);
        assertThat(outcome.decision()).isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(outcome.reason()).isEqualTo(PeerInboundPolicy.Reason.SELF_SENT);
    }

    @Test
    @DisplayName("显式 accept/hold/refuse 直接生效；非法设置 → HOLD INVALID_SETTING")
    void explicitSettings() {
        assertThat(decide("accept", null, PermissionMode.DEFAULT, false).decision())
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(decide("hold", "default", PermissionMode.DEFAULT, false).decision())
            .isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(decide("refuse", "default", PermissionMode.DEFAULT, false).decision())
            .isEqualTo(PeerInboundPolicy.Decision.REFUSE);

        PeerInboundPolicy.Outcome invalid = decide("sometimes", "default", PermissionMode.DEFAULT, false);
        assertThat(invalid.decision()).isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(invalid.reason()).isEqualTo(PeerInboundPolicy.Reason.INVALID_SETTING);
    }

    @Test
    @DisplayName("auto + 发送方未 attest：接收方 NON_PROMPTING → HOLD；PROMPTING → ACCEPT")
    void autoWithoutSenderMode() {
        PeerInboundPolicy.Outcome nonPrompting = decide("auto", null,
            PermissionMode.BYPASS_PERMISSIONS, false);
        assertThat(nonPrompting.decision()).isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(nonPrompting.reason()).isEqualTo(PeerInboundPolicy.Reason.NO_MODE_ASSERTED);

        PeerInboundPolicy.Outcome prompting = decide("auto", null, PermissionMode.DEFAULT, false);
        assertThat(prompting.decision()).isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(prompting.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_MATCH);
    }

    @Test
    @DisplayName("auto + 模式类匹配 → ACCEPT；不匹配 → HOLD MODE_MISMATCH")
    void autoModeClasses() {
        assertThat(decide("auto", "bypassPermissions", PermissionMode.BYPASS_PERMISSIONS, false).decision())
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(decide("auto", "acceptEdits", PermissionMode.PLAN, false).decision())
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);

        PeerInboundPolicy.Outcome mismatch = decide("auto", "default",
            PermissionMode.BYPASS_PERMISSIONS, false);
        assertThat(mismatch.decision()).isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(mismatch.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_MISMATCH);
    }

    @Test
    @DisplayName("DONT_ASK / BUBBLE / null 一律 UNKNOWN → auto 下 HOLD")
    void unknownModesHold() {
        for (PermissionMode receiver : new PermissionMode[]{
            PermissionMode.DONT_ASK, PermissionMode.BUBBLE, null}) {
            PeerInboundPolicy.Outcome outcome = decide("auto", "bypassPermissions", receiver, false);
            assertThat(outcome.decision()).as("receiver=%s", receiver)
                .isEqualTo(PeerInboundPolicy.Decision.HOLD);
            assertThat(outcome.reason()).as("receiver=%s", receiver)
                .isEqualTo(PeerInboundPolicy.Reason.MODE_UNKNOWN);
        }
    }

    @Test
    @DisplayName("发送方模式串无法解析 → HOLD MODE_UNKNOWN（不得回落 DEFAULT）")
    void unparsableSenderMode() {
        PeerInboundPolicy.Outcome outcome = decide("auto", "yolo", PermissionMode.DEFAULT, false);
        assertThat(outcome.decision()).isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(outcome.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_UNKNOWN);
    }

    @Test
    @DisplayName("设置源每次实时读，不缓存构造时值")
    void settingsSourceIsLive() {
        String[] current = {"hold"};
        policy.setSettingsSource(sessionId -> current[0]);
        assertThat(decide(null, "default", PermissionMode.DEFAULT, false).decision())
            .isEqualTo(PeerInboundPolicy.Decision.HOLD);
        current[0] = "accept";
        assertThat(decide(null, "default", PermissionMode.DEFAULT, false).decision())
            .as("设置变化后立刻生效")
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
    }
}
