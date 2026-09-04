package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.PermissionMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentToolUtils · 对齐 CC {@code Open-ClaudeCode/src/tools/AgentTool/agentToolUtils.ts}
 * 的 {@code filterToolsForAgent} (ts:70-116) 与 {@code constants/tools.ts:36-88} 4 个工具集合,
 * 及 {@code utils/toolPool.ts} 的 coordinator 过滤族
 * ({@code isPrActivitySubscriptionTool} :16-18 / {@code applyCoordinatorToolFilter} :35-41,
 * 见 {@link #applyCoordinatorToolFilter}).
 *
 * <p><b>WHY (ATS-14)</b>: 子 Agent (尤其 async) 的工具池若不按 CC 4-SET 过滤,
 * async agent 会拿到 Bash/Write 等无人监管工具 = 安全风险; 内置 agent 会拿到
 * TaskOutput/ExitPlanMode 等主线程抽象 → 语义错乱.
 *
 * <p>CC 真源 (Pattern #9):
 * <ul>
 *   <li>{@code constants/tools.ts:36-46} ALL_AGENT_DISALLOWED_TOOLS</li>
 *   <li>{@code constants/tools.ts:48-50} CUSTOM_AGENT_DISALLOWED_TOOLS</li>
 *   <li>{@code constants/tools.ts:55-71} ASYNC_AGENT_ALLOWED_TOOLS (16 项)</li>
 *   <li>{@code constants/tools.ts:77-88} IN_PROCESS_TEAMMATE_ALLOWED_TOOLS</li>
 *   <li>{@code agentToolUtils.ts:70-116} filterToolsForAgent (mcp__ 白名单 /
 *       plan-mode ExitPlanMode / ALL / CUSTOM / async 白名单 5 段)</li>
 * </ul>
 */
public final class AgentToolUtils {

    private static final Logger log = LoggerFactory.getLogger(AgentToolUtils.class);

    /**
     * CC constants/tools.ts:48-50 · 自定义 agent 额外 disallow（当前 = ALL_AGENT_DISALLOWED）。
     * <p>[B6 DEL-WFB-03] ALL_AGENT_DISALLOWED 双定义合并为单一权威 —— 权威在
     * {@link ToolNameConstants#ALL_AGENT_DISALLOWED_TOOLS}（静态工厂构建，条件分支可测试），
     * 本类不再定义，仅 {@code Set.copyOf} 引用。
     */
    public static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS =
        Set.copyOf(ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS);

    /**
     * CC constants/tools.ts:55-71 · async agent 白名单 (17 项).
     * <p>注: CC SHELL_TOOL_NAMES 含 Bash+PowerShell, Java 端对应 ToolNameConstants.
     * <p>[PDF 分页子代理修复] vision_analyze 补进 async 白名单：CC 异步 worker 承担"读图/PDF"
     *   靠 Read（在列）；Java 文本模型（deepseek）看 PDF 页图/附件图靠 vision_analyze 代理视觉
     *   模型——不加则 fork 异步子代理（>20 页 PDF NEEDS_SUBAGENT 分页）注册表剔除 vision_analyze →
     *   dispatch/schema 均无 → No such tool。isReadOnly + isConcurrencySafe（VisionAnalyzeTool）
     *   → 异步 worker 使用安全。
     */
    public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS = Set.of(
        ToolNameConstants.FILE_READ_TOOL_NAME,          // Read
        ToolNameConstants.WEB_SEARCH_TOOL_NAME,         // WebSearch
        ToolNameConstants.TODO_WRITE_TOOL_NAME,         // TodoWrite
        ToolNameConstants.GREP_TOOL_NAME,               // Grep
        ToolNameConstants.WEB_FETCH_TOOL_NAME,          // WebFetch
        ToolNameConstants.GLOB_TOOL_NAME,               // Glob
        ToolNameConstants.BASH_TOOL_NAME,               // Bash (SHELL_TOOL_NAMES)
        ToolNameConstants.POWER_SHELL_TOOL_NAME,        // PowerShell (SHELL_TOOL_NAMES)
        ToolNameConstants.FILE_EDIT_TOOL_NAME,          // Edit
        ToolNameConstants.FILE_WRITE_TOOL_NAME,         // Write
        ToolNameConstants.NOTEBOOK_EDIT_TOOL_NAME,      // NotebookEdit
        ToolNameConstants.SKILL_TOOL_NAME,              // Skill
        ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME,   // SyntheticOutput
        ToolNameConstants.TOOL_SEARCH_TOOL_NAME,        // ToolSearch
        ToolNameConstants.ENTER_WORKTREE_TOOL_NAME,     // EnterWorktree
        ToolNameConstants.EXIT_WORKTREE_TOOL_NAME,      // ExitWorktree
        ToolNameConstants.VISION_ANALYZE_TOOL_NAME      // vision_analyze（代理视觉模型读图/PDF 页）
    );

    /**
     * CC constants/tools.ts:77-88 · 仅 in-process teammate 可用工具。
     * <p>[B6 D-27] cron 3 项按 {@code feature('AGENT_TRIGGERS')} 条件放行（tools.ts:83-86）。
     * 常量用默认 {@code agentTriggers=true}（对齐 CC 生产 bundle G15 编译 true）——保持既有
     * 默认含 Cron* 行为不变；条件分支（关闭 → 不含 Cron*）由 {@link #buildInProcessTeammateAllowed}
     * 显式表达。
     */
    public static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED_TOOLS =
        buildInProcessTeammateAllowed(true);

    /**
     * 镜像 CC constants/tools.ts:77-88 条件集合的静态工厂。
     *
     * @param agentTriggers {@code feature('AGENT_TRIGGERS')}（tools.ts:83-86）· true 放行 Cron* 3 项
     * @return 不可变有序集合
     */
    public static Set<String> buildInProcessTeammateAllowed(boolean agentTriggers) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(ToolNameConstants.TASK_CREATE_TOOL_NAME);
        set.add(ToolNameConstants.TASK_GET_TOOL_NAME);
        set.add(ToolNameConstants.TASK_LIST_TOOL_NAME);
        set.add(ToolNameConstants.TASK_UPDATE_TOOL_NAME);
        set.add(ToolNameConstants.SEND_MESSAGE_TOOL_NAME);
        if (agentTriggers) {
            set.add(ToolNameConstants.CRON_CREATE_TOOL_NAME);
            set.add(ToolNameConstants.CRON_DELETE_TOOL_NAME);
            set.add(ToolNameConstants.CRON_LIST_TOOL_NAME);
        }
        if (log.isDebugEnabled()) {
            log.debug("[AgentToolUtils] buildInProcessTeammateAllowed(agentTriggers={}) 生成集合 size={}（含 Cron 3 项={}）· CC constants/tools.ts:77-88",
                agentTriggers, set.size(), agentTriggers);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * CC constants/tools.ts:107-112 · coordinator 模式专用白名单 —
     * 仅输出 + agent 管理工具给 coordinator (worker 侧由 filterToolsForAgent 过滤).
     *
     * <p>[D session] 补全 constants/tools.ts 第 5 集 (C session 已建前 4 集).
     * Java 端 coordinator mode 未启用 (feature flag 关闭), 集合保留为 CC 结构对齐.
     */
    public static final Set<String> COORDINATOR_MODE_ALLOWED_TOOLS = Set.of(
        AgentToolConstants.AGENT_TOOL_NAME,               // Agent
        ToolNameConstants.TASK_STOP_TOOL_NAME,            // TaskStop
        ToolNameConstants.SEND_MESSAGE_TOOL_NAME,         // SendMessage
        ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME      // StructuredOutput
    );

    /**
     * CC toolPool.ts:11-14 · MCP 工具名后缀（PR 活动订阅）。轻量编排动作，
     * coordinator 直接调用而非委派 worker。按后缀匹配（MCP server 名前缀可能变化）。
     */
    public static final List<String> PR_ACTIVITY_TOOL_SUFFIXES =
        List.of("subscribe_pr_activity", "unsubscribe_pr_activity");

    /**
     * CC toolPool.ts:16-18 {@code isPrActivitySubscriptionTool} · 是否 PR 活动订阅工具（按后缀匹配）。
     *
     * @param name 工具名（可为 null → false）
     * @return 命中任一 {@link #PR_ACTIVITY_TOOL_SUFFIXES} 后缀 → true
     */
    public static boolean isPrActivitySubscriptionTool(String name) {
        if (name == null) {
            return false;
        }
        for (String suffix : PR_ACTIVITY_TOOL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CC toolPool.ts:35-41 {@code applyCoordinatorToolFilter} · 过滤工具数组为 coordinator 模式允许集。
     *
     * <p>REPL 路径（{@code mergeAndFilterTools} toolPool.ts:55-79）与 headless 路径
     * （{@code main.tsx:1872-1877}）共用，保持两端同步。PR 活动订阅工具恒放行
     * （订阅管理属编排）。保留原顺序（CC {@code tools.filter} 保序）。
     *
     * @param tools 待过滤工具列表（null/空 → 空列表）
     * @return 仅含 {@link #COORDINATOR_MODE_ALLOWED_TOOLS} ∪ PR 活动订阅工具 的列表
     */
    public static List<Tool> applyCoordinatorToolFilter(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return tools == null ? List.of() : new ArrayList<>();
        }
        List<Tool> result = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            if (tool == null) {
                continue;
            }
            String name = tool.name();
            if (COORDINATOR_MODE_ALLOWED_TOOLS.contains(name)
                    || isPrActivitySubscriptionTool(name)) {
                result.add(tool);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[AgentToolUtils] applyCoordinatorToolFilter 输入 {} 工具 → 输出 {} 工具"
                    + "（COORDINATOR_MODE_ALLOWED_TOOLS ∪ PR 活动订阅后缀）· CC toolPool.ts:35-41",
                tools.size(), result.size());
        }
        return result;
    }

    private AgentToolUtils() {
        // 常量 + 静态过滤工具容器
    }

    /**
     * 对齐 CC {@code filterToolsForAgent} (agentToolUtils.ts:70-116) 的 5 段过滤:
     * <ol>
     *   <li>{@code mcp__} 前缀工具全放行 (CC :83-85)</li>
     *   <li>plan 模式下 ExitPlanMode 放行 (CC :88-93, in-process teammate)</li>
     *   <li>ALL_AGENT_DISALLOWED_TOOLS 拦截 (CC :94-96)</li>
     *   <li>非内置 agent + CUSTOM_AGENT_DISALLOWED_TOOLS 拦截 (CC :97-99)</li>
     *   <li>async + 不在 ASYNC_AGENT_ALLOWED_TOOLS 白名单 → 拦截 (CC :100-113)</li>
     * </ol>
     * 随后叠加 AgentDefinition.disallowedTools 精确剔除.
     *
     * <p>偏离注: CC :101-111 in-process teammate 例外 (Agent 工具 + task 工具放行)
     * 依赖 isAgentSwarmsEnabled() + isInProcessTeammate(), Java 端 swarm 未启用,
     * 该分支暂不实现 (记入 J.md 遗憾).
     *
     * @param tools            可用工具列表
     * @param isBuiltIn        agent source == built-in (CC :144)
     * @param isAsync          是否 async agent (CC :100)
     * @param permissionMode   权限模式; plan → ExitPlanMode 放行
     * @param disallowedTools  agent 定义 disallowedTools (CC :150-155)
     * @return 过滤后的工具列表 (保持原顺序)
     */
    public static List<Tool> filterToolsForAgent(
            List<Tool> tools,
            boolean isBuiltIn,
            boolean isAsync,
            PermissionMode permissionMode,
            Optional<List<String>> disallowedTools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Set<String> disallowed = disallowedTools == null || disallowedTools.isEmpty()
            ? Set.of()
            : Set.copyOf(disallowedTools.get());
        List<Tool> result = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            String name = tool.name();
            // (1) mcp__ 工具全放行 (CC :83-85)
            if (name.startsWith("mcp__")) {
                result.add(tool);
                continue;
            }
            // (2) plan 模式 ExitPlanMode 放行 (CC :88-93) — 绕过 ALL + async 双过滤
            if (ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME.equals(name)
                    && permissionMode == PermissionMode.PLAN) {
                result.add(tool);
                continue;
            }
            // (3) ALL_AGENT_DISALLOWED_TOOLS (CC :94-96) — 单一权威 ToolNameConstants
            if (ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS.contains(name)) {
                continue;
            }
            // (4) 非内置 + CUSTOM_AGENT_DISALLOWED_TOOLS (CC :97-99)
            if (!isBuiltIn && CUSTOM_AGENT_DISALLOWED_TOOLS.contains(name)) {
                continue;
            }
            // (5) async 白名单 (CC :100-113)
            if (isAsync && !ASYNC_AGENT_ALLOWED_TOOLS.contains(name)) {
                continue;
            }
            result.add(tool);
        }
        // AgentDefinition.disallowedTools 精确剔除 (CC resolveAgentTools :150-160)
        if (!disallowed.isEmpty()) {
            result.removeIf(t -> disallowed.contains(t.name()));
        }
        if (log.isDebugEnabled()) {
            log.debug("[AgentToolUtils] filterToolsForAgent(isBuiltIn={}, isAsync={}, mode={}) 输入 {} 工具 → 输出 {} 工具（ALL/CUSTOM/async 白名单过滤）· CC agentToolUtils.ts:70-116",
                isBuiltIn, isAsync, permissionMode, tools.size(), result.size());
        }
        return result;
    }
}
