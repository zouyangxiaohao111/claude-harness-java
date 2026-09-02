package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-07 · AutoCompactor CC 契约测试（守卫 querySource / 熔断 / DISABLE env / SM 优先 / PTL 重试）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-07 的目标是把 AutoCompactor 对齐 CC
 * autoCompact.ts shouldAutoCompact() + autoCompactIfNeeded()（REQ-10/11/12/03）：
 * <ol>
 *   <li>递归守卫从 isSubagent 改为 CC querySource（session_memory/compact/marble_origami → false，INV-6）</li>
 *   <li>熔断器成功复位 0 / 失败+1 / ≥3 停止 / USER_ABORT 计入（INV-5，CC autoCompact.ts:341-342 无条件 +1）</li>
 *   <li>DISABLE_COMPACT / DISABLE_AUTO_COMPACT env 早退（autoCompact.ts:148-152/253）</li>
 *   <li>session-memory 优先判定 trySessionMemoryCompaction（REQ-12 / OD-15，成功链 INV-8）</li>
 *   <li>PTL 重试循环 MAX_PTL_RETRIES=3（INV-16）</li>
 * </ol>
 */
@DisplayName("[IMP-07] AutoCompactor CC 契约（守卫/熔断/DISABLE env/SM 优先/PTL 重试）")
class AutoCompactorCcContractTest {

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
        // [sm-cursor-sessionize] 清本文件用到的会话游标键（s1 + 无会话 unknown）
        SessionMemoryService.setLastSummarizedMessageId("s1", null);
        SessionMemoryService.setLastSummarizedMessageId(null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · 递归守卫 querySource（INV-6，REQ-10）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("守卫: querySource=session_memory → shouldAutoCompact=false（INV-6）")
    void guardSessionMemory() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        assertThat(auto.shouldAutoCompact(big, "session_memory", 0)).isFalse();
    }

