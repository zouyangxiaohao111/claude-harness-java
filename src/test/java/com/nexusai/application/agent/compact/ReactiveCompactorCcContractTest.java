package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.recovery.RecoveryState;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-14] ReactiveCompactor CC 契约测试 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/services/compact/reactiveCompact.ts}（97 行，已入库 2026-08-18）。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * 2026-08-18 起内部算法从「用户给定算法（OD-01 #3）」迁移到 CC 真源语义（reactiveCompact.ts:
 * 60-97）——本类按 CC 行为钉死契约：
 * <ol>
 *   <li><b>{@code tryReactiveCompact} 委托 {@code compactConversation}</b>（reactiveCompact.ts:75-88）
 *       ——输出为 compactConversation 统一组装（boundary → summary），<b>无 {@code [Reactive compact]}
 *       前缀、无 tail 保留</b>（全量压缩 messagesToKeep=null）</li>
 *   <li><b>ccCtx 必须注入</b>（6 参构造）；ccCtx=null → 返回 null surface（无法委托 compactConversation）</li>
 *   <li><b>{@code hasAttempted || aborted} → null</b>（reactiveCompact.ts:72 单次守卫 + 中断）</li>
 *   <li><b>compactConversation 异常 → 捕获返回 null</b>（reactiveCompact.ts:89-94 logForDebugging(warn)
 *       + logError + null）——空输入（NOT_ENOUGH）/ 摘要回调缺失（NPE）均走此通道</li>
 *   <li><b>enabled 门收敛到调用方</b>：tryReactiveCompact 内部无 enabled 检查
 *       （reactiveCompact.ts:60-97，模块存在性在调用点 query.ts:1119 判定）</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert 到用户算法（[Reactive compact] 前缀 / tail 保留 5 条 / tail_start=len−5 /
 * tool 切口保护 / transcriptPath 方案 A / null-callback fail-loud 前置门）→ 必红。
 */
class ReactiveCompactorCcContractTest {

    private static final String SESSION = "s1";

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    /** 真实 TokenEstimator 计数 · 对齐 CC microCompact.ts:164 estimateMessageTokens block 口径。 */
    private static final TokenCounter REAL_COUNTER = new TokenEstimator()::estimateMessageTokens;

    @AfterEach
    void resetStaticState() {
        // compactConversation 成功路径 markPostCompaction（非 UUID 回落进程级布尔）→ 复位防串台。
        PostCompactionState.clear(SESSION);
        CacheSafeParamsHolder.clear();
    }

    /** 记录调用的 stub 摘要回调 · 断言 summarize 被 compactConversation 委托。 */
    private static class CapturingCallback implements AutoCompactor.CompactCallback {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> capturedPrompt = new AtomicReference<>();
        final AtomicReference<List<ChatMessageDto>> capturedMessages = new AtomicReference<>();
        volatile String summaryText = "reactive-summary-stub";

        @Override
        public CompactConversation.SummaryResult summarize(String prompt, List<ChatMessageDto> messages) {
            calls.incrementAndGet();
            capturedPrompt.set(prompt);
            capturedMessages.set(messages);
            // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）
            return new CompactConversation.SummaryResult(summaryText, null);
        }
    }

    private ReactiveCompactor compactor(boolean enabled, AutoCompactor.CompactCallback callback) {
        ReactiveCompactor rc = new ReactiveCompactor(REAL_COUNTER, callback);
        rc.setEnabled(enabled);
        return rc;
    }

    /**
     * 构建 compactConversation 上下文（不设 summaryProducer）· 验证 tryReactiveCompact 的
     * 自动注入路径（ccCtx.getSummaryProducer()==null → 从 compactor.summaryProducer() 补齐，
     * 对齐 LlmAgentLoop:4827-4829 生产接线）。
     */
    private CompactConversationContext ccCtx() {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(SESSION);
        cc.setAgentId("agent-1");
        cc.setModel("claude-sonnet-4-5");
        cc.setQuerySource("main_thread");
        return cc;
    }

