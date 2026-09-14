package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeService;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [批 subcwd] SubagentExecutor Step 18 子代理 effectiveCwd 决策契约。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：Step 18 的 {@code worktreePath} 初值曾是
 * {@code System.getProperty("user.dir")}（<b>进程级值</b> = 后端 JVM 启动目录），并且
 * {@code withEffectiveCwd} 是<b>无条件</b>调用 —— 于是一 JVM 多会话下，<b>所有非 worktree 隔离
 * 的子代理</b>（含不传 isolation 的默认情形）其 {@code ctx.effectiveCwd()} 都变成服务器启动目录，
 * 并把 {@code createSubagentContext.create} 从父会话继承来的项目根<b>覆盖掉</b>。
 * 实测（批 subcwd 探针）：user.dir={@code D:\...\<worktree>\backend}，父会话项目根
 * ={@code C:\...\Temp\junit-1460…}，透传到子 TUC 的 effectiveCwd = user.dir（父根被覆盖）。
 *
 * <p>对齐 CC：{@code AgentTool.tsx:793-794}
 * {@code const cwdOverridePath = cwd ?? worktreeInfo?.worktreePath;
 * wrapWithCwd = fn => cwdOverridePath ? runWithCwdOverride(cwdOverridePath, fn) : fn()}
 * —— <b>没有 override 就是继承环境值</b>（CC 一进程一会话使其天然正确；本仓必须显式继承父会话根）。
 *
 * <p><b>驱动方式</b>：全部经生产入口 {@code SubagentExecutor.execute(...)} 真跑到 Step 18
 * （contextFactory 未注入 ⇒ 在 Step 20 抛 ISE，异常即「装配已完成」的生产证据，同
 * {@code SubagentToolForkTest} 既有手法），断言取自 Step 18 的真实数据流日志
 * （{@code [Phase A 任务 4] effectiveCwd=… 透传到子 ToolUseContext}），非反射取私有字段。
 *
 * <p><b>反向实验配方（每条断言）</b>见各用例内 {@code 反向实验:} 注释。
 */
@DisplayName("批 subcwd · SubagentExecutor Step 18 子代理 effectiveCwd（父会话根继承 / worktree 隔离 / fail-loud）")
class SubagentStep18EffectiveCwdTest {

    private static final String PARENT_SESSION = "sess-subcwd-step18-parent";

