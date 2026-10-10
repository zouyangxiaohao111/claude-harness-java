package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.StreamIdleControl;
import com.nexusai.infra.llm.StreamIdleTimeoutError;
import com.nexusai.infra.llm.StreamIdleWatchdogSettings;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [流空闲看门狗 · T5] LlmAgentLoop 等待段 + stall 判决矩阵（对齐 CC 2.1.296 · 规格 §2.4）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：判决矩阵的每条分支错了都会以不同方式静默：
 * <ol>
 *   <li><b>nothing → 重试新流一次</b>（CC Bhr exe 223,026,053 {@code zF("stalls",1,...)}）：
 *       首块都没来多半是弱网/服务端抽风，重试一次可救回；若退化成直接终结 ⇒ 白丢一轮。</li>
 *   <li><b>OUTPUT → 保留部分 + 终局文案</b>（CC exe 223,160,318）：已吐完整块的流不重试、
 *       不降级，直接收尾为部分响应；文案必须是 CC 原文。</li>
 *   <li><b>PARTIAL_OUTPUT → 降级非流式</b>（CC {@code Kke} retryWithoutStreaming exe 223,028,125）：
 *       吐一半被卡 ⇒ 下一次模型调用必须带 {@code forceNonStreaming=true}（跳过流式）；
 *       若仍走流式 ⇒ 半截块与重试块在 state 里串味。</li>
 *   <li><b>开关关 → 不误触发</b>：慢但存活的流在阈值内不被打扰（否则 = 又造了一个误杀通道）。</li>
 * </ol>
 * 等待段触发路径：mock provider 在收到请求时把"读超时→stall"行为注册到
 * {@code control.watchdogController().onCancel}（真实 provider 由 okhttp read timeout 在
 * 读线程内完成，见 AnthropicSdkProvider.buildClient 换挡说明）。
 */
@DisplayName("[看门狗 T5] 循环等待段 + stall 判决矩阵（重试新流 / 终局文案 / 降级非流式 / 开关）")
class LlmAgentLoopStreamIdleWatchdogTest {

    @BeforeEach
    void enableWatchdogFast() {
        StreamIdleWatchdogSettings.setTestOverrides(true, 400L);   // 毫秒级注入（测试钩子绕过 300s 下限）
    }

    @AfterEach
    void resetWatchdog() {
        StreamIdleWatchdogSettings.resetTestOverrides();
    }

    // ══════════════════════ 1. nothing → 重试新流一次 ══════════════════════

    @Test
    @DisplayName("nothing（首块前静默）→ 重试新流一次 → 第二次正常完成")
    void nothingStall_retriesNewStreamOnce_thenCompletes() {
        AtomicInteger calls = new AtomicInteger();
        LlmProvider provider = watchdogProvider(inv -> {
            int n = calls.incrementAndGet();
            StreamIdleControl ctl = inv.getArgument(19);
            if (n == 1) {
                // 模拟 provider：空闲到点（循环 abort）后交付 stall（NOTHING）
                ctl.watchdogController().onCancel(ac -> inv.<Consumer<Throwable>>getArgument(15)
                    .accept(new StreamIdleTimeoutError(StreamIdleTimeoutError.Progress.NOTHING, false,
                        "Stream idle timeout - no chunks received")));
            } else {
                inv.<Consumer<String>>getArgument(9).accept("ok");
                inv.<Consumer<AssistantMessage>>getArgument(10)
                    .accept(new AssistantMessage("ok", "stop", List.of()));
                inv.<Runnable>getArgument(16).run();
            }
            return null;
        });
        AgentState state = runLoop(provider);

        assertThat(calls.get())
            .as("nothing + 预算可用 → 必须重试新流一次（CC stalls 预算 V6e=1）").isEqualTo(2);
        assertThat(state.exitReason())
            .as("重试成功后不得落 STREAM_TIMEOUT 终局")
            .isNotEqualTo(AgentState.ExitReason.STREAM_TIMEOUT);
        assertThat(state.lastError()).as("重试成功后无残留错误").isNull();
    }

    // ══════════════════════ 2. OUTPUT → 保留部分 + 终局文案 ══════════════════════

