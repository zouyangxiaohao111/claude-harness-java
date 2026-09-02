package com.nexusai.application.agent.command;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.command.CompactCommand.CompactCommandContext;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.CompactProgressEvent;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-10 · /compact slash command 契约测试 · 对齐 CC commands/compact/compact.ts:40-137。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-10 的目标是恢复用户显式 /compact
 * （CC slash command 语义，非 Tool）——REQ-17/REQ-12/REQ-21。本测试逐条验证 IMP-10 §5 验收：
 * <ol>
 *   <li>命令路由集成测试：UserInputDispatcher 注册 /compact SLASH_COMMAND handler（INV-14）</li>
 *   <li>错误翻译四分支：aborted→'Compaction canceled.' / NOT_ENOUGH→原样 / INCOMPLETE→原样 /
 *       其他→'Error during compaction: …'（compact.ts:125-135）</li>
 *   <li>displayText 单测（buildDisplayText，compact.ts:230-248）</li>
 *   <li>SM 优先集成测试（无 customInstructions → trySessionMemoryCompaction 成功收尾链，
 *       compact.ts:58-82，REQ-12）</li>
 *   <li>reactive-only 测试（isReactiveOnlyMode 恒 false + compactViaReactive 死代码 · CC
 *       reactiveCompact.ts:12）</li>
 * </ol>
 */
class CompactCommandCcContractTest {

    private static final String SESSION = "s1";
    private static final String AGENT = "agent-1";

    @AfterEach
    void resetStaticState() {
        // [sm-cursor-sessionize] 清本会话游标（SESSION="s1"）
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        CompactWarningState.clearCompactWarningSuppression();
        // SESSION="s1" 非 UUID → 方案 1b 走回落进程级单布尔（clear 复位之）；会话级隔离由
        // PostCompactionStateTest 覆盖，本测试仅清回落布尔防跨用例污染。
        PostCompactionState.clear(SESSION);
        CacheSafeParamsHolder.clear();
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 构造命令上下文 · 可注入 SM/reactive/producer/abort，其余用测试默认（cacheSafeParams 原料缺省 null）。 */
    private static CompactCommandContext ctx(List<ChatMessageDto> messages,
                                            SessionMemoryService sm,
                                            ReactiveCompactor reactive,
                                            CompactConversation.SummaryProducer producer,
                                            List<CompactProgressEvent> events,
                                            AbortController abort,
                                            Runnable notifyCompaction) {
        return ctx(messages, sm, reactive, producer, events, abort, notifyCompaction,
            null, null, null, null, false, () -> true);
    }

    /** 构造命令上下文（全参数）· [RES-R1] cacheSafeParams 原料可注入（toolUseContext/sysCtx/defaultAssemble/custom）。 */
    private static CompactCommandContext ctx(List<ChatMessageDto> messages,
                                            SessionMemoryService sm,
                                            ReactiveCompactor reactive,
                                            CompactConversation.SummaryProducer producer,
                                            List<CompactProgressEvent> events,
                                            AbortController abort,
                                            Runnable notifyCompaction,
                                            ToolUseContext tuc,
                                            SystemPromptContextProvider sysCtx,
                                            Supplier<SystemPrompt> defaultAssemble,
                                            String customSystemPrompt,
                                            boolean useGlobalCacheScope,
                                            java.util.function.BooleanSupplier promptCacheBreakDetectionGate) {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(SESSION);
        cc.setAgentId(AGENT);
        cc.setModel("claude-sonnet-4-5");
        cc.setQuerySource("compact");
        cc.setSummaryProducer(producer);
        cc.setOnCompactProgress(events::add);
        return new CompactCommandContext(messages, SESSION, AGENT, "compact", false, abort,
            sm, new MicroCompactor(), reactive, () -> cc, notifyCompaction, () -> { },
            tuc, sysCtx, defaultAssemble, customSystemPrompt, null, useGlobalCacheScope,
            promptCacheBreakDetectionGate);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · 命令路由集成测试（INV-14）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("UserInputDispatcher 注册 /compact SLASH_COMMAND handler，路由 args (INV-14)")
    void userInputDispatcherRoutesCompactSlashCommand() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        AtomicReference<String> invokedArgs = new AtomicReference<>("__unset__");
        dispatcher.registerSlashCommand("compact", invokedArgs::set);

        UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/compact summarize the whole conversation");

        assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(r.routedTo()).isEqualTo("compact");
        assertThat(r.payload()).isEqualTo("summarize the whole conversation");
        assertThat(invokedArgs.get()).isEqualTo("summarize the whole conversation");
    }

    @Test
    @DisplayName("/compact 无参数 → handler 收到空 args")
    void userInputDispatcherRoutesCompactWithoutArgs() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        AtomicReference<String> invokedArgs = new AtomicReference<>("__unset__");
        dispatcher.registerSlashCommand("compact", invokedArgs::set);

        dispatcher.dispatch("/compact");

        assertThat(invokedArgs.get()).isEqualTo("");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · 错误翻译四分支（compact.ts:125-135）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("aborted → 'Compaction canceled.' (compact.ts:127)")
    void abortedTranslatesToCompactionCanceled() {
        AbortController abort = new AbortController();
        abort.abort("interrupt");
        CompactCommandContext c = ctx(List.of(msg("m1", Role.user, "hi")), null, null,
            (m, p, t) -> { throw new IllegalArgumentException("any error"); },
            new ArrayList<>(), abort, () -> { });

        assertThatThrownBy(() -> CompactCommand.call("", c))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Compaction canceled.");
    }

    @Test
    @DisplayName("NOT_ENOUGH → 原样重抛 (compact.ts:128-129)")
    void notEnoughRethrownAsIs() {
        CompactCommandContext c = ctx(List.of(msg("m1", Role.user, "hi")), null, null,
            (m, p, t) -> { throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES); },
            new ArrayList<>(), new AbortController(), () -> { });

        assertThatThrownBy(() -> CompactCommand.call("", c))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
    }

