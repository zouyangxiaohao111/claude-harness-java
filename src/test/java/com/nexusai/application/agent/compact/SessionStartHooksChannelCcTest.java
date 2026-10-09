package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-14 · △-5 session_start hooks 附加通道（additionalContexts/watchPaths）·
 * 对齐 CC processSessionStartHooks（utils/sessionStart.ts:141-172）。
 *
 * <p><b>WHY</b>: CC 压缩后 SessionStart hooks 聚合 {@code additionalContexts}（非空 →
 * 追加 {@code hook_additional_context} 附件消息，sessionStart.ts:163-172）与
 * {@code watchPaths}（非空 → {@code updateWatchPaths}，sessionStart.ts:158-160）。
 * 旧 Java 实现（CompactHooks.processSessionStartHooks）只聚合 message，两个通道静默丢弃。
 *
 * <p><b>[压缩回执外显修复] 逐 hook 输出按 CC 两条通道产出</b>：CC 在 sessionStart.ts:141-143
 * 推入的是 attachment 消息对象（非文本），最终由 attachment→message 转换渲染——
 * <b>JSON 型</b> hook_success content 恒空（hooks.ts:729 硬编码 {@code content: ''}，stdout 仅作
 * 记录载荷 :730）→ content==='' 返回 []（messages.ts:4106-4108）不进模型；UI 侧 hook_success
 * 空渲染（nullRenderingAttachments.ts:14-16）⇒ 两端不可见（真库 39 条
 * {@code {"hookSpecificOutput":…}} 脏消息即旧实现此路径外显，已消除）。
 * <b>非 JSON 型</b>（content=stdout.trim()，hooks.ts:2617-2644）→ 产 isMeta system-reminder 消息
 * （messages.ts:4109-4116；模型可见、UI 不可见），渲染与本仓
 * {@code LlmAgentLoop.appendPlainHookMessage} 同构。仅 hook_additional_context 聚合行既有形态不变。
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
    @DisplayName("[压缩回执外显修复] String 形态 message 不产出对话消息 —— 仅 additionalContext 聚合行进返回列表")
    void stringMessageProducesNoMessage_additionalContextOnly() {
        // WHY: 旧行为逐 result 产出裸文本消息（"hook message 1/2"）——真库实证 39 条 JSON 形态脏消息。
        //   String message 无 CC attachment 对应物（CC result.message 恒为 attachment 消息对象，
        //   hooks.ts:710-736 / :2628-2643）⇒ 不产消息；非空 content 的 attachment 由下方
        //   nonJsonHookSuccessProducesIsMetaMessageBeforeAdditionalContext 锁定。
        List<GenericHook.HookResult> results = List.of(
            result("hook message 1", "附加上下文1", List.of("/path/a")),
            result("hook message 2", null, null));
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(results));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages)
            .as("hook 进程输出（message/stdout 原文）不得成为任何消息 content")
            .noneMatch(m -> "hook message 1".equals(m.content()) || "hook message 2".equals(m.content()));
        assertThat(hookMessages)
            .as("逐 hook 回执不产出消息 → 仅 additionalContext 聚合行")
            .hasSize(1);
        assertThat(hookMessages.get(0).subtype())
            .as("hook_additional_context 聚合行保留（sessionStart.ts:163-172）")
            .isEqualTo("hook_additional_context");
        assertThat(hookMessages.get(0).content())
            .as("additionalContexts 聚合 + 包 <system-reminder>（对齐 §14 与 CC messages.ts:4117-4127）")
            .isEqualTo("<system-reminder>\nSessionStart hook additional context: 附加上下文1\n</system-reminder>");
        assertThat(hookMessages.get(0).isMeta())
            .as("hook_additional_context 恒 isMeta=true（系统注入·非用户输入，对齐 CC createUserMessage({isMeta:true})）")
            .isTrue();
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
    @DisplayName("[压缩回执外显修复] String 形态 message 且无 additionalContext/watchPaths → 返回空列表")
    void noAdditionalChannelsProducesNothing() {
        // WHY: 旧行为「仅 hook message」= String message 变裸文本消息（真库 39 条脏消息根因）；
        //   新行为：String 无 CC attachment 对应物（hooks.ts:710-736）⇒ 不产消息，
        //   无 additionalContext → 列表为空。
        List<GenericHook.HookResult> results = List.of(
            result("only message", null, null));
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(results));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages).as("SUCCESS hook 输出不产出任何消息").isEmpty();
    }

    @Test
    @DisplayName("[压缩回执外显修复] JSON hook stdout（真库实证 {\"hookSpecificOutput\":…} 形态）不产出对话消息")
    void hookStdoutJsonNeverBecomesMessage() {
        // WHY: 真库 39 条脏消息 = hook 进程 stdout 原文（JSON）整串落为 user 消息。
        //   JSON 路径 hook_success content 恒空、stdout 仅记录载荷（hooks.ts:729-730）→
        //   attachment→message content==='' → return []（messages.ts:4106-4108）⇒ stdout 永不成为消息。
        String jsonStdout =
            "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"x\"}}";
        GenericHook.HookResult jsonHook = new GenericHook.HookResult(
            false, null, null, null,
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookSuccess(
                "SessionStart:s1", null, "SessionStart", "", jsonStdout, "", 0, "my-hook.sh", 12L),
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(List.of(jsonHook)));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages)
            .as("hook 进程 stdout 原文（JSON）不得成为任何消息（含 content 子串）")
            .noneMatch(m -> m.content() != null && m.content().contains("hookSpecificOutput"));
        assertThat(hookMessages).as("SUCCESS hook 输出不产出任何消息").isEmpty();
    }

    @Test
    @DisplayName("[压缩回执外显修复·补全] 非 JSON 型 hook_success（content 非空）→ 产 isMeta 渲染消息，位于 hook_additional_context 之前")
    void nonJsonHookSuccessProducesIsMetaMessageBeforeAdditionalContext() {
        // WHY: CC 非 JSON 回落路径 hook_success content=result.stdout.trim()（hooks.ts:2617-2644）→
        //   messages.ts:4099-4116 content 非空 → createUserMessage(wrapInSystemReminder(
        //   "{hookName} hook success: {content}"), isMeta:true)（模型可见；UI 侧 hook_success
        //   仍空渲染 nullRenderingAttachments.ts:14-16）。顺序 = 逐 hook 消息在前（循环内）、
        //   hook_additional_context 聚合行最后（循环后，sessionStart.ts:163-172）。
        GenericHook.HookResult plain = new GenericHook.HookResult(
            false, null, null, List.of("附加上下文1"),
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookSuccess(
                "pr-hook", null, "SessionStart", "技能已刷新", "技能已刷新", "", 0, "my-hook.sh", 12L),
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(List.of(plain)));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages).hasSize(2);
        ChatMessageDto hookMsg = hookMessages.get(0);
        assertThat(hookMsg.content())
            .as("非 JSON 型回执 = wrapInSystemReminder(\"{hookName} hook success: {content}\")，非裸文本")
            .isEqualTo("<system-reminder>\npr-hook hook success: 技能已刷新\n</system-reminder>");
        assertThat(hookMsg.isMeta())
            .as("恒 isMeta=true（对齐 CC createUserMessage({isMeta:true})，messages.ts:4114）")
            .isTrue();
        assertThat(hookMsg.author()).isEqualTo("hook");
        assertThat(hookMsg.role()).isEqualTo(Role.user);
        assertThat(hookMsg.subtype()).isNull();
        assertThat(hookMessages.get(1).subtype())
            .as("hook_additional_context 聚合行位于逐 hook 消息之后（sessionStart.ts:163-172）")
            .isEqualTo("hook_additional_context");
    }

    @Test
    @DisplayName("[压缩回执外显修复·F1] hook_blocking_error 产 blocking 文案（无事件门；CC messages.ts:4090-4097）")
    void hookBlockingErrorProducesCcBlockingMessage() {
        // WHY（F1）：JSON 路径 {"decision":"block"}（HookOutputParser.java:302-311 → :463-465）产
        //   hook_blocking_error attachment（content=blockingError 文本非空）且 outcome 恒 SUCCESS
        //   （:480-482）——只判 instanceof + content 非空的旧判据会把它错套 "hook success" 前缀（与事实相反）。
        //   CC messages.ts:4090-4097：无事件门，文案 =
        //   `{hookName} hook blocking error from command: "{blockingError.command}": {blockingError.blockingError}`
        //   （wrapped + isMeta=true）。本 fixture hookEvent="PreCompact"（不在 hook_success 白名单）即锁"无事件门"。
        GenericHook.HookResult blocking = new GenericHook.HookResult(
            false, null, null, null,
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookBlockingError(
                "bs-hook", null, "PreCompact", "脚本崩了", "my-hook.sh"),
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(List.of(blocking)));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages).hasSize(1);
        ChatMessageDto msg = hookMessages.get(0);
        assertThat(msg.content())
            .as("hook_blocking_error 必须用 blocking 文案，⛔ 不得套 \"hook success\" 前缀（F1 根因）")
            .isEqualTo("<system-reminder>\nbs-hook hook blocking error from command: \"my-hook.sh\": 脚本崩了\n</system-reminder>");
        assertThat(msg.isMeta()).isTrue();
    }

    @Test
    @DisplayName("[压缩回执外显修复·F1] hook_success content 非空但 hookEvent 非白名单（PreCompact）→ 不产（CC 事件门 messages.ts:4100-4105）")
    void hookSuccessEventGateBlocksNonWhitelistedEvent() {
        GenericHook.HookResult notWhitelisted = new GenericHook.HookResult(
            false, null, null, null,
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookSuccess(
                "g-hook", null, "PreCompact", "some text", "some text", "", 0, "my-hook.sh", 12L),
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(List.of(notWhitelisted)));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        assertThat(hookMessages)
            .as("hookEvent 不在 {SessionStart, UserPromptSubmit} → 不产（CC messages.ts:4100-4105 事件门）")
            .isEmpty();
    }

    @Test
    @DisplayName("[session-start-cc-align P0-3] 多 result 均带 additionalContext → 恰 1 条 hook_additional_context（聚合单份）")
    void multipleResultsWithAdditionalContext_aggregateSingleHook() {
        // WHY: compact 通道必须单份——§14 的 DB 持久化 hook（P0-1）在 compact 时随旧 transcript 整体
        //      被替换（replaceSessionMessages delete-all + reinsert）；compact 自插的这一条若按 result 逐条
        //      追加会与"压缩后仅剩一份"冲突，需证明 processSessionStartHooks 把 N 个 result 的
        //      additionalContext 聚合进 <b>1</b> 条 hook_additional_context（CC sessionStart.ts:163-172
        //      也是把收集的 additionalContexts join 进单条附件消息）。压缩替换 transcript 后下 run resume
        //      恢复出含本条的既有历史 → §14 resumedFromDb 门控（B 级对齐 CC startup 边界）跳过、不重注入
        //      → 单份固定（防 P0-1 持久化 hook 与 compact 重插份并存；旧 anyMatch(subtype) 守卫已删，见
        //      LlmAgentLoop :2691 注释）。
        List<GenericHook.HookResult> results = List.of(
            result("hook message 1", "附加上下文1", List.of("/path/a")),
            result("hook message 2", "附加上下文2", null));
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId("s1")
            .setAgentId("main")
            .setModel("claude-sonnet-4-5")
            .setHookRegistry(registryReturning(results));

        List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

        List<ChatMessageDto> hooks = hookMessages.stream()
            .filter(m -> "hook_additional_context".equals(m.subtype()))
            .toList();
        assertThat(hooks)
            .as("N 个 result 的 additionalContext 必须聚合进恰 1 条 hook_additional_context（防两份并存）")
            .hasSize(1);
        assertThat(hooks.get(0).content())
            .as("两条 result 的 additionalContext 以 \\n join 且包 <system-reminder>（对齐 §14 与 CC messages.ts:4117-4127）")
            .isEqualTo("<system-reminder>\nSessionStart hook additional context: 附加上下文1\n附加上下文2\n</system-reminder>");
        assertThat(hooks.get(0).isMeta()).as("hook_additional_context 恒 isMeta=true").isTrue();
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
