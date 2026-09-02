package com.nexusai.infra.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * AutoModeAllowlist · 对齐 CC utils/permissions/classifierDecision.ts SAFE_YOLO_ALLOWLISTED_TOOLS.
 *
 * <p>L1 语义: auto mode classifier 跳过安全工具的 allowlist。
 * Read-only + 任务管理 + plan mode + swarm coordination + 内部 classifier。
 * Write/Edit 不在此 list (由 acceptEdits fast path 处理)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: SAFE_ALLOWLIST Set (24 项) + isAllowlistedTool(name) 静态方法</li>
 *   <li><b>A2 Golden Trace</b>: Read/Glob/Grep/LSP/Task* 等在 list;Write/Edit/Bash 不在</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: null name→false;empty string→false;unknown tool→false</li>
 *   <li><b>A5 业务场景</b>: Read tool 在 auto mode → skip classifier check → 直接 allow</li>
 * </ul>
 *
 * <p>L3 升级: TS Set literal → Java Set.of immutable;
 * TS tool name string constant → Java String literal;
 * TS has(name) → Java Set.contains.
 */
public final class AutoModeAllowlist {

    public static final Set<String> SAFE_ALLOWLIST = Set.of(
        // Read-only file operations
        "Read", "FileReadTool",
        "Grep", "Glob", "LSP", "LSPTool", "ToolSearch",
        // [B6] 删除陈旧裸名 ListMcpResources（缺 Tool 后缀，与真工具名不匹配）；保留含后缀真名
        "ListMcpResourcesTool", "ReadMcpResourceTool",
        // Task management (metadata only)
        "TodoWrite",
        "TaskCreate", "TaskGet", "TaskUpdate", "TaskList", "TaskStop", "TaskOutput",
        // Plan mode / UI
        "AskUserQuestion",
        "EnterPlanMode", "ExitPlanMode",
        // Swarm coordination
        "TeamCreate", "TeamDelete",
        "SendMessage",
        // Workflow
        "Workflow",
        // Misc safe
        "Sleep",
        // Ant-only safe tools
        "TerminalCapture",
        "VerifyPlanExecution",
        // Internal classifier tool
        "YoloClassifier"
    );

    private AutoModeAllowlist() {}

    public static boolean isAllowlistedTool(String toolName) {
        if (toolName == null || toolName.isEmpty()) return false;
        return SAFE_ALLOWLIST.contains(toolName);
    }
}
