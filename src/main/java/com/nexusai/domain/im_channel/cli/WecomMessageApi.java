package com.nexusai.domain.im_channel.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.springframework.stereotype.Component;

/**
 * 企业微信消息发送（{@code POST /cli/message/send}）。
 *
 * <h2>协议（官方 CLI 的 {@code service_message.json} schema 逐字）</h2>
 * <pre>
 * 业务体（会被 {@link WecomCliClient} 包成 payload 信封）：
 * {"chat_id":"&lt;chatId&gt;","msg_type":"text","text":{"content":"&lt;文本&gt;"}}
 * </pre>
 * <ul>
 *   <li>{@code chat_id}：单聊传接收成员的 userid，群聊传群会话 ID ——
 *       <b>群聊与单聊是同一个字段</b>，所以本类不需要区分会话类型。</li>
 *   <li>{@code msg_type}：目前 schema 的 enum 只有 {@code text}。</li>
 *   <li>{@code text.content}：最长 2048 字符（schema {@code TextContent.content.maxLength}）。</li>
 * </ul>
 *
 * <h2>为什么返回值是 {@code null}</h2>
 * 发送响应 {@code SendMessageRsp} <b>没有任何业务字段</b>（schema 原文：「成功与否由框架外壳的
 * errcode / errmsg 表达，无业务字段」）⇒ 平台<b>不返回 message_id</b>。
 * ⛔ 绝不编造一个 id 出来 —— 编出来的 id 会让调用方以为「能按 id 撤回/查状态」，
 * 而这个 id 在平台侧根本不存在。
 *
 * <h2>失败语义</h2>
 * 平台/业务失败统一抛 {@link ImSendException}，语义映射集中在
 * {@link WecomCliClient#failureOf}（853004 token 失效 / 853008 不在最近会话 / -14 登录态失效）。
 */
@Component
public class WecomMessageApi {

    /** 发送文本的端点（官方 schema 的 {@code message.send} ⇒ {@code /message/send}）。 */
    public static final String PATH_SEND = "/cli/message/send";

    /**
     * 机器人身份发消息端点（schema {@code message.aibot.send} ⇒ {@code /message/aibot/send}）。
     *
     * <h3>与 {@link #PATH_SEND} 的分工（官方 CLI {@code wecomcli-message} 技能的用法）</h3>
     * 官方 CLI 的 {@code message aibot send} 只支持 {@code msg_type=markdown|image|file|voice|video}
     * —— **没有 text**，而且它的 {@code chat_id} 明确允许取「授权人 ID」
     * （{@code identity whoami} 的返回值）或 {@code sessions list} 的 {@code chat_id}。
     * 这正好解决企微单聊的入站/同步身份分叉：入站帧里只有 {@code from.userid}，
     * 走本端点时可以直接当 chat_id 用。
     */
    public static final String PATH_AIBOT_SEND = "/cli/message/aibot/send";

    /** markdown 正文上限（官方技能文档：20480 UTF-8 字节）。 */
    public static final int MAX_MARKDOWN_BYTES = 20480;

    /** 文本长度上限：schema {@code TextContent.content.maxLength} = 2048。 */
    public static final int MAX_TEXT_CHARS = 2048;

    /** {@code msg_type} 的 {@code text} 取值（schema enum 逐字）。 */
    private static final String MSG_TYPE_TEXT = "text";

    private final WecomCliClient client;
    private final ObjectMapper json = new ObjectMapper();

    /** 构造注入（不 new 客户端：HttpClient 要复用）。 */
    public WecomMessageApi(WecomCliClient client) {
        if (client == null) {
            throw new IllegalArgumentException("WecomCliClient 必填");
        }
        this.client = client;
    }

