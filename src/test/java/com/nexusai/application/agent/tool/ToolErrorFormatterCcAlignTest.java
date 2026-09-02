package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-C4 REQ-G3-2-3 / REQ-G3-2-4] formatError CC 对齐（ShellError 展开 + 10000 截断 +
 * AbortError 特例）+ EMPTY_USAGE 10 字段。
 *
 * <p>CC 真源:
 * <ul>
 *   <li>{@code formatError} — Open-ClaudeCode/src/utils/toolErrors.ts:5-22</li>
 *   <li>{@code getErrorParts} — toolErrors.ts:24-41</li>
 *   <li>{@code ShellError} — utils/errors.ts: ShellError（stdout/stderr/code/interrupted）</li>
 *   <li>{@code EMPTY_USAGE} — services/api/emptyUsage.ts:8-22（10 顶层字段）</li>
 * </ul>
 *
 * <p>WHY (规则九): 每个测试断言 CC 语义 — formatError 旧实现 {@code "<Class>: <msg>"}（TR-A2 S-10 /
 * TR-A3 △-13），Bash 失败 LLM 可见文本可读性差；EMPTY_USAGE 旧 7 字段（E7 ✗）。
 */
@DisplayName("[IMP-C4] ToolErrorFormatter formatError CC 对齐 + EMPTY_USAGE 10 字段")
class ToolErrorFormatterCcAlignTest {

    // ── formatError: ShellError 展开 (CC toolErrors.ts:24-32) ──

    @Test
    @DisplayName("ShellError → 'Exit code N' + interrupted 标记 + stderr + stdout 分区 (toolErrors.ts:26-32)")
    void formatError_shellError_expandsParts() {
        // WHY: CC getErrorParts ShellError → [Exit code N, interrupted?, stderr, stdout]，join('\n')。
        //   变异点: 回退旧 `<Class>: <msg>` → 断言红.
        ShellError se = new ShellError("out-lines", "err-lines", 2, false);
        String formatted = ToolErrorFormatter.formatError(se);
        assertThat(formatted)
            .as("ShellError 展开为分区文本 (CC toolErrors.ts:26-32)")
            .isEqualTo("Exit code 2\nerr-lines\nout-lines");
    }

    @Test
    @DisplayName("ShellError interrupted=true → 追加 INTERRUPT_MESSAGE_FOR_TOOL_USE (toolErrors.ts:28)")
    void formatError_shellErrorInterrupted_appendsInterruptMessage() {
        // WHY: CC getErrorParts interrupted → INTERRUPT_MESSAGE_FOR_TOOL_USE 占位。
        //   变异点: 忽略 interrupted → 断言红.
        ShellError se = new ShellError("", "boom", 130, true);
        String formatted = ToolErrorFormatter.formatError(se);
        assertThat(formatted)
            .as("interrupted ShellError 含中断标记 (CC toolErrors.ts:28)")
            .isEqualTo("Exit code 130\n[Request interrupted by user for tool use]\nboom");
    }

    // ── formatError: AbortError 特例 (CC toolErrors.ts:6-8) ──

    @Test
    @DisplayName("AbortException message 非空 → 返回 message；null → INTERRUPT_MESSAGE_FOR_TOOL_USE (toolErrors.ts:6-8)")
    void formatError_abortError_messageOrInterruptFallback() {
        // WHY: CC `error instanceof AbortError → error.message || INTERRUPT_MESSAGE_FOR_TOOL_USE`。
        //   变异点: 不特判 AbortException → 走通用 `<Class>: <msg>` → 断言红.
        com.nexusai.application.agent.permission.hook.AbortException withMsg =
            new com.nexusai.application.agent.permission.hook.AbortException("user pressed escape");
        assertThat(ToolErrorFormatter.formatError(withMsg))
            .as("AbortError message 非空 → 原样返回")
            .isEqualTo("user pressed escape");

        com.nexusai.application.agent.permission.hook.AbortException noMsg =
            new com.nexusai.application.agent.permission.hook.AbortException(null);
        assertThat(ToolErrorFormatter.formatError(noMsg))
            .as("AbortError message 空 → INTERRUPT_MESSAGE_FOR_TOOL_USE (CC toolErrors.ts:7)")
            .isEqualTo(com.nexusai.application.agent.LlmAgentLoop.INTERRUPT_MESSAGE_FOR_TOOL_USE);
    }

    // ── formatError: 10000 截断 (CC toolErrors.ts:15-21) ──

