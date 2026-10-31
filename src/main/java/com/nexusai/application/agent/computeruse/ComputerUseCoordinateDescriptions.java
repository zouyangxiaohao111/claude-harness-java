package com.nexusai.application.agent.computeruse;

/**
 * 坐标描述文案 · <b>逐字对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:20-29} 的 {@code COORD_DESC}</b>。
 *
 * <p><b>CCB 原形状（TS，逐字）</b>：
 * <pre>
 * const COORD_DESC: Record&lt;CoordinateMode, { x: string; y: string }&gt; = {
 *   pixels: {
 *     x: 'Horizontal pixel position read directly from the most recent screenshot image, measured from the left edge. The server handles all scaling.',
 *     y: 'Vertical pixel position read directly from the most recent screenshot image, measured from the top edge. The server handles all scaling.',
 *   },
 *   normalized_0_100: {
 *     x: 'Horizontal position as a percentage of screen width, 0.0–100.0 (0 = left edge, 100 = right edge).',
 *     y: 'Vertical position as a percentage of screen height, 0.0–100.0 (0 = top edge, 100 = bottom edge).',
 *   },
 * }
 * </pre>
 *
 * <h2>WHY 必须「一态一文案」</h2>
 * <p>CCB {@code tools.ts:5-10} 文件头注释原文：
 * <blockquote>
 * Coordinate descriptions are baked in at tool-list build time from the
 * {@code chicago_coordinate_mode} gate. The model sees exactly ONE coordinate
 * convention in the param descriptions and never learns the other exists.
 * The host ({@code serverDef.ts}) reads the same frozen gate value for
 * {@code scaleCoord} — both must agree or clicks land in the wrong space.
 * </blockquote>
 *
 * <p>⇒ 本类的<b>唯一正确用法</b>是：工具在<b>构造期</b>用
 * {@link ComputerUseCoordinateMode} 选定<b>一个</b>态，把那<b>一个</b>态的文案烤进
 * {@code inputSchema}；<b>绝不能</b>把两种约定同时写给模型（模型会混用 ⇒ 点错位置）。
 *
 * <p>⇒ 坐标模式<b>冻结</b>：CCB {@code src/utils/computerUse/gates.ts:66-72} 的
 * {@code getChicagoCoordinateMode()} 用 {@code frozenCoordinateMode ??= readConfig().coordinateMode}
 * —— <b>首次读取即冻结</b>，会话中途 GrowthBook 翻转会「告诉模型 pixels 却按 normalized 换算」
 * ⇒ 点击落在错误空间。本仓沿用同一冻结语义（{@link ComputerUseGates#coordinateMode()}）。
 *
 * <p><b>tuple 描述只带 x 文本</b>：CCB {@code tools.ts:143-149} 的
 * {@code coordinateTuple.description = `(x, y): ${coord.x}`} —— <b>不带 y 文本</b>。
 * 本仓逐字保留（Phase 0 的 {@code ComputerUseLeftClickTool} 也是这么抄的）。
 */
public final class ComputerUseCoordinateDescriptions {

    /** {@code pixels} 态的 x 文案 · CCB {@code tools.ts:22}。 */
    public static final String PIXELS_X =
            "Horizontal pixel position read directly from the most recent screenshot image, "
                    + "measured from the left edge. The server handles all scaling.";

    /** {@code pixels} 态的 y 文案 · CCB {@code tools.ts:23}。 */
    public static final String PIXELS_Y =
            "Vertical pixel position read directly from the most recent screenshot image, "
                    + "measured from the top edge. The server handles all scaling.";

    /** {@code normalized_0_100} 态的 x 文案 · CCB {@code tools.ts:26}。 */
    public static final String NORMALIZED_X =
            "Horizontal position as a percentage of screen width, 0.0–100.0 "
                    + "(0 = left edge, 100 = right edge).";

    /** {@code normalized_0_100} 态的 y 文案 · CCB {@code tools.ts:27}。 */
    public static final String NORMALIZED_Y =
            "Vertical position as a percentage of screen height, 0.0–100.0 "
                    + "(0 = top edge, 100 = bottom edge).";

    private ComputerUseCoordinateDescriptions() {
    }

    /**
     * 取当前态的 x 文案。
     *
     * @param mode 坐标模式（null ⇒ 回落 {@link ComputerUseCoordinateMode#PIXELS}）
     * @return x 描述文案
     */
    public static String x(ComputerUseCoordinateMode mode) {
        return mode == ComputerUseCoordinateMode.NORMALIZED_0_100 ? NORMALIZED_X : PIXELS_X;
    }

    /**
     * 取当前态的 y 文案。
     *
     * @param mode 坐标模式（null ⇒ 回落 {@link ComputerUseCoordinateMode#PIXELS}）
     * @return y 描述文案
     */
    public static String y(ComputerUseCoordinateMode mode) {
        return mode == ComputerUseCoordinateMode.NORMALIZED_0_100 ? NORMALIZED_Y : PIXELS_Y;
    }

    /**
     * {@code coordinate} 数组参数的描述 · 逐字对齐 CCB {@code tools.ts:143-149}。
     *
     * <p>CCB 原文：{@code description: `(x, y): ${coord.x}`} —— <b>只带 x 文本</b>。
     *
     * @param mode 坐标模式（null ⇒ 回落 {@link ComputerUseCoordinateMode#PIXELS}）
     * @return 描述文案
     */
    public static String tupleDescription(ComputerUseCoordinateMode mode) {
        return "(x, y): " + x(mode);
    }
}
