package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [P4] 迟到回执的结构化终态。
 *
 * <p><b>WHY</b>：桌面先答之后，渠道用户可能过几秒才回 "yes xxxxx"。此时 pending 已空：
 * 旧实现只打一条 debug「resolve miss」⇒ 无法回答「它到底是被处理了，还是号失效了」。
 * 本测试锁死：
 * <ol>
 *   <li>桌面先答 ⇒ 迟到回执返回 false，终态 = {@code already_processed}（原因 released_before_receipt）；</li>
 *   <li>渠道先答 ⇒ 终态 = {@code already_processed}（原因 resolved_by_channel），重复回执仍 false；</li>
 *   <li>未知短号 ⇒ 终态 = null（日志按 {@code expired/not_pending}）；</li>
 *   <li>⛔ 任何回执都<b>不向渠道回发</b>消息（不回复渠道）。</li>
 * </ol>
 */
@DisplayName("[P4] 迟到回执结构化终态")
class LateChannelReceiptTerminalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-p4-terminal";

    private SimpMessagingTemplate ws;
    private StompChannelPermissionCallbacks cb;

    @BeforeEach
    void setUp() {
        ws = mock(SimpMessagingTemplate.class);
        cb = new StompChannelPermissionCallbacks(ws);
    }

    private String issue(String toolUseId) {
        String id = cb.shortRequestId(SESSION, toolUseId);
        cb.sendRequest(SESSION, id, "Bash", "运行命令", JSON.createObjectNode(), toolUseId);
        return id;
    }

    @Test
    @DisplayName("桌面先答 ⇒ 迟到回执终态 already_processed（原因 released_before_receipt），且不向渠道回发")
    void desktopFirstThenLateReceiptIsTerminalAlreadyProcessed() {
        String id = issue("tool-desktop-first");
        AtomicBoolean channelHandlerCalled = new AtomicBoolean(false);
        Runnable unsubscribe = cb.onResponse(id, r -> channelHandlerCalled.set(true));

        // 桌面先答：prompter 会调用 onResponse 返回的退订 Runnable
        unsubscribe.run();

        int invocationsBefore = Mockito.mockingDetails(ws).getInvocations().size();
        boolean accepted = cb.resolve(id, "allow", "feishu");

        assertThat(accepted).as("迟到的渠道回执不得再命中").isFalse();
        assertThat(channelHandlerCalled).as("渠道 handler 不得被迟到回执触发").isFalse();
        StompChannelPermissionCallbacks.Terminal t = cb.terminalForTesting(id);
        assertThat(t).isNotNull();
        assertThat(t.state()).isEqualTo("already_processed");
        assertThat(t.reason()).isEqualTo("released_before_receipt");
        assertThat(t.sessionId()).isEqualTo(SESSION);
        assertThat(Mockito.mockingDetails(ws).getInvocations().size())
            .as("⛔ 不向渠道回发任何内容")
            .isEqualTo(invocationsBefore);
    }

    @Test
    @DisplayName("渠道先答 ⇒ 终态 already_processed（resolved_by_channel）；重复回执仍 false 且终态不变")
    void channelFirstThenDuplicateReceiptIsTerminal() {
        String id = issue("tool-channel-first");
        AtomicBoolean channelHandlerCalled = new AtomicBoolean(false);
        cb.onResponse(id, r -> channelHandlerCalled.set(true));

        assertThat(cb.resolve(id, "allow", "feishu")).isTrue();
        assertThat(channelHandlerCalled).isTrue();
        StompChannelPermissionCallbacks.Terminal t = cb.terminalForTesting(id);
        assertThat(t.state()).isEqualTo("already_processed");
        assertThat(t.reason()).isEqualTo("resolved_by_channel");
        assertThat(t.sessionId()).isEqualTo(SESSION);

        int invocationsBefore = Mockito.mockingDetails(ws).getInvocations().size();
        assertThat(cb.resolve(id, "deny", "feishu"))
            .as("重复回执不得二次生效").isFalse();
        assertThat(cb.terminalForTesting(id).reason()).isEqualTo("resolved_by_channel");
        assertThat(Mockito.mockingDetails(ws).getInvocations().size())
            .as("⛔ 不向渠道回发任何内容").isEqualTo(invocationsBefore);
    }

    @Test
    @DisplayName("未知短号 ⇒ 无终态记录（日志按 expired/not_pending），仍返回 false")
    void unknownShortIdHasNoTerminalRecord() {
        assertThat(cb.resolve("zzzzz", "allow", "feishu")).isFalse();
        assertThat(cb.terminalForTesting("zzzzz")).isNull();
    }

    @Test
    @DisplayName("短号被新请求复用 ⇒ 旧终态被清除（迟到回执不会读到上一条的终态）")
    void reissuedShortIdClearsStaleTerminal() {
        String id = issue("tool-reuse-1");
        Runnable unsubscribe = cb.onResponse(id, r -> { });
        unsubscribe.run();
        assertThat(cb.terminalForTesting(id).state()).isEqualTo("already_processed");

        // 同一短号再次发出（P2 释放后允许复用）⇒ 终态必须清掉
        cb.sendRequest(SESSION, id, "Bash", "运行命令", JSON.createObjectNode(), "tool-reuse-2");
        assertThat(cb.terminalForTesting(id)).isNull();
    }
}
