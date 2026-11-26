package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.OutboundProxy;
import com.nexusai.model.provider.dto.ProviderDto;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Google 订阅（Gemini Code Assist / Cloud Code）登录链路。
 *
 * <p><b>流程</b>：前端 {@code POST .../oauth/google/authorize} 拿 URL + 绝对硬 deadline →
 * 用户在浏览器登录 → 浏览器跳 {@code http://127.0.0.1:<端口>/oauth2callback?code&state} →
 * {@link GoogleOAuthCallbackServer} 调 {@link #onCallback}；端口起不来时用户可把整条回调 URL
 * （或裸 code）粘回 {@code complete}。
 *
 * <p><b>协议形态</b>（{@code gemini-cli@08e9a20} {@code code_assist/oauth2.ts:535-658}）：
 * <b>无 PKCE</b>；{@code state} 是 32 字节随机 hex；{@code access_type=offline}；{@code prompt=consent}；
 * token 端点 form-urlencoded（{@link OAuthTokenClient}）。
 *
 * <p><b>active-attempt 生命周期</b>：一次 authorize 建立一个内存 attempt，承载 provider /
 * 绝对硬 deadline（{@code createdAt + TTL}，含 onboarding）/ epoch / 确切 redirectUri / 工作线程 /
 * 到期定时器。从 authorize → claim（state 消费）→ 换 token → LRO onboard → 终态，deadline 始终是
 * authorize 时算出的那一个，⛔ status 轮询不会续命。pending 仍按旧口径落 {@code providers.oauth_state}；
 * 运行中上下文只在内存（重启不假装恢复运行中任务）。
 *
 * <p><b>原子终态</b>：成功写库前的「epoch + deadline + 仍是当前 attempt」检查，与 logout / 到期清凭据
 * 在同一个 per-provider 锁内完成（避免 check-then-write）；旧结果迟到一律丢弃，不覆盖新 attempt / error。
 *
 * <p><b>不落半成功</b>：只有「换 token 成功 + project 解析成功」才写库（token/project 同一次行更新）。
 *
 * <p><b>不打印 token / code / state / client_secret</b>。
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** state 原始随机字节数（32 字节 → 64 hex，官方 oauth2.ts:544 同口径）。 */
    private static final int STATE_BYTES = 32;

    /** 空闲/成功/失败后关闭监听的延迟（毫秒）：给当前 HTTP 响应留出写出时间。 */
    private static final long LISTENER_STOP_DELAY_MILLIS = 300L;

    /** 到期/取消时的默认错误文案（脱敏，不含 token）。 */
    private static final String EXPIRED_MESSAGE = "登录链接已过期，请重新点击登录获取新的授权链接";
    private static final String CANCELLED_MESSAGE = "登录已取消，请重新点击登录";

    /**
     * 已知 OAuth error token → 固定安全文案。
     *
     * <p>⛔ 绝不拼接 {@code error} / {@code error_description} / {@code state} / HTML —— 这些值是
     * provider 或攻击者可控的回显面。
     */
    private static final Map<String, String> OAUTH_ERROR_MESSAGES;
    /** 裸串输入时判定「这是错误 token 而不是授权码」的白名单（整串精确匹配，⛔ 不做子串猜）。 */
    private static final java.util.Set<String> OAUTH_ERROR_TOKENS;
    /** 未知 / 空 error 的安全兜底文案。 */
    static final String DEFAULT_OAUTH_ERROR_MESSAGE = "授权未完成，请重新登录";

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("access_denied", "已取消授权，请重新点击登录");
        m.put("login_required", "需要重新登录，请点击登录重新授权");
        m.put("consent_required", "需要重新确认授权，请点击登录重新授权");
        m.put("interaction_required", "需要重新交互授权，请点击登录重新授权");
        m.put("invalid_request", "授权请求无效，请重新点击登录");
        m.put("unauthorized_client", "授权客户端不被允许，请重新点击登录");
        m.put("unsupported_response_type", "授权方式不受支持，请重新点击登录");
        m.put("invalid_scope", "授权范围无效，请重新点击登录");
        m.put("server_error", "授权服务暂时异常，请重新点击登录");
        m.put("temporarily_unavailable", "授权服务暂时不可用，请稍后重试");
        m.put("invalid_grant", "授权已失效，请重新点击登录");
        m.put("admin_policy_enforced", "账号策略禁止该授权，请联系管理员");
        m.put("org_internal", "该账号属于组织内部应用限制，无法授权");
        OAUTH_ERROR_MESSAGES = java.util.Collections.unmodifiableMap(m);
        OAUTH_ERROR_TOKENS = OAUTH_ERROR_MESSAGES.keySet();
    }

    /** attempt 阶段。 */
    enum Phase { PENDING, ONBOARDING, READY, EXPIRED, ERROR }

    /** 到期调度器（默认共享 daemon 线程；单测注入可控实现）。 */
    public interface DeadlineScheduler {
        Cancellable schedule(long delayMillis, Runnable task);

        interface Cancellable {
            void cancel();
        }
    }

    /** 回调监听引用（测试可注入；生产由 Spring 提供 bean）。 */
    interface CallbackServerRef {
        GoogleOAuthCallbackServer get();
    }

    private static final DeadlineScheduler DEFAULT_SCHEDULER = new DeadlineScheduler() {
        private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "google-oauth-deadline");
            t.setDaemon(true);
            return t;
        });

        @Override
        public Cancellable schedule(long delayMillis, Runnable task) {
            ScheduledFuture<?> future = pool.schedule(task, Math.max(0L, delayMillis),
                TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    };

    private final ProviderService providerService;
    private final GoogleCodeAssistClient codeAssistClient;
    private final OAuthTokenClient tokenClient;
    /** token 端点（默认官方端点；包内构造器可注入本地桩，⛔ 生产恒为官方值）。 */
    private final String tokenEndpoint;
    private final Clock clock;
    private final DeadlineScheduler scheduler;
    private CallbackServerRef callbackServer;

    /** per-provider 串行锁（state 读取-消费、终态写库必须原子）。 */
    private final ConcurrentHashMap<String, Object> providerLocks = new ConcurrentHashMap<>();

    /** per-provider 退出/换代 epoch（防旧结果复活）。 */
    private final ConcurrentHashMap<String, AtomicLong> logoutEpochs = new ConcurrentHashMap<>();

    /** 当前在途 attempt（每 provider 至多一个；跨 provider 共享同一监听）。 */
    private final ConcurrentHashMap<String, Attempt> activeAttempts = new ConcurrentHashMap<>();

    /** 跨 provider 的 attempt 表 / 监听释放判定的全局锁（锁序：providerLock → attemptsLock）。 */
    private final Object attemptsLock = new Object();

    /** 最近一次登录失败原因（脱敏；仅内存态，重启即清）。 */
    private final ConcurrentHashMap<String, String> lastErrors = new ConcurrentHashMap<>();

    @Autowired
    public GoogleOAuthService(ProviderService providerService,
            GoogleCodeAssistClient codeAssistClient,
            ObjectProvider<GoogleOAuthCallbackServer> callbackServers) {
        this(providerService, codeAssistClient, new OAuthTokenClient(newTokenHttpClient()),
            GoogleOAuthProviderConfig.TOKEN_ENDPOINT, Clock.systemUTC(), DEFAULT_SCHEDULER);
        this.callbackServer = callbackServers == null ? null : () -> callbackServers.getObject();
    }

    GoogleOAuthService(ProviderService providerService, GoogleCodeAssistClient codeAssistClient,
            OAuthTokenClient tokenClient) {
        this(providerService, codeAssistClient, tokenClient, GoogleOAuthProviderConfig.TOKEN_ENDPOINT);
    }

    GoogleOAuthService(ProviderService providerService, GoogleCodeAssistClient codeAssistClient,
            OAuthTokenClient tokenClient, String tokenEndpoint) {
        this(providerService, codeAssistClient, tokenClient, tokenEndpoint,
            Clock.systemUTC(), DEFAULT_SCHEDULER);
    }

    GoogleOAuthService(ProviderService providerService, GoogleCodeAssistClient codeAssistClient,
            OAuthTokenClient tokenClient, String tokenEndpoint, Clock clock,
            DeadlineScheduler scheduler) {
        this.providerService = providerService;
        this.codeAssistClient = codeAssistClient;
        this.tokenClient = tokenClient;
        this.tokenEndpoint = tokenEndpoint;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /** 测试接线：绑定回调监听（真实 localhost 监听，不发 Google 请求）。 */
    void bindCallbackServer(CallbackServerRef ref) {
        this.callbackServer = ref;
    }

    /**
     * token 端点专用 client：Google 端点不在 {@code OutboundProxy} 的代理白名单里，
     * 设置页配了出站线路时应无条件走代理（与 Claude 链同口径），⛔ 不改 OutboundProxy。
     */
    static HttpClient newTokenHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(OutboundProxy.dynamicSelector())
            .build();
    }

    // ───────────────────────────── attempt ─────────────────────────────

    /** 一次在途登录的运行期上下文（仅在内存；重启不恢复）。 */
    private static final class Attempt {
        final String providerId;
        final String state;
        final String redirectUri;
        /** 绝对硬 deadline（authorize 时算出，含 onboarding；⛔ 不续期）。 */
        final long deadlineAt;
        final long epoch;
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PENDING);
        volatile Thread worker;
        volatile DeadlineScheduler.Cancellable timer;

        Attempt(String providerId, String state, String redirectUri, long deadlineAt, long epoch) {
            this.providerId = providerId;
            this.state = state;
            this.redirectUri = redirectUri;
            this.deadlineAt = deadlineAt;
            this.epoch = epoch;
        }
    }

    private static boolean isTerminal(Attempt a) {
        Phase p = a.phase.get();
        return p == Phase.READY || p == Phase.EXPIRED || p == Phase.ERROR;
    }

    // ───────────────────────────── 授权 ─────────────────────────────

    /** authorize 返回：URL + 本轮绝对硬 deadline（epoch ms；含 onboarding，不因 status 续命）。 */
    public record AuthorizeResult(String url, long expiresAt) {}

    /**
     * 生成授权链接 + 在回环端口启动（或复用）回调监听，并登记本轮到期的定时取消。
     *
     * @param providerId provider id（必须存在且 type=gemini_code_assist）
     * @throws IllegalArgumentException 类型不符 / 输入为空
     */
    public AuthorizeResult buildAuthorizeUrl(String providerId) {
        requireGoogleProvider(providerId);
        long now = clock.millis();
        long deadlineAt = now + GoogleOAuthProviderConfig.LOGIN_STATE_TTL_MILLIS;
        String state = newState();
        int port = ensureListenerPort();
        String redirectUri = redirectUri(port);

        synchronized (lockFor(providerId)) {
            // 新 authorize 赢：换代 epoch，旧 attempt 立即失效（迟到结果不可覆盖）。
            long epoch = epochOf(providerId).incrementAndGet();
            Attempt prev = activeAttempts.get(providerId);
            if (prev != null) {
                terminalizeLocked(prev, Phase.EXPIRED, null);
            }
            Attempt attempt = new Attempt(providerId, state, redirectUri, deadlineAt, epoch);
            synchronized (attemptsLock) {
                activeAttempts.put(providerId, attempt);
            }
            List<PendingState> pending = readPendingStates(providerId);
            pending.removeIf(s -> isExpired(s, now));
            pending.add(new PendingState(state, now, redirectUri));
            if (pending.size() > GoogleOAuthProviderConfig.MAX_PENDING_STATES) {
                pending = new ArrayList<>(pending.subList(
                    pending.size() - GoogleOAuthProviderConfig.MAX_PENDING_STATES, pending.size()));
            }
            providerService.writeOAuthState(providerId, encodeStates(pending));
            lastErrors.remove(providerId);
            scheduleExpiryLocked(attempt);
        }
        log.info("已生成 Google 订阅授权链接 provider={} 回调端口={} deadline={}", providerId, port,
            deadlineAt);
        return new AuthorizeResult(authorizeUrl(state, redirectUri), deadlineAt);
    }

    /**
     * 复用/分配回调端口：已有监听则复用同一端口（多个 attempt 共享同一监听，⛔ 不误关他人），
     * 否则分配临时端口并启动监听（3 次重试，失败降级手动粘贴）。无 callback 组件时只分配端口不监听。
     */
    int ensureListenerPort() {
        synchronized (attemptsLock) {
            GoogleOAuthCallbackServer server = callbackServer();
            if (server == null) {
                int p = findFreePort();
                return p > 0 ? p : 0;
            }
            int running = server.runningPort();
            if (running > 0) {
                return running;
            }
            for (int i = 0; i < 3; i++) {
                int candidate = findFreePort();
                if (candidate > 0 && startCallbackServer(candidate)) {
                    return candidate;
                }
            }
            int fallback = findFreePort();
            return fallback > 0 ? fallback : 0;
        }
    }

    /** 拼 authorize URL（纯函数；单测直接断言参数）。⚠️ 无 PKCE、state 为独立随机串。 */
    static String authorizeUrl(String state, String redirectUri) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("redirect_uri 不能为空");
        }
        StringBuilder sb = new StringBuilder(GoogleOAuthProviderConfig.AUTHORIZATION_ENDPOINT);
        sb.append('?');
        appendParam(sb, "client_id", GoogleOAuthProviderConfig.CLIENT_ID);
        appendParam(sb, "response_type", "code");
        appendParam(sb, "redirect_uri", redirectUri);
        appendParam(sb, "scope", String.join(" ", GoogleOAuthProviderConfig.SCOPES));
        appendParam(sb, "access_type", "offline");
        // 强制同意页：保证重登也发放 refresh_token（官方浏览器流本身不带此参数，
        // 但缺 refresh_token 会让登录看似成功却在过期后立刻失效 —— 见 §11 安全核验 2b）。
        appendParam(sb, "prompt", "consent");
        appendParam(sb, "state", state);
        return sb.toString();
    }

    static String redirectUri(int port) {
        return "http://" + GoogleOAuthProviderConfig.CALLBACK_HOST + ":" + port
            + GoogleOAuthProviderConfig.CALLBACK_PATH;
    }

    // ───────────────────────────── 完成登录 ─────────────────────────────

    /**
     * 用用户粘回的整条回调 URL（或裸 code）完成登录。
     *
     * @throws IllegalArgumentException 输入/state/TTL 错误、换 token 失败、project 未取得
     * @throws OAuthTokenExchangeError  token 端点返回错误（controller 另行 400）
     */
    public void complete(String providerId, String codeOrUrl) {
        requireGoogleProvider(providerId);
        ParsedCallback parsed = parseCallbackDetailed(codeOrUrl);
        if (parsed.error() != null) {
            // 用户在浏览器点了「拒绝」后粘回完整回调 URL：先在受限条件下终结本 provider 的本次 attempt，
            // 再用同一安全文案抛出（⛔ 不进入换票；⛔ 不因他 provider 的 state 取消任何其它 attempt）。
            failCurrentPendingError(providerId, parsed.state(), parsed.error());
            throw new IllegalArgumentException(safeOAuthErrorMessage(parsed.error()));
        }
        Attempt attempt = claimAttempt(providerId, parsed.state());
        runExchange(providerId, parsed.code(), attempt);
    }

    /**
     * 浏览器回调返回 {@code error}（如用户点了拒绝）时的入口，由 {@link GoogleOAuthCallbackServer} 调用。
     *
     * <p><b>仅</b>在状态确切匹配「本 provider、未被 claim、仍 PENDING、未过期」的当前 attempt 时终结它；
     * 未知 / 过期 / 他 provider / 无 state 一律 no-op，⛔ 不影响任何合法 attempt，也不会误关共享监听。
     */
    public void onCallbackError(String state, String error) {
        if (state == null || state.isBlank()) {
            return; // 无 state：无法归属，只拒绝
        }
        String providerId;
        try {
            providerId = findProviderIdByState(state);
        } catch (RuntimeException e) {
            return;
        }
        if (providerId == null) {
            return; // 未知 / 已消费 / 他类型 provider 的 state
        }
        failCurrentPendingError(providerId, state, error);
    }

    /**
     * 受限 error 终结：state 必须仍在本 provider 的 pending 列表（= 未被 claim），
     * 且与当前 attempt 精确相等、仍 PENDING、未过期，才落 error 终态。
     *
     * @return true = 本次确实终结了一个 attempt
     */
    private boolean failCurrentPendingError(String providerId, String state, String error) {
        if (providerId == null || state == null || state.isBlank()) {
            return false;
        }
        boolean stillPending = false;
        for (PendingState s : readPendingStates(providerId)) {
            if (s.state().equals(state)) {
                stillPending = true;
                break;
            }
        }
        if (!stillPending) {
            return false; // 已 claim / 已消费 / 过期回收 —— 不取消正在处理的 attempt
        }
        synchronized (lockFor(providerId)) {
            Attempt cur = activeAttempts.get(providerId);
            if (cur == null || !state.equals(cur.state)) {
                return false; // 已被新 authorize 替换 / 已 ready / 已退出
            }
            if (cur.phase.get() != Phase.PENDING || !liveLocked(cur)) {
                return false; // onboarding/ready/error 或已过期
            }
            failAttempt(cur, safeOAuthErrorMessage(error), true);
            return true;
        }
    }

    /** 已知 error token → 固定安全文案；未知/空 → 固定兜底。⛔ 不反射任何输入原文。 */
    static String safeOAuthErrorMessage(String error) {
        if (error == null) {
            return DEFAULT_OAUTH_ERROR_MESSAGE;
        }
        String key = error.trim().toLowerCase(java.util.Locale.ROOT);
        return OAUTH_ERROR_MESSAGES.getOrDefault(key, DEFAULT_OAUTH_ERROR_MESSAGE);
    }

    /**
     * 浏览器回调入口（{@link GoogleOAuthCallbackServer} 调用）。state 无 provider 前缀 ⇒ 全表反查。
     */
    public void onCallback(String state, String code) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("回调里没有 state 参数，请重新登录");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("回调里没有 code 参数，请重新登录");
        }
        String providerId = findProviderIdByState(state);
        if (providerId == null) {
            throw new IllegalArgumentException("登录状态不存在或已被使用，请重新点击登录获取授权链接");
        }
        Attempt attempt = claimAttempt(providerId, state);
        runExchange(providerId, code.trim(), attempt);
    }

    /**
     * 退出登录：取消本 attempt 的定时器 / 打断工作线程 / 清凭据 + project + state，回落 api_key。
     *
     * <p>与成功写库、到期终态共用同一 per-provider 锁 ⇒ 退出赢后，正在收尾的旧结果写不进来。
     */
    public void logout(String providerId) {
        requireGoogleProvider(providerId);
        Thread worker;
        synchronized (lockFor(providerId)) {
            epochOf(providerId).incrementAndGet();
            Attempt a = activeAttempts.get(providerId);
            if (a != null) {
                terminalizeLocked(a, Phase.EXPIRED, null);
                worker = a.worker;
            } else {
                worker = null;
            }
            providerService.clearOAuth(providerId);
            providerService.writeOAuthState(providerId, null);
            lastErrors.remove(providerId);
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
        releaseListenerIfNoLiveAttempts();
        log.info("Google 订阅已退出登录 provider={}", providerId);
    }

    // ───────────────────────────── 状态 ─────────────────────────────

    /** status 返回体（字段名与冻结契约一致）。 */
    public record Status(String status, String oauthProject, Long expiresAt, String message) {}

    /**
     * 只读状态（⛔ 不 refresh、不发网络请求、不创建/续期 deadline）。
     *
     * <p>判定顺序：<b>当前 attempt 优先</b>（pending / onboarding / 由固定 deadline 推出的 error）
     * → DB ready（token+project）→ DB onboarding（沿用 token 到期，仅历史数据）→ 最近失败 → DB pending
     * → logged_out。到期终态由 authorize 时登记的定时器产生，status 轮询不会续命。
     */
    public Status status(String providerId) {
        ProviderService.OAuthSnapshot snap = providerService.getOAuthSnapshot(providerId);
        if (snap == null) {
            throw new com.nexusai.infra.exception.NotFoundException(
                "Provider " + providerId + " not found");
        }
        if (!isGoogleType(snap.type())) {
            throw new IllegalArgumentException("该 provider 不是 Google 订阅类型");
        }

        long now = clock.millis();
        Attempt attempt = activeAttempts.get(providerId);
        if (attempt != null && !isTerminal(attempt)) {
            if (now >= attempt.deadlineAt) {
                // 只读：直接由固定 deadline 推导 error（定时器也会产生同一终态），不改任何状态。
                return new Status("error", null, null, EXPIRED_MESSAGE);
            }
            if (attempt.phase.get() == Phase.ONBOARDING) {
                return new Status("onboarding", null, attempt.deadlineAt,
                    "已登录，Cloud Code 项目初始化中（未完成）");
            }
            return new Status("pending", null, attempt.deadlineAt, null);
        }

        boolean googleLoggedIn = ProviderService.AUTH_MODE_GOOGLE_OAUTH.equals(snap.authMode())
            && snap.hasAccessToken();
        if (googleLoggedIn) {
            if (snap.project() != null) {
                return new Status("ready", snap.project(), snap.expiresAt(), null);
            }
            return new Status("onboarding", null, snap.expiresAt(),
                "已登录，Cloud Code 项目初始化未完成（账号可能无订阅资格或需要绑定 GCP project）");
        }
        String error = lastErrors.get(providerId);
        if (error != null) {
            return new Status("error", null, null, error);
        }
        PendingState pending = latestPending(providerId);
        if (pending != null) {
            return new Status("pending", null,
                pending.createdAt() + GoogleOAuthProviderConfig.LOGIN_STATE_TTL_MILLIS, null);
        }
        return new Status("logged_out", null, null, null);
    }

    // ───────────────────────────── 换 token + project ─────────────────────────────

    private void runExchange(String providerId, String code, Attempt attempt) {
        attempt.worker = Thread.currentThread();
        try {
            if (clock.millis() >= attempt.deadlineAt) {
                failAttempt(attempt, EXPIRED_MESSAGE, true);
                throw new IllegalArgumentException(EXPIRED_MESSAGE);
            }
            OAuthTokenResponse tokens;
            try {
                tokens = exchange(providerId, code, attempt.redirectUri);
            } catch (OAuthTokenExchangeError e) {
                // 先落脱敏 error 并清本 attempt 资源，再 rethrow（不泄露 token/client/code）。
                failAttempt(attempt, "授权码兑换失败，请重新点击登录获取新的授权链接", true);
                throw e;
            } catch (RuntimeException e) {
                String msg = sanitize(e.getMessage());
                failAttempt(attempt, msg == null || msg.isBlank()
                    ? "授权码兑换失败，请重新点击登录获取新的授权链接" : msg, true);
                throw new IllegalArgumentException("授权码兑换失败，请重新点击登录获取新的授权链接", e);
            }

            // 首次登录必须拿到 refresh_token：否则 access 过期后无法续期，登录「看似成功」实际不可用。
            if ((tokens.refreshToken() == null || tokens.refreshToken().isBlank())
                    && !hasStoredRefreshToken(providerId)) {
                failAttempt(attempt, "Google 未返回长期授权凭据（refresh_token），请重新登录并在授权页允许离线访问",
                    true);
                throw new IllegalArgumentException(
                    "Google 未返回长期授权凭据（refresh_token），请重新登录并在授权页允许离线访问");
            }

            // 换票后、onboard 前复核：退出/换代/到期后不再推进。
            if (!markOnboarding(attempt)) {
                throw new IllegalArgumentException(CANCELLED_MESSAGE);
            }
            Duration budget = remainingBudget(attempt);
            if (budget == null) {
                failAttempt(attempt, "Google 订阅项目初始化超时，请稍后重试", true);
                throw new IllegalArgumentException("Google 订阅项目初始化超时，请稍后重试");
            }
            String project;
            try {
                // 显式两参：剩余时间 = min(总 deadline 剩余, 90s cap)，≤0 已在上面短路。
                project = codeAssistClient.resolveProject(tokens.accessToken(), budget);
            } catch (GoogleCodeAssistClient.GoogleCodeAssistException e) {
                String msg = sanitize(e.getMessage());
                failAttempt(attempt, msg == null || msg.isBlank()
                    ? "Google 订阅项目初始化失败" : msg, true);
                throw new IllegalArgumentException(
                    msg == null || msg.isBlank() ? "Google 订阅项目初始化失败" : msg);
            } catch (RuntimeException e) {
                String msg = sanitize(e.getMessage());
                failAttempt(attempt, msg == null || msg.isBlank()
                    ? "Google 订阅项目初始化失败" : msg, true);
                throw new IllegalArgumentException("Google 订阅项目初始化失败", e);
            }

            if (!tryCommitReady(attempt, tokens, project)) {
                throw new IllegalArgumentException(CANCELLED_MESSAGE);
            }
            log.info("Google 订阅登录完成 provider={} expiresAt={}", providerId, tokens.expiresAt());
        } finally {
            if (attempt.worker == Thread.currentThread()) {
                attempt.worker = null;
            }
        }
    }

    /**
     * 原子提交 ready：在 per-provider 锁内复核「未终态 + 仍是当前 attempt + epoch 一致 + 未过 deadline」，
     * 复核通过才写库（token+project 同行）并清 state。过期/退出/换代后一律不写。
     */
    private boolean tryCommitReady(Attempt a, OAuthTokenResponse tokens, String project) {
        boolean ok = false;
        synchronized (lockFor(a.providerId)) {
            if (!liveLocked(a)) {
                return false;
            }
            try {
                providerService.saveGoogleOAuthCredential(a.providerId, tokens.accessToken(),
                    tokens.refreshToken(), tokens.expiresAt(), project);
                providerService.writeOAuthState(a.providerId, null);
                a.phase.set(Phase.READY);
                lastErrors.remove(a.providerId);
                cancelTimerLocked(a);
                synchronized (attemptsLock) {
                    activeAttempts.remove(a.providerId, a);
                }
                ok = true;
            } catch (RuntimeException e) {
                // 写库失败不落半成功：转 error，未完成。
                a.phase.set(Phase.ERROR);
                lastErrors.put(a.providerId, "Google 订阅登录写入失败，请重试");
                cancelTimerLocked(a);
                synchronized (attemptsLock) {
                    activeAttempts.remove(a.providerId, a);
                }
            }
        }
        releaseListenerIfNoLiveAttempts();
        return ok;
    }

    /** 换票成功 → 标记 onboarding（让 status 在 project 解析期间可见），仅在仍存活时设置。 */
    private boolean markOnboarding(Attempt a) {
        synchronized (lockFor(a.providerId)) {
            if (!liveLocked(a)) {
                return false;
            }
            a.phase.set(Phase.ONBOARDING);
            return true;
        }
    }

    /** 失败/取消终态：仅在仍是当前存活 attempt 时落 error（退出/换代赢后旧 result 不覆盖）。 */
    private void failAttempt(Attempt a, String message, boolean storeError) {
        synchronized (lockFor(a.providerId)) {
            if (!liveLocked(a)) {
                return;
            }
            a.phase.set(Phase.ERROR);
            if (storeError) {
                lastErrors.put(a.providerId, message == null || message.isBlank()
                    ? "Google 订阅登录未完成，请重试" : message);
            }
            cancelTimerLocked(a);
            synchronized (attemptsLock) {
                activeAttempts.remove(a.providerId, a);
            }
        }
        releaseListenerIfNoLiveAttempts();
    }

    /** 到期定时器回调：到点即取消本 attempt 并释放资源（不依赖 status 轮询）。 */
    private void expireAttempt(Attempt a) {
        synchronized (lockFor(a.providerId)) {
            if (activeAttempts.get(a.providerId) != a || isTerminal(a)) {
                return;
            }
            if (clock.millis() < a.deadlineAt) {
                return;
            }
            a.phase.set(Phase.EXPIRED);
            lastErrors.put(a.providerId, EXPIRED_MESSAGE);
            cancelTimerLocked(a);
            synchronized (attemptsLock) {
                activeAttempts.remove(a.providerId, a);
            }
            Thread w = a.worker;
            if (w != null && w != Thread.currentThread()) {
                w.interrupt();
            }
        }
        releaseListenerIfNoLiveAttempts();
    }

    private boolean liveLocked(Attempt a) {
        return !isTerminal(a)
            && activeAttempts.get(a.providerId) == a
            && epochOf(a.providerId).get() == a.epoch
            && clock.millis() < a.deadlineAt;
    }

    private void terminalizeLocked(Attempt a, Phase terminal, String error) {
        if (isTerminal(a)) {
            return;
        }
        a.phase.set(terminal);
        if (error != null) {
            lastErrors.put(a.providerId, error);
        }
        cancelTimerLocked(a);
        synchronized (attemptsLock) {
            activeAttempts.remove(a.providerId, a);
        }
    }

    private void scheduleExpiryLocked(Attempt a) {
        long delay = Math.max(0L, a.deadlineAt - clock.millis());
        a.timer = scheduler.schedule(delay, () -> expireAttempt(a));
    }

    private void cancelTimerLocked(Attempt a) {
        DeadlineScheduler.Cancellable c = a.timer;
        a.timer = null;
        if (c != null) {
            c.cancel();
        }
    }

    /** 剩余时间 = min(总 deadline 剩余, CodeAssist 的 90s cap)；≤0 → null（立即 timeout）。 */
    private Duration remainingBudget(Attempt a) {
        long left = a.deadlineAt - clock.millis();
        if (left <= 0) {
            return null;
        }
        long capped = Math.min(left, GoogleCodeAssistClient.DEFAULT_DEADLINE.toMillis());
        return Duration.ofMillis(capped);
    }

    private boolean hasStoredRefreshToken(String providerId) {
        ProviderService.OAuthSnapshot snap = providerService.getOAuthSnapshot(providerId);
        return snap != null && snap.hasRefreshToken();
    }

    private OAuthTokenResponse exchange(String providerId, String code, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("client_id", GoogleOAuthProviderConfig.CLIENT_ID);
        params.put("client_secret", GoogleOAuthProviderConfig.INSTANCE.clientSecret());
        params.put("redirect_uri", redirectUri);
        return tokenClient.exchangeCodeForTokens(tokenEndpoint, params,
            GoogleOAuthProviderConfig.INSTANCE);
    }

    // ───────────────────────────── state ─────────────────────────────

    /** 一条在途登录：state + 创建时刻 + 本次 redirect_uri（端口）。 */
    record PendingState(String state, long createdAt, String redirectUri) {}

    /** 用户输入解析结果（code 必有；state 可 null = 裸 code）。 */
    record CallbackInput(String code, String state) {}

    /**
     * 原子消费 state 并建立「本轮」attempt（deadline = 该 state 的 createdAt + TTL，单次消费不丢 deadline）。
     * 失败/过期/错 state 在 supersede 之前抛出 ⇒ ⛔ 不误清其它当前 attempt。
     */
    private Attempt claimAttempt(String providerId, String requestedState) {
        synchronized (lockFor(providerId)) {
            PendingState claimed = claimStateLocked(providerId, requestedState);
            long epoch = epochOf(providerId).incrementAndGet();
            Attempt prev = activeAttempts.get(providerId);
            if (prev != null) {
                terminalizeLocked(prev, Phase.EXPIRED, null);
            }
            Attempt a = new Attempt(providerId, claimed.state(), claimed.redirectUri(),
                claimed.createdAt() + GoogleOAuthProviderConfig.LOGIN_STATE_TTL_MILLIS, epoch);
            synchronized (attemptsLock) {
                activeAttempts.put(providerId, a);
            }
            scheduleExpiryLocked(a);
            return a;
        }
    }

    /**
     * 原子消费 state：从列表移除并落库后才返回（失败/过期也移除，防重放）。
     *
     * @param requestedState 回调里带来的 state；null/blank（裸 code）→ 取最近一条未过期
     * @throws IllegalArgumentException 无待完成登录 / state 不匹配 / 已过期
     */
    private PendingState claimStateLocked(String providerId, String requestedState) {
        long now = clock.millis();
        List<PendingState> pending = readPendingStates(providerId);
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("登录状态不存在或已被使用，请重新点击登录获取授权链接");
        }
        PendingState target = null;
        if (requestedState == null || requestedState.isBlank()) {
            target = pending.get(pending.size() - 1);
        } else {
            for (PendingState s : pending) {
                if (s.state().equals(requestedState)) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) {
            throw new IllegalArgumentException(
                "回调 state 与本地登录状态不一致（可能是过期的旧链接），请重新登录");
        }
        // 无论后续成败都从列表移除：一次性串，防重放 / 防并发双花
        pending.remove(target);
        providerService.writeOAuthState(providerId, pending.isEmpty() ? null : encodeStates(pending));
        if (isExpired(target, now)) {
            Attempt cur = activeAttempts.get(providerId);
            if (cur != null && cur.state.equals(target.state())) {
                terminalizeLocked(cur, Phase.EXPIRED, EXPIRED_MESSAGE);
            }
            throw new IllegalArgumentException(EXPIRED_MESSAGE);
        }
        return target;
    }

    private PendingState latestPending(String providerId) {
        long now = clock.millis();
        List<PendingState> pending = readPendingStates(providerId);
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingState s = pending.get(i);
            if (!isExpired(s, now)) {
                return s;
            }
        }
        return null;
    }

    private static boolean isExpired(PendingState s, long now) {
        return now - s.createdAt() > GoogleOAuthProviderConfig.LOGIN_STATE_TTL_MILLIS;
    }

    private List<PendingState> readPendingStates(String providerId) {
        try {
            return decodeStates(providerService.readOAuthState(providerId));
        } catch (IllegalArgumentException e) {
            log.warn("Google 订阅 state 解析失败 provider={}（按无在途登录处理）", providerId);
            return new ArrayList<>();
        }
    }

    /** 序列化（自有格式；不含 verifier）。 */
    static String encodeStates(List<PendingState> states) {
        ArrayNode arr = JSON.createArrayNode();
        for (PendingState s : states) {
            ObjectNode n = JSON.createObjectNode();
            n.put("state", s.state());
            n.put("createdAt", s.createdAt());
            n.put("redirectUri", s.redirectUri());
            arr.add(n);
        }
        return arr.toString();
    }

    /**
     * 反序列化：兼容「单对象 / 数组」；缺字段或非 Google 格式（如 Claude 的 verifier 条目）跳过。
     *
     * @throws IllegalArgumentException 内容不是合法 JSON
     */
    static List<PendingState> decodeStates(String json) {
        List<PendingState> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("登录状态不是合法 JSON，请重新登录", e);
        }
        if (root.isArray()) {
            for (JsonNode n : root) {
                PendingState s = pendingStateOf(n);
                if (s != null) {
                    out.add(s);
                }
            }
        } else {
            PendingState s = pendingStateOf(root);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static PendingState pendingStateOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String state = node.path("state").asText(null);
        String redirectUri = node.path("redirectUri").asText(null);
        long createdAt = node.path("createdAt").asLong(0);
        if (state == null || state.isBlank() || redirectUri == null || redirectUri.isBlank()
                || createdAt <= 0) {
            return null;
        }
        return new PendingState(state, createdAt, redirectUri);
    }

    private String findProviderIdByState(String state) {
        List<ProviderDto> providers;
        try {
            providers = providerService.listAll();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("读取登录状态失败，请重试", e);
        }
        for (ProviderDto p : providers) {
            if (p == null || p.id() == null || !isGoogleType(p.type() == null ? null : p.type().name())) {
                continue;
            }
            List<PendingState> pending;
            try {
                pending = decodeStates(providerService.readOAuthState(p.id()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (PendingState s : pending) {
                if (s.state().equals(state)) {
                    return p.id();
                }
            }
        }
        return null;
    }

    // ───────────────────────────── 工厂 / 工具 ─────────────────────────────

    /** 校验 provider 存在且是 Google 订阅类型（防跨协议 authorize/complete/logout）。 */
    private void requireGoogleProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        ProviderService.OAuthSnapshot snap = providerService.getOAuthSnapshot(providerId);
        if (snap == null) {
            throw new com.nexusai.infra.exception.NotFoundException(
                "Provider " + providerId + " not found");
        }
        if (!isGoogleType(snap.type())) {
            throw new IllegalArgumentException("该 provider 不是 Google 订阅类型");
        }
    }

    private static boolean isGoogleType(String type) {
        return "gemini_code_assist".equals(type);
    }

    private Object lockFor(String providerId) {
        return providerLocks.computeIfAbsent(providerId, k -> new Object());
    }

    private AtomicLong epochOf(String providerId) {
        return logoutEpochs.computeIfAbsent(providerId, k -> new AtomicLong());
    }

    private GoogleOAuthCallbackServer callbackServer() {
        CallbackServerRef ref = this.callbackServer;
        if (ref == null) {
            return null;
        }
        try {
            return ref.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0, 0,
                java.net.InetAddress.getByName(GoogleOAuthProviderConfig.CALLBACK_HOST))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            log.warn("Google 订阅回调端口分配失败: {}", e.getMessage());
            return -1;
        }
    }

    private boolean startCallbackServer(int port) {
        GoogleOAuthCallbackServer server = callbackServer();
        if (server == null) {
            return false;
        }
        try {
            return server.start(port);
        } catch (RuntimeException e) {
            log.warn("[GoogleOAuth] 回调监听启动失败，降级为手动粘贴: {}", e.getMessage());
            return false;
        }
    }

    /** 仅当没有任何存活 attempt 时才关监听（跨 provider 共享同一个 listener，⛔ 不误关他人）。 */
    private void releaseListenerIfNoLiveAttempts() {
        boolean anyLive = false;
        synchronized (attemptsLock) {
            for (Attempt a : activeAttempts.values()) {
                if (!isTerminal(a)) {
                    anyLive = true;
                    break;
                }
            }
        }
        if (!anyLive) {
            stopCallbackServerSoon();
        }
    }

    /** 关闭监听（延迟给当前 HTTP 响应写完；daemon 线程，不阻塞调用方）。 */
    private void stopCallbackServerSoon() {
        GoogleOAuthCallbackServer server = callbackServer();
        if (server == null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(LISTENER_STOP_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                server.stop();
            } catch (RuntimeException ignored) {
                // 停机失败不影响登录结果
            }
        }, "google-oauth-stop");
        t.setDaemon(true);
        t.start();
    }

    static String newState() {
        byte[] bytes = new byte[STATE_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 解析用户输入：整条回调 URL → code/state；裸 code → state=null。
     *
     * @throws IllegalArgumentException 输入为空 / URL 无 code（或带 error 参数）
     */
    /** 解析明细：{@code error != null} 表示这是 provider 返回的错误回调（此时 code 为 null）。 */
    record ParsedCallback(String code, String state, String error) {}

    /**
     * 解析用户输入：整条回调 URL → code/state 或 error/state；裸 code → state=null。
     *
     * <p>裸串若是已知 OAuth error token（如 {@code access_denied}）或明确 {@code error=xxx} 形式，
     * 判定为错误而非授权码（整串精确匹配，⛔ 不因子串误伤合法授权码）。
     */
    static ParsedCallback parseCallbackDetailed(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("请粘贴浏览器地址栏中的完整回调 URL（或授权码）");
        }
        if (!value.contains("://")) {
            String body = value.startsWith("?") ? value.substring(1) : value;
            if (body.regionMatches(true, 0, "error=", 0, 6)) {
                return new ParsedCallback(null, null, body.substring(6));
            }
            if (OAUTH_ERROR_TOKENS.contains(body.toLowerCase(java.util.Locale.ROOT))) {
                return new ParsedCallback(null, null, body.toLowerCase(java.util.Locale.ROOT));
            }
            return new ParsedCallback(value, null, null);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("回调地址格式无法解析，请复制浏览器地址栏中的完整 URL", e);
        }
        Map<String, String> query = queryParams(uri.getRawQuery());
        String error = query.get("error");
        if (error != null && !error.isBlank()) {
            return new ParsedCallback(null, query.get("state"), error);
        }
        String code = query.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("回调地址里没有 code 参数，请确认复制了完整的回调 URL");
        }
        return new ParsedCallback(code.trim(), query.get("state"), null);
    }

    /**
     * 严格解析（供既有调用方/测试）：错误回调抛出固定安全文案，⛔ 不回显 error/error_description。
     *
     * @throws IllegalArgumentException 输入为空 / 错误回调 / URL 无 code
     */
    static CallbackInput parseCallback(String input) {
        ParsedCallback parsed = parseCallbackDetailed(input);
        if (parsed.error() != null) {
            throw new IllegalArgumentException(safeOAuthErrorMessage(parsed.error()));
        }
        return new CallbackInput(parsed.code(), parsed.state());
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
            if (!result.containsKey(decodedKey)) {
                result.put(decodedKey, URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        if (sb.charAt(sb.length() - 1) != '?' && sb.charAt(sb.length() - 1) != '&') {
            sb.append('&');
        }
        String encoded = value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20");
        sb.append(name).append('=').append(encoded);
    }

    /** 错误文案脱敏：去控制字符 / 尖括号，压缩空白，截断 300。 */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\p{Cntrl}]", " ")
            .replace('<', ' ').replace('>', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    // ───────────────────────────── 刷新 ─────────────────────────────

    /**
     * Google access token 刷新器（独立组件，照 Claude 链防循环依赖）。
     *
     * <p>刷新响应通常不带新 refresh_token ⇒ 传 null，由 {@code ProviderService} 沿用旧值；
     * project 是独立列，{@link ProviderService#saveOAuthCredential} 不碰它 ⇒ 刷新保留 project。
     */
    @Component
    public static class GoogleOAuthRefresher implements ProviderService.OAuthRefresher {

        private static final Logger log = LoggerFactory.getLogger(GoogleOAuthRefresher.class);

        private final OAuthTokenClient tokenClient;

        public GoogleOAuthRefresher() {
            this(new OAuthTokenClient(newTokenHttpClient()));
        }

        GoogleOAuthRefresher(OAuthTokenClient tokenClient) {
            this.tokenClient = tokenClient;
        }

        @Override
        public String authMode() {
            return ProviderService.AUTH_MODE_GOOGLE_OAUTH;
        }

        @Override
        public Refreshed refresh(String refreshToken) {
            if (refreshToken == null || refreshToken.isBlank()) {
                return null;
            }
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("grant_type", "refresh_token");
                params.put("refresh_token", refreshToken);
                params.put("client_id", GoogleOAuthProviderConfig.CLIENT_ID);
                params.put("client_secret", GoogleOAuthProviderConfig.INSTANCE.clientSecret());
                OAuthTokenResponse tokens = tokenClient.refreshTokens(
                    GoogleOAuthProviderConfig.TOKEN_ENDPOINT, params,
                    GoogleOAuthProviderConfig.INSTANCE);
                if (tokens.expiresAt() == null) {
                    log.warn("[GoogleOAuth] 刷新响应缺少 expires_in，拒绝回写无过期时间的凭据");
                    return null;
                }
                return new Refreshed(tokens.accessToken(), tokens.refreshToken(),
                    tokens.expiresAt(), null);
            } catch (RuntimeException e) {
                log.warn("[GoogleOAuth] 刷新失败: {}", e.getMessage());
                return null;
            }
        }
    }
}
