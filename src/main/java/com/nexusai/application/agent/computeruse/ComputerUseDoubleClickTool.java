package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.mcp.McpStringUtils;
/**
 * {@code mcp__computer-use__double_click} · 双击工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:259-270}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:260-269}）</h2>
 * <pre>
 * name: 'double_click',
 * description: `Double-click at the given coordinates. Selects a word in most text editors. ${FRONTMOST_GATE_DESC}`,
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
 *   <li><b>dispatch</b>：CCB {@code toolCalls.ts:4163-4164}
 *       {@code case 'double_click': return handleClickVariant(adapter, a, overrides, subGates, 'left', 2)}
 *       ⇒ <b>左键 + 2 次</b>。</li>
 *   <li><b>门控档位</b>：左键无修饰键 ⇒ {@code mouse}（CCB {@code toolCalls.ts:2429-2432}），
 *       需 click 或 full 档。</li>
 *   <li><b>坐标换算</b>：CCB {@code toolCalls.ts:2484-2491} 的 {@code scaleCoord}
 *       ⇒ 本仓走 {@link ComputerUseCoordinateScaler}（截图时刻快照）。</li>
 *   <li><b>成功文案</b>：CCB {@code toolCalls.ts:2504} {@code 'Clicked.'}（逐字）。</li>
 * </ul>
 */
public final class ComputerUseDoubleClickTool extends ComputerUseClickVariantTool {

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "double_click");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:261}。 */
    private static final String DESCRIPTION = buildDescription(
            "Double-click at the given coordinates. Selects a word in most text editors.");

    /**
     * @param mouse                 鼠标动作实现
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     */
    public ComputerUseDoubleClickTool(ComputerUseMouseActions mouse,
                                      ComputerUseGeometryStore geometryStore,
                                      ComputerUseCoordinateMode coordinateMode,
                                      ComputerUseGrantStore grantStore,
                                      ComputerUseForegroundAppResolver foregroundAppResolver) {
        super(TOOL_NAME, DESCRIPTION, ComputerUseMouseButton.LEFT, 2,
                mouse, geometryStore, coordinateMode, grantStore, foregroundAppResolver);
    }
}
