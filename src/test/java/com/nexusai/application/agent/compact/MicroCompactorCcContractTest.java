package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.common.RequestContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-09 · microcompactMessages 链式入口契约测试 · 对齐 CC microCompact.ts:253-530。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-09 的目标是把 MicroCompactor 从
 * 「L3 内容清除主路径」（D-12，token 压力下清内容，与 CC 相反）重建为 CC 链式入口：
 * {@code clearCompactWarningSuppression → time-based 短路 → cached 门控 → 默认 no-op}
 * （INV-10）。本测试逐条验证 IMP-09 §5 验收：
 * <ol>
 *   <li>链式入口顺序（clear→time-based 短路→cached 门控→no-op）</li>
 *   <li>默认 no-op（INV-10）</li>
 *   <li>keepRecent floor 1（禁止清空全部，microCompact.ts:461）</li>
 *   <li>tokensSaved 真实估算（非 clearedCount×200 固定系数）</li>
 *   <li>resetMicrocompactState / notifyCacheDeletion 接线（cache break 误报防护）</li>
 *   <li>cached-MC 引用面（pendingCacheEdits 捕获/consumePendingCacheEdits，内部算法 OD-01 "?"）</li>
 * </ol>
 */
class MicroCompactorCcContractTest {

    private static final String SESSION = "s1";
    private static final String CLEARED = CompactConstants.TIME_BASED_MC_CLEARED_MESSAGE;

    @AfterEach
    void resetStaticState() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.setNowForTest(0L);
        MicroCompactor.resetMicrocompactState();
        CompactWarningState.clearCompactWarningSuppression();
        // OPD-CM5-A-10 会话级隔离：清 MDC 防止会话键泄漏到后续测试
        RequestContext.clear();
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

    /** 含 2 个可压缩工具（Read/Bash）的消息链（时间无关，用于 no-op/cached 门控用例）。 */
    private static List<ChatMessageDto> buildMessagesWithTools() {
        OffsetDateTime now = OffsetDateTime.now();
        return List.of(
            assistantMsg("asst-1", "t1", "Read", now),
            toolResultMsg("tool-1", "t1", "result one content", now),
            assistantMsg("asst-2", "t2", "Bash", now),
            toolResultMsg("tool-2", "t2", "result two content", now));
    }

    /** 距 fixedNow 2 小时前的消息链（3 个可压缩工具 Read/Bash/Grep），触发 gap=120min>60min。 */
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

