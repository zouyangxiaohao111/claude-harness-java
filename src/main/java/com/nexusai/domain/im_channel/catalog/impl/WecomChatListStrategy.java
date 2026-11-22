package com.nexusai.domain.im_channel.catalog.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImChatCandidate;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategy;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 企微会话列表策略。
 *
 * <h2>为什么两个端点都要打</h2>
 * 企微的「会话」有两个互补的来源，缺一不可：
 * <ul>
 *   <li>{@code POST /cli/message/aibot/sessions/list} —— 机器人**最近参与过**的会话
 *       （单聊 + 群，无入参、无时间窗，最多 20 个）。
 *       ⛔ 它对某些群**不返回 {@code chat_name}**（实测：拉到的群名字就是一串 id）。</li>
 *   <li>{@code POST /cli/chat/groups/list} —— 按时间窗拉**有消息**的群，
 *       带 {@code chat_name}。⚠️ 只能覆盖窗口内（参考实现 7 天）有消息的群。</li>
 * </ul>
 * ⇒ 前者负责「找全会话」，后者负责「补名字」。
 * ⛔ <b>不要因为 sessions 拿到数据就提前 return</b> —— 那样界面上的群名就是一串 id
 * （这是仓里踩过的坑）。
 *
 * <h2>时间格式（PIT-WC-028）</h2>
 * {@code groups/list} 的 {@code begin_time/end_time} 是 {@code yyyy-MM-dd HH:mm:ss}
 * <b>字符串</b>，不是毫秒 —— 直接塞 {@code currentTimeMillis()} 必被拒。
 */
@Component
public class WecomChatListStrategy implements ImChatListStrategy {

    private static final Logger log = LoggerFactory.getLogger(WecomChatListStrategy.class);

    /** 群列表端点（时间窗 = 最近 7 天有消息的群）。 */
    private static final String PATH_GROUPS_LIST = "/cli/chat/groups/list";
    /** 群列表时间窗（参考实现硬限制 7 天）。 */
    private static final int GROUP_WINDOW_DAYS = 7;
    /** 页数硬保护：服务端 {@code has_more} 恒 true 时不至于死循环。 */
    private static final int MAX_PAGES = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ImChannelService channelService;
    @Autowired private WecomCliTokenService tokenService;
    @Autowired private WecomMessageApi messageApi;
    @Autowired private WecomCliClient cliClient;

    @Override public String platform() { return "wecom"; }

    /** 企微两个端点都是实时的 ⇒ 支持刷新。 */
    @Override public boolean canRefresh() { return true; }

    @Override
    public List<ImChatCandidate> fetch(String instanceId) throws ImSendException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "缺少渠道实例 id");
        }
        Optional<String> credOpt = channelService.credentialOf(instanceId);
        if (credOpt.isEmpty()) {
            throw new ImSendException("IM_INSTANCE_NOT_FOUND",
                    "渠道实例不存在或没有凭证：" + instanceId);
        }
        String botId;
        String botSecret;
        try {
            JsonNode root = JSON.readTree(credOpt.get());
            botId = text(root, "botId", "botid");
            botSecret = text(root, "botSecret", "secret");
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企微凭证不是合法 JSON");
        }
        if (botId == null || botSecret == null) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企微凭证缺少 botId/botSecret");
        }
        String token = tokenService.tokenFor(instanceId, botId, botSecret);

        // peerId → 候选（用 LinkedHashMap 保序：sessions 先来，groups 后补名字）
        Map<String, ImChatCandidate> merged = new LinkedHashMap<>();

        // ── ① sessions/list：机器人最近参与的会话（找全）──
        JsonNode sessions = fetchSessions(token);
        for (JsonNode node : sessions.path("sessions")) {
            String chatId = node.path("chat_id").asText("");
            if (chatId.isBlank()) {
                continue;
            }
            String name = node.path("chat_name").asText("");
            // chat_type: single | group；未知值按群处理（aibot 场景以群为主）
            String kind = "single".equals(node.path("chat_type").asText("")) ? "p2p" : "group";
            String lastAt = node.path("last_msg_time").asText("");
            merged.put(chatId, new ImChatCandidate(chatId, name, kind, lastAt));
        }

        // ── ② groups/list：补群名（sessions 缺名字的那些）──
        for (JsonNode item : fetchGroups(token)) {
            String chatId = item.path("chat_id").asText("");
            if (chatId.isBlank()) {
                continue;
            }
            String name = item.path("chat_name").asText("");
            ImChatCandidate old = merged.get(chatId);
            if (old == null) {
                // groups/list 的端点文档「目前仅返回群聊会话」
                merged.put(chatId, new ImChatCandidate(chatId, name, "group", null));
            } else if ((old.peerName() == null || old.peerName().isBlank()) && name != null && !name.isBlank()) {
                // 名字优先取非空的那个
                merged.put(chatId, new ImChatCandidate(chatId, name, old.chatKind(), old.lastMessageAt()));
            }
        }

        log.info("[ImChatList] 企微会话拉取完成 instance={} total={}", instanceId, merged.size());
        return List.copyOf(merged.values());
    }

    /** 拉机器人会话列表；失败返回空节点（调用方据此继续用 groups 补名字）。 */
    private JsonNode fetchSessions(String token) {
        try {
            JsonNode r = messageApi.listSessions(token);
            return r == null ? JSON.createObjectNode() : r;
        } catch (ImSendException e) {
            // ⛔ 不抛：sessions 拿不到时仍可以用 groups 补一部分（且"没有会话"与"拉失败"要能区分，
            //    所以这里只记日志，最终列表少就是少了，工具层会如实报数量）
            log.warn("[ImChatList] 拉企微会话列表失败: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /** 按时间窗拉群列表（带 chat_name）。 */
    private List<JsonNode> fetchGroups(String token) {
        List<JsonNode> out = new java.util.ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        ZonedDateTime end = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime begin = end.minusDays(GROUP_WINDOW_DAYS);
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode body = JSON.createObjectNode();
            body.put("begin_time", begin.format(fmt));
            body.put("end_time", end.format(fmt));
            if (cursor != null && !cursor.isBlank()) {
                body.put("cursor", cursor);
            }
            WecomCliClient.Result r;
            try {
                r = cliClient.call(token, PATH_GROUPS_LIST, body);
            } catch (ImSendException e) {
                log.warn("[ImChatList] 拉企微群列表失败: {}", e.getMessage());
                break;
            }
            if (r == null || !r.ok()) {
                log.warn("[ImChatList] 拉企微群列表被拒: {}",
                        r == null ? "null" : (r.errcode() + " " + r.errmsg()));
                break;
            }
            JsonNode data = r.result();
            if (data == null) {
                break;
            }
            for (JsonNode item : data.path("items")) {
                out.add(item);
            }
            String next = data.path("next_cursor").asText("");
            boolean hasMore = data.path("has_more").asBoolean(false);
            if (!hasMore || next.isBlank() || next.equals(cursor)) {
                break;
            }
            cursor = next;
        }
        return out;
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
}
