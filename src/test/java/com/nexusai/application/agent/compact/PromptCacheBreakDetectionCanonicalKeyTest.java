package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP2-01 · V2-M1/V2-S5] PromptCacheBreakDetection canonical 键 + MicroCompactor 默认
 * notifier 门控契约测试（querySource 值域 canonical 统一）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: V2 探查发现生产传 {@code QuerySource.name()}
 * 大写枚举名（{@code REPL_MAIN_THREAD}）而匹配侧 {@code getTrackingKey} 前缀匹配 CC 小写字面量
 * （{@code 'repl_main_thread'}）→ 生产恒失配：notifyCacheDeletion 恒 no-op，cache break 检测
 * 把「我们自己内容清除造成的 cache read 下降」误报为 break。IMP2-01 修复 = 判定入口
 * {@code QuerySource.canonicalize} 归一：大写 {@code REPL_MAIN_THREAD} → 小写
 * {@code repl_main_thread} 再前缀匹配（小写幂等）。同时覆盖 V2-S5 门控侧：MicroCompactor
 * 默认 notifier 必须经 {@code gatedBy(FeatureFlags.promptCacheBreakDetection())}
 * （feature 关 → 恒 no-op）。
 *
 * <p><b>注意（IMP2-04 重建）</b>: 本类原由 IMP2-01 创建（untracked），IMP2-04 改造
 * MicroCompactor 配置注入面时静态钩子 {@code setTimeBasedMCConfig} 被移除，本类同步改为
 * 构造器注入（{@code new MicroCompactor(() -> config)}）；文件本体因环境事故被清空后按
 * surefire 方法清单 + IMP2-01 语义重建（方法名不变，6 用例）。
 */
class PromptCacheBreakDetectionCanonicalKeyTest {

    private static final AtomicInteger BREAK_EVENTS = new AtomicInteger();

    @BeforeEach
    @AfterEach
    void resetSharedState() {
        BREAK_EVENTS.set(0);
        MicroCompactor.resetMicrocompactState();
        MicroCompactor.setNowForTest(0L);
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.setFeatureFlags(FeatureFlags.ALL_DISABLED);
        new PromptCacheBreakDetection(r -> {}).resetPromptCacheBreakDetection();
    }