    @Test
    @DisplayName("INCOMPLETE → 原样重抛 (compact.ts:130-131)")
    void incompleteRethrownAsIs() {
        CompactCommandContext c = ctx(List.of(msg("m1", Role.user, "hi")), null, null,
            (m, p, t) -> { throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE); },
            new ArrayList<>(), new AbortController(), () -> { });

        assertThatThrownBy(() -> CompactCommand.call("", c))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE);
    }

    @Test
    @DisplayName("其他错误 → 'Error during compaction: …' (compact.ts:132-134)")
    void otherErrorWrappedWithPrefix() {
        CompactCommandContext c = ctx(List.of(msg("m1", Role.user, "hi")), null, null,
            (m, p, t) -> { throw new IllegalArgumentException("boom"); },
            new ArrayList<>(), new AbortController(), () -> { });

        assertThatThrownBy(() -> CompactCommand.call("", c))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Error during compaction: boom");
    }

    @Test
    @DisplayName("空消息（boundary 剥离后）→ 'No messages to compact' (compact.ts:48-50)")
    void emptyMessagesThrowNoMessagesToCompact() {
        CompactCommandContext c = ctx(List.of(), null, null,
            (m, p, t) -> { throw new IllegalStateException("should not be called"); },
            new ArrayList<>(), new AbortController(), () -> { });

        assertThatThrownBy(() -> CompactCommand.call("", c))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No messages to compact");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · displayText 单测（compact.ts:230-248 buildDisplayText）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildDisplayText: 非 verbose 无 userDisplayMessage → 基础文本（默认绑定 ctrl+o，CC chordToString 归一化小写）")
    void displayTextBaseNonVerbose() {
        assertThat(CompactCommand.buildDisplayText(false, null))
            .isEqualTo("Compacted (ctrl+o to see full summary)");
    }

    @Test
    @DisplayName("buildDisplayText: 键位映射——自定义绑定 → 动态解析显示（getShortcutDisplay 命中）")
    void displayTextCustomBindingMapped() {
        List<CompactCommand.ShortcutBinding> bindings = List.of(
            new CompactCommand.ShortcutBinding("Global", "app:toggleTranscript", "ctrl+shift+o"));
        assertThat(CompactCommand.buildDisplayText(false, null, bindings))
            .isEqualTo("Compacted (ctrl+shift+o to see full summary)");
    }

    @Test
    @DisplayName("buildDisplayText: fallback——action 未绑定 → ctrl+o（shortcutFormat.ts:45-61）")
    void displayTextFallbackWhenActionUnbound() {
        List<CompactCommand.ShortcutBinding> bindings = List.of(
            new CompactCommand.ShortcutBinding("Global", "app:otherAction", "ctrl+k"));
        assertThat(CompactCommand.buildDisplayText(false, null, bindings))
            .isEqualTo("Compacted (ctrl+o to see full summary)");
    }

    @Test
    @DisplayName("buildDisplayText: 用户覆盖优先——findLast 命中（resolver.ts:73-76）")
    void displayTextUserOverrideWins() {
        List<CompactCommand.ShortcutBinding> bindings = List.of(
            new CompactCommand.ShortcutBinding("Global", "app:toggleTranscript", "ctrl+o"),
            new CompactCommand.ShortcutBinding("Global", "app:toggleTranscript", "ctrl+shift+o"));
        assertThat(CompactCommand.buildDisplayText(false, null, bindings))
            .isEqualTo("Compacted (ctrl+shift+o to see full summary)");
    }

    @Test
    @DisplayName("getShortcutDisplay: 命中返回 chord / 未命中返回 fallback（shortcutFormat.ts:38-63）")
    void shortcutDisplayResolveAndFallback() {
        List<CompactCommand.ShortcutBinding> bindings = List.of(
            new CompactCommand.ShortcutBinding("Global", "app:toggleTranscript", "ctrl+o"));
        assertThat(CompactCommand.getShortcutDisplay("app:toggleTranscript", "Global", "ctrl+o", bindings))
            .as("命中 → chord 展示串")
            .isEqualTo("ctrl+o");
        assertThat(CompactCommand.getShortcutDisplay("app:unknown", "Global", "ctrl+o", bindings))
            .as("未命中 → fallback")
            .isEqualTo("ctrl+o");
    }

    @Test
    @DisplayName("buildDisplayText: 非 verbose + userDisplayMessage → 追加换行")
    void displayTextWithUserMessage() {
        assertThat(CompactCommand.buildDisplayText(false, "summary detail"))
            .isEqualTo("Compacted (ctrl+o to see full summary)\nsummary detail");
    }

    @Test
    @DisplayName("buildDisplayText: verbose → 省略 shortcut 提示")
    void displayTextVerbose() {
        assertThat(CompactCommand.buildDisplayText(true, "detail"))
            .isEqualTo("Compacted detail");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3.5 · upgradeMessage（[IMP-A3-4 · OPD-CM5-A-17]，对齐 CC
    //   contextWindowUpgradeCheck.ts:9-47 getAvailableUpgrade/getUpgradeMessage +
    //   check1mAccess.ts:46-72 check1mAccess + compact.ts:234-245 buildDisplayText 第 3 段）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getUpgradeMessage('tip'): 当前模型设置=opus 且 1M 访问可用 → Opus 1M 提示（contextWindowUpgradeCheck.ts:42-43）")
    void upgradeTipOpusWith1mAccess() {
        assertThat(CompactCommand.getUpgradeMessageTip("opus", false))
            .isEqualTo("Tip: You have access to Opus 1M with 5x more context");
    }

    @Test
    @DisplayName("getUpgradeMessage('tip'): 当前模型设置=sonnet 且 1M 访问可用 → Sonnet 1M 提示")
    void upgradeTipSonnetWith1mAccess() {
        assertThat(CompactCommand.getUpgradeMessageTip("sonnet", false))
            .isEqualTo("Tip: You have access to Sonnet 1M with 5x more context");
    }

    @Test
    @DisplayName("getUpgradeMessage('tip'): CLAUDE_CODE_DISABLE_1M_CONTEXT 门 → 1M 禁用时恒 null（check1mAccess.ts:47-49）")
    void upgradeTipNullWhen1mDisabled() {
        assertThat(CompactCommand.getUpgradeMessageTip("opus", true)).isNull();
        assertThat(CompactCommand.getUpgradeMessageTip("sonnet", true)).isNull();
    }

    @Test
    @DisplayName("getUpgradeMessage('tip'): 非 opus/sonnet 别名（haiku/best/null）→ null（getAvailableUpgrade 仅匹配别名，contextWindowUpgradeCheck.ts:15-29）")
    void upgradeTipNullForOtherModels() {
        assertThat(CompactCommand.getUpgradeMessageTip("haiku", false)).isNull();
        assertThat(CompactCommand.getUpgradeMessageTip("best", false)).isNull();
        assertThat(CompactCommand.getUpgradeMessageTip("claude-opus-4-6", false)).isNull();
        assertThat(CompactCommand.getUpgradeMessageTip(null, false)).isNull();
    }

    @Test
    @DisplayName("getUpgradeMessage('warning'): opus/sonnet + 1M 访问 → '/model <alias>'（contextWindowUpgradeCheck.ts:40-41，TokenWarning.tsx:121 上下文满场景）")
    void upgradeWarningCommand() {
        assertThat(CompactCommand.getUpgradeMessageWarning("opus", false))
            .isEqualTo("/model opus[1m]");
        assertThat(CompactCommand.getUpgradeMessageWarning("sonnet", false))
            .isEqualTo("/model sonnet[1m]");
        assertThat(CompactCommand.getUpgradeMessageWarning("opus", true)).isNull();
        assertThat(CompactCommand.getUpgradeMessageWarning(null, false)).isNull();
    }

    @Test
    @DisplayName("getAvailableUpgrade: opus 1M 访问 → {alias=opus[1m], name=Opus 1M, multiplier=5}（contextWindowUpgradeCheck.ts:9-30）")
    void availableUpgradeRecord() {
        CompactCommand.ModelUpgrade upgrade = CompactCommand.getAvailableUpgrade("opus", false);
        assertThat(upgrade).isNotNull();
        assertThat(upgrade.alias()).isEqualTo("opus[1m]");
        assertThat(upgrade.name()).isEqualTo("Opus 1M");
        assertThat(upgrade.multiplier()).isEqualTo(5);
        assertThat(CompactCommand.getAvailableUpgrade("haiku", false)).isNull();
    }

    @Test
    @DisplayName("buildDisplayText: opus + 1M 访问 → dimmed 第 3 段追加 upgradeMessage（compact.ts:240-247）")
    void displayTextWithUpgradeTip() {
        assertThat(CompactCommand.buildDisplayText(false, null, CompactCommand.DEFAULT_BINDINGS, "opus", false))
            .isEqualTo("Compacted (ctrl+o to see full summary)\n"
                + "Tip: You have access to Opus 1M with 5x more context");
    }

    @Test
    @DisplayName("buildDisplayText: verbose + opus + 1M 访问 → 仅升级提示段（省略 shortcut）")
    void displayTextVerboseWithUpgradeTip() {
        assertThat(CompactCommand.buildDisplayText(true, null, CompactCommand.DEFAULT_BINDINGS, "opus", false))
            .isEqualTo("Compacted Tip: You have access to Opus 1M with 5x more context");
    }

    @Test
    @DisplayName("buildDisplayText: userDisplayMessage + opus → shortcut + userMsg + upgradeMessage 三段（CC dimmed 顺序）")
    void displayTextUserMessageAndUpgrade() {
        assertThat(CompactCommand.buildDisplayText(false, "summary", CompactCommand.DEFAULT_BINDINGS, "sonnet", false))
            .isEqualTo("Compacted (ctrl+o to see full summary)\nsummary\n"
                + "Tip: You have access to Sonnet 1M with 5x more context");
    }

    @Test
    @DisplayName("buildDisplayText: 1M 禁用 → 无 upgradeMessage 段（默认场景等价 CC 无 1M 访问）")
    void displayTextNoUpgradeWhen1mDisabled() {
        assertThat(CompactCommand.buildDisplayText(false, null, CompactCommand.DEFAULT_BINDINGS, "opus", true))
            .isEqualTo("Compacted (ctrl+o to see full summary)");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · SM 优先集成测试（compact.ts:58-82，REQ-12）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无 customInstructions → trySessionMemoryCompaction 成功收尾链 (REQ-12)")
    void smPrioritySuccessChain(@TempDir Path baseDir) throws Exception {
        java.nio.file.Files.createDirectories(baseDir.resolve("s1").resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve("s1").resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        smService.setSmSessionMemoryEnabled(true);
        smService.setSmCompactEnabled(true);
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        CompactWarningState.clearCompactWarningSuppression();

        List<String> notifyCalls = new ArrayList<>();
        CompactCommandContext c = ctx(List.of(msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")),
            smService, null, (m, p, t) -> { throw new IllegalStateException("SM 优先不应走摘要"); },
            new ArrayList<>(), new AbortController(), () -> notifyCalls.add("compact:agent-1"));

        CompactCommand.CompactCommandResult result = CompactCommand.call("", c);

        // SM 压缩成功
        assertThat(result.compactionResult()).isNotNull();
        assertThat(result.compactionResult().summaryMessages()).isNotEmpty();
        // 成功链：cache.clear + runPostCompactCleanup + notifyCompaction + markPostCompaction + suppressCompactWarning
        assertThat(notifyCalls).containsExactly("compact:agent-1");
        // SESSION="s1" 非 UUID → 方案 1b 走回落进程级单布尔；本断言验证 INV-8 markPostCompaction
        // 确被调用（mark 成功链），会话级隔离语义由 PostCompactionStateTest 覆盖（规则 9：意图在专属测试钉死）。
        assertThat(PostCompactionState.isPostCompactionPending(SESSION)).isTrue();
        assertThat(SessionMemoryService.getLastSummarizedMessageId(SESSION)).isNull();
        assertThat(CompactWarningState.isCompactWarningSuppressed()).isTrue();
        // displayText（SM 路径 buildDisplayText(context)，无 userDisplayMessage）
        assertThat(result.displayText()).isEqualTo("Compacted (ctrl+o to see full summary)");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · reactive-only（isReactiveOnlyMode + compactViaReactive，内部算法 ?，OD-01）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reactive-only: isReactiveOnlyMode 恒 false（CC reactiveCompact.ts:12 硬编码），即使 REACTIVE_COMPACT flag 开启")
    void reactiveOnlyModeGate() {
        ReactiveCompactor reactive = new ReactiveCompactor(msgs -> 200_000);
        assertThat(CompactCommand.isReactiveOnlyMode(
            ctx(List.of(msg("m1", Role.user, "hi")), null, reactive,
                (m, p, t) -> null, new ArrayList<>(), new AbortController(), () -> { }))).isFalse();

        // CC reactiveCompact.ts:12 export const isReactiveOnlyMode: () => boolean = () => false
        // → 硬编码恒 false。即使 enabled（模块存在性）开启，reactive-only 路由（compact.ts:87）
        // 也不触发（旧实现返回 isEnabled() 代理 flag —— 对齐 CC 恒 false）。
        reactive.setEnabled(true);
        assertThat(CompactCommand.isReactiveOnlyMode(
            ctx(List.of(msg("m1", Role.user, "hi")), null, reactive,
                (m, p, t) -> null, new ArrayList<>(), new AbortController(), () -> { })))
            .as("CC isReactiveOnlyMode 恒 false → enabled=true 仍 false（路由死代码）")
            .isFalse();
    }

    @Test
    @DisplayName("reactive-only: compactViaReactive 为 CC 死代码——isReactiveOnlyMode 恒 false → /compact 走传统链，reactiveCompactOnPromptTooLong 不被调用")
    void compactViaReactiveSuccess() {
        // 注入 stub CompactCallback + enabled=true → 即使如此，CC 恒 false 使路由不触发（死代码）
        AtomicInteger reactiveCalls = new AtomicInteger();
        ReactiveCompactor reactive = new ReactiveCompactor(
            msgs -> 200_000,
            (prompt, msgs) -> new CompactConversation.SummaryResult("reactive summary stub", null)) {
            @Override
            public ReactiveCompactor.ReactiveCompactOutcome reactiveCompactOnPromptTooLong(
                    List<ChatMessageDto> messages, CompactConversationContext ccCtx, String customInstructions) {
                reactiveCalls.incrementAndGet();
                return super.reactiveCompactOnPromptTooLong(messages, ccCtx, customInstructions);
            }
        };
        reactive.setEnabled(true);
        List<ChatMessageDto> many = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            many.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "content " + i));
        }
        CompactCommandContext c = ctx(many, null, reactive,
            (m, p, t) -> new CompactConversation.SummaryResult("traditional summary", null),
            new ArrayList<>(), new AbortController(), () -> { });

        CompactCommand.CompactCommandResult result = CompactCommand.call("", c);

        // CC 死代码语义（compact.ts:87 路由不触发）：reactiveCompactOnPromptTooLong 不得被调用，
        // /compact 落到传统链（compactConversation + 收尾链）并成功返回。
        assertThat(CompactCommand.isReactiveOnlyMode(c))
            .as("CC reactiveCompact.ts:12 恒 false → reactive-only 路由死代码")
            .isFalse();
        assertThat(reactiveCalls.get())
            .as("compactViaReactive 死代码 → reactiveCompactOnPromptTooLong 不得被调用（传统链执行）")
            .isZero();
        assertThat(result.compactionResult()).isNotNull();
        assertThat(result.displayText()).startsWith("Compacted ");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6 · [RES-R1] manual 传统路径 fork 缓存共享通道（OPD-SP-24）
    //   CC compact.ts:101-108（getCacheSharingParams 传入 compactConversation）+ :250-287
    // ════════════════════════════════════════════════════════════════════

    /**
     * manual 传统路径在压缩摘要前 save CacheSafeParams → fork 路径可达（Holder 非 null）；
     * 5 字段完整（toolUseContext 原样透传、forkContextMessages=压缩前消息快照）；压缩后
     * finally clear（槽位空，防串台/泄漏）。WHY（规则 9）: manual /compact 缺口实锤
     * （OPD-SP-24）= 无 ToolUseContext 通道 → Holder 槽位恒空 → fork 缓存共享跳过 → 落
     * streamingFallback（tools=null），偏离 CC compact.ts:101-108。本测试钉死 R1 修复后的
     * 契约：摘要生产（summaryProducer，即 StreamCompactSummary fork 读侧）能读到非 null
     * CacheSafeParams，且槽位在使用后清空。
     */
    @Test
    @DisplayName("[RES-R1] manual 传统路径: 压缩前 save CacheSafeParams（5 字段完整）→ fork 路径可达 → finally clear")
    void manualTraditionalPathSavesAndClearsCacheSafeParams() {
        ToolUseContext tuc = baseContext();
        // I-13 custom 短路：customSystemPrompt 非空 → defaultAssemble 不被调用（无须构造真实组装链）
        Supplier<SystemPrompt> defaultAssemble = () -> {
            throw new IllegalStateException("custom 短路: defaultAssemble 不应被调用");
        };
        SystemPromptContextProvider sysCtx = new SystemPromptContextProvider(
            "2026-08-06",
            new UserContextProvider() {
                @Override
                public String claudeMd() {
                    return "项目指令";
                }

                @Override
                public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider() {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            });

        List<ChatMessageDto> preCompact = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"),
            msg("m3", Role.user, "how are you"));
        AtomicReference<CacheSafeParams> seenDuringSummarize = new AtomicReference<>();
        CompactCommandContext c = ctx(preCompact, null, null,
            (m, p, t) -> {
                // summaryProducer 即 StreamCompactSummary fork 读侧（cacheSafeParamsSupplier=Holder.get()）
                seenDuringSummarize.set(CacheSafeParamsHolder.get());
                return new CompactConversation.SummaryResult("summary ok", null);
            },
            new ArrayList<>(), new AbortController(), () -> { },
            tuc, sysCtx, defaultAssemble, "CUSTOM-PROMPT", false, () -> false);  // 3P 默认 gate=false

        CompactCommand.CompactCommandResult result = CompactCommand.call("  ", c);

        assertThat(result.compactionResult()).isNotNull();
        // 摘要生产期间 Holder 非 null → fork 路径可达（修复前恒 null → RED）
        CacheSafeParams cs = seenDuringSummarize.get();
        assertThat(cs).isNotNull();
        // 5 字段完整（forkedAgent.ts:57-68）
        assertThat(cs.systemPrompt()).containsExactly("CUSTOM-PROMPT");
        assertThat(cs.userContext()).isNotEmpty();
        assertThat(cs.systemContext()).isEmpty();      // I-13 custom 短路
        assertThat(cs.toolUseContext()).isSameAs(tuc); // 主线程一致 TUC 透传
        assertThat(cs.forkContextMessages()).isEqualTo(preCompact); // 压缩前消息快照
        // [RES-R4-1] gate 随 ctx 注入透传到 CacheSafeParams（3P 默认场景 gate=false，与主线程同一判定）
        assertThat(cs.useGlobalCacheScope()).isFalse();
        // 压缩后槽位清空（finally clear，防串台/泄漏到下一 turn）
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    /**
     * [RES-R4-1] firstParty gate 接线：ctx.useGlobalCacheScope()=true 时 manual 传统路径产物
     * CacheSafeParams.useGlobalCacheScope()==true（当前 6 参重载恒 false → RED）。WHY（规则 9）:
     * 09 §十 R4-1 决策要求 manual /compact 在 firstParty 部署（baseUrl 含 api.anthropic.com 且未
     * 禁用 experimental betas）下 fork 缓存共享命中 —— gate 是 fork 发送边界 split 模式
     * （boundary→global）的决定因子，gate 恒 false 则 cacheScope 与主线程不一致 → 缓存永不命中。
     */
    @Test
    @DisplayName("[RES-R4-1] manual 传统路径: firstParty gate=true 注入 ctx → CacheSafeParams.useGlobalCacheScope()==true")
    void manualTraditionalPathFirstPartyGateCarriedToCacheSafeParams() {
        ToolUseContext tuc = baseContext();
        SystemPromptContextProvider sysCtx = new SystemPromptContextProvider(
            "2026-08-06",
            new UserContextProvider() {
                @Override
                public String claudeMd() {
                    return "项目指令";
                }

                @Override
                public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider() {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            });

        List<ChatMessageDto> preCompact = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"),
            msg("m3", Role.user, "how are you"));
        AtomicReference<CacheSafeParams> seenDuringSummarize = new AtomicReference<>();
        CompactCommandContext c = ctx(preCompact, null, null,
            (m, p, t) -> {
                seenDuringSummarize.set(CacheSafeParamsHolder.get());
                return new CompactConversation.SummaryResult("summary ok", null);
            },
            new ArrayList<>(), new AbortController(), () -> { },
            tuc, sysCtx, null, "CUSTOM-PROMPT", true, () -> false);  // firstParty gate（REQ-R4-3）

        CompactCommand.CompactCommandResult result = CompactCommand.call("  ", c);

        assertThat(result.compactionResult()).isNotNull();
        CacheSafeParams cs = seenDuringSummarize.get();
        assertThat(cs).isNotNull();
        // gate 值随 CacheSafeParams 携带（fork 发送边界 split 模式决定因子，REQ-R4-1/3）
        assertThat(cs.useGlobalCacheScope()).isTrue();
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    @Test
    @DisplayName("[IMP-A2-1] manual 传统路径: compactConversation 内部 notifyCompaction 接线（CC compact.ts:698-699）——feature 开触发复位基线，feature 关 no-op")
    void manualTraditionalPathWiresNotifyCompaction() {
        List<ChatMessageDto> preCompact = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"),
            msg("m3", Role.user, "how are you"));

        // gate=true → 传统压缩成功后 notifyCompaction 触发一次（cache-read 基线复位，
        // 对齐 CC compact.ts:698-699 feature('PROMPT_CACHE_BREAK_DETECTION') 门控内调用）
        List<String> notifyCallsOn = new ArrayList<>();
        CompactCommandContext cOn = ctx(preCompact, null, null,
            (m, p, t) -> new CompactConversation.SummaryResult("summary ok", null),
            new ArrayList<>(), new AbortController(),
            () -> notifyCallsOn.add("notified"),
            null, null, null, null, false, () -> true);
        CompactCommand.CompactCommandResult resultOn = CompactCommand.call("  ", cOn);
        assertThat(resultOn.compactionResult()).isNotNull();
        assertThat(notifyCallsOn).containsExactly("notified");

        // gate=false → 不触发（对齐 CC feature 门控短路；与 SM 成功链 :242-244 同门）
        List<String> notifyCallsOff = new ArrayList<>();
        CompactCommandContext cOff = ctx(preCompact, null, null,
            (m, p, t) -> new CompactConversation.SummaryResult("summary ok", null),
            new ArrayList<>(), new AbortController(),
            () -> notifyCallsOff.add("notified"),
            null, null, null, null, false, () -> false);
        CompactCommand.CompactCommandResult resultOff = CompactCommand.call("  ", cOff);
        assertThat(resultOff.compactionResult()).isNotNull();
        assertThat(notifyCallsOff).isEmpty();
    }

    /** 最小 ToolUseContext（8 参兼容构造器 · 对齐 CacheSharingParamsBuilderTest.baseContext）。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }
}
