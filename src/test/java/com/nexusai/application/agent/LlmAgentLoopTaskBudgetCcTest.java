package com.nexusai.application.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.TokenCounter;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-16] taskBudget 生产链路注入 + 结转测量源 finalContextTokensFromLastResponse + tengu_auto_compact_succeeded。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>结转公式（REQ-23 · query.ts:508-515/1138-1146）</b> — CC 压缩成功后
 *       {@code taskBudgetRemaining = max(0, (taskBudgetRemaining ?? taskBudget.total) - finalContextTokensFromLastResponse(messagesForQuery))}。
 *       Java 端若结转公式偏移（如漏 max(0)、用 total 而非 prev 基准、或减法源不是 last response usage），
 *       跨压缩预算倒数错误 → 本测试直接断言纯函数公式（RED tooth：revert 公式 → fail）。</li>
 *   <li><b>测量源对齐（DRIFT-12 · tokens.ts:79-112）</b> — 结转减法必须用
 *       {@code finalContextTokensFromLastResponse}（从后回扫最近带 usage 的消息，返回
 *       iterations[-1]/顶层 input+output，排除 cache），而非 beforeTurn 的 pipeline 本地估算；
 *       无 usage → 0（CC 同语义，结转不减）。</li>
 *   <li><b>生产链路注入 + 埋点（MISS-4/MISS-5 · OD-13）</b> — RunRequest 工厂不再恒置
 *       taskBudget=null；taskBudget.remaining 沿生产链路（RunRequest → QueryParams → queryLoop）
 *       注入，压缩成功路径 emit {@code tengu_auto_compact_succeeded}（query.ts:478）并执行结转
 *       （INV-11 非死计算）。</li>
 *   <li><b>[IMP-16 REWORK] provider 透传参数断言（验收 #1）</b> — 结转后的 task_budget
 *       {total, remaining} 必须经 ModelCaller 到达 provider.stream（请求体
 *       output_config.task_budget，claude.ts:479-500）。本测试捕获 17-arg stream 的 taskBudget
 *       参数并断言 total==注入 total、remaining==结转后值——revert provider 透传（ModelCaller 不传
 *       taskBudget）该断言即 fail（非仅日志断言）。</li>
 * </ol>
 */
class LlmAgentLoopTaskBudgetCcTest {

    private ListAppender<ILoggingEvent> llmLoopAppender;
    private Logger llmLoopLogger;

    @AfterEach
    void tearDown() {
        if (llmLoopLogger != null && llmLoopAppender != null) {
            llmLoopLogger.detachAppender(llmLoopAppender);
        }
    }

