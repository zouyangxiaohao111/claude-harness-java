package com.nexusai.application.agent.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoopReason 枚举完整性测试 · 对齐 CC query.ts Terminal/Continue reason 全集。
 *
 * <p>CC 真源（grep 自验 {@code Open-ClaudeCode/src/query.ts}，共 18 处 / 17 个唯一 reason）：
 * <pre>
 * Terminal 10: blocking_limit(646) / image_error(977) / model_error(996) /
 *              aborted_streaming(1051) / prompt_too_long(1182) / completed(1264,1357) /
 *              stop_hook_prevented(1279) / aborted_tools(1515) / hook_stopped(1520) /
 *              max_turns(1711)
 * Continue 7:  collapse_drain_retry(1110) / reactive_compact_retry(1162) /
 *              max_output_tokens_escalate(1217) / max_output_tokens_recovery(1246) /
 *              stop_hook_blocking(1302) / token_budget_continuation(1338) / next_turn(1725)
 * </pre>
 *
 * <p>类型源断链（OPD-ER-01）：CC query.ts:104 {@code import type { Terminal, Continue }
 * from './query/transitions.js'}，但该文件不存在——Java 依据 query.ts 实际 return/
 * transition 字面量重建全集（见 {@link LoopReason} 类级 javadoc）。
 *
 * <p><b>WHY (意图验证)</b>: 本测试锁定 Java 端 reason 契约与 CC 字面量全集的一致性——
 * 一旦枚举增删值（如 Java 旧 withRetry 域动作 BACKOFF_RETRY/FALLBACK_MODEL/EXHAUSTED/FATAL
 * 回潮，DC-09）、或 isTerminal/isContinue 分组漂移，以下任一断言必须变红。这是防回归闸：
 * 旧实现（删除前）Transition.java 有 8 值混合枚举，本测试确保不得回潮。
 */
class LoopReasonCompletenessTest {

    private static final LoopReason[] TERMINAL =
        {LoopReason.BLOCKING_LIMIT, LoopReason.IMAGE_ERROR, LoopReason.MODEL_ERROR,
         LoopReason.ABORTED_STREAMING, LoopReason.PROMPT_TOO_LONG, LoopReason.COMPLETED,
         LoopReason.STOP_HOOK_PREVENTED, LoopReason.ABORTED_TOOLS, LoopReason.HOOK_STOPPED,
         LoopReason.MAX_TURNS};

    private static final LoopReason[] CONTINUE =
        {LoopReason.COLLAPSE_DRAIN_RETRY, LoopReason.REACTIVE_COMPACT_RETRY,
         LoopReason.MAX_OUTPUT_TOKENS_ESCALATE, LoopReason.MAX_OUTPUT_TOKENS_RECOVERY,
         LoopReason.STOP_HOOK_BLOCKING, LoopReason.TOKEN_BUDGET_CONTINUATION,
         LoopReason.NEXT_TURN};

    @Test
    @DisplayName("枚举仅含 CC 17 reason 全集：10 Terminal + 7 Continue，无 withRetry 域值回潮")
    void enumContainsExactlyCcSeventeenReasons() {
        assertThat(LoopReason.values())
            .hasSize(17)
            .containsExactlyInAnyOrder(
                LoopReason.BLOCKING_LIMIT, LoopReason.IMAGE_ERROR, LoopReason.MODEL_ERROR,
                LoopReason.ABORTED_STREAMING, LoopReason.PROMPT_TOO_LONG, LoopReason.COMPLETED,
                LoopReason.STOP_HOOK_PREVENTED, LoopReason.ABORTED_TOOLS, LoopReason.HOOK_STOPPED,
                LoopReason.MAX_TURNS,
                LoopReason.COLLAPSE_DRAIN_RETRY, LoopReason.REACTIVE_COMPACT_RETRY,
                LoopReason.MAX_OUTPUT_TOKENS_ESCALATE, LoopReason.MAX_OUTPUT_TOKENS_RECOVERY,
                LoopReason.STOP_HOOK_BLOCKING, LoopReason.TOKEN_BUDGET_CONTINUATION,
                LoopReason.NEXT_TURN);
    }

    @Test
    @DisplayName("isTerminal() 恰为 CC return 终止 10 值（query.ts Terminal）")
    void terminalClassificationMatchesCcTerminalSet() {
        assertThat(Arrays.stream(LoopReason.values()).filter(LoopReason::isTerminal).toArray())
            .containsExactlyInAnyOrder((Object[]) TERMINAL);
        for (LoopReason r : TERMINAL) {
            assertThat(r.isTerminal())
                .as("CC Terminal reason %s 应为 isTerminal()==true", r)
                .isTrue();
        }
    }

    @Test
    @DisplayName("isContinue() 恰为 CC transition 续传 7 值（query.ts Continue）")
    void continueClassificationMatchesCcContinueSet() {
        assertThat(Arrays.stream(LoopReason.values()).filter(LoopReason::isContinue).toArray())
            .containsExactlyInAnyOrder((Object[]) CONTINUE);
        for (LoopReason r : CONTINUE) {
            assertThat(r.isContinue())
                .as("CC Continue reason %s 应为 isContinue()==true", r)
                .isTrue();
        }
    }

    @Test
    @DisplayName("Terminal/Continue 分组互斥且无遗漏（并集=全集 17）")
    void terminalAndContinueArePartitionOfFullSet() {
        assertThat(TERMINAL.length).isEqualTo(10);
        assertThat(CONTINUE.length).isEqualTo(7);
        // 分组内无重复
        assertThat(Arrays.stream(TERMINAL).distinct().count()).isEqualTo(TERMINAL.length);
        assertThat(Arrays.stream(CONTINUE).distinct().count()).isEqualTo(CONTINUE.length);
        // 并集恰为枚举全集（17）
        assertThat(Enum.valueOf(LoopReason.class, "NEXT_TURN")).isEqualTo(LoopReason.NEXT_TURN);
        assertThat(TERMINAL.length + CONTINUE.length).isEqualTo(LoopReason.values().length);
    }

    @Test
    @DisplayName("枚举名映射 CC 字面量全集：snake_case reason 逐值命中")
    void enumNamesCoverAllCcReasonLiterals() {
        // CC reason 字面量全集（grep 自验 query.ts，18 处 / 17 唯一）
        String[] ccLiterals = {
            "blocking_limit", "image_error", "model_error", "aborted_streaming",
            "prompt_too_long", "completed", "stop_hook_prevented", "aborted_tools",
            "hook_stopped", "max_turns",
            "collapse_drain_retry", "reactive_compact_retry", "max_output_tokens_escalate",
            "max_output_tokens_recovery", "stop_hook_blocking", "token_budget_continuation",
            "next_turn",
        };
        // 枚举名 → snake_case 反推：MAX_TURNS → max_turns；逐值对比应全部命中 CC 字面量。
        for (LoopReason r : LoopReason.values()) {
            String snake = r.name().toLowerCase();
            assertThat(Arrays.asList(ccLiterals))
                .as("LoopReason.%s 应能在 CC reason 字面量全集中命中（%s）", r, snake)
                .contains(snake);
        }
        assertThat(ccLiterals).hasSize(17);
    }
}
