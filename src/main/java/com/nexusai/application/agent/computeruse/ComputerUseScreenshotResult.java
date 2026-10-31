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
        double scaleFactor) {

    public ComputerUseScreenshotResult {
        content = content == null ? List.of() : List.copyOf(content);
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
