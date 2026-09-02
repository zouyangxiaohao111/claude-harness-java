package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.loadAgentsDir;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.infra.util.ChromePrompt;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 压缩后附件恢复 · 对齐 CC {@code createPostCompactFileAttachments}（compact.ts:1415-1464）
 * + {@code createPlanAttachmentIfNeeded}（:1470-1486）+ {@code createSkillAttachmentIfNeeded}
 * （:1494-1534）+ {@code createPlanModeAttachmentIfNeeded}（:1542-1560）+
 * {@code createAsyncAgentAttachmentsIfNeeded}（:1568-1599）+ 3×delta（:567-585）。
 *
 * <p><b>WHY 存在（IMP-04 REQ-04）</b>: 压缩吃掉 delta 附件，需从当前状态恢复，让模型在
 * 压缩后首轮保有工具/指令上下文。INV-15 附件预算不变量：
 * <ul>
 *   <li>文件恢复上限 {@code POST_COMPACT_MAX_FILES_TO_RESTORE = 5}（compact.ts:122）</li>
 *   <li>总 token 预算 {@code POST_COMPACT_TOKEN_BUDGET = 50_000}（compact.ts:123）</li>
 *   <li>单文件上限 {@code POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000}（compact.ts:124）</li>
 *   <li>skill 单技能 {@code 5_000} / 总预算 {@code 25_000}（compact.ts:129-130）</li>
 * </ul>
 *
 * <p><b>附件载体</b>: CC AttachmentMessage → Java ChatMessageDto（author='attachment'，
 * subtype=附件类型，content=载荷文本）。与 {@link com.nexusai.application.agent.compact.StreamCompactSummary#stripReinjectedAttachments}
 * 的判别约定一致（author=attachment）。
 *
 * <p><b>数据源映射</b>: async-agent/plan/plan_mode/skill/3×delta 的 Java 数据源由调用方
 * （IMP-07/10/12）经 {@link CompactConversationContext#setAdditionalPostCompactAttachments} 注入
 * （本类提供各附件工厂方法）；本任务核心实现 file 恢复（INV-15）。
 */
public final class PostCompactAttachmentRestorer {

    private static final Logger log = LoggerFactory.getLogger(PostCompactAttachmentRestorer.class);

    /** 附件作者标记（CC AttachmentMessage → Java author='attachment'） */
    public static final String ATTACHMENT_AUTHOR = "attachment";

    /** [R1] 附件恢复成功遥测事件 · CC original: tengu_post_compact_file_restore_success（attachments.ts:1444） */
    static final String TENGU_POST_COMPACT_FILE_RESTORE_SUCCESS = "tengu_post_compact_file_restore_success";

    /** [R1] 附件恢复失败遥测事件 · CC original: tengu_post_compact_file_restore_error（attachments.ts:1445） */
    static final String TENGU_POST_COMPACT_FILE_RESTORE_ERROR = "tengu_post_compact_file_restore_error";

    /** [R1] 超大文件轻量引用 subtype · CC original: compact_file_reference（attachments.ts:307-312） */
    static final String COMPACT_FILE_REFERENCE_SUBTYPE = "compact_file_reference";

    /**
     * [R1] read-deny 检查用 Read 工具 stub · 对齐 CC isFileReadDenied（attachments.ts:3986-3997）
     * matchingRuleForInput(filePath, toolPermissionContext, 'read', 'deny')。
     *
     * <p>Java 复用 {@link RuleQuery#getDenyRuleByContentsForTool} 查 deny 桶 content rule；
     * matchesContent 只消费 tool.name() 与 input.file_path（RuleQuery.matchesContent :710-732），
     * 无需真实 ReadFileTool 实例。name() 必须为 CC 主名 "Read"（OPD-WF3-DC-v4-05 等价组已删）。
     */
    private static final Tool READ_TOOL_STUB = new Tool() {
        @Override public String name() { return "Read"; }
        @Override public String description() { return "Read tool stub for attachment restore deny check"; }
        @Override public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
    };

    /**
     * Read dedup stub 判别文本 · CC original: FILE_UNCHANGED_STUB
     * （FileReadTool/prompt.ts:8-9，逐字一致）。
     *
     * <p>与 {@code ReadFileTool.FILE_UNCHANGED_STUB}（ReadFileTool.java:150-153，package-private）
     * 同源 —— dedup 命中时 tool_result 载荷 = 本文本（CC mapToolResult :686-691 file_unchanged
     * case → FILE_UNCHANGED_STUB）。本类局部镜像以参与 {@link #collectReadToolFilePaths}
     * 的 stub 判别（R2 · OPD-CM5-A-04），避免扩大 ReadFileTool 常量可见性。
     */
    static final String FILE_UNCHANGED_STUB =
        "File unchanged since last read. The content from the earlier Read tool_result in this "
            + "conversation is still current — refer to that instead of re-reading.";

    /**
     * invoked_skills / task_status / plan_mode 载荷 JSON 序列化器。
     *
     * <p>生产侧（{@link #skillAttachment}/{@link #asyncAgentAttachments}/{@link #planModeAttachment}）
     * 与消费侧（{@link #restoreSkillStateFromMessages} readTree）共用同一 ObjectMapper，
     * 消除手工 safeJson 拼装与消费侧不对称（R2）。
     */
    private static final ObjectMapper SKILL_JSON = new ObjectMapper();

    private PostCompactAttachmentRestorer() { /* 静态工具类 */ }

    // ════════════════════════════════════════════════════════════════════
    // 组装入口（compact.ts:531-585）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 恢复压缩后附件 · 对齐 CC compact.ts:541-585 顺序：
     * file 附件（createPostCompactFileAttachments）+ async-agent + plan + plan_mode +
     * skill + 3×delta。file 附件为核心实现；其余类型经
     * {@link CompactConversationContext#getAdditionalPostCompactAttachments()} 追加
     * （由调用方按本类工厂方法填充）。
     *
     * @param ctx                     压缩上下文（additionalPostCompactAttachments）
     * @param preCompactReadFileState 压缩前 readFileState 快照（CC cacheToObject）
     * @param preservedMessages       压缩后保留消息（Read 结果去重，compact.ts:1419）——
     *                                仅 partial/reactive 路径传 messagesToKeep（compact.ts:925-931）；
     *                                全量压缩 {@link com.nexusai.application.agent.compact.CompactConversation#compactConversation}
     *                                传空集 List.of()，对齐 CC 默认 {@code []}（compact.ts:533-537）不去重
     * @return 附件消息列表
     */
    public static List<ChatMessageDto> restore(
            CompactConversationContext ctx,
            java.util.Map<String, CompactConversation.ReadFileState> preCompactReadFileState,
            List<ChatMessageDto> preservedMessages) {
        Set<String> preservedReadPaths = collectReadToolFilePaths(preservedMessages);
        List<ChatMessageDto> out = new ArrayList<>();
        // 1. file 附件（createPostCompactFileAttachments）
        // [FIX-C1 拍板#6] shouldExcludeFromPostCompactRestore（plan+memory 文件排除，compact.ts:1674-1705）：
        //   排除判断需要 workspaceDir（CC cwd / getOriginalCwd → Project/Local memory 路径）。
        //   workspaceDir 取自 ctx（CC context.effectiveCwd / getOriginalCwd 等价）。
        //   plan 文件排除经 resolvePlanFilePath（CC getPlanFilePath(agentId)，compact.ts:1680-1687）
        //   精确匹配排除（plan 文件已落地磁盘，压缩后不再重注入）；memory 排除（读入 CLAUDE.md
        //   等 memory 文件的会话压缩后不再重注入，避免浪费 token / 污染摘要）。
        String workspaceDir = ctx != null && ctx.getWorkspaceDir() != null
            ? ctx.getWorkspaceDir().toString() : null;
        String planFilePath = resolvePlanFilePath(ctx);
        // [R1 · OPD-CM5-A-03] 生产路径：重读磁盘拿最新附件内容 + deny 检查 + 遥测事件。
        //   contentReader = 磁盘重读（对齐 CC FileReadTool.call）；permCtx 取自 ctx.toolUseContext
        //   （tuc 未接线 → null → 跳过 deny，空安全对齐 CC toolPermissionContext 可空上下文）；
        //   telemetry 取自 ctx.getTelemetry()（null → 跳过事件）。
        ToolUseContext tuc = ctx != null ? ctx.getToolUseContext() : null;
        ToolPermissionContext permCtx = tuc != null ? tuc.permissionContext() : null;
        out.addAll(restoreFileAttachments(preCompactReadFileState, CompactConstants.POST_COMPACT_MAX_FILES_TO_RESTORE,
            preservedReadPaths, workspaceDir, planFilePath,
            PostCompactAttachmentRestorer::readFileFresh, permCtx,
            ctx != null ? ctx.getTelemetry() : null));
        // 2. 其余附件（async-agent/plan/plan_mode/skill/3×delta）—— 调用方注入
        if (ctx.getAdditionalPostCompactAttachments() != null) {
            out.addAll(ctx.getAdditionalPostCompactAttachments());
        }
        // 3. 3×delta 重宣布（compact.ts:563-585）—— 压缩吃掉既有 delta 附件，从当前状态
        //    diff 重宣布；全量压缩 preservedMessages=[] → 对空历史 → 宣布全量集合。
        //    [IMP2-03] 置于 additional 之后（skill 之后），对齐 CC 顺序
        //    file→async→plan→plan_mode→skill→3×delta（compact.ts:541-585）。
        appendPostCompactDeltaAttachments(ctx, preservedMessages, out);
        if (log.isDebugEnabled()) {
            int otherCount = ctx.getAdditionalPostCompactAttachments() == null ? 0 : ctx.getAdditionalPostCompactAttachments().size();
            log.debug("[PostCompactAttachmentRestorer] restore: file={} other={} delta={}",
                out.size() - otherCount - deltaCount(out, ctx.getAdditionalPostCompactAttachments()),
                otherCount,
                deltaCount(out, ctx.getAdditionalPostCompactAttachments()));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // file 附件恢复（compact.ts:1415-1464，INV-15）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 恢复最近读取文件为附件 · 对齐 CC {@code createPostCompactFileAttachments}
     * （compact.ts:1415-1464）。
     *
     * <p>选择：按 recency 排序 → 排除 preserved Read 路径 + 排除 plan/memory 文件
     * （compact.ts:1424-1430 shouldExcludeFromPostCompactRestore）→ 取 maxFiles →
     * 每个文件截断到 per-file 5K → 累计总预算 50K。
     *
     * @param readFileState     压缩前 readFileState（path → {content, timestamp}）
     * @param maxFiles          文件数上限（POST_COMPACT_MAX_FILES_TO_RESTORE=5）
     * @param preservedReadPaths preserved 尾部已可见的 Read 路径（去重跳过）
     * @return 文件附件消息列表
     */
    public static List<ChatMessageDto> restoreFileAttachments(
            java.util.Map<String, CompactConversation.ReadFileState> readFileState,
            int maxFiles,
            Set<String> preservedReadPaths) {
        // 无 workspaceDir / plan 路径的便捷重载（null → Project/Local memory 路径不可得，
        // 仅 User/Managed/AutoMem 排除；无 plan 排除）
        return restoreFileAttachments(readFileState, maxFiles, preservedReadPaths, null, null);
    }

    /**
     * 恢复最近读取文件为附件（无 plan 路径便捷重载）· 委托全量 5 参重载。
     *
     * @param readFileState     压缩前 readFileState（path → {content, timestamp}）
     * @param maxFiles          文件数上限（POST_COMPACT_MAX_FILES_TO_RESTORE=5）
     * @param preservedReadPaths preserved 尾部已可见的 Read 路径（去重跳过）
     * @param workspaceDir      会话工作目录（CC cwd / getOriginalCwd；null → Project/Local memory 排除跳过）
     * @return 文件附件消息列表
     */
    public static List<ChatMessageDto> restoreFileAttachments(
            java.util.Map<String, CompactConversation.ReadFileState> readFileState,
            int maxFiles,
            Set<String> preservedReadPaths,
            String workspaceDir) {
        return restoreFileAttachments(readFileState, maxFiles, preservedReadPaths, workspaceDir, null);
    }

    /**
     * 恢复最近读取文件为附件 · 对齐 CC {@code createPostCompactFileAttachments}
     * （compact.ts:1415-1464）+ shouldExcludeFromPostCompactRestore 排除（:1674-1705）。
     *
     * <p>选择：按 recency 排序 → 排除 preserved Read 路径 + 排除 plan/memory 文件
     * （compact.ts:1424-1430 filter）→ 取 maxFiles → 每个文件截断到 per-file 5K →
     * 累计总预算 50K。content==null/blank 的空壳条目过滤（NEW-GAP-3，对齐 CC
     * generateFileAttachment 读失败返回 null 被 results.filter(result !== null) 过滤，
     * 永不产 "File: path\n\n" 空壳附件）。
     *
     * @param readFileState     压缩前 readFileState（path → {content, timestamp}）
     * @param maxFiles          文件数上限（POST_COMPACT_MAX_FILES_TO_RESTORE=5）
     * @param preservedReadPaths preserved 尾部已可见的 Read 路径（去重跳过）
     * @param workspaceDir      会话工作目录（CC cwd / getOriginalCwd；null → Project/Local memory 排除跳过）
     * @param planFilePath      当前会话 plan 文件路径（CC getPlanFilePath(agentId)；null → plan 排除跳过）
     * @return 文件附件消息列表
     */
    public static List<ChatMessageDto> restoreFileAttachments(
            java.util.Map<String, CompactConversation.ReadFileState> readFileState,
            int maxFiles,
            Set<String> preservedReadPaths,
            String workspaceDir,
            String planFilePath) {
        // 便捷重载：内容源 = 快照 content（测试 / 无 ctx 路径）；deny / 遥测跳过。
        //   生产路径 restore(ctx, ...) 走 8 参重载（磁盘重读 + deny + 遥测，对齐 CC
        //   generateFileAttachment：attachments.ts:3020-3199）。
        return restoreFileAttachments(readFileState, maxFiles, preservedReadPaths, workspaceDir, planFilePath,
            null, null, null);
    }

    /**
     * 恢复最近读取文件为附件 · 对齐 CC {@code createPostCompactFileAttachments}（compact.ts:1415-1464）
     * + {@code generateFileAttachment}（attachments.ts:3020-3199）。[R1 · OPD-CM5-A-03]
     *
     * <p>与 5 参重载的差异（生产路径）：<b>重读磁盘拿最新附件内容</b>（CC FileReadTool.call 等价，
     * attachments.ts:3177-3178）而非用压缩前快照 content；前置 <b>deny 检查</b>（CC isFileReadDenied，
     * attachments.ts:3041，被拒文件跳过）；成功/失败<b>遥测事件</b>（tengu_post_compact_file_restore_success/error，
     * attachments.ts:1444-1445）；内容 token 数超过 per-file 上限时发 <b>compact_file_reference</b> 引用
     * （CC readTruncatedFile compact 分支，attachments.ts:3134-3140）而非截断内容。
     *
     * @param readFileState     压缩前 readFileState（path → {content, timestamp}；快照仅用于选择/recency）
     * @param maxFiles          文件数上限（POST_COMPACT_MAX_FILES_TO_RESTORE=5）
     * @param preservedReadPaths preserved 尾部已可见的 Read 路径（去重跳过）
     * @param workspaceDir      会话工作目录（CC cwd / getOriginalCwd；null → Project/Local memory 排除跳过）
     * @param planFilePath      当前会话 plan 文件路径（CC getPlanFilePath(agentId)；null → plan 排除跳过）
     * @param contentReader     重读磁盘内容提供者（path → 最新内容；null → 读失败/不存在返回 null →
     *                          跳过并遥测 error）。null → 回退快照 content（测试便捷重载语义）
     * @param permCtx           权限上下文（deny 检查；null → 跳过 deny）
     * @param telemetry         遥测发射器（null → 跳过事件）
     * @return 文件附件消息列表
     */
    public static List<ChatMessageDto> restoreFileAttachments(
            java.util.Map<String, CompactConversation.ReadFileState> readFileState,
            int maxFiles,
            Set<String> preservedReadPaths,
            String workspaceDir,
            String planFilePath,
            java.util.function.Function<String, String> contentReader,
            ToolPermissionContext permCtx,
            Telemetry telemetry) {
        if (readFileState == null || readFileState.isEmpty()) {
            return List.of();
        }
        // [FIX-C1 拍板#6] 预计算 memory 排除路径集（CC getMemoryPath 五类；一次计算避免每文件重复 I/O）
        Set<String> memoryPaths = memoryPathsForPostCompactRestore(workspaceDir);
        // 按 recency 排序（timestamp 降序），排除 preserved 路径 + plan/memory 文件
        List<java.util.Map.Entry<String, CompactConversation.ReadFileState>> recent = new ArrayList<>(readFileState.entrySet());
        recent.sort((a, b) -> Long.compare(b.getValue().timestamp(), a.getValue().timestamp()));
        List<java.util.Map.Entry<String, CompactConversation.ReadFileState>> candidates = new ArrayList<>();
        for (java.util.Map.Entry<String, CompactConversation.ReadFileState> e : recent) {
            if (shouldExcludeFromPostCompactRestore(e.getKey(), memoryPaths, planFilePath)) {
                continue;
            }
            if (preservedReadPaths != null && preservedReadPaths.contains(e.getKey())) {
                continue;
            }
            candidates.add(e);
            if (candidates.size() >= maxFiles) {
                break;
            }
        }

        int usedTokens = 0;
        List<ChatMessageDto> out = new ArrayList<>();
        for (java.util.Map.Entry<String, CompactConversation.ReadFileState> e : candidates) {
            String path = e.getKey();
            // [R1] deny 检查 · 对齐 CC isFileReadDenied（attachments.ts:3041 matchingRuleForInput 'read'/'deny'）
            if (permCtx != null && isFileReadDenied(path, permCtx)) {
                if (log.isDebugEnabled()) {
                    log.debug("[PostCompactAttachmentRestorer] 附件重读 deny 跳过: {}", path);
                }
                continue;
            }
            // [R1] 重读磁盘拿最新内容（CC FileReadTool.call 等价）；contentReader==null → 快照回退。
            //   读失败（文件不存在/IO 异常）→ 遥测 error + 跳过（对齐 CC generateFileAttachment 外
            //   catch → logEvent(errorEventName) → null，attachments.ts:3195-3198）。
            String content = contentReader != null ? contentReader.apply(path) : e.getValue().content();
            if (content == null || content.isBlank()) {
                emitFileRestoreTelemetry(telemetry, TENGU_POST_COMPACT_FILE_RESTORE_ERROR);
                if (log.isDebugEnabled()) {
                    log.debug("[PostCompactAttachmentRestorer] 附件重读失败/空内容跳过: {}", path);
                }
                continue;
            }
            // [R1] 内容 token 数 > per-file 上限 → compact_file_reference 引用（CC readTruncatedFile
            //   compact 分支 attachments.ts:3134-3140，轻量引用不带内容，渲染为 "Note: ... too large"）。
            if (CompactConversation.roughTokenCountEstimation(content) > CompactConstants.POST_COMPACT_MAX_TOKENS_PER_FILE) {
                ChatMessageDto ref = buildCompactFileReference(path);
                int refTokens = CompactConversation.roughTokenCountEstimation(ref.content());
                if (usedTokens + refTokens <= CompactConstants.POST_COMPACT_TOKEN_BUDGET) {
                    usedTokens += refTokens;
                    out.add(ref);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[PostCompactAttachmentRestorer] 附件过大 → compact_file_reference: {} (tokens={})",
                        path, CompactConversation.roughTokenCountEstimation(content));
                }
                continue;
            }
            emitFileRestoreTelemetry(telemetry, TENGU_POST_COMPACT_FILE_RESTORE_SUCCESS);
            // 预算按完整消息内容计量（含 "File: path" 头），确保真实消息载荷不超 50K（INV-15）
            String messageContent = "File: " + path + "\n\n" + content;
            int attachmentTokens = CompactConversation.roughTokenCountEstimation(messageContent);
            if (usedTokens + attachmentTokens > CompactConstants.POST_COMPACT_TOKEN_BUDGET) {
                log.debug("[PostCompactAttachmentRestorer] 附件超总预算 50K，跳过: {} (used={}+{})",
                    path, usedTokens, attachmentTokens);
                continue;
            }
            usedTokens += attachmentTokens;
            out.add(buildAttachmentMessage("file", path, content));
        }
        log.info("[PostCompactAttachmentRestorer] file 附件恢复: candidates={} restored={} usedTokens={}",
            candidates.size(), out.size(), usedTokens);
        return out;
    }

    /**
     * 构建 compact_file_reference 附件 · 对齐 CC {@code readTruncatedFile} compact 分支
     * （attachments.ts:3134-3140 {@code {type:'compact_file_reference', filename, displayPath}}）。
     *
     * <p>文件内容超过 per-file 上限（POST_COMPACT_MAX_TOKENS_PER_FILE）时发轻量引用替代完整内容；
     * 渲染层（CC messages.ts:3592-3598）注文案 "Note: {filename} was read before the last conversation
     * was summarized, but the contents are too large to include. Use Read tool if you need to access it."。
     *
     * @param path 文件路径（CC attachment.filename / displayPath）
     * @return subtype='compact_file_reference' 的附件消息
     */
    static ChatMessageDto buildCompactFileReference(String path) {
        String note = "Note: " + path
            + " was read before the last conversation was summarized, but the contents are too large to include. "
            + "Use Read tool if you need to access it.";
        return buildAttachmentMessage(COMPACT_FILE_REFERENCE_SUBTYPE, null, note);
    }

    /**
     * [R1] 读文件最新内容（重读磁盘）· 对齐 CC FileReadTool.call 的 Java 文本读（UTF-8）。
     * 文件不存在 / IO 异常 → null（对齐 CC 读失败 throw → 外层 catch → error telemetry → null）。
     *
     * @param path 文件路径（readFileState 键，已归一化绝对路径）
     * @return 最新内容；读失败返回 null
     */
    static String readFileFresh(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] 附件重读磁盘失败: path={} err={}", path, e.toString());
            }
            return null;
        }
    }

    /**
     * [R1] 附件恢复 deny 检查 · 对齐 CC {@code isFileReadDenied}（attachments.ts:3986-3997 =
     * matchingRuleForInput(filePath, toolPermissionContext, 'read', 'deny')）。
     *
     * <p>Java 实现：以 synthetic Read tool + input {file_path: path} 查 read-deny content rule
     * （RuleQuery.getDenyRuleByContentsForTool，与 ReadPermissionChecker step3 deny 同源）。
     *
     * @param path    待恢复文件路径
     * @param permCtx 权限上下文（null → false 不 deny）
     * @return true = 文件读被 deny，恢复应跳过
     */
    static boolean isFileReadDenied(String path, ToolPermissionContext permCtx) {
        if (permCtx == null || path == null) {
            return false;
        }
        JsonNode input = JsonNodeFactory.instance.objectNode().put("file_path", path);
        return RuleQuery.getDenyRuleByContentsForTool(permCtx, READ_TOOL_STUB, input) != null;
    }

    /**
     * [R1] 附件恢复遥测事件 · 对齐 CC {@code logEvent(successEventName, {})} /
     * {@code logEvent(errorEventName, {})}（attachments.ts:3179/:3196）。
     *
     * @param telemetry 遥测发射器（null → 跳过，对齐 ctx.getTelemetry() null 兜底惯例）
     * @param event     事件名（tengu_post_compact_file_restore_success / _error）
     */
    static void emitFileRestoreTelemetry(Telemetry telemetry, String event) {
        if (telemetry == null) {
            return;
        }
        telemetry.recordEvent(event, Map.of());
        telemetry.logOTelEvent(event, Map.of());
    }

    // ════════════════════════════════════════════════════════════════════
    // [FIX-C1 拍板#6] shouldExcludeFromPostCompactRestore（compact.ts:1674-1705）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 压缩后恢复是否应排除该文件 · CC original: {@code shouldExcludeFromPostCompactRestore}
     * （compact.ts:1674-1705）。
     *
     * <p>CC 语义（E2 自验 compact.ts:1674-1705）：
     * <ol>
     *   <li><b>plan 文件排除</b>（:1680-1687）：{@code expandPath(filename) ===
     *       expandPath(getPlanFilePath(agentId))} 精确匹配。Java 已实现：{@link #resolvePlanFilePath}
     *       经 {@link PlanProvider#getPlanFilePath(UUID)} 取当前会话 plan 文件路径
     *       （{@link PlanProviderImpl} 落地 plansDir/{slug}.md / {slug}-agent-{agentId}.md），
     *       按 {@link MemoryFileDetection#toComparable} 归一化精确匹配排除。</li>
     *   <li><b>memory 文件排除</b>（:1689-1702）：{@code MEMORY_TYPE_VALUES.map(type =>
     *       expandPath(getMemoryPath(type)))} 精确匹配——User/Project/Local/Managed/AutoMem 五类
     *       memory 文件路径。TeamMem 为 feature('TEAMMEM') 门控，Java 未接线（默认关闭）→ 不含。</li>
     * </ol>
     *
     * <p>Java 映射：readFileState 键为已归一化绝对路径（keyForReadFileState → PathGuard.resolve →
     * toAbsolutePath().normalize()）；memory/plan 路径经 {@link #memoryPathsForPostCompactRestore} /
     * {@link #resolvePlanFilePath} 同样归一化，再按 {@link MemoryFileDetection#toComparable}
     * （Windows 小写 + posix 分隔符）精确比较，消除分隔符 / 盘符大小写差异（CC expandPath 等价归一化）。
     *
     * @param filename     readFileState 键（待恢复文件路径）
     * @param memoryPaths  预计算的 memory 排除路径集（{@link #memoryPathsForPostCompactRestore}）
     * @param planFilePath 当前会话 plan 文件路径（null/blank → plan 排除跳过）
     * @return true=应排除（plan / memory 文件）；false=正常恢复候选
     */
    static boolean shouldExcludeFromPostCompactRestore(String filename, Set<String> memoryPaths, String planFilePath) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String comparable = MemoryFileDetection.toComparable(filename);
        // plan 文件排除（CC compact.ts:1680-1687 expandPath(getPlanFilePath(agentId)) 精确匹配）
        if (planFilePath != null && !planFilePath.isBlank()
                && comparable.equals(MemoryFileDetection.toComparable(planFilePath))) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] shouldExcludeFromPostCompactRestore:"
                    + " 排除 plan 文件 {}（CC compact.ts:1680-1687）", filename);
            }
            return true;
        }
        for (String memoryPath : memoryPaths) {
            if (comparable.equals(MemoryFileDetection.toComparable(memoryPath))) {
                if (log.isDebugEnabled()) {
                    log.debug("[PostCompactAttachmentRestorer] shouldExcludeFromPostCompactRestore:"
                        + " 排除 memory 文件 {}（CC compact.ts:1689-1702）", filename);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 计算 memory 排除路径集 · CC original: {@code MEMORY_TYPE_VALUES.map(getMemoryPath)}
     * （compact.ts:1693-1695 + config.ts:1779-1798）。
     *
     * <p>五类（CC MEMORY_TYPE_VALUES，memory/types.ts:3-10）：
     * <ul>
     *   <li>User → {@code {nexusaiHome}/CLAUDE.md} 优先 + {@code {configHome}/CLAUDE.md} 回落
     *       （决策 D1/D3 · config.ts:1783-1784）</li>
     *   <li>Local → {@code {cwd}/CLAUDE.local.md}（config.ts:1785-1786）</li>
     *   <li>Project → {@code {cwd}/CLAUDE.md}（config.ts:1787-1788）</li>
     *   <li>Managed → {@code {managedFilePath}/CLAUDE.md}（config.ts:1789-1790）</li>
     *   <li>AutoMem → {@code getAutoMemEntrypoint()}（config.ts:1791-1792，读取失败跳过）</li>
     * </ul>
     * TeamMem（config.ts:1795-1798）为 feature('TEAMMEM') 门控，Java 默认关闭 → 不含（N/A）。
     *
     * @param workspaceDir 会话工作目录（CC cwd / getOriginalCwd；null → Project/Local 排除跳过）
     * @return memory 文件绝对路径集（非 null）
     */
    static Set<String> memoryPathsForPostCompactRestore(String workspaceDir) {
        Set<String> paths = new HashSet<>();
        // User → 决策 D1/D3：nexusai 自有根 {nexusaiHome}/CLAUDE.md 优先 + claude 只读兼容
        //   {configHome}/CLAUDE.md 回落（用户 memory 可能存于任一目录，双加排除）
        paths.add(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md").normalize().toString());
        paths.add(Paths.get(ClaudePaths.getClaudeConfigHomeDir(), "CLAUDE.md").normalize().toString());
        // Managed → {managedFilePath}/CLAUDE.md（config.ts:1789-1790）
        paths.add(Paths.get(ClaudePaths.getManagedFilePath(), "CLAUDE.md").normalize().toString());
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            // Local → {cwd}/CLAUDE.local.md（config.ts:1785-1786）；Project → {cwd}/CLAUDE.md（:1787-1788）
            paths.add(Paths.get(workspaceDir, "CLAUDE.local.md").normalize().toString());
            paths.add(Paths.get(workspaceDir, "CLAUDE.md").normalize().toString());
        }
        // AutoMem → getAutoMemEntrypoint()（config.ts:1791-1792；CC try/catch 语义：取不到则跳过）
        try {
            paths.add(Paths.get(AutoMemPaths.defaultInstance().getAutoMemEntrypoint()).normalize().toString());
        } catch (RuntimeException e) {
            log.warn("[PostCompactAttachmentRestorer] AutoMem 入口路径解析失败，跳过该 memory 排除项: {}",
                e.getMessage());
        }
        return paths;
    }


    /**
     * 收集 preserved 消息中的 Read tool_use 路径 · 对齐 CC {@code collectReadToolFilePaths}
     * （compact.ts:1610-1655）：preserved 尾部已可见的 Read 结果跳过重注入（最多 25K/compact 浪费）。
     *
     * <p><b>[R2 · OPD-CM5-A-04] FILE_UNCHANGED_STUB 判别</b>：先扫 tool_result 的 dedup stub
     * （CC compact.ts:1613-1621 收集 stubIds），再跳过对应 tool_use（CC :1624-1628
     * {@code stubIds.has(block.id)}）。WHY（CC collectReadToolFilePaths 注释）：stub 的
     * tool_result 指向更早完整 Read —— 该完整 Read 可能已被压缩掉，故 stub Read 不计入
     * preservedReadPaths，让 {@code createPostCompactFileAttachments} 重注入真实文件内容；
     * 若计入了，则压缩后模型缺真实文件内容（实害，探查 ✗-R2）。
     *
     * <p>Java 映射：assistant 消息 contentBlocks 中 type='tool_use' + name='Read' 的
     * file_path 输入（第二遍）；stubIds 收集（第一遍）覆盖两种 tool_result 表达：
     * <ul>
     *   <li>Role.tool 消息：content() 载荷文本 startsWith FILE_UNCHANGED_STUB +
     *       toolCallId()=tool_use_id（主路径 {@code toolResultMessage} 序列化形态）</li>
     *   <li>Role.user 消息 contentBlocks 内嵌 tool_result 块（CC user message block 扫描等价）</li>
     * </ul>
     *
     * @param messages preserved 消息
     * @return Read 文件路径集合
     */
    public static Set<String> collectReadToolFilePaths(List<ChatMessageDto> messages) {
        Set<String> paths = new HashSet<>();
        if (messages == null) {
            return paths;
        }
        // [R2] 第一遍：收集 dedup stub 命中的 tool_use_id（CC compact.ts:1613-1621）
        Set<String> stubIds = collectUnchangedStubIds(messages);
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.assistant || m.contentBlocks() == null) {
                continue;
            }
            for (Object blockObj : m.contentBlocks()) {
                if (!(blockObj instanceof com.fasterxml.jackson.databind.JsonNode block) || !block.isObject()) {
                    continue;
                }
                if (!"tool_use".equals(block.path("type").asText(""))
                    || !"Read".equals(block.path("name").asText(""))) {
                    continue;
                }
                // [R2] stub Read 跳过（CC compact.ts:1624-1628 stubIds.has(block.id)）
                String toolUseId = block.path("id").asText("");
                if (!toolUseId.isBlank() && stubIds.contains(toolUseId)) {
                    continue;
                }
                String path = block.path("input").path("file_path").asText("");
                if (!path.isBlank()) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    /**
     * [R2 · OPD-CM5-A-04] 收集 FILE_UNCHANGED_STUB dedup 命中的 tool_use_id · 对齐 CC
     * compact.ts:1613-1621（tool_result 块 content startsWith FILE_UNCHANGED_STUB →
     * 收其 tool_use_id）。
     */
    private static Set<String> collectUnchangedStubIds(List<ChatMessageDto> messages) {
        Set<String> stubIds = new HashSet<>();
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            if (m.role() == Role.tool) {
                // 主路径：tool_result 消息载荷文本 = 摘要（file_unchanged → FILE_UNCHANGED_STUB）
                if (m.content() != null && m.content().startsWith(FILE_UNCHANGED_STUB)
                        && m.toolCallId() != null && !m.toolCallId().isBlank()) {
                    stubIds.add(m.toolCallId());
                }
            } else if (m.role() == Role.user && m.contentBlocks() != null) {
                // 兼容表达：user 消息 contentBlocks 内嵌 tool_result 块
                for (Object blockObj : m.contentBlocks()) {
                    if (!(blockObj instanceof com.fasterxml.jackson.databind.JsonNode block) || !block.isObject()) {
                        continue;
                    }
                    if (!"tool_result".equals(block.path("type").asText(""))) {
                        continue;
                    }
                    JsonNode content = block.path("content");
                    if (content.isTextual() && content.asText().startsWith(FILE_UNCHANGED_STUB)) {
                        String id = block.path("tool_use_id").asText("");
                        if (!id.isBlank()) {
                            stubIds.add(id);
                        }
                    }
                }
            }
        }
        return stubIds;
    }

    // ════════════════════════════════════════════════════════════════════
    // 附件工厂（compact.ts:1470-1599 + 3×delta :567-585）
    // ════════════════════════════════════════════════════════════════════

    /**
     * plan_file_reference 附件消息 · 对齐 CC {@code createPlanAttachmentIfNeeded}
     * （compact.ts:1470-1486）{@code createAttachmentMessage({type:'plan_file_reference',
     * planFilePath, planContent})} 的 Java ChatMessageDto 表达（author='attachment' +
     * subtype='plan_file_reference' + JSON 载荷，与 {@link #planModeAttachment} 同一载体形态）。
     *
     * <p><b>WHY 存在（IMP-CM-04 · OPD-CM3-15/D01）</b>: SM 压缩路径
     * （SessionMemoryService.trySessionMemoryCompaction）结果构造直接注入
     * CompactionResult.attachments（CC sessionMemoryCompact.ts:484-485），无
     * SessionAgentStateRegistry/state.attachments() typed 通道（传统路径 populatePlanAttachment
     * 用该通道）。本 helper 与 {@link #populatePostCompactAttachments} 的 plan_file_reference 渲染
     * 共享同一表达，避免 SM 路径手拼 ChatMessageDto 双轨（规则八：落笔前先阅读既有渲染）。
     *
     * @param planRef plan 引用（planFilePath + planContent · CC compact.ts:1481-1485）
     * @return plan_file_reference 附件消息；planRef null → null
     */
    public static ChatMessageDto planFileReferenceMessage(AttachmentMessageDto.PlanRef planRef) {
        if (planRef == null) {
            return null;
        }
        ObjectNode node = SKILL_JSON.createObjectNode();
        node.put("type", "plan_file_reference");
        node.put("planFilePath", planRef.planFilePath() == null ? "" : planRef.planFilePath());
        node.put("planContent", planRef.planContent() == null ? "" : planRef.planContent());
        return buildAttachmentMessage("plan_file_reference", toJson(node));
    }

    /** plan_mode 附件 · CC original: createPlanModeAttachmentIfNeeded（compact.ts:1542-1560，type 'plan_mode'）。 */
    public static ChatMessageDto planModeAttachment(boolean inPlanMode, String planFilePath, boolean planExists, boolean isSubAgent) {
        if (!inPlanMode) {
            return null;
        }
        ObjectNode node = SKILL_JSON.createObjectNode();
        node.put("type", "plan_mode");
        node.put("reminderType", "full");
        node.put("isSubAgent", isSubAgent);
        // safeJson 原语义 null→""（保持可观测行为不变）
        node.put("planFilePath", planFilePath == null ? "" : planFilePath);
        node.put("planExists", planExists);
        return buildAttachmentMessage("plan_mode", toJson(node));
    }

    /**
     * 调用技能附件 · CC original: createSkillAttachmentIfNeeded（compact.ts:1494-1534，
     * type 'invoked_skills'）：per-skill 截断 5K + 总预算 25K，most-recent-first 排序。
     *
     * @param skills 已调用技能（skillName/skillPath/content/invokedAt；按 invokedAt 降序）
     * @return 技能附件消息（技能为空 / 全部超预算 → null）
     */
    public static ChatMessageDto skillAttachment(List<SkillInfo> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        List<SkillInfo> sorted = new ArrayList<>(skills);
        sorted.sort((a, b) -> Long.compare(b.invokedAt(), a.invokedAt()));
        int usedTokens = 0;
        ArrayNode skillsArr = SKILL_JSON.createArrayNode();
        for (SkillInfo skill : sorted) {
            String truncated = SkillContentTruncator.truncateToTokens(skill.content() == null ? "" : skill.content(),
                CompactConstants.POST_COMPACT_MAX_TOKENS_PER_SKILL);
            int tokens = CompactConversation.roughTokenCountEstimation(truncated);
            if (usedTokens + tokens > CompactConstants.POST_COMPACT_SKILLS_TOKEN_BUDGET) {
                continue;
            }
            usedTokens += tokens;
            skillsArr.addObject()
                .put("name", skill.skillName())
                .put("path", skill.skillPath())
                .put("content", truncated);
        }
        if (skillsArr.size() == 0) {
            return null;
        }
        ObjectNode root = SKILL_JSON.createObjectNode();
        root.put("type", "invoked_skills");
        root.set("skills", skillsArr);
        return buildAttachmentMessage("invoked_skills", toJson(root));
    }

    /**
     * 从 AgentState 数据源构建 invoked_skills 附件 · 对齐 CC
     * {@code createSkillAttachmentIfNeeded(context.agentId)}（compact.ts:1494-1534）的
     * <b>读取路径</b> {@code getInvokedSkillsForAgent(agentId)}（state.ts:1530-1541）。
     *
     * <p><b>数据流</b>：AgentState.getInvokedSkillsForAgent(UUID) 严格相等过滤
     * （null 只匹配主会话 null-agent skill；agentId 为 UUID 字符串时只匹配对应 fork
     * 子 agent）→ 映射为 {@link SkillInfo} → 委托 {@link #skillAttachment(List)}
     * （复用 per-skill 5K 截断 + 总预算 25K + most-recent-first，行 212-236）。
     *
     * @param state  会话主 AgentState（未注册/不存在 → null）
     * @param agentId CC context.agentId（UUID 字符串；blank/null → null=主会话语义）
     * @return invoked_skills 附件消息（无 skill / 全部超预算 → null）
     */
    public static ChatMessageDto skillAttachmentForAgent(AgentState state, String agentId) {
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] skillAttachmentForAgent: state 为 null，跳过");
            }
            return null;
        }
        UUID agentUuid = parseUuidOrNull(agentId);
        List<SkillInfo> skills = state.getInvokedSkillsForAgent(agentUuid).values().stream()
            .map(info -> new SkillInfo(info.skillName(), info.skillPath(), info.content(), info.invokedAt()))
            .toList();
        if (skills.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] skillAttachmentForAgent: 无 invoked skill，跳过"
                    + "（agentId={}）", agentId);
            }
            return null;
        }
        return skillAttachment(skills);
    }

    /**
     * 在压缩成功路径填充 invoked_skills 附件 · 对齐 CC compact.ts:558-560
     * {@code const skillAttachment = createSkillAttachmentIfNeeded(context.agentId);
     * if (skillAttachment) postCompactFileAttachments.push(skillAttachment)}。
     *
     * <p><b>数据源</b>：经 {@link SessionAgentStateRegistry#get(UUID)} 按
     * {@code ctx.sessionId} 解析会话主 AgentState（LlmAgentLoop:1545-1546 主会话注册），
     * 再经 {@link #skillAttachmentForAgent} 读取该 agent 的 invokedSkills。结果 append 到
     * {@link CompactConversationContext#setAdditionalPostCompactAttachments}，使
     * {@code CompactionResult.attachments()} / {@code buildPostCompactMessages} 出现
     * subtype='invoked_skills' 附件（per-skill 5K + 总预算 25K，most-recent-first）。
     *
     * <p><b>防双注入</b>：additionalPostCompactAttachments 已含 subtype='invoked_skills'
     * 时跳过（调用方显式注入 + 本方法不得重复）。安全降级：registry/sessionId 任一缺失、
     * sessionId 非 UUID、会话未注册、无 invoked skill → 均跳过不抛错（不中断压缩成功路径）。
     *
     * @param registry 会话 AgentState 注册表（CC STATE 读侧 Java 等价）
     * @param ctx      压缩上下文（sessionId/agentId 数据源 + additionalPostCompactAttachments 填充面）
     */
    public static void populateInvokedSkillsAttachment(
            SessionAgentStateRegistry registry, CompactConversationContext ctx) {
        if (registry == null || ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] populateInvokedSkillsAttachment:"
                    + " registry/sessionId 缺失，跳过 skill 附件注入");
            }
            return;
        }
        // [session-id-short] sessionId 已 short 直键 registry（原 UUID.fromString 对 sess-xxx 抛 IAE
        // 使 compact 注入恒跳过 —— 生产 invoked_skills 永不注入的主路径断裂根因，本次根治）
        AgentState state = registry.get(ctx.getSessionId());
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] populateInvokedSkillsAttachment:"
                    + " 会话未注册 AgentState，跳过（sessionId={}）", ctx.getSessionId());
            }
            return;
        }
        List<ChatMessageDto> existing = ctx.getAdditionalPostCompactAttachments();
        if (existing != null && existing.stream().anyMatch(a ->
                a != null && "invoked_skills".equals(a.subtype()))) {
            log.info("[PostCompactAttachmentRestorer] populateInvokedSkillsAttachment:"
                + " invoked_skills 附件已存在，跳过双注入（sessionId={}）", ctx.getSessionId());
            return;
        }
        ChatMessageDto skillAttachment = skillAttachmentForAgent(state, ctx.getAgentId());
        if (skillAttachment == null) {
            log.info("[PostCompactAttachmentRestorer] populateInvokedSkillsAttachment:"
                + " 无 invoked skill 可注入，跳过（sessionId={} agentId={}）",
                ctx.getSessionId(), ctx.getAgentId());
            return;
        }
        List<ChatMessageDto> combined = new ArrayList<>();
        if (existing != null) {
            combined.addAll(existing);
        }
        combined.add(skillAttachment);
        ctx.setAdditionalPostCompactAttachments(combined);
        log.info("[PostCompactAttachmentRestorer] populateInvokedSkillsAttachment:"
            + " skill 附件注入成功（sessionId={} agentId={} subtype=invoked_skills）",
            ctx.getSessionId(), ctx.getAgentId());
    }

    /**
     * 在压缩成功路径填充 plan_file_reference 附件 · 对齐 CC compact.ts:545-548
     * {@code const planAttachment = createPlanAttachmentIfNeeded(context.agentId);
     * if (planAttachment) postCompactFileAttachments.push(planAttachment)}。
     *
     * <p><b>数据源</b>: 经 {@link PlanProvider#createPlanAttachmentIfNeeded(UUID)} 读磁盘 plan
     * 文件（plans.ts:119-145 getPlanFilePath / getPlan）。planProvider 未注入时按
     * {@code ctx.sessionId} 回落构造 {@link PlanProviderImpl}（默认 plans 目录），使 compact 重建
     * 链真正拿到磁盘真实路径。
     *
     * <p><b>[WF6 R2] typed 通道收敛</b>: 有 plan 文件 → 经
     * {@link AttachmentMessageDto#planFileReference} typed 工厂写入 {@code state.attachments()}
     * （经 registry 解析主会话 AgentState），由 {@code maybeInjectHookAttachments →
     * renderHookAttachmentForLlm case 'plan_file_reference'} 渲染为 system-reminder（携带磁盘全文）。
     * 不再手拼 ChatMessageDto（author='attachment' 双轨），消除 typed 工厂零调用方 + render case
     * 死代码。防累积：append 前 {@code removeAttachmentsByType("plan_file_reference")}
     * （对齐 invoked_skills 模式，跨压缩不累积）。
     *
     * <p><b>降级</b>: registry/sessionId/AgentState 缺失、sessionId 非 UUID、无 plan 文件 →
     * 均跳过不抛错（不中断压缩成功路径）。
     *
     * @param registry 会话 AgentState 注册表（CC STATE 读侧 Java 等价）
     * @param ctx      压缩上下文（sessionId/agentId/planProvider 数据源）
     */
    public static void populatePlanAttachment(SessionAgentStateRegistry registry, CompactConversationContext ctx) {
        if (registry == null || ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] populatePlanAttachment: registry/sessionId 缺失，跳过 plan 附件注入");
            }
            return;
        }
        // [session-id-short] sessionId 已 short 直键 registry（原 UUID.fromString 对 sess-xxx 抛 IAE）
        AgentState state = registry.get(ctx.getSessionId());
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] populatePlanAttachment:"
                    + " 会话未注册 AgentState，跳过（sessionId={}）", ctx.getSessionId());
            }
            return;
        }
        PlanProvider provider = resolvePlanProvider(ctx);
        if (provider == null) {
            log.warn("[PostCompactAttachmentRestorer] populatePlanAttachment:"
                + " 无显式 planProvider 且 sessionId 非法 UUID，跳过（sessionId={}）", ctx.getSessionId());
            return;
        }
        UUID agentUuid = parseUuidOrNull(ctx.getAgentId());
        AttachmentMessageDto.PlanRef planRef = provider.createPlanAttachmentIfNeeded(agentUuid);
        if (planRef == null) {
            log.info("[PostCompactAttachmentRestorer] populatePlanAttachment:"
                + " 无 plan 文件，降级不注入（sessionId={} agentId={}）", ctx.getSessionId(), ctx.getAgentId());
            return;
        }
        state.removeAttachmentsByType("plan_file_reference");
        state.appendAttachment(AttachmentMessageDto.planFileReference(planRef));
        log.info("[PostCompactAttachmentRestorer] populatePlanAttachment:"
            + " plan_file_reference 附件经 typed 工厂注入 state.attachments()（sessionId={} agentId={} path={} chars={}）",
            ctx.getSessionId(), ctx.getAgentId(), planRef.planFilePath(),
            planRef.planContent() == null ? 0 : planRef.planContent().length());
    }

    /**
     * 在压缩成功路径填充 plan_mode 附件 · 对齐 CC compact.ts:552-555
     * {@code const planModeAttachment = await createPlanModeAttachmentIfNeeded(context);
     * if (planModeAttachment) postCompactFileAttachments.push(planModeAttachment)}。
     *
     * <p><b>数据源</b>: plan 模式判定读 {@code appState.toolPermissionContext.mode === 'plan'}
     * （compact.ts:1545-1547，Java 经 {@link CompactConversationContext#isInPlanMode()}）；planFilePath /
     * planExists 经 {@link PlanProvider#getPlanFilePath(UUID)} / {@link PlanProvider#getPlan(UUID)}。
     * 非 plan 模式 → 不注入；plan 模式但无 plan 文件 → 仍注入 plan_mode（planExists=false，
     * 让模型知道当前处于 plan 模式 + plan 文件应写到的路径）。
     *
     * <p><b>防双注入</b>: additionalPostCompactAttachments 已含 subtype='plan_mode' 时跳过。
     *
     * @param ctx 压缩上下文（sessionId/agentId/planProvider/toolUseContext 数据源）
     */
    public static void populatePlanModeAttachment(CompactConversationContext ctx) {
        if (ctx == null) {
            return;
        }
        if (!ctx.isInPlanMode()) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] populatePlanModeAttachment: 非 plan 模式，跳过（sessionId={}）",
                    ctx.getSessionId());
            }
            return;
        }
        PlanProvider provider = resolvePlanProvider(ctx);
        if (provider == null) {
            log.warn("[PostCompactAttachmentRestorer] populatePlanModeAttachment:"
                + " plan 模式下 sessionId 非法 UUID 且无显式 planProvider，跳过（sessionId={}）", ctx.getSessionId());
            return;
        }
        List<ChatMessageDto> existing = ctx.getAdditionalPostCompactAttachments();
        if (existing != null && existing.stream().anyMatch(a ->
                a != null && "plan_mode".equals(a.subtype()))) {
            log.info("[PostCompactAttachmentRestorer] populatePlanModeAttachment:"
                + " plan_mode 附件已存在，跳过双注入（sessionId={}）", ctx.getSessionId());
            return;
        }
        UUID agentUuid = parseUuidOrNull(ctx.getAgentId());
        String planFilePath = provider.getPlanFilePath(agentUuid);
        boolean planExists = provider.getPlan(agentUuid) != null;
        boolean isSubAgent = agentUuid != null;
        ChatMessageDto attachment = planModeAttachment(true, planFilePath, planExists, isSubAgent);
        List<ChatMessageDto> combined = new ArrayList<>();
        if (existing != null) {
            combined.addAll(existing);
        }
        combined.add(attachment);
        ctx.setAdditionalPostCompactAttachments(combined);
        log.info("[PostCompactAttachmentRestorer] populatePlanModeAttachment:"
            + " plan_mode 附件注入成功（sessionId={} agentId={} path={} planExists={}）",
            ctx.getSessionId(), ctx.getAgentId(), planFilePath, planExists);
    }

    /**
     * 解析 plan 提供者 · 显式注入优先，否则按 sessionId 回落构造 {@link PlanProviderImpl}
     * （concern B —— 生产调用方未注入 planProvider 时兜底，使 compact 重建链真正读磁盘）。
     *
     * @param ctx 压缩上下文（planProvider + sessionId）
     * @return PlanProvider；sessionId 非法 UUID 且无显式 planProvider → null
     */
    private static PlanProvider resolvePlanProvider(CompactConversationContext ctx) {
        PlanProvider provider = ctx.getPlanProvider();
        if (provider != null) {
            return provider;
        }
        // [session-id-short] ctx.getSessionId() 已 short，PlanProviderImpl 直收 String（不再 UUID.fromString）
        return new PlanProviderImpl(ctx.getSessionId());
    }

    /**
     * 解析当前会话 plan 文件路径（供 shouldExcludeFromPostCompactRestore plan 排除）· 对齐 CC
     * {@code getPlanFilePath(agentId)}（compact.ts:1680-1687 try/catch 语义）。
     *
     * <p>经 {@link #resolvePlanProvider} 取 {@link PlanProvider}（显式注入优先，否则按 sessionId
     * 回落构造 {@link PlanProviderImpl}），再 {@link PlanProvider#getPlanFilePath(UUID)} 取主会话
     * （agentId null）/ 子代理（agentId 非 null）plan 文件路径。任一环节不可得（ctx/sessionId 缺失、
     * provider 不可解析、路径计算抛异常）→ 返回 null（跳过 plan 排除，继续 memory 排除），
     * 对齐 CC compact.ts:1682-1686 try/catch「拿不到 plan 路径则继续其它检查」。
     *
     * @param ctx 压缩上下文（planProvider/sessionId/agentId 数据源）
     * @return 当前会话 plan 文件绝对路径；不可得 → null
     */
    private static String resolvePlanFilePath(CompactConversationContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            PlanProvider provider = resolvePlanProvider(ctx);
            if (provider == null) {
                return null;
            }
            return provider.getPlanFilePath(parseUuidOrNull(ctx.getAgentId()));
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactAttachmentRestorer] 解析 plan 文件路径失败，跳过 plan 排除: {}",
                    e.getMessage());
            }
            return null;
        }
    }

    /**
     * 恢复 skill 状态（两段）· 对齐 CC {@code restoreSkillStateFromMessages}
     * （utils/conversationRecovery.ts:382-403；@internal 导出，调用序 :558 于
     * {@code deserializeMessagesWithInterruptDetection} :561 前 —— resume 加载转录后、
     * 恢复 loop 运行前）。
     *
     * <p><b>两段语义（CC :382-403 完整）</b>：
     * <ol>
     *   <li>invoked_skills attachment → {@code addInvokedSkill(name, path, content, null)}
     *       （CC :387-393）；</li>
     *   <li>skill_listing attachment → {@code suppressNextSkillListing()}（CC :399-401，
     *       一次性抑制，转录已含 skills-available 提醒时不重复注入）。消费侧在
     *       {@code AgentLoopContext.computeSkillListingDelta} 接线。</li>
     * </ol>
     *
     * <p><b>WHY（CC javadoc 原义）</b>: resume（compact 后会话接续 / 新会话继续）时
     * invokedSkills 必须从转录恢复 —— 否则 resume 后再发生一次压缩，技能会因
     * STATE.invokedSkills 为空而丢失（conversationRecovery.ts:382-386）。Java 端
     * invokedSkills 是 AgentState 内存态（{@code @JsonIgnore} local-only，不随会话
     * 持久化）；压缩后的 invoked_skills attachment 留在消息列表（{@link
     * #populateInvokedSkillsAttachment} 注入 → {@code AgentState.replaceMessages}
     * 存活 → DB 写回），本方法在 resume 侧把该 attachment 重建为 invokedSkills，
     * 使下一次压缩的 {@code createSkillAttachmentIfNeeded}（compact.ts:558/:950
     * 读取路径）能再次注入。
     *
     * <p><b>恢复条件（CC :388-390）</b>: skill.name && skill.path && skill.content
     * 全真才恢复，不完整条目跳过（M-36）。agentId 恒 null —— CC :391 注释
     * "Resume only happens for the main session, so agentId is null"。
     *
     * <p><b>附件判别</b>: ChatMessageDto author='attachment'（{@link #ATTACHMENT_AUTHOR}）
     * + subtype='invoked_skills'，content 为 {@link #skillAttachment} 产出的 JSON
     * payload（{@code {"type":"invoked_skills","skills":[{name,path,content}]}}，:236）。
     * 与 {@link #skillAttachment} 同一载荷契约，无第二格式（无兼容层）。
     *
     * <p><b>suppress 副作用（CC :399-401 第二半段）</b>: 检测到 skill_listing attachment
     * → 对齐 CC 恢复点直接调 {@code suppressNextSkillListing()} 置真（一次性 latch，
     * attachments.ts:2633-2636）。Java 端 suppressNextSkillListing 是 per-run
     * {@code AgentLoopContext.LoopSessionState} 的 AtomicBoolean（非 CC 进程级全局 boolean），
     * 由恢复点（LlmAgentLoop.run 入口，镜像 CC loadConversationForResume:556-558）经本参
     * 传入，置真后由 {@code AgentLoopContext.computeSkillListingDelta} 一次性
     * {@code compareAndSet(true,false)} 消耗（不重复注入 ~600 token 清单）。
     *
     * <p><b>resume 标志（P2-23 · WF8-01 △-2）</b>: CC {@code restoreSkillStateFromMessages}
     * 仅 resume 路径调用（conversationRecovery.ts:556-558 loadConversationForResume），
     * Java 端因 AgentState 每 run 新建 + invokedSkills {@code @JsonIgnore} 不持久化需每次
     * run 从转录重建（架构补偿）。引入 {@code resume} 标志将恢复限定于「会话已有历史
     * = 续跑/resume」的运行。
     *
     * <p><b>[P2-23 返工] resume 计算语义</b>: resume = 转录中存在<b>非当前 in-flight 用户消息</b>
     * 的消息（排除 {@code streamUserMessageId}）。生产 {@code ChatController.send()} 第一步
     * 同步 {@code createUserMessage} 持久化当前用户消息 → {@code listBySession} 在 run() 入口
     * 恒含当前用户消息 → 旧实现「转录非空」恒真，{@code if (!resume) return;} 为死分支。
     * 改为排除当前用户消息后判「会话有历史」：全新会话首 run（转录仅含当前用户消息）→
     * resume=false 直接返回（无技能状态可恢复）；后续 run（含先前历史消息）→ resume=true 恢复。
     * 调用方（LlmAgentLoop.run 入口）负责按此语义计算并传参；本方法对 resume=false
     * 直接 return（guard 可达，非死分支）。
     *
     * <p><b>残留登记（[P2-23 返工] 如实披露，不作「消除」声明）</b>:
     * <ul>
     *   <li><b>每 run 抑制风险（WF8-01 R1/T-3）</b>：会话续跑（resume=true）且转录残留
     *       skill_listing 附件时，本方法每 run 重武装 suppressNextSkillListing →
     *       {@code AgentLoopContext.computeSkillListingDelta:670} 每 run 走抑制分支
     *       （compareAndSet(true,false) → 全量标 sent → 空 delta），新技能可能不注入。
     *       Java sentSkillNames / suppressNextSkillListing 均为 per-run
     *       （LoopSessionState 每 run 新建），CC 为进程级常驻（attachments.ts:2607/:2636）——
     *       架构补偿固有残留，登记未解决。</li>
     *   <li><b>invokedAt 每 run 刷新（△-1 / DEL-WF8-1）</b>：转录残留 invoked_skills 附件
     *       且 resume=true 时，每 run addInvokedSkill 刷新时间戳
     *       （{@code max(prev+1, now)}，AgentState.java:860），invoked_skills 附件排序
     *       （compact.ts:1508 most-recent-first）时序偏差仍存在，登记未解决
     *       （invokedAt 不进附件载荷，LLM 侧无差异）。</li>
     * </ul>
     *
     * @param state                   目标主会话 AgentState（恢复写入面；非 null —— CC STATE 恒存在，P3-36 删除 null 防御半段）
     * @param messages                待扫描消息列表（resume 加载的转录；null → no-op）
     * @param suppressNextSkillListing per-run suppressNextSkillListing AtomicBoolean（LoopSessionState；null → 跳过置真）
     * @param resume                  resume 标志（P2-23）：true = 会话有历史续跑（转录含非当前用户消息，
     *                                对齐 CC 仅 resume 路径恢复）；false = 全新会话首 run（转录仅含当前
     *                                in-flight 用户消息）→ 跳过恢复（不刷新 invokedAt / 不重武装 suppress）
     */
    public static void restoreSkillStateFromMessages(AgentState state, List<ChatMessageDto> messages,
            AtomicBoolean suppressNextSkillListing, boolean resume) {
        if (!resume) {
            return;
        }
        if (messages == null) {
            return;
        }
        boolean skillListingFound = false;
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.user || !ATTACHMENT_AUTHOR.equals(m.author())) {
                continue;
            }
            // CC conversationRecovery.ts:399-401 第二半段：转录已含 skills-available 提醒
            // （skill_listing attachment）→ suppressNextSkillListing() 一次性抑制，避免 resume
            // 重复注入 ~600 token 清单。消费侧（compareAndSet 一次性消费）在
            // AgentLoopContext.computeSkillListingDelta（LoopSessionState.suppressNextSkillListing）。
            if ("skill_listing".equals(m.subtype())) {
                skillListingFound = true;
                continue;
            }
            if (!"invoked_skills".equals(m.subtype())) {
                continue;
            }
            JsonNode root;
            try {
                root = SKILL_JSON.readTree(m.content());
            } catch (Exception e) {
                log.warn("[PostCompactAttachmentRestorer] restoreSkillStateFromMessages:"
                    + " invoked_skills 载荷解析失败，跳过（id={}）: {}", m.id(), e.getMessage());
                continue;
            }
            JsonNode skills = root == null ? null : root.path("skills");
            if (skills == null || !skills.isArray()) {
                continue;
            }
            for (JsonNode skill : skills) {
                if (skill == null || !skill.isObject()) {
                    continue;
                }
                String name = skill.path("name").asText("");
                String path = skill.path("path").asText("");
                String content = skill.path("content").asText("");
                // CC conversationRecovery.ts:388-390: name/path/content 全真才恢复
                if (name.isBlank() || path.isBlank() || content.isBlank()) {
                    continue;
                }
                state.addInvokedSkill(name, path, content, null);
            }
        }
        if (skillListingFound) {
            if (suppressNextSkillListing != null) {
                suppressNextSkillListing.set(true);
            }
            log.info("[PostCompactAttachmentRestorer] restoreSkillStateFromMessages:"
                + " 检测到 skill_listing 附件 → suppressNextSkillListing 置真（一次性 latch，CC conversationRecovery.ts:399-401）");
        } else if (log.isDebugEnabled()) {
            log.debug("[PostCompactAttachmentRestorer] restoreSkillStateFromMessages: 未检测到 skill_listing 附件（CC conversationRecovery.ts:382-403）");
        }
    }

    /** 解析 agentId 为 UUID · blank/null → null（主会话语义，CC agentId ?? null）；非法 UUID → null（安全降级）。 */
    private static UUID parseUuidOrNull(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * async-agent 任务状态附件 · CC original: createAsyncAgentAttachmentsIfNeeded
     * （compact.ts:1568-1599，type 'task_status'）：跳过 retrieved/pending/自身 agentId。
     *
     * @param agents async-agent 任务（agentId/description/status/progressSummary/error/outputFilePath）
     * @param currentAgentId 当前 agentId（自身跳过）
     * @return task_status 附件列表
     */
    public static List<ChatMessageDto> asyncAgentAttachments(List<AsyncAgentInfo> agents, String currentAgentId) {
        List<ChatMessageDto> out = new ArrayList<>();
        if (agents == null) {
            return out;
        }
        for (AsyncAgentInfo agent : agents) {
            if (agent.retrieved() || "pending".equals(agent.status()) || agent.agentId().equals(currentAgentId)) {
                continue;
            }
            String deltaSummary = "running".equals(agent.status())
                ? (agent.progressSummary() == null ? "" : agent.progressSummary())
                : (agent.error() == null ? "" : agent.error());
            ObjectNode node = SKILL_JSON.createObjectNode();
            node.put("type", "task_status");
            node.put("taskType", "local_agent");
            // safeJson 原语义 null→""（保持可观测行为不变）
            node.put("taskId", agent.agentId() == null ? "" : agent.agentId());
            node.put("description", agent.description() == null ? "" : agent.description());
            node.put("status", agent.status() == null ? "" : agent.status());
            node.put("deltaSummary", deltaSummary);
            node.put("outputFilePath", agent.outputFilePath() == null ? "" : agent.outputFilePath());
            out.add(buildAttachmentMessage("task_status", toJson(node)));
        }
        return out;
    }

    /** 3×delta 附件 · CC original: getDeferredToolsDeltaAttachment / getAgentListingDeltaAttachment / getMcpInstructionsDeltaAttachment（compact.ts:567-585）。 */
    public static ChatMessageDto deltaAttachment(String deltaType, String content) {
        return buildAttachmentMessage(deltaType, content == null ? "" : content);
    }

    /**
     * [prompt-align CTX-09] deferred_tools_delta 人类可读渲染 · 对齐 CC messages.ts:4178-4195
     * case 'deferred_tools_delta'：
     * <pre>
     *   addedLines（= addedNames，formatDeferredToolLine = tool.name）非空 →
     *     `The following deferred tools are now available via ToolSearch:\n${addedLines.join('\n')}`
     *   removedNames 非空 →
     *     `The following deferred tools are no longer available (their MCP server disconnected). Do not
     *      search for them — ToolSearch will return no match:\n${removedNames.join('\n')}`
     *   parts.join('\n\n') → wrapMessagesInSystemReminder（:4193 = `&lt;system-reminder&gt;\n...\n&lt;/system-reminder&gt;`）
     * </pre>
     *
     * <p><b>双表示策略</b>：持久化 content 保持 JSON payload（{@link #deferredToolsDeltaAttachment} 产物，
     * scanAnnouncedDeltaNames :1627 读 addedNames/removedNames 完全兼容），LLM 注入用本方法渲染副本
     * （经 {@code ChatMessageDto.withContent} 生成 LLM 可见消息）。added/removed 均空或解析失败 → null。
     *
     * @param jsonPayload deferred_tools_delta 附件持久化 JSON（addedNames/removedNames 数组）
     * @return 人类可读 system-reminder 文本；null = 解析失败 / 两段均空
     */
    public static String renderDeferredToolsDelta(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = SKILL_JSON.readTree(jsonPayload);
            if (node == null || !node.isObject()) {
                return null;
            }
            java.util.List<String> added = new ArrayList<>();
            JsonNode addedNode = node.path("addedNames");
            if (addedNode.isArray()) {
                for (JsonNode n : addedNode) {
                    if (n.isTextual()) {
                        added.add(n.asText());
                    }
                }
            }
            java.util.List<String> removed = new ArrayList<>();
            JsonNode removedNode = node.path("removedNames");
            if (removedNode.isArray()) {
                for (JsonNode n : removedNode) {
                    if (n.isTextual()) {
                        removed.add(n.asText());
                    }
                }
            }
            if (added.isEmpty() && removed.isEmpty()) {
                return null;
            }
            java.util.List<String> parts = new ArrayList<>();
            if (!added.isEmpty()) {
                parts.add("The following deferred tools are now available via ToolSearch:\n"
                    + String.join("\n", added));
            }
            if (!removed.isEmpty()) {
                parts.add("The following deferred tools are no longer available (their MCP server disconnected). Do not search for them — ToolSearch will return no match:\n"
                    + String.join("\n", removed));
            }
            return "<system-reminder>\n" + String.join("\n\n", parts) + "\n</system-reminder>";
        } catch (Exception e) {
            log.debug("[PostCompactAttachmentRestorer] renderDeferredToolsDelta: 解析失败, 返回 null (subtype=deferred_tools_delta)");
            return null;
        }
    }
    // ════════════════════════════════════════════════════════════════════
    // [IMP2-03] 生产接线 · async-agent/plan/plan_mode 填充 + 3×delta 重宣布
    // （CC compact.ts:545-585 · ✗-1..✗-4 · INV-15；delta gate 默认关 → 生产默认 no-op）
    // ════════════════════════════════════════════════════════════════════

    /** CC 附件类型字面量 · deferred_tools_delta（attachments.ts:1474） */
    public static final String DELTA_TYPE_DEFERRED_TOOLS = "deferred_tools_delta";

    /** CC 附件类型字面量 · agent_listing_delta（attachments.ts:1548） */
    public static final String DELTA_TYPE_AGENT_LISTING = "agent_listing_delta";

    /** CC 附件类型字面量 · mcp_instructions_delta（attachments.ts:1584） */
    public static final String DELTA_TYPE_MCP_INSTRUCTIONS = "mcp_instructions_delta";

    /** CC Agent 工具名 · AGENT_TOOL_NAME（AgentTool/constants.ts:1；attachments.ts:1498 守卫） */
    public static final String AGENT_TOOL_NAME = AgentToolConstants.AGENT_TOOL_NAME;

    /** CC claude-in-chrome server 名 · NEXUSAI_IN_CHROME_MCP_SERVER_NAME（claudeInChrome/common.ts:12；CC 原值 'claude-in-chrome'，品牌改名 → 'nexusai-in-chrome'） */
    public static final String NEXUSAI_IN_CHROME_MCP_SERVER_NAME = "nexusai-in-chrome";

    /** env 注入 seam（测试用）· 镜像 ToolSearchService.envOverride 模式；null → System.getenv()。 */
    static volatile java.util.Map<String, String> envOverride = null;

    /**
     * [IMP2-03] 生产路径附件填充 · 对齐 CC compact.ts:545-560（async-agent → plan → plan_mode
     * 顺序 push postCompactFileAttachments；skill 由 {@link #populateInvokedSkillsAttachment}
     * 在压缩成功路径追加，3×delta 由 {@link #restore} 尾部重宣布——整体顺序
     * file→async→plan→plan_mode→skill→3×delta，compact.ts:541-585）。
     *
     * <p><b>数据源</b>（CC appState/context 依赖面 → Java）：
     * <ul>
     *   <li>async-agent：{@code TaskFrameworkService.listAll()} 的 LOCAL_AGENT 任务
     *       （CC appState.tasks local_agent，compact.ts:1571-1574）；BackgroundTask 无
     *       retrieved/progress/error 字段（MainSessionTaskState 有）→ 恒 false/null（保守照发）</li>
     *   <li>plan：{@link PlanProvider}（Java 无 plan 文件机制 → 生产 null → 不注入，concern B N/A）</li>
     *   <li>plan_mode：{@code ctx.toolUseContext.permissionMode() == PLAN}
     *       （CC appState.toolPermissionContext.mode === 'plan'，compact.ts:1545-1548）</li>
     * </ul>
     *
     * @param ctx                  压缩上下文（调用方须已 setToolUseContext）
     * @param taskFrameworkService 任务框架（LOCAL_AGENT 任务源；null → async-agent 跳过）
     * @param planProvider         plan 文件提供者（null → plan/planExists 降级不注入）
     */
    public static void populatePostCompactAttachments(
            CompactConversationContext ctx,
            TaskFrameworkService taskFrameworkService,
            PlanProvider planProvider) {
        if (ctx == null) {
            return;
        }
        List<ChatMessageDto> combined = new ArrayList<>();
        if (ctx.getAdditionalPostCompactAttachments() != null) {
            combined.addAll(ctx.getAdditionalPostCompactAttachments());
        }
        // ── 1. async-agent task_status（CC createAsyncAgentAttachmentsIfNeeded compact.ts:1568-1599）──
        List<AsyncAgentInfo> agents = new ArrayList<>();
        if (taskFrameworkService != null) {
            for (BackgroundTask task : taskFrameworkService.listAll()) {
                if (task.type() != TaskType.LOCAL_AGENT) {
                    continue;
                }
                agents.add(new AsyncAgentInfo(
                    task.agentId() != null ? task.agentId().toString() : task.id(),
                    task.description() == null ? "" : task.description(),
                    task.status() != null ? task.status().getStatusString() : "",
                    false,   // retrieved：BackgroundTask 无该字段（MainSessionTaskState 有）→ 恒 false
                    null,    // progressSummary：BackgroundTask 无（MainSessionTaskState.progress 有）
                    null,    // error：BackgroundTask 无
                    task.outputFile() == null ? "" : task.outputFile()));
            }
        }
        combined.addAll(asyncAgentAttachments(agents, ctx.getAgentId()));
        // ── 2. plan_file_reference（CC createPlanAttachmentIfNeeded compact.ts:1470-1486）──
        AttachmentMessageDto.PlanRef planRef =
            planProvider != null
                ? planProvider.createPlanAttachmentIfNeeded(parseUuidOrNull(ctx.getAgentId()))
                : null;
        if (planRef != null) {
            combined.add(planFileReferenceMessage(planRef));
        }
        // ── 3. plan_mode（CC createPlanModeAttachmentIfNeeded compact.ts:1542-1560）──
        ToolUseContext tuc = ctx.getToolUseContext();
        if (tuc != null && tuc.permissionMode() == PermissionMode.PLAN) {
            combined.add(planModeAttachment(true,
                planRef != null ? planRef.planFilePath() : null,
                planRef != null,
                ctx.getAgentId() != null && !ctx.getAgentId().isBlank()));
        }
        ctx.setAdditionalPostCompactAttachments(combined);
        if (log.isDebugEnabled()) {
            log.debug("[PostCompactAttachmentRestorer] populatePostCompactAttachments:"
                + " async={} plan={} planMode={} · CC compact.ts:545-560",
                agents.size(),
                planRef != null,
                tuc != null && tuc.permissionMode() == PermissionMode.PLAN);
        }
    }

    /**
     * [IMP2-03] 3×delta 压缩后重宣布 · 对齐 CC compact.ts:563-585：
     * {@code getDeferredToolsDeltaAttachment(tools, model, [], {callSite:'compact_full'})} +
     * {@code getAgentListingDeltaAttachment(context, [])} +
     * {@code getMcpInstructionsDeltaAttachment(mcpClients, tools, model, [])}。
     *
     * <p>Java 消息历史 preservedMessages 等价 CC diff 扫描源（partial 传 messagesToKeep、
     * 全量传 []）——从历史重建已 announce 集合，diff 后仅宣布增量；空历史 → 宣布全量集合。
     *
     * <p><b>Gate 默认关</b>：三 delta 均 feature/env 门控（isDeferredToolsDeltaEnabled /
     * shouldInjectAgentListInMessages / isMcpInstructionsDeltaEnabled 默认 false）→ 生产默认
     * no-op。CC 默认（feature 关）下 deferred-tools 走每调用 prepend（claude.ts:1330-1345
     * {@code <available-deferred-tools>} meta user message）、agent list 走 session_guidance
     * 子弹（prompts.ts:373）、mcp instructions 走 system prompt 段（prompts.ts:578-608）。
     * <b>[IMP2-03 返工 r2 更正]</b>：Java 主循环<b>无</b> deferred-tools prepend 通道（全库 grep
     * {@code available-deferred-tools} 0 命中，实证 eval JS 直读）——deferred 工具随 availableTools
     * 全量进 LLM schema（llmToolsArray LlmAgentLoop:6119）；该差异属主循环域任务（登记于 progress
     * IMP2-03 §7-1 返工 r2）。agent list / mcp instructions 两默认通道 Java 已有对应
     * （AgentToolSection 子弹 / SystemPromptSections.mcpInstructionsCompute）。Gate 开时按 Java
     * 数据源真实计算。
     */
    private static void appendPostCompactDeltaAttachments(
            CompactConversationContext ctx,
            List<ChatMessageDto> preservedMessages,
            List<ChatMessageDto> out) {
        ToolUseContext tuc = ctx == null ? null : ctx.getToolUseContext();
        if (tuc == null) {
            return;
        }
        String model = ctx.getModel();
        // 1. deferred_tools_delta（compact.ts:567-574）
        ChatMessageDto dtd = deferredToolsDeltaAttachment(tuc.availableTools(), model, preservedMessages);
        if (dtd != null) {
            out.add(dtd);
        }
        // 2. agent_listing_delta（compact.ts:575-577）
        ChatMessageDto ald = agentListingDeltaAttachment(tuc, preservedMessages);
        if (ald != null) {
            out.add(ald);
        }
        // 3. mcp_instructions_delta（compact.ts:578-585）
        ChatMessageDto mid = mcpInstructionsDeltaAttachment(tuc, model, preservedMessages);
        if (mid != null) {
            out.add(mid);
        }
        if (log.isDebugEnabled()) {
            log.debug("[PostCompactAttachmentRestorer] 3×delta 重宣布: dtd={} ald={} mid={}"
                + " · CC compact.ts:563-585",
                dtd != null, ald != null, mid != null);
        }
    }

    /**
     * deferred_tools_delta 生产 · 对齐 CC {@code getDeferredToolsDeltaAttachment}
     * （attachments.ts:1455-1475）+ {@code getDeferredToolsDelta}（toolSearch.ts:646-706）。
     *
     * <p>Gate 链（任一不过 → null）：isDeferredToolsDeltaEnabled（feature tengu_glacier_2xr /
     * USER_TYPE=ant，默认关）→ isToolSearchEnabledOptimistic（SchemaNotSentHint）→
     * modelSupportsToolReference（默认支持，'haiku' 不支持，toolSearch.ts:204/239-254）→
     * isToolSearchToolAvailable（ToolSearch 在工具池）。内容：当前 deferred 工具集
     * （isDeferredTool：MCP 工具恒 defer，alwaysLoad 排除，ToolSearch 自身不 defer，
     * ToolSearchService.isDeferredTool）对已 announce 集合的 diff。
     *
     * @param tools    工具池（CC options.tools）
     * @param model    主循环模型名（CC options.mainLoopModel；null → 假定支持）
     * @param messages 历史消息（diff 扫描源；全量压缩传 [] → 宣布全量）
     * @return deferred_tools_delta 附件；gate 关/无变化 → null
     */
    public static ChatMessageDto deferredToolsDeltaAttachment(
            List<Tool> tools, String model, List<ChatMessageDto> messages) {
        if (!isDeferredToolsDeltaEnabled()) {
            return null;
        }
        if (!ToolSearchService.isToolSearchEnabledOptimistic()) {
            return null;
        }
        if (!modelSupportsToolReference(model)) {
            return null;
        }
        if (!ToolSearchService.isToolSearchToolAvailable(tools)) {
            return null;
        }
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        Set<String> announced = scanAnnouncedDeltaNames(messages, DELTA_TYPE_DEFERRED_TOOLS,
            "addedNames", "removedNames");
        List<String> added = new ArrayList<>();
        Set<String> deferredNames = new HashSet<>();
        Set<String> poolNames = new HashSet<>();
        for (Tool t : tools) {
            poolNames.add(t.name());
            if (ToolSearchService.isDeferredTool(t, null)) {
                deferredNames.add(t.name());
                if (!announced.contains(t.name())) {
                    added.add(t.name());
                }
            }
        }
        added.sort(String::compareTo);
        List<String> removed = new ArrayList<>();
        for (String n : announced) {
            // CC toolSearch.ts:670-675：仍 deferred → silent（不下架）；已不在池 → removed
            if (!deferredNames.contains(n) && !poolNames.contains(n)) {
                removed.add(n);
            }
        }
        removed.sort(String::compareTo);
        if (added.isEmpty() && removed.isEmpty()) {
            return null;
        }
        // formatDeferredToolLine = tool.name（ToolSearchTool/prompt.ts:115-117）→ addedLines == addedNames
        String payload = "{\"type\":\"" + DELTA_TYPE_DEFERRED_TOOLS + "\",\"addedNames\":["
            + jsonArray(added) + "],\"addedLines\":[" + jsonArray(added)
            + "],\"removedNames\":[" + jsonArray(removed) + "]}";
        return deltaAttachment(DELTA_TYPE_DEFERRED_TOOLS, payload);
    }

    /**
     * agent_listing_delta 生产 · 对齐 CC {@code getAgentListingDeltaAttachment}
     * （attachments.ts:1490-1556）。
     *
     * <p>Gate：shouldInjectAgentListInMessages（env CLAUDE_CODE_AGENT_LIST_IN_MESSAGES 优先，
     * feature tengu_agent_list_attach 默认关；Java 静态面无 Spring 配置通道 → 默认关登记）+
     * AgentTool 在工具池。内容：agentDefinitions（SubagentTool.agentRegistry().listAgents()）经
     * MCP 需求过滤（loadAgentsDir.filterAgentsByMcpRequirements，mcpServers 取 TUC 已连接
     * server 名）+ SESSION deny 规则过滤（PermissionBubbleService 同款语义，attachments.ts:1513-1518）
     * → 对已 announce 集合的 diff → addedTypes/addedLines（formatAgentLine，prompt.ts:43-46）
     * /removedTypes。
     *
     * <p><b>Java 已知偏差（登记）</b>：allowedAgentTypes（CC options.agentDefinitions.
     * allowedAgentTypes，Java 无 Agent(x,y) 限制 → 不过滤）；showConcurrencyNote 恒 true
     * （Java 无订阅概念，CC getSubscriptionType() !== 'pro' 恒真）。
     *
     * @param tuc      工具使用上下文（tools/mcpClients/permissionContext 数据源）
     * @param messages 历史消息（diff 扫描源；全量压缩传 [] → 宣布全量）
     * @return agent_listing_delta 附件；gate 关/无 AgentTool/无 agent → null
     */
    public static ChatMessageDto agentListingDeltaAttachment(
            ToolUseContext tuc, List<ChatMessageDto> messages) {
        if (!shouldInjectAgentListInMessages()) {
            return null;
        }
        if (tuc == null || tuc.availableTools() == null || tuc.availableTools().isEmpty()) {
            return null;
        }
        SubagentTool subagentTool = null;
        boolean agentToolInPool = false;
        for (Tool t : tuc.availableTools()) {
            if (t instanceof SubagentTool st) {
                subagentTool = st;
            }
            if (AGENT_TOOL_NAME.equals(t.name())) {
                agentToolInPool = true;
            }
        }
        if (!agentToolInPool || subagentTool == null) {
            return null;
        }
        List<AgentDefinition> activeAgents = subagentTool.agentRegistry().listAgents();
        if (activeAgents.isEmpty()) {
            return null;
        }
        // CC attachments.ts:1508-1512 从工具池收集 mcp server 名；Java 取 TUC 已连接 server 名
        // （mcpClients 键，McpServerInfo.serverName 一致）——见方法 javadoc 偏差登记
        List<String> mcpServers = new ArrayList<>(tuc.mcpClients().keySet());
        List<AgentDefinition> filtered = loadAgentsDir
            .filterAgentsByMcpRequirements(activeAgents, mcpServers);
        // CC attachments.ts:1513-1518 filterDeniedAgents（SESSION deny 规则，agentId == toolName 匹配）
        filtered = filterDeniedAgentsBySessionRules(filtered, tuc.permissionContext());
        if (filtered.isEmpty()) {
            return null;
        }
        Set<String> announced = scanAnnouncedDeltaNames(messages, DELTA_TYPE_AGENT_LISTING,
            "addedTypes", "removedTypes");
        List<AgentDefinition> added = new ArrayList<>();
        Set<String> currentTypes = new HashSet<>();
        for (AgentDefinition a : filtered) {
            currentTypes.add(a.agentType());
            if (!announced.contains(a.agentType())) {
                added.add(a);
            }
        }
        List<String> removed = new ArrayList<>();
        for (String t : announced) {
            if (!currentTypes.contains(t)) {
                removed.add(t);
            }
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return null;
        }
        added.sort((a, b) -> a.agentType().compareTo(b.agentType()));
        removed.sort(String::compareTo);
        List<String> addedTypes = new ArrayList<>();
        List<String> addedLines = new ArrayList<>();
        for (AgentDefinition a : added) {
            addedTypes.add(a.agentType());
            addedLines.add(formatAgentLine(a));
        }
        String payload = "{\"type\":\"" + DELTA_TYPE_AGENT_LISTING + "\",\"addedTypes\":["
            + jsonArray(addedTypes) + "],\"addedLines\":[" + jsonArray(addedLines)
            + "],\"removedTypes\":[" + jsonArray(removed)
            + "],\"isInitial\":" + (announced.isEmpty())
            + ",\"showConcurrencyNote\":true}";
        return deltaAttachment(DELTA_TYPE_AGENT_LISTING, payload);
    }

    /**
     * mcp_instructions_delta 生产 · 对齐 CC {@code getMcpInstructionsDeltaAttachment}
     * （attachments.ts:1559-1585）+ {@code getMcpInstructionsDelta}（mcpInstructionsDelta.ts:55-130）。
     *
     * <p>Gate：isMcpInstructionsDeltaEnabled（env CLAUDE_CODE_MCP_INSTR_DELTA 优先，
     * feature tengu_basalt_3kr / USER_TYPE=ant，默认关）。内容：已连接且 instructions 非空的
     * server（Java TUC.mcpClients 即已连接 map）+ clientSide chrome 指令（ToolSearch 乐观可用
     * + 模型支持 + ToolSearch 在池，且 nexusai-in-chrome 已连接时）对已 announce 集合的 diff。
     *
     * @param tuc      工具使用上下文（mcpClients 数据源）
     * @param model    主循环模型名（clientSide chrome gate）
     * @param messages 历史消息（diff 扫描源；全量压缩传 [] → 宣布全量）
     * @return mcp_instructions_delta 附件；gate 关/无 instructions → null
     */
    public static ChatMessageDto mcpInstructionsDeltaAttachment(
            ToolUseContext tuc, String model, List<ChatMessageDto> messages) {
        if (!isMcpInstructionsDeltaEnabled()) {
            return null;
        }
        if (tuc == null || tuc.mcpClients() == null || tuc.mcpClients().isEmpty()) {
            return null;
        }
        Set<String> announced = scanAnnouncedDeltaNames(messages, DELTA_TYPE_MCP_INSTRUCTIONS,
            "addedNames", "removedNames");
        // blocks: serverName → 渲染块（mcpInstructionsDelta.ts:79-92，仅 instructions 非空 server）
        java.util.LinkedHashMap<String, String> blocks = new java.util.LinkedHashMap<>();
        Set<String> connectedNames = new HashSet<>();
        for (java.util.Map.Entry<String, McpClientRuntime> e : tuc.mcpClients().entrySet()) {
            McpClientRuntime info = e.getValue();
            String name = info != null && info.serverName() != null ? info.serverName() : e.getKey();
            connectedNames.add(name);
            if (info != null && info.instructions() != null && !info.instructions().isBlank()) {
                blocks.put(name, "## " + name + "\n" + info.instructions());
            }
        }
        // clientSide chrome（attachments.ts:1570-1580）：三 gate + nexusai-in-chrome 已连接
        if (ToolSearchService.isToolSearchEnabledOptimistic()
                && modelSupportsToolReference(model)
                && ToolSearchService.isToolSearchToolAvailable(tuc.availableTools())
                && connectedNames.contains(NEXUSAI_IN_CHROME_MCP_SERVER_NAME)) {
            String existing = blocks.get(NEXUSAI_IN_CHROME_MCP_SERVER_NAME);
            blocks.put(NEXUSAI_IN_CHROME_MCP_SERVER_NAME,
                existing != null
                    ? existing + "\n\n" + ChromePrompt.CHROME_TOOL_SEARCH_INSTRUCTIONS
                    : "## " + NEXUSAI_IN_CHROME_MCP_SERVER_NAME + "\n" + ChromePrompt.CHROME_TOOL_SEARCH_INSTRUCTIONS);
        }
        List<String> addedNames = new ArrayList<>();
        List<String> addedBlocks = new ArrayList<>();
        for (java.util.Map.Entry<String, String> b : blocks.entrySet()) {
            if (!announced.contains(b.getKey())) {
                addedNames.add(b.getKey());
                addedBlocks.add(b.getValue());
            }
        }
        List<String> removed = new ArrayList<>();
        for (String n : announced) {
            if (!connectedNames.contains(n)) {
                removed.add(n);
            }
        }
        if (addedNames.isEmpty() && removed.isEmpty()) {
            return null;
        }
        // CC 按 name 排序（mcpInstructionsDelta.ts:124 added.sort by name）
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < addedNames.size(); i++) {
            order.add(i);
        }
        order.sort((i, j) -> addedNames.get(i).compareTo(addedNames.get(j)));
        List<String> sortedNames = new ArrayList<>();
        List<String> sortedBlocks = new ArrayList<>();
        for (int i : order) {
            sortedNames.add(addedNames.get(i));
            sortedBlocks.add(addedBlocks.get(i));
        }
        removed.sort(String::compareTo);
        String payload = "{\"type\":\"" + DELTA_TYPE_MCP_INSTRUCTIONS + "\",\"addedNames\":["
            + jsonArray(sortedNames) + "],\"addedBlocks\":[" + jsonArray(sortedBlocks)
            + "],\"removedNames\":[" + jsonArray(removed) + "]}";
        return deltaAttachment(DELTA_TYPE_MCP_INSTRUCTIONS, payload);
    }

    // ── delta gate（默认关 · env 覆盖镜像 CC）──

    /**
     * CC isDeferredToolsDeltaEnabled（toolSearch.ts:629-634）：USER_TYPE=ant 或 feature
     * tengu_glacier_2xr。Java 无 GrowthBook 通道（OD-03 外部）→ 仅镜像 env，默认 false。
     */
    public static boolean isDeferredToolsDeltaEnabled() {
        java.util.Map<String, String> e = envOverride != null ? envOverride : System.getenv();
        return "ant".equals(e.get("USER_TYPE"));
    }

    /**
     * CC shouldInjectAgentListInMessages（AgentTool/prompt.ts:59-64）：env
     * CLAUDE_CODE_AGENT_LIST_IN_MESSAGES 优先，feature tengu_agent_list_attach 默认关。
     * Java 静态面无 Spring 配置通道（nexusai.agent.agent-list-in-messages 在 SubagentTool
     * 私有面）→ feature 通道默认关登记。
     */
    public static boolean shouldInjectAgentListInMessages() {
        java.util.Map<String, String> e = envOverride != null ? envOverride : System.getenv();
        if (isEnvTruthy(e.get("CLAUDE_CODE_AGENT_LIST_IN_MESSAGES"))) {
            return true;
        }
        if (isEnvDefinedFalsy(e.get("CLAUDE_CODE_AGENT_LIST_IN_MESSAGES"))) {
            return false;
        }
        return false; // feature tengu_agent_list_attach 默认关（Java 静态面无通道，登记）
    }

    /**
     * CC isMcpInstructionsDeltaEnabled（mcpInstructionsDelta.ts:37-44）：env
     * CLAUDE_CODE_MCP_INSTR_DELTA 优先，feature tengu_basalt_3kr / USER_TYPE=ant 默认关。
     */
    public static boolean isMcpInstructionsDeltaEnabled() {
        java.util.Map<String, String> e = envOverride != null ? envOverride : System.getenv();
        if (isEnvTruthy(e.get("CLAUDE_CODE_MCP_INSTR_DELTA"))) {
            return true;
        }
        if (isEnvDefinedFalsy(e.get("CLAUDE_CODE_MCP_INSTR_DELTA"))) {
            return false;
        }
        return "ant".equals(e.get("USER_TYPE"));
    }

    /**
     * CC modelSupportsToolReference（toolSearch.ts:239-254）：默认支持，仅命中 unsupported
     * 模式（DEFAULT_UNSUPPORTED_MODEL_PATTERNS = ['haiku']，toolSearch.ts:204）不支持。
     * null → 假定支持（CC 生产恒传已定义 model；Java manual 路径 best-effort 可 null）。
     */
    public static boolean modelSupportsToolReference(String model) {
        if (model == null) {
            return true;
        }
        return !model.toLowerCase().contains("haiku");
    }

    // ── delta 小工具 ──

    /**
     * 从历史重建已 announce 的 delta 名称集合 · 对齐 CC 三处 diff 扫描
     * （toolSearch.ts:651-663 / attachments.ts:1523-1530 / mcpInstructionsDelta.ts:63-70）：
     * 扫描 author='attachment' 且 subtype=deltaType 的消息，解析内联 JSON payload 的
     * added 数组入集、removed 数组出集。解析失败的消息跳过（保守）。
     *
     * @param messages    历史消息（null 安全）
     * @param deltaType   附件 subtype（CC attachment.type）
     * @param addedField  payload 中 added 数组字段名（addedNames/addedTypes）
     * @param removedField payload 中 removed 数组字段名（removedNames/removedTypes）
     * @return 已 announce 名称集合
     */
    static Set<String> scanAnnouncedDeltaNames(List<ChatMessageDto> messages, String deltaType,
            String addedField, String removedField) {
        Set<String> announced = new HashSet<>();
        if (messages == null) {
            return announced;
        }
        for (ChatMessageDto m : messages) {
            if (m == null || !ATTACHMENT_AUTHOR.equals(m.author()) || !deltaType.equals(m.subtype())
                    || m.content() == null) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.content());
                if (node == null || !node.isObject()) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode added = node.path(addedField);
                if (added.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : added) {
                        if (n.isTextual()) {
                            announced.add(n.asText());
                        }
                    }
                }
                com.fasterxml.jackson.databind.JsonNode removed = node.path(removedField);
                if (removed.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : removed) {
                        if (n.isTextual()) {
                            announced.remove(n.asText());
                        }
                    }
                }
            } catch (Exception e) {
                // 解析失败保守跳过（CC 无此分支——Java 消息模型差异，不中断压缩）
                log.debug("[PostCompactAttachmentRestorer] scanAnnouncedDeltaNames: 解析失败跳过"
                    + " subtype={}", deltaType);
            }
        }
        return announced;
    }

    /**
     * SESSION deny 规则过滤 · 对齐 PermissionBubbleService.filterDeniedAgents
     * （PermissionBubbleService.java:109-142）：SESSION 来源 alwaysDenyRules 中
     * ruleValue().toolName() == agentType 的 agent 剔除（CC filterDeniedAgents，
     * attachments.ts:1513-1518 同款消费）。Java 静态恢复面无 bubble service 实例 → 内联同语义。
     *
     * @param agents  待过滤 agent 列表
     * @param permCtx 权限上下文（null → 全量保留，对齐 SubagentTool.filterDeniedAgents fallback）
     * @return 未被 SESSION deny 的 agent 列表
     */
    private static List<AgentDefinition> filterDeniedAgentsBySessionRules(
            List<AgentDefinition> agents, ToolPermissionContext permCtx) {
        if (agents == null || agents.isEmpty() || permCtx == null) {
            return agents == null ? List.of() : agents;
        }
        Set<PermissionRule> sessionDenyRules = permCtx.alwaysDenyRules()
            .getOrDefault(PermissionRuleSource.SESSION, Set.of());
        if (sessionDenyRules.isEmpty()) {
            return agents;
        }
        Set<String> denied = new HashSet<>();
        for (PermissionRule rule : sessionDenyRules) {
            if (rule != null && rule.ruleValue() != null && rule.ruleValue().toolName() != null) {
                denied.add(rule.ruleValue().toolName());
            }
        }
        List<AgentDefinition> out = new ArrayList<>();
        for (AgentDefinition a : agents) {
            if (!denied.contains(a.agentType())) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * CC formatAgentLine（AgentTool/prompt.ts:43-46）+ getToolsDescription（prompt.ts:15-37）：
     * {@code `- type: whenToUse (Tools: toolsDescription)`}。
     */
    static String formatAgentLine(AgentDefinition agent) {
        return "- " + agent.agentType() + ": " + (agent.whenToUse() == null ? "" : agent.whenToUse())
            + " (Tools: " + toolsDescription(agent) + ")";
    }

    /** CC getToolsDescription（AgentTool/prompt.ts:15-37）四分支。 */
    private static String toolsDescription(AgentDefinition agent) {
        java.util.Optional<List<String>> toolsOpt = agent.tools();
        java.util.Optional<List<String>> disallowedOpt = agent.disallowedTools();
        boolean hasAllowlist = toolsOpt.isPresent() && !toolsOpt.get().isEmpty();
        boolean hasDenylist = disallowedOpt.isPresent() && !disallowedOpt.get().isEmpty();
        if (hasAllowlist && hasDenylist) {
            Set<String> denySet = new HashSet<>(disallowedOpt.get());
            List<String> effective = new ArrayList<>();
            for (String t : toolsOpt.get()) {
                if (!denySet.contains(t)) {
                    effective.add(t);
                }
            }
            return effective.isEmpty() ? "None" : String.join(", ", effective);
        } else if (hasAllowlist) {
            return String.join(", ", toolsOpt.get());
        } else if (hasDenylist) {
            return "All tools except " + String.join(", ", disallowedOpt.get());
        }
        return "All tools";
    }

    /** JSON 字符串数组（safeJson 转义）· CC addedNames/addedLines 等数组字段。 */
    private static String jsonArray(List<String> values) {
        ArrayNode arr = SKILL_JSON.createArrayNode();
        if (values != null) {
            for (String v : values) {
                arr.add(v == null ? "" : v);
            }
        }
        return arr.toString();
    }

    /** restore 日志辅助：统计 delta 附件数（subtype ∈ 三 delta 类型）。 */
    private static int deltaCount(List<ChatMessageDto> messages, List<ChatMessageDto> other) {
        int count = 0;
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            if (DELTA_TYPE_DEFERRED_TOOLS.equals(m.subtype())
                    || DELTA_TYPE_AGENT_LISTING.equals(m.subtype())
                    || DELTA_TYPE_MCP_INSTRUCTIONS.equals(m.subtype())) {
                count++;
            }
        }
        return count;
    }

    /** CC isEnvTruthy（envUtils.ts:32-37）· 1/true/yes/on。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase().trim();
        return List.of("1", "true", "yes", "on").contains(normalized);
    }

    /** CC isEnvDefinedFalsy（envUtils.ts:39-47）· 已定义且为 0/false/no/off。 */
    private static boolean isEnvDefinedFalsy(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase().trim();
        return List.of("0", "false", "no", "off").contains(normalized);
    }

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 构建附件消息（ChatMessageDto author='attachment'，subtype=附件类型）。 */
    static ChatMessageDto buildAttachmentMessage(String subtype, String content) {
        return buildAttachmentMessage(subtype, null, content);
    }

    /** 构建附件消息（含文件头）。 */
    static ChatMessageDto buildAttachmentMessage(String subtype, String filePath, String content) {
        String body = content;
        if (filePath != null && !filePath.isBlank()) {
            body = "File: " + filePath + "\n\n" + (content == null ? "" : content);
        }
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, ATTACHMENT_AUTHOR,
            body, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false,
            subtype);
    }

    /**
     * ObjectNode → JSON 字符串（规范序列化，与消费侧 {@code SKILL_JSON.readTree} 对称）。
     *
     * <p>替代旧 safeJson 手工拼装（仅转义 {@code \ " \n}，遇控制字符/Unicode 边界会产非法 JSON，
     * R2）。ObjectNode 序列化实际不抛异常，但保留 fail-loud 兜底（CLAUDE.md 规则十二：显式失败，
     * 不静默吞异常）。
     */
    private static String toJson(ObjectNode node) {
        try {
            return SKILL_JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invoked_skills/task_status/plan_mode 载荷序列化失败", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 数据源载体
    // ════════════════════════════════════════════════════════════════════

    /** 调用技能信息 · CC original: invoked skills attachment 项（compact.ts:1509-1516）。 */
    public record SkillInfo(String skillName, String skillPath, String content, long invokedAt) {}

    /** async-agent 任务状态 · CC original: LocalAgentTaskState（compact.ts:1571-1582）。 */
    public record AsyncAgentInfo(String agentId, String description, String status,
                                 boolean retrieved, String progressSummary, String error, String outputFilePath) {}
}
