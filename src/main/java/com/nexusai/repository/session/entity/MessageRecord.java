package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("messages")
public class MessageRecord {
    @Id private String id;
    private String sessionId;
    private String role;            // 'user'|'assistant'|'system'|'tool'
    private String author;
    private String content;
    private String reasoning;
    /**
     * 推理耗时（ms）· <b>净新增字段，非 CC 对齐</b>（CC 无 reasoning 计时字段——自验：
     * sessionStorage.ts:1706/2209-2252 的 turn_duration 是 system+subtype='turn_duration'
     * +messageCount，供 checkResumeConsistency 用，非推理耗时）。
     *
     * <p>语义 = 推理流首 SSE reasoning chunk 到达时刻 至 推理阶段结束（首 content chunk 或
     * onAssistantMessage）的时间跨度；无 reasoning → null（不记录）。V41 落库为
     * {@code reasoning_duration_ms} 列（MyBatis-Flex camelCase→snake_case 自动映射）。
     * 类型 Long（可空 ms），对齐既有 inputTokens/outputTokens 为 Integer 的惯例。
     */
    private Long reasoningDurationMs;
    private String finishReason;
    private Integer inputTokens;
    private Integer outputTokens;
    /**
     * cache_read_input_tokens · CC original: cache_read_input_tokens（BetaUsage /
     *   agentToolUtils.ts:242 usage 7 子字段；tokens.ts:46-53 getTokenCountFromUsage
     *   四通道含 cache_read）。
     *
     * <p>[token-compact-fix B1 方案A] V53 落库为 {@code cache_read_input_tokens} 列
     * （MyBatis-Flex camelCase→snake_case 自动映射）。写侧（MessageService appendMessage/
     *   replaceSessionMessages + ChatService.replayAndPersist final 块）从
     *   AgentUsage.cacheReadInputTokens() 投影；读侧（MessageService.toDto）回填
     *   ChatMessageDto.inputCacheReadTokens。类型 Integer（可空），对齐既有
     *   inputTokens/outputTokens 为 Integer 的惯例；null = 无 cache 数据（旧行/未解析，
     *   回退 0，与实时 usage null 省略语义等价）。
     */
    private Integer cacheReadInputTokens;
    /**
     * cache_creation_input_tokens · CC original: cache_creation_input_tokens（BetaUsage /
     *   agentToolUtils.ts:241 usage 7 子字段；tokens.ts:46-53 四通道含 cache_creation）。
     *
     * <p>[token-compact-fix B1 方案A] V53 落库为 {@code cache_creation_input_tokens} 列
     * （MyBatis-Flex camelCase→snake_case 自动映射）。写侧（MessageService appendMessage/
     *   replaceSessionMessages + ChatService.replayAndPersist final 块）从
     *   AgentUsage.cacheCreationInputTokens() 投影；读侧（MessageService.toDto）回填
     *   ChatMessageDto.inputCacheCreationTokens。类型 Integer（可空）；null = 无 cache 数据。
     */
    private Integer cacheCreationInputTokens;
    private String createdAt;
    private String toolCallId;      // Phase 6·s02 · role=tool 时必填
    /**
     * 消息 subtype · CC original: subtype（messages.ts:4539/4569，compact_boundary /
     * microcompact_boundary / compact_summary / progress）。V6 落库，读侧
     * BoundaryReader.isCompactBoundaryMessage（BoundaryReader.java:57-61）与
     * PartialCompactConversation.isCompactSummaryMessage（:407-411）按 subtype 判别。
     */
    private String subtype;
    /**
     * 结构化输出载荷 · CC original: structured_output（summary 消息的
     * summarizeMetadata，compact.ts:1037-1042）。V6 落库为 JSON 文本。
     */
    private String structuredOutput;
    /**
     * 来源 assistant 消息 ID · CC original: sourceToolAssistantUUID（utils/messages.ts:491）。
     *
     * <p>仅 tool 消息（role='tool'）设置：值为产生该 tool_use 的 assistant 消息 id
     * （CC 14 处写入点全在 tool_result 消息：query.ts:145 / services/tools/toolExecution.ts:407,449,486,
     * 676,729,857,1069,1465,1733 / services/tools/StreamingToolExecutor.ts:97,171,186,203）。
     * assistant 消息与 user 消息均不设置；Java 曾出现的 assistant 自填 id 行为
     * （LlmAgentLoop.assistantMessageWithToolCalls）为 R32-b15 自创，无 CC 对应，已废弃语义。
     *
     * <p>语义 = CC 父链归因：写侧经 sessionStorage.ts:1031-1037 落到
     * TranscriptMessage.parentUuid（JSONL 父链归因），Java 用 SQLite 列表达同一链接语义
     * （合理存储映射）。grouping 启发式（toolResultStorage.ts:623-630 seenAsstIds /
     * collectCandidatesByMessage）是瞬态局部集合，仅 tool_result 预算分组边界用，
     * 不持久化，非本字段语义。
     *
     * <p>DB 列名 assistant_message_id（MyBatis-Flex camelCase → snake_case 自动映射）。
     * 存量旧行该列为 NULL，读取方需兜底（回落 id / 判空，见 CompactConversation.java:553-559、
     * SessionMemoryService.java:1199-1207、ToolResultStorage.java:591）。
     *
     * @param assistantMessageId 产生该 tool_use 的 assistant 消息 id（仅 tool 消息非空）
     */
    private String assistantMessageId;
    /**
     * boundary compactMetadata · CC original: compactMetadata（messages.ts:4540-4546）。
     * V13 落库为 JSON 文本（IMP2-14 序列化闭环：boundary 经
     * MessageService.replaceSessionMessages/appendMessage 持久化、toDto 读回）。
     */
    private String compactMetadata;
    /**
     * microcompact_boundary 元数据 · CC original: microcompactMetadata（messages.ts:4567-4574）。
     * V13 落库为 JSON 文本（IMP2-14 序列化闭环）。
     */
    private String microcompactMetadata;
    /**
     * boundary 逻辑父 UUID · CC original: logicalParentUuid（messages.ts:4551-4553）。
     * V13 落库（IMP2-14 序列化闭环）。
     */
    private String logicalParentUuid;
    /**
     * snip_boundary snipMetadata · CC original: snipMetadata（snipCompact.ts:99-106 /
     * snipProjection.ts:31）。
     *
     * <p>V62 落库为 JSON 文本（snip 裁剪标记持久化）：SnipTool 注入的 snip_boundary 消息
     * 携带 {@code { removedUuids?: string[] }}（被裁剪消息 id），落库本列后经 GET /messages
     * 出站，前端据此在「被裁剪消息右上角」标注「已裁剪」角标。null = 非 snip_boundary 消息。
     * 对齐 V13 compactMetadata / microcompactMetadata 序列化闭环模式。
     */
    private String snipMetadata;
    /**
     * 消息 cwd 戳 · CC original: cwd（sessionStorage.ts:1059 transcriptMessage.cwd = getCwd()）。
     *
     * <p>G13 对齐：CC 每条 transcript 消息记录 cwd（消息产生时工作目录），供 /resume 恢复目录上下文
     * （sessionStorage.ts:2522 {@code projectPath: firstMessage.cwd}）。Java 等价 = messages 表 V22 列，
     * 写侧（MessageService.createUserMessage/appendMessage/replaceSessionMessages + ChatService
     * newAssistantMessage/newToolMessage）在落库时经 CwdResolution.getCwd(sessionId) 戳入；
     * 读侧（MessageService.toDto）回填到 ChatMessageDto.cwd。null = 旧消息/未戳（容错）。
     */
    private String cwd;
    /**
     * 图片粘贴序号 JSON 数组字符串 · CC original: imagePasteIds
     * （messages.ts:460-523 createUserMessage 签名 {@code imagePasteIds?: number[]}；
     * CC getNextImagePasteId 跨所有 Role 全局累计 maxId + 1 —— R32-b9-fix 不再仅 Role.user）。
     *
     * <p>V46 落库为 JSON 数组文本（{@code ["1","2",...]}，对齐 structured_output V6 / compact
     * metadata V13 同款 JSON 通道；MessageService.serializeStringList / parseStringList round-trip
     * 闭环）。null/空 = 无图片。DB 列名 image_paste_ids（MyBatis-Flex camelCase→snake_case
     * 自动映射，同 V44 permission_mode 范式）。
     *
     * <p><b>WHY</b>：前端重拉缩略图需消息携带图片粘贴序号；转录重放（listBySession / 恢复漏斗）
     * 必须还原该字段，否则 TokenEstimator/Tokens 图片 token 估算与前端图链展示在历史消息上失真。
     */
    private String imagePasteIds;
    /**
     * 附件快照 JSON 数组文本（[{type,filename}]）· user 消息发送时附件 · null = 无附件
     */
    private String userAttachments;
    /**
     * 发起该轮的用户消息 ID · 对齐 CC parentUuid 链
     * （sessionStorage.ts:1001-1068 insertMessageChain 的 transcriptMessage.parentUuid）。
     *
     * <p>语义 = 每条消息所属的「发起该轮的 user 消息 uuid」（CC 链根）：user 消息 =
     * 自己的 id；assistant 消息 = 发起该轮的用户消息 id；tool/tool_result = 跟随所属
     * assistant 的 user_message_id（CC effectiveParentUuid = sourceToolAssistantUUID，
     * sessionStorage.ts:1028-1037）；排队用户消息 = 排队命令 uuid。前端消息流/排队按
     * userMessageId 锚定（一个 userMessageId 一个 flow，工具轮挂主气泡下）。system 消息
     * 不在用户轮内 → NULL。
     *
     * <p>V47 落库为 user_message_id 列（MyBatis-Flex camelCase → snake_case 自动映射，
     * 同 V46 image_paste_ids 列范式）。存量旧行该列为 NULL（前端 null = 无归属，容错）。
     */
    private String userMessageId;
    /**
     * 元消息标志 · CC original: isMeta（messages.ts:3753 createUserMessage({..., isMeta:true}) /
     * useScheduledTasks.ts:76 cron 入队 isMeta 语义 —— UI 隐藏但模型可见）。
     *
     * <p>V51 落库为 is_meta 列（MyBatis-Flex camelCase → snake_case 自动映射，同 V47
     * user_message_id 列范式）。null/旧行 = false（toDto 读侧 Boolean.TRUE.equals 容错）。
     */
    private Boolean isMeta;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public Long getReasoningDurationMs() { return reasoningDurationMs; }
    public void setReasoningDurationMs(Long reasoningDurationMs) { this.reasoningDurationMs = reasoningDurationMs; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getCacheReadInputTokens() { return cacheReadInputTokens; }
    public void setCacheReadInputTokens(Integer cacheReadInputTokens) { this.cacheReadInputTokens = cacheReadInputTokens; }
    public Integer getCacheCreationInputTokens() { return cacheCreationInputTokens; }
    public void setCacheCreationInputTokens(Integer cacheCreationInputTokens) { this.cacheCreationInputTokens = cacheCreationInputTokens; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getSubtype() { return subtype; }
    public void setSubtype(String subtype) { this.subtype = subtype; }
    public String getStructuredOutput() { return structuredOutput; }
    public void setStructuredOutput(String structuredOutput) { this.structuredOutput = structuredOutput; }
    public String getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(String assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public String getCompactMetadata() { return compactMetadata; }
    public void setCompactMetadata(String compactMetadata) { this.compactMetadata = compactMetadata; }
    public String getMicrocompactMetadata() { return microcompactMetadata; }
    public void setMicrocompactMetadata(String microcompactMetadata) { this.microcompactMetadata = microcompactMetadata; }
    public String getSnipMetadata() { return snipMetadata; }
    public void setSnipMetadata(String snipMetadata) { this.snipMetadata = snipMetadata; }
    public String getLogicalParentUuid() { return logicalParentUuid; }
    public void setLogicalParentUuid(String logicalParentUuid) { this.logicalParentUuid = logicalParentUuid; }
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }
    public String getImagePasteIds() { return imagePasteIds; }
    public void setImagePasteIds(String imagePasteIds) { this.imagePasteIds = imagePasteIds; }
    public String getUserAttachments() { return userAttachments; }
    public void setUserAttachments(String userAttachments) { this.userAttachments = userAttachments; }
    public String getUserMessageId() { return userMessageId; }
    public void setUserMessageId(String userMessageId) { this.userMessageId = userMessageId; }
    public Boolean getIsMeta() { return isMeta; }
    public void setIsMeta(Boolean isMeta) { this.isMeta = isMeta; }
}
