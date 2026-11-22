package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书聊天「显示名」解析：<b>真名优先，拿不到才降级到尾号</b>。
 *
 * <h2>用户裁决（2026-11-23）</h2>
 * <ol>
 *   <li>能拿到 {@code open_id} 就用 {@code GET /contact/v3/users/{open_id}} 取 {@code name}，
 *       成功即写 {@code peer_name}（并写 {@code nick_name}）；</li>
 *   <li>{@code oc_} 私聊行也试：先 {@code GET /im/v1/chats/{chat_id}/members} 取对方
 *       {@code open_id}（排除机器人自己），再查联系人名；</li>
 *   <li>通讯录权限缺失 ⇒ 整批直接走尾号，<b>不逐行试</b>（每轮探一次）；</li>
 *   <li>{@code open_id → name} 进程内缓存（TTL 1h），contact 调用串行 + 间隔（≤5 QPS）；</li>
 *   <li>{@code nick_name} 非空 = 人工改名，调用方不得覆盖；</li>
 *   <li>日志必须能回答「这一行为什么用了尾号」：{@code no_permission / no_open_id /
 *       api_error / rate_limited}。</li>
 * </ol>
 *
 * <h2>为什么权限「探一次」而不是逐行试</h2>
 * 缺权限时每行都打一次 contact 会得到 N 个失败 + 可能触发限流。这里由调用方在循环外
 * 调 {@link #probeContacts} 一次：明确 DENIED 时整批跳过（0 次 contact 调用）；
 * UNKNOWN（例如还没授权、拿不到探针 open_id）时允许「先试一次」，失败即由调用方
 * 把本批降级为 DENIED —— 仍然是「最多一次」，不会逐行重试。
 *
 * <h2>不探测真实平台的边界</h2>
 * 本类只负责 HTTP 与判定；「机器人是谁」「该实例有没有授权用户」由调用方传入
 * （{@code botOpenId} / {@code probeOpenId}），以便单测用本地桩替换基址。
 */
@Component
public class FeishuChatNameResolver {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatNameResolver.class);

    static final String DEFAULT_BASE = "https://open.feishu.cn";

    /** contact 调用最小间隔：200ms ≈ 5 QPS。测试注入 0。 */
    private static final long DEFAULT_MIN_INTERVAL_MS = 200;

    /** 成功结果缓存 1 小时（用户裁决）。 */
    private static final long DEFAULT_CACHE_TTL_MS = 3_600_000L;

    /** 失败结果短缓存：避免反复打同一个坏 open_id，但不至于把「刚开权限」永久挡住。 */
    private static final long NEGATIVE_TTL_MS = 60_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试可替换基址（⛔ 生产恒为官方域名）。 */
    private String openBase = DEFAULT_BASE;
    private long minIntervalMs = DEFAULT_MIN_INTERVAL_MS;
    private long cacheTtlMs = DEFAULT_CACHE_TTL_MS;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private long lastRequestAt;
    private final Object rateLock = new Object();

    /** 缓存项：{@code name == null} 表示负结果（{@code reason} 为当时的降级原因）。 */
    private record CacheEntry(String name, Reason reason, long expiresAt) {}

    /** 降级原因（写日志用）。 */
    public enum Reason {
        OK,
        NO_PERMISSION,
        NO_OPEN_ID,
        /** 机器人自己的名字拿不到（{@code bot/v3/info} 失败或 app_name 为空）。 */
        NO_BOT_NAME,
        API_ERROR,
        RATE_LIMITED
    }

    /** 一次解析结果：{@code name} 为 null/空白 ⇒ 调用方写尾号合成名。 */
    public record Resolved(String name, Reason reason) {
        public boolean hasName() {
            return name != null && !name.isBlank();
        }
    }

    /** 通讯录权限探测状态。 */
    public enum ProbeStatus { GRANTED, DENIED, UNKNOWN }

    /**
     * 探测「应用身份能不能读到人名」。
     *
     * @param tenantToken 应用身份令牌
     * @param probeOpenId 用来探的 open_id（通常是已授权用户）；为空 ⇒ UNKNOWN（没法探）
     *
     * <p>判据与 {@code ImOAuthController.contacts-permission} 一致：返回 {@code name}
     * 即已开通；权限类错误码即未开通；其它失败按 UNKNOWN（不误杀）。
     */
    public ProbeStatus probeContacts(String tenantToken, String probeOpenId) {
        if (isBlank(probeOpenId)) {
            return ProbeStatus.UNKNOWN;
        }
        ContactFetch r = fetchContact(tenantToken, probeOpenId);
        if (r.name != null && !r.name.isBlank()) {
            return ProbeStatus.GRANTED;
        }
        if (r.permissionDenied) {
            return ProbeStatus.DENIED;
        }
        return ProbeStatus.UNKNOWN;
    }

    /**
     * 解析一个私聊会话的展示名。
     *
     * <h2>私聊的「对方」是谁（决定用哪个名字）</h2>
     * <ul>
     *   <li>{@code oc_} 行 = <b>用户与机器人的会话</b>（飞书单聊的 chat_id 也是 oc_ 开头）。
     *       对方就是<b>机器人自己</b> ⇒ 会话名用 {@code bot/v3/info} 的 {@code app_name}。
     *       ⛔ 不能去 members 里找「非机器人成员」——那查到的是<b>用户本人</b>，
     *       写进去会话列表就变成你的名字，语义完全错（2026-11-23 真机报障）。</li>
     *   <li>{@code ou_} 行 = 通讯录里搜出来的<b>人</b> ⇒ 查联系人名。</li>
     * </ul>
     *
     * @param tenantToken 应用身份令牌
     * @param peerId      该行的 {@code peer_id}
     * @param probe       {@link #probeContacts} 的整批探测结果
     * @param botName     机器人自己的名字（{@link #fetchBotName}）；拿不到传 null
     */
    public Resolved resolveP2pName(String tenantToken, String peerId,
                                   ProbeStatus probe, String botName) {
        if (peerId != null && peerId.startsWith("ou_")) {
            if (probe == ProbeStatus.DENIED) {
                return new Resolved(null, Reason.NO_PERMISSION);
            }
            return userName(tenantToken, peerId);   // 通讯录搜出来的人：peer_id 本身就是 open_id
        }
        // oc_（或其它形态）= 与机器人的会话：名字只能是机器人名
        if (isBlank(botName)) {
            return new Resolved(null, Reason.NO_BOT_NAME);
        }
        return new Resolved(botName, Reason.OK);
    }

    /**
     * {@code open_id → name}（缓存 + 限速）。
     *
     * <p>缓存键只认 open_id：同一个人的名字与渠道实例无关。
     */
    public Resolved userName(String tenantToken, String openId) {
        if (isBlank(openId) || isBlank(tenantToken)) {
            return new Resolved(null, Reason.NO_OPEN_ID);
        }
        CacheEntry hit = cache.get(openId);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) {
            return hit.name() == null
                    ? new Resolved(null, hit.reason() == null ? Reason.API_ERROR : hit.reason())
                    : new Resolved(hit.name(), Reason.OK);
        }
        // 限速在 fetchContact / get 内部统一做（每次真实 HTTP 前一次）
        ContactFetch r = fetchContact(tenantToken, openId);
        if (r.name != null && !r.name.isBlank()) {
            cache.put(openId, new CacheEntry(r.name, Reason.OK, now + cacheTtlMs));
            return new Resolved(r.name, Reason.OK);
        }
        Reason why = r.permissionDenied ? Reason.NO_PERMISSION
                : (r.rateLimited ? Reason.RATE_LIMITED : Reason.API_ERROR);
        cache.put(openId, new CacheEntry(null, why, now + NEGATIVE_TTL_MS));
        return new Resolved(null, why);
    }

    /**
     * 取机器人自己的名字（{@code GET /open-apis/bot/v3/info} → {@code data.bot.app_name}）。
     *
     * <p>这是「与机器人的私聊」该显示的会话名（用户 2026-11-23 真机确认：界面左侧已显示
     * 「智能bot」，右侧却是「飞书私聊 · 尾号 xxxx」）。
     *
     * @return 拿不到 ⇒ null（调用方据此降级到尾号）
     */
    public String fetchBotName(String tenantToken) {
        JsonNode data = get(tenantToken, "/open-apis/bot/v3/info");
        if (data == null) {
            return null;
        }
        String name = text(data.path("bot"), "app_name");
        return isBlank(name) ? null : name.trim();
    }

    // ═════════════════════ 内部 ═════════════════════

    /** contact 单次查询的结果分类。 */
    private record ContactFetch(String name, boolean permissionDenied, boolean rateLimited) {}

    private ContactFetch fetchContact(String token, String openId) {
        String url = openBase + "/open-apis/contact/v3/users/"
                + URLEncoder.encode(openId, StandardCharsets.UTF_8)
                + "?user_id_type=open_id";
        rateLimitWait();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ContactFetch(null, false, false);
        } catch (Exception e) {
            return new ContactFetch(null, false, false);
        }
        if (resp.statusCode() == 429) {
            return new ContactFetch(null, false, true);
        }
        if (resp.statusCode() != 200) {
            return new ContactFetch(null, false, false);
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            return new ContactFetch(null, false, false);
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            // 41050 no user authority / 99991672 缺 scope / 99991679 通讯录范围外
            boolean denied = code == 41050 || code == 99991672 || code == 99991679;
            return new ContactFetch(null, denied, false);
        }
        String name = text(root.path("data").path("user"), "name");
        if (isBlank(name)) {
            // code=0 但没有 name：字段级权限缺失（不是范围问题）⇒ 按未开通处理
            return new ContactFetch(null, true, false);
        }
        return new ContactFetch(name, false, false);
    }

    /** GET open-apis 并返回 {@code data} 节点；失败 ⇒ null。 */
    private JsonNode get(String token, String path) {
        if (isBlank(token)) {
            return null;
        }
        String url = openBase + path;
        rateLimitWait();
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode root = JSON.readTree(resp.body() == null ? "" : resp.body());
            return root.path("code").asInt(-1) == 0 ? root.path("data") : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 串行 + 最小间隔（≤5 QPS），避免触发 429。 */
    private void rateLimitWait() {
        synchronized (rateLock) {
            long wait = lastRequestAt + minIntervalMs - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    // ── 测试注入点（包级可见；生产不调用） ──

    void setOpenBase(String base) {
        this.openBase = base;
    }

    void setMinIntervalMs(long ms) {
        this.minIntervalMs = ms;
    }

    void setCacheTtlMs(long ms) {
        this.cacheTtlMs = ms;
    }

    void clearCache() {
        cache.clear();
    }
}
