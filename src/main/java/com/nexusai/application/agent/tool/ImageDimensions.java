package com.nexusai.application.agent.tool;

/**
 * 图片尺寸元数据 · 对齐 CC {@code ImageDimensions} (FileReadTool.ts:276-295,
 * 全字段 optional): {@code { originalWidth?, originalHeight?, displayWidth?, displayHeight? }}.
 *
 * <p><b>[B 组对齐 2026-08-04]</b>: ReadFileTool 图片分支返回尺寸供坐标映射 (CC describe
 * "Image dimension info for coordinate mapping"). original 尺寸经 {@link javax.imageio.ImageIO}
 * 读 header (JDK 内置, 无 pom 依赖)。
 *
 * <p><b>[rv-b-r1 修正]</b>: displayWidth/displayHeight 可非 null —— {@link #resized} 经
 * {@code ImageResizer.maybeResizeAndDownsampleImageBuffer} 真实填充（resize 后 display != original），
 * 供 {@code createImageMetadataText} 坐标映射提示使用；未 resize 时 display == original
 * （亦非 null）。旧 Javadoc「display 恒 null」已过时。
 *
 * @param originalWidth  原始宽 (像素), 可 null (解析失败)
 * @param originalHeight 原始高 (像素), 可 null
 * @param displayWidth   显示宽 (缩放后), 可 null (resize 前/失败)
 * @param displayHeight  显示高 (缩放后), 可 null
 */
public record ImageDimensions(
        Integer originalWidth,
        Integer originalHeight,
        Integer displayWidth,
        Integer displayHeight) {

    /** 仅原始尺寸 · display 恒 null (对齐 CC resize 前 undefined). */
    public static ImageDimensions original(int w, int h) {
        return new ImageDimensions(w, h, null, null);
    }

    /**
     * 原始尺寸 + display 尺寸 · 对齐 CC imageResizer.ts:216-226 resize 后 dimensions 全字段填充
     * （display 非 null）。CC original: {@code { originalWidth, originalHeight, displayWidth, displayHeight }}。
     */
    public static ImageDimensions resized(int originalW, int originalH, int displayW, int displayH) {
        return new ImageDimensions(originalW, originalH, displayW, displayH);
    }
}
