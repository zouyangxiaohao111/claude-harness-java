package com.nexusai.application.agent.subagent;

import java.util.List;

/**
 * ExploreAgentPrompt · 对齐 CC tools/AgentTool/built-in/exploreAgent.ts (system prompt 部分).
 *
 * <p>L1 语义: Explore 内置 agent 的系统 prompt 模板构建器。
 * 重要特点:
 * <ul>
 *   <li>READ-ONLY 模式 — 禁止 Edit/Write/redirect/heredoc</li>
 *   <li>工具名通过参数注入(Bash/Glob/Grep/FileRead 等)</li>
 *   <li>embedded 模式 (ant-native) 用 find/grep via Bash,否则用 Glob/Grep 工具</li>
 *   <li>输出要求:为 fast agent,优先并行 grep/read</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #render(String, String, String, String, boolean)} (bashToolName, globToolName, grepToolName, fileReadToolName, embedded) → String prompt</li>
 *   <li><b>A2 Golden Trace</b>: 含 READ-ONLY 警告 + glob/grep guidance + 只读 bash 白名单</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: toolName null → 占位;embedded=true → 用 find/grep via Bash</li>
 *   <li><b>A5 业务场景</b>: 用户问 '找一下这个 src 里有哪些公共方法' → Explore agent 找到并报告</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS template literal with conditional segments →
 * Java String.format + ternary 字符串分支 (与 CC ternary 等价);
 * 中文化注入使 caller 控制 tool name 渲染。
 */
public final class ExploreAgentPrompt {

    public static final String WHEN_TO_USE =
        "Fast agent specialized for exploring codebases. Use this when you need to quickly find files by patterns " +
        "(eg, \"src/components/**/*.tsx\"), search code for keywords (eg, \"API endpoints\"), or answer questions " +
        "about the codebase (eg, \"how do API endpoints work?\"). When calling this agent, specify the desired " +
        "thoroughness level: \"quick\" for basic searches, \"medium\" for moderate exploration, or " +
        "\"very thorough\" for comprehensive analysis across multiple locations and naming conventions.";

    private ExploreAgentPrompt() {}

    /**
     * Render the Explore agent system prompt with parameterized tool names.
     *
     * @param bashToolName   e.g. "Bash"
     * @param globToolName   e.g. "Glob" (only used if embedded=false)
     * @param grepToolName   e.g. "Grep" (only used if embedded=false)
     * @param fileReadToolName e.g. "Read"
     * @param embedded       true if ant-native (find/grep via Bash instead of Glob/Grep tools)
     */
    public static String render(
        String bashToolName, String globToolName, String grepToolName,
        String fileReadToolName, boolean embedded) {

        String globGuidance = embedded
            ? "- Use `find` via " + bashToolName + " for broad file pattern matching"
            : "- Use " + globToolName + " for broad file pattern matching";
        String grepGuidance = embedded
            ? "- Use `grep` via " + bashToolName + " for searching file contents with regex"
            : "- Use " + grepToolName + " for searching file contents with regex";
        String searchSuffix = embedded ? ", grep" : "";
        String searchReadOnly = "ls, git status, git log, git diff, find" + searchSuffix + ", cat, head, tail";

        return """
            You are a file search specialist for NexusAI, an open-source desktop assistant implementing the Claude Code agent harness. You excel at thoroughly navigating and exploring codebases.

            === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
            This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from:
            - Creating new files (no Write, touch, or file creation of any kind)
            - Modifying existing files (no Edit operations)
            - Deleting files (no rm or deletion)
            - Moving or copying files (no mv or cp)
            - Creating temporary files anywhere, including /tmp
            - Using redirect operators (>, >>, |) or heredocs to write to files
            - Running ANY commands that change system state

            Your role is EXCLUSIVELY to search and analyze existing code. You do NOT have access to file editing tools - attempting to edit files will fail.

            Your strengths:
            - Rapidly finding files using glob patterns
            - Searching code and text with powerful regex patterns
            - Reading and analyzing file contents

            Guidelines:
            %s
            %s
            - Use %s when you know the specific file path you need to read
            - Use %s ONLY for read-only operations (%s)
            - NEVER use %s for: mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install, or any file creation/modification
            - Adapt your search approach based on the thoroughness level specified by the caller
            - Communicate your final report directly as a regular message - do NOT attempt to create files

            NOTE: You are meant to be a fast agent that returns output as quickly as possible. In order to achieve this you must:
            - Make efficient use of the tools that you have at your disposal: be smart about how you search for files and implementations
            - Wherever possible you should try to spawn multiple parallel tool calls for grepping and reading files

            Complete the user's search request efficiently and report your findings clearly.
            """.formatted(globGuidance, grepGuidance, fileReadToolName, bashToolName,
                searchReadOnly, bashToolName);
    }

    /**
     * Disallowed tools list for the Explore agent (CC built-in).
     *
     * <p>对齐 CC exploreAgent.ts:67-73 disallowedTools + 工具名常量:
     * AGENT_TOOL_NAME='Agent' / EXIT_PLAN_MODE_TOOL_NAME='ExitPlanMode' /
     * FILE_EDIT_TOOL_NAME='Edit' (非 'FileEdit') / FILE_WRITE_TOOL_NAME='Write' (非 'FileWrite') /
     * NOTEBOOK_EDIT_TOOL_NAME='NotebookEdit'.
     *
     * <p>WHY: 旧值 'FileEdit'/'FileWrite' 在工具池中不存在 (CC 工具名是 'Edit'/'Write'),
     * disallowedTools 永不命中 = 静默失效. 修正为 CC 真值.
     */
    public static List<String> disallowedTools() {
        return List.of("Agent", "ExitPlanMode", "Edit", "Write", "NotebookEdit");
    }
}
