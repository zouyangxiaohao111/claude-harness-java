package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.AttachmentResolver;
import com.nexusai.application.agent.tool.BriefAttachmentUploader;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SendUserMessage 工具 · 对齐 CC {@code tools/BriefTool/BriefTool.ts}（IMP-H3 组 4-2 重写）。
 *
 * <p><b>IMP-H3 变更（组 4-2 拍板「Brief 重写 SendUserMessage/SendUserFile」）</b>：
 * <ul>
 *   <li><b>行为重写（D-H2-1）</b>：旧实现返回 <b>session 快照</b>（session_id/plan_mode/…），
 *       与 CC SendUserMessage 的「给用户发消息」完全错位（M-H2-5，最高优先差异）。现重写为
 *       CC {@code call()}（BriefTool.ts:186-203）：把 {@code message}（+ 可选 {@code attachments}）
 *       投递给用户，返回 {@code {message, sentAt, attachments?}}。</li>
 *   <li><b>工具名对齐（CC BRIEF_TOOL_NAME，prompt.ts:1）</b>：{@code name()="SendUserMessage"}，
 *       {@code aliases()=["Brief"]}（CC LEGACY_BRIEF_TOOL_NAME，prompt.ts:2）——老名 "Brief"
 *       走 alias 回退，历史 transcript 不破坏（Tool.ts:346-360 toolMatchesName/findToolByName）。</li>
 *   <li><b>附件链复用（D-H2-2/D-H2-3 复用保留）</b>：{@link AttachmentResolver}（CC
 *       BriefTool/attachments.ts validateAttachmentPaths/resolveAttachments）+
 *       {@link BriefAttachmentUploader}（CC upload.ts）重新接线为本工具依赖——validateInput 校验附件路径，
 *       execute 解析附件（stat + isImage + BRIDGE_MODE 上传）。<b>[G20②]</b> read-deny 静默跳过已删除
 *       （CC BriefTool 附件链无此逻辑，OPD-TR-H2-02 拍板）。</li>
 *   <li><b>删死常量（D-H2-4）</b>：{@code BriefToolPrompts} 删除（0 引用自述），常量并入本类。</li>
 * </ul>
 *
 * <p><b>CC 契约逐项（source 1992306b）</b>：
 * <ul>
 *   <li>{@code name} = BRIEF_TOOL_NAME 'SendUserMessage'（prompt.ts:1）；{@code aliases} =
 *       [LEGACY_BRIEF_TOOL_NAME 'Brief']（BriefTool.ts:138）</li>
 *   <li>{@code searchHint} = 'send a message to the user — your primary visible output channel'
 *       （BriefTool.ts:139-141）</li>
 *   <li>{@code maxResultSizeChars} = 100_000（BriefTool.ts:142）</li>
 *   <li>{@code userFacingName()} = ''（BriefTool.ts:143-145，UI 不显示工具框）</li>
 *   <li>{@code inputSchema} = z.strictObject{ message(必填), attachments?(数组 string),
 *       status?(enum normal/proactive) }（BriefTool.ts:20-37）</li>
 *   <li>{@code outputSchema} = { message, attachments?:[{path,size,isImage,file_uuid?}], sentAt? }
 *       （BriefTool.ts:42-63，attachments 必须可选——resume 会话逐字重放 pre-attachment 输出）</li>
 *   <li>{@code isEnabled()} = isBriefEnabled()（BriefTool.ts:151-153，feature/Kairos/opt-in 门控）</li>
 *   <li>{@code isConcurrencySafe()} = true（BriefTool.ts:154-156）</li>
 *   <li>{@code isReadOnly()} = true（BriefTool.ts:157-159）</li>
 *   <li>{@code toAutoClassifierInput(input)} = input.message（BriefTool.ts:160-162）</li>
 *   <li>{@code validateInput} = validateAttachmentPaths（BriefTool.ts:163-168，attachments 存在时）</li>
 *   <li>{@code description()} = 'Send a message to the user'（DESCRIPTION，prompt.ts:4）</li>
 *   <li>{@code prompt()} = BRIEF_TOOL_PROMPT（prompt.ts:6-10）</li>
 *   <li>{@code mapToolResultToToolResultBlockParam} → 'Message delivered to user.' + '(n attachment(s)
 *       included)' 后缀（BriefTool.ts:175-183）</li>
 *   <li>{@code call({message,attachments,status})} → logEvent + sentAt + resolveAttachments →
 *       {message, sentAt} 或 {message, attachments, sentAt}（BriefTool.ts:186-203）</li>
 * </ul>
 *
 * <p><b>受控偏差/登记（IMP-H3 范围外）</b>：
 * <ul>
 *   <li><b>isEnabled 门控未实现</b>：CC {@code isBriefEnabled()}（BriefTool.ts:126-134）需要
 *       {@code feature('KAIROS')/KAIROS_BRIEF + getKairosActive()/getUserMsgOptIn()} 门控；Web
 *       后端 {@code getUserMsgOptIn()} 无进程内状态（BriefCommand 为静态工具未接线）→ 本任务
 *       不 override isEnabled()（保持默认 true，工具恒可用，避免 kairos 未部署时工具消失破坏
 *       现有部署），门控待 userMsgOptIn 接线后按 CC 对齐（登记 owner 待决）。</li>
 *   <li><b>renderToolUseMessage/renderToolResultMessage</b>（UI.tsx）为 React 渲染（N/A，Java 无前端）。</li>
 *   <li><b>消息投递通道</b>：CC 中 message 经工具输出 data 渲染为用户可见消息；Web 后端本工具
 *       输出 data 契约 {message,sentAt,attachments} 走正常 tool_result 通道送达前端，前端渲染
 *       SendUserMessage 输出属前端接线范围（待前端对接）。</li>
 * </ul>
 */
