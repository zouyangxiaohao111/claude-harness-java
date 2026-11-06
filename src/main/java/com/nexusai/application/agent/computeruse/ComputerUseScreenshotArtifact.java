package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 观察的<b>截图产物</b>（对齐 dsh 的 Artifact 形态）。
 *
 * <h2>WHY 存文件而不是 inline image</h2>
 * <p>dsh 的 {@code computer_observe} 把截图写成<b>文件</b>，返回
 * {@code {path, filename, mimeType, bytes, width, height, pixelScale}}
 * （dsh {@code src/tools.ts:96-113} {@code artifactSchema}），
 * 模型需要视觉分析时<b>把路径交给 vision 工具</b>（dsh 的 vision-tools Skill）。
 * <br>本仓对齐同款形态：
 * <ul>
 *   <li>大图不进主模型上下文（98KB 的图 ≈ 2.5 万 token，塞进对话会挤爆预算）；</li>
 *   <li>本仓的视觉通道是 {@code vision_analyze}，它<b>已支持 path 入参</b>
 *       （{@code VisionAnalyzeTool.java:424} 读本地文件）⇒ 链路直接可用；</li>
 *   <li>截图<b>不是交付物</b> ⇒ 落在 {@code screenshots/} 而非 {@code deliverables/}
 *       （用户 2026-11-03 裁定），不进产物面板。</li>
 * </ul>
 *
 * <h2>WHY 像素坐标必须配 pixelScale</h2>
 * <p>截图按窗口 backing scale 拍（Retina 上通常 2 倍），且本仓截图还会<b>预缩放到 ≤1568</b>
 * ⇒ 模型从图上读到的坐标要<b>除以 pixelScale</b> 才是逻辑点坐标
 * （dsh {@code service.ts:70-83} {@code screenshotPixelScale}）。
 * <br>本仓口径 = {@code imageWidth / logicalWidth}，与既有 {@code ComputerUseScreenshotTool}
 * <b>逐字一致</b> ⇒ 该因子同时包含了 backing scale 与预缩放两段，模型只需除一次。
 */
final class ComputerUseScreenshotArtifact {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseScreenshotArtifact.class);

    /** 截图目录名（与 {@code SessionArtifactService.SCREENSHOTS_DIR_NAME} 同名，避免跨包依赖）。 */
    static final String SCREENSHOTS_DIR_NAME = "screenshots";

    private ComputerUseScreenshotArtifact() {
        throw new AssertionError("utility class");
    }

    /**
     * 截图并落盘 · 返回 artifact 描述节点。
     *
     * <p><b>权限语义（对齐 dsh {@code screenshot: none|optional|required}）</b>：
     * <ul>
     *   <li>{@code none} ⇒ 不截，返回 {@code null}；</li>
     *   <li>{@code optional} ⇒ 能截就截，截不了（无权限 / 失败）⇒ <b>不报错</b>，
     *       返回 {@code null} 并把原因写进 {@code permissions.screenRecording}；</li>
     *   <li>{@code required} ⇒ 截不了 ⇒ <b>显式失败</b>（抛异常，由调用方转成工具错误）。</li>
     * </ul>
     *
     * @param capturer      截图器（null ⇒ 不支持截图）
     * @param tcc           权限检查（null ⇒ 不检查）
     * @param sessionId     会话 id
     * @param observationId 观察 id（做文件名）
     * @param mode          {@code none} / {@code optional} / {@code required}
     * @param permissions   权限节点（把失败原因写回去）
     * @return artifact 描述；未截图 ⇒ {@code null}
     * @throws IllegalStateException {@code required} 且截不了
     */
    static ObjectNode capture(ComputerUseScreenCapturer capturer,
                              MacOsTccChecker tcc,
                              String sessionId,
                              String observationId,
                              String mode,
                              ObjectNode permissions) {
        if ("none".equals(mode)) {
            return null;
        }
        if (capturer == null) {
            // ⛔ required ⇒ 必须显式失败（没有截图器 = 一定截不到）
            if ("required".equals(mode)) {
                throw new IllegalStateException(
                        "[computer-use] screenshot=required 但本平台不支持截图（无截图器）");
            }
            return null;
        }
        // ⚠️ 先查权限：macOS 无屏幕录制授权时 screencapture 拍到的是**壁纸**（不是黑屏）
        //   ⇒ 必须显式判权限，否则模型拿到一张「看起来正常但内容是壁纸」的图。
        if (tcc != null && !tcc.screenRecordingGranted()) {
            permissions.put("screenRecording", "denied");
            if ("required".equals(mode)) {
                throw new IllegalStateException(
                        "[computer-use] screenshot=required 但屏幕录制权限未授权（macOS TCC Screen Recording）");
            }
            if (log.isInfoEnabled()) {
                log.info("[computer-use] 截图跳过（screenRecording 未授权，mode={}）", mode);
            }
            return null;
        }
        ComputerUseCaptureResult capture;
        try {
            capture = capturer.capture();
        } catch (Exception e) {
            if ("required".equals(mode)) {
                throw new IllegalStateException("[computer-use] 截图失败（screenshot=required）: " + e.getMessage(), e);
            }
            log.warn("[computer-use] 截图失败（mode={}，非致命）: {}", mode, e.getMessage());
            return null;
        }
        Path dir = screenshotDir(sessionId);
        String fileName = "observe-" + safeName(observationId) + ".jpg";
        Path file = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            Files.write(file, capture.jpegBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            if ("required".equals(mode)) {
                throw new IllegalStateException("[computer-use] 截图落盘失败（screenshot=required）: " + e.getMessage(), e);
            }
            log.warn("[computer-use] 截图落盘失败（mode={}，非致命）: {}", mode, e.getMessage());
            return null;
        }
        // pixelScale = 这张图的像素 / 逻辑点（模型从图上读坐标要除以它）
        double pixelScale = capture.logicalWidth() > 0
                ? (double) capture.imageWidth() / capture.logicalWidth()
                : 0d;
        ObjectNode node = JSON.createObjectNode();
        node.put("path", file.toAbsolutePath().toString());
        node.put("filename", fileName);
        node.put("mimeType", capture.mediaType());
        node.put("bytes", capture.jpegBytes().length);
        node.put("width", capture.imageWidth());
        node.put("height", capture.imageHeight());
        node.put("pixelScale", pixelScale);
        node.put("description", "Screenshot of the observed app. Coordinates read off this image are image pixels; "
                + "divide them by pixelScale to get window points.");
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 截图产物已落盘: path={} 字节={} {}x{} pixelScale={}",
                    file, capture.jpegBytes().length, capture.imageWidth(), capture.imageHeight(), pixelScale);
        }
        return node;
    }

    /** 截图目录：{@code {configHome}/artifacts/{sessionId}/screenshots}。 */
    static Path screenshotDir(String sessionId) {
        return com.nexusai.application.session.SessionArtifactService.screenshotsDir(sessionId);
    }

    /** 文件名安全化（observationId 是 UUID，理论上无特殊字符；仍做防御）。 */
    private static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
