package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-23 · ⊕-7 fork 双实现收敛契约测试。
 *
 * <p><b>WHY 本测试存在（意图）</b>: CC 单一 {@code runForkedAgent}（forkedAgent.ts:489-626）
 * 承载全部 fork；Java 旧实现 StreamCompactSummary.tryForkCacheSharing 内联 fork
 * （buildForkRequest + streamOnce）与 fork/RunForkedAgent 双轨并存（01 ⊕-7）。本测试钉扎
 * 收敛后契约：
 * <ol>
 *   <li><b>委托参数（INV-7）</b>——fork 不设 maxOutputTokens / skipCacheWrite=true /
 *       maxTurns=1 / querySource=COMPACT / forkLabel='compact' / abortController 透传
 *       （compact.ts:1188-1200 + forkedAgent.ts:524 消息拼接）。</li>
 *   <li><b>userContext 前置保留（RES-② F1）</b>——forkMessages = [userContext meta,
 *       ...forkContextMessages, summaryRequest]（CC query.ts:660 prependUserContext；
 *       旧 buildForkRequest 语义迁移，不得丢失否则 fork 缓存前缀与主线程不一致）。</li>
 *   <li><b>结果提取（CC compact.ts:1201-1230）</b>——getLastAssistantMessage →
 *       getAssistantMessageText；无 assistant / 无文本 / isApiErrorMessage → 落流式 fallback；
 *       查询异常 → 落流式 fallback。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert 到内联 streamOnce 双轨（无 setForkedQuery seam）→ testCompile
 * 缺符号 RED；去掉 abort 透传 / maxTurns=1 / skipCacheWrite=true → 断言 RED；漏掉
 * userContext 前置 → 消息顺序断言 RED。
 */
@DisplayName("[IMP2-23 ⊕-7] fork 双实现收敛：tryForkCacheSharing 委托 RunForkedAgent")
class ForkConvergenceCcContractTest {

    private static final String SUMMARY_REQUEST = "请对会话做摘要";

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · 委托参数（INV-7 + compact.ts:1188-1200）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("委托参数: 不设 maxOutputTokens / skipCacheWrite=true / maxTurns=1 / querySource=COMPACT / abortController 透传（INV-7）")
    void delegation_params_inv7_invariantsPreserved() {
        AbortController abort = new AbortController();
        CacheSafeParams cs = cacheSafeParams(Map.of("claudeMd", "项目指令"),
            List.of(userMessage("c1", "ctx1"), userMessage("c2", "ctx2")));
        RecordingQuery recording = new RecordingQuery();

        StreamCompactSummary scs = new StreamCompactSummary(
            () -> providerReturning("fallback text"), () -> "model", ProviderConfig::empty,
            () -> cs, () -> abort, null, null, false, true, false, null, null, null);
        scs.setForkedQuery(recording);

        scs.streamCompactSummary(
            List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0, "model",
            providerReturning("fallback text"), ProviderConfig.empty());

        RunForkedAgent.ForkQueryParams q = recording.lastParams();
        // INV-7: fork 不设 maxOutputTokens（破坏 cache key · compact.ts:1181-1187）
        assertThat(q.maxOutputTokensOverride()).as("fork 路径不设 maxOutputTokens（INV-7）").isNull();
        // skipCacheWrite=true（fork 不写缓存 · compact.ts:1195）
        assertThat(q.skipCacheWrite()).as("fork 路径 skipCacheWrite=true").isTrue();
        // maxTurns=1（compact.ts:1194）
        assertThat(q.maxTurns()).as("compact fork maxTurns=1").isEqualTo(1);
        // querySource='compact'（compact.ts:1192）
        assertThat(q.querySource()).as("compact fork querySource=COMPACT").isEqualTo(QuerySource.COMPACT);
        // abortController 透传（compact.ts:1196-1199 overrides.abortController → 隔离上下文）
        assertThat(q.toolUseContext().abortController())
            .as("abortController 透传到 fork 隔离上下文（用户 Esc 可中止 fork）")
            .isSameAs(abort);
        // canUseTool=deny（compact.ts:1191 createCompactCanUseTool）
        assertThat(q.canUseTool()).as("compact fork canUseTool 必须注入").isNotNull();
        assertThat(q.canUseTool().canUse(null, null, q.toolUseContext(), "toolUse-1", null).decision())
            .as("compact fork canUseTool 恒 DENY（摘要模型不调工具 · compact.ts:1125-1133）")
            .isEqualTo(ToolPermissionGate.Decision.DENY);
    }

