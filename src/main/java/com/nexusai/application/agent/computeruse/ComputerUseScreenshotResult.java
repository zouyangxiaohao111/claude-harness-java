package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * 截图工具的结果载荷 · 携带 MCP content blocks + 几何快照。
 *
 * <p><b>为什么要单独一个载荷类型</b>：本仓既有 image block 生产者
 * {@code ReadFileTool} 走的是 {@code ToolResult.image(...)} 工厂，其 data 是 JsonNode 且带
 * {@code read_file_output_type} discriminator（{@code ToolResult.java:81}）—— 那是
 * <b>读文件</b>的输出形状。computer-use 的截图是<b>屏幕捕获</b>，语义不同，
 * 故独立建模，避免把 CU 截图伪装成 read_file 的图片输出。
 *
 * <p><b>toString 不含 base64</b>：{@code ToolResult.renderToolResultPayloadText} 的兜底分支会
 * {@code String.valueOf(data)}；若 toString 带 base64，截图会以文本形式混进 tool_result 载荷
 * （纯噪音 + 巨 token）。这里显式覆写 toString 只留摘要。
 *
 * @param content        MCP content blocks（必有 image 块）
 * @param mediaType      MIME（{@code image/jpeg}）
 * @param imageWidth     模型所见图像宽（像素）
 * @param imageHeight    模型所见图像高（像素）
 * @param displayWidth   截图时刻显示宽（物理像素 = 点击坐标空间宽）
 * @param displayHeight  截图时刻显示高
 * @param originX        虚拟屏幕原点 X
 * @param originY        虚拟屏幕原点 Y
 * @param byteLength     JPEG 字节数
 * @param scaleFactor    显示缩放比
 */
public record ComputerUseScreenshotResult(
        List<ComputerUseMcpContent> content,
        String mediaType,
        int imageWidth,
        int imageHeight,
        int displayWidth,
        int displayHeight,
        int originX,
        int originY,
        long byteLength,
        double scaleFactor,
        double pixelScale) {

    public ComputerUseScreenshotResult {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * 兼容构造（无 {@code pixelScale}）· 由 {@code pixelScale} 反推。
     *
     * @param content      图片内容
     * @param mediaType    MIME
     * @param imageWidth   图像宽
     * @param imageHeight  图像高
     * @param displayWidth 显示宽（物理像素）
     * @param displayHeight 显示高
     * @param originX      虚拟屏幕原点 X
     * @param originY      虚拟屏幕原点 Y
     * @param byteLength   字节数
     * @param scaleFactor  显示缩放比（像素/点）
     */
    public ComputerUseScreenshotResult(List<ComputerUseMcpContent> content,
                                       String mediaType,
                                       int imageWidth,
                                       int imageHeight,
                                       int displayWidth,
                                       int displayHeight,
                                       int originX,
                                       int originY,
                                       long byteLength,
                                       double scaleFactor) {
        this(content, mediaType, imageWidth, imageHeight, displayWidth, displayHeight,
                originX, originY, byteLength, scaleFactor, 0d);
    }

    /**
     * <b>截图像素 → 窗口点</b> 的换算因子（对齐 dsh {@code ComputerAppSummary} / 截图 Artifact
     * 的 {@code pixelScale}）。
     *
     * <h2>WHY 需要它</h2>
     * <p>macOS 的截图按<b>窗口的 backing scale</b>拍（Retina 上通常是 2 倍），
     * 而元素 frame、窗口坐标、点击坐标都 speaking <b>点</b>。
     * ⇒ 模型从图上读到的像素坐标，<b>必须除以这个因子</b>才是点击坐标，
     * 否则会落在两倍偏移处（dsh {@code service.ts:98-103} 注释原文：
     * “A coordinate read off the screenshot therefore lands at twice its intended offset
     * unless it is divided by {@code pixelScale}”）。
     *
     * <h2>与 {@code scaleFactor} 的区别（别混）</h2>
     * <ul>
     *   <li>{@code scaleFactor} = <b>显示</b>缩放比（物理像素/点，Retina 2）；</li>
     *   <li>{@code pixelScale} = <b>这张图</b>的像素/点 ——
     *       还要叠加本仓的「缩到 ≤1568」步骤 ⇒ 它<b>不等于</b> scaleFactor。</li>
     * </ul>
     *
     * @return 换算因子（图上像素 ÷ 该值 = 点）；无法确定时返回 0（表示未知）
     */
    public double pixelScaleOrZero() {
        return pixelScale;
    }

    /** 几何快照 · 供点击工具的 {@code scaleCoord} 使用。 */
    public ComputerUseScreenshotGeometry geometry() {
        return new ComputerUseScreenshotGeometry(
                imageWidth, imageHeight, displayWidth, displayHeight, originX, originY);
    }

    /** 摘要（不含 base64）—— 供日志 / 文本回退路径使用。 */
    @Override
    public String toString() {
        return "ComputerUseScreenshotResult{mediaType=" + mediaType
                + ", image=" + imageWidth + "x" + imageHeight
                + ", display=" + displayWidth + "x" + displayHeight
                + ", origin=" + originX + "," + originY
                + ", bytes=" + byteLength
                + ", scaleFactor=" + scaleFactor + "}";
    }
}
