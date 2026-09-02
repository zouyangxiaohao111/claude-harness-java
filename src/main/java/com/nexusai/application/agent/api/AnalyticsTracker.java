package com.nexusai.application.agent.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Analytics Tracker · 对齐 CC services/analytics/index.ts (9 文件 ~800 行).
 *
 * <p>FIX-SVC-2: 简化版 analytics (Datadog/1P/采样/灰度/killswitch).
 *
 * <p>[IMP-T G15] 遥测统一通道：新增 {@link #logEvent(String, Map)} 对齐 CC
 * {@code logEvent(eventName, metadata)}（index.ts:133-144）—— metadata 值域
 * 仅允许 Boolean/Number（CC {@code LogEventMetadata = {[key]: boolean|number|undefined}}，
 * index.ts:61）；String 值必须经 {@link #verified(String)} 显式证明非 code/filepath
 * （Java 等价 CC {@code AnalyticsMetadata_I_VERIFIED_THIS_IS_NOT_CODE_OR_FILEPATHS}，
 * index.ts:19），或走 {@code _PROTO_*} 键（PII-tagged，index.ts:33 / sink.ts strip 语义）。
 * 未验证的裸 String 值 → warn + 拒绝该键（对齐 CC 编译期类型标记的运行时等价）。
 *
 * <p>事件计数：{@link #countsByEventName()} 提供 eventName → count 观测（新统一通道）；
 * 既有 {@link #counts()} 保留（枚举维度；track 存根已删，本方法无生产调用方）。
 *
 * <p>[IMP-T G15] 新增 {@link #trackGitOperations(String, int, String)}：对齐 CC
 * {@code shared/gitOperationTracking.ts:189-277 trackGitOperations}（Bash/PowerShell 共用），
 * 检测 git commit/push、gh pr 系列、glab mr create、curl POST PR 端点 → 发射
 * {@code tengu_git_operation}。
 *
 * <p>LIMIT: 真实埋点需 Datadog/1P endpoint; 当前是 stub 收集 + 内存存储.
 */
@Component
public class AnalyticsTracker {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsTracker.class);

    public enum EventName {
        TOOL_USE,
        LLM_CALL,
        SESSION_START,
        SESSION_END,
        ERROR,
        // [WF3-04 explainer] CC original: logEvent('tengu_permission_explainer_generated', ...)
        //   (permissionExplainer.ts:209) · 权限解释成功
        PERMISSION_EXPLAINER_GENERATED,
        // [WF3-04 explainer] CC original: logEvent('tengu_permission_explainer_error', ...)
        //   (permissionExplainer.ts:222,240) · 权限解释解析失败/异常
        PERMISSION_EXPLAINER_ERROR,
        // ═══════════════ IMP-G4 (组 11-1) Subagent hard_metrics ═══════════════
        // CC original: logEvent('tengu_agent_tool_selected', {...})
        //   (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:419-428) · 子 agent 选中/发射
        AGENT_TOOL_SELECTED,
        // CC original: logEvent('tengu_agent_tool_completed', {...})
        //   (Open-ClaudeCode/src/tools/AgentTool/agentToolUtils.ts:322-346) · 子 agent 正常完成
        AGENT_TOOL_COMPLETED,
        // CC original: logEvent('tengu_agent_tool_terminated', {...})
        //   (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:997/1132/1209 + agentToolUtils.ts:646)
        //   · 子 agent 中止/被杀/异常终态
        AGENT_TOOL_TERMINATED,
        // [IMP-F2-3] AGENT_MEMORY_LOADED 已删除（DC-V5-09）：tengu_agent_memory_loaded 改走真实
        //   Telemetry 通道（SubagentExecutor.emitAgentMemoryLoaded，recordEvent+logOTelEvent），
        //   不再经 AnalyticsTracker stub 计数；CC 属性仅 scope+source（无 agent_type）。
        // CC original: logEvent('tengu_cache_eviction_hint', {scope:'subagent_end', last_request_id})
        //   (Open-ClaudeCode/src/tools/AgentTool/agentToolUtils.ts:349-357) · 子 agent cache 链失效提示
        CACHE_EVICTION_HINT,
        // CC original: logEvent('tengu_workflow_done', {status:0|1|2, runId})
        //   (Open-ClaudeCode/src/workflow/ports.ts:83-87) · workflow run 终态 telemetry
        //   （completed→0 / failed→1 / killed→2；runId 经 AnalyticsMetadata 品牌 cast）
        WORKFLOW_DONE
    }

    private final AtomicLong eventCount = new AtomicLong(0);
    private final Map<EventName, AtomicLong> counts = new ConcurrentHashMap<>();
    /** 统一通道（logEvent 字符串事件名）计数 · eventName → count。 */
    private final Map<String, AtomicLong> countsByEventName = new ConcurrentHashMap<>();
    private volatile boolean killswitchActive = false;

    public void setKillswitch(boolean active) {
        killswitchActive = active;
    }

    public boolean isKillswitchActive() {
        return killswitchActive;
    }

    public long totalEvents() {
        return eventCount.get();
    }

    public Map<EventName, Long> counts() {
        return counts.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-T G15] 遥测统一通道 · 对齐 CC services/analytics/index.ts logEvent
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 已声明安全的 String 包装 · Java 等价 CC {@code AnalyticsMetadata_I_VERIFIED_THIS_IS_NOT_CODE_OR_FILEPATHS}
     * （index.ts:19）。
     *
     * <p>CC 用编译期类型标记强制调用方显式声明"不是 code/filepath"；Java 无编译期等价，
     * 故用包装类型运行时等价：{@link #logEvent} 只放行 {@link VerifiedString} 包装的 String，
     * 裸 String → warn + 拒绝。调用方仅应在确信值不含代码片段/文件路径/命令文本时包装。
     */
    public record VerifiedString(String value) {
        public VerifiedString {
            if (value == null) {
                throw new IllegalArgumentException("VerifiedString value must not be null");
            }
        }
    }

    /**
     * 显式声明非 code/filepath 的 String 值 · 对齐 CC VERIFIED 标记用法。
     *
     * @param value 已确认不含代码片段/文件路径/命令文本的字符串（如枚举名、可执行命令名）
     * @return 包装后的值（供 {@link #logEvent} metadata）
     */
    public static VerifiedString verified(String value) {
        return new VerifiedString(value);
    }

    /**
     * logEvent · 对齐 CC {@code logEvent(eventName, metadata)}（index.ts:133-144）。
     *
     * <p>metadata 值域对齐 CC {@code LogEventMetadata}（index.ts:61 boolean|number|undefined）：
     * <ul>
     *   <li>Boolean / Number → 放行</li>
     *   <li>{@code _PROTO_*} 键 → PII-tagged 列（index.ts:33），放行任意值（1P 专属，stub 全收）</li>
     *   <li>{@link VerifiedString} → 放行（调用方已声明非 code/filepath）</li>
     *   <li>null → CC undefined 等价，跳过该键</li>
     *   <li>裸 String / 其它类型 → warn + 拒绝（对齐 CC 编译期类型标记的运行时防线）</li>
     * </ul>
     *
     * <p>killswitch 激活时 no-op（对齐 CC sinkKillswitch 短路语义）。
     *
     * @param eventName 事件名（CC 原样 snake_case，如 {@code tengu_bash_tool_command_executed}）
     * @param metadata  受限 metadata（值域见上；null 视为空）
     */
    public void logEvent(String eventName, Map<String, Object> metadata) {
        if (killswitchActive) return;
        if (eventName == null || eventName.isBlank()) {
            log.warn("[AnalyticsTracker] logEvent 拒绝空 eventName（CC index.ts:133 eventName 必填）");
            return;
        }
        if (metadata != null) {
            for (Map.Entry<String, Object> e : metadata.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                boolean allowed = key.startsWith("_PROTO_")            // PII-tagged 列（index.ts:33）
                    || value instanceof Boolean
                    || value instanceof Number
                    || value instanceof VerifiedString
                    || value == null;                                  // CC undefined 等价
                if (!allowed) {
                    log.warn("[AnalyticsTracker] logEvent 拒绝未验证 String/非法类型 metadata 键={} 值类型={}"
                        + "（对齐 CC AnalyticsMetadata_I_VERIFIED_THIS_IS_NOT_CODE_OR_FILEPATHS："
                        + "非 code/filepath 的 String 必须 verified() 包装或走 _PROTO_* 键）",
                        key, value.getClass().getSimpleName());
                }
            }
        }
        eventCount.incrementAndGet();
        countsByEventName.computeIfAbsent(eventName, k -> new AtomicLong(0)).incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[AnalyticsTracker] logEvent 事件={} metadataKeys={}（CC index.ts:133-144）",
                eventName, metadata == null ? Map.of() : metadata.keySet());
        }
    }

    /**
     * 统一通道事件计数观测 · eventName → count（覆盖所有经 {@link #logEvent} 发射的事件）。
     *
     * @return 快照（并发安全，非实时引用）
     */
    public Map<String, Long> countsByEventName() {
        return countsByEventName.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-T G15] trackGitOperations · 对齐 CC shared/gitOperationTracking.ts:189-277
    // ════════════════════════════════════════════════════════════════════════

    // CC gitCmdRe(subcmd)（gitOperationTracking.ts:23-27）：`git <subcmd>` 且容忍
    // `-c key=val` / `-C path` / `--git-dir=path` 等全局选项。
    private static Pattern gitCmdRe(String subcmd, String suffix) {
        return Pattern.compile(
            "\\bgit(?:\\s+-[cC]\\s+\\S+|\\s+--\\S+=\\S+)*\\s+" + subcmd + "\\b" + suffix);
    }

    private static final Pattern GIT_COMMIT_RE = gitCmdRe("commit", "");
    private static final Pattern GIT_PUSH_RE = gitCmdRe("push", "");
    private static final Pattern GIT_AMEND_RE = Pattern.compile("\\b--amend\\b");

    /** CC GH_PR_ACTIONS（gitOperationTracking.ts:45-52）。 */
    private record PrAction(Pattern re, String op) {
        PrAction(String regex, String op) {
            this(Pattern.compile(regex), op);
        }
    }

    private static final PrAction[] GH_PR_ACTIONS = {
        new PrAction("\\bgh\\s+pr\\s+create\\b", "pr_create"),
        new PrAction("\\bgh\\s+pr\\s+edit\\b", "pr_edit"),
        new PrAction("\\bgh\\s+pr\\s+merge\\b", "pr_merge"),
        new PrAction("\\bgh\\s+pr\\s+comment\\b", "pr_comment"),
        new PrAction("\\bgh\\s+pr\\s+close\\b", "pr_close"),
        new PrAction("\\bgh\\s+pr\\s+ready\\b", "pr_ready"),
    };

    private static final Pattern GLAB_MR_CREATE_RE = Pattern.compile("\\bglab\\s+mr\\s+create\\b");
    // CC :260-264 isCurlPost —— curl && (显式 -X POST / --request POST / 隐式 -d 数据 POST) 三条件 OR
    private static final Pattern CURL_RE = Pattern.compile("\\bcurl\\b");
    private static final Pattern CURL_POST_FLAG_RE = Pattern.compile("(-X\\s*POST\\b|--request\\s*=?\\s*POST\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURL_D_FLAG_RE = Pattern.compile("\\s-d\\s");
    // CC :267-269 isPrEndpoint —— PR 端点但非子资源（/pulls/123/comments 排除）
    private static final Pattern PR_ENDPOINT_RE = Pattern.compile(
        "https?://[^\\s'\"]*/(pulls|pull-requests|merge[-_]requests)(?!/\\d)", Pattern.CASE_INSENSITIVE);

    /**
     * git 操作遥测 · 对齐 CC {@code trackGitOperations(command, exitCode, stdout)}
     * （shared/gitOperationTracking.ts:189-277）。
     *
     * <p>仅在 exitCode==0 时检测（CC :194-197 success 门）；命中 git commit/push、
     * gh pr 系列、glab mr create、curl POST PR 端点 → 发射 {@code tengu_git_operation}，
     * metadata {@code operation} 为经 {@link #verified} 包装的枚举串（CC
     * {@code AnalyticsMetadata_I_VERIFIED...} 标记，非 code/filepath）。
     * git commit --amend 额外发射一次 {@code commit_amend}（CC :204-209）。
     *
     * @param command  原始命令文本（CC input.command）
     * @param exitCode 进程退出码（CC result.code）
     * @param stdout   stdout（CC result.stdout；当前仅用于 future PR URL 关联，见 CC :228-248）
     */
    public void trackGitOperations(String command, int exitCode, String stdout) {
        if (exitCode != 0) {
            return; // CC gitOperationTracking.ts:194-197 success 门
        }
        if (command == null || command.isBlank()) {
            return;
        }
        String cmd = command.trim();
        if (GIT_COMMIT_RE.matcher(cmd).find()) {
            logEvent("tengu_git_operation", Map.<String, Object>of(
                "operation", verified("commit")));
            if (GIT_AMEND_RE.matcher(cmd).find()) {
                logEvent("tengu_git_operation", Map.<String, Object>of(
                    "operation", verified("commit_amend")));
            }
        }
        if (GIT_PUSH_RE.matcher(cmd).find()) {
            logEvent("tengu_git_operation", Map.<String, Object>of(
                "operation", verified("push")));
        }
        for (PrAction a : GH_PR_ACTIONS) {
            if (a.re().matcher(cmd).find()) {
                logEvent("tengu_git_operation", Map.<String, Object>of(
                    "operation", verified(a.op())));
                break;
            }
        }
        if (GLAB_MR_CREATE_RE.matcher(cmd).find()) {
            logEvent("tengu_git_operation", Map.<String, Object>of(
                "operation", verified("pr_create")));
        }
        // curl POST 到 PR 端点（CC :257-276）：curl && (显式 -X POST / --request POST / 隐式 -d) && PR 端点
        boolean isCurlPost = CURL_RE.matcher(cmd).find()
            && (CURL_POST_FLAG_RE.matcher(cmd).find() || CURL_D_FLAG_RE.matcher(cmd).find());
        if (isCurlPost && PR_ENDPOINT_RE.matcher(cmd).find()) {
            logEvent("tengu_git_operation", Map.<String, Object>of(
                "operation", verified("pr_create")));
        }
    }
}
