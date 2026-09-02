package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.powershell.PowerShellCommandSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 危险模式检测器 · 对齐 CC {@code permissionSetup.ts:94} + {@code dangerousPatterns.ts}
 *
 * <h2>职责</h2>
 * <p>检测并剥离"危险"的 Bash 权限规则，防止在 auto mode 下分类器被绕过。
 * 危险规则 = 允许执行任意代码的 {@code Bash(…)} 规则。
 *
 * <h2>危险 Bash 命令模式（S09 对齐 CC dangerousPatterns.ts 外部构建子集）</h2>
 * <p>精确形态 + 全小写匹配（非正则 find），集合 = CC {@code CROSS_PLATFORM_CODE_EXEC}
 * + zsh/fish/eval/exec/env/xargs/sudo（外部构建不含 ant 专属 gh/curl/wget/git 等）：
 * <ol>
 *   <li>跨平台代码执行入口：python / python3 / python2 / node / deno / tsx / ruby / perl / php / lua</li>
 *   <li>包运行器：npx / bunx / npm run / yarn run / pnpm run / bun run</li>
 *   <li>Shell + 远程：bash / sh / ssh</li>
 *   <li>附加 Bash 专属：zsh / fish / eval / exec / env / xargs / sudo</li>
 * </ol>
 * <p>PS 形态（powershell/pwsh/Invoke-* 等）已移除——归 CC {@code isDangerousPowerShellPermission}
 * （permissionSetup.ts:157-207，PowerShell 工具域 WF-5）；Node/编译器形态 CC 无对应已删。
 *
 * <h2>核心操作（CC 状态流：strip → stash → restore）</h2>
 * <ul>
 *   <li>{@link #isDangerousRule(PermissionRule)} — 判定单条规则是否危险</li>
 *   <li>{@link #findDangerousRules(ToolPermissionContext)} — 从上下文中提取所有危险 allow 规则</li>
 *   <li>{@link #stripDangerousPermissionsForAutoMode(ToolPermissionContext)} — 进入 auto mode 时剥离危险规则并 stash 于上下文</li>
 *   <li>{@link #restoreDangerousPermissions(ToolPermissionContext)} — 退出 auto mode 时按 stash 恢复规则（幂等，二次 no-op）</li>
 * </ul>
 *
 * <h2>CC 函数对应</h2>
 * <ul>
 *   <li>{@link #isDangerousRule} ↔ CC {@code findDangerousClassifierPermissions} 单条谓词
 *       （{@code permissionSetup.ts:303-309}，{@code ruleBehavior==='allow'} 前置）</li>
 *   <li>{@link #isDangerousClassifierPermission} ↔ CC {@code isDangerousClassifierPermission}
 *       （{@code permissionSetup.ts:272-285} = ant-only Tmux || bash || powershell || task）</li>
 *   <li>{@link #isDangerousBashPermission} ↔ CC {@code isDangerousBashPermission}（{@code :94-147}）</li>
 *   <li>{@link #isDangerousTaskPermission} ↔ CC {@code isDangerousTaskPermission}（{@code :240-245}，
 *       OPD-WF4-BC-03 补充）</li>
 *   <li>{@link #findDangerousRules} ↔ CC {@code findDangerousClassifierPermissions}</li>
 *   <li>{@link #stripDangerousPermissionsForAutoMode} ↔ CC {@code stripDangerousPermissionsForAutoMode}
 *       （{@code permissionSetup.ts:510-553}）</li>
 *   <li>{@link #restoreDangerousPermissions} ↔ CC {@code restoreDangerousPermissions}
 *       （{@code permissionSetup.ts:561-579}）</li>
 * </ul>
 *
 * <h2>S04 对齐说明</h2>
 * <p>S04 按 CC stash 语义重接恢复路径（O10 关闭）：被剥离规则 stash 进
 * {@link ToolPermissionContext#strippedDangerousRules()}，恢复时按 CC 逐源
 * {@code addRules} 回写并清空 stash —— 二次恢复 no-op。
 * <p>Applier 重建 ctx 会把 {@code strippedDangerousRules} 与 3 个上下文标志位
 * （{@code shouldAvoidPermissionPrompts} / {@code awaitAutomatedChecksBeforeDialog} /
 * {@code prePlanMode}）重置，本类 {@link #withStash} 按 CC spread 语义
 * （{@code permissionSetup.ts:549-552} {@code {...removeDangerousPermissions(context, ...),
 * strippedDangerousRules: stripped}}）从剥离/恢复前的原 ctx 保真这 3 个标志位。
 */
@Component
public class DangerousPatternDetector {

    private static final Logger log = LoggerFactory.getLogger(DangerousPatternDetector.class);

    /**
     * 危险的 Bash 命令模式（对齐 CC {@code dangerousPatterns.ts}，外部构建非 ant 子集）。
     *
     * <p>【S09 域】重构为精确形态 + 全小写匹配（CC {@code isDangerousBashPermission}
     * {@code permissionSetup.ts:94-147}）：匹配 {@code PermissionRuleValue.ruleContent}
     * 的<b>完整命令形态</b>，而非正则 {@code find} 子串。
     *
     * <p>集合来源：CC {@code DANGEROUS_BASH_PATTERNS}（dangerousPatterns.ts:44-80）
     * = {@code CROSS_PLATFORM_CODE_EXEC}（:18-42）+ zsh/fish/eval/exec/env/xargs/sudo
     * （:46-52）+ <b>ant 专属</b>（:58-78，{@code USER_TYPE==='ant'} 才含 gh/curl/wget/git/
     * kubectl/aws/gcloud/gsutil 等，外部构建不含）。本项目为外部构建 → 不含 ant 子集。
     *
     * <p>删除（DEL-WF4-02-03）：PS 形态（powershell/pwsh/Invoke-Expression/Start-Process 等）归
     * {@code isDangerousPowerShellPermission}（permissionSetup.ts:157-207，PowerShell 工具域
     * WF-5）；Node 模块（require(child_process)/exec(/spawn(/eval(）与编译器
     * （javac/dotnet/go run/rustc/cargo run/Rscript）CC 无对应 → 删。
     */
    private static final List<String> DANGEROUS_BASH_PATTERNS = List.of(
            // ── 跨平台代码执行入口（CC dangerousPatterns.ts:18-42 CROSS_PLATFORM_CODE_EXEC）──
            "python", "python3", "python2", "node", "deno", "tsx",
            "ruby", "perl", "php", "lua",
            // ── 包运行器（CC :30-36）──
            "npx", "bunx", "npm run", "yarn run", "pnpm run", "bun run",
            // ── Shell（Git Bash / WSL 均可达）──
            "bash", "sh",
            // ── 远程任意命令包装（Win10+ 原生 OpenSSH）──
            "ssh",
            // ── 附加 Bash 专属（CC :46-52，外部构建非 ant）──
            "zsh", "fish", "eval", "exec", "env", "xargs", "sudo"
    );

    /**
     * CC {@code applyPermissionUpdate} 的 Java 等价（{@code PermissionUpdate.ts:55-188}）。
     *
     * <p>剥离/恢复均按 CC 走 {@code removeRules} / {@code addRules} 更新：
     * <ul>
     *   <li>{@code removeDangerousPermissions}（CC :472-503）→ {@code PermissionUpdate.RemoveRules}</li>
     *   <li>{@code restoreDangerousPermissions}（CC :561-579）→ {@code PermissionUpdate.AddRules}</li>
     * </ul>
     *
     * <p>注意：Applier 重建 ctx 时会把 {@code strippedDangerousRules} 重置为
     * {@code Map.of()}、并把 3 个上下文标志位（{@code shouldAvoidPermissionPrompts} /
     * {@code awaitAutomatedChecksBeforeDialog} / {@code prePlanMode}）重置为
     * {@code false/false/null}（S16/S17 共享写集域，本类不修改），因此 stash 由
     * {@link #withStash} 在 Applier 结果上补回，标志位从剥离/恢复前的原 ctx 保真
     * （CC spread 语义，{@code permissionSetup.ts:549-552/:578}）。
     */
    private final PermissionUpdateApplier applier;

    public DangerousPatternDetector(PermissionUpdateApplier applier) {
        if (applier == null) {
            throw new IllegalArgumentException("applier is null");
        }
        this.applier = applier;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * 检查单条规则是否危险（对齐 CC {@code findDangerousClassifierPermissions}
     * {@code permissionSetup.ts:295-320} 的单条谓词）。
     *
     * <p>判定逻辑（S09 重构：精确形态 + 全小写，取代旧正则 {@code find}）：
     * <ol>
     *   <li>行为必须是 {@link PermissionBehavior#ALLOW}（deny/ask 不算危险——deny 是保护，ask 会弹窗；
     *       CC :303 {@code ruleBehavior === 'allow'}）</li>
     *   <li>委托 {@link #isDangerousClassifierPermission}（CC :272-285）：
     *       ant-only Tmux（:276-279）|| Bash（:94-147）|| PowerShell（:157-233）|| Task（:240-245）</li>
     * </ol>
     *
     * @param rule 权限规则（非 null）
     * @return {@code true} 如果规则是危险的 allow 规则
     */
    public boolean isDangerousRule(PermissionRule rule) {
        if (rule.ruleBehavior() != PermissionBehavior.ALLOW) {
            return false;
        }
        // CC :303-309 —— findDangerousClassifierPermissions 单条谓词
        // （ruleBehavior==='allow' && isDangerousClassifierPermission(toolName, ruleContent)）
        return isDangerousClassifierPermission(
                rule.ruleValue().toolName(),
                rule.ruleValue().ruleContent());
    }

    /**
     * 组合危险判定 · 对齐 CC {@code isDangerousClassifierPermission}
     * （{@code permissionSetup.ts:272-285}）。
     *
     * <p>CC 组合：{@code ant-only Tmux || isDangerousBashPermission ||
     * isDangerousPowerShellPermission || isDangerousTaskPermission}。三类工具集互斥，
     * 求值顺序不影响语义。
     *
     * <p><b>OPD-WF4-BC-03</b>：补充 ant-only Tmux（:276-279）+ isDangerousTaskPermission（:240-245）。
     * <ul>
     *   <li>ant-only Tmux：CC {@code process.env.USER_TYPE === 'ant'} 门控——Java 以
     *       {@code System.getenv("USER_TYPE")} 建模（与 SpeculationEngine.java:109 /
     *       MockRateLimits.java:59,66 同模式）。外部构建（USER_TYPE≠'ant'）恒 false。</li>
     *   <li>Task 危险判定：任何 {@code Agent}（或其 legacy wire 名 {@code Task}）allow 规则
     *       auto 模式下会绕过分类器对 sub-agent 提示词的安全评估（delegation attack 防御），
     *       必须剥离（HIGH 缺口）。</li>
     * </ul>
     *
     * @param toolName    工具名
     * @param ruleContent 规则内容（可为 null）
     * @return {@code true} 如果该 allow 规则危险，auto mode 应剥离
     */
    public boolean isDangerousClassifierPermission(String toolName, String ruleContent) {
        // CC :276-279 —— ant-only Tmux send-keys 执行任意 shell，绕过分类器同 Bash(*)
        if ("ant".equals(System.getenv("USER_TYPE")) && "Tmux".equals(toolName)) {
            return true;
        }
        // CC :280-285 —— bash || powershell || task
        return isDangerousBashPermission(toolName, ruleContent)
                || PowerShellCommandSafety.isDangerousPowerShellPermission(toolName, ruleContent)
                || isDangerousTaskPermission(toolName, ruleContent);
    }

    /**
     * Bash allow 规则是否危险（auto mode 剥离，防分类器绕过）· 对齐 CC
     * {@code isDangerousBashPermission}（{@code permissionSetup.ts:94-147}）。
     *
     * <p>从 {@link #isDangerousRule} 提取（原内嵌 Bash 判定），行为逐行等价。
     *
     * <p>判定逻辑（S09 重构：精确形态 + 全小写，取代旧正则 {@code find}）：
     * <ol>
     *   <li>工具必须是 {@code "Bash"}（CC {@code BASH_TOOL_NAME='Bash'}，tools/BashTool/toolName.ts:2）</li>
     *   <li>{@code ruleContent == null || ""}（wholeTool allow）→ 最危险（允许所有 Bash 命令，CC :104-106）</li>
     *   <li>{@code content.trim().toLowerCase() === '*' } → 危险（独立通配符，CC :111-113）</li>
     *   <li>{@code content} 精确命中任一 {@link #DANGEROUS_BASH_PATTERNS} 的 5 形态
     *       （{@code pattern}/{@code pattern:*}/{@code pattern*}/{@code pattern *}/{@code pattern -*}，
     *       CC :117-144）→ 危险</li>
     * </ol>
     *
     * @param toolName    工具名
     * @param ruleContent 规则内容（可为 null）
     * @return {@code true} 如果规则是危险的 Bash allow 规则
     */
    public boolean isDangerousBashPermission(String toolName, String ruleContent) {
        // [E session] 工具名已对齐 CC BASH_TOOL_NAME='Bash'（原 s03 双写兼容已收敛为单一真值）
        if (!"Bash".equals(toolName)) {
            return false;
        }
        // CC :104-106 —— 无内容（wholeTool allow）= 允许任意 Bash 命令 = 最危险
        if (ruleContent == null || ruleContent.isEmpty()) {
            return true;
        }

        // CC :108 —— trim + 全小写归一
        String content = ruleContent.trim().toLowerCase();

        // CC :111-113 —— 独立通配符 '*' 匹配一切
        if ("*".equals(content)) {
            return true;
        }

        // CC :117-144 —— 精确形态匹配（非正则 find）
        for (String pattern : DANGEROUS_BASH_PATTERNS) {
            String lowerPattern = pattern.toLowerCase();
            // Exact match to the pattern itself (e.g., "python" as a rule)
            if (content.equals(lowerPattern)) {
                return true;
            }
            // Prefix syntax: "python:*" allows any python command
            if (content.equals(lowerPattern + ":*")) {
                return true;
            }
            // Wildcard at end: "python*" matches python, python3, etc.
            if (content.equals(lowerPattern + "*")) {
                return true;
            }
            // Wildcard with space: "python *" would match "python script.py"
            if (content.equals(lowerPattern + " *")) {
                return true;
            }
            // "python -*" would match "python -c 'code'"
            if (content.startsWith(lowerPattern + " -") && content.endsWith("*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Agent（sub-agent）危险判定 · 对齐 CC {@code isDangerousTaskPermission}
     * （{@code permissionSetup.ts:240-245}）。
     *
     * <p>判定：{@code normalizeLegacyToolName(toolName) === AGENT_TOOL_NAME}。任何
     * {@code Agent}（或其 legacy wire 名 {@code Task}）allow 规则都危险——auto 模式下会
     * 绕过分类器对 sub-agent 提示词的安全评估（delegation attack 防御），必须剥离。
     *
     * <p>与 Bash/PS 不同，Task 判定<strong>与 {@code ruleContent} 无关</strong>
     * （CC 形参名 {@code _ruleContent}），因此 {@code Agent} / {@code Agent(...)} 全危险。
     *
     * <p>CC 真源：permissionSetup.ts:240-245；{@code normalizeLegacyToolName} 见
     * permissionRuleParser.ts:31-33（{@code LEGACY_TOOL_NAME_ALIASES} Task→Agent）；
     * {@code AGENT_TOOL_NAME='Agent'} 见 tools/AgentTool/constants.ts:1-3。
     *
     * @param toolName    工具名（如 {@code "Agent"} / legacy {@code "Task"}）
     * @param ruleContent 规则内容（CC 不使用，保留签名对齐）
     * @return {@code true} 如果是 Agent/Task 规则
     */
    public boolean isDangerousTaskPermission(String toolName, String ruleContent) {
        return AgentToolConstants.AGENT_TOOL_NAME.equals(normalizeLegacyToolName(toolName));
    }

    /**
     * CC {@code normalizeLegacyToolName}（{@code permissionRuleParser.ts:31-33}）的
     * {@code isDangerousTaskPermission} 所需子集。
     *
     * <p>只需 legacy {@code "Task"}→{@code "Agent"} 归一（AgentTool/constants.ts:3
     * {@code LEGACY_AGENT_TOOL_NAME}）即可使 {@code === AGENT_TOOL_NAME} 判定成立；
     * 其余 legacy 别名（KillShell→TaskStop 等）不影响该比较，无需在此展开。
     */
    private static String normalizeLegacyToolName(String toolName) {
        return AgentToolConstants.LEGACY_AGENT_TOOL_NAME.equals(toolName)
                ? AgentToolConstants.AGENT_TOOL_NAME
                : toolName;
    }

    /**
     * 从权限上下文中查找所有危险 allow 规则（对齐 CC {@code findDangerousClassifierPermissions}）。
     *
     * <p>遍历 {@code ctx.alwaysAllowRules()} 的所有 source 桶，
     * 对每条规则调用 {@link #isDangerousRule} 筛选。
     *
     * <p>【S09 域】CC 的 cliAllowedTools 输入与 DangerousPermissionInfo 结构化输出
     * （ruleDisplay/sourceDisplay）属 S09 对齐范围，本方法仅提供规则列表。
     *
     * @param ctx 工具权限上下文（非 null）
     * @return 危险规则列表（可能为空，但不会为 null）
     */
    public List<PermissionRule> findDangerousRules(ToolPermissionContext ctx) {
        List<PermissionRule> dangerous = new ArrayList<>();
        for (Set<PermissionRule> rules : ctx.alwaysAllowRules().values()) {
            for (PermissionRule rule : rules) {
                if (isDangerousRule(rule)) {
                    dangerous.add(rule);
                }
            }
        }
        return dangerous;
    }

    /**
     * 进入 auto mode 时剥离危险 allow 规则并 stash 于上下文。
     *
     * <p>对齐 CC {@code stripDangerousPermissionsForAutoMode}
     * （{@code permissionSetup.ts:510-553}，CC 原名 {@code stripDangerousPermissionsForAutoMode}）。
     *
     * <h3>CC 语义逐条对照</h3>
     * <ol>
     *   <li>从 {@code alwaysAllowRules} 收集全部规则 → {@link #findDangerousRules} 判定危险集</li>
     *   <li>无危险规则 → 原样返回（stash 保持原值，CC :530-535 {@code strippedDangerousRules ?? {}}）</li>
     *   <li>逐条记录被忽略的危险规则（CC {@code logForDebugging}，:536-540）</li>
     *   <li>stash = 仅可持久化 destination（userSettings/projectSettings/localSettings/session/cliArg）的危险规则
     *       —— 镜像 {@code removeDangerousPermissions} 的 source 过滤，保证 stash == 实际被剥离的规则
     *       （CC :541-548 注释 "Mirror removeDangerousPermissions' source filter"；源过滤见 :456-466
     *       {@code isPermissionUpdateDestination}）</li>
     *   <li>按 destination 分组执行 {@code removeRules}（CC {@code removeDangerousPermissions} :472-503）
     *       —— 非可持久化源（flagSettings/policySettings/command）的规则<strong>不剥离</strong></li>
     *   <li>返回剥离后的上下文，{@code strippedDangerousRules} = stash（CC :549-552）</li>
     * </ol>
     *
     * <p>剥离后 stash 随上下文传播（CC-PERM-25），退出 auto 时由
     * {@link #restoreDangerousPermissions} 消费恢复。
     * <p>返回的 ctx 按 CC spread 语义（:549-552）保留剥离前上下文的全部字段——
     * 3 个上下文标志位（{@code shouldAvoidPermissionPrompts} /
     * {@code awaitAutomatedChecksBeforeDialog} / {@code prePlanMode}）从原 ctx 保真。
     *
     * @param ctx 当前工具权限上下文（非 null）
     * @return 剥离后的新上下文（stash 已写入 strippedDangerousRules；无危险规则时原样返回）
     */
    public ToolPermissionContext stripDangerousPermissionsForAutoMode(ToolPermissionContext ctx) {
        List<PermissionRule> dangerous = findDangerousRules(ctx);
        if (dangerous.isEmpty()) {
            // CC :530-535 —— 无危险规则时返回原 context（stash 保持原值，不覆盖）
            if (log.isDebugEnabled()) {
                log.debug("auto 模式剥离：未发现危险规则，stash 保持原值");
            }
            return ctx;
        }

        if (log.isInfoEnabled()) {
            log.info("auto 模式剥离：发现 {} 条危险规则（绕过分类器）", dangerous.size());
        }
        for (PermissionRule rule : dangerous) {
            // CC :536-540 logForDebugging("Ignoring dangerous permission X from Y")
            if (log.isDebugEnabled()) {
                log.debug("忽略危险权限规则 {}（来自 {}，会绕过分类器）",
                        displayRule(rule), rule.source());
            }
        }

        // CC :541-548 —— 镜像 removeDangerousPermissions 的 source 过滤：stash == 实际被剥离的规则
        // isPermissionUpdateDestination（CC :456-466）：仅 userSettings/projectSettings/
        // localSettings/session/cliArg 可持久化；flagSettings/policySettings/command 不参与
        Map<PermissionRuleSource, Set<PermissionRule>> stash =
                new EnumMap<>(PermissionRuleSource.class);
        for (PermissionRule rule : dangerous) {
            if (isStashableSource(rule.source())) {
                stash.computeIfAbsent(rule.source(), k -> new LinkedHashSet<>()).add(rule);
            }
        }

        // CC removeDangerousPermissions（:491-499）：按 destination 分组 removeRules，
        // 每条 update 显式带 behavior:'allow'（CC :497）。非可持久化源的规则不剥离
        // （保留在上下文中，与 CC 一致）。
        ToolPermissionContext stripped = ctx;
        for (Map.Entry<PermissionRuleSource, Set<PermissionRule>> entry : stash.entrySet()) {
            stripped = applier.apply(
                    new PermissionUpdate.RemoveRules(
                            toDestination(entry.getKey()),
                            List.copyOf(entry.getValue()),
                            PermissionBehavior.ALLOW),
                    stripped);
        }

        // Applier 重建 ctx 时 strippedDangerousRules 恒为 Map.of()，且 3 个上下文标志位
        // （shouldAvoidPermissionPrompts/awaitAutomatedChecksBeforeDialog/prePlanMode）
        // 被 rebuildCtx 硬编码重置（S16/S17 域，本类不修改）。CC spread 语义
        // （permissionSetup.ts:549-552 {...removeDangerousPermissions(context, ...), strippedDangerousRules: stripped}）
        // 保留原 context 全字段 → stash 补回，标志位从剥离前 ctx 保真
        return withStash(stripped, ctx, stash);
    }

    /**
     * 退出 auto mode 时按 stash 恢复被剥离的危险 allow 规则。
     *
     * <p>对齐 CC {@code restoreDangerousPermissions}
     * （{@code permissionSetup.ts:561-579}，CC 原名 {@code restoreDangerousPermissions}）。
     *
     * <h3>CC 语义逐条对照</h3>
     * <ol>
     *   <li>stash 为空（未剥离过 / 已恢复过）→ 原样返回，<strong>二次调用 no-op</strong>（CC :564-567）</li>
     *   <li>逐源执行 {@code addRules}（behavior=allow）恢复规则（CC :569-577）</li>
     *   <li>返回 {@code strippedDangerousRules: undefined} —— 清空 stash（CC :578），
     *       使再次退出 auto 不产生重复恢复</li>
     * </ol>
     * <p>恢复来源为 {@code ctx.strippedDangerousRules()} 自带的 stash，恢复后清空 —— 天然幂等。
     *
     * @param ctx 当前工具权限上下文（非 null）
     * @return 恢复后的新上下文（stash 已清空；无 stash 时原样返回）
     */
    public ToolPermissionContext restoreDangerousPermissions(ToolPermissionContext ctx) {
        Map<PermissionRuleSource, Set<PermissionRule>> stash = ctx.strippedDangerousRules();
        if (stash == null || stash.isEmpty()) {
            // CC :564-567 —— 无 stash 直接返回（二次恢复 no-op）
            if (log.isDebugEnabled()) {
                log.debug("退出 auto 模式：stash 为空，无需恢复（幂等 no-op）");
            }
            return ctx;
        }

        int total = 0;
        for (Set<PermissionRule> rules : stash.values()) {
            total += rules.size();
        }
        if (log.isInfoEnabled()) {
            log.info("退出 auto 模式：按 stash 恢复 {} 条危险规则", total);
        }

        // CC :569-577 —— 逐源 addRules（behavior=allow）恢复
        ToolPermissionContext restored = ctx;
        for (Map.Entry<PermissionRuleSource, Set<PermissionRule>> entry : stash.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("恢复 source={} 的 {} 条规则: {}",
                        entry.getKey(), entry.getValue().size(),
                        entry.getValue().stream().map(DangerousPatternDetector::displayRule).toList());
            }
            restored = applier.apply(
                    new PermissionUpdate.AddRules(
                            toDestination(entry.getKey()),
                            List.copyOf(entry.getValue()),
                            PermissionBehavior.ALLOW),
                    restored);
        }

        // CC :578 —— { ...result, strippedDangerousRules: undefined }：清空 stash。
        // CC spread 保留 result（= 恢复前 ctx，其标志位已由 strip 保真）全字段 →
        // 3 个标志位从恢复前 ctx 取回，其余字段取 Applier 重建结果
        return withStash(restored, ctx, Map.of());
    }

    // ========================================================================
    // 内部工具
    // ========================================================================

    /**
     * 重建 {@link ToolPermissionContext}，替换 {@code strippedDangerousRules}，其余字段取
     * {@code rebuilt}，仅 3 个上下文标志位（{@code shouldAvoidPermissionPrompts} /
     * {@code awaitAutomatedChecksBeforeDialog} / {@code prePlanMode}）从 {@code original} 保真。
     *
     * <p>对齐 CC spread 语义（{@code permissionSetup.ts:549-552} / {@code :578}
     * {@code {...context, ...}}）：CC 重建只覆盖被更新字段，原 context 其余字段全部保留。
     *
     * <p>Applier（S16/S17 共享写集域，本类不修改）的 {@code rebuildCtx} 硬编码
     * {@code Map.of(), false, false, null}（strippedDangerousRules + 3 标志位全重置），
     * 因此 {@code rebuilt} 上的 3 个标志位不可信，必须从剥离/恢复前的 {@code original}
     * 取回——否则 auto 模式 + 存在危险规则 + 异步子 agent（B-2 flag）/coordinator worker
     * （CC runAgent.ts:457-464 flag）场景下剥离后标志位被重置为 false，
     * "自动拒绝弹窗 / 等待自动化检查"语义丢失（与 H14/H13 v3 修复的同类问题）。
     *
     * @param rebuilt  Applier removeRules/addRules 重建的上下文（规则桶等字段可信，标志位除外）
     * @param original 剥离/恢复前的原始上下文（3 个标志位的真源）
     * @param stash    新的 strippedDangerousRules（剥离时写入 / 恢复时清空）
     */
    private static ToolPermissionContext withStash(
            ToolPermissionContext rebuilt,
            ToolPermissionContext original,
            Map<PermissionRuleSource, Set<PermissionRule>> stash) {
        return new ToolPermissionContext(
                rebuilt.mode(),
                rebuilt.alwaysAllowRules(),
                rebuilt.alwaysDenyRules(),
                rebuilt.alwaysAskRules(),
                rebuilt.additionalWorkingDirectories(),
                rebuilt.isBypassPermissionsModeAvailable(),
                rebuilt.isAutoModeAvailable(),
                stash,
                original.shouldAvoidPermissionPrompts(),
                original.awaitAutomatedChecksBeforeDialog(),
                original.prePlanMode());
    }

    /**
     * CC {@code isPermissionUpdateDestination}（{@code permissionSetup.ts:456-466}）。
     *
     * <p>可持久化 destination = userSettings / projectSettings / localSettings / session / cliArg。
     * flagSettings / policySettings / command 不可持久化 → 不参与剥离与 stash
     * （CC {@code removeDangerousPermissions} :481-490 跳过）。
     */
    private static boolean isStashableSource(PermissionRuleSource source) {
        return source == PermissionRuleSource.USER_SETTINGS
                || source == PermissionRuleSource.PROJECT_SETTINGS
                || source == PermissionRuleSource.LOCAL_SETTINGS
                || source == PermissionRuleSource.SESSION
                || source == PermissionRuleSource.CLI_ARG;
    }

    /**
     * {@link PermissionRuleSource} → {@link PermissionUpdate.Destination} 一对一映射
     * （与 {@link PermissionUpdateApplier#mapDestination} 互逆）。
     */
    private static PermissionUpdate.Destination toDestination(PermissionRuleSource source) {
        return switch (source) {
            case USER_SETTINGS    -> PermissionUpdate.Destination.USER_SETTINGS;
            case PROJECT_SETTINGS -> PermissionUpdate.Destination.PROJECT_SETTINGS;
            case LOCAL_SETTINGS   -> PermissionUpdate.Destination.LOCAL_SETTINGS;
            case CLI_ARG          -> PermissionUpdate.Destination.CLI_ARG;
            case SESSION          -> PermissionUpdate.Destination.SESSION;
            // 不可持久化源（isStashableSource 已过滤），不会到达此处
            case FLAG_SETTINGS, POLICY_SETTINGS, COMMAND ->
                    throw new IllegalArgumentException(
                            "source 不可持久化，无法映射为 PermissionUpdate.Destination: " + source);
        };
    }

    /**
     * 规则展示文本（日志用，对齐 CC {@code DangerousPermissionInfo.ruleDisplay}：
     * {@code "Bash(...)"} 或 {@code "Bash(*)"}）。
     */
    private static String displayRule(PermissionRule rule) {
        String content = rule.ruleValue().ruleContent();
        return content == null
                ? rule.ruleValue().toolName() + "(*)"
                : rule.ruleValue().toolName() + "(" + content + ")";
    }
}
