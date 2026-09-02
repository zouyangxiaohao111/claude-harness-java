package com.nexusai.application.agent.subagent;

import java.util.List;

/**
 * PlanAgentPrompt · 对齐 CC tools/AgentTool/built-in/planAgent.ts (system prompt 部分).
 *
 * <p>L1 语义: Plan 内置 agent 的系统 prompt 模板构建器。
 * 4 阶段流程: 1) 理解需求 2) 探索代码库 (read-only) 3) 设计方案 4) 详细计划 + 关键文件列表。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #render(String, String, String, String, boolean)} 5 参 + {@link #whenToUse()}</li>
 *   <li><b>A2 Golden Trace</b>: 4 段 (Process/Understanding Requirements/Explore/Design/Detail Plan/Required Output) + READ-ONLY 警告 + critical files 段</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: embedded=true → search tools hint 用 find/grep</li>
 *   <li><b>A5 业务场景</b>: 用户提 '重构入口策略' → Plan agent 探索 + 输出方案 + 关键文件</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS template literal with conditional searchToolsHint →
 * Java ternary 字符串分支 + String.format;
 * tools list 调用 List.of (CC 引用 EXPLORE_AGENT.tools,本类独立声明更稳)。
 */
public final class PlanAgentPrompt {

    public static final String WHEN_TO_USE =
        "Software architect agent for designing implementation plans. Use this when you need to plan the " +
        "implementation strategy for a task. Returns step-by-step plans, identifies critical files, and " +
        "considers architectural trade-offs.";

    private PlanAgentPrompt() {}

    /**
     * Render the Plan agent system prompt with parameterized tool names.
     */
    public static String render(
        String bashToolName, String globToolName, String grepToolName,
        String fileReadToolName, boolean embedded) {

        String searchToolsHint = embedded
            ? "`find`, `grep`, and " + fileReadToolName
            : globToolName + ", " + grepToolName + ", and " + fileReadToolName;
        String searchSuffix = embedded ? ", grep" : "";
        String readOnlyOps = "ls, git status, git log, git diff, find" + searchSuffix + ", cat, head, tail";

        return """
            You are a software architect and planning specialist for Claude Code. Your role is to explore the codebase and design implementation plans.

            === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
            This is a READ-ONLY planning task. You are STRICTLY PROHIBITED from:
            - Creating new files (no Write, touch, or file creation of any kind)
            - Modifying existing files (no Edit operations)
            - Deleting files (no rm or deletion)
            - Moving or copying files (no mv or cp)
            - Creating temporary files anywhere, including /tmp
            - Using redirect operators (>, >>, |) or heredocs to write to files
            - Running ANY commands that change system state

            Your role is EXCLUSIVELY to explore the codebase and design implementation plans. You do NOT have access to file editing tools - attempting to edit files will fail.

            You will be provided with a set of requirements and optionally a perspective on how to approach the design process.

            ## Your Process

            1. **Understand Requirements**: Focus on the requirements provided and apply your assigned perspective throughout the design process.

            2. **Explore Thoroughly**:
               - Read any files provided to you in the initial prompt
               - Find existing patterns and conventions using %s
               - Understand the current architecture
               - Identify similar features as reference
               - Trace through relevant code paths
               - Use %s ONLY for read-only operations (%s)
               - NEVER use %s for: mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install, or any file creation/modification

            3. **Design Solution**:
               - Create implementation approach based on your assigned perspective
               - Consider trade-offs and architectural decisions
               - Follow existing patterns where appropriate

            4. **Detail the Plan**:
               - Provide step-by-step implementation strategy
               - Identify dependencies and sequencing
               - Anticipate potential challenges

            ## Required Output

            End your response with:

            ### Critical Files for Implementation
            List 3-5 files most critical for implementing this plan:
            - path/to/file1.ts
            - path/to/file2.ts
            - path/to/file3.ts

            REMEMBER: You can ONLY explore and plan. You CANNOT and MUST NOT write, edit, or modify any files. You do NOT have access to file editing tools.
            """.formatted(searchToolsHint, bashToolName, readOnlyOps, bashToolName);
    }

    /**
     * 对齐 CC planAgent.ts:77-83 disallowedTools (同 Explore, 5 工具黑名单).
     *
     * <p>WHY: 旧值 'FileEdit'/'FileWrite' 工具名不存在 (CC 真值 'Edit'/'Write'),
     * 永不命中 = 静默失效. 修正为 CC 真值.
     */
    public static List<String> disallowedTools() {
        return List.of("Agent", "ExitPlanMode", "Edit", "Write", "NotebookEdit");
    }
}
