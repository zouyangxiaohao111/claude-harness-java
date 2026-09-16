package com.nexusai.application.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.workflow.progress.ProgressStore;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * workflow 域「槽收敛」守护 · 对齐 CC {@code src/workflow/ports.ts:54-60} + {@code persistence.ts:32-34}。
 *
 * <p><b>WHY（这道断言为什么重要 · CLAUDE.md 规则 9）</b>：CC 在 {@code bootstrap/state.ts:496-508}
 * 把 projectRoot 定义为「<b>不</b>随 mid-session EnterWorktreeTool 重锚的项目身份根」，
 * 并明令 workflow 的 host cwd 与 journal runsDir <b>必须同根</b>：
 * <blockquote>
 * 「Use projectRoot rather than getCwd(): shares the same root as journalStore's runsDir, otherwise
 * named workflow resolution and journal persistence <b>diverge</b> when the user enters a
 * worktree/sub-directory.」(ports.ts:54-60)
 * </blockquote>
 * 本仓对应 projectRoot 的槽是 {@link SessionProjectRoot}（唯一写入方 = 项目绑定 / run 入口冻结 /
 * DB 回源；⛔ <b>不经</b> {@code EnterWorktreeTool} 写）。另有两条「可变」槽会被 worktree / bash
 * {@code cd} 挪走：{@code SessionCwdHolder.get}（= CC getCwd）与
 * {@code SessionCwdHolder.getOriginalCwd}（= CC getOriginalCwd，EnterWorktreeTool 会重锚）。
 *
 * <p>若 workflow 的脚本解析/宿主 cwd/journal 落在那两条可变槽上，则「脚本去哪找」与
 * 「运行记录存哪」在<b>进入 worktree 或子目录后分叉</b> —— 用户看到的现象是「同一个 workflow
 * 在 worktree 里跑，journal 却写到了别处 / 命名 workflow 突然找不到」。本类把「三者同根」
 * 钉成可执行断言：造出<b>三槽分叉</b>的现场，只有全部锚 {@link SessionProjectRoot} 才通过。
 *
 * <p><b>造现场手法</b>：{@code SessionProjectRoot} = A（项目根，命名 workflow 只放这里），
 * 两条可变槽 = B（模拟 worktree/cd 之后的目录，⛔ 不含任何 workflow 文件）。
 * ⇒ 任何一处仍读可变槽，都会落到 B ⇒ 断言必红。
 */
class WorkflowProjectRootConvergenceTest {

    /** 命名 workflow 名（放在 A 的 workflows 目录下；B 下刻意不放）。 */
    private static final String PROBE_NAME = "p10aProbe";

    /** fake TaskRegistrar 的哨兵消息：跑到 register 即证明「脚本解析已成功」。 */
    private static final String REGISTER_SENTINEL = "P10A_PROBE_REGISTER_REACHED";

    private static final String SESSION_ID = "sess-p10a-convergence";

    private Path projectRoot;   // A：会话项目根（CC getProjectRoot 槽）
    private Path divergedCwd;   // B：worktree / bash cd 之后的目录（两条可变槽）

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = Files.createTempDirectory("p10a-rootA-");
        divergedCwd = Files.createTempDirectory("p10a-divergedB-");

