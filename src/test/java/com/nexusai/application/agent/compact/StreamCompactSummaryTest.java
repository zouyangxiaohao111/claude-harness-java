package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * IMP2-13 · compact 批 1 聚焦单测：重试延迟（△-13）+ 流式 fallback 受限工具集（△-10）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>重试延迟（△-13）</b>：CC {@code getRetryDelay}（withRetry.ts:530-548）=
 *       {@code min(BASE_DELAY_MS=500 · 2^(attempt-1), 32000) + jitter(0..0.25·base)}；
 *       Java 旧实现 base=1000ms·2^(n-1) 无 jitter（探查 EV-01-12）→ 本测试断言对齐公式。
 *       RED teeth：把 base 改回 1000 或去掉 jitter → 断言失败。</li>
 *   <li><b>fallback 工具集（△-10）</b>：CC compact.ts:1265-1290 fallback 传
 *       {@code useToolSearch ? uniqBy([FileReadTool, ToolSearchTool, ...MCP], 'name') : [FileReadTool]}；
 *       Java 旧实现传 null（空工具集，探查 EV-01-12）→ 本测试断言受限工具集非 null 且含
 *       Read（canUseTool=deny 白名单只读语义保留）。RED teeth：改回传 null → tools 断言失败。</li>
 * </ol>
 */
class StreamCompactSummaryTest {

    private static final String SUMMARY_REQUEST = "请对会话做摘要";
    // ════════════════════════════════════════════════════════════════════
    // △-13 · 重试延迟（withRetry.ts:530-548）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-13: 重试基础延迟 = min(500·2^(attempt-1), 32000)（对齐 CC BASE_DELAY_MS=500，withRetry.ts:55/535-541）")
    void retryDelay_baseMatchesCcFormula() {
        assertThat(StreamCompactSummary.baseRetryDelayMs(1)).as("attempt 1 → 500ms").isEqualTo(500L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(2)).as("attempt 2 → 1000ms").isEqualTo(1000L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(3)).as("attempt 3 → 2000ms").isEqualTo(2000L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(4)).as("attempt 4 → 4000ms").isEqualTo(4000L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(6)).as("attempt 6 → 16000ms（500·2^5）").isEqualTo(16000L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(7)).as("attempt 7 → 32000ms（500·2^6 达 cap）").isEqualTo(32000L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(8)).as("attempt 8 → cap 32000ms").isEqualTo(32000L);
        assertThat(StreamCompactSummary.baseRetryDelayMs(10)).as("attempt 10 → cap 32000ms").isEqualTo(32000L);
    }

