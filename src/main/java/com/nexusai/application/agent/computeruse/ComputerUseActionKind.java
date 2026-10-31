package com.nexusai.application.agent.computeruse;

/**
 * computer-use 输入动作类别 · 逐字对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:355}
 * 的 {@code type CuActionKind = 'mouse_position' | 'mouse' | 'mouse_full' | 'keyboard'}。
 *
 * <p><b>逐个 handler 的归类（CCB 真源，不自创分类）</b>：
 * <table border="1">
 *   <caption>CCB handler → actionKind</caption>
 *   <tr><th>CCB handler</th><th>行号</th><th>actionKind</th></tr>
 *   <tr><td>{@code handleClickVariant}（纯左键、无修饰键）</td><td>2429-2432</td><td>{@link #MOUSE}</td></tr>
 *   <tr><td>{@code handleClickVariant}（右键/中键 <b>或</b> 带修饰键）</td><td>2429-2432</td><td>{@link #MOUSE_FULL}</td></tr>
 *   <tr><td>{@code handleType}</td><td>2520</td><td>{@link #KEYBOARD}</td></tr>
 *   <tr><td>{@code handleKey}</td><td>2613</td><td>{@link #KEYBOARD}</td></tr>
 *   <tr><td>{@code handleHoldKey}</td><td>3751</td><td>{@link #KEYBOARD}</td></tr>
 *   <tr><td>{@code handleScroll}（未按住鼠标键）</td><td>2678</td><td>{@link #MOUSE}</td></tr>
 *   <tr><td>{@code handleScroll}（按住鼠标键）</td><td>2678</td><td>{@link #MOUSE_FULL}</td></tr>
 *   <tr><td>{@code handleDrag}（left_click_drag 的 to 落点）</td><td>2772</td><td>{@link #MOUSE_FULL}</td></tr>
 *   <tr><td>{@code handleMoveMouse}（未按住鼠标键）</td><td>2795</td><td>{@link #MOUSE_POSITION}</td></tr>
 *   <tr><td>{@code handleMoveMouse}（按住鼠标键）</td><td>2795 / 2826</td><td>{@link #MOUSE}（命中测试再按 {@link #MOUSE_FULL}）</td></tr>
 *   <tr><td>{@code handleLeftMouseDown}</td><td>3776</td><td>{@link #MOUSE}</td></tr>
 *   <tr><td>{@code handleLeftMouseUp}</td><td>3846</td><td>{@link #MOUSE}（按住期间移动过则 {@link #MOUSE_FULL}）</td></tr>
 * </table>
 *
 * <p><b>CCB 原文注释（{@code toolCalls.ts:338-354}，逐字语义）</b>：
 * <ul>
 *   <li>{@code mouse_position} — 仅 mouse_move；<b>任何档位都通过</b>（含 read）。
 *       纯光标定位，不与应用交互。</li>
 *   <li>{@code mouse} — 普通左键、双击/三击、滚动、拖拽起点。
 *       需要档位 {@code click} 或 {@code full}。</li>
 *   <li>{@code mouse_full} — 右键/中键、任何带修饰键的点击、拖放落点。
 *       需要档位 {@code full}。</li>
 *   <li>{@code keyboard} — type / key / hold_key。需要档位 {@code full}。</li>
 * </ul>
 */
public enum ComputerUseActionKind {

    /** 纯光标定位（mouse_move，未按住鼠标键）· 任何档位都放行。 */
    MOUSE_POSITION("mouse_position"),

    /** 普通鼠标动作（纯左键 / 双击 / 三击 / 滚动 / 拖拽起点）· 需 click 或 full。 */
    MOUSE("mouse"),

    /** 升级为键盘等价输入的鼠标动作（右键 / 中键 / 修饰键点击 / 拖放落点）· 需 full。 */
    MOUSE_FULL("mouse_full"),

    /** 键盘动作（type / key / hold_key）· 需 full。 */
    KEYBOARD("keyboard");

    /** wire 值（CCB 的 actionKind 串本体）。 */
    private final String wire;

    ComputerUseActionKind(String wire) {
        this.wire = wire;
    }

    /**
     * @return wire 值（{@code "mouse_position"} / {@code "mouse"} / {@code "mouse_full"} / {@code "keyboard"}）
     */
    public String wire() {
        return wire;
    }
}
