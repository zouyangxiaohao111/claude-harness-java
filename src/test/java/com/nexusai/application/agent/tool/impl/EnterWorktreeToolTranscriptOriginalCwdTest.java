package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import com.nexusai.application.agent.worktree.WorktreeEventLog;
import com.nexusai.application.agent.worktree.WorktreeService;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 P10c 守护：transcript {@code worktreeSession.originalCwd} 必须是<b>进入 worktree 前</b>的
 * 会话 cwd，⛔ 不得是 worktree 自身。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：CC 里这是<b>两个不同对象</b>，取值时机不同（CC 真源，自验）：
 * <ul>
 *   <li>{@code claude-code-best/src/utils/worktree.ts:712} —— {@code createWorktreeForSession} 入口
 *       {@code const originalCwd = getCwd()}（此时尚未 {@code process.chdir(worktreePath)}），
 *       经 :722（hookBased）/ :757（git）存入 {@code currentWorktreeSession.originalCwd}，
 *       再由 {@code EnterWorktreeTool.ts:97 saveWorktreeState(worktreeSession)} 落 transcript。
 *       ⭐ 该字段的用途 = <b>退出时 chdir 回原目录</b>：{@code worktree.ts:789}
 *       {@code process.chdir(originalCwd)}（{@code keepWorktree} 同款）。
 *   <li>{@code EnterWorktreeTool.ts:93-96} —— {@code process.chdir(...) + setCwd(...) +
 *       setOriginalCwd(getCwd())} 改的是<b>进程 STATE.originalCwd</b>（此时 getCwd() 已是
 *       worktreePath）。⛔ 与上一条不是同一个字段。
 * </ul>
 * ⇒ 若 transcript 那个字段写成 worktreePath，则「进入前目录」信息<b>永久丢失</b>（该字段是它唯一的
 * 落盘载体）：任何按 CC worktree.ts:789 语义实现的「退出回原目录」都只能回到 worktree 自身。
 * 本仓因 {@code ExitWorktreeTool} 未用它（自陈设计缺口）而暂未表现为用户可见故障 —— 属<b>潜伏</b>，
 * 但落盘值是错的，且本条守护把「CC 契约」钉死在测试里（规则三：对齐 CC 不看现有架构将就）。
 *
 * <p><b>造现场的关键（只有取对了才过）</b>：会话 cwd 与 workspaceDir（boundProject）、
 * worktreePath <b>三者互不相同</b>，因此本测试同时排除两个错解：
 * <ol>
 *   <li>写成 worktreePath（原缺陷：在 {@code applySessionCwd} 之后经
 *       {@code CwdResolution.getCwd} 取到刚写进 L1 的 worktreePath）；</li>
 *   <li>写成 boundProject（候选 (b) 的错解：CC 取的是 {@code getCwd()} 而非项目根 ——
 *       会话 {@code cd} 进子目录后两者必然不同值）。</li>
 * </ol>
 *
 * <p><b>为什么必须真读 transcript 文件</b>：该字段<b>无读者</b>（{@code ChatService:3509-3554}
 * 只读 worktreePath/worktreeBranch/worktreeName/hookBased；{@code ExitWorktreeTool} 读的是
 * {@code WorktreeCwdTracker} 的内存态），故无法用「下游行为」断言，只能取回写入的 JSON 节点。
 *
 * @see EnterWorktreeTool#persistWorktreeState
 * @since 批 P10c
 */
@DisplayName("[P10c] EnterWorktree transcript originalCwd = 进入前 cwd（⛔ 非 worktree 自身）")
class EnterWorktreeToolTranscriptOriginalCwdTest {

    @TempDir
    Path tmp;

    private String sessionKey;

