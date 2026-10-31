package com.nexusai.application.agent.computeruse;

/**
 * UI Automation（UIA）服务 · computer-use Phase 2b 的
 * {@code click_element} / {@code type_into_element} 底座。
 *
 * <p><b>WHY 抽接口</b>：UIA 走 PowerShell 子进程（见
 * {@link ComputerUsePowerShell}），单测不能真跑 ⇒ 能力必须可注入
 * （与 {@link ComputerUseWindowOps} / {@link ComputerUseMouse} 同款约定）。
 *
 * <h2>CCB 对照</h2>
 * <table border="1">
 *   <caption>CCB Windows UIA 能力 → 本仓接口方法</caption>
 *   <tr><th>CCB</th><th>行号</th><th>本仓</th></tr>
 *   <tr><td>{@code captureAccessibilitySnapshot(hwnd)}</td>
 *       <td>{@code win32/accessibilitySnapshot.ts:44-145}</td>
 *       <td>{@link #captureSnapshot(long, int)}</td></tr>
 *   <tr><td>{@code findNodeInSnapshot(nodes, query)}</td>
 *       <td>{@code win32/accessibilitySnapshot.ts:202-225}</td>
 *       <td>{@link ComputerUseAccessibilitySnapshot#findNode}</td></tr>
 *   <tr><td>{@code uiAutomation.clickElement(title, automationId)}（InvokePattern）</td>
 *       <td>{@code win32/uiAutomation.ts:245-276}</td>
 *       <td>{@link #invokeElement(long, String)}</td></tr>
 *   <tr><td>{@code uiAutomation.setValue(title, automationId, value)}（ValuePattern）</td>
 *       <td>{@code win32/uiAutomation.ts:281-314}</td>
 *       <td>{@link #setElementValue(long, String, String)}</td></tr>
 * </table>
 *
 * <p><b>⚠️ 与 CCB 的两处实现差异（如实标注）</b>：
 * <ol>
 *   <li><b>定位窗口的方式</b>：CCB 的 {@code uiAutomation.ts} 用<b>窗口标题</b>在 UIA 根节点里
 *       查（{@code PS_FIND_WINDOW}，同名优先、再退化到包含匹配）⇒ 同名窗口会误命中。
 *       本仓改用<b>句柄直查</b>（{@code AutomationElement::FromHandle(hwnd)}）——
 *       与 CCB 自己的快照脚本（{@code accessibilitySnapshot.ts:118}）一致，且无同名歧义。</li>
 *   <li><b>屏幕坐标 → 客户区坐标的换算</b>：CCB {@code executorCrossPlatform.ts:1077-1084}
 *       用 {@code cx - nc.dx}（{@code nc} = 客户区原点相对窗口矩形的偏移），
 *       <b>漏减了窗口矩形自身的原点</b> ⇒ 窗口不在屏幕原点时落点偏移。
 *       本仓按数学正确的方式换算（见 {@link ComputerUseWindowOps#clientToScreen} 的逆运算），
 *       详见 {@code ComputerUseClickElementTool} 的类注释。</li>
 * </ol>
 */
public interface ComputerUseUiaService {

    /**
     * 抓取窗口的 UI Automation 树并格式化成模型可读的紧凑文本。
     *
     * <p>逐字对齐 CCB {@code win32/accessibilitySnapshot.ts:44-145}：
     * 深度 4 + 交互型白名单 + 不可见元素丢弃。
     *
     * @param hwnd    窗口句柄
     * @param maxDepth 最大遍历深度（CCB 默认 4）
     * @return 快照（窗口无元素 ⇒ {@link ComputerUseAccessibilitySnapshot#empty()}）
     * @throws RuntimeException UIA 通道失败（<b>显式失败</b>，不静默吞异常）
     */
    ComputerUseAccessibilitySnapshot captureSnapshot(long hwnd, int maxDepth);

    /**
     * 用 {@code InvokePattern} 点击元素。
     *
     * <p>逐字对齐 CCB {@code win32/uiAutomation.ts:245-276}。
     *
     * @param hwnd        窗口句柄
     * @param automationId 元素的 automationId
     * @return true = 已触发 Invoke；false = 元素不存在或不支持 InvokePattern
     */
    boolean invokeElement(long hwnd, String automationId);

    /**
     * 用 {@code ValuePattern} 设置元素的值。
     *
     * <p>逐字对齐 CCB {@code win32/uiAutomation.ts:281-314}。
     *
     * @param hwnd        窗口句柄
     * @param automationId 元素的 automationId
     * @param value       要写入的值
     * @return true = 已写入；false = 元素不存在或不支持 ValuePattern
     */
    boolean setElementValue(long hwnd, String automationId, String value);
}
