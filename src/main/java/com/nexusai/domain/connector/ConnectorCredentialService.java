package com.nexusai.domain.connector;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.repository.connector.entity.ConnectorCredentialRecord;
import com.nexusai.repository.connector.mapper.ConnectorCredentialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 连接器凭据持久化（{@code connector_credentials}，V336）· 加解密与状态机。
 *
 * <p><b>加密口径</b>：所有 token / client secret / 手动凭据整包一律 {@link CryptoUtil#encrypt}
 * 后落库（照 {@code ImChannelService} 的密文范式），⛔ 不写明文、⛔ 不把密文或明文回传 REST。
 *
 * <p><b>为什么 {@code updateByQuery(..., false, ...)}</b>：授权完成要把在途 {@code state}
 * 写成 NULL，而 {@code BaseMapper.update(entity)} 会忽略 null 字段（state 永远清不掉）。
 * 用 {@code updateByQuery(record, ignoreNulls=false, where)} 显式落 NULL。
 */
@Service
public class ConnectorCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCredentialService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 授权在途 state 过期的统一文案（poll / 目录 / 回调三处同口径）。 */
    public static final String AUTH_EXPIRED_MESSAGE = "授权请求已过期，请重新发起";

    private final ConnectorCredentialMapper mapper;
    private final CryptoUtil crypto;

    public ConnectorCredentialService(ConnectorCredentialMapper mapper, CryptoUtil crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public Optional<ConnectorCredential> find(String connectorKey) {
        if (connectorKey == null || connectorKey.isBlank()) {
            return Optional.empty();
        }
        ConnectorCredentialRecord r = mapper.selectOneById(connectorKey);
        return r == null ? Optional.empty() : Optional.of(r.toDomain());
    }

    public List<ConnectorCredential> findAll() {
        return mapper.selectAll().stream().map(ConnectorCredentialRecord::toDomain).toList();
    }

    /** 取既有行；没有则建一条 unconnected 行（不落库，调用方 save）。 */
    public ConnectorCredential ensure(String connectorKey, String authType) {
        return find(connectorKey).orElseGet(() -> {
            ConnectorCredential c = new ConnectorCredential();
            c.setConnectorKey(connectorKey);
            c.setAuthType(authType);
            c.setStatus("unconnected");
            return c;
        });
    }

    /** insert 或全列覆盖更新（含 NULL 清空）。 */
    public void save(ConnectorCredential c) {
        if (c == null || c.getConnectorKey() == null) {
            return;
        }
        String now = OffsetDateTime.now().format(ISO);
        c.setUpdatedAt(now);
        ConnectorCredentialRecord r = ConnectorCredentialRecord.fromDomain(c);
        if (mapper.selectOneById(c.getConnectorKey()) != null) {
            mapper.updateByQuery(r, false,
                    QueryWrapper.create().eq("connector_key", c.getConnectorKey()));
        } else {
            if (c.getCreatedAt() == null) {
                c.setCreatedAt(now);
                r.setCreatedAt(now);
            }
            mapper.insert(r);
        }
    }

    public void delete(String connectorKey) {
        if (connectorKey == null || connectorKey.isBlank()) {
            return;
        }
        mapper.deleteById(connectorKey);
        log.info("[ConnectorCredential] 已删除连接器凭据 key={}", connectorKey);
    }

    /** 加密（null 透传）。 */
    public String encrypt(String plaintext) {
        return crypto.encrypt(plaintext);
    }

    /** 解密（null/空透传为 null）。 */
    public String decrypt(String ciphertext) {
        return crypto.decrypt(ciphertext);
    }

    /** 进入授权中：记录在途 state 与（DCR/手工）客户端信息。 */
    public void markPending(String connectorKey, String authType, String state, long stateExpiresAt,
                            String redirectUri, String clientId, String clientSource,
                            String clientSecret) {
        ConnectorCredential c = ensure(connectorKey, authType);
        c.setStatus("pending");
        c.setState(state);
        c.setStateExpiresAt(stateExpiresAt);
        c.setRedirectUri(redirectUri);
        c.setClientId(clientId);
        c.setClientSource(clientSource);
        c.setClientSecretEncrypted(encrypt(clientSecret));
        c.setLastError(null);
        save(c);
    }

    /** 授权成功：存 token（密文）、清在途 state、状态置 connected。 */
    public void markConnected(String connectorKey, String authType, String tokenEndpoint,
                              String scope, Long expiresAt, String accessToken, String refreshToken) {
        ConnectorCredential c = ensure(connectorKey, authType);
        c.setStatus("connected");
        c.setTokenEndpoint(tokenEndpoint);
        c.setScope(scope);
        c.setExpiresAt(expiresAt);
        c.setAccessTokenEncrypted(encrypt(accessToken));
        c.setRefreshTokenEncrypted(encrypt(refreshToken));
        c.setState(null);
        c.setStateExpiresAt(null);
        c.setLastError(null);
        save(c);
    }

    /** 失败：保留客户端注册信息，状态置 error 并记录原因；在途 state 清空。 */
    public void markError(String connectorKey, String authType, String message) {
        ConnectorCredential c = ensure(connectorKey, authType);
        c.setStatus("error");
        c.setState(null);
        c.setStateExpiresAt(null);
        c.setLastError(truncate(message));
        save(c);
    }

    /**
     * 在途 state 过期对账（[2026-11-23] 状态机统一）。
     *
     * <p>DB 是唯一真源：pending 且 {@code state_expires_at} 已过 → 落 error（统一文案）并
     * 返回刷新后的行。poll / 目录 list / status 都先过这一道，保证「同一时刻同一口径」，
     * 不会出现「poll 还 PENDING、卡片已经异常」。
     *
     * @param c 已读出的凭据行（可为 null）
     * @return 对账后的最新行（未过期原样返回）
     */
    public ConnectorCredential reconcileExpired(ConnectorCredential c) {
        if (c == null || !"pending".equals(c.getStatus())) {
            return c;
        }
        if (c.getStateExpiresAt() == null || c.getStateExpiresAt() > System.currentTimeMillis()) {
            return c;
        }
        log.info("[ConnectorCredential] 授权 state 已过期 → error key={}", c.getConnectorKey());
        markError(c.getConnectorKey(), c.getAuthType(), AUTH_EXPIRED_MESSAGE);
        return find(c.getConnectorKey()).orElse(c);
    }

    /**
     * 标记需要重新授权（token 过期且刷新失败）。
     *
     * <p>保留 client 注册信息与旧 token（密文）；{@link #isConnected} 因 status 非 connected
     * 返回 false，运行时凭据源据此不发 Authorization（server 401 → 前端提示重新授权）。
     */
    public void markReauthRequired(String connectorKey, String authType, String message) {
        ConnectorCredential c = ensure(connectorKey, authType);
        c.setStatus("reauth_required");
        c.setState(null);
        c.setStateExpiresAt(null);
        c.setLastError(truncate(message));
        save(c);
    }

    /** 刷新后回写 token（不动 client 信息）。 */
    public void updateTokens(ConnectorCredential c, String accessToken, String refreshToken,
                             Long expiresAt, String scope) {
        c.setAccessTokenEncrypted(encrypt(accessToken));
        if (refreshToken != null && !refreshToken.isBlank()) {
            c.setRefreshTokenEncrypted(encrypt(refreshToken));
        }
        c.setExpiresAt(expiresAt);
        if (scope != null && !scope.isBlank()) {
            c.setScope(scope);
        }
        c.setStatus("connected");
        c.setLastError(null);
        save(c);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
