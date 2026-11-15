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
import org.springframework.beans.factory.annotation.Autowired;

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
 * （FileReadTool/prompt.ts:40 "NexusAI is a multimodal LLM"），无「多模态工具」路由。
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
 * <p><b>懒加载（shouldDefer=true）</b>：本工具 schema 不占初始 prompt（defer_loading），是否真懒由
 * 装配层豁免决定（R12 口径详见 {@link #shouldDefer}：provider==anthropic 且模型非 haiku 且多模态才
 * 保留懒、需时经 ToolSearch 激活，其余情况由装配层剔除 → schema 直发）—— 对齐 CC Tool.ts:442 +
 * AbstractTaskTool 一族模式。WHY：能走 Read 直给通道的多模态主模型用不上本工具（仅 PDF 超预算/分段时
 * 补充），始终进 schema 是 token 浪费。
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

    /**
     * 多图单次上限 · contentIds + paths 合计不得超过此数。
     *
     * <p>取值 8：图片 token 成本远高于 PDF 页（受 detail 档位放大），且视觉模型对 8 张以上
     * 容易出现注意力衰减。超过上限时 fail-loud 让模型分批调用，而不是静默截断。
     */
    static final int MAX_IMAGES = 8;

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
     * [usage-gateway] 旁路 LLM 单发客户端 · 视觉模型调用收口到请求网关并自动记账。
     *
     * <p>本类非 {@code @Component}，经 {@code ToolRegistrationConfig} 的 {@code @Bean} 工厂构造；
     * Spring 对 {@code @Bean} 返回对象仍执行 {@code AutowiredAnnotationBeanPostProcessor} →
     * 字段注入有效（{@code required=false}：孤立 new 场景缺省 → 调用点需容忍，但生产恒注入）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.cost.LlmSideCallClient sideCallClient;

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
        return "代理视觉模型分析：把图片/PDF 页与指令发给独立视觉模型"
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
     * 省 schema token）。是否<b>实际懒</b>由装配层豁免决定（LlmAgentLoop
     * {@code exemptVisionAnalyzeDeferForTextModel}，见该处 javadoc）。
     *
     * <p><b>[R12 2026-09-12 判据单点化 · 准确口径]</b>：豁免 = {@code ToolSearchService.toolReferenceUsable(
     * providerType, modelName)}（providerType 由调用方传 {@code tuc.effectiveProviderType()}）<b>且</b>
     * supportsImage 多模态 —— 即
     * 「<b>provider == anthropic 且模型名不命中 haiku 负向名单</b>（{@code DEFAULT_UNSUPPORTED_MODEL_PATTERNS
     * = ['haiku']}，haiku 无 tool_reference）」∧ 多模态。四格：
     * <ul>
     *   <li><b>anthropic + 多模态 + 非 haiku</b> → 能用 tool_reference，且能走 Read 直给通道，
     *       vision_analyze 仅 PDF &gt;预算/分段补充 → <b>保留懒</b>（需时经 ToolSearch/discovered 激活）；</li>
     *   <li><b>anthropic + haiku（即便 DB 标多模态）</b> → 无 tool_reference ⇒ 懒加载不可达（模型永远
     *       搜不出本工具）→ 装配层从 deferred 剔除 → <b>schema 直发</b>；</li>
     *   <li><b>anthropic + 非多模态文本模型</b> → 无 Read 直给通道，本工具是唯一视觉通道 →
     *       <b>schema 直发</b>；</li>
     *   <li><b>非 anthropic</b>（deepseek openai-completions，<b>含 vision-exp 多模态</b> / openai_sdk）
     *       → 无 tool_reference ⇒ <b>schema 直发</b>（不赌模型会 ToolSearch，历史 Read 图死循环 /
     *       fork 视觉子代理递归诱因之一）。</li>
     * </ul>
     * （旧口径「装配层按主模型能力豁免」指的是 provider-only 判据
     * {@code ContextUsageCalculator.isAnthropic}，R12 已收敛为上述 {@code toolReferenceUsable} 单点。）
     *
     * <p><b>Task#15（子代理拿不到）根治</b>：子代理共享主循环 queryLoop 装配（LlmAgentLoop:5360 注释），
     * 装配层豁免一处即覆盖主/子代理 —— fresh 子代理若不满足「toolReferenceUsable ∧ 多模态」（即非
     * anthropic、或 anthropic×haiku、或非多模态文本模型），vision_analyze 不再因 defer 排除，
     * schema 直发可见。
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
        contentType.put("description", "源格式（可选）：image=图片（单张或多张）；"
                + "pdf=PDF（**本身支持一次分析多页**，不需要逐页调用，推荐传 pages 一起看）。"
                + "缺省按 path 扩展名 / contentId 附件 mediaType 推断；suggest 忽略");

        ObjectNode contentId = props.putObject("contentId");
        contentId.put("type", "string");
        contentId.put("description", "单张图片：附件表 contentId（整数 id 字符串形式；附件/粘贴媒体源）。"
                + "type=analyze 时与 path、contentIds、paths 互斥。多张图请用 contentIds");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "单张图片：文件系统路径（Read 引导场景；图片或 PDF）。相对路径按会话 cwd 解析。"
                + "type=analyze 时与 contentId、contentIds、paths 互斥。多张图请用 paths");

        ObjectNode contentIds = props.putObject("contentIds");
        contentIds.put("type", "array");
        contentIds.put("description", "多张图片：附件表 contentId 数组（如 [\"12\",\"15\"]），按数组顺序送入模型，"
                + "模型可跨图对照。**只接受图片（image/*）**——PDF 不在此列，PDF 请用 "
                + "contentId + contentType=pdf + pages 一次分析多页。"
                + "与 contentId 互斥；可与 paths 同时使用（contentIds 在前）。单次最多 " + MAX_IMAGES + " 张");
        // [400 修复·同 pages] items 必须是 schema 对象 {"type":"string"}，不能是字符串数组。
        contentIds.putObject("items").put("type", "string");

        ObjectNode paths = props.putObject("paths");
        paths.put("type", "array");
        paths.put("description", "多张图片：文件系统路径数组，按数组顺序送入模型。**只接受图片文件**"
                + "（png/jpg/jpeg/gif/webp）——PDF 不在此列，PDF 请用 path + contentType=pdf + pages "
                + "一次分析多页。与 path 互斥；可与 contentIds 同时使用（contentIds 在前）。"
                + "单次最多 " + MAX_IMAGES + " 张");
        paths.putObject("items").put("type", "string");

        ObjectNode pages = props.putObject("pages");
        pages.put("type", "array");
        pages.put("description", "仅 contentType=pdf。PDF **默认就支持多页**，不需要逐页调用："
                + "不传 pages 时自动渲染全部页（≤10 页）；**推荐**显式传 pages 一次看多页，如 [1,2,3,4]——"
                + "多页一起分析更快更准，模型可跨页对照并合并同一份表格/合同的内容；"
                + "逐页调用会重复消耗上下文且丢失跨页关系。一次最多 " + PdfSupport.PDF_MAX_PAGES_PER_READ + " 页");
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
            // [multi-image] 数组源：contentIds（附件 id）+ paths（磁盘路径），与单值组互斥。
            List<String> contentIds = readStringArray(input, "contentIds");
            List<String> paths = readStringArray(input, "paths");
            if (contentIds == null || paths == null) {
                return ValidationResult.fail("12",
                    "Error: contentIds/paths must be arrays of non-empty strings");
            }
            boolean hasArraySources = !contentIds.isEmpty() || !paths.isEmpty();
            boolean hasSingleSource = hasContentId || hasPath;
            // [v2 source 二选一] contentId=附件 / path=文件系统：缺源 → errorCode 3（旧文案子串兼容）；互斥 → 5
            if (hasArraySources && hasSingleSource) {
                return ValidationResult.fail("11",
                    "Error: use either contentId/path (single) or contentIds/paths (multiple), not both");
            }
            if (!hasArraySources && !hasSingleSource) {
                return ValidationResult.fail("3", "Error: type=analyze requires contentId or path");
            }
            if (!hasArraySources && hasContentId && hasPath) {
                return ValidationResult.fail("5", "Error: contentId and path are mutually exclusive, pick one");
            }
            if (hasContentId) {
                try {
                    Long.parseLong(contentId.trim());
                } catch (NumberFormatException e) {
                    return ValidationResult.fail("4", "Error: contentId must be a numeric id, got: " + contentId);
                }
            }
            if (hasArraySources) {
                int total = contentIds.size() + paths.size();
                if (total > MAX_IMAGES) {
                    return ValidationResult.fail("13", "Error: too many images: " + total
                        + " (max " + MAX_IMAGES + " per call; split into batches)");
                }
                for (String id : contentIds) {
                    try {
                        Long.parseLong(id.trim());
                    } catch (NumberFormatException e) {
                        return ValidationResult.fail("12",
                            "Error: contentIds elements must be numeric ids, got: " + id);
                    }
                }
            }
            String contentType = readString(input, "contentType");
            if (contentType != null && !contentType.isBlank()
                    && !"image".equals(contentType) && !"pdf".equals(contentType)) {
                return ValidationResult.fail("6", "Error: contentType must be image|pdf, got: " + contentType);
            }
            // [multi-image] 多图只支持图片；PDF 走 contentId/path + contentType=pdf + pages 的多页通道
            if (hasArraySources && "pdf".equals(contentType)) {
                return ValidationResult.fail("14",
                    "Error: contentIds/paths only accept images; for PDF use contentId/path + contentType=pdf + pages");
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

        // ── 2. analyze 源解析（v2：contentId/path 单值 或 contentIds/paths 多图 + contentType 分流）──
        //    只解析源信息、不读二进制 —— PDF 页渲染推迟到 5 步（provider 校验通过后，免白渲染）
        long contentId = -1;
        boolean isPdf = false;
        String pdfFilePath = null;
        List<Integer> pages = null;
        // [multi-image] 待送视觉模型的图片（单图=长度1；多图=按 contentIds → paths 顺序）
        List<Base64Content> imageContents = new ArrayList<>();
        // [multi-image] 多图模式下的源值列表（内容 id 或磁盘路径，与 imageContents 一一对应）；
        //   单图模式保持 null → 结果占位符沿用既有 contentId 文案
        List<String> imageRefs = null;
        if ("analyze".equals(type)) {
            String path = readString(input, "path");
            String contentType = readString(input, "contentType");
            boolean hasPath = path != null && !path.isBlank();
            boolean hasContentId = contentIdStr != null && !contentIdStr.isBlank();
            List<String> contentIds = readStringArray(input, "contentIds");
            List<String> paths = readStringArray(input, "paths");
            if (contentIds == null || paths == null) {
                return ToolResult.error(call.id(), "contentIds/paths must be arrays of non-empty strings");
            }
            int arrayCount = contentIds.size() + paths.size();
            if (arrayCount > 0) {
                // [multi-image] 多图通道：与单值源互斥；仅图片（PDF 走 contentId/path + pages 多页通道）
                if (hasContentId || hasPath) {
                    return ToolResult.error(call.id(),
                        "use either contentId/path (single) or contentIds/paths (multiple), not both");
                }
                if (arrayCount > MAX_IMAGES) {
                    return ToolResult.error(call.id(), "too many images: " + arrayCount
                        + " (max " + MAX_IMAGES + " per call; split into batches)");
                }
                // 多图只支持图片；PDF 走 contentId/path + contentType=pdf + pages 的多页通道
                if ("pdf".equals(contentType)) {
                    return ToolResult.error(call.id(),
                        "contentIds/paths only accept images; for PDF use contentId/path + contentType=pdf + pages");
                }
                try {
                    imageRefs = new ArrayList<>(arrayCount);
                    imageRefs.addAll(contentIds);
                    imageRefs.addAll(paths);
                    for (String id : contentIds) {
                        imageContents.add(resolveImageContent(new ImageSource(true, id), sessionId, ctx));
                    }
                    for (String p : paths) {
                        imageContents.add(resolveImageContent(new ImageSource(false, p), sessionId, ctx));
                    }
                } catch (IllegalStateException e) {
                    return ToolResult.error(call.id(), e.getMessage());
                }
                if (log.isInfoEnabled()) {
                    log.info("VisionAnalyzeTool 多图解析成功: count={} contentIds={} paths={} session={}",
                        imageContents.size(), contentIds.size(), paths.size(), sessionId);
                }
            } else {
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
                    // 图片（单图）：contentId（附件表 image/* → image-cache 回退）或 path 读盘
                    try {
                        imageContents.add(resolveImageContent(
                            new ImageSource(hasContentId, hasContentId ? contentIdStr : path), sessionId, ctx));
                    } catch (IllegalStateException e) {
                        return ToolResult.error(call.id(), e.getMessage());
                    }
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

        // ── 5. 组装 user 消息 + 旁路调用（同步 + fail-loud）──
        String resultText;
        try {
            // 8-arg ChatRequestOptions: history/tools/outputFormat/thinkingConfig/temperature/querySource/abort/maxTokens
            if ("analyze".equals(type) && isPdf) {
                // [v2 pdf] 懒渲染 pages → 页图 image blocks → 视觉模型（单条 user 消息 text+N image）
                resultText = chatWithPdfPages(resolved, modelName, prompt, pdfFilePath, pages, contentId, ctx);
            } else if ("analyze".equals(type)) {
                // 图片（单图=1 张 / 多图=N 张）：contentBlocks=[text, image...]
                //   （text 块在前，对齐 CC prompt 数组 attachments.ts:1065-1071）
                if (imageContents.isEmpty()) {
                    return ToolResult.error(call.id(), "no image resolved for analyze");
                }
                List<JsonNode> blocks = new ArrayList<>(imageContents.size() + 1);
                blocks.add(textBlock(prompt));
                for (Base64Content image : imageContents) {
                    blocks.add(com.nexusai.application.agent.LlmAgentLoop.imageContentBlock(
                        image.mediaType(), image.base64()));
                }
                ChatMessageDto user = com.nexusai.application.agent.LlmAgentLoop.toMessage(
                        Role.user, prompt, null, null, blocks, List.of(), true);
                LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                        List.of(user), null, null, null, null, "vision_analyze", null, null,
                        // [S1-T7] agent 归因上下文 = **工具形参 ctx 的显式字段**（两套载体收口
                        //   到 TUC 单一来源；工具线程上 ThreadLocal 不可达）。
                        ctx != null ? ctx.agentContext() : null);
                // ⚠️ userMessage 必须传 null：图片+prompt 已在 history 的 contentBlocks 里；
                //    再传 userMessage 会导致连续两条 user 消息（AnthropicSdkProvider:1013-1017）
                // [usage-gateway] 旁路调用收口 LlmSideCallClient：归属取自工具形参 ctx
                //   （sessionId=ctx.sessionId()，agentId=ctx.agentId()，agentContext=ctx.agentContext()；
                //    ctx==null → standalone，不伪造 sessionId）。
                resultText = sideCallClient.chat(resolved.config(), modelName, null, null, options,
                        visionAnalyzeUsage(ctx));
            } else {
                LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                        List.of(), null, null, null, null, "vision_analyze", null, null,
                        ctx != null ? ctx.agentContext() : null);
                // [usage-gateway] 同上：归属取自工具形参 ctx。
                resultText = sideCallClient.chat(resolved.config(), modelName, null, prompt, options,
                        visionAnalyzeUsage(ctx));
            }
        } catch (Exception e) {
            log.error("VisionAnalyzeTool 视觉模型调用失败: model={} type={} contentId={} isPdf={} 原因={}",
                    modelName, type, contentId, isPdf, e.getMessage());
            return ToolResult.error(call.id(), "vision model call failed: " + e.getMessage());
        }

        // ── 6. 组装纯文本 ToolResult（占位符代替 base64，绝不给主模型 base64）──
        String data = buildResultText(type, contentId, resultText, isPdf ? pdfFilePath : null, pages, imageRefs);
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
     * @param resolved     已解析 ProviderConfig（resolved.config() 供旁路调用）
     * @param modelName    多模态档位模型名（日志）
     * @param prompt       主模型问题（text 块置前）
     * @param pdfFilePath  PDF 本地绝对路径（resolvePdfSourceFile 已 exists 校验）
     * @param pages        显式页号（1-based；null/空 → ≤10 页全渲染，>10 报错要求 pages）
     * @param contentId    源 contentId（日志；path 源为 -1）
     * @param ctx          [usage-gateway] 工具上下文（归属单一来源：sessionId=ctx.sessionId()、
     *                     agentId=ctx.agentId()、agentContext=ctx.agentContext()；null = 无会话上下文
     *                     → standalone 归属，不伪造 sessionId）。本方法自身无 ToolUseContext ⇒ 由调用方
     *                     execute(call, ctx) 传入。
     * @return 视觉模型纯文本响应
     */
    private String chatWithPdfPages(ModelConfigResolver.ResolvedModel resolved,
                                    String modelName, String prompt, String pdfFilePath,
                                    List<Integer> pages, long contentId, ToolUseContext ctx)
            throws Exception {
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
                List.of(user), null, null, null, null, "vision_analyze", null, null,
                // [S1-T7] agent 归因上下文 = **本方法新增形参 ctx 的显式字段**（原读 ambient 归因上下文
                //   已删）。本方法（chatWithPdfPages）自身无 ToolUseContext ⇒ 由调用方
                //   execute(call, ctx) 从 ctx.agentContext() 传入。
                ctx != null ? ctx.agentContext() : null);
            if (log.isDebugEnabled()) {
                log.debug("VisionAnalyzeTool PDF 页图已组装: path={} pages={} 页图={}（送视觉模型）",
                    pdfFilePath, selected, imgBlocks.size());
            }
            // [usage-gateway] 旁路调用收口 LlmSideCallClient：归属取自同一 ctx（sessionId/agentId/
            //   agentContext），ctx==null → standalone（不伪造 sessionId）。
            return sideCallClient.chat(resolved.config(), modelName, null, null, options,
                    visionAnalyzeUsage(ctx));
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    /**
     * [usage-gateway] 组装旁路记账归属（视觉分析调用专用）。
     *
     * <p>归属单一来源 = 工具形参 {@link ToolUseContext}：
     * <ul>
     *   <li>ctx != null → {@code inSession(sessionId=ctx.sessionId(), agentId=ctx.agentId(), ...)}
     *       + {@code withAgentContext(ctx.agentContext())}；</li>
     *   <li>ctx == null（无会话上下文，如静态/无 session 调用）→ {@code standalone}（不伪造 sessionId）。</li>
     * </ul>
     */
    private static com.nexusai.application.agent.cost.LlmSideCallClient.SideCallUsage
            visionAnalyzeUsage(ToolUseContext ctx) {
        if (ctx == null) {
            return com.nexusai.application.agent.cost.LlmSideCallClient.SideCallUsage
                    .standalone(com.nexusai.infra.llm.UsageCategory.SIDE_CALL, "vision-analyze");
        }
        return com.nexusai.application.agent.cost.LlmSideCallClient.SideCallUsage
                .inSession(ctx.sessionId(),
                        ctx.agentId() != null ? ctx.agentId().toString() : null,
                        com.nexusai.infra.llm.UsageCategory.SIDE_CALL, "vision-analyze")
                .withAgentContext(ctx.agentContext());
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
                                          String pdfFilePath, List<Integer> pages, List<String> imageRefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[vision_analyze 结果] type=").append(type);
        if ("analyze".equals(type)) {
            if (pdfFilePath != null) {
                sb.append(", PDF 占位符=[pdf:").append(pdfFilePath)
                  .append(" pages=").append(pages == null ? "全" : pages.toString()).append(']');
            } else if (imageRefs != null && !imageRefs.isEmpty()) {
                // [multi-image] 数组源：单元素且为数字 id 时沿用 [image:id] 文案，多元素用 [images:...]
                if (imageRefs.size() == 1 && imageRefs.get(0).matches("\\d+")) {
                    sb.append(", contentId=").append(imageRefs.get(0))
                      .append(", 图片占位符=[image:").append(imageRefs.get(0)).append(']');
                } else {
                    sb.append(", 图片占位符=[images:").append(String.join(",", imageRefs)).append(']');
                }
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
     * 会话解析 · 两源：① ToolUseContext.sessionId（当前 turn 直接源）；② null →
     * {@link ImageAttachmentStore} 内部 'unknown' 兜底（cron/后台无会话场景）。
     *
     * <p>[批 3c] 会话来源显式化：唯一源 = {@code ctx.sessionId()}；⛔ 已删原第二源 MDC
     * （裸 MDC）—— 该读点在 tool-exec 池线程上恒空或读到线程复用的
     * 残留会话 id（第三态）。
     */
    private static String resolveSessionId(ToolUseContext ctx) {
        if (ctx != null && ctx.sessionId() != null) {
            return ctx.sessionId();
        }
        return null;
    }

    private static String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }

    /**
     * 读取字符串数组字段（多图源）。
     *
     * @return 字段不存在 / null / missing → 空列表；字段是数组且元素均为非空字符串 → 元素列表；
     *         字段存在但不是数组，或含非字符串 / 空串元素 → {@code null}（非法，调用方转 errorCode 12）
     */
    private static List<String> readStringArray(JsonNode input, String key) {
        if (input == null || !input.has(key)) {
            return List.of();
        }
        JsonNode node = input.get(key);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (element == null || !element.isTextual()) {
                return null;
            }
            String value = element.asText();
            if (value == null || value.isBlank()) {
                return null;
            }
            values.add(value);
        }
        return values;
    }

    /** 图片来源：附件表 contentId 或文件系统 path（二者互斥由调用方保证）。 */
    private record ImageSource(boolean byContentId, String value) {}

    /**
     * 解析单个图片源 → base64 + mediaType。
     *
     * <p>逻辑搬运自原 execute 单图分支（行为等价）：contentId 优先查附件表
     * （mediaType 须 {@code image/*} 防 id 撞号）→ image-cache 回退；path 走文件系统读盘
     * （相对会话 cwd 解析）。逐张校验 5MB。
     *
     * <p>失败抛 {@link IllegalStateException}，message 即用户可读错误 —— 调用方转
     * {@link ToolResult#error}，与既有 fail-loud 文案保持一致（不静默丢弃某一张图）。
     */
    private Base64Content resolveImageContent(ImageSource source, String sessionId, ToolUseContext ctx) {
        if (source.byContentId()) {
            long contentId;
            try {
                contentId = Long.parseLong(source.value().trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid contentId: " + source.value());
            }
            // [附件双模式] 附件表（attachments 统一 contentId 中心：path/upload 大图）优先 →
            //   附件表 path 读盘 base64；附件表无记录（历史 image-cache contentId / 200 FIFO 逐出 /
            //   重启后内存索引丢失）→ 回退 imageAttachmentStore.getBase64OrDisk（内存命中 → 磁盘兜底）。
            //   [id 空间防撞] 附件表命中须 mediaType=image/*：防 image-cache 空间 id 与附件表
            //   非图片行同数字撞号张冠李戴（对齐发送侧 resolveContentIdInTable "image/" 前缀校验）。
            String sourcePath = null;
            Base64Content content = null;
            if (attachmentService != null) {
                AttachmentRecord rec = attachmentService.getContent(contentId);
                String recMt = rec == null ? null : rec.getMediaType();
                // [multi-image] PDF 附件不属图片通道：直接给可自纠的用法提示，别落到下面的
                //   「attachment cache miss」——那句话对模型没有任何指引价值。
                if (rec != null && recMt != null
                        && recMt.toLowerCase().startsWith("application/pdf")) {
                    throw new IllegalStateException("contentId=" + contentId
                        + " 是 PDF 附件，contentIds/paths 只接受图片；请改用 contentId=\"" + contentId
                        + "\" + contentType=\"pdf\" + pages=[...] 一次分析多页");
                }
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
            if (content == null) {
                log.warn("VisionAnalyzeTool 附件缓存未命中: contentId={} session={}", contentId, sessionId);
                throw new IllegalStateException("attachment cache miss for contentId=" + contentId);
            }
            if (isBase64Oversize(content.base64())) {
                String hint = sourcePath == null || sourcePath.isBlank() ? "" : "，本地路径=" + sourcePath;
                log.warn("VisionAnalyzeTool 图片超 5MB 无法发给视觉模型: contentId={} base64Len={} mediaType={} session={}{}",
                        contentId, content.base64().length(), content.mediaType(), sessionId, hint);
                throw new IllegalStateException(
                        "图片超 5MB 无法发送视觉模型，请改用本地路径引用: contentId=" + contentId + hint);
            }
            if (log.isDebugEnabled()) {
                log.debug("VisionAnalyzeTool 读取图片内容成功: contentId={} mediaType={} base64Len={} session={} 来源={}",
                        contentId, content.mediaType(), content.base64().length(), sessionId,
                        sourcePath == null ? "image-cache/path" : "附件表 path=" + sourcePath);
            }
            return content;
        }
        // [v2 path 图] 文件系统读盘（相对会话 cwd 解析）
        String path = source.value();
        // [multi-image] 同 contentId 分支：PDF 走 pages 多页通道，不要当图片读盘后发出去
        if (looksLikePdf(path)) {
            throw new IllegalStateException("path 是 PDF：" + path
                + "，contentIds/paths 只接受图片；请改用 path=\"" + path
                + "\" + contentType=\"pdf\" + pages=[...] 一次分析多页");
        }
        Path file = resolvePath(path, ctx);
        if (file == null) {
            throw new IllegalStateException("path 文件不存在: " + path);
        }
        Base64Content content;
        try {
            byte[] bytes = Files.readAllBytes(file);
            content = new Base64Content(resolveImageMediaType(path), Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            log.warn("VisionAnalyzeTool path 图片读取失败: path={} 原因={}", path, e.getMessage());
            throw new IllegalStateException("读取 path 图片失败: " + path + " (" + e.getMessage() + ")");
        }
        if (isBase64Oversize(content.base64())) {
            log.warn("VisionAnalyzeTool 图片超 5MB 无法发给视觉模型: path={} base64Len={} mediaType={} session={}",
                    path, content.base64().length(), content.mediaType(), sessionId);
            throw new IllegalStateException(
                    "图片超 5MB 无法发送视觉模型: path=" + path + "（建议缩小图片或改用压缩版本）");
        }
        if (log.isDebugEnabled()) {
            log.debug("VisionAnalyzeTool 读取图片内容成功: path={} mediaType={} base64Len={} session={}",
                    path, content.mediaType(), content.base64().length(), sessionId);
        }
        return content;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