    @Test
    @DisplayName("△-13: 重试总延迟 = base + jitter(0..25%·base)（对齐 CC withRetry.ts:542-544 Math.random()·0.25·base）")
    void retryDelay_jitterWithinQuarterOfBase() {
        Random random = new Random(20260813L);
        for (int attempt = 1; attempt <= 6; attempt++) {
            long base = StreamCompactSummary.baseRetryDelayMs(attempt);
            long maxJitter = (long) Math.floor(0.25 * base);
            for (int i = 0; i < 50; i++) {
                long delay = StreamCompactSummary.retryDelayMs(attempt, random);
                assertThat(delay)
                    .as("attempt=%d 延迟必须在 [base, base+25%]（base=%d）", attempt, base)
                    .isBetween(base, base + maxJitter);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // △-10 · 流式 fallback 受限工具集（compact.ts:1265-1290）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-10: fallback 工具集纯函数 —— tool search 关 → 仅 [FileReadTool]（CC compact.ts:1290）")
    void fallbackTools_toolSearchDisabled_onlyFileReadTool() {
        List<Tool> available = List.of(
            fakeTool("Read", false), fakeTool("Bash", false), fakeTool("Write", false));
        ArrayNode tools = StreamCompactSummary.buildFallbackToolsArray(available, false);
        assertThat(tools).as("tool search 关闭时工具集不得为 null").isNotNull();
        assertThat(toolNames(tools))
            .as("tool search 关闭 → 仅 Read（CC [FileReadTool]）")
            .containsExactly("Read");
    }

    @Test
    @DisplayName("△-10: fallback 工具集纯函数 —— tool search 开 → uniqBy([FileReadTool, ToolSearchTool, ...MCP], name)（CC compact.ts:1281-1289）")
    void fallbackTools_toolSearchEnabled_addsToolSearchAndMcp() {
        List<Tool> available = List.of(
            fakeTool("Read", false), fakeTool("Bash", false),
            fakeTool("ToolSearch", false), fakeTool("mcp_web", true), fakeTool("mcp_db", true));
        ArrayNode tools = StreamCompactSummary.buildFallbackToolsArray(available, true);
        assertThat(tools).isNotNull();
        assertThat(toolNames(tools))
            .as("tool search 开 → Read + ToolSearch + MCP（按 name 去重）")
            .containsExactly("Read", "ToolSearch", "mcp_web", "mcp_db");
    }

    @Test
    @DisplayName("△-10: fallback 工具集纯函数 —— 无工具源 → null（canUseTool=deny 等价退化）")
    void fallbackTools_noToolSource_returnsNull() {
        assertThat(StreamCompactSummary.buildFallbackToolsArray(null, true)).isNull();
        assertThat(StreamCompactSummary.buildFallbackToolsArray(List.of(), false)).isNull();
        // 有工具但无 Read（异常面）→ null（不发送空/伪造工具集）
        assertThat(StreamCompactSummary.buildFallbackToolsArray(List.of(fakeTool("Bash", false)), true)).isNull();
    }

    @Test
    @DisplayName("△-10: 流式 fallback 实际发送受限工具集（非 null）且含 Read —— RED teeth：改回传 null 则红")
    void streamingFallback_sendsRestrictedToolsContainingRead() {
        // 捕获传给 provider 的 tools（fallback 路径经 streamOnce → provider.stream）
        ArrayNode[] capturedTools = new ArrayNode[1];
        LlmProvider fake = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                capturedTools[0] = t;
                oa.accept(new AssistantMessage("summary text", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };

        // 13 参构造：fork 槽位含工具源但 forkContextMessages 为空 → 跳过 fork → 落流式 fallback
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(fakeTool("Read", false)), "", new AbortController(), List.of());
        CacheSafeParams params = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), tuc, List.of());
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fake, () -> "model", ProviderConfig::empty,
            () -> params, null, null, null, false, true, false, null, null, null);

        // [IMP-CM-14 F02] streamCompactSummary 返回 SummaryResult（text + usage）
        CompactConversation.SummaryResult result = scs.streamCompactSummary(
            List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model", fake, ProviderConfig.empty());

        assertThat(result).as("fallback 摘要结果正常返回").isNotNull();
        assertThat(result.text()).as("fallback 摘要文本正常返回").isEqualTo("summary text");
        assertThat(result.usage()).as("压缩调用必带 usage（非 null，可零值 · CC getTokenUsage）").isNotNull();
        assertThat(capturedTools[0])
            .as("流式 fallback 必须发送受限工具集（CC compact.ts:1290 [FileReadTool]；旧实现传 null 空工具集）")
            .isNotNull();
        assertThat(toolNames(capturedTools[0]))
            .as("受限工具集必须含 Read（deny 白名单只读工具）")
            .contains("Read");
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-CM-14 F02 · usage 透传（compact.ts:630-645 getTokenUsage → metrics）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("F02: 流式 fallback 透传 provider 真实 usage → compactionUsage 非零（CC getTokenUsage；旧实现返回 String 丢 usage）")
    void streamingFallback_passesThroughRealUsage() {
        // 3P provider 返回真实 usage（4 token 字段，对齐 AnthropicSdkProvider message_start/delta 解析）
        LlmProvider fake = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                oa.accept(new AssistantMessage("summary text", "stop", List.of(), "", null,
                    new com.nexusai.application.agent.tool.AgentUsage(
                        1000L, 500L, 300L, 200L, null, null, null)));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fake, () -> "model", ProviderConfig::empty,
            null, null, null, null, false, true, false, null, null, null);

        CompactConversation.SummaryResult result = scs.streamCompactSummary(
            List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model", fake, ProviderConfig.empty());

        // WHY: CC compact.ts:645 compactionUsage = getTokenUsage(summaryResponse) 读 API 响应
        //   usage 供 metrics/遥测消费。Java 旧实现 summarize 返回 String 丢 usage，生产 adapter
        //   恒 new SummaryResult(text, null) → compactionInputTokens/postCompactTokenCount 恒 null/0。
        //   若将来有人把 usage 重新丢弃（改回 String），本断言 RED。
        assertThat(result).as("摘要结果必含 usage").isNotNull();
        assertThat(result.usage()).as("压缩调用必须透传 provider 真实 usage").isNotNull();
        assertThat(result.usage().inputTokens()).as("input_tokens 透传").isEqualTo(1000);
        assertThat(result.usage().outputTokens()).as("output_tokens 透传").isEqualTo(500);
        assertThat(result.usage().cacheReadInputTokens()).as("cache_read_input_tokens 透传").isEqualTo(200);
        assertThat(result.usage().cacheCreationInputTokens()).as("cache_creation_input_tokens 透传").isEqualTo(300);
        assertThat(CompactConversation.tokenCountFromLastAPIResponse(result))
            .as("postCompactTokenCount = usage.total()（生产不再恒 0）").isEqualTo(1000 + 500 + 200 + 300);
    }

    @Test
    @DisplayName("F02: usage 映射纯函数 —— AssistantMessage 无 usage（EMPTY）→ TokenUsage 零值非 null（CC getTokenUsage 对真实响应恒返回）")
    void toTokenUsage_emptyAssistantMessage_zeroNonNull() {
        AssistantMessage empty = new AssistantMessage("summary text", "stop", List.of());
        CompactConversation.TokenUsage usage = StreamCompactSummary.toTokenUsage(empty);
        assertThat(usage).as("真实 assistant 响应的 usage 映射非 null（CC getTokenUsage 不返回 undefined）").isNotNull();
        assertThat(usage.total()).as("无上报 → 零值").isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-A3-2 · SCS-15/17 结构化遥测（compact.ts:1214/1235/1242/1364/1379）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SCS-17: fork 缓存共享成功 → tengu_compact_cache_sharing_success（CC compact.ts:1214-1227 全字段含 cacheHitRate）")
    void forkCacheSharing_success_emitsCacheSharingSuccessEvent() {
        Telemetry telemetry = Mockito.mock(Telemetry.class);
        StreamCompactSummary.setTelemetry(telemetry);
        try {
            ChatMessageDto assistant = assistantMessage("fork summary");
            // [A 命中率口径] 3 参带 providerType='anthropic'：cacheHitRate 保持 CC/Anthropic
            //   三字段分母语义（read/(input+read+create)）；2 参重载 providerType=null →
            //   isAnthropic()=false 走 read/input，本用例会 RED（防公式被误改）。
            ForkedAgentResult result = new ForkedAgentResult(
                List.of(assistant), new ForkedAgentResult.ForkUsage(1000, 500, 200, 300), "anthropic");
            RunForkedAgent.ForkedQuery query = params -> result;
            ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                Map.of(), List.of(fakeTool("Read", false)), "", new AbortController(), List.of());
            CacheSafeParams params = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), tuc,
                List.of(userMessage("forkctx1", "fork ctx")));
            StreamCompactSummary scs = new StreamCompactSummary(
                () -> (LlmProvider) null, () -> "model", ProviderConfig::empty,
                () -> params, null, null, null, false, true, false, null, null, null);
            scs.setForkedQuery(query);

            CompactConversation.SummaryResult summary = scs.streamCompactSummary(
                List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model",
                null, ProviderConfig.empty());

            // WHY: CC compact.ts:1214-1227 在 fork 成功时发射结构化成功事件（含 cacheHitRate 计算）；
            //   Java 旧实现仅 log.info，无事件 → fork 缓存共享成功路径不可观测（△-A3-4）。
            //   RED teeth：删掉 success 事件或改 cacheHitRate 公式 → 断言失败。
            assertThat(summary).as("fork 成功摘要返回").isNotNull();
            assertThat(summary.text()).as("fork 摘要文本").isEqualTo("fork summary");
            Mockito.verify(telemetry).recordEvent(
                Mockito.eq("tengu_compact_cache_sharing_success"),
                Mockito.argThat(attrs -> {
                    Object hit = attrs.get("cacheHitRate");
                    return attrs.get("outputTokens").equals(500L)
                        && attrs.get("cacheReadInputTokens").equals(200L)
                        && attrs.get("cacheCreationInputTokens").equals(300L)
                        && attrs.get("preCompactTokenCount").equals(0)
                        && hit instanceof Double d
                        && Math.abs(d - 200.0 / (200 + 300 + 1000)) < 1e-9;
                }));
            Mockito.verify(telemetry).logOTelEvent(
                Mockito.eq("tengu_compact_cache_sharing_success"), Mockito.anyMap());
        } finally {
            StreamCompactSummary.setTelemetry(null);
        }
    }

