package com.nexusai.application.agent.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2e-1 · 自动回调登录的 state 契约（{@code <providerId>:<32 hex>}）。
 *
 * <p><b>⛔ 不启 Spring</b>：只覆盖静态纯函数。
 *
 * <p><b>为什么这些断言重要</b>：1455 回调只带 {@code state}/{@code code}，没有 provider 上下文。
 * 若前缀解析或编解码任何一环松散，回调就会被记到<b>别的 provider</b> 名下（把 A 的账号写进 B），
 * 或旧链接能被重放 —— 都是凭据错配，而不仅仅是「登录失败」。
 */
class CodexOAuthStateTest {

    @Test
    @DisplayName("state 前缀解析：'p1:abc' → providerId=p1")
    void providerIdFromState_parsesPrefix() {
        assertThat(CodexOAuthService.providerIdFromState("p1:abc"))
            .as("回调靠前缀反查 provider；解错就会把凭据写到别的 provider 上")
            .isEqualTo("p1");
    }

    @Test
    @DisplayName("providerId 自身含 ':' 时按第一个冒号切（真实 id 是 UUID，不含冒号）")
    void providerIdFromState_splitsOnFirstColon() {
        assertThat(CodexOAuthService.providerIdFromState("p1:ab:cd")).isEqualTo("p1");
    }

    @Test
    @DisplayName("无 ':' → 抛 IllegalArgumentException（旧格式 state 不能静默当成 providerId）")
    void providerIdFromState_withoutColon_throws() {
        assertThatThrownBy(() -> CodexOAuthService.providerIdFromState("abcdef"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空 / null state → 抛 IllegalArgumentException")
    void providerIdFromState_blank_throws() {
        assertThatThrownBy(() -> CodexOAuthService.providerIdFromState(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodexOAuthService.providerIdFromState("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("前缀为空（':abc'）或随机串为空（'p1:'）→ 抛 IllegalArgumentException")
    void providerIdFromState_emptyParts_throw() {
        // 前缀为空 ⇒ 拿不到 provider，不能猜一个默认值去换 token
        assertThatThrownBy(() -> CodexOAuthService.providerIdFromState(":abc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodexOAuthService.providerIdFromState("p1:"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("newState(providerId) = providerId + ':' + 32 位 hex，且两次调用不同")
    void newStateWithProvider_prefixAndRandomSuffix() {
        String state = CodexOAuthService.newState("p1");
        assertThat(state).startsWith("p1:");
        assertThat(state.substring("p1:".length()))
            .as("随机段必须是 32 位 hex，才能在库里与回调串做全串相等校验")
            .hasSize(32)
            .matches("[0-9a-f]{32}");
        assertThat(CodexOAuthService.newState("p1"))
            .as("每次登录必须换新 state，否则旧链接可重放")
            .isNotEqualTo(state);
        // 反解回来仍是原 provider（编/解口径自洽）
        assertThat(CodexOAuthService.providerIdFromState(state)).isEqualTo("p1");
    }

    @Test
    @DisplayName("newState(null/空 providerId) → 抛 IllegalArgumentException")
    void newStateWithProvider_blank_throws() {
        assertThatThrownBy(() -> CodexOAuthService.newState(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodexOAuthService.newState(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("oauth_state JSON 编解码往返：带前缀的 state + verifier 原样回来")
    void stateJson_roundTripWithPrefixedState() {
        CodexOAuthService.AuthState original =
            new CodexOAuthService.AuthState(CodexOAuthService.newState("provider-1"), "verifier-xyz");
        String json = CodexOAuthService.encodeState(original);
        assertThat(CodexOAuthService.decodeState(json))
            .as("store 的 state 必须与回调里带的 state 全串相等，往返走样即回调必校验失败")
            .isEqualTo(original);
        assertThat(CodexOAuthService.providerIdFromState(
            CodexOAuthService.decodeState(json).state())).isEqualTo("provider-1");
    }
}
