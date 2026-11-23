package com.nexusai.domain.connector;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.repository.connector.entity.ConnectorCredentialRecord;
import com.nexusai.repository.connector.mapper.ConnectorCredentialMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在途 state 过期对账（[2026-11-23] 状态机统一）：
 * pending + state_expires_at 已过 → 落 error（统一文案）并返回最新行；未过期不动。
 */
class ConnectorCredentialReconcileTest {

    @Test
    @DisplayName("pending 过期 → markError 落库 + 返回 error 行（poll/目录同一口径）")
    void expiredPendingBecomesError() {
        ConnectorCredentialMapper mapper = mock(ConnectorCredentialMapper.class);
        ConnectorCredentialService service = new ConnectorCredentialService(mapper, mock(CryptoUtil.class));

        ConnectorCredentialRecord pending = record("pending", System.currentTimeMillis() - 1);
        ConnectorCredentialRecord error = record("error", null);
        error.setLastError(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
        error.setState(null);
        error.setStateExpiresAt(null);
        // find(1) / ensure 内 find(2) / save 内存在性检查(3) / reconcile 末尾重读(4)
        when(mapper.selectOneById("notion")).thenReturn(pending, pending, pending, error);

        ConnectorCredential out = service.reconcileExpired(service.find("notion").orElseThrow());

        assertThat(out.getStatus()).isEqualTo("error");
        assertThat(out.getLastError()).isEqualTo(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
        ArgumentCaptor<ConnectorCredentialRecord> captor =
                ArgumentCaptor.forClass(ConnectorCredentialRecord.class);
        verify(mapper).updateByQuery(captor.capture(), anyBoolean(), any(QueryWrapper.class));
        assertThat(captor.getValue().getStatus()).isEqualTo("error");
        assertThat(captor.getValue().getLastError())
                .isEqualTo(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
    }

    @Test
    @DisplayName("pending 未过期 → 原样返回，不写库")
    void unexpiredPendingUntouched() {
        ConnectorCredentialMapper mapper = mock(ConnectorCredentialMapper.class);
        ConnectorCredentialService service = new ConnectorCredentialService(mapper, mock(CryptoUtil.class));
        when(mapper.selectOneById("notion"))
                .thenReturn(record("pending", System.currentTimeMillis() + 60_000));

        ConnectorCredential out = service.reconcileExpired(service.find("notion").orElseThrow());

        assertThat(out.getStatus()).isEqualTo("pending");
        verify(mapper, never()).updateByQuery(any(), anyBoolean(), any(QueryWrapper.class));
    }

    private static ConnectorCredentialRecord record(String status, Long stateExpiresAt) {
        ConnectorCredentialRecord r = new ConnectorCredentialRecord();
        r.setConnectorKey("notion");
        r.setAuthType("oauth2");
        r.setStatus(status);
        r.setState("st-1");
        r.setStateExpiresAt(stateExpiresAt);
        r.setCreatedAt("2026-11-23T00:00:00+08:00");
        r.setUpdatedAt("2026-11-23T00:00:00+08:00");
        return r;
    }
}