    private List<ChatMessageDto> userMessages(int count) {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            msgs.add(new ChatMessageDto(
                "m" + i, SESSION, Role.user, "user", "content " + i, null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
        }
        return msgs;
    }

    @Test
    @DisplayName("flag=on + 首次 413 → 委托 compactConversation 产出（boundary/summary · reactiveCompact.ts:75-88），无 [Reactive compact] 前缀、无 tail 保留")
    void flagOn_firstCallReturnsCompactedWithSummaryPlusTail() {
        CapturingCallback cb = new CapturingCallback();
        ReactiveCompactor rc = compactor(true, cb);
        List<ChatMessageDto> original = userMessages(60);

        ReactiveCompactResult compacted = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", false, original, null, ccCtx()));
        assertThat(compacted)
            .as("flag=on + 首次 413 必须返回 compacted（真值 · query.ts:1134 if (compacted)）")
            .isNotNull();
        assertThat(compacted.compactionResult()).isNotNull();

        List<ChatMessageDto> postCompact = compacted.buildPostCompactMessages();
        // CC compactConversation 组装：boundary → summary（无 tail 保留，全量压缩 messagesToKeep=null）
        assertThat(postCompact.get(0).subtype())
            .as("CC 产出首元素 = compact_boundary（非 [Reactive compact] user 摘要消息）")
            .isEqualTo(CompactBoundaryMessage.SUBTYPE_COMPACT_BOUNDARY);
        // 无 [Reactive compact] 前缀（用户算法 OD-01 #3 已删除）
        assertThat(postCompact)
            .as("CC 摘要消息不再携带 '[Reactive compact]' 前缀（OD-01 #3 已删除）")
            .noneMatch(m -> m.content() != null && m.content().contains("[Reactive compact]"));
        // 无 tail 保留：原始 60 条消息不进入输出（全量压缩 messagesToKeep=null · compact.ts:743）
        assertThat(postCompact.stream().anyMatch(m -> m.id() != null && m.id().startsWith("m")))
            .as("CC 无 tail 保留（全量压缩 messagesToKeep=null）——原始消息不输出")
            .isFalse();
        // compactConversation 确实委托摘要生产（CC streamCompactSummary）
        assertThat(cb.calls.get())
            .as("compactConversation 必须调用摘要回调一次（reactiveCompact.ts:75-88 委托 compactConversation）")
            .isEqualTo(1);

        // 第二次 hasAttempted=true → null（单次守卫 · reactiveCompact.ts:72）
        ReactiveCompactResult second = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(true, "main_thread", false, original, null, ccCtx()));
        assertThat(second)
            .as("第二次 hasAttempted=true 必须返回 null → surface（single-shot 守卫 · reactiveCompact.ts:72）")
            .isNull();
    }

    @Test
    @DisplayName("ccCtx 必须注入（6 参构造）：ccCtx=null → tryReactiveCompact 返回 null → surface（无法委托 compactConversation）")
    void ccCtxNull_returnsNullSurface() {
        CapturingCallback cb = new CapturingCallback();
        ReactiveCompactor rc = compactor(true, cb);

        // 6 参构造显式传 ccCtx=null → 无压缩上下文 → null（reactiveCompact.ts:75 需上下文委托）
        ReactiveCompactResult result = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", false, userMessages(60), null, null));
        assertThat(result)
            .as("ccCtx=null 必须返回 null → surface（调用方未构建 CompactConversationContext）")
            .isNull();
        assertThat(cb.calls.get())
            .as("ccCtx=null 不得调用 summarize（未进入 compactConversation）")
            .isZero();
    }

