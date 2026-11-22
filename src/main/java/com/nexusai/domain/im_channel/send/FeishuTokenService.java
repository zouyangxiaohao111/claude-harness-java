package com.nexusai.domain.im_channel.send;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 飞书 {@code tenant_access_token} 的换取与进程内缓存。
 *
 * <h2>为什么要有这个类（而不是每次发消息都换票）</h2>
 * 换票端点是有配额的，而「每发一条消息换一张票」会把配额打光；
 * 更糟的是「刚好到期才换」会把「网络抖动 + 时钟偏差」直接暴露成线上
 * {@code 99991663}（token 失效）—— 用户看到的就是「消息偶尔发不出去」。
 * 所以这里做两层保护：<b>缓存</b> + <b>提前 5 分钟刷新</b>。
 *
 * <h2>协议（已实测）</h2>
 * <pre>
 * POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal
 *   Content-Type: application/json
 *   body: {"app_id":"cli_xxx","app_secret":"xxx"}
 *   → {"code":0,"msg":"ok","tenant_access_token":"t-xxx","expire":7200}
 * </pre>
 *
 * <p>⛔ <b>换票端点不带 Authorization 头</b>：凭据就是请求体里的
 * {@code app_id + app_secret}，加 Bearer 反而会被拒。
 *
 * <h2>并发单飞（per-instance）</h2>
 * 同一个实例的并发投递只换一次票。用 {@code computeIfAbsent} 把网络 I/O 塞进
 * 计算函数是**危险**的（{@link ConcurrentHashMap} 会锁住整段 bin，别的 key 也被拖住），
 * 所以这里用「per-instance 锁 + 双重检查」：锁的粒度是实例，不同实例互不阻塞，
 * 也不存在全局锁把多个平台的换票串行化。
 *
 * <h2>失败不写缓存</h2>
 * 换票失败**不**落缓存 ⇒ 下一轮仍会重试。否则一次瞬时故障会被固化成
 * 「这个实例永远没有票」，比偶发失败更难排查。
 *
 * <p>⛔ <b>安全</b>：{@code appSecret} 与 {@code tenant_access_token} 绝不进日志、
 * 绝不进异常 message（异常里只出现平台码与被截断的 msg）。
 */
@Component
public class FeishuTokenService {

    private static final Logger log = LoggerFactory.getLogger(FeishuTokenService.class);

    /** 换票端点路径（基址可替换，见构造器）。 */
    static final String TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";

    /** 生产基址（国内版）。 */
    static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    /** 提前刷新的窗口：提前 5 分钟换票，把「到期瞬间」从热路径上摘掉。 */
    static final long REFRESH_AHEAD_MILLIS = 300_000L;

    /** 服务端没给 {@code expire} 时的保守默认（官方是 7200 秒）。 */
    static final long DEFAULT_EXPIRE_SECONDS = 7200L;

    /** 异常文案里允许出现的最长原文长度（防止整个响应体塞进 message）。 */
    private static final int SNIPPET_LIMIT = 300;

    /** 兜底掩码：极端情况下（响应被截断导致解析失败）原文里可能带 token 字段。 */
    private static final Pattern TOKEN_FIELD =
            Pattern.compile("(?i)(\"tenant_access_token\"\\s*:\\s*\")[^\"]*(\")");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    /** 换票基址（末尾不带斜杠）。 */
    private final String baseUrl;

    /** 实例 id → 票。按实例分桶：一个实例的凭证轮换不会影响别的实例。 */
    private final ConcurrentHashMap<String, Holder> cache = new ConcurrentHashMap<>();

    /** 实例 id → 该实例的换票锁（per-instance，不用全局锁）。 */
    private final ConcurrentHashMap<String, Object> lockByInstance = new ConcurrentHashMap<>();

    /** 生产构造（打飞书官方端点）。 */
    public FeishuTokenService() {
        this(DEFAULT_BASE_URL);
    }

