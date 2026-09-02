package com.nexusai.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5 · BearerTokenAuthFilter 鉴权边界单元测试（纯 mock，不起 Spring 上下文）。
 *
 * <p><b>WHY (意图验证)</b>: ScheduleController 原为零鉴权，任何请求（含无凭证、伪造凭证、过期凭证）
 * 都能读写定时任务。过滤器的安全边界是「只有携带已知且未过期 OAuth token 的请求才放行」——
 * 若过期 token 仍放行，则 token 过期语义失效（CC {@code isOAuthTokenExpired} 5min buffer 白做）；
 * 若不过期 token 被误拒，则合法调度链断。测试须同时锁定四种拒绝路径（无/未知/过期/5min 缓冲内）
 * 与两种放行路径（未过期/不过期 token），才体现该边界为何重要。
 */
class BearerTokenAuthFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AccountOAuthTokenService tokenService;
    private BearerTokenAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = mock(AccountOAuthTokenService.class);
        filter = new BearerTokenAuthFilter(tokenService, OBJECT_MAPPER);
    }

    /** 记录链上「下一层」是否被调用，用于断言 filter 是否放行。 */
    private static FilterChain chainWithProbe(AtomicBoolean nextInvoked) {
        return (request, response) -> nextInvoked.set(true);
    }

    private static MockHttpServletRequest request(String authorizationHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/schedules");
        if (authorizationHeader != null) {
            req.addHeader("Authorization", authorizationHeader);
        }
        return req;
    }

    private static AccountOAuthToken token(Long expiresAt) {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider("github");
        t.setIdentity("alice");
        t.setAccessToken("access-token");
        t.setExpiresAt(expiresAt);
        return t;
    }

    @Test
    @DisplayName("无 Authorization 头 → 401 + 'Not authenticated' body，不放行")
    void missingTokenRejected() throws Exception {
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request(null), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("无 token 必须 401").isEqualTo(401);
        assertThat(res.getContentAsString())
            .as("401 body 必须含 CC 未认证语义 detail（RemoteTriggerTool.ts:83 去 claude.ai 措辞）")
            .contains("Not authenticated");
        assertThat(next.get()).as("无 token 不得放行到下一层").isFalse();
    }

    @Test
    @DisplayName("Bearer 空白 token → 401（空白等价无 token）")
    void blankTokenRejected() throws Exception {
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("Bearer    "), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("空白 token 必须 401").isEqualTo(401);
        assertThat(next.get()).isFalse();
    }

    @Test
    @DisplayName("未知 token（账号库未命中）→ 401")
    void unknownTokenRejected() throws Exception {
        when(tokenService.readByAccessToken("unknown")).thenReturn(null);
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("Bearer unknown"), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("未知 token 必须 401").isEqualTo(401);
        assertThat(next.get()).isFalse();
    }

    @Test
    @DisplayName("已过期 token → 401（过期凭证不得复用调度端点）")
    void expiredTokenRejected() throws Exception {
        when(tokenService.readByAccessToken("expired")).thenReturn(token(
            System.currentTimeMillis() - 1000L)); // 1 秒前过期
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("Bearer expired"), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("过期 token 必须 401").isEqualTo(401);
        assertThat(next.get()).isFalse();
    }

    @Test
    @DisplayName("5min buffer 内 token → 401（CC client.ts:349 bufferTime 提前判过期，不等到精确过期时刻）")
    void tokenWithinFiveMinuteBufferRejected() throws Exception {
        when(tokenService.readByAccessToken("near-expiry")).thenReturn(token(
            System.currentTimeMillis() + 3 * 60 * 1000L)); // 3 分钟后才过期，但落在 5min buffer 内
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("Bearer near-expiry"), res, chainWithProbe(next));

        assertThat(res.getStatus())
            .as("5min buffer 内 token 应提前判过期 → 401（验证 isOAuthTokenExpired 缓冲语义）")
            .isEqualTo(401);
        assertThat(next.get()).isFalse();
    }

    @Test
    @DisplayName("未过期 token（10 分钟后过期，越出 buffer）→ 放行")
    void validTokenPasses() throws Exception {
        when(tokenService.readByAccessToken("valid")).thenReturn(token(
            System.currentTimeMillis() + 10 * 60 * 1000L));
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("Bearer valid"), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("未过期 token 不得 401").isNotEqualTo(401);
        assertThat(next.get()).as("未过期 token 必须放行到下一层").isTrue();
    }

    @Test
    @DisplayName("不过期 token（expiresAt=null，GitHub 无 expires_in）→ 放行")
    void nullExpiryTokenPasses() throws Exception {
        when(tokenService.readByAccessToken("never-expires")).thenReturn(token(null));
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("Bearer never-expires"), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("不过期 token 不得 401").isNotEqualTo(401);
        assertThat(next.get()).as("不过期 token（null expiresAt）必须放行").isTrue();
    }

    @Test
    @DisplayName("isOAuthTokenExpired 边界：null→false / 3min→true（buffer）/ 10min→false")
    void isOAuthTokenExpiredBoundary() {
        assertThat(BearerTokenAuthFilter.isOAuthTokenExpired(null))
            .as("expiresAt null = 不过期（CC client.ts:345）").isFalse();
        assertThat(BearerTokenAuthFilter.isOAuthTokenExpired(
            System.currentTimeMillis() + 3 * 60 * 1000L))
            .as("5min buffer 内应判过期").isTrue();
        assertThat(BearerTokenAuthFilter.isOAuthTokenExpired(
            System.currentTimeMillis() + 10 * 60 * 1000L))
            .as("越出 5min buffer 不应判过期").isFalse();
    }

    @Test
    @DisplayName("requireOAuthAuth=false → 条件放行（token 生产链未接通过渡态，不 401、next=true）")
    void passThroughWhenRequireOAuthAuthDisabled() throws Exception {
        // FIX-2 / RV-C-01 NG-2：3 参构造器 requireOAuthAuth=false，doFilter 直接放行，
        // 不反查 token、不 401（token 生产链接通前不锁死 /api/v1/schedules/**）。
        BearerTokenAuthFilter passThrough = new BearerTokenAuthFilter(tokenService, OBJECT_MAPPER, false);
        AtomicBoolean next = new AtomicBoolean(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        passThrough.doFilter(request(null), res, chainWithProbe(next));

        assertThat(res.getStatus()).as("条件放行不得写 401").isNotEqualTo(401);
        assertThat(next.get()).as("条件放行必须直接放行到下一层").isTrue();
    }
}
