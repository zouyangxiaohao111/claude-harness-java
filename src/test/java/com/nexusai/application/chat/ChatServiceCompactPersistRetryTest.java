package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.compact.SqliteBusyRetry;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P2-9 · 2026-09-12] <b>①②④ 三条落库通道（auto / reactive / SM）接共用 BUSY_SNAPSHOT 重试</b>。
 *
 * <h2>WHY（规则九 · 测试验证意图）</h2>
 * ①②④ 在 nexusai 里<b>共用同一条</b>落库通道：{@code LlmAgentLoop.persistCompactedMessages}
 * （static，无 MessageService 引用）→ {@code AgentState.persistCompactedMessages} →
 * {@code ChatService.armRealTimePersist} 武装的 {@code compactPersistListener} →
 * {@code MessageService.appendPostCompactMessages}（唯一 DB 出口，{@code @Transactional} 且先读后写）。
 * ⑤ partial 走的是同一个出口。
 *
 * <p>因此真机那个 {@code [SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the
 * database} 对 ①②④ 与 ⑤ 是<b>同源故障</b>；修复时却只给 ⑤ 加了重试 → ①②④ 命中即失败
 * （{@code LlmAgentLoop.java:1124-1129} catch → log.error + 内存已换压缩视图、DB 未落 →
 * <b>下轮从 DB 恢复压缩前全量 → 每轮重复压缩</b>）。
 *
 * <p>本测试钉死的意图 = <b>「同源故障 = 同策略」</b>：①②④ 与 ③ manual / ⑤ partial 的
 * 重试上限、判别口径、fail-loud 全部来自同一个 {@link SqliteBusyRetry}。
 *
 * <p><b>RED 条件（改哪一行会红）</b>：
 * <ol>
 *   <li>把 {@code ChatService.armRealTimePersist} 的 listener 改回裸调
 *       {@code messageService.appendPostCompactMessages(...)} → {@link #compactPersistListenerRetriesBusySnapshot}
 *       红（第 1 次即抛、调用次数 = 1）；</li>
 *   <li>把耗尽分支改成吞异常 → {@link #compactPersistListenerFailsLoudAfterRetriesExhausted} 红；</li>
 *   <li>另起一套重试（自己写常量）→ 与 {@link SqliteBusyRetry#MAX_WRITE_ATTEMPTS} 的相等断言红。</li>
 * </ol>
 */
@DisplayName("[P2-9] ①②④ auto/reactive/SM 落库通道接共用 BUSY_SNAPSHOT 重试")
class ChatServiceCompactPersistRetryTest {

    private static final String SESSION = "sess-compact-retry";

    /** 真机异常文案（取自 3461 实例 500 响应体）。 */
    private static final String BUSY_SNAPSHOT_MSG =
        "[SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the database"
            + " (database is locked)";

    /** 生产形状：最外层 MyBatis 包装（message 不带 SQLITE_BUSY），文案在 cause 链上。 */
    private static RuntimeException busySnapshot() {
        return new RuntimeException(
            "### Error updating database. Cause: org.sqlite.SQLiteException (see cause)",
            new IllegalStateException(BUSY_SNAPSHOT_MSG));
    }

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static List<ChatMessageDto> postCompact() {
        // [boundary, summary, ...] —— 顺序由上游 buildPostCompactMessages 固定，本测试只关心落库通道
        return List.of(msg("cb-1", Role.assistant), msg("sm-1", Role.user));
    }

    /**
     * 走生产武装点（{@code armPersistenceListeners} → {@code armRealTimePersist}）→ 直接调
     * {@code AgentState.persistCompactedMessages}（= ①②④ 真实调用形状）。
     */
    private static void armChatService(MessageService messageService, AgentState state) {
        ChatService service = new ChatService();
        ReflectionTestUtils.setField(service, "messageService", messageService);
        // 与 ChatService.armRealTimePersist 生产接线同款最小武装（streamTopic/wsTemplate=null → 只落库不推送）
        service.armPersistenceListeners(state, SESSION);
    }

    @Test
    @DisplayName("[P2-9] ①②④：首次 BUSY_SNAPSHOT → 共用重试后成功（落库列表归一化返回，内存不被降级）")
    void compactPersistListenerRetriesBusySnapshot() {
        MessageService messageService = mock(MessageService.class);
        AtomicInteger calls = new AtomicInteger();
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw busySnapshot();
            }
            return inv.getArgument(1);
        });
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        armChatService(messageService, state);

        assertThat(state.isCompactPersistArmed()).as("前置：compact 落库通道已武装").isTrue();
        List<ChatMessageDto> out = state.persistCompactedMessages(postCompact());

        assertThat(calls.get())
            .as("第 1 次 BUSY_SNAPSHOT → 共用重试的换新事务第 2 次成功（旧实现此处直接抛出 → "
                + "LlmAgentLoop catch → 每轮重复压缩）")
            .isEqualTo(2);
        assertThat(out).as("返回落库归一化列表（内存据此替换，memory 与 DB id 一致）").isNotEmpty();
    }

    @Test
    @DisplayName("[P2-9] ①②④：BUSY_SNAPSHOT 耗尽 → 显式抛出（fail loud，尝试次数 = 共用上限）")
    void compactPersistListenerFailsLoudAfterRetriesExhausted() {
        MessageService messageService = mock(MessageService.class);
        AtomicInteger calls = new AtomicInteger();
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw busySnapshot();
        });
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        armChatService(messageService, state);

        assertThatThrownBy(() -> state.persistCompactedMessages(postCompact()))
            .as("耗尽后异常必须冒泡（LlmAgentLoop.persistCompactedMessages 的 catch 会 log.error "
                + "fail loud + 内存按压缩后视图继续），不得静默吞掉")
            .isInstanceOf(RuntimeException.class);

        assertThat(calls.get())
            .as("尝试次数 = 共用上限（与 ③ manual / ⑤ partial 同档；另起一套 = 策略分裂复发）")
            .isEqualTo(SqliteBusyRetry.MAX_WRITE_ATTEMPTS);
    }

    @Test
    @DisplayName("[P2-9] 非 BUSY 错误不重试：立即抛出，只尝试 1 次（不把真 bug 掩盖成偶发失败）")
    void nonBusyErrorIsNotRetriedOnCompactPersistChannel() {
        MessageService messageService = mock(MessageService.class);
        AtomicInteger calls = new AtomicInteger();
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("NOT NULL constraint failed: messages.id");
        });
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        armChatService(messageService, state);

        assertThatThrownBy(() -> state.persistCompactedMessages(postCompact()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOT NULL constraint failed");
        assertThat(calls.get()).isEqualTo(1);
    }
}
