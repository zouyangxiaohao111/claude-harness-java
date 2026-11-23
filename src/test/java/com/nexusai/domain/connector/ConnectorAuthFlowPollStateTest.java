package com.nexusai.domain.connector;

import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.model.connector.dto.PollResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * poll 的 DB 单一口径（[2026-11-23] 状态机统一）：
 * connected→AUTHORIZED / error→ERROR / pending+state 匹配→PENDING；
 * 过期对账由 {@link ConnectorCredentialService#reconcileExpired} 先做（poll 不再信内存 outcome）。
 */
class ConnectorAuthFlowPollStateTest {

    private ConnectorCatalog catalog;
    private ConnectorCredentialService credentials;
    private ConnectorCallbackServer callbackServer;
    private ConnectorMcpBinding binding;
    private ConnectorAuthFlow flow;

    @BeforeEach
    void setUp() {
        catalog = mock(ConnectorCatalog.class);
        credentials = mock(ConnectorCredentialService.class);
        callbackServer = mock(ConnectorCallbackServer.class);
        binding = mock(ConnectorMcpBinding.class);
        flow = new ConnectorAuthFlow(catalog, credentials, callbackServer, binding);
    }

    @Test
    @DisplayName("pending + state 匹配 → PENDING（目录同口径也是 pending）")
    void pendingState() {
        ConnectorCredential c = cred("pending", "st-1", System.currentTimeMillis() + 60_000);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.reconcileExpired(any())).thenReturn(c);

        PollResponse r = flow.poll("notion", "st-1");

        assertThat(r.state()).isEqualTo("PENDING");
        verify(credentials).reconcileExpired(c);
    }

    @Test
    @DisplayName("connected → AUTHORIZED（即使旧 state 已清空）")
    void connectedState() {
        ConnectorCredential c = cred("connected", null, null);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.reconcileExpired(any())).thenReturn(c);

        assertThat(flow.poll("notion", "old-state").state()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("error（含过期对账结果）→ ERROR + lastError 文案")
    void errorState() {
        ConnectorCredential c = cred("error", null, null);
        c.setLastError(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.reconcileExpired(any())).thenReturn(c);

        PollResponse r = flow.poll("notion", "st-1");

        assertThat(r.state()).isEqualTo("ERROR");
        assertThat(r.message()).isEqualTo(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
    }

    @Test
    @DisplayName("过期对账把 pending 变 error 后，poll 必须 ERROR（不再返回 PENDING）")
    void expiredReconciledToError() {
        ConnectorCredential pending = cred("pending", "st-1", System.currentTimeMillis() - 1);
        ConnectorCredential expired = cred("error", null, null);
        expired.setLastError(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
        when(credentials.find("notion")).thenReturn(Optional.of(pending));
        when(credentials.reconcileExpired(pending)).thenReturn(expired);

        PollResponse r = flow.poll("notion", "st-1");

        assertThat(r.state()).isEqualTo("ERROR");
        assertThat(r.message()).isEqualTo(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
    }

    @Test
    @DisplayName("state 不匹配且 pending → ERROR（旧 state 不可再被认领）")
    void staleState() {
        ConnectorCredential c = cred("pending", "st-new", System.currentTimeMillis() + 60_000);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.reconcileExpired(any())).thenReturn(c);

        assertThat(flow.poll("notion", "st-old").state()).isEqualTo("ERROR");
    }

    private static ConnectorCredential cred(String status, String state, Long stateExpiresAt) {
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("notion");
        c.setAuthType("oauth2");
        c.setStatus(status);
        c.setState(state);
        c.setStateExpiresAt(stateExpiresAt);
        return c;
    }
}
