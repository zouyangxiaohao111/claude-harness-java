package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt shell 注入执行器 · 对齐 CC {@code utils/promptShellExecution.ts:69-143}
 * {@code executeShellCommandsInPrompt}
 *
 * <p><b>职责</b>：技能内容组装（{@code SkillContentLoader} 纯文本替换）完成后，对 inline
 * {@code !`cmd`} 与 {@code ```! ```} 代码块执行 shell 注入（CC getPromptForCommand 闭包
 * loadSkillsDir.ts:344-396 的最后一步）。调用方 {@code SkillToolImpl.doExecute} 先经
 * {@code CommandSource.MCP} 安全闸（等价 CC {@code loadedFrom !== 'mcp'}）才调用本类。
 *
 * <p><b>CC 真源行为（Read 实证）</b>：
 * <ol>
 *   <li>shellTool 解析（:80-83）：{@code shell==='powershell' && isPowerShellToolEnabled() ?
 *       PowerShellTool : BashTool}——注释实证"不读 settings.defaultShell，来自 .md frontmatter"。
 *       Java 端 Command 无 shell 字段（F-15 ✗）→ shell 参数恒 null → 恒走 BashTool；
 *       pwsh 路由休眠至 P1-5 补 shell frontmatter 解析。</li>
 *   <li>matches 收集（:89-90）：BLOCK_PATTERN（```!）恒扫；INLINE_PATTERN（!`）仅当
 *       {@code text.includes('!`')}（低成本子串检查先于昂贵 lookbehind 扫描）。</li>
 *   <li>权限预检（:98-113）：{@code hasPermissionsToUseTool(...).behavior !== 'allow'} →
 *       logForDebugging + {@code throw new MalformedCommandError('Shell command permission check
 *       failed for pattern ...')}——<b>不可省略</b>（scope-coverage F-1 安全相关）。</li>
 *   <li>执行 + 替换（:115-131）：{@code shellTool.call({command}, context)} →
 *       {@code processToolResultBlock} → output 提取；:131 {@code result.replace(match[0],
 *       () => output)} function replacer 注释实证防 PowerShell {@code $env:PATH}、{@code $$}
 *       等任意用户数据破坏替换串（Java 端等价 {@link Matcher#quoteReplacement}）。</li>
 *   <li>catch（:132-137）：{@code MalformedCommandError} 原样 rethrow；其余经
 *       {@code formatBashError} → throw {@code MalformedCommandError}（interrupted 特判
 *       {@code [Command interrupted]}，:167-183）。</li>
 * </ol>
 *
 * <p><b>调用点对齐</b>：CC loadSkillsDir.ts:374-395 门控 {@code loadedFrom !== 'mcp'} +
 * {@code getAppState()} 包装注入 {@code alwaysAllowRules.command = allowedTools}
 * （:385-388）+ 传 {@code '/'+skillName} 与 {@code shell}（:393-394）。Java 端 allowedTools
 * 以 whole-tool ALLOW 规则并入 permCtx 的 alwaysAllowRules[COMMAND] 桶
 * （{@link #withAllowedTools}，仿 {@code SkillToolImpl.mergeAllowedToolsIntoAppState}），
 * 再经 {@link PermissionPipeline#check} 走 2b 层 whole-tool allow 放行。
 *
 * <p><b>构造链</b>：{@code ToolRegistrationConfig.promptShellExecutor()} @Bean 注入
 * BashTool/PowerShellTool/PermissionPipeline；无参构造（测试/手动）默认 fail-closed
 * （PermissionPipeline 未注入 → 权限预检一律拒绝 → 抛 MalformedCommandException）。
 * 可测性：{@link ShellCommandRunner}（执行）与 {@link ShellPermissionChecker}（权限）均可注入覆盖。
 */
public class PromptShellExecutor {

    private static final Logger log = LoggerFactory.getLogger(PromptShellExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 代码块模式 · CC original: BLOCK_PATTERN（promptShellExecution.ts:49）
     * {@code /```!\s*\n?([\s\S]*?)\n?```/g}——总是扫描（```! 不要求文本含 !`）。
     */
    static final Pattern BLOCK_PATTERN = Pattern.compile("```!\\s*\\n?([\\s\\S]*?)\\n?```");

    /**
     * 内联模式 · CC original: INLINE_PATTERN（promptShellExecution.ts:56）
     * {@code /(?<=^|\s)!`([^`]+)`/gm}——positive lookbehind 要求 ! 前是行首或空白，
     * 防止 markdown inline code span 内 {@code !!} / 相邻 span {@code foo}<!`bar} 误匹配
     * （CC :52-56 注释）。门控：仅当 {@code text.includes('!`')}（CC :90）。
     */
    static final Pattern INLINE_PATTERN = Pattern.compile("(?<=^|\\s)!`([^`]+)`", Pattern.MULTILINE);

    /** 默认 shell 工具名 · 对齐 CC BashTool（promptShellExecution.ts:83）。 */
    private static final String BASH = "Bash";
    /** PowerShell 工具名 · 对齐 CC PowerShellTool（promptShellExecution.ts:82）。 */
    private static final String POWER_SHELL = "PowerShell";

    private final BashTool bashTool;
    private final PowerShellTool powerShellTool;
    private final PermissionPipeline permissionPipeline;

    /** 可注入覆盖的执行器（测试用）；null → 默认委托 bashTool/powerShellTool.execute。 */
    private ShellCommandRunner commandRunner;
    /** 可注入覆盖的权限预检器（测试用）；null → 默认经 PermissionPipeline.check。 */
    private ShellPermissionChecker permissionChecker;

    /** 无参构造（测试 / SkillToolImpl 构造器兜底）· 全部依赖 null → fail-closed。 */
    public PromptShellExecutor() {
        this(null, null, null);
    }

    /**
     * Bash 专用构造 · 由 {@code ToolRegistrationConfig.promptShellExecutor()} @Bean 注入。
     *
     * @param bashTool           BashTool（shell==null 时恒用；可为 null → 执行/权限 fail-closed）
     * @param permissionPipeline 权限管线（可为 null → 权限预检一律拒绝）
     */
    public PromptShellExecutor(BashTool bashTool, PermissionPipeline permissionPipeline) {
        this(bashTool, null, permissionPipeline);
    }

    /**
     * 全依赖构造（Bash + PowerShell + Pipeline）。
     *
     * @param bashTool           BashTool（可为 null）
     * @param powerShellTool     PowerShellTool（pwsh 路由休眠至 P1-5；可为 null）
     * @param permissionPipeline 权限管线（可为 null → fail-closed）
     */
    public PromptShellExecutor(BashTool bashTool, PowerShellTool powerShellTool,
                               PermissionPipeline permissionPipeline) {
        this.bashTool = bashTool;
        this.powerShellTool = powerShellTool;
        this.permissionPipeline = permissionPipeline;
    }

    /** 注入自定义执行器（测试）· null 恢复默认委托。 */
    public void setCommandRunner(ShellCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    /** 注入自定义权限预检器（测试）· null 恢复默认 Pipeline 检查。 */
    public void setPermissionChecker(ShellPermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * 解析 prompt 文本并执行任何内嵌 shell 命令 · 对齐 CC
     * {@code executeShellCommandsInPrompt}（promptShellExecution.ts:69-143）。
     *
     * <p>支持两种语法（CC :60-63）：
     * <ul>
     *   <li>代码块：{@code ```! command ```}</li>
     *   <li>内联：{@code !`command`}</li>
     * </ul>
     *
     * @param text            技能 prompt 文本（经 prefix→substituteArguments→SKILL_DIR→SESSION_ID
     *                        组装后）
     * @param ctx             工具调用上下文（权限预检依赖 permissionContext；null → 跳过并 warn）
     * @param slashCommandName 形如 {@code "/skillName"}（用于权限失败日志，CC loadSkillsDir.ts:393）
     * @param shell           CC original: shell?（FrontmatterShell，.md frontmatter 作者选择；
     *                        Java Command 已有 shell 字段（P1-5，frontmatterParser.ts:351-370 解析），
     *                        SkillToolImpl 经 {@code cmd.getShell()} 透传，CC promptShellExecution.ts:64-67）
     * @param allowedTools    技能声明的 allowed tools（CC loadSkillsDir.ts:385-388 注入
     *                        alwaysAllowRules.command；null/空 → 不注入）
     * @return shell 注入后的 prompt 文本
     * @throws MalformedCommandException 权限预检失败 / 执行失败（CC MalformedCommandError 透传，
     *                                   fail-loud 不静默降级）
     */
    public String executeShellCommandsInPrompt(String text, ToolUseContext ctx, String slashCommandName,
                                               String shell, List<String> allowedTools) {
        if (text == null) {
            return null;
        }
        // CC :80-83 shellTool 解析 —— shell==='powershell' 且门控开启才走 PowerShell，否则 Bash。
        //   Java 端 shell 来自 Command.shell（P1-5，SkillToolImpl:676 cmd.getShell() 透传）；
        //   未声明/frontmatter 非法 → null → 回退 Bash。
        String shellToolName = "powershell".equals(shell) && isPowerShellToolEnabled()
                ? POWER_SHELL : BASH;

        // CC :89-90 matches 收集 —— BLOCK 恒扫；INLINE 仅 text.includes('!`') 门控
        //   （lookbehind 比 BLOCK 慢 ~100 倍，93% 技能无 !`，低成本子串检查先行，CC :85-90 注释）。
        List<Match> matches = collectMatches(text);
        if (matches.isEmpty()) {
            return text;
        }

        if (ctx == null) {
            // 无 ToolUseContext → 无法做权限预检 → fail-loud 日志跳过（不静默也不抛）。
            //   生产 StreamingToolExecutor 3 参 dispatch 恒有 ctx；仅 1 参 execute(block) 旧路径缺。
            log.warn("[PromptShellExecutor] 检测到 {} 个内嵌 shell 命令但 ctx=null，跳过 shell 注入 "
                    + "(无法权限预检，CC promptShellExecution.ts:98)", matches.size());
            return text;
        }

        ShellCommandRunner runner = commandRunner != null
                ? commandRunner : cmd -> runShellCommand(shellToolName, cmd);
        ShellPermissionChecker checker = permissionChecker != null
                ? permissionChecker : (tn, cmd, c, at) -> checkPermission(tn, cmd, c, at);

        if (log.isInfoEnabled()) {
            log.info("[PromptShellExecutor] 技能 '{}' 注入 {} 个内嵌 shell 命令 (shellTool={}) "
                    + "(CC promptShellExecution.ts:69-143)", slashCommandName, matches.size(), shellToolName);
        }

        String result = text;
        for (Match match : matches) {
            String command = match.command() == null ? "" : match.command().trim();
            if (command.isEmpty()) {
                continue;   // CC :95 if (command) —— 空命令跳过
            }
            try {
                // (a) 权限预检 · 对齐 CC :97-113（不可省略，scope-coverage F-1 安全相关）
                if (!checker.allow(shellToolName, command, ctx, allowedTools)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[PromptShellExecutor] Shell command permission check failed for "
                                + "command in {}: {} (CC promptShellExecution.ts:106-113)",
                                slashCommandName, abbreviate(command, 100));
                    }
                    throw new MalformedCommandException(
                            "Shell command permission check failed for pattern \""
                                    + match.fullMatch() + "\": Permission denied");
                }
                // (b) 执行 · 对齐 CC :115 shellTool.call({command}, context)
                ToolResult<String> toolResult = runner.run(command);
                if (toolResult == null) {
                    throw new IllegalStateException("ShellCommandRunner returned null for command: " + command);
                }
                // (c) 失败 → ShellError 等价（CC BashTool.call 非 0 退出码 throw ShellError，
                //     Java BashTool 返回 ToolResult.error；interrupted 特判 :169-172）
                if (com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(toolResult.data())) {
                    String data = toolResult.data() == null ? "" : toolResult.data();
                    throw new ShellCommandFailedException("", data, "interrupted".equals(data.trim()));
                }
                // (d) 替换 · 对齐 CC :126-131 —— String.replace 会解释 $$ $& $` $' 替换串；
                //     function replacer（Java 等价 Matcher.quoteReplacement）让任意输出（尤其
                //     PowerShell $env:PATH / $$）按字面插入（CC :127-131 注释）。
                String output = formatBashOutput(toolResult.data(), "", false);
                result = replaceOnce(result, match.fullMatch(), output);
            } catch (MalformedCommandException e) {
                throw e;   // CC :133-134 rethrow —— 权限/注入失败 fail-loud 上抛
            } catch (ShellCommandFailedException e) {
                throw formatBashError(e, match.fullMatch());   // CC formatBashError ShellError 分支
            } catch (Exception e) {
                throw formatBashError(e);   // CC formatBashError other 分支
            }
        }
        return result;
    }

    /** 收集 BLOCK + INLINE 匹配（BLOCK 恒扫；INLINE 仅 text.contains("!`") 门控，CC :89-90）。 */
    private static List<Match> collectMatches(String text) {
        List<Match> matches = new ArrayList<>();
        Matcher blockMatcher = BLOCK_PATTERN.matcher(text);
        while (blockMatcher.find()) {
            matches.add(new Match(blockMatcher.group(0), blockMatcher.group(1)));
        }
        if (text.contains("!`")) {
            Matcher inlineMatcher = INLINE_PATTERN.matcher(text);
            while (inlineMatcher.find()) {
                matches.add(new Match(inlineMatcher.group(0), inlineMatcher.group(1)));
            }
        }
        return matches;
    }

    /** 单个匹配 · fullMatch = match[0]（替换目标），command = group(1)（执行命令）。 */
    private record Match(String fullMatch, String command) {}

    /**
     * 默认执行器 · 委托注入的 BashTool/PowerShellTool.execute（合成 ToolUseBlock）。
     *
     * <p>与 CC {@code shellTool.call({command}, context)} 等价（promptShellExecution.ts:115）。
     * Java BashTool 内部含 parseForSecurity + DANGEROUS 黑名单拦截 + Windows cmd.exe（R11-2 既有
     * 行为，与 CC Git Bash 有执行器偏移——既有偏差，非本类引入）。BashTool.execute 失败返回
     * {@code ToolResult.error}（非抛异常），由 {@link #executeShellCommandsInPrompt} (c) 转
     * {@link ShellCommandFailedException}。
     */
    private ToolResult<String> runShellCommand(String shellToolName, String command) {
        Tool tool = resolveTool(shellToolName);
        if (tool == null) {
            throw new IllegalStateException("Shell tool not configured: " + shellToolName);
        }
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        input.put("description", "Execute skill-inline shell command");
        ToolUseBlock block = new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input);
        AgentToolResult<?> ar = tool.execute(block);
        if (!(ar instanceof ToolResult<?> tr)) {
            return ToolResult.error(block.id(), "shell tool returned non-ToolResult result");
        }
        @SuppressWarnings("unchecked")
        ToolResult<String> ts = (ToolResult<String>) tr;
        return ts;
    }

    /** 解析 shell 工具实例 · "PowerShell" → powerShellTool，否则 bashTool。 */
    private Tool resolveTool(String shellToolName) {
        return POWER_SHELL.equals(shellToolName) ? powerShellTool : bashTool;
    }

    /**
     * 默认权限预检 · 对齐 CC {@code hasPermissionsToUseTool}（permissions.ts:1158-1319）。
     *
     * <p>把技能 allowedTools 以 whole-tool ALLOW 规则并入 permCtx 的 alwaysAllowRules[COMMAND] 桶
     * （仿 {@code SkillToolImpl.mergeAllowedToolsIntoAppState}，对齐 CC loadSkillsDir.ts:385-388
     * {@code alwaysAllowRules.command = allowedTools}），再经 {@link PermissionPipeline#check}
     * 10 层检查（1c 工具自决 Allow / 2b whole-tool allow / 3 passthrough→Ask 兜底）。
     *
     * <p><b>Allow 才放行</b>（CC :106 {@code permissionResult.behavior !== 'allow'} → throw）：
     * <ul>
     *   <li>只读命令（echo/ls/cat 等，BashTool.checkPermissions 返回 Allow）→ 放行</li>
     *   <li>危险命令（DANGEROUS 黑名单，BashTool.checkPermissions 返回 Deny）→ 拒绝</li>
     *   <li>普通命令（Passthrough）+ allowedTools 含 Bash/PowerShell → 2b 层 Allow → 放行</li>
     *   <li>普通命令 + 无 allowedTools → 3 层转 Ask → 拒绝（CC 真语义：内嵌 shell 必须有
     *       allowed-tools 声明才有预授权）</li>
     * </ul>
     *
     * <p><b>fail-closed</b>：PermissionPipeline 未注入（默认构造/测试）或 shell 工具未配置 →
     * 一律拒绝（非空指针）。
     */
    private boolean checkPermission(String shellToolName, String command, ToolUseContext ctx,
                                    List<String> allowedTools) {
        if (permissionPipeline == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptShellExecutor] 权限管线未注入（默认构造/测试）→ fail-closed 拒绝 "
                        + "command={} (CC promptShellExecution.ts:98)", abbreviate(command, 80));
            }
            return false;
        }
        Tool tool = resolveTool(shellToolName);
        if (tool == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptShellExecutor] shell 工具未配置: {} → fail-closed 拒绝", shellToolName);
            }
            return false;
        }
        // CC permissionContext 恒有值；Java 测试可缺省 → 空规则集（对齐 SkillToolImpl.checkPermissions :310-313）
        ToolPermissionContext base = ctx != null && ctx.permissionContext() != null
                ? ctx.permissionContext()
                : ToolPermissionContext.strict(PermissionMode.DEFAULT);
        ToolPermissionContext merged = withAllowedTools(base, allowedTools);
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input);
        PermissionResult result = permissionPipeline.check(tool, call, input, ctx, merged);
        boolean allow = result instanceof PermissionResult.Allow;
        if (!allow && log.isDebugEnabled()) {
            log.debug("[PromptShellExecutor] 权限非 allow: tool={} command={} decision={} → throw "
                    + "(CC promptShellExecution.ts:106-113)", shellToolName, abbreviate(command, 80),
                    result == null ? "null" : result.getClass().getSimpleName());
        }
        return allow;
    }

    /**
     * 把 allowedTools 以 whole-tool ALLOW 规则并入 alwaysAllowRules[COMMAND] 桶 ·
     * 对齐 CC loadSkillsDir.ts:385-388 {@code alwaysAllowRules.command = allowedTools}。
     *
     * <p>Java 表示：command 桶 = {@code alwaysAllowRules[COMMAND]}（{@code Set<PermissionRule>}），
     * 每工具名映射为 whole-tool ALLOW rule（对齐 CC command 桶是工具名 string[]，SkillTool.ts:790-801）。
     *
     * @param base         当前权限上下文（可为 strict 空规则集）
     * @param allowedTools 技能声明的 allowed tools（null/空 → 原样返回 base）
     * @return 合并后的 ToolPermissionContext（含更新后的 COMMAND allow 桶）
     */
    static ToolPermissionContext withAllowedTools(ToolPermissionContext base, List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return base;
        }
        // 空规则集（Map.of() 非 EnumMap）不能直接 new EnumMap<>(m) —— EnumMap(Map) 构造器
        // 对空非-EnumMap 输入抛 "Specified map is empty"（JDK 实证）→ 空时按 enum 类型初始化。
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
                base.alwaysAllowRules().isEmpty()
                        ? new EnumMap<>(PermissionRuleSource.class)
                        : new EnumMap<>(base.alwaysAllowRules());
        Set<PermissionRule> commandRules = new LinkedHashSet<>(
                allow.getOrDefault(PermissionRuleSource.COMMAND, Set.of()));
        for (String toolName : allowedTools) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            commandRules.add(new PermissionRule(
                    PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                    PermissionRuleValue.wholeTool(toolName)));
        }
        allow.put(PermissionRuleSource.COMMAND, commandRules);
        return new ToolPermissionContext(
                base.mode(), allow, base.alwaysDenyRules(), base.alwaysAskRules(),
                base.additionalWorkingDirectories(), base.isBypassPermissionsModeAvailable(),
                base.isAutoModeAvailable(), base.strippedDangerousRules(),
                base.shouldAvoidPermissionPrompts(), base.awaitAutomatedChecksBeforeDialog(),
                base.prePlanMode());
    }

    /**
     * 输出格式化 · 对齐 CC {@code formatBashOutput}（promptShellExecution.ts:145-165）。
     *
     * <ul>
     *   <li>stdout 非空 → 加入 stdout.trim()</li>
     *   <li>stderr 非空 → inline ? {@code [stderr: X]} : {@code [stderr]\nX}</li>
     *   <li>join：inline ? 空格 : 换行</li>
     * </ul>
     *
     * @param stdout CC original: stdout（:146-154）
     * @param stderr CC original: stderr（:156-162）
     * @param inline CC original: inline（默认 false；skill 路径恒 false，REPL !` 路由才用 inline）
     */
    public static String formatBashOutput(String stdout, String stderr, boolean inline) {
        List<String> parts = new ArrayList<>();
        if (stdout != null && !stdout.trim().isEmpty()) {
            parts.add(stdout.trim());
        }
        if (stderr != null && !stderr.trim().isEmpty()) {
            parts.add(inline ? "[stderr: " + stderr.trim() + "]" : "[stderr]\n" + stderr.trim());
        }
        return String.join(inline ? " " : "\n", parts);
    }

    /**
     * 执行失败格式化 · 对齐 CC {@code formatBashError}（promptShellExecution.ts:167-183）
     * ShellError 分支。
     *
     * <ul>
     *   <li>interrupted → {@code Shell command interrupted for pattern "X": [Command interrupted]}（:169-172）</li>
     *   <li>否则 → {@code Shell command failed for pattern "X": <formatBashOutput>}（:174-177）</li>
     * </ul>
     */
    private static MalformedCommandException formatBashError(ShellCommandFailedException e, String pattern) {
        if (e.interrupted) {
            return new MalformedCommandException(
                    "Shell command interrupted for pattern \"" + pattern + "\": [Command interrupted]");
        }
        String output = formatBashOutput(e.stdout, e.stderr, false);
        return new MalformedCommandException(
                "Shell command failed for pattern \"" + pattern + "\": " + output);
    }

    /**
     * 其他错误格式化 · 对齐 CC {@code formatBashError}（promptShellExecution.ts:180-182）
     * 非 ShellError 分支：{@code [Error]\nmessage}（inline 用 {@code [Error: message]}）。
     */
    private static MalformedCommandException formatBashError(Exception e) {
        String message = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        return new MalformedCommandException("[Error]\n" + message);
    }

    /**
     * 单次替换 · 对齐 CC :131 function replacer 等价。
     *
     * <p>JS {@code String.replace} 会把替换串中的 {@code $$ $& $` $'} 解释为特殊语义
     * （CC :127-131 注释实证）——CC 用 {@code () => output} function replacer 让任意输出按字面
     * 插入。Java 端<b>手动 substring 拼接</b>即为字面插入的等价物（{@code Matcher.replaceFirst}
     * / {@code appendReplacement} 才会解释 {@code $}，而 {@code quoteReplacement} 只在这些 API
     * 消费时才需要反转义）——直接拼接让任意输出（尤其 PowerShell {@code $env:PATH}、{@code $$}）
     * 不被错误展开（RED 实证：quoteReplacement 手动拼接会把 {@code $} 双转义成 {@code \$}）。
     */
    private static String replaceOnce(String text, String search, String replacement) {
        if (search == null || search.isEmpty()) {
            return text;
        }
        Matcher m = Pattern.compile(Pattern.quote(search)).matcher(text);
        if (!m.find()) {
            return text;
        }
        return text.substring(0, m.start()) + replacement + text.substring(m.end());
    }

    /**
     * PowerShell 运行时门控 · 对齐 CC {@code isPowerShellToolEnabled}（shellToolUtils.ts:17-22）。
     *
     * <p>CC 真源：非 Windows 直接 false；{@code USER_TYPE==='ant'}（默认开可 opt-out）否则
     * （外部）默认关可 opt-in via {@code CLAUDE_CODE_USE_POWERSHELL_TOOL}。Java 无 USER_TYPE
     * 概念 → 按外部默认关：仅 Windows + {@code CLAUDE_CODE_USE_POWERSHELL_TOOL} truthy 时启用。
     */
    public static boolean isPowerShellToolEnabled() {
        if (!isWindows()) {
            return false;
        }
        String env = System.getenv("CLAUDE_CODE_USE_POWERSHELL_TOOL");
        if (env == null) {
            return false;
        }
        String normalized = env.toLowerCase().trim();
        return List.of("1", "true", "yes", "on").contains(normalized);
    }

    /** 是否 Windows 平台 · CC original: getPlatform() === 'windows'（shellToolUtils.ts:18）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 执行器 · CC original: PromptShellTool（promptShellExecution.ts:20-25）。
     *
     * <p>函数式接口，command → {@code ToolResult<String>}。默认实现委托注入的
     * BashTool/PowerShellTool.execute（合成 {@code ToolUseBlock}）；测试注入 fake 不跑真实进程。
     */
    @FunctionalInterface
    public interface ShellCommandRunner {
        ToolResult<String> run(String command);
    }

    /**
     * 权限预检器 · CC original: hasPermissionsToUseTool（permissions.ts:1158-1319）。
     *
     * <p>函数式接口，toolName/command/ctx/allowedTools → boolean（allow 才放行）。默认实现
     * {@link #checkPermission}（allowedTools 并入 COMMAND 桶后走 PermissionPipeline.check）。
     */
    @FunctionalInterface
    public interface ShellPermissionChecker {
        boolean allow(String toolName, String command, ToolUseContext ctx, List<String> allowedTools);
    }

    /**
     * Shell 执行失败 · CC original: ShellError（errors.ts:51-61）内部等价。
     *
     * <p>携带 stdout/stderr/interrupted 三字段；由 {@link #executeShellCommandsInPrompt} (c) 从
     * {@code ToolResult.isError} 构造，catch 后经 {@link #formatBashError(ShellCommandFailedException, String)}
     * 转 {@link MalformedCommandException}。
     */
    private static final class ShellCommandFailedException extends RuntimeException {
        final String stdout;
        final String stderr;
        final boolean interrupted;

        ShellCommandFailedException(String stdout, String stderr, boolean interrupted) {
            super("Shell command failed");
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.interrupted = interrupted;
        }
    }
}
