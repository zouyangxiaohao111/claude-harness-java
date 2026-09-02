package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.command.CompactCommand;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.apis.command.CommandController;
import com.nexusai.domain.command.CommandService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IMP2-02 · manual //clear 缓存清除与收尾链接线集成测试
 * （runPostCompactCleanup 无参门 + clearUserContextCache/notifyCompaction 真实接线）。
 *
 * <p><b>WHY（S-13 / OD-20 / 04 △-1/△-2）</b>: CC /compact 成功链调用
 * {@code runPostCompactCleanup()} <b>无参</b>（compact.ts:64/118/201）→ querySource=undefined
 * → main-thread gate=TRUE 全执行（postCompactCleanup.ts:36-39）；Java 旧实现传
 * {@code effectiveQuerySource()}（/compact 恒 "compact"）→ gate=false → 3 项 main-thread
 * 操作（resetContextCollapse + getUserContext.cache.clear + resetGetMemoryFilesCache('compact')）
 * 恒跳过 = P0 缓存残留。且 ToolRegistrationConfig:1504 注入 {@code () -> {}, () -> {}} no-op
 * （clearUserContextCache / notifyCompaction）→ 下轮 LLM turn 可能命中陈旧指令/记忆。
 *
 * <p>本测试钉死修复后的契约（RED→GREEN：修复前断言全红）：
 * <ol>
 *   <li><b>manual 传统/SM 两路径（+ reactive 注入死代码）</b>: /compact 压缩成功后
 *       resetContextCollapse / clearAllProviderCaches / resetGetMemoryFilesCache('compact')
 *       真实执行（无参门 gate=TRUE）；reactive-only 路由为 CC 对齐死代码
 *       （reactiveCompact.ts:12 isReactiveOnlyMode 恒 false → compact.ts:87 永不触发，
 *       注入 reactive 亦落传统链）</li>
 *   <li><b>ToolRegistrationConfig 真实接线</b>: clearUserContextCache =
 *       {@link SystemPromptInjection#clearAllProviderCaches()}（Java getUserContext.cache.clear
 *       等价，FIX-CL）；notifyCompaction = {@link PromptCacheBreakDetection#notifyCompaction}
 *       （PROMPT_CACHE_BREAK_DETECTION feature 开时真实重置 cache-read 基线，关时 no-op）</li>
 *   <li><b>/clear 等价清理</b>: CommandController 的 clear 分支补 runPostCompactCleanup()
 *       （对齐 CC caches.ts:74 无参调用）→ 主线程操作真实执行；随后
 *       resetGetMemoryFilesCache('session_start') 补偿（对齐 caches.ts:84 —— /clear 非压缩
 *       事件，覆盖 'compact' 为 'session_start'，下轮 InstructionsLoaded hook 不误报）</li>
 * </ol>
 *
 * <p>位于 prompt 包：{@link SystemPromptInjection#registerCacheClearHook} 为 package-private
 * （同包可注册观察钩子，与 {@code CacheInvalidationTest} / {@code PostCompactCleanupCcContractTest}
 * 同机制）。
 */
class ManualCacheClearCcIntegrationTest {

    private static final String SESSION = "s1";
    private static final String AGENT = "agent-1";

    private static final AtomicInteger COLLAPSE_RESETS = new AtomicInteger();
    private static final AtomicInteger MEMFILES_RESETS = new AtomicInteger();
    private static final List<Runnable> REGISTERED_HOOKS = new CopyOnWriteArrayList<>();

    /** CONTEXT_COLLAPSE 开启 + resetContextCollapse 计数 spy（经 STATIC_COLLAPSE 注入）。 */
    private static final com.nexusai.application.agent.loop.ContextCollapse ENABLED_COLLAPSE =
        new com.nexusai.application.agent.loop.ContextCollapse(FeatureFlags.ALL_DISABLED) {
            @Override
            public boolean isContextCollapseEnabled() {
                return true;
            }

            @Override
            public void resetContextCollapse() {
                COLLAPSE_RESETS.incrementAndGet();
            }
        };

    /** resetGetMemoryFilesCache 计数 spy（经 STATIC_CLAUDE_MD / controller 注入），记录 reason 序列。 */
    private static final List<String> MEMFILES_RESET_REASONS = new CopyOnWriteArrayList<>();
    private static final ClaudemdEngine CLAUDEMD_SPY = new ClaudemdEngine(
        AutoMemPaths.defaultInstance(),
        new MemoryFileDetection(AutoMemPaths.defaultInstance(), () -> true, () -> true)) {
        @Override
        public void resetGetMemoryFilesCache(String reason) {
            MEMFILES_RESETS.incrementAndGet();
            MEMFILES_RESET_REASONS.add(reason);
        }
    };

    @AfterEach
    void resetStaticState() {
        COLLAPSE_RESETS.set(0);
        MEMFILES_RESETS.set(0);
        MEMFILES_RESET_REASONS.clear();
        for (Runnable hook : REGISTERED_HOOKS) {
            SystemPromptInjection.unregisterUserCacheClearHook(hook);
        }
        REGISTERED_HOOKS.clear();
        // 复位 PostCompactCleanup 静态宿主，避免跨用例/跨测试类污染
        new PostCompactCleanup(null, null, null);
        // 复位 PromptCacheBreakDetection 静态 PREVIOUS 表（notifyCompaction 接线测试用）
        new PromptCacheBreakDetection(r -> {}).resetPromptCacheBreakDetection();
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        CompactWarningState.clearCompactWarningSuppression();
        PostCompactionState.clear(SESSION);
        CacheSafeParamsHolder.clear();
        com.nexusai.common.RequestContext.clear();
    }

    /** 注入全部 spy 协作器（main-thread 操作可观察）。 */
    private static void wireSpies() {
        new PostCompactCleanup(ENABLED_COLLAPSE, new SessionAgentStateRegistry(), CLAUDEMD_SPY);
    }

    /** 注册 cache-clear 观察钩子并跟踪（@AfterEach 注销）。 */
    private static AtomicInteger registerClearCounter() {
        AtomicInteger counter = new AtomicInteger();
        Runnable hook = counter::incrementAndGet;
        REGISTERED_HOOKS.add(hook);
        SystemPromptInjection.registerUserCacheClearHook(hook);
        return counter;
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** feature=promptCacheBreakDetection 开启的 FeatureFlags（其余默认关）。 */
    private static FeatureFlags cacheBreakFeatureOn() {
        return new FeatureFlags(false, false, false, true,
            false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
    }

    /**
     * 生产语义的 CompactCommandContext（对齐 ToolRegistrationConfig.buildCompactCommandContext:1504
     * 修复后的接线）：clearUserContextCache=真实缓存清除、notifyCompaction=gatedBy 门控真实通知。
     */
    private static CompactCommand.CompactCommandContext ctx(List<ChatMessageDto> messages,
                                                             SessionMemoryService sm,
                                                             ReactiveCompactor reactive,
                                                             CompactConversation.SummaryProducer producer,
                                                             FeatureFlags flags) {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(SESSION);
        cc.setAgentId(AGENT);
        cc.setModel("claude-sonnet-4-5");
        cc.setQuerySource("compact");
        cc.setSummaryProducer(producer);
        return new CompactCommand.CompactCommandContext(messages, SESSION, AGENT, "compact", false,
            new AbortController(), sm, new MicroCompactor(), reactive, () -> cc,
            // notifyCompaction 真实接线（feature 门控，CC compact.ts:67-72）
            () -> PromptCacheBreakDetection.gatedBy(flags).notifyCompaction("compact", AGENT),
            // clearUserContextCache 真实接线（CC getUserContext.cache.clear，compact.ts:63/117/203）
            // [merge 适配 2026-08-14] 清理面收敛为 user-only 通道（SP-07 △-6：只清 getUserContext），
            //   显式 clear 与 runPostCompactCleanup 内部一致走 clearUserOnlyProviderCaches
            () -> SystemPromptInjection.clearUserOnlyProviderCaches(),
            null, null, null, null, null, false, () -> flags.promptCacheBreakDetection());
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. manual 三路径 · 无参门（gate=TRUE）→ 3 项 main-thread 操作真实执行
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("manual 传统路径: /compact 成功 → resetContextCollapse + clearAllProviderCaches + resetGetMemoryFilesCache('compact') 全执行（无参门）")
    void manualTraditionalPath_noArgGate_allMainThreadOpsExecute() {
        wireSpies();
        AtomicInteger cacheClears = registerClearCounter();

        List<ChatMessageDto> preCompact = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"),
            msg("m3", Role.user, "how are you"));
        CompactCommand.CompactCommandResult result = CompactCommand.call("", ctx(
            preCompact, null, null, (m, p, t) -> new CompactConversation.SummaryResult("summary ok", null),
            FeatureFlags.ALL_DISABLED));

        assertThat(result.compactionResult()).isNotNull();
        assertThat(COLLAPSE_RESETS.get())
            .as("操作 1: resetContextCollapse 必须执行（CC postCompactCleanup.ts:42-49，无参门 gate=TRUE）").isEqualTo(1);
        assertThat(MEMFILES_RESETS.get())
            .as("操作 3: resetGetMemoryFilesCache('compact') 必须执行（CC :60）").isEqualTo(1);
        // 2 次 = 命令调用点显式 clearUserContextCache（compact.ts:117）+ runPostCompactCleanup 内部 :59 双清（CC 同构）
        assertThat(cacheClears.get())
            .as("操作 2: getUserContext.cache.clear 等价 clearAllProviderCaches 执行 2 次（显式 + 序列内，CC compact.ts:117 + postCompactCleanup.ts:59）")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("manual SM 优先路径: 压缩成功 → 3 项 main-thread 操作全执行（无参门）")
    void manualSmPath_noArgGate_allMainThreadOpsExecute(@TempDir Path baseDir) throws Exception {
        wireSpies();
        AtomicInteger cacheClears = registerClearCounter();
        java.nio.file.Files.createDirectories(baseDir.resolve("s1").resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve("s1").resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        smService.setSmSessionMemoryEnabled(true);
        smService.setSmCompactEnabled(true);

        CompactCommand.CompactCommandResult result = CompactCommand.call("", ctx(
            List.of(msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")),
            smService, null, (m, p, t) -> { throw new IllegalStateException("SM 优先不应走摘要"); },
            FeatureFlags.ALL_DISABLED));

        assertThat(result.compactionResult()).isNotNull();
        assertThat(COLLAPSE_RESETS.get())
            .as("SM 成功链 resetContextCollapse 必须执行（CC compact.ts:64 无参调用）").isEqualTo(1);
        assertThat(MEMFILES_RESETS.get())
            .as("SM 成功链 resetGetMemoryFilesCache('compact') 必须执行").isEqualTo(1);
        assertThat(cacheClears.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("manual reactive 注入路径（CC 死代码语义）: isReactiveOnlyMode 恒 false → /compact 走传统链，3 项 main-thread 操作全执行（无参门）")
    void manualReactiveInjection_noArgGate_allMainThreadOpsExecute() {
        wireSpies();
        AtomicInteger cacheClears = registerClearCounter();
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
        CompactCommand.CompactCommandContext c = ctx(many, null, reactive,
            // CC 死代码语义：reactive-only 路由不可达（compact.ts:87 恒 false）→ /compact 落传统链
            // → 摘要生产必被调用 → 哨兵"reactive 不应走摘要"已删（旧契约错误地期望 reactive 路由被驱动）
            (m, p, t) -> new CompactConversation.SummaryResult("traditional summary", null),
            FeatureFlags.ALL_DISABLED);

        CompactCommand.CompactCommandResult result = CompactCommand.call("", c);

        assertThat(CompactCommand.isReactiveOnlyMode(c))
            .as("CC reactiveCompact.ts:12 isReactiveOnlyMode 恒 false → reactive-only 路由死代码（enabled=true 仍不触发）")
            .isFalse();
        assertThat(reactiveCalls.get())
            .as("compactViaReactive 死代码 → reactiveCompactOnPromptTooLong 不得被调用（传统链执行）")
            .isZero();
        assertThat(result.compactionResult()).isNotNull();
        assertThat(COLLAPSE_RESETS.get())
            .as("传统链成功 resetContextCollapse 必须执行（CC postCompactCleanup.ts:42-49，无参门 gate=TRUE）").isEqualTo(1);
        assertThat(MEMFILES_RESETS.get())
            .as("传统链成功 resetGetMemoryFilesCache('compact') 必须执行（CC :60）").isEqualTo(1);
        assertThat(cacheClears.get())
            .as("操作 2: getUserContext.cache.clear 等价 clearAllProviderCaches 执行 2 次（显式 + 序列内，CC compact.ts:117 + postCompactCleanup.ts:59）")
            .isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. ToolRegistrationConfig:1504 真实接线（clearUserContextCache / notifyCompaction）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 经反射调用生产接线 {@link ToolRegistrationConfig#buildCompactCommandContext}（package-private），
     * 断言其产出的 Runnable 为真实实现（非 no-op）：clearUserContextCache → 缓存真实清除；
     * notifyCompaction → feature 开时真实重置 cache-read 基线。
     */
    @Test
    @DisplayName("[接线] ToolRegistrationConfig: clearUserContextCache 真实清除 + notifyCompaction feature 开时真实通知")
    void toolRegistrationConfigWiring_realImplementations() throws Exception {
        wireSpies();
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        // 注入生产 featureFlags 字段（默认 ALL_DISABLED；本用例开 PROMPT_CACHE_BREAK_DETECTION）
        ReflectionTestUtils.setField(config, "featureFlags", cacheBreakFeatureOn());
        Method build = ToolRegistrationConfig.class.getDeclaredMethod(
            "buildCompactCommandContext",
            List.class, String.class, String.class,
            ReactiveCompactor.class, com.nexusai.application.agent.compact.StreamCompactSummary.class,
            SessionMemoryService.class, com.nexusai.application.agent.tool.ToolUseContext.class,
            com.nexusai.application.agent.prompt.SystemPromptContextProvider.class,
            Supplier.class, String.class, String.class, boolean.class, Telemetry.class);
        build.setAccessible(true);
        CompactCommand.CompactCommandContext ctx = (CompactCommand.CompactCommandContext) build.invoke(
            config, List.of(msg("m1", Role.user, "hi")), SESSION, AGENT,
            null, null, null, null, null, null, null, null, false, null);

        // ── 2a. clearUserContextCache 真实接线：注册观察钩子 → 执行 → 钩子触发 ──
        AtomicInteger cacheClears = registerClearCounter();
        ctx.clearUserContextCache().run();
        assertThat(cacheClears.get())
            .as("clearUserContextCache 必须真实清除 provider 缓存（no-op 注入替换为 SystemPromptInjection.clearAllProviderCaches）")
            .isEqualTo(1);

        // ── 2b. notifyCompaction 真实接线：feature 开 → 重置 cache-read 基线 → 下降不误报 break ──
        List<PromptCacheBreakDetection.CacheBreakResult> events = new ArrayList<>();
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events::add);
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(), List.of(), "compact", "claude-sonnet-4-5", AGENT,
            false, "cache_control", List.of(), false, false, false, null, null));
        // 压缩前基线: cache-read=10000
        detector.checkResponseForCacheBreak("compact", 10_000, 8_000, null, AGENT, "req-1");
        // 压缩成功 → 生产接线 notifyCompaction（querySource='compact' → tracking key 'repl_main_thread'）
        ctx.notifyCompaction().run();
        // 压缩后下个 API: cache-read 10000→2000（预期下降，非 break）
        detector.checkResponseForCacheBreak("compact", 2_000, 8_000, null, AGENT, "req-2");
        assertThat(events)
            .as("feature 开时 notifyCompaction 真实重置 prevCacheReadTokens → 下降不报 break（CC promptCacheBreakDetection.ts:689-698）")
            .isEmpty();

        // 对照：不调 notifyCompaction → 同样下降报 break（证明差异确由接线造成）
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(), List.of(), "compact", "claude-sonnet-4-5", "control-agent",
            false, "cache_control", List.of(), false, false, false, null, null));
        detector.checkResponseForCacheBreak("compact", 10_000, 8_000, null, "control-agent", "req-c1");
        detector.checkResponseForCacheBreak("compact", 2_000, 8_000, null, "control-agent", "req-c2");
        assertThat(events).as("对照: 无 notifyCompaction 的同样下降必须报 break").hasSize(1);
    }

    @Test
    @DisplayName("[接线] notifyCompaction feature 关（默认）→ no-op，cache-read 基线不重置（门控对齐 CC）")
    void toolRegistrationConfigWiring_notifyCompactionFeatureOff_noop() throws Exception {
        wireSpies();
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        // 不注入 → featureFlags 默认 ALL_DISABLED（feature 关）
        Method build = ToolRegistrationConfig.class.getDeclaredMethod(
            "buildCompactCommandContext",
            List.class, String.class, String.class,
            ReactiveCompactor.class, com.nexusai.application.agent.compact.StreamCompactSummary.class,
            SessionMemoryService.class, com.nexusai.application.agent.tool.ToolUseContext.class,
            com.nexusai.application.agent.prompt.SystemPromptContextProvider.class,
            Supplier.class, String.class, String.class, boolean.class, Telemetry.class);
        build.setAccessible(true);
        CompactCommand.CompactCommandContext ctx = (CompactCommand.CompactCommandContext) build.invoke(
            config, List.of(msg("m1", Role.user, "hi")), SESSION, AGENT,
            null, null, null, null, null, null, null, null, false, null);

        List<PromptCacheBreakDetection.CacheBreakResult> events = new ArrayList<>();
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events::add);
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(), List.of(), "compact", "claude-sonnet-4-5", AGENT,
            false, "cache_control", List.of(), false, false, false, null, null));
        detector.checkResponseForCacheBreak("compact", 10_000, 8_000, null, AGENT, "req-1");
        // feature 关 → notifyCompaction no-op（gatedBy(false)，CC compact.ts:67 if feature(...)）
        ctx.notifyCompaction().run();
        detector.checkResponseForCacheBreak("compact", 2_000, 8_000, null, AGENT, "req-2");
        assertThat(events)
            .as("feature 关时 notifyCompaction 必须为 no-op → 下降仍报 break（门控语义保持）")
            .hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. /clear 等价清理（对齐 CC caches.ts:74 无参 runPostCompactCleanup + :84 session_start 补偿）

    @Test
    @DisplayName("/clear: executeBuiltin('clear') → runPostCompactCleanup 等价清理 + session_start reason 补偿（CC caches.ts:74/84）")
    void clearCommand_runsPostCompactCleanupEquivalent() {
        wireSpies();
        AtomicInteger cacheClears = registerClearCounter();
        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "commandService", mock(CommandService.class));
        ReflectionTestUtils.setField(controller, "skillRegistry", mock(com.nexusai.application.agent.skill.SkillRegistry.class));
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", registry);
        com.nexusai.application.agent.tasks.TaskFrameworkService tasks = mock(com.nexusai.application.agent.tasks.TaskFrameworkService.class);
        when(tasks.listAll()).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "taskFrameworkService", tasks);
        // [IMP2-02 r1] 注入 claudemd 引擎 spy → /clear 后 session_start 补偿可观察
        //（CC caches.ts:84：clearSessionCaches 非压缩事件，覆盖 'compact' 为 'session_start'）
        ReflectionTestUtils.setField(controller, "claudemdEngine", CLAUDEMD_SPY);
        // 会话存在（clearInvokedSkills 路径可达；注册表内无 state → 各清理 debug skip 不抛）
        com.nexusai.common.RequestContext.setSession("00000000-0000-0000-0000-00000000000c");

        Object dto = controller.executeBuiltin("clear", null);

        assertThat(dto).isNotNull();
        assertThat(COLLAPSE_RESETS.get())
            .as("/clear 等价清理 resetContextCollapse 必须执行（CC caches.ts:74 → postCompactCleanup.ts:42-49）").isEqualTo(1);
        assertThat(cacheClears.get())
            .as("/clear 等价清理 clearAllProviderCaches 必须执行（CC :59）").isEqualTo(1);
        // 2 次 = runPostCompactCleanup 内部 'compact'（CC :60）+ CommandController 补偿 'session_start'（CC caches.ts:84）
        assertThat(MEMFILES_RESETS.get())
            .as("/clear 等价清理 resetGetMemoryFilesCache 必须 2 次（compact 置位 + session_start 覆盖，CC caches.ts:74/84）").isEqualTo(2);
        assertThat(MEMFILES_RESET_REASONS)
            .as("reason 序列：先 'compact'（runPostCompactCleanup 内部）后 'session_start'（/clear 补偿覆盖，CC caches.ts:80-84 注释防错）")
            .containsExactly("compact", "session_start");
    }

    /** 会话级 AgentState 存在时 /clear 不破坏既有 invokedSkills 保留语义（回归，OPD-TP-19）。 */
    @Test
    @DisplayName("/clear: 后台化 agent invokedSkills 保留语义回归（OPD-TP-19 不受 runPostCompactCleanup 影响）")
    void clearCommand_preservesBackgroundedAgentSkills() {
        wireSpies();
        CommandController controller = new CommandController();
        // [IMP2-02 r1] 注入 claudemd 引擎 spy（与生产同链路：/clear → runPostCompactCleanup + session_start 补偿）
        ReflectionTestUtils.setField(controller, "claudemdEngine", CLAUDEMD_SPY);
        ReflectionTestUtils.setField(controller, "commandService", mock(CommandService.class));
        ReflectionTestUtils.setField(controller, "skillRegistry", mock(com.nexusai.application.agent.skill.SkillRegistry.class));
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", registry);
        com.nexusai.application.agent.tasks.TaskFrameworkService tasks = mock(com.nexusai.application.agent.tasks.TaskFrameworkService.class);
        java.util.UUID bgAgent = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        String sessionUuid = "00000000-0000-0000-0000-00000000000c";
        com.nexusai.common.RequestContext.setSession(sessionUuid.toString());
        AgentState state = new AgentState("test-system-prompt");
        registry.register(sessionUuid, state);
        when(tasks.listAll()).thenReturn(List.of(new com.nexusai.application.agent.tasks.BackgroundTask(
            "s12345678", com.nexusai.application.agent.tasks.TaskType.LOCAL_AGENT,
            com.nexusai.application.agent.tasks.BackgroundTaskStatus.RUNNING, "bg", null,
            System.currentTimeMillis(), null, null,
            "/tmp/nexusai-sessions/sess-x/tasks/s12345678.output", 0L, false, bgAgent, true)));
        ReflectionTestUtils.setField(controller, "taskFrameworkService", tasks);
        state.addInvokedSkill("bg-skill", "/s/bg.md", "c", bgAgent);
        state.addInvokedSkill("main-skill", "/s/main.md", "c", null);

        controller.executeBuiltin("clear", null);

        assertThat(state.getInvokedSkillsForAgent(bgAgent)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(null)).isEmpty();
    }
}