    /**
     * 发一条文本消息。
     *
     * @param token  CLI token（{@link WecomCliTokenService} 换得）
     * @param chatId 会话 id（单聊 userid / 群会话 id，同一个字段）
     * @param text   文本正文（≤ {@value #MAX_TEXT_CHARS}）
     * @return 恒为 {@code null}（企微响应没有消息 id，⛔ 不编造）
     * @throws ImSendException 入参不合法（不发请求）或平台/网络失败
     */
    public String sendText(String token, String chatId, String text) throws ImSendException {
        if (chatId == null || chatId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微 chat_id 为空");
        }
        if (text == null || text.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微消息内容为空");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            // 防御性断言：模板（AbstractImSendStrategy）已按能力上限截断，
            // 真到这里说明调用方绕过了模板 —— 当编程错误处理，不静默截断。
            throw new ImSendException("IM_PARAM_INVALID",
                    "企微文本超长：" + text.length() + " > " + MAX_TEXT_CHARS + "（schema maxLength）");
        }

        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("msg_type", MSG_TYPE_TEXT);
        body.putObject("text").put("content", text);

        WecomCliClient.Result result = client.call(token, PATH_SEND, body);
        if (!result.ok()) {
            // 语义映射（853004 / 853008 / -14 …）集中在客户端一处，避免各调用点各写一套。
            throw WecomCliClient.failureOf(result, "企微发送消息");
        }
        // 成功：企微不回 message_id ⇒ 返回 null（调用方据此知道「发出去了但没有 id」）。
        return null;
    }

    /**
     * 以**机器人身份**发一条 Markdown 消息（{@code /cli/message/aibot/send}）。
     *
     * <h2>为什么需要这条（而不是都用 {@link #sendText}）</h2>
     * {@link #sendText} 走 {@code /cli/message/send}，它的 {@code chat_id} 必须是**真实会话 id**
     * （单聊要对端的 chatid / 群聊的群 id）。而企微**单聊入站帧里没有 chatid**
     * （只有 {@code from.userid}）⇒ 用 userid 打 {@code /cli/message/send} 会回
     * {@code 40073 非法的 chat_id}（真机实测）。
     * <p>官方 CLI 的 {@code message aibot send} 明确允许 {@code chat_id} 取
     * 「授权人 ID」⇒ 本方法就是那条路。
     *
     * @param token  CLI token
     * @param chatId 会话 id（**可以是授权人 userid**）
     * @param markdown 正文（普通文本也按 markdown 发 —— 官方技能文档原话）
     */
    public String sendMarkdown(String token, String chatId, String markdown) throws ImSendException {
        if (chatId == null || chatId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微 chat_id 为空");
        }
        if (markdown == null || markdown.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微消息内容为空");
        }
        int bytes = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_MARKDOWN_BYTES) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "企微 markdown 超长：" + bytes + " > " + MAX_MARKDOWN_BYTES + " 字节");
        }

        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("msg_type", "markdown");
        body.putObject("markdown").put("content", markdown);

        WecomCliClient.Result result = client.call(token, PATH_AIBOT_SEND, body);
        if (!result.ok()) {
            throw WecomCliClient.failureOf(result, "企微发送 Markdown");
        }
        return null;
    }

    /** 机器人会话列表端点（schema {@code message.aibot.sessions.list}）。 */
    public static final String PATH_SESSIONS_LIST = "/cli/message/aibot/sessions/list";

    /**
     * 拉「机器人最近的会话」列表（最多 20 个，按最后消息时间倒序）。
     *
     * <h2>WHY 这个列表是「发消息的唯一入口」</h2>
     * schema 明确写：{@code AibotSessionItem.chat_id} 是「**框架自动加密输出**」，
     * 才可以直接当 {@code aibot/send} 的 chat_id 用。而入站帧里的 {@code from.userid}
     * 是**明文**，直接当 chat_id 会回 {@code 40073}（真机实测）。
     * <p>⇒ 要按 userid 反查可发送的 chat_id，只能从这里拿。
     *
     * <p>列表只含**机器人参与过的**会话；用户自己给自己发消息那种会话不在其中
     * （那种会话机器人本来就发不了）。
     */
    public com.fasterxml.jackson.databind.JsonNode listSessions(String token) throws ImSendException {
        WecomCliClient.Result result = client.call(token, PATH_SESSIONS_LIST, json.createObjectNode());
        if (!result.ok()) {
            throw WecomCliClient.failureOf(result, "企微查询会话列表");
        }
        return result.result() == null ? json.createObjectNode() : result.result();
    }
}
