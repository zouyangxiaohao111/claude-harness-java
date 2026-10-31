package com.nexusai.application.agent.computeruse;

/**
 * {@code mcp__computer-use__triple_click} · 三击工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:272-283}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:273-282}）</h2>
 * <pre>
 * name: 'triple_click',
 * description: `Triple-click at the given coordinates. Selects a line in most text editors. ${FRONTMOST_GATE_DESC}`,
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
 *   <li><b>dispatch</b>：CCB {@code toolCalls.ts:4165-4166}
 *       {@code case 'triple_click': return handleClickVariant(adapter, a, overrides, subGates, 'left', 3)}
 *       ⇒ <b>左键 + 3 次</b>。</li>
 *   <li><b>门控档位</b>：左键无修饰键 ⇒ {@code mouse}（CCB {@code toolCalls.ts:2429-2432}），
 *       需 click 或 full 档。</li>
 *   <li><b>坐标换算</b>：CCB {@code toolCalls.ts:2484-2491} 的 {@code scaleCoord}
 *       ⇒ 本仓走 {@link ComputerUseCoordinateScaler}（截图时刻快照）。</li>
 *   <li><b>成功文案</b>：CCB {@code toolCalls.ts:2504} {@code 'Clicked.'}（逐字）。</li>
 * </ul>
 */
public final class ComputerUseTripleClickTool extends ComputerUseClickVariantTool {

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__triple_click";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:274}。 */
    private static final String DESCRIPTION = buildDescription(
            "Triple-click at the given coordinates. Selects a line in most text editors.");

    /**
     * @param mouse                 鼠标动作实现
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     */
    public ComputerUseTripleClickTool(ComputerUseMouseActions mouse,
                                      ComputerUseGeometryStore geometryStore,
                                      ComputerUseCoordinateMode coordinateMode,
                                      ComputerUseGrantStore grantStore,
                                      ComputerUseForegroundAppResolver foregroundAppResolver) {
        super(TOOL_NAME, DESCRIPTION, ComputerUseMouseButton.LEFT, 3,
                mouse, geometryStore, coordinateMode, grantStore, foregroundAppResolver);
    }
}
