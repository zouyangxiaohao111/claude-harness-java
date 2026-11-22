package com.nexusai.domain.im_channel.inbound.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 企微智能机器人 WebSocket 帧协议 —— **纯手写，只依赖 Jackson**（不引任何第三方协议 SDK）。
 *
 * <h2>协议事实</h2>
 * <pre>
 * 端点  wss://openws.work.weixin.qq.com
 * 帧    {"cmd": "...", "headers": {"req_id": "..."}, "body": {...}}
 * cmd   aibot_subscribe | aibot_msg_callback | aibot_event_callback
 *       aibot_respond_msg | ping
 * </pre>
 *
 * <h2>四条必须刻在脑子里的协议事实</h2>
 * <ol>
 *   <li><b>PIT-WC-006</b>：{@code req_id} 在 {@code headers} 里，**不在 body**。
 *       解析时先取 {@code headers.req_id}，永远不要从 body 里找。</li>
 *   <li><b>PIT-WC-005</b>：回复必须是 <b>stream 形态</b>
 *       （{@code msgtype=stream} + {@code stream.id/finish/content}），
 *       即使一次性发完也要是 {@code finish=true} 的单帧流。企微没有「发一条普通文本」这种回包。</li>
 *   <li><b>PIT-WC-004</b>：凭证是 {@code bot_id} + {@code bot_secret}；
 *       但鉴权帧 body 里的字段名是 {@code secret}（协议规定）—— 键名与字段名不是一回事。</li>
 *   <li>心跳 {@code ping} 帧**没有 body**（只有 cmd + headers.req_id）。</li>
 * </ol>
 *
 * <h2>为什么自己写而不是用官方 SDK</h2>
 * 官方第三方 SDK（{@code wx-aibot-java-sdk}）是个人项目、协议一改就得等作者跟（PIT-WC-009），
 * 而协议本身只有 5 个命令字的 JSON 帧 ⇒ 自己手写约 100 行就够，且能精确控制
 * 「{@code req_id} 放哪」「回复是不是 stream」这些已经踩过坑的细节。
 *
 * <h2>本类只管「帧 ⇄ JSON」</h2>
 * 连接/鉴权/心跳/重连在 {@link WecomWsClient}；
 * 帧 → 统一消息在 {@link WecomInboundListener}。三段各管一件事，改协议只动本类。
 */
public final class WecomWsProtocol {

    /** 企微长连接端点。 */
    public static final String WS_ENDPOINT = "wss://openws.work.weixin.qq.com";

    /** 鉴权帧（连接后第一帧必须是它）。 */
    public static final String CMD_SUBSCRIBE = "aibot_subscribe";
    /** 消息回调帧（入站正文）。 */
    public static final String CMD_MSG_CALLBACK = "aibot_msg_callback";
    /** 事件回调帧（进群、卡片点击等）。 */
    public static final String CMD_EVENT_CALLBACK = "aibot_event_callback";
    /** 回复帧。 */
    public static final String CMD_RESPOND = "aibot_respond_msg";
    /** 应用层心跳帧。 */
    public static final String CMD_PING = "ping";

    /**
     * 帧编解码用的 mapper。
     *
     * <p>关掉 {@code FAIL_ON_EMPTY_BEANS}：空 body 的 {@code ping} 帧序列化成 {@code {}} 而不是报错。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private WecomWsProtocol() {
    }

    /**
     * 鉴权帧：body = {@code {bot_id, secret}}。
     *
     * <p>⚠️ <b>PIT-WC-004</b>：body 里的字段名是 {@code secret}（协议规定），
     * 而我们库里存的凭证键名是 {@code botSecret} —— 两者不要混。
     *
     * @param botId  机器人 bot_id（必填）
     * @param secret 机器人 bot_secret（必填）
     */
    public static ObjectNode subscribe(String botId, String secret) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("bot_id", botId);
        body.put("secret", secret);
        return frame(CMD_SUBSCRIBE, CMD_SUBSCRIBE + "_" + System.nanoTime(), body);
    }

    /** 心跳帧：只有 cmd + headers.req_id，**无 body**。 */
    public static ObjectNode ping() {
        return frame(CMD_PING, CMD_PING + "_" + System.nanoTime(), null);
    }

    /**
     * 回复帧（stream 形态）。
     *
     * <p>🔴 <b>PIT-WC-005</b>：即便一次性发完，也必须包成 stream 帧且 {@code finish=true}。
     * 用「普通文本帧」回复 ⇒ 企微收不到内容（且不报错，属于最难查的那种失败）。
     *
     * @param reqId    必须原样带回入站消息帧的 {@code headers.req_id}（PIT-WC-006）
     * @param streamId 同一 token 的多帧共用一个 id（本仓一次性发完 ⇒ 可随机）
     * @param content  正文
     * @param finish   是否最后一片
     */
    public static ObjectNode respondStream(String reqId, String streamId, String content, boolean finish) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("msgtype", "stream");
        ObjectNode stream = body.putObject("stream");
        stream.put("id", streamId);
        stream.put("finish", finish);
        stream.put("content", content == null ? "" : content);
        return frame(CMD_RESPOND, reqId, body);
    }

    /**
     * 取帧里的 {@code req_id}。
     *
     * <p><b>PIT-WC-006</b>：它在 {@code headers} 里，不在 body。
     *
     * @return {@code req_id}；缺失/空 ⇒ 返回 null（调用方据此判断「这帧没法回复」）
     */
    public static String reqIdOf(JsonNode frame) {
        if (frame == null || !frame.isObject()) {
            return null;
        }
        JsonNode headers = frame.path("headers");
        if (headers.isMissingNode() || headers.isNull()) {
            return null;
        }
        JsonNode v = headers.path("req_id");
        if (v.isMissingNode() || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 取帧的 {@code cmd}；缺失 ⇒ 空串（不抛异常）。 */
    public static String cmdOf(JsonNode frame) {
        if (frame == null || !frame.isObject()) {
            return "";
        }
        JsonNode v = frame.path("cmd");
        return (v.isMissingNode() || v.isNull() || !v.isValueNode()) ? "" : v.asText("");
    }

    /** 帧 → JSON 字符串。 */
    public static String write(ObjectNode frame) {
        try {
            return MAPPER.writeValueAsString(frame);
        } catch (Exception e) {
            throw new IllegalStateException("企微帧序列化失败", e);
        }
    }

    /**
     * JSON 字符串 → 帧。
     *
     * @return 解析结果；<b>不是合法 JSON ⇒ 返回 null</b>（调用方记日志后丢弃这一帧，
     *         ⛔ 不能让一条坏帧打断整个收包循环）。
     */
    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return (node == null || node.isMissingNode()) ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    /** 组一帧：{@code {cmd, headers:{req_id}, body?}}。 */
    private static ObjectNode frame(String cmd, String reqId, JsonNode body) {
        ObjectNode f = MAPPER.createObjectNode();
        f.put("cmd", cmd);
        ObjectNode h = f.putObject("headers");
        // PIT-WC-006：req_id 在 headers 里，不在 body
        h.put("req_id", reqId);
        if (body != null) {
            f.set("body", body);
        }
        return f;
    }
}
