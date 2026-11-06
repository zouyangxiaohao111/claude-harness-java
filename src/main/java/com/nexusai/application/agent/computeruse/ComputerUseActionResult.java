package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 动作结果 · <b>逐条对齐 dsh {@code actionResultSchema}</b>
 * （dsh {@code src/tools.ts:159-196}）。
 *
 * <h2>WHY 需要结构化结果</h2>
 * <p>本仓此前的动作工具只返回一句文本（{@code "Clicked element: \"Save\""}）⇒
 * 模型拿不到三件事：
 * <ol>
 *   <li><b>动作后的界面状态</b>（dsh 的 {@code observation}）—— 模型必须再调一次 observe
 *       才能知道结果，白白多一轮 token；</li>
 *   <li><b>动作到底生效没有</b>（dsh 的 {@code effect.observedStateChanged}）—— 无法区分
 *       「点了但没生效」和「点到了别的控件」；</li>
 *   <li><b>点的是不是我以为的那个</b>（dsh 的 {@code resolution}）—— 三级 rebind 的结果
 *       在本仓只出现在失败路径，成功时被整条丢弃。</li>
 * </ol>
 * ⇒ 这三项是 dsh 设计里最有价值的部分，模型靠它们自验证。
 *
 * <h2>dsh 的字段清单（9 个）</h2>
 * <pre>
 * action         click | set-value | type-text | press-key | scroll | drag | perform-action | wait
 * channel        accessibility | coordinates | keyboard | wait
 * activation     not-requested | already-frontmost | activated
 * pointerInput   boolean
 * pointerRouting none | target-process
 * resolution     { mode, confidence, candidateCount, targetChanged }
 * agentCursor    { visible: false, reason? }        ← 只在「该显示却没显示」时出现
 * effect         { observedStateChanged, observedForMs, note? }
 * observation    { observationId, app, createdAt, expiresAt, frontmost, window, tree, elements, permissions }
 * </pre>
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li><b>agentCursor 不产出</b>：dsh 有原生 helper 画一个可见的「AI 光标」，本仓没有
 *       ⇒ 该字段<b>整个省略</b>（dsh 也是「只在需要报告时才出现」，不是恒定字段）。
 *       模型不得据此推断「光标不可见」—— 它只是没有这个概念。</li>
 *   <li><b>pointerRouting 恒为 {@code none}</b>：本仓 macOS 的指针投递走全局 HID 流
 *       （<b>实测</b>：公开的 {@code CGEventPostToPid} 只对键盘有效，指针事件被静默丢弃
 *       ⇒ 定向指针必须走原生侧车）。报 {@code target-process} 是<b>撒谎</b>，
 *       必须如实报 {@code none}。</li>
 * </ul>
 */
