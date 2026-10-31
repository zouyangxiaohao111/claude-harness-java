package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@code mcp__computer-use__zoom} · 区域放大截图工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:220-244}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:221-243}）</h2>
 * <pre>
 * name: 'zoom',
 * description: 'Take a higher-resolution screenshot of a specific region of the last full-screen screenshot. Use this liberally to inspect small text, button labels, or fine UI details that are hard to read in the downsampled full-screen image. IMPORTANT: Coordinates in subsequent click calls always refer to the full-screen screenshot, never the zoomed image. This tool is read-only for inspecting detail.',
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     region: { type: 'array', items: { type: 'integer' }, minItems: 4, maxItems: 4, description: '(x0, y0, x1, y1): Rectangle to zoom into, in the coordinate space of the most recent full-screen screenshot. x0,y0 = top-left, x1,y1 = bottom-right.' },
 *     save_to_disk: { type: 'boolean', description: 'Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image.' },
 *   },
 *   required: ['region'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:2306-2373} 的 {@code handleZoom}）</h2>
 * <ol>
 *   <li>{@code region} 必须是长度 4 的数组（CCB {@code toolCalls.ts:2314-2319}）；</li>
 *   <li>四个值必须是非负数（CCB {@code toolCalls.ts:2321-2323}）；</li>
 *   <li>{@code x1 > x0} 且 {@code y1 > y0}（CCB {@code toolCalls.ts:2324-2327}）；</li>
 *   <li><b>必须先有全屏截图</b>（CCB {@code toolCalls.ts:2329-2335}：
 *       {@code 'take a screenshot before zooming (region coords are relative to it)'}）；</li>
 *   <li>区域不得超出上次截图的图像尺寸（CCB {@code toolCalls.ts:2336-2341}）；</li>
 *   <li>把<b>图像像素</b>坐标换算成逻辑点（CCB {@code toolCalls.ts:2343-2352}：
 *       {@code ratioX = last.displayWidth / last.width}）⇒ 本仓走
 *       {@link ComputerUseCoordinateScaler}（截图时刻快照，两态）；</li>
 *   <li>返回<b>只有图片</b>、<b>不附带</b> {@code .screenshot} piggyback
 *       （CCB {@code toolCalls.ts:2363-2372} 的注释：“NO {@code .screenshot} piggyback — this is the invariant.”）。</li>
 * </ol>
 *
 * <h2>⚠️ Windows 侧：CCB 的 zoom <b>返回整窗而非区域</b>（本仓照做 + 如实标注）</h2>
 * <p>CCB 的 Windows 截图实现（{@code src/utils/computerUse/platforms/win32.ts:423-430}）：
 * <pre>
 * async captureRegion(x, y, w, h): Promise&lt;ScreenshotResult&gt; {
 *   // When HWND is bound, the window IS the region (matches macOS behavior)
 *   if (boundHwnd) {
 *     const result = await this.captureWindow?.(String(boundHwnd))
 *     if (result) return result
 *   }
 *   return this.captureScreen()
 * }
 * </pre>
 * ⇒ <b>未绑定窗口时</b>：{@code captureRegion} <b>直接回落到整屏截图</b>（忽略 x/y/w/h）；
 * <b>绑定窗口时</b>：返回<b>整窗</b>截图（同样忽略区域）。
 * <br>⇒ 本仓<b>未做窗口绑定</b>（Phase 0 决策）⇒ 按 CCB 的<b>非绑定窗口</b>语义实现：
 * <b>真按 region 从当前屏幕重新截取并放大</b>（对齐 CCB macOS 的
 * {@code platforms/darwin.ts:83-86} 的 {@code captureRegion} 行为 —— 那是 CCB 真正实现
 * 「区域放大」的平台路径）。
 *
 * <h2>区域放大怎么做到</h2>
 * <p>本仓的 {@link ComputerUseScreenCapturer} 抓的是<b>整屏</b>再按
 * {@link ComputerUseApiResize} 缩到 ≤1568 长边（模型看到的是缩放图）。
 * <br>⇒ zoom 的实现：<b>重新按 region 原分辨率抓屏</b>（不缩到 1568）
 * ⇒ 模型看到的是该区域的<b>原始像素</b>（比整屏缩略图更清晰）。
 * <br>⇒ 坐标换算：region 是<b>上次整屏截图的图像像素</b>（与模型读到的图同一空间）
 * ⇒ 用同一张快照的 {@code displayWidth/width} 比例换算到屏幕逻辑点
 * （CCB {@code toolCalls.ts:2343-2352} 同款比例，但本仓统一走
 * {@link ComputerUseCoordinateScaler}，⛔ 不自己实现换算）。
 */
