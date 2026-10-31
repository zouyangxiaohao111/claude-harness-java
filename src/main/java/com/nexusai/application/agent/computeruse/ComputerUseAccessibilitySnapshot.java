package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * UI Automation 快照 · 对齐 CCB 2.8.4
 * {@code src/utils/computerUse/win32/accessibilitySnapshot.ts:27-32} 的 {@code AccessibilitySnapshot}。
 *
 * <p><b>CCB 原形状（TS，逐字语义）</b>：
 * <pre>
 * export interface AccessibilitySnapshot {
 *   text: string              // 紧凑文本（给模型看）
 *   nodes: AccessibilityNode[] // 结构化树（给元素定位用）
 *   timestamp: number
 * }
 * </pre>
 *
 * <p><b>快照语义（逐字对齐 CCB {@code accessibilitySnapshot.ts:44-145}）</b>：
 * <ul>
 *   <li><b>深度 4</b>（{@code maxDepth = 4}）；</li>
 *   <li><b>交互型白名单</b>（<b>25 项</b>，与 CCB {@code accessibilitySnapshot.ts:53-59} 一致，见 {@link #INTERACTIVE_ROLES}）；</li>
 *   <li>不可见元素（宽/高 ≤ 0 或 X &lt; -10000）整棵子树丢弃；</li>
 *   <li>非交互节点若<b>没有</b>交互后代则丢弃（只保留「有用的」节点）。</li>
 * </ul>
 *
 * <p><b>文本格式（逐字对齐 CCB {@code accessibilitySnapshot.ts:170-192} 的 {@code formatForModel}）</b>：
 * <pre>
 * [Button] "Save" (120,50 80x30)
 * [Edit] "" (200,80 400x25) DISABLED value="hello world" id=textBox1
 * </pre>
 * 规则：{@code [Role]} → 有 name 则 {@code "name"}（截断 40）→ {@code (x,y WxH)}
 * → 不可用则 {@code  DISABLED} → 有 value 则 {@code value="..."}（截断 30）
 * → 有 automationId 则 {@code id=...}；子节点每层缩进 2 空格。
 */
public record ComputerUseAccessibilitySnapshot(
        String text,
        List<ComputerUseAccessibilityNode> nodes,
        long timestamp) {

    /** 名字截断长度 · CCB {@code accessibilitySnapshot.ts:179}（{@code truncate(name, 40)}）。 */
    public static final int NAME_MAX = 40;

    /** 值截断长度 · CCB {@code accessibilitySnapshot.ts:182}（{@code truncate(value, 30)}）。 */
    public static final int VALUE_MAX = 30;

    /**
     * 交互型控件白名单 · 逐字对齐 CCB
     * {@code accessibilitySnapshot.ts:52-59} 的 {@code $interactiveTypes}。
     */
    public static final List<String> INTERACTIVE_ROLES = List.of(
            "Button", "Edit", "ComboBox", "CheckBox", "RadioButton",
            "MenuItem", "Menu", "MenuBar", "Link", "Slider", "Spinner",
            "Tab", "TabItem", "List", "ListItem", "Tree", "TreeItem",
            "DataGrid", "DataItem", "Document", "ScrollBar", "ToolBar",
            "SplitButton", "ToggleButton", "Hyperlink");

    public ComputerUseAccessibilitySnapshot {
        text = text == null ? "" : text;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * 空快照（窗口没有可访问元素）。
     *
     * @return 空快照
     */
    public static ComputerUseAccessibilitySnapshot empty() {
        return new ComputerUseAccessibilitySnapshot("", List.of(), System.currentTimeMillis());
    }

    /**
     * 把节点树格式化成给模型看的紧凑文本 · 逐字对齐 CCB
     * {@code accessibilitySnapshot.ts:170-192} 的 {@code formatForModel}。
     *
     * @param nodes 节点树
     * @return 文本（各行以 {@code \n} 连接）
     */
    public static String formatForModel(List<ComputerUseAccessibilityNode> nodes) {
        StringBuilder sb = new StringBuilder();
        appendNodes(sb, nodes, 0);
        return sb.toString();
    }

    private static void appendNodes(StringBuilder sb,
                                    List<ComputerUseAccessibilityNode> nodes,
                                    int indent) {
        String pad = "  ".repeat(indent);
        for (ComputerUseAccessibilityNode node : nodes) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(pad).append('[').append(node.role()).append(']');
            if (!node.name().isEmpty()) {
                sb.append(" \"").append(truncate(node.name(), NAME_MAX)).append('"');
            }
            sb.append(" (").append(node.x()).append(',').append(node.y()).append(' ')
                    .append(node.width()).append('x').append(node.height()).append(')');
            if (!node.enabled()) {
                sb.append(" DISABLED");
            }
            if (node.value() != null && !node.value().isEmpty()) {
                sb.append(" value=\"").append(truncate(node.value(), VALUE_MAX)).append('"');
            }
            if (!node.automationId().isEmpty()) {
                sb.append(" id=").append(node.automationId());
            }
            if (!node.children().isEmpty()) {
                appendNodes(sb, node.children(), indent + 1);
            }
        }
    }

    /**
     * 截断（超长时保留 {@code max-1} 个字符 + 省略号）· 逐字对齐 CCB
     * {@code accessibilitySnapshot.ts:194-196} 的 {@code truncate}。
     *
     * @param s   原串
     * @param max 上限
     * @return 截断后的串
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /**
     * 按 {@code name} / {@code role} / {@code automationId} 找节点 · 逐字对齐 CCB
     * {@code accessibilitySnapshot.ts:202-225} 的 {@code findNodeInSnapshot}。
     *
     * <p>匹配规则（CCB 逐字）：
     * <ul>
     *   <li>{@code name} ⇒ <b>大小写不敏感的部分匹配</b>（{@code node.name.contains(query.name)}）；</li>
     *   <li>{@code role} ⇒ <b>大小写不敏感的完全匹配</b>；</li>
     *   <li>{@code automationId} ⇒ <b>完全匹配</b>；</li>
     *   <li>三者中<b>指定的项必须全部命中</b>（AND）；一个都没指定 ⇒ 不匹配。</li>
     * </ul>
     *
     * @param nodes       节点树
     * @param name        名字（可空）
     * @param role        控件类型（可空）
     * @param automationId 自动化 id（可空）
     * @return 第一个命中的节点（深度优先）；无 ⇒ null
     */
    public static ComputerUseAccessibilityNode findNode(List<ComputerUseAccessibilityNode> nodes,
                                                         String name,
                                                         String role,
                                                         String automationId) {
        if (nodes == null) {
            return null;
        }
        boolean anyCriteria = (name != null && !name.isEmpty())
                || (role != null && !role.isEmpty())
                || (automationId != null && !automationId.isEmpty());
        for (ComputerUseAccessibilityNode node : nodes) {
            boolean match = true;
            if (name != null && !name.isEmpty()
                    && !node.name().toLowerCase().contains(name.toLowerCase())) {
                match = false;
            }
            if (role != null && !role.isEmpty()
                    && !node.role().equalsIgnoreCase(role)) {
                match = false;
            }
            if (automationId != null && !automationId.isEmpty()
                    && !node.automationId().equals(automationId)) {
                match = false;
            }
            if (match && anyCriteria) {
                return node;
            }
            ComputerUseAccessibilityNode found = findNode(node.children(), name, role, automationId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
