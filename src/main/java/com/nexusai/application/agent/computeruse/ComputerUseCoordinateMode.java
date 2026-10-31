package com.nexusai.application.agent.computeruse;

/**
 * 坐标模式 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/types.ts:86-96}
 * （{@code CoordinateMode = 'pixels' | 'normalized_0_100'}）。
 *
 * <p>CCB 由 GrowthBook 开关 {@code chicago_coordinate_mode} 决定（{@code types.ts:86-96} 注释），
 * 且「模型只该看到一种约定」（{@code tools.ts:5-10} 文件头注释）—— 描述文案与 {@code scaleCoord}
 * 必须读同一个冻结值，否则点击落在错误空间（{@code tools.ts:9-10}）。
 *
 * <p><b>PoC 取值</b>：本仓 Phase 0 固定使用 {@link #PIXELS}（CCB 默认、也是 CC 官方 computer-use
 * 的做法）—— 模型在<b>最近那张截图图像</b>上读像素坐标，描述文案照抄 CCB
 * {@code tools.ts:22-23} 原文。
 */
public enum ComputerUseCoordinateMode {

    /**
     * 模型给的是「最近那张截图图像上的像素坐标」。
     *
     * <p>CCB {@code tools.ts:21-24} 原文（PoC 工具描述逐字抄用）：
     * <pre>
     * x: 'Horizontal pixel position read directly from the most recent screenshot image, measured from the left edge. The server handles all scaling.'
     * y: 'Vertical pixel position read directly from the most recent screenshot image, measured from the top edge. The server handles all scaling.'
     * </pre>
     */
    PIXELS,

    /**
     * 模型给的是屏幕百分比（0–100）。
     *
     * <p>CCB {@code tools.ts:25-28}。Phase 1c 起<b>已接线</b>（描述随模式切换、
     * {@code scaleCoord} 按模式换算）。
     */
    NORMALIZED_0_100;

    /**
     * wire 值 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/types.ts:86-96}
     * （{@code CoordinateMode = 'pixels' | 'normalized_0_100'}）。
     *
     * @return {@code "pixels"} / {@code "normalized_0_100"}
     */
    public String wire() {
        return this == NORMALIZED_0_100 ? "normalized_0_100" : "pixels";
    }
}
