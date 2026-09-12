package com.nexusai.application.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-17 · 2026-09-11] {@code LlmAgentLoop.persistCompactedMessages} 未武装时<b>不得报假成功</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：{@code AgentState.persistCompactedMessages}
 * 在 {@code compactPersistListener == null} 时<b>原样返回入参</b>（AgentState.java 「未武装 → 原样返回」），
 * 即<b>根本没落库</b>；旧实现却无条件 {@code log.info("[compact-persist] compact 结果已 append-only 落库")}
 * —— 运维从日志<b>无法区分</b>「真落库」与「没落库」，而同语义的 {@code CompactCommand.applyResultToState}
 * 对同情形已有显式失败（{@code ApplyOutcome.NO_PERSIST_CHANNEL} + 用户可见告警）。本测试锁住新的显式失败：
 * <ol>
 *   <li>未武装 → ERROR 级「无落库通道 … 未写入历史」日志 + 内存仍替换（不阻断主循环）；</li>
 *   <li>已武装 → 走监听器（落库）+ 内存 = 监听器归一化返回，且<b>不得</b>出现无落库通道告警。</li>
 * </ol>
 *
 * <p><b>RED tooth</b>：删掉 {@code if (!state.isCompactPersistArmed())} 分支 → 第 1 条断言红
 * （未武装路径不再有 ERROR 日志、且继续打「已 append-only 落库」假成功）。
 */
@DisplayName("[P2-17] compact 落库未武装 → fail-loud（不报假成功）")
class LlmAgentLoopCompactPersistFailLoudTest {

    private static final String SESSION_KEY = "sess-p217";

    private Logger loopLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        loopLogger = (Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        previousLevel = loopLogger.getLevel();
        loopLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        loopLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        loopLogger.detachAppender(appender);
        appender.stop();
        loopLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("未武装 compactPersistListener → ERROR 显式失败日志（区分于「已落库」假成功）+ 内存仍替换")
    void unarmed_failsLoud_andStillReplacesMemory() throws Exception {
        AgentState state = new AgentState("sys", SESSION_KEY, null);
        state.replaceMessages(List.of(msg("old-1", "压缩前")));
        // 不调 setCompactPersistListener → 未武装（= 无落库通道）

        List<ChatMessageDto> postCompact = List.of(
            msg("boundary-1", "boundary"),
            msg("summary-1", "summary"));

        invokePersistCompactedMessages(state, postCompact);

        // ① 内存替换（本 run 请求面用压缩后视图，不阻断主循环）
        assertThat(state.rawMessages())
            .as("落库不可用时内存仍须替换为压缩后视图（服务本 run 的请求面）")
            .hasSize(2);
        assertThat(state.rawMessages().get(0).id()).isEqualTo("boundary-1");

        // ② 显式失败：ERROR 级日志明确「未写入历史」（旧实现的 log.info「已 append-only 落库」= 假成功）
        List<String> errors = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertThat(errors)
            .as("未武装必须 ERROR fail-loud（审计 P2-17：旧实现无条件 log.info 宣告已落库）")
            .anyMatch(m -> m.contains("无落库通道") && m.contains("未写入历史"));
        assertThat(errors)
            .as("失败日志必须带会话标识（可定位是哪次压缩没落库）")
            .anyMatch(m -> m.contains(SESSION_KEY));

        // ③ 反向：绝不得同时宣告「已 append-only 落库」
        assertThat(appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList())
            .as("未落库时不得出现「已 append-only 落库」的成功宣告（假成功即本项缺陷）")
            .noneMatch(m -> m.contains("已 append-only 落库"));
    }

    @Test
    @DisplayName("已武装 compactPersistListener → 走落库通道 + 内存 = 归一化返回 + 无假告警")
    void armed_persistsThroughListener() throws Exception {
        AgentState state = new AgentState("sys", SESSION_KEY, null);
        state.replaceMessages(List.of(msg("old-1", "压缩前")));
        List<ChatMessageDto> postCompact = List.of(
            msg("boundary-1", "boundary"),
            msg("summary-1", "summary"));
        // 落库通道返回「DB 归一化」列表（模拟 MessageService.appendPostCompactMessages 的 id/sessionId 落定）
        List<ChatMessageDto> normalized = List.of(
            msg("db-1", "boundary"),
            msg("db-2", "summary"));
        AtomicReference<List<ChatMessageDto>> persisted = new AtomicReference<>();
        state.setCompactPersistListener(msgs -> {
            persisted.set(msgs);
            return normalized;
        });

        invokePersistCompactedMessages(state, postCompact);

        assertThat(persisted.get())
            .as("已武装必须把 post-compact 消息集交给落库通道")
            .isEqualTo(postCompact);
        assertThat(state.rawMessages())
            .as("内存必须用落库返回的归一化列表覆盖（memory 与 DB id 一致）")
            .isEqualTo(normalized);
        assertThat(appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage)
            .toList())
            .as("已武装路径不得出现无落库通道告警（误报）")
            .noneMatch(m -> m.contains("无落库通道"));
        assertThat(appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList())
            .as("真落库路径保留成功日志")
            .anyMatch(m -> m.contains("已 append-only 落库"));
    }

    /** 反射驱动私有静态方法（与生产调用点同签名）。 */
    private static void invokePersistCompactedMessages(AgentState state, List<ChatMessageDto> postCompact)
            throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod(
            "persistCompactedMessages", AgentState.class, List.class);
        m.setAccessible(true);
        m.invoke(null, state, postCompact);
    }

    private static ChatMessageDto msg(String id, String content) {
        return new ChatMessageDto(id, SESSION_KEY, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
    }
}
