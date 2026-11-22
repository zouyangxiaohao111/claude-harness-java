package com.nexusai.domain.im_channel.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 企业微信 {@code /cli/*} 网关的通用调用器（纯 JDK HttpClient，零 CLI 子进程、零 SDK）。
 *
 * <h2>它只做协议，不做业务</h2>
 * 拼地址、包信封、解响应、映射错误码 —— 就这四件事。具体业务（发消息、列群…）由上层
 * （如 {@link WecomMessageApi}）拿 {@link #call} 返回的结果自己判断。
 *
 * <h2>🔴 协议（官方 CLI 逐字）</h2>
 * <pre>
 * POST https://qyapi.weixin.qq.com/cli/&lt;path&gt;
 *   Authorization: Bearer &lt;CLI token&gt;
 *   Content-Type: application/json
 *   body: {"payload":"&lt;业务 JSON 序列化成字符串&gt;"}      ← ★ 必须包一层（PIT-WC-022）
 * → {"errcode":0,"errmsg":"ok","results_json":"&lt;内层 JSON 字符串&gt;"}
 * </pre>
 *
 * <h3>为什么必须包 payload 信封（PIT-WC-022）</h3>
 * 不包的后果是平台回 {@code {"error":{"code":-1,"message":"系统失败"}}} ——
 * 这个错误信息<b>完全不提示真因</b>（参考实现为它排查过 12 个错误方向）。
 * 所以信封包裹放在本类这一个出口，业务侧只管传业务 JSON。
 *
 * <h3>响应是嵌套的（PIT-WC-023）</h3>
 * <pre>
 * 外层:  {"errcode":0,"errmsg":"ok","results_json":"&lt;字符串&gt;"}
 * 内层:  {"result":"&lt;又一层字符串&gt;"}  或  {"error":{"code":853008,"message":"..."}}
 * </pre>
 * 本类的解包策略：<b>逐层剥到能看见业务字段为止</b>，并且对每种形态都容忍
 * （{@code results_json} 可能是对象而不是字符串；{@code result} 可能已经是对象而不是字符串）。
 *
 * <h3>成功判据要同时看两处（PIT-WC-018）</h3>
 * <ul>
 *   <li>外层 {@code errcode} 必须为 0；</li>
 *   <li>内层<b>不能</b>有 {@code error} 键；</li>
 *   <li>内层若给了 {@code success:false}（没有 error 键但平台自认失败）⇒ <b>也算失败</b>。
 *       只判 {@code errcode} 会把「平台说失败了」当成「发出去了」。</li>
 * </ul>
 *
 * <h3>恒存在的两个非业务字段（PIT-WC-035）</h3>
 * 所有 CLI 响应的业务体里都恒含 {@code security_notice} / {@code extra_identity_context}
 * （长文本，schema 未声明）。解包出口统一剥掉，避免它们被上层当业务数据读。
 *
 * <h2>安全</h2>
 * ⛔ token 只出现在 {@code Authorization} 头里：<b>绝不进日志、绝不进异常消息</b>。
 * 异常里若需要贴响应片段，先做一次 token 替换（{@link #redact}）。
 */
@Component
public class WecomCliClient {

    private static final Logger log = LoggerFactory.getLogger(WecomCliClient.class);

    /** 企微 CLI 网关根地址。 */
    public static final String DEFAULT_BASE = "https://qyapi.weixin.qq.com";

    /** CLI 端点的统一前缀（业务 path 都在它后面）。 */
    public static final String CLI_PATH_PREFIX = "/cli";

    /** 连接超时（秒）：建连慢是网络问题，请求超时才是平台慢，两者分开设。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** 请求超时（秒）：对应集成目录 NXS-INT-CHN-007 的「发送 60s」。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** {@code security_notice}：所有 CLI 响应恒含的非业务字段（PIT-WC-035）。 */
    public static final String NON_BUSINESS_FIELD_SECURITY_NOTICE = "security_notice";
    /** {@code extra_identity_context}：同 {@link #NON_BUSINESS_FIELD_SECURITY_NOTICE}。 */
    public static final String NON_BUSINESS_FIELD_EXTRA_IDENTITY_CONTEXT = "extra_identity_context";

    /** 贴进异常消息的响应片段上限（字符）——够定位问题，又不至于把整段业务数据带进日志。 */
    private static final int RAW_SNIPPET_LIMIT = 200;

    /** 平台码：token 失效（官方 CLI {@code auth/session.rs}：同源 bot 静默重换后重放 1 次）。 */
    public static final int CODE_TOKEN_EXPIRED = 853004;
    /** 平台码：目标不在机器人最近会话（机器人不能主动私聊没聊过的人）。 */
    public static final int CODE_NOT_RECENT_SESSION = 853008;
    /** 平台码：登录态失效，需重新授权。 */
    public static final int CODE_SESSION_DEAD = -14;

    private final ObjectMapper json = new ObjectMapper();

    /** 共享 HttpClient（线程安全，连接池复用）。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final String baseUrl;

    /**
     * 生产用构造器（走默认网关）。
     *
     * <p>⚠️ 刻意标 {@code @Autowired}：本类有<b>两个</b>构造器，Spring 遇到多构造器且没有
     * 显式标注时只能按「默认无参」猜，规则一变就可能装配失败（单测还照样绿）。
     * 显式标注 ⇒ 装配行为确定。
     */
    @Autowired
    public WecomCliClient() {
        this(DEFAULT_BASE);
    }

    /** @param baseUrl 网关根地址（测试可注入 {@code http://127.0.0.1:<port>}） */
    public WecomCliClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : stripTrailingSlash(baseUrl.trim());
    }

    // ───────────────────────── 调用 ─────────────────────────

    /**
     * 调一个 CLI 网关方法。
     *
     * @param token        CLI token（由 {@link WecomCliTokenService} 换得）；空 ⇒ 直接失败
     * @param path         业务路径，如 {@code /cli/message/send}
     *                     （容忍 {@code /message/send} 这种少写前缀的写法，会自动补 {@code /cli}）
     * @param businessBody <b>未包裹</b>的业务 JSON；{@code null} 视作空对象 {@code {}}
     * @return 已解包的结果；<b>平台/业务失败不抛异常</b>，看 {@link Result#ok()}
     * @throws ImSendException 传输层失败（网络异常、HTTP 非 2xx、响应不是 JSON）
     */
    public Result call(String token, String path, ObjectNode businessBody) throws ImSendException {
        if (token == null || token.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微 CLI 调用失败：token 为空（未换取到 CLI token）", null, false);
        }
        String normalized = normalizePath(path);

        // ★ PIT-WC-022：payload 的值必须是**字符串**（序列化后的业务 JSON），不是对象本身。
        ObjectNode envelope = json.createObjectNode();
        envelope.put("payload", businessBody == null ? "{}" : businessBody.toString());

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + normalized))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(envelope.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "企微 CLI 路径非法（" + normalized + "）", null, false);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微 CLI 调用被中断（" + normalized + "）", null, true);
        } catch (Exception e) {
            // ⛔ 只放异常类型名：网络异常的 message 里可能带 URL/头部等敏感片段。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微 CLI 网络异常（" + normalized + "）：" + e.getClass().getSimpleName(),
                    null, true);
        }

        String raw = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微 CLI HTTP " + response.statusCode() + "（" + normalized + "）："
                            + truncate(redact(raw, token)),
                    String.valueOf(response.statusCode()), true);
        }
        return decode(normalized, raw, token);
    }

    // ───────────────────────── 解包（PIT-WC-023） ─────────────────────────

    private Result decode(String path, String raw, String token) throws ImSendException {
        JsonNode outer;
        try {
            outer = json.readTree(raw);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微 CLI 响应不是合法 JSON（" + path + "）：" + truncate(redact(raw, token)),
                    null, true);
        }
        if (outer == null || !outer.isObject()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微 CLI 响应不是 JSON 对象（" + path + "）：" + truncate(redact(raw, token)),
                    null, true);
        }

        int errcode = outer.path("errcode").asInt(0);
        String errmsg = outer.path("errmsg").asText("");

        // ① 网关层错误：直接带出去，由上层做语义映射。
        if (errcode != 0) {
            return new Result(errcode, errmsg, null, raw);
        }

        // ② results_json：字符串（正常）或对象（容错）。解析失败不算调用失败 ——
        //    有些端点压根不返回它（那时 result = null）。
        JsonNode resultsNode = outer.get("results_json");
        JsonNode inner = null;
        if (resultsNode != null && !resultsNode.isNull()) {
            if (resultsNode.isTextual()) {
                String text = resultsNode.asText();
                if (!text.isBlank()) {
                    try {
                        inner = json.readTree(text);
                    } catch (Exception e) {
                        // ⛔ 不抛：这是「没有可解析的业务体」，不是「调用失败」。
                        if (log.isWarnEnabled()) {
                            log.warn("[WecomCliClient] results_json 不是合法 JSON path={}", path);
                        }
                        inner = null;
                    }
                }
            } else if (resultsNode.isObject() || resultsNode.isArray()) {
                inner = resultsNode;
            }
        }
        if (inner == null) {
            return new Result(0, errmsg, null, raw);
        }

        // ③ 内层还可能再包一层 {"result": "<json 字符串>"} ⇒ 剥到能看见业务字段为止。
        //    ⚠️ 但**先看 error**：内层同时带 error 与 result 时，业务错误必须留在手上
        //    （剥了壳就再也读不到 error.code ⇒ 平台码丢失，853008/853004 无法映射）。
        JsonNode payload = inner;
        JsonNode innerError = inner.get("error");
        boolean hasInnerError = innerError != null && !innerError.isNull();
        JsonNode nested = inner.get("result");
        if (!hasInnerError && nested != null && !nested.isNull()) {
            if (nested.isTextual()) {
                String text = nested.asText();
                if (!text.isBlank()) {
                    try {
                        payload = json.readTree(text);
                    } catch (Exception e) {
                        // 非 JSON 的纯文本 ⇒ 用 TextNode 包住，不抛。
                        payload = json.getNodeFactory().textNode(text);
                    }
                }
            } else {
                payload = nested;
            }
        }

        return new Result(0, errmsg, stripNonBusinessFields(payload), raw);
    }

    /**
     * 剥掉恒存在的两个非业务字段（PIT-WC-035）。
     *
     * <p>⛔ 不修改入参节点（返回副本），原始响应体仍完整保留在 {@code rawBody} 里。
     */
    static JsonNode stripNonBusinessFields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        if (!node.has(NON_BUSINESS_FIELD_SECURITY_NOTICE)
                && !node.has(NON_BUSINESS_FIELD_EXTRA_IDENTITY_CONTEXT)) {
            return node;
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.remove(NON_BUSINESS_FIELD_SECURITY_NOTICE);
        copy.remove(NON_BUSINESS_FIELD_EXTRA_IDENTITY_CONTEXT);
        return copy;
    }

    // ───────────────────────── 结果 ─────────────────────────

    /**
     * 一次 CLI 调用的结果。
     *
     * @param errcode 外层网关错误码；0 = 网关通过
     * @param errmsg  外层网关消息
     * @param result  解包后的业务体；可能为 null（端点不返回业务数据）
     * @param rawBody 原始响应体（排障用，⛔ 不要整段写日志）
     */
    public record Result(int errcode, String errmsg, JsonNode result, String rawBody) {

        /**
         * 成功判据（PIT-WC-018）：<b>同时</b>看三处。
         *
         * <ol>
         *   <li>外层 {@code errcode == 0}；</li>
         *   <li>业务体里没有 {@code error} 键（平台把业务错误放在内层）；</li>
         *   <li>业务体里的 {@code success} 若存在且为 boolean，必须为 true ——
         *       {@code success:false} 是「无 error 键但平台自认失败」，不能当成功。</li>
         * </ol>
         */
        public boolean ok() {
            if (errcode != 0) {
                return false;
            }
            JsonNode body = result;
            if (body == null || !body.isObject()) {
                return true;
            }
            JsonNode error = body.get("error");
            if (error != null && !error.isNull()) {
                return false;
            }
            JsonNode success = body.get("success");
            return success == null || !success.isBoolean() || success.asBoolean();
        }

        /**
         * 平台错误码：优先取外层 {@code errcode}，其次取内层 {@code error.code}。
         *
         * <p>为什么两个地方都看：网关层错误（如 HTTP 层的错误码）走外层，
         * 业务层错误（如 853008 不在最近会话）走内层 {@code error.code}。
         */
        public int platformCode() {
            if (errcode != 0) {
                return errcode;
            }
            JsonNode body = result;
            if (body == null || !body.isObject()) {
                return 0;
            }
            JsonNode error = body.get("error");
            if (error != null && error.isObject()) {
                int code = intOf(error.get("code"));
                if (code != 0) {
                    return code;
                }
            }
            // 容错：有些响应把业务码直接放在业务体顶层。
            return intOf(body.get("code"));
        }

        /** 错误消息：优先取内层 {@code error.message}，否则用外层 {@code errmsg}。 */
        public String errorMessage() {
            JsonNode body = result;
            if (body != null && body.isObject()) {
                JsonNode error = body.get("error");
                if (error != null && error.isObject()) {
                    JsonNode message = error.get("message");
                    if (message != null && message.isTextual() && !message.asText().isBlank()) {
                        return message.asText();
                    }
                }
            }
            return errmsg == null ? "" : errmsg;
        }

        /** 排障摘要：只给码与类型，不含业务数据、不含 token。 */
        public String describe() {
            if (ok()) {
                return "OK errcode=0 result=" + (result == null ? "null" : result.getNodeType().name());
            }
            return "FAIL errcode=" + errcode + " code=" + platformCode()
                    + " msg=" + truncate(errorMessage());
        }

        /** 默认打印走 {@link #describe()}，避免业务数据被顺手打进日志。 */
        @Override
        public String toString() {
            return describe();
        }

        /** 数字节点取值（容忍平台把码写成字符串）。 */
        private static int intOf(JsonNode node) {
            if (node == null || node.isNull()) {
                return 0;
            }
            if (node.isNumber()) {
                return node.asInt();
            }
            if (node.isTextual()) {
                try {
                    return Integer.parseInt(node.asText().trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /**
     * 把失败结果映射成带语义的 {@link ImSendException}。
     *
     * <h3>只对有依据的码做语义映射</h3>
     * <ul>
     *   <li>{@code 853004} token 失效 ⇒ {@code IM_CREDENTIAL_EXCHANGE_FAILED}（可重试：
     *       调用方应当作废缓存、重新换票后重放 1 次）；</li>
     *   <li>{@code 853008} 不在机器人最近会话 ⇒ {@code IM_SEND_PLATFORM_ERROR}（不可重试：
     *       需要对方先给机器人发消息，重试没有意义）；</li>
     *   <li>{@code -14} 登录态失效 ⇒ {@code IM_SEND_PLATFORM_ERROR}（不可重试：需要重新授权）；</li>
     *   <li>其余 ⇒ 一律 {@code IM_SEND_PLATFORM_ERROR}，原始码放 {@code platformCode} 供排查，
     *       <b>不臆测含义</b>，也不自动重试（避免对不懂的错误码做重试风暴）。</li>
     * </ul>
     *
     * @param r    失败的结果
     * @param what 动作描述（给用户看的失败原因前缀）
     */
    public static ImSendException failureOf(Result r, String what) {
        if (r == null) {
            return new ImSendException("IM_SEND_PLATFORM_ERROR",
                    (what == null ? "企微 CLI 调用" : what) + " 返回空结果", null, false);
        }
        int code = r.platformCode();
        String prefix = what == null || what.isBlank() ? "企微 CLI 调用" : what;

        // errcode=0 却判失败 ⇒ 只可能是内层 success=false（PIT-WC-018）。
        // 这种情况必须点明，否则消息会写成「code=0 msg=ok 失败」—— 自相矛盾，排查时会误导。
        if (code == 0) {
            return new ImSendException("IM_SEND_PLATFORM_ERROR",
                    prefix + " 失败：平台返回 success=false（errcode=0 但平台自认失败）", "0", false);
        }

        if (code == CODE_TOKEN_EXPIRED) {
            return new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    prefix + " 失败：CLI token 已失效（code=853004），需重新换取后重试",
                    String.valueOf(CODE_TOKEN_EXPIRED), true);
        }
        if (code == CODE_NOT_RECENT_SESSION) {
            return new ImSendException("IM_SEND_PLATFORM_ERROR",
                    prefix + " 失败：目标不在机器人最近会话（code=853008），需对方先给机器人发一条消息",
                    String.valueOf(CODE_NOT_RECENT_SESSION), false);
        }
        if (code == CODE_SESSION_DEAD) {
            return new ImSendException("IM_SEND_PLATFORM_ERROR",
                    prefix + " 失败：登录态失效（code=-14），需重新授权",
                    String.valueOf(CODE_SESSION_DEAD), false);
        }
        return new ImSendException("IM_SEND_PLATFORM_ERROR",
                prefix + " 失败：code=" + code + " msg=" + truncate(r.errorMessage()),
                String.valueOf(code), false);
    }

    // ───────────────────────── 内部 ─────────────────────────

    /**
     * 路径归一：补前导斜杠；没写 {@code /cli} 前缀的补上（调用方少写前缀不该变成 404）。
     */
    static String normalizePath(String path) throws ImSendException {
        if (path == null || path.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微 CLI 路径不能为空", null, false);
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.equals(CLI_PATH_PREFIX) && !p.startsWith(CLI_PATH_PREFIX + "/")) {
            p = CLI_PATH_PREFIX + p;
        }
        return p;
    }

    /** 防凭证外泄：片段里若回显了 token，替换成掩码。 */
    private static String redact(String text, String token) {
        if (text == null) {
            return "";
        }
        if (token == null || token.isBlank()) {
            return text;
        }
        return text.replace(token, mask(token));
    }

    /** 日志掩码：前 4 + {@code …} + 后 2 + {@code (len=N)}；不足 8 字符全掩码。 */
    public static String mask(String token) {
        if (token == null || token.length() < 8) {
            return "***";
        }
        return token.substring(0, 4) + "…" + token.substring(token.length() - 2)
                + " (len=" + token.length() + ")";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= RAW_SNIPPET_LIMIT ? s : s.substring(0, RAW_SNIPPET_LIMIT) + "…";
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
