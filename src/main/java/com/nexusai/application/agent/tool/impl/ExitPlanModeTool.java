package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.compact.PlanModeAttachments;
import com.nexusai.application.agent.compact.PlanProvider;
import com.nexusai.application.agent.compact.PlanProviderImpl;
import com.nexusai.application.agent.permission.DangerousPatternDetector;
import com.nexusai.application.agent.permission.GetNextPermissionMode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdateApplier;
import com.nexusai.application.agent.permission.SessionPermissionOverlay;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.AgentToolConstants;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ExitPlanMode 工具 · 对齐 CC {@code ExitPlanModeV2Tool.ts}（V2 语义全量补齐）。
 *
 * <p>F1 迁移：从静态 plan 判定/plan-id 迁到读
 * {@code appStateRef['toolPermissionContext']}（EnterPlanMode 写入的 mode=PLAN + prePlanMode），
 * execute 恢复 prePlanMode + 清 prePlanMode。
 *
 * <p>CC 关键行为（自验源码，不信注释）:
 * <ul>
 *   <li>inputSchema {@code strictObject({allowedPrompts}).passthrough()}（ExitPlanModeV2Tool.ts:77-89）
 *       + SDK 注入 {@code plan}/{@code planFilePath}（:97-108）—— 去掉 Java 旧 {@code apply}/{@code plan_path}</li>
 *   <li>validateInput mode!=='plan' → errorCode 1 + CC 文案（:195-220）</li>
 *   <li>checkPermissions 非 teammate → Ask('Exit plan mode?')（:221-239）；requiresUserInteraction=true（:185-194）</li>
 *   <li>execute 恢复 {@code prePlanMode ?? 'default'} + prePlanMode=undefined（:357-403）</li>
 *   <li>输出 V2 契约 {plan, isAgent, filePath, hasTaskTool, planWasEdited}（:409-417）</li>
 *   <li>mapToolResult Approved Plan 全文回显（:481-489）</li>
 * </ul>
 */
