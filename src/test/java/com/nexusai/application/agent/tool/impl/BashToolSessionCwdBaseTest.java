package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.nexusai.application.agent.tool.ToolUseContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 subcwd] BashTool 会话态基准 cwd（{@code effectiveCwd}）契约 · 「进程级值冒充会话态」清理。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：BashTool 的 6 个消费点（分类器 cwd / cd-to-cwd 过滤 /
 * path 约束（主链 + operator 重检）/ pending 分类器检查 / git 只读守卫）都以 cwd 作越界基准。
 * 旧实现 {@code fallbackCwd(ctx)} 的第三档是 {@code System.getProperty("user.dir", ".")} ——
 * **进程级值**（后端 JVM 启动目录），一 JVM 多会话下它不是任何会话的项目根。
 *
 * <p><b>前置自证（实测，批 subcwd 探针读数）</b>：该第三档是<b>死代码</b> ——
 * {@code CwdResolution.getCwd} 逐分支只抛（会话存在却解析不出项目根）或只返回非空白，
 * 故「结果为 null/blank」只可能来自 {@code user.dir} 自身为空；实测把 {@code user.dir} 置空串后
 * 该档返回空串（与第二档同值）。删它的产出是去掉坏范式 + 把「基准缺失 ⇒ 安全方向」显式化。
 *
 * <p><b>可达性（照实声明，⛔ 不得声称守住了不存在的洞）</b>：正常构造的
 * {@link ToolUseContext} 紧凑构造器会用 {@code CwdResolution.getCwd(sessionId)} 回填
 * {@code effectiveCwd}（ToolUseContext.java:451-452）⇒ <b>ctx != null 时基准恒非 null</b>，
 * 故 6 个消费点的 null 分支属<b>防御性</b>（将来回填口径变化时方向仍在安全侧）。
 * 真正<b>可达</b>的两条生产路径是：① {@code ctx == null}
 * （{@code isReadOnly → gitReadOnlyGuardBlocked(cmd, null)}、
 * {@code PromptShellExecutor → BashTool.execute(block, null)} 的旧单参 dispatch）；
 * ② 直调（测试 / 管线外）。本类钉的正是 ① 与③（基准缺失）的方向。
 */
@DisplayName("批 subcwd · BashTool 会话态基准 cwd（effectiveCwd / sed 目标解析 / 不回落 user.dir）")
class BashToolSessionCwdBaseTest {

    private static final String SESSION = "sess-subcwd-bash-base";

    @AfterEach
    void tearDown() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    private static Path effectiveCwd(ToolUseContext ctx) throws Exception {
        Method m = BashTool.class.getDeclaredMethod("effectiveCwd", ToolUseContext.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, ctx);
    }

    private static Path resolveSedEditPath(String filePath, ToolUseContext ctx) throws Exception {
        Method m = BashTool.class.getDeclaredMethod("resolveSedEditPath", String.class, ToolUseContext.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, filePath, ctx);
    }

    @Test
    @DisplayName("① ctx=null ⇒ 基准为 null（⛔ 不回落进程 user.dir 冒充会话态）")
    void effectiveCwd_ctxNull_returnsNull_notProcessUserDir() throws Exception {
        Path cwd = effectiveCwd(null);

        assertThat(cwd)
            .as("无 ToolUseContext ⇒ 无会话态可取 ⇒ 必须返回 null（消费点按安全方向分流）；"
                + "进程 user.dir=%s 不得被当作会话基准", System.getProperty("user.dir"))
            .isNull();
        // 反向实验: 把 BashTool.effectiveCwd 的 `if (ctx != null) { … } return null;` 恢复成旧
        //   fallbackCwd 形态（末尾 `return Path.of(System.getProperty("user.dir", "."));`）⇒ 本断言
        //   变红（返回 user.dir）。实测见交付报告。
    }