@Component
public class BriefTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BriefTool.class);

    /** CC BRIEF_TOOL_NAME（BriefTool/prompt.ts:1）= 'SendUserMessage'。 */
    public static final String NAME = "SendUserMessage";
    /** CC LEGACY_BRIEF_TOOL_NAME（BriefTool/prompt.ts:2）= 'Brief'（历史 transcript 别名）。 */
    public static final String LEGACY_NAME = "Brief";
    /** CC DESCRIPTION（BriefTool/prompt.ts:4）。 */
    public static final String DESCRIPTION = "Send a message to the user";
    /** CC searchHint（BriefTool.ts:139-141）。 */
    public static final String SEARCH_HINT =
        "send a message to the user — your primary visible output channel";
    /** CC BRIEF_TOOL_PROMPT（BriefTool/prompt.ts:6-10）。 */
    public static final String PROMPT =
        "Send a message the user will read. Text outside this tool is visible in the detail view, "
            + "but most won't open it — the answer lives here.\n\n"
            + "`message` supports markdown. `attachments` takes file paths (absolute or cwd-relative) "
            + "for images, diffs, logs.\n\n"
            + "`status` labels intent: 'normal' when replying to what they just asked; 'proactive' when "
            + "you're initiating — a scheduled task finished, a blocker surfaced during background work, "
            + "you need input on something they haven't asked about. Set it honestly; downstream routing "
            + "uses it.";
    /** CC maxResultSizeChars = 100_000（BriefTool.ts:142）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    /** 注入式附件解析器（测试）；null → 生产默认链（stat + read-deny + bridge 上传）。 */
    private final AttachmentResolver attachmentResolver;

    /** BRIDGE_MODE 上传开关 · {@code nexusai.brief.bridge-mode}（默认 false，Web 后端无 bridge 上传）。 */
    @Value("${nexusai.brief.bridge-mode:false}")
    boolean bridgeMode;

    /** 惰性构建的 bridge 附件上传器（仅 bridgeMode=true 时使用，见 {@link #uploader()}）。 */
    private volatile BriefAttachmentUploader uploader;

    /**
     * [W4-1] 旁路改 DB：ANTHROPIC_BASE_URL env 删除 → DB provider baseUrl（首个 enabled provider，
     * 主链 ProviderConfig 同源）。null（未注入/测试/无 provider）→ bridge 上传 baseUrl 回落 null
     * （Web 后端 bridge-mode 默认 false，桥接上传本就不触发，仅登记不阻塞）。
     */
    private ProviderMapper providerMapper;

    /**
     * [W4-1] Spring 注入 DB provider mapper（required=false：测试/孤立运行不注入 → baseUrl 回落 null）。
     */
    @Autowired(required = false)
    public void setProviderMapper(ProviderMapper providerMapper) {
        this.providerMapper = providerMapper;
    }

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent('tengu_brief_send')
     * （BriefTool.ts:188-191）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
    }

    /** Spring 默认构造（生产默认附件解析链）。 */
    public BriefTool() {
        this(null);
    }

    /** 测试构造：强制指定附件解析器（跳过生产默认链）。 */
    BriefTool(AttachmentResolver attachmentResolver) {
        this.attachmentResolver = attachmentResolver;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> aliases() {
        return List.of(LEGACY_NAME);
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    @Override
    public String searchHint() {
        return SEARCH_HINT;
    }

    /** CC BriefTool.ts:143-145 userFacingName() = ''（UI 不显示工具框）。 */
    @Override
    public String userFacingName() {
        return "";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** CC BriefTool.ts:154-156 isConcurrencySafe() = true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** CC BriefTool.ts:157-159 isReadOnly() = true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** CC BriefTool.ts:160-162 toAutoClassifierInput(input) = input.message。 */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        return input != null ? input.path("message").asText("") : "";
    }

    /** CC BriefTool.ts:20-37 inputSchema = z.strictObject{message, attachments?, status?}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode message = props.putObject("message");
        message.put("type", "string");
        message.put("description", "The message for the user. Supports markdown formatting.");

        ObjectNode attachments = props.putObject("attachments");
        attachments.put("type", "array");
        ObjectNode items = JsonNodeFactory.instance.objectNode();
        items.put("type", "string");
        attachments.set("items", items);
        attachments.put("description",
            "Optional file paths (absolute or relative to cwd) to attach. "
                + "Use for photos, screenshots, diffs, logs, or any file the user should see "
                + "alongside the message.");

        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("normal").add("proactive");
        status.put("description",
            "'proactive' = surfacing something the user hasn't asked for and needs to see now "
                + "(task completion, blocker, unsolicited status update). "
                + "'normal' = replying to something the user just said.");

        schema.putArray("required").add("message");
        schema.put("additionalProperties", false);
        return schema;
    }

    /** CC BriefTool.ts:42-63 outputSchema = {message, attachments?, sentAt?}。 */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("message").put("type", "string").put("description", "The message");

        ObjectNode attachments = props.putObject("attachments");
        attachments.put("type", "array");
        ObjectNode item = attachments.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("path").put("type", "string");
        itemProps.putObject("size").put("type", "number");
        itemProps.putObject("isImage").put("type", "boolean");
        itemProps.putObject("file_uuid").put("type", "string");

        props.putObject("sentAt").put("type", "string")
            .put("description", "ISO timestamp captured at tool execution on the emitting process. "
                + "Optional — resumed sessions replay pre-sentAt outputs verbatim.");
        return schema;
    }

    /**
     * CC BriefTool.ts:163-168 validateInput — attachments 存在时校验路径（attachments.ts
     * validateAttachmentPaths：ENOENT/EACCES/EPERM/非普通文件 → errorCode 1）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        JsonNode attachments = input == null ? null : input.path("attachments");
        if (attachments == null || !attachments.isArray() || attachments.isEmpty()) {
            return ValidationResult.pass();
        }
        List<String> paths = new ArrayList<>(attachments.size());
        attachments.forEach(n -> paths.add(n.asText()));
        AttachmentResolver resolver = resolver(ctx);
        AttachmentResolver.ValidationResult vr = resolver.validateAttachmentPaths(paths);
        if (vr == null || vr.result()) {
            return ValidationResult.pass();
        }
        String errorCode = vr.errorCode() == null ? "1" : String.valueOf(vr.errorCode());
        String message = vr.message() == null ? "Attachment validation failed" : vr.message();
        if (log.isDebugEnabled()) {
            log.debug("[SendUserMessage] validateInput 拒绝附件: errorCode={} message={}", errorCode, message);
        }
        return ValidationResult.fail(errorCode, message);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC BriefTool.ts:186-203 call — 消息投递 + 附件解析。
     *
     * <p>无附件 → data {message, sentAt}；有附件 → resolveAttachments（stat + isImage +
     * read-deny + BRIDGE_MODE 上传）→ data {message, attachments, sentAt}。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String message = input.path("message").asText("");
        String status = input.path("status").asText("normal");
        boolean proactive = "proactive".equals(status);

        JsonNode attachmentsNode = input.path("attachments");
        List<String> rawPaths = null;
        if (attachmentsNode != null && attachmentsNode.isArray() && !attachmentsNode.isEmpty()) {
            List<String> paths = new ArrayList<>(attachmentsNode.size());
            attachmentsNode.forEach(n -> paths.add(n.asText()));
            rawPaths = paths;
        }
        // [IMP-T G15] 遥测 tengu_brief_send（CC BriefTool.ts:188-191）
        emitBriefSend(proactive, rawPaths == null ? 0 : rawPaths.size());

        String sentAt = Instant.now().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        data.put("sentAt", sentAt);

        if (rawPaths != null && !rawPaths.isEmpty()) {
            try {
                List<Map<String, Object>> resolved = resolve(rawPaths, ctx);
                data.put("attachments", resolved);
            } catch (Exception e) {
                // CC attachments.ts:73-79 语义：TOCTOU 下文件移动 → stat 抛错 → 模型看到错误。
                // Java Tool 约定「错误不抛」，转 ToolResult.error 返回（is_error 由执行器推导）。
                if (log.isWarnEnabled()) {
                    log.warn("[SendUserMessage] 附件解析失败: messageLen={} err={}",
                        message.length(), e.toString());
                }
                return ToolResult.error(call.id(),
                    "Attachment resolution failed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[SendUserMessage] 消息投递完成: messageLen={} attachments={} status={} proactive={} sentAt={}",
                message.length(), rawPaths == null ? 0 : rawPaths.size(), status, proactive, sentAt);
        }
        return ToolResult.success(call.id(), data);
    }

    /**
     * [IMP-T G15] tengu_brief_send 遥测 · 对齐 CC BriefTool.ts:188-191
     * {@code logEvent('tengu_brief_send', {proactive: status === 'proactive',
     * attachment_count: attachments?.length ?? 0})}。
     *
     * @param proactive 是否主动消息（status='proactive'）
     * @param attachmentCount 附件数（无附件 → 0）
     */
    private void emitBriefSend(boolean proactive, int attachmentCount) {
        if (analyticsTracker == null) {
            return;
        }
        analyticsTracker.logEvent("tengu_brief_send",
            Map.<String, Object>of(
                "proactive", proactive,
                "attachment_count", attachmentCount));
        if (log.isDebugEnabled()) {
            log.debug("[SendUserMessage] [IMP-T G15] 遥测 tengu_brief_send: proactive={} attachment_count={}",
                proactive, attachmentCount);
        }
    }

    /**
     * CC BriefTool.ts:175-183 mapToolResultToToolResultBlockParam — 投递确认文案。
     *
     * <p>成功路径返回 'Message delivered to user.' + '(n attachment(s) included)' 后缀；
     * 错误路径（isError=true，如附件 TOCTOU 缺失）透传错误消息（CC 错误块由 toolExecution.ts
     * 直构不经本 mapper，Java 端等价回退 renderToolResultPayloadText）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            String msg = result instanceof ToolResult<?> tr
                ? ToolResult.renderToolResultPayloadText(tr) : "Message delivery failed.";
            if (log.isDebugEnabled()) {
                log.debug("[SendUserMessage] 投递失败结果块: toolUseId={} err={}", toolUseId, msg);
            }
            return new ToolResultBlockParam(toolUseId, "tool_result", msg, true);
        }
        int n = 0;
        if (result != null && result.data() instanceof Map<?, ?> data) {
            Object att = data.get("attachments");
            if (att instanceof List<?> list) {
                n = list.size();
            }
        }
        String suffix = n == 0 ? "" : " (" + n + " " + (n == 1 ? "attachment" : "attachments") + " included)";
        if (log.isDebugEnabled()) {
            log.debug("[SendUserMessage] 投递结果块: toolUseId={} attachments={} isError={}",
                toolUseId, n, isError);
        }
        return new ToolResultBlockParam(
            toolUseId, "tool_result", "Message delivered to user." + suffix, isError);
    }

    // ═══════════════ 附件链（CC attachments.ts + upload.ts 生产接线） ═══════════════

    /** 注入式 resolver（测试）或生产默认链（stat + bridge 上传；G20② read-deny 已删）。 */
    private AttachmentResolver resolver(ToolUseContext ctx) {
        if (attachmentResolver != null) {
            return attachmentResolver;
        }
        return new AttachmentResolver(
            cwd(ctx),
            BriefTool::statFile,
            AttachmentResolver.IMAGE_EXTENSION_REGEX.asPredicate(),
            this::isBridgeMode,
            () -> isEnvTruthy(System.getenv("CLAUDE_CODE_BRIEF_UPLOAD")),
            this::uploadBriefAttachment);
    }

    /** 解析附件 → CC ResolvedAttachment {path,size,isImage,file_uuid?} 的扁平 Map 列表。 */
    private List<Map<String, Object>> resolve(List<String> rawPaths, ToolUseContext ctx) {
        AttachmentResolver resolver = resolver(ctx);
        List<AttachmentResolver.ResolvedAttachment> resolved =
            resolver.resolveAttachments(rawPaths, replBridgeEnabled(ctx), false);
        List<Map<String, Object>> out = new ArrayList<>(resolved.size());
        for (AttachmentResolver.ResolvedAttachment a : resolved) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", a.path());
            m.put("size", a.size());
            m.put("isImage", a.isImage());
            if (a.fileUuid() != null) {
                m.put("file_uuid", a.fileUuid());
            }
            out.add(m);
        }
        return out;
    }

    /** CC resolveAttachments uploader 函数 · 包装 {@link BriefAttachmentUploader}（复用，D-H2-2）。 */
    private String uploadBriefAttachment(AttachmentResolver.UploadRequest req) {
        BriefAttachmentUploader u = uploader();
        if (u == null) {
            return null;
        }
        BriefAttachmentUploader.Optional<String> uuid = u.uploadBriefAttachment(
            req.path(), req.size(),
            new BriefAttachmentUploader.BriefUploadContext(req.replBridgeEnabled()));
        return uuid == null ? null : uuid.get();
    }

    /** 惰性构建 bridge 上传器（仅 bridgeMode=true 触发；测试/桥接关 → 不构建）。 */
    private BriefAttachmentUploader uploader() {
        BriefAttachmentUploader u = uploader;
        if (u == null) {
            synchronized (this) {
                u = uploader;
                if (u == null) {
                    u = new BriefAttachmentUploader(
                        this::isBridgeMode,
                        this::dbProviderBaseUrl, // [W4-1] oauthConfig fallback（原 ANTHROPIC_BASE_URL env）→ DB provider
                        this::dbProviderBaseUrl, // [W4-1] 原 ANTHROPIC_BASE_URL env → DB provider baseUrl
                        () -> "",                                   // bridge override 无
                        () -> System.getenv("CLAUDE_CODE_OAUTH_TOKEN"),
                        BriefTool::readAllBytes,
                        BriefTool::httpPost,
                        BriefTool::parseFileUuid);
                    uploader = u;
                }
            }
        }
        return u;
    }

    /**
     * [W4-1] DB provider baseUrl 解析（首个 enabled provider）· 对齐 CC upload.ts:72
     * {@code process.env.ANTHROPIC_BASE_URL} 的 DB 承载。null = mapper 未注入 / 无 provider /
     * baseUrl 全空 / 读取失败（bridge 上传不触发，见 {@link #uploader()} 注释）。
     */
    private String dbProviderBaseUrl() {
        if (providerMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("[BriefTool] providerMapper 未注入（测试/孤立运行），bridge baseUrl 回落 null");
            }
            return null;
        }
        try {
            List<ProviderRecord> list =
                providerMapper.selectListByQuery(QueryWrapper.create().where("enabled = ?", true));
            for (ProviderRecord r : list) {
                if (r.getBaseUrl() != null && !r.getBaseUrl().isBlank()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[BriefTool] bridge baseUrl 改读 DB provider={} baseUrl={}（[W4-1] env 路删除）",
                            r.getName(), r.getBaseUrl());
                    }
                    return r.getBaseUrl();
                }
            }
        } catch (Exception e) {
            log.warn("[BriefTool] DB provider baseUrl 读取失败, 回落 null: {}", e.toString());
        }
        return null;
    }

    /** 当前会话 cwd（validate ENOENT 消息用；CC getCwd()）。
     *
     *  <p>cwd-align-ext：兜底改走会话 cwd（CC attachments.ts:29 {@code const cwd = getCwd()}）；
     *  保留 {@code ctx.effectiveCwd()} 优先层；无 sessionId 回落 user.dir（方案 1，零行为变化）。 */
    private static String cwd(ToolUseContext ctx) {
        if (ctx != null && ctx.effectiveCwd() != null) {
            return ctx.effectiveCwd().toString();
        }
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return Path.of(cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".")).toString();
    }

    /** CC appState.replBridgeEnabled（Web 后端默认 false；有键则读取）。 */
    private static boolean replBridgeEnabled(ToolUseContext ctx) {
        if (ctx == null || ctx.getAppState() == null) {
            return false;
        }
        try {
            Map<String, Object> snapshot = ctx.getAppState().apply(null);
            if (snapshot == null) {
                return false;
            }
            return Boolean.TRUE.equals(snapshot.get("replBridgeEnabled"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBridgeMode() {
        return bridgeMode;
    }

    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase();
        return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    /** CC fs.stat → FileStat（ENOENT/EACCES/EPERM → FileSystemException 供 validate 分类）。 */
    private static AttachmentResolver.FileStat statFile(String path) {
        try {
            Path p = Path.of(path);
            if (Files.isRegularFile(p)) {
                return new AttachmentResolver.FileStat(Files.size(p), true);
            }
            if (Files.exists(p)) {
                return new AttachmentResolver.FileStat(0L, false); // 非普通文件 → not a regular file
            }
            throw new AttachmentResolver.FileSystemException("ENOENT",
                "No such file or directory: " + path);
        } catch (java.nio.file.AccessDeniedException e) {
            throw new AttachmentResolver.FileSystemException("EACCES", e.getMessage());
        } catch (java.nio.file.NoSuchFileException e) {
            throw new AttachmentResolver.FileSystemException("ENOENT", e.getMessage());
        } catch (java.io.IOException e) {
            throw new AttachmentResolver.FileSystemException("EIO", e.getMessage());
        }
    }

    /** CC readFile → byte[]（注入给 uploader）。 */
    private static byte[] readAllBytes(String path) throws Exception {
        return Files.readAllBytes(Path.of(path));
    }

    /** CC axios.post（multipart）→ HttpResult；best-effort：异常 → status 0 → upload 返回空。 */
    private static BriefAttachmentUploader.HttpResult httpPost(
            String url, Map<String, String> headers, byte[] body, long timeoutMs) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(Math.min(timeoutMs, 10_000L)))
                .build();
            java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofMillis(timeoutMs))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(rb::header);
            java.net.http.HttpResponse<String> resp =
                client.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return new BriefAttachmentUploader.HttpResult(resp.statusCode(), resp.body());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SendUserMessage] 附件上传 HTTP 异常（best-effort 跳过）: url={} err={}",
                    url, e.getMessage());
            }
            return new BriefAttachmentUploader.HttpResult(0, "");
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** CC uploadResponseSchema — {file_uuid} 解析；缺/非文本 → empty。 */
    private static BriefAttachmentUploader.Optional<String> parseFileUuid(String body) {
        if (body == null || body.isBlank()) {
            return BriefAttachmentUploader.Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode uuid = node.path("file_uuid");
            if (uuid.isTextual() && !uuid.asText().isEmpty()) {
                return BriefAttachmentUploader.Optional.of(uuid.asText());
            }
            return BriefAttachmentUploader.Optional.empty();
        } catch (Exception e) {
            return BriefAttachmentUploader.Optional.empty();
        }
    }
}
