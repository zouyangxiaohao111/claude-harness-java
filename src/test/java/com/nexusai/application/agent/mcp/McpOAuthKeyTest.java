package com.nexusai.application.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 · McpOAuth.getServerKey 对齐 CC auth.ts:325-338。
 *
 * <p><b>WHY (意图验证)</b>: CC 用 name + config hash 生成凭据唯一键，防止同名/同配置
 * 复用凭据。Java 用该键作 {@code mcp_oauth_tokens} 主键：若键不稳定（同入参不同 hash）则
 * 每次写入产生新行，认证凭据永远读不回；若 headers 不参与哈希则改配置后仍复用旧凭据
 * （认证安全边界破坏）。
 */
class McpOAuthKeyTest {

    @Test
    @DisplayName("确定性：同入参同 hash，且精确等于 CC sha256(稳定键序 JSON) 前 16 hex")
    void deterministicAndMatchesCcHash() {
        String key1 = McpOAuth.getServerKey("srv", "sse", "http://example.com", null);
        String key2 = McpOAuth.getServerKey("srv", "sse", "http://example.com", null);
        assertThat(key1)
            .as("同入参两次调用必须完全一致（DB 主键稳定，否则认证凭据读不回）")
            .isEqualTo(key2);
        // CC: configJson = jsonStringify({type:'sse',url:'http://example.com',headers:{}})
        //   hash = sha256(configJson).hex.substring(0,16) = a7895e6304e7bbcc（实测）
        assertThat(key1)
            .as("sha256 前 16 hex 精确值（锁定与 CC JSON 序列化一致性）")
            .isEqualTo("srv|a7895e6304e7bbcc");
    }

    @Test
    @DisplayName("headers 敏感：headers 不同 → 不同 key（防同 URL 换头复用凭据）")
    void headersSensitive() {
        Map<String, String> withAuth = new LinkedHashMap<>();
        withAuth.put("Authorization", "Bearer abc");
        String withHeaders = McpOAuth.getServerKey("srv", "http", "http://example.com", withAuth);
        String withoutHeaders = McpOAuth.getServerKey("srv", "http", "http://example.com", null);
        assertThat(withHeaders)
            .as("headers 参与哈希：换 Authorization 头必须得到不同凭据键")
            .isNotEqualTo(withoutHeaders);
    }

    @Test
    @DisplayName("serverName| 前缀：key = serverName + '|' + 16hex")
    void serverNamePrefix() {
        String key = McpOAuth.getServerKey("my-server", "sse", "http://example.com", null);
        assertThat(key)
            .as("前缀为 serverName|（对齐 CC 返回 `${serverName}|${hash}`）")
            .startsWith("my-server|");
        String hashPart = key.substring("my-server|".length());
        assertThat(hashPart)
            .as("hash 部分为 16 位小写 hex")
            .matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("不同 URL 不同 key；同名不同配置不共用")
    void differentUrlDifferentKey() {
        String urlA = McpOAuth.getServerKey("srv", "sse", "http://a.example.com", null);
        String urlB = McpOAuth.getServerKey("srv", "sse", "http://b.example.com", null);
        assertThat(urlA).isNotEqualTo(urlB);
    }
}