    @AfterEach
    void tearDown() {
        if (sessionKey != null) {
            SessionCwdHolder.clear(sessionKey);
            SessionCwdHolder.clearOriginalCwd(sessionKey);
            WorktreeCwdTracker.clearCwd(sessionKey);
            WorktreeCwdTracker.clearOriginalCwd(sessionKey);
            WorktreeCwdTracker.clearWorktreeSession(sessionKey);
            SessionProjectRoot.clearSession(sessionKey);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 现场搭建
    // ════════════════════════════════════════════════════════════════════════

    /** 会话唯一标识（随机 ⇒ 与静态注册表（SessionProjectRoot 首写胜）无跨用例串扰）。 */
    private String newSession() {
        sessionKey = UUID.randomUUID().toString();
        return sessionKey;
    }

    /**
     * 造现场：boundProject(=workspaceDir=tmp) / 进入前 cwd(=tmp/entering-dir) / worktreePath
     * (=tmp/wt-live) 三者互不相同。
     *
     * <p>⛔ 调用前必须已建 {@link #sessionKey}（本方法按该 key 绑定，不再自行生成 —— 否则绑定到的
     * 会话与工具 {@code ctx.sessionId()} 不是同一个，transcript 会因「无绑定项目根」被跳过）。
     *
     * @param workspaceDir 会话绑定项目根（transcript 归目录）
     * @return 进入前 cwd（已建目录，使 realpath 可解析）
     */
    private Path prepareEnteringCwd(Path workspaceDir) throws Exception {
        Path entering = Files.createDirectory(workspaceDir.resolve("entering-dir"));
        SessionProjectRoot.setForSession(sessionKey, workspaceDir.toString());
        // 进入前会话 cwd（模拟「用户已在子目录 / 已 cd」——与 boundProject 必然不同值）
        SessionCwdHolder.set(sessionKey, entering.toString());
        // 前置条件：无前端传入的 originalCwd 工具参数（生产实测：front/src 全仓 0 处 originalCwd
        //   ⇒ WF-3 的「前端传入」腿在生产恒空），故 :428 必须落到本轮修的那条腿 —— 否则本测试
        //   测的就不是本缺陷。
        WorktreeCwdTracker.clearOriginalCwd(sessionKey);
        return entering;
    }

    private static ToolUseBlock enterCall(String name) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("name", name);
        return new ToolUseBlock("enter-" + name, EnterWorktreeTool.NAME, input);
    }

    private static ToolUseContext ctx(String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT);
    }

    /** 读取本轮 Enter 落盘的 transcript worktreeSession 节点（无 entry ⇒ null）。 */
    private JsonNode readWorktreeSession(Path workspaceDir) {
        return SessionStorage.readWorktreeState(workspaceDir, sessionKey);
    }

