package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.mcp.ChannelNotificationGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP-05 Y2 · channel method 字符串三处不一致统一（impl-I-3 T5）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: InteractiveHandler 的
 * {@code CHANNEL_PERMISSION_REQUEST_METHOD} 必须与 CC 真源
 * (channelNotification.ts L85-86 {@code 'notifications/claude/channel/permission_request'})
 * 完全一致，且<b>单点收敛</b>到 {@link ChannelNotificationGate#CHANNEL_PERMISSION_REQUEST_METHOD}
 * —— 否则服务端按协议名推送的 structured permission 事件与 Java 侧期望的 method 错配，
 * 通道权限回复静默丢失（CC 注释「server parses the user's reply and emits this event」）。
 */
class InteractiveHandlerChannelMethodTest {

    @Test
    @DisplayName("CHANNEL_PERMISSION_REQUEST_METHOD 带 notifications/claude/ 前缀（CC 全值）")
    void permissionRequestMethod_hasProtocolPrefix() {
        // CC channelNotification.ts:85-86 CHANNEL_PERMISSION_REQUEST_METHOD =
        //   'notifications/claude/channel/permission_request'。旧裸值 'channel/permission_request'
        //   缺前缀，服务端 structured permission 事件无法按 method 分发 → 通道审批死链。
        assertThat(InteractiveHandler.CHANNEL_PERMISSION_REQUEST_METHOD)
            .as("InteractiveHandler.CHANNEL_PERMISSION_REQUEST_METHOD 必须以 notifications/claude/ 开头（CC L85-86）")
            .startsWith("notifications/claude/");
    }

    @Test
    @DisplayName("CHANNEL_PERMISSION_REQUEST_METHOD 与 Gate 单点收敛（防再次漂移）")
    void permissionRequestMethod_singleSourcedFromGate() {
        // 单点收敛：InteractiveHandler 该常量必须引用 ChannelNotificationGate 的同名常量，
        // 两处语义同一来源，未来协议名变更只改 Gate 一处。
        assertThat(InteractiveHandler.CHANNEL_PERMISSION_REQUEST_METHOD)
            .as("InteractiveHandler 引用 ChannelNotificationGate.CHANNEL_PERMISSION_REQUEST_METHOD")
            .isEqualTo(ChannelNotificationGate.CHANNEL_PERMISSION_REQUEST_METHOD);
    }

    @Test
    @DisplayName("Gate 两个 method 常量与 CC 真源全值一致")
    void gateMethodConstants_matchCcFullValues() {
        // CC channelNotification.ts:62-63 CHANNEL_PERMISSION_METHOD = 'notifications/claude/channel/permission'
        // CC channelNotification.ts:85-86 CHANNEL_PERMISSION_REQUEST_METHOD = 'notifications/claude/channel/permission_request'
        assertThat(ChannelNotificationGate.CHANNEL_PERMISSION_METHOD)
            .isEqualTo("notifications/claude/channel/permission");
        assertThat(ChannelNotificationGate.CHANNEL_PERMISSION_REQUEST_METHOD)
            .isEqualTo("notifications/claude/channel/permission_request");
        // 三处互不冲突：permission vs permission_request 是两个不同 method
        assertThat(ChannelNotificationGate.CHANNEL_PERMISSION_METHOD)
            .isNotEqualTo(ChannelNotificationGate.CHANNEL_PERMISSION_REQUEST_METHOD);
    }
}
