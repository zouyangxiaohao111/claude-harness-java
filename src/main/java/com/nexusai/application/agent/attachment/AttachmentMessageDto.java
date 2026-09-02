package com.nexusai.application.agent.attachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AttachmentMessage · 对齐 CC {@code utils/attachments.ts:3201 createAttachmentMessage()}。
 *
 * <p>CC 源码契约:
 * <pre>
 * export function createAttachmentMessage(attachment: Attachment): AttachmentMessage {
 *   return {
 *     attachment,                     // 内嵌 attachment 对象(union type,含具体字段如 maxTurns/turnCount)
 *     type: 'attachment',             // message.type 固定为 'attachment' (区分普通 message)
 *     uuid: randomUUID(),
 *     timestamp: new Date().toISOString(),
 *   }
 * }
 * </pre>
 *
 * <p>Java 端设计选择 (L3 idiom upgrade):
 * <ul>
 *   <li>CC 用 union type + discriminator 字段;Java record 不支持 union,改为
 *       单 record + nullable 字段 (按需填充)</li>
 *   <li>{@link #type} 字段映射 CC {@code attachment.type} (e.g., 'max_turns_reached', 'hook_cancelled')</li>
 *   <li>{@link #maxTurns} / {@link #turnCount} 仅在 type='max_turns_reached' 时填充</li>
 *   <li>{@link #hookName} / {@link #toolUseID} / {@link #hookEvent} / {@link #blockingError}
 *       仅在 type='hook_*' 时填充 (Session H5 新增, 对齐 CC toolHooks.ts:79-185 5 类 hook attachment)</li>
 *   <li>{@link #skills} 仅在 type='invoked_skills' 时填充 (P1-6-READ-2, 对齐 CC
 *       attachments.ts:646-652 {@code {type:'invoked_skills', skills:[{name,path,content}]}},
 *       压缩后重注入产生;渲染层按 skills() 拼装,content 仅供 UI/日志展示)</li>
 *   <li>{@link #messageType} 固定为 'attachment',便于消费者快速识别(对应 CC message.type)</li>
 * </ul>
 *
 * <p>使用场景:
 * <ul>
 *   <li>MAX_TURNS 退出: AgentLoop yield createAttachmentMessage({type:'max_turns_reached', maxTurns, turnCount})</li>
 *   <li>[Session H5] hook attachment: toolHooks.ts 5 类 (hook_cancelled/hook_blocking_error/
 *       hook_stopped_continuation/hook_additional_context/hook_error_during_execution),
 *       由 HookRegistry 检测条件 -> StreamingToolExecutor 消费侧调 AgentState.appendAttachment 注入消息总线</li>
 *   <li>[P1-6-READ-1/2] invoked_skills attachment: 压缩成功后 CompactContext.createSkillAttachmentIfNeeded
 *       调 {@link #invokedSkills} 构建 (compact.ts:1530-1533),渲染层 AgentLoopContext
 *       renderHookAttachmentForLlm 每轮重注入 LLM (messages.ts:3644-3662)</li>
 * </ul>
 *
 * <p>L1/L2 对齐依据: {@code docs/specs/s01-audit-report-2026-07-15.md} §L1/L2 偏差列表 [P2-2]
 *
 * @see com.nexusai.application.agent.AgentState#appendAttachment(AttachmentMessageDto)
 * @see com.nexusai.application.agent.AgentState#attachments()
 */
public record AttachmentMessageDto(
    /** 唯一 ID · 对齐 CC {@code AttachmentMessage.uuid} */
    String id,
    /** 消息级别类型 · 固定 'attachment' · 对齐 CC {@code AttachmentMessage.type} */
    String messageType,
    /** attachment 内部类型 · 对齐 CC {@code attachment.type} (e.g., 'max_turns_reached', 'hook_cancelled') */
    String type,
    /** 可读文本内容 · 给 UI / 日志展示用,LLM 不直接消费 */
    String content,
    /** 仅 type='max_turns_reached' 时填充 · 对齐 CC query.ts:1708 */
    Integer maxTurns,
    /** 仅 type='max_turns_reached' 时填充 · 对齐 CC query.ts:1709 */
    Integer turnCount,
    /** 时间戳 · 对齐 CC {@code AttachmentMessage.timestamp} (ISO 8601) */
    OffsetDateTime timestamp,
    // ─── [Session H5] hook attachment 专用字段 · 对齐 CC toolHooks.ts:79-185 createAttachmentMessage ───
    /** hook 名 (e.g., 'PreToolUse:Bash') · 对齐 CC attachment.hookName */
    String hookName,
    /** 工具调用 ID · 对齐 CC attachment.toolUseID */
    String toolUseID,
    /** hook 事件名 (PreToolUse/PostToolUse/PostToolUseFailure) · 对齐 CC attachment.hookEvent */
    String hookEvent,
    /** 仅 type='hook_blocking_error' 时填充 · 对齐 CC attachment.blockingError */
    String blockingError,
    // ─── [对抗核验 H13-GAP] hook_non_blocking_error 三字段 · 对齐 CC createAttachmentMessage
    //      (execAgentHook.ts:328-336 / execPromptHook.ts:121-130 stderr/stdout/exitCode) ───
    /** 错误 stderr 文本 · 对齐 CC attachment.stderr（hook_non_blocking_error） */
    String stderr,
    /** LLM 原始响应 / 进程 stdout · 对齐 CC attachment.stdout（hook_non_blocking_error） */
    String stdout,
    /** 退出码 · 对齐 CC attachment.exitCode（hook_non_blocking_error 恒 1） */
    Integer exitCode,
    // ─── [H3 v3 修复] hook_success/hook_blocking_error 载荷对齐 CC (utils/attachments.ts:411-418) ───
    /** 触发 hook 的命令串 · 对齐 CC attachment.command（hook_success / hook_non_blocking_error） */
    String command,
    /** hook 执行耗时(毫秒) · 对齐 CC attachment.durationMs（hook_success / hook_non_blocking_error） */
    Long durationMs,
    // ─── [P1-6-READ-2] invoked_skills attachment 专用字段 · 对齐 CC utils/attachments.ts:646-652 ───
    /** 已调用 skill 引用列表 · 仅 type='invoked_skills' 时填充 · 对齐 CC {@code attachment.skills} */
    java.util.List<SkillRef> skills,
    // ─── [P1-2] dynamic_skill attachment 专用字段 · 对齐 CC utils/attachments.ts:2588-2593 ───
    /** 动态技能附件引用列表 · 仅 type='dynamic_skill' 时填充 · 对齐 CC {@code attachment.skillDir/skillNames/displayPath} */
    java.util.List<DynamicSkillRef> dynamicSkills,
    // ─── [P1-10] skill_listing attachment 专用字段 · 对齐 CC utils/attachments.ts:2743-2750 ───
    /** 本次注入的技能数量 · 仅 type='skill_listing' 时填充 · 对齐 CC {@code skillCount = newSkills.length} (attachments.ts:2747) */
    int skillCount,
    /** 是否首次全量注入 · 仅 type='skill_listing' 时填充 · 对齐 CC {@code isInitial = sent.size===0} (attachments.ts:2748) */
    boolean isInitial,
    // ─── [P3-CROSS-1] post-compact attachment 四类 (compact.ts:531-561) 专用字段 ───
    // 对齐 CC createPostCompactFileAttachments / createPlanAttachmentIfNeeded /
    // createPlanModeAttachmentIfNeeded / createAsyncAgentAttachmentsIfNeeded 构建的
    // 'file' / 'plan_file_reference' / 'plan_mode' / 'task_status' attachment 载荷。
    /** 仅 type='file' 时填充 · 对齐 CC attachment.filename/content/truncated (attachments.ts:3158-3164) */
    FileRef file,
    /** 仅 type='plan_file_reference' 时填充 (planFilePath + planContent) · 对齐 CC compact.ts:1481-1485 */
    PlanRef plan,
    /** 仅 type='plan_mode' 时填充 · 对齐 CC attachment.reminderType ('full'|'sparse') (compact.ts:1553) */
    String reminderType,
    /** 仅 type='plan_mode' 时填充 · 对齐 CC attachment.isSubAgent (compact.ts:1556) */
    boolean isSubAgent,
    /** 仅 type='plan_mode' 时填充 · 对齐 CC attachment.planExists (compact.ts:1557) */
    boolean planExists,
    /** 仅 type='task_status' 时填充 · 对齐 CC attachment.taskId/taskType/description/status/deltaSummary/outputFilePath (compact.ts:1584-1596) */
    TaskStatusRef taskStatus,
    // ─── [C-30] skill_discovery attachment 专用字段 · 对齐 CC utils/attachments.ts:537-540 ───
    /** 发现技能引用列表 · 仅 type='skill_discovery' 时填充 · 对齐 CC attachment.skills [{name,description,shortId?}] (attachments.ts:537) */
    java.util.List<SkillDiscoveryRef> discoveredSkills,
    /** 发现信号 · 仅 type='skill_discovery' 时填充 · 对齐 CC attachment.signal: DiscoverySignal (attachments.ts:538)；shape 未知 → String 承载 */
    String discoverySignal,
    /** 发现来源 · 仅 type='skill_discovery' 时填充 · 对齐 CC attachment.source: 'native'|'aki'|'both' (attachments.ts:539) */
    String discoverySource,
    // ─── [W9-01 OPD-TS-29] tool_use_summary attachment 专用字段 · 对齐 CC createToolUseSummaryMessage ───
    /** 前驱 tool_use id 列表 · 仅 type='tool_use_summary' 时填充 · 对齐 CC {@code precedingToolUseIds}
     *  (utils/messages.ts:5107, query.ts:1437 toolUseIds = toolUseBlocks.map(b=>b.id))；
     *  SDK 出站 snake_case {@code preceding_tool_use_ids}（coreSchemas.ts:1774） */
    java.util.List<String> precedingToolUseIds,
    // ─── [IT-6] structured_output attachment 专用字段 · 对齐 CC toolExecution.ts:1275-1277 ───
    /** 结构化输出载荷 · 仅 type='structured_output' 时填充 · 对齐 CC {@code createAttachmentMessage({type:'structured_output', data})} (toolExecution.ts:1272-1279) */
    Map<String, Object> structuredData,
    // ─── [ER-IMP-2026-04 P-21] output_token_usage attachment 三字段 · 对齐 CC
    //      getOutputTokenUsageAttachment (utils/attachments.ts:3834-3840) 载荷 {turn, session, budget} ───
    /** 本 turn 累计输出 tokens · 仅 type='output_token_usage' 时填充 · 对齐 CC attachment.turn = getTurnOutputTokens() (attachments.ts:3837) */
    Integer outputTokenTurn,
    /** 会话累计输出 tokens · 仅 type='output_token_usage' 时填充 · 对齐 CC attachment.session = getTotalOutputTokens() (attachments.ts:3838) */
    Integer outputTokenSession,
    /** 本 turn token 预算 · 仅 type='output_token_usage' 时填充（null=无预算目标）· 对齐 CC attachment.budget = getCurrentTurnTokenBudget() (attachments.ts:3839) */
    Integer outputTokenBudget,
    // ─── [ER-IMP-2026-05 P-27] tombstone 目标消息 ID · 对齐 CC query.ts:716-722
    //      yield {type:'tombstone', message} 的 message.uuid 等价位 ───
    /** 被 tombstone 的部分 assistant 消息 uuid · 仅 type='tombstone' 时填充 · CC original: message.uuid (query.ts:717) */
    String targetMessageId,
    // ─── [prompt-align CTX-06] token_usage attachment 三字段 · 对齐 CC
    //      getTokenUsageAttachment（utils/attachments.ts:3806-3821）载荷 {used, total, remaining} ───
    /** 已用 tokens · 仅 type='token_usage' 时填充 · CC original: attachment.used = tokenCountFromLastAPIResponse (attachments.ts:3816) */
    Integer tokenUsed,
    /** 模型 context window tokens · 仅 type='token_usage' 时填充 · CC original: attachment.total = getEffectiveContextWindowSize(model) (attachments.ts:3814) */
    Integer tokenTotal,
    /** 剩余 tokens（total-used）· 仅 type='token_usage' 时填充 · CC original: attachment.remaining = total - used (attachments.ts:3818) */
    Integer tokenRemaining,
    // ─── [prompt-align CTX-07] budget_usd attachment 三字段 · 对齐 CC
    //      getMaxBudgetUsdAttachment（utils/attachments.ts:3846-3858）载荷 {used, total, remaining} ───
    /** 已花费 USD · 仅 type='budget_usd' 时填充 · CC original: attachment.used = getTotalCostUSD() (attachments.ts:3850) */
    Integer budgetUsed,
    /** maxBudgetUsd 预算 · 仅 type='budget_usd' 时填充 · CC original: attachment.total = maxBudgetUsd (attachments.ts:3851) */
    Integer budgetTotal,
    /** 剩余预算（total-used）· 仅 type='budget_usd' 时填充 · CC original: attachment.remaining = maxBudgetUsd - used (attachments.ts:3852) */
    Integer budgetRemaining,
    // ─── [prompt-align GLB-08] compact_file_reference attachment 专用字段 · 对齐 CC
    //      messages.ts:3592-3599 normalizeAttachmentForAPI case 'compact_file_reference' ───
    /** 压缩后大文件引用文件名 · 仅 type='compact_file_reference' 时填充 · CC original: attachment.filename (messages.ts:3595) */
    String referenceFilename,
    // ─── [prompt-align GLB-09] pdf_reference attachment 专用字段 · 对齐 CC
    //      messages.ts:3600-3612 case 'pdf_reference' ───
    /** PDF 分页引用 · 仅 type='pdf_reference' 时填充 · CC original: attachment.filename/pageCount/fileSize (messages.ts:3603-3608) */
    PdfRef pdfReference,
    // ─── [prompt-align GLB-01] directory attachment 专用字段 · 对齐 CC
    //      messages.ts:3525-3537 case 'directory' ───
    /** 目录列示引用 · 仅 type='directory' 时填充 · CC original: attachment.path/content (messages.ts:3525-3537) */
    DirectoryRef directory,
    // ─── [prompt-align GLB-02] diagnostics attachment 专用字段 · 对齐 CC
    //      messages.ts:3812-3825 case 'diagnostics' ───
    /** LSP 诊断文件列表 · 仅 type='diagnostics' 时填充 · CC original: attachment.files (messages.ts:3813) */
    java.util.List<DiagnosticsFileRef> diagnosticsFiles,
    // ─── [prompt-align GLB-04] agent_listing_delta attachment 专用字段 · 对齐 CC
    //      messages.ts:4194-4215 case 'agent_listing_delta' ───
    /** agent 类型增删引用 · 仅 type='agent_listing_delta' 时填充 · CC original: attachment.addedLines/removedTypes/isInitial/showConcurrencyNote (messages.ts:4194-4215) */
    AgentListingDeltaRef agentListingDelta,
    // ─── [prompt-align GLB-05] mcp_instructions_delta attachment 专用字段 · 对齐 CC
    //      messages.ts:4216-4231 case 'mcp_instructions_delta' ───
    /** MCP 指令增删引用 · 仅 type='mcp_instructions_delta' 时填充 · CC original: attachment.addedBlocks/removedNames (messages.ts:4216-4231) */
    McpInstructionsDeltaRef mcpInstructionsDelta,
    // ─── [prompt-align GLB-10] CTX-S1..S9 九渲染分支专用字段 · 对齐 CC messages.ts 各 case ───
    /** 新日期 · 仅 type='date_change' 时填充 · CC original: attachment.newDate (messages.ts:4164) */
    String dateChange,
    /** 推理力度级别 · 仅 type='ultrathink_effort' 时填充 · CC original: attachment.level (messages.ts:4170) */
    String ultrathinkLevel,
    /** 提及的 agent 类型 · 仅 type='agent_mention' 时填充 · CC original: attachment.agentType (messages.ts:3949) */
    String agentType,
    /** MCP 资源引用 · 仅 type='mcp_resource' 时填充 · CC original: attachment.server/uri/content.contents (messages.ts:3877-3952) */
    McpResourceRef mcpResource,
    /** IDE 行选择/打开文件/编辑文件引用 · 仅 type='selected_lines_in_ide'/'opened_file_in_ide'/'edited_text_file' 时填充 · CC original: attachment.lineStart/lineEnd/filename/content/snippet (messages.ts:3613-3634/:3538-3543) */
    LineSelectionRef lineSelection,
    // ─── [prompt-align GLB-06] companion_intro attachment 专用字段 · 对齐 CC
    //      messages.ts:4232-4239 + buddy/prompt.ts:7-14 companionIntroText ───
    /** companion 引用 · 仅 type='companion_intro' 时填充 · CC original: attachment.name/species (messages.ts:4236) */
    CompanionRef companion
) {

    /**
     * 紧凑构造器:必填字段不变量保护。
     *
     * <p>WHY: type=null 会让消费者无法按 attachment 类型分支处理
     * ('max_turns_reached' 区别于 'hook_cancelled' 等);messageType=null
     * 会让消费者无法快速区分 attachment 与普通 ChatMessageDto。
     */
    public AttachmentMessageDto {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "AttachmentMessageDto.type is required (e.g., 'max_turns_reached', 'hook_cancelled')");
        }
        if (messageType == null) {
            messageType = "attachment";  // CC 固定值
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = OffsetDateTime.now();
        }
    }

    /**
     * [GLB-10/GLB-06 适配] 47 参兼容构造器 · 匹配 GLB-10 扩参前 canonical 形状
     * （…referenceFilename/pdfReference/directory/diagnosticsFiles/agentListingDelta/mcpInstructionsDelta），
     * 默认新尾部字段（dateChange/ultrathinkLevel/agentType/mcpResource/lineSelection/companion = null）。
     *
     * <p>WHY: GLB-10 追加 5 字段 + GLB-06 追加 companion 使 canonical 47→53 参，本文件内既有工厂
     * （outputTokenUsage/tokenUsage/budgetUsd/compactFileReference/pdfReference/directory/diagnostics/
     * agentListingDelta/mcpInstructionsDelta 等）以旧 47 参形状直接 new canonical 不再命中。本构造器
     * 保留旧 47 参形状并补 6 尾部 null，避免逐一改动这些工厂（外科手术式最小改动）。
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp,
                                String hookName, String toolUseID, String hookEvent, String blockingError,
                                String stderr, String stdout, Integer exitCode, String command,
                                Long durationMs, List<SkillRef> skills, List<DynamicSkillRef> dynamicSkills,
                                int skillCount, boolean isInitial, FileRef file, PlanRef plan,
                                String reminderType, boolean isSubAgent, boolean planExists,
                                TaskStatusRef taskStatus, List<SkillDiscoveryRef> discoveredSkills,
                                String discoverySignal, String discoverySource,
                                List<String> precedingToolUseIds, Map<String, Object> structuredData,
                                Integer outputTokenTurn, Integer outputTokenSession,
                                Integer outputTokenBudget, String targetMessageId,
                                Integer tokenUsed, Integer tokenTotal, Integer tokenRemaining,
                                Integer budgetUsed, Integer budgetTotal, Integer budgetRemaining,
                                String referenceFilename, PdfRef pdfReference, DirectoryRef directory,
                                List<DiagnosticsFileRef> diagnosticsFiles,
                                AgentListingDeltaRef agentListingDelta,
                                McpInstructionsDeltaRef mcpInstructionsDelta) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp, hookName, toolUseID,
            hookEvent, blockingError, stderr, stdout, exitCode, command, durationMs,
            skills, dynamicSkills, skillCount, isInitial,
            file, plan, reminderType, isSubAgent, planExists, taskStatus,
            discoveredSkills, discoverySignal, discoverySource, precedingToolUseIds, structuredData,
            outputTokenTurn, outputTokenSession, outputTokenBudget, targetMessageId,
            tokenUsed, tokenTotal, tokenRemaining, budgetUsed, budgetTotal, budgetRemaining,
            referenceFilename, pdfReference, directory, diagnosticsFiles,
            agentListingDelta, mcpInstructionsDelta,
            null, null, null, null, null, null); // GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * [向后兼容] 11 参构造器 · 既有 hook attachment 调用点专用（stderr/stdout/exitCode/command/
     * durationMs/skills 缺省 null）.
     *
     * <p>WHY (规则三 外科手术式): canonical ctor 持续扩参 (H13-GAP 加 stderr/stdout/exitCode/
     * command/durationMs, P1-6-READ-2 加 skills, P1-10 加 skillCount/isInitial) 后已 20 参. 既有
     * 11 参调用点 (hook_success/hook_cancelled/hook_blocking_error/tool_use_summary 等) 不使用这些字段,
     * 本构造器 delegate 末 9 参缺省 (null×7 + 0/false), 避免改动既有调用点 (非兼容壳, 是最小改动).
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp,
                                String hookName, String toolUseID, String hookEvent, String blockingError) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp, hookName, toolUseID,
            hookEvent, blockingError, null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null); // GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * [向后兼容] 7 参构造器 · 既有 max_turns_reached / todo_reminder / task_reminder /
     * background_task_notification / skill_catalog 等非 hook attachment 调用点专用.
     *
     * <p>WHY (规则三 外科手术式): 既有 4 处 {@code new AttachmentMessageDto(...)} 调用 (LlmAgentLoop)
     * 不使用 hook 字段 / skills 字段, canonical ctor 已 20 参. 本构造器 delegate 末 13 参缺省
     * (null×11 + 0/false), 避免改动既有调用点 (非兼容壳, 是最小改动).
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp,
            null, null, null, null, null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null); // GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * [IT-6] 8 参构造器 · structured_output attachment 专用（hook 字段 / skill 字段缺省 null/0/false）。
     *
     * <p>结构化输出附件由 {@code ToolResultApplier} 在工具结果落地时产出（对齐 CC
     * toolExecution.ts:1272-1279 resultingMessages 构建点）；structuredData 置尾部。
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp,
                                Map<String, Object> structuredData) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp,
            null, null, null, null, null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, structuredData,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null); // GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * [merge 融合桥] 30 参构造器 · 既有直接 new 调用点专用（HaikuToolUseSummaryGenerator.java:122
     * tool_use_summary 直接构造 canonical ctor）· structuredData 缺省 null。
     *
     * <p>WHY (merge ripple): 融合 {@code precedingToolUseIds}(W9-01 OPD-TS-29) +
     * {@code structuredData}(IT-6) 后 canonical ctor 31 参；HaikuToolUseSummaryGenerator 直接 new
     * 30 参，本构造器 delegate 末 1 参缺省 null 避免改动该文件（约束：不修改本文件外其他文件）。
     * 待协调方将该调用迁往 {@link #toolUseSummary} 工厂后可删除本构造器。
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp,
                                String hookName, String toolUseID, String hookEvent, String blockingError,
                                String stderr, String stdout, Integer exitCode, String command,
                                Long durationMs, List<SkillRef> skills, List<DynamicSkillRef> dynamicSkills,
                                int skillCount, boolean isInitial, FileRef file, PlanRef plan,
                                String reminderType, boolean isSubAgent, boolean planExists,
                                TaskStatusRef taskStatus, List<SkillDiscoveryRef> discoveredSkills,
                                String discoverySignal, String discoverySource,
                                List<String> precedingToolUseIds) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp, hookName, toolUseID,
            hookEvent, blockingError, stderr, stdout, exitCode, command, durationMs,
            skills, dynamicSkills, skillCount, isInitial,
            file, plan, reminderType, isSubAgent, planExists, taskStatus,
            discoveredSkills, discoverySignal, discoverySource, precedingToolUseIds, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null); // GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * [ER-IMP-2026-04/05 适配] 31 参兼容构造器 · 保留旧 canonical 调用方形状
     * （…precedingToolUseIds + structuredData），默认 P-21/P-27 新尾部字段
     * （outputTokenTurn/outputTokenSession/outputTokenBudget/targetMessageId = null）。
     *
     * <p>WHY: P-21（+3 字段）与 P-27（+targetMessageId）把 canonical 31→35 参后，本文件内
     * 既有工厂（hookSuccess/hookNonBlockingError/skills/file/plan 等）以 31 参形状直接 new
     * canonical 不再命中；P-27 会话已为 11/7/8/30 参兼容构造器补尾部 4 缺省，31 参形状遗漏，
     * 由本构造器补齐（ER-IMP-2026-03 执行期适配，登记 progress）。
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp,
                                String hookName, String toolUseID, String hookEvent, String blockingError,
                                String stderr, String stdout, Integer exitCode, String command,
                                Long durationMs, List<SkillRef> skills, List<DynamicSkillRef> dynamicSkills,
                                int skillCount, boolean isInitial, FileRef file, PlanRef plan,
                                String reminderType, boolean isSubAgent, boolean planExists,
                                TaskStatusRef taskStatus, List<SkillDiscoveryRef> discoveredSkills,
                                String discoverySignal, String discoverySource,
                                List<String> precedingToolUseIds, Map<String, Object> structuredData) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp, hookName, toolUseID,
            hookEvent, blockingError, stderr, stdout, exitCode, command, durationMs,
            skills, dynamicSkills, skillCount, isInitial,
            file, plan, reminderType, isSubAgent, planExists, taskStatus,
            discoveredSkills, discoverySignal, discoverySource, precedingToolUseIds, structuredData,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null); // P-21 outputTokenTurn/Session/Budget + P-27 targetMessageId + CTX-06 token_usage×3 + CTX-07 budget_usd×3 + GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * 便利工厂 · max_turns_reached 类型 · 对齐 CC query.ts:1706-1710 createAttachmentMessage 调用。
     */
    public static AttachmentMessageDto maxTurnsReached(int maxTurns, int turnCount) {
        return new AttachmentMessageDto(
            null, "attachment", "max_turns_reached",
            "Maximum turns reached: " + maxTurns + " (current: " + turnCount + ")",
            maxTurns, turnCount, null,
            null, null, null, null);
    }

    /**
     * structured_output attachment 工厂 · 对齐 CC toolExecution.ts:1272-1279
     * {@code createAttachmentMessage({type: 'structured_output', data: result.structured_output})}。
     *
     * <p><b>不进 LLM</b>: CC normalizeAttachmentForAPI 对 'structured_output' 返回 []
     * (utils/messages.ts:4258-4261); Java 侧 {@code AgentLoopContext.renderHookAttachmentForLlm}
     * default 分支返回 null 同样不注入. 最终结果由消费方读取 attachment.data
     * (CC QueryEngine.ts:838-840).
     *
     * @param data 结构化输出载荷 (ToolResult.structuredOutput); null/空 → 空 Map 归一
     * @return type='structured_output' 的 attachment
     */
    public static AttachmentMessageDto structuredOutput(Map<String, Object> data) {
        return new AttachmentMessageDto(
            null, "attachment", "structured_output",
            "Structured output provided",
            null, null, null,
            data == null || data.isEmpty() ? Map.of() : data);
    }

    /**
     * output_token_usage attachment 工厂 · 对齐 CC {@code getOutputTokenUsageAttachment()}
     * (utils/attachments.ts:3828-3844) 载荷 {@code {type:'output_token_usage', turn, session, budget}}。
     *
     * <p>content 文案对齐 CC messages.ts:4076-4088 渲染（formatNumber compact 小写 k/m/b，非千分位
     * —— CC utils/format.ts:124-131 {@code Intl.NumberFormat('en-US',{notation:'compact',
     * maxFractionDigits:1, minFractionDigits: n>=1000?1:0}).format(n).toLowerCase()}）：
     * {@code Output tokens — turn: {X / Y|X} · session: {Z}}；budget null 时仅 turn 值。
     * 实际 LLM 注入文案由 {@code AgentLoopContext.renderHookAttachmentForLlm} case
     * 'output_token_usage' 按本三字段渲染（<system-reminder> 包裹），本 content 供 UI/日志展示。
     *
     * @param turn    本 turn 累计输出 tokens（CC getTurnOutputTokens()，attachments.ts:3837）
     * @param session 会话累计输出 tokens（CC getTotalOutputTokens()，attachments.ts:3838）
     * @param budget  本 turn token 预算（null=无预算目标；CC getCurrentTurnTokenBudget()，attachments.ts:3839）
     * @return type='output_token_usage' 的 attachment
     */
    public static AttachmentMessageDto outputTokenUsage(int turn, int session, Integer budget) {
        String turnText = budget != null
            ? formatOutputTokenNumber(turn) + " / " + formatOutputTokenNumber(budget)
            : formatOutputTokenNumber(turn);
        String content = "Output tokens \u2014 turn: " + turnText
            + " \u00b7 session: " + formatOutputTokenNumber(session);
        return new AttachmentMessageDto(
            null, "attachment", "output_token_usage", content,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            turn, session, budget, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

    /**
     * [prompt-align CTX-06] token_usage attachment 工厂 · 对齐 CC {@code getTokenUsageAttachment()}
     * （utils/attachments.ts:3806-3821）载荷 {@code {type:'token_usage', used, total, remaining}}。
     *
     * <p>content 文案对齐 CC messages.ts:4062 渲染（{@code Token usage: {used}/{total}; {remaining} remaining}）
     * 供 UI/日志展示；实际 LLM 注入文案由 {@code AgentLoopContext.renderHookAttachmentForLlm}
     * case 'token_usage' 按本三字段渲染（<system-reminder> 包裹）。
     *
     * @param used      已用 tokens（CC tokenCountFromLastAPIResponse，attachments.ts:3816）
     * @param total     模型 context window（CC getEffectiveContextWindowSize(model)，attachments.ts:3814）
     * @param remaining 剩余 tokens（total-used，attachments.ts:3818）
     * @return type='token_usage' 的 attachment
     */
    public static AttachmentMessageDto tokenUsage(int used, int total, int remaining) {
        String content = "Token usage: " + used + "/" + total + "; " + remaining + " remaining";
        return new AttachmentMessageDto(
            null, "attachment", "token_usage", content,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null,
            used, total, remaining, null, null, null,
            null, null, null, null, null, null);
    }

    /**
     * [prompt-align CTX-07] budget_usd attachment 工厂 · 对齐 CC {@code getMaxBudgetUsdAttachment()}
     * （utils/attachments.ts:3846-3858）载荷 {@code {type:'budget_usd', used, total, remaining}}。
     *
     * <p>content 文案对齐 CC messages.ts:4070 渲染（{@code USD budget: ${used}/${total}; ${remaining} remaining}）
     * 供 UI/日志展示；实际 LLM 注入文案由 {@code AgentLoopContext.renderHookAttachmentForLlm}
     * case 'budget_usd' 按本三字段渲染。Java 无 maxBudgetUsd 配置源（全仓 grep 零命中）→ 消费点
     * 登记 N/A，本工厂保留供未来 producer 接线。
     *
     * @param used      已花费 USD（CC getTotalCostUSD()，attachments.ts:3850）
     * @param total     maxBudgetUsd 预算（attachments.ts:3851）
     * @param remaining 剩余预算（maxBudgetUsd-used，attachments.ts:3852）
     * @return type='budget_usd' 的 attachment
     */
    public static AttachmentMessageDto budgetUsd(int used, int total, int remaining) {
        String content = "USD budget: $" + used + "/$" + total + "; $" + remaining + " remaining";
        return new AttachmentMessageDto(
            null, "attachment", "budget_usd", content,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, used, total, remaining,
            null, null, null, null, null, null);
    }

    /**
     * output token 数 compact 格式化 · 对齐 CC {@code formatNumber}
     * (utils/format.ts:124-131)：{@code Intl.NumberFormat('en-US', {notation:'compact',
     * maximumFractionDigits:1, minimumFractionDigits: number>=1000 ? 1 : 0})} +
     * {@code toLowerCase()} —— "1321"→"1.3k"、"2000"→"2.0k"、"900"→"900"。
     * 供 {@link #outputTokenUsage} 与 AgentLoopContext render case 共用（单源，防双实现漂移）。
     *
     * @param n token 数（≥0）
     * @return compact 小写格式（k/m/b）
     */
    public static String formatOutputTokenNumber(long n) {
        if (n < 1000) {
            return Long.toString(n);
        }
        double scaled;
        char suffix;
        if (n < 1_000_000) {
            scaled = n / 1_000.0;
            suffix = 'k';
        } else if (n < 1_000_000_000) {
            scaled = n / 1_000_000.0;
            suffix = 'm';
        } else {
            scaled = n / 1_000_000_000.0;
            suffix = 'b';
        }
        String s = String.format("%.1f", scaled);
        if (s.startsWith("1000.0")) {
            // %.1f 四舍五入越界（999,951 → "1000.0k"）→ 升级单位（对齐 Intl compact 最短表示 "1.0m"）
            return suffix == 'k' ? "1.0m" : suffix == 'm' ? "1.0b" : "1000.0b";
        }
        return s + suffix;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Session H5] 5 类 hook attachment 工厂 · 对齐 CC toolHooks.ts:79-185
    // CC runPreToolUseHooks/runPostToolUseHooks/runPostToolUseFailureHooks yield 5 类
    // createAttachmentMessage attachment. Java 端消费侧 (StreamingToolExecutor) 检测 hook
    // 结果条件后调 AgentState.appendAttachment 注入消息总线.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook_cancelled attachment · 对齐 CC toolHooks.ts:79-87 / :234-242.
     *
     * <p>触发: abort 期间 hook 执行被取消 (CC executeHooks yield {message: hook_cancelled}).
     *
     * @param hookName  hook 名 (e.g., 'PreToolUse:Bash')
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名 (PreToolUse/PostToolUse/PostToolUseFailure)
     */
    public static AttachmentMessageDto hookCancelled(String hookName, String toolUseID, String hookEvent) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_cancelled",
            "Hook cancelled: " + hookName,
            null, null, null,
            hookName, toolUseID, hookEvent, null);
    }

    /**
     * hook_blocking_error attachment · 对齐 CC toolHooks.ts:105-115 / :257-267.
     *
     * <p>触发: hook 返回 blockingError (JSON {decision:"block"} 或 exit-code-2 路径).
     *
     * @param hookName      hook 名
     * @param toolUseID     工具调用 ID
     * @param hookEvent     hook 事件名
     * @param blockingError 阻塞错误文本 (注入 LLM 作为 feedback)
     */
    public static AttachmentMessageDto hookBlockingError(String hookName, String toolUseID,
                                                         String hookEvent, String blockingError) {
        return hookBlockingError(hookName, toolUseID, hookEvent, blockingError, null);
    }

    /**
     * [H3 v3 修复] 5 参重载 · 补 {@code command} 载荷 · 对齐 CC utils/attachments.ts:350-356
     * {@code hook_blocking_error} attachment {@code blockingError: {blockingError, command}}.
     *
     * <p>WHY (Gap 3): CC processHookJSONOutput (hooks.ts:710-715) 生成的 hook_blocking_error
     * attachment 内嵌 {@code blockingError.command}; Java 端此前 4 参版本丢弃 command.
     *
     * @param hookName      hook 名
     * @param toolUseID     工具调用 ID
     * @param hookEvent     hook 事件名
     * @param blockingError 阻塞错误文本 (注入 LLM 作为 feedback)
     * @param command       触发阻塞的 hook 命令串 (CC blockingError.command, 审计/UI 展示用)
     */
    public static AttachmentMessageDto hookBlockingError(String hookName, String toolUseID,
                                                         String hookEvent, String blockingError,
                                                         String command) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_blocking_error",
            blockingError,
            null, null, null,
            hookName, toolUseID, hookEvent, blockingError,
            null, null, null, command, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

    /**
     * hook_stopped_continuation attachment · 对齐 CC toolHooks.ts:118-130.
     *
     * <p>触发: PostToolUse hook 返回 preventContinuation=true (仅 PostToolUse, PostToolUseFailure 无此分支).
     *
     * @param hookName  hook 名
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名
     * @param message   停止原因 (stopReason, 默认 'Execution stopped by PostToolUse hook')
     */
    public static AttachmentMessageDto hookStoppedContinuation(String hookName, String toolUseID,
                                                               String hookEvent, String message) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_stopped_continuation",
            message != null ? message : "Execution stopped by PostToolUse hook",
            null, null, null,
            hookName, toolUseID, hookEvent, null);
    }

    /**
     * hook_additional_context attachment · 对齐 CC toolHooks.ts:133-143 / :270-280 / :566-578.
     *
     * <p>触发: hook 返回 additionalContexts (注入 LLM 的附加上下文).
     *
     * @param hookName  hook 名
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名
     * @param contexts  附加上下文列表 (CC additionalContexts string[])
     */
    public static AttachmentMessageDto hookAdditionalContext(String hookName, String toolUseID,
                                                             String hookEvent, List<String> contexts) {
        String content = contexts == null || contexts.isEmpty()
            ? "" : String.join("\n", contexts);
        return new AttachmentMessageDto(
            null, "attachment", "hook_additional_context",
            content,
            null, null, null,
            hookName, toolUseID, hookEvent, null);
    }

    /**
     * [Session H8] hook_user_message attachment · 对齐 CC runPreToolUseHooks
     * {@code if (result.message) { yield { type: 'message', message: { message: result.message } } }}
     * (toolHooks.ts:478-480) → resultingMessages → LLM 可见.
     *
     * <p>WHY (H 系列遗留 B-R5): H6 前 {@code AHR.message()} 只写不读 (StreamingToolExecutor
     * L1235-1240 死字段注记), hook 想给 LLM 的用户可见消息静默丢失. 本工厂把 message
     * 转成 attachment 经与 hook_additional_context 同一条交付通道
     * ({@code AgentState.appendAttachment} → state.attachments() → LlmAgentLoop 注入上下文),
     * 让 hook message 真正到达 LLM.
     *
     * <p>type 命名 {@code hook_user_message}: CC 无独立 attachment 类型 (message 是
     * 普通消息非 attachment), Java 端统一走 attachment 通道, 用本类型与既有 5 类
     * hook_* attachment 区分 (消费侧按 type 分支).
     *
     * @param hookName  hook 名 (e.g., 'PreToolUse:Bash')
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名 (PreToolUse/PostToolUse/PostToolUseFailure)
     * @param content   hook 返回的用户可见消息 (CC original: result.message, toolHooks.ts:479)
     */
    public static AttachmentMessageDto hookUserMessage(String hookName, String toolUseID,
                                                       String hookEvent, String content) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_user_message",
            content,
            null, null, null,
            hookName, toolUseID, hookEvent, null);
    }

    /**
     * hook_error_during_execution attachment · 对齐 CC toolHooks.ts:177-185 / :305-313 / :630-641.
     *
     * <p>触发: hook 执行抛异常 (catch error -> yield hook_error_during_execution).
     *
     * @param hookName  hook 名
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名
     * @param error     错误文本 (formatError(error))
     */
    public static AttachmentMessageDto hookErrorDuringExecution(String hookName, String toolUseID,
                                                                String hookEvent, String error) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_error_during_execution",
            error,
            null, null, null,
            hookName, toolUseID, hookEvent, null);
    }

    /**
     * [IMP-HOOKS-S6 CCJ-T6-19] hook_system_message attachment · 对齐 CC hooks.ts:2770-2780
     * {@code createAttachmentMessage({type:'hook_system_message', content: result.systemMessage,
     * hookName, toolUseID, hookEvent})}.
     *
     * <p>触发: hook JSON 输出含 {@code systemMessage} 字段 (hook 想给用户/转录层的系统级提示).
     * 不进 LLM API 上下文 (CC normalizeAttachmentForAPI messages.ts:4258 返回 []), Java 端
     * AgentLoopContext 既有排除逻辑覆盖 (hook_system_message → null).
     *
     * @param hookName  hook 名 (e.g., 'PreToolUse:Bash')
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名 (PreToolUse/PostToolUse/PostToolUseFailure)
     * @param content   systemMessage 文本 (CC original: result.systemMessage)
     */
    public static AttachmentMessageDto hookSystemMessage(String hookName, String toolUseID,
                                                         String hookEvent, String content) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_system_message",
            content,
            null, null, null,
            hookName, toolUseID, hookEvent, null);
    }

    /**
     * [IMP-HOOKS-S6 CCJ-T6-18] hook_permission_decision attachment · 对齐 CC toolExecution.ts:979-993
     * {@code createAttachmentMessage({type:'hook_permission_decision', decision:
     * permissionDecision.behavior, toolUseID, hookEvent:'PermissionRequest'})}.
     *
     * <p>触发: PermissionRequest hook 的 allow/deny 决策被最终权限决策采纳 (behavior != 'ask').
     * Java 端 AttachmentMessageDto 无 decision 专用字段, 决策值经 content 承载 (decision
     * 值域仅 allow/deny 两值, 消费侧按 content 读取; 差异登记于 IMP-HOOKS-S6-progress.md).
     * 不进 LLM API 上下文 (CC normalizeAttachmentForAPI messages.ts:4258 返回 []).
     *
     * @param decision  PermissionRequest hook 决策 (CC original: permissionDecision.behavior,
     *                  'allow' | 'deny')
     * @param toolUseID 工具调用 ID
     */
    public static AttachmentMessageDto hookPermissionDecision(String decision, String toolUseID) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_permission_decision",
            decision,
            null, null, null,
            "PermissionRequest", toolUseID, "PermissionRequest", null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Session H13] hook_success / hook_non_blocking_error attachment 工厂
    // 对齐 CC execAgentHook.ts:296-302/328-336 + execPromptHook.ts:121-130/175-182 createAttachmentMessage.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook_success attachment · 对齐 CC execAgentHook.ts:296-302 + execPromptHook.ts:175-182.
     *
     * <p>CC original:
     * {@code createAttachmentMessage({type:'hook_success', hookName, toolUseID, hookEvent, content:''})}。
     * 由 ExecAgentHook / ExecPromptHook success 结果返回，供前端/审计呈现 hook 成功。
     *
     * @param hookName  hook 名
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名
     */
    public static AttachmentMessageDto hookSuccess(String hookName, String toolUseID, String hookEvent) {
        return hookSuccess(hookName, toolUseID, hookEvent, "", null, null, null, null, null);
    }

    /**
     * [H3 v3 修复] 9 参重载 · 补 stdout/stderr/exitCode/command/durationMs 载荷 ·
     * 对齐 CC utils/attachments.ts:411-418 {@code HookSuccessAttachment}.
     *
     * <p>WHY (Gap 3): CC processHookJSONOutput (hooks.ts:716-736) 生成的 hook_success attachment
     * 携带 {@code stdout/stderr/exitCode/command/durationMs}; Java 端此前 3 参版本只有
     * content:'' — 审计/UI 看不到 hook 成功时的进程输出与耗时.
     *
     * @param hookName   hook 名
     * @param toolUseID  工具调用 ID
     * @param hookEvent  hook 事件名
     * @param content    展示文本 (CC content, hook_success 通常 '')
     * @param stdout     进程 stdout (CC attachment.stdout)
     * @param stderr     进程 stderr (CC attachment.stderr)
     * @param exitCode   退出码 (CC attachment.exitCode)
     * @param command    触发 hook 的命令串 (CC attachment.command)
     * @param durationMs hook 执行耗时毫秒 (CC attachment.durationMs)
     */
    public static AttachmentMessageDto hookSuccess(String hookName, String toolUseID,
                                                   String hookEvent, String content, String stdout,
                                                   String stderr, Integer exitCode, String command,
                                                   Long durationMs) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_success",
            content != null ? content : "",
            null, null, null,
            hookName, toolUseID, hookEvent, null,
            stderr, stdout, exitCode, command, durationMs, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null);
    }

    /**
     * hook_non_blocking_error attachment · 对齐 CC execAgentHook.ts:328-336 + execPromptHook.ts:121-130.
     *
     * <p>CC original:
     * {@code createAttachmentMessage({type:'hook_non_blocking_error', hookName, toolUseID, hookEvent, stderr, stdout, exitCode:1})}。
     *
     * <p>[对抗核验 H13-GAP 修复] Java 现承载 stderr/stdout/exitCode 三字段（旧实现仅 content=stderr,
     * stdout/exitCode 丢弃）。content 仍映射 CC stderr 文本（保持 UI/审计可读）。
     *
     * @param hookName  hook 名
     * @param toolUseID 工具调用 ID
     * @param hookEvent hook 事件名
     * @param stderr    错误 stderr 文本（CC attachment.stderr）
     * @param stdout    LLM 原始响应 / 进程 stdout（CC attachment.stdout）
     * @param exitCode  退出码（CC attachment.exitCode，hook 失败恒 1）
     */
    public static AttachmentMessageDto hookNonBlockingError(String hookName, String toolUseID,
                                                            String hookEvent, String stderr, String stdout,
                                                            int exitCode) {
        return hookNonBlockingError(hookName, toolUseID, hookEvent, stderr, stdout, exitCode, null, null);
    }

    /**
     * [H3 v3 修复] 8 参重载 · 补 {@code command}/{@code durationMs} 载荷 · 对齐 CC
     * utils/attachments.ts:425-437 {@code HookNonBlockingErrorAttachment}.
     *
     * <p>WHY: CC hook_non_blocking_error attachment 也携带 command/durationMs (hooks.ts:2721-2724 /
     * execAgentHook.ts:328-336); 既有 6 参版本丢弃这两个字段.
     *
     * @param command    触发 hook 的命令串 (CC attachment.command)
     * @param durationMs hook 执行耗时毫秒 (CC attachment.durationMs)
     */
    public static AttachmentMessageDto hookNonBlockingError(String hookName, String toolUseID,
                                                            String hookEvent, String stderr, String stdout,
                                                            int exitCode, String command, Long durationMs) {
        return new AttachmentMessageDto(
            null, "attachment", "hook_non_blocking_error",
            stderr != null ? stderr : "",
            null, null, null,
            hookName, toolUseID, hookEvent, null,
            stderr, stdout, exitCode, command, durationMs, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null);
    }

    /**
     * [IMPL-06 OD-EX-04] 注入 command/durationMs · 对齐 CC hooks.ts:2241-2250/2281-2290
     * {@code att.command = hookCommand; att.durationMs = Date.now() - hookStartMs}（仅
     * hook_success / hook_non_blocking_error 两类 attachment）。
     *
     * <p>record 不可变 → 返回携带新 command/durationMs 的副本（其余字段原样）。
     *
     * @param att        原 attachment（hook_success / hook_non_blocking_error）
     * @param command    触发 hook 的命令串（CC getHookDisplayText：statusMessage ?? prompt/url/command）
     * @param durationMs hook 执行耗时毫秒（CC Date.now() - hookStartMs）
     * @return 副本（command/durationMs 已替换）
     */
    public static AttachmentMessageDto withCommandAndDuration(AttachmentMessageDto att,
                                                              String command, Long durationMs) {
        return new AttachmentMessageDto(
            att.id(), att.messageType(), att.type(), att.content(),
            att.maxTurns(), att.turnCount(), att.timestamp(),
            att.hookName(), att.toolUseID(), att.hookEvent(), att.blockingError(),
            att.stderr(), att.stdout(), att.exitCode(),
            command, durationMs,
            att.skills(), att.dynamicSkills(), att.skillCount(), att.isInitial(),
            att.file(), att.plan(), att.reminderType(), att.isSubAgent(), att.planExists(),
            att.taskStatus(), att.discoveredSkills(), att.discoverySignal(), att.discoverySource(),
            att.precedingToolUseIds(), att.structuredData(),
            att.outputTokenTurn(), att.outputTokenSession(), att.outputTokenBudget(),
            att.targetMessageId(),
            att.tokenUsed(), att.tokenTotal(), att.tokenRemaining(),
            att.budgetUsed(), att.budgetTotal(), att.budgetRemaining(),
            att.referenceFilename(), att.pdfReference(), att.directory(),
            att.diagnosticsFiles(), att.agentListingDelta(), att.mcpInstructionsDelta());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [W9-02 OPD-TS-31] 入站 tool_use_summary attachment 工厂
    // 出站生产：HaikuToolUseSummaryGenerator 构建同型 attachment（type='tool_use_summary'）。
    // 入站联动：SDKMessageAdapter 解析出的 summary + precedingToolUseIds → 本工厂 →
    //           AgentState.appendAttachment。渲染层 renderHookAttachmentForLlm default
    //           返回 null → 仅供 transcript/UI 可观测，不喂 LLM（对齐 CC 出站附件通道语义）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 入站 tool_use_summary attachment 工厂 · 对齐 CC {@code createToolUseSummaryMessage}
     * （utils/messages.ts:5105-5116）。
     *
     * <p>WHY (规则九 · 测试验证意图)：出站 Haiku 摘要与入站联动必须产同一型 attachment，
     * 消费侧才能按 type='tool_use_summary' 统一分支；content 填摘要文本（UI/日志展示），
     * precedingToolUseIds 承载 SDK snake_case {@code preceding_tool_use_ids}（coreSchemas.ts:1774）。
     *
     * @param summary             工具批摘要文本（CC original: summary, messages.ts:5106）
     * @param precedingToolUseIds 前驱 tool_use id（CC original: precedingToolUseIds, messages.ts:5107）
     * @return type='tool_use_summary' 的 attachment
     */
    public static AttachmentMessageDto toolUseSummary(String summary,
                                                      List<String> precedingToolUseIds) {
        return new AttachmentMessageDto(
            null, "attachment", "tool_use_summary", summary != null ? summary : "", null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, 0, false, null, null, null, false, false, null,
            null, null, null, precedingToolUseIds, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P1-6-READ-2] invoked_skills attachment 工厂 + SkillRef 嵌套 record
    // 对齐 CC services/compact/compact.ts:1530-1533 createAttachmentMessage 调用 +
    // utils/attachments.ts:646-652 union member {type:'invoked_skills', skills:[{name,path,content}]}
    // ════════════════════════════════════════════════════════════════════════

    /**
     * invoked_skills attachment 工厂 · 对齐 CC services/compact/compact.ts:1530-1533
     * {@code createAttachmentMessage({type:'invoked_skills', skills})} + attachments.ts:646-652.
     *
     * <p>由 {@code CompactContext.createSkillAttachmentIfNeeded} (READ-1) 压缩成功后调用,
     * 把本次会话已调用 skill 引用打包为 attachment, 渲染层 (AgentLoopContext
     * {@code renderHookAttachmentForLlm}) 每轮重注入 LLM (messages.ts:3644-3662).
     *
     * @param skills 已调用 skill 引用 (name/path/content), content 已由 READ-1 truncateToTokens
     *               截断至 5000 tokens;空/ null → 渲染层不注入 (CC messages.ts:3645-3646 return [])
     * @return type='invoked_skills' 的 attachment;content 填可读摘要 (UI/日志展示用, LLM 渲染走
     *         skills() 不消费 content, CC attachment 无 content 字段)
     */
    public static AttachmentMessageDto invokedSkills(List<SkillRef> skills) {
        String summary = skills == null ? "Invoked skills: 0" : "Invoked skills: " + skills.size();
        return new AttachmentMessageDto(
            null, "attachment", "invoked_skills",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            skills, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null);
    }

    /**
     * [P1-2] dynamic_skill attachment 工厂 · 对齐 CC utils/attachments.ts:2547-2601
     * {@code getDynamicSkillAttachments} 构建的 {@code {type:'dynamic_skill', skillDir, skillNames, displayPath}}
     * （attachments.ts:2588-2593）。
     *
     * <p><b>用途</b>：文件工具（Write/Edit/Read）发现的新技能目录，在 per-turn attachment 装配时打包为
     * transcript/UI 可见的附件记录（CC messages.ts:3723-3727 明确 dynamic_skill 仅供 UI —— 技能本身
     * 已加载并可经 Skill tool 使用，故 Java 渲染层 {@code renderHookAttachmentForLlm} default 分支不注入
     * LLM，与本工厂的 content 仅供 UI 展示语义一致）。
     *
     * @param skillDir    技能目录绝对路径（CC original: skillDir）
     * @param skillNames  目录下含 SKILL.md 的子目录名（CC original: skillNames）
     * @param displayPath 相对 cwd 的展示路径（CC original: displayPath = relative(getCwd(), skillDir)）
     * @return type='dynamic_skill' 的 attachment；content 填可读摘要（UI/日志展示用）
     */
    public static AttachmentMessageDto dynamicSkill(String skillDir, List<String> skillNames, String displayPath) {
        String summary = (skillNames == null || skillNames.isEmpty())
            ? "Dynamic skill dir: " + displayPath
            : "Dynamic skills discovered in " + displayPath + ": " + String.join(", ", skillNames);
        return new AttachmentMessageDto(
            null, "attachment", "dynamic_skill",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null,
            java.util.List.of(new DynamicSkillRef(skillDir, skillNames, displayPath)),
            0, false,
            null, null, null, false, false, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P1-10] skill_listing attachment 工厂 · 对齐 CC utils/attachments.ts:2743-2750
    //   getSkillListingAttachments 返回 [{type:'skill_listing', content: formatCommandsWithinBudget(newSkills),
    //   skillCount: newSkills.length, isInitial}]。由 LlmAgentLoop 每轮经 computeSkillListingDelta
    //   （按 skill name 增量 dedup）算出 newSkills 后调用，渲染层 renderHookAttachmentForLlm
    //   case 'skill_listing' 渲染为 user message（CC messages.ts:3728-3738）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * skill_listing attachment 工厂 · 对齐 CC utils/attachments.ts:2743-2750
     * {@code {type:'skill_listing', content, skillCount: newSkills.length, isInitial}}.
     *
     * @param content    预算内技能清单文本（CC original: content = formatCommandsWithinBudget(newSkills, ...)）
     * @param skillCount 本次注入技能数（CC original: skillCount = newSkills.length）
     * @param isInitial  是否首次全量注入（CC original: isInitial = sent.size === 0）
     * @return type='skill_listing' 的 attachment；渲染层注入 LLM 作为 user message
     */
    public static AttachmentMessageDto skillListing(String content, int skillCount, boolean isInitial) {
        return new AttachmentMessageDto(
            null, "attachment", "skill_listing",
            content,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null,
            skillCount, isInitial,
            null, null, null, false, false, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [C-30] skill_discovery attachment 工厂 + SkillDiscoveryRef 嵌套 record
    // 对齐 CC utils/attachments.ts:537-540 union member {type:'skill_discovery',
    // skills:[{name,description,shortId?}], signal: DiscoverySignal, source:'native'|'aki'|'both'}
    // ════════════════════════════════════════════════════════════════════════

    /**
     * skill_discovery attachment 工厂 · 对齐 CC utils/attachments.ts:537-540
     * {@code {type:'skill_discovery', skills: [{name, description, shortId?}], signal: DiscoverySignal, source}}.
     *
     * <p>由 {@code SkillSearchPrefetch.collectSkillDiscoveryPrefetch}（CC query.ts:1620-1628，工具循环后收集）
     * 非空结果在 LlmAgentLoop 转 {@link SkillDiscoveryRef} 后注入；渲染层 AgentLoopContext
     * {@code renderHookAttachmentForLlm} case 'skill_discovery' 注入 LLM（CC messages.ts:3503-3520）。
     * C-30 占位实现恒空集 → 本工厂当前无生产调用点。
     *
     * @param skills 发现技能引用（CC original: attachment.skills）
     * @param signal 发现信号（CC original: attachment.signal，DiscoverySignal shape 未知 → String 承载）
     * @param source 发现来源（CC original: attachment.source，'native'|'aki'|'both'）
     * @return type='skill_discovery' 的 attachment
     */
    public static AttachmentMessageDto skillDiscovery(java.util.List<SkillDiscoveryRef> skills,
                                                      String signal, String source) {
        String summary = (skills == null || skills.isEmpty())
            ? "Skill discovery: 0"
            : "Skill discovery: " + skills.size();
        return new AttachmentMessageDto(
            null, "attachment", "skill_discovery",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null,
            skills, signal, source, null, null);
    }

    /**
     * 单条发现技能引用 · 对齐 CC {@code skill_discovery} attachment skills 元素
     * (utils/attachments.ts:537, {@code {name, description, shortId?}}).
     *
     * @param name        技能名（CC original: s.name）
     * @param description 技能描述（CC original: s.description）
     * @param shortId     短 ID，可选（CC original: s.shortId）
     */
    public record SkillDiscoveryRef(String name, String description, String shortId) {}

    /**
     * 单条 invoked skill 引用 · 对齐 CC {@code invoked_skills} attachment skills 元素
     * (utils/attachments.ts:647-650, {@code {name, path, content}}).
     *
     * @param name    skill 名 (CC original: skill.skillName, compact.ts:1509)
     * @param path    skill 文件路径 (CC original: skill.skillPath, compact.ts:1510)
     * @param content skill 内容(已由 READ-1 truncateToTokens 截断至
     *                POST_COMPACT_MAX_TOKENS_PER_SKILL=5_000, compact.ts:1511-1514)
     */
    public record SkillRef(String name, String path, String content) {}

    /**
     * 单条动态 skill 引用 · 对齐 CC {@code dynamic_skill} attachment
     * (utils/attachments.ts:2588-2593, {@code {type:'dynamic_skill', skillDir, skillNames, displayPath}}).
     *
     * @param skillDir    技能目录绝对路径（CC original: attachment.skillDir）
     * @param skillNames  目录下含 SKILL.md 的子目录名（CC original: attachment.skillNames）
     * @param displayPath 相对 cwd 的展示路径（CC original: attachment.displayPath =
     *                    relative(getCwd(), skillDir)）
     */
    public record DynamicSkillRef(String skillDir, List<String> skillNames, String displayPath) {}

    // ════════════════════════════════════════════════════════════════════════
    // [P3-CROSS-1] post-compact attachment 四类工厂 + 嵌套 record
    // 对齐 CC services/compact/compact.ts:531-561 全量压缩成功路径 postCompactFileAttachments 重建
    // (createPostCompactFileAttachments / createPlanAttachmentIfNeeded /
    //  createPlanModeAttachmentIfNeeded / createAsyncAgentAttachmentsIfNeeded)。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * file attachment 工厂 · 对齐 CC {@code createPostCompactFileAttachments}
     * (services/compact/compact.ts:1415-1464) 生成的 {@code {type:'file', filename, content, truncated}}
     * (attachments.ts:3158-3164)。
     *
     * <p>压缩后重注入最近读取的文件内容；渲染层 AgentLoopContext
     * {@code renderHookAttachmentForLlm} case 'file' 渲染为 meta user message
     * (CC messages.ts:3545-3590 tool_use+tool_result 对的 Java 端降级形态)。
     *
     * @param file 文件引用 (filename/content/truncated)；content 已由 PostCompactAttachmentRestorer
     *             restoreFileAttachments 截断至 POST_COMPACT_MAX_TOKENS_PER_FILE=5_000 (compact.ts:124)
     * @return type='file' 的 attachment
     */
    public static AttachmentMessageDto fileAttachment(FileRef file) {
        String summary = "File: " + (file != null ? file.filename() : "?")
            + (file != null && file.truncated() ? " (truncated)" : "");
        return new AttachmentMessageDto(
            null, "attachment", "file",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            file, null, null, false, false, null, null, null, null, null, null);
    }

    /**
     * [prompt-align GLB-08] compact_file_reference attachment 工厂 · 对齐 CC
     * utils/messages.ts:3592-3599 normalizeAttachmentForAPI case 'compact_file_reference'
     * （CC attachment 载荷 {@code {type:'compact_file_reference', filename}}）。
     *
     * <p>压缩后大文件引用提示：模型若需访问该文件可用 Read tool。渲染层 AgentLoopContext
     * {@code renderHookAttachmentForLlm} case 'compact_file_reference' 渲染为 meta user message
     * （CC messages.ts:3592-3599）。归 compact 域：Java 无 compact_file_reference producer
     * （压缩后大文件引用走既有 file case）→ render 防御保留，producer 接线登记 compact 域未来。
     *
     * @param filename 大文件路径（CC original: attachment.filename, messages.ts:3595）
     * @return type='compact_file_reference' 的 attachment
     */
    public static AttachmentMessageDto compactFileReference(String filename) {
        String summary = "Compact file reference: " + (filename != null ? filename : "?");
        return new AttachmentMessageDto(
            null, "attachment", "compact_file_reference",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            filename, null, null, null, null, null);
    }

    /**
     * [prompt-align GLB-09] pdf_reference attachment 工厂 · 对齐 CC
     * utils/messages.ts:3600-3612 normalizeAttachmentForAPI case 'pdf_reference'
     * （CC attachment 载荷 {@code {type:'pdf_reference', filename, pageCount, fileSize}}）。
     *
     * <p>PDF 分页阅读指令提示。渲染层 AgentLoopContext {@code renderHookAttachmentForLlm}
     * case 'pdf_reference' 渲染为 meta user message（CC messages.ts:3600-3612，含
     * formatFileSize 等价格式化 fileSize）。归 attachments/pdf 域：Java 有 PDF 上传通道
     * 但无该注入提示 → render 防御保留，producer 接线登记 pdf 域未来。
     *
     * @param filename   PDF 文件路径（CC original: attachment.filename, messages.ts:3604）
     * @param pageCount  PDF 总页数（CC original: attachment.pageCount, messages.ts:3604）
     * @param fileSize   PDF 文件字节数（CC original: attachment.fileSize, messages.ts:3604）
     * @return type='pdf_reference' 的 attachment
     */
    public static AttachmentMessageDto pdfReference(String filename, int pageCount, long fileSize) {
        String summary = "PDF reference: " + (filename != null ? filename : "?")
            + " (" + pageCount + " pages, " + fileSize + " bytes)";
        return new AttachmentMessageDto(
            null, "attachment", "pdf_reference",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, new PdfRef(filename, pageCount, fileSize), null, null, null, null);
    }

    /**
     * [prompt-align GLB-01] directory attachment 工厂 · 对齐 CC
     * utils/messages.ts:3525-3537 normalizeAttachmentForAPI case 'directory'
     * （CC attachment 载荷 {@code {type:'directory', path, content}}）。
     *
     * <p>目录列示引用：CC 原渲染为 Bash ls tool_use + tool_result 对（messages.ts:3527-3535），
     * Java provider 不支持经 attachment 通道注入 tool_use/tool_result 对 → 渲染层降级为
     * meta user message（镜像 P3-CROSS-1 file case 降级范式）。Java 无目录附件通道（生产零
     * producer）→ render 防御保留，登记 N/A（低影响，register 已注）。
     *
     * @param path    目录路径（CC original: attachment.path, messages.ts:3528）
     * @param content 目录列示内容（CC original: attachment.content = Bash ls stdout, messages.ts:3532）
     * @return type='directory' 的 attachment
     */
    public static AttachmentMessageDto directory(String path, String content) {
        String summary = "Directory: " + (path != null ? path : "?");
        return new AttachmentMessageDto(
            null, "attachment", "directory",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, new DirectoryRef(path, content), null, null, null);
    }

    /**
     * [prompt-align GLB-02] diagnostics attachment 工厂 · 对齐 CC
     * utils/messages.ts:3812-3825 normalizeAttachmentForAPI case 'diagnostics'
     * （CC attachment 载荷 {@code {type:'diagnostics', files: [{uri, diagnostics: [...]}]}}）。
     *
     * <p>LSP 诊断变化注入提示：渲染层 AgentLoopContext {@code renderHookAttachmentForLlm}
     * case 'diagnostics' 经 formatDiagnosticsSummary 等价格式化后注入为 meta user message
     * （CC messages.ts:3812-3825 + services/diagnosticTracking.ts:352-394）。归 LSP 域：
     * Java 有 LspDiagnosticRegistry 基建但无该 attachment 渲染与 producer → render 防御保留，
     * producer 接线登记 LSP 域未来。空/ null 列表 → 渲染层不注入（CC :3813 files.length===0 → []）。
     *
     * @param files 诊断文件列表（CC original: attachment.files, messages.ts:3813；
     *              uri + diagnostics[{message,severity,line,character,code,source}]）
     * @return type='diagnostics' 的 attachment
     */
    public static AttachmentMessageDto diagnostics(java.util.List<DiagnosticsFileRef> files) {
        int count = files == null ? 0
            : files.stream().mapToInt(f -> f.diagnostics() == null ? 0 : f.diagnostics().size()).sum();
        String summary = "Diagnostics: " + count;
        return new AttachmentMessageDto(
            null, "attachment", "diagnostics",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, files, null, null);
    }

    /**
     * [prompt-align GLB-04] agent_listing_delta attachment 工厂 · 对齐 CC
     * utils/messages.ts:4194-4215 normalizeAttachmentForAPI case 'agent_listing_delta'
     * （CC attachment 载荷 {@code {type:'agent_listing_delta', addedLines, removedTypes,
     * isInitial, showConcurrencyNote}}）。
     *
     * <p>agent 类型增删事件动态渲染。渲染层 AgentLoopContext {@code renderHookAttachmentForLlm}
     * case 'agent_listing_delta' 三段拼装（header/removed/concurrency）join '\n\n' 注入 LLM
     * （CC messages.ts:4194-4215）。归 subagent/agent 域：Java 无 agent_listing_delta producer
     * → render 防御保留，producer 接线登记 subagent/agent 域未来。
     *
     * @param addedLines          新增/可用 agent 类型行列表（CC original: attachment.addedLines）
     * @param removedTypes        移除的 agent 类型列表（CC original: attachment.removedTypes）
     * @param isInitial           是否首次全量（CC original: attachment.isInitial）
     * @param showConcurrencyNote 是否附并发提示（CC original: attachment.showConcurrencyNote）
     * @return type='agent_listing_delta' 的 attachment
     */
    public static AttachmentMessageDto agentListingDelta(java.util.List<String> addedLines,
                                                         java.util.List<String> removedTypes,
                                                         boolean isInitial, boolean showConcurrencyNote) {
        int count = (addedLines == null ? 0 : addedLines.size())
            + (removedTypes == null ? 0 : removedTypes.size());
        String summary = "Agent listing delta: " + count;
        return new AttachmentMessageDto(
            null, "attachment", "agent_listing_delta",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            new AgentListingDeltaRef(addedLines, removedTypes, isInitial, showConcurrencyNote), null);
    }

    /**
     * [prompt-align GLB-05] mcp_instructions_delta attachment 工厂 · 对齐 CC
     * utils/messages.ts:4216-4231 normalizeAttachmentForAPI case 'mcp_instructions_delta'
     * （CC attachment 载荷 {@code {type:'mcp_instructions_delta', addedBlocks, removedNames}}）。
     *
     * <p>MCP 指令增删事件动态渲染，与 SP-30 静态 mcp_instructions section
     * （SystemPromptSections，已对齐）区分——本 case 是 MCP 指令增删事件 attachment 的动态渲染。
     * 渲染层 AgentLoopContext {@code renderHookAttachmentForLlm} case 'mcp_instructions_delta'
     * 两段拼装 join '\n\n' 注入 LLM（CC messages.ts:4216-4231）。归 mcp 域：Java 无
     * mcp_instructions_delta producer → render 防御保留，producer 接线登记 mcp 域未来。
     *
     * @param addedBlocks  新增 MCP server 指令块（CC original: attachment.addedBlocks，join '\n\n'）
     * @param removedNames 断连 MCP server 名（CC original: attachment.removedNames，join '\n'）
     * @return type='mcp_instructions_delta' 的 attachment
     */
    public static AttachmentMessageDto mcpInstructionsDelta(java.util.List<String> addedBlocks,
                                                            java.util.List<String> removedNames) {
        int count = (addedBlocks == null ? 0 : addedBlocks.size())
            + (removedNames == null ? 0 : removedNames.size());
        String summary = "MCP instructions delta: " + count;
        return new AttachmentMessageDto(
            null, "attachment", "mcp_instructions_delta",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null,
            new McpInstructionsDeltaRef(addedBlocks, removedNames));
    }

    /**
     * plan_file_reference attachment 工厂 · 对齐 CC {@code createPlanAttachmentIfNeeded}
     * (services/compact/compact.ts:1470-1486) {@code createAttachmentMessage({type:'plan_file_reference',
     * planFilePath, planContent})}。
     *
     * <p>压缩后确保 plan 文件内容不丢失；渲染层 case 'plan_file_reference'
     * (CC messages.ts:3636-3643)。
     *
     * @param plan plan 引用 (planFilePath + planContent)；content 为磁盘 plan 文件全文
     * @return type='plan_file_reference' 的 attachment
     */
    public static AttachmentMessageDto planFileReference(PlanRef plan) {
        String summary = "Plan file reference: "
            + (plan != null && plan.planFilePath() != null ? plan.planFilePath() : "?");
        return new AttachmentMessageDto(
            null, "attachment", "plan_file_reference",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, plan, null, false, false, null, null, null, null, null, null);
    }

    /**
     * plan_mode attachment 工厂 · 对齐 CC {@code createPlanModeAttachmentIfNeeded}
     * (services/compact/compact.ts:1542-1560) {@code createAttachmentMessage({type:'plan_mode',
     * reminderType:'full', isSubAgent, planFilePath, planExists})}。
     *
     * <p>压缩时若处于 plan 模式，重注入 plan-mode 指引让模型压缩后继续按 plan 模式工作
     * (compact.ts:550-551 注释)；渲染层 case 'plan_mode' 对齐 CC messages.ts:3826
     * getPlanModeInstructions。
     *
     * @param reminderType CC original: attachment.reminderType ('full'|'sparse') (compact.ts:1553)
     * @param isSubAgent   CC original: attachment.isSubAgent = !!context.agentId (compact.ts:1556)
     * @param planFilePath CC original: attachment.planFilePath (compact.ts:1550)
     * @param planExists   CC original: attachment.planExists = getPlan(agentId) !== null (compact.ts:1551)
     * @return type='plan_mode' 的 attachment
     */
    public static AttachmentMessageDto planMode(String reminderType, boolean isSubAgent,
                                                String planFilePath, boolean planExists) {
        return new AttachmentMessageDto(
            null, "attachment", "plan_mode",
            "Plan mode is active",
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null,
            planFilePath != null ? new PlanRef(planFilePath, null) : null,
            reminderType, isSubAgent, planExists, null, null, null, null, null, null);
    }

    /**
     * plan_mode_reentry attachment 工厂 · 对齐 CC {@code getPlanModeAttachments}
     * (utils/attachments.ts:1216-1219) {@code {type:'plan_mode_reentry', planFilePath}}。
     *
     * <p>重新进入 plan 模式且已存在 plan 文件时一次性注入，提示模型先读旧 plan 再决定
     * 覆盖/续写；渲染层 case 'plan_mode_reentry' 对齐 CC messages.ts:3829-3846。
     *
     * @param planFilePath CC original: attachment.planFilePath（plans.ts:119-129 getPlanFilePath）
     * @return type='plan_mode_reentry' 的 attachment
     */
    public static AttachmentMessageDto planModeReentry(String planFilePath) {
        return new AttachmentMessageDto(
            null, "attachment", "plan_mode_reentry",
            "Re-entering plan mode",
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null,
            planFilePath != null ? new PlanRef(planFilePath, null) : null,
            null, false, false, null, null, null, null, null, null);
    }

    /**
     * plan_mode_exit attachment 工厂 · 对齐 CC {@code getPlanModeExitAttachment}
     * (utils/attachments.ts:1248-1273) {@code {type:'plan_mode_exit', planFilePath, planExists}}。
     *
     * <p>退出 plan 模式后一次性注入，告知模型已退出 plan 模式可执行变更；渲染层
     * case 'plan_mode_exit' 对齐 CC messages.ts:3848-3857。
     *
     * @param planFilePath CC original: attachment.planFilePath（plans.ts:119-129 getPlanFilePath）
     * @param planExists   CC original: attachment.planExists = getPlan(agentId) !== null (attachments.ts:1271)
     * @return type='plan_mode_exit' 的 attachment
     */
    public static AttachmentMessageDto planModeExit(String planFilePath, boolean planExists) {
        return new AttachmentMessageDto(
            null, "attachment", "plan_mode_exit",
            "Exited plan mode",
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null,
            planFilePath != null ? new PlanRef(planFilePath, null) : null,
            null, false, planExists, null, null, null, null, null, null);
    }

    /**
     * task_status attachment 工厂 · 对齐 CC {@code createAsyncAgentAttachmentsIfNeeded}
     * (services/compact/compact.ts:1568-1599) {@code createAttachmentMessage({type:'task_status',
     * taskId, taskType, description, status, deltaSummary, outputFilePath})}。
     *
     * <p>压缩后告知模型后台 async agent 的状态（运行中别重复 spawn / 完成可读输出文件）；
     * 渲染层 case 'task_status' 三状态分支 killed/running/completed (CC messages.ts:3954-4024)。
     *
     * @param taskStatus 后台任务状态引用 (taskId/taskType/description/status/deltaSummary/outputFilePath)
     * @return type='task_status' 的 attachment
     */
    public static AttachmentMessageDto taskStatus(TaskStatusRef taskStatus) {
        String summary = "Task status: "
            + (taskStatus != null ? taskStatus.taskId() + " " + taskStatus.status() : "?");
        return new AttachmentMessageDto(
            null, "attachment", "task_status",
            summary,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            null, null, 0, false,
            null, null, null, false, false,
            taskStatus, null, null, null, null, null);
    }

    /**
     * 单条恢复文件引用 · 对齐 CC {@code 'file'} attachment
     * (utils/attachments.ts:3158-3164, {@code {type:'file', filename, content, truncated}})。
     *
     * @param filename  文件路径 (CC original: attachment.filename, attachments.ts:3160)
     * @param content   文件内容 (CC original: attachment.content, attachments.ts:3161；
     *                  PostCompactAttachmentRestorer.restoreFileAttachments 已按
     *                  POST_COMPACT_MAX_TOKENS_PER_FILE 截断)
     * @param truncated 是否因过大被截断 (CC original: attachment.truncated, attachments.ts:3162)
     */
    public record FileRef(String filename, String content, boolean truncated) {}

    /**
     * plan 文件引用 · 对齐 CC {@code 'plan_file_reference'} attachment
     * (services/compact/compact.ts:1481-1485, {@code {type:'plan_file_reference', planFilePath, planContent}})
     * 与 {@code 'plan_mode'} attachment 的 planFilePath 部分 (compact.ts:1550)。
     *
     * @param planFilePath plan 文件路径 (CC original: attachment.planFilePath,
     *                     plans.ts:119-133 getPlanFilePath({planSlug}.md / {planSlug}-agent-{agentId}.md))
     * @param planContent  plan 文件全文 (CC original: attachment.planContent, plans.ts:135-145 getPlan;
     *                     plan_mode 不填充此字段)
     */
    public record PlanRef(String planFilePath, String planContent) {}

    /**
     * 后台任务状态引用 · 对齐 CC {@code 'task_status'} attachment
     * (services/compact/compact.ts:1584-1596, {@code {type:'task_status', taskId, taskType,
     * description, status, deltaSummary, outputFilePath}})。
     *
     * @param taskId        后台 agent ID (CC original: attachment.taskId = agent.agentId, compact.ts:1587)
     * @param taskType      任务类型 (CC original: attachment.taskType = 'local_agent', compact.ts:1588)
     * @param description   任务描述 (CC original: attachment.description, compact.ts:1589)
     * @param status        任务状态 (CC original: attachment.status, compact.ts:1590；
     *                      取值 'pending'/'running'/'completed'/'failed'/'killed')
     * @param deltaSummary  增量摘要 (CC original: attachment.deltaSummary = running ? progress?.summary
     *                      : error, compact.ts:1591-1594)
     * @param outputFilePath 输出文件路径 (CC original: attachment.outputFilePath =
     *                       getTaskOutputPath(agent.agentId), compact.ts:1595)
     */
    public record TaskStatusRef(String taskId, String taskType, String description, String status,
                                String deltaSummary, String outputFilePath) {}

    /**
     * [prompt-align GLB-09] PDF 分页引用 · 对齐 CC {@code 'pdf_reference'} attachment
     * (utils/messages.ts:3600-3612, {@code {type:'pdf_reference', filename, pageCount, fileSize}})。
     *
     * @param filename   PDF 文件路径 (CC original: attachment.filename, messages.ts:3604)
     * @param pageCount  PDF 总页数 (CC original: attachment.pageCount, messages.ts:3604)
     * @param fileSize   PDF 文件字节数 (CC original: attachment.fileSize, messages.ts:3604；
     *                   render 经 formatFileSize 等价格式化)
     */
    public record PdfRef(String filename, int pageCount, long fileSize) {}

    /**
     * [prompt-align GLB-01] 目录列示引用 · 对齐 CC {@code 'directory'} attachment
     * (utils/messages.ts:3525-3537，CC 原为 Bash ls tool_use/tool_result 对
     * {@code {type:'directory', path, content}})。
     *
     * <p>Java 端降级渲染为 meta user message（镜像 P3-CROSS-1 file case 降级范式），
     * CC quote([path]) shell 引号在降级路径不适用。
     *
     * @param path    目录路径 (CC original: attachment.path, messages.ts:3528)
     * @param content 目录列示内容 (CC original: attachment.content = ls stdout, messages.ts:3532)
     */
    public record DirectoryRef(String path, String content) {}

    /**
     * [prompt-align GLB-02] 单个 LSP 诊断条目 · 对齐 CC {@code 'diagnostics'} attachment
     * 的 file.diagnostics 元素 (utils/messages.ts:3812-3825)。range 已 flatten 为
     * line/character（CC original: d.range.start.line / d.range.start.character，0-based，
     * 渲染时 +1）。
     *
     * @param message   诊断消息 (CC original: d.message, diagnosticTracking.ts:363)
     * @param severity  严重级别 (CC original: d.severity: 'Error'|'Warning'|'Info'|'Hint'，
     *                  渲染按 figures 符号映射)
     * @param line      起始行（0-based，CC original: d.range.start.line）
     * @param character 起始列（0-based，CC original: d.range.start.character）
     * @param code      诊断码，可空 (CC original: d.code)
     * @param source    来源，可空 (CC original: d.source)
     */
    public record DiagnosticItemRef(String message, String severity, int line, int character,
                                    String code, String source) {}

    /**
     * [prompt-align GLB-02] 单文件诊断引用 · 对齐 CC {@code 'diagnostics'} attachment
     * (utils/messages.ts:3812-3825, {@code {type:'diagnostics', files: [{uri, diagnostics}]}})。
     *
     * @param uri         文件 URI (CC original: file.uri, diagnosticTracking.ts:356)
     * @param diagnostics 该文件诊断列表 (CC original: file.diagnostics)
     */
    public record DiagnosticsFileRef(String uri, java.util.List<DiagnosticItemRef> diagnostics) {}

    /**
     * [prompt-align GLB-04] agent 类型增删引用 · 对齐 CC {@code 'agent_listing_delta'} attachment
     * (utils/messages.ts:4194-4215, {@code {type:'agent_listing_delta', addedLines, removedTypes,
     * isInitial, showConcurrencyNote}})。
     *
     * @param addedLines          新增/可用 agent 类型行列表 (CC original: attachment.addedLines)
     * @param removedTypes        移除的 agent 类型列表 (CC original: attachment.removedTypes)
     * @param isInitial           是否首次全量 (CC original: attachment.isInitial)
     * @param showConcurrencyNote 是否附并发提示 (CC original: attachment.showConcurrencyNote)
     */
    public record AgentListingDeltaRef(java.util.List<String> addedLines,
                                       java.util.List<String> removedTypes,
                                       boolean isInitial, boolean showConcurrencyNote) {}

    /**
     * [prompt-align GLB-05] MCP 指令增删引用 · 对齐 CC {@code 'mcp_instructions_delta'} attachment
     * (utils/messages.ts:4216-4231, {@code {type:'mcp_instructions_delta', addedBlocks, removedNames}})。
     *
     * @param addedBlocks  新增 MCP server 指令块（CC original: attachment.addedBlocks，join '\n\n'）
     * @param removedNames 断连 MCP server 名（CC original: attachment.removedNames，join '\n'）
     */
    public record McpInstructionsDeltaRef(java.util.List<String> addedBlocks,
                                          java.util.List<String> removedNames) {}

    /**
     * [prompt-align GLB-10] MCP 资源引用 · 对齐 CC {@code 'mcp_resource'} attachment
     * (utils/messages.ts:3877-3952, {@code {type:'mcp_resource', server, uri, content:{contents}}}).
     *
     * @param server   MCP server 名（CC original: attachment.server，messages.ts:3887）
     * @param uri      MCP 资源 URI（CC original: attachment.uri，messages.ts:3887）
     * @param contents 资源内容（CC original: attachment.content.contents 逐项；Java 单 String 承载拼接文本，
     *                 blob 二进制以常量 application/octet-stream 占位 —— mimeType 传递 Java 无通道，已知差异）
     */
    public record McpResourceRef(String server, String uri, String contents) {}

    /**
     * [prompt-align GLB-10] IDE 行选择/打开文件/编辑文件引用 · 对齐 CC
     * {@code 'selected_lines_in_ide'/'opened_file_in_ide'/'edited_text_file'} attachment
     * (utils/messages.ts:3613-3634/:3538-3543, {@code {type:'selected_lines_in_ide', lineStart, lineEnd,
     * filename, content}} / {@code {type:'opened_file_in_ide', filename}} /
     * {@code {type:'edited_text_file', filename, snippet}})。
     *
     * @param filename  IDE 文件名（三分支共用，CC original: attachment.filename）
     * @param lineStart 选中行起始（CC original: attachment.lineStart，selected_lines_in_ide）
     * @param lineEnd   选中行结束（CC original: attachment.lineEnd，selected_lines_in_ide）
     * @param content   选中行内容（CC original: attachment.content，selected_lines_in_ide；>2000 截断）
     * @param snippet   编辑 diff 片段（CC original: attachment.snippet，edited_text_file）
     */
    public record LineSelectionRef(String filename, Integer lineStart, Integer lineEnd,
                                   String content, String snippet) {}

    /**
     * [prompt-align GLB-06] companion 引用 · 对齐 CC {@code 'companion_intro'} attachment
     * (utils/messages.ts:4232-4239 + buddy/prompt.ts:7-14 companionIntroText，
     * {@code {type:'companion_intro', name, species}})。
     *
     * @param name    companion 名（CC original: attachment.name，messages.ts:4236）
     * @param species companion 物种（CC original: attachment.species，messages.ts:4236）
     */
    public record CompanionRef(String name, String species) {}

    /**
     * [ER-IMP-2026-05 P-27] 兼容构造器：保留 34 参 canonical 调用方形状
     * （…outputTokenTurn/outputTokenSession/outputTokenBudget），默认 P-27 新字段
     * targetMessageId=null。
     *
     * <p>WHY: P-27 在 canonical 末尾新增 {@code targetMessageId}（canonical 34→35 参），
     * 既有 34 参 canonical 调用方（ER-IMP-04 output_token_usage 工厂）不再命中 canonical。
     * 本构造器保留旧 34 参形状并补 targetMessageId 默认 null，避免这些调用方重排参数；
     * tombstone attachment 经 35 参 canonical 显式填充。
     */
    public AttachmentMessageDto(String id, String messageType, String type, String content,
                                Integer maxTurns, Integer turnCount, OffsetDateTime timestamp,
                                String hookName, String toolUseID, String hookEvent, String blockingError,
                                String stderr, String stdout, Integer exitCode, String command,
                                Long durationMs, List<SkillRef> skills, List<DynamicSkillRef> dynamicSkills,
                                int skillCount, boolean isInitial, FileRef file, PlanRef plan,
                                String reminderType, boolean isSubAgent, boolean planExists,
                                TaskStatusRef taskStatus, List<SkillDiscoveryRef> discoveredSkills,
                                String discoverySignal, String discoverySource,
                                List<String> precedingToolUseIds, Map<String, Object> structuredData,
                                Integer outputTokenTurn, Integer outputTokenSession,
                                Integer outputTokenBudget) {
        this(id, messageType, type, content, maxTurns, turnCount, timestamp, hookName, toolUseID,
            hookEvent, blockingError, stderr, stdout, exitCode, command, durationMs,
            skills, dynamicSkills, skillCount, isInitial,
            file, plan, reminderType, isSubAgent, planExists, taskStatus,
            discoveredSkills, discoverySignal, discoverySource, precedingToolUseIds, structuredData,
            outputTokenTurn, outputTokenSession, outputTokenBudget, null, // P-27 targetMessageId null
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null); // CTX-06 token_usage×3 + CTX-07 budget_usd×3 + GLB-08..05 六字段 + GLB-10 五字段 + GLB-06 companion 缺省 null
    }

    /**
     * tombstone attachment 工厂 · 对齐 CC query.ts:716-722
     * {@code yield {type: 'tombstone', message}} 载荷（message.uuid 等价位 targetMessageId）。
     *
     * <p><b>WHY (ER-IMP-2026-05 P-27)</b>: CC 在 streaming→non-streaming fallback 降级响应到达前
     * 为孤儿部分 assistant 消息（尤其 thinking block，签名已随原模型/上下文失效）yield tombstone；
     * Java 以 attachment（type='tombstone'）承载，供 UI/transcript 移除/标记该部分消息。
     * content 保留部分消息文本（可读性/日志），targetMessageId = 部分消息 uuid（CC message.uuid
     * 等价位，Java = LlmAgentLoop turnAssistantId，见 tombstonePartialMessages）。
     *
     * @param content         被 tombstone 的部分 assistant 消息文本（可读性，UI/日志展示用）
     * @param targetMessageId 被 tombstone 消息的 uuid（CC original: message.uuid, query.ts:717）
     * @return type='tombstone' 的 attachment
     */
    public static AttachmentMessageDto tombstone(String content, String targetMessageId) {
        return new AttachmentMessageDto(
            null, "attachment", "tombstone", content, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, 0, false,
            null, null, null, false, false, null, null, null, null, null, null,
            null, null, null, targetMessageId, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Batch2 B1] teammate_mailbox attachment · 对齐 CC utils/attachments.ts:3760-3765
    //   getTeammateMailboxAttachments attachment union member
    //   {@code {type:'teammate_mailbox', messages:[{from,text,timestamp,color?,summary?}]}}
    //   （attachments.ts:3704-3710）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 单条 teammate mailbox 消息 · 对齐 CC getTeammateMailboxAttachments
     * {@code attachment.messages} 元素（attachments.ts:3704-3710,
     * {@code {from, text, timestamp, color?, summary?}}）。
     *
     * @param from      CC original: m.from — 发送方 agent 名
     * @param text      CC original: m.text — 消息文本
     * @param timestamp CC original: m.timestamp — ISO-8601
     * @param color     CC original: m.color（可空）
     * @param summary   CC original: m.summary（可空）
     */
    public record TeammateMailboxMessage(String from, String text, String timestamp,
                                         String color, String summary) {
    }

    /**
     * teammate_mailbox attachment 工厂 · 对齐 CC attachments.ts:3760-3765
     * {@code attachment = [{type:'teammate_mailbox', messages}]}。
     *
     * <p><b>LLM 渲染不经本 factory</b>：注入点
     * {@code AgentLoopContext.maybeInjectTeammateMailbox} 直接用原始消息列表经
     * {@code TeammateMailbox.formatTeammateMessages} 渲染为 meta user message（对齐 CC
     * messages.ts:3847-3857 createUserMessage isMeta，不包 system-reminder）。本工厂 content 供
     * UI/日志展示（N 条来自 teammate 的消息）；messages 载荷<b>不新增 record 字段</b>（canonical
     * 已 35+ 参不再膨胀，对齐规则三 —— 渲染在注入点直接用列表）。
     *
     * @param messages teammate mailbox 消息列表（可空 → 0 条摘要）
     * @return type='teammate_mailbox' 的 attachment
     */
    public static AttachmentMessageDto teammateMailbox(List<TeammateMailboxMessage> messages) {
        int count = messages == null ? 0 : messages.size();
        return new AttachmentMessageDto(
            null, "attachment", "teammate_mailbox",
            count + " message(s) from teammates", null, null, null);
    }
}
