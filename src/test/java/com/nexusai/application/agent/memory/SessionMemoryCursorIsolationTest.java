package com.nexusai.application.agent.memory;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sm-cursor-sessionize 2026-08-30] 多会话游标隔离测试 · 对齐项目铁律「multi-session-vs-cc-single-session」。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 单会话进程级的模块级游标
 * （lastSummarizedMessageId / extractionStartedAt / tokensAtLastExtraction /
 * sessionMemoryInitialized / lastMemoryMessageUuid）在 Web 多会话后端用 static volatile 镜像时，
 * 会话 A 的游标会被会话 B 读到/清空 —— B 的 SM 压缩静默降级、提取时机错乱、工具计数失真。
 * 本测试钉死<b>会话 A 的任何游标写入不得被会话 B 读到（stale-read）或清空（跨会话写）</b>：
 * <ol>
 *   <li>提取路径游标（lastSummarizedMessageId / lastMemoryMessageUuid / tokensAtLastExtraction /
 *       sessionMemoryInitialized）按 sessionId 键控 → B 独立</li>
 *   <li>压缩成功清空（setLastSummarizedMessageId(sessionId, null)，P0-2）只清本会话 → A 压缩不影响 B</li>
 *   <li>waitForSessionMemoryExtraction(sessionId) 只等本会话抽取（P1-1）→ A 抽取中 B 不等</li>
 * </ol>
 */
@DisplayName("[sm-cursor-sessionize] 多会话游标隔离（A 写入不被 B 读到/清空）")
class SessionMemoryCursorIsolationTest {

    private static final String SESSION_A = "session-A";
    private static final String SESSION_B = "session-B";

