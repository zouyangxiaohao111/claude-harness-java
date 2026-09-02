package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 · McpOAuth PKCE/state/授权URL 纯函数对齐（Q-01 编排步骤①②④ 的可验证单元）。
 *
 * <p><b>WHY (规则九)</b>: CC 依赖 MCP SDK 的 {@code randomPKCECodeVerifier}/S256 challenge
 * （auth.ts startAuthorization 链路）与 {@code redirectToAuthorization} 构建授权 URL。
 * Java 侧若 verifier 含非法字符或超长、challenge 非 S256、state 不含随机熵，则授权端点
 * 拒绝请求或 CSRF 校验失效——这是认证安全边界，必须锁定字符集/长度/派生算法不变量。
 */
class McpOAuthPkceTest {

    @Test
    @DisplayName("code_verifier：base64url 86 字符，全落在 RFC 7636 [A-Za-z0-9-._~] 无保留集")
    void codeVerifierIsRfc7636Compliant() {
        String verifier = McpOAuth.generateCodeVerifier();
        assertThat(verifier)
            .as("两次调用应产生不同随机值")
            .isNotEqualTo(McpOAuth.generateCodeVerifier());
        assertThat(verifier)
            .as("RFC 7636 §4.1: 43-128 字符")
            .hasSize(86)
            .as("只含 unreserved 字符（base64url 64 字节无填充）")
            .matches("[A-Za-z0-9\\-._~]{86}");
    }

    @Test
    @DisplayName("s256Challenge：S256 派生，base64url 无填充 43 字符，确定性可复算")
    void s256ChallengeIsDeterministicAndWellFormed() {
        String verifier = McpOAuth.generateCodeVerifier();
        String challenge1 = McpOAuth.s256Challenge(verifier);
        String challenge2 = McpOAuth.s256Challenge(verifier);
        assertThat(challenge1)
            .as("同 verifier 两次派生必须一致（授权端点比对 code_verifier 的前置）")
            .isEqualTo(challenge2);
        assertThat(challenge1)
            .as("SHA-256 32 字节 → base64url 无填充 43 字符")
            .hasSize(43)
            .matches("[A-Za-z0-9\\-._~]{43}");
        assertThat(McpOAuth.s256Challenge("other"))
            .as("不同 verifier → 不同 challenge")
            .isNotEqualTo(challenge1);
    }

    @Test
    @DisplayName("state：32 字节 base64url 43 字符（CC randomBytes(32).toString('base64url')），随机不可预测")
    void stateIsRandomBase64Url() {
        String state1 = McpOAuth.generateState();
        String state2 = McpOAuth.generateState();
        assertThat(state1)
            .as("CC auth.ts:1476 32 字节 base64url → 43 字符")
            .hasSize(43)
            .matches("[A-Za-z0-9\\-._~]{43}");
        assertThat(state1)
            .as("CSRF 防跨站要求 state 不可预测——两次调用必须不同")
            .isNotEqualTo(state2);
    }

    @Test
    @DisplayName("buildAuthorizationUrl：含 RFC 6749/7636 全部必需参数并正确 percent-encoding")
    void buildAuthorizationUrlCarriesAllParams() {
        String url = McpOAuth.buildAuthorizationUrl(
            "https://as.example.com/authorize", "client-1",
            "http://localhost:39152/callback",
            "challenge-s256", "state-abc", "read write");

        assertThat(url).startsWith("https://as.example.com/authorize?");
        assertThat(url)
            .contains("response_type=code")
            .contains("client_id=client-1")
            .contains("redirect_uri=http%3A%2F%2Flocalhost%3A39152%2Fcallback")
            .contains("code_challenge=challenge-s256")
            .contains("code_challenge_method=S256")
            .contains("state=state-abc")
            .contains("scope=read%20write");
    }

    @Test
    @DisplayName("buildAuthorizationUrl：scope 为空时不携带 scope 参数")
    void buildAuthorizationUrlOmitsBlankScope() {
        String url = McpOAuth.buildAuthorizationUrl(
            "https://as.example.com/authorize", "client-1",
            "http://localhost:39152/callback", "challenge", "state", null);
        assertThat(url).doesNotContain("scope=");
    }
}
