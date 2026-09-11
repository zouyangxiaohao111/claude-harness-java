package com.nexusai.application.agent.compact;

/**
 * 压缩常量 · 对齐 CC autoCompact.ts + compact.ts + microCompact.ts
 *
 * <p>所有常量值均来自 CC 源码，不可随意修改。
 *
 * <h2>CC 对齐对照</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC 源码</th><th>行号</th></tr>
 *   <tr><td>AUTOCOMPACT_BUFFER_TOKENS</td><td>autoCompact.ts:62</td><td>13_000</td></tr>
 *   <tr><td>WARNING_THRESHOLD_BUFFER_TOKENS</td><td>autoCompact.ts:63</td><td>20_000</td></tr>
 *   <tr><td>ERROR_THRESHOLD_BUFFER_TOKENS</td><td>autoCompact.ts:64</td><td>20_000</td></tr>
 *   <tr><td>MANUAL_COMPACT_BUFFER_TOKENS</td><td>autoCompact.ts:65</td><td>3_000</td></tr>
 *   <tr><td>MAX_OUTPUT_TOKENS_FOR_SUMMARY</td><td>autoCompact.ts:30</td><td>20_000</td></tr>
 *   <tr><td>MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES</td><td>autoCompact.ts:70</td><td>3</td></tr>
 *   <tr><td>MAX_PTL_RETRIES</td><td>compact.ts</td><td>3</td></tr>
 *   <tr><td>POST_COMPACT_MAX_FILES_TO_RESTORE</td><td>compact.ts:122</td><td>5</td></tr>
 *   <tr><td>POST_COMPACT_TOKEN_BUDGET</td><td>compact.ts:123</td><td>50_000</td></tr>
 *   <tr><td>POST_COMPACT_MAX_TOKENS_PER_FILE</td><td>compact.ts:124</td><td>5_000</td></tr>
 *   <tr><td>POST_COMPACT_MAX_TOKENS_PER_SKILL</td><td>compact.ts:129</td><td>5_000</td></tr>
 *   <tr><td>POST_COMPACT_SKILLS_TOKEN_BUDGET</td><td>compact.ts:130</td><td>25_000</td></tr>
 *   <tr><td>TIME_BASED_MC_CLEARED_MESSAGE</td><td>microCompact.ts:36</td><td>"[Old tool result content cleared]"</td></tr>
 * </table>
 *
 * <p><b>Snip 常量已迁出（2026-08-18）</b>: 旧 SNIP_HEAD_KEEP/SNIP_TAIL_KEEP/SNIP_PLACEHOLDER 为 Java 独有
 * 推测（head3+tail47），与 CC 真源 snipCompact.ts 不符，已删除。snip 模块常量现内聚于
 * {@link SnipCompactor}（CHARS_PER_TOKEN / SNIP_NUDGE_THRESHOLD / SNIP_NUDGE_TEXT /
 * SUBTYPE_SNIP_BOUNDARY / SUBTYPE_SNIP_MARKER，均标注 CC original 行号）。
 */
public final class CompactConstants {

    private CompactConstants() {
        // 工具类不可实例化
    }