    @Test
    @DisplayName("守卫: querySource=compact → shouldAutoCompact=false（INV-6）")
    void guardCompact() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        assertThat(auto.shouldAutoCompact(big, "compact", 0)).isFalse();
    }

    @Test
    @DisplayName("守卫: CONTEXT_COLLAPSE 启用 && querySource=marble_origami → false（INV-6）")
    void guardMarbleOrigami() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        // 未启用 CONTEXT_COLLAPSE 时 marble_origami 不守卫（autoCompact.ts:179 feature 门控）
        assertThat(auto.shouldAutoCompact(big, "marble_origami", 0)).isTrue();
        // 启用后守卫
        auto.setContextCollapseEnabled(true);
        assertThat(auto.shouldAutoCompact(big, "marble_origami", 0)).isFalse();
    }

    @Test
    @DisplayName("非守卫源 user 超阈 → shouldAutoCompact=true（阈值真实生效）")
    void guardUserAboveThreshold() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        assertThat(auto.shouldAutoCompact(big, "user", 0)).isTrue();
    }

    @Test
    @DisplayName("守卫（生产值域）: 大写 SESSION_MEMORY/COMPACT/MARBLE_ORIGAMI 归一后命中（S-3，INV-18）")
    void guard_productionUppercaseEnumNames_hit() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        // 生产 LlmAgentLoop 传 querySource().name() 大写枚举名 → canonical 归一后守卫命中
        assertThat(auto.shouldAutoCompact(big, "SESSION_MEMORY", 0))
            .as("生产大写 SESSION_MEMORY 必须命中递归守卫（fork 死锁防护）").isFalse();
        assertThat(auto.shouldAutoCompact(big, "COMPACT", 0))
            .as("生产大写 COMPACT 必须命中递归守卫").isFalse();
        auto.setContextCollapseEnabled(true);
        assertThat(auto.shouldAutoCompact(big, "MARBLE_ORIGAMI", 0))
            .as("生产大写 MARBLE_ORIGAMI + CONTEXT_COLLAPSE 必须命中 ctx-agent 守卫（autoCompact.ts:179-183）").isFalse();
        // 对照：USER 大写（主线程）超阈不受守卫 → true
        assertThat(auto.shouldAutoCompact(big, "USER", 0))
            .as("生产大写 USER（主线程）不受守卫，阈值真实生效").isTrue();
    }

    @Test
    @DisplayName("子代理源（agent:subagent/agent:builtin:fork canonical）非守卫 → 超阈照常压缩（DRIFT-8/S-8，IMP2-08）")
    void guard_subagentSources_notBlocked() {
        // CC shouldAutoCompact（autoCompact.ts:160-239）无 agent:* 守卫：递归守卫仅
        // session_memory/compact/marble_origami；子代理源（runAgent.ts:748 同一 query()）
        // 达阈值照常 proactive 压缩。本用例固化 gate 移除后单元层语义（集成层由
        // SubagentAutoCompactGateCcTest 覆盖）。
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        assertThat(auto.shouldAutoCompact(big, "agent:subagent", 0))
            .as("SUBAGENT canonical（agent:subagent）超阈不受守卫").isTrue();
        assertThat(auto.shouldAutoCompact(big, "agent:builtin:fork", 0))
            .as("FORK canonical（agent:builtin:fork）超阈不受守卫").isTrue();
        assertThat(auto.shouldAutoCompact(big, "SUBAGENT", 0))
            .as("生产大写 SUBAGENT 归一后仍非守卫源，超阈照常").isTrue();
        assertThat(auto.shouldAutoCompact(big, "FORK", 0))
            .as("生产大写 FORK 归一后仍非守卫源，超阈照常").isTrue();
        // [IMP2-05 精确化后] 运行时 querySource 从聚合占位升级为 agentType 级精确值
        //   （agent:builtin:<type>/agent:custom/agent:default，promptCategory.ts:16-28）。
        //   递归守卫值域不变（仅 session_memory/compact/marble_origami），精确值必须保持非守卫。
        assertThat(auto.shouldAutoCompact(big, "agent:builtin:Explore", 0))
            .as("[IMP2-05] 内置 Explore 精确值超阈不受守卫").isTrue();
        assertThat(auto.shouldAutoCompact(big, "agent:custom", 0))
            .as("[IMP2-05] 自定义 agent 精确值超阈不受守卫").isTrue();
        assertThat(auto.shouldAutoCompact(big, "agent:default", 0))
            .as("[IMP2-05] 默认 agent 精确值超阈不受守卫").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · 熔断语义（INV-5，REQ-11）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("熔断: 失败 2 次未熔断，第 3 次触发 ≥3 停止（INV-5）")
    void circuitBreakerTripsAt3() {
        // 恒定高 token → 每次都会尝试；callback 恒抛错 → 失败 +1
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> { throw new RuntimeException("boom"); });

        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(1);

        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(2);

        // 第 3 次失败 → 熔断（consecutiveFailures >= 3）
        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(3);
        assertThat(auto.getTracking().isCircuitBreakerOpen()).isTrue();

        // ≥3 停止：后续尝试不再执行（短路返回）
        AutoCompactor.AutoCompactResult r = auto.tryAutoCompact(largeMessages(50));
        assertThat(r.wasCompacted()).isFalse();
        // 熔断后仍保持 3（不继续 +1）
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("熔断: 成功复位 0（INV-5）")
    void successResetsFailureCount() {
        // 首次失败
        AutoCompactor fail = new AutoCompactor(msgs -> 200_000, (p, m) -> { throw new RuntimeException("boom"); });
        fail.tryAutoCompact(largeMessages(50));
        assertThat(fail.getTracking().getConsecutiveFailures()).isEqualTo(1);

        // 用成功 callback 的实例：成功 → consecutiveFailures 复位 0
        AutoCompactor ok = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("<summary>good</summary>", null));
        // 预先模拟一次失败
        ok.getTracking().recordFailure();
        assertThat(ok.getTracking().getConsecutiveFailures()).isEqualTo(1);

        AutoCompactor.AutoCompactResult r = ok.tryAutoCompact(largeMessages(50));
        assertThat(r.wasCompacted()).isTrue();
        assertThat(ok.getTracking().getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("熔断: USER_ABORT 计入失败数（CC autoCompact.ts:341-342 无条件 +1）")
    void userAbortCountsTowardBreaker() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> { throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_USER_ABORT); });

        auto.tryAutoCompact(largeMessages(50));
        // USER_ABORT 也计入（hasExactErrorMessage 仅门控 logError，不门控计数）
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · DISABLE env 早退（OD-16 / REQ-10/11）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("env: DISABLE_COMPACT → isAutoCompactEnabled=false（autoCompact.ts:148）")
    void disableCompactEnv() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setEnvProvider(key -> "DISABLE_COMPACT".equals(key) ? "true" : null);

        assertThat(auto.isAutoCompactEnabled()).isFalse();
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isFalse();
        assertThat(auto.tryAutoCompact(largeMessages(50)).wasCompacted()).isFalse();
    }

    @Test
    @DisplayName("env: DISABLE_AUTO_COMPACT → isAutoCompactEnabled=false（autoCompact.ts:152）")
    void disableAutoCompactEnv() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setEnvProvider(key -> "DISABLE_AUTO_COMPACT".equals(key) ? "1" : null);

        assertThat(auto.isAutoCompactEnabled()).isFalse();
    }

    @Test
    @DisplayName("env: 无 disable env + autoCompactEnabled=true → 启用")
    void enabledByDefault() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        assertThat(auto.isAutoCompactEnabled()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · SM 优先（REQ-12 / OD-15）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM 优先: trySessionMemoryCompaction 成功 → SESSION_MEMORY 源 + 成功链（INV-8）")
    void smPrioritySuccessChain(@TempDir Path baseDir) throws Exception {
        // 准备 session memory 文件（非空、非模板）
        java.nio.file.Files.createDirectories(baseDir.resolve("s1").resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve("s1").resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        smService.setSmSessionMemoryEnabled(true);
        smService.setSmCompactEnabled(true);

        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("should not be called", null));
        auto.setSessionMemoryService(smService);
        auto.setSessionId("s1");
        auto.setAgentId("agent-1");
        // 未设置 lastSummarizedMessageId → resumed session 分支（保留全部非 boundary 消息）
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        AtomicInteger cleanupCalls = new AtomicInteger();
        List<String> notifyCalls = new ArrayList<>();
        auto.setRunPostCompactCleanup(cleanupCalls::incrementAndGet);
        auto.setNotifyCompaction((qs, aid) -> notifyCalls.add(qs + ":" + aid));
        // [SM-07] PROMPT_CACHE_BREAK_DETECTION 门控开启 → notifyCompaction 可达（CC feature on 语义）
        auto.setPromptCacheBreakDetectionGate(() -> true);

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        // SM 压缩成功
        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("SESSION_MEMORY");
        // 成功链: runPostCompactCleanup + notifyCompaction + markPostCompaction（INV-8）
        assertThat(cleanupCalls.get()).isEqualTo(1);
        assertThat(notifyCalls).contains("user:agent-1");
        // setSessionId("s1") 非 UUID → 方案 1b 走回落进程级单布尔；本断言验证 INV-8
        // markPostCompaction 确被调用（mark 成功链），会话级隔离语义由 PostCompactionStateTest 覆盖。
        assertThat(PostCompactionState.isPostCompactionPending("s1")).isTrue();
        // setLastSummarizedMessageId 复位（autoCompact.ts:296）
        assertThat(SessionMemoryService.getLastSummarizedMessageId("s1")).isNull();
    }

    @Test
    @DisplayName("SM 优先: PROMPT_CACHE_BREAK_DETECTION gate=false → notifyCompaction 不被调用（autoCompact.ts:302-304）")
    void smPriorityNotifyCompaction_gatedOff(@TempDir Path baseDir) throws Exception {
        // 与 smPrioritySuccessChain 同场景，仅门控默认 false（CC feature 默认关）
        java.nio.file.Files.createDirectories(baseDir.resolve("s1").resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve("s1").resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        smService.setSmSessionMemoryEnabled(true);
        smService.setSmCompactEnabled(true);

        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("should not be called", null));
        auto.setSessionMemoryService(smService);
        auto.setSessionId("s1");
        auto.setAgentId("agent-1");
        SessionMemoryService.setLastSummarizedMessageId("s1", null);
        // 门控不设置 → 默认 false（feature('PROMPT_CACHE_BREAK_DETECTION') 默认关）

        List<String> notifyCalls = new ArrayList<>();
        auto.setNotifyCompaction((qs, aid) -> notifyCalls.add(qs + ":" + aid));

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        // SM 压缩仍成功（门控只影响 notifyCompaction，不影响压缩本身）
        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("SESSION_MEMORY");
        assertThat(notifyCalls)
            .as("gate=false → SM 成功链不调用 notifyCompaction（CC autoCompact.ts:302-304）")
            .isEmpty();
    }

    @Test
    @DisplayName("全量路径(回落 L4): PROMPT_CACHE_BREAK_DETECTION gate=true → notifyCompaction 触发（compact.ts:698-699）")
    void fullPathNotifyCompaction_gatedOn(@TempDir Path baseDir) {
        // SM 未启用 → 回落全量 compactConversation（AUTO 源），全量路径 step 15
        // ctx.getNotifyCompaction().run()（compact.ts:698-699 等价）→ wireAutoNotifyCompaction 接线后
        // 按门控调用 notifyCompaction.accept(querySource, agentId)。
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>llm fallback</summary>", null));
        auto.setSessionMemoryService(smService);
        auto.setSessionId("s1");
        auto.setAgentId("agent-1");
        List<String> notifyCalls = new ArrayList<>();
        auto.setNotifyCompaction((qs, aid) -> notifyCalls.add(qs + ":" + aid));
        // [IMP-CM-12] f4 全量路径门控开启 → notifyCompaction 可达（CC compact.ts:698-699 feature on 语义）
        auto.setPromptCacheBreakDetectionGate(() -> true);

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("AUTO");
        assertThat(notifyCalls).contains("user:agent-1");
    }

    @Test
    @DisplayName("全量路径(回落 L4): PROMPT_CACHE_BREAK_DETECTION gate=false → notifyCompaction 不触发（compact.ts:698-699）")
    void fullPathNotifyCompaction_gatedOff(@TempDir Path baseDir) {
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>llm fallback</summary>", null));
        auto.setSessionMemoryService(smService);
        auto.setSessionId("s1");
        auto.setAgentId("agent-1");
        List<String> notifyCalls = new ArrayList<>();
        auto.setNotifyCompaction((qs, aid) -> notifyCalls.add(qs + ":" + aid));
        // 门控不设置 → 默认 false（feature('PROMPT_CACHE_BREAK_DETECTION') 默认关）

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("AUTO");
        assertThat(notifyCalls)
            .as("gate=false → 全量路径不调用 notifyCompaction（CC compact.ts:698-699）")
            .isEmpty();
    }

    @Test
    @DisplayName("SM 不可用（gate=false）→ 回落 L4 LLM 摘要（AUTO 源）")
    void smUnavailableFallsBackToL4(@TempDir Path baseDir) {
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        // SM feature 未启用 → shouldUseSessionMemoryCompaction=false
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("<summary>llm fallback</summary>", null));
        auto.setSessionMemoryService(smService);
        auto.setSessionId("s1");

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("IMP-CM-13 SM 成功复位: 熔断计数清零 + turnId 轮换 + turnCounter 归零 + compacted（query.ts:519-526）")
    void smSuccessResetsCircuitBreaker(@TempDir Path baseDir) throws Exception {
        // 准备 session memory 文件（非空、非模板）
        java.nio.file.Files.createDirectories(baseDir.resolve("s1").resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve("s1").resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService smService = new SessionMemoryService(baseDir);
        smService.setSmSessionMemoryEnabled(true);
        smService.setSmCompactEnabled(true);

        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("should not be called", null));
        auto.setSessionMemoryService(smService);
        auto.setSessionId("s1");
        auto.setAgentId("agent-1");
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        // 前置：模拟此前 2 次压缩失败（熔断计数=2，未达阈值 3）——D-1 缺陷下 SM 成功不清零，
        // 再 1 次失败即达 3 → 熔断器提前 1 轮打开（"提前触发下一轮"）；对齐 CC query.ts:521-526
        // 公共复位后 SM 成功应把 consecutiveFailures 归零，熔断器不提前打开。
        auto.getTracking().recordFailure();
        auto.getTracking().recordFailure();
        // 再模拟 2 个 turn 计数（startNewTurn gate = compacted；先 markCompacted 使其生效）
        auto.getTracking().markCompacted();
        auto.getTracking().startNewTurn();
        auto.getTracking().startNewTurn();
        String preTurnId = auto.getTracking().getTurnId();
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(2);

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        // SM 压缩仍成功（熔断复位不影响压缩本身）
        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("SESSION_MEMORY");
        // 熔断计数清零（query.ts:525 consecutiveFailures: 0）→ 不提前打开熔断器
        assertThat(auto.getTracking().getConsecutiveFailures())
            .as("SM 成功后连续失败计数必须归零（CC query.ts:525 公共复位）").isZero();
        assertThat(auto.getTracking().isCircuitBreakerOpen()).isFalse();
        // turnId 轮换 + turnCounter 归零（query.ts:523-524）
        assertThat(auto.getTracking().getTurnId()).isNotEqualTo(preTurnId);
        assertThat(auto.getTracking().getTurnCounter())
            .as("SM 成功后 turnCounter 归零（query.ts:524），turnsSincePreviousCompact 从 0 重计").isZero();
        // compacted=true（query.ts:522）→ tengu_post_autocompact_turn 门开启（LlmAgentLoop:4700-4712）
        assertThat(auto.getTracking().isCompacted()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · PTL 重试循环（INV-16，REQ-03）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PTL: 前缀摘要触发重试，第二次成功（INV-16）")
    void ptlRetryThenSucceeds() {
        List<String> calls = new ArrayList<>();
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> {
            calls.add("call-" + m.size());
            if (calls.size() == 1) {
                return new CompactConversation.SummaryResult(
                    "Prompt is too long. Try reducing the length of the messages.", null);
            }
            return new CompactConversation.SummaryResult("<summary>valid summary</summary>", null);
        });

        // 交替 user/assistant 以便 groupMessagesByApiRound 产出 ≥2 组（PTL 截断才有可丢组）
        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(ptlMessages(10));

        assertThat(calls).hasSize(2);
        assertThat(result.wasCompacted()).isTrue();
    }

    @Test
    @DisplayName("PTL: 重试耗尽（3 次后仍 PTL）→ 失败 +1（MAX_PTL_RETRIES=3，INV-16）")
    void ptlRetryExhausted() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult(
                "Prompt is too long. Try reducing the length of the messages.", null));

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(ptlMessages(10));

        assertThat(result.wasCompacted()).isFalse();
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6 · shouldAutoCompact 两条抑制门（GR-2 · autoCompact.ts:195-223，REQ-10）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REACTIVE_COMPACT 抑制门: feature + tengu_cobalt_raccoon 双 true → false（autoCompact.ts:195-199）")
    void reactiveOnlyModeSuppresses() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setReactiveCompactEnabled(true);
        auto.setReactiveOnlyMode(true);

        // 超阈但 reactive-only 模式开启 → 抑制主动 autocompact（reactive compact 承接 413）
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isFalse();
    }

    @Test
    @DisplayName("REACTIVE_COMPACT 抑制门: feature=true 但 tengu=false → 不抑制（autoCompact.ts:196 缺省）")
    void reactiveFeatureWithoutGrowthbookDoesNotSuppress() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setReactiveCompactEnabled(true);
        // tengu_cobalt_raccoon 缺省 false → growthbook 未配置时自动压缩仍活跃
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isTrue();
    }

    @Test
    @DisplayName("REACTIVE_COMPACT 抑制门: tengu=true 但 feature=false → 不抑制（feature 门控）")
    void growthbookWithoutFeatureDoesNotSuppress() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setReactiveOnlyMode(true);
        // REACTIVE_COMPACT feature 关闭 → 抑制不生效
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isTrue();
    }

    @Test
    @DisplayName("CONTEXT_COLLAPSE 抑制门: feature + isContextCollapseEnabled 双 true → false（autoCompact.ts:215-223）")
    void contextCollapseModeSuppresses() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setContextCollapseEnabled(true);
        auto.setContextCollapseModeEnabled(true);

        // 超阈但 context-collapse 模式开启 → 抑制主动 autocompact（collapse 拥有 headroom）
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isFalse();
    }

    @Test
    @DisplayName("CONTEXT_COLLAPSE 抑制门: feature=true 但 isContextCollapseEnabled=false → 不抑制")
    void contextCollapseFeatureWithoutRuntimeDoesNotSuppress() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setContextCollapseEnabled(true);
        // isContextCollapseEnabled()=false → collapse 未运行时自动压缩仍活跃
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isTrue();
    }

    @Test
    @DisplayName("CONTEXT_COLLAPSE 抑制门: isContextCollapseEnabled=true 但 feature=false → 不抑制")
    void contextCollapseRuntimeWithoutFeatureDoesNotSuppress() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("summary", null));
        auto.setContextCollapseModeEnabled(true);
        // CONTEXT_COLLAPSE feature 关闭 → 抑制不生效
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0)).isTrue();
    }

    @Test
    @DisplayName("抑制门不破坏熔断器入口: autoCompactIfNeeded 内部经 shouldAutoCompact 一并受抑制门约束")
    void suppressionGatesReachAutoCompactIfNeeded() {
        // 双门开启 → autoCompactIfNeeded 返回 wasCompacted=false（不触发 L4 摘要回调）
        AtomicInteger summarizeCalls = new AtomicInteger();
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> { summarizeCalls.incrementAndGet();
                return new CompactConversation.SummaryResult("<summary>no-op</summary>", null); });
        auto.setReactiveCompactEnabled(true);
        auto.setReactiveOnlyMode(true);

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(50));

        assertThat(result.wasCompacted()).isFalse();
        assertThat(summarizeCalls.get()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 7 · L4 legacy 成功链 + recompactionInfo（GR-2 · autoCompact.ts:279-285/325-326，REQ-11）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("L4 legacy 成功链: setLastSummarizedMessageId(null) + runPostCompactCleanup + 复位 0（autoCompact.ts:325-326）")
    void l4LegacySuccessChain() {
        AtomicInteger cleanupCalls = new AtomicInteger();
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("<summary>llm fallback</summary>", null));
        auto.setRunPostCompactCleanup(cleanupCalls::incrementAndGet);
        // 预置旧 lastSummarizedMessageId → L4 成功后应复位（autoCompact.ts:325 注释：legacy compaction
        // 替换全部消息，旧 message UUID 在新 messages 数组中已不存在）
        SessionMemoryService.setLastSummarizedMessageId(null, "old-msg-id");

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("AUTO");
        // 成功链: setLastSummarizedMessageId(undefined)（:325）+ runPostCompactCleanup（:326）
        assertThat(SessionMemoryService.getLastSummarizedMessageId(null)).isNull();
        assertThat(cleanupCalls.get()).isEqualTo(1);
        // 成功复位熔断计数 0（autoCompact.ts:332）
        assertThat(auto.getTracking().getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("recompactionInfo 输入源: 成功复位后 tracking 轮换 turnId + 归零 turnCounter（query.ts:521-526，IMP2-07）")
    void recompactionInfoInputsAfterSuccess() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> new CompactConversation.SummaryResult("<summary>llm fallback</summary>", null));
        // 预置 previousCompact 状态（isRecompactionInChain ← tracking.compacted，autoCompact.ts:280）
        auto.getTracking().markCompacted();
        auto.getTracking().startNewTurn();
        String preCompactTurnId = auto.getTracking().getTurnId();
        assertThat(auto.getTracking().getTurnCounter()).isEqualTo(1);

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(20));

        assertThat(result.wasCompacted()).isTrue();
        // recompactionInfo 字段输入源（compact.ts:317-323 + autoCompact.ts:280-284）：
        // isRecompactionInChain ← tracking.compacted（保持 true）
        assertThat(auto.getTracking().isCompacted()).isTrue();
        // turnsSincePreviousCompact ← tracking.turnCounter（autoCompact.ts:281）——成功归零重计
        assertThat(auto.getTracking().getTurnCounter())
            .as("压缩成功必须归零 turnCounter（query.ts:524）").isZero();
        // previousCompactTurnId ← tracking.turnId（autoCompact.ts:282）——成功轮换（query.ts:523）
        assertThat(auto.getTracking().getTurnId())
            .as("压缩成功必须轮换 turnId（DRIFT-4/S-6）").isNotEqualTo(preCompactTurnId);
        // autoCompactThreshold ← getAutoCompactThreshold(model)（autoCompact.ts:283）
        assertThat(auto.getAutoCompactThreshold()).isPositive();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 8 · SM token 口径（OPD-R2-SM-01 · DRIFT-6/7 · G-32/G-38）
    // ════════════════════════════════════════════════════════════════════

    /** 建 SM 文件（非空、非模板）+ 启用 SM 双门控的 Service。 */
    private static SessionMemoryService smServiceWithContent(Path baseDir) throws Exception {
        java.nio.file.Files.createDirectories(baseDir.resolve("s1").resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve("s1").resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        return sm;
    }

    @Test
    @DisplayName("SM 压缩: preCompactTokenCount = tokenCountFromLastAPIResponse（无 usage → 0，CC sessionMemoryCompact.ts:445）")
    void smPreTokens_noUsage_zero(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = smServiceWithContent(baseDir);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);
        // 全部 user 消息无 usage → CC tokenCountFromLastAPIResponse = 0。
        // 旧实现用简化 tokenCountWithEstimation（input+output+rough 尾段）→ 20×rough("hi")=20（DRIFT-6）。
        CompactionResult r = sm.trySessionMemoryCompaction(
            largeMessages(5), "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.preCompactTokenCount()).isZero();
    }

    @Test
    @DisplayName("SM 压缩: preTokens 含 cache 四通道（末条 usage input+cache_read+cache_creation+output = 6200，CC :445 getTokenCountFromUsage）")
    void smPreTokens_includesCacheChannels(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = smServiceWithContent(baseDir);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(3));
        msgs.add(new ChatMessageDto("a1", null, Role.assistant, "assistant", "ok", null, List.of(),
            FinishReason.stop, 1000, 100, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false)
            .withUsageCache(5000, 100));

        CompactionResult r = sm.trySessionMemoryCompaction(msgs, "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        // 6200 = input 1000 + output 100 + cache_read 5000 + cache_creation 100（tokens.ts:46-53）
        // 旧实现仅 input+output = 1100（cache 恒 0）→ boundary preTokens 漂移（DRIFT-6）
        assertThat(r.preCompactTokenCount()).isEqualTo(6200);
    }

    @Test
    @DisplayName("SM 压缩: postCompactTokenCount = estimateMessageTokens(postCompactMessages)（block 统计 + ×4/3、全消息，CC :616-620）")
    void smPostCompactTokenCount_estimateOfFullPostCompactMessages(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = smServiceWithContent(baseDir);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        CompactionResult r = sm.trySessionMemoryCompaction(
            largeMessages(5), "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        TokenEstimator te = new TokenEstimator();
        int expected = te.estimateMessageTokens(CompactionResult.buildPostCompactMessages(r));
        // 结果字段 = 全 postCompact 消息（boundary+summary+keep）的 estimate（CC :616-620）
        assertThat(r.postCompactTokenCount()).isEqualTo(expected);
        assertThat(r.truePostCompactTokenCount()).isEqualTo(expected);
        // 区分性断言（DRIFT-7）：结果字段必须大于「仅摘要消息」估算（旧实现 = rough(summaryMessages)
        // len/4 无 padding 且不含 keep 消息 → 系统性低估）
        assertThat(r.postCompactTokenCount())
            .isGreaterThan(te.estimateMessageTokens(r.summaryMessages()));
    }

    @Test
    @DisplayName("SM 压缩: 超阈回落（postCompactTokenCount ≥ autoCompactThreshold → null，CC :605-614）")
    void smThresholdExceeded_fallsBackToNull(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = smServiceWithContent(baseDir);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);
        List<ChatMessageDto> msgs = largeMessages(5);

        CompactionResult r = sm.trySessionMemoryCompaction(msgs, "s1", "agent-1", Integer.MAX_VALUE);
        assertThat(r).isNotNull();
        // 阈值恰好 = estimate(postCompactMessages) → post ≥ threshold → null（tengu_sm_compact_threshold_exceeded）
        // 旧实现阈值比较用 rough(summaryMessages)（远小于 estimate）→ 错误返回结果（超阈不回落）
        int estimate = new TokenEstimator().estimateMessageTokens(
            CompactionResult.buildPostCompactMessages(r));

        assertThat(sm.trySessionMemoryCompaction(msgs, "s1", "agent-1", estimate)).isNull();
        // 阈值 +1 → 不超阈 → 正常结果
        assertThat(sm.trySessionMemoryCompaction(msgs, "s1", "agent-1", estimate + 1)).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 9 · G-2 model 源统一 effectiveModel（OPD-CM3-33，IMP-CM-06）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-2: 阈值吃 ccContext 有效模型（effectiveModel 驱动），模型不同阈值不同（CC autoCompact.ts:267 mainLoopModel）")
    void g2_thresholdUsesContextEffectiveModel() {
        // WHY (CLAUDE.md 规则 9 · 测试验证意图): G-2 对齐 CC autoCompact.ts:267
        // `const model = toolUseContext.options.mainLoopModel`（可被 fallbackModel 改写，
        // query.ts:922）——阈值体系必须吃"本 turn 有效模型"，而非固定原始模型。本用例证明
        // ccContext.getModel()（= LlmAgentLoop 传入的 effectiveModel）驱动阈值：
        // 同一消息量、同一 AutoCompactor，仅 ccContext 模型不同 → 压缩判定相反。
        // 模型上下文窗口解析器：small-model 50k 窗 / big-model 200k 窗（其他回落 200k）。
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setModelContextWindowResolver(model -> {
            if ("small-model".equals(model)) return 50_000;
            return 200_000;
        });
        // 消息 token 数 = 100_000：在 small-model 阈值（≤37k）之上、big-model 阈值（≥167k）之下，
        // 两判定必然相反（reserved 减法 ≤20k 不影响区间分离）。
        AutoCompactor auto = new AutoCompactor(msgs -> 100_000, (p, m) -> new CompactConversation.SummaryResult("<summary>no-op</summary>", null));
        auto.setThresholdSystem(ts);
        List<ChatMessageDto> msgs = largeMessages(50);

        // ── 有效模型 = big-model（200k 窗）→ 阈值高 → 100k 未达阈 → 不压缩 ──
        AutoCompactor.AutoCompactResult rBig = auto.autoCompactIfNeeded(
            msgs, 0, "user", new CompactConversationContext().setModel("big-model"));
        assertThat(rBig.wasCompacted())
            .as("G-2: ccContext 有效模型 big-model（200k 窗）→ 阈值高 → 100k 未达阈不应压缩")
            .isFalse();
        // model 已从 ccContext 注入 → getAutoCompactThreshold 反映 big-model 窗（200k-20k-13k ≥ 167k）
        assertThat(auto.getAutoCompactThreshold())
            .as("G-2: getAutoCompactThreshold 必须吃 ccContext 有效模型 big-model（非原始 modelName 默认窗）")
            .isGreaterThan(150_000);

        // ── 有效模型 = small-model（50k cap < 100k 能力门）→ 回落默认 200k 窗 → 阈值高 → 100k 未达阈 → 不压缩 ──
        // 对齐 CC context.ts:74-83：cap.max_input_tokens < 100_000 不进入 100k 门 → 回落
        //   MODEL_CONTEXT_WINDOW_DEFAULT(200k)。注：setModelContextWindowResolver 显式返回 50000
        //   亦被 CompactThresholdSystem 100k 能力门钳制为默认 200k（CC 语义，小窗模型不产生低阈值）。
        // ctx 镜像 buildDefaultCompactConversationContext 必需字段，避免 compactConversation NPE
        //（model/querySource/readFileState/notifyCompaction；sessionId/agentId null 与默认路径等价）。
        CompactConversationContext smallCtx = new CompactConversationContext()
            .setModel("small-model")
            .setQuerySource("user")
            .setReadFileState(new java.util.LinkedHashMap<>())
            .setNotifyCompaction(() -> { });
        AutoCompactor.AutoCompactResult rSmall = auto.autoCompactIfNeeded(
            msgs, 0, "user", smallCtx);
        assertThat(rSmall.wasCompacted())
            .as("G-2: small-model 50k cap < 100k 能力门 → 回落默认 200k 窗（CC context.ts:74-83）→ 100k 未达阈不应压缩")
            .isFalse();
        assertThat(auto.getAutoCompactThreshold())
            .as("G-2: small-model 回落默认 200k → 阈值高（≥167k，非小窗低阈值——CC 语义 cap<100k 回落默认）")
            .isGreaterThan(150_000);
    }

    @Test
    @DisplayName("G-2: 原始模型 vs 有效模型——ccContext 未带模型时回落默认窗（null → 默认 200k 窗，不 NPE）")
    void g2_nullContextModelFallsBackToDefaultWindow() {
        // WHY: G-2 统一后，模型源 = ccContext.getModel()（有效模型）。ccContext 未显式带模型
        //（null）时 must 不 NPE、回落默认窗（CC context.ts:9 MODEL_CONTEXT_WINDOW_DEFAULT 200k，
        // AutoCompactor 默认 thresholdSystem 未注入 resolver → null 模型走 200k 默认），
        // 与既有 tryAutoCompact（ccContext=null）路径语义一致。
        AutoCompactor auto = new AutoCompactor(msgs -> 10_000, (p, m) -> new CompactConversation.SummaryResult("<summary>no-op</summary>", null));

        AutoCompactor.AutoCompactResult r = auto.autoCompactIfNeeded(
            largeMessages(20), 0, "user", new CompactConversationContext());
        // ccContext.model=null → AutoCompactor.model 保持 null → 默认窗 200k → 10k 未达阈 → 不压缩
        assertThat(r.wasCompacted()).isFalse();
        assertThat(auto.getAutoCompactThreshold())
            .as("G-2: ccContext.model=null 必须回落默认窗（200k → 阈值≥167k，不 NPE）")
            .isGreaterThan(150_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════


    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }

    /** 交替 user/assistant 以便 groupMessagesByApiRound 产出 ≥2 组。 */
    private static List<ChatMessageDto> ptlMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Role role = i % 2 == 0 ? Role.user : Role.assistant;
            list.add(new ChatMessageDto("m" + i, null, role,
                role == Role.assistant ? "assistant" : "user",
                "message " + i, null, List.of(), FinishReason.stop,
                null, null, "刚刚", OffsetDateTime.now(),
                null, "asst-" + i, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }
}
