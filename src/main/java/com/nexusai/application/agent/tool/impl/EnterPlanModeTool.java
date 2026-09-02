package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.compact.PlanModeAttachments;
import com.nexusai.application.agent.permission.DangerousPatternDetector;
import com.nexusai.application.agent.permission.GetNextPermissionMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdateApplier;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EnterPlanMode 工具 · 对齐 CC {@code EnterPlanModeTool.ts}.
 *
 * <p>F1 迁移：从静态 plan 标志/plan-id map 迁到会话级
 * {@link ToolPermissionContext}（经 {@code ctx.setAppState} 写
 * {@code appStateRef['toolPermissionContext'].mode=PLAN} + prePlanMode），随上下文流转，
 * 消除多会话并发的静态 map 隐患（对齐 CC EnterPlanModeTool.ts:88-94）。
 *
 * <p>CC 关键行为（自验源码，不信注释）:
 * <ul>
 *   <li>inputSchema {@code z.strictObject({})}（EnterPlanModeTool.ts:21-25）—— 无 goal 参数</li>
 *   <li>agent 上下文拒绝（EnterPlanModeTool.ts:78-80）—— Java 端 {@code agentType()!=null}
 *       判定子 agent（SubagentExecutor:945 设 agentType；agentId 恒 UUID 兜底不可用）</li>
 *   <li>{@code applyPermissionUpdate(prepareContextForPlanMode(prev), {setMode,plan})}（:88-94）
 *       —— prePlanMode 记录当前 mode（permissionSetup.ts:1462-1493 非 plan → prePlanMode=currentMode）</li>
 *   <li>mapToolResult 6 步只读探索指令（:103-125，非 interview 分支）</li>
 * </ul>
 */
