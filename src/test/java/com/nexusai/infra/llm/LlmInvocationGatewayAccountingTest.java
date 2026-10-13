package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.loop.ModelRequest;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 请求网关（{@link LlmInvocationGateway}）记账行为守护 · 流式路径。
 *
 * <h2>WHY（规则九：这条测试守的是哪个真实故障）</h2>
 * <p>用户诉求原话：「在 ModelCaller 中，尝试在流中断、取消、结束时实现对 token 用量统计」
 * —— 改造前 abort/失败路径<b>完全不计费</b>（provider 在 abort 分支跳过 onAssistantMessage /
 * onComplete，只发 onError），而这些调用上游可能已经计费（Anthropic 在 message_start 就返回了
 * input/cache 用量）。本测试守住三类此前会「凭空消失」的调用：
 * <ol>
 *   <li>正常完成（结算快照）；</li>
 *   <li>被取消（ABORTED 部分用量 —— 最容易被漏的一类）；</li>
 *   <li>provider 未上报 usage（记为 UNKNOWN 或 ESTIMATED，<b>绝不伪造</b>成上游值）。</li>
 * </ol>
 *
 * <h2>反向实验（哪个变异会让哪条断言变红）</h2>
 * <ul>
 *   <li>把 {@code LlmInvocationGateway.stream(...)} 的结算从 {@code finally} 挪到 try 尾部
 *       ⇒ {@code 取消/中断} 用例的 {@code captured} 为空 → 该用例红；</li>
 *   <li>删掉 {@code settleStream} 里 {@code snapshots.isEmpty() → 记 UNKNOWN} 的分支
 *       ⇒ {@code 未上报且无文本} 用例红（调用次数观测丢失）；</li>
 *   <li>把关账用的 {@code request.modelName()} 换成 {@code resolved.sdkModelName()}
 *       ⇒ {@code 正常完成} 用例的 modelName 断言红（计费键漂移，价格解析会落默认档）。</li>
 * </ul>
 */
@DisplayName("[usage-gateway] 请求网关记账：完成 / 取消 / 无上报 三类结局")
class LlmInvocationGatewayAccountingTest {

    /** 计费键：带 provider 前缀的调用方传入名（与 SDK 发送名不同，用于守住键不被顶替）。 */
    private static final String MODEL = "deepseek/deepseek-v4-flash";

    /** provider 假实现的「结局剧本」：scope 写快照 / 触发回调 / 抛异常。 */
    @FunctionalInterface
    private interface Behavior {
        void run(LlmCallScope scope, Consumer<AssistantMessage> onAssistantMessage) throws Exception;
    }

    /** 可编程假 provider（只实现接口的 3 个抽象方法，其余走 default）。 */
    private static final class FakeProvider implements LlmProvider {

        private final Behavior behavior;

