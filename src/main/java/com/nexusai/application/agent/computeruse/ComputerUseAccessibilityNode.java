package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * UI Automation 树的一个节点 · 对齐 CCB 2.8.4
 * {@code src/utils/computerUse/win32/accessibilitySnapshot.ts:17-25} 的 {@code AccessibilityNode}。
 *
 * <p><b>CCB 原形状（TS，逐字语义）</b>：
 * <pre>
 * export interface AccessibilityNode {
 *   role: string            // Button, Edit, MenuItem, Link, Text, CheckBox, …
 *   name: string            // 可见文本 / 可访问名
 *   automationId: string
 *   bounds: { x, y, w, h }
 *   enabled: boolean
 *   value?: string           // Edit / ComboBox 的当前值
 *   children?: AccessibilityNode[]
 * }
 * </pre>
 *
 * <h2>本仓扩展：{@code targetHandle}（方案 B · 对齐 dsh 的 observation-local 索引）</h2>
 * <p>CCB 靠 {@code automationId} 做精确选择器 —— 那是 Windows UIA 特有的稳定标识。
 * <b>macOS 没有等价物</b>：AX 元素的 {@code AXIdentifier} 绝大多数为空（实测 Chrome / Finder
 * 全为空串），能用的只有「角色 + 名字 + 结构位置」。
 * <br>⇒ 引入 dsh 的 <b>observation-local handle</b>：
 * <ul>
 *   <li>{@code index} = 本次快照里的<b>先序序号</b>（0 起），模型可以直接说「点 #12」；</li>
 *   <li>{@code locator} = 从窗口根到该元素的<b>子节点索引路径</b>（如 {@code "0.2.1"}），
 *       是<b>可复现的定位键</b> —— 后续动作在同一棵树上按它反查。</li>
 * </ul>
 * <p>两者都<b>只对产生它们的那次快照有效</b>；树变了就失效（由
 * {@link ComputerUseUiSnapshotStore} 做陈旧拒绝）。
 *
 * @param role        控件类型名（CCB 的 {@code ControlType} 去掉前缀；macOS 的 {@code AX} 前缀也去掉）
 * @param name        可访问名（可见文本）
 * @param automationId 自动化 id（Windows = UIA AutomationId；<b>macOS = locator 路径</b>）
 * @param x           屏幕 X
 * @param y           屏幕 Y
 * @param width       宽
 * @param height      高
 * @param enabled     是否可用（false ⇒ 快照文本里标 {@code DISABLED}）
 * @param value       当前值（Edit / ComboBox 等）
 * @param children    子节点（可空）
 * @param index       本次快照内的先序序号（-1 = 未编号，Windows 侧沿用 -1）
 * @param locator     子节点索引路径（空串 = 根；Windows 侧沿用空串）
 * @param actions     该元素声明的可执行动作（macOS {@code AXActionNames}；Windows 侧为空）
 */
public record ComputerUseAccessibilityNode(
        String role,
        String name,
        String automationId,
        int x,
        int y,
        int width,
        int height,
        boolean enabled,
        String value,
        List<ComputerUseAccessibilityNode> children,
        int index,
        String locator,
        List<String> actions,
        String subrole,
        String nativeIdentifier,
        Boolean focused,
        Boolean selected) {

    public ComputerUseAccessibilityNode {
        if (role == null) {
            role = "";
        }
        if (name == null) {
            name = "";
        }
        if (automationId == null) {
            automationId = "";
        }
        if (value != null && value.isEmpty()) {
            value = null;
        }
        children = children == null ? List.of() : List.copyOf(children);
        locator = locator == null ? "" : locator;
        actions = actions == null ? List.of() : List.copyOf(actions);
        subrole = subrole == null ? "" : subrole;
        nativeIdentifier = nativeIdentifier == null ? "" : nativeIdentifier;
    }

    /**
     * Windows 兼容构造（无 index / locator / actions）· 保持既有调用点不变。
     *
     * @param role         控件类型名
     * @param name         可访问名
     * @param automationId 自动化 id
     * @param x            屏幕 X
     * @param y            屏幕 Y
     * @param width        宽
     * @param height       高
     * @param enabled      是否可用
     * @param value        当前值
     * @param children     子节点
     */
    public ComputerUseAccessibilityNode(String role,
                                        String name,
                                        String automationId,
                                        int x,
                                        int y,
                                        int width,
                                        int height,
                                        boolean enabled,
                                        String value,
                                        List<ComputerUseAccessibilityNode> children) {
        this(role, name, automationId, x, y, width, height, enabled, value, children,
                -1, "", List.of(), "", "", null, null);
    }

    /**
     * 兼容构造（有 index / locator / actions / subrole / nativeIdentifier，无 focused / selected）。
     *
     * @param role             控件类型名
     * @param name             可访问名
     * @param automationId     自动化 id
     * @param x                屏幕 X
     * @param y                屏幕 Y
     * @param width            宽
     * @param height           高
     * @param enabled          是否可用
     * @param value            当前值
     * @param children         子节点
     * @param index            先序序号
     * @param locator          子节点索引路径
     * @param actions          可执行动作集
     * @param subrole          子角色
     * @param nativeIdentifier 原生标识
     */
    public ComputerUseAccessibilityNode(String role,
                                        String name,
                                        String automationId,
                                        int x,
                                        int y,
                                        int width,
                                        int height,
                                        boolean enabled,
                                        String value,
                                        List<ComputerUseAccessibilityNode> children,
                                        int index,
                                        String locator,
                                        List<String> actions,
                                        String subrole,
                                        String nativeIdentifier) {
        this(role, name, automationId, x, y, width, height, enabled, value, children,
                index, locator, actions, subrole, nativeIdentifier, null, null);
    }

    /**
     * 控件中心的屏幕坐标。
     *
     * @return {@code {centerX, centerY}}
     */
    public int[] center() {
        return new int[]{x + Math.round(width / 2f), y + Math.round(height / 2f)};
    }

    /**
     * 是否有可用的屏幕矩形（macOS 拿不到坐标时全 0 ⇒ 视为没有）。
     *
     * @return true = 矩形有效
     */
    public boolean hasBounds() {
        return width > 0 && height > 0;
    }
}
