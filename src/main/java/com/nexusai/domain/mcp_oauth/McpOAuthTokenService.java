package com.nexusai.domain.mcp_oauth;

import com.nexusai.model.mcp_oauth.McpOAuthClientConfig;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import com.nexusai.repository.mcp_oauth.entity.McpOAuthClientConfigRecord;
import com.nexusai.repository.mcp_oauth.entity.McpOAuthTokenRecord;
import com.nexusai.repository.mcp_oauth.mapper.McpOAuthClientConfigMapper;
import com.nexusai.repository.mcp_oauth.mapper.McpOAuthTokenMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * MCP OAuth token 持久化服务（对齐 CC {@code SecureStorageData.mcpOAuth[serverKey]}，Java 用 DB）。
 *
 * <p>CC 把 OAuth 凭据存 keychain（auth.ts saveClientInformation/saveTokens/saveDiscoveryState
 * 全走 {@code storage.update(mcpOAuth[serverKey])}）；Java 对齐语义落 {@code mcp_oauth_tokens}
 * 表 + {@code mcp_oauth_client_config} 表（预配置 client_id 的 clientSecret 二级回退）。
 *
 * <p>null-safe：读不存在的 serverKey 返回 null；delete/clear 对不存在行静默成功（不抛）。
 */
@Service
public class McpOAuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthTokenService.class);

    @Autowired private McpOAuthTokenMapper mcpOAuthTokenMapper;
    @Autowired private McpOAuthClientConfigMapper mcpOAuthClientConfigMapper;

    /** 读取指定 serverKey 的 token 记录；不存在返回 null。 */
    public McpOAuthToken read(String serverKey) {
        if (serverKey == null) {
            return null;
        }
        McpOAuthTokenRecord r = mcpOAuthTokenMapper.selectOneById(serverKey);
        if (r == null) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpOAuthTokenService] read token serverKey={} 命中", serverKey);
        }
        return r.toDomain();
    }

    /** 保存 token 记录（insert 或 update，幂等）。 */
    public void save(McpOAuthToken token) {
        if (token == null || token.getServerKey() == null) {
            return;
        }
        McpOAuthTokenRecord r = McpOAuthTokenRecord.fromDomain(token);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (mcpOAuthTokenMapper.selectOneById(token.getServerKey()) != null) {
            r.setUpdatedAt(now);
            mcpOAuthTokenMapper.update(r);
        } else {
            // 对齐 McpServerService:createdAt 显式填充（MyBatis-Flex insert 会带 NULL 覆盖 DB DEFAULT）
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            mcpOAuthTokenMapper.insert(r);
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpOAuthTokenService] save token serverKey={}", token.getServerKey());
        }
    }

    /** 删除指定 serverKey 的 token 记录（不存在静默成功）。 */
    public void delete(String serverKey) {
        if (serverKey == null) {
            return;
        }
        mcpOAuthTokenMapper.deleteById(serverKey);
        if (log.isDebugEnabled()) {
            log.debug("[McpOAuthTokenService] delete token serverKey={}", serverKey);
        }
    }

    /** 保存预配置 client_secret（insert/update，幂等）。 */
    public void saveClientSecret(String serverKey, String clientSecret) {
        if (serverKey == null) {
            return;
        }
        McpOAuthClientConfig c = new McpOAuthClientConfig();
        c.setServerKey(serverKey);
        c.setClientSecret(clientSecret);
        McpOAuthClientConfigRecord r = McpOAuthClientConfigRecord.fromDomain(c);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (mcpOAuthClientConfigMapper.selectOneById(serverKey) != null) {
            r.setUpdatedAt(now);
            mcpOAuthClientConfigMapper.update(r);
        } else {
            // 对齐 McpServerService:createdAt 显式填充（MyBatis-Flex insert 会带 NULL 覆盖 DB DEFAULT）
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            mcpOAuthClientConfigMapper.insert(r);
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpOAuthTokenService] save client_secret serverKey={}", serverKey);
        }
    }

    /** 读取预配置 client_secret；无记录返回 null。 */
    public String getClientSecret(String serverKey) {
        if (serverKey == null) {
            return null;
        }
        McpOAuthClientConfigRecord r = mcpOAuthClientConfigMapper.selectOneById(serverKey);
        if (r == null) {
            return null;
        }
        return r.getClientSecret();
    }

    /** 清除预配置 client_secret（不存在静默成功）。 */
    public void clearClientSecret(String serverKey) {
        if (serverKey == null) {
            return;
        }
        mcpOAuthClientConfigMapper.deleteById(serverKey);
        if (log.isDebugEnabled()) {
            log.debug("[McpOAuthTokenService] clear client_secret serverKey={}", serverKey);
        }
    }
}