    @Test
    @DisplayName("空输入 → null（compactConversation NOT_ENOUGH 被捕获 → surface）；5 条 + ccCtx → 可压缩（CC 无 tail_start 门）")
    void emptyMessages_returnsNull_butNoTailStartGate() {
        CapturingCallback cb = new CapturingCallback();
        ReactiveCompactor rc = compactor(true, cb);
        CompactConversationContext cc = ccCtx();

        // 空消息 → compactConversation 前置空校验抛 NOT_ENOUGH → tryReactiveCompact 捕获 → null → surface
        ReactiveCompactResult empty = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", false, List.of(), null, cc));
        assertThat(empty).isNull();
        assertThat(cb.calls.get())
            .as("空输入不得调用 summarize（compactConversation 前置空校验 · compact.ts:397-399）")
            .isZero();

        // 5 条 + ccCtx → 非 null（CC 无 tail_start=len−5 门；旧算法 OD-01 #3「len<=5 → head 空 → null」
        // 已随算法删除——reactiveCompact.ts:60-97 只有 hasAttempted/aborted/ccCtx 门）
        ReactiveCompactResult five = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", false, userMessages(5), null, ccCtx()));
        assertThat(five)
            .as("5 条消息 + ccCtx → 委托 compactConversation 成功产出（CC 无 tail_start 门）")
            .isNotNull();
    }

    @Test
    @DisplayName("null 摘要回调 + ccCtx 注入 → 摘要生产缺失 → compactConversation 异常被捕获 → 返回 null（fail-loud → surface）")
    void nullCallback_failsLoud() {
        ReactiveCompactor rc = new ReactiveCompactor(REAL_COUNTER); // compactCallback = null
        rc.setEnabled(true);
        ReactiveCompactResult result = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", false, userMessages(60), null, ccCtx()));
        assertThat(result)
            .as("null callback → summaryProducer 缺失 → compactConversation NPE 被捕获 → 返回 null → surface（不编造 LLM 摘要）")
            .isNull();
    }

    @Test
    @DisplayName("enabled 默认 false（模块存在性 · CC REACTIVE_COMPACT flag）+ enabled 门在调用方而非 tryReactiveCompact 内部")
    void flagOff_neverCompacts() {
        ReactiveCompactor rc = compactor(false, new CapturingCallback());
        assertThat(rc.isEnabled())
            .as("ReactiveCompactor 默认 enabled 必须为 false（对齐 CC REACTIVE_COMPACT flag 默认关闭）")
            .isFalse();

        // enabled 门收敛到调用方（query.ts:1119 模块存在性在调用点判定）；tryReactiveCompact 内部
        // 无 enabled 检查（CC reactiveCompact.ts:60-97）→ ccCtx 注入时仍可压缩（非 null）。
        ReactiveCompactResult result = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", false, userMessages(60), null, ccCtx()));
        assertThat(result)
            .as("CC 无内部 enabled 门（调用方判定模块存在性）→ enabled=false + ccCtx 注入仍产出 compacted")
            .isNotNull();
    }

    @Test
    @DisplayName("aborted=true → tryReactiveCompact 返回 null（调用已中断不再恢复 · CC reactiveCompact.ts:72）")
    void abortedReturnsNull() {
        ReactiveCompactor rc = compactor(true, new CapturingCallback());
        ReactiveCompactResult result = rc.tryReactiveCompact(
            new ReactiveCompactor.TryReactiveCompactParams(false, "main_thread", true, userMessages(60), null, ccCtx()));
        assertThat(result)
            .as("aborted=true 必须返回 null（CC reactiveCompact.ts:72 aborted → 不恢复）")
            .isNull();
    }

