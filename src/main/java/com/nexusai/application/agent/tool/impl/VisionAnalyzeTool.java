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
        return "代理视觉模型分析：把图片与指令发给独立视觉模型（settings.multimodalModelName）"
                + "分析，返回纯文本结果。type=analyze 读附件缓存图片（contentId）→ 视觉模型分析；"
                + "type=suggest 纯 prompt 建议（不读图）。返回文本中图片以 [image:contentId] 占位符表示。";
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
     * <b>懒加载（shouldDefer=true，2026-09-01 拍板）</b>：本工具只在主模型<b>不支持视觉</b>时
     * 需要；视觉模型（Claude 系）图片直接注入 image block 用不上 → 懒加载省 token。
     *
     * <p><b>懒加载的生效由 useToolSearch 决定</b>（对齐 CC Tool.ts shouldDefer + toolSearch 门控）：
     * <ul>
     *   <li>useToolSearch=true（Claude 系/支持 tool_reference）→ 真懒加载：prompt 只给工具名，
     *       模型经 ToolSearch 确认后调用（defer_loading 语义，省 schema token）。</li>
     *   <li>useToolSearch=false（deepseek 等 openai_compatible 无 tool_reference）→ 懒加载不生效：
     *       全部工具（含本 defer 工具）直接发送完整 schema，模型视作普通工具直接调用（无搜索环节、
     *       无死锁）。由 {@code ToolSearchService.DEFAULT_UNSUPPORTED_MODEL_PATTERNS +"deepseek"}
     *       统一门控保障，非单列本工具。</li>
     * </ul>
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * 输入 schema · 方案定稿 {@code { type(必选), prompt(必选), contentId(可选) }}。
     * {@code contentId} 为 {@link ImageAttachmentStore} 分配的整数图片 id（LLM 侧以字符串传递），
     * 仅 {@code type=analyze} 必填。
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode type = props.putObject("type");
        type.put("type", "string");
        type.put("enum", JsonNodeFactory.instance.arrayNode().add("analyze").add("suggest"));
        type.put("description", "analyze=读缓存图并分析；suggest=纯 prompt 建议（不读图）");

        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "发给视觉模型的指令/问题文本（必填）");

        ObjectNode contentId = props.putObject("contentId");
        contentId.put("type", "string");
        contentId.put("description", "图片附件缓存 id（ImageAttachmentStore 分配的整数 id，字符串形式；type=analyze 必填）");

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
            if (contentId == null || contentId.isBlank()) {
                return ValidationResult.fail("3", "Error: type=analyze requires contentId");
            }
            try {
                Long.parseLong(contentId.trim());
            } catch (NumberFormatException e) {
                return ValidationResult.fail("4", "Error: contentId must be a numeric id, got: " + contentId);
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

        // ── 2. analyze 分支：contentId 必填 + 读图 + 5MB 门控 ──
        String base64 = null;
        String mediaType = null;
        long contentId = -1;
        if ("analyze".equals(type)) {
            if (contentIdStr == null || contentIdStr.isBlank()) {
                return ToolResult.error(call.id(), "type=analyze requires contentId");
            }
            try {
                contentId = Long.parseLong(contentIdStr.trim());
            } catch (NumberFormatException e) {
                log.warn("VisionAnalyzeTool contentId 非法: '{}' 原因={}", contentIdStr, e.getMessage());
                return ToolResult.error(call.id(), "invalid contentId: " + contentIdStr);
            }
            // [附件双模式] 附件表（attachments 统一 contentId 中心：path/upload 大图 contentId=附件表 id）优先
            //   → 附件表 path 读盘 base64；附件表无记录（历史 image-cache ≤5MB contentId / 200 FIFO 逐出 /
            //   重启后内存索引丢失）→ 回退 imageAttachmentStore.getBase64OrDisk（内存命中 → 磁盘兜底）。
            //   [id 空间防撞] 附件表命中须 mediaType=image/*（对齐发送侧 resolveContentIdInTable "image/"
            //   前缀校验）：contentId 可能为 image-cache 空间 id（多模态路由 / PDF 文本模型页图注册），与
            //   附件表非图片行（pdf/媒体/path 其它类型）同数字撞号时，若不加校验会错读非图片文件当图 → 张冠李戴。
            String sourcePath = null;
            Base64Content content = null;
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
            if (content == null) {
                log.warn("VisionAnalyzeTool 附件缓存未命中: contentId={} session={}", contentId, sessionId);
                return ToolResult.error(call.id(), "attachment cache miss for contentId=" + contentId);
            }
            if (isBase64Oversize(content.base64())) {
                String hint = sourcePath == null || sourcePath.isBlank() ? "" : "，本地路径=" + sourcePath;
                log.warn("VisionAnalyzeTool 图片超 5MB 无法发给视觉模型: contentId={} base64Len={} mediaType={} session={}{}（建议改用本地路径引用）",
                        contentId, content.base64().length(), content.mediaType(), sessionId, hint);
                return ToolResult.error(call.id(),
                        "图片超 5MB 无法发送视觉模型，请改用本地路径引用: contentId=" + contentId + hint);
            }
            base64 = content.base64();
            mediaType = content.mediaType();
            if (log.isDebugEnabled()) {
                log.debug("VisionAnalyzeTool 读取附件内容成功: contentId={} mediaType={} base64Len={} session={} 来源={}",
                        contentId, mediaType, base64.length(), sessionId,
                        sourcePath == null ? "image-cache" : "附件表 path=" + sourcePath);
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
            if ("analyze".equals(type)) {
                // 单条 user 消息：contentBlocks=[text, image]（text 块在前，对齐 CC prompt 数组 attachments.ts:1065-1071）
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
            log.error("VisionAnalyzeTool 视觉模型调用失败: model={} type={} contentId={} 原因={}",
                    modelName, type, contentId, e.getMessage());
            return ToolResult.error(call.id(), "vision model call failed: " + e.getMessage());
        }

        // ── 6. 组装纯文本 ToolResult（占位符代替 base64，绝不给主模型 base64）──
        String data = buildResultText(type, contentId, resultText);
        if (log.isDebugEnabled()) {
            log.debug("VisionAnalyzeTool 执行成功: type={} contentId={} model={} resultLen={}（返回纯文本，图片以占位符表示）",
                    type, contentId, modelName, resultText == null ? 0 : resultText.length());
        }
        return ToolResult.success(call.id(), data);
    }

    /**
     * 纯文本结果组装 · 图片以 {@code [image:{contentId}]} 占位符表示，绝不携带 base64。
     */
    private static String buildResultText(String type, long contentId, String resultText) {
        StringBuilder sb = new StringBuilder();
        sb.append("[vision_analyze 结果] type=").append(type);
        if ("analyze".equals(type)) {
            sb.append(", contentId=").append(contentId).append(", 图片占位符=[image:").append(contentId).append(']');
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
