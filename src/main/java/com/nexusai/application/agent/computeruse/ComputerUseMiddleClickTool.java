package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.mcp.McpStringUtils;
/**
 * {@code mcp__computer-use__middle_click} · 中键（滚轮按下）工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:298-310}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:299-309}）</h2>
 * <pre>
 * name: 'middle_click',
 * description: `Middle-click (scroll-wheel click) at the given coordinates. ${FRONTMOST_GATE_DESC}`,
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     coordinate: coordinateTuple,
 *     text: clickModifierText,
 *   },
 *   required: ['coordinate'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照</h2>
 * <ul>
 *   <li><b>dispatch</b>：CCB {@code toolCalls.ts:4169-4170}
 *       {@code case 'middle_click': return handleClickVariant(adapter, a, overrides, subGates, 'middle', 1)}
 *       ⇒ <b>中键 + 1 次</b>。</li>
 *   <li><b>门控档位</b>：中键 ⇒ <b>{@code mouse_full}</b>（CCB {@code toolCalls.ts:2429-2432}：
 *       {@code button !== 'left' ⇒ 'mouse_full'}）⇒ <b>需 full 档</b>。</li>
 *   <li><b>坐标换算</b>：CCB {@code toolCalls.ts:2484-2491} 的 {@code scaleCoord}
 *       ⇒ 本仓走 {@link ComputerUseCoordinateScaler}（截图时刻快照）。</li>
 *   <li><b>成功文案</b>：CCB {@code toolCalls.ts:2504} {@code 'Clicked.'}（逐字）。</li>
 * </ul>
 */
public final class ComputerUseMiddleClickTool extends ComputerUseClickVariantTool {

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "middle_click");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:300}。 */
    private static final String DESCRIPTION = buildDescription(
            "Middle-click (scroll-wheel click) at the given coordinates.");

    /**
     * @param mouse                 鼠标动作实现
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     */
    public ComputerUseMiddleClickTool(ComputerUseMouseActions mouse,
                                      ComputerUseGeometryStore geometryStore,
                                      ComputerUseCoordinateMode coordinateMode,
                                      ComputerUseGrantStore grantStore,
                                      ComputerUseForegroundAppResolver foregroundAppResolver) {
        super(TOOL_NAME, DESCRIPTION, ComputerUseMouseButton.MIDDLE, 1,
                mouse, geometryStore, coordinateMode, grantStore, foregroundAppResolver);
    }
}