    @Test
    @DisplayName("DRIFT-7: RecoveryState.hasAttemptedReactiveCompact 随轮重置（mark → reset → false）+ LlmAgentLoop next_turn 调用复位")
    void hasAttemptedReactiveCompact_resetsPerTurn() throws Exception {
        RecoveryState rs = new RecoveryState("test-model");
        rs.markReactiveCompact();
        assertThat(rs.isHasAttemptedReactiveCompact())
            .as("markReactiveCompact 后 must be true（single-shot 守卫 · CC query.ts:1154）")
            .isTrue();
        rs.resetReactiveCompactAttempt();
        assertThat(rs.isHasAttemptedReactiveCompact())
            .as("resetReactiveCompactAttempt 后必须 false（DRIFT-7 · CC next_turn query.ts:1721）")
            .isFalse();

        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("LlmAgentLoop 必须在 genuine next_turn 边界调用 resetReactiveCompactAttempt（DRIFT-7 接线）")
            .contains("recoveryState.resetReactiveCompactAttempt();");
    }

    @Test
    @DisplayName("loop wiring: PTL reactive compact 分支必须 gated on featureFlags().reactiveCompact() + reactiveCompactor() != null + buildPostCompactMessages 组装")
    void loopGatesReactiveCompactOnFeatureFlag() throws Exception {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("LlmAgentLoop PTL 分支必须检查 featureFlags().reactiveCompact()")
            .contains("ctx.featureFlags().reactiveCompact()");
        assertThat(source)
            .as("LlmAgentLoop PTL 分支必须检查 reactiveCompactor() != null（空值保护）")
            .contains("ctx.reactiveCompactor() != null");
        assertThat(source)
            .as("loop 必须调用 reactiveCompactor().tryReactiveCompact（对齐 CC query.ts:1120）")
            .contains(".tryReactiveCompact(");
        assertThat(source)
            .as("loop 必须经 compacted.buildPostCompactMessages() 组装输出（R6 · CC query.ts:1148）")
            .contains("compacted.buildPostCompactMessages()");
    }

    @Test
    @DisplayName("旧符号删除: react() / needsAutoCompact / SnipCompactor / tail_start 用户算法在 ReactiveCompactor 0 命中")
    void d24_legacySymbolsRemoved() throws Exception {
        String rcSource = Files.readString(
            Path.of("src/main/java/com/nexusai/application/agent/compact/ReactiveCompactor.java"));
        assertThat(rcSource)
            .as("D-24: ReactiveCompactor 必须无 needsAutoCompact 字段/record 组件声明")
            .doesNotContain("boolean needsAutoCompact");
        assertThat(rcSource)
            .as("D-24: ReactiveCompactor 必须无 react( 方法定义/调用")
            .doesNotContain(" react(List<ChatMessageDto> messages)");
        assertThat(rcSource)
            .as("D-24: ReactiveCompactor 不再嵌套 ReactiveCompactResult record（已提取为独立文件）")
            .doesNotContain("record ReactiveCompactResult");
        assertThat(rcSource)
            .as("D-24: ReactiveCompactor 不再内部调用 react(")
            .doesNotContain("react(messages)");
        assertThat(rcSource)
            .as("OD-01 #3: 用户算法移除 SnipCompactor 字段（snip-first 已删）")
            .doesNotContain("SnipCompactor");
        // 用户算法 trace 清理：tail_start / [Reactive compact] 前缀 / @Deprecated 7 参构造
        // （workspaceDir/sessionId transcriptPath 方案 A）参数与字段删除（注释可保留）。
        assertThat(rcSource)
            .as("用户算法 7 参构造已删：不得再有 workspaceDir 参数/字段")
            .doesNotContain("Path workspaceDir");
        assertThat(rcSource)
            .as("用户算法 7 参构造已删：不得再有 @Deprecated 构造（transcriptPath 方案 A 载体）")
            .doesNotContain("@Deprecated");
        String rcrSource = Files.readString(
            Path.of("src/main/java/com/nexusai/application/agent/compact/ReactiveCompactResult.java"));
        assertThat(rcrSource)
            .as("D-24: ReactiveCompactResult.java 必须无 needsAutoCompact record 组件")
            .doesNotContain("boolean needsAutoCompact");
    }
}
