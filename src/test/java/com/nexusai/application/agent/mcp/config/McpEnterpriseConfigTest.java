package com.nexusai.application.agent.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CC {@code doesEnterpriseMcpConfigExist}（config.ts:1471-1478）四态验证。
 *
 * <p><b>WHY (意图验证)</b>: enterprise 独占判定决定 add 校验链 c 是否短路
 * （config.ts:651-655）。语义 = {@code config !== null}（managed-mcp.json 解析通过即存在，
 * 含空 {@code mcpServers}）；文件缺失 / 读失败 / 非法 JSON / 空文件 → {@code config:null}
 * → 不独占。判反会让：漏判 → 本应短路的 add 放行（enterprise 被绕过）；误判 → 本应放行的
 * add 被拦（CC 接受的输入被拒绝）。四种状态逐一验证。
 *
 * <p>路径经 package-private 构造注入（测试缝，行为与 OS 平台路径一致）。
 */
class McpEnterpriseConfigTest {

    @TempDir
    Path tempDir;

    private McpEnterpriseConfig at(Path file) {
        return new McpEnterpriseConfig(file.toString());
    }

    @Test
    @DisplayName("managed-mcp.json 缺失 → 不独占（config:null，不短路不阻断）")
    void missing_fileNotExists() {
        McpEnterpriseConfig c = at(tempDir.resolve("missing.json"));
        assertThat(c.doesEnterpriseMcpConfigExist()).isFalse();
    }

    @Test
    @DisplayName("空文件（零字节）→ 不独占（JSON.parse(\"\") 抛错 → config:null）")
    void empty_fileZeroBytes() throws IOException {
        Path f = tempDir.resolve("empty.json");
        Files.writeString(f, "");
        McpEnterpriseConfig c = at(f);
        assertThat(c.doesEnterpriseMcpConfigExist())
            .as("零字节文件必须不独占（与「空 mcpServers」区分，McpEnterpriseConfig Javadoc）")
            .isFalse();
    }

    @Test
    @DisplayName("非法 JSON → 不独占（parse fatal → config:null）")
    void invalid_json() throws IOException {
        Path f = tempDir.resolve("invalid.json");
        Files.writeString(f, "{ not json !!");
        McpEnterpriseConfig c = at(f);
        assertThat(c.doesEnterpriseMcpConfigExist()).isFalse();
    }

    @Test
    @DisplayName("合法但空 mcpServers {} → 存在即独占（config 非 null）")
    void valid_emptyMcpServers_exclusive() throws IOException {
        Path f = tempDir.resolve("valid.json");
        Files.writeString(f, "{\"mcpServers\":{}}");
        McpEnterpriseConfig c = at(f);
        assertThat(c.doesEnterpriseMcpConfigExist())
            .as("空 mcpServers 也是 config !== null → 独占（CC config:null 语义）")
            .isTrue();
    }

    @Test
    @DisplayName("合法含 server → 存在即独占")
    void valid_withServer_exclusive() throws IOException {
        Path f = tempDir.resolve("valid2.json");
        Files.writeString(f,
            "{\"mcpServers\":{\"srv\":{\"type\":\"stdio\",\"command\":\"python\"}}}");
        McpEnterpriseConfig c = at(f);
        assertThat(c.doesEnterpriseMcpConfigExist()).isTrue();
    }

    @Test
    @DisplayName("getEnterpriseMcpFilePath：无注入时按 OS 平台路径，末尾为 managed-mcp.json")
    void filePath_defaultsToOsPath() {
        String p = new McpEnterpriseConfig().getEnterpriseMcpFilePath();
        assertThat(p).endsWith("managed-mcp.json");
    }

    @Test
    @DisplayName("注入路径生效（getEnterpriseMcpFilePath 返回注入值）")
    void filePath_injectedOverride() {
        McpEnterpriseConfig c = new McpEnterpriseConfig("C:/tmp/managed-mcp.json");
        assertThat(c.getEnterpriseMcpFilePath()).isEqualTo("C:/tmp/managed-mcp.json");
    }
}
