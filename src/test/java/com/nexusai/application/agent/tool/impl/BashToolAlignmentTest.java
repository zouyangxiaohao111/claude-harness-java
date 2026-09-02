package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session E · {@link BashTool} P0/P1 三显式 + isReadOnly AST 升级对齐 CC 验证。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * 本测试验证 BashTool 与 CC {@code tools/BashTool/} 的 L1 行为对齐契约：
 * <ol>
 *   <li><b>name() = 'Bash'</b> —— CC {@code toolName.ts:2 BASH_TOOL_NAME='Bash'}。
 *       Java 原返回 lowercase 'bash'，导致 LLM tool_use block name 漂移，且
 *       {@code StreamingToolExecutor:1470/1697 BASH_TOOL_NAME.equals(name())} 恒 false
 *       （B session 的 Bash isError sibling abort 从未真正触发）。改对后该链路激活。</li>
 *   <li><b>maxResultSizeChars = 30_000</b> —— CC {@code BashTool.tsx:424}，结果落盘阈值；
 *       Java {@code Tool.java} 默认 50_000 → LLM 可见的截断阈值不同。</li>
 *   <li><b>strict = true</b> —— CC {@code BashTool.tsx:425}，schema 严格模式，模型不可注入额外字段。</li>
 *   <li><b>isConcurrencySafe = isReadOnly ?? false</b> —— CC {@code BashTool.tsx:434-436}；
 *       两方法分叉会让并发调度语义漂移。</li>
 *   <li><b>isReadOnly 走 BashParser AST walker</b> —— CC {@code readOnlyValidation.ts} 的
 *       {@code isCommandReadOnly} 语义：只读命令 → allow；写操作 / substitution / 不可解析 → 非只读。
 *       引号内 {@code '>'} 是字面量（tokenizer 判 STRING 非 REDIRECT），旧 WRITE_PATTERNS regex
 *       会误命中 —— 这是 AST walker 与 regex 简化版的行为分叉点。</li>
 * </ol>
 */
@DisplayName("BashTool CC 对齐（Session E）")
class BashToolAlignmentTest {

    private final BashTool bashTool = new BashTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode input(String command) {
        return JSON.createObjectNode().put("command", command);
    }

