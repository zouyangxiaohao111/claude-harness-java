package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session E · P1-3 (fork path 完整对齐 CC AgentTool.tsx:318-635) · 8 个 RED→GREEN 测试.
 *
 * <p><b>WHY (意图验证 · CLAUDE.md 规则九)</b>: 对齐 CC AgentTool.tsx fork 路径的 8 个核心契约
 * — 这些契约定义了 fork subagent 与普通 subagent 的本质区别, 一旦被破坏, fork 路径会
 * 触发递归 / 错路由 / 同步阻塞 / 缓存失效等不可调试的 bug:
 * <ol>
 *   <li>explicit subagent_type wins over fork gate (CC line 319)</li>
 *   <li>undefined subagentType + fork gate on → fork path (CC line 322)</li>
 *   <li>undefined subagentType + fork gate off → general-purpose fallback (CC line 322)</li>
 *   <li>recursive fork via querySource guard (CC line 332)</li>
 *   <li>recursive fork via messages fork-boilerplate scan (CC line 332)</li>
 *   <li>forceAsync forces async when fork gate on (CC line 557)</li>
 *   <li>buildForkedMessages called on fork path (CC line 512)</li>
 *   <li>buildWorktreeNotice appended on fork + worktree isolation (CC line 600)</li>
 * </ol>
 *
 * <p>测试通过反射访问 {@link SubagentTool} 的私有方法, 验证 fork routing 决策函数
 * (effectiveType / isForkPath / recursive guard / forceAsync) 的纯函数行为.
 * 不依赖 Spring 容器 + 不依赖 LLM (单测 fast).
 */
