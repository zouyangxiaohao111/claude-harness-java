package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [Session H2] CommandHookExecutor · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:747-1335} (execCommandHook).
 *
 * <p>WHY (规则九 · 测试验证意图): 本测试验证 <b>命令 hook 执行器</b> 的意图 — settings.json
 * 配置的 CommandHook 必须能:
 * <ol>
 *   <li>exit 0 + stdout JSON {@code {continue:false}} → preventContinuation=true (CC :518-523)</li>
 *   <li>exit 2 → blocking stop (stderr 注入 LLM, CC :2648-2668)</li>
 *   <li>其他非零 → 不阻断 (non_blocking, CC :2670-2697)</li>
 *   <li>超时 → 终止进程 (CC wrapSpawn timeout)</li>
 *   <li>EPIPE (hook 不读 stdin) → 不抛未捕获, 返回 status 1 (CC :1288-1299)</li>
 *   <li>async 标志 → backgrounded 占位 (H2-3, H10 补 AsyncHookRegistry)</li>
 *   <li>Windows 路径转换 / .sh prepend / SHELL_PREFIX / prompt 检测 — 纯逻辑可测</li>
 * </ol>
 *
 * <p><b>可测性</b>: 进程执行经 {@link CommandHookExecutor.ProcessLauncher} 抽象, 测试注入
 * {@link FakeHookProcess} (内存 stdin/stdout/stderr), 不依赖真实 Git Bash/pwsh 存在
 * (Windows CI/本地可能没装 Git Bash, 见 H2.md 决策点).
 *
 * @since Session H2
 */
@DisplayName("[H2] CommandHookExecutor 对齐 CC execCommandHook")
class CommandHookExecutorTest {

    // ════════════════════════════════════════════════════════════════════════
    // Fake 进程 (注入用) · 不依赖真实 shell
    // ════════════════════════════════════════════════════════════════════════

    /** 内存 fake 进程 · stdin 收集到 stdout 缓冲, stdout/stderr 用 ByteArrayInputStream. */
    static class FakeHookProcess implements CommandHookExecutor.HookProcess {
        final ByteArrayOutputStream stdinCapture = new ByteArrayOutputStream();
        final InputStream stdoutIn;
        final InputStream stderrIn;
        final int exitCode;
        final boolean[] waitForResults;
        final boolean stdinThrowsEpipe;
        int waitForCalls = 0;
        volatile boolean destroyed;

        FakeHookProcess(String stdout, String stderr, int exitCode, boolean[] waitForResults,
                        boolean stdinThrowsEpipe) {
            this.stdoutIn = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderrIn = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
            this.waitForResults = waitForResults;
            this.stdinThrowsEpipe = stdinThrowsEpipe;
        }

        static FakeHookProcess normal(String stdout, String stderr, int exitCode) {
            return new FakeHookProcess(stdout, stderr, exitCode, new boolean[]{true}, false);
        }

        /**
         * 超时模拟 · waitFor 恒 false (在超时预算内不退出; 销毁后也不报告退出).
         *
         * <p>[ALIGN-HOOKS-2] 主线程改为切片轮询 waitFor(≤100ms) + backgroundedResult 检测
         * (对齐 CC :1273-1277 Promise.race) — 旧 {@code [false, true]} 模式 (首次 wait
         * 超时、销毁后退出) 会被切片循环当作"第二次调用即完成"而跳过 destroy.
         */
        static FakeHookProcess timeout(int exitCode) {
            return new FakeHookProcess("partial-out", "partial-err", exitCode,
                new boolean[]{false}, false);
        }

        static FakeHookProcess epipe() {
            return new FakeHookProcess("", "", 1, new boolean[]{true}, true);
        }

        @Override public OutputStream stdin() {
            if (stdinThrowsEpipe) {
                return new OutputStream() {
                    @Override public void write(int b) throws IOException { throw new IOException("Broken pipe"); }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        throw new IOException("Broken pipe");
                    }
                    @Override public void close() throws IOException { throw new IOException("Broken pipe"); }
                };
            }
            return stdinCapture;
        }