    private static PromptCacheBreakDetection.PromptStateSnapshot snapshot(String querySource) {
        return new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(Map.of("type", "text", "text", "sys")),
            List.of(Map.of("name", "toolA")),
            querySource, "claude-sonnet-4-6", null,
            false, "", List.of(), false, false, false, null, null);
    }

    private static ChatMessageDto assistantMsg(String id, String toolCallId, String toolName,
                                               OffsetDateTime createdAt) {
        return new ChatMessageDto(id, "s1", Role.assistant, "assistant", "thinking " + id, null,
            List.of(new ToolCallDto(toolCallId, toolName, "{}", null, false)),
            FinishReason.stop, null, null, "刚刚", createdAt, null, id, null,
            List.of(), List.of(), null, false, false);
    }

    private static ChatMessageDto toolResultMsg(String id, String toolCallId, String content,
                                                OffsetDateTime createdAt) {
        return new ChatMessageDto(id, "s1", Role.tool, "tool", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", createdAt, toolCallId, id, null,
            List.of(), List.of(), null, false, false);
    }

    /** 距 nowMs 2 小时前的消息链（3 个可压缩工具 Read/Bash/Grep）· gap=120min > 默认 60min。 */
    private static List<ChatMessageDto> timeBasedMessages(long nowMs) {
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

    // ════════════════════════════════════════════════════════════════════
    // getTrackingKey canonical 归一（V2-S5 key 命中）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getTrackingKey 生产值域: 大写枚举名 canonical 归一后命中（REPL_MAIN_THREAD/COMPACT/SDK/FORK）")
    void getTrackingKey_uppercaseProductionValues_hits() {
        assertThat(PromptCacheBreakDetection.getTrackingKey("REPL_MAIN_THREAD", null))
            .as("REPL_MAIN_THREAD → canonical repl_main_thread 命中").isEqualTo("repl_main_thread");
        assertThat(PromptCacheBreakDetection.getTrackingKey("COMPACT", null))
            .as("COMPACT → canonical compact → CC 特判 repl_main_thread").isEqualTo("repl_main_thread");
        assertThat(PromptCacheBreakDetection.getTrackingKey("SDK", "agent-9"))
            .as("SDK → canonical sdk 命中 → agentId 优先").isEqualTo("agent-9");
        assertThat(PromptCacheBreakDetection.getTrackingKey("FORK", null))
            .as("FORK → canonical agent:builtin:fork 命中（前缀 agent:builtin）").isEqualTo("agent:builtin:fork");
    }

    @Test
    @DisplayName("getTrackingKey 幂等/未跟踪: 小写原样命中；未跟踪源 → null")
    void getTrackingKey_lowercaseIdempotent_untrackedNull() {
        assertThat(PromptCacheBreakDetection.getTrackingKey("repl_main_thread", null))
            .as("小写幂等（canonicalize 未知名原样）").isEqualTo("repl_main_thread");
        assertThat(PromptCacheBreakDetection.getTrackingKey("sdk", "agent-3"))
            .as("小写 sdk 命中 → agentId 优先").isEqualTo("agent-3");
        assertThat(PromptCacheBreakDetection.getTrackingKey("some_unknown_source", null))
            .as("未跟踪源 → null（不产生跟踪）").isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // notifyCacheDeletion 生效链（canonical 键命中 → 抑制误报）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canonical 键: 大写 REPL_MAIN_THREAD 下 notifyCacheDeletion 命中 → 抑制 cache break 误报")
    void canonicalKey_notifyCacheDeletion_suppressesFalseBreak() {
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(r -> BREAK_EVENTS.incrementAndGet());
        detector.recordPromptState(snapshot("REPL_MAIN_THREAD"));
        detector.checkResponseForCacheBreak("REPL_MAIN_THREAD", 4000, 0, System.currentTimeMillis(), null, "r1");
        assertThat(BREAK_EVENTS.get()).as("首次 check 无 prevCacheRead → 不触发").isZero();

        // 生产大写（name() 原值）notifyCacheDeletion → canonical 键命中 → cacheDeletionsPending
        detector.notifyCacheDeletion("REPL_MAIN_THREAD", null);
        detector.checkResponseForCacheBreak("REPL_MAIN_THREAD", 1000, 0, System.currentTimeMillis(), null, "r2");
        assertThat(BREAK_EVENTS.get()).as("大写键 notify 后低 read 不触发（抑制误报，V2-S5）").isZero();
    }

    @Test
    @DisplayName("对照: 无 notifyCacheDeletion（未压缩）→ 低 read 照常触发 cache break 事件")
    void withoutNotify_cacheBreakStillFires() {
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(r -> BREAK_EVENTS.incrementAndGet());
        detector.recordPromptState(snapshot("REPL_MAIN_THREAD"));
        detector.checkResponseForCacheBreak("REPL_MAIN_THREAD", 4000, 0, System.currentTimeMillis(), null, "r1");
        detector.checkResponseForCacheBreak("REPL_MAIN_THREAD", 1000, 0, System.currentTimeMillis(), null, "r2");
        assertThat(BREAK_EVENTS.get())
            .as("无 notify → 4000→1000 大降（≥2000）触发事件").isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // MicroCompactor 默认 notifier 门控（V2-S5：defaultInstance 绕过 gatedBy）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("门控: MicroCompactor feature 开启 → time-based 触发经默认 notifier 置 cacheDeletionsPending")
    void microCompactor_featureOn_notifierTakesEffect() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        // PROMPT_CACHE_BREAK_DETECTION 开启（FeatureFlags 构造第 4 位）
        MicroCompactor.setFeatureFlags(new FeatureFlags(false, false, false, true,
            false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false));
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(r -> BREAK_EVENTS.incrementAndGet(), true);
        detector.recordPromptState(snapshot("REPL_MAIN_THREAD"));
        detector.checkResponseForCacheBreak("REPL_MAIN_THREAD", 4000, 0, System.currentTimeMillis(), null, "r1");

        // 生产值域触发 time-based MC → 默认 notifier（gatedBy 开启）→ cacheDeletionsPending
        new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1))
            .microcompactMessages(timeBasedMessages(now), "REPL_MAIN_THREAD");

        detector.checkResponseForCacheBreak("REPL_MAIN_THREAD", 1000, 0, System.currentTimeMillis(), null, "r2");
        assertThat(BREAK_EVENTS.get()).as("feature 开时默认 notifier 必须生效（抑制误报）").isZero();
    }

    @Test
    @DisplayName("门控: MicroCompactor feature 关（默认）→ time-based 触发不产生跟踪副作用")
    void microCompactor_featureOff_notifierNoop() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        // feature 默认关（ALL_DISABLED）→ gatedBy 返回 enabled=false → notify no-op
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(r -> BREAK_EVENTS.incrementAndGet(), true);

        new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1))
            .microcompactMessages(timeBasedMessages(now), "REPL_MAIN_THREAD");

        assertThat(detector.getTrackedSourceCount())
            .as("feature 关时默认 notifier no-op，不产生任何跟踪状态").isZero();
    }
}
