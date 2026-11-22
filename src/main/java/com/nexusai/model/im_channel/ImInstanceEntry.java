package com.nexusai.model.im_channel;

/**
 * IM 渠道实例（domain record）。
 *
 * <p><b>语义</b>：一个实例 = 一条到某个平台的连接（一个微信号 / 一个飞书租户）。
 * 实例<b>不归属任何会话</b> —— 任何会话都能用它发消息（"发送即接线"）。
 * 接线关系在 {@code mcp_channel_binding}（V255），不在本实体上。
 *
 * <p><b>credential 不出实体</b>：本 record 不含凭证明文/密文，避免它随 DTO
 * 一路序列化到前端。需要读凭证的场景（发消息/建连）走
 * {@code ImChannelService.credentialOf(id)} 单点读取。
 *
 * @param platform      wechat | feishu | wecom | dingtalk
 * @param status        connected | disconnected | error
 * @param statusDetail  断线原因/最近错误（给人看的一句话，可空）
 * @param configJson    平台特有开关（autoReply / groupAtOnly …），原样 JSON
 */
public record ImInstanceEntry(
    String id,
    String platform,
    String displayName,
    String status,
    String statusDetail,
    String lastActiveAt,
    String configJson,
    String createdAt,
    String updatedAt
) {}
