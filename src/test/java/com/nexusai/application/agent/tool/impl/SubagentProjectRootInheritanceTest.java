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
 * <p>WHY（整合版 F4 + OPD-M-38 + subagent-reverify #10）：子代理线程（sync=工具池线程 /
 * async=new Thread）从不携带 {@link AutoMemPaths#CURRENT_PROJECT_ROOT} ThreadLocal →
 * agent-memory 注入回落 config home（M-05）、hook 载荷 cwd/transcript_path 错位（M-06）、
 * loop workspaceDir=user.dir（M-07）、userContext 根=user.dir（M-12）、worktree 隔离
 * agent-memory 根错位（M-08）。修复 = spawn 入口 capture/set/restore（模板
 * {@code AgentContext.runWithAgentContext} :154-166）+ shared(projectRoot) 参数化 +
 * userContext 改读会话 projectRoot + withEffectiveCwd 覆盖。
 *
 * <p>RED 条件：删除 spawn 注入 / shared 参数 / userContext 根改造 / withEffectiveCwd →
 * 对应断言失败。
 */
@DisplayName("IMP-D · 子代理 projectRoot 继承（F4: M-05/M-06/M-07/M-08/M-12）")
class SubagentProjectRootInheritanceTest {

    @AfterEach
    void tearDown() {
        AutoMemPaths.resetCurrentProjectRoot();
    }

    @Test
    @DisplayName("spawn 注入后 agent-memory project scope 根 = 会话 projectRoot（无 config-home mkdir 副作用）")
    void agentMemory_projectScope_resolvesToSessionProjectRoot(@TempDir Path project,
                                                               @TempDir Path configHome) {
        // GIVEN: 生产同构 AgentMemoryDirectory（cwdSupplier = currentSessionProjectRoot），
        //   ensureDirConsumer 捕获 mkdir 目标（断言无 config-home 副作用）
        List<String> mkdirTargets = new CopyOnWriteArrayList<>();
        AgentMemoryDirectory dir = new AgentMemoryDirectory(
            AutoMemPaths::currentSessionProjectRoot,
            () -> configHome,
            () -> null,
            () -> Paths.get(AutoMemPaths.currentSessionProjectRoot()),
            AutoMemPaths::sanitizePath,
            mkdirTargets::add,
            () -> null,
            () -> true, // autoMemoryEnabled 开 → 走真实 prompt 构建 + mkdir 路径（OPD-M-38 注入面）
            MemoryPromptBuilder.productionDefault());

        // WHEN: 子代理 spawn 线程注入会话 projectRoot（IMP-D asyncWorker set / IMP-C 工具线程传播）
        AutoMemPaths.setCurrentProjectRoot(project.toString());
        String prompt = dir.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT);

        // THEN: 读 P/.nexusai/agent-memory/（非 config-home · 修 M-05），无 config-home mkdir 副作用
        assertThat(prompt).as("门控开启时必须产出真实 memory prompt").isNotEmpty();
        String expectedDir = Paths.get(project.toString(), NexusaiPaths.getProjectDirName(), "agent-memory", "my-agent").toString();
        assertThat(mkdirTargets).as("project scope mkdir 目标 = P/.{appName}/agent-memory/<type>").contains(expectedDir);
        assertThat(mkdirTargets)
            .as("不得有任何 mkdir 落在 config home（M-05 修复核心）")
            .noneMatch(t -> t.contains(configHome.toString()));
    }

    @Test
    @DisplayName("asyncWorker 跨线程注入：新线程 capture/set/restore 成对，子代理线程读到会话 projectRoot")
    void asyncWorker_threadInjection_readsSessionProjectRoot(@TempDir Path project) throws Exception {
        // GIVEN: 调度线程（工具线程，IMP-C 已传播）持有会话值
        AutoMemPaths.setCurrentProjectRoot(project.toString());
        final String parentRoot = AutoMemPaths.captureCurrentProjectRoot();

        // WHEN: 模拟 SubagentTool asyncWorker 线程体（capture 线程原值 → set 父值 → finally restore）
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> insideWorker = new AtomicReference<>();
        AtomicReference<String> afterRestore = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            String prev = AutoMemPaths.captureCurrentProjectRoot();
            try {
                if (parentRoot != null && !parentRoot.isBlank()) {
                    AutoMemPaths.setCurrentProjectRoot(parentRoot);
                }
                insideWorker.set(AutoMemPaths.currentSessionProjectRoot());
            } finally {
                AutoMemPaths.restoreCurrentProjectRoot(prev);
                afterRestore.set(AutoMemPaths.captureCurrentProjectRoot());
            }
            done.countDown();
        });
        worker.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).as("worker 必须在 5s 内完成").isTrue();

        // THEN: 新线程读到会话值（修 M-05）；restore 后线程回落；调度线程值不受影响（ThreadLocal 隔离）
        assertThat(insideWorker.get()).as("asyncWorker 线程内 currentSessionProjectRoot() == 会话 P").isEqualTo(project.toString());
        assertThat(afterRestore.get()).as("restore 线程原值（新线程 null）→ 回落").isNull();
        assertThat(AutoMemPaths.currentSessionProjectRoot())
            .as("调度线程 ThreadLocal 不被子代理线程改动")
            .isEqualTo(project.toString());
    }

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

        // WHEN: 子代理 spawn 线程注入会话 projectRoot 后解析 userContext
        AutoMemPaths.setCurrentProjectRoot(project.toString());
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition.builder(
            "test-agent", "when to use", (ctx, dirs) -> "sys").build();
        String userContext = executor.userContextFor(def);

        // THEN: P/CLAUDE.md 内容进入 userContext（M-12：非 user.dir/CLAUDE.md）
        assertThat(userContext).as("P/CLAUDE.md 必须被读到（修 M-12 user.dir 根）").contains("项目指令");
    }

    @Test
    @DisplayName("worktree 隔离 agent-memory 根改绑 effectiveCwd；非 worktree 保持 projectRoot（M-08）")
    void withEffectiveCwd_overridesProjectScopeRoot(@TempDir Path project, @TempDir Path worktree) {
        AutoMemPaths.setCurrentProjectRoot(project.toString());
        AgentMemoryDirectory dir = AgentMemoryDirectory.productionDefault();

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

    @Test
    @DisplayName("多嵌套 spawn restore 成对：内层恢复外层原值，链尾不串台（subagent-reverify #10）")
    void nestedSpawn_restorePairs_preserveOuterValue(@TempDir Path outer, @TempDir Path inner) {
        // 外层 = 子代理 spawn 作用域（Step 20 模式：capture → set → loop → restore）
        AutoMemPaths.setCurrentProjectRoot(outer.toString());
        String prevOuter = AutoMemPaths.captureCurrentProjectRoot();
        AutoMemPaths.setCurrentProjectRoot(prevOuter);
        try {
            // 内层 = 孙子 spawn（同线程捕获当前值 → set 同值 → restore 同值）
            String prevInner = AutoMemPaths.captureCurrentProjectRoot();
            AutoMemPaths.setCurrentProjectRoot(prevInner);
            try {
                assertThat(AutoMemPaths.currentSessionProjectRoot())
                    .as("嵌套链内子代理读到外层会话值").isEqualTo(outer.toString());
            } finally {
                AutoMemPaths.restoreCurrentProjectRoot(prevInner);
            }
            assertThat(AutoMemPaths.currentSessionProjectRoot())
                .as("内层 restore 后外层值保持").isEqualTo(outer.toString());
        } finally {
            AutoMemPaths.restoreCurrentProjectRoot(prevOuter);
        }
        assertThat(AutoMemPaths.captureCurrentProjectRoot())
            .as("链尾 restore 后无残留").isEqualTo(outer.toString());
    }
}