    @TempDir
    Path baseDir;

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.resetLastMemoryMessageUuid();
    }

    /** assistant 消息 · inputTokens 参与 tokenCountWithEstimation 估算。 */
    private static ChatMessageDto asst(String id, int tokens, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "ok", null,
            toolCalls, FinishReason.stop, tokens, 0, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ToolCallDto call(String id) {
        return new ToolCallDto(id, "Read", "{}", null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 提取路径游标 · lastSummarizedMessageId
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A 提取写 lastSummarizedMessageId → B 读不到 A 的游标（独立）")
    void lastSummarizedMessageId_isolatedPerSession() {
        // A 提取成功推进游标
        SessionMemoryService.setLastSummarizedMessageId(SESSION_A, "msg-A1");

        // B 的 lastSummarizedMessageId 独立（null，不受 A 影响）
        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION_B))
            .as("会话 B 必须读不到会话 A 的 lastSummarizedMessageId（旧 static volatile 会 stale-read）")
            .isNull();
        // A 自身读得到
        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION_A))
            .isEqualTo("msg-A1");
    }

    @Test
    @DisplayName("A 压缩成功清空（setLastSummarizedMessageId(A, null)）不影响 B 的游标（P0-2）")
    void compactClear_isolatedPerSession() {
        SessionMemoryService.setLastSummarizedMessageId(SESSION_A, "msg-A1");
        SessionMemoryService.setLastSummarizedMessageId(SESSION_B, "msg-B1");

        // A 压缩成功链清空本会话游标（AutoCompactor/CompactCommand P0-2 语义）
        SessionMemoryService.setLastSummarizedMessageId(SESSION_A, null);

        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION_A))
            .as("A 压缩成功 → A 的游标复位（undefined）")
            .isNull();
        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION_B))
            .as("B 的游标必须保留（旧 static volatile 会被 A 的压缩成功跨会话清空）")
            .isEqualTo("msg-B1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 提取路径游标 · lastMemoryMessageUuid（工具调用计数源）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A 推进 lastMemoryMessageUuid → B 的工具调用计数游标独立")
    void lastMemoryMessageUuid_isolatedPerSession() {
        SessionMemoryService.setLastMemoryMessageUuid(SESSION_A, "m0-A");

        assertThat(SessionMemoryService.getLastMemoryMessageUuid(SESSION_B))
            .as("B 的 lastMemoryMessageUuid 独立（null = 首次运行全量计数）")
            .isNull();
        assertThat(SessionMemoryService.getLastMemoryMessageUuid(SESSION_A)).isEqualTo("m0-A");

        // B 设置自己的游标不影响 A
        SessionMemoryService.setLastMemoryMessageUuid(SESSION_B, "m0-B");
        assertThat(SessionMemoryService.getLastMemoryMessageUuid(SESSION_A)).isEqualTo("m0-A");
    }

    // ════════════════════════════════════════════════════════════════════
    // 提取路径游标 · tokensAtLastExtraction / sessionMemoryInitialized
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A 记录 extraction token → B 的 hasMetUpdateThreshold 用 B 自己的基线（独立计算）")
    void updateThreshold_isolatedPerSession() {
        // A 记录抽取时上下文 14000
        SessionMemoryUtils.recordExtractionTokenCount(SESSION_A, 14_000);

        // A：增长 2000（16000-14000）< 5000 → 未达更新阈值
        assertThat(SessionMemoryUtils.hasMetUpdateThreshold(SESSION_A, 16_000)).isFalse();
        // B：无 A 的记录，基线 0，增长 16000 ≥ 5000 → 已达（旧 static volatile 会被 A 的 14000 污染 → false）
        assertThat(SessionMemoryUtils.hasMetUpdateThreshold(SESSION_B, 16_000))
            .as("B 的更新阈值必须按 B 自己的 tokensAtLastExtraction 计算（旧 static volatile 会被 A 污染）")
            .isTrue();
    }

    @Test
    @DisplayName("A 已初始化 → B 未初始化时独立计算（hasMetInitializationThreshold 各自独立）")
    void initializedFlag_isolatedPerSession() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // A 达 init 阈值（12000 ≥ 10000）→ A 标记已初始化
        assertThat(svc.shouldExtractMemory(SESSION_A, List.of(asst("a1", 12_000, List.of())))).isTrue();

        // B 未初始化（独立），低于 init 阈值不提取
        assertThat(SessionMemoryUtils.isSessionMemoryInitialized(SESSION_A)).isTrue();
        assertThat(SessionMemoryUtils.isSessionMemoryInitialized(SESSION_B))
            .as("B 必须未初始化（旧 static volatile 会让 B 跳过 init 阈值 → 提取时机错乱）")
            .isFalse();
        assertThat(svc.shouldExtractMemory(SESSION_B, List.of(asst("b1", 5_000, List.of())))).isFalse();
        assertThat(SessionMemoryUtils.isSessionMemoryInitialized(SESSION_B)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // P1-1 · waitForSessionMemoryExtraction 会话化
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A 抽取进行中 → B 的 waitForSessionMemoryExtraction 立即返回（P1-1 不跨会话阻塞）")
    void waitForExtraction_doesNotWaitOnOtherSession() {
        SessionMemoryUtils.markExtractionStarted(SESSION_A);
        assertThat(SessionMemoryUtils.getExtractionStartedAt(SESSION_A))
            .as("A 抽取标记已写入")
            .isNotNull();
        assertThat(SessionMemoryUtils.getExtractionStartedAt(SESSION_B))
            .as("B 的 extractionStartedAt 独立（无抽取进行）")
            .isNull();

        long start = System.currentTimeMillis();
        // B 无本会话抽取进行 → 立即返回（若错误等待 A 的 15s 超时，本调用会阻塞）
        SessionMemoryUtils.waitForSessionMemoryExtraction(SESSION_B);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
            .as("B 的 wait 必须立即返回（不跨会话等 A 的 15s），实际耗时=%dms", elapsed)
            .isLessThan(5_000L);

        // A 自己完成抽取 → 只清 A
        SessionMemoryUtils.markExtractionCompleted(SESSION_A);
        assertThat(SessionMemoryUtils.getExtractionStartedAt(SESSION_A)).isNull();
    }
}
