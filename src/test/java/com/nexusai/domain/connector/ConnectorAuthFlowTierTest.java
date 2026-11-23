package com.nexusai.domain.connector;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.connector.ConnectorDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 30 条目录分档门（[2026-11-23]）：start 只允许 supported。
 * unavailable 给置灰原因；credentialOnly 明确「只能保存凭据」——都不进入授权流程。
 */
class ConnectorAuthFlowTierTest {

    private ConnectorCatalog catalog;
    private ConnectorCredentialService credentials;
    private ConnectorCallbackServer callbackServer;
    private ConnectorAuthFlow flow;

    @BeforeEach
    void setUp() {
        catalog = mock(ConnectorCatalog.class);
        credentials = mock(ConnectorCredentialService.class);
        callbackServer = mock(ConnectorCallbackServer.class);
        flow = new ConnectorAuthFlow(catalog, credentials, callbackServer, mock(ConnectorMcpBinding.class));
    }

    @Test
    @DisplayName("unavailable → 400 语义（ValidationException + 中文原因），不启动回调监听")
    void unavailableRejected() {
        when(catalog.require("tencent-news")).thenReturn(def(
                "tencent-news", "apiKey", "gateway", "unavailable", "需要 Octop 网关适配器"));

        assertThatThrownBy(() -> flow.start("tencent-news"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("暂不可用")
                .hasMessageContaining("网关");
        verifyNoInteractions(callbackServer);
    }

    @Test
    @DisplayName("credentialOnly → 拒绝一键授权（只能保存凭据），不启动回调监听")
    void credentialOnlyRejected() {
        when(catalog.require("tencent-docs")).thenReturn(def(
                "tencent-docs", "personalToken", "remote", "credentialOnly", null));

        assertThatThrownBy(() -> flow.start("tencent-docs"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("只支持保存凭据");
        verifyNoInteractions(callbackServer);
    }

    private static ConnectorDefinition def(String key, String authType, String mcpMode,
                                           String tier, String reason) {
        return new ConnectorDefinition(
                key, key, "desc", authType, "office", List.of(),
                null, null, "http", null, null, null, null,
                false, false, false, null, null, key, "#000000",
                "available", mcpMode, tier, reason);
    }
}