        private FakeProvider(Behavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public void stream(ProviderConfig config, String modelName, List<SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools, Integer maxOutputTokensOverride,
                           TaskBudgetParam taskBudget, String effortValue, String querySource,
                           Consumer<String> onChunk, Consumer<AssistantMessage> onAssistantMessage,
                           Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                           LlmCallScope scope,
                           // [流空闲看门狗 · 合并] 末参跟随接口（本 fake 不消费；NONE 语义）
                           StreamIdleControl streamIdleControl) {
            try {
                behavior.run(scope, onAssistantMessage);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
            return "";
        }
    }

    /** 构造最小可用流式请求（归属固定，回调按用例注入）。 */
    private static ModelRequest request(Consumer<AssistantMessage> onAssistantMessage, LlmUsageRecorder recorder) {
        return new ModelRequest(
            new ProviderConfig("http://localhost", "test-key"),
            MODEL,
            null,                       // blocks（null = 不发送 system）
            "unit-test",                // querySource
            List.of(),                  // messages
            null,                       // tools
            null, null, null, null,     // maxOutputTokensOverride / taskBudget / effortValue / thinkingConfig
            chunk -> { },               // onChunk
            onAssistantMessage,         // 网关会包装它以捕获末条消息（T3 估算用）
            toolCall -> { },            // onToolCallComplete
            reasoning -> { },           // onReasoningChunk
            () -> { },                  // onStreamingFallback
            err -> { },                 // onError
            () -> { },                  // onComplete
            null,                       // abortController
            null,                       // skipCacheWrite
            null,                       // agentContext
            new UsageAttribution("sess-gw-test", "sess-gw-test", UsageCategory.MAIN, null, 0, null),
            recorder,
            // [流空闲看门狗 · 合并] 本测试无看门狗（null = 网关按 NONE 处理）
            null);
    }

    private static LlmProviderFactory factoryReturning(LlmProvider provider) {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    @Nested
    @DisplayName("正常完成：结算快照落账，计费键 = 调用方传入的模型名")
    class Settled {

        @Test
        @DisplayName("provider 上报 usage → 一条 OK 记录，token 来源为上游，modelName 未被 SDK 名顶替")
        void settledRecordsUpstreamUsage() {
            // WHY: 会话金额的原始输入就是这个记录；模型名若被剥前缀后的 SDK 名顶替，价格解析会落默认档
            // WHEN: provider 以 SETTLED 写入 (1000 in / 200 out)，网关结算
            List<LlmCallRecord> captured = new ArrayList<>();
            FakeProvider provider = new FakeProvider((scope, onAssistantMessage) ->
                LlmCallScope.recordSettled(scope, new AgentUsage(1000, 200, 3L, 40L, null, null, null)));

            LlmInvocationGateway.stream(factoryReturning(provider), null, request(null, captured::add));

            assertThat(captured).as("正常完成必须产生恰好一条记账记录").hasSize(1);
            LlmCallRecord rec = captured.get(0);
            assertThat(rec.status()).as("状态短标签").isEqualTo("ok");
            assertThat(rec.phase()).as("尝试结局").isEqualTo(LlmUsagePhase.SETTLED);
            assertThat(rec.tokenSource()).as("上游上报值可对账").isEqualTo(UsageTokenSource.UPSTREAM);
            assertThat(rec.modelName()).as("计费键必须是调用方传入模型名（含 provider 前缀）").isEqualTo(MODEL);
            assertThat(rec.stream()).as("流式标志").isTrue();
            assertThat(rec.category()).as("归类维度透传").isEqualTo(UsageCategory.MAIN);
            assertThat(rec.usage().inputTokens()).isEqualTo(1000L);
            // AgentUsage 7 参便捷构造序：(input, output, cacheCreation, cacheRead, ...)
            assertThat(rec.usage().cacheCreationInputTokens()).as("cache 写入通道一并入账").isEqualTo(3L);
            assertThat(rec.usage().cacheReadInputTokens()).as("cache 读取通道一并入账").isEqualTo(40L);
        }
    }

    @Nested
    @DisplayName("取消/中断：provider 已累积的部分用量仍要入账")
    class Aborted {

        @Test
        @DisplayName("provider 报 ABORTED 快照 + 抛取消异常 → 仍有一条 aborted 记录，token 不丢")
        void abortedStillRecorded() {
            // WHY: 改造前 abort 分支只发 onError，用量彻底丢失（上游可能已计费）—— 本次改造的核心诉求
            // WHEN: provider 在中断前已累积 input，写入 ABORTED 快照后以取消异常终止
            List<LlmCallRecord> captured = new ArrayList<>();
            FakeProvider provider = new FakeProvider((scope, onAssistantMessage) -> {
                LlmCallScope.record(scope, new AgentUsage(4200, 30, null, 1500L, null, null, null),
                    UsageTokenSource.UPSTREAM, LlmUsagePhase.ABORTED);
                throw new CancellationException("stream aborted");
            });

            try {
                LlmInvocationGateway.stream(factoryReturning(provider), null, request(null, captured::add));
            } catch (CancellationException expected) {
                // 取消异常必须原样上抛（仓库铁律：取消异常不吞）
            }

            assertThat(captured).as("取消也必须留账（否则真实开销凭空消失）").hasSize(1);
            assertThat(captured.get(0).status()).as("状态应标 aborted").isEqualTo("aborted");
            assertThat(captured.get(0).phase()).isEqualTo(LlmUsagePhase.ABORTED);
            assertThat(captured.get(0).usage().inputTokens())
                .as("中断前的部分 input 用量必须保留").isEqualTo(4200L);
        }
    }

    @Nested
    @DisplayName("无上报：留调用事实但不伪造 token")
    class NoUsage {

        @Test
        @DisplayName("provider 未上报且无文本 → UNKNOWN / 0 token（仅观测调用次数与耗时）")
        void unknownWhenNothingReported() {
            // WHY: 「调用发生了」本身就是要观测的事实（开销与耗时），但不能凭空编 token
            List<LlmCallRecord> captured = new ArrayList<>();
            FakeProvider provider = new FakeProvider((scope, onAssistantMessage) -> { /* 不写 sink，不回调 */ });

            LlmInvocationGateway.stream(factoryReturning(provider), null, request(null, captured::add));

            assertThat(captured).as("未上报也要留一条记录（否则调用次数统计不到）").hasSize(1);
            assertThat(captured.get(0).tokenSource()).isEqualTo(UsageTokenSource.UNKNOWN);
            assertThat(captured.get(0).usage().totalTokens()).as("不伪造 token").isZero();
            assertThat(captured.get(0).elapsedMs()).as("耗时仍可用于开销观测").isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("provider 未上报但有文本 → 按 T3 口径估算 output 并标记 ESTIMATED（与上游值分列）")
        void estimatedWhenTextProducedButNoUsage() {
            // WHY: 保留改造前「T3 估算兜底」的可观测性，但必须标记为估算（不可当账单）
            // WHEN: provider 只回调 assistant 消息（模拟未解析到 usage 的路径），文本 8 字符
            List<LlmCallRecord> captured = new ArrayList<>();
            final String text = "abcdefgh";
            FakeProvider provider = new FakeProvider((scope, onAssistantMessage) ->
                onAssistantMessage.accept(new AssistantMessage(text, "stop", List.of(), "")));

            LlmInvocationGateway.stream(factoryReturning(provider), null, request(msg -> { }, captured::add));

            assertThat(captured).as("有文本产出即应留账").hasSize(1);
            assertThat(captured.get(0).tokenSource()).as("估算值必须与上游上报值分列")
                .isEqualTo(UsageTokenSource.ESTIMATED);
            assertThat(captured.get(0).usage().outputTokens()).as("T3 口径 8/4 = 2").isEqualTo(2L);
        }
    }
}