    @AfterEach
    void tearDown() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    /** 父会话 TUC：绑定项目根 = {@code sessionProject}（与进程 user.dir 必然不同）。 */
    private static ToolUseContext parentTucBoundTo(Path sessionProject) {
        SessionProjectRoot.setForSession(PARENT_SESSION, sessionProject.toString());
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), PARENT_SESSION);
        // 前置条件：父 ctx 的会话项目根确实解析出来了，且 ≠ 进程启动目录（否则本类断言无鉴别力）
        assertThat(tuc.effectiveCwd()).as("前置条件：父 TUC effectiveCwd = 会话项目根").isNotNull();
        assertThat(tuc.effectiveCwd().toString())
            .as("前置条件：会话项目根必须 ≠ 进程 user.dir，否则断言无鉴别力")
            .isNotEqualTo(CwdResolution.normalizeCwd(System.getProperty("user.dir")));
        return tuc;
    }

    private static SubagentExecutor.ForkPathParams forkParams(String toolUseId) {
        ObjectMapper mapper = new ObjectMapper();
        ForkSubagentMessages.AssistantMessage parentAssistant = new ForkSubagentMessages.AssistantMessage(
            "parent-uuid",
            List.of(new ForkSubagentMessages.BetaToolUseBlock(toolUseId, "Bash",
                mapper.createObjectNode().put("cmd", "ls"))));
        return new SubagentExecutor.ForkPathParams(parentAssistant, List.of(), "parent-system-prompt", null);
    }

    private static SubagentExecutor executorWith(ToolUseContext parentTuc) {
        return new SubagentExecutor(
            ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt", parentTuc);
    }

    /** 跑生产路径并返回日志行（Step 20 抛 ISE 属预期：装配已完成）。 */
    private static List<String> runAndCaptureLogs(SubagentExecutor executor,
                                                  SubagentExecutor.ForkPathParams forkParams) {
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level prev = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            try {
                executor.execute("subcwd directive", "fork", null, forkParams);
            } catch (Exception expected) {
                // contextFactory 未注入 ⇒ Step 20 抛 ISE；Step 18 已执行完毕
            }
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.setLevel(prev);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 取 Step 18 数据流日志里的 effectiveCwd 字面量。 */
    private static String effectiveCwdFromLogs(List<String> logs) {
        return logs.stream()
            .filter(m -> m.contains("[Phase A 任务 4] effectiveCwd="))
            .map(m -> m.substring(m.indexOf("effectiveCwd=") + "effectiveCwd=".length(),
                m.indexOf(" 透传到子 ToolUseContext")))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("未捕获 Step 18 effectiveCwd 日志: " + logs));
    }

    @Test
    @DisplayName("① 非 worktree 子代理：effectiveCwd = **父会话项目根**（不覆盖为进程 user.dir）")
    void nonWorktreeSubagent_inheritsParentSessionRoot(@TempDir Path sessionProject) {
        ToolUseContext parentTuc = parentTucBoundTo(sessionProject);
        SubagentExecutor executor = executorWith(parentTuc);

        List<String> logs = runAndCaptureLogs(executor, forkParams("toolu_subcwd_1"));
        String cwd = effectiveCwdFromLogs(logs);

        assertThat(cwd)
            .as("非 worktree 子代理必须继承父会话项目根（CC AgentTool.tsx:793-794 无 override ⇒ 继承）")
            .isEqualTo(parentTuc.effectiveCwd().toString());
        assertThat(cwd)
            .as("⛔ 不得是进程 user.dir（服务器启动目录）")
            .isNotEqualTo(CwdResolution.normalizeCwd(System.getProperty("user.dir")));
        // 反向实验: 把 Step 18 恢复成「worktreePath 初值 = System.getProperty("user.dir") +
        //   withEffectiveCwd 无条件调用」⇒ 本断言变红（cwd = user.dir）。实测见交付报告。
    }

    @Test
    @DisplayName("② isolation=worktree：effectiveCwd 仍指向 **worktree 路径**（防把该支路一起改坏）")
    void isolationWorktree_effectiveCwd_pointsAtWorktreePath(@TempDir Path sessionProject,
                                                             @TempDir Path fakeWorktree) {
        ToolUseContext parentTuc = parentTucBoundTo(sessionProject);
        SubagentExecutor executor = executorWith(parentTuc);
        executor.setEffectiveIsolation("worktree");
        executor.setWorktreeService(new FakeWorktreeService(fakeWorktree.toString(), null));

        List<String> logs = runAndCaptureLogs(executor, forkParams("toolu_subcwd_2"));
        String cwd = effectiveCwdFromLogs(logs);

        assertThat(cwd)
            .as("worktree 隔离支路必须仍然把 effectiveCwd 覆盖为 worktree 路径"
                + "（对齐 CC runWithCwdOverride，批 subcwd ⛔ 不得改坏）")
            .isEqualTo(fakeWorktree.toString());
        assertThat(cwd)
            .as("必须是 worktree 路径而不是父会话根（两条支路的结果必须不同，否则本条无鉴别力）")
            .isNotEqualTo(parentTuc.effectiveCwd().toString());
        // 反向实验: 把 Step 18 的 `worktreePath != null ? withEffectiveCwd(...) : subagentCtx`
        //   改成恒不调用（直接 subagentCtx）⇒ 本断言变红（cwd = 父会话根）。
    }

    @Test
    @DisplayName("③ worktree 创建失败 ⇒ fail-loud（⛔ 不回落 user.dir fail-open）")
    void worktreeCreationFailure_failLoud(@TempDir Path sessionProject) {
        ToolUseContext parentTuc = parentTucBoundTo(sessionProject);
        SubagentExecutor executor = executorWith(parentTuc);
        executor.setEffectiveIsolation("worktree");
        executor.setWorktreeService(new FakeWorktreeService("/dev/null/never-used",
            new IllegalStateException("boom: not a git repository")));

        assertThatThrownBy(() -> executor.execute("subcwd directive", "fork", null,
            forkParams("toolu_subcwd_3")))
            .as("isolation=worktree 建树失败必须 fail-loud（CC AgentTool.tsx:741-743 外层无 try/catch）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worktree 隔离创建失败")
            .hasMessageContaining("boom: not a git repository");
        // 反向实验: 把 catch 块恢复成「log.warn + agentWorktreeSlug = null」（fail-open）⇒ 本断言
        //   变红（异常变成 Step 20 的 contextFactory ISE，message 不含「worktree 隔离创建失败」）。
    }

    @Test
    @DisplayName("④ worktree 创建基准 = **父会话项目根**（不是进程 user.dir）")
    void worktreeCreationBase_parentSessionRoot(@TempDir Path sessionProject,
                                                @TempDir Path fakeWorktree) {
        ToolUseContext parentTuc = parentTucBoundTo(sessionProject);
        SubagentExecutor executor = executorWith(parentTuc);
        executor.setEffectiveIsolation("worktree");
        FakeWorktreeService svc = new FakeWorktreeService(fakeWorktree.toString(), null);
        executor.setWorktreeService(svc);

        runAndCaptureLogs(executor, forkParams("toolu_subcwd_4"));

        assertThat(svc.gitRootArg)
            .as("建树基准必须 = 父会话项目根（CC worktree.ts:926 findCanonicalGitRoot(getCwd())）")
            .isEqualTo(parentTuc.effectiveCwd().toString());
        assertThat(svc.gitRootArg)
            .as("⛔ 不得是进程 user.dir（服务器启动目录）")
            .isNotEqualTo(CwdResolution.normalizeCwd(System.getProperty("user.dir")));
        // 反向实验: 把 createAgentWorktree 的实参换回 Paths.get(System.getProperty("user.dir"))
        //   ⇒ 本断言变红。
    }

    @Test
    @DisplayName("⑤ fork+worktree notice 的 parentCwd = **父会话 cwd**（CC AgentTool.tsx:751 getCwd()）")
    void worktreeNotice_parentCwd_isSessionCwd(@TempDir Path sessionProject,
                                              @TempDir Path fakeWorktree) {
        ToolUseContext parentTuc = parentTucBoundTo(sessionProject);
        SubagentExecutor executor = executorWith(parentTuc);
        executor.setEffectiveIsolation("worktree");
        executor.setWorktreeService(new FakeWorktreeService(fakeWorktree.toString(), null));

        List<String> logs = runAndCaptureLogs(executor, forkParams("toolu_subcwd_5"));

        assertThat(logs)
            .as("notice 必须注入且 parentCwd = 父会话 cwd（不是进程 user.dir）")
            .anyMatch(m -> m.contains("fork path: worktree 隔离提示已注入")
                        && m.contains("parentCwd=" + parentTuc.effectiveCwd())
                        && m.contains("worktreeCwd=" + fakeWorktree));
        // 反向实验: 把 notice 的 parentCwd 换回 System.getProperty("user.dir") ⇒ 本断言变红。
    }

    /**
     * 假 WorktreeService（同 {@code SubagentToolForkTest} 手法）：不碰真实 git。
     * {@code failure} 非 null 时 {@code createAgentWorktree} 抛该异常（fail-loud 用例）。
     */
    private static final class FakeWorktreeService extends WorktreeService {
        private final String worktreePath;
        private final RuntimeException failure;
        private String gitRootArg;

        FakeWorktreeService(String worktreePath, RuntimeException failure) {
            this.worktreePath = worktreePath;
            this.failure = failure;
        }

        @Override
        public WorktreeCreateResult createAgentWorktree(Path gitRoot, String agentSlug) {
            this.gitRootArg = gitRoot == null ? null : gitRoot.toString();
            if (failure != null) {
                throw failure;
            }
            return new WorktreeCreateResult.Created(Path.of(worktreePath), "branch-" + agentSlug, gitRoot);
        }

        @Override
        public WorktreeService.WorktreeChanges countChanges(Path gitRoot, String slug) {
            return new WorktreeService.WorktreeChanges(0, 0);
        }

        @Override
        public void removeAgentWorktree(Path gitRoot, String agentSlug) {
            // no-op
        }

        @Override
        public void keepWorktree(Path gitRoot, String agentSlug) {
            // no-op
        }
    }
}