    @Test
    @DisplayName("消息拼接: forkMessages = [userContext meta, ...forkContextMessages, summaryRequest]（RES-② F1 前置保留）")
    void delegation_messages_prependsUserContextKeepsOrder() {
        CacheSafeParams cs = cacheSafeParams(
            Map.of("claudeMd", "项目指令", "currentDate", "Today's date is 2026-08-14."),
            List.of(userMessage("c1", "ctx1"), userMessage("c2", "ctx2")));
        RecordingQuery recording = new RecordingQuery();
        StreamCompactSummary scs = compactSummaryWith(cs, recording);

        scs.streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", providerReturning("fallback text"), ProviderConfig.empty());

        List<ChatMessageDto> messages = recording.lastParams().messages();
        // 前置后 = 1(userContext meta) + 2(forkCtx) + 1(summary) = 4 · CC query.ts:660
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).isMeta()).as("userContext 元消息必须 isMeta=true").isTrue();
        assertThat(messages.get(0).content()).startsWith("<system-reminder>");
        assertThat(messages.get(1).content()).isEqualTo("ctx1");
        assertThat(messages.get(2).content()).isEqualTo("ctx2");
        assertThat(messages.get(3).content()).as("summaryRequest 恒在末尾（forkedAgent.ts:524 + compact.ts:1189）")
            .isEqualTo(SUMMARY_REQUEST);
        // cache-safe 参数透传（systemPrompt 数组 / userContext）
        assertThat(recording.lastParams().systemPrompt()).containsExactly("main-system-prompt");
        assertThat(recording.lastParams().userContext()).containsEntry("claudeMd", "项目指令");
    }

    @Test
    @DisplayName("消息拼接: userContext 空 map → 不前置 meta（对齐 CC api.ts:457-459 空 context 不污染前缀）")
    void delegation_messages_emptyUserContext_noMeta() {
        CacheSafeParams cs = cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1")));
        RecordingQuery recording = new RecordingQuery();
        StreamCompactSummary scs = compactSummaryWith(cs, recording);

        scs.streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", providerReturning("fallback text"), ProviderConfig.empty());

        List<ChatMessageDto> messages = recording.lastParams().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("ctx1");
        assertThat(messages.get(1).content()).isEqualTo(SUMMARY_REQUEST);
        assertThat(messages.get(0).isMeta()).as("无 userContext 不得前置 meta 消息").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · 结果提取（CC compact.ts:1201-1230）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("成功: 最后 assistant 文本返回为摘要（CC getLastAssistantMessage → getAssistantMessageText）")
    void delegation_success_lastAssistantTextReturned() {
        RecordingQuery recording = new RecordingQuery();
        recording.respond(new ForkedAgentResult(
            List.of(userMessage("u0", "前置 user"), assistantMessage("summary text", false)),
            ForkedAgentResult.ForkUsage.empty()));

        CompactConversation.SummaryResult result =
            compactSummaryWith(cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1"))), recording)
            .streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
                "model", providerReturning("fallback text"), ProviderConfig.empty());

        assertThat(result).as("最后 assistant 文本 = 摘要文本（compact.ts:1201-1210）").isNotNull();
        assertThat(result.text()).as("最后 assistant 文本 = 摘要文本（compact.ts:1201-1210）").isEqualTo("summary text");
    }

    @Test
    @DisplayName("fallback: 无 assistant 消息 → 落流式 fallback（CC compact.ts:1231-1234 no_text_response）")
    void delegation_noAssistantMessage_fallsBackToStreaming() {
        RecordingQuery recording = new RecordingQuery();
        recording.respond(new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty()));

        CompactConversation.SummaryResult result =
            compactSummaryWith(cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1"))), recording)
            .streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
                "model", providerReturning("fallback text"), ProviderConfig.empty());

        assertThat(result).as("fork 无文本 → 流式 fallback 产出摘要").isNotNull();
        assertThat(result.text()).as("fork 无文本 → 流式 fallback 产出摘要").isEqualTo("fallback text");
    }

    @Test
    @DisplayName("fallback: assistant 为 API 错误消息（isApiErrorMessage=true）→ 落流式 fallback（CC compact.ts:1210 守卫）")
    void delegation_apiErrorMessage_fallsBackToStreaming() {
        RecordingQuery recording = new RecordingQuery();
        recording.respond(new ForkedAgentResult(
            List.of(assistantMessage("Request was aborted.", true)),
            ForkedAgentResult.ForkUsage.empty()));

        CompactConversation.SummaryResult result =
            compactSummaryWith(cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1"))), recording)
            .streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
                "model", providerReturning("fallback text"), ProviderConfig.empty());

        assertThat(result).as("abort 合成的 API 错误消息不得作为摘要成功返回（CC compact.ts:1205-1210）").isNotNull();
        assertThat(result.text())
            .as("abort 合成的 API 错误消息不得作为摘要成功返回（CC compact.ts:1205-1210）")
            .isEqualTo("fallback text");
    }

    @Test
    @DisplayName("fallback: assistant 文本带 API 错误前缀 → 落流式 fallback（旧内联语义保留）")
    void delegation_apiErrorPrefix_fallsBackToStreaming() {
        RecordingQuery recording = new RecordingQuery();
        recording.respond(new ForkedAgentResult(
            List.of(assistantMessage("API Error: 401 authentication_error", false)),
            ForkedAgentResult.ForkUsage.empty()));

        CompactConversation.SummaryResult result =
            compactSummaryWith(cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1"))), recording)
            .streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
                "model", providerReturning("fallback text"), ProviderConfig.empty());

        assertThat(result).as("API 错误前缀文本不得作为摘要成功返回").isNotNull();
        assertThat(result.text()).as("API 错误前缀文本不得作为摘要成功返回").isEqualTo("fallback text");
    }

    @Test
    @DisplayName("fallback: fork 查询抛异常 → 落流式 fallback（CC compact.ts:1240-1247 error 分支）")
    void delegation_queryException_fallsBackToStreaming() {
        RecordingQuery recording = new RecordingQuery();
        recording.respondWith(() -> {
            throw new IllegalStateException("fork loop failed");
        });

        CompactConversation.SummaryResult result =
            compactSummaryWith(cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1"))), recording)
            .streamCompactSummary(List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
                "model", providerReturning("fallback text"), ProviderConfig.empty());

        assertThat(result).as("fork 异常 → 流式 fallback（不抛给上层）").isNotNull();
        assertThat(result.text()).as("fork 异常 → 流式 fallback（不抛给上层）").isEqualTo("fallback text");
    }

    @Test
    @DisplayName("fallback: fork seam 未注入（setForkedQuery 未调用）→ 直落流式 fallback（fail-loud 日志）")
    void delegation_seamNotInjected_fallsBackToStreaming() {
        CacheSafeParams cs = cacheSafeParams(Map.of(), List.of(userMessage("c1", "ctx1")));
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> providerReturning("fallback text"), () -> "model", ProviderConfig::empty,
            () -> cs, null, null, null, false, true, false, null, null, null);
        // 未调用 setForkedQuery

        CompactConversation.SummaryResult result = scs.streamCompactSummary(
            List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", providerReturning("fallback text"), ProviderConfig.empty());

        assertThat(result).as("seam 未注入 → fork 路径不可用，必须落流式 fallback").isNotNull();
        assertThat(result.text())
            .as("seam 未注入 → fork 路径不可用，必须落流式 fallback")
            .isEqualTo("fallback text");
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    private static StreamCompactSummary compactSummaryWith(CacheSafeParams cs, RecordingQuery recording) {
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> providerReturning("fallback text"), () -> "model", ProviderConfig::empty,
            () -> cs, () -> new AbortController(), null, null, false, true, false, null, null, null);
        scs.setForkedQuery(recording);
        return scs;
    }

    private static CacheSafeParams cacheSafeParams(Map<String, String> userContext,
                                                   List<ChatMessageDto> forkCtx) {
        return new CacheSafeParams(
            List.of("main-system-prompt"), userContext, Map.of("os", "linux"),
            baseContext(), forkCtx, false);
    }

    /** 最小 ToolUseContext（8 参兼容构造器）。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    /** fake provider：任何调用返回固定文本（fallback 路径断言用）。 */
    private static LlmProvider providerReturning(String text) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                oa.accept(new AssistantMessage(text, "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return text;
            }
        };
    }

    /** 构造 assistant 消息（isApiErrorMessage 可控 · CC AssistantMessage 判别守卫）。 */
    private static ChatMessageDto assistantMessage(String content, boolean isApiErrorMessage) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null,
            isApiErrorMessage, null, null, null,
            null, null, null, false, false);
    }

    /** 构造 user 消息（对齐 RunForkedAgentTest.userMessage 语义）。 */
    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** 捕获最后一次 fork 调用参数的 fake ForkedQuery（对齐 RunForkedAgentTest.RecordingQuery 模式）。 */
    static final class RecordingQuery implements RunForkedAgent.ForkedQuery {
        private final AtomicReference<ForkedAgentResult> response = new AtomicReference<>();
        private volatile RunForkedAgent.ForkQueryParams last;
        private volatile java.util.function.Supplier<ForkedAgentResult> responder =
            () -> new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());

        void respond(ForkedAgentResult result) {
            responder = () -> result;
        }

        void respondWith(java.util.function.Supplier<ForkedAgentResult> supplier) {
            responder = supplier;
        }

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.last = params;
            return responder.get();
        }

        RunForkedAgent.ForkQueryParams lastParams() {
            return last;
        }
    }
}