        @Override public InputStream stdout() { return stdoutIn; }
        @Override public InputStream stderr() { return stderrIn; }

        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (waitForCalls < waitForResults.length) {
                return waitForResults[waitForCalls++];
            }
            return waitForResults[waitForResults.length - 1];
        }

        @Override public void destroyForcibly() { destroyed = true; }
        @Override public int exitValue() { return exitCode; }
    }

    /** fake launcher · 固定返回同一 fake 进程. */
    static class FakeLauncher implements CommandHookExecutor.ProcessLauncher {
        final FakeHookProcess process;
        CommandHookExecutor.ProcessSpec lastSpec;

        FakeLauncher(FakeHookProcess process) { this.process = process; }

        @Override
        public CommandHookExecutor.HookProcess launch(CommandHookExecutor.ProcessSpec spec) throws IOException {
            this.lastSpec = spec;
            return process;
        }
    }

    /** 构造测试用 executor · fake launcher + 空 env (无 SHELL_PREFIX) + 恒真 pathExists. */
    private CommandHookExecutor newExecutor(FakeHookProcess process) {
        return new CommandHookExecutor(new FakeLauncher(process),
            k -> null, p -> true, () -> "C:/project", pluginId ->
            "C:/Users/test/.claude/plugins/" + pluginId);
    }

    private static CommandHook command(String cmd, Integer timeout, Boolean async) {
        return new CommandHook(cmd, null, null, timeout, null, null, async, null);
    }

    private static HookEvent preToolEvent() {
        return HookEvent.toolPre("Bash", null, "s1", null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. exit code 分流 (toHookResult 纯静态) · CC runHook :2616-2697
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("exit 0 + stdout JSON {continue:false} → preventContinuation=true (CC :518-523)")
    void exit0_stdoutContinueFalse_preventContinuation() {
        // WHY: command hook 协议里 hook 返回 {continue:false} 表示阻止后续流程 (CC processHookJSONOutput),
        //       若 Java 端解析不出来, hook 的阻断意图静默丢失 → 主流程继续, 等同 hook 没配.
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("{\"continue\":false}", "", "{\"continue\":false}", 0, false, false);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "check.sh");

        assertThat(hookResult.preventContinuation()).isTrue();
        // [H3] CC 对齐: status 0 JSON 路径 outcome 恒 SUCCESS (hooks.ts:2592/:2610),
        //   阻断语义由 preventContinuation 承载 (H2 曾误设 BLOCKING)
        assertThat(hookResult.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
    }

    @Test
    @DisplayName("exit 2 → blocking stop + blockingError 注入 stderr (CC :2648-2668)")
    void exit2_blockingStopWithStderr() {
        // WHY: exit 2 是 hook 协议的"阻断"码, stderr 文本注入 LLM 作为反馈 (types/hooks.ts:243-246).
        //       若映射成 proceed, 阻断 hook 等于摆设.
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("", "tests failed", "tests failed", 2, false, false);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "test.sh");

        assertThat(hookResult.preventContinuation()).isTrue();
        assertThat(hookResult.outcome()).isEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(hookResult.blockingError()).isNotNull();
        assertThat(hookResult.blockingError().blockingError()).isEqualTo("[test.sh]: tests failed");
        assertThat(hookResult.blockingError().command()).isEqualTo("test.sh");
    }
    @Test
    @DisplayName("G02 exit 3 (其他非零) → NON_BLOCKING_ERROR + hook_non_blocking_error attachment (CC :2670-2697)")
    void exitOther_nonBlockingError() {
        // WHY: 非 0/2 退出码是 warning 级错误 (CC :2670-2697 → non_blocking_error), 必须产出
        //      hook_non_blocking_error attachment (stderr 含 'Failed with non-blocking status code:')
        //      而非静默 proceed — 用户可观测 hook 失败原因 (S4 G02 对齐).
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("out", "warning", "outwarning", 3, false, false);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "warn.sh");

        assertThat(hookResult.preventContinuation()).isFalse();
        assertThat(hookResult.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(hookResult.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) hookResult.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        assertThat(att.stderr()).isEqualTo("Failed with non-blocking status code: warning");
        assertThat(att.exitCode()).isEqualTo(3);
    }

    @Test
    @DisplayName("G01 exit 0 纯文本 → hook_success attachment content=stdout.trim() (CC :2617-2645)")
    void exit0_plainText_hookSuccessWithTrimmedContent() {
        // WHY: 纯文本 exit 0 → CC :2617-2645 hook_success content=result.stdout.trim() —
        //       不是 proceed (旧 Java 静默 proceed 丢 stdout, S4 G01 对齐).
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("  hello hook  \n", "", "  hello hook  \n", 0, false, false);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "ok.sh");

        assertThat(hookResult.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(hookResult.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) hookResult.message();
        assertThat(att.type()).isEqualTo("hook_success");
        assertThat(att.content()).isEqualTo("hello hook");
        assertThat(att.stdout()).isEqualTo("  hello hook  \n");
        assertThat(att.exitCode()).isEqualTo(0);
        assertThat(att.command()).isEqualTo("ok.sh");
    }

    @Test
    @DisplayName("G03 aborted → CANCELLED + hook_cancelled attachment (CC :2473-2497)")
    void aborted_cancelledWithAttachment() {
        // WHY: 用户中止 → CC :2473-2497 outcome 'cancelled' + hook_cancelled attachment,
        //       旧 Java 无 aborted 检查落到 exit-code 分流 (S4 G03 对齐).
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("", "Hook cancelled", "Hook cancelled", 1, true, false);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "slow.sh");

        assertThat(hookResult.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
        assertThat(hookResult.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) hookResult.message();
        assertThat(att.type()).isEqualTo("hook_cancelled");
        assertThat(hookResult.preventContinuation()).isFalse();
    }

    @Test
    @DisplayName("G17 { 开头非法 JSON → NON_BLOCKING_ERROR + 'JSON validation failed:' attachment exitCode=1 (CC :2504-2531)")
    void invalidJson_validationErrorAttachment() {
        // WHY: stdout 以 { 开头但 zod 校验失败 → CC :2504-2531 hook_non_blocking_error,
        //       stderr=`JSON validation failed: ${validationError}` exitCode=1.
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("{\"continue\":\"false\"}", "", "{\"continue\":\"false\"}", 0, false, false);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "bad.sh");

        assertThat(hookResult.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(hookResult.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) hookResult.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        assertThat(att.stderr()).startsWith("JSON validation failed:");
        assertThat(att.stdout()).isEqualTo("{\"continue\":\"false\"}");
        assertThat(att.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("backgrounded=true → 不阻断 (async 占位, H2-3)")
    void backgrounded_doesNotBlock() {
        CommandHookExecutor.CommandHookResult result =
            new CommandHookExecutor.CommandHookResult("", "", "", 0, false, true);

        GenericHook.HookResult hookResult = CommandHookExecutor.toHookResult(result, "bg.sh");

        assertThat(hookResult.preventContinuation()).isFalse();
    }

    @Test
    @DisplayName("[IMP-RS-01 DEL-01e] prompt 请求检测: stdout 出现 {prompt,message,options} → 回调 requestPrompt → 写回 stdin (CC :1072-1110)")
    void execute_promptRequest_writesResponseBack() throws Exception {
        // WHY: command hook 可向用户发起交互式 prompt, 执行器必须把用户选择写回 stdin,
        //       否则 hook 挂起等待输入 (CC :1093-1096 串行回调 + 写回). 本测试验证 prompt
        //       分支真实可达 (DEL-01e 补回后): prompt 行被检测 → requestPrompt 回调 →
        //       {prompt_response, selected} 写回 stdin → 最终 stdout 过滤 prompt 行 (CC :1243-1249).
        String promptLine = "{\"prompt\":\"req1\",\"message\":\"choose\","
            + "\"options\":[{\"key\":\"a\",\"label\":\"A\"},{\"key\":\"b\",\"label\":\"B\"}]}";
        FakeHookProcess fake = FakeHookProcess.normal(promptLine + "\nok\n", "", 0);
        CommandHookExecutor executor = newExecutor(fake);
        PromptRequester requester = req -> new PromptResponse(req.prompt(), "b");

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("read", null, null), preToolEvent(), "h", "{}", null, null, null, null, false,
            null, CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, null, requester);

        assertThat(result.status()).isEqualTo(0);
        // 用户选择写回 stdin (串行响应, CC :1096)
        String stdin = fake.stdinCapture.toString(StandardCharsets.UTF_8);
        assertThat(stdin).contains("\"prompt_response\":\"req1\"");
        assertThat(stdin).contains("\"selected\":\"b\"");
        // prompt 行已从 stdout 过滤 (防泄漏, CC :1243-1249)
        assertThat(result.stdout()).doesNotContain(promptLine);
    }

    @Test
    @DisplayName("[IMP-RS-01 DEL-01e] prompt 回调失败 → 关闭 stdin 防 hook 挂起 (CC :1098-1102)")
    void execute_promptRequest_callbackFails_destroysStdin() throws Exception {
        // WHY: 用户取消 / prompt 处理失败时, 若 stdin 保持 open 而 hook 等待输入 → 挂起.
        //       CC 在回调失败后 child.stdin.destroy() (hooks.ts:1098-1102), Java 等价 close.
        //       本测试用抛异常的回调验证 stdin 被关闭 (FakeHookProcess.stdinCapture close 后
        //       再 write 抛 IOException → 通过 destroyed/stdin 状态断言).
        String promptLine = "{\"prompt\":\"req1\",\"message\":\"choose\","
            + "\"options\":[{\"key\":\"a\",\"label\":\"A\"}]}";
        FakeHookProcess fake = FakeHookProcess.normal(promptLine + "\nok\n", "", 0);
        CommandHookExecutor executor = newExecutor(fake);
        PromptRequester failingRequester = req -> {
            throw new IllegalStateException("user cancelled");
        };

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("read", null, null), preToolEvent(), "h", "{}", null, null, null, null, false,
            null, CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, null, failingRequester);

        // 回调失败不阻断整体结果 (stdout 过滤后正常返回); prompt 行被过滤 (防泄漏)
        assertThat(result.status()).isEqualTo(0);
        assertThat(result.stdout()).doesNotContain(promptLine);
        assertThat(result.stdout()).contains("ok");
    }

    @Test
    @DisplayName("[IMP-RS-01 DEL-01e] requestPrompt=null → stdin 写入后立即 close (通道关闭, CC :1212-1214)")
    void execute_requestPromptNull_closesStdinImmediately() throws Exception {
        // WHY: 无 prompt 通道 (feature('HOOK_PROMPTS')=false 等价) 时 stdin 写入后立即 end
        //       (CC :1212-1214) — 不得保持 open. 验证 13 参 execute 传 null requestPrompt
        //       与 12 参便捷版行为一致.
        FakeHookProcess fake = FakeHookProcess.normal("ok\n", "", 0);
        CommandHookExecutor executor = newExecutor(fake);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("read", null, null), preToolEvent(), "h",
            CommandHookExecutor.buildJsonInput(preToolEvent()), null, null, null, null, false,
            null, CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, null, null);

        assertThat(result.status()).isEqualTo(0);
        assertThat(result.stdout()).contains("ok");
        // stdin 已写 (jsonInput + '\n'), 通道关闭语义不影响输出收集
        assertThat(fake.stdinCapture.toString(StandardCharsets.UTF_8)).contains("hook_event_name");
    }

    @Test
    @DisplayName("G09 execute 13 参: hookCwd 透传 ProcessSpec.cwd (对齐 CC spawn cwd=safeCwd hooks.ts:931-938/:969/:979)")
    void execute_hookCwd_passedToProcessSpec() throws Exception {
        // WHY: CC execCommandHook spawn cwd = safeCwd (会话 cwd, hooks.ts:931-938);
        //       13 参重载接收 HookRegistry 解析的会话 cwd 并透传 ProcessSpec (S4 G09).
        FakeHookProcess fake = FakeHookProcess.normal("", "", 0);
        FakeLauncher launcher = new FakeLauncher(fake);
        CommandHookExecutor executor = new CommandHookExecutor(launcher,
            k -> null, p -> true, () -> "C:/project", id -> "C:/data");

        executor.execute(command("echo hi", null, null), preToolEvent(), "h", "{}",
            null, null, null, null, false, null,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, "C:/worktrees/sub");

        assertThat(launcher.lastSpec.cwd()).isEqualTo("C:/worktrees/sub");
    }

    @Test
    @DisplayName("G09 execute 11 参委托: hookCwd=defaultProjectRoot (既有行为不变)")
    void execute_legacy11Param_usesDefaultProjectRoot() throws Exception {
        // WHY: 11 参重载委托 12 参并传 defaultProjectRoot()=user.dir — 既有测试/调用方零改动.
        FakeHookProcess fake = FakeHookProcess.normal("", "", 0);
        FakeLauncher launcher = new FakeLauncher(fake);
        CommandHookExecutor executor = new CommandHookExecutor(launcher,
            k -> null, p -> true, () -> "C:/project", id -> "C:/data");

        executor.execute(command("echo hi", null, null), preToolEvent(), "h", "{}",
            null, null, null, null, false, null);

        assertThat(launcher.lastSpec.cwd()).isEqualTo(System.getProperty("user.dir"));
    }

    @Test
    @DisplayName("G14 resolveSpawnCwd: event.cwd() 优先 ?? CwdResolution.getCwd(sessionId) 单一入口")
    void resolveSpawnCwd_priorityChain() throws Exception {
        // WHY (G14): hook spawn cwd 必须走 CwdResolution 单一入口（对齐 CC hooks.ts:931
        //   hookCwd=getCwd()），消除 hook 域自建 effectiveCwd ?: currentSessionProjectRoot 三级链
        //   与工具域 CwdResolution 同语义两套标准（OD-4）。event.cwd() 显式覆盖优先 = Java 侧
        //   escape hatch（CC 端 BaseHookInput 恒 getCwd() 无事件级覆盖）。
        // event.cwd 优先（显式覆盖胜出，CC hooks.ts:931 getCwd 第一来源语义）
        HookEvent eventWithCwd = new HookEvent(HookEventType.NOTIFICATION, "s1-g14", null,
            "C:/event-cwd", null, null, null, null, null, null, null, null,
            new HookEventData.Notification(null, null, null), 0);
        assertThat(CommandHookExecutor.resolveSpawnCwd(eventWithCwd))
            .isEqualTo("C:/event-cwd");

        // event 无 cwd → CwdResolution.getCwd(sessionId) 收敛：会话绑定 projectRoot 胜出
        // （对齐 CC getCwd → STATE.cwd/sessionProjectDir；身份域红线 D-1 不读 env/config home）
        // 修复（2026-08-30）：绑定路径必须本机真实存在（CwdResolution.isValidDirectory 校验），
        //   原假路径 C:/worktrees/g14-bound 不存在 → isValidDirectory false → 回落 user.dir（恒失败）。
        String sid = "s1-g14-bound";
        Path boundDir = Files.createTempDirectory("g14-bound");
        String boundP = boundDir.toString();
        try {
            com.nexusai.common.SessionProjectRoot.setForSession(sid, boundP);
            HookEvent eventNoCwd = HookEvent.toolPre("Bash", null, sid, null);
            assertThat(CommandHookExecutor.resolveSpawnCwd(eventNoCwd))
                .as("event 无 cwd 时必须经 CwdResolution 取会话绑定 projectRoot（G14 单一入口）")
                .isEqualTo(com.nexusai.application.agent.agent.CwdResolution.normalizeCwd(boundP));
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession(sid);
            try { java.nio.file.Files.deleteIfExists(boundDir); } catch (java.io.IOException ignored) {}
        }

        // event 无 cwd 且 sessionId 无绑定 → CwdResolution 回落 user.dir（非 env/config home，
        //   身份域红线 D-1）——证明不再读 AutoMemPaths.currentSessionProjectRoot 的 config home 兜底
        HookEvent eventNoSid = HookEvent.toolPre("Bash", null, "s1-g14-nobody", null);
        String resolved = CommandHookExecutor.resolveSpawnCwd(eventNoSid);
        assertThat(resolved)
            .as("无会话绑定时回落 user.dir（对齐 CC getCwd catch → getOriginalCwd → 启动 cwd），绝不读 config home")
            .isEqualTo(com.nexusai.application.agent.agent.CwdResolution.normalizeCwd(
                System.getProperty("user.dir")));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. 进程执行 (execute + fake process)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("exit 0 + stdout JSON {continue:false} → backgrounded=false + status=0 (executor 层)")
    void execute_exit0CapturesStdout() throws Exception {
        // WHY: execute 是纯执行器, 不应吞掉 stdout JSON — 由调用方 (toHookResult) 做协议解析.
        FakeHookProcess fake = FakeHookProcess.normal("{\"continue\":false}\n", "", 0);
        CommandHookExecutor executor = newExecutor(fake);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("echo {\"continue\":false}", null, null), preToolEvent(), "h",
            CommandHookExecutor.buildJsonInput(preToolEvent()), null, null, null, null, false);

        assertThat(result.status()).isEqualTo(0);
        assertThat(result.stdout()).contains("\"continue\":false");
        assertThat(result.backgrounded()).isFalse();
        assertThat(fake.stdinCapture.toString(StandardCharsets.UTF_8)).endsWith("\n");
    }

    @Test
    @DisplayName("hook.timeout 短 → 超时终止进程, 不抛未捕获 (CC wrapSpawn timeout)")
    void execute_timeout_destroysProcess() throws Exception {
        // WHY: command hook 超时必须强杀进程, 否则失控进程永久挂起占用资源 (CC wrapSpawn timeout).
        //       waitFor 恒 false 模拟进程在超时预算内不退出 → 预算耗尽后 destroyForcibly.
        FakeHookProcess fake = FakeHookProcess.timeout(1);
        CommandHookExecutor executor = newExecutor(fake);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("sleep 100", 1, null), preToolEvent(), "h", "{}", null, null, null, null, false);

        assertThat(fake.destroyed).isTrue();
        assertThat(result).isNotNull();  // 不抛未捕获
    }

    @Test
    @DisplayName("EPIPE (hook 不读 stdin) → status 1 + EPIPE 消息, 不抛未捕获 (CC :1288-1299)")
    void execute_epipe_returnsStatus1() throws Exception {
        // WHY: hook 提前关闭 stdin (不读输入) 时 stdin.write 抛 EPIPE, 必须捕获为 status 1 错误结果,
        //       不能让异常泄漏到调用方 (hook 执行错误不该中断主流程).
        FakeHookProcess fake = FakeHookProcess.epipe();
        CommandHookExecutor executor = newExecutor(fake);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("head -1", null, null), preToolEvent(), "h", "{}", null, null, null, null, false);

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.stderr()).contains("EPIPE");
    }

    @Test
    @DisplayName("async 标志 (hook.asyncFlag=true) → 注册进 AsyncHookRegistry 后台托管 (CC :995-1030)")
    void execute_configAsync_registeredToRegistry() throws Exception {
        // WHY: async hook 应立即后台化不阻塞主流程 (CC executeInBackground). H2 返回占位,
        //      进程结果永远丢失; H10 起真实注册进 AsyncHookRegistry, 由轮询交付响应.
        FakeHookProcess fake = FakeHookProcess.normal("", "", 0);
        HookEventBus eventBus = new HookEventBus();
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        CommandHookExecutor executor = newExecutor(fake);
        executor.setAsyncHookRegistry(registry);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("long-task", null, true), preToolEvent(), "h", "{}", null, null, null, null, false);

        assertThat(result.backgrounded()).isTrue();
        assertThat(result.status()).isEqualTo(0);
        // 真实接线: 进程包装已入池 (CC :1010 executeInBackground → registerPendingAsyncHook)
        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
        assertThat(registry.getPendingAsyncHooks().get(0).processId()).startsWith("async_hook_");
        // 配置 async 路径不 waitFor → fake.waitForCalls == 0
        assertThat(fake.waitForCalls).isEqualTo(0);
    }

    @Test
    @DisplayName("stdout 首行 {async:true} → 注册进 AsyncHookRegistry 后台托管 (CC :1117-1164)")
    void execute_stdoutAsync_registeredToRegistry() throws Exception {
        // WHY: async hook 协议是 stdout 首行输出 {"async":true}, 检测后应立即后台化
        //      (CC :1137-1151 executeInBackground, stdout 路径不传 asyncRewake → 只走 registry).
        FakeHookProcess fake = FakeHookProcess.normal("{\"async\":true}\ndone\n", "", 0);
        HookEventBus eventBus = new HookEventBus();
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        CommandHookExecutor executor = newExecutor(fake);
        executor.setAsyncHookRegistry(registry);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("echo async", null, null), preToolEvent(), "h", "{}", null, null, null, null, false);

        assertThat(result.backgrounded()).isTrue();
        // 真实接线: 已入池 (H2 占位无池)
        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
        assertThat(registry.getPendingAsyncHooks().get(0).processId()).startsWith("async_hook_");
    }

    @Test
    @DisplayName("stdout 首行 async 在进程仍运行时即检测 → 后台化且不被超时杀死 (CC :1117-1164, H10-9 修复)")
    void execute_stdoutAsync_streamingDetection_beatsSyncTimeout() throws Exception {
        // WHY (H10-9): 旧实现先 waitFor 进程退出再检测 stdout 首行 — 长跑 async hook 在
        //     检测前先被同步超时强杀 (destroyForcibly). CC 在 data 流首行即检测并后台化
        //     (hooks.ts:1117-1164), asyncResolve 竞胜 childClosePromise (:1273-1277).
        //     fake.waitFor 恒 false (进程不退出) + 短 timeout: 流式检测必须在超时预算内
        //     完成 → backgrounded=true 且 destroyed=false (未被超时杀死).
        // fake.waitFor 恒 false (进程不退出) + 短 timeout: 流式检测必须在超时预算内
        //     完成 → backgrounded=true 且 destroyed=false (未被超时杀死).
        FakeHookProcess fake = new FakeHookProcess("{\"async\":true}\nlong-running...\n", "", 0,
            new boolean[]{false}, false); // stdout 首行 async + waitFor 恒 false = 进程长跑
        HookEventBus eventBus = new HookEventBus();
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        CommandHookExecutor executor = newExecutor(fake);
        executor.setAsyncHookRegistry(registry);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("long-running-async", 1, null), preToolEvent(), "h", "{}", null, null, null, null, false);

        assertThat(result.backgrounded()).isTrue();
        assertThat(result.status()).isEqualTo(0);
        // 关键: 未被同步超时杀死 (H10-9 修复的直接证明)
        assertThat(fake.destroyed).isFalse();
        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
    }

    @Test
    @DisplayName("asyncRewake=true → bypass registry, exit 2 → task-notification 唤醒 (CC :205-240)")
    void execute_asyncRewake_bypassExit2Notification() throws Exception {
        // WHY: asyncRewake hook 不注册 registry (CC :205-240 bypass) — 完成时若 exit 2
        //      (blocking error) 必须以 task-notification 唤醒模型 (CC :232-238
        //      enqueuePendingNotification), 否则 blocking error 静默丢失, 模型永远不知道
        //      后台 hook 报了阻断错误.
        FakeHookProcess fake = FakeHookProcess.normal("", "blocking error", 2);
        HookEventBus eventBus = new HookEventBus();
        List<HookEventBus.HookExecutionEvent> hookEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        eventBus.registerHookEventHandler(hookEvents::add);
        // 真实消费方 (SDK includeHookEvents) 会开启全量事件 — 否则 PreToolUse 被白名单过滤,
        // 响应事件到不了 handler (hookEvents.ts:83-91 shouldEmit)
        eventBus.setAllHookEventsEnabled(true);
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        NotificationQueue queue = new NotificationQueue();
        CommandHookExecutor executor = newExecutor(fake);
        executor.setAsyncHookRegistry(registry);
        executor.setHookEventBus(eventBus);
        executor.setNotificationQueue(queue);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            new CommandHook("reawake", null, null, null, null, null, true, true),
            preToolEvent(), "h", "{}", null, null, null, null, false);

        assertThat(result.backgrounded()).isTrue();
        // bypass: 不进 registry 池
        assertThat(registry.getPendingAsyncHooks()).isEmpty();

        // 完成回调 (daemon watcher) 异步执行 → 轮询等待入队
        NotificationQueue.QueueItem item = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            List<NotificationQueue.QueueItem> drained = queue.dequeueAll();
            if (!drained.isEmpty()) {
                item = drained.get(0);
                break;
            }
            Thread.sleep(20);
        }
        assertThat(item).isNotNull();
        assertThat(item.mode()).isEqualTo("task-notification");
        // CC :234 wrapInSystemReminder 包装的阻断错误文本
        assertThat(item.value()).contains("Stop hook blocking error from command \"h\"");
        assertThat(item.value()).contains("blocking error");
        // response 事件已广播 (outcome=error, exit 2)
        HookEventBus.HookResponseEvent resp = hookEvents.stream()
            .filter(e -> e instanceof HookEventBus.HookResponseEvent)
            .map(e -> (HookEventBus.HookResponseEvent) e)
            .findFirst().orElse(null);
        assertThat(resp).isNotNull();
        assertThat(resp.outcome()).isEqualTo(HookEventBus.HookOutcome.ERROR);
        assertThat(resp.exitCode()).isEqualTo(2);
    }


    // ════════════════════════════════════════════════════════════════════════
    // 3. Windows 平台差异 (纯静态 · 不 spawn 真实 bash)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("windowsPathToPosixPath: C:\\Users\\foo → /c/Users/foo (CC windowsPaths.ts:128-145)")
    void windowsPathToPosix_driveLetter() {
        assertThat(CommandHookExecutor.windowsPathToPosixPath("C:\\Users\\foo")).isEqualTo("/c/Users/foo");
        assertThat(CommandHookExecutor.windowsPathToPosixPath("C:/Users/foo")).isEqualTo("/c/Users/foo");
    }

    @Test
    @DisplayName("windowsPathToPosixPath: UNC \\\\server\\share → //server/share")
    void windowsPathToPosix_unc() {
        assertThat(CommandHookExecutor.windowsPathToPosixPath("\\\\server\\share\\dir"))
            .isEqualTo("//server/share/dir");
    }

    @Test
    @DisplayName("Windows bash .sh 自动前置 bash (CC :862-866)")
    void windows_sh_prependBash() {
        CommandHook hook = command("run.sh arg", null, null);
        Function<String, String> identity = Function.identity();
        String transformed = CommandHookExecutor.buildFinalCommand(hook, false, true, null, null,
            identity, p -> true, id -> "");
        assertThat(transformed).isEqualTo("bash run.sh arg");
    }

    @Test
    @DisplayName("已是 'bash ' 开头的 .sh 不重复前置 (CC :863-864)")
    void windows_sh_noDoubleBash() {
        CommandHook hook = command("bash run.sh arg", null, null);
        String transformed = CommandHookExecutor.buildFinalCommand(hook, false, true, null, null,
            Function.identity(), p -> true, id -> "");
        assertThat(transformed).isEqualTo("bash run.sh arg");
    }

    @Test
    @DisplayName("PowerShell 不前置 bash (CC :862, PS 原生跑 .ps1)")
    void powershell_noBashPrepend() {
        CommandHook hook = command("run.ps1", null, null);
        String transformed = CommandHookExecutor.buildFinalCommand(hook, true, true, null, null,
            Function.identity(), p -> true, id -> "");
        assertThat(transformed).isEqualTo("run.ps1");
    }

    @Test
    @DisplayName("pluginRoot 非 null 但目录不存在 → throw (CC :831-836)")
    void pluginRootMissing_throws() {
        CommandHook hook = command("python3 ${CLAUDE_PLUGIN_ROOT}/script.py", null, null);
        assertThatCode(() -> CommandHookExecutor.buildFinalCommand(hook, false, false, "C:/missing",
            "plugin-x", Function.identity(), p -> false, id -> ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Plugin directory does not exist");
    }

    @Test
    @DisplayName("${CLAUDE_PLUGIN_ROOT} 替换 (CC :844-845)")
    void pluginRoot_substitution() {
        CommandHook hook = command("python3 ${CLAUDE_PLUGIN_ROOT}/script.py", null, null);
        String transformed = CommandHookExecutor.buildFinalCommand(hook, false, false, "C:/plugins/p",
            "p", Function.identity(), p -> true, id -> "C:/data");
        assertThat(transformed).isEqualTo("python3 C:/plugins/p/script.py");
    }

    @Test
    @DisplayName("CLAUDE_CODE_SHELL_PREFIX → POSIX 单引号包装 (CC :872-875, shellPrefix.ts)")
    void shellPrefix_posixQuote() {
        Function<String, String> env = k -> "CLAUDE_CODE_SHELL_PREFIX".equals(k) ? "bash" : null;
        String finalCmd = CommandHookExecutor.applyShellPrefix("echo hi", false, env);
        assertThat(finalCmd).isEqualTo("'bash' 'echo hi'");
    }

    @Test
    @DisplayName("PowerShell 忽略 CLAUDE_CODE_SHELL_PREFIX (CC :870-871)")
    void shellPrefix_powershellIgnored() {
        Function<String, String> env = k -> "CLAUDE_CODE_SHELL_PREFIX".equals(k) ? "bash" : null;
        String finalCmd = CommandHookExecutor.applyShellPrefix("Write-Output hi", true, env);
        assertThat(finalCmd).isEqualTo("Write-Output hi");
    }

    @Test
    @DisplayName("buildEnv: CLAUDE_PROJECT_DIR 恒注入 + skillRoot → CLAUDE_PLUGIN_ROOT (CC :882-926)")
    void buildEnv_projectDirAndSkillRoot() {
        Map<String, String> env = CommandHookExecutor.buildEnv(preToolEvent(), null, null, "C:/skills/s",
            Function.identity(), () -> "C:/proj", id -> "C:/data", false, null);
        // 双注入：CC 协议名 + nexusai 命名（同一项目根路径，兼容两类脚本，决策 D1/D6）
        assertThat(env).containsEntry("CLAUDE_PROJECT_DIR", "C:/proj");
        assertThat(env).containsEntry("NEXUSAI_PROJECT_DIR", "C:/proj");
        assertThat(env).containsEntry("CLAUDE_PLUGIN_ROOT", "C:/skills/s");
        assertThat(env).doesNotContainKey("CLAUDE_ENV_FILE");
    }

    @Test
    @DisplayName("hookIndex 非 null + SessionStart → CLAUDE_ENV_FILE 注入 (CC :917-926)")
    void buildEnv_claudeEnvFile_onSessionStart() {
        HookEvent sessionStart = HookEvent.sessionStart("s1", null, "startup", null, null);
        Map<String, String> env = CommandHookExecutor.buildEnv(sessionStart, null, null, null,
            Function.identity(), () -> "C:/proj", id -> "C:/data", false, 0);
        assertThat(env.get("CLAUDE_ENV_FILE")).contains("SessionStart-hook-0.sh");
    }

    @Test
    @DisplayName("buildJsonInput: hook_event_name PascalCase + session_id + tool_name + data KV (CC BaseHookInput)")
    void buildJsonInput_shape() {
        HookEvent event = HookEvent.userPromptSubmit("s1", "a1", "hello");
        String json = CommandHookExecutor.buildJsonInput(event);
        assertThat(json).contains("\"hook_event_name\":\"UserPromptSubmit\"");
        assertThat(json).contains("\"session_id\":\"s1\"");
        assertThat(json).contains("\"agent_id\":\"a1\"");
        assertThat(json).contains("\"prompt\":\"hello\"");
    }

    @Test
    @DisplayName("enrichBaseFields: UserPromptSubmit 视为工具事件 → 从 parentTuc 注入 permission_mode（CC hooks.ts:3842）")
    void enrichBaseFields_userPromptSubmit_injectsPermissionMode() {
        // WHY: CC executeUserPromptSubmitHooks（hooks.ts:3826-3855）对 UserPromptSubmit 注入
        //       permission_mode（createBaseHookInput(permissionMode)，hooks.ts:3842；processUserInput.ts:184
        //       传 appState.toolPermissionContext.mode）。Java enrichBaseFields 的 isToolEvent 集合原不含
        //       USER_PROMPT_SUBMIT → UserPromptSubmit hook stdin 缺 permission_mode（OPD-WF3-TH-02）。
        //       parentTuc 构造镜像 LlmAgentLoop:1963 UserPromptSubmit 段（of 便捷工厂，agentType=null）。
        HookEvent event = HookEvent.userPromptSubmit("s1", "a1", "hello");
        // 9 参工厂显式传 permissionMode=PLAN（6 参便捷工厂会把 permissionMode 字段硬编码 DEFAULT，
        //   enrichBaseFields 读 parentTuc.permissionMode() 注入，故须用显式 permissionMode 工厂验证映射）。
        ToolUseContext parentTuc = ToolUseContext.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "",
            PermissionMode.PLAN, List.of(), "", AbortController.NOOP,
            List.of(), null, PermissionMode.PLAN);

        HookEvent enriched = CommandHookExecutor.enrichBaseFields(event, parentTuc);

        // 关键断言: permission_mode 经 modeToCcString(PLAN) = "plan" 注入（对齐 CC 小写字面量）。
        assertThat(enriched.permissionMode()).isEqualTo("plan");
        // 载荷其余字段不变: agent_id / prompt 保留（HookEvent.userPromptSubmit 3 参已带）。
        assertThat(enriched.agentId()).isEqualTo("a1");
        assertThat(enriched.data()).containsEntry("prompt", "hello");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. HookRegistry 接线 (配置驱动 CommandHook 主链路)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("HookRegistry.executeEvent 分发 CommandHook → exit 0 + {continue:false} → preventContinuation")
    void hookRegistry_dispatchesCommandHook() {
        // WHY: settings.json 的 CommandHook 必须经 executeEvent 到达执行器, 否则配置驱动 hook
        //       只观测不执行 (H1 遗留). 本测试走完整链路: HooksSettings → snapshot → matcher engine
        //       → getMatchingHooks → executor(fake) → toHookResult 聚合.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo ok", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());

        FakeHookProcess fake = FakeHookProcess.normal("{\"continue\":false}\n", "", 0);
        CommandHookExecutor executor = newExecutor(fake);
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(executor);

        GenericHook.HookResult result = registry.executeEvent(preToolEvent());

        assertThat(result.preventContinuation()).isTrue();
        // [H3] CC 对齐: status 0 JSON 路径 outcome 恒 SUCCESS (hooks.ts:2592/:2610)
        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
    }
}
