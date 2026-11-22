package com.nexusai.domain.mcp;

import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [保密 · 2026-11-22 第二批 B2] MCP env / headers 的敏感键脱敏 + 写侧哨兵。
 *
 * <p>只按<b>键名</b>掩码（*KEY/*TOKEN/*SECRET/*PASSWORD/Authorization）：MCP env 里还有
 * {@code LOG_LEVEL}、{@code PATH} 这类普通配置，整份掩码会让配置无法编辑。
 *
 * <p>真实连接仍用明文：{@code toTransportConfig} 读 {@link McpServer#getEnv()}（DB），
 * 不经 DTO —— 本测试断言记录里的 env 保持原值。
 */
class McpEnvHeadersRedactionTest {

    private static final String ID = "mcp-1";

    private static McpServerService service(McpServerRecord rec) {
        McpServerService service = new McpServerService();
        McpServerMapper mapper = mock(McpServerMapper.class);
        McpToolPool pool = mock(McpToolPool.class);
        McpConfigAddValidator validator = mock(McpConfigAddValidator.class);
        ReflectionTestUtils.setField(service, "mcpServerMapper", mapper);
        ReflectionTestUtils.setField(service, "mcpToolPool", pool);
        ReflectionTestUtils.setField(service, "addValidator", validator);
        when(mapper.selectOneById(ID)).thenReturn(rec);
        when(pool.isServerConnected(anyString())).thenReturn(false);
        when(validator.ensureConfigScope(any())).thenReturn("project");
        when(validator.ensureTransport(any())).thenReturn("ws");
        return service;
    }

    private static McpServerRecord record(String type, Map<String, String> env) {
        McpServerRecord r = new McpServerRecord();
        r.setId(ID);
        r.setName("srv");
        r.setCommand("npx");
        r.setType(type);
        r.setEnv(McpServerService.serializeEnv(env));
        r.setEnabled(true);
        r.setApprovalStatus("approved");
        r.setScope("project");
        return r;
    }

    private static Map<String, String> stdioEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LOG_LEVEL", "debug");
        env.put("GITHUB_TOKEN", "ghp-1234567890");
        env.put("DB_PASSWORD", "pw-abcdefgh");
        return env;
    }

    @Test
    @DisplayName("GET stdio：敏感键掩码，非敏感键原样；DB env 保持明文")
    void get_stdioEnv_masksSensitiveOnly() {
        McpServerRecord rec = record("stdio", stdioEnv());
        McpServerService service = service(rec);

        McpServerDto dto = service.getById(ID);

        assertThat(dto.env()).containsEntry("LOG_LEVEL", "debug");
        assertThat(dto.env()).containsEntry("GITHUB_TOKEN", "****7890");
        assertThat(dto.env()).containsEntry("DB_PASSWORD", "****efgh");
        assertThat(rec.getEnv()).as("运行时连接读 DB（toTransportConfig）⇒ 明文必须还在")
            .contains("ghp-1234567890");
    }

    @Test
    @DisplayName("GET 远程：headers 与 env 两侧都掩码")
    void get_remoteHeaders_masked() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("Authorization", "Bearer tok-1234567890");
        env.put("X-Trace-Id", "trace-1");
        McpServerRecord rec = record("ws", env);
        rec.setCommand("https://example.com/mcp");
        McpServerService service = service(rec);

        McpServerDto dto = service.getById(ID);

        assertThat(dto.headers()).containsEntry("Authorization", "****7890");
        assertThat(dto.headers()).containsEntry("X-Trace-Id", "trace-1");
        assertThat(dto.env()).containsEntry("Authorization", "****7890");
    }

    @Test
    @DisplayName("PATCH：env 掩码回传保留真值，非敏感键照改")
    void update_maskedEnvKeepsStored() {
        McpServerRecord rec = record("ws", stdioEnv());
        McpServerService service = service(rec);

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("LOG_LEVEL", "info");
        incoming.put("GITHUB_TOKEN", "****7890");
        McpCreateRequest req = new McpCreateRequest(
            "srv", null, null, incoming, null, "ws", null, null, null, null, null, null);

        service.update(ID, req);

        ArgumentCaptor<McpServerRecord> captor = ArgumentCaptor.forClass(McpServerRecord.class);
        verify((McpServerMapper) ReflectionTestUtils.getField(service, "mcpServerMapper"))
            .update(captor.capture());
        Map<String, String> saved = McpServerService.deserializeEnv(captor.getValue().getEnv());
        assertThat(saved).containsEntry("GITHUB_TOKEN", "ghp-1234567890");
        assertThat(saved).containsEntry("LOG_LEVEL", "info");
        // PATCH env 语义是「整份替换」（前端发完整列表）⇒ 未出现在入参里的键被删除，
        // 不是哨兵的职责范围；这里只锁「掩码不覆盖真值」。
        assertThat(saved).doesNotContainKey("DB_PASSWORD");
    }

    @Test
    @DisplayName("PATCH：新明文敏感值 → 覆盖")
    void update_newPlainSecretOverwrites() {
        McpServerRecord rec = record("ws", stdioEnv());
        McpServerService service = service(rec);

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("GITHUB_TOKEN", "ghp-rotated-0000");
        McpCreateRequest req = new McpCreateRequest(
            "srv", null, null, incoming, null, "ws", null, null, null, null, null, null);

        service.update(ID, req);

        ArgumentCaptor<McpServerRecord> captor = ArgumentCaptor.forClass(McpServerRecord.class);
        verify((McpServerMapper) ReflectionTestUtils.getField(service, "mcpServerMapper"))
            .update(captor.capture());
        Map<String, String> saved = McpServerService.deserializeEnv(captor.getValue().getEnv());
        assertThat(saved).containsEntry("GITHUB_TOKEN", "ghp-rotated-0000");
    }
}
