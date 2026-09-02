package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-04 · time-based MC 配置注入契约测试 · 对齐 CC timeBasedMCConfig.ts:36-43
 * （GrowthBook {@code tengu_slate_heron} 配置通道的 Java 等价——Spring 属性注入）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP2-04 的目标是把 time-based MC 配置从
 * {@code static volatile} 恒 DEFAULTS（enabled 恒 false，生产不可开）改为<b>生产配置通道可注入</b>：
 * <ul>
 *   <li>默认值对齐 CC TIME_BASED_MC_CONFIG_DEFAULTS（timeBasedMCConfig.ts:30-34）=
 *       {@code {enabled:false, gapThresholdMinutes:60, keepRecent:5}}</li>
 *   <li>enabled=true 注入 → time-based 触发路径真实执行（microCompact.ts:422-444）</li>
 *   <li>gapThresholdMinutes 可调 → 触发阈值随配置改变</li>
 *   <li>enabled=false 注入 → 保持 no-op（INV-10）</li>
 *   <li>配置源每次评估实时读取（对齐 CC "hoist the GB read"，timeBasedMCConfig.ts:37-38）</li>
 * </ul>
 *
 * <p><b>生产接线</b>: {@code ToolRegistrationConfig.microCompactor()} 从
 * {@code nexusai.feature.time-based-mc.*} 属性构建配置注入（GB 未接入，属性为等价载体）。
 */
class TimeBasedMcConfigInjectionCcTest {

    private static final String SESSION = "s1";
    private static final String CLEARED = CompactConstants.TIME_BASED_MC_CLEARED_MESSAGE;

