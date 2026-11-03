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
     * 元素定位是否需要<b>预先绑定窗口</b>。
     *
     * <h2>WHY 需要这个区分</h2>
     * <p>两个平台定位元素的<b>锚点</b>不同：
     * <ul>
     *   <li><b>Windows</b>：UIA 树从<b>窗口句柄</b>（{@code AutomationElement::FromHandle}）开始，
     *       没有绑定窗口就无法确定「操作哪个窗口」⇒ 必须<b>先 bind</b>；
     *       名字/角色可能在多个窗口里重名。</li>
     *   <li><b>macOS</b>：AX 树从<b>进程</b>（{@code AXUIElementCreateApplication(pid)}）开始，
     *       再取<b>前台窗口</b>（{@code AXFocusedWindow} / {@code AXMainWindow}）——
     *       <b>不需要句柄</b>，而且前台应用本来就是门控判定用的同一对象
     *       ⇒ 绑定窗口是多余状态（dsh 的 macOS 实现同样按 bundle id + pid 选目标，
     *       不要求先 bind）。</li>
     * </ul>
     *
     * @return true = 调用方必须先绑定窗口（Windows）；false = 无需绑定（macOS）
     */
    default boolean requiresBoundWindow() {
        return true;
    }

    /**
     * 抓取窗口的 UI Automation 树并格式化成模型可读的紧凑文本。
     *
     * <p>逐字对齐 CCB {@code win32/accessibilitySnapshot.ts:44-145}：
     * 深度 4 + 交互型白名单 + 不可见元素丢弃。
     *
     * @param hwnd    窗口句柄（<b>macOS 忽略此参数</b>，用前台应用窗口；见 {@link #requiresBoundWindow()}）
     * @param maxDepth 最大遍历深度（CCB 默认 4）
     * @return 快照（窗口无元素 ⇒ {@link ComputerUseAccessibilitySnapshot#empty()})
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
     * 带<b>陈旧校验</b>的点击（dsh {@code expectedStateHash} 语义）。
     *
     * <p>观察（{@code observe}）时记下整树状态指纹；动作时后端重新计算并与期望值比对，
     * <b>不一致即拒绝</b> —— 界面变了还按旧序号操作，会打到别的控件上。
     *
     * <p>默认实现忽略哈希、退化为 {@link #invokeElement(long, String)}：
     * Windows 的 UIA 用 {@code AutomationId} 精确定位，不依赖「树序」，故无需该校验。
     *
     * @param hwnd              窗口句柄（macOS 忽略）
     * @param automationId      定位键（macOS = locator 路径）
     * @param expectedStateHash 期望状态指纹（null / 空 ⇒ 不校验）
     * @return true = 已触发
     */
    default boolean invokeElement(long hwnd, String automationId, String expectedStateHash) {
        return invokeElement(hwnd, automationId);
    }

    /**
     * 按条件等待 AX 状态满足（对齐 dsh {@code computer_wait}）。
     *
     * <p>默认实现：不支持（返回 {@code null}）—— Windows 侧暂不提供条件等待。
     *
     * @param hwnd         窗口句柄（macOS 忽略）
     * @param text         条件：树文本包含该子串（大小写不敏感；可空）
     * @param elementRole  条件：存在该角色的元素（可空）
     * @param elementTitle 条件：存在该标题/名字的元素（可空）
     * @param timeoutMs    超时（毫秒）
     * @return 等待结束时的快照；超时未满足 ⇒ 返回最后一次快照（调用方据
     *         {@code satisfies} 判断）；不支持 ⇒ {@code null}
     */
    default ComputerUseAccessibilitySnapshot waitForCondition(long hwnd,
                                                              String text,
                                                              String elementRole,
                                                              String elementTitle,
                                                              long timeoutMs) {
        return null;
    }

    /**
     * 执行元素声明的<b>任意</b> Accessibility 动作（对齐 dsh {@code computer_perform_action}）。
     *
     * <p>默认实现：Windows 的 UIA 没有等价物（动作集是 {@code Invoke}/{@code Expand}… 的模式，
     * 不是统一的名字空间）⇒ 默认返回 {@code false}，由 macOS 实现覆写。
     *
     * @param hwnd    窗口句柄（macOS 忽略）
     * @param locator 定位键（macOS = 路径；Windows = AutomationId）
     * @param action  动作名（如 {@code AXPress} / {@code AXExpand}）
     * @return true = 动作已执行
     */
    default boolean performAction(long hwnd, String locator, String action) {
        return false;
    }

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
