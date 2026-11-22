package com.nexusai.domain.im_channel.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信「签名换 CLI token」服务：用 bot 凭证换出调用 {@code /cli/*} 网关所需的 Bearer token。
 *
 * <h2>为什么需要这一层（而不是直接把 botSecret 当 token 用）</h2>
 * 企微有<b>两套完全不同的凭证</b>（PIT-WC-004）：
 * <ul>
 *   <li><b>bot 凭证</b>（{@code botId} + {@code botSecret}）：库里存的那一份，是「机器人身份」；</li>
 *   <li><b>CLI token</b>（43 字符）：调用 {@code https://qyapi.weixin.qq.com/cli/*} 网关时
 *       放在 {@code Authorization: Bearer} 里的那一份。</li>
 * </ul>
 * 把两者混用会拿到 {@code {"errcode":40001,"errmsg":"invalid credential"}} ——
 * 这是本服务存在的全部理由。
 *
 * <h2>协议（官方开源实现逐字核对）</h2>
 * 来源：{@code github.com/WecomTeam/wecom-cli} → {@code crates/wecom-cli/src/auth/bootstrap.rs}
 * <pre>
 * POST https://qyapi.weixin.qq.com/cgi-bin/aibot/cli/get_cli_config
 *   Content-Type: application/json
 *   ⛔ 不带 Authorization 头 —— 凭据就是请求体里的签名，不是一张卡
 *   ⛔ 不包 payload 信封 —— 这个端点是 FlatRes，响应整体即业务结果
 *   body: {"bot_id":"&lt;botId&gt;","time":&lt;秒&gt;,"nonce":"cli_&lt;毫秒&gt;_&lt;8位hex&gt;",
 *          "signature":"&lt;sha256_hex(secret+botId+time+nonce)&gt;","bind_source":2}
 * → {"errcode":0,"errmsg":"ok","token":"&lt;43字符&gt;"}
 * </pre>
 * <ul>
 *   <li><b>签名</b>：{@code sha256_hex(secret + botId + time + nonce)}，小写零填充（{@code %02x}）；</li>
 *   <li><b>{@code bind_source}</b>：{@code 2} = 扫码创建（本仓的机器人都是扫码建的，见
 *       {@code WecomQrStrategy}）；{@code 1} = 交互式创建，我们不用。</li>
 * </ul>
 *
 * <h2>缓存与并发（NXS-UC-CHN-021 §4）</h2>
 * <ul>
 *   <li><b>进程内缓存</b>，键 = {@code instanceId}（不是 platform —— 同一平台可以有多个实例、
 *       各自独立凭证）。令牌是短时的、可随时重算，<b>不落库</b>：落库只会多出一份
 *       「库里存着过期 token」的脏状态。</li>
 *   <li><b>提前 300s 过期</b>：{@code now >= expiresAt - 300s} 即视为过期 ⇒ 换票时不会撞上
 *       正在用旧 token 的请求。</li>
 *   <li><b>按 instanceId 单飞</b>：同一实例的并发请求只换一次票（见 {@code tokenFor} 里的
 *       「每实例一把锁 + 双重检查」），避免把企微的换票接口打爆。</li>
 * </ul>
 *
 * <h2>安全</h2>
 * ⛔ {@code botSecret} 与换出来的 {@code token} <b>绝不进日志、绝不进异常消息</b>。
 * 日志里只出现 {@code instanceId}、{@code botId} 与令牌长度。
 *
 * <h2>失败语义</h2>
 * 换票失败一律抛 {@link ImSendException}，code = {@code IM_CREDENTIAL_EXCHANGE_FAILED}、
 * {@code retryable = true}（网络问题重试有意义）。<b>不</b>因为换票失败去清库里的根凭证 ——
 * 那可能只是一次网络抖动。
 */
@Component
public class WecomCliTokenService {

    private static final Logger log = LoggerFactory.getLogger(WecomCliTokenService.class);

    /**
     * 换票端点（官方 CLI {@code crates/wecom-cli/src/auth/bootstrap.rs} 的
     * {@code DEFAULT_AUTH_ENDPOINT} 逐字）。
     *
     * <p>⛔ 注意它<b>不在</b> {@code /cli/*} 网关前缀下 —— 它挂在 {@code /cgi-bin/aibot/cli/} 下。
     */
    public static final String AUTH_ENDPOINT =
            "https://qyapi.weixin.qq.com/cgi-bin/aibot/cli/get_cli_config";

    /**
     * {@code bind_source} = 2 = 扫码创建（官方 CLI 的 {@code bind_source: Qrcode}）。
     * 本仓的企微机器人一律由扫码流程创建（{@code WecomQrStrategy}），所以固定 2。
     */
    public static final int BIND_SOURCE_QRCODE = 2;

    /**
     * 本地令牌有效期（秒）。
     *
     * <p>⚠️ <b>假设</b>：{@code get_cli_config} 的响应体<b>只给 token 字符串、不给 expires_in</b>
     * （官方 {@code FetchAuthResponse} 只有 {@code errcode}/{@code errmsg}/{@code token} 三个字段），
     * 所以有效期只能由本地约定。取 1 小时是保守值：短到不会拿着一个失效的 token 去发消息，
     * 长到不会把换票接口打成日常热点。真正的失效由平台码 {@code 853004} 兜底（见发送侧）。
     */
    public static final long TOKEN_TTL_SECONDS = 3600L;

    /**
     * 提前失效的余量（秒）：{@code now >= expiresAt - 300s} 即认为令牌已过期。
     *
     * <p>为什么是 300：换票是一次网络往返 + 签名计算，留 5 分钟余量足以覆盖
     * 「刚换完票、还没用出去就被判过期」这种边缘情况。
     */
    public static final long REFRESH_AHEAD_SECONDS = 300L;

    /** 连接超时（秒）。与发送通道同量级：换票是一次网络往返，不该更慢。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /** 请求超时（秒）：对应集成目录 NXS-INT-CHN-007 的「HTTP 30s」。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 随机源（nonce 用）。必须用 {@link SecureRandom}：nonce 是签名的组成部分，可预测就会被重放。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 平台 errmsg 的截断上限（字符）。真机实测平台消息约 160 字符且长度不受控。 */
    private static final int ERRMSG_LIMIT = 300;

    /**
     * 换票端点（**可注入**：端到端测试要指向本地 stub，否则测试会打真实平台）。
     *
     * <p>生产走 {@link #AUTH_ENDPOINT}；只有在显式传入时才改变。
     */
    private final String authEndpoint;

    private final ObjectMapper json = new ObjectMapper();

    /** 共享 HttpClient（线程安全；连接池复用）。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** 生产构造（打企微真实端点）。 */
    public WecomCliTokenService() {
        this(AUTH_ENDPOINT);
    }

    /**
     * 自定义换票端点（**仅测试用**：指向本地 stub 才能验证签名/信封/单飞等行为）。
     *
     * @param authEndpoint 完整 URL；null/空白 ⇒ 用 {@link #AUTH_ENDPOINT}
     */
    public WecomCliTokenService(String authEndpoint) {
        this.authEndpoint = (authEndpoint == null || authEndpoint.isBlank())
                ? AUTH_ENDPOINT : authEndpoint.trim();
    }

    /** 令牌缓存：instanceId → 令牌。进程内，不落库（理由见类注释）。 */
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    /**
     * 每实例一把锁：用来做「按 instanceId 单飞」。
     *
     * <p>为什么不用 {@code computeIfAbsent}：它锁的是 ConcurrentHashMap 的**桶**，
     * 换票是一次网络往返（最长 30s），期间同桶的**其它** instanceId 也会被卡住。
     * 按实例各锁各的，互不牵连。
     */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 取可用的 CLI token（带缓存与单飞）。
     *
     * <p>调用方（发送策略）只需要「一个能用的 Bearer token」，不关心它是不是刚换的。
     *
     * @param instanceId 渠道实例 id（缓存键）
     * @param botId      bot 凭证里的 bot id（参与签名）
     * @param botSecret  bot 凭证里的 secret（参与签名，⛔ 不进日志/异常）
     * @return CLI token（43 字符）
     * @throws ImSendException 换票失败（网络 / 平台拒绝 / 响应缺字段）⇒
     *                         {@code IM_CREDENTIAL_EXCHANGE_FAILED}
     */
    public String tokenFor(String instanceId, String botId, String botSecret) throws ImSendException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "换取企微 CLI token 失败：instanceId 为空", null, false);
        }
        if (botId == null || botId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "换取企微 CLI token 失败：botId 为空", null, false);
        }
        if (botSecret == null || botSecret.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "换取企微 CLI token 失败：botSecret 为空", null, false);
        }

        long now = Instant.now().getEpochSecond();

        // ── 快路径：缓存命中 ──
        CachedToken cached = cache.get(instanceId);
        if (cached != null && cached.usable(now) && cached.matches(botId)) {
            return cached.token();
        }

        // ── 慢路径：按 instanceId 单飞 ──
        // 同一实例的并发请求排队等同一把锁 ⇒ 只换一次票；
        // 不同实例各锁各的 ⇒ 互不阻塞。
        Object lock = locks.computeIfAbsent(instanceId, k -> new Object());
        synchronized (lock) {
            // 双重检查：等锁期间可能已经有别的线程换好了。
            cached = cache.get(instanceId);
            long nowInLock = Instant.now().getEpochSecond();
            if (cached != null && cached.usable(nowInLock) && cached.matches(botId)) {
                return cached.token();
            }
            String token = exchange(instanceId, botId, botSecret);
            cache.put(instanceId, new CachedToken(token, botId, nowInLock + TOKEN_TTL_SECONDS));
            return token;
        }
    }

    /**
     * 作废缓存（下次调用会重新换票）。
     *
     * <p>用途：发送时平台回 {@code 853004}（token 失效）⇒ 清掉旧 token 再换一次。
     * 这是官方 CLI {@code auth/session.rs} 的同源重换策略（换 1 次，不循环）。
     *
     * <p>⛔ <b>只清缓存，不动锁</b>：锁对象一旦被移除，正在等锁的线程会拿到<b>另一把</b>锁，
     * 单飞保证就破了（两个线程同时换票）。锁对象的数量 = 实例数量，可忽略。
     */
    public void invalidate(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        CachedToken removed = cache.remove(instanceId);
        if (removed != null && log.isInfoEnabled()) {
            log.info("[WecomCliToken] 已作废 CLI token 缓存 instance={}", instanceId);
        }
    }

    // ───────────────────────── 换票实现 ─────────────────────────

    /**
     * 执行一次签名换票（无缓存、无重试）。
     *
     * <p>⛔ 请求里<b>没有</b> {@code Authorization} 头，也没有 {@code payload} 信封 ——
     * 这两个是这个端点与 {@code /cli/*} 网关最大的差别，写错就换不到票。
     */
    private String exchange(String instanceId, String botId, String botSecret) throws ImSendException {
        long time = Instant.now().getEpochSecond();
        String nonce = "cli_" + System.currentTimeMillis() + "_" + randomHex8();
        String signature = sha256Hex(botSecret + botId + time + nonce);

        ObjectNode body = json.createObjectNode();
        body.put("bot_id", botId);
        body.put("time", time);
        body.put("nonce", nonce);
        body.put("signature", signature);
        body.put("bind_source", BIND_SOURCE_QRCODE);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(authEndpoint))
                    // ⛔ 刻意不加 Authorization：这个端点的凭据就是 body 里的签名。
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：请求构造非法", null, true);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：调用被中断", null, true);
        } catch (Exception e) {
            // ⛔ 异常消息里只放异常类型，不放可能含 token 的原始报文。
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：网络异常（" + e.getClass().getSimpleName() + "）", null, true);
        }

        if (response.statusCode() / 100 != 2) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：HTTP " + response.statusCode(),
                    String.valueOf(response.statusCode()), true);
        }

        JsonNode root;
        try {
            root = json.readTree(response.body() == null ? "" : response.body());
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：响应不是合法 JSON", null, true);
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：响应不是 JSON 对象", null, true);
        }

        // 响应缺 errcode：官方 Rust 的 `FetchAuthResponse` 里 errcode 是 `#[serde(default)]`
        // （缺失 = 0 = 成功），所以这里也按 0 处理；真正的失败信号是「没有 token」。
        // 但缺 errcode 本身是异常形态 ⇒ 记一条告警，便于事后发现协议变化。
        if (!root.has("errcode") && log.isWarnEnabled()) {
            log.warn("[WecomCliToken] 换票响应缺少 errcode 字段 instance={}", instanceId);
        }
        int errcode = root.path("errcode").asInt(0);
        String errmsg = truncate(root.path("errmsg").asText(""));
        if (errcode != 0) {
            if (log.isWarnEnabled()) {
                log.warn("[WecomCliToken] 换票被平台拒绝 instance={} errcode={} errmsg={}",
                        instanceId, errcode, errmsg);
            }
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：errcode=" + errcode + " errmsg=" + errmsg,
                    String.valueOf(errcode), !isCredentialError(errcode));
        }

        String token = root.path("token").asText("");
        if (token.isBlank()) {
            // errcode=0 但没有 token —— 平台说成功了却没给票，必须当失败。
            String codeText = root.has("errcode") ? String.valueOf(errcode) : "缺失";
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "企业微信换取 CLI token 失败：errcode=" + codeText + " errmsg=响应缺少 token 字段",
                    root.has("errcode") ? String.valueOf(errcode) : null, true);
        }

        if (log.isInfoEnabled()) {
            // ⛔ 只打长度，不打 token 本身。
            log.info("[WecomCliToken] 换票成功 instance={} botId={} tokenLen={}",
                    instanceId, botId, token.length());
        }
        return token;
    }

    // ───────────────────────── 工具 ─────────────────────────

    /**
     * sha256 十六进制（小写、零填充）。
     *
     * <p>与官方 Rust 实现的 {@code sha256_hex} 一致：逐字节 {@code %02x}。
     * {@code String.format("%02x", byte)} 对负值按无符号处理（{@code (byte)0xFF → "ff"}），
     * 与 Rust 的 {@code u8} 语义一致。
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 必须实现的算法，走到这里说明 JRE 有问题，属于不可恢复的编程错误。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 8 位随机十六进制（nonce 的随机段）。4 字节 ⇒ 8 个 hex 字符。 */
    private static String randomHex8() {
        byte[] buf = new byte[4];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(8);
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 截断平台返回的 {@code errmsg}。
     *
     * <p>为什么：真机实测平台错误消息里会带 hint 号、来源 IP 与一个查询链接（约 160 字符），
     * 且长度不受我们控制 ⇒ 截断到 {@value #ERRMSG_LIMIT} 字符，既保留排障信息又不让异常消息无限长。
     */
    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= ERRMSG_LIMIT ? s : s.substring(0, ERRMSG_LIMIT) + "…";
    }

    /**
     * 缓存条目。
     *
     * @param token    CLI token
     * @param botId    换这张票时用的 botId（凭证轮换后 ⇒ 旧票必须作废）
     * @param expiresAt 失效时刻（epoch 秒）
     */
    private record CachedToken(String token, String botId, long expiresAt) {

        /** 是否仍然可用（把「提前 300s」算进去了）。 */
        boolean usable(long nowEpochSecond) {
            return nowEpochSecond < expiresAt - REFRESH_AHEAD_SECONDS;
        }

        /** 是否是同一个 botId 换来的（凭证轮换 ⇒ 旧票不能再用）。 */
        boolean matches(String requestedBotId) {
            return botId != null && botId.equals(requestedBotId);
        }
    }

    /**
     * 是否是「凭证本身不对」的错误码（重试没有意义）。
     *
     * <p>判断依据：
     * <ul>
     *   <li>{@code 853000} —— 真机实测：假 bot_id/secret 时返回
     *       {@code invalid bot_id or secret}；</li>
     *   <li>{@code 40001} / {@code 40013} —— 企微经典「invalid credential / invalid appid」。</li>
     * </ul>
     * 这几类必须 {@code retryable=false}：凭证错了再换一万次也换不到票，
     * 标成可重试只会让调用方做无用的重试（还会不断打平台）。
     *
     * <p>其余错误码（网络、限流、未知码）保持可重试 —— 不臆测含义，但给一次机会。
     */
    private static boolean isCredentialError(int errcode) {
        return errcode == 853000 || errcode == 40001 || errcode == 40013;
    }
}
