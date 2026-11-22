package com.nexusai.domain.im_channel.catalog.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImContactCandidate;
import com.nexusai.domain.im_channel.catalog.ImContactSearchStrategy;
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
import java.util.Locale;
import java.util.Optional;

/**
 * 飞书通讯录搜索（{@code GET /open-apis/contact/v3/users}，应用身份，本地过滤）。
 *
 * <h2>🔴 为什么不是「服务端按关键词搜」</h2>
 * 飞书确实有关键词搜索接口 {@code GET /open-apis/contact/v3/users/search}，但它
 * <b>只接受 {@code user_access_token}</b>（OAuth）——
 * 本仓飞书凭证是 {@code {appId, appSecret}}（一键建应用产出的），只能换出
 * {@code tenant_access_token}，打过去必然是无权限。
 * <p>{@code GET /contact/v3/users} 则明确接受 {@code tenant_access_token}
 * （官方文档：「{@code tenant_access_token} 基于应用通讯录范围进行权限鉴定」），
 * 所以这里走「<b>翻页拉权限范围内的用户 + 本地按名字过滤</b>」。
 *
 * <h2>⚠️ 能力边界（必须让调用方知道）</h2>
 * <ul>
 *   <li>只看得到<b>应用通讯录范围内</b>的人。管理员没给范围 ⇒ 返回空列表而不是报错
 *       （这与「没有权限」是两回事，别把空当成失败）。</li>
 *   <li>大组织要翻很多页 ⇒ 有 {@link #MAX_PAGES} 硬保护与 {@link #MAX_HITS} 提前停：
 *       宁可少返回几个人，也不让一次工具调用打穿配额（飞书对该接口有频率限制）。</li>
 *   <li>返回的 {@code open_id}（{@code ou_xxx}）可直接作为
 *       {@code im/v1/messages?receive_id_type=open_id} 的接收者 ——
 *       这正是「给从没跟机器人说过话的人发消息」的唯一路径。</li>
 * </ul>
 *
 * <h2>与企微的差别</h2>
 * 企微是服务端搜索（{@code keywords}），飞书这里是客户端过滤；
 * 因此<a>关键词匹配规则</a>要写死在这类里（见 {@link #matches}），
 * 不做拼音/模糊音（平台侧没有，硬造会给出「明明有这个人却搜不到」的错觉）。
 */