    @Test
    @DisplayName("② 会话绑定 ctx ⇒ 基准 = 该会话项目根（不是进程 user.dir）")
    void effectiveCwd_sessionBound_returnsSessionRoot(@TempDir Path project) throws Exception {
        SessionProjectRoot.setForSession(SESSION, project.toString());
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), SESSION);

        Path cwd = effectiveCwd(ctx);

        assertThat(cwd)
            .as("会话态基准必须来自显式 ctx 的会话（boundProject=%s）", project)
            .isNotNull();
        assertThat(cwd.toString())
            .as("⛔ 不得是进程 user.dir")
            .isNotEqualTo(CwdResolution.normalizeCwd(System.getProperty("user.dir")));
    }

    @Test
    @DisplayName("③ sed 相对路径解析：ctx=null ⇒ null（⛔ 不以进程 user.dir 解析相对路径去写文件）")
    void resolveSedEditPath_ctxNull_returnsNull_notProcessUserDir() throws Exception {
        Path resolved = resolveSedEditPath("notes/../notes/out.txt", null);

        assertThat(resolved)
            .as("基准缺失 ⇒ 必须返回 null（调用方 fail-loud）；不得解析成 user.dir 下的路径")
            .isNull();
        // 反向实验: 恢复旧实现（`ctx == null → System.getProperty("user.dir", ".")`）⇒ 本断言变红
        //   （返回 <user.dir>/notes/out.txt）。
    }

    @Test
    @DisplayName("④ sed 相对路径解析：会话绑定 ctx ⇒ 落在该会话项目根下；绝对路径原样")
    void resolveSedEditPath_sessionBound_underSessionRoot(@TempDir Path project) throws Exception {
        SessionProjectRoot.setForSession(SESSION, project.toString());
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), SESSION);

        Path expectedRoot = Path.of(CwdResolution.normalizeCwd(project.toString()));
        assertThat(resolveSedEditPath("sub/out.txt", ctx))
            .as("相对路径必须基于会话项目根展开（CC expandPath 语义）")
            .isEqualTo(expectedRoot.resolve("sub/out.txt").normalize());
        Path absolute = project.resolve("abs.txt").toAbsolutePath();
        assertThat(resolveSedEditPath(absolute.toString(), ctx))
            .as("绝对路径不参与 cwd 展开（原样 normalize）")
            .isEqualTo(absolute.normalize());
    }

    @Test
    @DisplayName("⑤ applySimulatedSedEdit：ctx=null ⇒ fail-loud（不写文件），且不触碰 user.dir 下的相对路径")
    void applySimulatedSedEdit_ctxNull_failsLoud(@TempDir Path tmp) throws Exception {
        // 前置条件：在**进程 user.dir** 下放一个同名相对目标；旧实现会写到它（真实误写）
        Path userDir = Path.of(System.getProperty("user.dir"));
        String relative = "subcwd-should-never-be-written.txt";
        Path bait = userDir.resolve(relative);

        Method m = BashTool.class.getDeclaredMethod("applySimulatedSedEdit",
            String.class, String.class, String.class, ToolUseContext.class);
        m.setAccessible(true);
        BashTool tool = new BashTool();
        Object result = m.invoke(tool, "toolu_sed_ctxnull", relative, "SHOULD NOT BE WRITTEN\n", null);
        com.nexusai.application.agent.tool.ToolResult<?> tr =
            (com.nexusai.application.agent.tool.ToolResult<?>) result;

        assertThat(String.valueOf(tr.data()))
            .as("基准缺失 ⇒ 必须返回 error（fail-loud），不得静默写文件")
            .contains("Cannot resolve sed edit target");
        assertThat(Files.exists(bait))
            .as("⛔ 绝不能把相对路径解析到进程 user.dir 并写文件（%s）", bait)
            .isFalse();
        // 反向实验: 恢复 resolveSedEditPath 的 user.dir 兜底 ⇒ 本断言变红（result 变成 success 且
        //   若目标不存在则是 "No such file or directory" error，均不含 "Cannot resolve sed edit target"）。
    }
}
