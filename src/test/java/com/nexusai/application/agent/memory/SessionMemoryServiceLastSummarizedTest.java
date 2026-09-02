package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * setLastSummarizedMessageId / getLastSummarizedMessageId 测试 ·
 * 对齐 CC services/SessionMemory/sessionMemoryUtils.ts:58/65.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 用模块级 {@code lastSummarizedMessageId}
 * 记录"session memory 摘要到哪条消息为止"。每次压缩成功后必须
 * {@code setLastSummarizedMessageId(undefined)}（autoCompact.ts:296/325、/compact:112）——
 * 因为压缩把旧消息 UUID 换掉，残留的 lastSummarizedMessageId 会让下次摘要提取
 * （sessionMemoryCompact.ts:529 getLastSummarizedMessageId）越过新消息。本测试锁定:
 * <ol>
 *   <li>初始为 undefined（Java null）</li>
 *   <li>set 后 get 返回同一值（摘要提取指针推进）</li>
 *   <li>set(undefined) 后归零（压缩成功路径的复位语义, REQ-12）</li>
 * </ol>
 *
 * <p><b>[sm-cursor-sessionize 2026-08-30]</b>：游标按 sessionId 键控——本测试用会话 "s1"，
 * 并钉死会话 A 的 set/clear 不影响会话 B（多会话隔离语义，见
 * {@code SessionMemoryCursorIsolationTest}）。
 */
@DisplayName("[IMP-08] setLastSummarizedMessageId/getLastSummarizedMessageId（压缩成功置 undefined）")
class SessionMemoryServiceLastSummarizedTest {

    private static final String SESSION = "s1";

    @AfterEach
    void tearDown() {
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
    }

    @Test
    @DisplayName("初始为 undefined（null）—— 尚无摘要")
    void initiallyNull() {
        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION)).isNull();
    }

    @Test
    @DisplayName("set 后 get 返回同一 messageId（摘要提取指针推进）")
    void setThenGet() {
        SessionMemoryService.setLastSummarizedMessageId(SESSION, "msg-0001");

        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION)).isEqualTo("msg-0001");
    }

    @Test
    @DisplayName("set(undefined) 归零 —— 压缩成功后旧 UUID 失效必须复位（REQ-12）")
    void setUndefinedResets() {
        SessionMemoryService.setLastSummarizedMessageId(SESSION, "msg-0001");

        // 压缩成功路径: setLastSummarizedMessageId(undefined)（autoCompact.ts:296/325）
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);

        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION)).isNull();
    }
}