@Component
public class ExitPlanModeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ExitPlanModeTool.class);

    public static final String NAME = "ExitPlanMode";

    /** appStateRef key · CC original: toolPermissionContext（ExitPlanModeV2Tool.ts:204）。 */
    private static final String KEY_TOOL_PERMISSION_CONTEXT = "toolPermissionContext";

    /** [WF-13 接线] 危险规则剥离/恢复器（S04 统一入口依赖）· 生产由 Spring 注入；测试懒建。 */
    @Autowired(required = false)
    private DangerousPatternDetector dangerousPatternDetector;

    /**
     * [plan-approval] 会话列写入器 · 退出后的恢复模式必须写回 sessions.session_permission_rules，
     * 否则下一条消息仍按 permission_mode=plan 启动（用户「退出后又被拉回 plan」）。
     * 审批已按用户选择持久化时（mode 已非 plan），这里不覆盖。
     */
    @Autowired(required = false)
    private SessionMapper sessionMapper;

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent('tengu_exit_plan_mode_called_outside_plan')
     * （ExitPlanModeV2Tool.ts:206-212）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    @Autowired(required = false)
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
    }

    private DangerousPatternDetector detector() {
        if (dangerousPatternDetector == null) {
            dangerousPatternDetector = new DangerousPatternDetector(new PermissionUpdateApplier());
        }
        return dangerousPatternDetector;
    }

    /**
     * [WF-13 接线] 统一入口依赖配置 · 对齐 CC ExitPlanModeV2Tool.ts 依赖
     * （feature('TRANSCRIPT_CLASSIFIER')/isAutoModeGateEnabled()）。
     *
     * <p>Java 生产当前无 TRANSCRIPT_CLASSIFIER 编译期宏（feature 恒 false，对齐外部构建）——
     * 统一入口退化到 CC :640-643 prePlanMode 清理分支，与旧 restoreFromPlanMode 行为一致；
     * feature/gate 真值接线归 WF-3/WF-8 域。
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
        // CC ExitPlanModeV2Tool.ts:151-153 description()
        return "Prompts the user to exit plan mode and start coding";
    }

    /**
     * 用户可见名称 · 对齐 CC ExitPlanModeV2Tool.ts:163-165 {@code userFacingName()}
     * {@code return ''}（空串 = UI 不显示专属名，回退默认展示）。
     */
    @Override
    public String userFacingName() {
        return ""; // CC ExitPlanModeV2Tool.ts:164
    }

    /**
     * 工具提示词 · 对齐 CC {@code ExitPlanModeTool/prompt.ts:6-29}
     * {@code EXIT_PLAN_MODE_V2_TOOL_PROMPT} 逐字移植（CC :4 内联
     * {@code ASK_USER_QUESTION_TOOL_NAME='AskUserQuestion'}）。
     */
    @Override
    public String prompt() {
        return """
            Use this tool when you are in plan mode and have finished writing your plan to the plan file and are ready for user approval.

            ## How This Tool Works
            - You should have already written your plan to the plan file specified in the plan mode system message
            - This tool does NOT take the plan content as a parameter - it will read the plan from the file you wrote
            - This tool simply signals that you're done planning and ready for the user to review and approve
            - The user will see the contents of your plan file when they review it

            ## When to Use This Tool
            IMPORTANT: Only use this tool when the task requires planning the implementation steps of a task that requires writing code. For research tasks where you're gathering information, searching files, reading files or in general trying to understand the codebase - do NOT use this tool.

            ## Before Using This Tool
            Ensure your plan is complete and unambiguous:
            - If you have unresolved questions about requirements or approach, use AskUserQuestion first (in earlier phases)
            - Once your plan is finalized, use THIS tool to request approval

            **Important:** Do NOT use AskUserQuestion to ask "Is this plan okay?" or "Should I proceed?" - that's exactly what THIS tool does. ExitPlanMode inherently requests user approval of your plan.

            ## Examples

            1. Initial task: "Search for and understand the implementation of vim mode in the codebase" - Do not use the exit plan mode tool because you are not planning the implementation steps of a task.
            2. Initial task: "Help me implement yank mode for vim" - Use the exit plan mode tool after you have finished planning the implementation steps of the task.
            3. Initial task: "Add a new feature to handle user authentication" - If unsure about auth method (OAuth, JWT, etc.), use AskUserQuestion first, then use exit plan mode tool after clarifying the approach.
            """;
    }

    @Override
    public JsonNode inputSchema() {
        // CC ExitPlanModeV2Tool.ts:77-89 strictObject({allowedPrompts}).passthrough()
        // + :97-108 _sdkInputSchema 注入 plan / planFilePath（normalizeToolInput 从 disk 注入）
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // CC :64-73 allowedPromptSchema — {tool: enum[Bash], prompt: string}
        ObjectNode allowedPrompts = props.putObject("allowedPrompts");
        allowedPrompts.put("type", "array");
        ObjectNode apItems = allowedPrompts.putObject("items");
        apItems.put("type", "object");
        ObjectNode apProps = apItems.putObject("properties");
        ObjectNode apTool = apProps.putObject("tool");
        apTool.put("type", "string");
        apTool.putArray("enum").add("Bash");
        apTool.put("description", "The tool this prompt applies to");
        ObjectNode apPrompt = apProps.putObject("prompt");
        apPrompt.put("type", "string");
        apPrompt.put("description",
            "Semantic description of the action, e.g. \"run tests\", \"install dependencies\"");
        allowedPrompts.put("description",
            "Prompt-based permissions needed to implement the plan. These describe categories of actions rather than specific commands.");

        // CC :98-102 _sdkInputSchema plan（normalizeToolInput 注入）
        ObjectNode plan = props.putObject("plan");
        plan.put("type", "string");
        plan.put("description", "The plan content (injected by normalizeToolInput from disk)");
        // CC :103-106 _sdkInputSchema planFilePath
        ObjectNode planFilePath = props.putObject("planFilePath");
        planFilePath.put("type", "string");
        planFilePath.put("description", "The plan file path (injected by normalizeToolInput)");

        // [IT-5][RES-04] 广告层 = true（布尔）· zod v4 toJSONSchema 对 z.strictObject({...}).
        // passthrough() 实测输出是 additionalProperties:{}（空对象），但 Spring AI MCP server
        // 的 ToolCallbackConverter（syncTools）只接受布尔 additionalProperties，空对象 →
        // "Cannot deserialize value of type Boolean from Object value"，生产 /mcp 启动即挂。
        // 布尔 true = 允许任意附加键，与 {} 及 passthrough() 运行时语义等价（SDK 注入
        // plan/planFilePath 键），故改用 true（RES-04 全量 @SpringBootTest 发现并修复）。
        schema.put("additionalProperties", true);
        return schema;
    }

    // ── CC ExitPlanModeV2Tool.ts:179-194 —— shouldDefer/isConcurrencySafe/isReadOnly/requiresUserInteraction ──

    @Override
    public boolean shouldDefer(JsonNode input) {
        return true; // CC :166 shouldDefer: true
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true; // CC :179-181 isConcurrencySafe()
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return false; // CC :182-184 "Now writes to disk"
    }

    /**
     * 审批前注入磁盘计划正文 · 对齐 CC {@code normalizeToolInput} 的 ExitPlanMode 分支
     * （exe 2.1.328 {@code qyt}，偏移 326613822：读盘后返回 {@code {plan, planFilePath}} 并入
     * 权限可见输入）。
     *
     * <p><b>WHY</b>：模型按提示只调用 {@code ExitPlanMode()}，计划正文在磁盘上；若不回填，
     * 权限事件里的 toolInput 是空对象，审批卡只能显示工具名，用户实际批准的不是计划内容。
     * 回填只作用于 hook / 权限可见输入，{@link #execute} 仍用原始 call.input()（CC
     * callInput 不突变契约）。
     */
    @Override
    public JsonNode backfillObservableInput(JsonNode input, ToolUseContext ctx) {
        if (ctx == null || input == null || !input.isObject()) {
            return input;
        }
        try {
            PlanProvider provider = new PlanProviderImpl(ctx.sessionId());
            String plan = provider.getPlan(null);
            String planFilePath = provider.getPlanFilePath(null);
            if (plan == null && planFilePath == null) {
                return input;
            }
            ObjectNode out = ((ObjectNode) input).deepCopy();
            if (!out.has("plan") && plan != null) {
                out.put("plan", plan);
            }
            if (!out.has("planFilePath") && planFilePath != null) {
                out.put("planFilePath", planFilePath);
            }
            if (log.isDebugEnabled()) {
                log.debug("[ExitPlanModeTool] 审批前回填计划: path={} planChars={} inputKeys={}",
                    planFilePath, plan == null ? 0 : plan.length(), out.size());
            }
            return out;
        } catch (Exception e) {
            log.warn("[ExitPlanModeTool] 审批前回填计划失败（按原输入继续）: {}", e.getMessage());
            return input;
        }
    }

    @Override
    public boolean requiresUserInteraction() {
        // CC :185-194 — 非 teammate 需要用户确认。Java 无 teammate 概念（OPD-IMP-F1-2）→ 恒 true
        return true;
    }

    @Override
    public long maxResultSizeChars() {
        return 100_000L; // CC :150 maxResultSizeChars: 100_000
    }

    @Override
    public String searchHint() {
        return "present plan for approval and start coding (plan mode only)"; // CC ExitPlanModeV2Tool.ts:149 searchHint
    }

    /**
     * [IT-5] 未知键运行时策略 = PASSTHROUGH · 对齐 CC ExitPlanModeV2Tool.ts:79-88
     * {@code inputSchema = lazySchema(() => z.strictObject({allowedPrompts?}).passthrough())}
     * —— SDK 经 normalizeToolInput 注入 {@code plan}/{@code planFilePath} 键
     * （:97-108 _sdkInputSchema），运行时接受未知键。
     */
    @Override
    public Tool.UnknownKeysPolicy unknownKeysPolicy() {
        return Tool.UnknownKeysPolicy.PASSTHROUGH;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * validateInput · 对齐 CC ExitPlanModeV2Tool.ts:195-220.
     * mode !== 'plan' → {@code {result:false, message, errorCode:1}}；拒绝在 checkPermissions 之前
     * 避免弹出确认对话框。另含 allowedPrompts 运行时项形状校验（[G22④ / OPD-PW-06]，CC
     * allowedPromptSchema :64-73 zod 前置拦截等价）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        if (ctx == null) {
            return ValidationResult.fail("1",
                "You are not in plan mode. This tool is only for exiting plan mode after writing a plan. If your plan was already approved, continue with implementation.");
        }
        ToolPermissionContext tpc = appStateTpc(ctx);
        PermissionMode mode = tpc != null ? tpc.mode() : null;
        if (mode != PermissionMode.PLAN) {
            if (log.isDebugEnabled()) {
                log.debug("[ExitPlanModeTool] validateInput 拒绝: 当前 mode={}（期望 PLAN，CC ExitPlanModeV2Tool.ts:205-217 errorCode 1）", mode);
            }
            // [IMP-T G15] 遥测 tengu_exit_plan_mode_called_outside_plan（CC ExitPlanModeV2Tool.ts:206-212）
            emitExitPlanModeCalledOutsidePlan(ctx, mode);
            return ValidationResult.fail("1",
                "You are not in plan mode. This tool is only for exiting plan mode after writing a plan. If your plan was already approved, continue with implementation.");
        }
        // [G22④ / OPD-PW-06] allowedPrompts 运行时项形状校验 · 对齐 CC allowedPromptSchema
        // （ExitPlanModeV2Tool.ts:64-73 z.object({tool: z.enum(['Bash']), prompt: z.string()})）：
        // 非法项（tool 非 'Bash' / prompt 非字符串 / 非对象项）→ 前置拒绝，不再静默忽略
        // （△ B2：旧实现非法 allowedPrompts 静默透传，权限链可能误放行）。
        String allowedPromptsError = validateAllowedPrompts(input);
        if (allowedPromptsError != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ExitPlanModeTool] validateInput 拒绝: allowedPrompts 形状非法 — {}", allowedPromptsError);
            }
            return ValidationResult.fail("1", allowedPromptsError);
        }
        return ValidationResult.pass();
    }

    /**
     * allowedPrompts 项形状校验 · 对齐 CC {@code allowedPromptSchema}
     * （ExitPlanModeV2Tool.ts:64-73）：每项须 {@code {tool: 'Bash', prompt: string}}。
     *
     * @param input 工具输入（可能无 allowedPrompts 键 → 合法，null-safe）
     * @return 错误消息；合法（含缺省）返回 null
     */
    private static String validateAllowedPrompts(JsonNode input) {
        if (input == null || !input.has("allowedPrompts") || input.get("allowedPrompts").isNull()) {
            return null;
        }
        JsonNode ap = input.get("allowedPrompts");
        if (!ap.isArray()) {
            return "allowedPrompts must be an array of {tool: 'Bash', prompt: string} objects";
        }
        for (int i = 0; i < ap.size(); i++) {
            JsonNode item = ap.get(i);
            if (!item.isObject()) {
                return "allowedPrompts[" + i + "] must be an object {tool: 'Bash', prompt: string}";
            }
            JsonNode tool = item.get("tool");
            if (tool == null || !tool.isTextual() || !"Bash".equals(tool.asText())) {
                return "allowedPrompts[" + i + "].tool must be 'Bash'";
            }
            JsonNode prompt = item.get("prompt");
            if (prompt == null || !prompt.isTextual() || prompt.asText().isEmpty()) {
                return "allowedPrompts[" + i + "].prompt must be a non-empty string";
            }
        }
        return null;
    }

    /**
     * checkPermissions · 对齐 CC ExitPlanModeV2Tool.ts:221-239.
     * 非 teammate → Ask('Exit plan mode?')，requiresUserInteraction=true 保证 bypass 模式也弹确认。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("[ExitPlanModeTool] checkPermissions 返回 Ask('Exit plan mode?')（CC ExitPlanModeV2Tool.ts:234-238）");
        }
        // [plan-approval] 审批卡「执行方式」的可选项（对齐 CC buildPlanApprovalOptions）：
        //   保留上下文执行 → setMode(acceptEdits)；手动逐次批准 → setMode(default)。
        //   两者都是 SESSION 级（本次会话），与 CC 的 destination='session' 一致。
        //   用户点选后前端原样回传 updatedPermissions → onResponse 先 apply 再 complete，
        //   ExitPlanMode.execute 读到 mode != PLAN 即不再覆盖（CC 同序）。
        List<PermissionUpdate> suggestions = List.of(
            new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS),
            new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.DEFAULT));
        return new PermissionResult.Ask(
            "Exit plan mode?",
            new PermissionDecisionReason.Other("ExitPlanMode checkPermissions ask"),
            suggestions, null, input, null, false, null, List.of());
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        if (ctx == null) {
            return ToolResult.error(call.id(), "ToolUseContext is required");
        }
        // CC :244 isAgent = !!context.agentId；Java agentId 恒 UUID 兜底（ToolUseContext:276-278）→
        // 用 agentType()!=null 判定子 agent（SubagentExecutor:945 设，派生链透传）
        boolean isAgent = ctx.agentType() != null;

        // CC :246-253 filePath/inputPlan/plan —— 接线 PlanProvider 磁盘（对齐 ExitPlanModeV2Tool.ts:246-261）：
        //   filePath = getPlanFilePath(agentId)（主会话 context.agentId 为 undefined → 传 null 得 {slug}.md）
        //   inputPlan = 'plan' in input && typeof input.plan==='string' ? input.plan : undefined
        //   plan = inputPlan ?? getPlan(agentId)（input.plan 缺失走磁盘 fallback）
        //   inputPlan !== undefined && filePath → writeFile(filePath, inputPlan) 同步磁盘（CC :256-258）
        UUID planAgentId = isAgent ? ctx.agentId() : null;
        PlanProvider planProvider = new PlanProviderImpl(ctx.sessionId());
        String filePath = planProvider.getPlanFilePath(planAgentId);
        JsonNode input = call.input();
        String inputPlan = input != null && input.has("plan") && input.get("plan").isTextual()
            ? input.get("plan").asText() : null;
        String plan = inputPlan != null ? inputPlan : planProvider.getPlan(planAgentId);
        if (inputPlan != null && filePath != null) {
            writePlanFile(filePath, inputPlan);
        }

        // [plan-approval] 退出后的恢复模式写回会话列（跨 send）：只在「审批没替我们切模式」时做；
        // 用户已选执行方式（onResponse 先持久化 setMode）时 mode 已非 plan，不覆盖用户选择。
        ToolPermissionContext beforeExit = appStateTpc(ctx);
        if (beforeExit != null && beforeExit.mode() == PermissionMode.PLAN
                && sessionMapper != null && ctx.sessionId() != null && !ctx.sessionId().isBlank()) {
            try {
                PermissionMode restoreMode = restoreFromPlanMode(beforeExit, transitionConfig()).mode();
                SessionPermissionOverlay.persistSessionUpdates(sessionMapper, ctx.sessionId(),
                    List.of(new PermissionUpdate.SetMode(
                        PermissionUpdate.Destination.SESSION, restoreMode)),
                    "exit_plan_mode");
            } catch (Throwable t) {
                log.warn("[ExitPlanModeTool] 退出模式持久化失败（本次 run 内仍生效）: {}", t.toString());
            }
        }

        // CC :357-403 setAppState 恢复 prePlanMode + 清 prePlanMode；guard：mode!=='plan' 原样返回
        ctx.setAppState().accept(prev -> {
            ToolPermissionContext current = permissionContextFrom(prev);
            if (current == null) {
                return prev != null ? prev : Map.of();
            }
            // 会话级载体（appState 生产快照不可变，put 会抛 UOE；且 appState 跨 send 不活）
            PlanModeAttachments.PlanModeFlags flags =
                PlanModeAttachments.getOrCreateFlagsBySession(ctx.sessionId());
            if (current.mode() != PermissionMode.PLAN) {
                // 审批阶段用户已选定执行方式（onResponse 先 apply 了 setMode → mode 已不是 plan）
                // ⇒ CC 在审批组件里直接置位（ExitPlanModePermissionRequest 的 cV/eQ），本工具
                //   execute 的 guard 会早退；但退出提醒必须照发，故这里同样置位（幂等）。
                flags.setHasExitedPlanModeInSession(true);
                flags.setNeedsPlanModeExitAttachment(true);
                return prev != null ? prev : Map.of();
            }
            ToolPermissionContext restored = restoreFromPlanMode(current, transitionConfig());
            Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
            next.put(KEY_TOOL_PERMISSION_CONTEXT, restored);
            // CC ExitPlanModeV2Tool.ts:358-360 —— 退出 plan 模式时一次性置 reentry/exit 标志
            // （plan_mode_reentry / plan_mode_exit 附件由 loop 的 maybeInjectPlanModeAttachments 消费）
            flags.setHasExitedPlanModeInSession(true);
            flags.setNeedsPlanModeExitAttachment(true);
            return next;
        });

        // CC :405-407 hasTaskTool = isAgentSwarmsEnabled() && tools.some(AGENT_TOOL_NAME)。
        // [G14③ / OPD-PW-07] 补 isAgentSwarmsEnabled 门控（TaskSystemConfig 等价
        // agentSwarmsEnabled.ts:24-44）——swarms 未启用时 hasTaskTool 恒 false（不再输出
        // team hint，对齐 CC :405-407 短路语义）。注：IMP-F/IMP-G 对同点等价改动，合并保留本版。
        boolean hasTaskTool = TaskSystemConfig.isAgentSwarmsEnabled()
            && ctx.availableTools() != null && ctx.availableTools().stream()
            .anyMatch(t -> AgentToolConstants.AGENT_TOOL_NAME.equals(t.name()));

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("plan", plan);             // CC outputSchema plan: string|null (:111-116)
        structuredOutput.put("isAgent", isAgent);       // CC outputSchema isAgent: boolean (:117)
        structuredOutput.put("filePath", filePath);     // CC outputSchema filePath?: string (:118-120) — 磁盘真实路径
        if (hasTaskTool) {
            structuredOutput.put("hasTaskTool", true);  // CC :121-124 hasTaskTool?: boolean
        }
        if (inputPlan != null) {
            structuredOutput.put("planWasEdited", true);// CC :125-130 planWasEdited?: boolean (inputPlan 非空)
        }

        // data = Approved Plan 回显（CC mapToolResultToToolResultBlockParam 的 content 语义，见下方 mapper）
        String summary = approvedPlanEcho(plan, isAgent, hasTaskTool, inputPlan != null, filePath);

        log.info("[ExitPlanModeTool] 会话 {} 退出 plan 模式 isAgent={} planWasEdited={} 恢复 mode={}（CC ExitPlanModeV2Tool.ts:357-403 恢复 prePlanMode）",
            ctx.sessionId(), isAgent, inputPlan != null,
            appStateTpc(ctx) != null ? appStateTpc(ctx).mode() : null);
        return ToolResult.successWithStructuredOutput(call.id(), summary, structuredOutput);
    }

    /**
     * 读取 appState.toolPermissionContext（EnterPlanMode 写入的会话级 permission context）。
     */
    private ToolPermissionContext appStateTpc(ToolUseContext ctx) {
        return permissionContextFrom(ctx.getAppState().apply(null));
    }

    private ToolPermissionContext permissionContextFrom(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Object tpc = snapshot.get(KEY_TOOL_PERMISSION_CONTEXT);
        return tpc instanceof ToolPermissionContext p ? p : null;
    }

    /**
     * [IMP-T G15] tengu_exit_plan_mode_called_outside_plan 遥测 · 对齐 CC
     * ExitPlanModeV2Tool.ts:206-212 {@code logEvent('tengu_exit_plan_mode_called_outside_plan',
     * {model, mode, hasExitedPlanModeInSession})}。
     *
     * <p>metadata 对齐：
     * <ul>
     *   <li>{@code mode} — 当前 PermissionMode 枚举名（非 code/filepath）→
     *       {@link AnalyticsTracker#verified} 包装（CC AnalyticsMetadata_I_VERIFIED 标记）</li>
     *   <li>{@code hasExitedPlanModeInSession} — 会话级 flag（CC STATE.hasExitedPlanMode，
     *       bootstrap/state.ts:1333-1361）→ {@link PlanModeAttachments.PlanModeFlags}</li>
     *   <li>{@code model} — <b>省略</b>：CC 传 {@code options.mainLoopModel}（ExitPlanModeV2Tool.ts:207），
     *       Java ToolUseContext 无 mainLoopModel 等价源（登记 gap）；省略 = CC metadata undefined 等价</li>
     * </ul>
     *
     * @param ctx  工具调用上下文（appState 读 PlanModeFlags）
     * @param mode 当前 permission mode（可为 null → 'unknown'）
     */
    private void emitExitPlanModeCalledOutsidePlan(ToolUseContext ctx, PermissionMode mode) {
        if (analyticsTracker == null) {
            return;
        }
        String modeValue = mode == null ? "unknown" : mode.name();
        boolean hasExitedPlanModeInSession = false;
        if (ctx != null) {
            PlanModeAttachments.PlanModeFlags flags =
                PlanModeAttachments.getOrCreateFlagsBySession(ctx.sessionId());
            hasExitedPlanModeInSession = flags.hasExitedPlanModeInSession();
        }
        analyticsTracker.logEvent("tengu_exit_plan_mode_called_outside_plan",
            Map.<String, Object>of(
                "mode", AnalyticsTracker.verified(modeValue),
                "hasExitedPlanModeInSession", hasExitedPlanModeInSession));
        if (log.isDebugEnabled()) {
            log.debug("[ExitPlanModeTool] [IMP-T G15] 遥测 tengu_exit_plan_mode_called_outside_plan: mode={} hasExited={}",
                modeValue, hasExitedPlanModeInSession);
        }
    }

    /**
     * 同步写 plan 文件 · CC original: {@code writeFile(filePath, inputPlan, 'utf-8').catch(e => logError(e))}
     * （ExitPlanModeV2Tool.ts:256-258）：使 VerifyPlanExecution / Read 看到编辑后的 plan。
     * Java 无 persistFileSnapshotIfRemote（CC :259，remote-only snapshot）→ 跳过。
     *
     * @param filePath plan 文件绝对路径（getPlanFilePath 结果）
     * @param content  plan 全文（inputPlan）
     */
    private void writePlanFile(String filePath, String content) {
        try {
            Files.writeString(Path.of(filePath), content, StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("[ExitPlanModeTool] 写 plan 文件成功: path={} chars={}", filePath, content.length());
            }
        } catch (IOException e) {
            log.warn("[ExitPlanModeTool] 写 plan 文件失败（CC logError 不抛）: path={}", filePath, e);
        }
    }

    /**
     * 恢复进入 plan 前的 mode · 对齐 CC ExitPlanModeV2Tool.ts:357-403.
     * guard {@code mode !== 'plan' → 原样}；{@code restoreMode = prePlanMode ?? 'default'}；
     * {@code prePlanMode = undefined}。Java 无 TRANSCRIPT_CLASSIFIER（auto-mode 门）→
     * 跳过 auto gate fallback 分支（:328-355）。
     *
     * <p>[WF-13 接线] 退出 plan 统一走 {@link GetNextPermissionMode#transitionPermissionMode}
     * （对齐 CC permissionSetup.ts:597-646 统一入口）：处理离开 plan 的副作用
     * （feature on 时 auto 侧 {@code setAutoModeActive(false) + restoreDangerousPermissions}，
     * CC :633-637）+ prePlanMode 清理（CC :640-643）。transitionPermissionMode 不设置 mode
     * （CC :590-591 调用方职责），故本方法在过渡结果上补设 {@code restoreMode} + 清 prePlanMode。
     *
     * @param prev   当前上下文（mode 必须为 PLAN）
     * @param config 统一入口依赖配置（feature/gate/planAuto/detector）
     * @return 恢复 mode=restoreMode + prePlanMode=null 的上下文（非 plan 原样返回）
     */
    static ToolPermissionContext restoreFromPlanMode(
            ToolPermissionContext prev, GetNextPermissionMode.TransitionConfig config) {
        if (prev.mode() != PermissionMode.PLAN) {
            return prev; // CC :358 守卫
        }
        PermissionMode restoreMode = prev.prePlanMode() != null ? prev.prePlanMode() : PermissionMode.DEFAULT;
        // 统一入口：transitionPermissionMode 应用离开 plan 的副作用 + prePlanMode 清理
        ToolPermissionContext transitioned = GetNextPermissionMode.transitionPermissionMode(
            PermissionMode.PLAN, restoreMode, prev, config);
        return new ToolPermissionContext(
            restoreMode,
            transitioned.alwaysAllowRules(), transitioned.alwaysDenyRules(), transitioned.alwaysAskRules(),
            transitioned.additionalWorkingDirectories(),
            transitioned.isBypassPermissionsModeAvailable(), transitioned.isAutoModeAvailable(),
            transitioned.strippedDangerousRules(), transitioned.shouldAvoidPermissionPrompts(),
            transitioned.awaitAutomatedChecksBeforeDialog(),
            null // prePlanMode = undefined（CC :400）
        );
    }

    /**
     * Approved Plan 回显 · 对齐 CC ExitPlanModeV2Tool.ts:419-491
     * {@code mapToolResultToToolResultBlockParam} 的 content 语义（isAgent / 空 plan / 全文回显）。
     */
    static String approvedPlanEcho(String plan, boolean isAgent, boolean hasTaskTool,
                                   boolean planWasEdited, String filePath) {
        if (isAgent) {
            return "User has approved the plan. There is nothing else needed from you now. Please respond with \"ok\""; // CC :452-459
        }
        if (plan == null || plan.trim().isEmpty()) {
            return "User has approved exiting plan mode. You can now proceed."; // CC :462-468
        }
        String teamHint = hasTaskTool
            ? "\n\nIf this plan can be broken down into multiple independent tasks, consider using the TeamCreate tool to create a team and parallelize the work."
            : ""; // CC :470-472 TEAM_CREATE_TOOL_NAME='TeamCreate'（TeamCreateTool/constants.ts:1）
        String planLabel = planWasEdited ? "Approved Plan (edited by user)" : "Approved Plan"; // CC :477-479
        // CC :486 "Your plan has been saved to: ${filePath}" —— [WF6] filePath 已接线
        // PlanProvider.getPlanFilePath（不再恒 null），非 null 时打印保存路径
        String savedTo = filePath != null ? "Your plan has been saved to: " + filePath + "\n" : "";
        return "User has approved your plan. You can now start coding. Start with updating your todo list if applicable\n\n"
            + savedTo
            + "You can refer back to it if needed during implementation." + teamHint + "\n\n"
            + "## " + planLabel + ":\n"
            + plan; // CC :481-489
    }

    /**
     * mapToolResult → tool_result 块 · 对齐 CC ExitPlanModeV2Tool.ts:419-491.
     * 从 {@code ToolResult#structuredOutput}（已删；现折入 {@code presentationMeta}）读 V2 契约字段，重建 content（Approved Plan 回显）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr)) {
            return null;
        }
        // ⛔ 错误/拒绝结果必须原样回传：旧实现无视 isError 继续走 approvedPlanEcho(null,…)，
        //   把「用户拒绝」的 tool_result 伪装成 "User has approved exiting plan mode" ——
        //   真机 e2e 实测：用户点拒绝并写意见，模型却收到「已批准」。
        if (isError) {
            String errorContent = tr.data() instanceof String s && !s.isEmpty()
                ? s : "ExitPlanMode failed";
            if (log.isDebugEnabled()) {
                log.debug("[ExitPlanModeTool] mapToToolResultBlockParam: 错误/拒绝结果原样回传 isError=true length={}",
                    errorContent.length());
            }
            return new ToolResultBlockParam(toolUseId, "tool_result", errorContent, true);
        }
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        String plan = so.get("plan") instanceof String s ? s : null;
        boolean isAgent = Boolean.TRUE.equals(so.get("isAgent"));
        boolean hasTaskTool = Boolean.TRUE.equals(so.get("hasTaskTool"));
        boolean planWasEdited = Boolean.TRUE.equals(so.get("planWasEdited"));
        String filePath = so.get("filePath") instanceof String s ? s : null;
        String content = approvedPlanEcho(plan, isAgent, hasTaskTool, planWasEdited, filePath);

        if (log.isDebugEnabled()) {
            log.debug("[ExitPlanModeTool] mapToToolResultBlockParam 生成 tool_result content长度={}（CC ExitPlanModeV2Tool.ts:419-491）",
                content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }
}