public record ComputerUseActionResult(
        String action,
        String channel,
        String activation,
        boolean pointerInput,
        String pointerRouting,
        ComputerUseTargetResolver.Resolution resolution,
        Effect effect,
        ComputerUseUiSnapshotStore.Observation observation) {

    /** 动作类型 · dsh {@code actionResultSchema.action} 的枚举。 */
    public static final String ACTION_CLICK = "click";
    public static final String ACTION_SET_VALUE = "set-value";
    public static final String ACTION_TYPE_TEXT = "type-text";
    public static final String ACTION_PERFORM_ACTION = "perform-action";
    public static final String ACTION_WAIT = "wait";

    /** 通道 · dsh {@code actionResultSchema.channel} 的枚举。 */
    public static final String CHANNEL_ACCESSIBILITY = "accessibility";
    public static final String CHANNEL_COORDINATES = "coordinates";
    public static final String CHANNEL_KEYBOARD = "keyboard";
    public static final String CHANNEL_WAIT = "wait";

    /** 前台激活结果 · dsh {@code actionResultSchema.activation} 的枚举。 */
    public static final String ACTIVATION_NOT_REQUESTED = "not-requested";
    public static final String ACTIVATION_ALREADY_FRONTMOST = "already-frontmost";
    public static final String ACTIVATION_ACTIVATED = "activated";

    /** 指针投递路由 · dsh {@code actionResultSchema.pointerRouting} 的枚举。 */
    public static final String POINTER_ROUTING_NONE = "none";
    public static final String POINTER_ROUTING_TARGET_PROCESS = "target-process";

    /**
     * 动作的可观测效果（对齐 dsh {@code actionResultSchema.effect}）。
     *
     * @param observedStateChanged 观察到的状态是否变化（窗口元数据 + AX 树）
     * @param observedForMs        为观察变化花了多久（毫秒）
     * @param note                 补充说明（仅在「没变化」时给）
     */
    public record Effect(boolean observedStateChanged, long observedForMs, String note) {

        /**
         * 「没变化」时的标准提示（逐字对齐 dsh {@code service.ts:672-675}）。
         *
         * <p>⭐ <b>WHY 必须带这条 note</b>：{@code observedStateChanged=false}
         * <b>不等于动作失败</b> —— 只说明「窗口标题 / 窗口 id / 窗口矩形 / AX 元素树」
         * 这些<b>结构性</b>状态没变；纯像素变化、远程变化、瞬时变化都可能已经发生。
         * 不说清楚 ⇒ 模型会误判「没生效」而重试（dsh 的 Skill 明确禁止自动重试）。
         */
        public static final String NO_CHANGE_NOTE =
                "no change was observed in the window title, id, frame, or accessibility element tree; "
                        + "pixel-only, remote, or transient effects may still have occurred";

        /** 构造「没变化」的效果（带标准提示）。 */
        public static Effect unchanged(long observedForMs) {
            return new Effect(false, observedForMs, NO_CHANGE_NOTE);
        }

        /** 构造「有变化」的效果（不带提示）。 */
        public static Effect changed(long observedForMs) {
            return new Effect(true, observedForMs, null);
        }
    }

    /**
     * 转成给模型看的 JSON（字段名逐字对齐 dsh）。
     *
     * @param elementsJson 观察的元素表（JSON 数组）
     * @param permissions  权限状态节点
     * @param extra        额外字段（可空）
     * @return JSON 节点
     */
    public ObjectNode toJson(com.fasterxml.jackson.databind.node.ArrayNode elementsJson,
                             ObjectNode permissions,
                             ObjectNode extra) {
        ObjectNode out = JSON.createObjectNode();
        out.put("action", action);
        out.put("channel", channel);
        out.put("activation", activation);
        out.put("pointerInput", pointerInput);
        out.put("pointerRouting", pointerRouting);
        if (resolution != null) {
            ObjectNode res = out.putObject("resolution");
            res.put("mode", resolution.mode());
            res.put("confidence", resolution.confidence());
            res.put("candidateCount", resolution.candidateCount());
            res.put("targetChanged", resolution.targetChanged());
        }
        ObjectNode eff = out.putObject("effect");
        eff.put("observedStateChanged", effect.observedStateChanged());
        eff.put("observedForMs", effect.observedForMs());
        if (effect.note() != null && !effect.note().isEmpty()) {
            eff.put("note", effect.note());
        }
        ObjectNode obs = out.putObject("observation");
        obs.put("observationId", observation.observationId());
        ObjectNode app = obs.putObject("app");
        app.put("name", observation.appName());
        app.put("bundleId", observation.bundleId());
        app.put("pid", observation.pid());
        obs.put("createdAt", observation.createdAt());
        obs.put("expiresAt", observation.expiresAt() == Long.MAX_VALUE
                ? "9999-12-31T23:59:59.999Z"
                : java.time.Instant.ofEpochMilli(observation.expiresAt()).toString());
        if (!observation.windowTitle().isEmpty()) {
            obs.putObject("window").put("title", observation.windowTitle());
        }
        ObjectNode tree = obs.putObject("tree");
        tree.put("mode", observation.treeMode());
        tree.put("text", observation.treeText());
        tree.put("truncated", observation.truncated());
        obs.set("elements", elementsJson);
        obs.set("permissions", permissions);
        if (extra != null) {
            out.setAll(extra);
        }
        return out;
    }

    /**
     * 渲染成给模型看的工具结果（文字摘要 + 结构化 JSON）。
     *
     * <p><b>WHY 两个都要</b>：本仓的工具结果以<b>纯文本</b>进主模型上下文
     * （{@code ToolResult.success(callId, String)}）；而 dsh 的结构化字段
     * （{@code effect}/{@code resolution}）必须让模型<b>真的看到</b>才有意义。
     * ⇒ 文本摘要里带上关键结论（动作成功没有 / 目标变了没有），JSON 里带完整契约。
     *
     * @param result      动作结果
     * @param permissions 权限节点
     * @param extra       额外字段（可空）
     * @return 工具结果文本
     */
    static String render(ComputerUseActionResult result,
                         com.fasterxml.jackson.databind.node.ObjectNode permissions,
                         com.fasterxml.jackson.databind.node.ObjectNode extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("Action ").append(result.action()).append(" completed.");
        if (result.resolution() != null) {
            sb.append(" target=").append(result.resolution().mode())
                    .append(" (confidence=").append(result.resolution().confidence())
                    .append(", targetChanged=").append(result.resolution().targetChanged()).append(')');
        }
        sb.append(" observedStateChanged=").append(result.effect().observedStateChanged());
        if (!result.effect().observedStateChanged() && result.effect().note() != null) {
            sb.append(" (").append(result.effect().note()).append(')');
        }
        if (result.observation() != null) {
            sb.append("\n\nPost-action observation (use this observationId for the next action): ")
                    .append(result.observation().observationId())
                    .append("\n").append(result.observation().treeText());
        } else {
            sb.append("\n\nPost-action observation is unavailable.");
        }
        sb.append("\n\n").append(result.toJson(
                result.observation() == null
                        ? JSON.createArrayNode()
                        : ComputerUseUiSnapshotStore.elementsToJson(result.observation().elements()),
                permissions, extra).toPrettyString());
        return sb.toString();
    }

    /** 观察里没有的元素（供工具层自查）。 */
    public List<ComputerUseUiSnapshotStore.ObservedElement> elements() {
        return observation == null ? List.of() : observation.elements();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