    @Test
    @DisplayName("超长错误消息截断为头尾 5000 + '... [N characters truncated] ...' (toolErrors.ts:15-21)")
    void formatError_longMessage_truncatedAt10000() {
        // WHY: CC fullMessage.length > 10000 → 头尾各 5000，中插截断提示。
        //   变异点: 去掉截断 → 全量返回 → 长度断言红.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11000; i++) {
            sb.append('x');
        }
        String formatted = ToolErrorFormatter.formatError(new RuntimeException(sb.toString()));
        // 5000 + "\n\n... [1000 characters truncated] ...\n\n"(39) + 5000 = 10039
        assertThat(formatted).hasSize(10039);
        assertThat(formatted).startsWith("x".repeat(5000));
        assertThat(formatted).endsWith("x".repeat(5000));
        assertThat(formatted)
            .as("截断提示含真实截断字符数 (CC toolErrors.ts:21)")
            .contains("... [1000 characters truncated] ...");
    }

    @Test
    @DisplayName("≤10000 消息不截断；空 parts → 'Command failed with no output' (toolErrors.ts:13-14)")
    void formatError_shortOrEmpty_fullMessageOrFallback() {
        // WHY: CC fullMessage = parts.filter(Boolean).join('\n').trim() || 'Command failed with no output'。
        //   变异点: 回退空兜底 / 截断阈值错 → 断言红.
        assertThat(ToolErrorFormatter.formatError(new RuntimeException("short error")))
            .as("≤10000 消息原样返回")
            .isEqualTo("short error");
        assertThat(ToolErrorFormatter.formatError(new RuntimeException()))
            .as("message null → parts 全空 → 'Command failed with no output' (CC toolErrors.ts:14)")
            .isEqualTo("Command failed with no output");
    }

    // ── formatError: 通用异常 duck-typing stderr/stdout (CC toolErrors.ts:34-40) ──

    @Test
    @DisplayName("异常携带 getStderr/getStdout → message + stderr + stdout 分区 (toolErrors.ts:34-40)")
    void formatError_genericException_duckTypedStderrStdout() {
        // WHY: CC `'stderr' in error` duck-typing 把带 stderr/stdout 的任意错误展开。
        //   变异点: 不检查 getter → 丢失 stderr/stdout 分区 → 断言红.
        RuntimeException withStreams = new RuntimeException("boom") {
            public String getStderr() { return "err-part"; }
            public String getStdout() { return "out-part"; }
        };
        assertThat(ToolErrorFormatter.formatError(withStreams))
            .as("通用异常 message + getStderr + getStdout 分区 (CC toolErrors.ts:34-40)")
            .isEqualTo("boom\nerr-part\nout-part");
    }

    // ── EMPTY_USAGE 10 字段 (CC emptyUsage.ts:8-22 / REQ-G3-2-4) ──

    @Test
    @DisplayName("AgentUsage.EMPTY 11 顶层字段（emptyUsage.ts:8-22 + cache_deleted_input_tokens，E7 ✗ 关闭）")
    void emptyUsage_hasAllTenTopLevelFields() {
        // WHY: E7 (EV-A1-019) — Java EMPTY 仅 7 字段，缺 inference_geo/iterations/speed。
        //   变异点: 移除 3 新增字段 → 组件数变 7 → 断言红.
        //   2026-08-30 追加 cacheDeletedInputTokens（CC cache_deleted_input_tokens，query.ts:877 /
        //   claude.ts:2965-2973，compact_v5 2c8285b25 加入）→ 10 → 11。
        RecordComponent[] comps = AgentUsage.class.getRecordComponents();
        assertThat(comps).hasSize(11);
        assertThat(java.util.Arrays.stream(comps).map(RecordComponent::getName))
            .containsExactlyInAnyOrder(
                "inputTokens", "outputTokens", "cacheCreationInputTokens", "cacheReadInputTokens",
                "serverToolUse", "serviceTier", "cacheCreation",
                "inferenceGeo", "iterations", "speed", "cacheDeletedInputTokens");
    }

    @Test
    @DisplayName("EMPTY 值零初始化: inferenceGeo='' / iterations=[] / speed='standard'（emptyUsage.ts:19-21）")
    void emptyUsage_zeroInitValues() {
        // WHY: CC emptyUsage.ts:19-21 零初始化值（inference_geo:'', iterations:[], speed:'standard'）。
        //   变异点: 默认值漂移 → 断言红.
        assertThat(AgentUsage.EMPTY.inferenceGeo()).isEqualTo("");
        assertThat(AgentUsage.EMPTY.iterations()).isEmpty();
        assertThat(AgentUsage.EMPTY.speed()).isEqualTo("standard");
        assertThat(AgentUsage.EMPTY.inputTokens()).isZero();
        assertThat(AgentUsage.EMPTY.serviceTier()).isEqualTo("standard");
        assertThat(AgentUsage.EMPTY.serverToolUse()).isNotNull();
        assertThat(AgentUsage.EMPTY.cacheCreation()).isNotNull();
    }
}