    @Test
    @DisplayName("name() = 'Bash'（CC BASH_TOOL_NAME PascalCase）")
    void bashTool_nameMatchesCCPascalCase() {
        // WHY: CC toolName.ts:2 BASH_TOOL_NAME='Bash'；Java 原 lowercase 'bash' 破坏
        //      StreamingToolExecutor 的 BASH_TOOL_NAME.equals(name()) 匹配（sibling abort 失效）
        assertThat(bashTool.name()).isEqualTo(ToolNameConstants.BASH_TOOL_NAME);
        assertThat(bashTool.name()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("maxResultSizeChars() = 30_000 显式覆写")
    void bashTool_maxResultSizeChars_explicitly30000() {
        // WHY: CC BashTool.tsx:424 maxResultSizeChars: 30_000（工具结果落盘阈值）；
        //      Java Tool.java 默认 50_000 → LLM 看到不同的截断阈值
        assertThat(bashTool.maxResultSizeChars()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("strict() = true 显式覆写")
    void bashTool_strict_explicitlyTrue() {
        // WHY: CC BashTool.tsx:425 strict: true → API 严格遵循工具指令与参数 schema；
        //      Java Tool.java strict() 默认 false → 模型可注入额外字段
        assertThat(bashTool.strict()).isTrue();
    }

    @Test
    @DisplayName("isConcurrencySafe 委托 isReadOnly（CC BashTool.tsx:434-436）")
    void isConcurrencySafe_delegatesToIsReadOnly() {
        // WHY: CC isConcurrencySafe = this.isReadOnly?.(input) ?? false；
        //      两方法分叉 → Java 并发调度语义与 CC 不一致
        assertThat(bashTool.isConcurrencySafe(input("ls -la")))
            .isEqualTo(bashTool.isReadOnly(input("ls -la")));
        assertThat(bashTool.isConcurrencySafe(input("rm x")))
            .isEqualTo(bashTool.isReadOnly(input("rm x")));
    }

    @Test
    @DisplayName("只读命令 true / 写操作 false")
    void readOnlyCommand_lsCatGrep_returnsTrue_redirectionOperator_returnsFalse() {
        // WHY: 只读命令 (ls/cat/grep/find -type f) → safe 并发；
        //      写操作 (>, >>, rm, mv) → 不 safe，需串行 + 权限检查
        assertThat(bashTool.isReadOnly(input("ls -la"))).isTrue();
        assertThat(bashTool.isReadOnly(input("cat file.txt"))).isTrue();
        assertThat(bashTool.isReadOnly(input("grep -r foo ."))).isTrue();
        assertThat(bashTool.isReadOnly(input("find . -type f"))).isTrue();
        assertThat(bashTool.isReadOnly(input("echo hi > out.txt"))).isFalse();
        assertThat(bashTool.isReadOnly(input("echo hi >> out.txt"))).isFalse();
        assertThat(bashTool.isReadOnly(input("rm -rf /tmp/x"))).isFalse();
        assertThat(bashTool.isReadOnly(input("mv a b"))).isFalse();
    }

    @Test
    @DisplayName("process/command substitution fail-closed 非只读")
    void readOnly_astWalkerRejectsProcessAndCommandSubstitution() {
        // WHY: Pattern #11 fail-closed — $(...) / <(...) / `...` 本质是执行任意命令，
        //      CC checkReadOnlyConstraints: 不可解析/不安全 → passthrough → isReadOnly=false
        assertThat(bashTool.isReadOnly(input("echo $(ls)"))).isFalse();
        assertThat(bashTool.isReadOnly(input("cat <(echo hi)"))).isFalse();
        assertThat(bashTool.isReadOnly(input("ls `pwd`"))).isFalse();
    }

    @Test
    @DisplayName("引号内重定向是字面量（AST walker vs regex 分叉点）")
    void readOnly_quotedRedirectionIsNotWrite() {
        // WHY: V2 §7.2 措辞纠正实证 —— BashParser 是 tokenizer walker 非纯 regex：
        //      `cat ">" file` 的 > 在引号内是 STRING 字面量非 REDIRECT token；
        //      旧 WRITE_PATTERNS regex (?<![<>])>(?!>) 不感知引号会误判 → 行为分叉
        assertThat(bashTool.isReadOnly(input("cat \">\" file"))).isTrue();
    }

    @Test
    @DisplayName("复合命令任一子命令写 → 整体非只读")
    void readOnly_compoundCommand_anySubcommandWrite_returnsFalse() {
        // WHY: CC checkReadOnlyConstraints: all subcommands read-only → allow；
        //      `ls && rm x` 含写子命令 → 整体非只读（任一子命令可写即不可并发）
        assertThat(bashTool.isReadOnly(input("ls && rm x"))).isFalse();
        assertThat(bashTool.isReadOnly(input("ls && cat f"))).isTrue();
        assertThat(bashTool.isReadOnly(input("cat f | grep x"))).isTrue();
    }

    @Test
    @DisplayName("反斜杠转义操作符 → 非只读（BashSecurity 校验器接入 read-only 路径）")
    void readOnly_backslashEscapedOperator_notReadOnly() {
        // WHY: CC checkReadOnlyConstraints (readOnlyValidation.ts:1893-1899) 先跑
        //      bashCommandIsSafe_DEPRECATED(command)，非 passthrough → 非只读。
        //      `cat safe.txt \; echo ~/.ssh/id_rsa` 的 `\;` 命中 validateBackslashEscapedOperators
        //      (bashSecurity.ts:1696, M14)。若无此门禁，BashParser.parseForReadOnly 把 `\;`
        //      当 WORD 吞掉（非 OPERATOR token），`echo ~/.ssh/id_rsa`（读私钥）被误判只读
        //      → auto-allow 放行（CC bashSecurity.ts:1606-1618 注释描述的私钥泄露攻击面）。
        assertThat(bashTool.isReadOnly(input("cat safe.txt \\; echo ~/.ssh/id_rsa"))).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // Session A2 · 退出码语义解释接线（DEL-A2-01 · 对齐 CC BashTool.tsx:690
    // interpretCommandResult 消费 + commandSemantics.ts 6 语义/default）
    // 合成退出码单测，不依赖真实 grep/find 进程（Windows 宿主可能缺失）。
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("grep 退出 1 → success 且文本含 No matches found（CC commandSemantics.ts:33-39 + BashTool.tsx:690）")
    void interpretExitCodeResult_grepExit1_successWithNoMatchesText() {
        // WHY: grep 退出 1 = 无匹配（非错误）。旧 BashTool.execute 硬编码 exit!=0→error
        //      （DEL-A2-01）会误报失败；接线后应 success 且 message 折入 data 文本。
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "grep foo file.txt", 1, "", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.result().data()).contains("No matches found");
    }

    @Test
    @DisplayName("grep 退出 2 → error（CC commandSemantics.ts:35-37）")
    void interpretExitCodeResult_grepExit2_error() {
        // WHY: exit>=2 是 grep 真实错误；与 exit 1 区分，仍须 error
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "grep foo missing.txt", 2, "", "grep: No such file");
        assertThat(r.isError()).isTrue();
        assertThat(r.result().data()).contains("Exit code 2");
    }

    @Test
    @DisplayName("isError 文本顺序对齐 CC：输出在前、'Exit code N' 在后（BashTool.tsx:687+699）")
    void interpretExitCodeResult_errorText_orderAfterOutput() {
        // WHY: CC stdoutAccumulator 先 append 输出（:687 trimEnd+EOL），isError 时才在输出之后
        //      append "Exit code N"（:699，大写 E，无后缀换行）。Java 旧实现前置小写
        //      "exit code N\n"（DEL-A2-01 收口项），顺序颠倒会让模型先看到退出码再看到输出，
        //      与 CC 供模型阅读的文本流不一致。
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "git status", 1, "some output line", "fatal: not a git repository");
        assertThat(r.isError()).isTrue();
        // 输出必须在 "Exit code 1" 之前（CC :687 先 append 输出，:699 后 append 退出码），
        // 且 "Exit code 1" 是文本尾部（CC 无后缀换行）
        assertThat(r.result().data()).endsWith("Exit code 1");
        assertThat(r.result().data().indexOf("some output line")).isLessThan(r.result().data().indexOf("Exit code 1"));
    }

    @Test
    @DisplayName("diff 退出 1 → success 且文本含 Files differ（CC commandSemantics.ts:60-67）")
    void interpretExitCodeResult_diffExit1_successWithFilesDifferText() {
        // WHY: diff 退出 1 = 文件有差异（正常业务结果），非错误
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "diff a b", 1, "< old\n---\n> new", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.result().data()).contains("Files differ");
    }

    @Test
    @DisplayName("find 退出 1 → success（非 error，CC commandSemantics.ts:50-58）")
    void interpretExitCodeResult_findExit1_success() {
        // WHY: find 退出 1 = 部分目录不可访问（partial success）非错误。
        //      ★ A2.md §5.3 误写 find 1=error，以 CC 为准
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "find / -name foo", 1, "", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.result().data()).contains("Some directories were inaccessible");
    }

