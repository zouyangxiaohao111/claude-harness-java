package com.nexusai.application.agent.computeruse;

/**
 * 鼠标按键 · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2376-2383} 的
 * {@code button: 'left' | 'right' | 'middle'}。
 *
 * <p><b>CCB 原形状（TS，逐字）</b>：
 * <pre>
 * async function handleClickVariant(
 *   adapter: ComputerUseHostAdapter,
 *   args: Record<string, unknown>,
 *   overrides: ComputerUseOverrides,
 *   subGates: CuSubGates,
 *   button: 'left' | 'right' | 'middle',
 *   count: 1 | 2 | 3,
 * ): Promise&lt;CuCallToolResult&gt;
 * </pre>
 *
 * <p><b>调用方映射（CCB {@code toolCalls.ts:4161-4170}）</b>：
 * <ul>
 *   <li>{@code left_click} ⇒ (left, 1)</li>
 *   <li>{@code double_click} ⇒ (left, 2)</li>
 *   <li>{@code triple_click} ⇒ (left, 3)</li>
 *   <li>{@code right_click} ⇒ (right, 1)</li>
 *   <li>{@code middle_click} ⇒ (middle, 1)</li>
 * </ul>
 */
public enum ComputerUseMouseButton {

    /** 左键（CCB wire 值 {@code "left"}）。 */
    LEFT("left"),

    /** 右键（CCB wire 值 {@code "right"}）。 */
    RIGHT("right"),

    /** 中键（滚轮按下，CCB wire 值 {@code "middle"}）。 */
    MIDDLE("middle");

    /** wire 值（CCB 的按钮名字符串本体）。 */
    private final String wire;

    ComputerUseMouseButton(String wire) {
        this.wire = wire;
    }

    /**
     * @return wire 值（{@code "left"} / {@code "right"} / {@code "middle"}）
     */
    public String wire() {
        return wire;
    }
}
