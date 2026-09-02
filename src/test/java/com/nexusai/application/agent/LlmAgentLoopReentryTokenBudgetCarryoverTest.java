package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.recovery.LoopReason;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [V-TOK / DEC-RV-04] stop_hook_blocking 重入后 cumulativeOutputTokens 透传测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 的 {@code getTurnOutputTokens()} 累计在<b>循环
 * 外层</b>——{@code bootstrap/state.ts:724} {@code let outputTokensAtTurnStart = 0} 是模块级闭包变量，
 * 只在 turn 起始 snapshot（REPL.tsx:2135/2895/2967 {@code snapshotOutputTokensForTurn}）；而
 * stop_hook_blocking 重入（query.ts:1300-1305 {@code state = next; continue}）<b>不重新 snapshot</b>。
 * 故 CC 的 turnTokens 跨 stop-hook 重入<b>保留累计</b>（state.ts:726-728 差值口径天然累计）。
 *
 * <p>Java 旧实现：{@code loop()} 为递归方法，方法体内 {@code int cumulativeOutputTokens = 0} 每次重入
 * 都新建为 0 → 重入后 checkTokenBudget（LlmAgentLoop:4317）收到被<b>低估</b>的 turnTokens
 * （丢重入前累计）→ 90% 阈值（TokenBudgetChecker.COMPLETION_THRESHOLD=0.9）与 diminishing 判定偏移，
 * 可能 CC 该 stop 时 Java 却 continue 续跑。
 *
 * <p><b>RED tooth</b>: 回退「重入透传当前累计值」（重入改传 0 或仍每次新建局部变量）后本测试必须 fail
 * —— budget=1000、frame1 累计 800 → stop hook blockingError 重入（stopHookActive=true）→ frame2 再出
 * 300 tokens：透传时 800+300=1100 ≥ 90%×1000=900 → stop(null) 不续跑（共 2 次模型调用）；
 * 若重入从 0 起 → frame2 仅 300 &lt; 900 → continue → 注入 nudge → provider 被第 3 次调用。
 */
