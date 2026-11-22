package com.nexusai.apis.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【排障探针】企微两条发消息端点的差异实测。
 *
 * <h2>要回答的问题</h2>
 * 企微单聊入站帧里**没有 chatid**（只有 {@code from.userid}），按 userid 打
 * {@code /cli/message/send} 会回 {@code 40073 非法的 chat_id}。而官方 CLI 的
 * {@code message aibot send} 文档说 {@code chat_id} 可以取「授权人 ID」。
 * <p>⇒ 实测：同一个 userid，分别打 {@code /cli/message/send}(text) 与
 * {@code /cli/message/aibot/send}(markdown)，看哪个能发到。
 *
 * <h2>⚠️ 只在 dev profile 注册（安全边界）</h2>
 * 本类能**绕过所有业务校验直接给企微发消息**（`/wecom` 传 mode 就发），
 * 因此绝不能出现在生产/场景版里：{@code @Profile("dev")} 保证只有
 * 「本地开发（application.yml 的 {@code spring.profiles.default=dev}）」才注册。
 * Tauri 壳自启用的是 {@code prod}/{@code scene} ⇒ 这两个端点不存在。
 *
 * <p>保留它的理由：对接新平台时「形态探测 → 单目标发 → 跨目标对照」三步都要它，
 * 比每次改代码+重启快得多。
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/im/probe")
public class ImWecomProbeController {

    private static final Logger log = LoggerFactory.getLogger(ImWecomProbeController.class);

    @Autowired private ImChannelService channelService;
    @Autowired private WecomCliTokenService tokenService;
    @Autowired private WecomMessageApi messageApi;
    @Autowired private com.nexusai.domain.im_channel.cli.WecomCliClient cliClient;
    @Autowired private ObjectMapper json;

    /**
     * @param instanceId 企微实例（拿 botId/botSecret 换 CLI token）
     * @param chatId     要发的目标（userid 或 chatid）
     * @param text       正文
     * @param mode       {@code send}（/cli/message/send + text）| {@code aibot}（/cli/message/aibot/send + markdown）
     */
    @PostMapping("/wecom")
    public Map<String, Object> probe(@RequestBody Map<String, Object> body) {
        String instanceId = str(body, "instanceId");
        String chatId = str(body, "chatId");
        String text = str(body, "text");
        String mode = str(body, "mode");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("chatId", chatId);
        out.put("mode", mode);
        try {
            String cred = channelService.credentialOf(instanceId).orElse(null);
            if (cred == null) {
                out.put("ok", false);
                out.put("error", "IM_INSTANCE_NOT_FOUND / 无凭证");
                return out;
            }
            JsonNode c = json.readTree(cred);
            String botId = text(c.path("botId"));
            String botSecret = text(c.path("botSecret"));
            String token = tokenService.tokenFor(instanceId, botId, botSecret);
            out.put("tokenLen", token == null ? 0 : token.length());

            long t0 = System.currentTimeMillis();
            if ("aibot".equals(mode)) {
                messageApi.sendMarkdown(token, chatId, text);
            } else {
                messageApi.sendText(token, chatId, text);
            }
            out.put("ok", true);
            out.put("ms", System.currentTimeMillis() - t0);
            log.info("[ImProbe] OK mode={} chatId={}", mode, chatId);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("[ImProbe] FAIL mode={} chatId={} err={}", mode, chatId, e.toString());
        }
        return out;
    }

    /** 拉机器人会话列表（看真实有几条、分别是什么）。 */
    @PostMapping("/wecom/sessions")
    public Map<String, Object> sessions(@RequestBody Map<String, Object> body) {
        String instanceId = str(body, "instanceId");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String cred = channelService.credentialOf(instanceId).orElse(null);
            if (cred == null) {
                out.put("ok", false);
                out.put("error", "无凭证");
                return out;
            }
            JsonNode c = json.readTree(cred);
            String token = tokenService.tokenFor(instanceId, text(c.path("botId")), text(c.path("botSecret")));
            JsonNode r = messageApi.listSessions(token);
            out.put("ok", true);
            out.put("raw", r);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * 搜人 + 用返回的 userid 试发一条（验证「以用户名义给别人发」这条路通不通）。
     *
     * <p>要回答：{@code contact/users/search} 返回的 {@code userid} 能不能直接当
     * {@code message/send} 的 chat_id（我们已知入站 on 的 {@code from.userid} 不行）。
     */
    @PostMapping("/wecom/contact")
    public Map<String, Object> contact(@RequestBody Map<String, Object> body) {
        String instanceId = str(body, "instanceId");
        String keyword = str(body, "keyword");
        boolean trySend = !"false".equals(String.valueOf(body.getOrDefault("trySend", "true")));

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String cred = channelService.credentialOf(instanceId).orElse(null);
            if (cred == null) {
                out.put("ok", false);
                out.put("error", "无凭证");
                return out;
            }
            JsonNode c = json.readTree(cred);
            String token = tokenService.tokenFor(instanceId, text(c.path("botId")), text(c.path("botSecret")));

            com.fasterxml.jackson.databind.node.ObjectNode req = json.createObjectNode();
            req.putArray("keywords").add(keyword);
            var r = cliClient.call(token, "/cli/contact/users/search", req);
            out.put("searchOk", r.ok());
            out.put("raw", r.result() == null ? r.rawBody() : r.result());
            if (!r.ok()) {
                return out;
            }
            JsonNode users = r.result() == null ? null : r.result().path("users");
            if (users == null || !users.isArray() || users.isEmpty()) {
                out.put("note", "没搜到人");
                return out;
            }
            String userid = text(users.get(0).path("userid"));
            out.put("firstUserid", userid);
            if (trySend && userid != null) {
                try {
                    messageApi.sendText(token, userid, "【探针】contact userid 试发，如果这条能被拒就说明不能当 chat_id");
                    out.put("sendMode", "text/message-send");
                    out.put("sendOk", true);
                } catch (Exception e1) {
                    out.put("sendMode", "text/message-send");
                    out.put("sendOk", false);
                    out.put("sendError", e1.getMessage());
                    try {
                        messageApi.sendMarkdown(token, userid, "【探针】contact userid 试发(markdown)");
                        out.put("aibotOk", true);
                    } catch (Exception e2) {
                        out.put("aibotOk", false);
                        out.put("aibotError", e2.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