    @AfterEach
    void resetStaticState() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.setNowForTest(0L);
        MicroCompactor.resetMicrocompactState();
        CompactWarningState.clearCompactWarningSuppression();
    }

    // ─────────────────────── 消息构造 ───────────────────────

    private static ChatMessageDto assistantMsg(String id, String toolCallId, String toolName,
                                               OffsetDateTime createdAt) {
        return new ChatMessageDto(id, SESSION, Role.assistant, "assistant", "thinking " + id, null,
            List.of(new ToolCallDto(toolCallId, toolName, "{}", null, false)),
            FinishReason.stop, null, null, "刚刚", createdAt, null, id, null,
            List.of(), List.of(), null, false, false);
    }

    private static ChatMessageDto toolResultMsg(String id, String toolCallId, String content,
                                                OffsetDateTime createdAt) {
        return new ChatMessageDto(id, SESSION, Role.tool, "tool", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", createdAt, toolCallId, id, null,
            List.of(), List.of(), null, false, false);
    }

    /** 距 fixedNow 2 小时前的消息链（3 个可压缩工具 Read/Bash/Grep），触发 gap=120min。 */
    private static List<ChatMessageDto> buildTimeBasedMessages(long nowMs) {
        OffsetDateTime old = OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs - 2 * 3600_000L),
            ZoneOffset.UTC);
        return List.of(
            assistantMsg("asst-1", "t1", "Read", old),
            toolResultMsg("tool-1", "t1", "x".repeat(40), old),
            assistantMsg("asst-2", "t2", "Bash", old),
            toolResultMsg("tool-2", "t2", "x".repeat(8), old),
            assistantMsg("asst-3", "t3", "Grep", old),
            toolResultMsg("tool-3", "t3", "x".repeat(12), old));
    }

    private static long clearedCount(List<ChatMessageDto> messages) {
        return messages.stream().filter(m -> CLEARED.equals(m.content())).count();
    }

    /** 生产等价注入：ToolRegistrationConfig 以属性构建配置（GB tengu_slate_heron 等价载体）。 */
    private static MicroCompactor injected(boolean enabled, int gapThresholdMinutes, int keepRecent) {
        return new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(
            enabled, gapThresholdMinutes, keepRecent));
    }

    // ════════════════════════════════════════════════════════════════════
    // 默认值对齐 CC（timeBasedMCConfig.ts:30-34）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认配置对齐 CC TIME_BASED_MC_CONFIG_DEFAULTS {enabled:false, gap:60, keepRecent:5}")
    void defaults_alignCc() {
        assertThat(MicroCompactor.TimeBasedMCConfig.DEFAULTS.enabled()).isFalse();
        assertThat(MicroCompactor.TimeBasedMCConfig.DEFAULTS.gapThresholdMinutes()).isEqualTo(60);
        assertThat(MicroCompactor.TimeBasedMCConfig.DEFAULTS.keepRecent()).isEqualTo(5);
    }

    @Test
    @DisplayName("无参构造默认配置源 = DEFAULTS → enabled=false 不触发（INV-10 默认 no-op）")
    void defaultSource_disabled_noTrigger() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactResult result = new MicroCompactor().microcompactMessages(messages, "repl_main_thread");

        assertThat(clearedCount(result.messages())).as("默认 enabled=false → time-based 不触发").isZero();
        assertThat(result.messages()).as("no-op 返回原列表引用").isSameAs(messages);
    }

    // ════════════════════════════════════════════════════════════════════
    // enabled=true 注入 → time-based 触发路径（microCompact.ts:422-444）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("注入 enabled=true + gap=60 → gap=120min 触发清除（3 工具清 2 留 1）")
    void injectedEnabled_true_triggers() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactResult result = injected(true, 60, 1).microcompactMessages(messages, "REPL_MAIN_THREAD");

        assertThat(clearedCount(result.messages()))
            .as("生产大写 name() + 注入 enabled=true → time-based 清除 2 条留 1").isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // gapThresholdMinutes 可调（配置注入生效）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("注入 gapThresholdMinutes=150 → gap=120 < 150 不触发；gap=60 → 触发（阈值可调）")
    void injected_gapThreshold_adjustable() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactResult strict = injected(true, 150, 5).microcompactMessages(messages, "repl_main_thread");
        assertThat(clearedCount(strict.messages()))
            .as("gap=120min < 阈值 150min → 不触发").isZero();

        MicroCompactResult loose = injected(true, 60, 1).microcompactMessages(messages, "repl_main_thread");
        assertThat(clearedCount(loose.messages()))
            .as("gap=120min >= 阈值 60min → 触发（keepRecent=1 清 2 留 1）").isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // enabled=false 注入 → 保持 no-op
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("注入 enabled=false + 阈值可调 → time-based 恒 no-op（主开关优先）")
    void injectedEnabled_false_noOp() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactResult result = injected(false, 30, 1).microcompactMessages(messages, "repl_main_thread");

        assertThat(clearedCount(result.messages()))
            .as("enabled=false → 即便 gap 超阈值也不触发").isZero();
        assertThat(result.messages()).isSameAs(messages);
    }

    // ════════════════════════════════════════════════════════════════════
    // 配置源每次评估实时读取（对齐 CC "hoist the GB read"）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Supplier 配置源每次评估实时读取：同实例先关后开 → 第二次评估触发（GB read hoist 语义）")
    void supplier_reReadPerEvaluation() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);
        AtomicReference<MicroCompactor.TimeBasedMCConfig> config =
            new AtomicReference<>(MicroCompactor.TimeBasedMCConfig.DEFAULTS);
        Supplier<MicroCompactor.TimeBasedMCConfig> source = config::get;
        MicroCompactor mc = new MicroCompactor(source);

        MicroCompactResult before = mc.microcompactMessages(messages, "repl_main_thread");
        assertThat(clearedCount(before.messages())).as("配置关 → 不触发").isZero();

        // 生产配置热更新（GB 等价：feature 值变化 → 下次评估生效）
        config.set(new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        MicroCompactResult after = mc.microcompactMessages(messages, "repl_main_thread");
        assertThat(clearedCount(after.messages()))
            .as("同实例配置源更新后 → 下一次评估实时读取并触发").isEqualTo(2);
    }
}