class LlmAgentLoopReentryTokenBudgetCarryoverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("stop_hook_blocking 重入后累计透传 → 800+300=1100 ≥ 90%×1000 → stop，不续跑（CC state.ts:726-728 循环外层累计跨重入保留）")
    void reentryCarriesCumulativeOutputTokens_budgetStopWithoutThirdCall() {
        // ── 1. provider：frame1 文本回合（outputTokens=800），frame2 文本回合（outputTokens=300）──
        List<String> calledModels = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            String model = inv.getArgument(1);
            calledModels.add(model);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // frame1：stop-hook 重入前的文本回合 · outputTokens=800
                onChunk.accept("first");
                onMsg.accept(new AssistantMessage("first", "stop", List.of(), "", null, 800L));
            } else {
                // frame2：重入后的文本回合 · outputTokens=300
                onChunk.accept("second");
                onMsg.accept(new AssistantMessage("second", "stop", List.of(), "", null, 300L));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. hookRegistry：第 1 次 STOP 事件 → blockingError（触发 stop_hook_blocking 重入），
        //      之后 STOP 事件 → proceed（重入帧正常走 budget check）。──
        // [IMP-HOOKS-S5 D-11 ②] LlmAgentLoop stop hooks 评估已改走 executeStopHooksCollecting
        //   （逐 result 消费，对齐 CC stopHooks.ts:200-295），不再走旧 executeEvent 折叠通道。
        //   必须 mock collecting API：若仍 mock executeEvent，mock 对未 stub 的
        //   executeStopHooksCollecting 返回 null → in-loop 无拦截 → 800<90%×1000 → continue
        //   → 误注入 nudge（本测试即为此失败），且 stop_hook_blocking 重入语义完全丢失。
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        AtomicInteger stopEventCount = new AtomicInteger(0);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any())).thenAnswer(inv -> {
            HookEvent ev = inv.getArgument(0);
            if (ev.type() == HookEventType.STOP) {
                if (stopEventCount.incrementAndGet() == 1) {
                    // CC stopHooks.ts blockingError 通道 → query.ts:1300-1305 state=next;continue（重入）
                    return new HookRegistry.StopHookCollectResult(
                        List.of(GenericHook.HookResult.stop("blocking", "stop hook blocking error")),
                        1, List.of(), List.of(), false, "blocking", false);
                }
            }
            // 重入帧 / §14：proceed（无 blockingError、无 preventContinuation）→ 正常走 budget check
            return new HookRegistry.StopHookCollectResult(
                List.of(), 0, List.of(), List.of(), false, null, false);
        });

        // ── 3. state：budgetTracker + 预算 1000 ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        state.setBudgetTracker(checker.createBudgetTracker());
        state.setTurnTokenBudget(1000);

        // ── 4. ctx：注入 tokenBudgetChecker（位置 9）+ hookRegistry（位置 2）──
        QueryConfig qc = new QueryConfig("s", new QueryConfig.Gates(false, false, false, true));
        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            checker,
            qc, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        // ── 5. 执行 queryLoop ──
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        // ── 6. 断言 ──
        // 透传时：frame1(800) + frame2(300) = 1100 ≥ 900 → stop，不再调模型（共 2 次结束）。
        // 若重入从 0 起（旧 bug）：frame2 累计 300 < 900 → continue → nudge → 第 3 次调用。
        assertThat(calledModels)
            .as("[V-TOK/DEC-RV-04] stop_hook_blocking 重入透传累计 → 重入后 1100 ≥ 90%×1000 → stop，共 2 次模型调用（重入归零时 frame2 仅 300<900 → continue → 第 3 次调用）")
            .hasSize(2);
        assertThat(state.messages().stream().map(m -> m.content()))
            .as("[V-TOK/DEC-RV-04] 透传累计后 budget stop（非 continue）→ 不得注入 nudge（CC query.ts:1316-1340 continue 才有 nudge）")
            .noneMatch(c -> c != null && c.contains("Keep working"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // [SH-02 E4] 场景 A-D：stop_hook_blocking 双触发防护（OPD-WF4-SH-02 + X-PROBE §2.1）
    //
    // CC 真源（X-PROBE EV-XP-W45-001/002/005/006/008/009/025）：
    //   · query.ts:1267-1306 handleStopHooks 每 turn 边界恰好一次；blockingErrors 非空 → 构造 next
    //     state：stopHookActive:true（:1300）、transition stop_hook_blocking（:1302）、state=next;continue
    //     （:1304-1305，栈平坦，不新增调用栈）。turnCount 不递增（:1301），maxTurns 不约束。
    //   · hooks.ts:3683 stop_hook_active 载荷 = 告知性字段（不跳过重跑，hook 自愈靠脚本判断）。
    //   · CC 无 stopHooksEvaluated 符号（query.ts 全文 1729 行 grep 零命中）——Java 该守卫是 do-while +
    //     §14 两段式结构必需等价物（in-loop 已评估则 §14 跳过，防同帧双发，EV-XP-W45-005）。
    // Java 现状：stopHooksEvaluated（:2753/5171/5384）+ 递归重入 loop(..., stopHookActive=true)
    //   （in-loop :5237 / §14 :5521，EV-XP-W45-006）。
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[SH-02 A] 单 turn 无双触发：Stop hook proceed → executeStopHooksCollecting 恰好 1 次（in-loop 跑，§14 stopHooksEvaluated=true 跳过）+ exitReason NORMAL")
    void scenarioA_singleTurn_proceeds_hookEvaluatedOnce() {
        // provider：单次文本响应；hookRegistry：STOP 恒 proceed；无 token budget（单 turn 后正常退出）。
        LlmProvider provider = textProvider();
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        List<HookEvent> recorded = new ArrayList<>();
        HookRegistry hookRegistry = recordingRegistry(recorded, new AtomicInteger(0), 0);

        AgentState state = runQueryLoop(hookRegistry, factory);

        // A：同一 turn 边界 hook 恰好评估 1 次（in-loop 跑、§14 stopHooksEvaluated=true 跳过）。
        //   若 §14 无守卫重复执行 → 计数 2（双发）。对齐 CC 单 for-loop 每 turn 边界 handleStopHooks 一次。
        assertThat(recorded)
            .as("[SH-02 A] 单 turn 无双触发：in-loop 已评估 → §14 跳过（stopHooksEvaluated 守卫），executeStopHooksCollecting 恰好 1 次")
            .hasSize(1);
        assertThat(state.exitReason())
            .as("[SH-02 A] 无阻塞 → 正常终止（对齐 CC query.ts:1264 return completed 等价）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
    }

    @Test
    @DisplayName("[SH-02 B] 阻塞重入载荷：首 STOP blockingError → lastReason=STOP_HOOK_BLOCKING（CC query.ts:1302）+ 第 2 次 STOP 事件 stop_hook_active=true（CC hooks.ts:3683）+ 共 2 次评估（2 turn 边界各一次）")
    void scenarioB_blockingReentry_payloadAndCount() {
        LlmProvider provider = textProvider();
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        List<HookEvent> recorded = new ArrayList<>();
        // 第 1 次 STOP → blockingError（触发 stop_hook_blocking 重入）；第 2 次 → proceed（重入帧正常）。
        HookRegistry hookRegistry = recordingRegistry(recorded, new AtomicInteger(0), 1);

        AgentState state = runQueryLoop(hookRegistry, factory);

        // B-1：重入后 lastReason=STOP_HOOK_BLOCKING（LlmAgentLoop loop() 入口 setLastReason · CC query.ts:1302）
        assertThat(state.recoveryState())
            .as("[SH-02 B] 重入帧入口 setLastReason(STOP_HOOK_BLOCKING) · CC query.ts:1302 transition")
            .isNotNull();
        assertThat(state.recoveryState().getLastReason())
            .as("[SH-02 B] stop_hook_blocking 重入 reason · CC query.ts:1302 transition:{reason:'stop_hook_blocking'}")
            .isEqualTo(LoopReason.STOP_HOOK_BLOCKING);
        // B-2：共 2 次评估（2 个 turn 边界各一次，对齐 CC 重入后 hook 照常重跑、不跳过）
        assertThat(recorded)
            .as("[SH-02 B] 2 个 turn 边界各评估 1 次（首帧 blocking 重入 + 重入帧 proceed）")
            .hasSize(2);
        // B-3：首事件 stop_hook_active=false（首调 CC query.ts:274-275 初始 false）；
        //      第 2 事件 stop_hook_active=true（重入点 loop(..., stopHookActive=true) → CC hooks.ts:3683 载荷）
        assertThat(recorded.get(0).data().get("stop_hook_active"))
            .as("[SH-02 B] 首 STOP 事件 stop_hook_active=false（CC query.ts:274 初始 false）")
            .isEqualTo(Boolean.FALSE);
        assertThat(recorded.get(1).data().get("stop_hook_active"))
            .as("[SH-02 B] 重入 STOP 事件 stop_hook_active=true（CC hooks.ts:3683 stopHookActive 透传，告知 hook 自愈，不跳过重跑）")
            .isEqualTo(Boolean.TRUE);
        // B-4：blocking 反馈注入 LLM（CC stopHooks.ts:257-267 blockingErrors → query.ts:1274-1277 append user message）
        assertThat(state.messages().stream().map(m -> m.content()))
            .as("[SH-02 B] blockingError 注入 LLM 反馈（CC getStopHookMessage 前缀）")
            .anyMatch(c -> c != null && c.contains("Stop hook feedback:"));
    }

    @Test
    @DisplayName("[SH-02 C] §14 守卫跳过：模型响应后 in-loop 已评估（无阻塞）→ §14 不重复执行（总评估次数仍为 1）")
    void scenarioC_s14Guard_doesNotReEvaluate() {
        LlmProvider provider = textProvider();
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        List<HookEvent> recorded = new ArrayList<>();
        HookRegistry hookRegistry = recordingRegistry(recorded, new AtomicInteger(0), 0);

        AgentState state = runQueryLoop(hookRegistry, factory);

        // C：总评估次数仍为 1 —— 若 §14 无 stopHooksEvaluated 守卫，会双发为 2（Java do-while + §14 两段式
        //   区别于 CC 单 for-loop；守卫是 Java 结构必需等价物，EV-XP-W45-005）。
        assertThat(recorded)
            .as("[SH-02 C] §14 stopHooksEvaluated=true 跳过 → 总评估 1 次（无双触发）")
            .hasSize(1);
        assertThat(state.exitReason())
            .as("[SH-02 C] 正常终止")
            .isEqualTo(AgentState.ExitReason.NORMAL);
    }

    @Test
    @DisplayName("[SH-02 D] 反复阻塞递归终止：Stop hook 恒 exit 2 → 重入上限触发 → STOP_HOOK_BLOCKING_LIMIT_EXCEEDED + 评估 MAX+1 次（防 StackOverflowError，对齐 CC 栈平坦不崩溃）")
    void scenarioD_alwaysBlocking_reentryLimitTerminates() {
        LlmProvider provider = textProvider();
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        List<HookEvent> recorded = new ArrayList<>();
        // 恒 blocking：CC 为无限循环烧 API（query.ts:1304-1305 continue 栈平坦，EV-XP-W45-002），
        // Java 递归重入深度无界 → 无安全阀会 StackOverflowError（EV-XP-W45-006 边界差异）。
        HookRegistry hookRegistry = recordingRegistry(recorded, new AtomicInteger(0), Integer.MAX_VALUE);

        AgentState state = runQueryLoop(hookRegistry, factory);

        // D-1：上限触发终止（不崩溃）· 评估次数 = MAX + 1（首帧 + MAX 次重入帧）
        int max = LlmAgentLoop.maxStopHookBlockingReentries();
        assertThat(recorded)
            .as("[SH-02 D] 重入上限内每帧 hook 重跑一次；超上限终止 → 评估恰好 MAX+1 次")
            .hasSize(max + 1);
        assertThat(state.exitReason())
            .as("[SH-02 D] 恒阻塞重入超限 → 安全阀终止（ExitReason.STOP_HOOK_BLOCKING_LIMIT_EXCEEDED，防 StackOverflowError）")
            .isEqualTo(AgentState.ExitReason.STOP_HOOK_BLOCKING_LIMIT_EXCEEDED);
        // D-2：首事件 stop_hook_active=false；其余全部 true（重入帧均携带 stop_hook_active=true）
        assertThat(recorded.get(0).data().get("stop_hook_active"))
            .as("[SH-02 D] 首帧 stop_hook_active=false")
            .isEqualTo(Boolean.FALSE);
        for (int i = 1; i < recorded.size(); i++) {
            assertThat(recorded.get(i).data().get("stop_hook_active"))
                .as("[SH-02 D] 重入帧 %d stop_hook_active=true（CC hooks.ts:3683）", i)
                .isEqualTo(Boolean.TRUE);
        }
    }

    // ── [SH-02 E4] 测试夹具 ──

    /** 文本 provider：每次调用产出 1 段文本（finishReason=stop）响应。 */
    private static LlmProvider textProvider() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        AtomicInteger callCount = new AtomicInteger(0);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int count = callCount.incrementAndGet();
            onChunk.accept("turn-" + count);
            onMsg.accept(new AssistantMessage("turn-" + count, "stop", List.of(), "", null, 1L));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 记录型 hookRegistry：记录每次 STOP 事件；前 blockingCalls 次返回 blockingError，其后 proceed。 */
    private static HookRegistry recordingRegistry(List<HookEvent> recorded, AtomicInteger callCount, int blockingCalls) {
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any())).thenAnswer(inv -> {
            HookEvent ev = inv.getArgument(0);
            recorded.add(ev);
            if (callCount.incrementAndGet() <= blockingCalls) {
                return new HookRegistry.StopHookCollectResult(
                    List.of(GenericHook.HookResult.stop("blocking", "stop hook blocking error")),
                    1, List.of(), List.of(), false, "blocking", false);
            }
            return new HookRegistry.StopHookCollectResult(
                List.of(), 0, List.of(), List.of(), false, null, false);
        });
        return hookRegistry;
    }

    /** 构造 ctx：tokenBudgetChecker=null（跳过 budget check，单 turn 后正常退出）。 */
    private static AgentLoopContext ctxWithoutBudget(HookRegistry hookRegistry, LlmProviderFactory factory) {
        QueryConfig qc = new QueryConfig("s", new QueryConfig.Gates(false, false, false, true));
        return new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            null, // tokenBudgetChecker → null（跳过 budget check）
            qc, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

    /** 执行 queryLoop 并返回终态。 */
    private static AgentState runQueryLoop(HookRegistry hookRegistry, LlmProviderFactory factory) {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        AgentLoopContext ctx = ctxWithoutBudget(hookRegistry, factory);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());
        return state;
    }
}
