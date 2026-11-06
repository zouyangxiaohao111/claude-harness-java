package com.nexusai.model.mcp_channel_binding;

/**
 * Domain entity: 外部渠道 ↔ 会话 绑定条目（{@code mcp_channel_binding} 表行）。
 *
 * <p><b>WHY 独立成表而不是复用 {@code mcp_channel_allowlist}</b>：后者是
 * <b>plugin 级「允许清单」</b>（对齐 CC GrowthBook 'tengu_harbor_ledger'，语义 =「这个插件
 * 被批准可以当 channel」）；本表是<b>运行时绑定状态</b>（语义 =「这个渠道此刻归哪个会话」）。
 * 两者混用会让「批准」与「占用」互相污染（批准 ≠ 占用，占用 ≠ 批准）。
 *
 * <p><b>为什么必须落库而不是进程内 Map</b>（对齐 CC 的一处**有意分歧**）：
 * CC 的白名单是进程级单例（{@code Rt.allowedChannels}），进程活多久它就在多久；
 * 本仓 MCP 连接本身是全局单例，但**会话是持久的**（可关页面、可重启后端），
 * 绑定关系必须跟着会话活过进程重启 ⇒ 落 {@code mcp_channel_binding} 表。
 *
 * @param id         主键（形如 {@code chb-xxxxxxxx}）
 * @param channelId  渠道 id（标准词表：wechat / feishu / wecom / ... / custom:<name>）·
 *                   <b>互斥键</b>——同一 channelId 全局只能绑一个会话
 * @param serverName 承载该渠道的 MCP server 名（CC original: server.name）
 * @param sessionId  归属会话 id（消息投给谁；CC 用 ambient 的 {@code agentId: ci()}，
 *                   本仓多会话必须显式）
 * @param boundAt    绑定时间（ISO 8601）
 */
public record ChannelBindingEntry(
    String id,
    String channelId,
    String serverName,
    String sessionId,
    String boundAt
) {
}
