package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * computer-use <b>视觉 grounding</b> 服务 · 把 CU 截图交给<b>本仓配置的视觉模型</b>
 * （{@code settings.multimodal_model_name}）定位目标，产出<b>图像像素坐标</b>。
 *
 * <h2>WHY 需要它（背景）</h2>
 * <p>CU 截图以 {@code role=user} + {@code image_url} 送达主模型 ⇒ <b>主模型不支持视觉</b>
 * 的端点（如 {@code deepseek-chat}）根本看不到画面 ⇒ 它给出的点击坐标<b>没有来源</b>。
 * <p>CCB 2.8.4 <b>没有</b>这条路径：它的 computer-use MCP 假定主模型恒为 Claude（能看图）。
 * 本仓主模型可被换成文本端点 ⇒ 补一条<b>降级路径</b>：让<b>视觉模型</b>看图并给出坐标。
 *
 * <h2>与 {@code vision_analyze} 的关系</h2>
 * <p>本服务<b>复用</b> {@code vision_analyze} 的旁路调用链（视觉模型名 →
 * {@code ModelConfigResolver.resolve} → {@code LlmProviderFactory} →
 * {@code LlmSideCallClient.chat}），但<b>prompt 是新的</b>：
 * {@code vision_analyze} 是<b>主模型原文直传</b>（通用「看这张图」），本服务要的是
 * <b>结构化坐标</b> ⇒ 必须自带 grounding 模板 + 严格解析 + 严格校验。
 *
 * <h2>⭐ 坐标一律【图像像素】（与主链路 pixels 语义同制式）</h2>
 * <p>CU 截图抓的是 1920×1080，但送进视觉模型的是<b>预缩放后</b>的图（如 1456×819）
 * ⇒ <b>模型看到的那张图就是缩放图</b> ⇒ 它回的「图上像素坐标」<b>天然等于</b> CCB
 * {@code pixels} 模式的制式（{@code tools.ts:22-23}：“read directly from the most recent
 * screenshot image”）⇒ 降级路径<b>不必</b>再把坐标模式切成 {@code normalized_0_100}。
 * <p>换算由既有 {@link ComputerUseCoordinateScaler} 的 {@code pixels} 分支负责
 * （{@code rawX × (displayWidth / imageWidth) + originX}），<b>本类不自己换算</b>。
 *
 * <h2>⛔ 失败一律 fail loud（绝不猜坐标）</h2>
 * <ol>
 *   <li>{@link Status#NOT_FOUND} —— 视觉模型说「图里没有这个目标 / 目标不唯一」；</li>
 *   <li>{@link Status#PARSE_FAILED} —— 模型输出不是可解析的 JSON / 缺 found / 缺坐标 / 坐标非数值；</li>
 *   <li>{@link Status#OUT_OF_RANGE} —— 像素坐标不在 {@code [0, 图像宽-1] × [0, 图像高-1]}；</li>
 *   <li>{@link Status#PERCENT_PIXEL_MISMATCH} —— 模型额外回填的百分比与像素坐标<b>互校不一致</b>；</li>
 *   <li>{@link Status#TARGET_MISMATCH} —— <b>护栏①</b>：模型自报的目标名与请求描述不一致；</li>
 *   <li>{@link Status#MODEL_UNAVAILABLE} —— 视觉模型未配置 / provider 不可用；</li>
 *   <li>{@link Status#CALL_FAILED} —— 旁路调用抛异常。</li>
 * </ol>
 * <p>每种都返回<b>明确的中文错误文案</b>（{@link GroundingOutcome#errorMessage()}），
 * <b>绝不</b>返回一个「大概是」的坐标。
 *
 * <p><b>与 CCB 的差异（如实标注）</b>：CCB 无此服务、无此 prompt、无此 JSON 契约。
 */
public final class ComputerUseGroundingService {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseGroundingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记账标签（usage-gateway 侧信道来源）。 */
    public static final String USAGE_LABEL = "computer_use_grounding";

    /**
     * 百分比 ↔ 像素互校容差 · {@value}（占图像宽 / 高的比例）。
     *
     * <p><b>取值依据</b>：模型把像素换算成百分比再回填时，1 位小数的舍入误差
     * ≤ 0.05% × 图像宽（1456 宽 ⇒ 0.73px）；整数百分比 ⇒ ≤ 0.5% × 图像宽（7.3px）。
     * ⇒ 取 3%（1456 宽 ⇒ 43.7px）既能吸收正常舍入，又能抓住「把百分比当像素回填」
     * 或「按屏幕宽（1920）而不是图像宽（1456）算百分比」这两类错误。
     */
    public static final double PERCENT_PIXEL_TOLERANCE = 0.03d;

    /**
     * grounding prompt 模板 · <b>本仓新增</b>（CCB 无对应；{@code vision_analyze} 也没有）。
     *
     * <p><b>WHY 要求结构化 JSON</b>：下游要做的是「拿到坐标就点」⇒ 必须有可机器解析的
     * 契约；自由文本 ⇒ 无法校验 ⇒ 只能靠猜（本仓明确禁止）。
     *
     * <p><b>⭐ 必须是【图像像素】</b>：模型看到的就是缩放后的那张图 ⇒ 它的「图上像素」
     * 与 CCB {@code pixels} 模式同制式。⛔ 因此 prompt <b>必须把本次图像的宽高告诉模型</b>，
     * 否则模型无从给出像素坐标（它默认按 0–100 或按常见分辨率猜）。
     *
     * <p><b>占位符</b>：{@code %1$s}=目标描述、{@code %2$d}=图像宽、{@code %3$d}=图像高、
     * {@code %4$d}=最大合法 X、{@code %5$d}=最大合法 Y。
     */
    public static final String PROMPT_TEMPLATE = """
            你是一个屏幕坐标定位助手。给定一张屏幕截图和一个目标描述，请找出目标在图中的位置。

            这张截图的图像尺寸是 %2$d × %3$d 像素（这是图片自身的像素尺寸，不是屏幕分辨率）。

            输出要求（必须严格遵守）：
            1. 只输出一个 JSON 对象，不要输出任何解释文字，不要使用 Markdown 代码块。
            2. JSON 的形状固定为：{"found": true, "x": 0, "y": 0, "target": "你找到的控件名称"}
            3. x 与 y 是目标【中心点】在这张截图图像上的【像素坐标】：
               - x = 从图片左边缘算起的像素数，取值范围 0 到 %4$d；
               - y = 从图片上边缘算起的像素数，取值范围 0 到 %5$d。
               x 与 y 必须是整数、必须落在上面的范围内；不要输出百分比，也不要输出屏幕坐标。
            4. 如果截图里找不到目标，或者目标不唯一（画面里有多个长得很像的候选控件），
               只输出：{"found": false, "x": 0, "y": 0, "target": "目标描述"}
               此时不要猜坐标，也不要退而选择相邻的那个相似控件。
            5. target 字段回填你实际找到的那【一个】控件的名称（找不到时回填原目标描述）。

            目标描述：%1$s
            """;

    private final ModelConfigResolver modelConfigResolver;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmSideCallClient sideCallClient;
    /** 护栏③ · 系统性偏移补偿 X（<b>屏幕物理像素</b>；默认 0 = 不启用）。 */
    private final double offsetX;
    /** 护栏③ · 系统性偏移补偿 Y（<b>屏幕物理像素</b>；默认 0 = 不启用）。 */
    private final double offsetY;

    /**
     * @param modelConfigResolver 多模态档位模型名 + ProviderConfig 解析（{@code settings.multimodal_model_name}）
     * @param llmProviderFactory  provider 工厂（按 providerType 路由）
     * @param sideCallClient      旁路单发客户端（调用 + 记账）
     */
    public ComputerUseGroundingService(ModelConfigResolver modelConfigResolver,
                                       LlmProviderFactory llmProviderFactory,
                                       LlmSideCallClient sideCallClient) {
        this(modelConfigResolver, llmProviderFactory, sideCallClient, 0.0d, 0.0d);
    }

    /**
     * 生产构造器（带护栏③ 的系统性偏移补偿）。
     *
     * @param modelConfigResolver 多模态档位模型名 + ProviderConfig 解析
     * @param llmProviderFactory  provider 工厂
     * @param sideCallClient      旁路单发客户端
     * @param offsetX             偏移补偿 X（<b>屏幕物理像素</b>；0 = 不启用）
     * @param offsetY             偏移补偿 Y（<b>屏幕物理像素</b>；0 = 不启用）
     */
    public ComputerUseGroundingService(ModelConfigResolver modelConfigResolver,
                                       LlmProviderFactory llmProviderFactory,
                                       LlmSideCallClient sideCallClient,
                                       double offsetX, double offsetY) {
        this.modelConfigResolver = modelConfigResolver;
        this.llmProviderFactory = llmProviderFactory;
        this.sideCallClient = sideCallClient;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /**
     * grounding 结果状态。
     */
    public enum Status {
        /** 找到目标且坐标合法。 */
        FOUND,
        /** 视觉模型明确说图里没有这个目标（或目标不唯一）。 */
        NOT_FOUND,
        /** 模型输出无法解析成约定的 JSON 形状。 */
        PARSE_FAILED,
        /** 坐标越界（不在图像像素范围内）。 */
        OUT_OF_RANGE,
        /** 模型额外回填的百分比与像素坐标互校不一致。 */
        PERCENT_PIXEL_MISMATCH,
        /** 护栏①：模型自报的目标名与请求的目标描述不一致。 */
        TARGET_MISMATCH,
        /** 视觉模型不可用（未配置 / provider 解析失败）。 */
        MODEL_UNAVAILABLE,
        /** 旁路调用抛异常。 */
        CALL_FAILED
    }

    /**
     * grounding 结果。
     *
     * @param status          状态
     * @param xPixels         目标中心的图像像素 X（仅 {@link Status#FOUND} 有值）
     * @param yPixels         目标中心的图像像素 Y（仅 {@link Status#FOUND} 有值）
     * @param echoedPercentX  模型<b>额外</b>回填的百分比 X（可空；仅用于互校与诊断）
     * @param echoedPercentY  模型<b>额外</b>回填的百分比 Y（可空；仅用于互校与诊断）
     * @param target          视觉模型回填的目标描述（可空）
     * @param detail          失败细节 / 原始输出（诊断用，可空）
     */
    public record GroundingOutcome(Status status,
                                   Double xPixels,
                                   Double yPixels,
                                   Double echoedPercentX,
                                   Double echoedPercentY,
                                   String target,
                                   String detail) {

        /** @return 是否定位成功 */
        public boolean found() {
            return status == Status.FOUND;
        }

        /**
         * 用户 / 模型可见的错误文案（成功时返回 null）。
         *
         * <p>⛔ 各类失败各有<b>明确且互不相同</b>的文案 ⇒ 模型能据此自纠（换描述 / 换模型 /
         * 先截图再点），而不是收到一句含糊的「失败了」。
         *
         * @return 错误文案；成功 ⇒ null
         */
        public String errorMessage() {
            if (found()) {
                return null;
            }
            String t = target == null || target.isBlank() ? "该目标" : "「" + target + "」";
            return switch (status) {
                case NOT_FOUND ->
                        "视觉模型在当前屏幕上没有找到" + t + "（或找到多个相似候选、无法确定是哪一个）。"
                                + "请换一种描述（例如加上窗口名、按钮文字），或确认目标确实在屏幕上。";
                case PARSE_FAILED ->
                        "视觉模型返回的定位结果无法解析成约定的 JSON 坐标格式"
                                + (detail == null || detail.isBlank() ? "" : "（原始输出：" + detail + "）")
                                + "。已中止本次定位，不会返回猜测的坐标，请重试。";
                case OUT_OF_RANGE ->
                        "视觉模型返回的坐标越界"
                                + (detail == null || detail.isBlank() ? "" : "（" + detail + "）")
                                + "，像素坐标必须落在该截图图像范围内。已中止本次定位，不会返回猜测的坐标，请重试。";
                case PERCENT_PIXEL_MISMATCH ->
                        "视觉模型返回的像素坐标与它自己回填的百分比坐标互相矛盾"
                                + (detail == null || detail.isBlank() ? "" : "（" + detail + "）")
                                + "。已中止本次定位（坐标可能被换算错了空间），请重试。";
                case TARGET_MISMATCH ->
                        "视觉模型找到的目标与请求的目标描述不一致"
                                + (detail == null || detail.isBlank() ? "" : "（" + detail + "）")
                                + "。为避免点错相邻控件，已中止本次定位；请把目标描述写得更具体"
                                + "（例如加上窗口名与控件上的文字）后重试。";
                case MODEL_UNAVAILABLE ->
                        "视觉模型不可用"
                                + (detail == null || detail.isBlank() ? "" : "：" + detail)
                                + "。请在设置里配置「多模态档位模型」（settings.multimodal_model_name）"
                                + "并确认该模型已启用且有可用的 API Key，然后重试。";
                case CALL_FAILED ->
                        "调用视觉模型失败"
                                + (detail == null || detail.isBlank() ? "" : "：" + detail)
                                + "。已中止本次定位，请稍后重试或改用支持视觉输入的模型。";
                case FOUND -> null;
            };
        }
    }

    /**
     * 执行一次 grounding（截图 + 目标描述 → <b>图像像素坐标</b>）。
     *
     * @param capture 截图结果（含 base64 + 媒体类型 + 图像宽高）
     * @param target  目标描述（用户/模型给的自然语言，如「保存按钮」）
     * @param ctx     工具上下文（归属 + 记费用；可 null ⇒ standalone 归属）
     * @return 结果（永不为 null）
     */
    public GroundingOutcome ground(ComputerUseCaptureResult capture, String target, ToolUseContext ctx) {
        if (capture == null) {
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "没有可用的截图");
        }
        if (target == null || target.isBlank()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target,
                    "缺少目标描述");
        }

        // ── 1. 视觉模型名（fail loud，不静默回落）──
        String modelName = modelConfigResolver == null ? null : modelConfigResolver.resolveMultimodalModelName();
        if (modelName == null || modelName.isBlank()) {
            log.warn("[computer-use] grounding: 视觉模型未配置（settings.multimodalModelName 缺失）⇒ fail loud");
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "未配置「多模态档位模型」（settings.multimodal_model_name）");
        }

        // ── 2. ProviderConfig 解析 ──
        ModelConfigResolver.ResolvedModel resolved;
        try {
            resolved = modelConfigResolver.resolve(modelName);
        } catch (Exception e) {
            log.warn("[computer-use] grounding: provider 解析异常 model={} err={}", modelName, e.toString());
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "解析视觉模型 '" + modelName + "' 失败：" + e.getMessage());
        }
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("[computer-use] grounding: provider 不可用 model={}", modelName);
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "视觉模型 '" + modelName + "' 没有可用的 provider / API Key");
        }
        if (llmProviderFactory == null) {
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "provider 工厂未装配");
        }
        LlmProvider provider = llmProviderFactory.getProvider(resolved.config(), resolved.providerType());
        if (provider == null) {
            log.warn("[computer-use] grounding: provider 工厂路由失败 model={} type={}",
                    modelName, resolved.providerType());
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "视觉模型 provider 路由失败：" + modelName);
        }
        if (sideCallClient == null) {
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, null, null, target,
                    "旁路调用客户端未装配");
        }

        // ── 3. 组装 user 消息（text + image）→ 旁路单发 ──
        //    ⛔ prompt 必须带上【本次图像的宽高】—— 否则模型无法给出像素坐标。
        int imageWidth = capture.imageWidth();
        int imageHeight = capture.imageHeight();
        String prompt = PROMPT_TEMPLATE.formatted(
                target.trim(), imageWidth, imageHeight,
                Math.max(0, imageWidth - 1), Math.max(0, imageHeight - 1));
        String raw;
        try {
            List<JsonNode> blocks = new ArrayList<>(2);
            blocks.add(textBlock(prompt));
            blocks.add(com.nexusai.application.agent.LlmAgentLoop.imageContentBlock(
                    capture.mediaType(), capture.base64()));
            ChatMessageDto user = com.nexusai.application.agent.LlmAgentLoop.toMessage(
                    Role.user, prompt, null, null, blocks, List.of(), true);
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                    List.of(user), null, null, null, null, USAGE_LABEL, null, null,
                    ctx != null ? ctx.agentContext() : null);
            LlmSideCallClient.SideCallUsage usage = ctx != null
                    ? LlmSideCallClient.SideCallUsage.inSession(
                            ctx.sessionId(),
                            ctx.agentId() == null ? null : ctx.agentId().toString(),
                            UsageCategory.SIDE_CALL, USAGE_LABEL)
                            .withAgentContext(ctx.agentContext())
                    : LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, USAGE_LABEL);
            raw = sideCallClient.chat(resolved.config(), modelName, null, null, options, usage);
        } catch (Exception e) {
            log.error("[computer-use] grounding: 视觉模型调用失败 model={} target={} err={}",
                    modelName, target, e.toString());
            return new GroundingOutcome(Status.CALL_FAILED, null, null, null, null, target, e.getMessage());
        }

        if (raw == null || raw.isBlank()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target, "（空输出）");
        }

        // ── 4. 宽松 JSON 解析 + 严格校验（像素域 + 护栏① + 百分比互校）──
        GroundingOutcome outcome = parse(raw, target, imageWidth, imageHeight);
        if (!outcome.found()) {
            return outcome;
        }
        // ── 5. 护栏③：系统性偏移补偿（默认 0 = 不启用 ⇒ 行为不变）──
        return applyOffset(outcome, capture);
    }

    /**
     * 护栏③ · 应用系统性偏移补偿。
     *
     * <p><b>WHY 在【换算之前】应用</b>：偏移是<b>视觉模型系统性偏右下</b>的补偿量，
     * 必须作用在「模型给的图像像素坐标」上，再由
     * {@link ComputerUseCoordinateScaler} 统一换算到屏幕 ⇒ 换算链只有一条。
     *
     * <p><b>单位</b>：配置值是<b>屏幕物理像素</b>（与 PoC 实测的 dx/dy 同量纲），
     * 内部按截图时刻的缩放比折算成图像像素（{@code offset / scale}）⇒
     * 换算回屏幕时正好等于配置值。
     *
     * @param outcome grounding 结果
     * @param capture 截图结果（提供几何）
     * @return 补偿后的结果（{@code offsetX == 0 && offsetY == 0} ⇒ 原样返回）
     */
    private GroundingOutcome applyOffset(GroundingOutcome outcome, ComputerUseCaptureResult capture) {
        if (offsetX == 0.0d && offsetY == 0.0d) {
            return outcome;
        }
        ComputerUseScreenshotGeometry geometry = capture.geometry();
        double adjustedX = outcome.xPixels() + offsetX / geometry.scaleX();
        double adjustedY = outcome.yPixels() + offsetY / geometry.scaleY();
        if (log.isInfoEnabled()) {
            log.info("[computer-use] grounding 偏移补偿: dx={}px dy={}px · 图像坐标 ({},{}) → ({},{})"
                            + "（截图 {}x{} · 缩放 {}）",
                    offsetX, offsetY, outcome.xPixels(), outcome.yPixels(),
                    adjustedX, adjustedY, capture.imageWidth(), capture.imageHeight(),
                    geometry.scaleX());
        }
        return new GroundingOutcome(Status.FOUND, adjustedX, adjustedY,
                outcome.echoedPercentX(), outcome.echoedPercentY(), outcome.target(),
                "偏移补偿 dx=" + offsetX + "px dy=" + offsetY + "px");
    }

    /**
     * 宽松 JSON 解析（剥围栏 + 取花括号）· 照抄
     * {@code com.nexusai.application.session.SessionArtifactParser} 的手法。
     *
     * @param raw         视觉模型原始输出
     * @param target      目标描述（失败时回显）
     * @param imageWidth  本次截图的图像宽（像素）
     * @param imageHeight 本次截图的图像高（像素）
     * @return 结果
     */
    static GroundingOutcome parse(String raw, String target, int imageWidth, int imageHeight) {
        String json = extractJsonObject(raw);
        if (json == null) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target, truncate(raw));
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target, truncate(raw));
        }
        if (root == null || !root.isObject()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target, truncate(raw));
        }
        JsonNode foundNode = root.get("found");
        if (foundNode == null || !foundNode.isBoolean()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target, truncate(raw));
        }
        String resolvedTarget = root.path("target").isTextual() ? root.path("target").asText() : null;
        if (!foundNode.asBoolean()) {
            // found=false ⇒ 不要求 target 字段（模型如实说「没找到」就够了）
            return new GroundingOutcome(Status.NOT_FOUND, null, null, null, null,
                    resolvedTarget == null ? target : resolvedTarget, null);
        }
        if (resolvedTarget == null || resolvedTarget.isBlank()) {
            // found=true 却没回填「它找到的是哪个控件」⇒ 无法交叉验证（护栏① 无从下手）
            // ⇒ 按「缺字段」fail loud（⛔ 绝不拿请求描述冒充模型的自报）。
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, target,
                    "模型没有回填 target 字段（无法交叉验证它是否找到了请求的那个目标）");
        }
        JsonNode xNode = root.get("x");
        JsonNode yNode = root.get("y");
        if (xNode == null || yNode == null || !xNode.isNumber() || !yNode.isNumber()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, resolvedTarget, truncate(raw));
        }
        double x = xNode.asDouble();
        double y = yNode.asDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, null, null, resolvedTarget, truncate(raw));
        }
        // ── 越界判定：像素域（⛔ 不再用 0–100 的百分比域）──
        if (x < 0 || y < 0 || x > imageWidth - 1 || y > imageHeight - 1) {
            return new GroundingOutcome(Status.OUT_OF_RANGE, null, null, null, null, resolvedTarget,
                    "像素坐标 x=" + x + ", y=" + y + "，但图像尺寸只有 " + imageWidth + "x" + imageHeight);
        }
        // ── 可选的百分比字段（模型自行回填时）→ 与像素坐标互校 ──
        Double percentX = readPercent(root, "xPercent");
        Double percentY = readPercent(root, "yPercent");
        if (percentX != null || percentY != null) {
            String mismatch = crossCheck(x, y, percentX, percentY, imageWidth, imageHeight);
            if (mismatch != null) {
                return new GroundingOutcome(Status.PERCENT_PIXEL_MISMATCH, null, null,
                        percentX, percentY, resolvedTarget, mismatch);
            }
        }
        // ── 护栏①：模型自报的目标名 ↔ 请求的目标描述 ──
        if (!ComputerUseGroundingTargetMatch.matches(target, resolvedTarget)) {
            return new GroundingOutcome(Status.TARGET_MISMATCH, null, null, percentX, percentY,
                    resolvedTarget,
                    "请求的目标描述=「" + target + "」，模型回填的目标=「" + resolvedTarget + "」");
        }
        return new GroundingOutcome(Status.FOUND, x, y, percentX, percentY, resolvedTarget, null);
    }

    /**
     * 读模型可选回填的百分比字段（非数值 ⇒ 当作没给）。
     *
     * @param root JSON 根
     * @param name 字段名
     * @return 百分比值或 null
     */
    private static Double readPercent(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isNumber()) {
            return null;
        }
        double v = node.asDouble();
        return Double.isFinite(v) ? v : null;
    }

    /**
     * 像素 ↔ 百分比互校：{@code px ≈ percent / 100 × 图像宽}。
     *
     * @param x           像素 X
     * @param y           像素 Y
     * @param percentX    模型回填的百分比 X（可空）
     * @param percentY    模型回填的百分比 Y（可空）
     * @param imageWidth  图像宽
     * @param imageHeight 图像高
     * @return null = 一致（或没有百分比可比）；非 null = 不一致的描述（含两个值）
     */
    static String crossCheck(double x, double y, Double percentX, Double percentY,
                             int imageWidth, int imageHeight) {
        if (percentX != null) {
            double expected = percentX / 100.0d * imageWidth;
            double tolerance = PERCENT_PIXEL_TOLERANCE * imageWidth;
            if (Math.abs(expected - x) > tolerance) {
                return "像素 x=" + x + " 与回填百分比 xPercent=" + percentX
                        + "（换算后应为 " + round(expected) + "，容差 " + round(tolerance) + "）不一致";
            }
        }
        if (percentY != null) {
            double expected = percentY / 100.0d * imageHeight;
            double tolerance = PERCENT_PIXEL_TOLERANCE * imageHeight;
            if (Math.abs(expected - y) > tolerance) {
                return "像素 y=" + y + " 与回填百分比 yPercent=" + percentY
                        + "（换算后应为 " + round(expected) + "，容差 " + round(tolerance) + "）不一致";
            }
        }
        return null;
    }

    /**
     * 去掉 Markdown 代码围栏，再取<b>第一个左大括号到最后一个右大括号</b>之间的内容。
     *
     * <p>照抄 {@code SessionArtifactParser.extractJsonObject} + {@code stripCodeFence}。
     *
     * @param raw 模型原始输出
     * @return 形如 {...} 的串；找不到成对花括号 ⇒ null
     */
    static String extractJsonObject(String raw) {
        String text = stripCodeFence(raw).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 剥掉 ``` 围栏行（```json / ```JSON / ``` 三种都算）；无围栏原样返回。 */
    static String stripCodeFence(String raw) {
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text.replace("```", " ");
        }
        String body = text.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body;
    }

    /** 文本 content block（CC/Anthropic 形状：{@code {"type":"text","text":...}}）。 */
    private static ObjectNode textBlock(String text) {
        ObjectNode block = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /** 原始输出截断（诊断用，避免把整段模型输出塞进错误文案）。 */
    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** 保留两位小数（诊断用）。 */
    private static double round(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }
}
