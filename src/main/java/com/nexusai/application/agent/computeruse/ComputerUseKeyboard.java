package com.nexusai.application.agent.computeruse;

/**
 * 键盘输入接口 · 抽象出「怎么打字/按键」以便纯单测（不碰真实桌面）。
 *
 * <p>生产实现：{@link Win32Keyboard}（JNA 直调 user32 的 {@code SendInput}）。
 *
 * <h2>WHY 两个原语而不是一个 {@code type(String)}</h2>
 * <p>CCB 的语义把「文本」与「按键」分成两条路（{@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2548-2565}）：
 * <ul>
 *   <li><b>普通字符</b> ⇒ 按字符注入（{@code executor.type(grapheme)}）；</li>
 *   <li><b>{@code \n} / {@code \r} / {@code \t}</b> ⇒ <b>走按键</b>（{@code executor.key('return' / 'tab')}），
 *       <b>不能</b>当普通字符打 —— CCB 注释原文：
 *       “Unicode text-insertion of '\n' is not a Return key press. URL bars and terminals ignore it;
 *       the model's intent (submit/execute) is lost.”</li>
 * </ul>
 * ⇒ 本接口保留 {@link #typeChar(int)}（普通字符）与 {@link #sendKeyEvent(ComputerUseKeyEvent)}（按键）
 * 两个原语，让<b>上层工具</b>决定每个字形走哪条路（逐字对齐 CCB）。
 */
public interface ComputerUseKeyboard {

    /**
     * 注入一个 Unicode 码点（<b>普通字符</b>路径）。
     *
     * <p><b>语义</b>：该字符必须<b>原样</b>出现在目标应用的输入框里 ——
     * <b>不得</b>被解释成任何特殊键标记。
     *
     * <p>⚠️ 这是本仓相对 CCB 的<b>缺陷修正</b>：CCB 的 Windows 文本注入走
     * {@code [System.Windows.Forms.SendKeys]::SendWait('...')}
     * （{@code packages/@ant/computer-use-input/src/backends/win32.ts:284-289}），
     * 而 {@code SendKeys} 里 {@code + ^ % ~ ( ) { } [ ]} 都是<b>特殊键标记</b>
     * ⇒ 文本含 {@code +} 会被当成 Shift。本仓改用 {@code KEYEVENTF_UNICODE} 逐字符注入
     * ⇒ <b>无任何特殊字符语义</b>。
     *
     * @param codePoint Unicode 码点（可 &gt; 0xFFFF，实现内部按 UTF-16 代理对拆发）
     * @throws Exception 注入失败（fail loud，由调用方转成工具错误）
     */
    void typeChar(int codePoint) throws Exception;

    /**
     * 派发一个按键事件（按下 / 抬起）。
     *
     * <p>组合键的<b>派发顺序</b>由上层（{@link ComputerUseKeyCombo}）算好，
     * 本接口只负责把事件<b>逐个</b>注入系统。
     *
     * @param event 按键事件（见 {@link ComputerUseKeyEvent#named} / {@link ComputerUseKeyEvent#down}）
     * @throws Exception 注入失败（fail loud）
     */
    void sendKeyEvent(ComputerUseKeyEvent event) throws Exception;
}