    /**
     * 自定义基址（**测试用**：指向本地 stub 服务，才能验证「只换一次票」这类行为）。
     *
     * @param baseUrl 基址，如 {@code http://127.0.0.1:1234}；null / 空白 ⇒ 用默认值
     */
    public FeishuTokenService(String baseUrl) {
        String v = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    /**
     * 取一张可用的 {@code tenant_access_token}（缓存命中即返回，否则换票）。
     *
     * @param instanceId 渠道实例 id（缓存与单飞的维度；不能为空）
     * @param appId      飞书应用 id
     * @param appSecret  飞书应用密钥（⛔ 绝不进日志 / 异常）
     * @return 可直接放进 {@code Authorization: Bearer …} 的票
     * @throws ImSendException 换票失败（凭证错误、网络失败、响应不可解析）
     */
    public String tokenFor(String instanceId, String appId, String appSecret) throws ImSendException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "渠道实例 id 缺失，无法换取飞书 tenant_access_token");
        }
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            // ⛔ 只说缺什么，不回显缺的那个值
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证不完整（缺少 appId 或 appSecret），无法换取 tenant_access_token");
        }

        // ── 快路径：命中缓存直接返回（不额外创建锁对象）──
        Holder hit = cache.get(instanceId);
        long now = System.currentTimeMillis();
        if (hit != null && !hit.needsRefresh(now)) {
            return hit.value();
        }

        // ── 慢路径：per-instance 锁 + 双重检查 ⇒ 同一实例并发只换一次票 ──
        Object lock = lockByInstance.computeIfAbsent(instanceId, k -> new Object());
        synchronized (lock) {
            Holder again = cache.get(instanceId);
            long t = System.currentTimeMillis();
            if (again != null && !again.needsRefresh(t)) {
                return again.value();
            }
            Exchange exchange = exchange(appId, appSecret);
            // 只有成功才写缓存：失败不缓存 ⇒ 下一轮还会重试
            cache.put(instanceId, new Holder(exchange.token(), t + exchange.expireSeconds() * 1000L));
            return exchange.token();
        }
    }

    /**
     * 作废某实例缓存的票（收到飞书「token 失效」时调用，强制下一轮换新票）。
     *
     * @param instanceId 渠道实例 id；null / 空白时什么都不做
     */
    public void invalidate(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        cache.remove(instanceId);
        log.info("[FeishuToken] 已作废缓存的 tenant_access_token instance={}", instanceId);
    }

    // ═════════════════════ 内部 ═════════════════════

    /** 换票结果（票值 + 有效期秒数）。 */
    private record Exchange(String token, long expireSeconds) {
    }

    /** 一张票的值与到期时刻。 */
    private record Holder(String value, long expiresAtMillis) {
        /** 是否已到「提前刷新」的边界（含边界，与飞书侧经验一致）。 */
        boolean needsRefresh(long nowMillis) {
            return nowMillis >= expiresAtMillis - REFRESH_AHEAD_MILLIS;
        }
    }

    /** 真的去换一张票（不发 Authorization 头 —— 凭据就是请求体）。 */
    private Exchange exchange(String appId, String appSecret) throws ImSendException {
        ObjectNode body = json.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        HttpResponse<String> resp;
        try {
            resp = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + TOKEN_PATH))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书换取 tenant_access_token 失败：请求被中断", null, true);
        } catch (Exception e) {
            // 网络类故障值得重试；⛔ 这里不拼 e.getMessage()（可能含请求体片段）
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书换取 tenant_access_token 失败：网络异常 " + e.getClass().getSimpleName(),
                    null, true);
        }

        int status = resp.statusCode();
        String raw = resp.body() == null ? "" : resp.body();

        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书换取 tenant_access_token 失败：响应不是合法 JSON（HTTP " + status + "）："
                            + snippet(mask(raw, appSecret)),
                    null, false);
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书换取 tenant_access_token 失败：响应不是 JSON 对象（HTTP " + status + "）："
                            + snippet(mask(raw, appSecret)),
                    null, false);
        }

        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != 0) {
            String msg = mask(root.path("msg").asText(""), appSecret);
            log.warn("[FeishuToken] 换票被拒 appId={} code={} msg={}", appId, code, snippet(msg));
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书换取 tenant_access_token 失败：code=" + code + " msg=" + snippet(msg),
                    String.valueOf(code), false);
        }

        String token = root.path("tenant_access_token").asText("");
        if (token.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书换取 tenant_access_token 失败：code=0 但响应缺少 tenant_access_token",
                    "0", false);
        }

        long expire = root.path("expire").asLong(0L);
        if (expire <= 0) {
            expire = DEFAULT_EXPIRE_SECONDS;
        }
        // ⛔ 日志里只有 appId 与有效期，绝不出现 secret / token
        log.info("[FeishuToken] 换票成功 appId={} expiresIn={}s", appId, expire);
        return new Exchange(token, expire);
    }

    /** 把凭证（以及极端情况下的 token 字段）从可外泄的文本里抹掉。 */
    private static String mask(String text, String secret) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (secret != null && secret.length() >= 4) {
            out = out.replace(secret, "***");
        }
        return TOKEN_FIELD.matcher(out).replaceAll("$1***$2");
    }

    /** 截断原文（异常 message 不接受整段响应体）。 */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
    }
}