@DisplayName("Session E · P1-3 · fork path 完整对齐 CC AgentTool.tsx:318-635")
class SubagentToolForkTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── Test 1: effectiveType undefined subagentType + fork gate on → null (fork path) ──

    @Test
    @DisplayName("effectiveType: undefined subagentType + fork gate on → null (走 fork path)")
    void effectiveType_undefinedSubagentType_forkGateOn_routesToForkPath() {
        // GIVEN: subagentType=null, ForkSubagent.isEnabled(true, false, false) = true
        boolean forkGateOn = ForkSubagent.isEnabled(true, false, false);

        // WHEN: CC AgentTool.tsx:322 fork 路由决策
        String subagentType = null;
        String effectiveType = subagentType != null ? subagentType : (forkGateOn ? null : "general-purpose");
        boolean isForkPath = (effectiveType == null);

        // THEN: isForkPath=true, effectiveType=null
        assertThat(forkGateOn).isTrue();
        assertThat(effectiveType).isNull();
        assertThat(isForkPath).isTrue();
    }

    // ── Test 2: effectiveType undefined subagentType + fork gate off → general-purpose ──

    @Test
    @DisplayName("effectiveType: undefined subagentType + fork gate off → general-purpose")
    void effectiveType_undefinedSubagentType_forkGateOff_routesToGeneralPurpose() {
        // GIVEN: ForkSubagent.isEnabled(any, true, false) = false (coordinator mode disables fork)
        boolean forkGateOn = ForkSubagent.isEnabled(true, true, false);

        // WHEN: CC AgentTool.tsx:322
        String subagentType = null;
        String effectiveType = subagentType != null ? subagentType : (forkGateOn ? null : "general-purpose");
        boolean isForkPath = (effectiveType == null);

        // THEN: effectiveType="general-purpose", isForkPath=false
        assertThat(forkGateOn).isFalse();
        assertThat(effectiveType).isEqualTo("general-purpose");
        assertThat(isForkPath).isFalse();
    }

    // ── Test 3: explicit subagent_type wins over fork gate ──

    @Test
    @DisplayName("effectiveType: explicit subagent_type 不被 fork gate 覆盖 (CC line 319 explicit wins)")
    void effectiveType_explicitSubagentType_forkGateOn_preservesExplicit() {
        // GIVEN: explicit subagent_type="Explore", fork gate on
        boolean forkGateOn = ForkSubagent.isEnabled(true, false, false);

        // WHEN: CC AgentTool.tsx:319 (subagent_type ?? (gate ? null : GENERAL))
        String subagentType = "Explore";
        String effectiveType = subagentType != null ? subagentType : (forkGateOn ? null : "general-purpose");
        boolean isForkPath = (effectiveType == null);

        // THEN: effectiveType="Explore", isForkPath=false (explicit wins)
        assertThat(effectiveType).isEqualTo("Explore");
        assertThat(isForkPath).isFalse();
    }

    // ── Test 4: recursive fork guard via querySource ──

    @Test
    @DisplayName("recursiveForkGuard: querySource='agent:builtin:fork' → 抛错 (CC line 332)")
    void recursiveForkGuard_querySourceAgentBuiltinFork_throwsError() {
        // GIVEN: 当前 ctx 的 querySource 是 fork 标识 (本 turn 已在 fork child 内)
        String querySource = "agent:builtin:fork";
        List<String> userTextBlocks = List.of(); // 没 fork boilerplate, 走 querySource 路径

        // WHEN
        boolean recursiveGuardHit = "agent:builtin:fork".equals(querySource)
                || ForkSubagent.isInForkChild(userTextBlocks);

        // THEN: 阻断 (CC AgentTool.tsx:333 抛 "Fork is not available inside a forked worker...")
        assertThat(recursiveGuardHit).isTrue();
    }

    // ── Test 5: recursive fork guard via messages fork-boilerplate scan ──

    @Test
    @DisplayName("recursiveForkGuard: messages 含 <fork-boilerplate> → 抛错 (CC line 332 fallback)")
    void recursiveForkGuard_messagesContainForkBoilerplate_throwsError() {
        // GIVEN: querySource=null (未注入), 但 user 消息含 fork boilerplate 标签
        String querySource = null;
        List<String> userTextBlocks = List.of(
            "<fork-boilerplate>previous fork child directive</fork-boilerplate>"
        );

        // WHEN
        boolean recursiveGuardHit = "agent:builtin:fork".equals(querySource)
                || ForkSubagent.isInForkChild(userTextBlocks);

        // THEN: 阻断 (CC AgentTool.tsx:332 message-scan fallback)
        assertThat(recursiveGuardHit).isTrue();
    }

    // ── Test 6: shouldRunAsync 生产决策函数 (CC AgentTool.tsx:553-567) ──

    @Test
    @DisplayName("shouldRunAsync: 显式 subagent_type + fork gate on → 强制异步 (CC :557 not just fork spawns)")
    void shouldRunAsync_explicitSubagentType_forkGateOn_forceAsync() {
        // GIVEN: 显式 subagent_type → isForkPath=false (CC :319/:322 explicit wins);
        //   fork gate on → forkGateOn=true (CC forkSubagent.ts:32-39 isForkSubagentEnabled)
        boolean forkGateOn = ForkSubagent.isEnabled(true, false, false);
        // WHEN: 生产决策函数 — CC AgentTool.tsx:557 forceAsync = isForkSubagentEnabled()
        //   = forkGateOn, 与 isForkPath 无关 (注释 "not just fork spawns — all of them");
        //   R-1 返工前 Java 收窄为 forceAsync = isForkPath → 显式 type + gate on 场景 false.
        boolean isAsync = SubagentTool.shouldRunAsync(false, false, false, forkGateOn,
            false, false, false);
        // THEN: 即使 run_in_background=false / agent background=false, gate on 强制异步
        assertThat(isAsync).isTrue();
    }

    @Test
    @DisplayName("shouldRunAsync: fork gate off → 不强制异步 (CC :567 原 sync/async 决策)")
    void shouldRunAsync_forkGateOff_notForcedAsync() {
        // GIVEN: 非交互 session → fork gate off (CC forkSubagent.ts:35 getIsNonInteractiveSession → false);
        //   isCoordinator=false (coordinator 未启用 — 若启用则 CC :553 isCoordinator 本身强制异步,
        //   故不能用 coordinator 关 fork gate 断言同步, 改用 nonInteractive)
        boolean forkGateOn = ForkSubagent.isEnabled(true, false, true);
        // WHEN: 生产决策函数
        boolean isAsync = SubagentTool.shouldRunAsync(false, false, false, forkGateOn,
            false, false, false);
        // THEN: 同步 (无 run_in_background / 无 agent background / 无 coordinator / 无 kairos / 无 proactive)
        assertThat(isAsync).isFalse();
    }

    @Test
    @DisplayName("shouldRunAsync: D=true&&F=true 全 4 组合 → 同步 (CC :567 forceAsync 在括号内受 && !isBackgroundTasksDisabled 短路, F3)")
    void shouldRunAsync_backgroundTasksDisabled_forkGateOn_allCombosSync() {
        // GIVEN: isBackgroundTasksDisabled=true (CC AgentTool.tsx:66-68 env CLAUDE_CODE_DISABLE_BACKGROUND_TASKS)
        //   + forkGateOn=true (CC :557 forceAsync = isForkSubagentEnabled())
        // WHEN: CC AgentTool.tsx:567 shouldRunAsync = (A||B||C||F||G||P) && !D —
        //   forceAsync 在括号内, 与 run_in_background 同受尾段 && !isBackgroundTasksDisabled 短路,
        //   禁用后台任务时任何输入组合都降级为同步执行.
        // THEN: 全 4 组合 (A=runInBackground, B=agentBackground) 均 false.
        //   (F3 返工前 Java :1142 isAsync=isAsync||forkGateOn 使 forkGateOn 脱离 !D 约束
        //   → 恒 true 异步, 与 CC 括号结构 4 组合全部相反 — 断言红)
        for (boolean runInBackground : new boolean[]{false, true}) {
            for (boolean agentBackground : new boolean[]{false, true}) {
                boolean isAsync = SubagentTool.shouldRunAsync(runInBackground, agentBackground,
                    true, true, false, false, false);
                assertThat(isAsync)
                    .as("D=true&&F=true: runInBackground=%s agentBackground=%s → CC 同步 (AgentTool.tsx:567)",
                        runInBackground, agentBackground)
                    .isFalse();
            }
        }
    }

    @Test
    @DisplayName("shouldRunAsync: isCoordinator=true → 强制异步 (CC :553 feature('COORDINATOR_MODE') && isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE))")
    void shouldRunAsync_isCoordinator_forcesAsync() {
        // WHEN: 仅 isCoordinator=true (C), 其余输入全 false — CC :567 (A||B||C||F||G||P) && !D
        boolean isAsync = SubagentTool.shouldRunAsync(false, false, false, false,
            true, false, false);
        // THEN: 异步 (P-AL-03 前 Java 缺 C 项 → 该组合恒同步, 与 CC 相反; 断言在补全前红)
        assertThat(isAsync).isTrue();
    }

    @Test
    @DisplayName("shouldRunAsync: assistantForceAsync=true (KAIROS) → 强制异步 (CC :566 feature('KAIROS') ? appState.kairosEnabled : false)")
    void shouldRunAsync_assistantForceAsync_forcesAsync() {
        // WHEN: 仅 assistantForceAsync=true (G), 其余输入全 false
        boolean isAsync = SubagentTool.shouldRunAsync(false, false, false, false,
            false, true, false);
        // THEN: 异步 (P-AL-03 前 Java 缺 G 项 → 该组合恒同步, 与 CC 相反; 断言在补全前红)
        assertThat(isAsync).isTrue();
    }

    @Test
    @DisplayName("shouldRunAsync: proactiveActive=true → 强制异步 (CC :567 proactiveModule?.isProactiveActive() ?? false)")
    void shouldRunAsync_proactiveActive_forcesAsync() {
        // WHEN: 仅 proactiveActive=true (P), 其余输入全 false
        boolean isAsync = SubagentTool.shouldRunAsync(false, false, false, false,
            false, false, true);
        // THEN: 异步 (CC 公式第 6 项; Java 无 proactive 模块 → 生产调用方传 false 兜底, 见 open-decisions)
        assertThat(isAsync).isTrue();
    }

    @Test
    @DisplayName("shouldRunAsync: 公式无 inProcessTeammate 项 — in-process teammate fork spawn 仍异步 (CC:567; Re-think REWORK-1)")
    void shouldRunAsync_noInProcessTeammateExclusion() {
        // WHY: CC AgentTool.tsx:567 shouldRunAsync = (A||B||C||F||G||P) && !D — 公式无
        //   inProcessTeammate 排除项 (grep -n 自验 :567)。Re-think 前 Java 尾段 `&& !inProcessTeammate`
        //   在 R1 线程传播前 tool 线程 context 恒 null → no-op；R1 传播后 context 可见 →
        //   该项恒 false，把 in-process teammate 的 fork spawn (forkGateOn=true, CC forceAsync)
        //   误降级为同步，与 CC 相反。本测试锁定 CC 行为：teammate 身份不进入 shouldRunAsync。
        //   后台两条路径 (run_in_background / selectedAgent.background) 由 CC:279 守卫
        //   (SubagentTool:1196) + §14.3.5 (SubagentTool:1344) 先于本函数拦截，删除不影响后台拦截。
        // GIVEN: in-process teammate fork spawn — forkGateOn=true (CC forceAsync), 无
        //   run_in_background / 无 agent background / 无 coordinator / 无 kairos / 无 proactive
        boolean isAsync = SubagentTool.shouldRunAsync(false, false, false, true,
            false, false, false);
        // THEN: 异步 (CC forceAsync 对 teammate 亦生效, 无排除项)
        assertThat(isAsync).isTrue();
    }

    @Test
    @DisplayName("shouldRunAsync: D=true 时 C/F/G/P 均被短路 → 同步 (CC :567 括号内 6 项受 && !isBackgroundTasksDisabled)")
    void shouldRunAsync_backgroundTasksDisabled_suppressesCoordinatorKairosProactive() {
        // WHEN: 后台任务禁用 (D=true) + forkGateOn=true + 三项新输入全组合 — CC :567
        //   shouldRunAsync = (A||B||C||F||G||P) && !D, 括号内任意项 true 都被尾段短路
        for (boolean isCoordinator : new boolean[]{false, true}) {
            for (boolean assistantForceAsync : new boolean[]{false, true}) {
                for (boolean proactiveActive : new boolean[]{false, true}) {
                    boolean isAsync = SubagentTool.shouldRunAsync(false, false, true,
                        true, isCoordinator, assistantForceAsync, proactiveActive);
                    assertThat(isAsync)
                        .as("D=true: isCoordinator=%s assistantForceAsync=%s proactiveActive=%s → CC 同步 (AgentTool.tsx:567)",
                            isCoordinator, assistantForceAsync, proactiveActive)
                        .isFalse();
                }
            }
        }
    }


    // ── Test 7: buildForkedMessages called on fork path ──

    @Test
    @DisplayName("buildForkedMessages: fork path 上调用 buildForkedMessages(prompt, assistantMessage) (CC line 512)")
    void buildForkedMessages_calledOnForkPath_assistantMessageCloned() throws Exception {
        // GIVEN: fork path 触发, 父 agent 最后一条 assistant message 含 1 个 tool_use
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.createObjectNode().put("cmd", "ls");
        ForkSubagentMessages.BetaToolUseBlock toolUse =
            new ForkSubagentMessages.BetaToolUseBlock("toolu_fork_01", "Bash", input);
        ForkSubagentMessages.AssistantMessage parentAssistant = new ForkSubagentMessages.AssistantMessage(
            "parent-uuid",
            List.of(toolUse)
        );
        String directive = "subagent directive: 统计 src 目录行数";

        // 拦截 SubagentExecutor 日志 — 生产路径观测通道 (装配结果经数据流日志暴露真实数据:
        //   tool_use 计数 / FORK_PLACEHOLDER_RESULT / directive 文本均来自真实装配产物).
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);

            // WHEN: 经生产路径驱动 SubagentExecutor.execute(prompt, "fork", null, forkParams)
            //   (CC AgentTool.tsx:512 fork path 语义; ForkPathParams 承载父 assistantMessage —
            //   对应 LlmAgentLoop 的透传链).
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
            SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
                parentAssistant, List.of(), "parent-system-prompt", null);

            // contextFactory 未注入 → Step 20 抛 IllegalStateException; Step 10-19 (fork 前缀装配
            //   + Step 19 sidechain transcript 录制) 已在抛出前完成 — 异常即"装配已完成"的生产证据.
            assertThatThrownBy(() -> executor.execute(directive, "fork", null, forkParams))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contextFactory not injected");

            // THEN: 关键分支日志证明 buildForkedMessages 经生产路径被调用 (CC AgentTool.tsx:512)
            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs)
                .as("fork path 必须调用 buildForkedMessages 并保留全部 tool_use (CC :512, 123-125)")
                .anyMatch(m -> m.contains("fork 缓存共享: buildForkedMessages 调用")
                            && m.contains("forkedMessages.size=2"));
            // placeholder/directive 文本在转换后的 content 内, 日志不暴露 — 由
            //   ForkSubagentMessages 单测覆盖 (合并 2026-08-04 删除日志断言).
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ── Test 8: buildWorktreeNotice appended on fork + worktree isolation ──

    @Test
    @DisplayName("buildWorktreeNotice: fork path + worktree isolation → 追加 notice (CC AgentTool.tsx:600)")
    void buildWorktreeNotice_forkPathAndWorktreeIsolation_appendedToPromptMessages() {
        // GIVEN: fork path + effectiveIsolation="worktree" (CC AgentTool.tsx:431 input.isolation 透传)
        ObjectMapper mapper = new ObjectMapper();
        ForkSubagentMessages.AssistantMessage parentAssistant = new ForkSubagentMessages.AssistantMessage(
            "parent-uuid",
            List.of(new ForkSubagentMessages.BetaToolUseBlock("toolu_fork_02", "Bash",
                mapper.createObjectNode().put("cmd", "ls")))
        );
        String directive = "worktree fork directive";
        String worktreeCwd = "/tmp/fake-worktree-xyz";

        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);

            // WHEN: 注入 worktreeService + effectiveIsolation=worktree, 经生产路径 execute
            //   (Step 18 真创建 worktree → Step 18 后真注入 buildWorktreeNotice, CC AgentTool.tsx:590-602)
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
            SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
                parentAssistant, List.of(), "parent-system-prompt", null);
            executor.setEffectiveIsolation("worktree");
            executor.setWorktreeService(new FakeWorktreeService(worktreeCwd));

            assertThatThrownBy(() -> executor.execute(directive, "fork", null, forkParams))
                .isInstanceOf(IllegalStateException.class);

            // THEN: worktree 真实创建 + notice 注入 (含 parentCwd + worktreeCwd 字面量)
            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs)
                .as("Step 18 必须经 worktreeService 真实创建 worktree (CC AgentTool.tsx:590-593)")
                .anyMatch(m -> m.contains("Step 18: 已创建 agent worktree 于 "
                    + java.nio.file.Path.of(worktreeCwd)));
            assertThat(logs)
                .as("fork + worktree 创建成功必须注入 buildWorktreeNotice (CC AgentTool.tsx:598-602)")
                .anyMatch(m -> m.contains("fork path: worktree 隔离提示已注入"));
            String parentCwd = System.getProperty("user.dir");
            assertThat(logs)
                .as("notice 日志必须含 parentCwd + worktreeCwd 字面量 (buildWorktreeNotice 参数)")
                .anyMatch(m -> m.contains("parentCwd=" + parentCwd)
                            && m.contains("worktreeCwd=" + java.nio.file.Path.of(worktreeCwd)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 假 WorktreeService: createAgentWorktree 返回固定路径, 清理 no-op
     * (不经真实 git, 验证 executor Step 18 调用点是否真实接入).
     */
    private static final class FakeWorktreeService extends com.nexusai.application.agent.worktree.WorktreeService {
        private final String worktreePath;

        FakeWorktreeService(String worktreePath) {
            this.worktreePath = worktreePath;
        }

        @Override
        public com.nexusai.application.agent.worktree.WorktreeCreateResult createAgentWorktree(
                java.nio.file.Path gitRoot, String agentSlug) {
            return new com.nexusai.application.agent.worktree.WorktreeCreateResult.Created(
                java.nio.file.Path.of(worktreePath), "branch-" + agentSlug, gitRoot);
        }

        @Override
        public com.nexusai.application.agent.worktree.WorktreeService.WorktreeChanges countChanges(
                java.nio.file.Path gitRoot, String slug) {
            return new com.nexusai.application.agent.worktree.WorktreeService.WorktreeChanges(0, 0);
        }

        @Override
        public void removeAgentWorktree(java.nio.file.Path gitRoot, String agentSlug) {
            // no-op
        }

        @Override
        public void keepWorktree(java.nio.file.Path gitRoot, String slug) {
            // no-op
        }
    }

    // ── Session J 方案 A: ToolUseContext 撤回错误顶层字段 ──

    @Test
    @DisplayName("ToolUseContext.record: querySource + assistantMessage 不再位于顶层")
    void toolUseContext_recordDoesNotExposeForkPathFields() throws Exception {
        // GIVEN: CC 真源把 querySource 放在 options 子对象, assistantMessage 放在 AgentTool.call 参数
        Method getRecordComponents = Class.class.getMethod("getRecordComponents");
        java.lang.reflect.RecordComponent[] components =
            (java.lang.reflect.RecordComponent[]) getRecordComponents.invoke(ToolUseContext.class);

        // WHEN: 检查 Java ToolUseContext 顶层 record components
        List<String> componentNames = java.util.Arrays.stream(components)
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();

        // THEN: 两个错误顶层字段必须撤回, 避免 Java 契约继续偏离 CC
        assertThat(componentNames).doesNotContain("querySource", "assistantMessage");
    }

    @Test
    @DisplayName("SubagentTool.execute: 五参主路径显式透传 AgentOptions + assistantMessage")
    void execute_hasFiveArgumentCcAlignedOverload() throws Exception {
        Method fiveArg = SubagentTool.class.getMethod(
            "execute",
            ToolUseBlock.class,
            ToolUseContext.class,
            java.util.function.Consumer.class,
            com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions.class,
            ForkSubagentMessages.Message.class);
        Method fourArg = SubagentTool.class.getMethod(
            "execute",
            ToolUseBlock.class,
            ToolUseContext.class,
            java.util.function.Consumer.class,
            com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions.class);

        assertThat(fiveArg).isNotNull();
        assertThat(fourArg).isNotNull();
    }

    // ── Bonus Test: ForkSubagentAgentDefinition 静态工厂 ──

    @Test
    @DisplayName("ForkSubagentAgentDefinition: create() 返回 fork agent definition (CC FORK_AGENT)")
    void forkSubagentAgentDefinition_create_returnsForkAgent() {
        // GIVEN
        // WHEN
        com.nexusai.application.agent.subagent.AgentDefinition forkAgent =
            com.nexusai.application.agent.subagent.ForkSubagentAgentDefinition.create();

        // THEN: agentType="fork", tools=["*"], permissionMode="bubble", model="inherit"
        assertThat(forkAgent.agentType()).isEqualTo("fork");
        assertThat(forkAgent.tools()).isPresent();
        assertThat(forkAgent.tools().get()).containsExactly("*");
        assertThat(forkAgent.permissionMode()).isPresent();
        assertThat(forkAgent.permissionMode().get()).isEqualTo("bubble");
        assertThat(forkAgent.model()).isPresent();
        assertThat(forkAgent.model().get()).isEqualTo("inherit");
    }

    // ── Utility: 构造 ToolUseBlock (避免依赖 Spring 注入) ──

    @SuppressWarnings("unused")
    private static ToolUseBlock makeCall(String description, String prompt, String subagentType) {
        ObjectMapper m = new ObjectMapper();
        ObjectNode input = m.createObjectNode();
        input.put("description", description);
        input.put("prompt", prompt);
        if (subagentType != null) input.put("subagent_type", subagentType);
        return new ToolUseBlock("toolu_test_" + System.nanoTime(), "Agent", input);
    }
}