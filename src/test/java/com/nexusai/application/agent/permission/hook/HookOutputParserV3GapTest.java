package com.nexusai.application.agent.permission.hook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H3 v3 对抗核验] 残留缺口修复 RED 测试 · 覆盖 v2 PARTIAL 剩余 3 缺口中的 2 个.
 *
 * <p>WHY (规则九 意图验证): v2 对抗复验判 H3 为 PARTIAL, 残留缺口:
 * <ol>
 *   <li><b>Gap 3 (attachment 载荷薄于 CC)</b>: CC hook_success / hook_blocking_error attachment
 *       携带 stdout/stderr/exitCode/command/durationMs (utils/attachments.ts:411-418,
 *       hooks.ts:710-736); Java 端 3 参 hookSuccess 只有 content:'', 4 参 hookBlockingError 丢
 *       command. 本测试验证 processHookJSONOutput 生成的 message attachment 载荷完整.</li>
 *   <li><b>Gap 2 (expectedHookEvent 无真实分发路径测试)</b>: 既有 Gap3a 直接调
 *       processHookJSONOutput 断言 throw, 不经过 CommandHookExecutor.toHookResult /
 *       HookRegistry.httpToHookResult (executeConfiguredCommand/executeConfiguredHttp 的委托层).
 *       本测试走真实分发委托层, 验证 event.type().ccName() 灌入 expectedHookEvent 后
 *       mismatch → NON_BLOCKING_ERROR (fail-loud, 对齐 CC runHook catch :2698-2729), 而非
 *       静默 proceed.</li>
 * </ol>
 */
class HookOutputParserV3GapTest {

    // ─────────── Gap 3: hook_success/hook_blocking_error 载荷对齐 CC (Gap 3) ───────────

    @Test
    @DisplayName("Gap3a hook_success 携带 stdout/stderr/exitCode/command/durationMs (CC utils/attachments.ts:411-418)")
    void hookSuccess_carriesFullCcPayload() {
        // WHY: CC processHookJSONOutput (hooks.ts:716-736) 生成的 hook_success attachment 带
        //      stdout/stderr/exitCode/command/durationMs — 审计/UI 需要看到 hook 成功时的进程
        //      输出与耗时. Java 端 3 参 hookSuccess 只有 content:'' → 载荷薄于 CC (对抗核验 Gap 3).
        GenericHook.HookResult result = HookOutputParser.processHookJSONOutput(
            (HookJSONOutput.SyncHookOutput) HookOutputParser.validateHookJson(
                "{\"continue\":true}").json(),
            "check.sh", "UserPromptSubmit:check.sh", "UserPromptSubmit",
            "toolu_9", "UserPromptSubmit",
            "stdout-content", "stderr-content", 0, 1234L).result();

        AttachmentMessageDto msg = (AttachmentMessageDto) result.message();
        assertThat(msg).isNotNull();
        assertThat(msg.type()).isEqualTo("hook_success");
        assertThat(msg.stdout()).isEqualTo("stdout-content");
        assertThat(msg.stderr()).isEqualTo("stderr-content");
        assertThat(msg.exitCode()).isEqualTo(0);
        assertThat(msg.command()).isEqualTo("check.sh");
        assertThat(msg.durationMs()).isEqualTo(1234L);
    }

    @Test
    @DisplayName("Gap3b hook_blocking_error 携带 command (CC hooks.ts:710-715 blockingError.command)")
    void hookBlockingError_carriesCommand() {
        // WHY: CC hook_blocking_error attachment 内嵌 blockingError.command (hooks.ts:710-715);
        //      Java 端 4 参 hookBlockingError 版本丢弃 command → 载荷薄于 CC (对抗核验 Gap 3).
        GenericHook.HookResult result = HookOutputParser.processHookJSONOutput(
            (HookJSONOutput.SyncHookOutput) HookOutputParser.validateHookJson(
                "{\"decision\":\"block\",\"reason\":\"no access\"}").json(),
            "check.sh", "PreToolUse:check.sh", "PreToolUse", null, null).result();

        AttachmentMessageDto msg = (AttachmentMessageDto) result.message();
        assertThat(msg).isNotNull();
        assertThat(msg.type()).isEqualTo("hook_blocking_error");
        assertThat(msg.command()).isEqualTo("check.sh");
    }

