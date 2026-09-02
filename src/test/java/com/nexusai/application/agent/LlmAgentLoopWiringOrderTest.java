package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.recovery.RecoveryState;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [IMP-12] 主循环接线顺序 + RecoveryState 随轮重置 + turnCounter++ 测试。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>循环入口 boundary 剥离（DRIFT-17）</b> — CC 每轮 query.ts:365 先
 *       {@code getMessagesAfterCompactBoundary} 切片去 pre-boundary 冗余历史；Java 端若循环入口
 *       不剥离，pre-boundary 消息会继续进入 LLM 上下文（被摘要覆盖的旧内容反复重发）。本测试驱动
 *       queryLoop，断言最终工作上下文不含 pre-boundary 消息。</li>
 *   <li><b>RecoveryState 随轮重置（DRIFT-7/8）</b> — CC next_turn transition（query.ts:1721）把
 *       {@code maxOutputTokensRecoveryCount} 与 {@code hasAttemptedReactiveCompact} 重置；Java 端
 *       {@link RecoveryState#resetContinuation()} 必须在 genuine next_turn 边界被调用，否则
 *       max_tokens 续写计数跨 turn 累计（比 CC 更严格）。</li>
 *   <li><b>主循环接线顺序（DRIFT-1）</b> — boundary 剥离 → 预算 → snip → micro → collapse →
 *       autocompact → blocking → callModel；顺序偏移则触发语义偏移（如 snip 在 autocompact 后才跑，
 *       snipTokensFreed 无法真实透传 INV-9）。本测试以源码顺序断言锚定不变量。</li>
 *   <li><b>REWORK（IMP-12 返工 + S3-B1 更新）</b> — 主循环顺序接线为 boundary → 预算 → snip →
 *       micro → collapse → autocompact → blocking；micro 已进入主循环（S3-B1 · CC query.ts:414
 *       snip 后、collapse 前恒调用；"micro 走独立链式入口归 IMP-09"的说法在 B1 后过时），
 *       旧编排器/管线（D-02/D-03）已删除（GR-3）。本测试只声明 LlmAgentLoop 层可达成顺序，
 *       不声称完整 DRIFT-1 覆盖。</li>
 *   <li><b>工具批后 turnCounter++ + tengu_post_autocompact_turn（MISS-3）</b> — CC query.ts:1523-1533
 *       {@code if (tracking?.compacted) { tracking.turnCounter++; logEvent(...) }}；Java 端若缺失，
 *       压缩后 turn 计数遥测不可观测。</li>
 *   <li><b>L4 尾段不丢（OD-04/INV-2）</b> — 压缩成功后必须用压缩结果（buildPostCompactMessages
 *       组装，含 messagesToKeep）直接取代消息链，不额外切片丢弃。</li>
 * </ol>
 */
class LlmAgentLoopWiringOrderTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    // ─────────────────────── 1. 循环入口 boundary 剥离（DRIFT-17） ───────────────────────

    @Test
    @DisplayName("循环入口 boundary 剥离: pre-boundary 消息不进工作上下文（CC query.ts:365）")
    void loopEntry_stripsPreBoundaryMessages() throws IOException {
        // ── 1. state: [pre1, compact_boundary, post1] ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.replaceMessages(List.of(
            message("pre1", "pre-boundary question"),
            boundaryMessage(),
            message("post1", "post-boundary question")));

        // ── 2. provider 返回简单 end_turn 响应（无工具）──
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [IMP-SP-08] blocks 重载：onChunk@9/onMsg@10/onComplete@16
        Mockito.doAnswer(inv -> {
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("response");
            onMsg.accept(new AssistantMessage("response", "end_turn", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        // ── 3. 驱动 loop ──
        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        // ── 4. 断言: pre1 已剥离，工作上下文 = [boundary, post1, assistant] ──
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        List<String> contents = state.messages().stream()
            .map(m -> m.id() != null ? m.id() : "").toList();
        assertThat(contents)
            .as("pre-boundary 消息必须被剥离（DRIFT-17 · CC query.ts:365 getMessagesAfterCompactBoundary）")
            .doesNotContain("pre1");
        assertThat(contents)
            .as("boundary 消息（含）向后保留")
            .contains("post1");
    }

    // ─────────────────────── 2. RecoveryState 随轮重置（DRIFT-7/8） ───────────────────────

    @Test
    @DisplayName("RecoveryState.continuationCount 随轮重置（CC query.ts:1721 next_turn）+ LlmAgentLoop 接线")
    void recoveryState_continuationCount_resetsPerTurn() throws IOException {
        // RecoveryState 单元级：incrementContinuation → resetContinuation → 0
        RecoveryState rs = new RecoveryState("test-model");
        rs.incrementContinuation();
        rs.incrementContinuation();
        assertThat(rs.getContinuationCount())
            .as("incrementContinuation 后计数=2（CC MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3）")
            .isEqualTo(2);
        rs.resetContinuation();
        assertThat(rs.getContinuationCount())
            .as("resetContinuation 后必须 0（DRIFT-8 · CC next_turn query.ts:1721 maxOutputTokensRecoveryCount: 0）")
            .isZero();

        // LlmAgentLoop 接线级：genuine next_turn 边界（真实 assistant 响应后）调用复位
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("LlmAgentLoop 必须在 genuine next_turn 边界调用 resetContinuation（DRIFT-8 接线）")
            .contains("recoveryState.resetContinuation();");
        assertThat(source)
            .as("hasAttemptedReactiveCompact 随轮重置接线必须保留（DRIFT-7 · IMP-14）")
            .contains("recoveryState.resetReactiveCompactAttempt();");
    }

    // ─────────────────────── 3. 主循环接线顺序（DRIFT-1） ───────────────────────

    @Test
    @DisplayName("主循环接线顺序: boundary 剥离→预算→snip→micro→collapse→autocompact→blocking（query.ts:365-648 · S3-B1 更新）")
    void mainLoop_ccOrderCheckpoints() throws IOException {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));

        int boundaryIdx = source.indexOf("BoundaryReader.getMessagesAfterCompactBoundary(state.messages())");
        int budgetIdx = source.indexOf("AgentLoopContext.applyPerMessageBudget(ctx, state, params.querySource(), skipToolNames)");
        int snipIdx = source.indexOf("snipCompactIfNeeded(messagesForQuery)");
        int microIdx = source.indexOf("microCompactor.microcompactMessages(beforeMicro");
        int collapseIdx = source.indexOf("applyCollapsesIfNeeded(messagesForQuery");
        // 【GR-1 返工】主自动压缩直调 autoCompactor.autoCompactIfNeeded → compactConversation 单函数
        //（autoCompact.ts:313），无手工 [boundary,summary] 组装双轨。per-session 上下文经 buildAutoContext 映射。
        int autoCtxIdx = source.indexOf("CompactConversation.buildAutoContext(");
        int autocompactIdx = source.indexOf("autoCompactor.autoCompactIfNeeded(");
        int blockingIdx = source.indexOf("blocking-limit 预检");

        assertThat(boundaryIdx).as("boundary 剥离必须存在（DRIFT-17）").isPositive();
        assertThat(budgetIdx).as("预算步骤必须存在").isPositive();
        assertThat(snipIdx).as("snip 步骤必须存在（INV-9）").isPositive();
        assertThat(microIdx).as("micro 步骤必须进入主循环（S3-B1 · CC query.ts:414 恒调用）").isPositive();
        assertThat(collapseIdx).as("collapse 步骤必须存在").isPositive();
        assertThat(autoCtxIdx).as("GR-1 per-session 上下文必须经 CompactConversation.buildAutoContext 映射（compact.ts:387-395 context 依赖面）").isPositive();
        assertThat(autocompactIdx).as("GR-1 主自动压缩必须直调 autoCompactor.autoCompactIfNeeded（消除 beforeTurn/tryAutoCompact 双轨）").isPositive();
        assertThat(blockingIdx).as("blocking 预检必须存在（query.ts:637）").isPositive();

        // CC 顺序不变量（DRIFT-1）：boundary → 预算 → snip → micro → collapse → autocompact → blocking。
        // 【S3-B1 更新】micro 已进入主循环（snip 后、collapse 前，CC query.ts:414-426）；
        // 旧"micro 走独立链式入口归 IMP-09"断言不含 micro 的顺序在 B1 后过时。
        assertThat(boundaryIdx).as("boundary 剥离先于预算").isLessThan(budgetIdx);
        assertThat(budgetIdx).as("预算先于 snip").isLessThan(snipIdx);
        assertThat(snipIdx).as("snip 先于 micro（CC query.ts:401 → :414）").isLessThan(microIdx);
        assertThat(microIdx).as("micro 先于 collapse（CC query.ts:414 → :440）").isLessThan(collapseIdx);
        assertThat(collapseIdx).as("collapse 先于 autocompact").isLessThan(autocompactIdx);
        assertThat(autocompactIdx).as("autocompact 先于 blocking 预检").isLessThan(blockingIdx);
    }

    // ─────────────────────── 3.1 G-2 model 源统一 effectiveModel（OPD-CM3-33 · IMP-CM-06） ───────────────────────

    @Test
    @DisplayName("G-2: autocompact 阈值 model 源统一 effectiveModel（CC autoCompact.ts:267 mainLoopModel，可被 fallback 改写）")
    void g2_autoCompactUsesEffectiveModel() throws IOException {
        // WHY (CLAUDE.md 规则 9 · 测试验证意图): CC autoCompact.ts:267 阈值读
        // `toolUseContext.options.mainLoopModel`，query.ts:922 fallback 后改写为 fallbackModel
        // （= effectiveModel）。旧 Java 在 autocompact gate 用 params.modelName()（原始 spawn 模型）
        // → fallback 场景阈值与 blocking-limit（effectiveModel）模型源错位（Q-1）。
        // 本测试锚定：① autocompact gate 必须经 resolveTurnEffectiveModel 取有效模型；
        // ② 旧"直接传 params.modelName() 给 buildAutoContext"接线必须消失。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("G-2: autocompact gate 必须经 resolveTurnEffectiveModel(params, recoveryState) 取 effectiveModel"
                + "（对齐 CC mainLoopModel，非原始 params.modelName()）")
            .contains("String compactEffectiveModel = resolveTurnEffectiveModel(params, recoveryState);");
        assertThat(source)
            .as("G-2: buildAutoContext 不再直接传 params.modelName()（旧接线已消除，fallback 场景模型源错位 Q-1）")
            .doesNotContain("params.toolUseContext(), params.modelName(), params.querySource().canonical(), ctx.hookRegistry()");
        assertThat(source)
            .as("G-2: 有效模型解析必须统一经 resolveTurnEffectiveModel（s11 effectiveModel 与 autocompact 阈值同源）")
            .contains("String effectiveModel = resolveTurnEffectiveModel(params, recoveryState);");
    }

    // ─────────────────────── 4. 工具批后 turnCounter++ + tengu_post_autocompact_turn（MISS-3） ───────────────────────

    @Test
    @DisplayName("工具批后 turnCounter++ 归并 tracking.startNewTurn + tengu_post_autocompact_turn 埋点（CC query.ts:1523-1533）")
    void turnCounter_postCompactTurnWiring() throws IOException {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        String trackingSource = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/compact/AutoCompactTrackingState.java"));
        assertThat(source)
            .as("必须存在 turnCounter++ 接线（IMP2-07 归并 · CC query.ts:1524）")
            .contains("getTracking().startNewTurn();");
        assertThat(source)
            .as("必须存在 tengu_post_autocompact_turn 遥测事件（CC query.ts:1525）")
            .contains("tengu_post_autocompact_turn");
        assertThat(source)
            .as("postCompactTurnCounter 局部计数必须已归并进 tracking（DRIFT-4/S-6 单计数源）")
            .doesNotContain("postCompactTurnCounter");
        assertThat(trackingSource)
            .as("压缩成功必须轮换 turnId（CC query.ts:523 deps.uuid()）")
            .contains("this.turnId = UUID.randomUUID().toString();");
        assertThat(trackingSource)
            .as("压缩成功必须归零 turnCounter（CC query.ts:524）")
            .contains("this.turnCounter = 0;");
    }

    // ─────────────────────── ER-IMP-09: stop-hook 重入守卫 + hook_stopped 终止 + 预算复位 ───────────────────────

    @Test
    @DisplayName("stop-hook 重入保留 hasAttemptedReactiveCompact（CC query.ts:1297 防死亡螺旋）")
    void erImp09_reentryPreservesReactiveCompactGuard() throws IOException {
        // WHY (CLAUDE.md 规则 9): CC query.ts:1293-1296 注释明示——compact 已跑仍 PTL，stop-hook
        // blocking 错误后重试结果相同；置 false 会引发无限循环（compact → 仍过长 → error → stop hook
        // blocking → compact → … 烧几千次 API）。Java 旧实现 loop() 重入（blockingError → loop(..., true)）
        // 每次都 new RecoveryState 天然 reset 守卫 → 死亡螺旋回归风险。本测试锚定：
        // ① 重入点（stopHookActive=true 且 state.recoveryState()!=null）必须搬运旧守卫到新实例。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("loop() 入口必须存在 stop-hook 重入守卫保留（ER-IMP-09 · CC stop_hook_blocking query.ts:1297）")
            .contains("recoveryState.preserveReactiveCompactAttempt(true);");
        assertThat(source)
            .as("重入守卫搬运必须仅在 stopHookActive=true（重入）且 state.recoveryState()!=null 时触发")
            .contains("stopHookActive && state.recoveryState() != null");

        // ② RecoveryState 单元级：preserveReactiveCompactAttempt(true) 把守卫从 false 抬到 true，
        //    且不触碰 continuationCount（CC :1291/:1332 复位由新实例天然 0 承担）。
        RecoveryState fresh = new RecoveryState("test-model");
        fresh.preserveReactiveCompactAttempt(true);
        assertThat(fresh.isHasAttemptedReactiveCompact())
            .as("preserveReactiveCompactAttempt(true) 后守卫必须 true（重入防二次 compact）")
            .isTrue();
        assertThat(fresh.getContinuationCount())
            .as("preserveReactiveCompactAttempt 不得触碰 continuationCount（CC :1291 复位由新实例承担）")
            .isZero();
        // ③ preserve(false) 不改变当前值（首调/无旧守卫 no-op）
        RecoveryState noGuard = new RecoveryState("test-model");
        noGuard.preserveReactiveCompactAttempt(false);
        assertThat(noGuard.isHasAttemptedReactiveCompact())
            .as("preserveReactiveCompactAttempt(false) 不得抬守卫（首调 stopHookActive=false）")
            .isFalse();
    }

    @Test
    @DisplayName("hook_stopped 作终止信号（CC query.ts:1520）：工具 turn 后扫描 hook_stopped_continuation → ExitReason.HOOK_STOPPED")
    void erImp09_hookStoppedIsTerminationSignal() throws IOException {
        // WHY (CLAUDE.md 规则 9): CC query.ts:1390-1392 工具执行流内 hook_stopped_continuation
        // → shouldPreventContinuation=true，工具批后 query.ts:1519-1520 return { reason:'hook_stopped' }
        // —— hook 指示停止续行是【终止信号】。Java 旧实现把该 attachment 渲染为 LLM 注入文本继续
        // （AgentLoopContext.renderHookAttachmentForLlm :2049），错误地把终止当续行（双发/注入继续）。
        // 本测试锚定：工具 turn 后必须扫描本 turn 新增切片并 setExitReason(HOOK_STOPPED) 终止。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("工具 turn 后必须记录本 turn attachments 切片前计数（作用域=新增切片防历史误触发）")
            .contains("int attachmentsBefore = state.attachments().size();");
        assertThat(source)
            .as("必须存在 hook_stopped_continuation 检测 helper（ER-IMP-09）")
            .contains("hasHookStoppedContinuation(state, attachmentsBefore)");
        assertThat(source)
            .as("检测命中必须置 ExitReason.HOOK_STOPPED（CC query.ts:1520 终止信号）")
            .contains("state.setExitReason(ExitReason.HOOK_STOPPED);");
        assertThat(source)
            .as("hook_stopped 终止后必须 break（不进入下一 LLM 调用 → 注入文本永不进 LLM）")
            .contains("hasHookStoppedContinuation(state, attachmentsBefore)) {")
            .contains("break;");
    }

    @Test
    @DisplayName("token_budget_continuation 复位（CC query.ts:1332-1333）：ContinueDecision 后 resetContinuation + resetReactiveCompactAttempt")
    void erImp09_tokenBudgetContinuationResetsRecoveryState() throws IOException {
        // WHY (CLAUDE.md 规则 9): CC query.ts:1332-1333 token_budget_continuation 重建 State 时
        // maxOutputTokensRecoveryCount:0 + hasAttemptedReactiveCompact:false —— 预算续写开启全新
        // 恢复预算 + 新一轮 reactive compact 尝试资格。Java 若缺复位，前一轮的续写计数/守卫残留，
        // 预算续写后的 PTL 恢复被提前判死或守卫误拦。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("ContinueDecision 分支必须调用 resetContinuation（CC :1332 maxOutputTokensRecoveryCount:0）")
            .contains("recoveryState.resetContinuation();");
        assertThat(source)
            .as("ContinueDecision 分支必须调用 resetReactiveCompactAttempt（CC :1333 hasAttemptedReactiveCompact:false）")
            .contains("recoveryState.resetReactiveCompactAttempt();");
        // 两复位必须位于 ContinueDecision 分支内（token budget continue 注入 nudge 之后）
        // D3: token budget check 移至模型响应后，ContinueDecision 在文件尾部的 post-response check 中。
        // genuine next_turn 复位块在 ContinueDecision 之前，故用 indexOf(str, continueIdx) 找后出现的那个。
        int continueIdx = source.indexOf("TokenBudgetChecker.ContinueDecision cont)");
        int resetContinuationIdx = source.indexOf("recoveryState.resetContinuation();", continueIdx);
        int resetAttemptIdx = source.indexOf("recoveryState.resetReactiveCompactAttempt();", continueIdx);
        assertThat(continueIdx)
            .as("必须存在 ContinueDecision 分支（ER-IMP-09 复位锚点）")
            .isGreaterThan(-1);
        assertThat(resetContinuationIdx)
            .as("resetContinuation 必须出现在 ContinueDecision 分支之后（CC :1332 在 nudge 注入后）")
            .isGreaterThan(continueIdx);
        assertThat(resetAttemptIdx)
            .as("resetReactiveCompactAttempt 必须出现在 ContinueDecision 分支之后（CC :1333）")
            .isGreaterThan(continueIdx);
    }

    @Test
    @DisplayName("HOOK_STOPPED 与 STOP_HOOK_PREVENTED 同入 task_summary skip 列表（不生成通用 task_summary）")
    void erImp09_hookStoppedSkipsTaskSummary() throws IOException {
        // WHY: hook_stopped_continuation attachment 已承载终止（hook 停止原因），再生成通用
        // task_summary 会双 attachment 干扰消费契约。WF9-4（OPD-TS-30 方案 A）删除 LlmAgentLoop
        // instance 死代码后，skip 列表收敛为单一真源 —— AgentLoopContext static
        // generateTaskSummaryAttachment（:1056）。LlmAgentLoop 不得再持有重复 instance 版（防双实现漂移）。
        String alcSource = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/loop/AgentLoopContext.java"));
        assertThat(alcSource)
            .as("AgentLoopContext task_summary skip 列表必须含 HOOK_STOPPED（单一真源）")
            .contains("reason == AgentState.ExitReason.STOP_HOOK_PREVENTED")
            .contains("reason == AgentState.ExitReason.HOOK_STOPPED");

        String loopSource = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(loopSource)
            .as("LlmAgentLoop 不得再持有 instance generateTaskSummaryAttachment（已删死代码，防双实现漂移）")
            .doesNotContain("public void generateTaskSummaryAttachment(AgentState state)")
            .doesNotContain("public void emitPeriodicTaskSummaryHook(AgentState state)");
    }

    @Test
    @DisplayName("IMP-HOOKS-S5 D-14: A11 仅退出前 attachment 生成，周期发射已删除（CC 无 taskSummary 模块）")
    void wf94_bgSessionsGatePinsTaskSummaryAttachment() throws IOException {
        // WHY (D-14): CC 基线无 taskSummary 模块（query.ts:118-120 仅 feature('BG_SESSIONS')
        // 构建期 off 门控悬空引用），periodic_task_summary Notification hook 发射为 Java 独有
        // 错位参数（notification_type=摘要文本破坏 matcher 键语义）→ 全链删除。删除前该测试
        // 断言"两 static 调用在门控体内"（emitPeriodicTaskSummaryHook 调用点）已随删除失效，
        // 改写为：门控行存在 + generateTaskSummaryAttachment 在门控体内 + 周期发射符号零残留。
        String loopSource = Files.readString(Path.of(LLM_LOOP_PATH));
        String gate = "if (ctx.featureFlags().bgSessions() && state.agentId() == null) {";
        int gateIdx = loopSource.indexOf(gate);
        assertThat(gateIdx)
            .as("A11 task summary 调用点必须带 bgSessions && !agentId 门控（CC query.ts:1685；删门控=无条件回退=违约）")
            .isGreaterThan(-1);
        int attachmentIdx = loopSource.indexOf("AgentLoopContext.generateTaskSummaryAttachment(ctx, state);");
        assertThat(attachmentIdx)
            .as("generateTaskSummaryAttachment static 调用必须在门控 if 体内（门控行之后）")
            .isGreaterThan(gateIdx);
        assertThat(loopSource)
            .as("D-14: emitPeriodicTaskSummaryHook 调用点必须零残留（周期发射全链删除）")
            .doesNotContain("emitPeriodicTaskSummaryHook");
    }

    // ─────────────────────── V-SH 复核修复：stop-hook 重入守卫 + hook_stopped 终止 + token_budget 复位 ───────────────────────

    @Test
    @DisplayName("V-SH-1: HOOK_STOPPED 终止后跳过 §14 Stop hooks（CC query.ts:1519-1520 hook_stopped 不触达 handleStopHooks）")
    void vsh1_hookStoppedSkipsStopHooksSection() throws IOException {
        // WHY (CLAUDE.md 规则 9): CC shouldPreventContinuation=true → 立即 return {reason:'hook_stopped'}
        // （query.ts:1519-1520），handleStopHooks（:1267）只在 !needsFollowUp 分支（:1062）内，
        // hook_stopped 路径永不触达 stop hooks。Java 旧实现 HOOK_STOPPED break 后落到 §14，
        // 唯一门控 !skipStopPipeline 不因 HOOK_STOPPED 置 true → stop hook blockingError（§14 内）
        // 可重入 loop 覆盖终止信号（把终止变续跑，ER-IMP-09 本应修复）。本测试锚定 §14 gate
        // 必须排除 HOOK_STOPPED（CC 互斥分支：hook_stopped vs stop_hook_prevented/blocking）。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("§14 Stop hooks gate 必须排除 HOOK_STOPPED（CC query.ts:1520 立即退出不触达 handleStopHooks）")
            .contains("if (!skipStopPipeline && state.exitReason() != ExitReason.HOOK_STOPPED) {");
    }

    @Test
    @DisplayName("V-SH-2: hook_stopped_continuation 终止信号不注入 LLM（renderHookAttachmentForLlm 返回 null）")
    void vsh2_hookStoppedContinuationNotInjected() throws IOException {
        // WHY: CC normalizeAttachmentForAPI 虽渲染该 attachment（messages.ts:4130-4136），但同一
        // query() 内 shouldPreventContinuation → 立即 return {reason:'hook_stopped'}（query.ts:1519-1520），
        // 渲染结果随 toolResults 丢弃，同次 query() 无后续 LLM 调用永不送达模型。Java attachments()
        // 跨 loop 常驻，若渲染非 null 会在后续 LLM 调用被 maybeInjectHookAttachments 注入为 meta user
        // 文本 → 错误地把终止当续行（ER-IMP-09 本应修复的'渲染注入继续'）。本测试锚定
        // hook_stopped_continuation case 必须 return null。
        String source = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/loop/AgentLoopContext.java"));
        int caseIdx = source.indexOf("case \"hook_stopped_continuation\":");
        assertThat(caseIdx)
            .as("renderHookAttachmentForLlm 必须存在 hook_stopped_continuation case")
            .isGreaterThan(-1);
        int nullIdx = source.indexOf("return null;", caseIdx);
        int nextCaseIdx = source.indexOf("case \"", caseIdx + 1);
        assertThat(nullIdx)
            .as("hook_stopped_continuation case 内必须 return null（终止信号不注入 LLM）")
            .isGreaterThan(caseIdx)
            .isLessThan(nextCaseIdx > -1 ? nextCaseIdx : Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("V-SH-3: token_budget_continuation 复位 stopHookActive 语义（CC query.ts:1336 stopHookActive: undefined）")
    void vsh3_tokenBudgetContinuationResetsStopHookActive() throws IOException {
        // WHY: stop_hook_blocking 重入（loop(..., stopHookActive=true)）后若 token_budget_continuation
        // 发生，CC 把 stopHookActive 复位 undefined（query.ts:1336）；Java loop() 参数不可变，
        // 旧实现保持 true → 后续 stop hook 评估 stop_hook_active 语义错位。本测试锚定 ContinueDecision
        // 分支必须含 effectiveStopHookActive = false 复位。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int continueIdx = source.indexOf("TokenBudgetChecker.ContinueDecision cont)");
        int resetIdx = source.indexOf("effectiveStopHookActive = false;", continueIdx);
        assertThat(continueIdx)
            .as("必须存在 ContinueDecision 分支（V-SH-3 复位锚点）")
            .isGreaterThan(-1);
        assertThat(resetIdx)
            .as("ContinueDecision 分支必须复位 effectiveStopHookActive=false（CC :1336 stopHookActive:undefined）")
            .isGreaterThan(continueIdx);
    }

    @Test
    @DisplayName("V-SH-4: lastReason 写 STOP_HOOK_BLOCKING / TOKEN_BUDGET_CONTINUATION（CC query.ts:1302/:1338）")
    void vsh4_lastReasonWrites() throws IOException {
        // WHY: CC 设 transition.reason(stop_hook_blocking :1302 / token_budget_continuation :1338)；
        // Java 旧实现这两值从不写 RecoveryState.lastReason，测试无法经 lastReason 断言恢复路径触发。
        // 本测试锚定：① stop-hook 重入（loop 入口 stopHookActive=true）写 STOP_HOOK_BLOCKING；
        // ② ContinueDecision 分支写 TOKEN_BUDGET_CONTINUATION。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("loop 入口 stopHookActive=true 必须写 STOP_HOOK_BLOCKING（CC query.ts:1302）")
            .contains("recoveryState.setLastReason(LoopReason.STOP_HOOK_BLOCKING);");
        int continueIdx = source.indexOf("TokenBudgetChecker.ContinueDecision cont)");
        int tokenBudgetReasonIdx = source.indexOf(
            "recoveryState.setLastReason(LoopReason.TOKEN_BUDGET_CONTINUATION);", continueIdx);
        assertThat(tokenBudgetReasonIdx)
            .as("ContinueDecision 分支必须写 TOKEN_BUDGET_CONTINUATION（CC query.ts:1338）")
            .isGreaterThan(continueIdx);
    }

    @Test
    @DisplayName("V-SH-5: HOOK_STOPPED 跳过 s09 memory extract + autoDream（CC query.ts:1519-1520 hook_stopped 不触达 handleStopHooks 内 extractMemories）")
    void vsh5_hookStoppedSkipsMemoryExtract() throws IOException {
        // WHY: handleStopHooks（stopHooks.ts:141-156 内含 executeExtractMemories :149 +
        //   executeAutoDream :155）只在 !needsFollowUp 分支（query.ts:1062，调用点 :1267）内调用；
        //   hook_stopped（query.ts:1519-1520）在工具执行路径立即 return，永不触达 handleStopHooks
        //   → memory extract 不执行。[H-WF4-01] 后阶段 4 extract/dream 已移入 do-while 纯文本分支的
        //   in-loop Stop hook 评估段（:6320 gate → :6392 executeExtractMemoriesAndAutoDream）；
        //   HOOK_STOPPED 在工具批路径 :6121 setExitReason + :6124 break 恒先于纯文本分支退出 ——
        //   in-loop 段仅在「模型产生有效响应 + 纯文本非空」路径执行（:7050-7054 注释），HOOK_STOPPED
        //   的 turn 不产生有效响应文本 → 结构性排除。本测试锚定该结构（STOP_HOOK_PREVENTED 保留：
        //   CC stop_hook_prevented 由 handleStopHooks 内部返回，extract 已 fire-and-forget）。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int memExtractIdx = source.indexOf("StopHookPipeline.executeExtractMemoriesAndAutoDream(");
        assertThat(memExtractIdx)
            .as("in-loop 阶段4 memory extract 调用点必须存在（H-WF4-01 · CC stopHooks.ts:149/:155）")
            .isGreaterThan(-1);
        int hookStoppedIdx = source.indexOf("state.setExitReason(ExitReason.HOOK_STOPPED);");
        assertThat(hookStoppedIdx)
            .as("HOOK_STOPPED 终止检测必须存在（CC query.ts:1520 终止信号）")
            .isGreaterThan(-1);
        assertThat(hookStoppedIdx)
            .as("HOOK_STOPPED 必须先行 break（工具批路径）早于 in-loop memory extract → 结构性排除（CC query.ts:1519-1520 hook_stopped 不触达 handleStopHooks）")
            .isLessThan(memExtractIdx);
        assertThat(source)
            .as("§14 Stop hooks 亦须排除 HOOK_STOPPED（V-SH-1 同门）")
            .contains("!skipStopPipeline && state.exitReason() != ExitReason.HOOK_STOPPED");
    }

    // ─────────────────────── V-IMG-01 返工：Stop hook 中断附加用户中断消息 ───────────────────────

    @Test
    @DisplayName("V-IMG-01: Stop hook 执行中被中断 → 附加用户中断消息 + tengu_pre_stop_hooks_cancelled 遥测（CC stopHooks.ts:284-294）")
    void vimg01_stopHookAbortAppendsInterruptionAndTelemetry() throws IOException {
        // WHY (CLAUDE.md 规则 9): CC stopHooks.ts:283-294 Stop hook 执行中 abortController 被中断 →
        //   logEvent('tengu_pre_stop_hooks_cancelled')（:284）+ yield createUserInterruptionMessage({toolUse:false})
        //   （:290）→ INTERRUPT_MESSAGE "[Request interrupted by user]"（messages.ts:207），resume 时模型
        //   可见中断信号。Java 旧实现两处 Stop hook abort 路径（in-loop + §14）仅设 ExitReason.ABORTED，
        //   不附加中断消息 → 模型在 resume 时看不到中断上下文（V-IMG verify 报告第 3 调用点缺失 HIGH）。
        //   本测试锚定：两处 stopAborted 分支都必须 ① appendMessage(createUserInterruptionMessage(false))
        //   ② emit tengu_pre_stop_hooks_cancelled 遥测等价。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));

        // 两处 stopAborted 分支（in-loop :4238 / §14 :4429）都必须附加中断消息
        int firstAbortIdx = source.indexOf("if (stopAborted) {");
        assertThat(firstAbortIdx)
            .as("必须存在 stopAborted 分支（V-IMG-01 锚点）")
            .isGreaterThan(-1);
        int secondAbortIdx = source.indexOf("if (stopAborted) {", firstAbortIdx + 1);
        assertThat(secondAbortIdx)
            .as("必须存在第二处 stopAborted 分支（in-loop + §14 两路径）")
            .isGreaterThan(firstAbortIdx);

        // 每处分支内都必须含中断消息 + tengu 遥测
        // [IMP-HOOKS-S5 D-11 ①] in-loop 分支结构由 `} else if (stopResult.blockingError()`
        // 改为 `} else {`（逐 result 消费）→ 两处分支终止符统一用 "} else {"
        String firstBranch = source.substring(firstAbortIdx,
            source.indexOf("} else {", firstAbortIdx));
        String secondBranch = source.substring(secondAbortIdx,
            source.indexOf("} else {", secondAbortIdx));
        assertThat(firstBranch)
            .as("in-loop stopAborted 分支必须附加 createUserInterruptionMessage(false)（CC stopHooks.ts:290）")
            .contains("state.appendMessage(createUserInterruptionMessage(false));")
            .contains("tengu_pre_stop_hooks_cancelled");
        assertThat(secondBranch)
            .as("§14 stopAborted 分支必须附加 createUserInterruptionMessage(false)（CC stopHooks.ts:290）")
            .contains("state.appendMessage(createUserInterruptionMessage(false));")
            .contains("tengu_pre_stop_hooks_cancelled");
    }

    // ─────────────────────── 5. L4 尾段不丢（OD-04/INV-2） ───────────────────────

    @Test
    @DisplayName("L4 压缩成功后必须用压缩结果（含 messagesToKeep 尾段）直接取代消息链（OD-04/INV-2 · GR-1）")
    void compactResult_messagesFlowThroughUnchanged() throws IOException {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        // 【GR-1 返工】主自动压缩已迁移到 compactConversation 单函数 → 只有 l4Result.messages()
        // （buildPostCompactMessages 组装，含 messagesToKeep 完整尾段）取代消息链；
        // 旧的手工组装 state.replaceMessages(compactTarget) 已被 GR-1 移除。
        assertThat(source)
            .as("GR-1 自动压缩成功后用 l4Result.messages()（compactConversation 单函数 buildPostCompactMessages 完整尾段）取代消息链（L4 尾段不丢）")
            .contains("state.replaceMessages(l4Result.messages());");
    }

    // ─────────────────────── helpers ───────────────────────

    private static ChatMessageDto message(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto boundaryMessage() {
        return CompactBoundaryMessage.createCompactBoundaryMessage("auto", 100, null, null, null)
            .toChatMessageDto();
    }
}
