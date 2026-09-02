package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptShellExecutor 单元测试 · 对齐 CC promptShellExecution.ts:69-143 executeShellCommandsInPrompt
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>BLOCK/INLINE 解析 + 替换是注入行为的地基</b>——lookbehind 防误匹配（! 前需行首/空白）、
 *       inline 门控（text.includes('!`')）、空命令跳过（CC :95）决定哪些文本会被执行。</li>
 *   <li><b>replacer $ 语义是安全红线</b>——Shell 输出（尤其 PowerShell $env:PATH / $$）是任意
 *       用户数据，String.replace 会解释 $$ $& $` $' 破坏替换串（CC :127-131 function replacer）——
 *       Java 端必须 quoteReplacement 等价，否则注入输出被错误展开。</li>
 *   <li><b>权限预检不可省略</b>（scope-coverage F-1 安全相关）——非 allow 一律 throw
 *       MalformedCommandException（CC :106-113），fail-loud 不静默降级。</li>
 *   <li><b>allowedTools 预授权 + 只读命令 auto-allow 是 CC 权限真语义</b>——内嵌 shell 必须有
 *       allowed-tools: [Bash] 声明才有预授权（loadSkillsDir.ts:385-388），否则普通命令走
 *       3 层转 Ask → 拒绝。</li>
 *   <li><b>formatBashOutput/formatBashError 文案锁定 LLM 可读性</b>——[stderr] 块 / inline /
 *       interrupted 特判（promptShellExecution.ts:145-183）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：revert 任一分支（如 replaceOnce 不加 quoteReplacement / 权限非 allow 不
 * throw / BLOCK 恒扫改门控 / inline lookbehind 去掉）→ 本测试必须 fail。
 *
 * <p>不跑真实进程（fake ShellCommandRunner），Windows CI 可用。
 */
class PromptShellExecutorTest {

    /** 默认 ctx（permissionContext=null → 默认 checker 按 strict 空规则集 + allowedTools 合并）。 */
    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT, List.of());
    }

    /** fake 执行器 · 返回固定 output（不跑真实进程）。 */
    private static PromptShellExecutor.ShellCommandRunner fakeRunner(String output) {
        return cmd -> ToolResult.success("id", output);
    }

    /** fake 权限预检器 · 固定返回 allow。 */
    private static PromptShellExecutor.ShellPermissionChecker fakeChecker(boolean allow) {
        return (tn, cmd, c, at) -> allow;
    }

    /** 构造 + fake checker/runner 的执行器（权限/执行均注入覆盖）。 */
    private static PromptShellExecutor executorWithFakes(String output, boolean allow) {
        PromptShellExecutor executor = new PromptShellExecutor();
        executor.setPermissionChecker(fakeChecker(allow));
        executor.setCommandRunner(fakeRunner(output));
        return executor;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ① BLOCK / INLINE 正则解析与替换 + replacer $ 语义（CC :49-56, :126-131）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BLOCK 代码块 ```! ... ``` 解析与替换（CC BLOCK_PATTERN :49）")
    void blockPattern_replacesCommand() {
        PromptShellExecutor executor = executorWithFakes("HELLO-OUTPUT", true);
        String text = "title\n\n```!\necho hi\n```\n\nafter";
        assertThat(executor.executeShellCommandsInPrompt(text, ctx(), "/skill", null, null))
            .isEqualTo("title\n\nHELLO-OUTPUT\n\nafter");
    }

    @Test
    @DisplayName("INLINE !`cmd` 解析与替换（CC INLINE_PATTERN :56）")
    void inlinePattern_replacesCommand() {
        PromptShellExecutor executor = executorWithFakes("HELLO-OUTPUT", true);
        String text = "before !`echo hi` after";
        assertThat(executor.executeShellCommandsInPrompt(text, ctx(), "/skill", null, null))
            .isEqualTo("before HELLO-OUTPUT after");
    }

    @Test
    @DisplayName("INLINE lookbehind 防误匹配: ! 前无行首/空白 或 markdown inline span 内（CC :52-56）")
    void inlinePattern_noFalseMatch() {
        PromptShellExecutor executor = executorWithFakes("SHOULD-NOT-REPLACE", true);
        // foo!`...` — ! 前是 'o'（非空白/行首）→ 不匹配
        assertThat(executor.executeShellCommandsInPrompt("foo!`echo hi`bar", ctx(), "/skill", null, null))
            .isEqualTo("foo!`echo hi`bar");
    }

    @Test
    @DisplayName("无任何 !` 或 ```! → matches 空 → 原样返回（inline 门控 text.includes('!`')，CC :90）")
    void noShellSyntax_returnsUnchanged() {
        PromptShellExecutor executor = executorWithFakes("NOPE", true);
        String text = "plain skill content, no shell";
        assertThat(executor.executeShellCommandsInPrompt(text, ctx(), "/skill", null, null))
            .isEqualTo(text);
    }

    @Test
    @DisplayName("替换串含 $, $$, $&, $`, $' 按字面插入不被解释（CC :127-131 function replacer 等价）")
    void replacer_dollarSignsLiteral() {
        // PowerShell 输出典型用户数据：$env:PATH、$$、$PSVersionTable 等
        String shellOutput = "cost: $5 and $$ and $& and $` and $'";
        PromptShellExecutor executor = executorWithFakes(shellOutput, true);
        String result = executor.executeShellCommandsInPrompt("before !`cmd` after", ctx(), "/skill", null, null);
        assertThat(result).isEqualTo("before " + shellOutput + " after");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ② 空 command 跳过（CC :95 if (command)）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空 command 跳过: BLOCK ```! ``` group(1) 为空 → 原样返回")
    void emptyCommand_blockSkipped() {
        PromptShellExecutor executor = executorWithFakes("SHOULD-NOT-RUN", true);
        String text = "```! ```";
        assertThat(executor.executeShellCommandsInPrompt(text, ctx(), "/skill", null, null))
            .isEqualTo(text);
    }

    @Test
    @DisplayName("空 command 跳过: INLINE !`` 无 [^`]+ 内容 → 不匹配原样返回")
    void emptyCommand_inlineSkipped() {
        PromptShellExecutor executor = executorWithFakes("SHOULD-NOT-RUN", true);
        String text = "before !`` after";
        assertThat(executor.executeShellCommandsInPrompt(text, ctx(), "/skill", null, null))
            .isEqualTo(text);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ③ 权限非 allow → 抛 MalformedCommandException（CC :106-113，不可省略）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("权限非 allow → 抛 MalformedCommandException 含 pattern + Permission denied（CC :110-112）")
    void permissionDenied_throws() {
        PromptShellExecutor executor = executorWithFakes("SHOULD-NOT-RUN", false);
        assertThatThrownBy(() -> executor.executeShellCommandsInPrompt(
                "!`echo hi`", ctx(), "/skill", null, null))
            .isInstanceOf(MalformedCommandException.class)
            .hasMessageContaining("Shell command permission check failed for pattern \"!`echo hi`\"")
            .hasMessageContaining("Permission denied");
    }

    @Test
    @DisplayName("PermissionPipeline 未注入（默认构造）→ fail-closed 拒绝 → 抛（非空指针）")
    void noPipeline_failClosed_throws() {
        PromptShellExecutor executor = new PromptShellExecutor();  // 无 pipeline + 无 runner/checker 注入
        executor.setCommandRunner(fakeRunner("SHOULD-NOT-RUN"));
        // 默认 checker: permissionPipeline==null → fail-closed false
        assertThatThrownBy(() -> executor.executeShellCommandsInPrompt(
                "!`echo hi`", ctx(), "/skill", null, null))
            .isInstanceOf(MalformedCommandException.class)
            .hasMessageContaining("permission check failed");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ④ 权限放行（只读 auto-allow / allowedTools 预授权）· 对齐 CC hasPermissionsToUseTool
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("只读命令（echo）无 allowedTools → BashTool.checkPermissions Allow → 放行（CC :98-104）")
    void readOnlyCommand_autoAllowed() {
        BashTool bashTool = new BashTool();
        PermissionPipeline pipeline = new PermissionPipeline();
        PromptShellExecutor executor = new PromptShellExecutor(bashTool, pipeline);
        executor.setCommandRunner(fakeRunner("hi-echo"));   // 不跑真实进程
        assertThat(executor.executeShellCommandsInPrompt("!`echo hi`", ctx(), "/skill", null, null))
            .isEqualTo("hi-echo");
    }

    @Test
    @DisplayName("普通命令 + allowedTools 含 Bash → whole-tool allow 放行（CC loadSkillsDir.ts:385-388 → 2b 层）")
    void allowedTools_containsBash_allowed() {
        BashTool bashTool = new BashTool();
        PermissionPipeline pipeline = new PermissionPipeline();
        PromptShellExecutor executor = new PromptShellExecutor(bashTool, pipeline);
        executor.setCommandRunner(fakeRunner("npm-ok"));
        assertThat(executor.executeShellCommandsInPrompt(
                "!`npm install`", ctx(), "/skill", null, List.of("Bash")))
            .isEqualTo("npm-ok");
    }

    @Test
    @DisplayName("普通命令无 allowedTools → 3 层转 Ask → 拒绝（CC 真语义: 内嵌 shell 必须有 allowed-tools 声明）")
    void noAllowedTools_nonReadOnly_throws() {
        BashTool bashTool = new BashTool();
        PermissionPipeline pipeline = new PermissionPipeline();
        PromptShellExecutor executor = new PromptShellExecutor(bashTool, pipeline);
        executor.setCommandRunner(fakeRunner("nope"));
        assertThatThrownBy(() -> executor.executeShellCommandsInPrompt(
                "!`npm install`", ctx(), "/skill", null, null))
            .isInstanceOf(MalformedCommandException.class)
            .hasMessageContaining("permission check failed");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ⑤ formatBashOutput / formatBashError 文案（CC :145-183）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("formatBashOutput 文案: stdout / [stderr] 块 / inline（CC :145-165）")
    void formatBashOutput_variants() {
        assertThat(PromptShellExecutor.formatBashOutput("out", "", false)).isEqualTo("out");
        assertThat(PromptShellExecutor.formatBashOutput("", "err", false)).isEqualTo("[stderr]\nerr");
        assertThat(PromptShellExecutor.formatBashOutput("out", "err", false)).isEqualTo("out\n[stderr]\nerr");
        assertThat(PromptShellExecutor.formatBashOutput("out", "err", true)).isEqualTo("out [stderr: err]");
        assertThat(PromptShellExecutor.formatBashOutput("", "", false)).isEmpty();
    }

    @Test
    @DisplayName("执行失败 → Shell command failed for pattern + [stderr] 块（CC formatBashError :174-177）")
    void shellFailed_throwsMalformed() {
        PromptShellExecutor executor = new PromptShellExecutor();
        executor.setPermissionChecker(fakeChecker(true));
        executor.setCommandRunner(cmd -> ToolResult.error("id", "exit code 1"));
        assertThatThrownBy(() -> executor.executeShellCommandsInPrompt(
                "!`bad cmd`", ctx(), "/skill", null, null))
            .isInstanceOf(MalformedCommandException.class)
            .hasMessageContaining("Shell command failed for pattern \"!`bad cmd`\"")
            .hasMessageContaining("[stderr]")
            .hasMessageContaining("exit code 1");
    }

    @Test
    @DisplayName("interrupted → Shell command interrupted 文案（CC :169-172）")
    void interrupted_throwsMalformed() {
        PromptShellExecutor executor = new PromptShellExecutor();
        executor.setPermissionChecker(fakeChecker(true));
        executor.setCommandRunner(cmd -> ToolResult.error("id", "interrupted"));
        assertThatThrownBy(() -> executor.executeShellCommandsInPrompt(
                "!`sleep 100`", ctx(), "/skill", null, null))
            .isInstanceOf(MalformedCommandException.class)
            .hasMessageContaining("Shell command interrupted for pattern \"!`sleep 100`\": [Command interrupted]");
    }
}
