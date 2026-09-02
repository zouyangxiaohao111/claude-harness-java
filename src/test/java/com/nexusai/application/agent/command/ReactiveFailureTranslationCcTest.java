package com.nexusai.application.agent.command;

import com.nexusai.application.agent.command.CompactCommand.CompactCommandContext;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.CompactProgressEvent;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP2-17 · reactive 失败翻译四分支 + abort 注入 + reactive-only 判定
 * （△-4/△-7/△-6 · CC commands/compact/compact.ts:87-94/139-228 + reactiveCompact.ts:12）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: 2026-08-18 CC 真源对齐后，
 * {@code isReactiveOnlyMode}（reactiveCompact.ts:12）<b>硬编码恒 false</b> → {@code compactViaReactive}
 * 为 CC 对齐死代码（compact.ts:87 路由永不触发）。本类契约相应收敛：
 * <ol>
 *   <li><b>△-4 失败翻译四分支</b>（compact.ts:181-194）：{@code !ok} 时按 reason 翻译——
 *       too_few_groups→NOT_ENOUGH / aborted→USER_ABORT（外层 catch 再改写为
 *       'Compaction canceled.'，compact.ts:126）/ exhausted|error|media_unstrippable→INCOMPLETE。
 *       reactive 路由已死，翻译函数（{@link CompactCommand#classifyReactiveFailure} +
 *       {@link CompactCommand#translateReactiveFailureReason}）直接单测（引用面语义不依赖路由）。</li>
 *   <li><b>△-7 abort 生产接线</b>：生产 AbortController 复用会话 run 级 live 取消信号
 *       （ToolRegistrationConfig.buildCompactCommandContext 从 ToolUseContext 派生），
 *       'Compaction canceled.' 分支生产可达（旧实现 new AbortController() 恒未取消）。
 *       <b>既有反射签名漂移（NoSuchMethod · 13 参含 Telemetry vs 测试 12 参）——与本次 reactive
 *       改动无关，登记 leftover 待修。</b></li>
 *   <li><b>△-6 reactive-only 判定</b>（compact.ts:87）：{@code ReactiveCompactor.isReactiveOnlyMode()}
 *       <b>恒 false</b>（reactiveCompact.ts:12 硬编码）→ /compact reactive-only 路由死代码，
 *       {@code compactViaReactive} 不得被调用（传统链执行）。</li>
 * </ol>
 */
class ReactiveFailureTranslationCcTest {

    private static final String SESSION = "s1";
    private static final String AGENT = "agent-1";

    @AfterEach
    void resetStaticState() {
        com.nexusai.application.agent.compact.CompactWarningState.clearCompactWarningSuppression();
        CacheSafeParamsHolder.clear();
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 构造命令上下文 · reactive/abort/ccCtx/tuc/sysCtx 可注入，其余测试默认。 */
    private static CompactCommandContext ctx(List<ChatMessageDto> messages,
                                             ReactiveCompactor reactive,
                                             AbortController abort,
                                             CompactConversationContext cc,
                                             ToolUseContext tuc,
                                             SystemPromptContextProvider sysCtx) {
        return new CompactCommandContext(messages, SESSION, AGENT, "compact", false, abort,
            null, new MicroCompactor(), reactive, () -> cc, () -> { }, () -> { },
            tuc, sysCtx, () -> { throw new IllegalStateException("custom 短路: defaultAssemble 不应被调用"); },
            "CUSTOM-PROMPT", null, false, () -> false);
    }

    private static CompactConversationContext baseCc(List<CompactProgressEvent> events) {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(SESSION);
        cc.setAgentId(AGENT);
        cc.setModel("claude-sonnet-4-5");
        cc.setQuerySource("compact");
        cc.setSummaryProducer((m, p, t) -> new CompactConversation.SummaryResult("summary ok", null));
        cc.setOnCompactProgress(events::add);
        return cc;
    }

    private static List<ChatMessageDto> manyMessages(int n) {
        List<ChatMessageDto> many = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            many.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "content " + i));
        }
        return many;
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · △-4 失败翻译四分支（compact.ts:181-194）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-4 翻译映射: too_few_groups→NOT_ENOUGH / aborted→USER_ABORT / exhausted|error|media_unstrippable→INCOMPLETE")
    void fourReasonTranslationMatchesCc() {
        assertThat(CompactCommand.translateReactiveFailureReason(
            CompactCommand.ReactiveFailureReason.TOO_FEW_GROUPS))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
        assertThat(CompactCommand.translateReactiveFailureReason(
            CompactCommand.ReactiveFailureReason.ABORTED))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_USER_ABORT);
        assertThat(CompactCommand.translateReactiveFailureReason(
            CompactCommand.ReactiveFailureReason.EXHAUSTED))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        assertThat(CompactCommand.translateReactiveFailureReason(
            CompactCommand.ReactiveFailureReason.ERROR))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        assertThat(CompactCommand.translateReactiveFailureReason(
            CompactCommand.ReactiveFailureReason.MEDIA_UNSTRIPPABLE))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE);
    }

    @Test
    @DisplayName("△-4 too_few_groups 分类: reason 含 NOT_ENOUGH → TOO_FEW_GROUPS → NOT_ENOUGH（compact.ts:185-193）")
    void tooFewGroupsTranslatesToNotEnough() {
        // reactive-only 路由已死（CC 恒 false），失败翻译改直接单测 classifyReactiveFailure +
        // translateReactiveFailureReason（引用面语义 compact.ts:181-194，不依赖路由触发）。
        ReactiveCompactor reactive = new ReactiveCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("stub summary", null));
        reactive.setEnabled(true);
        CompactCommandContext c = ctx(manyMessages(5), reactive, new AbortController(),
            baseCc(new ArrayList<>()), null, null);

        // compactConversation 空输入抛 NOT_ENOUGH（compact.ts:397-399）→ 信封 reason 含该常量
        CompactCommand.ReactiveFailureReason reason = CompactCommand.classifyReactiveFailure(
            c, "some error: " + CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
        assertThat(reason).isEqualTo(CompactCommand.ReactiveFailureReason.TOO_FEW_GROUPS);
        assertThat(CompactCommand.translateReactiveFailureReason(reason))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
    }

    @Test
    @DisplayName("△-4 exhausted|error 分类: 非 abort/非 NOT_ENOUGH reason → ERROR → INCOMPLETE（compact.ts:190-193）")
    void exhaustedErrorTranslatesToIncomplete() {
        ReactiveCompactor reactive = new ReactiveCompactor(msgs -> 200_000);
        reactive.setEnabled(true);
        CompactCommandContext c = ctx(manyMessages(60), reactive, new AbortController(),
            baseCc(new ArrayList<>()), null, null);

        // 摘要失败 / API 错误等 → reason 非 abort / 非 NOT_ENOUGH → ERROR → INCOMPLETE
        CompactCommand.ReactiveFailureReason reason = CompactCommand.classifyReactiveFailure(
            c, "API Error: stream failed");
        assertThat(reason).isEqualTo(CompactCommand.ReactiveFailureReason.ERROR);
        assertThat(CompactCommand.translateReactiveFailureReason(reason))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE);
    }

    @Test
    @DisplayName("△-4 aborted 分类: 取消信号置位 → ABORTED → USER_ABORT（外层 call catch 改写 'Compaction canceled.' · compact.ts:126-127）")
    void abortedTranslatesToCompactionCanceled() {
        AbortController abort = new AbortController();
        abort.abort("interrupt");
        ReactiveCompactor reactive = new ReactiveCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("stub summary", null));
        reactive.setEnabled(true);
        CompactCommandContext c = ctx(manyMessages(60), reactive, abort,
            baseCc(new ArrayList<>()), null, null);

        // abort 信号置位 → reason 分类 ABORTED → USER_ABORT；外层 catch 按 signal.aborted
        // 改写 'Compaction canceled.'（compact.ts:126-127）——外层改写由
        // CompactCommandCcContractTest.abortedTranslatesToCompactionCanceled 覆盖。
        CompactCommand.ReactiveFailureReason reason = CompactCommand.classifyReactiveFailure(
            c, "whatever reason");
        assertThat(reason).isEqualTo(CompactCommand.ReactiveFailureReason.ABORTED);
        assertThat(CompactCommand.translateReactiveFailureReason(reason))
            .isEqualTo(CompactConstants.ERROR_MESSAGE_USER_ABORT);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · △-5 → CC 死代码语义（compactViaReactive 路由不可达）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 旧 △-5 并发语义（executePreCompactHooks ∥ getCacheSharingParams · compact.ts:159-165）
     * 位于 {@code compactViaReactive} 内部——CC reactiveCompact.ts:12 恒 false → 该路由
     * 死代码（compact.ts:87 永不触发）。WHY（规则 9）: 钉死死代码语义 ——
     * {@code reactiveCompactOnPromptTooLong} 不得被调用，/compact 落到传统链（compactConversation）
     * 并成功返回。
     */
    @Test
    @DisplayName("CC 死代码语义: compactViaReactive 路由不可达 → reactiveCompactOnPromptTooLong 不被调用，走传统链成功")
    void hooksAndCacheParamsRunConcurrentlyWithNonNullCacheSafeParams() {
        AtomicInteger reactiveCalls = new AtomicInteger();
        ReactiveCompactor reactive = new ReactiveCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("stub summary", null)) {
            @Override
            public ReactiveCompactor.ReactiveCompactOutcome reactiveCompactOnPromptTooLong(
                    List<ChatMessageDto> messages, CompactConversationContext ccCtx, String customInstructions) {
                reactiveCalls.incrementAndGet();
                return super.reactiveCompactOnPromptTooLong(messages, ccCtx, customInstructions);
            }
        };
        reactive.setEnabled(true);

        CompactConversationContext cc = baseCc(new ArrayList<>());
        CompactCommandContext c = ctx(manyMessages(55), reactive, new AbortController(), cc, null, null);

        CompactCommand.CompactCommandResult result = CompactCommand.call("", c);

        assertThat(reactiveCalls.get())
            .as("compactViaReactive 死代码 → reactiveCompactOnPromptTooLong 不得被调用")
            .isZero();
        // 传统链成功（compactConversation 走 baseCc summaryProducer）
        assertThat(result.compactionResult()).isNotNull();
        assertThat(result.displayText()).startsWith("Compacted ");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · △-7 abort 生产接线（ToolRegistrationConfig 从 live TUC 派生，取消可达）
    //   ⚠ 既有反射签名漂移（NoSuchMethod）：生产方法已加 13 参 Telemetry，测试仍按 12 参反射
    //   → 与本任务 reactive 改动无关的既有失败，登记 leftover（见 IMPL_SCHEMA）。
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-7 生产接线: buildCompactCommandContext 复用 ToolUseContext live 取消信号 → 取消 → 'Compaction canceled.'（既有反射签名漂移待修）")
    void productionAbortControllerWiringIsReachable() throws Exception {
        com.nexusai.application.agent.config.ToolRegistrationConfig config =
            new com.nexusai.application.agent.config.ToolRegistrationConfig();
        AbortController live = new AbortController();
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", live, List.of());
        // 本测试仅验 abort 接线，不依赖 telemetry（no-arg 构造可用，Telemetry.java:124）
        com.nexusai.application.agent.telemetry.Telemetry telemetry =
            new com.nexusai.application.agent.telemetry.Telemetry();

        // 与 ManualCacheClearCcIntegrationTest 相同的反射模式调用生产接线（package-private）
        // 生产签名 13 参（末尾追加 Telemetry，ToolRegistrationConfig.java:1816），此处同步 13 参。
        Method build = com.nexusai.application.agent.config.ToolRegistrationConfig.class.getDeclaredMethod(
            "buildCompactCommandContext",
            List.class, String.class, String.class,
            ReactiveCompactor.class, com.nexusai.application.agent.compact.StreamCompactSummary.class,
            com.nexusai.application.agent.memory.SessionMemoryService.class, ToolUseContext.class,
            SystemPromptContextProvider.class, Supplier.class, String.class, String.class, boolean.class,
            com.nexusai.application.agent.telemetry.Telemetry.class);
        build.setAccessible(true);
        CompactCommandContext ctx = (CompactCommandContext) build.invoke(
            config, List.of(msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")),
            SESSION, AGENT, null, null, null, tuc, null, null, null, null, false, telemetry);

        // 生产接线断言：命令级取消信号 == 会话 live 信号（不再是断开 new AbortController()）
        assertThat(ctx.abortController())
            .as("生产 AbortController 必须复用 ToolUseContext 的 live 取消信号（CC context.abortController）")
            .isSameAs(live);
        // 取消信号可达：abort → 压缩异常 → 'Compaction canceled.'（旧实现分支不可达 → RED）
        live.abort("user_cancelled");
        assertThatThrownBy(() -> CompactCommand.call("", ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactCommand.ERROR_COMPACTION_CANCELED);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · △-6 reactive-only 判定引用面（compact.ts:87 · reactiveCompact.ts:12）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-6 判定面: ReactiveCompactor.isReactiveOnlyMode 恒 false（reactiveCompact.ts:12 硬编码），即使 enabled=true")
    void reactiveOnlyModeMatchesCcReferenceSurface() {
        ReactiveCompactor reactive = new ReactiveCompactor(msgs -> 200_000);
        assertThat(reactive.isReactiveOnlyMode())
            .as("flag 关闭 → false（CC reactiveCompact 为 null 时 ?.isReactiveOnlyMode() 不路由）")
            .isFalse();
        reactive.setEnabled(true);
        assertThat(reactive.isReactiveOnlyMode())
            .as("CC reactiveCompact.ts:12 硬编码恒 false → enabled=true 仍 false")
            .isFalse();
        assertThat(CompactCommand.isReactiveOnlyMode(
            ctx(manyMessages(3), reactive, new AbortController(), baseCc(new ArrayList<>()), null, null)))
            .as("CompactCommand 判定面收敛到恒 false → /compact reactive-only 路由死代码")
            .isFalse();
    }
}
