package com.nexusai.model.im_message;

/**
 * 统一消息条目（domain record）· V315 {@code im_message}。
 *
 * <p><b>为什么要有这张统一表</b>：三平台的「读历史」能力差得很远（飞书能翻到底、
 * 企微 {@code chat/messages/list} 只有最近 7 天、微信在本机库里），
 * 而且企微<b>读不到机器人自己发出去的消息</b>（那不是「用户视角的消息流」）
 * ⇒ 只有「我们自己落库」才能给 agent 一个三平台一致、且不丢历史的视图。
 *
 * <p><b>为什么不带 dedupKey / fetchedAt</b>：这两个是**持久化细节**，对调用方
 * （拉取层 / 工具层）没有意义：
 * <ul>
 *   <li>{@code dedupKey} 是幂等键，由 {@code ImMessageService.dedupKey(...)} 统一生成；
 *       让调用方自己拼 = 拼错一个字段 ⇒ 幂等失效 ⇒ 重复拉取会插两条（静默脏数据）。</li>
 *   <li>{@code fetchedAt} 记的是「我们什么时候把它抓下来的」，属排障信息，不是业务语义。</li>
 * </ul>
 * 分寸参照 {@code ImChatEntry}：技术列留在持久化层，业务语义进 domain record。
 *
 * @param platform  wechat | feishu | wecom | dingtalk（与 {@code im_channel_instance.platform} 同口径）
 * @param chatId    平台侧会话 id（微信 wxid / 企微加密 chatid / 飞书 oc_xxx）
 * @param messageId 平台消息 id（飞书 om_xxx / 微信 msg_id）；⚠️ 企微恒为 null
 * @param msgType   text | image | file | voice | video | mixed（平台值直接用，不重命名）
 * @param mediaJson 媒体引用（JSON 数组字符串，元素结构同 {@code InboundMessage.Media}：kind/fileName/ref）
 * @param sentAt    平台侧发送时间（ISO-8601 文本）
 * @param direction in（别人发的）| out（我们自己发的）—— 补「企微读不到 bot 自己消息」的缺口
 * @param origin    official（从平台接口拉的）| local（我们自己发送时写的）—— 与 platform 是两个维度
 */
public record ImMessageEntry(
    String id,
    String instanceId,
    String platform,
    String chatId,
    String messageId,
    String senderId,
    String senderName,
    String msgType,
    String content,
    String mediaJson,
    String sentAt,
    String direction,
    String origin
) {
    /** 是否是我们自己发出的消息（{@code direction='out'}）。 */
    public boolean outbound() {
        return "out".equals(direction);
    }
}
