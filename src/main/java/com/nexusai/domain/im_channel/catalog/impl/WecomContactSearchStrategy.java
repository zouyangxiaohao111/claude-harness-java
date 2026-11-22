package com.nexusai.domain.im_channel.catalog.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImContactCandidate;
import com.nexusai.domain.im_channel.catalog.ImContactSearchStrategy;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业微信通讯录搜索（{@code POST /cli/contact/users/search}）。
 *
 * <h2>返回的 userid 是「加密形态」，可直接当 chat_id</h2>
 * 真机实测（2026-11-18）：搜「碟碟」返回
 * {@code userid=woNH-7HAAA8REXhE7whFKoItieATKeZw}，用 {@code message/send} 拿它发文本
 * <b>成功</b>（对方收到，且显示为"用户本人"发的）。
 * <p>⚠️ 而入站帧 {@code from.userid} 是明文 {@code ZhengChangWei}，拿它发会回
 * {@code 40073 非法的 chat_id}。两套标识别混用。
 */
@Component
public class WecomContactSearchStrategy implements ImContactSearchStrategy {

    /** schema {@code contact.users.search} 的 path（cli 网关加 {@code /cli} 前缀）。 */
    public static final String PATH_SEARCH = "/cli/contact/users/search";

    private final WecomCliClient client;
    private final WecomCliTokenService tokenService;
    private final ImChannelService channelService;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public WecomContactSearchStrategy(WecomCliClient client,
                                      WecomCliTokenService tokenService,
                                      ImChannelService channelService) {
        this.client = client;
        this.tokenService = tokenService;
        this.channelService = channelService;
    }

    @Override public String platform() { return "wecom"; }

    @Override public boolean canSearch() { return true; }

    @Override
    public List<ImContactCandidate> search(String instanceId, String keyword) throws ImSendException {
        if (keyword == null || keyword.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "搜索关键词不能为空");
        }
        String cred = channelService.credentialOf(instanceId)
                .orElseThrow(() -> new ImSendException("IM_INSTANCE_NOT_FOUND",
                        "渠道实例不存在或未配置凭证：" + instanceId));
        String botId;
        String botSecret;
        try {
            JsonNode c = json.readTree(cred);
            botId = text(c.path("botId"));
            botSecret = text(c.path("botSecret"));
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企微凭证不是合法 JSON");
        }
        String token = tokenService.tokenFor(instanceId, botId, botSecret);

        ObjectNode body = json.createObjectNode();
        body.putArray("keywords").add(keyword);

        WecomCliClient.Result r = client.call(token, PATH_SEARCH, body);
        if (!r.ok()) {
            throw WecomCliClient.failureOf(r, "企微搜索通讯录");
        }
        JsonNode users = r.result() == null ? null : r.result().path("users");
        List<ImContactCandidate> out = new ArrayList<>();
        if (users == null || !users.isArray()) {
            return out;
        }
        for (JsonNode u : users) {
            String userId = text(u.path("userid"));
            if (userId == null || userId.isBlank()) {
                continue;   // 没有 id 就等于发不出去，别让模型看到一条不可用的联系人
            }
            List<String> depts = new ArrayList<>();
            JsonNode d = u.path("departments");
            if (d.isArray()) {
                for (JsonNode x : d) {
                    String s = text(x);
                    if (s != null) depts.add(s);
                }
            }
            out.add(new ImContactCandidate(userId, text(u.path("name")),
                    text(u.path("alias")), text(u.path("position")), depts));
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
