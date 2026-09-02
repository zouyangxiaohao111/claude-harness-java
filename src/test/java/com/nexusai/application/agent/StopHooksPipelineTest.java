package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.api.PromptSuggestion;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.hook.CollapseHookSummaries;
import com.nexusai.application.agent.memory.AutoDreamConsolidator;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.StopHookPipeline;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.TaskUpdateTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Session H6] StopHookPipeline 5 阶段 + 主 Stop hook 消费追踪 + TaskCompleted 真实执行。
 *
 * <p>对齐 CC {@code Open-ClaudeCode/src/query/stopHooks.ts}：
 * <ul>
 *   <li>L96-98 saveCacheSafeParams — 子代理 turn 结束不得覆盖父会话 cache-safe params</li>
 *   <li>L108-132 classifyAndWriteState — 仅 repl_main_thread + 无 agentId 的 job 分类</li>
 *   <li>L136-140 executePromptSuggestion — 非 bare 且 env 未禁用时 fire-and-forget</li>
 *   <li>L141-156 extractMemories/autoDream — agentId 为 null（主线程）才触发</li>
 *   <li>L164-173 cleanupComputerUseAfterTurn — 子代理跳过，失败静默</li>
 *   <li>L175-333 主 Stop hook 消费追踪 + L298-308 createStopHookSummaryMessage</li>
 *   <li>L345-453 TaskCompleted/TeammateIdle 真实执行（isTeammate 消费端）</li>
 * </ul>
 *
 * <p><b>WHY (规则九 · 验证意图) 总纲</b>: H6 是 P1 关键路径 —— Stop hook 消费端此前只有
 * blockingError/preventContinuation 两条粗通道，5 阶段流水线、progress/hookErrors/hasOutput/
 * summary message、TaskCompleted 真实执行全部缺失。这些测试验证的是"这些 CC 行为为何重要"
 * （子代理污染父会话、后台 fork 污染 job 时间线、配置了 TaskCompleted hook 就必须执行），
 * 而非仅仅断言方法返回什么。
 */
@DisplayName("[Session H6] StopHookPipeline 5 阶段 + 主消费追踪 + TaskCompleted 真实执行")
class StopHooksPipelineTest {

    @BeforeEach
    void setUp() {
        TaskSystemConfig.clearForTest();
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        System.clearProperty("CLAUDE_JOB_DIR");
        System.clearProperty("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION");
        System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
        System.clearProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE");
        System.clearProperty("nexusai.taskListId");
        // [sm 决策] isExtractModeActive DB 主控测试注入的 DB 桥接必须清理（防跨测试静态泄漏）
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
    }

