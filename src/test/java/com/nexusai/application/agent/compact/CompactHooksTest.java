package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-A2-2 · OPD-CM5-A-07 · compact hooks 接 abort。
 *
 * <p><b>WHY</b>: 探查 CM-A2 △-3 确认 Java {@link CompactHooks} 的 pre/post 执行器不消费
 * {@code ctx.getAbortController()}，用户 Esc 中止压缩时 compact 的 command hook 仍会执行
 * （CC 在 executeHooksOutsideREPL 入口 {@code if (signal?.aborted) return []}
 * hooks.ts:3051-3053 早退，compact.ts:418 / :728 传 {@code context.abortController.signal}）。
 *
 * <p>本测试锁定：① 钩子执行器把 ctx.abortController 作为批级 signal 传给 HookRegistry
 * （"接收中止信号并传给压缩钩子"）；② 已取消 abort → 整批跳过（CC 入口早退）；③ 未取消
 * （NOOP 缺省）→ 聚合正常（无回归）。
 */
@DisplayName("[IMP-A2-2] CompactHooks 接 abort：PreCompact/PostCompact 透传 ctx.abortController → HookRegistry 入口早退")
class CompactHooksTest {

    /** 成功 hook 结果 · 复用 SessionStartHooksChannelCcTest 18 参构造模式。 */
    private static GenericHook.HookResult successResult(String message) {
        return new GenericHook.HookResult(
            false, null, List.of(), List.of(), message, null, null,
            null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
    }

    /**
     * [P1-6] 真实 command hook 形态的 success 结果 —— message 是 hook_success attachment
     * （CommandHookExecutor.toHookResultCore 产出：content=stdout.trim()，stdout 原文）。
     */
    private static GenericHook.HookResult commandSuccessResult(String stdoutText) {
        return new GenericHook.HookResult(
            false, null, List.of(), List.of(),
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookSuccess(
                "PreCompact:s1", null, "PreCompact", stdoutText, stdoutText, "", 0, "my-hook.sh", 12L),
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
    }

    /** [P1-6] 真实 command hook 形态的失败结果（非 0 退出 → hook_non_blocking_error，文本在 stderr）。 */
    private static GenericHook.HookResult commandFailureResult(String stderrText) {
        return new GenericHook.HookResult(
            false, null, List.of(), List.of(),
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookNonBlockingError(
                "PreCompact:s1", null, "PreCompact", stderrText, "", 3, "my-hook.sh", 12L),
            null, null, null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null,
            null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // [P1-6 · 2026-09-11] hook 输出 = hook 文本，不是 attachment 的 toString()
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-6: PreCompact 取 hook 的 stdout 文本（非 AttachmentMessageDto.toString 整串）")
    void preCompact_usesHookOutputNotDtoToString() {
        // WHY（规则九 · 验证意图）: CC 的 HookOutsideReplResult.output 是 hook 进程的文本输出
        //   （utils/hooks.ts:3476-3480 status===0 ? stdout : stderr），PreCompact 的
        //   newCustomInstructions / userDisplayMessage 全部派生自它（:4149-4184）。旧实现取
        //   message().toString() → 结构化 DTO 被当 hook 输出（DB 实证：content=AttachmentMessageDto[id=…,
        //   type=hook_success, stdout={"hookSpecificOutput":{… 整串进模型上下文）。
        HookRegistry registry = new HookRegistry();
        registry.register("test-pre-out", event -> commandSuccessResult("用中文总结这次压缩"),
            HookEventType.PRE_COMPACT);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1").setHookRegistry(registry);

        CompactHooks.PreCompactHookResult result =
            CompactHooks.executePreCompactHooks(ctx, "manual", null);

        assertThat(result.newCustomInstructions())
            .as("newCustomInstructions 必须是 hook 的输出文本（CC newCustomInstructions）")
            .isEqualTo("用中文总结这次压缩");
        assertThat(result.userDisplayMessage())
            .as("展示消息取输出文本（CC `PreCompact [cmd] completed successfully: ${output}`；"
                + "command 段对非 CommandHook 恒 '?' —— commandOf 既有语义）")
            .endsWith("completed successfully: 用中文总结这次压缩");
        assertThat(result.userDisplayMessage())
            .as("不得把 attachment 的 toString 当 hook 输出（P1-6 根因）")
            .doesNotContain("AttachmentMessageDto");
    }

    @Test
    @DisplayName("P1-6: PreCompact 失败 hook 取 stderr 文本（CC output = stderr）")
    void preCompact_failureUsesStderrText() {
        HookRegistry registry = new HookRegistry();
        registry.register("test-pre-fail", event -> commandFailureResult("脚本崩了"),
            HookEventType.PRE_COMPACT);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1").setHookRegistry(registry);

        CompactHooks.PreCompactHookResult result =
            CompactHooks.executePreCompactHooks(ctx, "manual", null);

        assertThat(result.newCustomInstructions())
            .as("失败 hook 的输出不进 newCustomInstructions（CC 只取 succeeded 的 output）")
            .isNull();
        assertThat(result.userDisplayMessage())
            .as("失败展示消息取 stderr 文本（CC `PreCompact [cmd] failed: ${stderr}`）")
            .endsWith("failed: 脚本崩了")
            .doesNotContain("AttachmentMessageDto");
    }

    @Test
    @DisplayName("P1-6: SessionStart hook 消息 content = hook 输出文本（不再落 attachment.toString）")
    void sessionStart_hookMessageContentIsHookOutput() {
        // WHY: 该路径产物进 postCompactMessages → 作为 user 消息进模型上下文 —— 旧实现把
        //   AttachmentMessageDto 的 toString 整串喂给模型（DB 实证每次 compact 3 条）。
        HookRegistry registry = new HookRegistry();
        registry.register("test-session-start", event -> commandSuccessResult("技能已刷新"),
            HookEventType.SESSION_START);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1").setHookRegistry(registry);

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages)
            .as("成功 hook 的非空输出产一条 hook 消息")
            .hasSize(1);
        assertThat(hookMessages.get(0).content())
            .as("消息 content 必须是 hook 输出文本（模型读到的内容）")
            .isEqualTo("技能已刷新");
        assertThat(hookMessages.get(0).content())
            .as("不得把结构化 attachment 的 toString 当消息内容（P1-6 根因）")
            .doesNotContain("AttachmentMessageDto");
    }

    @Test
    @DisplayName("PreCompact: 把 ctx.abortController 作为批级 signal 传给 HookRegistry（compact.ts:418）")
    void preCompactForwardsAbortControllerToRegistry() {
        AbortController abort = new AbortController();
        abort.abort("interrupt");
        AtomicReference<AbortController> received = new AtomicReference<>();
        HookRegistry registry = new HookRegistry() {
            @Override
            public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                                AbortController batchAbort) {
                received.set(batchAbort);
                return List.of();
            }
        };
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setHookRegistry(registry)
            .setAbortController(abort);

        CompactHooks.PreCompactHookResult result =
            CompactHooks.executePreCompactHooks(ctx, "manual", null);

        assertThat(received.get())
            .as("HookRegistry 入口必须收到 ctx.abortController（对齐 CC context.abortController.signal，compact.ts:418）")
            .isSameAs(abort);
        assertThat(result.newCustomInstructions()).isNull();
        assertThat(result.userDisplayMessage()).isNull();
    }

    @Test
    @DisplayName("PostCompact: 把 ctx.abortController 作为批级 signal 传给 HookRegistry（compact.ts:728）")
    void postCompactForwardsAbortControllerToRegistry() {
        AbortController abort = new AbortController();
        AtomicReference<AbortController> received = new AtomicReference<>();
        HookRegistry registry = new HookRegistry() {
            @Override
            public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                                AbortController batchAbort) {
                received.set(batchAbort);
                return List.of();
            }
        };
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setHookRegistry(registry)
            .setAbortController(abort);

        CompactHooks.PostCompactHookResult result =
            CompactHooks.executePostCompactHooks(ctx, "manual", "summary");

        assertThat(received.get())
            .as("HookRegistry 入口必须收到 ctx.abortController（对齐 CC context.abortController.signal，compact.ts:728）")
            .isSameAs(abort);
        assertThat(result.userDisplayMessage()).isNull();
    }

    @Test
    @DisplayName("入口早退: abortController 已取消 → PreCompact hooks 整批跳过（CC signal.aborted return []）")
    void preCompactHooksSkippedWhenAbortCancelled() {
        HookRegistry registry = new HookRegistry();
        registry.register("test-pre", event -> successResult("should-not-run"),
            HookEventType.PRE_COMPACT);

        // 对照: 未取消 abort → programmatic hook 正常执行
        List<GenericHook.HookResult> normal = registry.executeEventAll(
            HookEvent.preCompact("s1", "manual"), AbortController.NOOP);
        assertThat(normal)
            .as("未取消 abort → hook 应正常执行（对照组）")
            .isNotEmpty();

        // 已取消 abort → 入口早退返回空（对齐 CC executeHooksOutsideREPL if (signal?.aborted) return []）
        AbortController abort = new AbortController();
        abort.abort("interrupt");
        List<GenericHook.HookResult> skipped = registry.executeEventAll(
            HookEvent.preCompact("s1", "manual"), abort);
        assertThat(skipped)
            .as("abortController 已取消 → 整批跳过，registered hook 不执行")
            .isEmpty();
    }

    @Test
    @DisplayName("无 abort（NOOP 缺省）→ PreCompact 聚合正常，无回归")
    void preCompactAggregatesNormallyWithoutAbort() {
        HookRegistry registry = new HookRegistry() {
            @Override
            public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                                AbortController batchAbort) {
                return List.of(successResult("summary line"));
            }
        };
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setHookRegistry(registry);

        CompactHooks.PreCompactHookResult result =
            CompactHooks.executePreCompactHooks(ctx, "auto", "extra");

        assertThat(result.newCustomInstructions())
            .as("成功 hook 非空输出 join（对齐 CC executePreCompactHooks，无 abort 时行为不变）")
            .isEqualTo("summary line");
        assertThat(result.userDisplayMessage())
            .isEqualTo("PreCompact [?] completed successfully: summary line");
    }
}
