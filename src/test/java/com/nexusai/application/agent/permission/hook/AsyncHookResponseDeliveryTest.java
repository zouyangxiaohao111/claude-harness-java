package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H10 · 对抗核验修复] async hook 响应在生产路径的交付环路.
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: 对抗核验发现 <b>checkForAsyncHookResponses 生产无消费方</b> —
 * {@link CommandHookExecutor} 把 async hook 注册进 {@link AsyncHookRegistry} pending 池后,
 * 生产链路（LLM loop）从不调用 {@code checkForAsyncHookResponses()} 消费响应:
 * <ul>
 *   <li>轮询注入机制已删除 (T3-⊕1, 决策 09#4 — CC 事件驱动无轮询) → 主动 drain 是唯一
 *       消费通道</li>
 *   <li>HookRegistry / LLM loop 必须经 {@code collectAsyncHookResponses} 委托消费 —
 *       否则响应静默丢失 (仅 debug 日志)</li>
 * </ul>
 *
 * <p>CC 真源: {@code attachments.ts:3465 getAsyncHookResponseAttachments} 在每次主线程 LLM 调用前
 * 调 {@code checkForAsyncHookResponses()} 并把响应转成 user message (systemMessage +
 * additionalContext, messages.ts:4026) 注入 LLM 上下文. 本测试锁定等价行为:
 * LLM loop 每轮 drain async hook 响应并注入 messagesForLlm.
 *
 * @since Session H10 对抗核验修复
 */
@DisplayName("[H10-fix] async hook 响应生产交付环路")
class AsyncHookResponseDeliveryTest {

    /** 内存 fake 进程 · status='completed', stdout 直接注入 (镜像 AsyncHookRegistryTest 模式). */
    static class FakeAsyncHookProcess implements PendingAsyncHook.AsyncHookProcess {
        final String stdout;
        FakeAsyncHookProcess(String stdout) { this.stdout = stdout; }
        @Override public String status() { return "completed"; }
        @Override public String stdout() { return stdout; }
        @Override public String stderr() { return ""; }
        @Override public void cleanup() {}
        @Override public void kill() {}
        @Override public int exitCode() { return 0; }
    }

    /** 装配生产链路: HookRegistry ← AsyncHookRegistry ← pending hook. */
    private AgentLoopContext wiredContext(HookRegistry hookRegistry) {
        return TestContexts.agentLoopContext(null, null, null, null, null, null, hookRegistry);
    }

    @Test
    @DisplayName("LLM loop drain async hook 响应 → systemMessage 注入 messagesForLlm (CC attachments.ts:3465)")
    void loop_consumesAsyncHookResponses_injectsSystemMessageIntoContext() {
        // WHY: 生产路径的响应交付环路 — async hook 完成后, 其 systemMessage 必须回到 LLM 上下文,
        //      否则 hook 想让模型看到的系统消息静默丢失 (生产无消费方缺口).
        HookEventBus eventBus = new HookEventBus();
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        registry.registerPendingAsyncHook("async_hook_1", "hook-1",
            new HookJSONOutput.AsyncHookOutput(true, null),
            "testHook", "SessionStart", "echo hi",
            new FakeAsyncHookProcess("{\"continue\":true,\"systemMessage\":\"async result delivered\"}\n"),
            null, null);
        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.setAsyncHookRegistry(registry);

        AgentLoopContext ctx = wiredContext(hookRegistry);
        AgentState state = new AgentState("system");
        List<ChatMessageDto> messagesForLlm = new ArrayList<>(state.messages());

        List<ChatMessageDto> result = AgentLoopContext.maybeInjectAsyncHookResponses(ctx, state, messagesForLlm);

        // [prompt-align CTX-04] CC wrapMessagesInSystemReminder（messages.ts:4043 → :3097-3100）：
        //   systemMessage 每条包 `<system-reminder>\n...\n</system-reminder>` 的 isMeta user 消息。
        assertThat(result).anySatisfy(m ->
            assertThat(m.content()).isEqualTo("<system-reminder>\nasync result delivered\n</system-reminder>"));
        // 响应已从 pending 池移除 (checkForAsyncHookResponses finalize + remove, 不重复交付)
        assertThat(registry.getPendingAsyncHooks()).isEmpty();
    }

    @Test
    @DisplayName("LLM loop drain async hook 响应 → hookSpecificOutput.additionalContext 注入 (CC messages.ts:4030)")
    void loop_consumesAsyncHookResponses_injectsAdditionalContext() {
        // WHY: CC 对 async_hook_response attachment 还提取 hookSpecificOutput.additionalContext
        //      作为第二条 user message (messages.ts:4030 'additionalContext' in ...) — 附加上下文
        //      与 systemMessage 走同一交付环路, 不能遗漏.
        HookEventBus eventBus = new HookEventBus();
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        // SessionStart hookSpecificOutput 含 additionalContext 字段
        registry.registerPendingAsyncHook("async_hook_2", "hook-2",
            new HookJSONOutput.AsyncHookOutput(true, null),
            "testHook", "SessionStart", "echo hi",
            new FakeAsyncHookProcess(
                "{\"continue\":true,\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"extra context\"}}\n"),
            null, null);
        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.setAsyncHookRegistry(registry);

        AgentLoopContext ctx = wiredContext(hookRegistry);
        AgentState state = new AgentState("system");
        List<ChatMessageDto> messagesForLlm = new ArrayList<>(state.messages());

        List<ChatMessageDto> result = AgentLoopContext.maybeInjectAsyncHookResponses(ctx, state, messagesForLlm);

        // [prompt-align CTX-04] additionalContext 每条包 `<system-reminder>\n...\n</system-reminder>`（CC :4030-4043）
        assertThat(result).anySatisfy(m ->
            assertThat(m.content()).isEqualTo("<system-reminder>\nextra context\n</system-reminder>"));
        assertThat(registry.getPendingAsyncHooks()).isEmpty();
    }

    @Test
    @DisplayName("无 pending hook → messagesForLlm 原样返回 (零行为变化)")
    void loop_noPendingHooks_returnsMessagesUnchanged() {
        // WHY: drain 是幂等消费 — 无响应时必须保持 messagesForLlm 原样, 不新增任何注入
        //      (否则每个无 async hook 的 turn 也会被污染).
        HookEventBus eventBus = new HookEventBus();
        AsyncHookRegistry registry = new AsyncHookRegistry(eventBus);
        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.setAsyncHookRegistry(registry);

        AgentLoopContext ctx = wiredContext(hookRegistry);
        AgentState state = new AgentState("system");
        state.appendMessage(new ChatMessageDto("u1", null, com.nexusai.model.session.dto.Role.user,
            "system", "base", null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of()));
        List<ChatMessageDto> messagesForLlm = new ArrayList<>(state.messages());

        List<ChatMessageDto> result = AgentLoopContext.maybeInjectAsyncHookResponses(ctx, state, messagesForLlm);

        assertThat(result).isEqualTo(messagesForLlm);
    }

    @Test
    @DisplayName("hookRegistry 未接线 (null) → messagesForLlm 原样返回 (不破坏老路径)")
    void loop_hookRegistryNull_returnsMessagesUnchanged() {
        // WHY: HookRegistry 未注入 AsyncHookRegistry (手动 new / 无 Spring) → drain 必须 no-op,
        //      不能抛 NPE 拖垮 LLM loop.
        AgentLoopContext ctx = wiredContext(null);
        AgentState state = new AgentState("system");
        List<ChatMessageDto> messagesForLlm = new ArrayList<>(state.messages());

        List<ChatMessageDto> result = AgentLoopContext.maybeInjectAsyncHookResponses(ctx, state, messagesForLlm);

        assertThat(result).isEqualTo(messagesForLlm);
    }
}
