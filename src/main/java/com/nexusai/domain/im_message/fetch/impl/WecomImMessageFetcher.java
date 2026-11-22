package com.nexusai.domain.im_message.fetch.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.domain.im_message.fetch.ImFetchWindow;
import com.nexusai.domain.im_message.fetch.ImMessageFetchException;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.model.im_message.ImMessageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 企微消息拉取器 —— {@code POST /cli/chat/messages/list}（走 {@link WecomCliClient}）。
 *
 * <h2>协议（官方 CLI 逐字）</h2>
 * <pre>
 * body: {"chat_id":"…","begin_time":"YYYY-MM-DD HH:MM:SS","end_time":"…","cursor":"…"}
 *   ← ⛔ 不自己包 payload 信封：{@link WecomCliClient#call} 会自动包成
 *     {"payload":"&lt;业务 JSON 字符串&gt;"}（PIT-WC-022，不包会回 -1「系统失败」）
 * rsp : {"errcode":0,"errmsg":"ok","results_json":"…"}  ← 由 WecomCliClient 逐层剥壳
 *       业务体：messages[] / messages_count / has_more / next_cursor
 * </pre>
 *
 * <h2>🔴 企微的两个硬天花板（必须写进注释，否则下一个人会误判）</h2>
 * <ol>
 *   <li><b>只有最近 7 天</b>：{@code begin_time} 逐字「仅支持拉取最近 7 天的消息」
 *       ⇒ 超窗的请求不是错误，是「那段平台侧根本没有」⇒ 本类把区间<b>收敛</b>到 7 天内
 *       （再留 {@value #WINDOW_SAFETY_MARGIN_MINUTES} 分钟余量，否则正好 7 天前会撞线报 850016），
 *       并通过 {@link #window()} 告诉上层「要给用户一句说明」。</li>
 *   <li><b>读不到机器人自己发的消息</b>（PIT-WC-017）：{@code chat/messages/list} 返回的是
 *       「<b>用户视角</b>的消息流」，bot 用 {@code aibot/send} 发出去的不在这个流里
 *       ⇒ 本类返回的 {@code direction} <b>一律 {@code in}</b>。
 *       缺口由「发送时补写 out」那一层补（把 {@code direction='out'} / {@code origin='local'}
 *       的行自己写进 {@code im_message}）。</li>
 * </ol>
 *
 * <h2>🔴 40073 = 「这条会话拉不了」，不是「平台挂了」</h2>
 * {@code code=40073 非法的 'chat_id'} 的真因是<b>会话 id 形态不对</b>：
 * 入站事件带的是明文 {@code from.userid}（只能收、不能当 chat_id），或库里残留着历史测试数据。
 * ⇒ 本类抛<b>可识别</b>的异常（{@link ImMessageFetchException#CODE_INVALID_CHAT_ID} + 平台码 40073），
 * 让上层写出一句用户能照着做的提示（⛔ 不静默吞掉，也不当成整实例失败）。
 *
 * <h2>🔴 企微消息没有 messageId</h2>
 * {@code ChatMessage} 的字段全集 = {@code msg_type / send_time / text / userid / user_name /
 * image / file / voice / video / mixed} ⇒ <b>没有 messageId</b>，且 {@code send_time} 只到<b>秒</b>。
 * ⇒ {@code messageId} 传 {@code null}，由 {@code ImMessageService.dedupKey} 走
 * {@code (chat_id|sent_at|sender_id|content)} 的 hash 分支。
 * ⚠️ 副作用（同一人<b>同一秒</b>发<b>完全相同</b>的两条会被当成一条）—— 已知且接受。
 */
@Component
public class WecomImMessageFetcher implements ImMessageFetcher {

    private static final Logger log = LoggerFactory.getLogger(WecomImMessageFetcher.class);

    public static final String PLATFORM = "wecom";

    /** 消息列表端点（{@code /cli/*} 网关下）。 */
    public static final String PATH_MESSAGES_LIST = "/cli/chat/messages/list";

    /** 企微 {@code begin_time} 的窗口：逐字「仅支持拉取最近 7 天」。 */
    public static final int HISTORY_WINDOW_DAYS = 7;

    /**
     * 收敛余量（分钟）：请求起点正好落在「7 天前」会<b>撞线</b> ——
     * 平台按自己的时钟判（{@code code=850016 begin_time不能早于7天前}），
     * 加上网络延迟，差一秒就整轮失败 ⇒ 往窗口里收 10 分钟。
     */
    public static final int WINDOW_SAFETY_MARGIN_MINUTES = 10;

    /**
     * 企微错误码：<b>非法的 {@code chat_id}</b>。
     *
     * <p>真实场景：入站事件带的是<b>明文 {@code from.userid}</b>（只能收、不能当 chat_id 用），
     * 或库里残留着历史测试数据（{@code chatid-e2e-probe}）。
     * ⇒ 这个码<b>不是</b>「平台挂了」，而是「这条会话压根拉不了」⇒ 单独识别，往上抛可识别的异常。
     */
    public static final int CODE_INVALID_CHAT_ID = 40073;

    /** 企微时间格式：{@code YYYY-MM-DD HH:MM:SS}（PIT-WC-028：不是毫秒！）。 */
    private static final DateTimeFormatter WECOM_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ImMessageTime.CANONICAL_OFFSET);

    /** 页数硬保护：服务端 {@code has_more} 恒 true 时不至于死循环。 */
    private static final int MAX_PAGES = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ImChannelService channelService;
    @Autowired private WecomCliTokenService tokenService;
    @Autowired private WecomCliClient cliClient;

    @Override
    public String platform() {
        return PLATFORM;
    }

    /**
     * 企微的 7 天窗口。
     *
     * <p>{@code earliestSince} 取「现在 − 7 天 + {@value #WINDOW_SAFETY_MARGIN_MINUTES} 分钟余量」
     * （按规范偏移的<b>秒</b>精度，与平台口径对齐）⇒ 声明值 = 实际请求值，两边不会打架。
     */
    @Override
    public ImFetchWindow window() {
        return new ImFetchWindow(earliestFetchableSince(),
            "企微仅能补拉最近 " + HISTORY_WINDOW_DAYS + " 天（begin_time 官方限制），"
                + "更早的消息只能来自本地记录");
    }

    /**
     * 实际可请求的最早时刻 = 「7 天前 + 10 分钟余量」。
     *
     * <p>⛔ 余量是必需的：正好 7 天前会和平台时钟/网络延迟撞线，差一秒就报
     * {@code 850016 begin_time不能早于7天前}（真机踩过）。
     */
    private static String earliestFetchableSince() {
        return ImMessageTime.canonicalFromEpochSeconds(
            Instant.now().minus(HISTORY_WINDOW_DAYS, ChronoUnit.DAYS)
                .plus(WINDOW_SAFETY_MARGIN_MINUTES, ChronoUnit.MINUTES)
                .getEpochSecond());
    }

    @Override
    public List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso,
                                      String untilIso, int maxMessages) throws ImMessageFetchException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ImMessageFetchException("IM_PARAM_INVALID", "企微拉取消息失败：instanceId 为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new ImMessageFetchException("IM_PARAM_INVALID", "企微拉取消息失败：chatId 为空");
        }

        String token = cliToken(instanceId);
        String begin = clampToWindow(sinceIso);
        String end = untilIso == null || untilIso.isBlank()
            ? ImMessageTime.canonicalNow()
            : ImMessageTime.canonicalOrSelf(untilIso);

        List<ImMessageEntry> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode body = JSON.createObjectNode();
            body.put("chat_id", chatId);
            body.put("begin_time", toWecomTime(begin));
            body.put("end_time", toWecomTime(end));
            if (cursor != null && !cursor.isBlank()) {
                body.put("cursor", cursor);
            }

            WecomCliClient.Result r;
            try {
                r = cliClient.call(token, PATH_MESSAGES_LIST, body);
            } catch (ImSendException e) {
                throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                    "企微拉取消息失败：" + e.getMessage(), e.platformCode(), e.retryable());
            }
            if (r == null || !r.ok()) {
                throw toFetchException(r, chatId);
            }

            JsonNode located = locateMessages(r.result());
            if (located == null) {
                break;
            }
            for (JsonNode m : located.path("messages")) {
                ImMessageEntry e = toEntry(instanceId, chatId, m);
                if (e != null) {
                    out.add(e);
                }
            }
            if (out.size() >= maxMessages) {
                break;
            }
            boolean hasMore = located.path("has_more").asBoolean(false)
                || r.result().path("has_more").asBoolean(false);
            String next = firstNonBlank(located.path("next_cursor").asText(""),
                r.result().path("next_cursor").asText(""));
            if (!hasMore || next.isBlank() || next.equals(cursor)) {
                break;
            }
            cursor = next;
        }
        log.info("[ImFetch] 企微消息 instance={} chat={} rows={}", instanceId, chatId, out.size());
        return out;
    }

    // ───────────────────────── 内部 ─────────────────────────

    /**
     * 把平台失败结果映射成「可识别」的拉取异常。
     *
     * <p>🔴 {@code 40073}（非法的 {@code chat_id}）单独识别：
     * 它不是「平台挂了」，而是「这个会话 id 形态不对，压根拉不了」
     * （入站明文 {@code from.userid} / 历史测试数据）
     * ⇒ 往上抛带统一错误码 {@link ImMessageFetchException#CODE_INVALID_CHAT_ID} 的异常，
     * 让上层能写出一句用户看得懂、也能照着做的提示（⛔ 不静默吞掉）。
     */
    private static ImMessageFetchException toFetchException(WecomCliClient.Result r, String chatId) {
        if (r == null) {
            return new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                "企微拉取消息被拒：空结果", null, false);
        }
        int code = r.platformCode();
        if (code == CODE_INVALID_CHAT_ID) {
            return new ImMessageFetchException(ImMessageFetchException.CODE_INVALID_CHAT_ID,
                "企微会话 id 不是合法 chat_id（code=" + CODE_INVALID_CHAT_ID + "），拉不了该会话历史: " + chatId,
                String.valueOf(code), false);
        }
        return new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
            "企微拉取消息被拒：" + r.describe(), String.valueOf(code), false);
    }

    /** 换 CLI token（bot 凭证签名派生）。失败 ⇒ 受检异常（不吞）。 */
    private String cliToken(String instanceId) throws ImMessageFetchException {
        String credential = channelService.credentialOf(instanceId).orElseThrow(
            () -> new ImMessageFetchException("IM_INSTANCE_NOT_FOUND",
                "渠道实例不存在或没有凭证：" + instanceId));
        String botId;
        String botSecret;
        try {
            JsonNode root = JSON.readTree(credential);
            botId = text(root, "botId", "botid");
            botSecret = text(root, "botSecret", "secret");
        } catch (Exception e) {
            throw new ImMessageFetchException("IM_CREDENTIAL_INVALID", "企微凭证不是合法 JSON");
        }
        if (botId == null || botSecret == null) {
            throw new ImMessageFetchException("IM_CREDENTIAL_INVALID", "企微凭证缺少 botId/botSecret");
        }
        try {
            return tokenService.tokenFor(instanceId, botId, botSecret);
        } catch (ImSendException e) {
            throw new ImMessageFetchException("IM_CREDENTIAL_EXCHANGE_FAILED",
                "企微换取 CLI token 失败：" + e.getMessage(), e.platformCode(), e.retryable());
        }
    }

    /**
     * 把区间起点收敛到平台窗口内（7 天 + 余量）。
     *
     * <p>⛔ <b>不抛异常</b>：超窗不是错误，是「平台侧没有那段历史」⇒ 由上层给一句说明即可。
     * <p>⚠️ {@code sinceIso} 为空（首次扫描）⇒ 直接用「7 天前 + 余量」。
     */
    private String clampToWindow(String sinceIso) {
        String earliest = earliestFetchableSince();
        String since = sinceIso == null || sinceIso.isBlank()
            ? earliest
            : ImMessageTime.canonicalOrSelf(sinceIso);
        if (since == null) {
            return earliest;
        }
        if (earliest == null) {
            return since;
        }
        Instant a = ImMessageTime.toInstant(since);
        Instant b = ImMessageTime.toInstant(earliest);
        if (a == null || b == null) {
            return since;
        }
        return a.isBefore(b) ? earliest : since;
    }

    /** 规范 ISO → 企微要的 {@code yyyy-MM-dd HH:mm:ss}（按规范偏移的本地时间）。 */
    private static String toWecomTime(String canonicalIso) {
        Instant at = ImMessageTime.toInstant(canonicalIso);
        if (at == null) {
            return WECOM_TIME.format(Instant.now().atOffset(ZoneOffset.ofHours(8)));
        }
        return WECOM_TIME.format(at.atOffset(ImMessageTime.CANONICAL_OFFSET));
    }

    /** 消息项 → domain record；时间解析不出来 ⇒ 返回 {@code null}（丢弃，不猜）。 */
    private ImMessageEntry toEntry(String instanceId, String chatId, JsonNode m) {
        if (m == null || !m.isObject()) {
            return null;
        }
        String sentAt = ImMessageTime.canonical(m.path("send_time").asText(""));
        if (sentAt == null) {
            log.warn("[ImFetch] 企微消息 send_time 无法解析，丢弃该条: {}", m.path("send_time").asText(""));
            return null;
        }
        String msgType = m.path("msg_type").asText("");
        // 🔴 `text` 是**对象** `{content: "..."}`，不是字符串。
        //    直接 `m.path("text").asText("")` 对对象返回空串 ⇒ 真机表现为
        //    「消息都在、media 也在，但正文全是空的」，而且 dedupKey 里的 content
        //    也变成空串 ⇒ 同一人同一秒的两条不同内容会被误判成重复。
        String text = m.path("text").path("content").asText("");
        if (text.isEmpty() && m.path("text").isTextual()) {
            // 兼容：万一某天平台改成直接给字符串（或历史报文差异）
            text = m.path("text").asText("");
        }
        String mediaJson = mediaJson(m);

        return new ImMessageEntry(
            null,                                   // 主键由持久化层生成
            instanceId,
            PLATFORM,
            chatId,
            null,                                   // ⭐ 企微没有 messageId ⇒ 走 hash 幂等键
            m.path("userid").asText(""),
            m.path("user_name").asText(""),
            msgType,
            text,
            mediaJson,
            sentAt,
            "in",                                   // ⭐ 企微读不到 bot 自己发的消息 ⇒ 一律 in
            "official");
    }

    /**
     * 媒体字段 → {@code media_json}（JSON 数组，元素同 {@code InboundMessage.Media}：kind/fileName/ref）。
     *
     * <p>企微 {@code ChatMessage} 的媒体字段全集 = {@code image / file / voice / video / mixed}
     * （{@code text} 之外）。⛔ 只存平台引用，⛔ 不存二进制。
     */
    private String mediaJson(JsonNode m) {
        String[] kinds = {"image", "file", "voice", "video", "mixed"};
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String kind : kinds) {
            JsonNode v = m.get(kind);
            if (v == null || v.isNull()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"kind\":").append(quote(kind))
              .append(",\"fileName\":").append(quote(fileNameOf(v)))
              .append(",\"ref\":").append(quote(refOf(v)))
              .append('}');
        }
        sb.append(']');
        return first ? null : sb.toString();
    }

    /** 媒体节点里的文件名（尽力取，取不到就 null —— 不猜）。 */
    private static String fileNameOf(JsonNode v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return firstNonBlank(text(v, "file_name"), text(v, "filename"), text(v, "name"));
    }

    /** 媒体节点里的引用（平台 key / url / media_id …）。 */
    private static String refOf(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isObject()) {
            String named = firstNonBlank(text(v, "file_key"), text(v, "image_key"),
                text(v, "media_id"), text(v, "url"), text(v, "key"), text(v, "id"));
            if (named != null) {
                return named;
            }
        }
        String raw = v.toString();
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }

    /**
     * 在解包后的业务体里找到「带 messages 的那一层」。
     *
     * <p>企微的响应层级不固定（{@code results_json → result → …}），而 {@link WecomCliClient}
     * 只剥到「能看见业务字段」为止 ⇒ 这里再兜一层「找 messages 数组」。
     */
    private static JsonNode locateMessages(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (node.has("messages")) {
            return node;
        }
        for (String key : new String[]{"result", "data", "payload"}) {
            JsonNode child = node.get(key);
            if (child != null && !child.isNull()) {
                JsonNode found = locateMessages(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 取第一个非空文本字段（兼容蛇形/驼峰两种历史写法）。 */
    private static String text(JsonNode root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText().trim();
            }
        }
        return null;
    }

    /** JSON 字符串字面量（null ⇒ JSON null）。 */
    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
