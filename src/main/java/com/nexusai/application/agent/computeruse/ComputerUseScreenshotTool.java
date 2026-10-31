package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@code mcp__computer-use__screenshot} · 截屏工具（对齐 CCB computer-use MCP 的 {@code screenshot}）。
 *
 * <p><b>工具名不可改</b>：CCB {@code src/utils/computerUse/setup.ts:17-21} 说明 ——
 * API 后端靠 {@code mcp__computer-use__*} 这个<b>工具名</b>识别 computer-use，
 * 并据此向 system prompt 注入 CU 可用性提示；换成别的名字就不会触发。
 * 同理 CCB {@code setup.ts:25-32} 把这些工具名放进 {@code allowedTools} 以免弹权限气泡。
 *
 * <p><b>结果形态（PoC 的验收关键）</b>：截图必须以 <b>image content block</b> 送达模型，
 * <b>不能</b>降级成「已保存到本地路径」的文本指针 —— 那是本仓既有缺陷
 * {@code BrowserMcpTool.java:242-248}（其 note 明写「若需模型看该图请走视觉/读文件通道读取，
 * 不要用 base64」）。本工具走
 * {@link Tool#mapToToolResultBlockParam(AgentToolResult, String, boolean)} 产出
 * {@code {type:'image', source:{type:'base64', media_type:'image/jpeg', data:<base64>}}} 块，
 * 与本仓唯一在役的 image block 生产者 {@code ReadFileTool.java:1404-1418} 同款。
 *
 * <p><b>CCB 描述对齐</b>：{@code packages/@ant/computer-use-mcp/src/tools.ts:203-218}。
 * Phase 0 <b>没有</b>应用白名单（{@code request_access} 留到 Phase 1），
 * 故 CCB 描述里「allowlist 为空则报错」「不在白名单的应用会被拒绝」两句被移除 ——
 * 留着等于对模型说谎（模型会以为必须先授权）。其余逐字保留。
 *
 * <p><b>[Phase 1b · A] 视觉能力闸</b>：执行前经 {@link ComputerUseVisionCapability} 判定
 * <b>当前模型是否支持视觉</b>；<b>已确定不支持</b> ⇒ <b>fail loud</b> 返回模型可见的明确错误
 * （⛔ 不发图）。判不出 ⇒ 放行（不把图重新藏起来）。
 *
 * <p><b>[Phase 1e] grounding 降级路径</b>：当视觉闸判定<b>确定不支持</b>视觉
 * <b>且</b>降级开关已打开（{@code nexusai.computer-use.grounding-fallback-enabled=true}
 * ⇒ 生产注入 {@link ComputerUseGroundingService}）时，<b>不再直接报错</b>：
 * 改为把截图交给<b>本仓配置的视觉模型</b>（{@code settings.multimodal_model_name}）做
 * <b>grounding</b>，产出<b>图像像素坐标</b>并以<b>文本</b>回给主模型。
 * <br>⭐ 2026-10-31 起降级路径与主路径<b>统一用 {@code pixels}</b>（模型看到的就是这张缩放图
 * ⇒ 它回的「图上像素」天然是 CCB pixels 制式）⇒ 点击侧<b>不再</b>切百分比态
 * （见 {@link ComputerUseLeftClickTool}）。
 * <br>⚠️ 视觉闸判<b>确定支持</b> ⇒ 仍走<b>CCB 原路径</b>（把图直接送给主模型，优先级更高、
 * 精度更高）；判<b>判不出</b> ⇒ <b>保持现状：放行</b>（宁可让端点报错也不把图藏起来）。
 */
public final class ComputerUseScreenshotTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseScreenshotTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "screenshot");

    /**
     * 工具描述 · 对齐 CCB {@code tools.ts:154-157 + 203-206}。
     *
     * <p>CCB 原文（Windows 分支，{@code caps.screenshotFiltering === 'none'}）：
     * “Take a screenshot of the primary display. On this platform, screenshots are NOT filtered —
     * all open windows are visible. Input actions targeting apps not in the session allowlist are rejected.”
     * <br>＋ “Returns an error if the allowlist is empty. The returned image is what subsequent click
     * coordinates are relative to.”
     *
     * <p>Phase 0 移除的是两处<b>白名单</b>语义（无 {@code request_access} ⇒ 无白名单 ⇒ 留着是说谎）：
     * “Input actions targeting apps not in the session allowlist are rejected.”
     * 与 “Returns an error if the allowlist is empty.”。
     */
    private static final String DESCRIPTION =
            "Take a screenshot of the primary display. On this platform, screenshots are NOT filtered — "
                    + "all open windows are visible. "
                    + "The returned image is what subsequent click coordinates are relative to.";

    /** 输入 schema · 对齐 CCB {@code tools.ts:207-217}（Phase 0 去掉未实现的 save_to_disk）。
     *
     *  <p>[Phase 1e] 新增可选 {@code target}：<b>仅在 grounding 降级路径</b>（主模型确定
     *  不支持视觉）下被消费 —— 它是「要定位的界面元素描述」，供视觉模型做 grounding。
     *  <b>主模型支持视觉时该参数被忽略</b>（不影响 CCB 原路径）。
     *
     *  <p>⭐ 2026-10-31 起：降级路径回的是<b>图像像素坐标</b>（与 {@code pixels} 模式同制式）
     *  ⇒ 描述文案同步改为「pixel coordinates on the screenshot image」。
     */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Optional. Describe the UI element you want located on the screen (e.g. \\"the Save button\\"). Only used when the current model cannot see images: the configured vision model will locate it and return pixel coordinates on the screenshot image. Ignored when the model can see images directly."
                }
              }
            }
            """;

    private final ComputerUseScreenCapturer capturer;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseVisionCapability visionCapability;
    private final ComputerUseGroundingService groundingService;
    /** 护栏② · 定位帧存储（供点击前落点校验比对「首次截图的同一区域」；null ⇒ 不存帧）。 */
    private final ComputerUseGroundingStore groundingStore;

    /**
     * PoC / 单测构造器 · <b>不做视觉闸</b>（无模型上下文可判）。
     *
     * <p>生产路径见
     * {@link #ComputerUseScreenshotTool(ComputerUseScreenCapturer, ComputerUseGeometryStore, ComputerUseVisionCapability, ComputerUseGroundingService)}。
     *
     * @param capturer      截屏实现（JNA / 单测假实现）
     * @param geometryStore 截图几何快照存储（供点击工具按「截图时刻」换算）
     */
    public ComputerUseScreenshotTool(ComputerUseScreenCapturer capturer,
                                     ComputerUseGeometryStore geometryStore) {
        this(capturer, geometryStore, null, null);
    }

    /**
     * 生产构造器 · <b>带视觉能力闸</b>。
     *
     * @param capturer         截屏实现（JNA）
     * @param geometryStore    截图几何快照存储（供点击工具按「截图时刻」换算）
     * @param visionCapability 视觉能力判定（可 null ⇒ 不做闸；生产由
     *                         {@code ComputerUseToolConfig} 注入 DB 支撑实现）
     */
    public ComputerUseScreenshotTool(ComputerUseScreenCapturer capturer,
                                     ComputerUseGeometryStore geometryStore,
                                     ComputerUseVisionCapability visionCapability) {
        this(capturer, geometryStore, visionCapability, null);
    }

    /**
     * 生产构造器 · <b>带视觉能力闸 + grounding 降级服务</b>（Phase 1e）。
     *
     * @param capturer         截屏实现（JNA）
     * @param geometryStore    截图几何快照存储（供点击工具按「截图时刻」换算）
     * @param visionCapability 视觉能力判定（可 null ⇒ 不做闸）
     * @param groundingService grounding 降级服务（<b>null ⇒ 降级路径关闭</b> ⇒ 视觉闸判
     *                         「确定不支持」时维持 fail loud 报错；非 null ⇒ 走 grounding）
     */
    public ComputerUseScreenshotTool(ComputerUseScreenCapturer capturer,
                                     ComputerUseGeometryStore geometryStore,
                                     ComputerUseVisionCapability visionCapability,
                                     ComputerUseGroundingService groundingService) {
        this(capturer, geometryStore, visionCapability, groundingService, null);
    }

    /**
     * 生产构造器 · <b>带视觉能力闸 + grounding 降级服务 + 定位帧存储</b>（护栏②）。
     *
     * @param capturer         截屏器（JNA）
     * @param geometryStore    几何快照存储（供点击工具按「截图时刻」换算）
     * @param visionCapability 视觉能力判定（可 null ⇒ 不做闸）
     * @param groundingService grounding 降级服务（<b>null ⇒ 降级路径关闭</b> ⇒ 视觉闸判
     *                         「确定不支持」时维持 fail loud 报错；非 null ⇒ 走 grounding）
     * @param groundingStore   定位帧存储（null ⇒ 不存帧 ⇒ 点击前落点校验跳过）
     */
    public ComputerUseScreenshotTool(ComputerUseScreenCapturer capturer,
                                     ComputerUseGeometryStore geometryStore,
                                     ComputerUseVisionCapability visionCapability,
                                     ComputerUseGroundingService groundingService,
                                     ComputerUseGroundingStore groundingStore) {
        if (capturer == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseScreenshotTool 依赖不能为空");
        }
        this.capturer = capturer;
        this.geometryStore = geometryStore;
        this.visionCapability = visionCapability;
        this.groundingService = groundingService;
        this.groundingStore = groundingStore;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        try {
            return JSON.readTree(INPUT_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException("[computer-use] screenshot inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只读 · 截屏不改系统状态 ⇒ 可并发（对齐 BrowserMcpTool 的读类工具先例）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读工具自决放行（对齐 {@code BrowserMcpTool} 读类工具先例）。 */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use screenshot is read-only"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行截屏。
     *
     * <p>产出 MCP content block（{@code {type:'image', data, mimeType}}）并把
     * <b>截图时刻</b>的几何快照写入 {@link ComputerUseGeometryStore}（供点击工具换算）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        // ── [Phase 1b · A] 视觉能力闸 · 主模型不支持视觉 ⇒ fail loud（⛔ 不发图）──
        //   WHY：CU 截图经 role=user + image_url 送达（OpenAiSdkProvider.toSdkImageCarrierMessage）
        //   ⇒ 不支持视觉的端点（如 deepseek-chat）会 400。改前是静默丢图 ⇒ 同样不可接受。
        //
        // ── [Phase 1e] grounding 降级路径 ──
        //   视觉闸判「确定不支持」且降级服务已接线 ⇒ 不报错，改走 grounding：
        //   截图交给本仓视觉模型定位目标 ⇒ 回【归一化百分比坐标】给主模型。
        //   判「确定支持」 ⇒ 仍走下方 CCB 原路径（把图直接送给主模型，精度更高）。
        //   判「判不出」   ⇒ 保持现状：放行（宁可端点报错也不把图藏起来）。
        ComputerUseVisionCapability.Verdict verdict =
                ComputerUseVisionCapability.verdictOf(visionCapability, ctx);
        boolean visionUnsupported = verdict != null && !verdict.supported() && verdict.determinate();
        if (visionUnsupported && groundingService != null) {
            return executeGrounding(call, ctx);
        }
        String rejection = ComputerUseVisionCapability.rejectionIfUnsupported(visionCapability, ctx);
        if (rejection != null) {
            return ToolResult.error(call.id(), rejection);
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseCaptureResult capture;
        try {
            capture = capturer.capture();
        } catch (Exception e) {
            log.error("[computer-use] 截屏失败: {}", e.getMessage(), e);
            return ToolResult.error(call.id(), "截屏失败: " + e.getMessage());
        }

        ComputerUseScreenshotGeometry geometry = capture.geometry();
        geometryStore.put(sessionId, geometry);

        List<ComputerUseMcpContent> content = List.of(
                ComputerUseMcpContent.image(capture.base64(), capture.mediaType()));

        ComputerUseScreenshotResult payload = new ComputerUseScreenshotResult(
                content,
                capture.mediaType(),
                capture.imageWidth(),
                capture.imageHeight(),
                capture.displayWidth(),
                capture.displayHeight(),
                capture.originX(),
                capture.originY(),
                capture.jpegBytes().length,
                capture.scaleFactor());

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 图像 {}x{} · 显示 {}x{} · origin ({},{}) · {} 字节 · session={}",
                    TOOL_NAME, capture.imageWidth(), capture.imageHeight(),
                    capture.displayWidth(), capture.displayHeight(),
                    capture.originX(), capture.originY(),
                    capture.jpegBytes().length, sessionId);
        }

        return ToolResult.success(call.id(), payload);
    }

    /**
     * [Phase 1e] grounding 降级路径 · 截图 → 视觉模型定位 → <b>归一化百分比坐标</b>回给主模型。
     *
     * <p><b>流程</b>：
     * <ol>
     *   <li>读入参 {@code target}（要定位的界面元素描述）；<b>缺省 ⇒ fail loud</b>
     *       （没有目标就没有可定位的东西，绝不返回「随便一个坐标」）；</li>
     *   <li>截屏 + <b>把几何快照写入 store</b>（点击侧按同一份快照换算百分比 ⇒ 不漂移）；</li>
     *   <li>{@link ComputerUseGroundingService#ground} ⇒ 成功则给文本结果，
     *       失败则把<b>明确错误文案</b>原样回给模型（fail loud）。</li>
     * </ol>
     *
     * @param call 工具调用
     * @param ctx  工具上下文
     * @return 文本结果（成功）或错误结果（失败）
     */
    private AgentToolResult<?> executeGrounding(ToolUseBlock call, ToolUseContext ctx) {
        String target = readTarget(call.input());
        if (target == null) {
            return ToolResult.error(call.id(),
                    "当前模型不支持图片输入，无法直接看到屏幕截图。"
                            + "请在截图工具参数里带上 target（想要定位的界面元素描述，例如「保存按钮」），"
                            + "系统会用已配置的视觉模型定位它并返回归一化（百分比）坐标。");
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseCaptureResult capture;
        try {
            capture = capturer.capture();
        } catch (Exception e) {
            log.error("[computer-use] grounding 降级路径截屏失败: {}", e.getMessage(), e);
            return ToolResult.error(call.id(), "截屏失败: " + e.getMessage());
        }
        ComputerUseScreenshotGeometry geometry = capture.geometry();
        geometryStore.put(sessionId, geometry);

        ComputerUseGroundingService.GroundingOutcome outcome =
                groundingService.ground(capture, target, ctx);
        if (!outcome.found()) {
            log.warn("[computer-use] grounding 降级失败: status={} target={} detail={}",
                    outcome.status(), target, outcome.detail());
            return ToolResult.error(call.id(), outcome.errorMessage());
        }

        // 护栏② · 留下「首次截图」这一帧（点击前落点校验要比对同一区域）
        if (groundingStore != null) {
            groundingStore.put(sessionId, new ComputerUseGroundingFrame(
                    capture, outcome.xPixels(), outcome.yPixels(), outcome.target()));
        }

        String text = renderGroundingText(outcome, geometry, capture.imageWidth(), capture.imageHeight());
        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} grounding 命中: target={} → 图像像素 ({},{}) · 图像 {}x{} "
                            + "· 快照显示 {}x{} · session={}",
                    TOOL_NAME, outcome.target(), outcome.xPixels(), outcome.yPixels(),
                    capture.imageWidth(), capture.imageHeight(),
                    geometry.displayWidth(), geometry.displayHeight(), sessionId);
        }
        return ToolResult.success(call.id(), text);
    }

    /**
     * grounding 结果文本 · <b>含目标描述 + 图像像素坐标 + 屏幕参考坐标 + 误差预期提示</b>。
     *
     * <p><b>⭐ 坐标语义</b>：2026-10-31 起降级路径与主路径<b>统一用 {@code pixels}</b> ⇒
     * 回给主模型的必须是<b>图像像素坐标</b>（模型照抄进 {@code left_click} 的 coordinate）
     * ⇒ 换算由既有 {@link ComputerUseCoordinateScaler} 的 pixels 分支负责
     * （本方法只<b>附带</b>显示一个屏幕参考值，不参与换算）。
     *
     * <p><b>误差预期的诚实口径</b>：本仓<b>无法</b>预先知道视觉模型的定位误差
     * ⇒ 明确告知「这是二手定位、不是主模型直接读图」+ 建议点击后复核。
     *
     * @param outcome      grounding 结果
     * @param geometry     截图时刻的几何快照
     * @param imageWidth   本次截图的图像宽
     * @param imageHeight  本次截图的图像高
     * @return 文本结果
     */
    private static String renderGroundingText(ComputerUseGroundingService.GroundingOutcome outcome,
                                               ComputerUseScreenshotGeometry geometry,
                                               int imageWidth, int imageHeight) {
        double x = outcome.xPixels();
        double y = outcome.yPixels();
        int[] screen = ComputerUseCoordinateScaler.scaleCoord(
                x, y, ComputerUseCoordinateMode.PIXELS, geometry);
        return "已在屏幕上定位到「" + outcome.target() + "」。\n"
                + "截图图像尺寸：" + imageWidth + " × " + imageHeight + " 像素\n"
                + "图像像素坐标（在这张截图上）：x = " + trimNumber(x) + ", y = " + trimNumber(y) + "\n"
                + "参考屏幕像素（按本次截图时的显示几何 " + geometry.displayWidth() + "x" + geometry.displayHeight()
                + " 换算）：约 (" + screen[0] + ", " + screen[1] + ")\n"
                + "⚠️ 这是视觉模型看图二次定位的结果，不是当前模型直接读图得到的坐标，存在偏差。\n"
                + "请用上面的【图像像素坐标】调用 left_click（coordinate 传 [x, y]），点击后再截一次图确认结果。";
    }

    /** 去掉多余的尾零（12.50 → 12.5；12.00 → 12）。 */
    private static String trimNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** 读入参 {@code target}（空白 ⇒ null）。 */
    private static String readTarget(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        JsonNode node = input.get("target");
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 产出 tool_result 的 image content block · 对齐 CCB {@code wrapper.tsx:302-318} 的
     * 「MCP content → API block」映射（本工具为原生 Tool，不经 McpResultTransformer，
     * 但映射规则与目标形状逐字一致）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result,
                                                          String toolUseId,
                                                          boolean isError) {
        if (result instanceof ToolResult<?> tr
                && tr.data() instanceof ComputerUseScreenshotResult payload
                && !payload.content().isEmpty()) {
            List<ContentBlockParam> blocks = ComputerUseMcpContent.toApiBlocks(payload.content());
            if (!blocks.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[computer-use] {} 产出 image block: toolUseId={} 块数={} mediaType={} base64Len={}",
                            TOOL_NAME, toolUseId, blocks.size(), payload.mediaType(),
                            payload.content().get(0).data() == null ? 0 : payload.content().get(0).data().length());
                }
                return ToolResultBlockParam.blocksContent(toolUseId, blocks);
            }
        }
        return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
    }
}