    @Test
    @DisplayName("OUTPUT（块完成且有输出）→ 不重试 → 终局 = CC 原文文案 + STREAM_TIMEOUT")
    void outputStall_finalizesWithCcMessage() {
        AtomicInteger calls = new AtomicInteger();
        LlmProvider provider = watchdogProvider(inv -> {
            int n = calls.incrementAndGet();
            StreamIdleControl ctl = inv.getArgument(19);
            inv.<Consumer<String>>getArgument(9).accept("partial answer");
            ctl.watchdogController().onCancel(ac -> inv.<Consumer<Throwable>>getArgument(15)
                .accept(new StreamIdleTimeoutError(StreamIdleTimeoutError.Progress.OUTPUT, false,
                    "Stream idle timeout - partial response received")));
            return null;
        });
        AgentState state = runLoop(provider);

        assertThat(calls.get()).as("OUTPUT 不重试（保留部分收尾）").isEqualTo(1);
        assertThat(state.lastError())
            .as("终局文案必须逐字对齐 CC exe 223,160,318")
            .isEqualTo("API Error: The response stopped arriving. The response above may be incomplete.");
        assertThat(state.exitReason()).isEqualTo(AgentState.ExitReason.STREAM_TIMEOUT);
    }

    // ══════════════════════ 3. PARTIAL_OUTPUT → 降级非流式 ══════════════════════

    @Test
    @DisplayName("PARTIAL_OUTPUT（吐一半被卡）→ 下一次调用 forceNonStreaming=true，且非流式产出全文必须落为 assistant 文本")
    void partialOutputStall_fallsBackToNonStreaming() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> secondForceFlag = new AtomicReference<>();
        LlmProvider provider = watchdogProvider(inv -> {
            int n = calls.incrementAndGet();
            StreamIdleControl ctl = inv.getArgument(19);
            if (n == 1) {
                // 真实形态：先吐一半（partial 前提），再静默 → 等待段到点 abort → onCancel 交付 stall
                inv.<Consumer<String>>getArgument(9).accept("half ");
                ctl.watchdogController().onCancel(ac -> inv.<Consumer<Throwable>>getArgument(15)
                    .accept(new StreamIdleTimeoutError(StreamIdleTimeoutError.Progress.PARTIAL_OUTPUT, false,
                        "Stream idle timeout - partial response received")));
            } else {
                secondForceFlag.set(ctl.forceNonStreaming());
                // ⚠️ 真实 provider 的非流式成功路径**不调 onChunk**（一次返回完整消息，见
                //   AnthropicSdkProvider.runNonStreamingWithRetries → onAssistantMessage）。
                //   本用例刻意不推 chunk —— 这正是 e2e 抓到的形态：old 实现按 acc 取值，
                //   此时 acc/chunkCount 恒空 → 空响应守卫把完整回复误杀为 NO_ASSISTANT_TEXT。
                inv.<Consumer<AssistantMessage>>getArgument(10)
                    .accept(new AssistantMessage("full answer", "stop", List.of()));
                inv.<Runnable>getArgument(16).run();
            }
            return null;
        });
        AgentState state = runLoop(provider);

