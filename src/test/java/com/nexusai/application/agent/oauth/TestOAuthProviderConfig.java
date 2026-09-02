package com.nexusai.application.agent.oauth;

import java.util.List;

/**
 * 测试夹具 · 保留接口 {@link OAuthProviderConfig} 的包私有 record 实现（纯测试脚手架，非生产能力）。
 *
 * <p><b>为什么存在</b>：S09 按 O-1 裁决删除账号 OAuth 登录镜像（provider 配置类随登录链删除，
 * D-OA-05/06/07/08），但共享 token 基础设施
 * （{@link OAuthTokenClient} / {@link OAuthTokenResponseParser} / {@link AccountOAuthTokenRefresher}）
 * 的保留测试仍需要「会过期有刷新 / 永不过期无刷新」两种 provider 语义矩阵的配置实例——
 * 本夹具以接口的 8 个抽象组件 + 两个生命周期声明字段承载该矩阵，替代被删配置类。
 *
 * <p>record 组件名与接口方法名一致（provider/authorizationEndpoint/.../scopes/redirectUri +
 * {@code accessTokenExpires()}/{@code supportsRefreshToken()} accessor 自动实现接口默认方法），
 * 无需任何覆写样板。
 *
 * <p><b>非生产能力（对齐删除合规硬约束）</b>：仅存在于 src/test，不参与 Spring 装配，
 * 不新增任何 CC 之外的生产语义。
 */
record TestOAuthProviderConfig(
        String provider,
        String authorizationEndpoint,
        String tokenEndpoint,
        String userInfoEndpoint,
        String clientId,
        String clientSecret,
        List<String> scopes,
        String redirectUri,
        boolean accessTokenExpires,
        boolean supportsRefreshToken
) implements OAuthProviderConfig {

    /**
     * GitHub 形态：永不过期无刷新（accessTokenExpires=false / supportsRefreshToken=false）。
     * 端点/scope 沿用被删配置类的公开固定桩值，client 空串（无硬编码字面量）。
     */
    static TestOAuthProviderConfig githubLike() {
        return new TestOAuthProviderConfig(
            "github",
            "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "", "", List.of("read:user"), "",
            false, false);
    }

    /**
     * Google 形态：会过期有刷新（accessTokenExpires=true / supportsRefreshToken=true，
     * 即接口默认语义）。clientId/clientSecret 为测试桩值（非生产硬编码）。
     */
    static TestOAuthProviderConfig googleLike() {
        return new TestOAuthProviderConfig(
            "google",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo",
            "google-client-id", "google-client-secret", List.of("openid", "email", "profile"), "",
            true, true);
    }
}
