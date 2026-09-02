package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-07 · tracking 轮换接线 CC 契约测试（DRIFT-4 / S-6 / S-7）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC query.ts:521-526 压缩成功后
 * {@code tracking = {compacted:true, turnId:uuid(), turnCounter:0, consecutiveFailures:0}}
 * ——turnId 轮换 + turnCounter 归零；query.ts:1523-1533 回合末 {@code if (tracking.compacted)
 * tracking.turnCounter++} + tengu_post_autocompact_turn。旧 Java 实现 startNewTurn 0 调用 →
 * turnId/turnCounter 恒不轮换（recompactionInfo.turnsSincePreviousCompact 恒 0、
 * previousCompactTurnId 失真，02 S-6 / DRIFT-4）。
 *
 * <p>熔断范围（S-7）：CC tracking 为单次 query() 调用（= 一次用户回合 = Java 一次 run()）的
 * 局部状态（query.ts:268-272 每 query 调用初始化为 undefined），回合内跨工具轮累计、
 * 回合边界复位。Java run() 级 reset 与之语义等价 → per-run 登记（08 矩阵 S-7 行）。
 */
@DisplayName("[IMP2-07] tracking 轮换接线（DRIFT-4/S-6）+ 熔断范围（S-7）")
class TrackingRotationCcTest {

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
        // [sm-cursor-sessionize] 本文件 AutoCompactor 未设 sessionId → 游标落在 "unknown" 键
        com.nexusai.application.agent.memory.SessionMemoryService.setLastSummarizedMessageId(null, null);
    }

    private static AutoCompactor compactingAutoCompactor() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>llm fallback</summary>", null));
        return auto;
    }

    // ════════════════════════════════════════════════════════════════════
    // DRIFT-4 / S-6 · 压缩成功轮换（CC query.ts:521-526）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("压缩成功: turnId 轮换（新 UUID）+ turnCounter 归零 + compacted=true（query.ts:521-526）")
    void compactSuccessRotatesTurnIdAndResetsCounter() {
        AutoCompactor auto = compactingAutoCompactor();
        String oldTurnId = auto.getTracking().getTurnId();

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(50));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(auto.getTracking().getTurnId())
            .as("压缩成功必须轮换 turnId（CC query.ts:523 deps.uuid()）")
            .isNotEqualTo(oldTurnId);
        assertThat(auto.getTracking().getTurnCounter())
            .as("压缩成功必须归零 turnCounter（CC query.ts:524）").isZero();
        assertThat(auto.getTracking().isCompacted())
            .as("压缩成功必须置 compacted=true（CC query.ts:522）").isTrue();
        assertThat(auto.getTracking().getConsecutiveFailures())
            .as("压缩成功必须归零连续失败（CC query.ts:525）").isZero();
    }

    @Test
    @DisplayName("回合末 startNewTurn: 仅 turnCounter++，不重置 compacted、不轮换 turnId（query.ts:1523-1524）")
    void startNewTurnOnlyIncrementsCounter() {
        AutoCompactor auto = compactingAutoCompactor();
        auto.tryAutoCompact(largeMessages(50));  // 成功 → compacted=true, turnId 轮换, turnCounter=0
        String turnIdAfterCompact = auto.getTracking().getTurnId();

        auto.getTracking().startNewTurn();

        assertThat(auto.getTracking().getTurnCounter())
            .as("回合末 turnCounter 必须 +1（CC query.ts:1524）").isEqualTo(1);
        assertThat(auto.getTracking().getTurnId())
            .as("startNewTurn 不得轮换 turnId（turnId 仅在压缩成功时轮换，query.ts:523）")
            .isEqualTo(turnIdAfterCompact);
        assertThat(auto.getTracking().isCompacted())
            .as("compacted 在 query 调用内保持 true（isRecompactionInChain 持续，autoCompact.ts:280）")
            .isTrue();
    }

    @Test
    @DisplayName("二次压缩: turnId 再次轮换 + turnCounter 再归零（recompaction 链）")
    void secondCompactRotatesAgain() {
        AutoCompactor auto = compactingAutoCompactor();
        auto.tryAutoCompact(largeMessages(50));
        String firstTurnId = auto.getTracking().getTurnId();
        auto.getTracking().startNewTurn();  // 模拟 1 个后置回合
        assertThat(auto.getTracking().getTurnCounter()).isEqualTo(1);

        auto.tryAutoCompact(largeMessages(50));  // 二次压缩（recompaction）

        assertThat(auto.getTracking().getTurnId())
            .as("recompaction 成功必须再次轮换 turnId").isNotEqualTo(firstTurnId);
        assertThat(auto.getTracking().getTurnCounter())
            .as("recompaction 成功必须再次归零 turnCounter").isZero();
        assertThat(auto.getTracking().isCompacted()).isTrue();
    }

    @Test
    @DisplayName("recompactionInfo 输入源: turnsSincePreviousCompact ← tracking.turnCounter（autoCompact.ts:281）")
    void turnCounterDrivesRecompactionInfoSource() {
        AutoCompactor auto = compactingAutoCompactor();
        auto.tryAutoCompact(largeMessages(50));  // 压缩成功 → turnCounter=0
        auto.getTracking().startNewTurn();
        auto.getTracking().startNewTurn();

        assertThat(auto.getTracking().getTurnCounter())
            .as("压缩后第 N 回合 → turnsSincePreviousCompact=N（autoCompact.ts:281）").isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // S-7 · 熔断范围登记（per-run = CC 单次 query() 调用语义）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("熔断范围: 单次 run 内累计（回合间不清零），run 边界 reset() 归零（S-7 per-run 登记）")
    void circuitBreakerAccumulatesWithinRunAndResetsAtRunBoundary() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000, (p, m) -> { throw new RuntimeException("boom"); });

        // run 内两次失败（两个工具回合）→ 累计不归零
        auto.tryAutoCompact(largeMessages(50));
        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().getConsecutiveFailures())
            .as("run 内跨回合累计（CC 单 query() 调用内累计，autoCompact.ts:341-342）").isEqualTo(2);
        assertThat(auto.getTracking().isCircuitBreakerOpen()).isFalse();

        // 第三次失败 → 熔断打开（≥3，autoCompact.ts:262）
        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().isCircuitBreakerOpen()).isTrue();

        // run 边界（下一次用户回合）→ LlmAgentLoop:1615-1623 reset() → 熔断复位
        auto.reset();
        assertThat(auto.getTracking().getConsecutiveFailures())
            .as("run 边界 reset 归零（对齐 CC query.ts:272 每 query 调用 tracking 重新初始化）").isZero();
        assertThat(auto.getTracking().isCircuitBreakerOpen()).isFalse();
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
}