@Component
public class EnterPlanModeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(EnterPlanModeTool.class);

    public static final String NAME = "EnterPlanMode";

    /** appStateRef key · CC original: toolPermissionContext（EnterPlanModeTool.ts:90）。 */
    private static final String KEY_TOOL_PERMISSION_CONTEXT = "toolPermissionContext";

    /** 权限更新调度器（对齐 CC applyPermissionUpdate）· 字段注入 @Autowired(required=false) 与 BashTool 同款 */
    @Autowired(required = false)
    private PermissionUpdateApplier permissionUpdateApplier;

    /** [WF-13 接线] 危险规则剥离/恢复器（S04 统一入口依赖）· 生产由 Spring 注入；测试懒建。 */
    @Autowired(required = false)
    private DangerousPatternDetector dangerousPatternDetector;

    private PermissionUpdateApplier applier() {
        // 测试直 new 时无 Spring 注入 → 懒建（applier 无状态）
        if (permissionUpdateApplier == null) {
            permissionUpdateApplier = new PermissionUpdateApplier();
        }
        return permissionUpdateApplier;
    }

    private DangerousPatternDetector detector() {
        if (dangerousPatternDetector == null) {
            dangerousPatternDetector = new DangerousPatternDetector(new PermissionUpdateApplier());
        }
        return dangerousPatternDetector;
    }

    /**
     * [WF-13 接线] 统一入口依赖配置 · 对齐 CC EnterPlanModeTool.ts 依赖
     * （feature('TRANSCRIPT_CLASSIFIER')/isAutoModeGateEnabled()/shouldPlanUseAutoMode）。
     *
     * <p>Java 生产当前无 TRANSCRIPT_CLASSIFIER 编译期宏（feature 恒 false，对齐外部构建），
     * planUsesAutoMode 无等价（false）——统一入口退化到 CC :1488-1492 plain 分支，
     * 与旧 EnterPlanModeTool 简化版行为一致；feature/gate 真值接线归 WF-3/WF-8 域。
     *
     * @return TransitionConfig（detector 必填）
     */
    private GetNextPermissionMode.TransitionConfig transitionConfig() {
        return new GetNextPermissionMode.TransitionConfig(
                () -> false,  // transcriptClassifierFeature：Java 无 feature('TRANSCRIPT_CLASSIFIER') 编译期宏
                () -> false,  // autoModeGateEnabled：gate 接线归 WF-8
                () -> false,  // planUsesAutoMode：Java 无 shouldPlanUseAutoMode 等价
                detector());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // CC EnterPlanModeTool.ts:40-42 description()
        return "Requests permission to enter plan mode for complex tasks requiring exploration and design";
    }

    /**
     * 用户可见名称 · 对齐 CC EnterPlanModeTool.ts:52-54 {@code userFacingName()}
     * {@code return ''}（空串 = UI 不显示专属名，回退默认展示）。
     */
    @Override
    public String userFacingName() {
        return ""; // CC EnterPlanModeTool.ts:53
    }

    /**
     * 工具提示词 · 对齐 CC {@code EnterPlanModeTool/prompt.ts:1-98}
     * {@code getEnterPlanModeToolPrompt()} 外部（非 ant）分支逐字移植。
     *
     * <p>Java 无 {@code USER_TYPE='ant'} 分支（生产默认外部构建）→ 恒走 external 分支
     * （prompt.ts:166-170）；Java 无 planModeInterviewPhase 特性（{@code isPlanModeInterviewPhaseEnabled()}
     * 恒 false，prompt.ts:19-21）→ WHAT_HAPPENS_SECTION 恒包含（prompt.ts:4-14）。
     * {@code ASK_USER_QUESTION_TOOL_NAME} 以 'AskUserQuestion' 内联（prompt.ts:2/11/54）。
     */
    @Override
    public String prompt() {
        return """
            Use this tool proactively when you're about to start a non-trivial implementation task. Getting user sign-off on your approach before writing code prevents wasted effort and ensures alignment. This tool transitions you into plan mode where you can explore the codebase and design an implementation approach for user approval.

            ## When to Use This Tool

            **Prefer using EnterPlanMode** for implementation tasks unless they're simple. Use it when ANY of these conditions apply:

            1. **New Feature Implementation**: Adding meaningful new functionality
               - Example: "Add a logout button" - where should it go? What should happen on click?
               - Example: "Add form validation" - what rules? What error messages?

            2. **Multiple Valid Approaches**: The task can be solved in several different ways
               - Example: "Add caching to the API" - could use Redis, in-memory, file-based, etc.
               - Example: "Improve performance" - many optimization strategies possible

            3. **Code Modifications**: Changes that affect existing behavior or structure
               - Example: "Update the login flow" - what exactly should change?
               - Example: "Refactor this component" - what's the target architecture?

            4. **Architectural Decisions**: The task requires choosing between patterns or technologies
               - Example: "Add real-time updates" - WebSockets vs SSE vs polling
               - Example: "Implement state management" - Redux vs Context vs custom solution

            5. **Multi-File Changes**: The task will likely touch more than 2-3 files
               - Example: "Refactor the authentication system"
               - Example: "Add a new API endpoint with tests"

            6. **Unclear Requirements**: You need to explore before understanding the full scope
               - Example: "Make the app faster" - need to profile and identify bottlenecks
               - Example: "Fix the bug in checkout" - need to investigate root cause

            7. **User Preferences Matter**: The implementation could reasonably go multiple ways
               - If you would use AskUserQuestion to clarify the approach, use EnterPlanMode instead
               - Plan mode lets you explore first, then present options with context

            ## When NOT to Use This Tool

            Only skip EnterPlanMode for simple tasks:
            - Single-line or few-line fixes (typos, obvious bugs, small tweaks)
            - Adding a single function with clear requirements
            - Tasks where the user has given very specific, detailed instructions
            - Pure research/exploration tasks (use the Agent tool with explore agent instead)

            ## What Happens in Plan Mode

            In plan mode, you'll:
            1. Thoroughly explore the codebase using Glob, Grep, and Read tools
            2. Understand existing patterns and architecture
            3. Design an implementation approach
            4. Present your plan to the user for approval
            5. Use AskUserQuestion if you need to clarify approaches
            6. Exit plan mode with ExitPlanMode when ready to implement

            ## Examples

            ### GOOD - Use EnterPlanMode:
            User: "Add user authentication to the app"
            - Requires architectural decisions (session vs JWT, where to store tokens, middleware structure)

            User: "Optimize the database queries"
            - Multiple approaches possible, need to profile first, significant impact

            User: "Implement dark mode"
            - Architectural decision on theme system, affects many components

            User: "Add a delete button to the user profile"
            - Seems simple but involves: where to place it, confirmation dialog, API call, error handling, state updates

            User: "Update the error handling in the API"
            - Affects multiple files, user should approve the approach

            ### BAD - Don't use EnterPlanMode:
            User: "Fix the typo in the README"
            - Straightforward, no planning needed

            User: "Add a console.log to debug this function"
            - Simple, obvious implementation

            User: "What files handle routing?"
            - Research task, not implementation planning

            ## Important Notes

            - This tool REQUIRES user approval - they must consent to entering plan mode
            - If unsure whether to use it, err on the side of planning - it's better to get alignment upfront than to redo work
            - Users appreciate being consulted before significant changes are made to their codebase
            """;
    }

    @Override
    public JsonNode inputSchema() {
        // CC EnterPlanModeTool.ts:21-25 z.strictObject({}) — 无 goal 参数（顶层指令"可选 goal"与
        // CC 真源冲突，按真源去 goal，见 concern F1-CC-PLAN-2）
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }

    // ── CC EnterPlanModeTool.ts:55-73 —— shouldDefer/isConcurrencySafe/isReadOnly/maxResultSizeChars ──

    @Override
    public boolean shouldDefer(JsonNode input) {
        return true; // CC EnterPlanModeTool.ts:55 shouldDefer: true
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true; // CC EnterPlanModeTool.ts:68-70 isConcurrencySafe()
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true; // CC EnterPlanModeTool.ts:71-73 isReadOnly()
    }

    @Override
    public long maxResultSizeChars() {
        return 100_000L; // CC EnterPlanModeTool.ts:39 maxResultSizeChars: 100_000
    }

    @Override
    public String searchHint() {
        return "switch to plan mode to design an approach before coding"; // CC EnterPlanModeTool.ts:38 searchHint
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        // CC EnterPlanModeTool.ts:78-80 — agent 上下文拒绝
        if (ctx != null && ctx.agentType() != null) {
            if (log.isDebugEnabled()) {
                log.debug("[EnterPlanModeTool] 子 agent 上下文拒绝: agentType={}（CC EnterPlanModeTool.ts:79 'EnterPlanMode tool cannot be used in agent contexts'）",
                    ctx.agentType());
            }
            return ToolResult.error(call.id(), "EnterPlanMode tool cannot be used in agent contexts");
        }
        if (ctx == null) {
            return ToolResult.error(call.id(), "ToolUseContext is required");
        }

        // CC :88-94 setAppState(prev => ({...prev, toolPermissionContext:
        //   applyPermissionUpdate(prepareContextForPlanMode(prev.toolPermissionContext),
        //   {type:'setMode', mode:'plan', destination:'session'})}))
        // [WF-13 接线] plan 进入统一走 GetNextPermissionMode.prepareContextForPlanMode
        //   （对齐 CC EnterPlanModeTool.ts:91 prepareContextForPlanMode），不再本地简化实现。
        ctx.setAppState().accept(prev -> {
            ToolPermissionContext current = currentPermissionContext(ctx, prev);
            ToolPermissionContext prepared = GetNextPermissionMode.prepareContextForPlanMode(
                current.mode(), current, transitionConfig());
            ToolPermissionContext updated = applier().apply(
                new PermissionUpdate.SetMode(
                    PermissionUpdate.Destination.SESSION, PermissionMode.PLAN), prepared);
            Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
            next.put(KEY_TOOL_PERMISSION_CONTEXT, updated);
            // CC handlePlanModeTransition（state.ts:1350-1356）：切换 TO plan 时清 pending exit 附件标志，
            //   防快速切换时 plan_mode 与 plan_mode_exit 同发。
            PlanModeAttachments.PlanModeFlags flags = PlanModeAttachments.getOrCreateFlags(next);
            flags.setNeedsPlanModeExitAttachment(false);
            return next;
        });

        ToolPermissionContext current = appStateTpc(ctx);
        log.info("[EnterPlanModeTool] 会话 {} 已进入 plan 模式 prePlanMode={}（对齐 CC EnterPlanModeTool.ts:88-94 setAppState 写 permission mode）",
            ctx.sessionId(), current != null ? current.prePlanMode() : null);
        return ToolResult.success(call.id(), buildOutput());
    }

    /**
     * [WF-13 接线] plan 进入上下文准备 · 委托统一入口
     * {@link GetNextPermissionMode#prepareContextForPlanMode(PermissionMode, ToolPermissionContext, GetNextPermissionMode.TransitionConfig)}
     * （对齐 CC EnterPlanModeTool.ts:91 + permissionSetup.ts:1462-1493）。
     *
     * <p>保留本静态方法仅供测试/直调兼容（行为=统一入口退化 plain 分支）；生产 execute 直接走统一入口。
     *
     * @param context 当前上下文（mode 即 fromMode）
     * @return prePlanMode 已写入的上下文（已 plan 时原样返回）
     */
    static ToolPermissionContext prepareContextForPlanMode(ToolPermissionContext context) {
        return GetNextPermissionMode.prepareContextForPlanMode(
            context.mode(), context,
            new GetNextPermissionMode.TransitionConfig(
                () -> false, () -> false, () -> false,
                new DangerousPatternDetector(new PermissionUpdateApplier())));
    }

    /** 读取当前 appState.toolPermissionContext；appState 无该字段时回退 ctx.permissionContext()/strict(DEFAULT)。 */
    private ToolPermissionContext currentPermissionContext(ToolUseContext ctx, Map<String, Object> prev) {
        Object tpc = prev != null ? prev.get(KEY_TOOL_PERMISSION_CONTEXT) : null;
        if (tpc instanceof ToolPermissionContext existing) {
            return existing;
        }
        if (ctx.permissionContext() != null) {
            return ctx.permissionContext();
        }
        return ToolPermissionContext.strict(PermissionMode.DEFAULT);
    }

    private ToolPermissionContext appStateTpc(ToolUseContext ctx) {
        Map<String, Object> snapshot = ctx.getAppState().apply(null);
        if (snapshot == null) {
            return null;
        }
        Object tpc = snapshot.get(KEY_TOOL_PERMISSION_CONTEXT);
        return tpc instanceof ToolPermissionContext p ? p : null;
    }

    /**
     * 输出 message · 对齐 CC call() data.message（EnterPlanModeTool.ts:97-100）。
     * Java 端 data 即 LLM 可见 content（CC data.message 字符串本体），mapToToolResultBlockParam
     * 追加 6 步指令；不包 JSON（避免 mapper 输出带 {"message":...} 前缀）。
     */
    private String buildOutput() {
        return "Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach.";
    }

    /**
     * tool_result 6 步只读探索指令 · 对齐 CC EnterPlanModeTool.ts:103-125
     * {@code mapToolResultToToolResultBlockParam}（非 interview 分支 :108-118）。
     * Java 无 planModeInterviewPhase 特性 → 恒走非 interview 分支。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (result == null || result.data() == null) {
            return null;
        }
        String message = String.valueOf(result.data());
        String instructions = message + "\n\n"
            + "In plan mode, you should:\n"
            + "1. Thoroughly explore the codebase to understand existing patterns\n"
            + "2. Identify similar features and architectural approaches\n"
            + "3. Consider multiple approaches and their trade-offs\n"
            + "4. Use AskUserQuestion if you need to clarify the approach\n"
            + "5. Design a concrete implementation strategy\n"
            + "6. When ready, use ExitPlanMode to present your plan for approval\n\n"
            + "Remember: DO NOT write or edit any files yet. This is a read-only exploration and planning phase.";
        if (log.isDebugEnabled()) {
            log.debug("[EnterPlanModeTool] mapToToolResultBlockParam 生成 6 步只读探索指令 content长度={}（CC EnterPlanModeTool.ts:108-118）",
                instructions.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", instructions, isError);
    }
}