        // A 下放一个命名 workflow（B 下不放 —— 落 B 就找不到）
        Path workflows = projectRoot.resolve(WorkflowConstants.WORKFLOW_DIR_NAME);
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve(PROBE_NAME + ".js"),
                "export const meta = { name: '" + PROBE_NAME + "', description: 'p10a probe' }\nreturn 1");

        // 三槽刻意分叉：项目根 = A；getCwd 槽 + getOriginalCwd 槽 = B
        SessionProjectRoot.setForSession(SESSION_ID, projectRoot.toString());
        SessionCwdHolder.set(SESSION_ID, divergedCwd.toString());
        SessionCwdHolder.setOriginalCwd(SESSION_ID, divergedCwd.toString());
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.clearSession(SESSION_ID);
        SessionCwdHolder.clear(SESSION_ID);
        SessionCwdHolder.clearOriginalCwd(SESSION_ID);
        deleteRecursively(projectRoot);
        deleteRecursively(divergedCwd);
    }

    // ═══════════════ 三者同根（CC ports.ts:54-60 + persistence.ts:32-34） ═══════════════

    @Test
    @DisplayName("journal runsDir 锚会话项目根（CC getRunsDir() = join(getProjectRoot(), ...)）")
    void journalRunsDirAnchorsProjectRoot() {
        assertEquals(realProjectRoot() + "/" + WorkflowConstants.WORKFLOW_RUNS_DIR,
                WorkflowPortsImpl.defaultRunsDir(SESSION_ID),
                "journal runsDir 必须 = <projectRoot>/<WORKFLOW_RUNS_DIR>；落到 worktree/cd 槽 ⇒ "
                        + "「脚本去哪找」与「记录存哪」分叉（ports.ts:54-60 明令避免的 desync）");
    }

    @Test
    @DisplayName("hostFactory 的 host.cwd 锚会话项目根（不是 worktree/bash cd 那条槽）")
    void hostFactoryCwdAnchorsProjectRoot() {
        WorkflowHostContext host = ports().hostFactory()
                .create(new HostFactory.HostFactoryArgs(toolUseContext(), null, null));

        assertEquals(realProjectRoot(), host.cwd(),
                "host.cwd 必须与 journal runsDir 同根 = projectRoot（ports.ts:55-59）；"
                        + "CC 明确「the engine's internal ctx.cwd is only used for resolution (scriptPath/name)」");
    }

    @Test
    @DisplayName("WorkflowServiceImpl launch 的脚本解析基准锚会话项目根（命名 workflow 只在 A 下）")
    void serviceLaunchResolvesScriptFromProjectRoot() {
        WorkflowService service = newService();

        // 解析成功才会走到 taskRegistrar.register（哨兵抛）；解析失败则是 "Named workflow ... not found"。
        // projectRoot 形参 = null ⇒ 调用方未解析 ⇒ 本方法按会话解析（这条腿也必须锚 projectRoot）。
        ExecutionException ex = assertThrows(ExecutionException.class, () -> service
                .launch(probeInput(), toolUseContext(), null, null).get());

        assertFalse(String.valueOf(ex.getCause().getMessage()).contains("not found"),
                "命名 workflow 只在 projectRoot(A) 下 ⇒ 解析基准必须是 A；实得失败："
                        + ex.getCause().getMessage());
        assertTrue(String.valueOf(ex.getCause().getMessage()).contains(REGISTER_SENTINEL),
                "解析通过后必须到达 register 哨兵；实得：" + ex.getCause().getMessage());
    }

    @Test
    @DisplayName("[P10a · D3] 同链只解析一次：形参 projectRoot 优先于会话解析（⛔ 不第二次解析）")
    void launchHonoursCallerResolvedProjectRoot() {
        // 造「两次解析会得到不同答案」的现场：会话根 = B（无 workflow），形参 = A（有 workflow）。
        // ⇒ 只有真正采用调用方下传值（而不是再按会话解析一次）才解析得到。
        SessionProjectRoot.clearSession(SESSION_ID);
        SessionProjectRoot.setForSession(SESSION_ID, divergedCwd.toString());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> newService()
                .launch(probeInput(), toolUseContext(), null, realProjectRoot()).get());

        assertTrue(String.valueOf(ex.getCause().getMessage()).contains(REGISTER_SENTINEL),
                "形参（A，有 workflow）必须优先于会话解析（B，无 workflow）—— 实得："
                        + ex.getCause().getMessage());
    }

    // ═══════════════ 夹具 ═══════════════

    /** A 经 realpath（{@code CwdResolution.normalizeCwd} 同款语义）—— 断言端也要归一化才能逐字比。 */
    private String realProjectRoot() {

        try {
            return projectRoot.toRealPath().toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolUseContext toolUseContext() {
        return new ToolUseContext(UUID.randomUUID(), SESSION_ID, PermissionMode.DEFAULT, Map.of());
    }

    private LaunchInput probeInput() {
        return new LaunchInput(null, PROBE_NAME, null, null, null, null, null, null);
    }

    /** 真 WorkflowServiceImpl + 哨兵 ports（cwdOverride = null ⇒ 走真实项目根解析链）。 */
    private WorkflowService newService() {
        return WorkflowServiceImpl.makeService(new ProbePorts(), new ProgressStore(new ProgressBus()), null);
    }

    private WorkflowPortsImpl ports() {
        // 生产默认 runsDir 解析器 = WorkflowPortsImpl::defaultRunsDir（会话感知）；worktreeManager/runner 允许 null。
        return new WorkflowPortsImpl(new ProgressBus(), new AnalyticsTracker(), new ObjectMapper(),
                WorkflowPortsImpl::defaultRunsDir,
                new SubagentExecutor(null, null, null, null, null, "probe-model", "probe-system-prompt"),
                new AgentWorktreeManager(), null);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 只把 {@code taskRegistrar().register} 做成哨兵抛的 ports 假体 —— 用途是<b>把断言点钉在
     * 「脚本解析已成功」</b>（register 在 resolveSource 之后、引擎启动之前），既不真的跑引擎，
     * 又能用异常消息区分「解析失败」与「解析成功」。
     */
    private static final class ProbePorts implements WorkflowPorts {

        @Override
        public AgentRunner agentRunner() {
            return null;
        }

        @Override
        public AgentAdapterRegistry agentAdapterRegistry() {
            return null; // 断言点钉在 register 哨兵之前，引擎不启动 ⇒ registry 不被触碰
        }

        @Override
        public ProgressEmitter progressEmitter() {
            return event -> {
            };
        }

        @Override
        public TaskRegistrar taskRegistrar() {
            return new TaskRegistrar() {
                @Override
                public RegisterResult register(RegisterOpts opts, HostHandle host) {
                    throw new IllegalStateException(REGISTER_SENTINEL);
                }

                @Override
                public void complete(String runId, String summary) {
                }

                @Override
                public void fail(String runId, String error) {
                }

                @Override
                public void kill(String runId) {
                }

                @Override
                public void registerAgentAbort(String runId, int agentId,
                                               com.nexusai.application.agent.tool.AbortController abortController) {
                }

                @Override
                public void unregisterAgentAbort(String runId, int agentId) {
                }

                @Override
                public boolean killAgent(String runId, int agentId) {
                    return false;
                }

                @Override
                public PendingAction pendingAction(String runId) {
                    return null;
                }
            };
        }

        @Override
        public JournalStore journalStore() {
            return null;
        }

        @Override
        public PermissionGate permissionGate() {
            return host -> false;
        }

        @Override
        public WorkflowLogger logger() {
            return new WorkflowLogger() {
                @Override
                public void debug(String message) {
                }

                @Override
                public void warn(String message, Object... args) {
                }

                @Override
                public void event(String name, Map<String, Object> metadata) {
                }
            };
        }
    }
}
