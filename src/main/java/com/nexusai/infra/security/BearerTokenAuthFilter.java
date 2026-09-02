package com.nexusai.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.dto.Problem;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bearer token 鉴权过滤器 · 补齐五端点族零鉴权缺口（S5 + IMP-MV2-17 鉴权收敛）：
 * {@code /api/v1/schedules/**}、{@code /api/v1/memory/**}、{@code /api/v1/sessions/**}、
 * {@code /api/agent/away-summary}、{@code /api/v1/session-memory/config/**}、
 * {@code /api/v1/session-memory/export}（会话记忆导出 · OPD-CM5-F-24）。
 * 注册覆盖面见 {@link BearerTokenAuthFilterConfig}。
 *
 * <p><b>语义对齐 CC 未认证路径</b>（Open-ClaudeCode/src/tools/RemoteTriggerTool/RemoteTriggerTool.ts:79-85）：
 * CC 客户端在发起远程调用前先 {@code checkAndRefreshOAuthTokenIfNeeded()} 再取
 * {@code getClaudeAIOAuthTokens()?.accessToken}，无 token 抛
 * {@code 'Not authenticated with a claude.ai account. Run /login and try again.'}。
 * Java 侧本项目是 <b>服务端</b>（等价 CCR），职责是把「无 token / 未知 token / 已过期 token」
 * 一律拒为 401，由客户端（S6 WIRE-SF-06 补头 + handleOAuth401Error 刷新）负责刷新重试，
 * 故本过滤器 <b>不</b> 在服务端做主动刷新（CC 的 {@code checkAndRefreshOAuthTokenIfNeeded}
 * 主动刷新是客户端职责，见 {@code utils/auth.ts:1427-1462}）。
 *
 * <p><b>判定逻辑</b>（{@link #doFilter}）：
 * <ol>
 *   <li>提取 {@code Authorization: Bearer <token>}；缺失/空 → 401；</li>
 *   <li>{@link AccountOAuthTokenService#readByAccessToken} 反查；未知 token → 401；</li>
 *   <li>{@link #isOAuthTokenExpired} 判过期（含 5min buffer）；已过期 → 401；</li>
 *   <li>否则放行（未过期 token）。</li>
 * </ol>
 *
 * <p><b>项目无 Spring Security 依赖</b>（pom 仅 starter-web/webflux/websocket），
 * 故用纯 {@link jakarta.servlet.Filter} + {@code FilterRegistrationBean} 注册，
 * 不走 {@code SecurityFilterChain}。
 */
public class BearerTokenAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthFilter.class);

    /**
     * 过期缓冲 5 分钟 · CC original: bufferTime = 5 * 60 * 1000
     * (Open-ClaudeCode/src/services/oauth/client.ts:349)。
     */
    private static final long EXPIRY_BUFFER_MS = 5 * 60 * 1000L;

    /** Bearer 前缀（含尾随空格），RFC 6750。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 401 detail · CC original: 'Not authenticated with a claude.ai account. Run /login and try again.'
     * (Open-ClaudeCode/src/tools/RemoteTriggerTool/RemoteTriggerTool.ts:83)。
     * GitHub 泛化去 claude.ai 措辞（通用 provider，非 GitHub 专用，见 concerns）。
     */
    private static final String UNAUTHENTICATED_DETAIL = "Not authenticated. Run /login and try again.";

    private final AccountOAuthTokenService accountTokenService;
    private final ObjectMapper objectMapper;
    /**
     * 是否强制要求 OAuth 鉴权（FIX-2 / RV-C-01 NG-2）。
     *
     * <p><b>生产注入源</b> = {@code nexusai.security.require-oauth-auth} 配置项
     * （{@code BearerTokenAuthFilterConfig} @Value 注入，默认 true）。
     * 2/3 参构造器仍保留供测试与手动装配（2 参默认 true，3 参显式传入）。
     *
     * <p>{@code true}（默认）= 保持 deny-all 边界（无/未知/过期 token 一律 401）。
     * {@code false} = 条件放行（直接 {@code chain.doFilter}），供 token 生产链未接通过渡期使用，
     * 避免把 {@code /api/v1/schedules/**} 锁死在 token 生产链接通前。
     * <b>⚠ 过渡态 fail-open 安全姿态</b>：{@code false} 等于回到 S5 前零鉴权状态，
     * 仅限「未上线、token 未接通」阶段，token 生产链接通后必须翻回 {@code true}。
     */
    private final boolean requireOAuthAuth;

    /** 构造器注入（由 {@code BearerTokenAuthFilterConfig} @Bean 构造）；默认 requireOAuthAuth=true。 */
    public BearerTokenAuthFilter(AccountOAuthTokenService accountTokenService, ObjectMapper objectMapper) {
        this(accountTokenService, objectMapper, true);
    }

    /**
     * @param requireOAuthAuth true=deny-all 鉴权（默认）；false=条件放行（token 生产链未接通过渡态）
     */
    public BearerTokenAuthFilter(AccountOAuthTokenService accountTokenService, ObjectMapper objectMapper,
            boolean requireOAuthAuth) {
        this.accountTokenService = accountTokenService;
        this.objectMapper = objectMapper;
        this.requireOAuthAuth = requireOAuthAuth;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // FIX-2：requireOAuthAuth=false 条件放行（token 生产链未接通过渡态，不锁死 schedules 端点）
        if (!requireOAuthAuth) {
            if (log.isDebugEnabled()) {
                log.debug("[BearerTokenAuthFilter] requireOAuthAuth=false 条件放行 path={}"
                        + "（token 生产链未接通过渡态，接通后须翻回 true）",
                    ((HttpServletRequest) request).getRequestURI());
            }
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        // ① 无 token → 401（对齐 CC RemoteTriggerTool.ts:82-83 无 accessToken 抛 Not authenticated）
        String bearer = extractBearer(httpReq);
        if (bearer == null || bearer.isBlank()) {
            log.warn("[BearerTokenAuthFilter] 请求缺少 Authorization: Bearer token，返回 401 path={}",
                httpReq.getRequestURI());
            respond401(httpRes, httpReq);
            return;
        }

        // ③ 未知 token → 401（CC getClaudeAIOAuthTokens 无此凭据返回 null → 无 accessToken → 401）
        AccountOAuthToken token = accountTokenService.readByAccessToken(bearer);
        if (token == null) {
            log.warn("[BearerTokenAuthFilter] access token 未命中账号库（未知 token），返回 401 path={}",
                httpReq.getRequestURI());
            respond401(httpRes, httpReq);
            return;
        }

        // ④ 已过期 → 401（服务端拒过期 token；CC isOAuthTokenExpired 5min buffer，client.ts:344-353）
        if (isOAuthTokenExpired(token.getExpiresAt())) {
            log.warn("[BearerTokenAuthFilter] access token 已过期（含 5min buffer），返回 401 path={} provider={}",
                httpReq.getRequestURI(), token.getProvider());
            respond401(httpRes, httpReq);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("[BearerTokenAuthFilter] token 校验通过，放行 path={} provider={}",
                httpReq.getRequestURI(), token.getProvider());
        }
        chain.doFilter(request, response);
    }

    /** 提取 {@code Authorization: Bearer <token>}；非 Bearer 或缺失返回 null。 */
    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    /**
     * 判过期 · CC original: isOAuthTokenExpired (Open-ClaudeCode/src/services/oauth/client.ts:344-353)。
     *
     * <p>行为：{@code expiresAt === null → false}（不过期 token，如 GitHub）；
     * {@code expired = (now + 5*60*1000) >= expiresAt}。Java 侧严格镜像，不用 MCP 域 300s 阈值。
     */
    static boolean isOAuthTokenExpired(Long expiresAt) {
        if (expiresAt == null) {
            return false;
        }
        return System.currentTimeMillis() + EXPIRY_BUFFER_MS >= expiresAt;
    }

    /** 写 401 响应 · body 用 RFC 7807 Problem 格式，detail 对齐 CC 未认证语义。 */
    private void respond401(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Problem problem = new Problem("about:blank", "Unauthorized", HttpServletResponse.SC_UNAUTHORIZED,
            UNAUTHENTICATED_DETAIL, request.getRequestURI(), null, null, null);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