    // ════════════════════════════════════════════════════════════════════════
    // 窗口常量 · 对齐 utils/context.ts + autoCompact.ts:30
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC 的默认模型上下文窗口 · CC original: {@code MODEL_CONTEXT_WINDOW_DEFAULT}
     * (Open-ClaudeCode/src/utils/context.ts:9) = 200_000。
     *
     * <p><b>⚠ 职责收窄（2026-09-11）——本常量现在只此一职</b>：
     * 「1M 上下文被禁用（{@code CLAUDE_CODE_DISABLE_1M_CONTEXT}）时的收窄目标」
     * ——{@code is1mContextDisabled() && resolved > 200_000 → 200_000}
     * （CC original: context.ts:76-82；Java 见
     * {@link CompactThresholdSystem#getContextWindowForModel(String)}）。
     * <b>不再是</b>「窗口未配置 / 查不到时的回落值」——回落值见
     * {@link #CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}。
     *
     * <p>两个值（200_000 / 1_048_576）语义互斥、不可互换：前者是 CC 对 Claude 系模型窗口的
     * 兜底 + HIPAA 收窄目标，后者是本产品「用户没配窗口」的默认值。
     */
    public static final int MODEL_CONTEXT_WINDOW_DEFAULT = 200_000;

    /** 1M 上下文窗口（对齐 CC context.ts:70 has1mContext → 1_000_000） */
    public static final int CONTEXT_1M_WINDOW = 1_000_000;

    /**
     * 本产品「模型上下文窗口未配置 / 查不到」时的默认窗口 = 1_048_576（1M）。
     *
     * <p><b>WHY 1_048_576 而非 CC 的 {@link #MODEL_CONTEXT_WINDOW_DEFAULT}(200_000)</b>：
     * 本产品的模型窗口由用户配置（DB {@code models.max_context_tokens}），前端契约明确承诺
     * 「留空 = 1M」（{@code front/src/components/ui/ModelFormModal.tsx:88}
     * 「模型上下文窗口 · 1M=1048576 · 留空=1M」；写侧 {@code ModelService} 建模型默认亦为
     * 1_048_576）。当 DB 行 {@code max_context_tokens} 为 NULL（历史遗留 / 种子数据）或模型
     * 查不到时，必须按 1M 计，否则会静默退化成 20 万 ——
     * <b>实测事故（2026-09-11）</b>：{@code models.deepseek-flash.max_context_tokens=NULL}
     * → 窗口回落 200_000 → autoCompact 阈值 179_000 → 会话在 92.6 万 tokens 就被压缩
     * （按 1M 应 ~98.7 万才压）→ 「该不压时压了」。
     *
     * <p><b>与 {@link #CONTEXT_1M_WINDOW} 的区别</b>：后者 = 1_000_000，是 CC
     * {@code [1m]} 后缀「显式 opt-in」的窗口值（context.ts:70-72），语义不同、数值也不同
     * （1_000_000 vs 1_048_576），禁止互相替代。
     *
     * <p><b>唯一权威</b>：本常量是「未配置窗口」默认值的<b>单源</b>，消费点 =
     * {@link ContextUsageCalculator}（展示口径）/
     * {@link CompactThresholdSystem}（阈值口径）/
     * {@code AgentLoopContextFactory.resolveModelContextWindow}（DB 解析回落）/
     * {@code MessageService.resolveContextWindowForModel}（重拉补算回落）。
     * {@code ChatService.publishCompleteEvent} 亦为该值（本批未改，见报告）。
     */
    public static final int CONTEXT_WINDOW_UNCONFIGURED_DEFAULT = 1_048_576;

    /**
     * 模型上下文窗口「能力门」下限 · CC original: {@code cap.max_input_tokens >= 100_000}
     * (Open-ClaudeCode/src/utils/context.ts:75)。
     *
     * <p><b>⚠ 2026-09-11 起不再被应用（保留为 CC 对照物，不是接线）</b>：CC 该门作用于
     * {@code getModelCapability(model)} 的<b>静态能力表</b>（模型内置硬上限）；NexusAI 没有能力表，
     * 解析器返回的是<b>用户显式配置</b>（DB {@code models.max_context_tokens}），对应 CC 的
     * {@code CLAUDE_CODE_MAX_CONTEXT_TOKENS} 覆盖分支（context.ts:52-60）——该分支无能力门。
     * 套用此门会导致「用户配 90k 被静默抬成 200k」，故
     * {@link CompactThresholdSystem#getContextWindowForModel(String)} 已删除该判定
     * （详见该方法 javadoc「两处语义变更」）。
     */
    public static final int CONTEXT_WINDOW_CAPABILITY_GATE = 100_000;

    // ════════════════════════════════════════════════════════════════════════
    // Token 阈值 · 对齐 autoCompact.ts:62-65
    // ════════════════════════════════════════════════════════════════════════

    /** 自动压缩缓冲区 token 数（对齐 CC autoCompact.ts:62） */
    public static final int AUTOCOMPACT_BUFFER_TOKENS = 13_000;

    /** 警告阈值缓冲区（对齐 CC autoCompact.ts:63 WARNING_THRESHOLD_BUFFER_TOKENS） */
    public static final int WARNING_THRESHOLD_BUFFER_TOKENS = 20_000;

    /** 错误阈值缓冲区（对齐 CC autoCompact.ts:64 ERROR_THRESHOLD_BUFFER_TOKENS） */
    public static final int ERROR_THRESHOLD_BUFFER_TOKENS = 20_000;

    /** 手动压缩缓冲区（对齐 CC autoCompact.ts:65 MANUAL_COMPACT_BUFFER_TOKENS） */
    public static final int MANUAL_COMPACT_BUFFER_TOKENS = 3_000;

    /** 压缩摘要输出最大 token 数（对齐 CC autoCompact.ts:30 MAX_OUTPUT_TOKENS_FOR_SUMMARY） */
    public static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;

    /**
     * 压缩最大输出 token 数 · CC original: COMPACT_MAX_OUTPUT_TOKENS
     * (Open-ClaudeCode/src/utils/context.ts:12) = 20_000。
     *
     * <p><b>使用点（INV-7）</b>: 流式 fallback {@code maxOutputTokensOverride =
     * Math.min(COMPACT_MAX_OUTPUT_TOKENS, getMaxOutputTokensForModel(model))}
     * （compact.ts:1317-1320），由 {@link com.nexusai.application.agent.compact.fork.RunForkedAgent#maxOutputTokensOverride}
     * 接线（fork 缓存共享路径不设 maxOutputTokens —— 破坏 cache key，compact.ts:1181-1187）。
     *
     * <p><b>与 {@link #MAX_OUTPUT_TOKENS_FOR_SUMMARY} 的区别</b>: 后者对齐 CC
     * autoCompact.ts:30（shouldAutoCompact 阈值 reserved 减法）；本常量对齐 context.ts:12
     * （streamCompactSummary maxOutputTokensOverride 封顶）。
     */
    public static final int COMPACT_MAX_OUTPUT_TOKENS = 20_000;

    // ════════════════════════════════════════════════════════════════════════
    // 压缩重试与熔断 · 对齐 autoCompact.ts:70 + compact.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 连续自动压缩最大失败次数（对齐 CC autoCompact.ts:70）
     *
     * <p>WHY: BQ 2026-03-10 发现 1,279 个会话出现 50+ 次连续失败（最高 3,272 次），
     * 每天浪费约 250K API 调用。设置 3 次熔断防止死循环。
     */
    public static final int MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3;

    /** Prompt-too-long 重试次数上限（对齐 CC compact.ts:227 MAX_PTL_RETRIES） */
    public static final int MAX_PTL_RETRIES = 3;

    /** 压缩流式重试上限（对齐 CC compact.ts:131 MAX_COMPACT_STREAMING_RETRIES） */
    public static final int MAX_COMPACT_STREAMING_RETRIES = 2;

    // ════════════════════════════════════════════════════════════════════════
    // 压缩错误常量 · 对齐 compact.ts:225-226/293-297（D-01 D1/F1/F2 值迁移）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 空输入压缩错误 · CC original: ERROR_MESSAGE_NOT_ENOUGH_MESSAGES
     * (Open-ClaudeCode/src/services/compact/compact.ts:225-226)。
     * <p>值由 D-01（旧桩 D1 常量）迁移至此（旧桩类已删除）。
     */
    public static final String ERROR_MESSAGE_NOT_ENOUGH_MESSAGES =
        "Not enough messages to compact.";

    /**
     * PTL 重试耗尽错误 · CC original: ERROR_MESSAGE_PROMPT_TOO_LONG
     * (compact.ts:293-294)。<p>值由 D-01（旧桩 F1 常量）迁移。
     */
    public static final String ERROR_MESSAGE_PROMPT_TOO_LONG =
        "Conversation too long. Press esc twice to go up a few messages and try again.";

    /**
     * 用户中止错误 · CC original: ERROR_MESSAGE_USER_ABORT
     * (compact.ts:295)。<p>值由 D-01（旧桩 F2 常量）迁移。
     */
    public static final String ERROR_MESSAGE_USER_ABORT = "API Error: Request was aborted.";

    /**
     * 无流式响应错误 · CC original: ERROR_MESSAGE_INCOMPLETE_RESPONSE
     * (compact.ts:296-297)。
     */
    public static final String ERROR_MESSAGE_INCOMPLETE_RESPONSE =
        "Compaction interrupted · This may be due to network issues — please try again.";

    /**
     * PTL 重试合成 user 标记 · CC original: PTL_RETRY_MARKER
     * (compact.ts:228) = '[earlier conversation truncated for compaction retry]'。
     * <p>truncateHeadForPTLRetry 丢弃 group 0 后首条为 assistant 时前置的合成 user 标记
     * （ensureToolResultPairing 已处理由此产生的孤儿 tool_result）。
     */
    public static final String PTL_RETRY_MARKER =
        "[earlier conversation truncated for compaction retry]";

    // ════════════════════════════════════════════════════════════════════════
    // PostCompact 参数 · 对齐 compact.ts:122-130
    // ════════════════════════════════════════════════════════════════════════

    /** 压缩后最大恢复文件数（对齐 CC compact.ts:122） */
    public static final int POST_COMPACT_MAX_FILES_TO_RESTORE = 5;

    /** 压缩后恢复文件的 token 预算（对齐 CC compact.ts:123） */
    public static final int POST_COMPACT_TOKEN_BUDGET = 50_000;

    /** 压缩后单文件最大 token 数（对齐 CC compact.ts:124） */
    public static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;

    /** 压缩后单个技能最大 token 数（对齐 CC compact.ts:129） */
    public static final int POST_COMPACT_MAX_TOKENS_PER_SKILL = 5_000;

    /** 压缩后技能恢复 token 预算（对齐 CC compact.ts:130） */
    public static final int POST_COMPACT_SKILLS_TOKEN_BUDGET = 25_000;

    // ════════════════════════════════════════════════════════════════════════
    // MicroCompact 参数 · 对齐 microCompact.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 时间触发 MC 清除消息文本 · CC original: TIME_BASED_MC_CLEARED_MESSAGE
     * (Open-ClaudeCode/src/services/compact/microCompact.ts:36)。
     *
     * <p><b>D-13/D-14/D-15 已删</b>: 固定系数 200（D-13，tokensSaved 改为真实估算）、
     * 消息数门（D-14，CC 无消息数门）、旧毫秒阈值（D-15，
     * 由 {@link MicroCompactor.TimeBasedMCConfig} gapThresholdMinutes 默认 60 分钟替代）。
     */
    public static final String TIME_BASED_MC_CLEARED_MESSAGE = "[Old tool result content cleared]";
}