    // ─────────── Gap 2: expectedHookEvent 真实分发路径 (经 toHookResult / httpToHookResult) ───────────

    @Test
    @DisplayName("Gap2a command 分发委托层: expectedHookEvent 不匹配 → NON_BLOCKING_ERROR fail-loud (CC runHook catch :2698-2729)")
    void commandDispatch_expectedHookEventMismatch_nonBlockingError() {
        // WHY: 既有 Gap3a 直接调 processHookJSONOutput 断言 throw, 但"接线"测试应走真实分发委托层
        //      (CommandHookExecutor.toHookResult = executeConfiguredCommand 的委托). 必须把
        //      event.type().ccName() 灌入 expectedHookEvent, mismatch 时 catch → NON_BLOCKING_ERROR
        //      (对齐 CC runHook catch), 而非静默 proceed — 否则配置错误的 hook 事件名被无感吞掉.
        CommandHookExecutor.CommandHookResult execResult =
            new CommandHookExecutor.CommandHookResult(
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                "", "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                0, false, false);
        CommandHook hook = new CommandHook("check.sh", null, null, null, null, null, null, null);

        GenericHook.HookResult result =
            CommandHookExecutor.toHookResult(execResult, hook, "UserPromptSubmit", 5L);

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) result.message()).type()).isEqualTo("hook_non_blocking_error");
    }

    @Test
    @DisplayName("Gap2b command 分发委托层: expectedHookEvent 匹配 → 正常接受不误伤 (CC hooks.ts:583-590)")
    void commandDispatch_expectedHookEventMatch_accepted() {
        // WHY: expectedHookEvent == hookSpecificOutput.hookEventName 时校验通过, 不得误伤正常 hook —
        //      否则每个合法 hook 都会被当成事件名错误降级.
        CommandHookExecutor.CommandHookResult execResult =
            new CommandHookExecutor.CommandHookResult(
                "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"ctx\"}}",
                "", "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"ctx\"}}",
                0, false, false);

        GenericHook.HookResult result = CommandHookExecutor.toHookResult(
            execResult, new CommandHook("check.sh", null, null, null, null, null, null, null),
            "UserPromptSubmit", 5L);

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.additionalContexts()).containsExactly("ctx");
    }

    @Test
    @DisplayName("Gap2c http 分发委托层: expectedHookEvent 不匹配 → NON_BLOCKING_ERROR fail-loud (CC :2413-2440)")
    void httpDispatch_expectedHookEventMismatch_nonBlockingError() {
        // WHY: httpToHookResult (executeConfiguredHttp 的委托层) 走真实分发 — body 解释 +
        //      event 名校验. mismatch 必须 fail-loud (NON_BLOCKING_ERROR), 不能静默接受错误事件名.
        ExecHttpHook.HttpHookResult httpResult =
            new ExecHttpHook.HttpHookResult(true, 200,
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                null, false);
        HttpHook hook = new HttpHook("http://localhost:8080/hook", null, null, null, null, null, null);

        GenericHook.HookResult result = HookRegistry.httpToHookResult(
            httpResult, hook, "config-http:http://localhost:8080/hook", "UserPromptSubmit", "toolu_1");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
    }

    // ─────────── H3 v4 残留缺口: Gap② httpToHookResult sync catch 补 attachment ───────────

    @Test
    @DisplayName("Gap② http sync 路径 catch: expectedHookEvent 不匹配 → hook_non_blocking_error attachment（CC :2715-2729）")
    void httpSyncCatch_producesHookNonBlockingErrorAttachment() {
        // WHY (对抗复验 PARTIAL 残留): httpToHookResult sync 分支 catch 此前返回
        //      NON_BLOCKING_ERROR 但 message=null — CC runHook catch (hooks.ts:2698-2729) 两条
        //      路径（exit-code 非 0 / JSON 解析 throw）都产 hook_non_blocking_error attachment.
        //      message=null 会让调用方（executeEvent 聚合层 / LLM 注入）拿不到 attachment.
        ExecHttpHook.HttpHookResult httpResult =
            new ExecHttpHook.HttpHookResult(true, 200,
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                null, false);
        HttpHook hook = new HttpHook("http://localhost:8080/hook", null, null, null, null, null, null);

        GenericHook.HookResult result = HookRegistry.httpToHookResult(
            httpResult, hook, "config-http:http://localhost:8080/hook", "UserPromptSubmit", "toolu_1");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        AttachmentMessageDto msg = (AttachmentMessageDto) result.message();
        assertThat(msg).as("http sync catch 必须产 hook_non_blocking_error attachment（CC :2715-2729）")
            .isNotNull();
        assertThat(msg.type()).isEqualTo("hook_non_blocking_error");
        assertThat(msg.stderr()).startsWith("Failed to run: ");
        assertThat(msg.stdout()).as("CC runHook catch stdout:''（hooks.ts:2726）").isEmpty();
        assertThat(msg.exitCode()).isEqualTo(1);
    }

    // ─────────── H3 v4 残留缺口: Gap③ parseStdoutJson catch stdout 对齐 CC stdout:'' ───────────

    @Test
    @DisplayName("Gap③ command catch: hook_non_blocking_error stdout 对齐 CC stdout:''（hooks.ts:2726）")
    void commandCatch_stdoutAlignedToCcEmpty() {
        // WHY (对抗复验 PARTIAL 残留): parseStdoutJson catch 的 stdout 字段用 result.stdout()
        //      (解析失败时的原始 stdout), CC runHook catch (hooks.ts:2726) 用 stdout:'' —
        //      对齐后载荷与 CC 一致（解析失败 ≠ 进程输出, 不该把原始 stdout 当 attachment.stdout）.
        CommandHookExecutor.CommandHookResult execResult =
            new CommandHookExecutor.CommandHookResult(
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                "", "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                0, false, false);
        CommandHook hook = new CommandHook("check.sh", null, null, null, null, null, null, null);

        GenericHook.HookResult result =
            CommandHookExecutor.toHookResult(execResult, hook, "UserPromptSubmit", 5L);

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        AttachmentMessageDto msg = (AttachmentMessageDto) result.message();
        assertThat(msg.type()).isEqualTo("hook_non_blocking_error");
        assertThat(msg.stdout())
            .as("command catch stdout 必须对齐 CC runHook catch stdout:''（hooks.ts:2726）")
            .isEmpty();
        assertThat(msg.stderr()).startsWith("Failed to run: ");
    }

    @Test
    @DisplayName("Gap2d executeEvent 全链路: command hook mismatch → fail-loud warn 日志 + 不抛穿 (CC runHook catch :2698-2729)")
    void executeEvent_commandHookMismatch_failsLoudlyViaWarnLog() {
        // WHY: 完整分发路径 (executeEvent → executeConfiguredCommand → toHookResult →
        //      event.type().ccName() 灌入 expectedHookEvent) 必须 fail-loud — mismatch 既不能
        //      静默 proceed (看不到问题), 也不能把异常抛穿 executeEvent (挂掉整批 hook). 日志
        //      是"接线正确"的观测点 (CC fail-loud 语义).
        Logger logger = (Logger) LoggerFactory.getLogger(CommandHookExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            HookRegistryDispatchTest.StubMatcherEngine engine =
                new HookRegistryDispatchTest.StubMatcherEngine();
            engine.setHooks(List.of(new MatchedHook(
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                null, null, null, "settings")));
            HookRegistryDispatchTest.FakeHookProcess proc =
                new HookRegistryDispatchTest.FakeHookProcess(
                    "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
                    "", 0);
            HookRegistryDispatchTest.FakeLauncher launcher =
                new HookRegistryDispatchTest.FakeLauncher(proc);
            HookRegistry registry = new HookRegistry();
            registry.setHookMatcherEngine(engine);
            registry.setCommandHookExecutor(
                new CommandHookExecutor(launcher, null, null, null, null));

            // 事件类型 UserPromptSubmit → ccName "UserPromptSubmit"; hook 返回 PostToolUse → mismatch
            GenericHook.HookResult result = registry.executeEvent(
                HookEvent.userPromptSubmit("sess-1", "agent-1"));

            assertThat(result).isNotNull(); // 不抛穿 executeEvent
            assertThat(appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("stdout JSON 处理失败") && m.contains("non_blocking_error")))
                .as("mismatch 必须 fail-loud: warn 日志记录 non_blocking_error (CC runHook catch)")
                .isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
