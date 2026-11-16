package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.api.UltrareviewQuota;
import com.nexusai.application.agent.command.UltrareviewVisibilityChecker;
import com.nexusai.application.agent.policy.TipHistoryTracker;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Ultrareview post-commit 提示 hook · 对齐 claude.exe v2.1.325
 * {@code registerUltrareviewPostCommitHook}（注册 off≈352,170,157，回调 off≈366,376,5xx）。
 *
 * <p><b>L1 语义</b>：这是 CC 唯一与 code review 相关的内置 hook。它是 {@code PostToolUse}
 * 的内建 callback（matcher = {@code Bash|PowerShell}，timeout 1，{@code internal:true}），
 * 在一次成功的 {@code git commit} 之后给用户一条 {@code systemMessage} 提示，建议先跑
 * {@code /ultrareview} 再 push。<b>它只提示，绝不执行审查</b>——CC 没有 Review 类事件，
 * 也没有"执行完自动审查"的内建 hook。
 *
 * <p><b>L2 CC 触发条件（逐条对齐）</b>：
 * <ol>
 *   <li>事件必须是 PostToolUse；非交互会话或子代理（{@code agent_id} 已定义）直接跳过；</li>
 *   <li>{@code tool_input.command} 匹配
 *       {@code \bgit\b(?:\s+(?:-C\s+\S+|-c\s+\S+=\S+|--\S+))*\s+commit(?:\s|$)}；</li>
 *   <li>工具输出（CC 为 stdout+stderr；本仓 Bash/PowerShell 是合并单流）匹配任一 commit 标记：
 *       {@code [branch abc1234] }、{@code create mode}、{@code N files changed}、
 *       {@code N insertions(+)}、{@code N deletions(-)}；</li>
 *   <li>tip 门控：awareness 特性开 + {@code allow_remote_sessions}、从未跑过 ultrareview、
 *       lifetime 显示次数 &lt; 5、本 startup 未显示过；显示后记遥测
 *       {@code tengu_ultrareview_awareness_shown{surface:"ultrareview-post-commit"}}；</li>
 *   <li>输出 {@code systemMessage}：提示原文，quota&gt;0 时追加免费次数。</li>
 * </ol>
 *
 * <p><b>L3 本仓适配与已登记差异</b>：
 * <ul>
 *   <li>{@code allow_remote_sessions} 门控：本仓无该远端会话开关，等价条件由
 *       {@link #commandEnabled} 承担（= {@code /ultrareview} 命令可见性，同一 GrowthBook
 *       特性 {@code tengu_review_bughunter_config.enabled}，env/system 属性可开）。</li>
 *   <li>{@code hasRunUltrareview}：CC 是跨进程持久状态（{@code pe().hasRunUltrareview}）。
 *       本仓暂无等价持久位，默认 supplier 恒 {@code false} —— 已登记缺口，<b>不假实现</b>；
 *       调用方接入真实状态后提示会自动收敛。</li>
 *   <li>lifetime 计数与"本 startup 已显示"：CC 跨进程持久；本仓用类级静态计数/历史（进程内），
 *       重启进程后重新计数。差异已在 {@link #LIFETIME_SHOWS} 与 {@link #TIP_HISTORY} 标注。</li>
 *   <li>返回通道：CC 用 hook 的 {@code systemMessage}；本仓映射为
 *       {@link GenericHook.HookResult#systemMessages()}，由
 *       {@code StreamingToolExecutor} 折叠成 {@code hook_system_message} 附件（既有消费链）。</li>
 * </ul>
 *
 * <p><b>local-only</b>：本类只读工具输入/结果，只发一条本地提示；除遥测事件名外不向外发送数据。
 */
public final class UltrareviewPostCommitHook {

    private static final Logger log = LoggerFactory.getLogger(UltrareviewPostCommitHook.class);

    /** CC 提示原文（off≈330,098,829，逐字）。 */
    public static final String TIP =
        "Tip: Run /ultrareview before you push to catch bugs with a cloud-based multi-agent review";

    /** CC 遥测事件名（surface=ultrareview-post-commit）。 */
    public static final String TELEMETRY_EVENT = "tengu_ultrareview_awareness_shown";

    /** CC {@code b=5}：同一 tip lifetime 最多显示 5 次（off≈330,098,777）。 */
    public static final int LIFETIME_SHOWS = 5;

    /** CC {@code ROs}（off≈317,991,222）：git commit 命令判定。 */
    static final Pattern COMMIT_COMMAND = Pattern.compile(
        "\\bgit\\b(?:\\s+(?:-C\\s+\\S+|-c\\s+\\S+=\\S+|--\\S+))*\\s+commit(?:\\s|$)");

    /**
     * CC {@code yXi}（off≈317,991,318）：commit 输出标记。
     * 顺序与原实现一致；命中任一即为一次 commit 输出。
     */
    static final Pattern[] COMMIT_OUTPUT_MARKERS = {
        Pattern.compile("\\[\\w+[^\\]]*\\]\\s+"),
        Pattern.compile("create mode"),
        Pattern.compile("\\d+ files? changed"),
        Pattern.compile("\\d+ insertions?"),
        Pattern.compile("\\d+ deletions?"),
    };

    /** 进程内 lifetime 计数（CC 跨进程；本仓登记差异）。 */
    private static final AtomicInteger LIFETIME_SHOWN = new AtomicInteger();

    /**
     * 进程内 startup 纪元 · 供 {@link TipHistoryTracker} 做"本次启动是否已显示"判定。
     *
     * <p>CC 的 startup 计数跨进程持久；本仓取类加载时刻作为唯一 startup 值 ⇒
     * {@code sessionsSinceLastShown} 恒为 0，等价"本进程内已显示过即不再显示"。
     */
    private static final long STARTUP_EPOCH = System.currentTimeMillis();

    /** 进程内"本次启动是否已显示"历史（CC 跨进程；本仓登记差异）。 */
    private static final TipHistoryTracker TIP_HISTORY = new TipHistoryTracker(() -> STARTUP_EPOCH);

    private final BooleanSupplier commandEnabled;
    private final BooleanSupplier hasRunUltrareview;
    private final Supplier<UltrareviewQuota.UltrareviewQuotaResponse> quotaSupplier;
    private final Telemetry telemetry;

    /**
     * 完整构造器（测试可注入全部门控）。
     *
     * @param commandEnabled    等价 CC awareness 特性 + allow_remote_sessions；false → 永不提示
     * @param hasRunUltrareview 已跑过 ultrareview 则不再提示（CC 跨进程状态，本仓默认 false）
     * @param quotaSupplier     免费次数来源；null 或返回 null → 提示不带次数后缀
     * @param telemetry         遥测；null → 跳过（仅 debug 日志）
     */
    public UltrareviewPostCommitHook(BooleanSupplier commandEnabled, BooleanSupplier hasRunUltrareview,
                                     Supplier<UltrareviewQuota.UltrareviewQuotaResponse> quotaSupplier,
                                     Telemetry telemetry) {
        this.commandEnabled = commandEnabled != null ? commandEnabled : () -> false;
        this.hasRunUltrareview = hasRunUltrareview != null ? hasRunUltrareview : () -> false;
        this.quotaSupplier = quotaSupplier;
        this.telemetry = telemetry;
    }

    /**
     * 生产装配 · 门控取 {@code /ultrareview} 命令同一特性源。
     *
     * <p>与 {@code CommandRegistrationConfig.ultrareviewGate()} 同源：system property
     * {@code nexusai.feature.ultra-review} 优先，env {@code NEXUSAI_FEATURE_ULTRA_REVIEW} 兜底。
     *
     * @param telemetry 遥测（可 null）
     * @return 生产实例
     */
    public static UltrareviewPostCommitHook production(Telemetry telemetry) {
        BooleanSupplier enabled = () -> new UltrareviewVisibilityChecker(
            UltrareviewPostCommitHook::featureConfig).isUltrareviewEnabled();
        // hasRunUltrareview：本仓无持久位 → 恒 false（已登记缺口，不假实现）
        return new UltrareviewPostCommitHook(enabled, () -> false, () -> null, telemetry);
    }

    /** 特性源 · 同 {@code CommandRegistrationConfig.ultrareviewFeature()}（无 GrowthBook 时 env/属性可开）。 */
    private static Map<String, Object> featureConfig() {
        String sysProp = System.getProperty("nexusai.feature.ultra-review");
        if (sysProp != null && !sysProp.isBlank()) {
            return Boolean.parseBoolean(sysProp) ? Map.of("enabled", Boolean.TRUE) : Map.of();
        }
        String envVal = System.getenv("NEXUSAI_FEATURE_ULTRA_REVIEW");
        if (envVal != null && !envVal.isBlank()) {
            return Boolean.parseBoolean(envVal) ? Map.of("enabled", Boolean.TRUE) : Map.of();
        }
        return Map.of();
    }

    /**
     * 注册 Bash / PowerShell 两个 PostToolUse internal hook · 对齐 CC
     * {@code Oz({PostToolUse:[{matcher:"Bash|PowerShell",hooks:[...]}]})}。
     *
     * <p>本仓 {@link HookRegistry} 无 matcher map（{@code SessionFileAccessHooks} 同模式），
     * 逐工具注册 + 回调内 toolName 过滤，语义等价。
     *
     * @param registrar PostToolUse 注册目标（生产传 HookRegistry）
     */
    public void register(SessionFileAccessHooks.PostToolUseRegistrar registrar) {
        if (registrar == null) {
            log.warn("[UltrareviewPostCommitHook] PostToolUseRegistrar 未注入, 跳过注册");
            return;
        }
        for (String tool : List.of(ToolNameConstants.BASH_TOOL_NAME,
                                   ToolNameConstants.POWER_SHELL_TOOL_NAME)) {
            registrar.registerPostToolUseInternal("ultrareviewPostCommit:" + tool, (toolName, input, result, ctx, stopHookActive) -> {
                if (toolName == null || !toolName.equals(tool)) {
                    return GenericHook.HookResult.proceed();
                }
                String tip = maybeTip(toolName, input, result, ctx);
                if (tip == null) {
                    return GenericHook.HookResult.proceed();
                }
                if (log.isInfoEnabled()) {
                    log.info("[UltrareviewPostCommitHook] 检测到 git commit, 提示 /ultrareview: tool={} session={}",
                        toolName, ctx != null ? ctx.sessionId() : null);
                }
                // CC 返回 {systemMessage: tip}；本仓走 HookResult.systemMessages →
                // StreamingToolExecutor hook_system_message 附件（既有折叠链）。
                return new GenericHook.HookResult(false, null, List.of(tip), null,
                    null, null, null, null, null,
                    GenericHook.HookOutcome.SUCCESS, null, null, null, null,
                    null, null, null, null, null);
            });
        }
        log.info("[UltrareviewPostCommitHook] 注册 2 个 PostToolUse matcher hooks: Bash/PowerShell");
    }

    /**
     * 判定本次工具调用是否应产出提示 · 对齐 CC 回调主体。
     *
     * @param toolName 工具名（已过滤为 Bash/PowerShell）
     * @param input    工具输入（{@code command}）
     * @param result   工具结果（合并输出文本）
     * @param ctx      工具上下文（子代理/非交互判定）
     * @return 提示文案；不应提示 → null
     */
    String maybeTip(String toolName, JsonNode input, ToolResult<?> result, ToolUseContext ctx) {
        // 1. 非交互会话 / 子代理不提示（CC: ge() || agent_id !== undefined）
        if (ctx != null && (ctx.isNonInteractiveSession() || ctx.agentId() != null)) {
            return null;
        }
        // 2. tool_input.command 是字符串且是 git commit
        String command = input != null && input.hasNonNull("command")
            ? input.get("command").asText(null) : null;
        if (command == null || !isCommitCommand(command)) {
            return null;
        }
        // 3. 输出带 commit 标记
        String output = result != null && result.data() != null ? String.valueOf(result.data()) : "";
        if (!hasCommitOutput(output)) {
            return null;
        }
        return gateAndRender();
    }

    /** CC {@code ROs}：git commit 判定。 */
    static boolean isCommitCommand(String command) {
        return command != null && COMMIT_COMMAND.matcher(command).find();
    }

    /** CC {@code yXi}：stdout+stderr 的 commit 输出标记判定（本仓为合并单流）。 */
    static boolean hasCommitOutput(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        for (Pattern p : COMMIT_OUTPUT_MARKERS) {
            if (p.matcher(output).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 门控 + 文案渲染 · 对齐 CC {@code CFi}/{@code d()}（off≈330,098,777）。
     *
     * <p>顺序与 CC 一致：awareness 关 → null；已跑过 → null；lifetime ≥5 → null；
     * 本 startup 已显示 → null；通过则记历史 + 遥测并返回文案。
     *
     * @return 提示文案；门控拦截 → null
     */
    synchronized String gateAndRender() {
        if (!commandEnabled.getAsBoolean()) {
            return null;
        }
        if (hasRunUltrareview.getAsBoolean()) {
            return null;
        }
        if (LIFETIME_SHOWN.get() >= LIFETIME_SHOWS) {
            return null;
        }
        // CC: gW(tipId) === 0 → 本 startup 已显示过（本仓为进程内历史）
        if (TIP_HISTORY.sessionsSinceLastShown(TELEMETRY_EVENT) == 0L) {
            return null;
        }
        TIP_HISTORY.recordShown(TELEMETRY_EVENT);
        LIFETIME_SHOWN.incrementAndGet();
        emitTelemetry();
        return TIP + freeReviewsSuffix();
    }

    /** CC {@code Wus} 等价：quota 剩余 >0 时的免费次数后缀；否则空串。 */
    private String freeReviewsSuffix() {
        if (quotaSupplier == null) {
            return "";
        }
        UltrareviewQuota.UltrareviewQuotaResponse quota;
        try {
            quota = quotaSupplier.get();
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("[UltrareviewPostCommitHook] quota 读取失败, 省略次数后缀: {}", e.toString());
            }
            return "";
        }
        if (quota == null || quota.reviewsRemaining() <= 0) {
            return "";
        }
        int n = quota.reviewsRemaining();
        return " — " + n + " free " + (n == 1 ? "review" : "reviews") + " left";
    }

    /** 遥测 · CC {@code tengu_ultrareview_awareness_shown{surface:"ultrareview-post-commit"}}。 */
    private void emitTelemetry() {
        if (telemetry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[UltrareviewPostCommitHook] telemetry 未注入, 跳过 {}", TELEMETRY_EVENT);
            }
            return;
        }
        Map<String, Object> attrs = Map.of("surface", "ultrareview-post-commit");
        telemetry.recordEvent(TELEMETRY_EVENT, attrs);
        telemetry.logOTelEvent(TELEMETRY_EVENT, attrs);
    }

    /** 测试用 · 重置进程内状态（lifetime + 本次 startup 已显示）。 */
    static void resetProcessStateForTest() {
        LIFETIME_SHOWN.set(0);
        TIP_HISTORY.clear();
    }

    /** 测试用 · 只清"本次 startup 已显示"历史，保留 lifetime 计数（模拟新一次启动）。 */
    static void clearStartupHistoryForTest() {
        TIP_HISTORY.clear();
    }

    /** 测试用 · 当前 lifetime 计数。 */
    static int lifetimeShown() {
        return LIFETIME_SHOWN.get();
    }
}
