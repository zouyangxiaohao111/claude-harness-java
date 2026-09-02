package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-14 · △-5 session_start hooks 附加通道（additionalContexts/watchPaths）·
 * 对齐 CC processSessionStartHooks（utils/sessionStart.ts:141-172）。
 *
 * <p><b>WHY</b>: CC 压缩后 SessionStart hooks 除 hook message 外还聚合
 * {@code additionalContexts}（非空 → 追加 {@code hook_additional_context} 附件消息，
 * sessionStart.ts:163-172，顺序在普通 hook message 之后）与 {@code watchPaths}
 * （非空 → {@code updateWatchPaths}，sessionStart.ts:158-160）。旧 Java 实现
 * （CompactHooks.processSessionStartHooks）只聚合 message，两个通道静默丢弃。
 *
 * <p>Java 映射：additionalContext 经 HookResult.additionalContext（H3 单值 String，
 * types/hooks.ts:269）收集为列表 → hook_additional_context 消息
 * （ChatMessageDto subtype='hook_additional_context'）；watchPaths 经
 * CompactConversationContext.sessionStartWatchPathsConsumer 出口交接线方
 * （生产接 FileChangedWatcher.updateWatchPaths）。
 */
class SessionStartHooksChannelCcTest {

    /** fake HookRegistry：override executeEventAll 返回预置 results。 */
    private static HookRegistry registryReturning(List<GenericHook.HookResult> results) {
        return new HookRegistry() {
            @Override
            public List<GenericHook.HookResult> executeEventAll(HookEvent event) {
                return results;
            }
        };
    }

    private static GenericHook.HookResult result(
            String message, String additionalContext, List<String> watchPaths) {
        // [H-WF5a-02 折叠链项2] additionalContext 单值 String → List 承载
        List<String> additionalContexts = additionalContext != null ? List.of(additionalContext) : null;
        return new GenericHook.HookResult(
            false, null, null, additionalContexts, message, null, null,
            null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, watchPaths, null, null);
    }

    @Test
    @DisplayName("△-5: 顺序 = 普通 hook message 先 → hook_additional_context 最后（sessionStart.ts:141-172）")
    void additionalContextMessageAppendedAfterHookMessages() {
        List<GenericHook.HookResult> results = List.of(
            result("hook message 1", "附加上下文1", List.of("/path/a")),
            result("hook message 2", null, null));
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(results));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        // CC 顺序断言：逐 result push message，最后 push hook_additional_context
        assertThat(hookMessages).hasSize(3);
        assertThat(hookMessages.get(0).content()).isEqualTo("hook message 1");
        assertThat(hookMessages.get(1).content()).isEqualTo("hook message 2");
        assertThat(hookMessages.get(2).subtype())
            .as("hook_additional_context 消息追加到 hookMessages 尾部（sessionStart.ts:163-172）")
            .isEqualTo("hook_additional_context");
        assertThat(hookMessages.get(2).content())
            .as("additionalContexts 聚合（H3 单值 String → 列表，join('\\n')）")
            .isEqualTo("附加上下文1");
    }

    @Test
    @DisplayName("△-5: watchPaths 经 ctx 出口交接（CC updateWatchPaths，sessionStart.ts:158-160）")
    void watchPathsForwardedToContextConsumer() {
        List<String> received = new ArrayList<>();
        List<GenericHook.HookResult> results = List.of(
            result("msg", "ctx", List.of("/path/a", "/path/b")),
            result("msg2", null, null));
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(results))
            .setSessionStartWatchPathsConsumer(paths -> {
                received.clear();
                received.addAll(paths);
            });

        CompactHooks.processSessionStartHooks(ctx);

        assertThat(received)
            .as("watchPaths 聚合（去重）→ ctx 出口 → 接线方（FileChangedWatcher.updateWatchPaths）")
            .containsExactly("/path/a", "/path/b");
    }

    @Test
    @DisplayName("△-5: 无 additionalContext/watchPaths 时行为不变（仅 hook message）")
    void noAdditionalChannelsKeepsExistingBehavior() {
        List<GenericHook.HookResult> results = List.of(
            result("only message", null, null));
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(results));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages).hasSize(1);
        assertThat(hookMessages.get(0).content()).isEqualTo("only message");
        assertThat(hookMessages.get(0).subtype()).isNull();
    }

    @Test
    @DisplayName("△-5: 失败结果不进消息链、不聚合附加通道（CC executeSessionStartHooks 成功语义）")
    void failedResultsSkipped() {
        GenericHook.HookResult failed = new GenericHook.HookResult(
            false, null, null, List.of("context-from-failed"), "failed message", null, null,
            null, null, GenericHook.HookOutcome.BLOCKING, "some reason", null, null, null,
            null, List.of("/path-fail"), null, null);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(List.of(failed)));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages).as("失败结果不产出 hook message / additional_context").isEmpty();
    }
}
