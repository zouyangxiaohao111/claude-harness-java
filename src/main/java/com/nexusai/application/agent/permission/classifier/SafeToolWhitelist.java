package com.nexusai.application.agent.permission.classifier;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 安全工具白名单 · 对齐 CC {@code classifierDecision.ts:56-94}
 * {@code SAFE_YOLO_ALLOWLISTED_TOOLS}（26 项 = 22 固定 + 4 条件）。
 *
 * <p>这些工具在 auto mode 下直接 allow（decisionReason = Mode(AUTO)），不需要分类器判断。
 * 只读/搜索/任务元数据/plan 模式/swarm 协调工具，不会修改文件系统或执行任意代码。
 * Write/Edit 不在此 list —— 由 acceptEdits fast-path 处理（CC :53-55 注释）。
 *
 * <h2>[S10] allowlist 对齐（T07 R5/F7；EV-018/EV-034/EV-043）</h2>
 * <p>旧 Java 9 项（read_file/glob/Skill/TaskList/TaskGet/TaskOutput/TaskCreate/TaskUpdate/
 * TodoWrite）与 CC 26 项偏移：多 {@code Skill}（CC 无 skill 免检概念）、缺 17 项。
 * S10 按 CC 26 项全量对齐 —— CC 工具名 → Java 注册工具名（{@code Tool#name()}）映射：
 *
 * <table>
 *   <caption>CC 22 固定项 → Java 映射</caption>
 *   <tr><th>CC 常量（classifierDecision.ts）</th><th>Java 注册名</th><th>Java 工具</th></tr>
 *   <tr><td>{@code FILE_READ_TOOL_NAME} 'Read'（:58）</td><td>read_file</td><td>ReadFileTool</td></tr>
 *   <tr><td>{@code GREP_TOOL_NAME} 'Grep'（:60）</td><td>grep</td><td>GrepTool</td></tr>
 *   <tr><td>{@code GLOB_TOOL_NAME} 'Glob'（:61）</td><td>glob</td><td>GlobTool</td></tr>
 *   <tr><td>{@code LSP_TOOL_NAME} 'LSP'（:62）</td><td>LSP</td><td>LspTool</td></tr>
 *   <tr><td>{@code TOOL_SEARCH_TOOL_NAME} 'ToolSearch'（:63）</td><td>ToolSearch</td><td>ToolSearchTool</td></tr>
 *   <tr><td>{@code LIST_MCP_RESOURCES_TOOL_NAME} 'ListMcpResources'（:64）</td><td>ListMcpResourcesTool</td><td>ListMcpResourcesTool</td></tr>
 *   <tr><td>'ReadMcpResourceTool' 字面量（:65，无导出常量）</td><td>ReadMcpResourceTool</td><td>ReadMcpResourceTool</td></tr>
 *   <tr><td>{@code TODO_WRITE_TOOL_NAME} 'TodoWrite'（:67）</td><td>TodoWrite</td><td>TodoWriteTool</td></tr>
 *   <tr><td>{@code TASK_CREATE_TOOL_NAME}（:68）</td><td>TaskCreate</td><td>TaskCreateTool</td></tr>
 *   <tr><td>{@code TASK_GET_TOOL_NAME}（:69）</td><td>TaskGet</td><td>TaskGetTool</td></tr>
 *   <tr><td>{@code TASK_UPDATE_TOOL_NAME}（:70）</td><td>TaskUpdate</td><td>TaskUpdateTool</td></tr>
 *   <tr><td>{@code TASK_LIST_TOOL_NAME}（:71）</td><td>TaskList</td><td>TaskListTool</td></tr>
 *   <tr><td>{@code TASK_STOP_TOOL_NAME}（:72）</td><td>TaskStop</td><td>TaskStopTool</td></tr>
 *   <tr><td>{@code TASK_OUTPUT_TOOL_NAME}（:73）</td><td>TaskOutput</td><td>TaskOutputTool</td></tr>
 *   <tr><td>{@code ASK_USER_QUESTION_TOOL_NAME}（:75）</td><td>AskUserQuestion</td><td>AskUserQuestionTool</td></tr>
 *   <tr><td>{@code ENTER_PLAN_MODE_TOOL_NAME}（:76）</td><td>EnterPlanMode</td><td>EnterPlanModeTool</td></tr>
 *   <tr><td>{@code EXIT_PLAN_MODE_TOOL_NAME}（:77）</td><td>ExitPlanMode</td><td>ExitPlanModeTool</td></tr>
 *   <tr><td>{@code TEAM_CREATE_TOOL_NAME}（:80）</td><td>TeamCreate</td><td>TeamCreateTool</td></tr>
 *   <tr><td>{@code TEAM_DELETE_TOOL_NAME}（:82）</td><td>TeamDelete</td><td>TeamDeleteTool</td></tr>
 *   <tr><td>{@code SEND_MESSAGE_TOOL_NAME}（:83）</td><td>SendMessage</td><td>SendMessageTool</td></tr>
 *   <tr><td>{@code SLEEP_TOOL_NAME} 'Sleep'（:87）</td><td>Sleep</td><td>SleepTool</td></tr>
 *   <tr><td>{@code YOLO_CLASSIFIER_TOOL_NAME} 'classify_result'（:93）</td><td>— N/A —</td><td>Java 分类器非 Tool（YoloClassifier 接口），无注册名</td></tr>
 * </table>
 *
 * <p>CC 4 条件项（:84-91，feature/ant 门控）—— Java 均不纳入 SAFE_TOOLS（对齐 CC 条件门控语义）：
 * <ul>
 *   <li>{@code WORKFLOW_TOOL_NAME}（feature('WORKFLOW_SCRIPTS')，:85）—— Java WorkflowTool 桩已建
 *       （IMP-H G11+G32①），feature 默认关、桩恒 isEnabled()==false → LLM 不可见；非只读安全工具，不白名单</li>
 *   <li>{@code TERMINAL_CAPTURE_TOOL_NAME}（feature('TERMINAL_PANEL')，:89）—— Java TerminalCaptureTool 桩已建，同上</li>
 *   <li>{@code OVERFLOW_TEST_TOOL_NAME}（feature('OVERFLOW_TEST_TOOL')，:90）—— Java OverflowTestTool 已删除
 *       （G30⑫：CC tools.ts:107-108/221 在 CC 亦为纯测试工具、Java 无对应测试通道；flag 为死标志），不白名单</li>
 *   <li>{@code VERIFY_PLAN_EXECUTION_TOOL_NAME}（USER_TYPE==='ant'，:91）—— Java VerifyPlanExecutionTool 桩已建
 *       （CLAUDE_CODE_VERIFY_PLAN env 门控），同上</li>
 * </ul>
 *
 * <p>Java 最终 21 项 = 21 固定（22 固定中 classify_result N/A）+ 0 条件（4 条件项均不纳入白名单，见上）。
 * {@code Skill} 免检已移除（CC 无 skill 免检概念，旧 Java 独有 —— T07 R5 登记）。
 *
 * <p>注：CC 26 项口径（22+4）与 Java 21 项（21+0）的差 = 5 项 N/A（classify_result +
 * 4 条件项），N/A 依据已在上文逐项登记，不进入对齐分母（对齐度规则 §5.4）。
 */
@Component
public class SafeToolWhitelist {

    /**
     * 安全工具列表 · 对齐 CC {@code SAFE_YOLO_ALLOWLISTED_TOOLS}（classifierDecision.ts:56-94）。
     *
     * <p>以 Java 注册工具名（{@code Tool#name()}）为准 —— 管线消费点
     * {@code safeToolWhitelist.isSafe(tool.name())} 拿到的就是注册名
     * （B2 后主名对齐 CC 大小写，旧 snake_case 仅存于 aliases，不参与白名单匹配）。
     */
    private static final Set<String> SAFE_TOOLS = Set.of(
            // ── Read-only file operations ──
            "Read",                 // CC 'Read'（FILE_READ_TOOL_NAME，classifierDecision.ts:58）
            "Grep",                 // CC 'Grep'（:60）
            "Glob",                 // CC 'Glob'（:61）
            "LSP",                  // CC 'LSP'（:62）
            "ToolSearch",           // CC 'ToolSearch'（:63）
            "ListMcpResourcesTool", // CC 'ListMcpResources'（:64）
            "ReadMcpResourceTool",  // CC 'ReadMcpResourceTool' 字面量（:65）
            // ── Task management (metadata only) ──
            "TodoWrite",            // CC 'TodoWrite'（:67）
            "TaskCreate",           // CC :68
            "TaskGet",              // CC :69
            "TaskUpdate",           // CC :70
            "TaskList",             // CC :71
            "TaskStop",             // CC :72
            "TaskOutput",           // CC :73
            // ── Plan mode / UI ──
            "AskUserQuestion",      // CC 'AskUserQuestion'（:75）
            "EnterPlanMode",        // CC :76
            "ExitPlanMode",         // CC :77
            // ── Swarm coordination（内部 mailbox/team 状态，无安全绕过）──
            "TeamCreate",           // CC :80
            "TeamDelete",           // CC :82
            "SendMessage",          // CC :83
            // ── Misc safe ──
            "Sleep"                 // CC 'Sleep'（:87，SLEEP_TOOL_NAME）
            // 注：CC :84-91 条件项（Workflow/TerminalCapture/OverflowTest/VerifyPlanExecution）
            //   与 :93 classify_result —— Java N/A，见类 javadoc 逐项依据
    );

    /**
     * 工具是否在安全白名单中（CC {@code isAutoModeAllowlistedTool}，classifierDecision.ts:96-98）。
     *
     * @param toolName 工具名（Java 注册名）
     * @return true = 安全，auto mode 下直接 allow
     */
    public boolean isSafe(String toolName) {
        return toolName != null && SAFE_TOOLS.contains(toolName);
    }
}
