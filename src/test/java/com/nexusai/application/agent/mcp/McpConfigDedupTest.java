package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.mcp.config.McpConfigDedup;
import com.nexusai.application.agent.mcp.config.McpConfigDedup.DedupResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 · 去重 + 签名（getMcpServerSignature / unwrapCcrProxyUrl / dedup*）。
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC config.ts:202-310。导入 .mcp.json 时同名内容
 * （同 command/同 url）的 server 必须按 CC 规则去重：manual 优先于 plugin、plugin 间
 * first-loaded 优先、claude.ai connector 仅被 enabled manual 抑制。签名是去重地基，
 * stdio 签名含 command+args 数组、url 签名经 CCR 代理 marker 还原 mcp_url、sdk 无签名。
 */
class McpConfigDedupTest {

    private static Map<String, Object> stdio(String command, String... args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        m.put("args", List.of(args));
        return m;
    }

    private static Map<String, Object> url(String u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "http");
        m.put("url", u);
        return m;
    }

    private static Map<String, Object> sdk(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "sdk");
        m.put("name", name);
        return m;
    }

    private static Map<String, Map<String, Object>> m(String k, Map<String, Object> v) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        map.put(k, v);
        return map;
    }

    @Test
    @DisplayName("getMcpServerSignature: stdio → stdio:[command,...args]（jsonStringify 无空格）")
    void stdioSignature() {
        assertThat(McpConfigDedup.getMcpServerSignature(stdio("npx", "-y", "pkg")))
            .isEqualTo("stdio:[\"npx\",\"-y\",\"pkg\"]");
        // type 缺省 = stdio 兼容
        assertThat(McpConfigDedup.getMcpServerSignature(stdio("python"))).isEqualTo("stdio:[\"python\"]");
    }

    @Test
    @DisplayName("getMcpServerSignature: url → url:unwrapCcrProxyUrl；CCR marker 还原 mcp_url")
    void urlSignatureWithCcrUnwrap() {
        assertThat(McpConfigDedup.getMcpServerSignature(url("https://example.com/mcp")))
            .isEqualTo("url:https://example.com/mcp");

        String proxy = "https://proxy.example/v2/session_ingress/shttp/mcp/xyz?mcp_url="
            + "https%3A%2F%2Fvendor.example%2Fmcp";
        assertThat(McpConfigDedup.getMcpServerSignature(url(proxy)))
            .isEqualTo("url:https://vendor.example/mcp");
    }

    @Test
    @DisplayName("getMcpServerSignature: sdk（无 command 无 url）→ null")
    void sdkSignatureIsNull() {
        assertThat(McpConfigDedup.getMcpServerSignature(sdk("claude-vscode"))).isNull();
    }

    @Test
    @DisplayName("dedupPluginMcpServers: manual 覆盖 plugin（同签名）")
    void manualOverridesPlugin() {
        DedupResult r = McpConfigDedup.dedupPluginMcpServers(
            m("plugin:git:github", stdio("npx", "-y", "pkg")),
            m("github", stdio("npx", "-y", "pkg")));
        assertThat(r.servers()).isEmpty();
        assertThat(r.suppressed()).containsExactly(
            new McpConfigDedup.Suppressed("plugin:git:github", "github"));
    }

    @Test
    @DisplayName("dedupPluginMcpServers: plugin 间 first-loaded 优先")
    void pluginFirstLoadedWins() {
        Map<String, Map<String, Object>> plugins = m("plugin:a:s1", stdio("npx", "-y", "pkg"));
        plugins.put("plugin:b:s2", stdio("npx", "-y", "pkg"));
        DedupResult r = McpConfigDedup.dedupPluginMcpServers(plugins, Map.of());
        assertThat(r.servers()).containsKey("plugin:a:s1");
        assertThat(r.suppressed()).containsExactly(
            new McpConfigDedup.Suppressed("plugin:b:s2", "plugin:a:s1"));
    }

    @Test
    @DisplayName("dedupPluginMcpServers: sdk（签名 null）不参与去重，直接保留")
    void sdkPluginKept() {
        DedupResult r = McpConfigDedup.dedupPluginMcpServers(
            m("plugin:x:sdk", sdk("claude-vscode")), Map.of());
        assertThat(r.servers()).containsKey("plugin:x:sdk");
        assertThat(r.suppressed()).isEmpty();
    }

    @Test
    @DisplayName("dedupClaudeAiMcpServers: 仅 enabled manual 为去重目标（disabled 不抑制）")
    void claudeAiDedupOnlyEnabledManual() {
        Map<String, Map<String, Object>> claudeAi = m("claude.ai Slack", url("https://mcp.slack.com"));
        Map<String, Map<String, Object>> manual = m("slack", url("https://mcp.slack.com"));

        // manual enabled → 抑制 connector
        DedupResult r = McpConfigDedup.dedupClaudeAiMcpServers(claudeAi, manual,
            name -> "slack".equals(name) ? false : false);
        assertThat(r.suppressed()).containsExactly(new McpConfigDedup.Suppressed("claude.ai Slack", "slack"));

        // manual disabled → 不抑制（否则两个都不跑，config.ts:278-280）
        DedupResult r2 = McpConfigDedup.dedupClaudeAiMcpServers(claudeAi, manual,
            name -> "slack".equals(name));
        assertThat(r2.servers()).containsKey("claude.ai Slack");
        assertThat(r2.suppressed()).isEmpty();
    }
}
