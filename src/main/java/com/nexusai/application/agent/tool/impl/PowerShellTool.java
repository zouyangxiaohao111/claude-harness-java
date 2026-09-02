package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.bash.BashOutputUtils;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskIdGenerator;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.ToolLimits;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.powershell.PowerShellAstService;
import com.nexusai.application.agent.tool.powershell.PowerShellCommandSemantics;
import com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain;
import com.nexusai.application.agent.tool.powershell.PowerShellToolPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * s02 PowerShellTool — 对齐 CC PowerShellTool.tsx（Windows 平台 shim）。
 *
 * <p><b>注册期门控（决策 #65，2026-08-23 用户拍板）</b>：本类带
 * {@code @Conditional(PowerShellToolRegistrationCondition.class)} —— 对齐 CC tools.ts:150-156
 * {@code getPowerShellTool()} 注册期条件 {@code isPowerShellToolEnabled()}（shellToolUtils.ts:17-22）：
 * 非 Windows 恒不注册；Windows 且 USER_TYPE=ant（默认开可 opt-out）或外部 opt-in
 * （CLAUDE_CODE_USE_POWERSHELL_TOOL truthy）才注册。未注册时不进 {@code @Autowired List<Tool>}，
 * 不进 ToolRegistry/schema（与 CC 未启用 → getPowerShellTool() 返回 null → 不进 getAllBaseTools 等价）。
 * {@link #isEnabled()}（运行时门控 = isWindows() && featureFlags.usePowerShellTool()）与注册层
 * 两因子同源，注册层过 → 运行时亦 true，无分裂。消费方 {@code ToolRegistrationConfig.promptShellExecutor}
 * 以 {@code @Autowired(required=false)} 注入（非 Windows 未注册时 null，PromptShellExecutor 容忍 null）。</p>
 *
 * <p><b>execute 运行时管线</b>（OPD-TOOL-34，对齐 CC {@code PowerShellTool.tsx:437-658}）：
 * <ul>
 *   <li>仅 Windows 平台真正执行（CC {@code isPowerShellToolEnabled} 平台分支）</li>
 *   <li>pwsh 探测 pre-flight：缺失 → 返回 code 0 + stderr "PowerShell is not available on this system."（CC :717-728）</li>
 *   <li>显式 {@code run_in_background} → {@link #executeBackground}（TaskIdGenerator + BackgroundTaskRunner + TaskType.LOCAL_BASH，CC :845-857）</li>
 *   <li>流式捕获 stdout/stderr（captureOutput，超 30k spill 临时文件，替代旧 50KB 内存截断）</li>
 *   <li>退出码语义（{@link PowerShellCommandSemantics}）：isError → {@code ToolResult.error}（CC :583 ShellError，非零码不再恒 success）</li>
 *   <li>64MB 落盘（{@link ToolResultStorage#persistOutputFile}）：combined &gt; 30k 且非 error 时落盘（CC :596-617）</li>
 * </ul>
 *
 * <p><b>Java 偏差（登记，非虚报）</b>：streaming onProgress 因 Java Tool.execute 同步阻塞不实现；
 * resetCwd 因 ProcessBuilder.directory 非共享进程 cwd 无法 1:1 复现 CC 全局 cwd 重置；isImage/resizeShellImageOutput
 * PS 图片通道本期范围外。均与 DEC-1 BashTool 一致。
 */
@Component
@Conditional(PowerShellToolRegistrationCondition.class)
public class PowerShellTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PowerShellTool.class);

    public static final String NAME = "PowerShell";

    /**
     * 输出 spill 触发阈值（CC original: {@code getMaxOutputLength()} 默认 30000，
     * Open-ClaudeCode/src/utils/shell/outputLimits.ts:5；对齐 BashTool.SPILL_THRESHOLD）。
     * 完整输出超过该阈值 → spill 临时文件并持久化到 tool-results（CC PowerShellTool.tsx:596-617）。
     */
    private static final long SPILL_THRESHOLD = BashOutputUtils.BASH_MAX_OUTPUT_DEFAULT;

    /** 默认超时（CC prompt.ts getDefaultBashTimeoutMs = 120000）。 */
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /** 最大超时（CC prompt.ts getMaxBashTimeoutMs = 600000）。 */
    private static final long MAX_TIMEOUT_MS = 600_000L;

    /** 后台任务输出目录 · 批次4 #18：static final user.dir 常量 → {@link BackgroundTaskRunner#taskOutputPath} 方法调用
     *  （spawn 时算 sessionId，对齐 CC diskOutput.ts:50-55，PowerShell 与 Bash 同走 LocalShellTask 通道）。
     */

    /** pwsh 路径缓存（对齐 CC parser.ts:1156 getCachedPowerShellPath 缓存语义）。 */
    private static volatile String cachedPwshPath;

    private static final Object PWSH_LOCK = new Object();

    /**
     * 权限主链 · 对齐 CC {@code powershellToolHasPermission}（powershellPermissions.ts:639-1648）。
     * PowerShellTool 从 Tool.java:238 default Allow 改为 override checkPermissions 接入全流程。
     */
    private final PowerShellPermissionChain permissionChain;

    /**
     * [OPD-TOOL-35] 可见性门控 · 对齐 CC {@code shellToolUtils.ts:17-22 isPowerShellToolEnabled()}。
     * isEnabled() = isWindows() && featureFlags.usePowerShellTool()：平台因子 + USER_TYPE/env 三元因子，
     * 消除旧版 isEnabled() 纯平台判断（isWindows()）——缺 USER_TYPE + env 两因子。
     */
    private final FeatureFlags featureFlags;

    /**
     * 命令退出码语义解释器 · 对齐 CC {@code tools/PowerShellTool/commandSemantics.ts}
     * interpretCommandResult 消费（CC PowerShellTool.tsx:555）。PS 版与 Bash CommandSemanticsInterpreter
     * 不同（仅外部 exe 语义：grep/rg/findstr/robocopy），见 {@link PowerShellCommandSemantics}。
     */
    private final PowerShellCommandSemantics commandSemantics = new PowerShellCommandSemantics();

    /** s13 后台任务执行器（@Autowired required=false 向后兼容，对齐 BashTool 模式）。 */
    @Autowired(required = false)
    private BackgroundTaskRunner backgroundTaskRunner;

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent 埋点
     * （PowerShellTool.tsx:504 trackGitOperations + :637-643 tengu_powershell_tool_command_executed）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）；@Autowired(required=false) + setter
     * 注入（对齐 BashTool [IMP-T G15] 同款短路语义）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
        if (log.isDebugEnabled()) {
            log.debug("[PowerShellTool] [IMP-T G15] analyticsTracker 注入={}（CC tengu_powershell_tool_command_executed / tengu_git_operation）",
                analyticsTracker != null);
        }
    }

    /** 生产注入（WF-A 链）· @Autowired 保证多构造器时 Spring 仍用此构造器（FeatureFlags 为 bean）。 */
    @Autowired
    public PowerShellTool(PowerShellPermissionChain permissionChain, FeatureFlags featureFlags) {
        this.permissionChain = permissionChain;
        this.featureFlags = featureFlags;
    }

    /**
     * 测试便捷构造（1 参）· 注入 {@link FeatureFlags#ALL_DISABLED}（usePowerShellTool=false，
     * 使 no-arg 实例在 Windows 上 isEnabled()=false；测试如需断言 isEnabled=true 须走 2 参构造）。
     */
    public PowerShellTool(PowerShellPermissionChain permissionChain) {
        this(permissionChain, FeatureFlags.ALL_DISABLED);
    }

    /**
     * 测试便捷构造（兼容 master 侧 `new PowerShellTool()`）· 自建默认链。
     * 供 RuleQuery/Pipeline 集成测试（RuleQueryTest / CheckLayer1bFallthroughTest 等
     * 直接 new PowerShellTool()）使用；生产走
     * {@link #PowerShellTool(PowerShellPermissionChain, FeatureFlags)}。
     */
    public PowerShellTool() {
        this(new PowerShellPermissionChain(new PowerShellAstService()), FeatureFlags.ALL_DISABLED);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // IMP-DEL1（TR-C1-D-2）：移除 platform_not_supported 文案——CC 非 Windows 不注册工具，
        // isEnabled() 已按平台短路；pwsh 缺失时返回 availability sentinel（CC :717-728）。
        return "Execute a PowerShell command on Windows. Use Bash tool for cross-platform shell.";
    }

    /**
     * 搜索提示 · 对齐 CC {@code PowerShellTool.tsx:274} {@code searchHint: 'execute Windows PowerShell commands'}。
     * 供 ToolSearch 关键词匹配（G9 契约 override 族）。
     */
    @Override
    public String searchHint() {
        return "execute Windows PowerShell commands";
    }

    /**
     * SDK 严格模式标志 · 对齐 CC {@code PowerShellTool.tsx:276} {@code strict: true}（G9）。
     */
    @Override
    public boolean strict() {
        return true;
    }

    /**
     * 用户可见名称 · 对齐 CC {@code PowerShellTool.tsx:326-328} {@code userFacingName() { return 'PowerShell' }}（G9）。
     */
    @Override
    public String userFacingName() {
        return NAME;
    }

    /**
     * 活动描述 · 对齐 CC {@code PowerShellTool.tsx:342-348} {@code getActivityDescription(input)}（G9）：
     * {@code !input?.command → 'Running command'}；否则 {@code 'Running ' + (input.description ?? truncate(command, TOOL_SUMMARY_MAX_LENGTH))}。
     *
     * <p><b>Java 偏差（登记）</b>：truncate 用 {@link #abbreviate}（"..." 后缀）替代 CC 宽度感知 {@code truncate}
     * （U+2026 …，truncate.ts:63-71）——显示摘要非 load-bearing，字符级差异可接受。
     */
    @Override
    public String getActivityDescription(JsonNode input) {
        if (input == null || !input.has("command") || input.get("command").asText("").isBlank()) {
            return "Running command";
        }
        String desc = input.has("description")
            ? input.get("description").asText("")
            : abbreviate(input.get("command").asText(), (int) ToolLimits.TOOL_SUMMARY_MAX_LENGTH);
        return "Running " + desc;
    }

    /**
     * 工具系统提示词 · 对齐 CC {@code PowerShellTool.tsx:282-284} {@code async prompt() { return getPrompt() }}（G10）。
     *
     * <p>G10（决策拍板评级 HIGH）：Java PowerShellTool 原未 override {@link Tool#prompt()}（默认回退
     * description()）→ LLM 缺失 PowerShell 版本特定语法引导 / Start-Sleep 滥用抑制 / run_in_background
     * 用法，模型产出 PS 5.1/UTF-16/Start-Sleep 滥用概率上升。本 override 完整移植 CC prompt.ts getPrompt()
     * 全量文本（{@link PowerShellToolPrompt}），消费点 {@code ToolRegistry.toOpenAiToolsArray}
     * {@code prompt() 非 null 优先于 description()} 注入 LLM 可见工具描述。
     *
     * <p>动态值组装（对齐 CC 真源）：edition=resolvePwshPath() basename 派生（pwsh→core，
     * powershell→desktop，null→unknown，对齐 powershellDetection.ts:87-100）；backgroundTasksDisabled=
     * {@link #backgroundTasksDisabled}（CLAUDE_CODE_DISABLE_BACKGROUND_TASKS 对等开关，BashTool 同款）；
     * timeout/maxOutputLength 常量与 Java 执行层一致。
     */
    @Override
    public String prompt() {
        PowerShellToolPrompt.PromptInputs inputs = new PowerShellToolPrompt.PromptInputs(
            backgroundTasksDisabled,
            PowerShellToolPrompt.PowerShellEdition.fromPwshPath(resolvePwshPath()),
            DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS, SPILL_THRESHOLD);
        String prompt = PowerShellToolPrompt.getPrompt(inputs);
        if (log.isDebugEnabled()) {
            log.debug("[PowerShellTool] prompt(): 生成 CC 对齐 getPrompt 系统提示词, 长度={} (backgroundDisabled={}, edition={})",
                prompt.length(), backgroundTasksDisabled, inputs.edition());
        }
        return prompt;
    }

    /** CC PowerShellTool.tsx:225-227 — CLAUDE_CODE_DISABLE_BACKGROUND_TASKS 对等开关（BashTool 同款可注入字段）。 */
    private boolean backgroundTasksDisabled = false;

    /** 测试注入门控（非 Spring 场景）· 对齐 BashTool.setBackgroundTasksDisabled 模式。 */
    public void setBackgroundTasksDisabled(boolean backgroundTasksDisabled) {
        this.backgroundTasksDisabled = backgroundTasksDisabled;
    }

    /** 被拦截 Start-Sleep 模式正则 · 逐字移植 CC PowerShellTool.tsx:198（仅首语句，整数秒，-s/-Seconds 缩写）。 */
    private static final java.util.regex.Pattern BLOCKED_SLEEP_PATTERN = java.util.regex.Pattern.compile(
        "^(?:start-sleep|sleep)(?:\\s+-s(?:econds)?)?\\s+(\\d+)\\s*$",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * PS 版 detectBlockedSleepPattern · 逐字移植 CC {@code PowerShellTool.tsx:189-205}。
     *
     * <p>首语句 only（split {@code [;|&\r\n]}）· 匹配 {@code Start-Sleep N} / {@code Start-Sleep -Seconds N} /
     * {@code Start-Sleep -s N} / {@code sleep N}（内置别名）；{@code secs < 2} → null（sub-2s pacing 合法，
     * 对齐 CC :201）；rest 非空 → {@code "Start-Sleep N followed by: rest"}，空 → {@code "standalone Start-Sleep N"}（:203-204）。
     * <b>不拦</b> {@code Start-Sleep -Milliseconds}（浮点/子秒 pacing 合法）。
     *
     * @param command 原始 PowerShell 命令（input.command）
     * @return 命中模式描述（非 null = 应拦截）；未命中 → null
     */
    static String detectBlockedSleepPattern(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String trimmed = command.trim();
        // 首语句 only —— split on PS statement separators: `;`, `|`, `&`/`&&`/`||`, newline（CC :195）
        String first = trimmed.split("[;|&\\r\\n]")[0].trim();
        java.util.regex.Matcher m = BLOCKED_SLEEP_PATTERN.matcher(first);
        if (!m.matches()) {
            return null;
        }
        int secs = Integer.parseInt(m.group(1));
        if (secs < 2) {
            return null; // sub-2s sleeps fine（rate limiting, pacing）· CC :201
        }
        String rest = trimmed.substring(first.length()).replaceAll("^[\\s;|&]+", "");
        return rest.isEmpty() ? "standalone Start-Sleep " + secs
            : "Start-Sleep " + secs + " followed by: " + rest;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [B11] PowerShell 破坏性命令警告 · 逐字移植 CC tools/PowerShellTool/destructiveCommandWarning.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * PowerShell 破坏性命令警告模式对 · 对齐 CC {@code tools/PowerShellTool/destructiveCommandWarning.ts:7-10}
     * {@code {pattern, warning}}。与 Bash 版（bash/DestructiveCommandWarning）模式集<b>不同</b>——
     * PS 版含 Remove-Item/rm/del/rd/rmdir/ri（-Recurse/-Force 变体）、Clear-Content、Format-Volume、
     * Clear-Disk、Stop/Restart-Computer、Clear-RecycleBin 等 PS 特有模式（CC destructiveCommandWarning.ts:12-96）。
     * 纯信息展示，不影响权限逻辑或 auto-approval（:4）。
     */
    private record PowerShellDestructivePattern(java.util.regex.Pattern pattern, String warning) {}

    /** PowerShell 破坏性模式集 · 逐字移植 CC PowerShellTool/destructiveCommandWarning.ts:12-96（全部 /i → {@link java.util.regex.Pattern#CASE_INSENSITIVE}）。 */
    private static final java.util.List<PowerShellDestructivePattern> PS_DESTRUCTIVE_PATTERNS = java.util.List.of(
        // Remove-Item with -Recurse and/or -Force (and common aliases) · CC :21-40
        // 锚定语句起点 (^, |, ;, &, newline, {, ()，停用符加 }（非 )）——对齐 CC 注释语义。
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Recurse\\b[^|;&\\n}]*-Force\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may recursively force-remove files"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Force\\b[^|;&\\n}]*-Recurse\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may recursively force-remove files"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Recurse\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may recursively remove files"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Force\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may force-remove files"),

        // Clear-Content on broad paths · CC :43-46
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bClear-Content\\b[^|;&\\n]*\\*",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may clear content of multiple files"),

        // Format-Volume and Clear-Disk · CC :49-56
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bFormat-Volume\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may format a disk volume"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bClear-Disk\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may clear a disk"),

        // Git destructive operations (same as BashTool) · CC :58-75
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bgit\\s+reset\\s+--hard\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may discard uncommitted changes"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bgit\\s+push\\b[^|;&\\n]*\\s+(--force|--force-with-lease|-f)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may overwrite remote history"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bgit\\s+clean\\b(?![^|;&\\n]*(?:-[a-zA-Z]*n|--dry-run))[^|;&\\n]*-[a-zA-Z]*f",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may permanently delete untracked files"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bgit\\s+stash\\s+(drop|clear)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may permanently remove stashed changes"),

        // Database operations · CC :77-81
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: may drop or truncate database objects"),

        // System operations · CC :83-95
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bStop-Computer\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: will shut down the computer"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bRestart-Computer\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: will restart the computer"),
        new PowerShellDestructivePattern(java.util.regex.Pattern.compile(
            "\\bClear-RecycleBin\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE),
            "Note: permanently deletes recycled files")
    );

    /**
     * 检测可能有破坏性的 PowerShell 命令并返回人类可读警告字符串（权限弹窗显示）。
     * 纯信息展示 — 不影响权限逻辑或 auto-approval。对齐 CC
     * {@code tools/PowerShellTool/destructiveCommandWarning.ts:102-109}
     * {@code getDestructiveCommandWarning(command)}：有序遍历首个命中即返回（CC :103-108）。
     *
     * <p><b>B11 接线</b>：{@code WebSocketPermissionPrompter.renderPermissionWarning} PowerShell 分支
     * 门控 {@code nexusai.feature.destructive-command-warning}（默认关，对齐 CC
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false)}
     * PowerShellPermissionRequest.tsx:60）后调用本方法。
     *
     * @param command 原始 PowerShell 命令（input.command）
     * @return 命中模式警告串；未命中 / null / 空 → null
     */
    public static String getDestructiveCommandWarning(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        for (PowerShellDestructivePattern p : PS_DESTRUCTIVE_PATTERNS) {
            if (p.pattern().matcher(command).find()) {
                return p.warning();
            }
        }
        return null;
    }

    /**
     * 工具级语义验证 · 对齐 CC {@code PowerShellTool.tsx:352-374 validateInput}（G9）。
     *
     * <p>①MONITOR_TOOL 门控 Start-Sleep 拦截（errorCode 10，CC :361-369）——门控与 MonitorTool 同 flag：
     * {@code feature('MONITOR_TOOL') && !isBackgroundTasksDisabled && !input.run_in_background}（BashTool 同款）。
     * ②Windows 沙箱策略拒绝（errorCode 11，CC :354-360 + isWindowsSandboxPolicyViolation :219-222）。
     *
     * <p><b>②受控差异登记</b>：CC 沙箱策略需 {@code SandboxManager.isSandboxEnabledInSettings() &&
     * !areUnsandboxedCommandsAllowed()}——Java SandboxManager（permission/sandbox）无这两个设置 getter
     * （仅四门组合 isEnabled()，Windows 平台恒 false），无法在不动共享基础设施的前提下忠实实现；
     * DEC-1 BashTool 同款缺口（BashTool.validateInput 无沙箱策略检查）。登记受控差异，不伪造实现。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        boolean monitorToolGate = featureFlags != null && featureFlags.monitorTool();
        if (monitorToolGate && !backgroundTasksDisabled
                && !input.path("run_in_background").asBoolean(false)) {
            String command = input.path("command").asText("");
            String sleepPattern = detectBlockedSleepPattern(command);
            if (sleepPattern != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[PowerShellTool] 拦截阻塞 Start-Sleep 模式：{}（CC PowerShellTool.tsx:361-369, errorCode 10）",
                        sleepPattern);
                }
                return ValidationResult.fail("10",
                    "Blocked: " + sleepPattern + ". Run blocking commands in the background with "
                        + "run_in_background: true — you'll get a completion notification when done. "
                        + "For streaming events (watching logs, polling APIs), use the Monitor tool. "
                        + "If you genuinely need a delay (rate limiting, deliberate pacing), keep it under 2 seconds.");
            }
        }
        return ValidationResult.pass();
    }

    /**
     * [OPD-TOOL-35] 可见性门控 · 对齐 CC {@code shellToolUtils.ts:17-22 isPowerShellToolEnabled()}：
     * {@code platform==='windows' ? (USER_TYPE==='ant' ? !isEnvDefinedFalsy(env) : isEnvTruthy(env)) : false}。
     * Java 端拆为两因子合成：平台因子 isWindows() && env+USER_TYPE 三元 usePowerShellTool()。
     * 非 Windows 恒 false（先短路 isWindows）；Windows 才进入 usePowerShellTool() 三元。
     */
    @Override
    public boolean isEnabled() {
        return isWindows() && featureFlags.usePowerShellTool();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        // command: 必填（Java 命名约定 = CC shell_command；CC PowerShellTool.tsx:228-235）
        ObjectNode command = props.putObject("command");
        command.put("type", "string");
        command.put("description",
            "PowerShell command to execute (CC 'shell_command' field, Java 命名 'command').");
        // CC PowerShellTool.tsx:230 — timeout 字段（覆盖默认 120000ms，封顶 600000ms，对齐 CC :698）。
        // G12：移除 minimum:1（CC semanticNumber(z.number().optional()) 无 minimum，0 回退默认 — CC :698
        //   timeout || getDefaultTimeoutMs()，0/falsy → 默认 120000ms；Java execute 同款回退，见 :386-389）。
        ObjectNode timeout = props.putObject("timeout");
        timeout.put("type", "integer");
        timeout.put("description",
            "Optional timeout in milliseconds (overrides default " + DEFAULT_TIMEOUT_MS
                + "ms, max " + MAX_TIMEOUT_MS + "ms).");
        // CC PowerShellTool.tsx:231 — description 字段
        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description",
            "Clear, concise description of what this command does in 5-10 words.");
        // CC PowerShellTool.tsx:236 — run_in_background 字段
        ObjectNode runInBg = props.putObject("run_in_background");
        runInBg.put("type", "boolean");
        runInBg.put("description",
            "Set to true to run this command in the background. Use Read to read the output later.");
        schema.putArray("required").add("command");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 输出契约 · 对齐 CC {@code PowerShellTool.tsx:245-257 outputSchema} 10 字段。
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode stdout = props.putObject("stdout");
        stdout.put("type", "string");
        stdout.put("description", "The standard output of the command");
        ObjectNode stderr = props.putObject("stderr");
        stderr.put("type", "string");
        stderr.put("description", "The standard error output of the command");
        ObjectNode interrupted = props.putObject("interrupted");
        interrupted.put("type", "boolean");
        interrupted.put("description", "Whether the command was interrupted");
        ObjectNode returnCode = props.putObject("returnCodeInterpretation");
        returnCode.put("type", "string");
        returnCode.put("description", "Semantic interpretation for non-error exit codes with special meaning");
        ObjectNode isImage = props.putObject("isImage");
        isImage.put("type", "boolean");
        isImage.put("description", "Flag to indicate if stdout contains image data");
        ObjectNode persistedPath = props.putObject("persistedOutputPath");
        persistedPath.put("type", "string");
        persistedPath.put("description", "Path to persisted full output when too large for inline");
        ObjectNode persistedSize = props.putObject("persistedOutputSize");
        persistedSize.put("type", "integer");
        persistedSize.put("description", "Total output size in bytes when persisted");
        ObjectNode bgTaskId = props.putObject("backgroundTaskId");
        bgTaskId.put("type", "string");
        bgTaskId.put("description", "ID of the background task if command is running in background");
        ObjectNode bgByUser = props.putObject("backgroundedByUser");
        bgByUser.put("type", "boolean");
        bgByUser.put("description", "True if the user manually backgrounded the command with Ctrl+B");
        ObjectNode autoBg = props.putObject("assistantAutoBackgrounded");
        autoBg.put("type", "boolean");
        autoBg.put("description", "True if the command was auto-backgrounded by the assistant-mode blocking budget");
        return schema;
    }

    /**
     * 权限表态 · 对齐 CC {@code PowerShellTool.tsx:375-376 checkPermissions → powershellToolHasPermission}。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return permissionChain.check(input, ctx, this);
    }

    /**
     * 同步只读契约 · 对齐 CC {@code PowerShellTool.tsx:300-315 isReadOnly(input)}。
     *
     * <p>两步结构（与 CC 一致）：
     * <ol>
     *   <li>同步安全预检：{@link PowerShellPermissionChain#hasSyncSecurityConcerns(String)}
     *   （CC {@code readOnlyValidation.ts:1112-1159} 7 模式正则）命中 → return false。</li>
     *   <li>否则 {@link PowerShellPermissionChain#isReadOnlyCommand(String)}（无 AST 重载，
     *   恒保守 false，CC {@code readOnlyValidation.ts:1174-1177} `if (!parsed) return false`）。</li>
     * </ol>
     *
     * <p><b>同步 isReadOnly 恒 false 是 CC 实际行为</b>（无 AST → 保守 false，无法在不解析 AST
     * 的情况下判定只读，解析需真启 pwsh 30s 阻塞）。真实只读 auto-allow 走
     * {@link PowerShellPermissionChain#check}（{@code :230}）异步 AST 版 isReadOnlyCommand，
     * 不重复实现。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        String command = input != null && input.has("command") ? input.get("command").asText() : null;
        if (PowerShellPermissionChain.hasSyncSecurityConcerns(command)) {
            if (log.isDebugEnabled()) {
                log.debug("[PowerShellTool] 同步安全预检命中（hasSyncSecurityConcerns），阻断只读放行 command={}",
                    abbreviate(command, 120));
            }
            return false;
        }
        // 无 AST 保守 false（CC readOnlyValidation.ts:1174-1177），非 stub——CC 同步契约本就恒 false。
        return permissionChain.isReadOnlyCommand(command);
    }

    /**
     * 并发安全契约 · 对齐 CC {@code PowerShellTool.tsx:283-286 isConcurrencySafe(input)}：
     * {@code return this.isReadOnly?.(input) ?? false;}。
     *
     * <p>生产可达锚点 = {@code StreamingToolExecutor.java:569}
     * {@code t.isConcurrencySafe = tool.isConcurrencySafe(call.input())} —— 经此将
     * {@link #isReadOnly(JsonNode)}（及其前置 hasSyncSecurityConcerns）接入并发调度。
     */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return isReadOnly(input);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * execute 运行时管线 · 对齐 CC {@code PowerShellTool.tsx:437-658}（runPowerShellCommand 消费 →
     * interpretCommandResult → preSpawnError/pre-flight sentinel → ShellError → 64MB 持久化）。
     *
     * <p>{@code ctx == null}（PromptShellExecutor 1 参 dispatch 兼容路径）时跳过后台/持久化
     * （无 sessionId/workspaceDir/backgroundTaskRunner 上下文），不抛错。
     */
    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String command = input != null && input.has("command") ? input.get("command").asText() : null;
        if (command == null || command.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: command");
        }
        // IMP-DEL1（TR-C1-D-2）：删除 platform_not_supported 运行时错误——CC 非 Windows 不注册
        // PowerShellTool（shellToolUtils.ts:17-22 isPowerShellToolEnabled），isEnabled() 已按
        // 平台短路（isWindows() && featureFlags.usePowerShellTool()）；非 Windows 执行不会到达
        // 此处，落到下方 pwsh pre-flight（缺失返回 availability sentinel，对齐 CC :717-728）。
        // ── CC PowerShellTool.tsx:845-857 — 显式 run_in_background ──
        boolean runInBackground = input != null && input.path("run_in_background").asBoolean(false);
        if (runInBackground && backgroundTaskRunner != null) {
            // Phase 4 (cron-notify): 透传创建会话 sessionId（ctx.sessionId()，可靠源 —— 本工具在
            // tool-exec 池线程执行，MDC 无值）→ BackgroundTaskRunner.spawn → 完成通知注入创建会话回合。
            String sessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
            return executeBackground(command, call.id(), sessionId);
        }
        // ── CC :717-728 — pwsh 探测 pre-flight（缺失返回 code 0 + stderr sentinel）──
        String pwshPath = resolvePwshPath();
        if (pwshPath == null) {
            log.warn("[PowerShellTool] pwsh 不可用，返回 pre-flight sentinel（CC PowerShellTool.tsx:718-727）");
            return ToolResult.success(call.id(), "PowerShell is not available on this system.");
        }

        // E4: stdout/stderr 捕获载体（reader 线程写入，finally 清理 spill 临时文件）
        OutputCapture[] stdoutCap = new OutputCapture[1];
        OutputCapture[] stderrCap = new OutputCapture[1];
        Path combinedSpill = null;
        // [OD-2A-2] cd 追踪临时文件（外层声明，供 finally 清理）。
        Path cwdTrackFile = null;

        try {
            // ── [OD-2A-2 · INV-2] PowerShell 前台 cd 持久化 · 对齐 CC powershellProvider
            //    buildExecCommand（powershellProvider.ts:35-97）+ Shell.ts:385-421 cd tracking 全机制 ──
            // CC 真源（自验，不信注释）：powershellProvider.ts:65 cwdTracking 三连
            //   `\n; $_ec = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($?) { 0 } else { 1 }
            //    \n; (Get-Location).Path | Out-File -FilePath '<cwdFilePath>' -Encoding utf8 -NoNewline
            //    \n; exit $_ec`
            //   —— ① 生成 cwdFilePath（同 bashProvider 产 pwd 等价，non-sandbox 落 tmpdir
            //   `claude-pwd-ps-${id}`）；② 退出码保持：$LASTEXITCODE 非 null 优先（native exe 语义），
            //   $? 兜底（cmdlet-only 管道），尾随 Out-File 成功不得把失败命令改报 exit 0 —— 经
            //   `exit $_ec` 保持原退出码，否则 PowerShellTool.execute 的 exitCode 语义（
            //   PowerShellCommandSemantics，grep/robocopy 非零码）被追踪尾巴污染。
            // Shell.ts:385-421 跑完 readFileSync 读回 → NFC 比对（Shell.ts:406）→ 变化时 setCwd +
            //   onCwdChangedForHooks；仅前台 !backgroundTaskId（:395）；preventCwdChanges 不更新（:395，
            //   Java 未接沙箱执行路径 → 等价 false，登记 OD-2A-1）。
            // Java 等价（与 BashTool [WF-2A] 同构，DEC-1 联动）：命令尾部追加完整 CC cwdTracking
            //   （含 $_ec 退出码保持，逐字对齐 powershellProvider.ts:65）到临时文件 → 跑完读回 →
            //   NFC 比对 sessionCwd → 变化时 SessionCwdHolder.set（内部 realpath+NFC，
            //   对齐 setCwdState + setCwd realpathSync）。
            // sessionId=null（dispatch 兼容路径）时跳过持久化（无会话载体，对齐 CC 无 STATE）。
            String sessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
            String sessionCwd = CwdResolution.getCwd(sessionId);
            String wrappedCommand = command;
            try {
                cwdTrackFile = Files.createTempFile("ps-cwd-", ".tmp");
                String trackPath = cwdTrackFile.toString();
                // 对齐 CC powershellProvider.ts:65 cwdTracking 尾部追加（逐字，含 $_ec 退出码保持）；
                //   Out-File -Encoding utf8 -NoNewline 对齐 CC 同款编码/无尾换行；单引号转义文件路径
                //   （CC :54 escapedCwdFilePath = cwdFilePath.replace(/'/g, "''")）。
                String escapedTrack = trackPath.replace("'", "''");
                wrappedCommand = command
                    + "\n; $_ec = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($?) { 0 } else { 1 }"
                    + "\n; (Get-Location).Path | Out-File -FilePath '" + escapedTrack
                    + "' -Encoding utf8 -NoNewline"
                    + "\n; exit $_ec";
            } catch (Exception cwdEx) {
                if (log.isDebugEnabled()) {
                    log.debug("[PowerShellTool] cd 追踪临时文件创建失败，降级不追踪 cwd: {}", cwdEx.toString());
                }
                cwdTrackFile = null;
            }

            ProcessBuilder pb = new ProcessBuilder(pwshPath, "-NoProfile", "-Command", wrappedCommand);
            pb.redirectErrorStream(false);  // 分离 stdout / stderr（CC outputSchema 10 字段）
            // [OD-2A-2 返工 R1] spawn cwd = 实时会话 cwd（对齐 CC Shell.ts:218 pwd() 每次取全局
            //   STATE.cwd → spawn {cwd}；对齐 BashTool pb.directory 走实时 sessionCwd）。
            //   【关键修正】不再优先 ctx.effectiveCwd() —— effectiveCwd 是 ToolUseContext 构造时冻结的
            //   cwd 快照（WF-1A DEL-02，INV-6），循环内 per-turn TUC 经 with...() 派生保留该快照，
            //   不随 cd 刷新。若优先 effectiveCwd，PS cd sub 后下一条命令 pb.directory 仍用循环启动时的
            //   旧 cwd → cd 持久化（INV-2）在跨工具调用场景失效（BashTool 无此问题，其直接用 sessionCwd）。
            //   sessionCwd 此处 = CwdResolution.getCwd(sessionId) 实时解析（cd 读回后 SessionCwdHolder
            //   已更新 → 下一条命令取到新 cwd）。effectiveCwd 快照仅供技能发现/权限 baseDir 等消费
            //   （INV-6 注释），不应用于 spawn 目录。
            Path cwd = Path.of(sessionCwd != null && !sessionCwd.isBlank() ? sessionCwd : fallbackCwd());
            pb.directory(cwd.toFile());

            // CC :698 — timeoutMs = Math.min(timeout || getDefaultTimeoutMs(), getMaxTimeoutMs())
            // G12：0/falsy timeout 回退默认（CC `timeout || getDefaultTimeoutMs()` —— 0、null、undefined
            //   均回退 120000ms）。旧 Java 用 asLong(DEFAULT) 作缺省值，timeout=0 时返回 0 →
            //   Math.min(0, MAX)=0 → waitFor(0) 立即判超时杀进程（与 CC「0 回退默认」相悖）。
            long timeoutInput = input != null && input.has("timeout")
                ? input.get("timeout").asLong(DEFAULT_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS;
            long timeoutMs = Math.min(timeoutInput > 0 ? timeoutInput : DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS);

            Process process = pb.start();

            // 异步读 stdout / stderr · 流式捕获完整输出，超 SPILL_THRESHOLD(30k) spill 临时文件
            //（对齐 CC ShellCommand 大输出写 outputFilePath；移除旧 50KB 内存截断）
            Thread stdoutReader = new Thread(() -> {
                try {
                    stdoutCap[0] = captureOutput(process.getInputStream(),
                        (int) SPILL_THRESHOLD, SPILL_THRESHOLD);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[PowerShellTool] stdout 捕获失败: {}", e.toString());
                    }
                }
            }, "ps-stdout");
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            Thread stderrReader = new Thread(() -> {
                try {
                    stderrCap[0] = captureOutput(process.getErrorStream(),
                        (int) SPILL_THRESHOLD, SPILL_THRESHOLD);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[PowerShellTool] stderr 捕获失败: {}", e.toString());
                    }
                }
            }, "ps-stderr");
            stderrReader.setDaemon(true);
            stderrReader.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                // [G8 受控差异] 超时转后台：CC PowerShellTool.tsx:824-828 超时 → shellCommand.onTimeout →
                //   startBackgrounding（前台任务原地后台化 / spawnBackgroundTask），进程不杀、命令继续运行。
                //   Java BackgroundTaskRunner.spawn()（BackgroundTaskRunner.java:130-229）经
                //   LocalBashTaskRunner.execute() 重新执行命令，无「采纳已运行 java.lang.Process」的
                //   后台化基础设施；DEC-1 BashTool 同款缺口（BashTool.java:937-943 超时 destroyForcibly）。
                //   登记受控差异（owner 后续拍板），保留 kill-on-timeout（不伪造实现）。
                process.destroyForcibly();
                stdoutReader.interrupt();
                stderrReader.interrupt();
                log.warn("[PowerShellTool] 超时 {}ms，进程被强制终止 command={}", timeoutMs, abbreviate(command, 120));
                return ToolResult.error(call.id(), "Timeout (" + (timeoutMs / 1000) + "s) · process killed");
            }

            stdoutReader.join(2000);
            stderrReader.join(2000);

            int exitCode = process.exitValue();

            // ── [OD-2A-2 · CC-CWD-06] 前台命令跑完读回 cwd 更新 SessionCwdHolder ──
            // 对齐 CC Shell.ts:385-421：仅前台命令（executeBackground 路径不经此）；
            // readFileSync 读回 newCwd → NFC 比对（Shell.ts:406 newCwd.normalize('NFC') !== cwd）→
            // 变化时 setCwd（realpath+NFC，Shell.ts:447-464 setCwd）。Java 等价：读临时文件
            // → NFC 比对 sessionCwd → 变化时 SessionCwdHolder.set（内部 realpath+NFC，对齐 setCwdState）。
            // sessionId=null（dispatch 兼容路径）跳过持久化（无会话载体，对齐 CC 无 STATE）。
            // [OD-2A-2 返工 R1] PS 5.1 BOM：CC 真源 pwsh 7+ 的 Out-File -Encoding utf8 无 BOM；
            //   Java resolvePwshPath 兜底 powershell（5.1），其 -Encoding utf8 写 UTF-8 BOM
            //   （U+FEFF 前缀）。不剥离 BOM 则 Path.of(newCwd) 抛 InvalidPathException、持久化
            //   cwd 含不可见前缀（SessionCwdHolder.set → normalizeCwd realpath 失败回原值+NFC
            //   仍带 BOM）→ 下一条命令 pb.directory 用带 BOM 路径失败。故读回后统一剥离
            //   ﻿（对齐 CC readFileSync utf8 语义 = 无 BOM 路径）。
            if (cwdTrackFile != null && sessionId != null) {
                try {
                    String newCwd = Files.readString(cwdTrackFile, StandardCharsets.UTF_8)
                        .replace("﻿", "").trim();
                    if (!newCwd.isBlank()) {
                        String normalizedNew = Normalizer.normalize(newCwd, Normalizer.Form.NFC);
                        if (!normalizedNew.equals(sessionCwd)) {
                            com.nexusai.application.agent.agent.SessionCwdHolder.set(sessionId, newCwd);
                            if (log.isDebugEnabled()) {
                                log.debug("[PowerShellTool] cd 持久化: sessionId {} {} -> {}",
                                    sessionId, sessionCwd, newCwd);
                            }
                        }
                    }
                } catch (Exception trackEx) {
                    // 对齐 CC Shell.ts:411-413 catch 兜底：读回失败记 debug 不抛（命令可能于 pwd 前失败）。
                    if (log.isDebugEnabled()) {
                        log.debug("[PowerShellTool] cd 追踪读回失败（命令可能于 pwd 前失败）: {}", trackEx.toString());
                    }
                }
            }
            OutputCapture stdoutOut = stdoutCap[0] != null ? stdoutCap[0] : new OutputCapture("", 0, null);
            OutputCapture stderrOut = stderrCap[0] != null ? stderrCap[0] : new OutputCapture("", 0, null);
            String out = stdoutOut.preview();
            String err = stderrOut.preview();
            if (log.isDebugEnabled()) {
                log.debug("[PowerShellTool] exitCode={} stdoutBytes={} stderrBytes={}",
                    exitCode, stdoutOut.totalBytes(), stderrOut.totalBytes());
            }

            // ── 退出码语义解释 · 对齐 CC PowerShellTool.tsx:555 interpretCommandResult 消费 ──
            // isError → ToolResult.error（CC :583 ShellError），非零码不再恒 success（OPD-TOOL-34）。
            PowerShellExitCodeResult baseResult =
                interpretExitCodeResult(call.id(), command, exitCode, out, err);

            // ── [IMP-T G15] 命令执行遥测 · 对齐 CC PowerShellTool.tsx:504 trackGitOperations
            //    + :637-643 logEvent('tengu_powershell_tool_command_executed') ──
            // 仅非错误路径发射命令事件（CC :583 ShellError throw 跳过；Java 等价 isError 门）。
            emitPowerShellCommandExecutedTelemetry(command, exitCode, out, err, baseResult);

            // ── 64MB 落盘 · 对齐 CC :596-617（与 DEC-1 BashTool 联动）──
            // 完整输出（stdout+stderr 合计）超 SPILL_THRESHOLD 且非 error 且会话上下文可用时落盘
            // {workspaceDir}/{sessionId}/tool-results/{call.id()}.txt。失败降级保留截断 stdout。
            // IMP-DEL1（TR-B1-⊕-9 跨 Bash/PowerShell 同步）：落盘 = 原始输出（无 stderr 分隔行）。
            // [IMP-C2 返工 R2] 用显式 baseResult.isError()（PowerShellCommandSemantics 判定），
            //   替换 isToolErrorData 前缀启发式（Bash/PowerShell 错误载荷 "Exit code" 前缀漏检）。
            try {
                long combinedTotal = stdoutOut.totalBytes() + stderrOut.totalBytes();
                if (!baseResult.isError()
                    && combinedTotal > SPILL_THRESHOLD
                        && ctx != null && ctx.sessionId() != null) {
                    combinedSpill = writeOriginalOutputSpill(stdoutOut, stderrOut);
                    // 批次4 #20：persist workspaceDir 稳定锚 = getOriginalCwdLayer(sessionId)
                    //   （对齐 CC toolResultStorage.ts:97-104 用 getOriginalCwd 稳定锚；
                    //   去 effectiveCwd 优先层——cd 后落盘目录漂移是 bug，落启动/worktree 目录）。
                    Path workspaceDir = Path.of(
                        CwdResolution.getOriginalCwdLayer(ctx.sessionId()));
                    ToolResultStorage.PersistedToolResult persisted = ToolResultStorage.persistOutputFile(
                        workspaceDir, ctx.sessionId(), call.id(),
                        combinedSpill, ToolResultStorage.MAX_PERSISTED_SIZE);
                    if (persisted != null) {
                        String previewMessage = ToolResultStorage.buildLargeToolResultMessage(persisted);
                        Map<String, Object> structuredOutput = Map.of(
                            "persistedOutputPath", persisted.filepath(),
                            "persistedOutputSize", persisted.originalSize());
                        if (log.isDebugEnabled()) {
                            log.debug("[PowerShellTool] 输出落盘成功: path={} originalSize={} 预览len={}",
                                persisted.filepath(), persisted.originalSize(), previewMessage.length());
                        }
                        return ToolResult.successWithStructuredOutput(
                            call.id(), previewMessage, structuredOutput);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[PowerShellTool] 持久化降级（保留截断 stdout）: total={}", combinedTotal);
                    }
                }
            } catch (Exception pe) {
                if (log.isDebugEnabled()) {
                    log.debug("[PowerShellTool] 持久化异常降级（保留截断 stdout）: {}", pe.toString());
                }
            }
            return baseResult.result();

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ToolResult.error(call.id(), "interrupted");
        } catch (Exception e) {
            log.warn("[PowerShellTool] failed: {}", e.getMessage());
            return ToolResult.error(call.id(), "powershell failed: " + e.getMessage());
        } finally {
            // 清理 spill 临时文件（persistOutputFile 已 link/copy 进 tool-results，源可删）
            if (stdoutCap[0] != null) {
                deleteQuietly(stdoutCap[0].spillFile());
            }
            if (stderrCap[0] != null) {
                deleteQuietly(stderrCap[0].spillFile());
            }
            deleteQuietly(combinedSpill);
            // [OD-2A-2] cd 追踪临时文件清理（对齐 CC Shell.ts:417 unlinkSync，失败静默）。
            deleteQuietly(cwdTrackFile);
        }
    }

    /**
     * [IMP-C2 返工 R2] 退出码解释结果 · 显式携带 isError 标志（同 BashTool.BashExitCodeResult）。
     *
     * <p><b>WHY</b>: ToolResult 4 字段契约删除 isError 后，直接 execute 消费链（持久化守卫）
     * 需要错误语义。CC 端 is_error 由实际执行路径推导（PowerShellCommandSemantics
     * interpretCommandResult），非载荷文本前缀判定。本记录在知悉错误的边界（本方法内已得
     * isError）同步携带标志，替换数据前缀启发式 {@code LlmAgentLoop.isToolErrorData}。
     *
     * @param result  ToolResult（isError → ToolResult.error(data=错误消息)）
     * @param isError 显式错误标志（CC interpretCommandResult.isError 推导）
     */
    record PowerShellExitCodeResult(ToolResult<String> result, boolean isError) {}

    /**
     * [IMP-T G15] PowerShell 命令执行遥测 · 对齐 CC PowerShellTool.tsx:504 trackGitOperations +
     * :637-643 logEvent('tengu_powershell_tool_command_executed')。
     *
     * <p>metadata 值域对齐 CC LogEventMetadata（boolean|number|undefined）：
     * <ul>
     *   <li>{@code command_type} — CC :638 {@code getCommandTypeForLogging(input.command)}
     *       （PowerShellTool.tsx:262-270）：首词命中 {@link #COMMON_BACKGROUND_COMMANDS}
     *       （大小写不敏感）→ 规范化命令名；否则 {@code 'other'}。经
     *       {@link AnalyticsTracker#verified} 包装（CC AnalyticsMetadata_I_VERIFIED 标记）</li>
     *   <li>{@code stdout_length}/{@code stderr_length} — 输出长度（number，CC :639-640 分离流真实值）</li>
     *   <li>{@code exit_code} — 进程退出码（number，CC :641）</li>
     *   <li>{@code interrupted} — 恒 false：Java 中断走 InterruptedException 早退，不达本块</li>
     * </ul>
     *
     * @param command   原始命令文本
     * @param exitCode  进程退出码
     * @param out       stdout（preview）
     * @param err       stderr（preview）
     * @param baseResult 退出码语义解释结果（isError → 不发射命令事件，对齐 CC :583 throw）
     */
    private void emitPowerShellCommandExecutedTelemetry(String command, int exitCode,
            String out, String err, PowerShellExitCodeResult baseResult) {
        if (analyticsTracker == null) {
            return;
        }
        // CC PowerShellTool.tsx:504 trackGitOperations（成功门在内，exitCode==0 才检测）
        analyticsTracker.trackGitOperations(command, exitCode, out);
        // CC :583 ShellError throw → 命令事件不发射；Java 等价 = isError 门
        if (baseResult.isError()) {
            return;
        }
        analyticsTracker.logEvent("tengu_powershell_tool_command_executed",
            Map.<String, Object>of(
                "command_type", AnalyticsTracker.verified(commandTypeForLogging(command)),
                "stdout_length", out == null ? 0 : out.length(),
                "stderr_length", err == null ? 0 : err.length(),
                "exit_code", exitCode,
                "interrupted", false));
        if (log.isDebugEnabled()) {
            log.debug("[PowerShellTool] [IMP-T G15] 遥测 tengu_powershell_tool_command_executed: "
                + "command_type={} stdout_len={} exit={}",
                commandTypeForLogging(command), out == null ? 0 : out.length(), exitCode);
        }
    }

    /** CC COMMON_BACKGROUND_COMMANDS（PowerShellTool.tsx:261）· getCommandTypeForLogging 规范化命令集。 */
    private static final java.util.Set<String> COMMON_BACKGROUND_COMMANDS = java.util.Set.of(
        "npm", "yarn", "pnpm", "node", "python", "python3", "go", "cargo", "make", "docker",
        "terraform", "webpack", "vite", "jest", "pytest", "curl", "Invoke-WebRequest",
        "build", "test", "serve", "watch", "dev");

    /**
     * 命令类型规范化 · 对齐 CC {@code getCommandTypeForLogging}（PowerShellTool.tsx:262-270）：
     * 首词（trim + split(/\s+/)）命中 {@link #COMMON_BACKGROUND_COMMANDS}（大小写不敏感）→
     * 返回规范化命令名；否则 {@code 'other'}。值域为枚举串（非 code/filepath）。
     */
    private static String commandTypeForLogging(String command) {
        if (command == null) {
            return "other";
        }
        String trimmed = command.trim();
        String firstWord = trimmed.isEmpty() ? "" : trimmed.split("\\s+")[0];
        for (String cmd : COMMON_BACKGROUND_COMMANDS) {
            if (firstWord.equalsIgnoreCase(cmd)) {
                return cmd;
            }
        }
        return "other";
    }

    /**
     * 解释命令退出码并按语义分派 ToolResult · 对齐 CC PowerShellTool.tsx:555 消费
     * {@code interpretCommandResult(input.command, result.code, processedStdout, result.stderr)}。
     *
     * <p><b>package-private</b>：供合成退出码聚焦单测（不依赖真实 pwsh 进程）。
     * isError → {@code ToolResult.error}（CC :583 ShellError）；message 非 null（grep/robocopy 语义）
     * 折入 success 文本（Java ToolResult 无 returnCodeInterpretation 字段，折入 data 文本，
     * 与 BashTool DEC-1 interpretExitCodeResult 同款偏离）。
     *
     * <p><b>[IMP-C2 返工 R2]</b>: 返回 {@link PowerShellExitCodeResult} 显式携带 isError（错误路径
     * 由 {@code PowerShellCommandSemantics} 判定，非数据前缀启发式），持久化守卫据此跳过错误结果落盘。
     *
     * @param toolUseId 工具调用 ID（CC mapper 透传）
     * @param command   原始 PowerShell 命令（CC input.command）
     * @param exitCode  进程退出码（CC result.code）
     * @param out       stdout（CC result.stdout）
     * @param err       stderr（CC result.stderr）
     */
    PowerShellExitCodeResult interpretExitCodeResult(String toolUseId, String command, int exitCode, String out, String err) {
        // IMP-DEL1：对齐 CC PowerShellTool.tsx mapToolResultToToolResultBlockParam 输出语义
        // （TR-B1-⊕-3/⊕-4，跨 Bash/PowerShell 同步）——CC 合并 fd 无 stderr 分隔行
        // （[stdout, stderr].filter(Boolean).join('\n')），空输出由 UI 显示而非注入模型可见文本。
        StringBuilder combined = new StringBuilder();
        if (out != null && !out.isEmpty()) combined.append(out);
        if (err != null && !err.isEmpty()) {
            if (combined.length() > 0) combined.append("\n");
            combined.append(err);
        }
        String result = BashOutputUtils.stripEmptyLines(combined.toString());

        // 输出格式化接线（stripEmptyLines → formatOutput，截断阈值 30000）
        BashOutputUtils.FormattedOutput formatted =
            BashOutputUtils.formatOutput(result, BashOutputUtils.BASH_MAX_OUTPUT_DEFAULT);
        result = formatted.truncatedContent();
        if (log.isDebugEnabled()) {
            log.debug("[PowerShellTool] 输出格式化 totalLines={} isImage={} resultLen={}",
                formatted.totalLines(), formatted.isImage(), result.length());
        }

        PowerShellCommandSemantics.Result r =
            commandSemantics.interpretCommandResult(command, exitCode, out, err);
        if (log.isDebugEnabled()) {
            log.debug("[PowerShellTool] 退出码语义解释 command={} exitCode={} isError={} message={}",
                abbreviate(command, 100), exitCode, r.isError(), r.message());
        }
        if (r.isError()) {
            // 对齐 CC BashTool.tsx:687+699：stdout 先入输出，isError 时 append "Exit code N"。
            return new PowerShellExitCodeResult(
                ToolResult.error(toolUseId, result + "\nExit code " + exitCode), true);
        }
        if (r.message() != null) {
            // CC 走 data.returnCodeInterpretation（:649）；Java ToolResult 无此字段，折入 success 文本。
            return new PowerShellExitCodeResult(
                ToolResult.success(toolUseId, r.message() + "\n" + result), false);
        }
        return new PowerShellExitCodeResult(ToolResult.success(toolUseId, result), false);
    }

    /**
     * 后台执行路径 — 对齐 CC PowerShellTool.tsx:845-857（run_in_background → spawnBackgroundTask）
     * + LocalShellTask.tsx:60-95。
     *
     * <p>生成 taskId → 创建 BackgroundTask(RUNNING) → spawn 到 BackgroundTaskRunner → 返回
     * backgroundTaskId（CC 后台早返回 data.backgroundTaskId）。TaskType 复用 LOCAL_BASH（CC PowerShell
     * 走同一 LocalShellTask local_bash 通道，无独立 PS 类型）。
     */
    private ToolResult<java.util.Map<String, Object>> executeBackground(String command, String toolUseId, String createSessionId) {
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        // 批次4 #18：outputFile 走 taskOutputPath 同源分层 {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks/{taskId}.output
        // （对齐 CC getTaskOutputPath，diskOutput.ts:72-74；sessionId 防并发会话 clobber；扩展名 .output 对齐 CC）。
        String outputFile = BackgroundTaskRunner.taskOutputPath(taskId);

        try {
            // 分层格式父目录不天然存在 · CC ensureOutputDir diskOutput.ts:65-67
            Files.createDirectories(Path.of(outputFile).getParent());
        } catch (Exception e) {
            log.warn("[PowerShellTool] cannot create task output dir {}: {}", outputFile, e.getMessage());
        }

        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            abbreviate(command, 100), toolUseId,
            System.currentTimeMillis(), null, null,
            outputFile, 0L, false
        );

        backgroundTaskRunner.spawn(task, command, createSessionId);

        log.info("[PowerShellTool] background task {} spawned, output={}, createSessionId={}", taskId, outputFile, createSessionId);
        Map<String, Object> structuredOutput = Map.of(
            "backgroundTaskId", taskId,
            "backgroundedByUser", false,
            "assistantAutoBackgrounded", false);
        return ToolResult.successWithStructuredOutput(toolUseId,
            "Background task started: " + taskId
                + "\nUse TaskOutput to read the result from: " + outputFile,
            structuredOutput);
    }

    /**
     * 解析 pwsh 路径（对齐 CC parser.ts:1156 getCachedPowerShellPath；缓存语义）。
     *
     * <p>Java 端重复 {@link PowerShellAstService#resolvePwshPath}（该私有方法无法从本类复用，
     * 且 AstService 在 files 之外不可改），按 CC 顺序探测 pwsh / powershell / powershell.exe。
     */
    private static String resolvePwshPath() {
        String cached = cachedPwshPath;
        if (cached != null) {
            return cached;
        }
        synchronized (PWSH_LOCK) {
            if (cachedPwshPath != null) {
                return cachedPwshPath;
            }
            String resolved = null;
            if (isExecutable("pwsh")) resolved = "pwsh";
            else if (isExecutable("powershell")) resolved = "powershell";
            else if (isExecutable("powershell.exe")) resolved = "powershell.exe";
            cachedPwshPath = resolved;
            if (log.isDebugEnabled()) {
                log.debug("[PowerShellTool] resolvePwshPath={}", resolved);
            }
            return resolved;
        }
    }

    private static boolean isExecutable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-NoProfile", "-NonInteractive", "-NoLogo", "-Command", "$true")
                .redirectErrorStream(true).start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }

    /**
     * effectiveCwd 缺失时的兜底 cwd · 对齐 CC getCwd()（Shell.ts:218 pwd()）。
     * cwd-align-ext：user.dir 兜底 → 会话 cwd；无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    private static String fallbackCwd() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    // ── PowerShell 完整输出捕获 · CC ShellCommand outputFilePath 的 Java 等价（DEC-1 BashTool 同款）──

    /**
     * 进程单流捕获结果（stdout / stderr 各自一路）。
     *
     * @param preview    内存保留前缀（前 {@code SPILL_THRESHOLD} 字符，供模型小输出路径与解释语义用）
     * @param totalBytes 该流完整字节数（不受 preview 上限截断）
     * @param spillFile  完整输出 spill 临时文件；输出未超阈值时为 null
     */
    private record OutputCapture(String preview, long totalBytes, Path spillFile) {
        boolean spilled() {
            return spillFile != null;
        }
    }

    /**
     * 流式捕获进程单路输出：内存仅保留前 {@code previewLimit} 字符，完整输出超 {@code spillThreshold}
     * 时 spill 到临时文件（CC ShellCommand 大输出写 outputFilePath）。
     */
    private static OutputCapture captureOutput(InputStream stream, int previewLimit, long spillThreshold)
            throws IOException {
        StringBuilder preview = new StringBuilder(previewLimit + 512);
        Path spillFile = null;
        BufferedWriter spillWriter = null;
        long total = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                total += line.length() + 1L;
                boolean needSpill = spillWriter != null;
                if (!needSpill && total > spillThreshold) {
                    spillFile = Files.createTempFile("ps-out-", ".txt");
                    spillWriter = Files.newBufferedWriter(spillFile, StandardCharsets.UTF_8);
                    spillWriter.write(preview.toString());
                    needSpill = true;
                }
                if (needSpill) {
                    spillWriter.write(line);
                    spillWriter.newLine();
                } else if (preview.length() < previewLimit) {
                    preview.append(line).append('\n');
                }
            }
        } finally {
            if (spillWriter != null) {
                spillWriter.close();
            }
        }
        return new OutputCapture(preview.toString(), total, spillFile);
    }

    /**
     * 持久化原始输出到临时文件（IMP-DEL1 · TR-B1-⊕-9，替代已删除的旧合并落盘方法）。
     * 对齐 CC 持久化「原始 outputFilePath」语义：CC 合并 fd，stderr 已并入 stdout 单流，
     * 落盘文件 = 完整输出、无 stderr 分隔行。Java 分离读取两路，落盘时按模型可见内容
     * 同款衔接（stdout 后跟单个换行再跟 stderr，对齐 mapToolResultToToolResultBlockParam
     * [stdout, stderr].filter(Boolean).join('\n')）。
     */
    private static Path writeOriginalOutputSpill(OutputCapture stdoutCap, OutputCapture stderrCap) throws IOException {
        Path combined = Files.createTempFile("ps-out-", ".txt");
        try {
            if (stdoutCap.spilled()) {
                try (InputStream in = Files.newInputStream(stdoutCap.spillFile());
                     var w = Files.newOutputStream(combined)) {
                    in.transferTo(w);
                }
            } else if (stdoutCap.totalBytes() > 0) {
                Files.writeString(combined, stdoutCap.preview(), StandardCharsets.UTF_8);
            }
            if (stderrCap.totalBytes() > 0) {
                try (BufferedWriter w = Files.newBufferedWriter(
                        combined, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                    if (stdoutCap.totalBytes() > 0) {
                        w.write("\n");
                    }
                    if (stderrCap.spilled()) {
                        try (BufferedReader r = Files.newBufferedReader(
                                stderrCap.spillFile(), StandardCharsets.UTF_8)) {
                            r.transferTo(w);
                        }
                    } else {
                        w.write(stderrCap.preview());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(combined);
            throw e;
        }
        return combined;
    }

    /** 静默删除临时文件（清理 spill 载体，失败仅 debug 日志）。 */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[PowerShellTool] 清理临时文件失败（忽略）: path={} err={}", p, e.toString());
            }
        }
    }
}