    // ════════════════════════════════════════════════════════════════════════
    // git 分支（execute :319-332）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY：git 分支的 {@code persistWorktreeState} 调用点（{@code EnterWorktreeTool:332}）在
     * {@code applySessionCwd}（{@code :325}）<b>之后</b> —— 若在此之后再经
     * {@code CwdResolution.getCwd} 取值，L1 sessionCwd 已被本次 worktree 改写 ⇒ 取到 worktree 自身。
     * CC 的取值时机是 {@code worktree.ts:712}（chdir 之前）⇒ 本断言钉死「边界解析一次、在改写之前」。
     */
    @Test
    @DisplayName("git 分支：落盘 originalCwd == 进入前 cwd（⛔ ≠ worktreePath、≠ boundProject）")
    void gitBranch_transcriptOriginalCwdIsPreEntryCwd() throws Exception {
        String sessionId = newSession();
        Path entering = prepareEnteringCwd(tmp);
        Path worktreePath = Files.createDirectory(tmp.resolve("wt-live"));

        WorktreeService service = mock(WorktreeService.class);
        when(service.createWorktree(any(), eq("wt-live")))
            .thenReturn(new WorktreeCreateResult.Created(worktreePath, "feature/x", tmp));
        EnterWorktreeTool tool = new EnterWorktreeTool(service);
        tool.setHookRegistry(new HookRegistry()); // 无 WorktreeCreate hook → git 路径

        AgentToolResult<?> result = tool.execute(enterCall("wt-live"), ctx(sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("前置：Enter 成功（否则下面的 transcript 断言无意义）")
            .isFalse();
        assertThat(WorktreeCwdTracker.getCwd(sessionId))
            .as("前置：applySessionCwd 已把会话 cwd 改写为 worktreePath（= 本缺陷的致因）")
            .isEqualTo(worktreePath);

        JsonNode session = readWorktreeSession(tmp);
        assertThat(session).as("Enter 必须落 transcript worktree-state entry").isNotNull();
        String written = session.path("originalCwd").asText();
        assertThat(written)
            .as("CC worktree.ts:712 —— transcript originalCwd 捕获自 chdir 之前；此处 = 进入前的会话 cwd")
            .isEqualTo(CwdResolution.normalizeCwd(entering.toString()));
        assertThat(written)
            .as("⛔ 不得写成 worktree 自身（原缺陷：在 applySessionCwd 之后取值）")
            .isNotEqualTo(CwdResolution.normalizeCwd(worktreePath.toString()));
        assertThat(written)
            .as("⛔ 不得写成 boundProject（CC 取 getCwd()，会话 cd 进子目录后与项目根必然不同值）")
            .isNotEqualTo(CwdResolution.normalizeCwd(tmp.toString()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // hookBased 分支（execute :296-317）—— 同一缺陷的第二个调用点
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY：hookBased 分支是<b>另一个</b> {@code persistWorktreeState} 调用点（{@code :309}），
     * 位于 {@code applySessionCwd}（{@code :302}）之后 —— 与 git 分支同款缺陷、同一处根因。
     * 单测其一 ⇒ 「只修一半」的改动不会被发现（两个调用点必须各自被覆盖）。
     */
    @Test
    @DisplayName("hookBased 分支：落盘 originalCwd == 进入前 cwd（第二调用点，⛔ ≠ worktreePath）")
    void hookBasedBranch_transcriptOriginalCwdIsPreEntryCwd() throws Exception {
        String sessionId = newSession();
        Path entering = prepareEnteringCwd(tmp);
        Path hookWorktree = Files.createDirectory(tmp.resolve("wt-hook"));

        HookRegistry registry = new HookRegistry();
        registry.register("wt-create",
            event -> successWithStdout(hookWorktree.toString() + "\n"));
        EnterWorktreeTool tool = new EnterWorktreeTool(new WorktreeService((WorktreeEventLog) null));
        tool.setHookRegistry(registry);

        AgentToolResult<?> result = tool.execute(enterCall("wt-hook"), ctx(sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("前置：hook 分支 Enter 成功")
            .isFalse();
        assertThat(WorktreeCwdTracker.getCwd(sessionId))
            .as("前置：hook 分支同样先 applySessionCwd 再 persistWorktreeState")
            .isEqualTo(hookWorktree);

        JsonNode session = readWorktreeSession(tmp);
        assertThat(session).as("hook 分支同样必须落 transcript worktree-state entry").isNotNull();
        assertThat(session.path("originalCwd").asText())
            .as("CC worktree.ts:712+722 —— hookBased 分支的 originalCwd 同样是进入前 cwd")
            .isEqualTo(CwdResolution.normalizeCwd(entering.toString()));
        assertThat(session.path("originalCwd").asText())
            .as("⛔ 不得写成 worktree 自身")
            .isNotEqualTo(CwdResolution.normalizeCwd(hookWorktree.toString()));
    }

    /** 对齐 WorktreeToolHooksTest 同款构造（CC hooks.ts:4944-4947 exit 0 → stdout 即 worktreePath）。 */
    private static GenericHook.HookResult successWithStdout(String stdout) {
        return new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookSuccess("WorktreeCreate:wt", "tu-wt", "WorktreeCreate",
                "", stdout, null, 0, "echo path", 5L),
            null, null, null, null, GenericHook.HookOutcome.SUCCESS,
            null, null, null, null, null, null, null, null);
    }
}
