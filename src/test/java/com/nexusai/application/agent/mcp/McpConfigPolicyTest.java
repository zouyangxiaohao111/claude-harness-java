package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.mcp.config.McpConfigPolicy;
import com.nexusai.application.agent.mcp.config.McpConfigPolicy.FilterResult;
import com.nexusai.application.agent.mcp.config.McpProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 · 策略过滤（isMcpServerDenied / isMcpServerAllowedByPolicy / filterMcpServersByPolicy）。
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC config.ts:364-551。导入 .mcp.json 前必须按 enterprise
 * 策略过滤：denylist 绝对优先、空 allowlist 全拦、command/url entry 强制匹配、sdk 豁免。
 * 任何偏离会让被策略禁止的 server 进入 DB（安全边界破坏）。
 */
class McpConfigPolicyTest {

    private static Map<String, Object> stdio(String command, String... args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        m.put("args", List.of(args));
        return m;
    }

    private static Map<String, Object> remote(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "http");
        m.put("url", url);
        return m;
    }

    private static Map<String, Object> sdk(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "sdk");
        m.put("name", name);
        return m;
    }

    private static McpProperties.Policy policy(List<McpProperties.Entry> allowed,
            List<McpProperties.Entry> denied) {
        return new McpProperties.Policy(allowed, denied, false);
    }

    @Test
    @DisplayName("isMcpServerDenied: name 命中 → denied")
    void deniedByName() {
        List<McpProperties.Entry> denied = List.of(McpProperties.Entry.byName("evil"));
        assertThat(McpConfigPolicy.isMcpServerDenied("evil", stdio("npx"), denied)).isTrue();
        assertThat(McpConfigPolicy.isMcpServerDenied("good", stdio("npx"), denied)).isFalse();
    }

    @Test
    @DisplayName("isMcpServerDenied: command 数组精确匹配")
    void deniedByCommand() {
        List<McpProperties.Entry> denied = List.of(McpProperties.Entry.byCommand(List.of("npx", "-y", "evil")));
        assertThat(McpConfigPolicy.isMcpServerDenied("s", stdio("npx", "-y", "evil"), denied)).isTrue();
        assertThat(McpConfigPolicy.isMcpServerDenied("s", stdio("npx", "-y", "good"), denied)).isFalse();
    }

    @Test
    @DisplayName("isMcpServerDenied: url wildcard 匹配")
    void deniedByUrlWildcard() {
        List<McpProperties.Entry> denied = List.of(McpProperties.Entry.byUrl("https://*.example.com/*"));
        assertThat(McpConfigPolicy.isMcpServerDenied("s", remote("https://api.example.com/path"), denied)).isTrue();
        assertThat(McpConfigPolicy.isMcpServerDenied("s", remote("https://other.org/x"), denied)).isFalse();
    }

    @Test
    @DisplayName("isMcpServerAllowedByPolicy: denylist 绝对优先（同时命中 allow+deny → denied）")
    void denylistTakesPrecedence() {
        McpProperties.Policy p = policy(
            List.of(McpProperties.Entry.byName("s")),
            List.of(McpProperties.Entry.byName("s")));
        assertThat(McpConfigPolicy.isMcpServerAllowedByPolicy("s", stdio("npx"), p)).isFalse();
    }

    @Test
    @DisplayName("isMcpServerAllowedByPolicy: 空 allowlist → 全拦")
    void emptyAllowlistBlocksAll() {
        McpProperties.Policy p = policy(List.of(), null);
        assertThat(McpConfigPolicy.isMcpServerAllowedByPolicy("anything", stdio("npx"), p)).isFalse();
    }

    @Test
    @DisplayName("isMcpServerAllowedByPolicy: allowlist 为 null → 全放行")
    void nullAllowlistAllowsAll() {
        McpProperties.Policy p = policy(null, null);
        assertThat(McpConfigPolicy.isMcpServerAllowedByPolicy("anything", stdio("npx"), p)).isTrue();
    }

    @Test
    @DisplayName("isMcpServerAllowedByPolicy: 有 command entry 时 stdio 必须匹配（否则拦）")
    void stdioMustMatchCommandEntry() {
        McpProperties.Policy p = policy(
            List.of(McpProperties.Entry.byCommand(List.of("npx", "-y", "good"))), null);
        assertThat(McpConfigPolicy.isMcpServerAllowedByPolicy("s", stdio("npx", "-y", "good"), p)).isTrue();
        assertThat(McpConfigPolicy.isMcpServerAllowedByPolicy("s", stdio("npx", "-y", "bad"), p)).isFalse();
    }

    @Test
    @DisplayName("filterMcpServersByPolicy: sdk 类型豁免；被拦返回 blocked")
    void filterSdkExempt() {
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        configs.put("sdk-server", sdk("claude-vscode"));
        configs.put("blocked", stdio("npx", "-y", "bad"));
        McpProperties.Policy p = policy(
            List.of(McpProperties.Entry.byCommand(List.of("npx", "-y", "good"))), null);
        FilterResult r = McpConfigPolicy.filterMcpServersByPolicy(configs, p);
        assertThat(r.allowed()).containsKey("sdk-server");
        assertThat(r.blocked()).containsExactly("blocked");
    }
}
