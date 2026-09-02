package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.mcp.config.McpJsonConfigParser;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 · .mcp.json 解析链（parseMcpConfig / parseMcpConfigFromFilePath）。
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC config.ts:1297-1468。Java 导入管线以 .mcp.json
 * 为入口（Q-09=C），解析必须严格复刻 CC 三档 fatal 分类 + 逐 server 8 type 校验 +
 * env 展开（missingVars warning / ${X:-default} 展开）。任何偏离都会让导入行为与
 * CC 不一致（如错误 type 静默通过、缺失 env 不告警）。
 */
class McpJsonConfigParserTest {

    private static EnvExpansion env(Map<String, String> vars) {
        return new EnvExpansion(vars::get);
    }

    private static Map<String, Object> stdio(String command) {
        Map<String, Object> m = new HashMap<>();
        m.put("command", command);
        m.put("args", List.of("-y", "pkg"));
        return m;
    }

    @Test
    @DisplayName("合法 stdio config 解析通过（type 缺省 → stdio）")
    void validStdioParses() {
        Map<String, Object> config = Map.of("mcpServers", Map.of("playwright", stdio("python")));
        McpJsonConfigParser.ParseResult r =
            McpJsonConfigParser.parseMcpConfig(config, false, "project", env(Map.of()));
        assertThat(r.errors()).isEmpty();
        assertThat(r.servers()).containsKey("playwright");
        assertThat(r.servers().get("playwright").get("command")).isEqualTo("python");
    }

    @Test
    @DisplayName("合法 sdk / http config 解析通过")
    void validSdkAndHttpParse() {
        Map<String, Object> sdk = Map.of("mcpServers", Map.of("vscode",
            Map.of("type", "sdk", "name", "claude-vscode")));
        McpJsonConfigParser.ParseResult sdkR =
            McpJsonConfigParser.parseMcpConfig(sdk, false, "user", env(Map.of()));
        assertThat(sdkR.errors()).isEmpty();
        assertThat(sdkR.servers().get("vscode").get("type")).isEqualTo("sdk");

        Map<String, Object> http = Map.of("mcpServers", Map.of("remote",
            Map.of("type", "http", "url", "https://example.com/mcp")));
        McpJsonConfigParser.ParseResult httpR =
            McpJsonConfigParser.parseMcpConfig(http, false, "user", env(Map.of()));
        assertThat(httpR.errors()).isEmpty();
        assertThat(httpR.servers().get("remote").get("url")).isEqualTo("https://example.com/mcp");
    }

