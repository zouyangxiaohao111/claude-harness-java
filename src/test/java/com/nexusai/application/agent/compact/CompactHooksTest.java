package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
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
