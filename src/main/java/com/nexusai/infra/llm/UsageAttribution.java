package com.nexusai.infra.llm;

/**
 * 一次 LLM 调用的<b>归属</b>（会话 / agent / 归类 / 层级）· 请求网关记账的键。
 *
 * <p><b>为什么需要（用户 的原始诉求）</b>：用量统计前置到请求网关后，网关必须知道
 * 「这次调用属于哪个会话、哪个 agent、哪一类调用、层级多深」，否则无法把子代理 / fork /
 * 后台 / 旁路调用的用量归到正确的账目上。此前 {@code ModelRequest} 只带
 * {@code agentContext}（仅子代理归因用 invokingRequestId，不含 agentId/sessionId/层级，
 * 且主 agent 恒 null）⇒ 网关无从归属。
 *
 * <p><b>层级语义</b>：{@link #parentAgentId()} + {@link #depth()} 构成
 * session → 主 agent(0) → 子代理/fork(1) → 嵌套子代理(2)… 的树；账本按此树组织，
 * 「费用与 token 用量构成的层级信息」由此而来。{@link #child(String, UsageCategory, String)}
 * 供 spawn 点直接派生下一级归属（显式传参，不用 ThreadLocal —— 仓库铁律）。
 *
 * @param sessionId     归属会话（可 null = 无会话上下文，如未绑定会话的旁路调用）
 * @param agentId       归属 agent（主 agent = 会话短 id；子代理 = a+16hex；teammate = 全名）
 * @param category      归类维度（聚合键之一）
 * @param parentAgentId 父 agent id（null = 顶层，即主 agent 自身或独立旁路）
 * @param depth         层级深度（0 = 顶层 agent；子代理 1；嵌套子代理 2…）
 * @param label         可读标签（可 null）：**子代理/fork/队友 = 该次任务的名称**（CC AgentTool
 *                      {@code description}；取不到则回落 agent 类型名），旁路 = 用途 slug，
 *                      压缩 = 压缩标签，cron = workload 名
 */
public record UsageAttribution(
        String sessionId,
        String agentId,
        UsageCategory category,
        String parentAgentId,
        int depth,
        String label
) {

    /** 顶层 agent（主 agent）归属。 */
    public static UsageAttribution top(String sessionId, String agentId, UsageCategory category, String label) {
        return new UsageAttribution(sessionId, agentId, category, null, 0, label);
    }

    /** 无归属信息（调用方未接线时的兜底；统计接口按 UNKNOWN 归类，不伪造）。 */
    public static UsageAttribution unknown(String sessionId) {
        return new UsageAttribution(sessionId, null, UsageCategory.UNKNOWN, null, 0, null);
    }

    /** spawn 点派生下一级归属：parentAgentId = 本 agent，depth + 1。 */
    public UsageAttribution child(String childAgentId, UsageCategory childCategory, String childLabel) {
        return new UsageAttribution(sessionId, childAgentId, childCategory, agentId, depth + 1, childLabel);
    }

    /** 层级键（账本树节点 id）：agentId 优先，退化到 parentAgentId + category，最后到 "unknown"。 */
    public String nodeKey() {
        if (agentId != null && !agentId.isBlank()) {
            return agentId;
        }
        if (category != null && category != UsageCategory.UNKNOWN) {
            return "cat:" + category.name();
        }
        return "unknown";
    }
}
