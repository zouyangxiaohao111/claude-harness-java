package com.nexusai.application.agent.attachment;

/**
 * 媒体/图片限额常量 · 对齐 CC {@code constants/apiLimits.ts}（Anthropic API 硬限制）。
 *
 * <p>所有常量值均来自 CC 实际 TS 源码（apiLimits.ts），不可随意修改 —— 这些是 API 侧强制上限，
 * 超限请求被直接拒绝（cc 内部也仅在超限时裁剪/压缩规避，从不突破）。
 *
 * <h2>CC 对齐对照</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>{@link #API_IMAGE_MAX_BASE64_SIZE}</td><td>{@code API_IMAGE_MAX_BASE64_SIZE}</td><td>apiLimits.ts:19</td></tr>
 *   <tr><td>{@link #IMAGE_TARGET_RAW_SIZE}</td><td>{@code IMAGE_TARGET_RAW_SIZE}</td><td>apiLimits.ts:29</td></tr>
 *   <tr><td>{@link #IMAGE_MAX_WIDTH}</td><td>{@code IMAGE_MAX_WIDTH}</td><td>apiLimits.ts:42</td></tr>
 *   <tr><td>{@link #IMAGE_MAX_HEIGHT}</td><td>{@code IMAGE_MAX_HEIGHT}</td><td>apiLimits.ts:43</td></tr>
 *   <tr><td>{@link #API_MAX_MEDIA_PER_REQUEST}</td><td>{@code API_MAX_MEDIA_PER_REQUEST}</td><td>apiLimits.ts:94</td></tr>
 * </table>
 *
 * <p><b>与 {@link com.nexusai.application.agent.tool.impl.ImageResizer} 的常量关系</b>：
 * ImageResizer 内含同值常量副本（apiLimits.ts:19/29/42-43，既有代码，外科手术规则不改动）；
 * 本类为附件门控（{@link MediaLimitGuard}）的 apiLimits.ts 权威映射，二者值相同（同一 CC 来源），
 * 未来如需去重可让 ImageResizer 引用本类（不在本任务范围内）。
 *
 * <p><b>PDF 限额（apiLimits.ts:48-83）</b>：PDF 三态（document 块 / 页渲染 image / pdf_reference）
 * 属 P1-3 范围，常量映射由 P1-3 任务负责，本类暂不收录。
 */
public final class MediaLimitConstants {

    private MediaLimitConstants() {
        // 工具类不可实例化
    }

    /**
     * 单图 base64 最大长度（API 硬限制）· CC original: {@code API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024}
     * (Open-ClaudeCode/src/constants/apiLimits.ts:19)。
     *
     * <p>注意：这是 <b>base64 字符串长度</b>，非原始字节（base64 编码放大 ~33%）。API 按 base64
     * 长度拒绝超限图（imageValidation.ts:89-90 校验 {code base64Size = block.source.data.length}）。
     */
    public static final int API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024;

    /**
     * 压缩目标原始字节上限 · CC original: {@code IMAGE_TARGET_RAW_SIZE = (API_IMAGE_MAX_BASE64_SIZE * 3) / 4}
     * (Open-ClaudeCode/src/constants/apiLimits.ts:29) = 3.75 MB。
     *
     * <p>推导：base64 编码放大 4/3，故 {@code raw * 4/3 = base64} → {@code raw = base64 * 3/4}。
     * 压缩以「原始字节 ≤ 3.75MB」为目标，等价「base64 ≤ 5MB」不击穿 API 硬限制
     * （imageResizer.ts:26-29 注释；压缩阈值 imageResizer.ts:213/242/261 等均按此）。
     */
    public static final long IMAGE_TARGET_RAW_SIZE = (API_IMAGE_MAX_BASE64_SIZE * 3L) / 4L;

    /** 客户端缩放最大宽 · CC original: {@code IMAGE_MAX_WIDTH = 2000}（apiLimits.ts:42）。 */
    public static final int IMAGE_MAX_WIDTH = 2000;

    /** 客户端缩放最大高 · CC original: {@code IMAGE_MAX_HEIGHT = 2000}（apiLimits.ts:43）。 */
    public static final int IMAGE_MAX_HEIGHT = 2000;

    /**
     * 单请求媒体（图片+PDF）最大数量 · CC original: {@code API_MAX_MEDIA_PER_REQUEST = 100}
     * (Open-ClaudeCode/src/constants/apiLimits.ts:94)。
     *
     * <p>API 对超限请求返回迷惑性错误，CC 在 API 调用前裁剪规避
     * （claude.ts:1306-1316 stripExcessMediaItems 静默丢<b>最早</b>媒体项）。
     */
    public static final int API_MAX_MEDIA_PER_REQUEST = 100;
}