    /** 含 n 个可压缩工具（Read）的 assistant+tool 链（toolCallId = prefix0..prefix(n-1)，用于会话级隔离用例）。 */
    private static List<ChatMessageDto> buildToolChain(int n, String prefix) {
        OffsetDateTime now = OffsetDateTime.now();
        List<ChatMessageDto> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(assistantMsg("asst-" + prefix + "-" + i, prefix + i, "Read", now));
            out.add(toolResultMsg("tool-" + prefix + "-" + i, prefix + i, "result content " + i, now));
        }
        return out;
    }

    private static long clearedCount(List<ChatMessageDto> messages) {
        return messages.stream().filter(m -> CLEARED.equals(m.content())).count();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1/2 · 链式入口顺序 + 默认 no-op（INV-10）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认 no-op（INV-10）：无 time-based/cached 触发时返回原消息，content 不清除")
    void defaultNoOp_returnsMessagesUnchanged() {
        List<ChatMessageDto> messages = buildMessagesWithTools();
        MicroCompactor mc = new MicroCompactor();

        MicroCompactResult result = mc.microcompactMessages(messages, null);

        assertThat(result.messages()).as("no-op 返回原列表引用（microCompact.ts:292）").isSameAs(messages);
        assertThat(result.compactionInfo()).as("no-op 无 compactionInfo").isNull();
        assertThat(clearedCount(result.messages())).as("no-op 不清除任何工具结果内容").isZero();
    }

    @Test
    @DisplayName("链式入口第 1 步：入口先复位警告抑制（microCompact.ts:259 clearCompactWarningSuppression）")
    void entry_clearsCompactWarningSuppression_first() {
        CompactWarningState.suppressCompactWarning();
        assertThat(CompactWarningState.isCompactWarningSuppressed())
            .as("前置：压缩后处于抑制态").isTrue();

        new MicroCompactor().microcompactMessages(List.of(), null);

        assertThat(CompactWarningState.isCompactWarningSuppressed())
            .as("新 microcompact 尝试开始必须复位抑制").isFalse();
    }

    @Test
    @DisplayName("链式入口第 2 步：time-based 短路先于 cached 门控（microCompact.ts:267-270）")
    void timeBased_shortCircuits_beforeCachedGate() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        MicroCompactor.setCachedMicrocompactEnabled(true); // 即便 cached 也开，time-based 仍先短路
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        MicroCompactResult result = mc.microcompactMessages(messages, "repl_main_thread");

        assertThat(clearedCount(result.messages()))
            .as("time-based 触发后内容被清除（cached 被跳过）").isEqualTo(2);
    }

    @Test
    @DisplayName("链式入口第 3 步：cached 门控仅 main-thread + feature 开启时进入路径（OD-01 引用面，无编辑产出）")
    void cachedGate_entersPath_onlyMainThread() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        List<ChatMessageDto> messages = buildMessagesWithTools();
        MicroCompactor mc = new MicroCompactor();

        // main-thread → cached 路径进入，内部算法 OD-01 "?" → 无编辑产出，返回原消息
        MicroCompactResult main = mc.microcompactMessages(messages, "repl_main_thread");
        assertThat(main.messages()).as("cached 路径（OD-01）无编辑产出返回原列表").isSameAs(messages);
        assertThat(main.compactionInfo()).as("无编辑产出 → compactionInfo null").isNull();

        // 非 main-thread（/compact 等）→ cached 门控不过，默认 no-op
        MicroCompactResult sub = mc.microcompactMessages(messages, "compact");
        assertThat(sub.messages()).as("非 main-thread 走默认 no-op").isSameAs(messages);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · keepRecent floor 1（microCompact.ts:461）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("time-based keepRecent floor 1：keepRecent=0 仍至少保留 1 条（禁止清空全部）")
    void timeBased_keepRecent_floorAt1() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now); // 3 个可压缩工具

        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 0));
        MicroCompactResult result = mc.microcompactMessages(messages, "repl_main_thread");

        assertThat(clearedCount(result.messages()))
            .as("keepRecent floor 1 → 3 个工具清 2 留 1").isEqualTo(2);
    }

    @Test
    @DisplayName("time-based keepRecent=2 保留最近 2 条，清 1 条")
    void timeBased_keepRecent_keepsTwo() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 2));
        MicroCompactResult result = mc.microcompactMessages(messages, "repl_main_thread");

        assertThat(clearedCount(result.messages())).as("3 个工具清 1 留 2").isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · tokensSaved 真实估算（非固定系数 200）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("time-based tokensSaved 真实口径（V2-S2）：等于 Σ calculateToolResultTokens（raw，无 ×4/3），非固定系数")
    void timeBased_tokensSaved_realEstimate_notFixedCoefficient() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);
        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));

        mc.microcompactMessages(messages, "repl_main_thread");

        // CC 口径（microCompact.ts:481）：tokensSaved += calculateToolResultTokens(block) —— 无 ×4/3 padding
        // 被清除两条工具消息（tool-1: 40 chars → rough=10，tool-2: 8 chars → rough=2）→ 真实合计 = 12
        TokenEstimator estimator = new TokenEstimator();
        int expected = estimator.calculateToolResultTokens(messages.get(1))
            + estimator.calculateToolResultTokens(messages.get(3));
        assertThat(mc.lastTimeBasedTokensSaved())
            .as("tokensSaved 必须等于 Σ calculateToolResultTokens（raw，无 ×4/3）").isEqualTo(expected);
        assertThat(mc.lastTimeBasedTokensSaved())
            .as("raw 合计 10+2=12（40 字符→round(40/4)=10，8 字符→round(8/4)=2）").isEqualTo(12);
        assertThat(mc.lastTimeBasedTokensSaved())
            .as("非 ×4/3 高估（单消息重载 ceil 后 14+3=17，高估 ~33%，V2-S2）").isNotEqualTo(17);
        assertThat(mc.lastTimeBasedTokensSaved())
            .as("非固定系数 200 的清除数倍（2×200=400）").isNotEqualTo(400);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · resetMicrocompactState / notifyCacheDeletion 接线（cache break 误报防护）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("time-based 成功后 resetMicrocompactState 清空 pendingCacheEdits + notifyCacheDeletion 以 querySource 通知")
    void timeBased_resetsMicrocompactState_andNotifiesCacheDeletion() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);
        MicroCompactor.setPendingCacheEditsForTest(new MicroCompactResult.PendingCacheEdits("auto", List.of("t1"), 0));
        AtomicReference<String> notified = new AtomicReference<>("__unset__");
        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        mc.setNotifyCacheDeletion((qs, aid) -> notified.set(qs));

        MicroCompactResult result = mc.microcompactMessages(messages, "repl_main_thread");

        assertThat(clearedCount(result.messages())).isEqualTo(2);
        // resetMicrocompactState 内部清空 pendingCacheEdits（microCompact.ts:513-517）
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("time-based 后 resetMicrocompactState 已清空 pendingCacheEdits").isNull();
        // notifyCacheDeletion 接线：以实际 querySource 通知（microCompact.ts:520-527，cache break 误报防护）
        assertThat(notified.get()).as("notifyCacheDeletion 必须以 querySource 通知").isEqualTo("repl_main_thread");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6 · cached-MC 引用面（pendingCacheEdits 捕获/consumePendingCacheEdits，OD-01 "?"）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cached-MC 引用面：consumePendingCacheEdits 返回并清空；resetMicrocompactState 复位")
    void cachedMC_referenceSurface_consumeAndReset() {
        assertThat(MicroCompactor.consumePendingCacheEdits()).as("未捕获时返回 null").isNull();

        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t1"), 123L));
        MicroCompactResult.PendingCacheEdits edits = MicroCompactor.consumePendingCacheEdits();
        assertThat(edits).isNotNull();
        assertThat(edits.trigger()).as("CC trigger 恒 'auto'").isEqualTo("auto");
        assertThat(edits.deletedToolIds()).containsExactly("t1");
        assertThat(edits.baselineCacheDeletedTokens()).isEqualTo(123L);
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("consume 后 pendingCacheEdits 已清空").isNull();

        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t2"), 0));
        MicroCompactor.resetMicrocompactState();
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("resetMicrocompactState 复位 pendingCacheEdits").isNull();
    }

    @Test
    @DisplayName("resetMicrocompactState 范围（V2-S6）：清空引用面 pendingCacheEdits；cached 门控配置非 reset 对象（CC microCompact.ts:130-135 仅 reset 状态数据）")
    void resetMicrocompactState_scope_clearsState_keepsGateConfig() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t1"), 7L));

        MicroCompactor.resetMicrocompactState();

        // CC 语义 1/2：pendingCacheEdits = null（microCompact.ts:134）——引用面内唯一状态
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("reset 必须清空 pendingCacheEdits（microCompact.ts:134）").isNull();
        // CC 语义 2/2：cachedMCState.resetCachedMCState（microCompact.ts:131-133）属缺失模块
        // cachedMicrocompact.js 内部算法（OD-01 "?"），Java 无镜像态可复位——引用面内 reset 范围 = pendingCacheEdits
        // 范围外：reset 不得复位 cached 门控配置（feature/module/model 模块态，CC reset 不触碰模块配置）
        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t2"), 5L));
        CompactBoundaryMessage boundary = MicroCompactor.maybeCreateMicrocompactBoundaryMessage(10L);
        assertThat(boundary).as("reset 后 feature 门仍开启 → pendingCacheEdits 可消费并 yield boundary（配置未被 reset 复位）")
            .isNotNull();
        assertThat(boundary.content()).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 补充 · evaluateTimeBasedTrigger 触发条件（microCompact.ts:422-444）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("evaluateTimeBasedTrigger：禁用/非 main-thread/无 assistant/不足阈值 → null")
    void evaluateTimeBasedTrigger_conditions() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        // 禁用 → null
        assertThat(new MicroCompactor().evaluateTimeBasedTrigger(messages, "repl_main_thread")).isNull();

        MicroCompactor enabled = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 5));
        // 启用 + 非 main-thread / null source → null（microCompact.ts:429-433）
        assertThat(enabled.evaluateTimeBasedTrigger(messages, "compact")).isNull();
        assertThat(enabled.evaluateTimeBasedTrigger(messages, null)).isNull();

        // 启用 + main-thread + gap 120min >= 60min → 非 null
        MicroCompactor.TimeBasedTriggerResult trigger =
            enabled.evaluateTimeBasedTrigger(messages, "repl_main_thread");
        assertThat(trigger).as("gap=120min >= 60min 必须触发").isNotNull();
        assertThat(trigger.gapMinutes()).isGreaterThanOrEqualTo(60.0);
        assertThat(trigger.config().keepRecent()).isEqualTo(5);

        // 无 assistant → null（microCompact.ts:434-437）
        List<ChatMessageDto> userOnly = List.of(toolResultMsg("tool-1", "t1", "x", OffsetDateTime.now()));
        assertThat(new MicroCompactor().evaluateTimeBasedTrigger(userOnly, "repl_main_thread")).isNull();
    }

    @Test
    @DisplayName("time-based 触发仅 main-thread：/compact 的 'compact' source 不触发，默认 no-op")
    void timeBased_notFired_forCompactQuerySource() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        MicroCompactResult result = mc.microcompactMessages(messages, "compact");

        assertThat(clearedCount(result.messages())).as("/compact 源不触发 time-based").isZero();
        assertThat(result.messages()).isSameAs(messages);
    }

    // ════════════════════════════════════════════════════════════════════
    // OD-01 · baseline 真实读取（最后 assistant usage.cache_deleted_input_tokens）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OD-01: cached-MC baseline 读取最后 assistant 的真实 cache_deleted_input_tokens（microCompact.ts:372-383，非恒 0）")
    void cachedPath_baselineReadsRealCacheDeletedTokensFromLastAssistant() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        OffsetDateTime now = OffsetDateTime.now();
        List<ChatMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            messages.add(assistantMsg("asst-" + i, "t" + i, "Read", now));
            messages.add(toolResultMsg("tool-" + i, "t" + i, "result content", now));
        }
        // 最后一条 assistant 携带上一次 API 响应的累计 cache_deleted_input_tokens（usage 通道，
        // provider 提取后经 ChatMessageDto.usage() 到达微压缩入口）
        ChatMessageDto lastAsst = messages.get(messages.size() - 2);
        messages.set(messages.size() - 2,
            lastAsst.withUsage(new AgentUsage(100L, 50L, 30L, 20L,
                null, null, null, "", List.of(), "standard", 500L)));

        new MicroCompactor().microcompactMessages(messages, "repl_main_thread");

        MicroCompactResult.PendingCacheEdits edits = MicroCompactor.consumePendingCacheEdits();
        assertThat(edits).as("active=13 > triggerThreshold=10 → 删除触发，compactionInfo.pendingCacheEdits 入队")
            .isNotNull();
        assertThat(edits.baselineCacheDeletedTokens())
            .as("baseline = 最后 assistant usage.cache_deleted_input_tokens（microCompact.ts:374，真实值非恒 0）")
            .isEqualTo(500L);
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-01 · 生产值域用例（V2-M1：生产 LlmAgentLoop 传 querySource().name() 大写枚举名）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("生产值域: 传 REPL_MAIN_THREAD 大写枚举名 → time-based 触发（canonical 归一，V2-M1）")
    void productionValue_uppercaseEnumName_timeBasedFires() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        // 生产主循环传 params.querySource().name() = "REPL_MAIN_THREAD"（大写枚举名）
        MicroCompactResult result = mc.microcompactMessages(messages, "REPL_MAIN_THREAD");

        assertThat(clearedCount(result.messages()))
            .as("生产大写 name() 必须触发 time-based 清除（canonical 归一消费）").isEqualTo(2);
    }

    @Test
    @DisplayName("生产值域: 传 SDK 大写 → isMainThreadSource 归一仍为 main-thread（cached 门控进入）")
    void productionValue_uppercaseSdk_isMainThreadSource() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        List<ChatMessageDto> messages = buildMessagesWithTools();
        MicroCompactor mc = new MicroCompactor();

        MicroCompactResult result = mc.microcompactMessages(messages, "SDK");

        assertThat(result.messages()).as("SDK 大写归一后视为 main-thread → cached 路径（OD-01 无编辑产出）返回原列表").isSameAs(messages);
    }

    @Test
    @DisplayName("生产值域: REPL_MAIN_THREAD 大写触发后 notifyCacheDeletion 以原值通知且 gatedBy 门控生效")
    void productionValue_uppercase_notifyCacheDeletion_gated() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);
        AtomicReference<String> notified = new AtomicReference<>("__unset__");
        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        mc.setNotifyCacheDeletion((qs, aid) -> notified.set(qs));

        MicroCompactResult result = mc.microcompactMessages(messages, "REPL_MAIN_THREAD");

        assertThat(clearedCount(result.messages())).isEqualTo(2);
        assertThat(notified.get()).as("notifyCacheDeletion 必须以传入 querySource 原值通知（CC microCompact.ts:526 透传）")
            .isEqualTo("REPL_MAIN_THREAD");
    }

    @Test
    @DisplayName("canonical 归一幂等: 小写 repl_main_thread 与非 main-thread 大写 COMPACT 语义保持")
    void canonicalize_idempotent_andNonMainThread() {
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);
        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));

        // 小写（测试既有值域）仍触发
        MicroCompactResult lower = mc.microcompactMessages(messages, "repl_main_thread");
        assertThat(clearedCount(lower.messages())).as("小写 repl_main_thread 保持触发").isEqualTo(2);

        // 大写 COMPACT 仍不触发（非 main-thread 语义保持，microCompact.ts:429-433）
        MicroCompactResult compact = mc.microcompactMessages(messages, "COMPACT");
        assertThat(clearedCount(compact.messages())).as("COMPACT 大写归一 'compact' 非 main-thread → 不触发").isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // OPD-CM5-A-10 · cachedMCState 会话级隔离（消除多会话并发污染）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-CM5-A-10 会话级隔离：cached-MC 状态按会话键（MDC sessionId）隔离，会话 A/B 互不污染")
    void cachedMCState_sessionIsolation() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        try {
            // 会话 A：注册 13 个工具（t0..t12）→ active=13 > threshold=10 → 删除最旧 8 个（保留最近 5 个）
            List<ChatMessageDto> chainA = buildToolChain(13, "t");
            RequestContext.setSession("sess-A");
            new MicroCompactor().microcompactMessages(chainA, "repl_main_thread");
            MicroCompactResult.PendingCacheEdits editsA = MicroCompactor.consumePendingCacheEdits();
            assertThat(editsA).as("会话 A active=13>10 → 删除触发，compactionInfo.pendingCacheEdits 入队").isNotNull();
            assertThat(editsA.deletedToolIds())
                .as("会话 A 删除最旧 8 个 t0..t7（slice(0, 13-5)，CC cachedMicrocompact.ts:87-94）")
                .containsExactly("t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7");

            // 会话 B：全新桶，未受 A 污染——A 已写入的 pendingCacheEdits / cache_edits 块 / pinnedEdits 均不可见
            RequestContext.setSession("sess-B");
            assertThat(MicroCompactor.consumePendingCacheEdits())
                .as("会话 B 桶初始为空，不消费到 A 的 pendingCacheEdits").isNull();
            assertThat(MicroCompactor.consumePendingCacheEditsBlock())
                .as("会话 B 桶初始为空，不消费到 A 的 cache_edits 块").isNull();
            assertThat(MicroCompactor.getPinnedCacheEdits())
                .as("会话 B 桶初始为空，无 A 的 pinnedEdits").isEmpty();

            // 会话 B：注册自己的 13 个工具（u0..u12）→ 触发自己的删除（u0..u7），与 A 完全隔离
            List<ChatMessageDto> chainB = buildToolChain(13, "u");
            new MicroCompactor().microcompactMessages(chainB, "repl_main_thread");
            MicroCompactResult.PendingCacheEdits editsB = MicroCompactor.consumePendingCacheEdits();
            assertThat(editsB).as("会话 B active=13>10 → 删除触发").isNotNull();
            assertThat(editsB.deletedToolIds())
                .as("会话 B 删除的是自己的 u0..u7，不含 A 的 t 前缀（状态机完全隔离）")
                .containsExactly("u0", "u1", "u2", "u3", "u4", "u5", "u6", "u7");

            // 回到会话 A：A 的 pendingCacheEdits 已被自身 consume 清空，且不受 B 影响
            RequestContext.setSession("sess-A");
            assertThat(MicroCompactor.consumePendingCacheEdits())
                .as("回到 A：A 的 pendingCacheEdits 已消费清空，B 未污染 A").isNull();

            // 主循环模型同样按会话隔离（microCompact.ts:278 门控 model 谓词入参）
            MicroCompactor.setMainLoopModel("claude-opus-4-20250514");
            RequestContext.setSession("sess-B");
            assertThat(MicroCompactor.getMainLoopModel())
                .as("会话 B 的 mainLoopModel 不受 A 注入影响（仍为 null）").isNull();
        } finally {
            RequestContext.clear();
            // 清理测试会话桶，防跨用例内存积累
            MicroCompactor.removeSessionState("sess-A");
            MicroCompactor.removeSessionState("sess-B");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // MG-6 A2-4 · removeSessionState 会话结束接线（/clear、会话删除）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MG-6 A2-4 removeSessionState：会话结束钩子（/clear、会话删除）移除桶 → 下轮懒建新桶，旧态不残留（防 SESSION_STATES 内存累积）")
    void removeSessionState_releasesSessionBucketOnSessionEnd() {
        RequestContext.setSession("sess-MG6");
        try {
            // 会话 A 注入主循环模型（桶内 mainLoopModel 持久化，模拟 cached-MC 会话态已建立）
            MicroCompactor.setMainLoopModel("claude-opus-4-20250514");
            assertThat(MicroCompactor.getMainLoopModel())
                .as("会话态建立后 mainLoopModel 可读（桶已存在）").isEqualTo("claude-opus-4-20250514");

            // 会话结束钩子（/clear、会话删除）：removeSessionState 移除整桶 → SESSION_STATES 不累积
            //   （决策登记 6 A2-4：CC 进程随会话结束退出无泄漏；Java 多会话常驻 JVM，外层在会话结束
            //   时调 removeSessionState 释放桶内存；null/未知会话 no-op）
            MicroCompactor.removeSessionState("sess-MG6");

            // 下轮 currentSessionState() computeIfAbsent 懒建新桶 → 旧桶状态（mainLoopModel）不残留
            //   （语义等价 CC reset 后新 turn）
            assertThat(MicroCompactor.getMainLoopModel())
                .as("removeSessionState 后会话桶已移除，下轮懒建新桶，旧 mainLoopModel 不残留")
                .isNull();
        } finally {
            RequestContext.clear();
            MicroCompactor.removeSessionState("sess-MG6");
        }
    }
}
