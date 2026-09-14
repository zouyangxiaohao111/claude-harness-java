package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryPromptBuilder;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.subagent.AgentDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-D F4] 子代理 projectRoot 继承测试（M-05/M-06/M-07/M-08/M-12）。
 *
 * <p>WHY（整合版 F4 + OPD-M-38 + subagent-reverify #10）：子代理 spawn 入口把**会话项目根**
 * 交给子代理（agent-memory 注入 / loop workspaceDir / userContext / worktree 隔离根）。
 * 批 4b-1 前该值经 {@code AutoMemPaths#CURRENT_PROJECT_ROOT} ThreadLocal 捕获-回放传播（已删）；
 * 载体删除后一律**显式传参**（用户铁律：会话态一律显式传参，回放不算合规）——
 * 生产来源 = {@code ToolUseContext.effectiveCwd()} / {@code shared(projectRoot)} 参数。
 *
 * <p>RED 条件：删除显式传参（agent-memory 根 / shared 参数 / withEffectiveCwd 覆盖）→
 * 对应断言失败。
 */
@DisplayName("IMP-D · 子代理 projectRoot 继承（F4: M-05/M-06/M-07/M-08/M-12）")
class SubagentProjectRootInheritanceTest {


    @Test
    @DisplayName("spawn 注入后 agent-memory project scope 根 = 会话 projectRoot（无 config-home mkdir 副作用）")
    void agentMemory_projectScope_resolvesToSessionProjectRoot(@TempDir Path project,
                                                               @TempDir Path configHome) {
        // GIVEN: AgentMemoryDirectory（cwd/projectRoot 显式传入 = 会话项目根；批 4b-1 起不再经
        //   ThreadLocal 隐式注入），ensureDirConsumer 捕获 mkdir 目标（断言无 config-home 副作用）
        List<String> mkdirTargets = new CopyOnWriteArrayList<>();
        AgentMemoryDirectory dir = new AgentMemoryDirectory(
            project::toString,
            () -> configHome,
            () -> null,
            () -> Paths.get(project.toString()),
            AutoMemPaths::sanitizePath,
            mkdirTargets::add,
            () -> null,
            () -> true, // autoMemoryEnabled 开 → 走真实 prompt 构建 + mkdir 路径（OPD-M-38 注入面）
            MemoryPromptBuilder.productionDefault());

        // WHEN: 显式传会话项目根（生产 = 各 spawn 入口 ToolUseContext.effectiveCwd()）
        String prompt = dir.loadAgentMemoryPrompt("my-agent",
            AgentMemoryDirectory.AgentMemoryScope.PROJECT, project.toString());

        // THEN: 读 P/.nexusai/agent-memory/（非 config-home · 修 M-05），无 config-home mkdir 副作用
        assertThat(prompt).as("门控开启时必须产出真实 memory prompt").isNotEmpty();
        String expectedDir = Paths.get(project.toString(), NexusaiPaths.getProjectDirName(), "agent-memory", "my-agent").toString();
        assertThat(mkdirTargets).as("project scope mkdir 目标 = P/.{appName}/agent-memory/<type>").contains(expectedDir);
        assertThat(mkdirTargets)
            .as("不得有任何 mkdir 落在 config home（M-05 修复核心）")
            .noneMatch(t -> t.contains(configHome.toString()));
    }

    // [批 4b-1 已退役] 原「asyncWorker 跨线程注入：新线程 capture/set/restore 成对，子代理线程读到
    //   会话 projectRoot」用例 —— 其被验证的机制 = AutoMemPaths.CURRENT_PROJECT_ROOT（ThreadLocal）
    //   捕获-回放，该载体已随批 4b-1 删除（用户铁律：会话态一律显式传参，回放不算合规；
    //   ⛔ 不得保留「删了实现仍恒绿」的回放断言）。子代理项目根的显式继承现由本类其余用例
    //   （显式入参 agent-memory / shared(projectRoot) / withEffectiveCwd）正向锚守护。

    @Test
    @DisplayName("shared(projectRoot) 注入子代理 LoopSessionState.workspaceDir（修 M-07 user.dir 兜底链）")
    void shared_withProjectRoot_injectsWorkspaceDir() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        Path project = Paths.get("P:/test-project");

        // WHEN: 子代理 spawn 传会话 projectRoot（SubagentExecutor Step 20 同构）
        AgentLoopContext ctx = factory.shared(project.toString());

        // THEN: workspaceDir = 会话 P（STOP hook agent_transcript_path → P/<session>/subagents/...）
        assertThat(ctx.sessionState().workspaceDir())
            .as("M-07: 子代理 loop workspaceDir 必须 = 会话 projectRoot（非 user.dir 兜底）")
            .isEqualTo(project);

        // 兼容面：null（无会话上下文）→ 回落既有兜底（workspaceDir bean ?? user.dir），不抛异常
        AgentLoopContext noCtx = factory.shared(null);
        assertThat(noCtx.sessionState().workspaceDir()).as("shared(null) 必须回落非 null 兜底").isNotNull();
    }

    @Test
    @DisplayName("resolveUserContext 根 = 会话 projectRoot（修 M-12：P/CLAUDE.md 进入子代理 userContext）")
    void userContext_readsClaudeMdFromSessionProjectRoot(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("CLAUDE.md"), "# 项目指令\n仅在绑定项目中生效\n");
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "model", "system-prompt");

        // WHEN: 显式传会话项目根后解析 userContext（[批 4b-1] 原经 ThreadLocal 隐式注入；载体已删
        //   ⇒ 由调用方显式传入 = 生产 SubagentExecutor.executeStreaming 的 agentTuc.effectiveCwd()）
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition.builder(
            "test-agent", "when to use", (ctx, dirs) -> "sys").build();
        String userContext = executor.userContextFor(def, project.toString());

        // THEN: P/CLAUDE.md 内容进入 userContext（M-12：非 user.dir/CLAUDE.md）
        assertThat(userContext).as("P/CLAUDE.md 必须被读到（修 M-12 user.dir 根）").contains("项目指令");
    }

    @Test
    @DisplayName("[TL-W3 Phase A] 无会话上下文：不注入 config home 的 CLAUDE.md（不伪造项目根）")
    void userContext_noSessionContext_doesNotInjectConfigHomeClaudeMd(@TempDir Path configHome) throws Exception {
        // WHY（规则九 · 验证意图）：resolveUserContext 的根 = 会话 projectRoot。无会话上下文的线程
        //   （teammate 裸线程 SpawnInProcess / HOOK_EXECUTOR 上由无回放线程调度者：RACERS 池、STOMP、
        //   ConfigChange watcher）旧实现回落 config home ⇒ 把 ~/.nexusai/CLAUDE.md 当成「项目指令」
        //   注入子代理 userContext（读错项目上下文，且静默）。本用例：config home 放一份 CLAUDE.md，
        //   无会话上下文时**必须不注入**（旧 currentSessionProjectRoot() → 注入 → 本断言红）。
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        Files.writeString(configHome.resolve("CLAUDE.md"), "# config-home 指令（不得作为项目 CLAUDE.md 注入）");
        try {
            SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "model", "system-prompt");
            AgentDefinition def = AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "sys").build();

            assertThat(executor.userContextFor(def))
                .as("无会话上下文 ⇒ 不注入 userContext（绝不拿 config home 的 CLAUDE.md 冒充项目指令）")
                .isEmpty();
        } finally {
            NexusaiPaths.setConfigHomeDirOverride(null);
        }
    }

    @Test
    @DisplayName("worktree 隔离 agent-memory 根改绑 effectiveCwd；非 worktree 保持 projectRoot（M-08）")
    void withEffectiveCwd_overridesProjectScopeRoot(@TempDir Path project, @TempDir Path worktree) {
        // [批 4b-1] 原用 productionDefault()（其 projectRootSupplier 经 ThreadLocal 注入）——载体已删，
        //   改为显式商定根构造实例（与生产装配同形）。
        AgentMemoryDirectory dir = new AgentMemoryDirectory(
            project::toString,
            () -> Paths.get(project.toString(), ".nexusai", "agent-memory-base"),
            () -> null,
            () -> project,
            AutoMemPaths::sanitizePath,
            p -> { /* 纯路径解析，不 mkdir */ },
            () -> null,
            () -> true,
            MemoryPromptBuilder.productionDefault());

        // WHEN: SubagentExecutor Step 18 worktree 隔离生效 → withEffectiveCwd(worktreePath)
        AgentMemoryDirectory worktreeDir = dir.withEffectiveCwd(worktree.toString());

        // THEN: worktree 隔离子代理 project scope 根 = worktree（CC getCwd 语义）
        assertThat(worktreeDir.getAgentMemoryDir("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT))
            .as("M-08: worktree 隔离场景 agent-memory 根 = effectiveCwd(worktree)")
            .isEqualTo(Paths.get(worktree.toString(), NexusaiPaths.getProjectDirName(), "agent-memory", "my-agent"));
        // 非 worktree 保持 projectRoot（T5 C3：user.dir 不是 projectRoot 替身）
        assertThat(dir.getAgentMemoryDir("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT))
            .as("非 worktree 场景保持 projectRoot 绑定")
            .isEqualTo(Paths.get(project.toString(), NexusaiPaths.getProjectDirName(), "agent-memory", "my-agent"));
        // 空/blank 覆盖 → 回落原实例（不可变共享安全）
        assertThat(dir.withEffectiveCwd(null)).as("null 覆盖必须回落原实例").isSameAs(dir);
        assertThat(dir.withEffectiveCwd("  ")).as("blank 覆盖必须回落原实例").isSameAs(dir);
    }

    // [批 4b-1 已退役] 原「多嵌套 spawn restore 成对」用例（同上：验证的 ThreadLocal 捕获-回放
    //   载体已删）。嵌套子代理的项目根现由各 spawn 入口**显式传参**（ToolUseContext.effectiveCwd）
    //   承载，无线程槽可残留，故无「成对 restore」语义需要守护。
}
