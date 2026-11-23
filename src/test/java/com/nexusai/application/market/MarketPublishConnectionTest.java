package com.nexusai.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.connector.ConnectorConnectionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器上架的两处专项（[2026-11-23] 顺手修）：
 * ① {@code validatePluginDir} 的连接器分支（连接器包只有 connector.json，插件白名单会误拒）；
 * ② manifest {@code connection} 块的写出（三处逐键解析的一端；漏写 = 界面永远看不到授权元数据）。
 */
class MarketPublishConnectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    @DisplayName("连接器目录只有 connector.json → 可上架；空目录 → 拒绝并点名 connector.json")
    void connectorDirValidation() throws Exception {
        assertThat(validate(dir, "connector")).contains("connector.json");

        Files.writeString(dir.resolve("connector.json"), "{}");
        assertThat(validate(dir, "connector")).isNull();
    }

    @Test
    @DisplayName("技能分类不认 connector.json（两个白名单互不串）")
    void skillDirDoesNotAcceptConnectorJson() throws Exception {
        Files.writeString(dir.resolve("connector.json"), "{}");
        assertThat(validate(dir, "skill")).isNotNull();
    }

    @Test
    @DisplayName("connection 块逐键写出；null 时不覆盖（保留既有块）")
    void connectionBlockWritten() throws Exception {
        ObjectNode entry = JSON.createObjectNode();
        ConnectorConnectionInfo info = new ConnectorConnectionInfo(
                "oauth2", List.of("tasks:read", "tasks:write"),
                "https://as/.well-known/oauth-authorization-server",
                "https://as/authorize", "https://as/token",
                "https://mcp.example.com", true, "/callback");

        writeConnection(entry, info);

        JsonNode c = entry.get("connection");
        assertThat(c).isNotNull();
        assertThat(c.path("authType").asText()).isEqualTo("oauth2");
        assertThat(c.path("scopes")).hasSize(2);
        assertThat(c.path("discoveryUrl").asText()).contains("oauth-authorization-server");
        assertThat(c.path("authorizationEndpoint").asText()).isEqualTo("https://as/authorize");
        assertThat(c.path("tokenEndpoint").asText()).isEqualTo("https://as/token");
        assertThat(c.path("resource").asText()).isEqualTo("https://mcp.example.com");
        assertThat(c.path("requiresManualClient").asBoolean()).isTrue();
        assertThat(c.path("callbackPath").asText()).isEqualTo("/callback");

        ObjectNode keep = JSON.createObjectNode();
        keep.putObject("connection").put("authType", "oauth2");
        writeConnection(keep, null);
        assertThat(keep.get("connection").path("authType").asText()).isEqualTo("oauth2");
    }

    // ── 反射调私有静态方法（纯函数，不构建服务依赖） ──────────────────────

    private static String validate(Path dir, String category) throws Exception {
        Method m = MarketPublishService.class.getDeclaredMethod("validatePluginDir", Path.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, dir, category);
    }

    private static void writeConnection(ObjectNode entry, ConnectorConnectionInfo info) throws Exception {
        Method m = MarketPublishService.class.getDeclaredMethod(
                "writeConnection", ObjectNode.class, ConnectorConnectionInfo.class);
        m.setAccessible(true);
        m.invoke(null, entry, info);
    }
}
