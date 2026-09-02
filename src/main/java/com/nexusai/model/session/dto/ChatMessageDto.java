package com.nexusai.model.session.dto;

import com.nexusai.application.agent.tool.AgentUsage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 响应：单条 chat 消息
 *
 * <p>Phase 6 加 {@link #toolCallId} 字段：role=tool 时必填（对应 OpenAI 的
 * {@code tool_call_id}），role=其他时为 null。
 *
 * <p>R28-3.4 加 {@link #assistantMessageId} 字段：assistant 消息填自身 id,
 * user/tool 消息填其所属的 assistant 消息 id（用于 {@code collectCandidatesByMessage}
 * 合并同 ID 助手片段）。CC 对齐 {@code toolResultStorage.ts} 中
 * {@code seenAsstIds} Set 追踪逻辑。
 *
 * <p>R32-b9 加 {@link #imagePasteIds} 字段:对齐 CC
 * {@code Open-ClaudeCode/src/utils/messages.ts:460-523} createUserMessage
 * 签名的 {@code imagePasteIds?: number[]} 字段(Java 端按 brief 使用
 * {@code List<String>})。该字段记录该 user message 携带的 image block 在
 * 全局对话中的"粘贴序号"(对齐 CC {@code getNextImagePasteId(messages)},
 * 跨所有 Role 累计 maxId + 1 — R32-b9-fix 修复:不再仅 Role.user)。
 *
 * <p>R32-b9 加 {@link #acceptFeedback} 字段:对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1418-1467}
 * addToolResult 中允许路径注入的{@code PermissionAllowDecision.acceptFeedback}
 * (string 文本块),在 hook/pipeline 允许工具时由用户提供反馈文本。R32-b9-fix
 * Fix E 后由 Provider role=tool 序列化分支作为独立 text block 结构化注入
 * (不再字符串拼接到 {@link #content} 末尾)。
 *
 * <p>R32-b9 加 {@link #contentBlocks} 字段:对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1431-1454}
 * addToolResult 中允许路径注入的{@code PermissionAllowDecision.contentBlocks}
 * 与 {@code toolExecution.ts:1040-1067} ask 路径注入的
 * {@code PermissionAskDecision.contentBlocks} ({@code ContentBlockParam[]} 类型,
 * 含 image / text 等块)。Java 端按既有 {@code PermissionResult} 约定以
 * {@code List<JsonNode>} 透传,Provider 序列化时按协议转换为多模态块
 * (R32-b9-fix 修复: text 块不再跳过,完整序列化)。
 *
 * <p>R32-b9 默认值策略:4 字段(acceptFeedback/contentBlocks/imagePasteIds 等)
 * 在 record canonical constructor 直接传值(null-safe);调用方传入 null / 空 list
 * 时 record 保持原样(由各消费者按需处理)。无 compact constructor / BuilderFactory —
 * record 自身的 canonical constructor 是唯一构造路径。
 *
 * <p>R32-b9-fix 兼容性说明:canonical constructor 现为 17 参
 * (id/sessionId/role/author/content/reasoning/toolCalls/finishReason/inputTokens/
 *  outputTokens/time/createdAt/toolCallId/assistantMessageId/acceptFeedback/
 *  contentBlocks/imagePasteIds)。从 R32-b9 之前的 14 参构造升级时:
 * <ul>
 *   <li>14 参构造器已<b>不存在</b>(R32-b9 brief 强制破坏性升级,无向后兼容构造器)</li>
 *   <li>所有调用方必须传全部 17 参(常见模式:后 3 字段传 {@code null}/{@code List.of()})</li>
 *   <li>{@link com.nexusai.application.agent.LlmAgentLoop#toolResultMessage}
 *       与 LlmAgentLoop 工厂
 *       方法是推荐构造入口</li>
 * </ul>
 */
public record ChatMessageDto(
    String id,
    String sessionId,
    Role role,
    String author,
    String content,
    String reasoning,
    List<ToolCallDto> toolCalls,
    FinishReason finishReason,
    Integer inputTokens,
    Integer outputTokens,
    String time,                      // 人读时间，如 "2 分钟" / "刚刚"
    OffsetDateTime createdAt,
    String toolCallId,                // Phase 6·s02 · role=tool 时填
    String assistantMessageId,        // R28-3.4 · 对齐 CC seenAsstIds 追踪; [ER-IMP-14] tool 消息=CC sourceToolAssistantUUID (utils/messages.ts:491) 等价位: 含对应 tool_use 的 assistant message uuid
    // ── R32-b9 Hook 字段启用 (CC addToolResult acceptFeedback/contentBlocks/imagePasteIds) ──
    String acceptFeedback,            // R32-b9 · 用户接受反馈（CC PermissionAllowDecision.acceptFeedback）
    List<?> contentBlocks,            // R32-b9 · 内容块列表（CC permissionDecision.contentBlocks,透传 List<JsonNode>）
    List<String> imagePasteIds,       // R32-b9 · 图片粘贴序号（CC getNextImagePasteId 全局累计）
    Map<String, Object> structuredOutput, // R32-b14 · 独立 structured_output 载荷 · [IT-6] Java 内部载体
                                         //   （DB 持久化 MessageService / ExecAgentHook 检测 / outbound DTO）；
                                         //   provider 不再序列化为模型 text block（CC normalizeAttachmentForAPI
                                         //   structured_output→[]，模型不可见；attachment 通道由 ToolResultApplier 产出）
    boolean isMeta,                   // R32-c-1 · 元消息标志（CC createUserMessage({isMeta:true})）
                                      // [P-13/F29] 2026-08-15 移除 @JsonIgnore —— CC isMeta 参与消息
                                      // 序列化（前端按 isMeta 隐藏元消息）；对齐后全量 outbound JSON 含
                                      // isMeta 字段（待前端对接.md F29 登记）。
    // [对抗核验 H13-GAP] isError · 对齐 CC tool_result.is_error (messages.ts:4754)
    // WHY: CC hasSuccessfulToolCall 用 tool_result.is_error!==true 判定工具成功; Java 旧实现用
    //      content==SUCCESS_CONTENT 文案判定（工具失败返回同文案时误判）。本字段由 ToolResult.isError
    //      经 LlmAgentLoop.toolResultMessage 填充，供 StructuredOutputEnforcementHook 判成功。
    // NOTE: 字段置于 isMeta 之后 —— 既有 19 参调用方（RetryMessageFactory/AgentLoopContext）末参即 isMeta,
    //      重排会使其值落入 isError（回归）。19 参兼容构造器保留 (structuredOutput, isMeta) 语义。
    boolean isError,                  // H13-GAP · 工具结果错误标志（CC original: is_error, messages.ts:4754）
    // [P2-22] CC original: sourceToolUseID（tools/utils.ts:21 写入 / messages.ts:2778 消费）
    // transient 语义：Skill inline 展开产生的 user newMessage 打上本次 Skill tool_use id，
    //   UI 经 getToolUseID（messages.ts:2778 user 分支）关联到正在运行的 Skill 工具调用，
    //   不落历史独立条目。null = 无源工具调用（普通消息，CC tagMessagesWithToolUseID 未命中）。
    String sourceToolUseID,           // P2-22 · 源工具调用 ID（CC original: sourceToolUseID, tools/utils.ts:21）
    // IMP-05 · subtype · 对齐 CC Message 联合类型的 subtype 判别字段（messages.ts:4539/4569）
    // WHY: boundary 消息从「文本前缀 [Compact boundary: X]」双轨统一为 CC 结构化 subtype 单一表示
    //      （OD-18 裁决）。读侧 isCompactBoundaryMessage（messages.ts:4608）按
    //      subtype==='compact_boundary' 判别边界，因此消息流载体 ChatMessageDto 必须携带 subtype。
    //      compact_boundary / microcompact_boundary 由 CompactBoundaryMessage.toChatMessageDto() 填充；
    //      普通消息为 null。字段置于 sourceToolUseID 之后（第 22 参），20 参兼容构造器默认 null。
    String subtype,                   // IMP-05 · 消息 subtype（CC original: subtype, messages.ts:4539/4569）
    // ── ER-IMP-11 修正版2 · isApiErrorMessage 消息级错误标志 · CC AssistantMessage ──
    boolean isApiErrorMessage,         // 消息级错误标志 · CC original: isApiErrorMessage (messages.ts:453; baseCreate 默认 false messages.ts:357)
    String apiError,                   // API 错误类型 · CC original: apiError (messages.ts:454)，如 'max_output_tokens'（claude.ts:2274）
    String error,                      // 错误描述 · CC original: error (messages.ts:455)
    String errorDetails,               // 错误详情 · CC original: errorDetails (messages.ts:456)
    // ── OPD-R2-SM-01 · cache 用量字段 · CC BetaUsage cache_read_input_tokens / cache_creation_input_tokens ──
    // WHY: tokenCountWithEstimation（tokens.ts:226-260）usage 四通道 = input + cache_creation + cache_read
    //      + output（getTokenCountFromUsage tokens.ts:46-53）。旧 DTO 仅 inputTokens/outputTokens 两字段，
    //      cache 恒 0 → SM 提取阈值 / boundary preTokens / 遥测系统性低估（DRIFT-1/2/6）。本字段由
    //      provider 用量捕获侧填充（S4-2b 已登记 infra 排期）；null = 无 cache 数据（回退 0，
    //      Usage.of 映射，与现状等价）。
    Integer inputCacheReadTokens,     // CC original: cache_read_input_tokens（BetaUsage）
    Integer inputCacheCreationTokens, // CC original: cache_creation_input_tokens（BetaUsage）
    // ── IMP2-14 · boundary 元数据 + 摘要可观察性 · CC messages.ts:4530-4583 / 460-523 ──
    // WHY: △-6/△-16 序列化断头修复——compactMetadata/preservedSegment/logicalParentUuid
    //      此前只在 CompactBoundaryMessage record 存在，toChatMessageDto 仅序列化
    //      content/subtype/isMeta/timestamp，元数据在消息模型 ↔ SDK 序列化 ↔ 加载域
    //      （DB 持久化/重放）之间全丢。本批字段使 ChatMessageDto 成为完整消息载体
    //      （round-trip 闭环）。isCompactSummary/isVisibleInTranscriptOnly 对齐 CC
    //      createUserMessage 的可观察性标志（✗-8，messages.ts:464-465/479-480）。
    Map<String, Object> compactMetadata,        // CC original: compactMetadata (messages.ts:4540-4546) · boundary 元数据 JSON 形状；非 boundary 消息 null
    Map<String, Object> microcompactMetadata,   // CC original: microcompactMetadata (messages.ts:4567-4574) · microcompact_boundary 元数据；非 boundary 消息 null
    String logicalParentUuid,                   // CC original: logicalParentUuid (messages.ts:4551-4553) · 压缩前最后消息 uuid（仅 compact_boundary，有值才存在）
    boolean isCompactSummary,                   // CC original: isCompactSummary (messages.ts:465/480) · 摘要 user 消息标记
    boolean isVisibleInTranscriptOnly,          // CC original: isVisibleInTranscriptOnly (messages.ts:464/479) · 仅 transcript 可见（不进模型上下文）
    // [DEC-04 R2-USAGE] usage 数据源闭环 · CC original: message.usage (agentToolUtils.ts:238-256,
    //   finalizeAgentTool :355 直接透传 lastAssistantMessage.message.usage)
    // WHY: Java 旧 ChatMessageDto 仅 inputTokens/outputTokens 2 字段恒 null (provider 未解析),
    //   DEC-04 使 usage 恒 0/EMPTY. 本字段承载 provider (Anthropic/OpenAI/Mock) 解析的完整
    //   AgentUsage, LlmAgentLoop 在 assistant 消息 append 时经 withUsage() 填充. null = 无 usage
    //   数据 (user/tool 消息 / 未解析旧消息). inputTokens/outputTokens 字段保留为 usage 的
    //   withUsage 投影 (compact/Tokens/billing 旧消费方兼容, 非双轨: usage 为真源).
    AgentUsage usage,
    // ── ER-IMP-2026-05 P-27 · system 消息 level · CC original: level (messages.ts:4349,
    //    SystemMessageLevel SDK 联合类型, createSystemMessage(content, 'warning') 使用面) ──
    // WHY: CC 把 fallback 降级通知建模为 system/informational/warning 消息（query.ts:945-948,
    //    createSystemMessage :4335-4352），Java 旧实现用 model_fallback_warning attachment
    //    （P-27 已删）。本字段承载 level 值域（'warning'/'info'/'success'/'error' 等），
    //    由 role=system 消息填充；普通消息 null。不持久化（DB schema 无列，P-27 实施期确认，
    //    与 isApiErrorMessage 同先例）。字段置于 usage 之后（canonical 末尾），
    //    既有 34 参 canonical 调用方经 34 参兼容构造器默认 null。
    String level,
    // ── IMP-WF3-TC-01 · classifier 自动批准规则 · CC original: matchedRule
    //    (Open-ClaudeCode/src/utils/classifierApprovals.ts:11, UserToolSuccessMessage.tsx:47-50) ──
    // WHY: 前端渲染工具结果时显示"✓ 已自动批准（规则X）"（对齐 CC UserToolSuccessMessage
    //    "Auto-approved · matched "rule""）。服务端在工具结果 payload 附带 matchedRule，
    //    值源 = ClassifierApprovals store（bash classifier 放行时由 ToolPermissionGate 写入，
    //    LlmAgentLoop.toolResultMessage 渲染点读取+一次性删除）。字段置于 level 之后
    //    （canonical 末尾），既有 35 参 canonical 调用方经兼容构造器默认 null。
    String matchedRule,
    // ── snipMetadata · CC original: snipMetadata (snipCompact.ts:99 / snipProjection.ts:31) ──
    // WHY: CC snip 流程在 subtype==='snip_boundary' 的 system 消息上携带 snipMetadata
    //      （结构 { removedUuids?: string[] }），SnipCompactor 反向扫描边界消息读取
    //      removedUuids（snipCompact.ts:99-103），projectSnippedView 据此剔除已被删除的消息
    //      （snipProjection.ts:31）。Java 消息载体 ChatMessageDto 新增本字段承载；
    //      null = 非 snip_boundary 消息（与 subtype/compactMetadata 同先例，随消息序列化 round-trip）。
    Map<String, Object> snipMetadata,   // CC original: snipMetadata (snipCompact.ts:99 / snipProjection.ts:31)
    // ── G13 · 消息 cwd 戳 · CC original: cwd (sessionStorage.ts:1059 transcriptMessage.cwd = getCwd()) ──
    // WHY: CC 每条 transcript 消息记录 cwd（消息产生时工作目录），供 /resume 恢复目录上下文
    //   （sessionStorage.ts:2522 {@code projectPath: firstMessage.cwd} + :4751 extractJsonStringField(head,'cwd')）。
    //   Java 等价 = 消息载体 ChatMessageDto 携带 cwd 戳，持久化到 messages 表（V22 列），toDto 读回。
    //   写侧（toMessage 工厂 + MessageService/ChatService 持久化点）经 CwdResolution.getCwd(sessionId)
    //   在消息产生/落库时戳入；读侧（toDto）原样回填。null = 旧消息/未戳（容错，对齐 CC 旧 jsonl 无 cwd）。
    //   非 @JsonIgnore：cwd 需随消息序列化出站 + round-trip（CC transcript cwd 非敏感数据，可出站）。
    String cwd,                         // G13 · 消息 cwd 戳（CC original: cwd, sessionStorage.ts:1059）
    // ── reasoningDurationMs · 后端测推理耗时 · 净新增字段（非 CC 对齐）──
    // WHY: 用户拍板（2026-08-24）后端测量模型推理耗时（thinking 阶段），STOMP
    //   MessageCompleteEvent + messages 表 + transcript 文件三轨下发/记录。CC 无对应计时字段
    //   （自验 sessionStorage.ts:1706/2209-2252 的 turn_duration 是 system+subtype='turn_duration'
    //   +messageCount，非推理耗时）。语义 = 推理流首 SSE reasoning chunk 至推理阶段结束（首
    //   content chunk 或 onAssistantMessage）的时间跨度；null = 无 reasoning（前端 null=无数据）。
    //   写侧 LlmAgentLoop 计时 → withReasoningDurationMs 挂载，ChatService/MessageService 落库
    //   （V41 列），读侧 MessageService.toDto 回填。置于 canonical 末尾（cwd 之后），既有
    //   38 参 canonical 调用方经 38 参兼容构造器默认 null。
    Long reasoningDurationMs,           // 后端测推理耗时 ms（CC original: 无；null = 无 reasoning）
    // ── userMessageId · 发起该轮的用户消息 ID · 对齐 CC parentUuid 链 ──
    // WHY: 前端消息流/排队按 userMessageId 锚定（一个 userMessageId 一个 flow，工具轮挂
    //   主气泡下）。CC transcriptMessage.parentUuid（sessionStorage.ts:1001-1068
    //   insertMessageChain）链根 = 该 turn 的用户消息 uuid；Java 以 DB 列 user_message_id
    //   表达同一归因（写侧 MessageService/ChatService 落库，读侧 MessageService.toDto 回填）。
    //   user 消息 = 自己的 id；assistant 消息 = 发起该轮的用户消息 id；tool/tool_result =
    //   跟随所属 assistant（replayAndPersist 同源 userMessageId）；排队用户消息 = 排队命令
    //   uuid；system 消息 → null。置于 canonical 末尾（reasoningDurationMs 之后），既有
    //   39 参 canonical 调用方经 39 参兼容构造器默认 null。
    String userMessageId,               // 发起该轮的用户消息 ID（CC original: parentUuid, sessionStorage.ts:1001-1068）
    // ── B7-R9 · decodeMs · 后端测输出解码耗时 · 净新增字段（非 CC 对齐）──
    // WHY: 用户拍板（2026-08-28 B7）后端测量 t/s（tokens per second）= output_tokens*1000/decode_ms，
    //     首 SSE content chunk（firstTokenMs）至流结束的时间跨度；null = 无计时（前端 null=无数据）。
    //     写侧 LlmAgentLoop firstTokenMs 打点 → withDecodeMs 挂载，ChatService 读末条 assistant
    //     消息挂到 complete 事件 usage.decode_ms。置于 canonical 末尾（userMessageId 之后），
    //     既有 40 参 canonical 调用方经 40 参兼容构造器默认 null。
    Long decodeMs,                      // 后端测输出解码耗时 ms（CC original: 无；null = 无计时）
    // ── [token-compact-fix ⑤方案B] 上下文快照 · 重拉补算字段（净新增，非 CC 对齐）──
    // WHY: 实时 message.complete 事件推 contextWindow/contextTokensUsed/percentLeft（ChatService:559-581，
    //      对齐 CC context.ts current_usage/percentLeft），历史消息重拉（GET /messages）不落库 → 丢失。
    //      本批字段在重拉路径对末条 assistant 消息补算（MessageService.applyContextSnapshotToLastAssistant）：
    //      DB 只存 input/output，cache 未存 → contextTokensUsed = inputTokens + 0 + 0（重算近似，实时推
    //      含 cache 更准）；contextWindow = 模型表 models.max_context_tokens（回落 1M，对齐实时路径）；
    //      percentLeft = max(0, round((1 - used/window)*100))。null = 非末条 assistant / 模型不可判定 /
    //      无 usage（前端 null=无数据，与实时 usage null 省略同语义）。置于 canonical 末尾（decodeMs 之后），
    //      既有 41 参 canonical 调用方经 41 参兼容构造器默认 null。
    Long contextTokensUsed,             // 上下文已用（重算近似：inputTokens + cache(0)；实时含 cache）
    Integer percentLeft,                // 上下文剩余百分比（0-100，负数 clamp 0）
    Long contextWindow,                 // 模型上下文窗口（models.max_context_tokens，回落 1M）
    // ── userAttachments · 附件快照（type+filename+mediaType+contentId）· 净新增字段（非 CC 对齐，
    //    前端 F5 重拉附件 chip）；url 为出站投影不落库（toDto 按 contentId 动态拼）──
    List<UserAttachmentInfo> userAttachments
) {
    /**
     * 附件快照项 · 对齐前端 userAttachments: [{ type, filename, mediaType, contentId, url }]
     *
     * <p>[附件双模式 + 统一附件表 contentId] 扩展 mediaType/contentId/url 三字段（均可 null，
     * 兼容历史 2 字段 JSON —— record 反序列化缺省 null 容错）。字段语义：
     * <ul>
     *   <li>{@link #type()} / {@link #filename()}：既有快照核心（前端附件 chip 显示）</li>
     *   <li>{@link #mediaType()}：MIME 类型（e.g. 'image/png'）· 出站透传；null = 未知</li>
     *   <li>{@link #contentId()}：附件表注册 id（upload/path 附件落库；&le;5MB base64 图 / 历史旧行
     *       null；写侧 createUserMessage 直接带、updateUserAttachments 回补）</li>
     *   <li>{@link #url()}：F5 预览 url —— 出站纯投影，DB 不落库（恒 null）；MessageService.toDto 按
     *       contentId 动态拼 {@code /api/v1/attachments/content/{sessionId}/{contentId}}</li>
     * </ul>
     *
     * <p><b>canonical 5 参唯一构造器</b>：不加便捷构造 —— Jackson 反序列化 record 需精确命中
     * canonical 构造器（多构造会引入 creator 歧义风险），DB 旧 {type,filename} 2 字段 JSON 经缺省
     * null 容错。既有唯一调用点（MessageService.userAttachmentsFromAttachments）已同步补参。
     */
    public record UserAttachmentInfo(String type, String filename, String mediaType, String contentId, String url) {}

    /**
     * IMP-05 兼容构造器：保留既有 20 参调用方（R32-b14 之后 canonical 形状）。
     *
     * <p>WHY: IMP-05 在 record 末尾新增 {@code subtype} 字段（第 21 参 canonical）。
     * 既有 20 参调用方（含 17/18/19 参构造器委派的 {@code this(...)}）不传 subtype，
     * 本构造器接收旧 20 参形状并默认 subtype=null —— 避免全部 94 处调用方重排参数。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            null, null,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null);// ER-IMP-11 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * R32-b14 兼容构造器：保留 b9 的 17 参调用方，默认无结构化输出 + isMeta=false + isError=false。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, null, false, false, null, null,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null);// ER-IMP-11 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }


    /**
     * R32-b14 兼容构造器：保留 18 参调用方（仅缺 isMeta/isError, 默认 false）。
     * 用于 R32-b14 阶段引入的 ChatMessageDto 18 参版本，向后兼容避免破坏既有 caller。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, false, false, null, null,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null);// ER-IMP-11 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * R32-b14 兼容构造器：保留 19 参调用方（末参 isMeta, 缺 isError 默认 false）。
     *
     * <p>WHY: 既有 19 参调用方（RetryMessageFactory.createRetryMessage / AgentLoopContext isMeta
     * user message 工厂）末参即 isMeta（R32-c-1 语义）。isError 字段加在 canonical 末尾后，
     * 本构造器保留 (structuredOutput, isMeta) 位置，isError 默认 false —— 避免这些调用方
     * 的 isMeta 值落入 isError 参数（重排回归）。
     *
     * @param isMeta 元消息标志（CC createUserMessage({isMeta:true})）
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, false, null, null,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null);// ER-IMP-11 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * IMP-05 兼容构造器：保留 21 参调用方（…isError, subtype）· subtype 为第 21 参
     * （sourceToolUseID 默认 null）。
     *
     * <p>WHY: ContextCompact 对齐（IMP-05）的 boundary 消息构造调用方（CompactConversation/
     * PartialCompactConversation/PostCompactAttachmentRestorer/MicroCompactor）传
     * {@code (…, isError, subtype)} 形状（subtype 为末参），不传 skill 的 sourceToolUseID。
     * 本构造器保留 21 参签名委托到 canonical（sourceToolUseID=null），避免这些调用方重排参数。
     *
     * @param subtype 消息 subtype（CC original: subtype, messages.ts:4539/4569）
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String subtype) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            null, subtype,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null);// ER-IMP-11 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * ER-IMP-11 兼容构造器：保留 22 参 canonical 调用方（…sourceToolUseID, subtype），
     * 默认 ER-IMP-11 新字段（isApiErrorMessage=false + apiError/error/errorDetails null）。
     *
     * <p>WHY: ER-IMP-11 在 record 末尾新增 4 字段后（修正版2 删 retryAttempt/maxRetries/retryDelayMs，
     * canonical 29→26 参），22 参 canonical 调用方
     * （LlmAgentLoop.relevantMemoriesMetaMessage / MessageService.replaceSessionMessages / toDto）
     * 不再命中 canonical。本构造器保留旧 22 参形状并补默认值，避免这些调用方重排参数。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null); // ER-IMP-11 默认 + OPD-R2-SM-01 cache 默认 null + IMP2-14 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * IMP2-14 兼容构造器：保留 ER-IMP-11 的 26 参 canonical 调用方
     * （…isApiErrorMessage/apiError/error/errorDetails），默认 IMP2-14 新字段
     * （compactMetadata/microcompactMetadata/logicalParentUuid null +
     * isCompactSummary/isVisibleInTranscriptOnly false）。
     *
     * <p>WHY: IMP2-14 在 record 末尾新增 5 字段后（canonical 26→31 参），既有 26 参
     * canonical 调用方不再命中 canonical。本构造器保留旧 26 参形状并补默认值，
     * 避免这些调用方重排参数；boundary/摘要消息经 31 参 canonical 显式填充新字段。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            null, null,
            null, null, null, false, false,
            null, null, null, null);// ER-IMP-11 默认 + DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * DEC-04 兼容构造器：保留 27 参 canonical 调用方形状（26 参 ER-IMP-11 形状 + usage），
     * 默认 OPD-R2-SM-01 cache + IMP2-14 字段（null/false）。
     *
     * <p>WHY: DEC-04（R2-USAGE）在 canonical 末尾新增 {@link AgentUsage usage} 后，既有
     * 26 参调用方（ApiErrorMessageFactory / TeammateMessageFoldingChain 等）以 27 参形状构造
     * （…errorDetails + usage）——本构造器保留旧形状并补默认值，避免调用方重排参数。
     *
     * @param usage CC original: message.usage（agentToolUtils.ts:238-256）；null = 无 usage
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            AgentUsage usage) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            null, null,
            null, null, null, false, false,
            usage, null, null, null); // P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * DEC-04 兼容构造器：保留 32 参 canonical 调用方形状（26 参 + IMP2-14 5 字段 + usage），
     * 默认 OPD-R2-SM-01 cache 字段（null）。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            null, null,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, null, null, null); // P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * P2-22 拷贝方法：镜像 CC spread {@code {...m, sourceToolUseID}}（tools/utils.ts:21）。
     *
     * <p>供 SkillToolImpl {@code tagMessagesWithToolUseID} 对 inline user newMessage 打标，
     * 免冗长 21 参重构造（CC original: sourceToolUseID, tools/utils.ts:21 写入 /
     * messages.ts:2778 getToolUseID user 分支消费）。
     *
     * @param sourceToolUseID 源工具调用 ID（Java 侧 = Skill tool_use block.id()）
     * @return 与原 record 全字段相同、仅 sourceToolUseID 覆盖的新实例
     */
    public ChatMessageDto withSourceToolUseID(String sourceToolUseID) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly, usage, level, matchedRule, snipMetadata,
            cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments); // G13: 补 cwd（withSourceToolUseID 早期未重建 cwd，补全保字段）+ userMessageId 透传 + B7 decodeMs 透传
    }

    /**
     * [prompt-align CTX-09] 拷贝方法：仅替换 {@code content}，其余字段全透传 · 镜像 CC spread
     * {@code {...m, content: rendered}}（deferred_tools_delta 双表示：持久化 JSON content 不变，
     * LLM 注入侧以渲染副本覆盖 content）。
     *
     * <p>供 LlmAgentLoop 主循环 / post-compact replay 边界对 subtype=deferred_tools_delta 消息生成
     * LLM 可见的人类可读副本（PostCompactAttachmentRestorer.renderDeferredToolsDelta），持久化原消息
     * 不被污染（scanAnnouncedDeltaNames 跨 turn 读 JSON 兼容）。
     *
     * @param newContent 新的 content 文本
     * @return 与原 record 全字段相同、仅 content 覆盖的新实例
     */
    public ChatMessageDto withContent(String newContent) {
        return new ChatMessageDto(
            id, sessionId, role, author, newContent, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly, usage, level, matchedRule, snipMetadata,
            cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

    /**
     * [snip-persist-field] 覆盖 snipMetadata · 对齐 compactMetadata 构造传参模式，供
     * {@code MessageService.toDto} 读侧回填（V62 snip_metadata 列）。
     *
     * <p>snip_boundary 消息的 snipMetadata = {@code { removedUuids?: string[] }}（被裁剪消息 id，
     * snipCompact.ts:99-106 / snipProjection.ts:31）。GET /messages 出站后前端据此在被裁剪消息
     * 右上角标注「已裁剪」。null = 非 snip_boundary 消息。
     *
     * @param newSnipMetadata 新的 snipMetadata（可为 null）
     * @return 与原 record 全字段相同、仅 snipMetadata 覆盖的新实例
     */
    public ChatMessageDto withSnipMetadata(Map<String, Object> newSnipMetadata) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly, usage, level, matchedRule, newSnipMetadata,
            cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow,
            userAttachments);
    }

    /**
     * [DEC-04 R2-USAGE] 附加完整 usage · 镜像 CC spread {@code {...m, usage}}（message.usage 透传语义）。
     *
     * <p>供 LlmAgentLoop assistant 消息 append（toMessage / assistantMessageWithToolCalls 后置）
     * 填充 provider 解析的 AgentUsage。同时把 usage 的 inputTokens/outputTokens 投影到本 record
     * 既有字段（compact/Tokens/billing 旧消费方读这两个字段），避免"usage 对象有值但
     * inputTokens/outputTokens 仍 null"的半对齐（DEC-04 症状）。
     *
     * <p><b>顺序约束（必须最先调用）</b>：本方法重建 record 时把 {@code inputCacheReadTokens} /
     * {@code inputCacheCreationTokens} 硬编码为 {@code null}（下方 canonical 第 28/29 参），因此
     * <b>必须先于 {@link #withUsageCache} 调用</b>——若先 {@code withUsageCache(a,b).withUsage(u)}，
     * cache 用量会被本方法覆盖回 null（usage 缓存字段与 DTO cache 字段双轨漂移）。链式顺序：
     * {@code msg.withUsage(usage).withUsageCache(cacheRead, cacheCreation)}（LlmAgentLoop
     * toMessage 即按此顺序，:6249-6254）。
     *
     * @param usage CC original: message.usage（agentToolUtils.ts:238-256）；null = 保持原值
     * @return 与原 record 全字段相同、仅 usage + inputTokens/outputTokens 投影覆盖的新实例
     */
    public ChatMessageDto withUsage(AgentUsage usage) {
        if (usage == null) {
            return this;
        }
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            (int) usage.inputTokens(), (int) usage.outputTokens(), time, createdAt,
            toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            null, null,
            null, null, null, false, false,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments); // G13: 保留 cwd 戳 + B7 decodeMs 透传
    }

    /**
     * OPD-R2-SM-01 拷贝方法：镜像 CC spread 语义，覆盖 cache 用量字段
     * （BetaUsage cache_read_input_tokens / cache_creation_input_tokens）。
     *
     * <p>WHY: 既有消息工厂（17/18/19/20 参兼容构造器）不传 cache；provider 用量捕获侧
     * （S4-2b 已登记 infra 排期）填充时经本方法在既有消息上覆盖，免冗长 28 参重构造。
     *
     * @param inputCacheReadTokens     cache_read_input_tokens（null = 无值）
     * @param inputCacheCreationTokens cache_creation_input_tokens（null = 无值）
     * @return 与原 record 全字段相同、仅 cache 用量覆盖的新实例
     */
    public ChatMessageDto withUsageCache(Integer inputCacheReadTokens, Integer inputCacheCreationTokens) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments); // G13: 保留 cwd 戳 + B7 decodeMs 透传
    }

    /**
     * IMP-WF3-TC-01 拷贝方法：附加 classifier 自动批准规则 · 镜像 CC spread
     * {@code {...m, matchedRule}}（classifierApprovals.ts:11, UserToolSuccessMessage.tsx:47-50）。
     *
     * <p>供 LlmAgentLoop 工具结果 payload 构建（toolResultMessage）在渲染点读取
     * {@code ClassifierApprovals.getClassifierApproval} 后附带 matchedRule，前端展示
     * "已自动批准（规则X）"。null = 非 classifier 放行（无规则可显示）。
     *
     * @param matchedRule 自动批准的 classifier 规则（CC original: matchedRule）；null = 无
     * @return 与原 record 全字段相同、仅 matchedRule 覆盖的新实例
     */
    public ChatMessageDto withMatchedRule(String matchedRule) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments); // G13: 保留 cwd 戳 + B7 decodeMs 透传
    }

    /**
     * G13 拷贝方法：附加消息 cwd 戳 · 镜像 CC spread {@code {...m, cwd}} 语义
     * （sessionStorage.ts:1059 {@code transcriptMessage.cwd = getCwd()} 在消息落 transcript 时戳入）。
     *
     * <p>供消息工厂（toMessage / toolResultMessage）在消息产生时戳入 CwdResolution.getCwd(sessionId)，
     * 以及读侧（MessageService.toDto）把 DB 列 cwd 回填到出站 DTO。null = 旧消息/未戳（容错）。
     *
     * @param cwd 消息产生时工作目录（CC original: cwd, sessionStorage.ts:1059）；null = 未戳
     * @return 与原 record 全字段相同、仅 cwd 覆盖的新实例
     */
    public ChatMessageDto withCwd(String cwd) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

    /**
     * reasoningDurationMs 拷贝方法：覆盖推理耗时（ms）· 镜像 CC spread {@code {...m, ...}} 语义
     * （净新增字段，CC 无对应）。
     *
     * <p>供 LlmAgentLoop 在 assistant 消息构建点（max_tokens 截断 / 纯文本 / 工具轮）经
     * {@code computeReasoningDurationMs} 计时后附加；读侧 MessageService.toDto 回填时也可用
     * （更常用 withCwd 链式后追加）。null = 无 reasoning（不记录，前端 null=无数据）。
     *
     * @param reasoningDurationMs 推理耗时 ms（CC original: 无；null = 无 reasoning）
     * @return 与原 record 全字段相同、仅 reasoningDurationMs 覆盖的新实例
     */
    public ChatMessageDto withReasoningDurationMs(Long reasoningDurationMs) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

    /**
     * userMessageId 拷贝方法：附加发起该轮的用户消息 ID · 镜像 CC spread {@code {...m, ...}} 语义
     * （净新增字段，对齐 CC transcriptMessage.parentUuid 链根，sessionStorage.ts:1001-1068）。
     *
     * <p>供读侧 {@code MessageService.toDto} 从 DB 列 user_message_id 回填到出站 DTO
     * （GET /messages 出站唯一点）；写侧（replayAndPersist / createUserMessage 等）直接
     * 在 MessageRecord 上 setUserMessageId，不经本方法。null = 无归属（system 消息 / 旧行）。
     *
     * @param userMessageId 发起该轮的用户消息 ID（CC original: parentUuid, sessionStorage.ts:1001-1068）；
     *                      null = 无归属
     * @return 与原 record 全字段相同、仅 userMessageId 覆盖的新实例
     */
    public ChatMessageDto withUserMessageId(String userMessageId) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

    /**
     * B7-R9 拷贝方法：覆盖输出解码耗时（ms）· 镜像 CC spread {@code {...m, ...}} 语义
     * （净新增字段，CC 无对应）。
     *
     * <p>供 LlmAgentLoop 在 assistant 消息构建点（流结束 / 纯文本 / 工具轮）经
     * {@code firstTokenMs} 打点后附加（首 SSE content chunk → 流结束跨度）；读侧
     * ChatService 从末条 assistant 消息读取并挂到 complete 事件 usage.decode_ms。
     * null = 无计时（不记录，前端 null=无数据）。
     *
     * @param decodeMs 输出解码耗时 ms（CC original: 无；null = 无计时）
     * @return 与原 record 全字段相同、仅 decodeMs 覆盖的新实例
     */
    public ChatMessageDto withDecodeMs(Long decodeMs) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs, contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

    /**
     * IMP2-14 兼容构造器：保留 ContextCompact 的 31 参 canonical 调用方形状
     * （…isApiErrorMessage/apiError/error/errorDetails + compactMetadata/microcompactMetadata/
     * logicalParentUuid/isCompactSummary/isVisibleInTranscriptOnly），默认 OPD-R2-SM-01 cache
     * 新字段（inputCacheReadTokens/inputCacheCreationTokens = null）。
     *
     * <p>WHY: 合并后 canonical 33 参（26 参 ER-IMP-11 形状 + OPD-R2-SM-01 cache 2 字段 +
     * IMP2-14 5 字段）。ContextCompact 侧 canonical 调用方（CompactBoundaryMessage
     * toChatMessageDto / CompactConversation / MessageService）以 31 参形状构造 ——
     * 本构造器保留旧 31 参形状并补 cache 默认 null（Usage.of 映射 0，与现状等价）。
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            null, null,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            null, null, null, null); // DEC-04 usage null + P-27 level null + IMP-WF3-TC-01 matchedRule null + reasoningDurationMs null
    }

    /**
     * snipMetadata 兼容构造器：保留既有 36 参 canonical 调用方（…usage, level, matchedRule），
     * 默认 snipMetadata=null。
     *
     * <p>WHY: snipMetadata 在 record 末尾新增字段（canonical 36→37 参）。既有 36 参 canonical
     * 调用方（LlmAgentLoop model_fallback warning system 消息 / StreamingToolExecutorMatchedRulePayloadTest
     * 以全参形状构造）不再命中新 canonical —— 本构造器保留旧 36 参形状并补 snipMetadata=null，
     * 避免调用方重排参数。
     *
     * @param snipMetadata CC original: snipMetadata (snipCompact.ts:99 / snipProjection.ts:31)；
     *                     null = 非 snip_boundary 消息
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Integer inputCacheReadTokens,
            Integer inputCacheCreationTokens,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage,
            String level,
            String matchedRule) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, null, null); // snipMetadata null = 非 snip_boundary 消息 + reasoningDurationMs null
    }

    /**
     * G13 兼容构造器：保留既有 37 参 canonical 调用方形状（…matchedRule, snipMetadata），
     * 默认 G13 新字段 cwd=null。
     *
     * <p>WHY: G13 在 record 末尾新增 {@code cwd} 字段后（canonical 37→38 参），既有 37 参
     * canonical 调用方（snip_boundary 消息构造测试 SnipReplayVariantCcTest / IMP21SnipCollapseCcContractTest /
     * LlmAgentLoopSnipMicroWiringTest 以全参形状构造）不再命中新 canonical —— 本构造器保留旧 37 参
     * 形状并补 cwd=null（旧消息无 cwd 戳，对齐容错），避免调用方重排参数。
     *
     * @param snipMetadata CC original: snipMetadata (snipCompact.ts:99 / snipProjection.ts:31)；
     *                     null = 非 snip_boundary 消息
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Integer inputCacheReadTokens,
            Integer inputCacheCreationTokens,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage,
            String level,
            String matchedRule,
            Map<String, Object> snipMetadata) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, null, null); // G13 cwd null = 未戳（容错）+ reasoningDurationMs null
    }

    /**
     * reasoningDurationMs 兼容构造器：保留既有 38 参 canonical 调用方形状（…cwd），
     * 默认 reasoningDurationMs=null。
     *
     * <p>WHY: reasoningDurationMs 在 record 末尾新增字段后（canonical 38→39 参），既有 38 参
     * canonical 调用方（含 LlmAgentLoop 等以全参形状构造、或经 withXxx 拷贝方法重建前路径）不再
     * 命中新 canonical —— 本构造器保留旧 38 参形状并补 reasoningDurationMs=null（无 reasoning
     * 容错），避免调用方重排参数。与 G13 37 参构造器同先例。
     *
     * @param cwd 消息产生时工作目录（CC original: cwd, sessionStorage.ts:1059）；null = 未戳
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Integer inputCacheReadTokens,
            Integer inputCacheCreationTokens,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage,
            String level,
            String matchedRule,
            Map<String, Object> snipMetadata,
            String cwd) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, null); // reasoningDurationMs null = 无 reasoning（容错）
    }

    /**
     * userMessageId 兼容构造器：保留既有 39 参 canonical 调用方形状（…cwd, reasoningDurationMs），
     * 默认 userMessageId=null。
     *
     * <p>WHY: userMessageId 在 record 末尾新增字段后（canonical 39→40 参），既有 39 参 canonical
     * 调用方（LlmAgentLoop 等以全参形状构造、或经 withXxx 拷贝方法重建前路径）不再命中新
     * canonical —— 本构造器保留旧 39 参形状并补 userMessageId=null（无归属容错），避免调用方
     * 重排参数。与 reasoningDurationMs 38 参构造器同先例。
     *
     * @param reasoningDurationMs 推理耗时 ms（CC original: 无；null = 无 reasoning）
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Integer inputCacheReadTokens,
            Integer inputCacheCreationTokens,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage,
            String level,
            String matchedRule,
            Map<String, Object> snipMetadata,
            String cwd,
            Long reasoningDurationMs) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, null); // userMessageId null = 无归属（容错）
    }

    /**
     * B7-R9 兼容构造器：保留既有 40 参 canonical 调用方形状（…userMessageId），
     * 默认 decodeMs=null。
     *
     * <p>WHY: decodeMs 在 record 末尾新增字段后（canonical 40→41 参），既有 40 参 canonical
     * 调用方（LlmAgentLoop / MessageService 等以全参形状构造、或经 withXxx 拷贝方法重建前路径）
     * 不再命中新 canonical —— 本构造器保留旧 40 参形状并补 decodeMs=null（无计时容错），避免
     * 调用方重排参数。与 userMessageId 39 参构造器同先例。
     *
     * @param userMessageId 发起该轮的用户消息 ID（CC original: parentUuid, sessionStorage.ts:1001-1068）；
     *                      null = 无归属
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Integer inputCacheReadTokens,
            Integer inputCacheCreationTokens,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage,
            String level,
            String matchedRule,
            Map<String, Object> snipMetadata,
            String cwd,
            Long reasoningDurationMs,
            String userMessageId) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId,
            null); // decodeMs null = 无计时（B7-R9 容错）
    }

    /**
     * [token-compact-fix ⑤] 兼容构造器：保留既有 41 参 canonical 调用方形状（…userMessageId, decodeMs），
     * 默认 3 个上下文快照字段（contextTokensUsed/percentLeft/contextWindow = null）。
     *
     * <p>WHY: 上下文快照字段在 record 末尾新增后（canonical 41→44 参），既有 41 参 canonical
     * 调用方（LlmAgentLoop / MessageService / 各 withXxx 拷贝方法等以全参形状构造）不再命中新
     * canonical —— 本构造器保留旧 41 参形状并补 3 字段 null（非重拉路径消息无快照，对齐前端可选
     * 字段），避免调用方重排参数。与 B7-R9 40 参构造器同先例。
     *
     * @param decodeMs 输出解码耗时 ms（CC original: 无；null = 无计时）
     */
    public ChatMessageDto(
            String id,
            String sessionId,
            Role role,
            String author,
            String content,
            String reasoning,
            List<ToolCallDto> toolCalls,
            FinishReason finishReason,
            Integer inputTokens,
            Integer outputTokens,
            String time,
            OffsetDateTime createdAt,
            String toolCallId,
            String assistantMessageId,
            String acceptFeedback,
            List<?> contentBlocks,
            List<String> imagePasteIds,
            Map<String, Object> structuredOutput,
            boolean isMeta,
            boolean isError,
            String sourceToolUseID,
            String subtype,
            boolean isApiErrorMessage,
            String apiError,
            String error,
            String errorDetails,
            Integer inputCacheReadTokens,
            Integer inputCacheCreationTokens,
            Map<String, Object> compactMetadata,
            Map<String, Object> microcompactMetadata,
            String logicalParentUuid,
            boolean isCompactSummary,
            boolean isVisibleInTranscriptOnly,
            AgentUsage usage,
            String level,
            String matchedRule,
            Map<String, Object> snipMetadata,
            String cwd,
            Long reasoningDurationMs,
            String userMessageId,
            Long decodeMs) {
        this(id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs,
            null, null, null, null); // [token-compact-fix ⑤] 上下文快照默认 null + userAttachments null
    }

    /**
     * [token-compact-fix ⑤] 上下文快照拷贝方法：附加 contextTokensUsed/percentLeft/contextWindow ·
     * 镜像 CC spread {@code {...m, ...}} 语义（净新增字段，CC 无对应）。
     *
     * <p>供 {@code MessageService.applyContextSnapshotToLastAssistant} 在重拉路径（GET /messages）
     * 对末条 assistant 消息补算后挂载。null = 不覆盖（其余 withXxx 调用点零影响，非末条 assistant
     * 消息快照三字段恒 null）。
     *
     * @param contextTokensUsed 上下文已用（重算近似：inputTokens + cache(0)；实时推含 cache 更准）；
     *                          null = 不覆盖
     * @param percentLeft       上下文剩余百分比（0-100，负数 clamp 0）；null = 不覆盖
     * @param contextWindow     模型上下文窗口（tokens，models.max_context_tokens，回落 1M）；
     *                          null = 不覆盖
     * @return 与原 record 全字段相同、仅上下文快照三字段覆盖的新实例
     */
    public ChatMessageDto withContextSnapshot(Long contextTokensUsed, Integer percentLeft, Long contextWindow) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs,
            contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

    /**
     * userAttachments 拷贝方法：附加附件快照 · 镜像 CC spread 语义（净新增字段，CC 无对应）。
     *
     * <p>供读侧 MessageService.toDto 从 DB 列 user_attachments 回填到出站 DTO（GET /messages 出站唯一点）。
     * null = 无附件。
     *
     * @param userAttachments 附件快照列表（{@link UserAttachmentInfo}）；null = 无附件
     * @return 与原 record 全字段相同、仅 userAttachments 覆盖的新实例
     */
    public ChatMessageDto withUserAttachments(List<UserAttachmentInfo> userAttachments) {
        return new ChatMessageDto(
            id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
            inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
            acceptFeedback, contentBlocks, imagePasteIds, structuredOutput, isMeta, isError,
            sourceToolUseID, subtype,
            isApiErrorMessage, apiError, error, errorDetails,
            inputCacheReadTokens, inputCacheCreationTokens,
            compactMetadata, microcompactMetadata, logicalParentUuid,
            isCompactSummary, isVisibleInTranscriptOnly,
            usage, level, matchedRule, snipMetadata, cwd, reasoningDurationMs, userMessageId, decodeMs,
            contextTokensUsed, percentLeft, contextWindow, userAttachments);
    }

}