    @Test
    @DisplayName("普通命令（DEFAULT）退出 1 → error（CC commandSemantics.ts:22-26）")
    void interpretExitCodeResult_unknownCommandExit1_error() {
        // WHY: 未识别命令只有 0=成功，1+ 都是错误（DEFAULT_SEMANTIC）
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "git status", 1, "", "fatal: not a git repository");
        assertThat(r.isError()).isTrue();
        assertThat(r.result().data()).contains("Exit code 1");
    }

    @Test
    @DisplayName("普通命令退出 0 → success（DEFAULT，无语义 message）")
    void interpretExitCodeResult_unknownCommandExit0_success() {
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "ls -la", 0, "total 0", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.result().data()).contains("total 0");
    }

    // ════════════════════════════════════════════════════════════════
    // [IMP-C2 返工 R2] isError 显式携带 · 持久化守卫语义
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R2: Bash 错误结果显式 isError=true（工具层）+ isToolErrorData 末尾标记识别（executor 层）")
    void interpretExitCodeResult_bashError_explicitIsErrorFlag() {
        // WHY: [IMP-C2 返工 R2] ToolResult 删除 isError 后，直接 execute 消费链（持久化守卫）需要
        //   错误语义。CC 端 is_error 由 interpretCommandResult 推导（commandSemantics.ts:22-26），
        //   非载荷文本前缀判定。Bash 错误载荷 = `<输出>\nExit code N`——"Exit code" 在尾部，
        //   前缀表对 "cat:" 命令前缀无法穷举漏检。R2 闭环后双通道：
        //   1) 工具层显式 BashExitCodeResult.isError=true（持久化守卫据此跳过错误落盘）；
        //   2) executor 层 isToolErrorData 识别统一末尾标记 "\nExit code <非零>"（LlmAgentLoop
        //      isBashExitCodeErrorMarker），真实 Bash 失败 → t.isError=true → sibling abort +
        //      failure analytics + tool_result is_error=true。
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "cat /nonexistent", 1, "", "cat: /nonexistent: No such file or directory");
        assertThat(r.isError())
            .as("Bash 错误结果必须显式标记 isError（CC interpretCommandResult 推导，非前缀启发式）")
            .isTrue();
        // 对照（R2 修复后）：isToolErrorData 经 "\n Exit code 1" 末尾标记识别真实 Bash 失败
        //   （前缀表对 "cat:" 仍不命中，但末尾标记是 BashTool 语义错误统一附加的可识别标记）
        assertThat(LlmAgentLoop.isToolErrorData(r.result().data()))
            .as("R2 修复后 isToolErrorData 应识别真实 Bash 失败载荷（\n Exit code 非零 末尾标记）")
            .isTrue();
        assertThat(r.result().data()).endsWith("Exit code 1");
    }

    @Test
    @DisplayName("R2: 成功输出以 'Error:' 开头 → 显式 isError=false（前缀启发式误检对照）")
    void interpretExitCodeResult_successWithErrorPrefix_notError() {
        // WHY: 旧前缀启发式对成功输出以 "Error:" 开头的文本误检（false-positive），
        //   会错误跳过持久化。显式标志由 interpretCommandResult 判定 → isError=false 正确。
        var r = bashTool.interpretExitCodeResult("test-tooluse-1", "echo 'Error: expected marker'", 0, "Error: expected marker", "");
        assertThat(r.isError())
            .as("成功输出以 'Error:' 开头不得误判为错误（CC 语义由退出码判定）")
            .isFalse();
        assertThat(LlmAgentLoop.isToolErrorData(r.result().data()))
            .as("对照：前缀启发式对 'Error:' 开头成功文本误检（false-positive）")
            .isTrue();
    }

    // ─────────────────────────────────────────────
    // Session A3 · mode 分支接线（BashModeValidation.checkPermissionMode
    // 接入 checkPermissions · CC modeValidation.ts:72-109）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("mode=acceptEdits + mkdir → Allow（reason=Mode(ACCEPT_EDITS)）")
    void checkPermissions_acceptEditsMkdir_allow() {
        // WHY: CC modeValidation.ts:38-50 —— acceptEdits mode 下 filesystem 命令自动 allow。
        //      若 mode 分支未接线，mkdir 落到 read-only/passthrough → 用户多余弹窗。
        var ctx = toolUseContext(PermissionMode.ACCEPT_EDITS);
        var r = bashTool.checkPermissions(input("mkdir foo"), ctx);
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        var allow = (PermissionResult.Allow) r;
        assertThat(allow.reason()).isInstanceOf(PermissionDecisionReason.Mode.class);
    }

    @Test
    @DisplayName("mode=bypassPermissions + mkdir → 非 allow（mode 分支 passthrough，交上层管线）")
    void checkPermissions_bypassMkdir_notAllow() {
        // WHY: CC modeValidation.ts:77-90 —— bypassPermissions 直接 passthrough，
        //      不该在此 allow（bypass 由主权限流程处理）。
        var ctx = toolUseContext(PermissionMode.BYPASS_PERMISSIONS);
        var r = bashTool.checkPermissions(input("mkdir foo"), ctx);
        assertThat(r).isInstanceOf(PermissionResult.Passthrough.class);
    }

    @Test
    @DisplayName("mode=acceptEdits + 非 filesystem（git push）→ 非 allow（mode 分支 passthrough）")
    void checkPermissions_acceptEditsGitPush_notAllow() {
        // WHY: CC modeValidation.ts:52-56 —— acceptEdits 只放行 7 个 filesystem 命令，
        //      git push 属上层管线判定（git 相关有独立 allow/ask 规则）。
        var ctx = toolUseContext(PermissionMode.ACCEPT_EDITS);
        var r = bashTool.checkPermissions(input("git push origin main"), ctx);
        assertThat(r).isInstanceOf(PermissionResult.Passthrough.class);
    }

    // ─────────────────────────────────────────────
    // Session A3 · image 输出接线（maybeBuildImageResult → ToolResult.image）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("image 输出 data URI → ToolResult<JsonNode> 且 META_IMAGE 字段就绪")
    void maybeBuildImageResult_dataUri_buildsImageToolResult() {
        // WHY: CC BashTool.tsx:772-785 stripEmptyLines→isImageOutput→buildImageToolResult
        //      → image block。Java 经 ToolResult.image（ToolResultMapper imageContent 序列化
        //      [{type:"image",source:{type:"base64",media_type,data}}]）。
        var r = bashTool.maybeBuildImageResult("test-tooluse-1", "data:image/png;base64,QUJD");
        assertThat(r).isNotNull();
        assertThat(r).isInstanceOf(ToolResult.class);
        var data = (com.fasterxml.jackson.databind.JsonNode) r.data();
        assertThat(data.has("image_base64")).isTrue();
        assertThat(data.get("image_media_type").asText()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("非 image stdout → maybeBuildImageResult null（交退出码语义路径）")
    void maybeBuildImageResult_plainText_returnsNull() {
        // WHY: 非 image 输出不应构造 image ToolResult（LLM 看到错误 image block）。
        assertThat(bashTool.maybeBuildImageResult("test-tooluse-1", "hello world")).isNull();
    }

    // ── E4 / OPD-32 · 小输出行为不回归（execute 持久化下沉后）──

    @Test
    @DisplayName("小输出经 execute(call,ctx) 不落盘：data 形状与现状一致，无 <persisted-output>")
    void execute_smallOutput_noPersist_shapePreserved() throws Exception {
        Path ws = Files.createTempDirectory("e4-align-ws");
        ToolUseContext ctx = toolUseContextWithCwd(PermissionMode.DEFAULT, ws);
        ToolResult<String> r = bashTool.execute(call("e4-align", "echo hello-align"), ctx);
        // WHY: 持久化下沉 BashTool.call 后，小输出（<30k）必须保持原截断 stdout 路径
        //      （CC BashTool.tsx:731 条件 outputFilePath && outputTaskId 不满足 → 不落盘）。
        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("小输出成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        assertThat(r.data()).contains("hello-align");
        assertThat(r.data()).doesNotContain("<persisted-output>");
        assertThat(com.nexusai.application.agent.tool.ToolResultStorage
            .getToolResultsDir(ws, ctx.sessionId().toString())).doesNotExist();
    }

    @Test
    @DisplayName("execute(call) 无 ctx fallback：不 NPE，小输出正常返回")
    void execute_noCtx_fallback_noNpe() {
        // WHY: ToolRegistry.dispatch 兼容路径 ctx 可能为 null（Tool.java:169 default 回退 execute(call)），
        //      E4 持久化下沉后 ctx=null 必须跳过持久化而非 NPE。
        ToolResult<String> r = bashTool.execute(call("e4-noctx", "echo hi-noctx"));
        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("无 ctx 小输出成功结果 data 非错误消息")
            .isFalse();
        assertThat(r.data()).contains("hi-noctx");
    }

    @Test
    @DisplayName("extractClaudeCodeHints：hint 记录携带 sourceCommand=命令首 token（P2-10，claudeCodeHints.ts:81/:130-134）")
    void extractClaudeCodeHints_carriesSourceCommand() {
        // WHY（P2-10）：CC extractClaudeCodeHints(output, command) 双参 —— hint 记录 sourceCommand =
        //   firstCommandToken(command)（安装提示展示"发射 hint 的工具 vs 推荐的插件"）。旧 Java 单参
        //   签名缺 sourceCommand，与 CC :72-120 漂移。本断言锁死双参 + 首 token 提取。
        String out = "line\n<claude-code-hint v=\"1\" type=\"plugin\" value=\"my-plugin@market\"/>\nend\n";
        BashTool.HintsResult r = BashTool.extractClaudeCodeHints(out, "  npm install my-plugin");

        assertThat(r.stripped()).as("hint 整行剥离").doesNotContain("<claude-code-hint");
        assertThat(r.hints()).hasSize(1);
        java.util.Map<String, String> hint = r.hints().get(0);
        assertThat(hint.get("sourceCommand"))
            .as("sourceCommand = 命令 trim 后首空白分隔 token（CC firstCommandToken）")
            .isEqualTo("npm");
        assertThat(hint.get("v")).isEqualTo("1");
        assertThat(hint.get("type")).isEqualTo("plugin");
        assertThat(hint.get("value")).isEqualTo("my-plugin@market");
    }

    @Test
    @DisplayName("extractClaudeCodeHints：空命令 → sourceCommand 空串（CC firstCommandToken 边界）")
    void extractClaudeCodeHints_blankCommand_emptySourceCommand() {
        String out = "<claude-code-hint v=\"1\" type=\"plugin\" value=\"a@b\"/>\n";
        BashTool.HintsResult r = BashTool.extractClaudeCodeHints(out, "   ");

        assertThat(r.hints()).hasSize(1);
        assertThat(r.hints().get(0).get("sourceCommand")).as("纯空白命令 → sourceCommand 空串").isEqualTo("");
    }

    private static ToolUseContext toolUseContext(PermissionMode permissionMode) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of(),
            null, permissionMode);
    }

    private static ToolUseContext toolUseContextWithCwd(PermissionMode permissionMode, Path effectiveCwd) {
        // 13 字段便利工厂（ToolUseContext.java:719）含 effectiveCwd → 持久化 workspaceDir 可断言
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, permissionMode,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolUseBlock call(String id, String command) {
        return new ToolUseBlock(id, "Bash", JSON.createObjectNode().put("command", command));
    }
}
