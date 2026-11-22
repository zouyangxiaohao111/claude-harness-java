package com.nexusai.domain.im_channel.catalog.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImChatCandidate;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategy;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 飞书会话列表策略。
 *
 * <h2>数据源</h2>
 * {@code GET /open-apis/im/v1/chats?page_size=100}，用 {@code tenant_access_token} 鉴权。
 *
 * <h3>⚠️ 平台限制：只能看到机器人「已加入」的会话</h3>
 * 飞书没有「通讯录里所有群」这种接口 —— 机器人能看到的会话，就是它已经进去的那些。
 * 用户想让 AI 往一个新群发消息，必须先把机器人拉进那个群。
 *
 * <h2>分页</h2>
 * 终止条件用 {@code has_more}（{@code has_more=false} 时服务端<b>不返回</b> {@code page_token}
 * ⇒ 只能靠它判停）；另加 20 页硬保护 —— 服务端异常返回恒 true 时不至于死循环。
 */
@Component
public class FeishuChatListStrategy implements ImChatListStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatListStrategy.class);

    /** 群列表端点。 */
    private static final String CHATS_PATH = "/open-apis/im/v1/chats";
    /** 默认网关（国际站是 open.larksuite.com，此处与既有实现保持一致）。 */
    private static final String DEFAULT_BASE = "https://open.feishu.cn";
    /** 每页条数上限。 */
    private static final int PAGE_SIZE = 100;
    /** 页数硬保护（防服务端 has_more 恒 true 时死循环）。 */
    private static final int MAX_PAGES = 20;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ImChannelService channelService;
    @Autowired private FeishuTokenService tokenService;

    @Override public String platform() { return "feishu"; }

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
        String appId;
        String appSecret;
        try {
            JsonNode root = JSON.readTree(credOpt.get());
            appId = text(root, "appId", "app_id");
            appSecret = text(root, "appSecret", "app_secret");
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "飞书凭证不是合法 JSON");
        }
        if (appId == null || appSecret == null) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "飞书凭证缺少 appId/appSecret");
        }
        String token = tokenService.tokenFor(instanceId, appId, appSecret);

        List<ImChatCandidate> out = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode data = fetchPage(token, pageToken);
            if (data == null) {
                break;
            }
            for (JsonNode item : data.path("items")) {
                String chatId = item.path("chat_id").asText("");
                if (chatId.isBlank()) {
                    continue;
                }
                String name = item.path("name").asText("");
                // chat_mode: "group" | "p2p" | "topic"；非 p2p 一律当群（topic 也是群的一种）
                String kind = "p2p".equals(item.path("chat_mode").asText("")) ? "p2p" : "group";
                out.add(new ImChatCandidate(chatId, name, kind, null));
            }
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            pageToken = data.path("page_token").asText("");
            if (pageToken.isBlank()) {
                break;
            }
        }
        log.info("[ImChatList] 飞书会话拉取完成 instance={} total={}", instanceId, out.size());
        return out;
    }

    /** 拉一页；失败抛异常（⛔ 不能吞成空列表 —— 那会让「平台拒绝」看起来像「没有会话」）。 */
    private JsonNode fetchPage(String token, String pageToken) throws ImSendException {
        String url = DEFAULT_BASE + CHATS_PATH + "?page_size=" + PAGE_SIZE;
        if (pageToken != null && !pageToken.isBlank()) {
            url += "&page_token=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
        }
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "拉飞书会话列表被中断", null, true);
        } catch (Exception e) {
            // ⛔ 不拼 e.getMessage()（可能含请求片段/令牌）
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "拉飞书会话列表失败：" + e.getClass().getSimpleName(), null, true);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "拉飞书会话列表 HTTP " + resp.statusCode(), null, true);
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "飞书会话列表响应不是合法 JSON", null, false);
        }
        // 飞书业务错误常配 HTTP 200 ⇒ 判成功看 code==0，不看 HTTP 状态码
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书拒绝拉会话列表：code=" + code + " msg=" + root.path("msg").asText(""),
                    String.valueOf(code), false);
        }
        return root.path("data");
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
