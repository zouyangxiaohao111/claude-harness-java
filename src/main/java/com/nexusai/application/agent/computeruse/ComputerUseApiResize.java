package com.nexusai.application.agent.computeruse;

/**
 * API 目标尺寸算法 · 逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/imageResize.ts}。
 *
 * <p><b>CCB 真源</b>（{@code imageResize.ts}）：
 * <ul>
 *   <li>{@code :26-30} {@code API_RESIZE_PARAMS = { pxPerToken: 28, maxTargetPx: 1568, maxTargetTokens: 1568 }}</li>
 *   <li>{@code :33-35} {@code nTokensForPx(px, pxPerToken) = Math.floor((px - 1) / pxPerToken) + 1}</li>
 *   <li>{@code :37-43} {@code nTokensForImg(w, h, pxPerToken) = nTokensForPx(w) * nTokensForPx(h)}</li>
 *   <li>{@code :60-108} {@code targetImageSize(width, height, params)} —— 沿宽边二分查找</li>
 * </ul>
 *
 * <p><b>WHY 必须前置缩放</b>（CCB {@code imageResize.ts:1-12} 文件头注释）：预缩放到本函数输出尺寸后，
 * API 端的早期返回生效（tokens ≤ max）⇒ <b>服务端不再二次缩放</b> ⇒ 模型看到的尺寸就是
 * {@code ScreenshotResult.width/height} ⇒ {@code scaleCoord} 的分母与模型所见一致，点击不偏。
 * 反之若只做长边 ≤1568 的粗裁剪（56×37 = 2072 tokens 超预算），服务端会再缩到 1372×887，
 * 模型在 1372 空间给坐标而 {@code scaleCoord} 按 1568 空间换算 ⇒ <b>约 14% 坐标误差</b>
 * （CCB {@code imageResize.ts:52-56} 原文举例）。
 *
 * <p><b>数值语义对齐说明</b>：JS 的 {@code Math.floor(a / b)} 与 Java 的 {@code Math.floorDiv(a, b)}
 * 在整数非负域等价；JS 的 {@code Math.round(x)}（四舍五入、.5 向上）与 Java {@code Math.round(double)}
 * 等价。本类所有取整点均为逐字搬运，未做任何"更合理"的改写。
 */
public final class ComputerUseApiResize {

    /** CCB {@code imageResize.ts:27} —— 视觉编码器 28px 一块。 */
    public static final int PX_PER_TOKEN = 28;

    /** CCB {@code imageResize.ts:28} —— 长边上限（56 tiles）。 */
    public static final int MAX_TARGET_PX = 1568;

    /** CCB {@code imageResize.ts:29} —— token 预算上限。 */
    public static final int MAX_TARGET_TOKENS = 1568;

    private ComputerUseApiResize() {
    }

    /**
     * 单边 token 数 · 对齐 CCB {@code imageResize.ts:33-35}。
     *
     * @param px         像素边长
     * @param pxPerToken 每 token 像素数
     * @return ceil(px / pxPerToken)（CCB 用整数 ceil-div 实现）
     */
    public static int nTokensForPx(int px, int pxPerToken) {
        // TS: Math.floor((px - 1) / pxPerToken) + 1
        return Math.floorDiv(px - 1, pxPerToken) + 1;
    }

    /**
     * 整图 token 数 · 对齐 CCB {@code imageResize.ts:37-43}。
     *
     * @param width      图宽（像素）
     * @param height     图高（像素）
     * @param pxPerToken 每 token 像素数
     * @return token 数
     */
    public static int nTokensForImg(int width, int height, int pxPerToken) {
        return nTokensForPx(width, pxPerToken) * nTokensForPx(height, pxPerToken);
    }

    /**
     * 目标尺寸 · 对齐 CCB {@code imageResize.ts:60-108}（生产参数 {@link #PX_PER_TOKEN} /
     * {@link #MAX_TARGET_PX} / {@link #MAX_TARGET_TOKENS}）。
     *
     * <p>语义：保持宽高比，返回同时满足「长边 ≤ 1568」且「ceil(w/28)×ceil(h/28) ≤ 1568」的
     * <b>最大</b>尺寸；输入已满足时原样返回（no-op）。
     *
     * @param width  输入宽（物理像素）
     * @param height 输入高（物理像素）
     * @return {@code int[]{width, height}}（目标宽、目标高）
     */
    public static int[] targetImageSize(int width, int height) {
        return targetImageSize(width, height, PX_PER_TOKEN, MAX_TARGET_PX, MAX_TARGET_TOKENS);
    }

    /**
     * 目标尺寸（参数化）· 对齐 CCB {@code imageResize.ts:60-108} 逐字。
     *
     * @param width           输入宽
     * @param height          输入高
     * @param pxPerToken      每 token 像素数
     * @param maxTargetPx     长边像素上限
     * @param maxTargetTokens token 预算上限
     * @return {@code int[]{width, height}}
     */
    public static int[] targetImageSize(int width, int height, int pxPerToken,
                                        int maxTargetPx, int maxTargetTokens) {
        if (width <= maxTargetPx
                && height <= maxTargetPx
                && nTokensForImg(width, height, pxPerToken) <= maxTargetTokens) {
            return new int[]{width, height};
        }

        // 归一化为横图搜索，结果再转置回来（CCB imageResize.ts:75-79）。
        if (height > width) {
            int[] transposed = targetImageSize(height, width, pxPerToken, maxTargetPx, maxTargetTokens);
            return new int[]{transposed[1], transposed[0]};
        }

        double aspectRatio = (double) width / height;

        // 循环不变式：lowerBoundWidth 恒合法，upperBoundWidth 恒不合法（CCB imageResize.ts:83-86）。
        int upperBoundWidth = width;
        int lowerBoundWidth = 1;

        for (;;) {
            if (lowerBoundWidth + 1 == upperBoundWidth) {
                return new int[]{
                        lowerBoundWidth,
                        Math.max((int) Math.round(lowerBoundWidth / aspectRatio), 1)
                };
            }

            int middleWidth = Math.floorDiv(lowerBoundWidth + upperBoundWidth, 2);
            int middleHeight = Math.max((int) Math.round(middleWidth / aspectRatio), 1);

            if (middleWidth <= maxTargetPx
                    && nTokensForImg(middleWidth, middleHeight, pxPerToken) <= maxTargetTokens) {
                lowerBoundWidth = middleWidth;
            } else {
                upperBoundWidth = middleWidth;
            }
        }
    }
}
