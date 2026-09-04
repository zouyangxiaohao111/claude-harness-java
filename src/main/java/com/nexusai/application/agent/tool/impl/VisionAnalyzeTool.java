package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.attachment.ImageAttachmentStore.Base64Content;
import com.nexusai.application.agent.attachment.MediaLimitConstants;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.AttachmentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 视觉分析工具 · 自建工具（CC 无对应）· 代理视觉模型（任务 VisionAnalyze 方案定稿）。
 *
 * <p><b>CC 无对应（铁律：只信 CC 实际 TS 源码行为）</b>：CC 多模态为<b>模型内建能力</b>
 * （FileReadTool/prompt.ts:40 "Claude Code is a multimodal LLM"），无「多模态工具」路由。
 * Java 后端主模型不支持多模态（type=multimodal/vision 缺失）时，主模型收到「多模态提示
 * （含缓存 id）」→ 调用本工具 → 工具读 {@link ImageAttachmentStore} 缓存 → 把图片 + prompt
 * 发给独立视觉模型（settings.multimodalModelName）→ 返回<b>纯文本</b>给主模型。
 *
 * <p><b>与旧 MultimodalAttachmentTool 的本质差异</b>（重构动机）：旧工具把 image content
 * block（含 base64）注入<b>主模型</b>上下文，逻辑矛盾（只在主模型不支持视觉时触发，
 * 但注入 image block 又需要主模型看得懂）。本工具<b>代理视觉模型</b>：图片只发给视觉
 * 模型，主模型全程<b>不接触 base64</b>，收到的只是文本结果（图片以占位符
 * {@code [image:{contentId}]} 表示）。
 *
 * <p><b>type 枚举</b>：{@code analyze}=读缓存图（contentId 必填）→ 图片+prompt 发给视觉
 * 模型分析；{@code suggest}=纯文本 prompt（不读图）→ 视觉模型给建议。两路统一走
 * {@code settings.multimodalModelName}（视觉模型本身是 Claude 系，纯文本也能给建议）。
 *
 * <p><b>惰性（Tool impl 天然惰性）</b>：不预加载任何图片，只在模型发出 {@code tool_use}
 * 时经 {@link #execute} 触发 —— 对齐 CC 工具执行模型（toolExecution.ts {@code tool.call(...)}
 * 才执行）。
 *
 * <p><b>懒加载（shouldDefer=true）</b>：本工具 schema 不占初始 prompt（defer_loading），
 * 只在主模型不支持视觉、需要时经 ToolSearch 检索加载 —— 对齐 CC Tool.ts:442 + AbstractTaskTool
 * 一族模式。WHY：主流视觉模型用不上本工具，始终进 schema 是 token 浪费。
 *
 * <p><b>返回纯文本</b>：<b>不 override</b> {@link Tool#mapToToolResultBlockParam} → 走默认
 * {@code renderToolResultPayloadText} 文本渲染，绝无 base64 image block 注入主模型。
 *
 * <p><b>旧名保留</b>：{@code multimodal_attachment} 经 {@link #aliases()} 保留，ToolRegistry
 * aliasMap 保证 LLM 历史 transcript 老名仍可派发（对齐 CC toolMatchesName）。
 *
 * <p><b>注册</b>：本类<b>非</b> {@code @Component}，经
 * {@link com.nexusai.application.agent.config.ToolRegistrationConfig#visionAnalyzeTool}
 * {@code @Bean Tool} 注册（同 SkillTool / LSPTool 模式），由 ToolRegistry 构造器的
 * {@code @Autowired List<Tool>} 收集。
 */
public class VisionAnalyzeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(VisionAnalyzeTool.class);

    public static final String NAME = ToolNameConstants.VISION_ANALYZE_TOOL_NAME;

    /** 旧名 legacy alias · 保留 LLM 历史 transcript 派发（ToolRegistry aliasMap）。 */
    private static final String LEGACY_ALIAS = "multimodal_attachment";

    /** 图片附件缓存存储（读缓存图供 analyze 分支）。 */
    private final ImageAttachmentStore imageAttachmentStore;
    /** 多模态档位模型名 + ProviderConfig 单一来源解析。 */
    private final ModelConfigResolver modelConfigResolver;
    /** provider 工厂 · resolve 结果按 providerType 路由。 */
    private final LlmProviderFactory llmProviderFactory;
    /**
     * [附件双模式] 附件表（attachments）统一 contentId 注册中心 · path/upload 大图 contentId → 真实 path。
     *
     * <p>analyze 读图优先经本 bean 解析（contentId = attachments 自增 id，读盘 base64）；附件表无记录
     * （历史 image-cache ≤5MB contentId / 重启后内存索引丢失）→ 回退 {@link #imageAttachmentStore}。
     * 可 null（测试 / 3 参构造）→ 恒走 image-cache 回退（现状不变）。
     */
    private final AttachmentService attachmentService;

    /**
     * 3 参构造（历史调用方 / 测试零改动）→ 委托 4 参（attachmentService=null → 恒走 image-cache 回退）。
     */
    public VisionAnalyzeTool(ImageAttachmentStore imageAttachmentStore,
                             ModelConfigResolver modelConfigResolver,
                             LlmProviderFactory llmProviderFactory) {
        this(imageAttachmentStore, modelConfigResolver, llmProviderFactory, null);
    }

    /**
     * 4 参构造 · [附件双模式] 注入附件表统一 contentId 解析（ToolRegistrationConfig 生产装配；null → 回退）。
     *
     * @param imageAttachmentStore 图片附件缓存（读缓存图供 analyze 分支）
     * @param modelConfigResolver  多模态档位模型名 + ProviderConfig 解析（settings.multimodalModelName）
     * @param llmProviderFactory   provider 工厂（resolve 结果按 providerType 路由）
     * @param attachmentService    附件表统一 contentId → path 解析（&gt;5MB 大图注册中心；可 null）
     */
    public VisionAnalyzeTool(ImageAttachmentStore imageAttachmentStore,
                             ModelConfigResolver modelConfigResolver,
                             LlmProviderFactory llmProviderFactory,
                             AttachmentService attachmentService) {
        this.imageAttachmentStore = imageAttachmentStore;
        this.modelConfigResolver = modelConfigResolver;
        this.llmProviderFactory = llmProviderFactory;
        this.attachmentService = attachmentService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> aliases() {
        return List.of(LEGACY_ALIAS);
    }

    @Override
    public String description() {
        return "代理视觉模型分析：把图片/PDF 页与指令发给独立视觉模型（settings.multimodalModelName）"
                + "分析，返回纯文本结果。type=analyze 读源（source 二选一：contentId=附件表 contentId，"
                + "path=文件系统路径）+ contentType=image|pdf（pdf 支持 pages 显式页号数组 [1,2,3]）→ 视觉"
                + "模型分析；type=suggest 纯 prompt 建议（不读图）。返回文本以 [image/…] 占位符代替二进制，"
                + "绝不注入图片给主模型。";
    }

    /** 只读 · 读缓存 + 外部模型调用，无副作用。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 可并发 · 读缓存 + 独立模型调用无共享状态。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * <b>想懒（shouldDefer=true，2026-09-03 定稿）</b>：vision_analyze 默认懒加载（defer_loading 语义，
     * 省 schema token）。是否<b>实际懒</b>由<b>装配层按主模型能力豁免</b>决定（LlmAgentLoop
     * {@code exemptVisionAnalyzeDeferForTextModel}，见该处 javadoc）：
     * <ul>
     *   <li>主模型 = ant/response 直给格式 + 多模态（supportsImage）→ 能走 Read 直给通道，vision_analyze
     *       仅 PDF &gt;预算/分段补充 → <b>保留懒</b>（需时经 ToolSearch/discovered 激活）；</li>
     *   <li>其余（deepseek openai-completions，<b>含 vision-exp 多模态</b> / 任何文本模型）→ vision_analyze
     *       是<b>唯一视觉通道</b> → 装配层从 deferred 剔除 → <b>schema 直发</b>（不赌模型会 ToolSearch，
     *       历史 Read 图死循环 / fork 视觉子代理递归诱因之一）。</li>
     * </ul>
     *
     * <p><b>Task#15（子代理拿不到）根治</b>：子代理共享主循环 queryLoop 装配（LlmAgentLoop:5360 注释），
     * 装配层豁免一处即覆盖主/子代理 —— fresh 子代理若主模型非 ant+多模态，vision_analyze 不再因 defer
     * 排除，schema 直发可见。
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * 输入 schema v2 · {@code { type(必选), prompt(必选), contentType(可选), contentId|path(analyze 二选一), pages(可选) }}。
     * {@code contentId} = 附件表 contentId（LLM 侧字符串传递）；{@code path} = 文件系统路径（Read 引导场景，
     * 相对会话 cwd 解析）；{@code contentType=image|pdf} 分流源格式；{@code pages} 仅 pdf（显式页号数组）。
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode type = props.putObject("type");
        type.put("type", "string");
        type.put("enum", JsonNodeFactory.instance.arrayNode().add("analyze").add("suggest"));
        type.put("description", "analyze=读源并分析；suggest=纯 prompt 建议（不读图）");

        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "发给视觉模型的指令/问题文本（必填）");

        ObjectNode contentType = props.putObject("contentType");
        contentType.put("type", "string");
        contentType.put("enum", JsonNodeFactory.instance.arrayNode().add("image").add("pdf"));
        contentType.put("description", "源格式（可选）：image=单图；pdf=PDF（支持 pages 页号数组）。"
                + "缺省按 path 扩展名 / contentId 附件 mediaType 推断；suggest 忽略");

        ObjectNode contentId = props.putObject("contentId");
        contentId.put("type", "string");
        contentId.put("description", "附件表 contentId（整数 id 字符串形式；附件/粘贴媒体源）。"
                + "type=analyze 时与 path 二选一，互斥");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件系统路径（Read 引导场景；图片或 PDF）。相对路径按会话 cwd 解析。"
                + "type=analyze 时与 contentId 二选一，互斥");

        ObjectNode pages = props.putObject("pages");
        pages.put("type", "array");
        pages.put("description", "仅 contentType=pdf：显式页号数组（1-based，如 [1,2,3]），一次 ≤20 页。"
                + "缺省：≤10 页全渲染，>10 页报错提示带 pages");
        // [400 修复 2026-09-03] items 必须是 schema 对象 {"type":"integer"} —— 原 putArray("items").add("integer")
        //   生成 items=["integer"]（字符串数组）非法 JSON Schema → OpenAI 400 "Invalid schema ... anyOf"。
        //   putObject("items") 生成 items={"type":"integer"}，合法单元素数组 schema。
        pages.putObject("items").put("type", "integer");

        schema.putArray("required").add("type").add("prompt");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 输入语义验证 · type 缺失/非法 → errorCode "1"；prompt 缺失 → "2"；
     * analyze 缺 contentId → "3"；contentId 非数字 → "4"。数值格式合法性在 execute 内解析校验
     * （error message 注入 LLM 自纠）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String type = readString(input, "type");
        if (type == null || type.isBlank()) {
            return ValidationResult.fail("1", "Error: Missing type (expected analyze|suggest)");
        }
        if (!"analyze".equals(type) && !"suggest".equals(type)) {
            return ValidationResult.fail("1", "Error: Invalid type '" + type + "' (expected analyze|suggest)");
        }
        String prompt = readString(input, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return ValidationResult.fail("2", "Error: Missing prompt");
        }
        if ("analyze".equals(type)) {
            String contentId = readString(input, "contentId");
            String path = readString(input, "path");
            boolean hasContentId = contentId != null && !contentId.isBlank();
            boolean hasPath = path != null && !path.isBlank();
            // [v2 source 二选一] contentId=附件 / path=文件系统：缺源 → errorCode 3（旧文案子串兼容）；互斥 → 5
            if (!hasContentId && !hasPath) {
                return ValidationResult.fail("3", "Error: type=analyze requires contentId or path");
            }
            if (hasContentId && hasPath) {
                return ValidationResult.fail("5", "Error: contentId and path are mutually exclusive, pick one");
            }
            if (hasContentId) {
                try {
                    Long.parseLong(contentId.trim());
                } catch (NumberFormatException e) {
                    return ValidationResult.fail("4", "Error: contentId must be a numeric id, got: " + contentId);
                }
            }
            String contentType = readString(input, "contentType");
            if (contentType != null && !contentType.isBlank()
                    && !"image".equals(contentType) && !"pdf".equals(contentType)) {
                return ValidationResult.fail("6", "Error: contentType must be image|pdf, got: " + contentType);
            }
            JsonNode pagesNode = input == null ? null : input.get("pages");
            if (pagesNode != null && !pagesNode.isNull() && !pagesNode.isMissingNode()) {
                if (!pagesNode.isArray()) {
                    return ValidationResult.fail("7", "Error: pages must be an array of page numbers");
                }
                if (pagesNode.isEmpty()) {
                    return ValidationResult.fail("8", "Error: pages must not be empty");
                }
                if (!"pdf".equals(contentType)) {
                    return ValidationResult.fail("9", "Error: pages only applies to contentType=pdf");
                }
                if (pagesNode.size() > PdfSupport.PDF_MAX_PAGES_PER_READ) {
                    return ValidationResult.fail("10", "Error: pages exceeds " + PdfSupport.PDF_MAX_PAGES_PER_READ
                        + " pages per request");
                }
                for (JsonNode p : pagesNode) {
                    if (!p.isInt() || p.asInt() < 1) {
                        return ValidationResult.fail("8",
                            "Error: pages must contain positive integer page numbers (1-based)");
                    }
                }
            }
        }
        return ValidationResult.pass();
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String type = readString(input, "type");
        String prompt = readString(input, "prompt");
        String contentIdStr = readString(input, "contentId");

        // ── 1. 输入防御（execute 内二次校验，validateInput 可能不在所有派发路径被调用）──
        if (type == null || type.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: type");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: prompt");
        }
        if (!"analyze".equals(type) && !"suggest".equals(type)) {
            log.warn("VisionAnalyzeTool 非法 type: '{}'（期望 analyze|suggest）", type);
            return ToolResult.error(call.id(), "invalid type: " + type + " (expected analyze|suggest)");
        }

        String sessionId = resolveSessionId(ctx);
        if (log.isDebugEnabled()) {
            log.debug("VisionAnalyzeTool 惰性执行触发: type={} prompt='{}' contentId='{}' session={}（代理视觉模型，返回纯文本不给主模型 base64）",
                    type, truncate(prompt), contentIdStr == null ? "" : contentIdStr, sessionId);
        }

        // ── 2. analyze 源解析（v2：contentId/path 二选一 + contentType 分流 image|pdf）──
        //    只解析源信息、不读二进制 —— PDF 页渲染推迟到 5 步（provider 校验通过后，免白渲染）
        String base64 = null;
        String mediaType = null;
        long contentId = -1;
        boolean isPdf = false;
        String pdfFilePath = null;
        List<Integer> pages = null;
        if ("analyze".equals(type)) {
            String path = readString(input, "path");
            String contentType = readString(input, "contentType");
            boolean hasPath = path != null && !path.isBlank();
            boolean hasContentId = contentIdStr != null && !contentIdStr.isBlank();
            // [v2 source 二选一] contentId=附件 / path=文件系统（互斥）；缺源 → 同 validateInput errorCode 3 语义
            if (!hasContentId && !hasPath) {
                return ToolResult.error(call.id(), "type=analyze requires contentId or path");
            }
            if (hasContentId && hasPath) {
                return ToolResult.error(call.id(), "contentId and path are mutually exclusive, pick one");
            }
            if (hasContentId) {
                try {
                    contentId = Long.parseLong(contentIdStr.trim());
                } catch (NumberFormatException e) {
                    log.warn("VisionAnalyzeTool contentId 非法: '{}' 原因={}", contentIdStr, e.getMessage());
                    return ToolResult.error(call.id(), "invalid contentId: " + contentIdStr);
                }
            }
            // contentType 缺省推断：path 按扩展名（.pdf）；contentId 按附件 mediaType（application/pdf）
            if (contentType != null && !contentType.isBlank()) {
                isPdf = "pdf".equals(contentType);
            } else if (hasPath) {
                isPdf = looksLikePdf(path);
            } else {
                isPdf = attachmentIsPdf(contentId);
            }
            pages = readPages(input);
            if (isPdf) {
                // [v2 pdf 源] path 直读 / contentId → 附件表 getPath（零拷贝引用磁盘 PDF）。仅解析路径，
                //    渲染在 5 步（resolvePdfSourceFile 含 exists 校验；null → fail-loud 提示走 path 重传）
                pdfFilePath = resolvePdfSourceFile(path, contentId, hasContentId, ctx);
                if (pdfFilePath == null) {
                    return ToolResult.error(call.id(), hasContentId
                        ? "无法解析 PDF 附件路径: contentId=" + contentId
                            + "（附件表无 application/pdf 记录；历史存量 contentId 不支持，请改传 path 重试）"
                        : "path 文件不存在或不可读: " + path);
                }
                if (log.isInfoEnabled()) {
                    log.info("VisionAnalyzeTool PDF 源解析成功: source={} path={} pages={} session={}",
                        hasContentId ? "contentId=" + contentId : "path", pdfFilePath, pages, sessionId);
                }
            } else {
                // image 单图：contentId（附件表 image/* → image-cache 回退）或 path 读盘
                String sourcePath = null;
                Base64Content content = null;
                if (hasContentId) {
                    // [附件双模式] 附件表（attachments 统一 contentId 中心：path/upload 大图）优先 →
                    //   附件表 path 读盘 base64；附件表无记录（历史 image-cache contentId / 200 FIFO 逐出 /
                    //   重启后内存索引丢失）→ 回退 imageAttachmentStore.getBase64OrDisk（内存命中 → 磁盘兜底）。
                    //   [id 空间防撞] 附件表命中须 mediaType=image/*：防 image-cache 空间 id 与附件表
                    //   非图片行同数字撞号张冠李戴（对齐发送侧 resolveContentIdInTable "image/" 前缀校验）。
                    if (attachmentService != null) {
                        AttachmentRecord rec = attachmentService.getContent(contentId);
                        String recMt = rec == null ? null : rec.getMediaType();
                        if (rec != null && rec.getPath() != null && !rec.getPath().isBlank()
                                && recMt != null && recMt.toLowerCase().startsWith("image/")) {
                            sourcePath = rec.getPath();
                            content = readAttachmentTableContent(contentId, sourcePath);
                        }
                    }
                    if (content == null) {
                        content = imageAttachmentStore == null
                                ? null : imageAttachmentStore.getBase64OrDisk(sessionId, contentId);
                    }
                } else {
                    // [v2 path 图] 文件系统读盘（相对会话 cwd 解析）
                    Path file = resolvePath(path, ctx);
                    if (file == null) {
                        return ToolResult.error(call.id(), "path 文件不存在: " + path);
                    }
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        content = new Base64Content(resolveImageMediaType(path), Base64.getEncoder().encodeToString(bytes));
                    } catch (Exception e) {
                        log.warn("VisionAnalyzeTool path 图片读取失败: path={} 原因={}", path, e.getMessage());
                        return ToolResult.error(call.id(), "读取 path 图片失败: " + path + " (" + e.getMessage() + ")");
                    }
                }
                if (content == null) {
                    log.warn("VisionAnalyzeTool 附件缓存未命中: contentId={} session={}", contentId, sessionId);
                    return ToolResult.error(call.id(), "attachment cache miss for contentId=" + contentId);
                }
                if (isBase64Oversize(content.base64())) {
                    String hint = sourcePath == null || sourcePath.isBlank() ? "" : "，本地路径=" + sourcePath;
                    log.warn("VisionAnalyzeTool 图片超 5MB 无法发给视觉模型: contentId={} base64Len={} mediaType={} session={}{}",
                            contentId, content.base64().length(), content.mediaType(), sessionId, hint);
                    return ToolResult.error(call.id(),
                            "图片超 5MB 无法发送视觉模型，请改用本地路径引用: contentId=" + contentId + hint);
                }
                base64 = content.base64();
                mediaType = content.mediaType();
                if (log.isDebugEnabled()) {
                    log.debug("VisionAnalyzeTool 读取图片内容成功: contentId={} mediaType={} base64Len={} session={} 来源={}",
                            contentId, mediaType, base64.length(), sessionId,
                            sourcePath == null ? "image-cache/path" : "附件表 path=" + sourcePath);
                }
            }
        }

        // ── 3. 视觉模型名解析（fail-loud，不静默回落）──
        String modelName = modelConfigResolver == null ? null : modelConfigResolver.resolveMultimodalModelName();
        if (modelName == null || modelName.isBlank()) {
            log.warn("VisionAnalyzeTool 多模态档位未配置：settings.multimodalModelName 缺失/未命中 enabled model → fail-loud");
            return ToolResult.error(call.id(),
                    "视觉模型未配置：请先设置 settings.multimodalModelName（多模态档位模型）后再调用 vision_analyze");
        }

        // ── 4. ProviderConfig 解析（ModelConfigResolver.resolve 单一来源，不复制 listAll 循环）──
        ModelConfigResolver.ResolvedModel resolved;
        try {
            resolved = modelConfigResolver.resolve(modelName);
        } catch (Exception e) {
            log.warn("VisionAnalyzeTool provider 解析异常: model={} 原因={}", modelName, e.getMessage());
            resolved = null;
        }
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("VisionAnalyzeTool provider 解析失败: model={} → 回落 fail-loud（不落 mock）", modelName);
            return ToolResult.error(call.id(),
                    "视觉模型 '" + modelName + "' 无匹配的 enabled provider/apiKey，无法调用");
        }
        LlmProvider provider = llmProviderFactory == null ? null
                : llmProviderFactory.getProvider(resolved.config(), resolved.providerType());
        if (provider == null) {
            log.warn("VisionAnalyzeTool provider 工厂路由失败: model={} providerType={}", modelName, resolved.providerType());
            return ToolResult.error(call.id(), "视觉模型 provider 工厂路由失败: model=" + modelName);
        }

        // ── 5. 组装 user 消息 + chatWithOptions（同步 + fail-loud）──
        String resultText;
        try {
            // 8-arg ChatRequestOptions: history/tools/outputFormat/thinkingConfig/temperature/querySource/abort/maxTokens
            if ("analyze".equals(type) && isPdf) {
                // [v2 pdf] 懒渲染 pages → 页图 image blocks → 视觉模型（单条 user 消息 text+N image）
                resultText = chatWithPdfPages(provider, resolved, modelName, prompt, pdfFilePath, pages, contentId);
            } else if ("analyze".equals(type)) {
                // image 单图：contentBlocks=[text, image]（text 块在前，对齐 CC prompt 数组 attachments.ts:1065-1071）
                List<JsonNode> blocks = new ArrayList<>(2);
                blocks.add(textBlock(prompt));
                blocks.add(com.nexusai.application.agent.LlmAgentLoop.imageContentBlock(mediaType, base64));
                ChatMessageDto user = com.nexusai.application.agent.LlmAgentLoop.toMessage(
                        Role.user, prompt, null, null, blocks, List.of(), true);
                LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                        List.of(user), null, null, null, null, "vision_analyze", null, null);
                // ⚠️ userMessage 必须传 null：图片+prompt 已在 history 的 contentBlocks 里；
                //    再传 userMessage 会导致连续两条 user 消息（AnthropicSdkProvider:1013-1017）
                resultText = provider.chatWithOptions(resolved.config(), modelName, null, null, options);
            } else {
                LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                        List.of(), null, null, null, null, "vision_analyze", null, null);
                resultText = provider.chatWithOptions(resolved.config(), modelName, null, prompt, options);
            }
        } catch (Exception e) {
            log.error("VisionAnalyzeTool 视觉模型调用失败: model={} type={} contentId={} isPdf={} 原因={}",
                    modelName, type, contentId, isPdf, e.getMessage());
            return ToolResult.error(call.id(), "vision model call failed: " + e.getMessage());
        }

        // ── 6. 组装纯文本 ToolResult（占位符代替 base64，绝不给主模型 base64）──
        String data = buildResultText(type, contentId, resultText, isPdf ? pdfFilePath : null, pages);
        if (log.isDebugEnabled()) {
            log.debug("VisionAnalyzeTool 执行成功: type={} contentId={} isPdf={} model={} resultLen={}（返回纯文本，图片以占位符表示）",
                    type, contentId, isPdf, modelName, resultText == null ? 0 : resultText.length());
        }
        return ToolResult.success(call.id(), data);
    }

    /**
     * [v2 pdf] PDF 懒渲染 pages → 页图 image blocks → 视觉模型（多图单条 user 消息）→ 纯文本返回。
     *
     * <p><b>懒渲染</b>：不预注册页图（替代 registerPageImagesToStore），调用时经 PdfSupport 100 DPI
     * 渲染 <code>pages</code> 指定页到临时目录，只挑所需页转 base64（每页 ≤5MB gate）。渲染临时目录
     * finally 递归清理。页号越界 / 超 20 页跨度 / 渲染失败 → 抛 {@link IllegalStateException}（execute 外层
     * catch → fail-loud ToolResult.error）。
     *
     * @param provider     视觉模型 provider
     * @param resolved     已解析 ProviderConfig（resolved.config() 供 chatWithOptions）
     * @param modelName    多模态档位模型名（日志）
     * @param prompt       主模型问题（text 块置前）
     * @param pdfFilePath  PDF 本地绝对路径（resolvePdfSourceFile 已 exists 校验）
     * @param pages        显式页号（1-based；null/空 → ≤10 页全渲染，>10 报错要求 pages）
     * @param contentId    源 contentId（日志；path 源为 -1）
     * @return 视觉模型纯文本响应
     */
    private String chatWithPdfPages(LlmProvider provider, ModelConfigResolver.ResolvedModel resolved,
                                    String modelName, String prompt, String pdfFilePath,
                                    List<Integer> pages, long contentId) throws Exception {
        Path file = Path.of(pdfFilePath);
        Integer pageCount = PdfSupport.getPDFPageCount(file);
        if (pageCount == null || pageCount <= 0) {
            throw new IllegalStateException("无法读取 PDF 页数: " + pdfFilePath);
        }
        List<Integer> selected = (pages != null && !pages.isEmpty()) ? pages : defaultPdfPages(pageCount);
        for (Integer p : selected) {
            if (p == null || p < 1 || p > pageCount) {
                throw new IllegalStateException("PDF 页号越界: page=" + p + "（共 " + pageCount + " 页）");
            }
        }
        int min = selected.get(0);
        int max = selected.get(selected.size() - 1);
        if ((long) max - min + 1 > PdfSupport.PDF_MAX_PAGES_PER_READ) {
            throw new IllegalStateException("PDF 一次最多渲染 " + PdfSupport.PDF_MAX_PAGES_PER_READ
                + " 页，当前跨度 " + (max - min + 1) + " 页，请缩小 pages 范围");
        }
        Path tmpDir = Files.createTempDirectory("vision-analyze-pdf-");
        try {
            PdfSupport.PdfExtractResult extract = PdfSupport.extractPDFPages(file, tmpDir, min, max);
            if (!extract.success()) {
                throw new IllegalStateException("PDF 页渲染失败: " + extract.error().message());
            }
            List<JsonNode> imgBlocks = new ArrayList<>();
            for (Integer p : selected) {
                Path jpg = tmpDir.resolve(String.format("page-%02d.jpg", p));
                if (!Files.exists(jpg)) {
                    throw new IllegalStateException("PDF 页图缺失: page-" + p + ".jpg");
                }
                byte[] bytes = Files.readAllBytes(jpg);
                String b64 = Base64.getEncoder().encodeToString(bytes);
                if (isBase64Oversize(b64)) {
                    throw new IllegalStateException("PDF 第 " + p + " 页图超 5MB 无法发送视觉模型"
                        + "（页面过大，建议减小 pages 范围）");
                }
                imgBlocks.add(com.nexusai.application.agent.LlmAgentLoop.imageContentBlock(
                    PDF_PAGE_IMAGE_MEDIA_TYPE, b64));
            }
            if (imgBlocks.isEmpty()) {
                throw new IllegalStateException("PDF 页图产出为空（渲染失败）");
            }
            List<JsonNode> blocks = new ArrayList<>(imgBlocks.size() + 1);
            blocks.add(textBlock(prompt));
            blocks.addAll(imgBlocks);
            ChatMessageDto user = com.nexusai.application.agent.LlmAgentLoop.toMessage(
                Role.user, prompt, null, null, blocks, List.of(), true);
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                List.of(user), null, null, null, null, "vision_analyze", null, null);
            if (log.isDebugEnabled()) {
                log.debug("VisionAnalyzeTool PDF 页图已组装: path={} pages={} 页图={}（送视觉模型）",
                    pdfFilePath, selected, imgBlocks.size());
            }
            return provider.chatWithOptions(resolved.config(), modelName, null, null, options);
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    /**
     * pages 缺省策略 · 对齐 CC FileReadTool PDF_AT_MENTION_INLINE_THRESHOLD=10：
     * ≤10 页全渲染；>10 页报错要求显式 pages（fail-loud，防整份大 PDF 全渲染浪费）。
     */
    private static List<Integer> defaultPdfPages(int pageCount) throws Exception {
        if (pageCount > PdfSupport.PDF_AT_MENTION_INLINE_THRESHOLD) {
            throw new IllegalStateException("PDF 共 " + pageCount + " 页（>"
                + PdfSupport.PDF_AT_MENTION_INLINE_THRESHOLD + "），请传 pages=[页号数组] 分段分析，一次 ≤"
                + PdfSupport.PDF_MAX_PAGES_PER_READ + " 页");
        }
        List<Integer> all = new ArrayList<>(pageCount);
        for (int i = 1; i <= pageCount; i++) {
            all.add(i);
        }
        return all;
    }

    /**
     * [v2] PDF 源 → 本地绝对路径（exists 校验）。contentId 走附件表 {@link AttachmentService#getPath}
     * （application/pdf 附件，零拷贝引用磁盘）；path 走 {@link #resolvePath}（相对 cwd）。
     * 解析失败 → null（调用方 fail-loud；历史存量 store contentId 无附件表记录 → 提示改传 path）。
     */
    private String resolvePdfSourceFile(String path, long contentId, boolean hasContentId, ToolUseContext ctx) {
        if (!hasContentId) {
            Path f = resolvePath(path, ctx);
            return f == null ? null : f.toString();
        }
        if (attachmentService == null) {
            return null;
        }
        try {
            String p = attachmentService.getPath(contentId);
            if (p == null || p.isBlank()) {
                return null;
            }
            Path f = Path.of(p);
            return Files.exists(f) ? f.toString() : null;
        } catch (Exception e) {
            log.warn("VisionAnalyzeTool PDF contentId 路径解析失败: contentId={} 原因={}", contentId, e.getMessage());
            return null;
        }
    }

    /** [v2] 文件系统路径解析：绝对直读；相对按会话 cwd（ctx.effectiveCwd）解析；不存在 → null。 */
    private static Path resolvePath(String path, ToolUseContext ctx) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path p = Path.of(path.trim());
        if (!p.isAbsolute()) {
            p = (ctx != null && ctx.effectiveCwd() != null ? ctx.effectiveCwd() : Path.of("").toAbsolutePath())
                .resolve(p);
        }
        p = p.normalize();
        return Files.exists(p) ? p : null;
    }

    /** 扩展名推断 PDF（contentType 缺省 + path 源时）。 */
    private static boolean looksLikePdf(String path) {
        if (path == null) {
            return false;
        }
        return path.toLowerCase().endsWith(".pdf");
    }

    /** contentId 附件 mediaType 判 PDF（contentType 缺省 + contentId 源时）。 */
    private boolean attachmentIsPdf(long contentId) {
        if (attachmentService == null) {
            return false;
        }
        try {
            AttachmentRecord rec = attachmentService.getContent(contentId);
            return rec != null && rec.getMediaType() != null
                && PdfSupport.PDF_MEDIA_TYPE.equalsIgnoreCase(rec.getMediaType());
        } catch (Exception e) {
            return false;
        }
    }

    /** [v2] path 图 mediaType：扩展名映射兜底 image/png（无附件表，独立于 resolveMediaType）。 */
    private static String resolveImageMediaType(String path) {
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String mapped = EXTENSION_TO_MEDIA_TYPE.get(path.substring(dot + 1).toLowerCase());
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return "image/png";
    }

    /** [v2] pages 数组读取：input.pages int[] → List&lt;Integer&gt;（保序）；缺省 → null。 */
    private static List<Integer> readPages(JsonNode input) {
        if (input == null || !input.has("pages") || input.get("pages").isNull()
                || !input.get("pages").isArray()) {
            return null;
        }
        JsonNode arr = input.get("pages");
        List<Integer> list = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n.isInt()) {
                list.add(n.asInt());
            }
        }
        return list.isEmpty() ? null : list;
    }

    /** 递归删除临时目录（渲染页图清理）。 */
    private static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // 尽力清理，失败不阻断主流程
                }
            });
        } catch (java.io.IOException e) {
            log.warn("VisionAnalyzeTool 清理 PDF 临时目录失败: dir={} 原因={}", dir, e.getMessage());
        }
    }

    /** PDF 页图 media_type（PdfSupport 100 DPI JPEG 渲染产出）。 */
    private static final String PDF_PAGE_IMAGE_MEDIA_TYPE = "image/jpeg";

    /**
     * 纯文本结果组装 · image 以 {@code [image:{contentId}]}、pdf 以 {@code [pdf:path pages=[…]]}
     * 占位符表示，绝不携带 base64。
     */
    private static String buildResultText(String type, long contentId, String resultText,
                                          String pdfFilePath, List<Integer> pages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[vision_analyze 结果] type=").append(type);
        if ("analyze".equals(type)) {
            if (pdfFilePath != null) {
                sb.append(", PDF 占位符=[pdf:").append(pdfFilePath)
                  .append(" pages=").append(pages == null ? "全" : pages.toString()).append(']');
            } else {
                sb.append(", contentId=").append(contentId)
                  .append(", 图片占位符=[image:").append(contentId).append(']');
            }
        }
        sb.append('\n').append(resultText == null ? "" : resultText);
        return sb.toString();
    }

    /** {@code {type:'text', text}} JsonNode · appendSdkContentBlock text 分支消费。 */
    private static ObjectNode textBlock(String text) {
        ObjectNode block = JsonNodeFactory.instance.objectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    // ── [附件双模式] 附件表读盘 + mediaType 解析 + 5MB 门控 ──

    /**
     * 附件表 path → 读盘 base64 · mediaType 经 {@link #resolveMediaType}（附件记录优先）。
     * 读失败 → null（调用方回退 image-cache，不 fail-loud 中断——两通道共享一条降级链）。
     */
    private Base64Content readAttachmentTableContent(long contentId, String path) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            return new Base64Content(resolveMediaType(contentId, path),
                    Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            log.warn("VisionAnalyzeTool 附件表读盘失败: contentId={} path={} 原因={}", contentId, path, e.getMessage());
            return null;
        }
    }

    /** mediaType 解析：附件表记录 media_type 优先 → 文件名扩展名映射兜底 → image/png。 */
    private String resolveMediaType(long contentId, String path) {
        if (attachmentService != null) {
            try {
                AttachmentRecord rec = attachmentService.getContent(contentId);
                if (rec != null && rec.getMediaType() != null && !rec.getMediaType().isBlank()) {
                    return rec.getMediaType();
                }
            } catch (Exception ignored) {
                // 记录查询失败 → 扩展名兜底
            }
        }
        String name = path == null ? "" : path;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String mapped = EXTENSION_TO_MEDIA_TYPE.get(name.substring(dot + 1).toLowerCase());
            if (mapped != null) {
                return mapped;
            }
        }
        return "image/png";
    }

    /** base64 估算原始字节 &gt;5MB（对齐 CC apiLimits.ts:19 {@code API_IMAGE_MAX_BASE64_SIZE}；attachmentService 读盘路径 store 可 null 的独立判定）。 */
    private static boolean isBase64Oversize(String base64) {
        if (base64 == null) {
            return false;
        }
        return (long) base64.length() * 3 / 4 > MediaLimitConstants.API_IMAGE_MAX_BASE64_SIZE;
    }

    /** 常见图片扩展名 → MIME 映射（附件表 mediaType 缺失时的兜底源）。 */
    private static final Map<String, String> EXTENSION_TO_MEDIA_TYPE = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "svg", "image/svg+xml",
            "bmp", "image/bmp",
            "avif", "image/avif"
    );

    /**
     * 会话解析 · 三源：① ToolUseContext.sessionId（当前 turn 直接源）；②
     * {@link com.nexusai.common.RequestContext#sessionId()}（MDC）；③ null →
     * {@link ImageAttachmentStore} 内部 'unknown' 兜底（cron/后台无 MDC 场景）。
     */
    private static String resolveSessionId(ToolUseContext ctx) {
        if (ctx != null && ctx.sessionId() != null) {
            return ctx.sessionId();
        }
        String fromMdc = com.nexusai.common.RequestContext.sessionId();
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return null;
    }

    private static String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
