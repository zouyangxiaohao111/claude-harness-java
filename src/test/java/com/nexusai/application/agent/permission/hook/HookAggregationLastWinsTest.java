package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-07] 聚合归因 last-wins + 多 hook 消息不丢 + AHR.message AttachmentMessageDto 通道.
 *
 * <p>WHY (D3-1/D3-2/EV-L01-028 + D3-3/EV-003/019 + OD-14):
 * <ul>
 *   <li>CC 消费端 {@code hookPermissionResult = result.hookPermissionResult} 末次 yield 覆盖
 *       (toolExecution.ts:831-832); reason/source 随当前 result 配对 (hooks.ts:2862-2867).
 *       最小反例 A(allow,reasonA)+B(ask,reasonB) → CC {ask,reasonB} vs 旧 Java {ask,reasonA}.</li>
 *   <li>CC executeHooks 逐结果 yield message/additionalContexts (hooks.ts:2765-2767/2783-2790),
 *       消费端 resultingMessages.push 全保留 — Java 旧 first-non-null 丢第 2..N 个.</li>
 *   <li>OD-14: AHR.message 统一 AttachmentMessageDto 通道, 附件载荷 (stdout/stderr/exitCode/
 *       command/durationMs) 不截断.</li>
 * </ul>
 */
@DisplayName("[IMPL-07] 聚合归因 last-wins + 多 hook 消息不丢 + AHR.message 通道")
class HookAggregationLastWinsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── helpers ────────────────────────────────────────────────────────────

    private static AggregatedHookResult behaviorAhr(PermissionResult behavior, String reason,
                                                    String source, String messageContent) {
        return new AggregatedHookResult(
            messageContent != null
                ? AggregatedHookResult.messageChannel(messageContent, null, null, "PreToolUse")
                : null,
            null, false, null,
            reason, source, behavior, null, null, null, null, null, null, null, null, null);
    }

    private ToolUseContext ctx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT
        );
    }

    private JsonNode input() {
        return JSON.createObjectNode().put("k", "v");
    }

    private AggregatedHookResult runPreToolUse(HookRegistry registry) {
        return registry.executePreToolUse(
            "Bash", input(), ctx(), "tu-1");
    }

    /** [S4 G14] 完成序确定性延迟 · 让该 hook 稳定晚于同批其他 hook 完成. */
    private static void slow() {
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. last-wins 最小反例 (D3-1/CCJ-007)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: 最小反例 A(allow,reasonA)+B(ask,reasonB) → CC {ask,reasonB} (EV-L01-028).
     * 旧 Java first-non-null reason → {ask,reasonA} — reason 与最终 behavior 错位.
     */
    @Test
    @DisplayName("A(allow,reasonA)+B(ask,reasonB) → {ask,reasonB} (last-wins 配对)")
    void lastWins_reasonPairsWithLastBehaviorResult() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("A", (toolName, input, c) ->
            behaviorAhr(new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("A"), null, false, null, List.of()),
                "reasonA", "source-A", null));
        registry.registerPreToolUse("B", (toolName, input, c) -> {
            // [S4 G14] 完成序确定性: B 慢于 A → 完成序 A,B → B 的 reason last-wins 稳定
            //   (旧注册序断言在并行池下会因完成序翻转偶发失败)
            slow();
            return behaviorAhr(new PermissionResult.Ask("ask",
                new PermissionDecisionReason.Other("B"), List.of(), null, null, null, false, null, null),
                "reasonB", "source-B", null);
        });

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.permissionBehavior()).isInstanceOf(PermissionResult.Ask.class);
        assertThat(result.hookPermissionDecisionReason())
            .as("reason 应随末次 permissionBehavior result 配对 (last-wins, CCJ-007)")
            .isEqualTo("reasonB");
    }

    /**
     * WHY: CCJ-005 hookSource 归因 — 随产出 permissionBehavior 的 hook (末次 yield 生效),
     * 与 reason 同源配对.
     */
    @Test
    @DisplayName("hookSource 与 reason 同源配对 (last-wins, CCJ-005)")
    void lastWins_hookSourcePairsWithSameResultAsReason() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("A", (toolName, input, c) ->
            behaviorAhr(new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("A"), null, false, null, List.of()),
                "reasonA", "source-A", null));
        registry.registerPreToolUse("B", (toolName, input, c) -> {
            slow(); // [S4 G14] 完成序确定性: B 慢于 A → 完成序 A,B → source-B 稳定
            return behaviorAhr(new PermissionResult.Ask("ask",
                new PermissionDecisionReason.Other("B"), List.of(), null, null, null, false, null, null),
                "reasonB", "source-B", null);
        });

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.hookSource())
            .as("hookSource 应随末次 permissionBehavior result 配对 (last-wins, CCJ-005)")
            .isEqualTo("source-B");
        assertThat(result.hookSource())
            .as("hookSource 与 reason 同源 (来自同一 result)")
            .isNotEqualTo("source-A");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. 多 hook 消息不丢 (D3-3)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC 逐结果 yield message/additionalContexts → 消费端全 push (toolExecution.ts:815-829);
     * 旧 Java first-non-null 丢第 2 个 hook 的载荷.
     */
    @Test
    @DisplayName("2 hook 的 message 全保留 (第 2 个不丢)")
    void multiHook_messagesAllRetained() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("first", (toolName, input, c) ->
            behaviorAhr(null, null, null, "message-1"));
        registry.registerPreToolUse("second", (toolName, input, c) -> {
            slow(); // [S4 G14] 完成序确定性: second 慢于 first → concat 序 message-1,message-2 稳定
            return behaviorAhr(null, null, null, "message-2");
        });

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.message())
            .as("2 个 hook 的 message 全保留 (第 2 个不丢)")
            .extracting(AttachmentMessageDto::content)
            .containsExactly("message-1", "message-2");
    }

    /**
     * WHY: CC 逐结果 yield additionalContexts (hooks.ts:2783-2790) → 消费端全 push;
     * 旧 Java first-non-null 只留第 1 个列表.
     */
    @Test
    @DisplayName("2 hook 的 additionalContexts 全保留 (concat)")
    void multiHook_additionalContextsAllRetained() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("first", (toolName, input, c) -> new AggregatedHookResult(
            null, null, false, null, null, null, null,
            List.of("ctx-1a", "ctx-1b"), null, null, null, null, null, null, null, null));
        registry.registerPreToolUse("second", (toolName, input, c) -> {
            slow(); // [S4 G14] 完成序确定性: second 慢于 first → concat 序 ctx-1a,ctx-1b,ctx-2a 稳定
            return new AggregatedHookResult(
                null, null, false, null, null, null, null,
                List.of("ctx-2a"), null, null, null, null, null, null, null, null);
        });

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.additionalContexts())
            .as("2 个 hook 的 additionalContexts 全保留 (concat, 第 2 个不丢)")
            .containsExactly("ctx-1a", "ctx-1b", "ctx-2a");
    }

    /**
     * WHY: CC PreToolUse 逐 blockingError yield deny (toolHooks.ts:481-498), 消费端后到覆盖 →
     * 最后阻断生效 (与 reason last-wins 同哲学); 旧 Java first-wins 丢第 2 个阻断.
     */
    @Test
    @DisplayName("2 hook 的 blockingError — 末次阻断生效 (第 2 个不丢)")
    void multiHook_blockingErrorLastWins() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("first", (toolName, input, c) -> new AggregatedHookResult(
            null, new HookBlockingError("block-1", "first"), false, null,
            null, null, null, null, null, null, null, null, null, null, null, null));
        registry.registerPreToolUse("second", (toolName, input, c) -> {
            slow(); // [S4 G14] 完成序确定性: second 慢于 first → block-2 last-wins 稳定
            return new AggregatedHookResult(
                null, new HookBlockingError("block-2", "second"), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        });

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.blockingError())
            .as("blockingError 末次 yield 覆盖 (第 2 个不丢)")
            .isNotNull()
            .extracting(HookBlockingError::blockingError)
            .isEqualTo("block-2");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. AHR.message AttachmentMessageDto 通道 (OD-14)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: OD-14 (EV-011/Q-04) — 旧边界 instanceof String 截断附件载荷; 现在 AttachmentMessageDto
     * 原样透传, stdout/stderr/exitCode/command/durationMs 不截断.
     */
    @Test
    @DisplayName("AHR.message AttachmentMessageDto 载荷 (stdout/stderr/exitCode/command/durationMs) 不截断")
    void ahrMessage_attachmentPayloadNotTruncated() {
        AttachmentMessageDto att = new AttachmentMessageDto(
            null, "attachment", "hook_non_blocking_error",
            "visible content", null, null, null,
            "PreToolUse:Bash", "tu-1", "PreToolUse", null,
            "stderr-line", "stdout-line", 1,
            "echo hi", 1234L,
            null, null, 0, false, null, null, null, false, false, null, null, null, null, null);

        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("att", (toolName, input, c) -> new AggregatedHookResult(
            List.of(att), null, false, null, null, null, null,
            null, null, null, null, null, null, null, null, null));

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.message()).hasSize(1);
        AttachmentMessageDto passed = result.message().get(0);
        assertThat(passed.type()).isEqualTo("hook_non_blocking_error");
        assertThat(passed.stderr()).isEqualTo("stderr-line");
        assertThat(passed.stdout()).isEqualTo("stdout-line");
        assertThat(passed.exitCode()).isEqualTo(1);
        assertThat(passed.command()).isEqualTo("echo hi");
        assertThat(passed.durationMs()).isEqualTo(1234L);
    }

    /**
     * WHY: String 消息 (旧通道) 在转换边界包装为 hook_user_message DTO, 语义与旧消费端一致.
     */
    @Test
    @DisplayName("String message 经 messageChannel 包装为 hook_user_message DTO")
    void stringMessage_wrappedAsHookUserMessageDto() {
        List<AttachmentMessageDto> channel = AggregatedHookResult.messageChannel(
            "hello from hook", "PreToolUse:Bash", "tu-1", "PreToolUse");

        assertThat(channel).hasSize(1);
        assertThat(channel.get(0).type()).isEqualTo("hook_user_message");
        assertThat(channel.get(0).content()).isEqualTo("hello from hook");
        assertThat(channel.get(0).hookName()).isEqualTo("PreToolUse:Bash");
        assertThat(channel.get(0).toolUseID()).isEqualTo("tu-1");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. session + settings 同命令去重 (OD-11)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: OD-11 (EV-L01-007/008) — CC getAllHooks 单链合并 + hookDedupKey 同 '' 前缀折叠,
     * session last-wins; 旧 Java settings/session 分链 → 同命令双执行.
     */
    @Test
    @DisplayName("settings 与 session 同命令 hook 去重 → 仅执行 1 次 (session 胜出)")
    void sessionAndSettings_sameCommandDeduped() {
        AtomicInteger calls = new AtomicInteger();
        HookRegistry registry = new HookRegistry();
        registry.setCommandHookExecutor(new CommandHookExecutor() {
            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                             String jsonInput, String pluginRoot, String pluginId,
                                             String skillRoot, Integer hookIndex,
                                             boolean forceSyncExecution,
                                             com.nexusai.application.agent.tool.AbortController parentAbort) {
                calls.incrementAndGet();
                return new CommandHookResult("{\"decision\":\"allow\"}", "", "", 0, false, false);
            }
                @Override
                public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                 String jsonInput, String pluginRoot, String pluginId,
                                                 String skillRoot, Integer hookIndex,
                                                 boolean forceSyncExecution,
                                                 com.nexusai.application.agent.tool.AbortController parentAbort,
                                                 long defaultTimeoutMs, String hookCwd) {
                    // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
                    return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                        hookIndex, forceSyncExecution, parentAbort);
                }
        });
        // settings 快照: 同命令 "echo dedup-me"
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo dedup-me", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(new HookMatcherEngine(snapshot,
            new com.nexusai.application.agent.permission.source.PermissionRuleValueParser()));
        // session hook: 同命令同 matcher ('' 前缀 + payload 同键)
        registry.addSessionHook("sess-1", HookEventType.PRE_TOOL_USE, "Bash",
            new CommandHook("echo dedup-me", null, null, null, null, null, null, null),
            null, null);

        registry.executeEvent(HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", "agent-1"));

        assertThat(calls.get())
            .as("settings+session 同命令折叠为一条, 不双执行 (OD-11)")
            .isEqualTo(1);
    }

    /**
     * WHY: OD-SSE-05 (EV-SSE-030 / AU-24 △-SSE-08) — CC query.ts:1000-1002 postSampling
     * 载荷为 [...messagesForQuery, ...assistantMessages], hook 输入必须含本次采样输出;
     * 旧 Java 只传 query 输入消息. 本测试断言 postSamplingMessages 在末尾追加当前
     * assistant message (文本 + tool_calls 两种形态).
     */
    @Test
    @DisplayName("postSampling 载荷含本次采样输出 (assistant 消息在 hook 输入中)")
    void postSampling_payloadContainsCurrentAssistantMessage() {
        com.nexusai.model.session.dto.ChatMessageDto userMsg =
            new com.nexusai.model.session.dto.ChatMessageDto(
                null, null, com.nexusai.model.session.dto.Role.user,
                null, "prior user msg", null, null, null, null, null, null, null,
                null, null, null, java.util.List.of(), java.util.List.of());

        // 纯文本形态: 本次采样输出 = "hello from model"
        java.util.List<com.nexusai.model.session.dto.ChatMessageDto> textPayload =
            com.nexusai.application.agent.LlmAgentLoop.postSamplingMessages(
                java.util.List.of(userMsg),
                new com.nexusai.infra.llm.AssistantMessage("hello from model", "stop", java.util.List.of()),
                "turn-1");
        assertThat(textPayload).hasSize(2);
        assertThat(textPayload.get(1).role()).isEqualTo(com.nexusai.model.session.dto.Role.assistant);
        assertThat(textPayload.get(1).content())
            .as("postSampling 载荷含本次采样输出 (assistant 消息在 hook 输入中)")
            .isEqualTo("hello from model");
        assertThat(textPayload.get(0).content()).isEqualTo("prior user msg");

        // tool_calls 形态: 载荷含本次 assistant tool_use 块
        java.util.List<com.nexusai.model.session.dto.ChatMessageDto> toolPayload =
            com.nexusai.application.agent.LlmAgentLoop.postSamplingMessages(
                java.util.List.of(userMsg),
                new com.nexusai.infra.llm.AssistantMessage("",
                    "tool_calls",
                    java.util.List.of(new com.nexusai.application.agent.tool.ToolUseBlock(
                        "toolu_1", "Bash", JSON.createObjectNode().put("command", "ls")))),
                "turn-2");
        assertThat(toolPayload).hasSize(2);
        assertThat(toolPayload.get(1).role()).isEqualTo(com.nexusai.model.session.dto.Role.assistant);
        assertThat(toolPayload.get(1).toolCalls()).hasSize(1);
        assertThat(toolPayload.get(1).toolCalls().get(0).name()).isEqualTo("Bash");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. [EX-HOOK R2] stopReason last-wins
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 验证意图): CC PreToolUse 逐 hook preventContinuation 时 yield stopReason
     * (toolHooks.ts:503-507), 消费端 {@code stopReason = result.stopReason} 后到覆盖
     * (toolExecution.ts:831-832) — 多 hook 均 preventContinuation 且带 stopReason 时取
     * 最后一个。旧 Java first-wins（base 优先）→ 错取第一个 hook 的原因。
     */
    @Test
    @DisplayName("A(stopA)+B(stopB) 均 preventContinuation → stopReason=stopB (last-wins, EX-HOOK R2)")
    void multiHook_stopReasonLastWins() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("A", (toolName, input, c) ->
            new AggregatedHookResult(null, null, true, "stopA",
                null, null, null, null, null, null, null, null, null, null, null, null));
        registry.registerPreToolUse("B", (toolName, input, c) -> {
            slow(); // [S4 G14] 完成序确定性: B 慢于 A → stopB last-wins 稳定
            return new AggregatedHookResult(null, null, true, "stopB",
                null, null, null, null, null, null, null, null, null, null, null, null);
        });

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.preventContinuation()).isTrue();
        assertThat(result.stopReason())
            .as("多 hook 均 preventContinuation 且带 stopReason → 取最后一个 (CC toolHooks.ts:503-507 + toolExecution.ts:831-832)")
            .isEqualTo("stopB");
    }

    /**
     * WHY (规则九): CC 仅 preventContinuation 且带 stopReason 的 hook 才 yield stopReason
     * (toolHooks.ts:504-507); 后续 hook 无 stopReason 不 yield → 不覆盖前值。Java last-wins
     * 实现必须保留 next==null 时 base 值（不能因 next 无值而清空）。
     */
    @Test
    @DisplayName("B 仅 preventContinuation 无 stopReason → 保留 A 的 stopReason (EX-HOOK R2)")
    void multiHook_stopReasonKeptWhenNextHasNone() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("A", (toolName, input, c) ->
            new AggregatedHookResult(null, null, true, "stopA",
                null, null, null, null, null, null, null, null, null, null, null, null));
        registry.registerPreToolUse("B", (toolName, input, c) ->
            new AggregatedHookResult(null, null, true, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

        AggregatedHookResult result = runPreToolUse(registry);

        assertThat(result.preventContinuation()).isTrue();
        assertThat(result.stopReason())
            .as("后续 hook 无 stopReason 不 yield → 保留首个 stopReason (CC toolHooks.ts:504-507)")
            .isEqualTo("stopA");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. [S4 G14] 配置驱动 hook 完成序收集 (CC all() 逐完成序消费, generators.ts:56-71)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): CC executeHooks 用 all(hookPromises) (generators.ts:32-72) 按完成序
     * yield — 聚合/折叠消费的"首个/末个"随完成序 (hooks.ts:2744). Java 旧实现按注册序
     * futures.get(i).join() 收集 → 并发下聚合顺序偏离 CC. 本测试用两个不同延迟的假
     * CommandHookExecutor: A 先注册但慢 (150ms), B 后注册但快 (0ms) — 完成序 = B, A.
     * 断言: (a) executeEventAll 结果列表按完成序 (B 在前); (b) executeEvent 折叠的
     * firstBlockingError = 先完成者 (B) 的 blockingError (注册序会给 A).
     */
    @Test
    @DisplayName("G14 配置驱动 hook 结果按完成序收集 (慢 hook 先注册、快 hook 后注册 → 完成序快,慢)")
    void configuredHooks_completionOrderCollection() throws Exception {
        HookRegistry registry = new HookRegistry();
        registry.setCommandHookExecutor(new CommandHookExecutor() {
            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                             String jsonInput, String pluginRoot, String pluginId,
                                             String skillRoot, Integer hookIndex,
                                             boolean forceSyncExecution,
                                             com.nexusai.application.agent.tool.AbortController parentAbort) {
                if (hook.command().contains("slow")) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String id = hook.command().contains("slow") ? "slow" : "fast";
                // exit 2 → blocking (blockingError 文本携带 hook 身份)
                return new CommandHookResult("", "blocked-by-" + id, "blocked-by-" + id, 2, false, false);
            }
            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                             String jsonInput, String pluginRoot, String pluginId,
                                             String skillRoot, Integer hookIndex,
                                             boolean forceSyncExecution,
                                             com.nexusai.application.agent.tool.AbortController parentAbort,
                                             long defaultTimeoutMs, String hookCwd) {
                // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
                return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                    hookIndex, forceSyncExecution, parentAbort);
            }
        });
        // settings 快照: slow 先注册, fast 后注册
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("slow-hook", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("fast-hook", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(new HookMatcherEngine(snapshot,
            new com.nexusai.application.agent.permission.source.PermissionRuleValueParser()));

        HookEvent event = HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", "agent-1");

        // (a) executeEventAll 结果列表按完成序: fast 先完成 → 结果[0] 是 fast 的 blockingError
        List<GenericHook.HookResult> results = registry.executeEventAll(event);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).blockingError())
            .as("G14: 完成序收集 — 先完成者 (fast) 在结果首位 (旧注册序会给 slow)")
            .isNotNull()
            .extracting(HookBlockingError::blockingError)
            .isEqualTo("[fast-hook]: blocked-by-fast");

        // (b) executeEvent 折叠 firstBlockingError = 先完成者 (fast)
        GenericHook.HookResult folded = registry.executeEvent(event);
        assertThat(folded.blockingError())
            .as("G14: 折叠首个 blockingError 随完成序 (fast 先完成 → fast 的阻断生效)")
            .isNotNull()
            .extracting(HookBlockingError::blockingError)
            .isEqualTo("[fast-hook]: blocked-by-fast");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. [H-WF5a-02] 折叠链5项 + 5-W3-4 (executePostToolUse / Failure 全保留)
    // ════════════════════════════════════════════════════════════════════════

    /** [H-WF5a-02] 单 hook 结果构造 (message + 可选附加字段) · 仅测折叠, 不关心其余字段. */
    private static GenericHook.HookResult hookResult(Object message, java.util.List<String> systemMessages,
                                                     java.util.List<String> additionalContexts,
                                                     Object updatedMCPToolOutput, boolean preventContinuation) {
        return new GenericHook.HookResult(preventContinuation, null,
            systemMessages, additionalContexts, message,
            null, updatedMCPToolOutput, null, null,
            GenericHook.HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);
    }

    /**
     * WHY (5-W3-4): CC PostToolUse 逐 result yield message → 消费端全 push (toolHooks.ts:95-103);
     * 旧 Java firstMessage 只留第 1 条. executePostToolUse 改 List 收集后全保留.
     */
    @Test
    @DisplayName("5-W3-4: PostToolUse 2 hook message 全保留 (第 2 个不丢)")
    void postToolUse_multiHookMessagesAllRetained() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("first", (toolName, input, result, ctx, stopHookActive) ->
            hookResult("message-1", null, null, null, false));
        registry.registerPostToolUse("second", (toolName, input, result, ctx, stopHookActive) -> {
            slow(); // [S4 G14] 完成序确定性: second 慢于 first → 收集序稳定
            return hookResult("message-2", null, null, null, false);
        });

        GenericHook.HookResult outcome = registry.executePostToolUse(
            "Bash", input(), ToolResult.success("tu-1", "ok"), ctx(), false);

        assertThat(outcome.message())
            .as("5-W3-4: PostToolUse 2 hook message 全保留 (第 2 个不丢)")
            .isInstanceOf(java.util.List.class);
        assertThat(((java.util.List<Object>) outcome.message()))
            .containsExactly("message-1", "message-2");
    }

    /**
     * WHY (折叠链项2): CC PostToolUse 逐 result yield additionalContexts (hooks.ts:2782-2790)
     * → 消费端 N hook_additional_context 附件 (toolHooks.ts:132-143); 旧仅首条.
     */
    @Test
    @DisplayName("折叠链项2: PostToolUse 2 hook additionalContexts 全保留")
    void postToolUse_multiHookAdditionalContextsAllRetained() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("first", (toolName, input, result, ctx, stopHookActive) ->
            hookResult(null, null, java.util.List.of("ctx-1a", "ctx-1b"), null, false));
        registry.registerPostToolUse("second", (toolName, input, result, ctx, stopHookActive) -> {
            slow();
            return hookResult(null, null, java.util.List.of("ctx-2a"), null, false);
        });

        GenericHook.HookResult outcome = registry.executePostToolUse(
            "Bash", input(), ToolResult.success("tu-1", "ok"), ctx(), false);

        assertThat(outcome.additionalContexts())
            .as("折叠链项2: 2 hook additionalContexts 全保留 (concat)")
            .containsExactly("ctx-1a", "ctx-1b", "ctx-2a");
    }

    /**
     * WHY (折叠链项3): CC 逐 result yield systemMessage → N hook_system_message 附件
     * (hooks.ts:2769-2780); 旧 first-non-null 只留第 1 条.
     */
    @Test
    @DisplayName("折叠链项3: PostToolUse 2 hook systemMessages 全保留")
    void postToolUse_multiHookSystemMessagesAllRetained() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("first", (toolName, input, result, ctx, stopHookActive) ->
            hookResult(null, java.util.List.of("sys-1"), null, null, false));
        registry.registerPostToolUse("second", (toolName, input, result, ctx, stopHookActive) -> {
            slow();
            return hookResult(null, java.util.List.of("sys-2"), null, null, false);
        });

        GenericHook.HookResult outcome = registry.executePostToolUse(
            "Bash", input(), ToolResult.success("tu-1", "ok"), ctx(), false);

        assertThat(outcome.systemMessages())
            .as("折叠链项3: 2 hook systemMessages 全保留")
            .containsExactly("sys-1", "sys-2");
    }

    /**
     * WHY (折叠链项5): CC PostToolUse updatedMCPToolOutput last-wins (toolHooks.ts:145-151,
     * 后到覆盖); 旧 firstUpdatedOutput 首非空 → 错取第 1 个.
     */
    @Test
    @DisplayName("折叠链项5: updatedMCPToolOutput last-wins (后到覆盖)")
    void postToolUse_updatedMCPToolOutputLastWins() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("first", (toolName, input, result, ctx, stopHookActive) ->
            hookResult(null, null, null, "out-1", false));
        registry.registerPostToolUse("second", (toolName, input, result, ctx, stopHookActive) -> {
            slow(); // 完成序 first,second → second last-wins 稳定
            return hookResult(null, null, null, "out-2", false);
        });

        GenericHook.HookResult outcome = registry.executePostToolUse(
            "Bash", input(), ToolResult.success("tu-1", "ok"), ctx(), false);

        assertThat(outcome.updatedMCPToolOutput())
            .as("折叠链项5: 末个 hook 的 updatedMCPToolOutput 生效 (CC toolHooks.ts:147-148)")
            .isEqualTo("out-2");
    }

    /**
     * WHY (折叠链项4 + MERG-01 R1): CC PostToolUse 阻断 (preventContinuation) → 消费端 yield
     * hook_stopped_continuation 后 return (toolHooks.ts:118-130), 生成器被 abandon:
     * 该阻断 result 自身后续 yield 的 message/systemMessage/additionalContext 亦不消费
     * (executeHooks 对单 result 先 yield preventContinuation, hooks.ts:2748-2756),
     * 后续 result 更不消费. Java 对齐: 阻断 result 自身 message 不收集 + 早停 break.
     */
    @Test
    @DisplayName("折叠链项4: preventContinuation 早停 — 阻断 result 自身 message 亦不收集 (CC 生成器 abandon)")
    void postToolUse_preventContinuationEarlyStop() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("blocker", (toolName, input, result, ctx, stopHookActive) ->
            hookResult("block-message", null, null, null, true));
        registry.registerPostToolUse("late", (toolName, input, result, ctx, stopHookActive) ->
            hookResult("late-message", null, null, null, false));

        GenericHook.HookResult outcome = registry.executePostToolUse(
            "Bash", input(), ToolResult.success("tu-1", "ok"), ctx(), false);

        assertThat(outcome.preventContinuation()).isTrue();
        assertThat(outcome.message())
            .as("折叠链项4: 阻断 result 自身 message 不收集 (CC toolHooks.ts:129 return 使生成器 abandon), 后续 result 亦不消费")
            .isNull();
    }

    /**
     * WHY (5-W3-4 + 折叠链项1): PostToolUseFailure 失败链 message 同样全保留
     * (CC toolHooks.ts:224-243). 注意: 失败链调 onPostToolUseFailure (default 返回 proceed),
     * 须匿名类覆盖, 不能用 registerPostToolUse lambda (lambda 只实现 onPostToolUse).
     */
    @Test
    @DisplayName("5-W3-4: PostToolUseFailure 2 hook message 全保留")
    void postToolUseFailure_multiHookMessagesAllRetained() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("failure-1", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode in, ToolResult res,
                                                        ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode in,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                return hookResult("failure-1-msg", null, null, null, false);
            }
        });
        registry.registerPostToolUse("failure-2", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode in, ToolResult res,
                                                        ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode in,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                return hookResult("failure-2-msg", null, null, null, false);
            }
        });

        GenericHook.HookResult outcome = registry.executePostToolUseFailure(
            "Bash", input(), ToolResult.error("tu-1", "boom"), ctx(), false, false);

        assertThat(outcome.message())
            .as("5-W3-4: PostToolUseFailure 2 hook message 全保留 (第 2 个不丢)")
            .isInstanceOf(java.util.List.class);
        assertThat(((java.util.List<Object>) outcome.message()))
            .containsExactly("failure-1-msg", "failure-2-msg");
    }

    /**
     * WHY (MERG-01 R2): CC 失败链 runPostToolUseFailureHooks (toolHooks.ts:193-319) 无
     * preventContinuation 分支/无 return — 阻断 payload 落入无匹配分支, 循环持续, 后续失败 hook
     * 的 message/additionalContexts 全消费. Java 对齐: 失败链不早停 (原 :2260-2263 break 误引
     * 成功链 toolHooks.ts:129 return 语义, 已删). 本测试锁死"阻断 hook 与其后失败 hook 的
     * message 全收集", 若恢复早停 break 即变红.
     */
    @Test
    @DisplayName("MERG-01 R2: PostToolUseFailure 失败链 preventContinuation 不早停 — 阻断及后续 message 全收集")
    void postToolUseFailure_preventContinuation_doesNotEarlyStop() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("failure-blocker", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode in, ToolResult res,
                                                        ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode in,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                return hookResult("blocker-msg", null, null, null, true);
            }
        });
        registry.registerPostToolUse("failure-late", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode in, ToolResult res,
                                                        ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode in,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                return hookResult("late-msg", null, null, null, false);
            }
        });

        GenericHook.HookResult outcome = registry.executePostToolUseFailure(
            "Bash", input(), ToolResult.error("tu-1", "boom"), ctx(), false, false);

        assertThat(outcome.preventContinuation()).isTrue();
        assertThat(((java.util.List<Object>) outcome.message()))
            .as("MERG-01 R2: 失败链不早停, 阻断 hook 与其后失败 hook 的 message 全收集 (CC toolHooks.ts:193-319 无 return)")
            .containsExactly("blocker-msg", "late-msg");
    }
}
