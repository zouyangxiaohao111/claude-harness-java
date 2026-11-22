package com.nexusai.application.agent.permission;

/**
 * [P1] 渠道权限中继的**统一资格判定结果**。
 *
 * <p><b>WHY</b>：此前资格判定是三个私有方法在 {@code startChannelRace} 里**并列短路**
 * （settings 两门 → 会话绑定 → 合格 channel server），而「本会话已绑 IM」与「存在合格 MCP
 * channel server」是两条**并列的来源**，却写在同一个 {@code if} 里 ⇒ 谁把条件顺序写反，
 * 就会退化成「必须同时满足两条来源」或「IM 也必须过 MCP 白名单」。
 *
 * <p>现在只保留一个入口
 * {@link WebSocketPermissionPrompter#evaluateChannelRelayEligibility}，无论 IM 还是 MCP 来源，
 * 都返回本结构：<b>来源类型 + 能力声明 + 是否合格 + 原因码</b>（+ 诊断 detail）。
 *
 * <p>两条来源的求值口径<b>各自独立</b>：
 * <ul>
 *   <li>{@link Source#IM} —— 按<b>会话绑定</b>的聊天求值（{@code imNotifier.boundChats}），
 *       <b>不</b>走 MCP 白名单；</li>
 *   <li>{@link Source#MCP} —— 按<b>白名单 + 双 capability</b>求值
 *       （{@code filterPermissionRelayClients}）。</li>
 * </ul>
 */
public record ChannelRelayEligibility(
    Source source,
    Capabilities capabilities,
    boolean qualified,
    Reason reason,
    String detail) {

    /** 合格来源类型。 */
    public enum Source {
        /** 本会话绑定的原生 IM 聊天。 */
        IM,
        /** 合格的 MCP channel server（connected + allowlist + 双 capability）。 */
        MCP,
        /** 没有来源（或未检查）。 */
        NONE
    }

    /**
     * 能力声明 · 两种来源共用同一形状。
     *
     * @param permissionRelay 该来源能否承接权限中继（yes/no 回执）
     */
    public record Capabilities(boolean permissionRelay) {
        public static final Capabilities NONE = new Capabilities(false);
        public static final Capabilities RELAY = new Capabilities(true);
    }

    /** 资格原因码（日志 / 测试断言 / 后续前端提示共用，不再用自由文本判断）。 */
    public enum Reason {
        /** 合格：本会话已绑定 IM 聊天。 */
        QUALIFIED_IM_BOUND,
        /** 合格：存在合格 MCP channel server。 */
        QUALIFIED_MCP_SERVER,
        /** 合格：MCP 池未装配（测试 seam / 无 MCP 场景）⇒ 不做来源阻断，能力未经检查。 */
        QUALIFIED_SOURCE_NOT_CHECKED,
        /** 不合格：settings 两道门未同时开启。 */
        NOT_SETTINGS_GATE,
        /** 不合格：本会话未绑定渠道。 */
        NOT_SESSION_UNBOUND,
        /** 不合格：IM 未绑定且无合格 channel server。 */
        NOT_NO_SOURCE
    }

    public static ChannelRelayEligibility qualified(Source source, Capabilities capabilities,
                                                    Reason reason, String detail) {
        return new ChannelRelayEligibility(source, capabilities, true, reason, detail);
    }

    public static ChannelRelayEligibility rejected(Reason reason, String detail) {
        return new ChannelRelayEligibility(Source.NONE, Capabilities.NONE, false, reason, detail);
    }
}
