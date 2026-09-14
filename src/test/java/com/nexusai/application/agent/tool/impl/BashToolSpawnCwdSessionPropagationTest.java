package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [欠账清理批 · c3 覆盖缺口补齐] {@code BashTool} <b>调用点</b>是否真的把会话标识传给
 * spawn-cwd 回落层 —— 经<b>真实 bash 执行</b>的端到端断言。
 *
 * <h2>WHY 必须单独存在（「只覆盖一侧」第 7 次的收口）</h2>
 * 姊妹用例 {@link com.nexusai.application.agent.bash.ShellExecutorSpawnCwdFallbackTest} 守的是
 * {@link com.nexusai.application.agent.bash.ShellExecutor#resolveSpawnCwd(String, String)}
 * 的 <b>2 参契约</b>（"给了 sessionId 就回落该会话的 originalCwd"）。它<b>守不住 BashTool 是否
 * 把这个实参传下去</b>：把 {@code BashTool} 的实参改回 {@code null}（c3 漏传的原样复发），
 * 那个类 <b>3/3 全绿</b>。这正是本仓反复栽的「写入端与读取端只有一侧有断言」。
 *
 * <h2>本类的判据（行为级，非源码字面）</h2>
 * 让「会话 cwd 已被删除」（触发回落层的唯一条件），然后跑一条**真实 bash 命令**读一个
 * <b>只存在于会话项目目录</b>里的标记文件：
 * <ul>
 *   <li>传了 sessionId ⇒ 回落层 = 该会话的 originalCwd（= 绑定的 projectDir）⇒ 命令在 projectDir
 *       里跑 ⇒ 读到标记；</li>
 *   <li>漏传（{@code null}）⇒ 回落层 = 进程 {@code user.dir}（后端 JVM 启动目录）⇒ 读不到标记
 *       ⇒ 本类红。</li>
 * </ul>
 * 断言对象是「命令能否读到该目录下的文件」，与路径分隔符 / MSYS 路径形态无关（不解析 stdout 里的
 * 路径字符串，故不受 Git Bash 的 {@code /c/...} 形态影响）。
 *
 * <p><b>反向对照</b>见 {@link #existingSessionCwd_probeReadsFromThatDir()}：会话 cwd 存在
 * （不触发回落）时同一条探测命令必须读到标记 —— 它证明探测机制真的在跑，
 * 于是上一条的失败不可能被归因成「夹具/权限没放行」。
 *
 * <p><b>变异验证</b>：把 {@code BashTool} 里 {@code resolveSpawnCwd(sessionCwd, sessionId)} 的
 * 第 2 实参改成 {@code null} ⇒ {@link #deletedSessionCwd_readsMarkerFromSessionProject()} 红
 * （姊妹 ShellExecutor 类仍全绿 ⇒ 本类确实补上了缺口）。
 */
class BashToolSpawnCwdSessionPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 标记文件名（仅出现在「期望的 cwd」目录里）。 */
    private static final String MARKER = "spawn-cwd-probe.txt";

    /** 标记内容（断言 stdout 含它即证明命令在标记所在目录里执行）。 */
    private static final String MARKER_BODY = "SPAWN_CWD_PROBE_OK";

    /** 读不到标记时的哨兵输出（让「跑错目录」表现为**断言失败**而非 shell 非零退出的异常）。 */
    private static final String MARKER_MISSING = "SPAWN_CWD_MARKER_NOT_FOUND";

    private final BashTool bashTool = new BashTool();

    @AfterEach
    void clearSessionState() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    /** 与 {@code BashToolPersistenceTest} / {@code BashToolAlignmentTest} 同款真实执行夹具。 */
    private ToolUseContext ctx(Path workspaceDir, String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, null, Map.of(),
            false, "", workspaceDir);
    }

    private ToolUseBlock call(String callId, String command) {
        JsonNode input = JSON.createObjectNode().put("command", command);
        return new ToolUseBlock(callId, "Bash", input);
    }

    /**
     * 探测命令：读标记；读不到则打印哨兵并**以 0 退出**。
     *
     * <p>WHY 要 {@code || echo 哨兵}：否则「跑错目录」会让 {@code cat} 非零退出 ⇒
     * {@code BashTool.execute} 抛 {@code ShellError} ⇒ 用例以**异常**形式红，
     * 报错里看不到「跑到了哪个目录」。加哨兵后失败表现为下面那条带 {@code .as(...)} 说明的
     * 断言失败，故障原因直接可读（「标记没读到 ⇒ 会话标识没传下去」）。
     */
    private static String readMarkerCommand() {
        return "cat " + MARKER + " || echo " + MARKER_MISSING;
    }

    @Test
    @DisplayName("c3 覆盖缺口：会话 cwd 已删 ⇒ 真实 bash 命令跑在**会话项目目录**（不是进程 user.dir）")
    void deletedSessionCwd_readsMarkerFromSessionProject(@TempDir Path projectDir,
                                                         @TempDir Path scratch) throws Exception {
        // WHY：这是 c3 漏传的失效现场 —— worktree / 临时项目目录被清理后，bash 必须仍在**本会话**
        //   的项目目录里跑。若 BashTool 漏传 sessionId，回落层取进程 user.dir（后端启动目录）
        //   ⇒ 命令在**别的项目**里执行，不报错（本仓「静默读到看似合法的错值」同族）。
        String sessionId = "sess-bash-spawn-prop";
        Files.writeString(projectDir.resolve(MARKER), MARKER_BODY);
        SessionProjectRoot.setForSession(sessionId, projectDir.toString());

        // 会话 cwd 指向一个「先建后删」的目录 ⇒ getCwd 仍返回该值（realpath 失败回原值），
        // 而 spawn 时 realpath 失败 ⇒ 触发回落层（唯一能区分 sessionId 传/不传的路径）。
        Path deletedCwd = Files.createDirectory(scratch.resolve("gone"));
        Files.delete(deletedCwd);
        SessionCwdHolder.set(sessionId, deletedCwd.toString());

        ToolResult<String> result = bashTool.execute(call("e5c-spawn-1", readMarkerCommand()),
            ctx(projectDir, sessionId));

        assertThat(result.data())
            .as("会话 cwd 被删时，spawn 必须回落到**该会话**的 originalCwd（= 绑定 projectDir）；"
                + "若 BashTool 漏传 sessionId，回落层会取进程 user.dir ⇒ 读不到标记 ⇒ 本断言红")
            .contains(MARKER_BODY);
        assertThat(result.data())
            .as("不得出现「读不到标记」哨兵（出现即证明命令跑到了别的目录）")
            .doesNotContain(MARKER_MISSING);
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("回落目录有效 ⇒ 命令正常执行（非错误结果）")
            .isFalse();
    }

    @Test
    @DisplayName("反向对照：会话 cwd 存在（不触发回落）⇒ 同一条探测命令读到标记（证明探测机制在跑）")
    void existingSessionCwd_probeReadsFromThatDir(@TempDir Path projectDir,
                                                  @TempDir Path workDir) throws Exception {
        // WHY（规则九）：没有本条，上一条的失败可能被误归因为「夹具没放行 / 命令没跑 / 权限拒绝」。
        //   本条把会话 cwd 设为一个存在但**与 projectDir 不同**的目录，并在其中放标记
        //   ⇒ 命令读得到 ⇒ 证明「cat 标记文件」这条探测链真的能跑通，且 cwd 确实由会话层决定。
        String sessionId = "sess-bash-spawn-ok";
        Files.writeString(workDir.resolve(MARKER), MARKER_BODY);
        SessionProjectRoot.setForSession(sessionId, projectDir.toString());   // 故意绑到另一个目录
        SessionCwdHolder.set(sessionId, workDir.toString());

        ToolResult<String> result = bashTool.execute(call("e5c-spawn-2", readMarkerCommand()),
            ctx(workDir, sessionId));

        assertThat(result.data())
            .as("会话 cwd 存在 ⇒ 命令就在该目录里跑（不回落、不受 projectDir 影响）")
            .contains(MARKER_BODY);
        assertThat(Files.exists(projectDir.resolve(MARKER)))
            .as("标记只放在 workDir；projectDir 里不该有它（证明上一条的读成功不是巧合）")
            .isFalse();
    }
}