    @Test
    @DisplayName("非法 type → fatal（config=null 语义）")
    void invalidTypeIsFatal() {
        Map<String, Object> config = Map.of("mcpServers", Map.of("bad",
            Map.of("type", "teleport", "command", "npx")));
        McpJsonConfigParser.ParseResult r =
            McpJsonConfigParser.parseMcpConfig(config, false, "project", env(Map.of()));
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).severity()).isEqualTo("fatal");
        assertThat(r.errors().get(0).message())
            .isEqualTo("Does not adhere to MCP server configuration schema");
    }

    @Test
    @DisplayName("混合 config：任一 server 校验失败 → 整文件拒绝（servers 空 + 全 fatal）")
    void anyServerFatalRejectsWholeFile() {
        // WHY: CC config.ts:1307-1321 用整对象 zod schema（types.ts:171-175）safeParse——
        // 任一 server 不满足 union 分支 → 整个 config 返回 config:null + 全部 fatal，
        // 不保留合法 server（config:null 语义）。当前实现曾逐 server continue 跳过坏 server，
        // 保留 "ok"（servers 非空），与本测试目标相悖。
        Map<String, Object> config = Map.of("mcpServers", Map.of(
            "ok", stdio("python"),
            "bad", Map.of("type", "teleport", "command", "npx")));
        McpJsonConfigParser.ParseResult r =
            McpJsonConfigParser.parseMcpConfig(config, false, "project", env(Map.of()));
        assertThat(r.servers()).isEmpty();
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors()).allMatch(e -> "fatal".equals(e.severity()));
        assertThat(r.errors()).allMatch(e ->
            "Does not adhere to MCP server configuration schema".equals(e.message()));
    }

    @Test
    @DisplayName("${MISSING_VAR} 无默认 → warning + 保留字面量")
    void missingVarWarnsAndKeepsLiteral() {
        Map<String, Object> config = Map.of("mcpServers", Map.of("s",
            stdio("${MISSING_VAR}/bin")));
        McpJsonConfigParser.ParseResult r =
            McpJsonConfigParser.parseMcpConfig(config, true, "project", env(Map.of()));
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).severity()).isEqualTo("warning");
        assertThat(r.errors().get(0).message()).contains("Missing environment variables: MISSING_VAR");
        assertThat(r.servers().get("s").get("command")).isEqualTo("${MISSING_VAR}/bin");
    }

    @Test
    @DisplayName("${X:-default} → 展开 default；env 有值 → 用 env 值")
    void defaultExpansionAndEnvValue() {
        Map<String, Object> config = Map.of("mcpServers", Map.of(
            "withDefault", stdio("${HOST2:-localhost}"),
            "withEnv", stdio("${HOST}/bin")));
        McpJsonConfigParser.ParseResult r =
            McpJsonConfigParser.parseMcpConfig(config, true, "project", env(Map.of("HOST", "api.x")));
        assertThat(r.errors()).isEmpty();
        assertThat(r.servers().get("withDefault").get("command")).isEqualTo("localhost");
        assertThat(r.servers().get("withEnv").get("command")).isEqualTo("api.x/bin");
    }

    @Test
    @DisplayName("parseMcpConfigFromFilePath: 非法 JSON → fatal「MCP config is not a valid JSON」")
    void invalidJsonIsFatal() {
        McpJsonConfigParser.ParseResult r = McpJsonConfigParser.parseMcpConfigFromFilePath(
            "/x/.mcp.json", true, "project", env(Map.of()), p -> "not json{{{");
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).severity()).isEqualTo("fatal");
        assertThat(r.errors().get(0).message()).isEqualTo("MCP config is not a valid JSON");
    }

    @Test
    @DisplayName("Windows 平台 stdio command=npx 无 cmd /c 包装 → warning（config.ts:1351-1369）")
    void windowsNpxWarningOnWindows() {
        // WHY: CC 对 Windows npx 无 cmd /c 包装给出 warning（不阻断）。
        // 若运行环境是 Windows（本项目 CI 是 Windows），command=npx 必须产生 warning。
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return; // 非 Windows 环境跳过（CC getPlatform() !== 'windows' 时不告警）
        }
        Map<String, Object> config = Map.of("mcpServers", Map.of("s", stdio("npx")));
        McpJsonConfigParser.ParseResult r =
            McpJsonConfigParser.parseMcpConfig(config, false, "project", env(Map.of()));
        assertThat(r.errors()).anySatisfy(e -> {
            assertThat(e.severity()).isEqualTo("warning");
            assertThat(e.message()).contains("Windows requires 'cmd /c' wrapper to execute npx");
        });
    }

    @Test
    @DisplayName("parseMcpConfigFromFilePath: 文件不存在 → fatal「MCP config file not found」")
    void missingFileIsFatal() {
        McpJsonConfigParser.ParseResult r = McpJsonConfigParser.parseMcpConfigFromFilePath(
            "/missing/.mcp.json", true, "project", env(Map.of()),
            p -> { throw new NoSuchFileException(p); });
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).severity()).isEqualTo("fatal");
        assertThat(r.errors().get(0).message()).startsWith("MCP config file not found");
    }

    @Test
    @DisplayName("parseMcpConfigFromFilePath: 读失败（非 ENOENT）→ fatal「Failed to read file」")
    void readErrorIsFatal() {
        McpJsonConfigParser.ParseResult r = McpJsonConfigParser.parseMcpConfigFromFilePath(
            "/x/.mcp.json", true, "project", env(Map.of()),
            p -> { throw new IOException("permission denied"); });
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).severity()).isEqualTo("fatal");
        assertThat(r.errors().get(0).message()).startsWith("Failed to read file");
    }
}
