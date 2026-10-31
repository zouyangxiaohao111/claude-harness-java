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
 * （{@code settings.multimodal_model_name}）定位目标，产出<b>归一化（百分比）坐标</b>。
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
 * <h2>⛔ 五种失败一律 fail loud（绝不猜坐标）</h2>
 * <ol>
 *   <li>{@link Status#NOT_FOUND} —— 视觉模型说「图里没有这个目标」；</li>
 *   <li>{@link Status#PARSE_FAILED} —— 模型输出不是可解析的 JSON / 缺 found / 缺坐标；</li>
 *   <li>{@link Status#OUT_OF_RANGE} —— 坐标不在 0–100（百分比越界）；</li>
 *   <li>{@link Status#MODEL_UNAVAILABLE} —— 视觉模型未配置 / provider 不可用；</li>
 *   <li>{@link Status#CALL_FAILED} —— 旁路调用抛异常。</li>
 * </ol>
 * <p>五种都返回<b>明确的中文错误文案</b>（{@link GroundingOutcome#errorMessage()}），
 * <b>绝不</b>返回一个「大概是」的坐标。
 *
 * <h2>坐标一律归一化（百分比）</h2>
 * <p>主模型看不到图 ⇒ 它没有像素参照 ⇒ 降级路径只走
 * {@code normalized_0_100}（与截图缩放无关）。消费方（截图工具）负责把它换算成
 * 屏幕像素或原样交给模型。
 *
 * <p><b>与 CCB 的差异（如实标注）</b>：CCB 无此服务、无此 prompt、无此 JSON 契约。
 */
public final class ComputerUseGroundingService {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseGroundingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记账标签（usage-gateway 侧信道来源）。 */
    public static final String USAGE_LABEL = "computer_use_grounding";

    /**
     * grounding prompt 模板 · <b>本仓新增</b>（CCB 无对应；{@code vision_analyze} 也没有）。
     *
     * <p><b>WHY 要求结构化 JSON</b>：下游要做的是「拿到坐标就点」⇒ 必须有可机器解析的
     * 契约；自由文本 ⇒ 无法校验 ⇒ 只能靠猜（本仓明确禁止）。
     *
     * <p><b>百分比坐标</b>：与截图缩放无关（截图可能被缩到 ≤1568）⇒ 百分比是唯一
     * 在「图像空间」与「屏幕空间」之间无歧义的中间表示。
     */
    public static final String PROMPT_TEMPLATE = """
            你是一个屏幕坐标定位助手。给定一张屏幕截图和一个目标描述，请找出目标在图中的位置。

            输出要求（必须严格遵守）：
            1. 只输出一个 JSON 对象，不要输出任何解释文字，不要使用 Markdown 代码块。
            2. JSON 的形状固定为：{"found": true, "x": 0, "y": 0, "target": "目标描述"}
            3. x 与 y 是目标【中心点】的位置，单位是百分比：
               x = 目标中心横坐标占整张图片宽度的百分比（0 到 100），y = 目标中心纵坐标占整张图片高度的百分比（0 到 100）。
            4. 如果截图里找不到目标，只输出：{"found": false, "x": 0, "y": 0, "target": "目标描述"}
               此时不要猜测坐标。
            5. x 与 y 必须是 0 到 100 之间的数字（可以带小数），不要输出负数，也不要输出大于 100 的数。
            6. target 字段回填你实际找到的目标名称（找不到时回填原目标描述）。

            目标描述：%s
            """;

    private final ModelConfigResolver modelConfigResolver;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmSideCallClient sideCallClient;

    /**
     * @param modelConfigResolver 多模态档位模型名 + ProviderConfig 解析（{@code settings.multimodal_model_name}）
     * @param llmProviderFactory  provider 工厂（按 providerType 路由）
     * @param sideCallClient      旁路单发客户端（调用 + 记账）
     */
    public ComputerUseGroundingService(ModelConfigResolver modelConfigResolver,
                                       LlmProviderFactory llmProviderFactory,
                                       LlmSideCallClient sideCallClient) {
        this.modelConfigResolver = modelConfigResolver;
        this.llmProviderFactory = llmProviderFactory;
        this.sideCallClient = sideCallClient;
    }

    /**
     * grounding 结果状态。
     */
    public enum Status {
        /** 找到目标且坐标合法。 */
        FOUND,
        /** 视觉模型明确说图里没有这个目标。 */
        NOT_FOUND,
        /** 模型输出无法解析成约定的 JSON 形状。 */
        PARSE_FAILED,
        /** 坐标越界（不在 0–100）。 */
        OUT_OF_RANGE,
        /** 视觉模型不可用（未配置 / provider 解析失败）。 */
        MODEL_UNAVAILABLE,
        /** 旁路调用抛异常。 */
        CALL_FAILED
    }

    /**
     * grounding 结果。
     *
     * @param status    状态
     * @param xPercent  目标中心横坐标百分比（仅 {@link Status#FOUND} 有值）
     * @param yPercent  目标中心纵坐标百分比（仅 {@link Status#FOUND} 有值）
     * @param target    视觉模型回填的目标描述（可空）
     * @param detail    失败细节 / 原始输出（诊断用，可空）
     */
    public record GroundingOutcome(Status status,
                                   Double xPercent,
                                   Double yPercent,
                                   String target,
                                   String detail) {

        /** @return 是否定位成功 */
        public boolean found() {
            return status == Status.FOUND;
        }

        /**
         * 用户 / 模型可见的错误文案（成功时返回 null）。
         *
         * <p>⛔ 五种失败各有<b>明确且互不相同</b>的文案 ⇒ 模型能据此自纠（换描述 / 换模型 /
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
                        "视觉模型在当前屏幕上没有找到" + t + "。"
                                + "请换一种描述（例如加上窗口名、按钮文字），或确认目标确实在屏幕上。";
                case PARSE_FAILED ->
                        "视觉模型返回的定位结果无法解析成约定的 JSON 坐标格式"
                                + (detail == null || detail.isBlank() ? "" : "（原始输出：" + detail + "）")
                                + "。已中止本次定位，不会返回猜测的坐标，请重试。";
                case OUT_OF_RANGE ->
                        "视觉模型返回的坐标越界"
                                + (detail == null || detail.isBlank() ? "" : "（" + detail + "）")
                                + "，百分比坐标必须落在 0 到 100 之间。已中止本次定位，不会返回猜测的坐标，请重试。";
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
     * 执行一次 grounding（截图 + 目标描述 → 百分比坐标）。
     *
     * @param capture 截图结果（含 base64 + 媒体类型）
     * @param target  目标描述（用户/模型给的自然语言，如「保存按钮」）
     * @param ctx     工具上下文（归属 + 记费用；可 null ⇒ standalone 归属）
     * @return 结果（永不为 null）
     */
    public GroundingOutcome ground(ComputerUseCaptureResult capture, String target, ToolUseContext ctx) {
        if (capture == null) {
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target, "没有可用的截图");
        }
        if (target == null || target.isBlank()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, target, "缺少目标描述");
        }

        // ── 1. 视觉模型名（fail loud，不静默回落）──
        String modelName = modelConfigResolver == null ? null : modelConfigResolver.resolveMultimodalModelName();
        if (modelName == null || modelName.isBlank()) {
            log.warn("[computer-use] grounding: 视觉模型未配置（settings.multimodalModelName 缺失）⇒ fail loud");
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target,
                    "未配置「多模态档位模型」（settings.multimodal_model_name）");
        }

        // ── 2. ProviderConfig 解析 ──
        ModelConfigResolver.ResolvedModel resolved;
        try {
            resolved = modelConfigResolver.resolve(modelName);
        } catch (Exception e) {
            log.warn("[computer-use] grounding: provider 解析异常 model={} err={}", modelName, e.toString());
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target,
                    "解析视觉模型 '" + modelName + "' 失败：" + e.getMessage());
        }
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("[computer-use] grounding: provider 不可用 model={}", modelName);
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target,
                    "视觉模型 '" + modelName + "' 没有可用的 provider / API Key");
        }
        if (llmProviderFactory == null) {
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target, "provider 工厂未装配");
        }
        LlmProvider provider = llmProviderFactory.getProvider(resolved.config(), resolved.providerType());
        if (provider == null) {
            log.warn("[computer-use] grounding: provider 工厂路由失败 model={} type={}",
                    modelName, resolved.providerType());
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target,
                    "视觉模型 provider 路由失败：" + modelName);
        }
        if (sideCallClient == null) {
            return new GroundingOutcome(Status.MODEL_UNAVAILABLE, null, null, target, "旁路调用客户端未装配");
        }

        // ── 3. 组装 user 消息（text + image）→ 旁路单发 ──
        String prompt = PROMPT_TEMPLATE.formatted(target.trim());
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
            return new GroundingOutcome(Status.CALL_FAILED, null, null, target, e.getMessage());
        }

        if (raw == null || raw.isBlank()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, target, "（空输出）");
        }

        // ── 4. 宽松 JSON 解析 + 严格校验 ──
        return parse(raw, target);
    }

    /**
     * 宽松 JSON 解析（剥围栏 + 取花括号）· 照抄
     * {@code com.nexusai.application.session.SessionArtifactParser} 的手法。
     *
     * @param raw    视觉模型原始输出
     * @param target 目标描述（失败时回显）
     * @return 结果
     */
    static GroundingOutcome parse(String raw, String target) {
        String json = extractJsonObject(raw);
        if (json == null) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, target, truncate(raw));
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, target, truncate(raw));
        }
        if (root == null || !root.isObject()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, target, truncate(raw));
        }
        JsonNode foundNode = root.get("found");
        if (foundNode == null || !foundNode.isBoolean()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, target, truncate(raw));
        }
        String resolvedTarget = root.path("target").isTextual() ? root.path("target").asText() : target;
        if (!foundNode.asBoolean()) {
            return new GroundingOutcome(Status.NOT_FOUND, null, null, resolvedTarget, null);
        }
        JsonNode xNode = root.get("x");
        JsonNode yNode = root.get("y");
        if (xNode == null || yNode == null || !xNode.isNumber() || !yNode.isNumber()) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, resolvedTarget, truncate(raw));
        }
        double x = xNode.asDouble();
        double y = yNode.asDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return new GroundingOutcome(Status.PARSE_FAILED, null, null, resolvedTarget, truncate(raw));
        }
        if (x < 0 || x > 100 || y < 0 || y > 100) {
            return new GroundingOutcome(Status.OUT_OF_RANGE, null, null, resolvedTarget,
                    "x=" + x + ", y=" + y);
        }
        return new GroundingOutcome(Status.FOUND, x, y, resolvedTarget, null);
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
}