@Component
public class FeishuContactSearchStrategy implements ImContactSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuContactSearchStrategy.class);

    /** 用户列表端点。 */
    static final String LIST_PATH = "/open-apis/contact/v3/users";

    /** 生产基址（国内版）。 */
    static final String DEFAULT_BASE = "https://open.feishu.cn";

    /** 每页条数（官方上限 100）。 */
    private static final int PAGE_SIZE = 100;

    /** 页数硬保护：防服务端异常恒返回 has_more=true 时死循环，也防一次调用打穿配额。 */
    private static final int MAX_PAGES = 10;

    /** 命中上限：够了就提前停，不必把整个通讯录翻完。 */
    private static final int MAX_HITS = 50;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImChannelService channelService;
    private final FeishuTokenService tokenService;
    private final String baseUrl;

    @Autowired
    public FeishuContactSearchStrategy(ImChannelService channelService, FeishuTokenService tokenService) {
        this(channelService, tokenService, DEFAULT_BASE);
    }

    /**
     * 自定义基址（**测试用**：指向本地 stub，才能验证分页/过滤/上限这类行为）。
     *
     * @param baseUrl 基址；null / 空白 ⇒ 用生产地址
     */
    public FeishuContactSearchStrategy(ImChannelService channelService,
                                       FeishuTokenService tokenService,
                                       String baseUrl) {
        if (channelService == null) {
            throw new IllegalArgumentException("channelService 必填（要读实例凭证）");
        }
        if (tokenService == null) {
            throw new IllegalArgumentException("tokenService 必填（要换 tenant_access_token）");
        }
        this.channelService = channelService;
        this.tokenService = tokenService;
        String v = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl.trim();
        this.baseUrl = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    @Override public String platform() { return "feishu"; }

    /**
     * ⚠️ {@code true} 的含义是「这个平台有可用的联系人发现通道」，
     * 不是「一定能搜到你要的人」—— 结果受应用通讯录范围限制（见类注释）。
     */
    @Override public boolean canSearch() { return true; }

    @Override
    public List<ImContactCandidate> search(String instanceId, String keyword) throws ImSendException {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            throw new ImSendException("IM_PARAM_INVALID", "搜索关键词不能为空");
        }
        String token = tokenFor(instanceId);

        List<ImContactCandidate> hits = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode data = fetchPage(token, instanceId, pageToken);
            if (data == null) {
                break;
            }
            for (JsonNode u : data.path("items")) {
                String openId = text(u, "open_id");
                if (openId == null || openId.isBlank()) {
                    continue;   // 没有 id 就发不出去，别让模型看到一条不可用的联系人
                }
                String name = text(u, "name");
                String enName = text(u, "en_name");
                if (!matches(kw, name, enName)) {
                    continue;
                }
                hits.add(new ImContactCandidate(
                        openId,
                        name == null ? openId : name,
                        enName,
                        null,                    // 飞书这个接口不返回职务
                        departmentIds(u)));
                if (hits.size() >= MAX_HITS) {
                    log.info("[FeishuContact] 命中上限提前停 instance={} keyword={} hits={} page={}",
                            instanceId, kw, hits.size(), page + 1);
                    return hits;
                }
            }
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            pageToken = text(data, "page_token");
            if (pageToken == null || pageToken.isBlank()) {
                break;
            }
        }
        log.info("[FeishuContact] 搜索完成 instance={} keyword={} hits={}", instanceId, kw, hits.size());
        return hits;
    }

    /**
     * 关键词匹配：中文名/英文名<b>包含</b>即命中（大小写不敏感）。
     *
     * <p>⛔ 不做拼音、不做首字母、不做模糊音 —— 平台侧没有这些能力，
     * 在本仓硬造会让模型以为「搜不到 = 这个人不存在」，而真相是「匹配规则不支持」。
     */
    static boolean matches(String keyword, String name, String enName) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        if (name != null && name.toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
        }
        return enName != null && enName.toLowerCase(Locale.ROOT).contains(kw);
    }

    // ═════════════════════ 内部 ═════════════════════

    /** 取实例的 tenant_access_token（凭证缺失/非法 ⇒ 明确报错，不吞成空列表）。 */
    private String tokenFor(String instanceId) throws ImSendException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "缺少渠道实例 id");
        }
        Optional<String> credOpt = channelService.credentialOf(instanceId);
        if (credOpt.isEmpty()) {
            throw new ImSendException("IM_INSTANCE_NOT_FOUND", "渠道实例不存在或没有凭证：" + instanceId);
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
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "飞书凭证缺少 appId/appSecret");
        }
        return tokenService.tokenFor(instanceId, appId, appSecret);
    }

    /** 拉一页；失败抛异常（⛔ 吞成空列表会让「平台拒绝」看起来像「通讯录里没人」）。 */
    private JsonNode fetchPage(String token, String instanceId, String pageToken)
            throws ImSendException {
        String url = baseUrl + LIST_PATH
                + "?page_size=" + PAGE_SIZE
                + "&user_id_type=open_id";
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
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "拉飞书通讯录被中断", null, true);
        } catch (Exception e) {
            // ⛔ 不拼 e.getMessage()（可能含请求片段/令牌）
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "拉飞书通讯录失败：" + e.getClass().getSimpleName(), null, true);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "拉飞书通讯录 HTTP " + resp.statusCode(), null, true);
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "飞书通讯录响应不是合法 JSON", null, false);
        }
        // 飞书业务错误常配 HTTP 200 ⇒ 判成功看 code==0
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书拒绝拉通讯录：code=" + code + " msg=" + root.path("msg").asText(""),
                    String.valueOf(code), false);
        }
        return root.path("data");
    }

    /** 用户所属部门 id 列表（可能缺失 = 权限没开）。 */
    private static List<String> departmentIds(JsonNode user) {
        JsonNode arr = user.path("department_ids");
        if (!arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n == null || n.isNull() ? null : n.asText("");
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
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