        assertThat(calls.get()).as("PARTIAL_OUTPUT → 非流式降级 = 新一次模型调用").isEqualTo(2);
        assertThat(secondForceFlag.get())
            .as("第二次调用必须带 forceNonStreaming=true（CC retryWithoutStreaming exe 223,028,125）")
            .isTrue();
        assertThat(state.lastError()).as("降级成功 → 无残留错误").isNull();
        assertThat(state.exitReason()).as("不得落 NO_ASSISTANT_TEXT 误杀")
            .isNotEqualTo(AgentState.ExitReason.NO_ASSISTANT_TEXT);
        ChatMessageDto lastAssistant = null;
        for (ChatMessageDto m : state.rawMessages()) {
            if (m.role() == Role.assistant) {
                lastAssistant = m;
            }
        }
        assertThat(lastAssistant).as("降级成功后必须有 assistant 消息落 state").isNotNull();
        assertThat(lastAssistant.content())
            .as("非流式产出的全文必须成为 assistant 文本（无 onChunk 路径以 msg 全文补齐）——"
                + "旧实现按 acc 取值 ⇒ 完整回复被误杀丢弃（e2e stub 实测）")
            .isEqualTo("full answer");
    }

    // ══════════════════════ 4. 开关关 → 不误触发 ══════════════════════

    @Test
    @DisplayName("开关关：慢但存活的流（800ms > 400 阈值）不被干扰，正常完成")
    void disabled_slowButAliveStream_completesUntouched() {
        StreamIdleWatchdogSettings.setTestOverrides(false, 0L);
        LlmProvider provider = watchdogProvider(inv -> {
            try {
                Thread.sleep(800);   // 慢于（若开启的）400ms 阈值——证明"关=不打扰"
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            inv.<Consumer<String>>getArgument(9).accept("slow ok");
            inv.<Consumer<AssistantMessage>>getArgument(10)
                .accept(new AssistantMessage("slow ok", "stop", List.of()));
            inv.<Runnable>getArgument(16).run();
            return null;
        });
        AgentState state = runLoop(provider);

        assertThat(state.lastError()).as("看门狗关闭 → 慢流不得被误杀").isNull();
        assertThat(state.exitReason()).isNotEqualTo(AgentState.ExitReason.STREAM_TIMEOUT);
    }

    // ═══════════ 5. 空闲语义对照：总时长 > 阈值但间隔 < 阈值 → 不触发 ═══════════

    @Test
    @DisplayName("空闲语义对照：5 个 chunk 间隔 150ms（总时长 750ms > 阈值 400ms）→ 不触发、不误杀")
    void sustainedOutput_totalExceedsThresholdButIdleNeverDoes_completesUntouched() {
        LlmProvider provider = watchdogProvider(inv -> {
            StreamIdleControl ctl = inv.getArgument(19);
            // 若看门狗被误触发（abort），按 OUTPUT 态交付 stall —— 误杀将在 lastError 上可见。
            // （真实 provider 的误触发同样会经读超时/abort 竞争交付 stall，这里以显式注册等价。）
            ctl.watchdogController().onCancel(ac -> inv.<Consumer<Throwable>>getArgument(15)
                .accept(new StreamIdleTimeoutError(StreamIdleTimeoutError.Progress.OUTPUT, false,
                    "Stream idle timeout - partial response received")));
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(150);   // chunk 间隔 150ms < 阈值 400ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                inv.<Consumer<String>>getArgument(9).accept("chunk" + i);
            }
            inv.<Consumer<AssistantMessage>>getArgument(10)
                .accept(new AssistantMessage("chunk0..4", "stop", List.of()));
            inv.<Runnable>getArgument(16).run();
            return null;
        });
        AgentState state = runLoop(provider);

        assertThat(state.lastError())
            .as("总时长 750ms > 阈值 400ms，但每 chunk 间隔 150ms < 阈值 ⇒ 空闲看门狗不得触发。"
                + "本用例是『空闲语义 vs 固定总时长语义』的判别器：若等待段按『从调用开始计时』"
                + "（旧固定 300s deadline 语义），750ms > 400ms 必误杀 ⇒ 此处必红。")
            .isNull();
        assertThat(state.exitReason())
            .isNotEqualTo(AgentState.ExitReason.STREAM_TIMEOUT);
    }

    // ══════════════════════════════ 脚手架 ══════════════════════════════

    /** blocks 重载（20 参）doAnswer 挂桩：末参 index19 = StreamIdleControl。 */
    private static LlmProvider watchdogProvider(org.mockito.stubbing.Answer<Object> answer) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    private static AgentState runLoop(LlmProvider provider) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentState state = new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        ToolUseContext baseTuc = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8))
            .withAvailableTools(List.of(TestContexts.dummyTool("Bash")));

        com.nexusai.application.agent.loop.QueryParams callerParams = com.nexusai.application.agent.loop.QueryParams.forLoop(
            state.rawMessages(), null, baseTuc,
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(callerParams.deps().context(), callerParams, state),
            state, new java.util.ArrayList<>());
        return state;
    }
}
