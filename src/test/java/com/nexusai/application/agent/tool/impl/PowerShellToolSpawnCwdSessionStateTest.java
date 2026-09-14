package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [批 subcwd] PowerShellTool 的 {@code pb.directory}（<b>pwsh 子进程真实工作目录</b>）只能取
 * <b>会话态</b>来源 · 「进程级值冒充会话态」清理。
 *
 * <p><b>WHY（规则九）</b>：旧实现
 * {@code Path.of(sessionCwd != null && !sessionCwd.isBlank() ? sessionCwd : fallbackCwd(sessionId))}
 * 里，{@code sessionCwd} 来自 {@code CwdResolution.getCwd(sessionId)} —— sessionId 为
 * null/空白（含 {@code ctx == null}）时它命中 CwdResolution 的「无会话出口」（恒等于进程
 * {@code user.dir}），{@code fallbackCwd} 的第三档又是 {@code System.getProperty("user.dir", ".")}。
 * 结果是整条 pwsh 命令在<b>后端服务器启动目录</b>里跑：相对路径读写全锚错、cd 追踪把会话 cwd
 * 误写成服务器目录。⛔ 本类钉住「没有会话态 ⇒ 拒绝执行」这一方向。
 *
 * <p><b>可跳过</b>：本机无 pwsh/powershell 时 execute 会先返回 availability sentinel
 * （CC PowerShellTool.tsx:717-728），此时断言无意义 ⇒ 用 {@code assumeTrue} 跳过（并在报告里声明）。
 */
@DisplayName("批 subcwd · PowerShellTool pb.directory = 会话态（ctx=null ⇒ 拒绝执行）")
class PowerShellToolSpawnCwdSessionStateTest {

    private static final String SESSION = "sess-subcwd-ps-spawn";

    @AfterEach
    void tearDown() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    private static ToolUseBlock psCall(String id, String command) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return new ToolUseBlock(id, "PowerShell", input);
    }

    private static boolean pwshUnavailable(ToolResult<?> r) {
        return String.valueOf(r.data()).contains("PowerShell is not available");
    }

    @Test
    @DisplayName("① ctx=null ⇒ 拒绝执行（⛔ 不让 pwsh 子进程在服务器启动目录里跑）")
    void execute_ctxNull_refusesInsteadOfUsingProcessUserDir() {
        PowerShellTool tool = new PowerShellTool();

        ToolResult<?> r = (ToolResult<?>) tool.execute(psCall("subcwd-ps-1", "Write-Output subcwd"), null);

        assumeTrue(!pwshUnavailable(r), "本机无 pwsh/powershell ⇒ 前置 pre-flight sentinel 先行，用例不适用");
        assertThat(String.valueOf(r.data()))
            .as("无 ToolUseContext ⇒ 无会话态基准 ⇒ 必须拒绝执行（ToolResult.error 的 data = 错误文案）；"
                + "进程 user.dir=%s 不得被当作 pwsh 工作目录", System.getProperty("user.dir"))
            .contains("Cannot determine the session working directory");
        // 反向实验: 把 :653/:685 恢复成「sessionCwd = CwdResolution.getCwd(sessionId) +
        //   fallbackCwd(sessionId) 兜底」⇒ 本断言变红（命令在 user.dir 里成功执行，data 不含
        //   "Cannot determine the session working directory"）。
    }

    @Test
    @DisplayName("② 会话绑定 ctx ⇒ pb.directory = 会话项目根（子进程在会话目录里跑）")
    void execute_sessionBound_spawnsInSessionRoot(@TempDir Path project) {
        SessionProjectRoot.setForSession(SESSION, project.toString());
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), SESSION);
        PowerShellTool tool = new PowerShellTool();

        ToolResult<?> probe = (ToolResult<?>) tool.execute(
            psCall("subcwd-ps-2-probe", "Write-Output subcwd"), ctx);
        assumeTrue(!pwshUnavailable(probe), "本机无 pwsh/powershell ⇒ 用例不适用");

        ToolResult<?> r = (ToolResult<?>) tool.execute(psCall("subcwd-ps-2", "(Get-Location).Path"), ctx);

        assertThat(String.valueOf(r.data()))
            .as("pwsh 子进程工作目录必须 = 会话项目根（实测 (Get-Location).Path 读数；"
                + "⛔ 不是进程 user.dir=%s）", System.getProperty("user.dir"))
            .contains(project.getFileName().toString())
            .doesNotContain(System.getProperty("user.dir"));
        // 反向实验: 把 pb.directory 换成 Path.of(System.getProperty("user.dir")) ⇒ 本断言变红。
    }
}