public final class ComputerUseZoomTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseZoomTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__zoom";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:223-224}。 */
    private static final String DESCRIPTION =
            "Take a higher-resolution screenshot of a specific region of the last full-screen screenshot. "
                    + "Use this liberally to inspect small text, button labels, or fine UI details that are hard "
                    + "to read in the downsampled full-screen image. "
                    + "IMPORTANT: Coordinates in subsequent click calls always refer to the full-screen "
                    + "screenshot, never the zoomed image. This tool is read-only for inspecting detail.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:225-242}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "region": {
                  "type": "array",
                  "items": { "type": "integer" },
                  "minItems": 4,
                  "maxItems": 4,
                  "description": "(x0, y0, x1, y1): Rectangle to zoom into, in the coordinate space of the most recent full-screen screenshot. x0,y0 = top-left, x1,y1 = bottom-right."
                },
                "save_to_disk": {
                  "type": "boolean",
                  "description": "Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image."
                }
              },
              "required": ["region"]
            }
            """;

    private final ComputerUseScreenCapturer capturer;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseCoordinateMode coordinateMode;

    /**
     * @param capturer       截屏实现
     * @param geometryStore  截图几何快照存储（提供「上次整屏截图」的换算基准）
     * @param coordinateMode 坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     */
    public ComputerUseZoomTool(ComputerUseScreenCapturer capturer,
                               ComputerUseGeometryStore geometryStore,
                               ComputerUseCoordinateMode coordinateMode) {
        if (capturer == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseZoomTool 依赖不能为空");
        }
        this.capturer = capturer;
        this.geometryStore = geometryStore;
        this.coordinateMode = coordinateMode == null ? ComputerUseCoordinateMode.PIXELS : coordinateMode;
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
            throw new IllegalStateException("[computer-use] zoom inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 纯读（无副作用）⇒ 只读、并发安全。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读工具 · <b>权限自决 Allow（免弹窗）</b>。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use zoom is read-only"),
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
     * 执行区域放大截图。
     *
     * <p>流程：校验 region → 取上次截图快照 → 换算成屏幕矩形 → <b>原分辨率</b>重抓 → 返回图片。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode regionNode = input.path("region");
        if (!regionNode.isArray() || regionNode.size() != 4) {
            return ToolResult.error(call.id(), "region must be an array of length 4: [x0, y0, x1, y1]");
        }
        int[] region = new int[4];
        for (int i = 0; i < 4; i++) {
            JsonNode value = regionNode.get(i);
            if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) {
                return ToolResult.error(call.id(), "region values must be non-negative numbers");
            }
            region[i] = value.asInt();
        }
        if (region[2] <= region[0]) {
            return ToolResult.error(call.id(), "region x1 must be greater than x0");
        }
        if (region[3] <= region[1]) {
            return ToolResult.error(call.id(), "region y1 must be greater than y0");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseScreenshotGeometry lastScreenshot = geometryStore.get(sessionId);
        if (lastScreenshot == null) {
            // CCB toolCalls.ts:2329-2335（错误文案逐字）
            return ToolResult.error(call.id(),
                    "take a screenshot before zooming (region coords are relative to it)");
        }
        if (region[2] > lastScreenshot.width() || region[3] > lastScreenshot.height()) {
            // CCB toolCalls.ts:2336-2341（错误文案逐字）
            return ToolResult.error(call.id(),
                    "region exceeds screenshot bounds (" + lastScreenshot.width() + "×" + lastScreenshot.height() + ")");
        }

        // 图像像素 → 屏幕逻辑点 · 走既有 scaler（截图时刻快照，两态）
        // ⚠️ region 是「上次整屏截图的图像像素」⇒ 与模型读到的图同一空间 ⇒
        //    即便坐标模式是 normalized_0_100，region 仍按图像像素解释（CCB 同款：
        //    region 始终相对 lastScreenshot 的图像坐标系）。
        int[] topLeft = scaleRegionPoint(region[0], region[1], lastScreenshot);
        int[] bottomRight = scaleRegionPoint(region[2], region[3], lastScreenshot);
        int width = bottomRight[0] - topLeft[0];
        int height = bottomRight[1] - topLeft[1];
        if (width <= 0 || height <= 0) {
            return ToolResult.error(call.id(), "region is empty after coordinate scaling");
        }

        ComputerUseCaptureResult capture;
        try {
            capture = capturer.captureRegion(topLeft[0], topLeft[1], width, height);
        } catch (Exception e) {
            log.error("[computer-use] {} 区域截图失败: ({},{}) {}x{} · {}",
                    TOOL_NAME, topLeft[0], topLeft[1], width, height, e.getMessage(), e);
            return ToolResult.error(call.id(), "区域截图失败: " + e.getMessage());
        }

        List<ComputerUseMcpContent> content = List.of(
                ComputerUseMcpContent.image(capture.base64(), capture.mediaType()));

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: region=[{},{},{},{}] → 屏幕 ({},{}) {}x{} · 图像 {}x{} · {} 字节",
                    TOOL_NAME, region[0], region[1], region[2], region[3],
                    topLeft[0], topLeft[1], width, height,
                    capture.imageWidth(), capture.imageHeight(), capture.jpegBytes().length);
        }

        // CCB toolCalls.ts:2364-2372 —— 只返回图片，不带 .screenshot piggyback
        return ToolResult.success(call.id(), new ComputerUseZoomResult(
                content, capture.mediaType(), capture.imageWidth(), capture.imageHeight(),
                capture.jpegBytes().length));
    }

    /**
     * 图像像素坐标 → 屏幕坐标（按快照比例）。
     *
     * <p>CCB {@code toolCalls.ts:2343-2352} 用的是
     * {@code ratioX = last.displayWidth / last.width}（图像像素 → 逻辑点），
     * 且<b>不加</b> origin（CCB 的截图原点恒为 0）。
     * <br>⇒ 本仓走 {@link ComputerUseCoordinateScaler}：它内部用
     * {@code raw * (displayWidth/width) + origin}，比例与 CCB 同款，
     * 且额外支持了 CCB 缺失的<b>虚拟屏幕原点</b>（多屏时 origin ≠ 0）。
     *
     * @param imageX 图像像素 X
     * @param imageY 图像像素 Y
     * @param snapshot 上次截图的几何快照
     * @return {@code int[]{x, y}} 屏幕坐标
     */
    private int[] scaleRegionPoint(int imageX, int imageY, ComputerUseScreenshotGeometry snapshot) {
        try {
            return ComputerUseCoordinateScaler.scaleCoord(imageX, imageY, ComputerUseCoordinateMode.PIXELS, snapshot);
        } catch (ComputerUseGeometryMissingException e) {
            // 快照已在上方判过非空 ⇒ 这里不可能发生；仍 fail loud 而非静默返回 0
            throw new IllegalStateException("[computer-use] zoom 坐标换算失败: " + e.getMessage(), e);
        }
    }

    /**
     * zoom 的结果载荷 · 只有图片 content（CCB {@code toolCalls.ts:2364-2372}）。
     *
     * @param content      MCP content blocks（必有 image 块）
     * @param mediaType    MIME
     * @param imageWidth   放大后图像宽
     * @param imageHeight  放大后图像高
     * @param byteLength   JPEG 字节数
     */
    public record ComputerUseZoomResult(
            List<ComputerUseMcpContent> content,
            String mediaType,
            int imageWidth,
            int imageHeight,
            long byteLength) {

        public ComputerUseZoomResult {
            content = content == null ? List.of() : List.copyOf(content);
        }

        /** 摘要（不含 base64）—— 避免 base64 混进文本回退路径。 */
        @Override
        public String toString() {
            return "ComputerUseZoomResult{mediaType=" + mediaType
                    + ", image=" + imageWidth + "x" + imageHeight
                    + ", bytes=" + byteLength + "}";
        }
    }

    /**
     * 产出 image block（对齐 CCB {@code wrapper.tsx:302-318} 的映射规则）。
     *
     * @param result     工具结果
     * @param toolUseId  工具调用 id
     * @param isError    是否错误
     * @return tool_result 块参数
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result,
                                                          String toolUseId,
                                                          boolean isError) {
        if (result instanceof ToolResult<?> tr
                && tr.data() instanceof ComputerUseZoomResult payload
                && !payload.content().isEmpty()) {
            List<ContentBlockParam> blocks = ComputerUseMcpContent.toApiBlocks(payload.content());
            if (!blocks.isEmpty()) {
                return ToolResultBlockParam.blocksContent(toolUseId, blocks);
            }
        }
        return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
    }
}