    @Test
    @DisplayName("A: fork 缓存共享成功 + 非 anthropic（openai_sdk/deepseek）→ cacheHitRate = cache_read/input（input 已含 cache hit）")
    void forkCacheSharing_success_deepseek_cacheHitRateReadOverInput() {
        // WHY: deepseek（openai 协议）input_tokens 已含 cache hit（input==H+M）；旧恒三字段分母
        //   read/(input+read+create) 会给出 900/(1000+900+100)=0.45 —— 恒为真实一半。修复后按
        //   provider 分派：providerType='openai_sdk' → isAnthropic()=false → read/input = 0.9。
        //   RED teeth：若公式回到恒三字段分母或 provider 分派丢失 → 0.45 → 断言失败。
        Telemetry telemetry = Mockito.mock(Telemetry.class);
        StreamCompactSummary.setTelemetry(telemetry);
        try {
            ChatMessageDto assistant = assistantMessage("fork summary");
            ForkedAgentResult result = new ForkedAgentResult(
                List.of(assistant), new ForkedAgentResult.ForkUsage(1000, 500, 900, 100), "openai_sdk");
            RunForkedAgent.ForkedQuery query = params -> result;
            ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                Map.of(), List.of(fakeTool("Read", false)), "", new AbortController(), List.of());
            CacheSafeParams params = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), tuc,
                List.of(userMessage("forkctx1", "fork ctx")));
            StreamCompactSummary scs = new StreamCompactSummary(
                () -> (LlmProvider) null, () -> "model", ProviderConfig::empty,
                () -> params, null, null, null, false, true, false, null, null, null);
            scs.setForkedQuery(query);

            CompactConversation.SummaryResult summary = scs.streamCompactSummary(
                List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model",
                null, ProviderConfig.empty());

            assertThat(summary).as("fork 成功摘要返回").isNotNull();
            Mockito.verify(telemetry).recordEvent(
                Mockito.eq("tengu_compact_cache_sharing_success"),
                Mockito.argThat(attrs -> {
                    Object hit = attrs.get("cacheHitRate");
                    return hit instanceof Double d
                        && Math.abs(d - 0.9) < 1e-9;
                }));
        } finally {
            StreamCompactSummary.setTelemetry(null);
        }
    }

    @Test
    @DisplayName("SCS-17: fork 无有效文本 → tengu_compact_cache_sharing_fallback reason=no_text_response（CC compact.ts:1235-1239）")
    void forkCacheSharing_noText_emitsFallbackNoTextResponse() {
        Telemetry telemetry = Mockito.mock(Telemetry.class);
        StreamCompactSummary.setTelemetry(telemetry);
        try {
            // fork 返回空消息（无 assistant）→ 无文本 → fallback 事件 + 落流式 fallback
            ForkedAgentResult result = new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());
            RunForkedAgent.ForkedQuery query = params -> result;
            ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                Map.of(), List.of(fakeTool("Read", false)), "", new AbortController(), List.of());
            CacheSafeParams params = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), tuc,
                List.of(userMessage("forkctx1", "fork ctx")));
            // 流式 fallback provider：返回有效摘要，验证 fork fallback 后流程仍成功完成
            LlmProvider fake = new LlmProvider() {
                @Override public String type() { return "test"; }
                @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                             List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                             TaskBudgetParam tb, String ev, String qs,
                                             Consumer<String> oc, Consumer<AssistantMessage> oa,
                                             Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                             Runnable osf, AbortController ac,
                                             Consumer<Throwable> oe, Runnable ocp) {
                    oa.accept(new AssistantMessage("fallback summary", "stop", List.of()));
                    ocp.run();
                }
                @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                    return "fallback summary";
                }
            };
            StreamCompactSummary scs = new StreamCompactSummary(
                () -> fake, () -> "model", ProviderConfig::empty,
                () -> params, null, null, null, false, true, false, null, null, null);
            scs.setForkedQuery(query);

            CompactConversation.SummaryResult summary = scs.streamCompactSummary(
                List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model",
                fake, ProviderConfig.empty());

            // WHY: CC compact.ts:1235-1239 无文本/API 错误时发射 reason='no_text_response' fallback 事件；
            //   Java 旧实现仅 log.warn（△-A3-4）。
            assertThat(summary).as("fork fallback 后流式成功完成").isNotNull();
            assertThat(summary.text()).isEqualTo("fallback summary");
            Mockito.verify(telemetry).recordEvent(
                Mockito.eq("tengu_compact_cache_sharing_fallback"),
                Mockito.argThat(attrs ->
                    "no_text_response".equals(attrs.get("reason"))
                        && attrs.get("preCompactTokenCount").equals(0)));
        } finally {
            StreamCompactSummary.setTelemetry(null);
        }
    }

    @Test
    @DisplayName("SCS-17: fork 异常 → tengu_compact_cache_sharing_fallback reason=error（CC compact.ts:1242-1246）")
    void forkCacheSharing_error_emitsFallbackError() {
        Telemetry telemetry = Mockito.mock(Telemetry.class);
        StreamCompactSummary.setTelemetry(telemetry);
        try {
            RunForkedAgent.ForkedQuery query = params -> {
                throw new RuntimeException("fork boom");
            };
            ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                Map.of(), List.of(fakeTool("Read", false)), "", new AbortController(), List.of());
            CacheSafeParams params = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), tuc,
                List.of(userMessage("forkctx1", "fork ctx")));
            LlmProvider fake = new LlmProvider() {
                @Override public String type() { return "test"; }
                @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                             List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                             TaskBudgetParam tb, String ev, String qs,
                                             Consumer<String> oc, Consumer<AssistantMessage> oa,
                                             Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                             Runnable osf, AbortController ac,
                                             Consumer<Throwable> oe, Runnable ocp) {
                    oa.accept(new AssistantMessage("fallback summary", "stop", List.of()));
                    ocp.run();
                }
                @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                    return "fallback summary";
                }
            };
            StreamCompactSummary scs = new StreamCompactSummary(
                () -> fake, () -> "model", ProviderConfig::empty,
                () -> params, null, null, null, false, true, false, null, null, null);
            scs.setForkedQuery(query);

            CompactConversation.SummaryResult summary = scs.streamCompactSummary(
                List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model",
                fake, ProviderConfig.empty());

            // WHY: CC compact.ts:1242-1246 catch 分支发射 reason='error' fallback 事件；
            //   Java 旧实现仅 log.warn（△-A3-4）。
            assertThat(summary).as("fork 异常落流式 fallback 后成功完成").isNotNull();
            Mockito.verify(telemetry).recordEvent(
                Mockito.eq("tengu_compact_cache_sharing_fallback"),
                Mockito.argThat(attrs ->
                    "error".equals(attrs.get("reason"))
                        && attrs.get("preCompactTokenCount").equals(0)));
        } finally {
            StreamCompactSummary.setTelemetry(null);
        }
    }

    @Test
    @DisplayName("SCS-15: 流式 fallback 无响应重试 → tengu_compact_streaming_retry（CC compact.ts:1364-1368 attempt/preCompactTokenCount/hasStartedStreaming）")
    void streamingFallback_retry_emitsStreamingRetryEvent() {
        Telemetry telemetry = Mockito.mock(Telemetry.class);
        StreamCompactSummary.setTelemetry(telemetry);
        try {
            // 状态 provider：第 1 次无响应（触发重试），第 2 次返回有效摘要
            final int[] calls = {0};
            LlmProvider fake = new LlmProvider() {
                @Override public String type() { return "test"; }
                @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                             List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                             TaskBudgetParam tb, String ev, String qs,
                                             Consumer<String> oc, Consumer<AssistantMessage> oa,
                                             Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                             Runnable osf, AbortController ac,
                                             Consumer<Throwable> oe, Runnable ocp) {
                    if (calls[0]++ == 0) {
                        ocp.run(); // 无 assistant 消息 → 无响应
                    } else {
                        oa.accept(new AssistantMessage("retry summary", "stop", List.of()));
                        ocp.run();
                    }
                }
                @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                    return "retry summary";
                }
            };
            // retryEnabled=true → maxAttempts=2（CC tengu_compact_streaming_retry 默认 false，测试显式开）
            StreamCompactSummary scs = new StreamCompactSummary(
                () -> fake, () -> "model", ProviderConfig::empty,
                null, null, null, null, false, true, true, null, null, null);

            CompactConversation.SummaryResult summary = scs.streamCompactSummary(
                List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0, "model",
                fake, ProviderConfig.empty());

            // WHY: CC compact.ts:1364-1368 重试前发射结构化 retry 事件；Java 旧实现仅 log.warn（△-A3-3）。
            //   RED teeth：删掉 retry 事件 → 断言失败。
            assertThat(summary).as("第 2 次尝试成功返回").isNotNull();
            assertThat(summary.text()).isEqualTo("retry summary");
            Mockito.verify(telemetry).recordEvent(
                Mockito.eq("tengu_compact_streaming_retry"),
                Mockito.argThat(attrs ->
                    attrs.get("attempt").equals(1)
                        && attrs.get("preCompactTokenCount").equals(0)
                        && attrs.get("hasStartedStreaming").equals(false)));
        } finally {
            StreamCompactSummary.setTelemetry(null);
        }
    }

    @Test
    @DisplayName("SCS-15: 流式 fallback 全部尝试失败 → tengu_compact_failed（CC compact.ts:1379-1387 全字段 + hasStartedStreaming 真实值）")
    void streamingFallback_failed_emitsFailedEvent() {
        Telemetry telemetry = Mockito.mock(Telemetry.class);
        StreamCompactSummary.setTelemetry(telemetry);
        try {
            // provider 先发一个文本块（hasStartedStreaming=true 经 sink 回传）但无 assistant 消息 → 无响应
            LlmProvider fake = new LlmProvider() {
                @Override public String type() { return "test"; }
                @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                             List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                             TaskBudgetParam tb, String ev, String qs,
                                             Consumer<String> oc, Consumer<AssistantMessage> oa,
                                             Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                             Runnable osf, AbortController ac,
                                             Consumer<Throwable> oe, Runnable ocp) {
                    oc.accept("partial text"); // 首个文本块 → hasStartedStreaming=true
                    ocp.run();                 // 流结束但无 assistant → 无响应
                }
                @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                    return "";
                }
            };
            // retryEnabled=false → maxAttempts=1 → 单次失败直接抛错
            StreamCompactSummary scs = new StreamCompactSummary(
                () -> fake, () -> "model", ProviderConfig::empty,
                null, null, null, null, false, true, false, null, null, null);

            org.junit.jupiter.api.Assertions.assertThrows(
                StreamCompactSummary.StreamCompactSummaryException.class,
                () -> scs.streamCompactSummary(List.of(userMessage("c1", "ctx1")),
                    SUMMARY_REQUEST, 0, "model", fake, ProviderConfig.empty()));

            // WHY: CC compact.ts:1379-1387 全部尝试失败后发射 failed 事件（reason='no_streaming_response'
            //   + hasStartedStreaming/retryEnabled/attempts/promptCacheSharingEnabled）；Java 旧实现仅
            //   log.error 且 hasStartedStreaming 恒 false（△-A3-3）。RED teeth：删事件 / 恒 false → 红。
            Mockito.verify(telemetry).recordEvent(
                Mockito.eq("tengu_compact_failed"),
                Mockito.argThat(attrs ->
                    "no_streaming_response".equals(attrs.get("reason"))
                        && attrs.get("preCompactTokenCount").equals(0)
                        && attrs.get("hasStartedStreaming").equals(true)
                        && attrs.get("retryEnabled").equals(false)
                        && attrs.get("attempts").equals(1)
                        && attrs.get("promptCacheSharingEnabled").equals(true)));
        } finally {
            StreamCompactSummary.setTelemetry(null);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    /** fake Tool（name/isMcp 可控；isEnabled=true 否则 ToolRegistry.toOpenAiToolsArray 跳过）。 */
    private static Tool fakeTool(String name, boolean isMcp) {
        Tool tool = Mockito.mock(Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.isMcp()).thenReturn(isMcp);
        when(tool.isEnabled()).thenReturn(true);
        return tool;
    }

    /** 提取 OpenAI tools 数组中的工具名（保持顺序）。 */
    private static List<String> toolNames(ArrayNode tools) {
        Set<String> names = new LinkedHashSet<>();
        if (tools != null) {
            tools.forEach(t -> names.add(t.path("function").path("name").asText("")));
        }
        return new ArrayList<>(names);
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-15 · △-11 流式事件 / △-12 stripReinjected / △-15 300s 超时裁决
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-11: 首个文本块 → setStreamMode('responding')（CC compact.ts:1331-1337）")
    void streamOnce_switchesToRespondingOnFirstTextChunk() {
        List<SpinnerMode> modes = new ArrayList<>();
        LlmProvider fake = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                oc.accept("first chunk");
                oa.accept(new AssistantMessage("summary text", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fake, () -> "model", ProviderConfig::empty,
            null, null, null, null, false, true, false, null,
            modes::add, n -> { });

        AssistantMessage result = scs.streamOnce(fake, ProviderConfig.empty(), "model",
            List.of("sys"), false, List.of(userMessage("c1", "ctx1")),
            null, null, new AbortController());

        assertThat(result).isNotNull();
        assertThat(modes)
            .as("首个文本块必须切 responding（CC 首个 content_block_start type=text → setStreamMode('responding')；旧实现全程不切）")
            .contains(SpinnerMode.RESPONDING);
    }

    @Test
    @DisplayName("△-11: responseLength 累加 delta（CC setResponseLength(length => length + delta)；旧实现 last-chunk 覆盖）")
    void streamOnce_responseLengthAccumulatesAcrossChunks() {
        List<Integer> lengths = new ArrayList<>();
        LlmProvider fake = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                oc.accept("ab");
                oc.accept("cd");
                oc.accept("ef");
                oa.accept(new AssistantMessage("abcdef", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "abcdef";
            }
        };
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fake, () -> "model", ProviderConfig::empty,
            null, null, null, null, false, true, false, null, null,
            lengths::add);

        scs.streamOnce(fake, ProviderConfig.empty(), "model",
            List.of("sys"), false, List.of(userMessage("c1", "ctx1")),
            null, null, new AbortController());

        assertThat(lengths)
            .as("每个 text_delta 累加（CC length => length + delta；旧实现每次覆盖为 chunk.length）")
            .containsExactly(2, 4, 6);
    }

    @Test
    @DisplayName("△-12: stripReinjectedAttachments feature 关 → no-op（CC compact.ts:211-223 EXPERIMENTAL_SKILL_SEARCH 门）")
    void stripReinjectedAttachments_featureOff_noOp() {
        try {
            StreamCompactSummary.setFeatureFlags(
                com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
            // content 含类型字样（旧实现 content 子串匹配会误剥）→ feature 关时必须全部保留
            List<ChatMessageDto> messages = List.of(
                attachment("skill_listing", "内容包含 skill_listing 字样"),
                attachment(null, "内容包含 skill_discovery 字样"),
                userMessage("u1", "普通消息"));

            List<ChatMessageDto> stripped =
                StreamCompactSummary.stripReinjectedAttachments(messages);

            assertThat(stripped)
                .as("EXPERIMENTAL_SKILL_SEARCH 关 → 恒 no-op（CC feature() false 直接返回原消息；"
                    + "旧实现无条件剥 content 子串匹配附件，△-12）")
                .hasSize(3);
        } finally {
            StreamCompactSummary.setFeatureFlags(
                com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        }
    }

    @Test
    @DisplayName("△-12: stripReinjectedAttachments feature 开 → 精确 subtype 匹配剥离（CC attachment.type 精确匹配，非 content 子串）")
    void stripReinjectedAttachments_featureOn_exactTypeMatch() {
        try {
            StreamCompactSummary.setFeatureFlags(new com.nexusai.application.agent.loop.FeatureFlags(
                false, false, true, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, false)); // skillPrefetch=true
            List<ChatMessageDto> messages = List.of(
                attachment("skill_listing", "内容包含 skill_listing 字样"),
                attachment("skill_discovery", "内容包含 skill_discovery 字样"),
                attachment("invoked_skills", "已调用技能"),
                attachment(null, "内容包含 skill_discovery 字样但无 subtype"),
                userMessage("u1", "普通消息"));

            List<ChatMessageDto> stripped =
                StreamCompactSummary.stripReinjectedAttachments(messages);

            assertThat(stripped)
                .as("仅剥 type=skill_discovery|skill_listing 的 attachment（CC m.attachment.type 精确匹配；"
                    + "content 子串误剥修复，△-12）")
                .extracting(ChatMessageDto::subtype)
                .containsExactly("invoked_skills", null, null);
        } finally {
            StreamCompactSummary.setFeatureFlags(
                com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        }
    }

    @Test
    @DisplayName("△-15: 无 300s 硬超时 —— 慢 provider 流一直持续则等待到完成（CC 无硬超时，compact.ts 全程）")
    void streamOnce_waitsForSlowProvider_noHardTimeout() throws Exception {
        // 契约钉扎：CC 无 300s 级硬超时（靠 abortController + SDK 状态），
        // 流未结束则持续等待。provider 延迟 500ms 完成 → 必须成功返回（旧实现 300s 内
        // 也成功——本用例钉扎"等待到完成"契约；硬超时机制删除由 grep STREAM_AWAIT_TIMEOUT_MS
        // 0 命中 + 编译验证，见 progress §5）。
        LlmProvider slow = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                oa.accept(new AssistantMessage("slow summary", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "slow summary";
            }
        };
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> slow, () -> "model", ProviderConfig::empty,
            null, null, null, null, false, true, false, null, null, null);

        AssistantMessage result = scs.streamOnce(slow, ProviderConfig.empty(), "model",
            List.of("sys"), false, List.of(userMessage("c1", "ctx1")),
            null, null, new AbortController());

        assertThat(result)
            .as("流未结束则持续等待（CC 无硬超时；等待到 provider 完成返回）")
            .isNotNull();
        assertThat(result.content()).isEqualTo("slow summary");
    }

    /** 构造 author='attachment' 消息（subtype=CC attachment.type；content 可控）。 */
    private static ChatMessageDto attachment(String subtype, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "attachment",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, subtype);
    }

    /** 构造 user 消息（对齐 StreamCompactSummaryForkUserContextTest.userMessage 语义）。 */
    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** 构造 assistant 消息（SCS-17 fork 成功路径 · isApiErrorMessage 默认 false）。 */
    private static ChatMessageDto assistantMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
