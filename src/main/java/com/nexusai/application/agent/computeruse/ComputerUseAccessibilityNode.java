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
 * @param role        控件类型名（CCB 的 {@code ControlType} 去掉 {@code "ControlType."} 前缀）
 * @param name        可访问名（可见文本）
 * @param automationId 自动化 id（最精确的选择器）
 * @param x           屏幕 X（{@code BoundingRectangle} 的左）
 * @param y           屏幕 Y
 * @param width       宽（像素）
 * @param height      高（像素）
 * @param enabled     是否可用（false ⇒ 快照文本里标 {@code DISABLED}）
 * @param value       当前值（Edit / ComboBox 等）
 * @param children    子节点（可空）
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
        List<ComputerUseAccessibilityNode> children) {

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
    }

    /**
     * 控件中心的屏幕坐标（{@code BoundingRectangle} 中心）。
     *
     * <p>CCB 的 {@code clickElement} / {@code typeIntoElement} 回落路径都用它算点击落点
     * （{@code executorCrossPlatform.ts:1077-1078} / {@code :1133-1134}）。
     *
     * @return {@code {centerX, centerY}}
     */
    public int[] center() {
        return new int[]{x + Math.round(width / 2f), y + Math.round(height / 2f)};
    }
}
