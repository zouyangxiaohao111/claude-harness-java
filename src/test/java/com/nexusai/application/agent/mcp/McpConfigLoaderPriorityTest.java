package com.nexusai.application.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 · loadAllMcpServers 合并序对齐 CC（local 最高）。
 *
 * <p><b>WHY (意图验证)</b>: CC config.ts:1231-1238 getClaudeCodeMcpConfigs 合并序是
 * {@code plugin < user < project < local}（local 最高，后写覆盖）。Java 旧实现是
 * {@code enterprise > user > project > local}（local 最低），与本模块导入语义相反——
 * 导入 .mcp.json 时同名 server 会错误地让 user 覆盖 local。本测试锁定 CC 语义：
 * 同名冲突时 local 版本胜出 + enterprise 独占短路（config.ts:1084-1096/1470-1477）。
 * （原显式优先级查找用例已随 D-B10-08 删除——生产按名解析由
 * McpServerService.getServerConfigByName 承担，DB 唯一运行时源 Q-09=C。）
 */
class McpConfigLoaderPriorityTest {

    private static Map<String, Map<String, Object>> scope(String scope, String cmd) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", cmd);
        server.put("args", List.of());
        server.put("scope", scope);
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("server1", server);
        return m;
    }

    private McpConfigLoader loader(
            Optional<Map<String, Map<String, Object>>> enterprise,
            Map<String, Map<String, Object>> user,
            Map<String, Map<String, Object>> project,
            Map<String, Map<String, Object>> local,
            Map<String, Map<String, Object>> dynamic) {
        return new McpConfigLoader(
            () -> enterprise, () -> user, () -> project, () -> local, () -> dynamic, () -> Map.of());
    }

    @Test
    @DisplayName("loadAllMcpServers: dynamic/claudeai/user/project/local 同名 server1 → local 版本胜出")
    void loadAllMcpServersLocalWins() {
        // WHY: 对齐 CC config.ts:1231-1238 合并序（plugin < user < project < local），
        // Java 无 plugin scope → dynamic/claudeai 最低、local 最高。旧实现 user 覆盖 local，
        // 会让同名的 project .mcp.json 配置被 user 配置冲掉（优先级错位）。
        McpConfigLoader loader = loader(
            Optional.empty(),
            scope("user", "user-cmd"),
            scope("project", "project-cmd"),
            scope("local", "local-cmd"),
            scope("dynamic", "dynamic-cmd"));

        Map<String, Map<String, Object>> all = loader.loadAllMcpServers();

        assertThat(all).containsKey("server1");
        assertThat(all.get("server1").get("command"))
            .as("local 优先级最高，同名 server 必须用 local 版本")
            .isEqualTo("local-cmd");
        assertThat(all.get("server1").get("scope")).isEqualTo("local");
    }

    @Test
    @DisplayName("loadAllMcpServers: enterprise 存在但空（config!==null）→ 独占短路，返回空 enterprise-only")
    void emptyEnterpriseExistsShortCircuits() {
        // WHY: CC doesEnterpriseMcpConfigExist（config.ts:1470-1477）= config !== null——
        // enterprise 文件存在且解析为合法 schema（空 mcpServers 也算）→ true → 独占短路，
        // 忽略 user/project/local。旧实现按 !enterprise.isEmpty()（内容非空）判断，空 enterprise
        // 会错误合并 user/local。
        McpConfigLoader loader = loader(
            Optional.of(Map.of()),
            scope("user", "user-cmd"),
            scope("project", "project-cmd"),
            scope("local", "local-cmd"),
            Map.of());

        Map<String, Map<String, Object>> all = loader.loadAllMcpServers();

        assertThat(all)
            .as("空 enterprise 存在也短路，user/project/local 被抑制（CC config:null 语义）")
            .isEmpty();
    }

    @Test
    @DisplayName("loadAllMcpServers: 无 local 时 project 覆盖 user（project 次高）")
    void loadAllMcpServersProjectBeatsUser() {
        McpConfigLoader loader = loader(
            Optional.empty(),
            scope("user", "user-cmd"),
            scope("project", "project-cmd"),
            Map.of(),
            Map.of());

        Map<String, Map<String, Object>> all = loader.loadAllMcpServers();

        assertThat(all.get("server1").get("command"))
            .as("local 缺省 → project 次高，覆盖 user")
            .isEqualTo("project-cmd");
    }
}