    /**
     * [sm 决策 2026-08-30] 注入 DB settings 列 auto_memory_enabled 真值（isExtractModeActive DB 主控
     * 测试确定性用）—— 经 BundledSkillEnabledGates 静态桥接 mock SettingsMapper，DB 列优先于
     * 宿主 settings.json 链（否则依赖本机 ~/.claude/settings.json，环境敏感）。tearDown 清理。
     */
    private static void bridgeAutoMemoryEnabled(boolean enabled) {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord rec = new SettingsRecord();
        rec.setAutoMemoryEnabled(enabled);
        Mockito.when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 1: saveCacheSafeParams
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("saveCacheSafeParams 仅 repl_main_thread/sdk 保存，子代理不得覆盖父会话 cache-safe params")
    void saveCacheSafeParams_onlyForReplMainThreadAndSdk() {
        // WHY: CC stopHooks.ts:96-98 —— 子代理 turn 结束若覆盖 cache-safe params，
        // 父会话的 prompt-suggestion snapshot 会被污染。SUBAGENT/COMPACT 必须跳过。
        // [H-WF4-01 · R-1] USER 不得跳过：CC stopHooks.ts:96 是 canonical 字符串精确匹配
        // （=== 'repl_main_thread'），Java USER.canonical()=="repl_main_thread"（QuerySource.java:110）
        // 语义=主线程用户输入 → 应命中。旧断言 isFalse 固化"排除 USER"的收窄 bug（与 5-W4-9 同类）。
        assertThat(StopHookPipeline.saveCacheSafeParams(QuerySource.REPL_MAIN_THREAD)).isTrue();
        assertThat(StopHookPipeline.saveCacheSafeParams(QuerySource.SDK)).isTrue();
        assertThat(StopHookPipeline.saveCacheSafeParams(QuerySource.SUBAGENT)).isFalse();
        assertThat(StopHookPipeline.saveCacheSafeParams(QuerySource.USER)).isTrue();
        assertThat(StopHookPipeline.saveCacheSafeParams(QuerySource.COMPACT)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 2: classifyAndWriteState
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classifyAndWriteState 仅 repl_main_thread 前缀 + 无 agentId + 有 CLAUDE_JOB_DIR 才分类")
    void classifyAndWriteState_onlyMainThreadWithJobDir() {
        // WHY: CC stopHooks.ts:108-113 —— 只有 dispatched job（repl_main_thread + 无 agentId）
        // 才在每个 turn 后分类写 state.json；后台 fork（extract-memories/auto-dream）不得
        // 用它们自己的 assistant messages 污染 job 时间线。
        System.setProperty("CLAUDE_JOB_DIR", "D:/tmp/job-1");
        assertThat(StopHookPipeline.classifyAndWriteState(QuerySource.REPL_MAIN_THREAD, null)).isTrue();
        // [H-WF4-01 · 5-W4-9] USER canonical 同为 "repl_main_thread"（QuerySource.java:110），
        // CC stopHooks.ts:111 startsWith('repl_main_thread') 命中 —— 前缀匹配不得排除 USER
        assertThat(StopHookPipeline.classifyAndWriteState(QuerySource.USER, null)).isTrue();
        // 子代理（agentId 非 null）即使主线程来源也必须跳过 —— CC: !toolUseContext.agentId
        assertThat(StopHookPipeline.classifyAndWriteState(QuerySource.REPL_MAIN_THREAD, "sub-1")).isFalse();
        // 非 repl_main_thread 来源（COMPACT / SESSION_MEMORY 后台 fork）跳过
        assertThat(StopHookPipeline.classifyAndWriteState(QuerySource.COMPACT, null)).isFalse();
        // 无可用 CLAUDE_JOB_DIR → 跳过。
        // 注意: 不能用 clearProperty 模拟「无 job dir」——resolveEnvOrProperty 在属性缺失时
        // 会回退到 System.getenv，若宿主 shell 设了 CLAUDE_JOB_DIR 环境变量（如 Claude Code
        // 会话自身的 jobs 目录）断言即随环境漂移。置 blank 属性（isBlank gate 与「缺失」同为
        // 跳过分支）使测试对环境无关地锁定 CC stopHooks.ts:108 gate 语义。
        System.setProperty("CLAUDE_JOB_DIR", "");
        assertThat(StopHookPipeline.classifyAndWriteState(QuerySource.REPL_MAIN_THREAD, null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 3: executePromptSuggestion
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executePromptSuggestion 注入式：无实例显式跳过、有 enabled 实例 fire-and-forget、bare/env 禁用跳过")
    void executePromptSuggestion_wiredFireAndForget() {
        // WHY: CC stopHooks.ts:136-140 —— --bare/SIMPLE 脚本 -p 调用不想要后台 bookkeeping
        // （prompt suggestion 是后台 fork agent，会与 shutdown 争抢资源）。裸模式必须跳过；
        // 非 bare 且 env 未显式禁用时必须以 fire-and-forget 触发（void executePromptSuggestion）。
        // [H6-FIX] 去静态 no-op 假触发（CHANGELOG 0.2.29 H6-2）：无注入实例 → 显式跳过（不假触发）。
        System.clearProperty("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION");
        // [IMP-GP-03 · OPD-WF7-JS-03] PromptSuggestion 4 参构造第 4 位改为 SpeculationEngine 协作方
        //   （旧 lastAssistantMessageSupplier 由 executeSuggestion 的 SuggestionContext 参数承担）；
        //   executePromptSuggestion 3 参（bareMode, promptSuggestion, SuggestionContext）。
        PromptSuggestion enabled = new PromptSuggestion(
            () -> true,
            (p, params, signal) -> new PromptSuggestion.ForkResult("suggestion", false, null),
            (e, f) -> {}, null);
        assertThat(StopHookPipeline.executePromptSuggestion(false, enabled, null)).isTrue();
        // 无注入实例 → 显式跳过（不再返回 true 假触发静态 no-op）
        assertThat(StopHookPipeline.executePromptSuggestion(false, null, null)).isFalse();

        // bare 模式 → 跳过（CC stopHooks.ts:136: if (!isBareMode()) 外层守卫）
        assertThat(StopHookPipeline.executePromptSuggestion(true, enabled, null)).isFalse();

        // env 显式禁用 → 跳过（CC: !isEnvDefinedFalsy(process.env.CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION)）
        System.setProperty("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION", "false");
        assertThat(StopHookPipeline.executePromptSuggestion(false, enabled, null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 4: extractMemories + autoDream
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractMemories/autoDream 仅主线程（agentId=null）触发，子代理跳过")
    void extractMemoriesAndAutoDream_gatedByAgentId() {
        // WHY: CC stopHooks.ts:141-156 —— 记忆提取是主会话级行为；子代理 turn 结束时
        // 触发会把它自己的片段当作主会话记忆写入。agentId != null 必须整段跳过。
        ExtractMemoriesAgent extractAgent = Mockito.mock(ExtractMemoriesAgent.class);
        AutoDreamConsolidator dreamer = Mockito.mock(AutoDreamConsolidator.class);
        List<ChatMessageDto> msgs = List.of();

        // 主线程（agentId=null）：extract 需显式开启 extract mode（OPD-M-54 默认 false），
        // autoDream 无条件触发（CC stopHooks.ts:154-156 !agentId）
        System.setProperty("NEXUSAI_EXTRACT_MEMORIES", "true");
        try {
            // [FIX-EX] 单方法签名（bareMode 并入）：(agentId, extractAgent, dreamer, messages,
            //   isNonInteractiveSession, appendSystemMessage, bareMode)
            assertThat(StopHookPipeline.executeExtractMemoriesAndAutoDream(null, extractAgent, dreamer, msgs, false, null, false, null, null)).isTrue();
            // [IMP-M-P0-3b] 阶段4 改为 fire-and-forget 调 executeExtractMemories（CC stopHooks.ts:149-152
            //   void executeExtractMemories），不再 CompletableFuture.runAsync+extract 直调
            verify(extractAgent, Mockito.timeout(2000)).executeExtractMemories(any(), any(), isNull(), isNull(), isNull());
            verify(dreamer, Mockito.timeout(2000)).consolidateIfNeeded(any(), any(), any(), isNull());
        } finally {
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
        }

        // 子代理（agentId=sub-1）：跳过，agent 不被调用
        ExtractMemoriesAgent subExtract = Mockito.mock(ExtractMemoriesAgent.class);
        AutoDreamConsolidator subDream = Mockito.mock(AutoDreamConsolidator.class);
        assertThat(StopHookPipeline.executeExtractMemoriesAndAutoDream("sub-1", subExtract, subDream, msgs, false, null, false, null, null)).isFalse();
        Mockito.verifyNoInteractions(subExtract, subDream);
    }

    @Test
    @DisplayName("[IMP-M-P0-3b] 阶段4 透传 appendSystemMessage 到 executeExtractMemories（CC stopHooks.ts:149-152）")
    void extractMemoriesAndAutoDream_passesAppendSystemMessage() {
        // WHY: CC stopHooks.ts:149-152 void executeExtractMemories(stopHookContext,
        //   toolUseContext.appendSystemMessage) —— memory_saved 系统消息（extractMemories.ts:490-496）
        //   必须经该回调直达 UI。阶段4 不透传 = 前端永远收不到"记忆已保存"提示。
        ExtractMemoriesAgent extractAgent = Mockito.mock(ExtractMemoriesAgent.class);
        AutoDreamConsolidator dreamer = Mockito.mock(AutoDreamConsolidator.class);
        Consumer<SystemMessage> append = msg -> {};

        System.setProperty("NEXUSAI_EXTRACT_MEMORIES", "true");
        try {
            StopHookPipeline.executeExtractMemoriesAndAutoDream(null, extractAgent, dreamer, List.of(), false, append, false, null, null);
            verify(extractAgent, Mockito.timeout(2000)).executeExtractMemories(any(), eq(append), isNull(), isNull(), isNull());
        } finally {
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
        }
    }

    @Test
    @DisplayName("[IMP-CM-20 OPD-CM3-13/B06] EXTRACT_MEMORIES 模块开关与运行时主 flag（DB 主控）两独立控制（可分别控制）")
    void extractMemoriesAndAutoDream_moduleSwitchIndependence() {
        // WHY (规则 9): CC feature('EXTRACT_MEMORIES')（模块级编译开关，stopHooks.ts:42/142）与
        //   tengu_passport_quail（运行时开关，extractMemories.ts:536 + paths.ts:70）是两独立 flag；
        //   [sm 决策 2026-08-30] 运行时主 flag 由 env 移至 DB settings 列 auto_memory_enabled
        //   （默认 true），env NEXUSAI_EXTRACT_MEMORIES 仅作可选强制关/开运维覆盖。四组合中
        //   「模块 ON + 运行时 OFF（DB off）」与「模块 OFF + 运行时 ON」都不得触发 extract ——
        //   两独立控制各自独立生效/失效。方法返回 true 不代表 extract 已触发（autoDream 主线程
        //   无条件跑），故用 Mockito 断言 extractAgent 是否被调用（CC stopHooks.ts:149
        //   extractMemoriesModule!.executeExtractMemories）。
        ExtractMemoriesAgent extractAgent = Mockito.mock(ExtractMemoriesAgent.class);
        AutoDreamConsolidator dreamer = Mockito.mock(AutoDreamConsolidator.class);
        final String moduleKey = "NEXUSAI_EXTRACT_MEMORIES_MODULE";
        final String runtimeKey = "NEXUSAI_EXTRACT_MEMORIES";
        try {
            // DB 主控置 false（运行时主 flag OFF）· 组合 1 的"运行时 OFF"由 DB 列承载
            bridgeAutoMemoryEnabled(false);
            // 组合 1: 模块 ON（默认 true）+ 运行时 OFF（DB auto_memory_enabled=false，无 env 覆盖）
            //   → extract 不触发（DB 主控 gate 关闭，CC :545 isAutoMemoryEnabled 分支）。
            System.clearProperty(moduleKey);
            System.clearProperty(runtimeKey);
            StopHookPipeline.executeExtractMemoriesAndAutoDream(
                null, extractAgent, dreamer, List.of(), false, null, false, null, null);

            // 组合 2: 模块 OFF + 运行时 env 强制 ON → extract 不触发（模块级 feature('EXTRACT_MEMORIES')
            //   关闭，即使运行时 flag 开也不执行 —— CC 未编译入模块 → extractMemoriesModule 为 null）。
            System.setProperty(moduleKey, "false");
            System.setProperty(runtimeKey, "true");
            StopHookPipeline.executeExtractMemoriesAndAutoDream(
                null, extractAgent, dreamer, List.of(), false, null, false, null, null);

            // 组合 1+2 均未调用 extract（模块/运行时两独立控制各自 OFF 都会拦截）
            Mockito.verifyNoInteractions(extractAgent);

            // 组合 3: 模块 ON（默认 true）+ 运行时 env 强制 ON（运维覆盖绕过 DB gate）→ extract 触发。
            System.clearProperty(moduleKey);
            System.setProperty(runtimeKey, "true");
            StopHookPipeline.executeExtractMemoriesAndAutoDream(
                null, extractAgent, dreamer, List.of(), false, null, false, null, null);
            Mockito.verify(extractAgent, Mockito.timeout(2000)).executeExtractMemories(any(), any(), isNull(), isNull(), isNull());
        } finally {
            System.clearProperty(moduleKey);
            System.clearProperty(runtimeKey);
        }
    }

    @Test
    @DisplayName("extractMemories/autoDream 无 agent 注入时静默返回 false（不抛异常）")
    void extractMemoriesAndAutoDream_noAgentsReturnsFalseSilently() {
        // WHY: Java 端 extractMemoriesAgent/autoDreamConsolidator 是 @Autowired(required=false)，
        // 未注入时 stop 路径必须继续（CC isExtractModeActive 等价 gate 是 agent 非 null）。
        assertThatCode(() -> StopHookPipeline.executeExtractMemoriesAndAutoDream(null, null, null, List.of(), false, null, false, null, null))
            .doesNotThrowAnyException();
        assertThat(StopHookPipeline.executeExtractMemoriesAndAutoDream(null, null, null, List.of(), false, null, false, null, null)).isFalse();
    }

    @Test
    @DisplayName("[FIX-EX] bareMode=true → extract/dream 全部跳过（CC stopHooks.ts:136 if (!isBareMode()) 外层守卫）")
    void extractMemoriesAndAutoDream_bareModeSkipsAll() {
        // WHY: CC stopHooks.ts:133-157 —— --bare / SIMPLE 脚本 -p 调用不想要后台 bookkeeping
        //   （prompt suggestion / memory extraction / auto-dream 三段都在 if (!isBareMode()) 内），
        //   不与 shutdown 争抢资源。bareMode=true 时即使 agent 非 null 也必须跳过（此前 Java 无
        //   bare 分支，/bare 下仍会触发后台 fork）。
        ExtractMemoriesAgent extractAgent = Mockito.mock(ExtractMemoriesAgent.class);
        AutoDreamConsolidator dreamer = Mockito.mock(AutoDreamConsolidator.class);
        assertThat(StopHookPipeline.executeExtractMemoriesAndAutoDream(null, extractAgent, dreamer, List.of(), false, null, true, null, null)).isFalse();
        Mockito.verifyNoInteractions(extractAgent, dreamer);
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 5: cleanupComputerUseAfterTurn
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cleanupComputerUseAfterTurn 子代理跳过且失败静默")
    void cleanupComputerUseAfterTurn_subagentSkippedAndFailureSilent() {
        // WHY: CC stopHooks.ts:159-173 —— CU lock 是 process-wide module 级变量，子代理释放会
        // 让主线程 cleanup 看到 isLockHeldLocally()===false → 无 exit notification。子代理必须
        // 纯跳过；且 CU 清理是 dogfooding，失败静默（try/catch 空 catch）。
        assertThat(StopHookPipeline.cleanupComputerUseAfterTurn("sub-1")).isFalse();
        assertThat(StopHookPipeline.cleanupComputerUseAfterTurn(null)).isTrue();
        // 不可假实现：Java 无 CHICAGO_MCP 基础设施，但调用本身绝不抛异常
        assertThatCode(() -> StopHookPipeline.cleanupComputerUseAfterTurn(null)).doesNotThrowAnyException();
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMPL-10] DEL-TH-06: createStopHookSummaryMessage / StopHookConsumption 测试已删除
    //   （hookCount 0/1 近似追踪随删除；CC 逐条累计需 per-progress 通道，Java 无 — 09 §2）。
    // ════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════
    // [H6-FIX] isExtractModeActive Java 等价（对齐 CC memdir/paths.ts:69-77）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[sm 决策 2026-08-30] isExtractModeActive DB 主控: auto_memory_enabled=true → 交互激活；env 强制关/开覆盖；非交互维度保留")
    void extractMemories_isExtractModeActiveGating() {
        // WHY: CC memdir/paths.ts:69-77 —— extractMemories gate =
        //   feature('EXTRACT_MEMORIES') && !agentId && isExtractModeActive()
        //   isExtractModeActive = flag('tengu_passport_quail') && (!isNonInteractiveSession || flag('tengu_slate_thimble'))
        //   [sm 决策 2026-08-30] 主 flag（≈ tengu_passport_quail）由 env（旧默认 false）移至 DB
        //   settings 列 auto_memory_enabled（默认 true）—— 直接 DB 改即生效；env
        //   NEXUSAI_EXTRACT_MEMORIES 保留为可选强制关/开运维覆盖（null = 不影响，交 DB 主控）。
        try {
            // DB 主控 ON（auto_memory_enabled=true）+ 无 env 覆盖 → 交互激活；非交互仍需 NON_INTERACTIVE
            bridgeAutoMemoryEnabled(true);
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE");
            assertThat(StopHookPipeline.isExtractModeActive(false)).as("DB 主控 true → 交互激活").isTrue();
            assertThat(StopHookPipeline.isExtractModeActive(true)).as("非交互默认仍跳过（NON_INTERACTIVE 未开）").isFalse();
            // 非交互 + 显式允许（≈ tengu_slate_thimble=true）
            System.setProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE", "true");
            assertThat(StopHookPipeline.isExtractModeActive(true)).isTrue();
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE");

            // DB 主控 OFF（auto_memory_enabled=false）+ 无 env 覆盖 → 交互/非交互都关
            bridgeAutoMemoryEnabled(false);
            assertThat(StopHookPipeline.isExtractModeActive(false)).as("DB 主控 false → 不激活").isFalse();
            assertThat(StopHookPipeline.isExtractModeActive(true)).as("DB 主控 false → 非交互也不激活").isFalse();

            // env 强制开覆盖（NEXUSAI_EXTRACT_MEMORIES=true）→ 绕过 DB gate 激活交互
            System.setProperty("NEXUSAI_EXTRACT_MEMORIES", "true");
            assertThat(StopHookPipeline.isExtractModeActive(false)).as("env 强制开覆盖 DB → 激活").isTrue();
            assertThat(StopHookPipeline.isExtractModeActive(true)).as("非交互仍需 NON_INTERACTIVE（env 强制开也过不去）").isFalse();

            // env 强制关覆盖（NEXUSAI_EXTRACT_MEMORIES=false）→ 即使 DB ON 也关闭
            System.setProperty("NEXUSAI_EXTRACT_MEMORIES", "false");
            bridgeAutoMemoryEnabled(true);
            assertThat(StopHookPipeline.isExtractModeActive(false)).as("env 强制关覆盖 DB ON → 关闭").isFalse();
        } finally {
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE");
        }
    }

    @Test
    @DisplayName("5 参 executeExtractMemoriesAndAutoDream：非交互会话 extract 跳过但 autoDream 仍触发")
    void extractMemoriesAndAutoDream_nonInteractiveSkipsExtract() {
        // WHY: CC stopHooks.ts:141-156 —— extractMemories 受 isExtractModeActive 门控
        // （非交互默认跳过），autoDream 仅 !agentId 无条件。此前 Java 只检查 extractAgent != null，
        // 丢失交互维度（CHANGELOG 0.2.29 H6-1）。
        ExtractMemoriesAgent extractAgent = Mockito.mock(ExtractMemoriesAgent.class);
        AutoDreamConsolidator dreamer = Mockito.mock(AutoDreamConsolidator.class);
        // 非交互会话（isNonInteractiveSession=true）：extract 跳过（verifyNoInteractions），
        // autoDream 仍触发（dreamer 非 null + agentId=null 无条件）
        assertThat(StopHookPipeline.executeExtractMemoriesAndAutoDream(null, extractAgent, dreamer, List.of(), true, null, false, null, null)).isTrue();
        Mockito.verifyNoInteractions(extractAgent);
        verify(dreamer, Mockito.timeout(2000)).consolidateIfNeeded(any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("生产可达（DEL-M-48 消除）: 真实 ExtractMemoriesAgent/AutoDreamConsolidator bean 经 StopHookPipeline 触发")
    void extractMemoriesAndAutoDream_productionReachableWithRealAgents(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws InterruptedException {
        // WHY: DEL-M-48 —— @Autowired(required=false) 恒 null 是接线缺口。IMP-M-P0-3 注册
        // 三组件 @Bean 后，阶段 4 必须能用真实 bean 触发（非 mock）：extract fork 真实可达
        // （recording query 被调），auto-dream consolidateIfNeeded 被调（gate 尊重不抛异常）。
        com.nexusai.application.agent.memory.MemoryStorage storage =
            new com.nexusai.application.agent.memory.MemoryStorage(tempDir);
        RunForkedAgent.ForkedQuery recording = params ->
            new ForkedAgentResult(params.messages(), ForkedAgentResult.ForkUsage.empty());
        ExtractMemoriesAgent extractAgent = Mockito.spy(
            new com.nexusai.application.agent.memory.ExtractMemoriesAgent(storage));
        extractAgent.setForkedQuery(recording);
        AutoDreamConsolidator dreamer = Mockito.spy(new AutoDreamConsolidator(storage));
        dreamer.setForkedQuery(recording);
        dreamer.setAutoDreamEnabled(() -> false); // 默认关闭 → consolidateIfNeeded 内部门控跳过 fork

        // [sm 决策] env 强制开覆盖（绕过 DB 主控，测试确定性 —— 不依赖宿主 settings.json/DB 列）
        System.setProperty("NEXUSAI_EXTRACT_MEMORIES", "true");
        try {
            List<ChatMessageDto> msgs = List.of(
                new ChatMessageDto("m1", null, com.nexusai.model.session.dto.Role.user, "user", "hi",
                    null, List.of(), com.nexusai.model.session.dto.FinishReason.stop,
                    null, null, "刚刚", java.time.OffsetDateTime.now(),
                    null, null, null, List.of(), List.of()));
            assertThat(StopHookPipeline.executeExtractMemoriesAndAutoDream(null, extractAgent, dreamer, msgs, false, null, false, null, null)).isTrue();
            // [IMP-M-P0-3b] 阶段4 触发 executeExtractMemories（fire-and-forget · CC stopHooks.ts:149-152）
            verify(extractAgent, Mockito.timeout(2000)).executeExtractMemories(any(), any(), isNull(), isNull(), isNull());
            verify(dreamer, Mockito.timeout(2000)).consolidateIfNeeded(any(), any(), any(), isNull());
            // extract fork 真实发起（recording query 被调用 → lastForkParams 非空）
            // verify(timeout) 在 extract 进入时即返回，poll 等 fork 完成设置 lastForkParams（sessionId=null → "unknown" 键）
            long deadline = System.currentTimeMillis() + 3000;
            while (extractAgent.lastForkParams() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(extractAgent.lastForkParams()).isNotNull();
        } finally {
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [H6-FIX] AgentState.recordStopHookSummary → CollapseHookSummaries.collapse 生产调用点
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("recordStopHookSummary 应用 CollapseHookSummaries 折叠：连续同 label 摘要合并为一条")
    void recordStopHookSummary_collapsesConsecutiveSummaries() {
        // WHY: CHANGELOG 0.2.29 H6-3 —— CollapseHookSummaries.collapse 此前 0 生产调用。
        // recordStopHookSummary 每次记录先 collapse 再存，保证消费端读到折叠形态
        // （CC collapseHookSummaries.ts：并行 tool call 各发一条 stop_hook_summary → 折叠为 1）。
        // [IMP-HOOKS-S7 H6] 折叠机器测试改用带 label 摘要（'PostToolUse'，CC toolExecution.ts:1549
        //   生产形态）—— Stop/SubagentStop 摘要 hookLabel=null 永不折叠（测试 B）。
        AgentState state = new AgentState("s-1");
        state.recordStopHookSummary(new CollapseHookSummaries.SimpleHookMsg(
            "PostToolUse", 1, List.of("echo a"), List.of(), false, true, 100L));
        // [R6-IMP] 8 参构造器携带 stopReason（CC SystemStopHookSummaryMessage.stopReason）
        state.recordStopHookSummary(new CollapseHookSummaries.SimpleHookMsg(
            "PostToolUse", 1, List.of("echo b"), List.of("exit 2"), true, true, 300L, "guard 阻止"));
        List<CollapseHookSummaries.HookMessage> collapsed = state.stopHookSummaries();
        assertThat(collapsed).hasSize(1);
        assertThat(collapsed.get(0).hookCount()).isEqualTo(2);
        assertThat(collapsed.get(0).hookInfos()).containsExactly("echo a", "echo b");
        assertThat(collapsed.get(0).hookErrors()).containsExactly("exit 2");
        assertThat(collapsed.get(0).preventedContinuation()).isTrue();
        assertThat(collapsed.get(0).hasOutput()).isTrue();
        assertThat(collapsed.get(0).totalDurationMs()).isEqualTo(300L);
        // [IMP-HOOKS-S7 D6] CC collapseHookSummaries.ts:41-50 {...group[0]} spread 保留组首
        //   stopReason 原文：组首 7 参构造 stopReason=null（组二非空）→ 结果 null（组首语义，
        //   旧"首个非空"取到组二值 → 红；现断言 null）
        assertThat(collapsed.get(0).stopReason()).isNull();
    }

    @Test
    @DisplayName("hookLabel=null（Stop 生产形态）摘要永不折叠 —— CC stopHooks.ts:297-308 8 参无 hookLabel")
    void recordStopHookSummary_nullLabelNeverCollapses() {
        // WHY: [IMP-HOOKS-S7 H6] LlmAgentLoop Stop/SubagentStop 摘要以 hookLabel=null 记录
        // （CC createStopHookSummaryMessage 8 参省略 hookLabel → undefined）—— isLabeledHookSummary
        // 守卫（hookLabel !== undefined）不过 → 连续两条 Stop 摘要各自保留，绝不合并。
        AgentState state = new AgentState("s-1");
        state.recordStopHookSummary(new CollapseHookSummaries.SimpleHookMsg(
            null, 1, List.of("echo a"), List.of(), false, true, 100L));
        state.recordStopHookSummary(new CollapseHookSummaries.SimpleHookMsg(
            null, 1, List.of("echo b"), List.of(), true, true, 300L));
        assertThat(state.stopHookSummaries()).hasSize(2);
    }

    @Test
    @DisplayName("TaskUpdateTool TaskCompleted hook 从 ctx 拿真实 permissionMode/abortController（非死 null）")
    void taskUpdateTool_completedHook_passesRealPermissionModeAndAbortController() {
        // WHY: CHANGELOG 0.2.29 H6-6 —— TaskUpdateTool 此前仅 override execute(ToolUseBlock)，
        // 拿不到 ToolUseContext → permissionMode/abortController 传死 null，hook 端丢失
        // permission_mode / abort_signal 上下文。CC executeTaskCompletedHooks 9 参
        // （utils/hooks.ts:3789-3799）含 permissionMode + abortSignal，必须真实传入。
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.agent.name", "teammateA");
        System.setProperty("nexusai.team.name", "teamX");
        // TaskUpdateTool call() 内逐次 getTaskListId() 解析列表 ID：mock getTask("tl-1","t-1") 要命中必须让
        // 优先级 1（nexusai.taskListId）返回 "tl-1"（优先级 3 的 nexusai.team.name=teamX 会盖掉回退）。
        System.setProperty("nexusai.taskListId", "tl-1");

        TaskService taskService = Mockito.mock(TaskService.class);
        Task inProgress = new Task("t-1", "subject", "desc", null, "teammateA",
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(java.util.Optional.of(inProgress));
        when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(java.util.Optional.of(inProgress));

        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("recorder", event -> {
            if (event.type() == HookEventType.TASK_COMPLETED) captured.set(event);
            return GenericHook.HookResult.proceed();
        }, HookEventType.TASK_COMPLETED);
        TaskUpdateTool tool = new TaskUpdateTool(taskService, registry);

        AbortController abort = new AbortController();
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "tl-1", abort)
            // permissionMode 是 TUC 位置 10 字段（mode 是位置 3）——对齐生产 baseTuc.permissionMode()
            .withPermissionContext(null, PermissionMode.BYPASS_PERMISSIONS);
        ObjectMapper json = new ObjectMapper();
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("status", "completed"));

        ToolResult result = tool.execute(call, ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("TaskUpdate 成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        HookEvent evt = captured.get();
        assertThat(evt).isNotNull();
        assertThat(evt.permissionMode()).isEqualTo("BYPASS_PERMISSIONS");
        assertThat(evt.data()).containsEntry("abort_signal_cancelled", false);
        // 1 参 execute 委托 2 参：无 ctx → permissionMode 为 null（不抛异常）
        ToolResult r1 = tool.execute(call);
        assertThat(r1).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMPL-10] DEL-L03-01: 内置 GenericHook 消费端集成测试已删除（类已删除 — CC stopHooks.ts
    //   为 turn-end 内联，无 event-consumer 形态；TaskCompleted/TeammateIdle 内联语义
    //   由 StopHookPipeline 5 阶段承载，见上）。
    // ════════════════════════════════════════════════════════════════════
}
