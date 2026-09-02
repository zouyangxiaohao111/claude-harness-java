package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session F — AgentLoop 调用层对齐 + MC 纠正实证 + drift +11 + ALI-1 惰性入口.
 *
 * <p><b>WHY (意图验证, 规则九)</b>: V2 {@code 探查-tool-CC对比-缺口-V2.md} §2.5 (MC-1/2/3 纠正)
 * + §2.6 (gitnexus 行漂移 +11) + §2.1 (ALI-1 getRemainingResults 阻塞 vs CC AsyncGenerator
 * 惰性 yield) 是本 session 的修复目标. 本测试锚定这些纠正, 防止后续 commit 让行号回退 /
 * 惰性语义退化回一次性阻塞.
 *
 * <p><b>行号断言设计 (Pattern #2)</b>: 探查报告与 F.md 的行号都是快照, 必然漂移. 因此本测试
 * 读取当前源码, 断言关键符号落在<b>容差区间</b> (V2 MC-2 区间 [3879,3900] 原样保留;
 * drift 符号 ±8 行容差), 而非精确行号 — 精确断言反而会在下一次合理插入后误报.
 *
 * <p><b>CC 真源</b>:
 * <ul>
 *   <li>MC-2 4 gates — {@code LlmAgentLoop.java:3879-3900} (V2 §2.5 实证)</li>
 *   <li>MC-3 A2-P0-1 注释块 — {@code LlmAgentLoop.java:4214-4224} (V2 §2.5 实证)</li>
 *   <li>ALI-1 — {@code Open-ClaudeCode/src/services/tools/StreamingToolExecutor.ts:453-490}
 *       getRemainingResults AsyncGenerator 逐条 yield; {@code query.ts:1380-1408} for-await 消费</li>
 * </ul>
 */
class LlmAgentLoopDriftAndLazinessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LLM_AGENT_LOOP =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";
    // [merge] H7-arch Phase 5-2 P3-⑤: buildStreamingExecutor/runTools/handleToolCallsTurn
    //   已 static 化搬入 AgentLoopContext (LlmAgentLoop 仅保留 run 适配), 行号锚定随之迁移.
    private static final String AGENT_LOOP_CONTEXT =
        "src/main/java/com/nexusai/application/agent/loop/AgentLoopContext.java";
    private static final String AGENT_TURN_EXECUTOR =
        "src/main/java/com/nexusai/application/agent/tool/impl/AgentTurnExecutor.java";

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "laziness-test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ─────────────────────────── MC 纠正 + drift 实证 (行号锚定) ───────────────────────────

    @Test
    @DisplayName("MC-1 演进: AgentTurnExecutor 已删除, 子 Agent 走 queryLoop (H7-arch Phase 2)")
    void executeAgentTurn_removed_byH7arch_queryLoopReplaces() throws IOException {
        // WHY: V2 §2.5 MC-1 纠正 executeAgentTurn 11→10 参; C session (J.md C-R3) 折入
        //   MultiTurnRequest 单参; H7-arch Phase 2 (origin/master 1a038ea) 整文件删除
        //   AgentTurnExecutor, 子 Agent 路径改走 LlmAgentLoop.queryLoop 单一循环源.
        //   锚定当前契约: 文件不存在 + SubagentExecutor 调 queryLoop. 防回退到
        //   AgentTurnExecutor 双循环源 (CC runAgent 复用 query() 语义).
        assertThat(Files.exists(Path.of(AGENT_TURN_EXECUTOR)))
            .as("AgentTurnExecutor.java 必须不存在 (H7-arch Phase 2 删除)")
            .isFalse();
        List<String> seLines = Files.readAllLines(Path.of(
            "src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java"));
        // [merge] B1: queryLoop 签名 QueryParams 化 (deps 折入 QueryParams.deps + state + uuids)
        assertThat(findLine(seLines, "LlmAgentLoop.queryLoop(queryParams, state,"))
            .as("SubagentExecutor 必须调 queryLoop (单一循环源)")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("MC-2: 4 gates 读取方法存在 (H7 合并后重定位)")
    void fourGates_readingMethods_locationsIn_range() throws IOException {
        // WHY: V2 §2.5 MC-2 实证区间 [3879,3900] 是合并前快照; H7-arch 重构后 3 gates
        //   留在 LlmAgentLoop (Stream-A2 便捷取值), isEmitToolUseSummariesEnabled 已 static
        //   化搬入 AgentLoopContext (P3-⑤). 锚定"存在 + 在预期区间", 防止后续 commit
        //   把 gates 移走或删掉 (gates 是 QueryConfig 便捷取值, 全 LlmAgentLoop 分支依赖).
        //   [H14] 区间上移: H14 在 run() 入口加 registerSessionFileAccessHooks 注册 +
        //   PostCompactCleanup 钩子 (26 行), gates 从 4328-4340 漂到 4354-4366. 区间
        //   锚定仍防"移走/删除", 具体行号允许正常插入漂移 (Pattern #2).
        List<String> lines = Files.readAllLines(Path.of(LLM_AGENT_LOOP));
        for (String sig : List.of(
            "public boolean isStreamingToolExecutionEnabled()",
            "public boolean isAnt()",
            "public boolean isFastModeEnabled()")) {
            int line = findLine(lines, sig);
            assertThat(line)
                .as("gate accessor 行号应落在合并后区间: %s", sig)
                // [workflow 合并] j-integration(H11-H14) + H6/H10-fix + H9/H14 v3 全量合入后,
                //   isFastModeEnabled() 实测 4397 → 4448 → (H13 v3 +代码后) 4480.
                //   H13 v3 新增 STREAM_EXECUTOR 字段 + STOP/SessionEnd agent_type 注入 +
                //   ModelRequest abortController → gates 漂到 4468-4480.
                //   [H3 v4] injectHookResultMessage 生产者侧注入方法 (+25 行) → gates 漂到 4525.
                //   放宽到 [4300,4560] 覆盖实测, 锚定仍防"移走/删除" (Pattern #2 行号漂移).
                //   [IMP-15 REWORK] LlmAgentLoop 恢复块接线 (maxOutputTokensOverrideRef +
                //     ESCALATED/CONTINUATION override 结转 + 耗尽 surface 消息 + 辅助方法 ~+35 行)
                //     → gates 实测 4560/4566/4572. 上界放宽到 4600 (±8 容差覆盖 4572).
                //   [workflow 合并收尾] LlmAgentLoop gates 实测 4602/4608/4614, 三 gate 集中
                //     区间, 单区间覆盖 ±8 得 [4594,4622] (沿用文件既有『三 gate 单区间』风格).
                //   [ContextCompact 对齐 + MF3-3] 工作树 ContextCompact/GR-3 接线 (~+53 行)
                //     + MF3-3 max_tokens override 接线 (decl+4, 双守卫-6, CONTINUATION+2,
                //     ESCALATED+7, 耗尽消息+10, next_turn+2 = +19) → gates 实测 4676/4682/4688.
                //     按 ±8 容差以实测三 gate 集中区间重锚 → [4668,4696].
                //   [origin/master 合并 2026-08-05] todo-write 线合入后实测 4681/4687/4693
                //     (+5: LlmAgentLoop 新增 TodoWrite reminder 注入段) → 重锚 [4673,4701].
                // [memory-align × system_prompt 合并 2026-08-06] 两侧改造合入后 LlmAgentLoop
                //   三 gate 实测 4778/4784/4790（memory 接线 + IMP-SP-08 s10 新链并存）。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [4770,4798]（三 gate 单区间风格）。
                // [residual2 实施批次 2026-08-07] R1/R2/R6/SP31/RES-R4 累积接线（compact fork
                //   缓存共享 + resume + append 通道 + useGlobalCacheScope 注入）→ 三 gate 实测
                //   4835/4841/4847。按 ±8 容差以实测三 gate 集中区间重锚 → [4827,4855]。
                // [S3 压缩块接线 2026-08-07] snip 门控 + micro 接线 + B2 透传 + B4/B6 blocking
                //   窗口（~+72 行）→ 三 gate 实测 4990/4996/5002。按 ±8 容差以实测三 gate
                //   集中区间重锚 → [4982,5010]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [OD-01 S3 2026-08-07] 压缩块接线全量落地 → 三 gate 实测 5001/5007/5013。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [4999,5015]。
                // [OD-17 再思考 2026-08-07] LlmAgentLoop STREAM_EXECUTOR 前新增 MDC context map
                //   捕获/回放块（~+16 行，:3139）→ 三 gate 实测 5018/5024/5030。按 ±8 容差以
                //   实测三 gate 集中区间重锚 → [5010,5038]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [ER-IMP-02 2026-08-07] checkTokenBudget 入参 agentId 对齐 CC 主线程语义
                //   （:2677 注释 + isSubagent 判定 ~+16 行）→ 三 gate 实测 5041/5047/5053。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [5033,5061]（Pattern #2 行号漂移）。
                // [ER-IMP-03 2026-08-07] withRetry 引擎重建（Path3 重写 ~+40 行 + 闭包计数器
                //   + try/catch + next_turn 复位 ~+86 行，均在三 gate 之前）→ 三 gate 实测
                //   5125/5131/5137。按 ±8 容差以实测三 gate 集中区间重锚 → [5117,5145]。
                // [ER-IMP-04 2026-08-07] Path3 529 前台/后台甄别闸 + RetryOptions/retryContext
                //   上移 + isRetryable 入口门（~+19 行，均在三 gate 之前）→ 三 gate 实测
                //   5144/5150/5156。按 ±8 容差以实测三 gate 集中区间重锚 → [5136,5164]。
                // [ER-IMP-06 2026-08-07] Path3 持久重试 + fast-mode fallback/cooldown 接线
                //   （wasFastModeActive/persistent 计算 + fast-mode fallback 三分支 + 持久分片
                //   sleep + 静态 gate 读取辅助 + 抑制闸 isFastModeRejectedRetryable 排除 ~+126 行，
                //   均在三 gate 之前）→ 三 gate 实测 5252/5258/5271。按 ±8 容差以实测三 gate
                //   集中区间重锚 → [5244,5279]。
                // [ER-IMP-07 2026-08-07] DC-23 移除 isStreamErrorSuppressed 流式期错误抑制
                //   （静默块 ~-14 行 + 方法 ~-35 行，均在三 gate 之前）→ 三 gate 实测 5214/5220/5233。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [5206,5241]。
                // [ER-IMP-08 2026-08-07] PTL 恢复链对齐（loop 局部 retryContextMaxTokensOverride +
                //   入口 override 继承 + Path3 overflow 调整分支 + PTL drain/compact 复位 + media
                //   门控 hoist ~+60 行，均在三 gate 之前）→ 三 gate 实测 5274/5280/5293。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [5266,5301]。
                // [ER-IMP-09 2026-08-07] stop-hook 重入守卫 + hook_stopped 终止 + 预算复位
                //   （loop 入口守卫保留块 + ContinueDecision 复位 + 工具 turn hook_stopped 扫描
                //   ~+54 行，均在三 gate 之前）→ 三 gate 实测 5347/5353/5366。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [5339,5374]。
                // [ER-IMP-10 2026-08-07] fallback 机制对齐（tombstone/query_error 遥测埋点 +
                //   handle fallbackModel 形参 + handleModelFallback renderModelName + 资格闸，
                //   均在三 gate 之前）→ 三 gate 实测 5383/5389/5402。按 ±8 容差以实测三 gate
                //   集中区间重锚 → [5375,5410]。
                // [ER-IMP-11 2026-08-08] max_tokens 耗尽路径接线（skipStopPipeline=true +
                //   StopFailure hook + isApiErrorMessage() 门控读取，~+22 行，均在三 gate 之前）
                //   → 三 gate 实测 5416/5422/5435。按 ±8 容差以实测三 gate 集中区间重锚
                //   → [5408,5443]。
                // [ER-IMP-12 2026-08-08] ImageValidator 前置校验（~+21 行）+ isImageError
                //   instanceof 前置（+6 行）+ 门翻转/createUserInterruptionMessage 常量（后置）
                //   → 三 gate 实测 5442/5448/5461。按 ±8 容差以实测三 gate 集中区间重锚
                //   → [5434,5469]。
                // [ER-IMP-13 2026-08-08] token-budget 语义对齐（doRun 入口 +500k 接线 ~+9 行 +
                //   budget 源改 state.turnTokenBudget −2 行 + StopDecision null 分支 +6 行 + reactive
                //   carryover ~+9 行，均在三 gate 之前）→ 三 gate 实测 5478/5484/5497。按 ±8 容差以
                //   实测三 gate 集中区间重锚 → [5470,5505]。
                // [ER-IMP-14 2026-08-08] sourceToolAssistantUUID 父链归因（三 inline synthetic error
                //   路径补传 turnAssistantId + 注释 ~+9 行，均在三 gate 之前）→ 三 gate 实测
                //   5487/5493/5506。按 ±8 容差以实测三 gate 集中区间重锚 → [5479,5514]。
                // [ER-IMP-15 2026-08-08] DC-26 删除 LlmAgentLoop 实例 applyPerMessageBudget 及其
                //   私有 helper（collectCandidatesByMessage/sumSize/selectFreshToReplace/
                //   replaceMessageContent，~-204 行，均在三 gate 之前）→ 三 gate 实测 5283/5289/5302。
                //   按 ±8 容差以实测三 gate 集中区间重锚 → [5275,5310]。
                // [B-TB D3 2026-08-08] token-budget 时机对齐（pre-model-call 检查 ~67 行移除 +
                //   post-response 检查 ~40 行新增，净 -27 行，均在三 gate 之前）→ 三 gate 实测
                //   5140/5146/5159。按 ±8 容差以实测三 gate 集中区间重锚 → [5132,5167]。
                // [V-TOK 2026-08-08] V-TOK-01 outputTokens 提取（AssistantMessage +13 行 +
                //   AnthropicSdkProvider message_delta usage 提取 +15 行）+ V-TOK-03 跨 turn 预算复用（+4 行）+
                //   V-TOK-04 stop hooks 移入 do-while budget check 前（+40 行，均在三 gate 之前）
                //   -> 三 gate 实测 5280/5286/5299。按 ±8 容差以实测三 gate 集中区间重锚 -> [5272,5307]。
                // [V-EC-3 2026-08-08] Path3 陈旧连接检测块（~+13 行，均在三 gate 之前）
                //   -> 三 gate 实测 5297/5303/5316。按 ±8 容差以实测三 gate 集中区间重锚 -> [5289,5324]。
                // [V-PF 2026-08-08] V-PF-2 fast-mode 分支重排（副作用先行、耗尽检查后置，
                //   分支改嵌套 if/else + 注释 ~+15 行）+ V-PF-3 fastModeTemporarilyDisabled episode
                //   标志接线（~+5 行），均在三 gate 之前 -> 三 gate 实测 5317/5323/5336。按 ±8 容差
                //   以实测三 gate 集中区间重锚 -> [5309,5344]。
                // [V-SH 2026-08-08] V-SH-1 §14 HOOK_STOPPED gate + V-SH-3 effectiveStopHookActive
                //   + V-SH-4 lastReason 接线（loop 入口 +6 / 局部 decl +7 / ContinueDecision +6 /
                //   §14 gate +10，净 +29 行，均在三 gate 之前）-> 三 gate 实测 5352/5358/5371。
                //   按 ±8 容差以实测三 gate 集中区间重锚 -> [5344,5379]。
                // [V-IMG 2026-08-09] V-IMG-01 Stop hook 中断附加用户消息（两处 +8 行）+ V-IMG-02
                //   isImageError 移除 ImageResizeError 死检查（+4 行），均在三 gate 之前 -> 三 gate
                //   实测 5367/5373/5386。按 ±8 容差以实测三 gate 集中区间重锚 -> [5359,5394]。
                // [V-SH 返工 2026-08-09] s09 HOOK_STOPPED gate 追加（+8 行，s09 位于三 gate 之前）
                //   -> 三 gate 实测 5381/5387/5400。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [5373,5408]。
                // [V-FB 返工 2026-08-09] V-FB-03 tengu_query_error 全量计数（state.messages() stream
                //   assistant 消息数 + tool_use 块数，替代 0/1 硬编码，~+13 行，位于三 gate 之前）
                //   -> 三 gate 实测 5393/5399/5412。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [5385,5420]。
                // [V-IMG 返工 2026-08-09] V-IMG-01 补 tengu_pre_stop_hooks_cancelled 遥测
                //   （in-loop +6 行 / §14 +6 行 = +12 行，均在三 gate 之前）-> 三 gate 实测
                //   5407/5413/5426。按 ±8 容差以实测三 gate 集中区间重锚 -> [5399,5434]。
                // [V-TOK/DEC-RV-04 2026-08-09] 当前帧 tokens 累计移至 Stop hooks 评估前
                //   （累积块 ~+13 行，在纯文本分支，位于三 gate 之前）-> 三 gate 实测
                //   5420/5426/5439。按 ±8 容差以实测三 gate 集中区间重锚 -> [5412,5447]。
                // [DEC-RV-05 2026-08-09] s09 异常结束路径跳过 memory extract 门扩展
                //   （DEC-RV-05 注释块 + 内层 s09Reason 排除门 ~+25 行 + CannotRetryException
                //   注释修订 +2 行，均在三 gate 之前）-> 三 gate 实测 5446/5452/5465。按 ±8
                //   容差以实测三 gate 集中区间重锚 -> [5438,5473]。
                // [DEC-RV-05 返工 2026-08-09] stopAborted 映射 STOP_HOOK_PREVENTED 注释块
                //   （in-loop +6 行 / §14 +6 行 / s09 注释 +7 行，均在三 gate 之前）-> 三 gate
                //   实测 5460/5466/5479。按 ±8 容差以实测三 gate 集中区间重锚 -> [5452,5487]。
                // [RV14B-WIRE-04 2026-08-10] LlmAgentLoop 新增 modelConfigResolver 字段/setter +
                //   triggerSkillCatalogHaikuSummaryAsync 接线（~+56 行，均在三 gate 之前）-> 三
                //   gate 实测 5508/5514/5527。按 ±8 容差以实测三 gate 集中区间重锚 -> [5500,5535]。
                // [RV-FOLLOWUP MAINCHAIN-01 2026-08-10] :2833 主链 2 参 getProvider 改造（+4 行）
                //   + 新增静态 resolveMainProviderType helper（~+29 行，位于三 gate 之前）
                // [CRON-D2 2026-08-10] RUNNING_SESSIONS 静态注册表（markRunning/markIdle/isSessionRunning ~+27 行，类头部）
                // [CRON-D3 2026-08-10] 删 cronNotificationQueue 死通道（字段/setter ~23 行 + cron drain 块 ~16 行）
                // [CRON-D5 2026-08-10] 通知 drain 对齐 CC query.ts:1566-1643（~+17 行）
                // [MERGE 2026-08-10] MAINCHAIN-01 + CRON-D2/D3/D5 全量合入后实测 5544/5550/5563。
                //   按 ±8 容差以实测三 gate 集中区间重锚 -> [5536,5571]。
                // [DEL-13/14/15 / OPD-TS-28 2026-08-10] W3-04 删除 cronNotificationQueue /
                //   CommandQueue 字段与 setter + NotificationQueue hook 接线（~-30 行，位于三 gate
                //   之前）-> 三 gate 实测 5512/5518/5531。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [5504,5539]。
                // [WF3-01/02 追加 2026-08-10] SdkEventQueue 出站 drain 段 + modelConfigResolver/
                //   triggerSkillCatalogHaikuSummaryAsync 接线等（~+22 行，位于三 gate 之前）-> 三
                //   gate 实测 5534/5540/5553。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [5526,5561]。
                // [WF-3 合并冲突解决 2026-08-11] CRON-D2/D3/D5 + DEL-13/14/15 + WF3-01/02 两侧
                //   全量融合落盘后实测三 gate 5567/5573/5586（LlmAgentLoop.java 6812 行）。
                //   按 ±8 容差以实测三 gate 集中区间重锚 -> [5559,5594]。
                // [F-3 EVD-B 返工 2026-08-11] F-2/EVD-B 接线改动（PersistReplacements 辅助方法
                //   + 门控扩展，位于三 gate 之前）-> 三 gate 实测 5636/5642/5655（LlmAgentLoop.java
                //   shouldPersistReplacements :5628 前置）。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [5628,5663]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [WF-5 + WF-8 merge 2026-08-11] WF-5 合入重锚 [5616,5651]；再合入 WF-8 GAP-R1
                //   （STREAM_EXECUTOR teammate context 捕获/回放 +23 行）→ merge 后重新 grep
                //   实测三 gate 5647/5653/5666，按 ±8 容差集中区间重锚 -> [5639,5674]。
                // [task-align-merge 2026-08-12] task-system-align 大分支（WF 全量 + 本 hook message
                //   改造）合入后三 gate 实测 5754/5760/5773（LlmAgentLoop.java 6812+ 行）。按 ±8
                //   容差以实测三 gate 集中区间重锚 -> [5746,5781]（Pattern #2 行号漂移，锚定仍防
                //   "移走/删除"）。
                // [RE-THINK 2026-08-12] LlmAgentLoop.injectHookResultMessage 新增
                //   appendPlainHookMessage 私有方法（+25 行，位于三 gate 之前）→ 三 gate 实测
                //   5779/5785/5798。按 ±8 容差以实测三 gate 集中区间重锚 -> [5771,5806]。
                // [IMP-HOOKS-S5 2026-08-14] 生命周期对齐重构（stop hooks collecting API + §14
                //   重排 + s09 门扩展，位于三 gate 之前）→ 三 gate 实测 5925/5931/5944。
                //   按 ±8 容差以实测三 gate 集中区间重锚 -> [5917,5952]。
                // [R2-USAGE 2026-08-13] LlmAgentLoop 文本分支/max_tokens 恢复分支 assistant 消息
                //   附加 usage（withUsage，+7 行，位于三 gate 之前）→ 三 gate 实测 5815/5821/5834。
                //   按 ±8 容差以实测三 gate 集中区间重锚 -> [5807,5842]。
                // [WF-2 tool-wf2-exec-registry 2026-08-13] B5 tool-reference 惰性接线等
                //   LlmAgentLoop 改动后三 gate 实测 5850/5856/5869。按 ±8 容差以实测三 gate
                //   集中区间重锚 -> [5842,5877]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [merge 冲突解决 2026-08-14] 两侧 (R2-USAGE + WF-2 及主线后续改动) 合入后
                //   LlmAgentLoop 三 gate 实测 5986/5992/6005。按 ±8 容差以实测三 gate
                //   集中区间重锚 -> [5978,6013]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [ContextCompact 合入 master 2026-08-14] 测试适配 + 既有接线后三 gate
                //   实测 6168/6174/6187。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [6160,6195]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [merge 终局 2026-08-14] LlmAgentLoop.java 冲突解决合入（master 侧前置内容
                //   ~+52 行）后三 gate 实测 6220/6226/6239。按 ±8 容差以实测三 gate
                //   集中区间重锚 -> [6212,6247]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [resolve:mch 2026-08-14] master × workflow/hooks 合入终局后实测
                //   isStreamingToolExecutionEnabled :6313 / isAnt :6319 / isFastModeEnabled :6332
                //   （LlmAgentLoop.java 合入后无冲突，实测即终局）。按 ±8 容差以实测三 gate
                //   集中区间重锚 -> [6305,6340]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [IMP-CM-09 2026-08-15] registerSessionFileAccessHooks 生产构造升级双门控拆分
                //   （SessionFileAccessHooks 三参构造 + 注释 ~+12 行，位于三 gate 之前）→ 三 gate
                //   实测 6348/6354/6367。按 ±8 容差以实测三 gate 集中区间重锚
                //   -> [6340,6375]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [origin/master 合并 2026-08-15] hooks_v3 阶段4 移 in-loop + session-project-root
                //   IMP-F 参数化合并后实测 isStreamingToolExecutionEnabled :6897 / isAnt :6903 /
                //   isFastModeEnabled :6916（三 gate 集中区间）。按 ±8 容差以实测重锚
                //   -> [6889,6924]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [subagent_v3 rebase 2026-08-17] 合入 master(c8fd44cf 含 d17e5a5a) 后三 gate 实测
                //   7263/7269/7282（本 rebase 终局实测，含 hooks_v4-impl 移入）。按 ±8 容差以实测
                //   三 gate 集中区间重锚 -> [7255,7290]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                // [tool_v4 合并 2026-08-22] tool-v4 全量接线 + 合并后三 gate 实测
                //   7825/7831/7845（LlmAgentLoop.java 行号大幅漂移）。按 ±8 容差以实测
                //   三 gate 集中区间重锚 -> [7817,7853]（Pattern #2 行号漂移，锚定仍防"移走/删除"）。
                .isBetween(7817, 7853);

        }
        // isEmitToolUseSummariesEnabled → AgentLoopContext static (private, ctx-aware)
        // 锚定"存在 + 在预期区间"，防"移走/删除"（Pattern #2 行号漂移惯例）。
        List<String> ctxLines = Files.readAllLines(Path.of(AGENT_LOOP_CONTEXT));
        assertThat(findLine(ctxLines, "boolean isEmitToolUseSummariesEnabled(AgentLoopContext ctx)"))
            .as("isEmitToolUseSummariesEnabled 应已 static 化搬入 AgentLoopContext")
            // [workflow 合并] H8+canUseTool+H9 v2 全量合入后实测 1422 行.
            //   ±8 容差重锚 [1414,1430], 锚定仍防"移走/删除" (Pattern #2 行号漂移).
            // [H13-GAP v3] toolExecContext 新增 hook DONT_ASK permCtx 保留分支 (+13 行) → 实测 1435.
            //   按 ±8 容差以实测 1435 为中心重锚 → [1427,1443].
            // [IMP-15] AgentLoopContext 新增 max_output_tokens 接线段 (~+72 行) → 实测 1512.
            //   按 ±8 容差以实测 1512 为中心重锚 → [1504,1520].
            // [IMP-22] merger 迁移去重删除 AgentLoopContext 私有 collectCandidatesByMessage (~-22 行)
            //   → 实测 1489. 按 ±8 容差以实测 1489 为中心重锚 → [1481,1497].
            // [GR-3] AgentLoopContext 移除 compactContext record 组件 + computeBlockingLimit 改造
            //   → 实测 1499. 按 ±8 容差以实测 1499 为中心重锚 → [1491,1507].
            // [workflow 合并收尾] AgentLoopContext.isEmitToolUseSummariesEnabled 实测 1601,
            //   按 ±8 容差以实测 1601 为中心重锚 → [1593,1609].
            // [origin/master 合并 2026-08-05] 实测 1646 → 重锚 [1638,1654].
            // [IMP-SP-08] AgentLoopContext 删 promptAssembler/systemPromptCache record 组件 +
            //   static buildPromptContext（~-30 行）→ 实测 1615. ±8 容差重锚 [1607,1623].
            // [OD-01 S3 2026-08-07] 压缩块接线（snip 门控+micro+B2/B4/B6 ~+215 行）→ 实测 1674.
            //   ±8 容差重锚 [1666,1682].
            // [ER-IMP-12 2026-08-08] aborted_tools 门翻转（~+2 行）→ 实测 1683. ±8 容差重锚
            //   [1675,1691].
            // [B-TB D1 2026-08-08] getLastAssistantMessage 新增（~+18 行，在此标记之前）→ 实测 1701.
            //   ±8 容差重锚 [1693,1709].
            // [RV14B-WIRE-04 2026-08-10] AgentLoopContext 新增 resolver 组件 + 35 参兼容 ctor +
            //   两个 Haiku helper（~+102 行，在此标记之前）→ 实测 1803. ±8 容差重锚 [1795,1811].
            // [R2-USAGE 2026-08-13] handleToolCallsTurn assistant 消息 withUsage（+6 行，在此标记
            //   之前）→ 实测 1847. ±8 容差重锚 [1839,1855].
            // [RF-1 2026-08-14] handleToolCallsTurn 新增 thinkingConfig 形参 + 顶部新增 ThinkingConfig
            //   import（+2 行，在此标记之前）→ 实测 1856. ±8 容差重锚 [1848,1864].
            // [WF-2 tool-wf2-exec-registry 2026-08-13] lazy-stream 接线（AgentLoopContext +5 行）
            //   → 实测 1849. ±8 容差重锚 [1841,1857]（Pattern #2 行号漂移）。
            // [merge 冲突解决 2026-08-14] 两侧 (R2-USAGE + RF-1 + WF-2) 合入后 AgentLoopContext
            //   isEmitToolUseSummariesEnabled 实测 1881. ±8 容差重锚 [1873,1889].
            // [ContextCompact 合入 master 2026-08-14] 实测 1876. ±8 容差重锚 [1868,1884].
            // [hooks × origin/master 合入 2026-08-14] workflow/hooks (IMP-HOOKS-S5) 与
            //   origin/master 全量合入后（stop-hook 相关段搬迁/精简 ~-36 行）实测 1845.
            //   ±8 容差重锚 [1837,1853].
            // [resolve:mch 2026-08-14] 合入终局后实测 1840（AgentLoopContext.java 无冲突）.
            //   ±8 容差重锚 [1832,1848].
            // [subagent_v3 rebase 2026-08-17] 合入 master 后实测 1895. ±8 容差重锚
            //   [1887,1903]（Pattern #2 行号漂移，锚定仍防"移走/删除"）.
            // [tool_v4 合并 2026-08-22] tool-v4 接线后实测 1914. ±8 容差重锚
            //   [1906,1922]（Pattern #2 行号漂移，锚定仍防"移走/删除"）.
            .isBetween(1906, 1922);

    }

    @Test
    @DisplayName("MC-3: handleToolCallsTurn A2-P0-1 删除外层 PostToolUse 注释块在 AgentLoopContext")
    void handleToolCallsTurn_deletionBlock_in_range() throws IOException {
        // WHY: V2 §2.5 MC-3 实证区间 [4214,4240] 是合并前快照; H7-arch Phase 5-2 P3-⑤ 后
        //   handleToolCallsTurn 连同 A2-P0-1 WHY 注释块 static 化搬入 AgentLoopContext.
        //   该块记录"删除外层 PostToolUse hook"的 WHY (双发风险), 误删回外层调用会导致
        //   hook 双发回归.
        List<String> lines = Files.readAllLines(Path.of(AGENT_LOOP_CONTEXT));
        int line = findLine(lines, "删除外层 PostToolUse");
        assertThat(line)
            // [workflow 合并] H8+canUseTool+H9 v2 全量合入后实测 1294 行.
            //   ±8 容差重锚 [1286,1302], 锚定仍防"误删回外层调用" (Pattern #2 行号漂移).
            // [H13-GAP v3] toolExecContext 新增 hook DONT_ASK 保留分支 (+13 行) → 实测 1307.
            //   按 ±8 容差以实测 1307 为中心重锚 → [1299,1315].
            // [IMP-15] AgentLoopContext 新增 max_output_tokens 接线段 (~+72 行) → 实测 1384.
            //   按 ±8 容差以实测 1384 为中心重锚 → [1376,1392].
            // [IMP-22] merger 迁移去重删除 AgentLoopContext 私有 collectCandidatesByMessage (~-22 行)
            //   → 实测 1361. 按 ±8 容差以实测 1361 为中心重锚 → [1353,1369].
            // [GR-3] AgentLoopContext 移除 compactContext record 组件 + computeBlockingLimit 改造
            //   → 实测 1371. 按 ±8 容差以实测 1371 为中心重锚 → [1363,1379].
            // [workflow 合并收尾] '删除外层 PostToolUse' 实测 AgentLoopContext:1473,
            //   按 ±8 容差以实测 1473 为中心重锚 → [1465,1481].
            // [origin/master 合并 2026-08-05] 实测 1518 → 重锚 [1510,1526].
            // [IMP-SP-08] 同上（~-30 行）→ 实测 1487. ±8 容差重锚 [1479,1495].
            // [OD-01 S3 2026-08-07] 压缩块接线（snip 门控+micro+B2/B4/B6 ~+215 行）→ 实测 1546.
            // [RV14B-WIRE-04 2026-08-10] AgentLoopContext 新增 resolver 组件 + 35 参兼容 ctor +
            //   两个 Haiku helper（~+98 行，在此标记之前）→ 实测 1666. ±8 容差重锚 [1658,1674].
            // [IMP-HOOKS-S5 2026-08-14] 生命周期对齐重构（AgentLoopContext stop-hook 相关段
            //   搬迁/精简 ~-36 行）→ 实测 1630. ±8 容差重锚 [1622,1638].
            // [REW-PROGRESS R32-03 2026-08-13] ProgressMessage 生产接线（本 Session，未提交）：
            //   runTools/handleToolCallsTurn 新增 onToolProgress 形参（签名 +2 行，均在此标记之前）
            //   + runTools 内 [REW-PROGRESS R32-03] onToolProgress 注入注释块（+5 行）= 增行 +7
            //   （fa4590a1 实测 1669 → 1669+7=1676）。按 ±8 容差以实测 1676 为中心重锚 [1668,1684]
            //   （Pattern #2 行号漂移，锚定仍防"误删回外层调用"，块完整：1676-1682 含 CC
            //   toolExecution.ts:1483 runPostToolUseHooks 引用）。
            .as("A2-P0-1 注释块应落在合并后区间 (1663-1679)")
            // [merge master 2026-08-14] D-1~D-8 子残留 + IPD 删除合入 → 实测 1702. ±8 重锚 [1694,1710].
            // [hooks × origin/master 合入 2026-08-14] 两侧全量合入后实测 1666. ±8 容差重锚
            //   [1658,1674].
            // [resolve:mch 2026-08-14] 合入终局后实测 1661（AgentLoopContext.java 无冲突）.
            //   ±8 容差重锚 [1653,1669].
            // [tool_v3 2026-08-16] 主代码对齐 CC 后实测 1671（tool_v3 接线位于该块之前）.
            //   ±8 容差重锚 [1663,1679]（Pattern #2 行号漂移，锚定仍防"误删回外层调用"）.
            // [tool_v4 合并 2026-08-22] tool-v4 接线后实测 1690. ±8 容差重锚
            //   [1682,1698]（Pattern #2 行号漂移，锚定仍防"误删回外层调用"）.
            .isBetween(1682, 1698);
    }

    @Test
    @DisplayName("drift: buildStreamingExecutor/runTools/handleToolCallsTurn 锚定 AgentLoopContext 行号 ±8")
    void driftAnchored_callLayerMethods_atCurrentLines() throws IOException {
        // WHY: V2 §2.6 gitnexus 行漂移 +11 (3955→3966→3968 再漂); H7-arch Phase 5-2 P3-⑤
        //   后 buildStreamingExecutor/runTools/handleToolCallsTurn static 化搬入 AgentLoopContext
        //   (LlmAgentLoop 仅剩 run 适配 + 轻方法). 锚定 AgentLoopContext ±8 容差,
        //   防止大段误删导致行号回退 (回退 = 有人删了 A2-P0-1 或 gate 区的代码).
        List<String> lines = Files.readAllLines(Path.of(AGENT_LOOP_CONTEXT));
        int build = findLine(lines, "public static StreamingToolExecutor buildStreamingExecutor(AgentLoopContext ctx,");
        int run = findLine(lines, "private static com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome runTools(");
        int handle = findLine(lines, "public static String handleToolCallsTurn(AgentLoopContext ctx,");
        // [workflow 合并] H8+canUseTool+H9 v2 全量合入后实测 build=1071 / run=1153 / handle=1230.
        //   ±8 容差重锚, 锚定仍防"大段误删导致行号回退" (Pattern #2 行号漂移).
        // [H13-GAP v3] toolExecContext 新增 hook DONT_ASK 保留分支 (+13 行) → 实测 1084/1166/1243.
        //   按 ±8 容差重锚 → [1076,1092] / [1158,1174] / [1235,1251].
        // [IMP-15] AgentLoopContext 新增 max_output_tokens 接线段 (~+72 行) → 实测 1161/1243/1320.
        //   按 ±8 容差重锚 → [1153,1169] / [1235,1251] / [1312,1328].
        // [IMP-22] merger 迁移去重删除 AgentLoopContext 私有 collectCandidatesByMessage (~-22 行)
        //   → 实测 1138/1220/1297. 按 ±8 容差重锚 → [1130,1146] / [1212,1228] / [1289,1305].
        // [GR-3] AgentLoopContext 移除 compactContext record 组件 + computeBlockingLimit 改造
        //   → 实测 1148/1230/1307. 按 ±8 容差重锚 → [1140,1156] / [1222,1238] / [1299,1315].
        // [workflow 合并收尾] AgentLoopContext 实测 build=1250 / run=1332 / handle=1409,
        //   按 ±8 容差重锚 → [1242,1258] / [1324,1340] / [1401,1417].
        // [origin/master 合并 2026-08-05] 实测 build=1295 / run=1377 / handle=1454
        //   (ContextCompact 对齐 +skillSearchPrefetch 组件位) → 重锚 [1287,1303]/[1369,1385]/[1446,1462].
        // [IMP-SP-08] 实测 1264/1346/1423（删 static buildPromptContext ~-26 行）→ ±8 重锚
        // [OD-01 S3 2026-08-07] 压缩块接线 → 实测 build=1323/run=1405/handle=1482。±8 重锚。
        // [B-TB D1 2026-08-08] getLastAssistantMessage 新增（~+18 行，均在三标记之前）→ 实测 build=1345/run=1427/handle=1504。±8 重锚。
        // [RV14B-WIRE-04 2026-08-10] AgentLoopContext 新增 resolver 组件 + 35 参兼容 ctor +
        //   两个 Haiku helper（~+98 行，均在三标记之前）→ 实测 build=1443/run=1525/handle=1602。±8 重锚。
        // [ContextCompact 合入 master 2026-08-14] 实测 build=1451 / run=1541 / handle=1628.
        //   按 ±8 容差重锚 → [1443,1459] / [1533,1549] / [1620,1636].
        // [IMP-HOOKS-S5 2026-08-14] 生命周期对齐重构（AgentLoopContext stop-hook 相关段
        //   搬迁/精简 ~-36 行）→ 实测 build=1407/run=1489/handle=1566。±8 重锚。
        // [merge master 2026-08-14] D-1~D-8 + IPD 删除合入 → 实测 build=1456/run=1546/handle=1633。±8 重锚。
        // [resolve:mch 2026-08-14] master × workflow/hooks 合入终局后实测
        //   build=1415/run=1505/handle=1592（AgentLoopContext.java 无冲突）. ±8 重锚.
        // [tool_v4 合并 2026-08-22] tool-v4 接线后实测 build=1440/run=1530/handle=1619.
        //   按 ±8 容差重锚 → [1432,1448] / [1522,1538] / [1611,1627].
        assertThat(build).as("buildStreamingExecutor 漂移锚定").isBetween(1432, 1448);
        assertThat(run).as("runTools 漂移锚定").isBetween(1522, 1538);
        assertThat(handle).as("handleToolCallsTurn 漂移锚定").isBetween(1611, 1627);
    }
    // ─────────────────────────── ALI-1 惰性入口 (行为断言, RED-GREEN) ───────────────────────────

    @Test
    @DisplayName("ALI-1: getRemainingResultsStream 惰性 — 快工具结果在慢工具完成前 yield")
    void getRemainingResultsStream_isLazy_yieldsFastBeforeSlowCompletes() throws Exception {
        // WHY: CC getRemainingResults 是 AsyncGenerator (StreamingToolExecutor.ts:453-490),
        //   每个工具完成即 yield (query.ts:1380-1408 for-await 逐条消费 → UI 流式可见).
        //   Java 阻塞版 (getRemainingResults) 一次性返回全部, 慢工具拖住快工具结果.
        //   RED: 若惰性版用 tools.values().stream() 拍平实现 (先阻塞收集再返回),
        //   快结果不会在慢工具完成前到达 → 下方 hasSize(1) 断言失败.
        ToolRegistry reg = new ToolRegistry();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);
        reg.register(tool("Fast", 60, true, () -> { }));
        reg.register(tool("Slow", 0, true, () -> {
            slowStarted.countDown();
            try {
                slowRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));

        StreamingToolExecutor exec = new StreamingToolExecutor(reg, pool);
        exec.add(call("t-fast", "Fast"));
        exec.add(call("t-slow", "Slow"));

        List<String> arrival = Collections.synchronizedList(new ArrayList<>());
        // [IMP-C2] ToolResult 已删 toolUseId（由 mapper 从调用块推导），此处以 data
        // （工具名内嵌 "ok: {name}"）标识到达结果，仍可区分快/慢工具。
        Thread consumer = new Thread(() ->
            exec.getRemainingResultsStream().forEach(r -> arrival.add(String.valueOf(r.data()))));
        consumer.start();

        // 慢工具已开始阻塞 (保证它未完成), 等快工具结果到达
        assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
        long deadline = System.currentTimeMillis() + 3000;
        while (arrival.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        // 惰性: 此刻只有快工具结果 (慢工具仍阻塞) — 阻塞实现这里 size==0 → RED
        assertThat(arrival).as("快工具结果必须在慢工具完成前 yield").hasSize(1);
        assertThat(arrival.get(0)).isEqualTo("ok: Fast");

        slowRelease.countDown();
        consumer.join(5000);
        assertThat(consumer.isAlive()).isFalse();
        assertThat(arrival).containsExactly("ok: Fast", "ok: Slow");
    }

    @Test
    @DisplayName("ALI-1: 惰性 stream 在 discard 后短路返回 synthetic (CC:454-456 + C7)")
    void getRemainingResultsStream_discarded_shortCircuitsWithSynthetic() {
        // WHY: CC StreamingToolExecutor.ts:454-456 discarded → generator 立即 return, 不等待
        //   in-flight; Java C7 (R32-b15) 语义: discard 后 QUEUED/EXECUTING 填 synthetic
        //   streaming_fallback. 惰性版必须继承该短路 (否则 discard 场景阻塞调用方).
        ToolRegistry reg = new ToolRegistry();
        reg.register(tool("Read", 0, true, () -> { }));
        StreamingToolExecutor exec = new StreamingToolExecutor(reg, pool);
        exec.discard();
        exec.add(call("t-1", "Read"));

        long start = System.currentTimeMillis();
        List<ToolResult> results = exec.getRemainingResultsStream().toList();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(1000);
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isTrue();
        assertThat((String) results.get(0).data()).contains("Streaming fallback");
    }

    @Test
    @DisplayName("ALI-1: 惰性 stream 与阻塞版结果等价 (add 顺序, 全量)")
    void getRemainingResultsStream_matchesBlockingVersion_inAddOrder() {
        // WHY: LlmAgentLoop 两条路径 (runTools streaming/fallback) 将改走惰性 stream 累积,
        //   最终 ToolRunOutcome.results 必须与阻塞版完全一致 (顺序 = add 顺序, 全量),
        //   否则 tool_result 消息契约破坏 (每个 tool_use 必须有对应 tool_result).
        ToolRegistry reg = new ToolRegistry();
        reg.register(tool("Read", 0, true, () -> { }));
        reg.register(tool("Write", 0, false, () -> { }));

        StreamingToolExecutor exec = new StreamingToolExecutor(reg, pool);
        exec.add(call("t-1", "Read"));
        exec.add(call("t-2", "Write"));

        List<ToolResult> viaStream = exec.getRemainingResultsStream().toList();
        assertThat(viaStream).hasSize(2);
        // [IMP-C2] toolUseId 已删（mapper 推导）；工具返回 data="ok: {name}"，按 data 验证 add 序
        assertThat((String) viaStream.get(0).data()).isEqualTo("ok: Read");
        assertThat((String) viaStream.get(1).data()).isEqualTo("ok: Write");
        assertThat(LlmAgentLoop.isToolErrorData(viaStream.get(0).data())).isFalse();
    }

    // ─────────────────────── DEC-2 惰性 stream 生产接线 (OPD-TOOL-EX-01) ───────────────────────

    @Test
    @DisplayName("DEC-2: 生产接线 — runTools 两条路径 + getRemainingResults 委托均消费惰性 stream")
    void productionWiring_consumesLazyStream_notBlockingGetRemainingResults() throws IOException {
        // WHY (规则九): DEC-2『惰性 stream 为增量实现核心』的生产接线必须锚定在源码层面, 而非
        //   只测行为 —— 若 runTools 回退到阻塞 getRemainingResults() 或 getRemainingResults
        //   回退到 allOf(...).join(), 行为测试 (慢工具不拖快工具) 也未必立即红, 但架构已退化.
        //   RED: 生产接线一旦回退到阻塞收集, 本 source-anchor 断言立即变红.

        // (1) AgentLoopContext.runTools streaming + fallback 两条路径直接消费惰性 stream
        List<String> ctxLines = Files.readAllLines(Path.of(AGENT_LOOP_CONTEXT));
        long streamCalls = ctxLines.stream()
            .filter(l -> l.contains(".getRemainingResultsStream().toList()"))
            .count();
        assertThat(streamCalls)
            .as("runTools streaming/fallback 两条路径必须消费惰性 stream (增量实现核心)")
            .isGreaterThanOrEqualTo(2);
        assertThat(ctxLines.stream().filter(l -> l.contains(".getRemainingResults()")).count())
            .as("runTools 不得残留阻塞 getRemainingResults() 终端调用 (旧 allOf 收集)")
            .isZero();

        // (2) StreamingToolExecutor.getRemainingResults() (List 终端契约) 委托惰性 stream, 不再 allOf
        List<String> execLines = Files.readAllLines(Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        assertThat(findLine(execLines,
            "getRemainingResultsStream().collect(java.util.stream.Collectors.toList())"))
            .as("getRemainingResults() 必须委托 getRemainingResultsStream().collect(...)")
            .isGreaterThan(0);
        assertThat(execLines.stream().filter(l -> l.contains("CompletableFuture.allOf")).count())
            .as("getRemainingResults() 不得残留 allOf(...).join() 阻塞循环")
            .isZero();
    }

    @Test
    @DisplayName("DEC-2: getRemainingResults() 委托惰性 stream — terminal List 与 stream 全量等价 (含 discarded 边界)")
    void getRemainingResults_delegatesToLazyStream_terminalEquivalentIncludingDiscarded() throws Exception {
        // WHY (规则九): DEC-2 规定 getRemainingResults() 保留 List 终端契约, 但内部委托惰性
        //   getRemainingResultsStream(). 委托不能改变结果契约 —— 尤其 discarded 时仍 QUEUED 的
        //   工具必须补 synthetic 'streaming_fallback' (阻塞版行为), 否则断 tool_result 契约
        //   (每个 tool_use 必须有对应 tool_result). RED: 委托后 discard 只 drain COMPLETED
        //   工具 (漏 QUEUED/EXECUTING), 下方排队工具的 synthetic 会缺失.

        // (1) 非 discard 等价: 两个独立 executor 上 terminal List == stream.toList() (全量, add 序)
        List<ToolResult> terminalResults = collectTerminalResults();
        List<ToolResult> streamResults = collectStreamResults();
        assertThat(terminalResults).hasSize(2);
        assertThat(streamResults).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(terminalResults.get(i).data())
                .as("terminal List 与 stream 第 %d 项 data 一致（toolUseId 已随 IMP-C2 删除，data 承载结果）", i)
                .isEqualTo(streamResults.get(i).data());
            assertThat(LlmAgentLoop.isToolErrorData(terminalResults.get(i).data()))
                .as("terminal List 与 stream 第 %d 项 isError 一致", i)
                .isEqualTo(LlmAgentLoop.isToolErrorData(streamResults.get(i).data()));
        }

        // (2) discarded 边界: unsafe 执行中 → add 另一工具排队 → discard → terminal List 仍含排队 synthetic
        ToolRegistry reg = new ToolRegistry();
        CountDownLatch unsafeStarted = new CountDownLatch(1);
        CountDownLatch unsafeRelease = new CountDownLatch(1);
        reg.register(tool("Write", 0, false, () -> {
            unsafeStarted.countDown();
            try {
                unsafeRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        reg.register(tool("Read", 0, true, () -> { }));
        StreamingToolExecutor disc = new StreamingToolExecutor(reg, pool);
        disc.add(call("t-unsafe", "Write"));   // unsafe 串行 → EXECUTING (阻塞在 latch)
        assertThat(unsafeStarted.await(2, TimeUnit.SECONDS))
            .as("unsafe 工具必须已进入执行中").isTrue();
        disc.add(call("t-queued", "Read"));    // unsafe 执行中保序 → 保持 QUEUED
        disc.discard();

        List<ToolResult> discardedResults = disc.getRemainingResults();
        unsafeRelease.countDown();

        assertThat(discardedResults).hasSize(2);
        // [IMP-C2] toolUseId 已删；synthetic 'streaming_fallback' 载荷以
        // "<tool_use_error>..." 前缀被 isToolErrorData 识别为错误（t-unsafe 的 "ok: Write"
        // 非错误 → 匹配项即 QUEUED 工具）
        assertThat(discardedResults.stream().anyMatch(r ->
            LlmAgentLoop.isToolErrorData(r.data())
                && String.valueOf(r.data()).contains("Streaming fallback")))
            .as("discard 时仍 QUEUED 的工具必须在 terminal List 中得到 synthetic 'streaming_fallback'")
            .isTrue();
    }

    private List<ToolResult> collectTerminalResults() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(tool("Read", 0, true, () -> { }));
        reg.register(tool("Write", 0, false, () -> { }));
        StreamingToolExecutor exec = new StreamingToolExecutor(reg, pool);
        exec.add(call("t-1", "Read"));
        exec.add(call("t-2", "Write"));
        return exec.getRemainingResults();
    }

    private List<ToolResult> collectStreamResults() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(tool("Read", 0, true, () -> { }));
        reg.register(tool("Write", 0, false, () -> { }));
        StreamingToolExecutor exec = new StreamingToolExecutor(reg, pool);
        exec.add(call("t-1", "Read"));
        exec.add(call("t-2", "Write"));
        return exec.getRemainingResultsStream().toList();
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static int findLine(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static String nextNonBlank(List<String> lines, int from) {
        for (int i = from - 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l != null && !l.isBlank()) {
                return l;
            }
        }
        return "";
    }

    private Tool tool(String name, long delayMs, boolean concurrencySafe, Runnable block) {
        JsonNode schema = JSON.createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test " + name; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult<?> execute(ToolUseBlock c) {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (block != null) {
                    block.run();
                }
                return ToolResult.success(c.id(), "ok: " + name);
            }
            @Override public boolean isConcurrencySafe(JsonNode input) {
                return concurrencySafe;
            }
            @Override public String interruptBehavior() { return "cancel"; }
        };
    }

    private ToolUseBlock call(String id, String name) {
        JsonNode input = JSON.createObjectNode().put("foo", "bar");
        return new ToolUseBlock(id, name, input);
    }
}