    private void attachLogAppender() {
        llmLoopLogger = (Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        llmLoopAppender = new ListAppender<>();
        llmLoopAppender.start();
        llmLoopLogger.addAppender(llmLoopAppender);
        llmLoopLogger.setLevel(Level.INFO);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 结转公式（REQ-23 · query.ts:508-515）——纯函数断言
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("结转: max(0,(prev??total)−measured) · prev=null → total 基准（query.ts:513 (taskBudgetRemaining ?? total)）")
    void carryover_prevNull_usesTotalAsBase() {
        assertThat(LlmAgentLoop.applyTaskBudgetCarryover(null, 200_000, 30_000))
            .as("prev=null → (null ?? total)=total=200000；200000−30000=170000")
            .isEqualTo(170_000);
        assertThat(LlmAgentLoop.applyTaskBudgetCarryover(null, 200_000, 0))
            .as("measured=0（无 usage）→ 不减（CC 语义），仍为 total")
            .isEqualTo(200_000);
    }

    @Test
    @DisplayName("结转: 有 prev 用 prev 基准；结果 floor 0（query.ts:511 Math.max(0,·)）")
    void carryover_withPrev_subtractsAndFloorsAtZero() {
        assertThat(LlmAgentLoop.applyTaskBudgetCarryover(50_000, 200_000, 30_000))
            .as("prev=50000 > measured=30000 → 20000")
            .isEqualTo(20_000);
        assertThat(LlmAgentLoop.applyTaskBudgetCarryover(10_000, 200_000, 30_000))
            .as("prev=10000 < measured=30000 → max(0,·)=0")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 结转测量源（DRIFT-12 · tokens.ts:79-112）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("测量源: 从后回扫最近带 usage 的 assistant 消息 → input+output（排除 cache，tokens.ts:107）")
    void measurement_walksBackToLastUsage() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(message("m1", Role.user, "q1", null, null));
        msgs.add(message("m2", Role.assistant, "a1", 100, 20));       // 120
        msgs.add(message("m3", Role.user, "q2", null, null));
        msgs.add(message("m4", Role.assistant, "a2", null, null));    // 无 usage → 跳过
        msgs.add(message("m5", Role.user, "q3", null, null));

        assertThat(LlmAgentLoop.finalContextTokensFromLastResponse(msgs))
            .as("回扫最近带 usage 的消息 m2 → 100+20=120")
            .isEqualTo(120);
    }

    @Test
    @DisplayName("测量源: 全部无 usage → 0（CC tokens.ts:111 同语义，结转不减）")
    void measurement_noUsage_returnsZero() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(message("m1", Role.user, "q1", null, null));
        msgs.add(message("m2", Role.assistant, "a1", null, null));

        assertThat(LlmAgentLoop.finalContextTokensFromLastResponse(msgs)).isZero();
        assertThat(LlmAgentLoop.finalContextTokensFromLastResponse(List.of())).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 生产链路注入 + tengu_auto_compact_succeeded + 结转（queryLoop 集成）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 端到端：RunRequest.taskBudget → QueryParams.forLoop → queryLoop；压缩成功
     * （L1-L3 或 L4 任一路径）必须 emit {@code tengu_auto_compact_succeeded} 且按 CC
     * 公式执行结转（测量源 = finalContextTokensFromLastResponse 而非 pipeline 本地估算）。
     *
     * <p><b>RED-tooth</b>: revert 工厂注入（taskBudget 恒置 null）/ 删除 tengu 埋点 /
     * 结转公式偏移 → 本测试 fail。
     */
    @Test
    @DisplayName("生产链路: queryLoop 压缩成功 emit tengu_auto_compact_succeeded + task_budget 结转 + 真实参数断言（验收 #1）")
    void compactSuccess_emitsTenguAndCarriesBudget() {
        attachLogAppender();

        // ── 1. provider：捕获 17-arg blocks stream 的 taskBudget 参数（验收 #1 参数断言）──
        //   [IMP-SP-08] ModelCaller 切 blocks 重载：loop 恒经 splitSysPromptPrefix → blocks 发送，
        //   blocks 重载 = stream(config,model,blocks,history,tools,override,taskBudget,effortValue,
        //   querySource,onChunk,onMsg,onTool,onReasoning,onFallback,abort,onError,onComplete)
        //   → taskBudget 在 arg(6)，onChunk@9/onMsg@10/onComplete@16。
        LlmProvider provider = mock(LlmProvider.class);
        com.nexusai.infra.llm.TaskBudgetParam[] capturedBudget = { null };
        org.mockito.Mockito.doAnswer(inv -> {
            capturedBudget[0] = inv.getArgument(6);
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. 压缩能力：高 token 计数 → 必压缩；AutoCompactor 返回有效摘要 ──
        // 【GR-1 返工】AutoCompactor 单独构建并注入 4 参 queryLoop，使主自动压缩路径真实执行
        //（autoCompactIfNeeded → compactConversation 单函数），无旧编排器委托。
        TokenCounter highTokens = msgs -> 200_000;
        AutoCompactor autoCompactor = new AutoCompactor(highTokens,
            (p, m) -> new CompactConversation.SummaryResult("<summary>ok</summary>", null));

        // ── 3. state 预置：末位 assistant 消息带 usage → 结转测量源非 0 ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.replaceMessages(List.of(
            message("m1", Role.assistant, "pre-response", 30_000, 5_000),   // finalContextTokens = 35000
            message("m2", Role.user, "question", null, null)));

        // ── 4. 最小 AgentLoopContext（无压缩组件位 · GR-3 后 AutoCompactor 独立注入 queryLoop）──
        AgentLoopContext ctx = agentLoopContextWithCompaction(factory);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        // ── 5. 注入 taskBudget（REQ-23 · OD-13 上游注入：非恒 null · 输入契约仅 {total}）──
        // 【GR-1 返工】4 参 queryLoop 注入 autoCompactor → 主自动压缩路径真实执行并 emit
        //   tengu_auto_compact_succeeded + task_budget 结转（旧 3 参 queryLoop autoCompactor=null
        //   → 自动压缩跳过，断言必然失败，属陈旧 harness）。
        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "test-model", null,
                new TaskBudget(200_000),   // CC query.ts:197 {total} 输入契约；remaining loop 内维护
                null, null, null, deps, ProviderConfig.empty()),
            state, new ArrayList<>(),
            autoCompactor);

        // ── 6. 断言 ──
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        List<String> logs = llmLoopAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs)
            .as("压缩成功必须 emit tengu_auto_compact_succeeded（MISS-5 · CC query.ts:478）")
            .anyMatch(l -> l.contains("tengu_auto_compact_succeeded"));
        assertThat(logs)
            .as("压缩成功必须执行 task_budget 结转（INV-11 · CC query.ts:508-515）")
            .anyMatch(l -> l.contains("[IMP-16 task_budget.remaining] cross-compact carry"));
        // 结转测量源 = finalContextTokensFromLastResponse（m1 35000）：200000 − 35000 = 165000
        assertThat(logs)
            .as("结转减法源为 finalContextTokensFromLastResponse（35000），非 pipeline 本地估算 → now=165000")
            .anyMatch(l -> l.contains("now=165000"));

        // ── 7. 真实参数断言（验收 #1 · 反射要求：revert provider 透传 → 本断言 fail）──
        assertThat(capturedBudget[0])
            .as("taskBudget 必须经 ModelCaller 透传到 provider.stream（MISS-4 根因闭合，非写后不读）")
            .isNotNull();
        assertThat(capturedBudget[0].total())
            .as("task_budget.total == 注入 total=200000（CC query.ts:699-706）")
            .isEqualTo(200_000);
        assertThat(capturedBudget[0].remaining())
            .as("task_budget.remaining == 结转后值 165000（压缩后告知服务端被 summary 掉的 final context 窗口）")
            .isEqualTo(165_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 构造最小 AgentLoopContext（与 TestContexts.agentLoopContext 同形 · GR-3 后无压缩组件位）。 */
    private static AgentLoopContext agentLoopContextWithCompaction(LlmProviderFactory factory) {
        return new AgentLoopContext(
            null, null,                              // 1-2 toolRegistry/hookRegistry
            null, null, null, null, null, null, null, null,  // 3-10
            factory, null, null, null, null, null, null, null, null,        // 11-19
            FeatureFlags.ALL_DISABLED,               // 20 featureFlags
            null, null, null, null, null, null, null, null, null, null, null, null);       // 21-32
    }

    private static ChatMessageDto message(String id, Role role, String content,
                                          Integer inputTokens, Integer outputTokens) {
        return new ChatMessageDto(
            id, null, role, role.name(), content, null, List.of(),
            FinishReason.stop, inputTokens, outputTokens,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null);
    }
}
