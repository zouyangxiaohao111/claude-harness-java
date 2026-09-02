package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ODF-C3] SubagentTool JSON producer 装配点测试 · 对齐 CC main.tsx:2035-2044
 * ({@code --agents} flag → {@code parseAgentsFromJson(parsedAgents, 'flagSettings')}
 * → 并入 allAgents + {@code getActiveAgentsFromList} 覆盖合并).
 *
 * <p>验证 Java 端 SubagentTool 是 {@code --agents} flag 等价的配置入口：
 * ① agents JSON map 解析出 source='flagSettings' 的 AgentDefinition 并入 registry；
 * ② 同 type 覆盖内置（flagSettings 高于 built-in）；
 * ③ registry.listAgents() 返回含 flagSettings 来源 agent（占位 N/A 移除后的可见性）。
 */
class SubagentToolTest {

    private SubagentTool newTool(Path workspaceDir) {
        return new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            workspaceDir, List.of());
    }

    private static Map<String, Object> jsonAgent(String description) {
        return Map.of("description", description, "prompt", "you are " + description);
    }

    @Test
    @DisplayName("setJsonAgents: --agents flag 等价配置入口解析出 source=flagSettings agent 并入 registry")
    void setJsonAgents_produces_flagSettings_agents() throws Exception {
        // WHY: CC main.tsx:2035-2044 --agents flag → parseAgentsFromJson(json,'flagSettings')。
        //   Java 端 SubagentTool 是配置装配点，setJsonAgents 必须产出 source=flagSettings 的
        //   AgentDefinition，否则 registry.listAgents() 永远看不到 flag 来源（占位 N/A 状态）。
        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3"));
        Map<String, Object> json = Map.of(
            "flag-agent", jsonAgent("flag agent"),
            "flag-agent-2", jsonAgent("flag agent 2"));

        tool.setJsonAgents(json);

        List<AgentDefinition> agents = tool.listAgents();
        assertThat(agents.stream().map(AgentDefinition::agentType))
            .contains("flag-agent", "flag-agent-2");
        AgentDefinition flag = agents.stream()
            .filter(a -> a.agentType().equals("flag-agent")).findFirst().orElseThrow();
        assertThat(flag.source())
            .as("JSON producer 必须打 source='flagSettings'（CC main.tsx:2039 字面量）")
            .isEqualTo("flagSettings");
        assertThat(flag.whenToUse()).isEqualTo("flag agent");
    }

    @Test
    @DisplayName("setJsonAgents: 同 type 覆盖内置（flagSettings > built-in，对齐 getActiveAgentsFromList）")
    void setJsonAgents_flagSettings_overrides_builtIn() throws Exception {
        // WHY: CC getActiveAgentsFromList 6 组 [builtIn,plugin,user,project,flag,managed] 按序 set，
        //   flagSettings 晚于 built-in 注册 → 同 agentType 时 flag 胜出。Java 端 registry.merge
        //   必须经 getActiveAgentsFromList 应用该优先级，否则内置 general-purpose 吞掉 flag 定义。
        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3"));
        // 内置 general-purpose 必然存在（BuiltInAgents）；flag JSON 同名覆盖它
        Map<String, Object> json = Map.of(
            "general-purpose", jsonAgent("flag general purpose"));

        tool.setJsonAgents(json);

        AgentDefinition gp = tool.listAgents().stream()
            .filter(a -> a.agentType().equals("general-purpose")).findFirst().orElseThrow();
        assertThat(gp.source())
            .as("同 type flag 定义必须覆盖内置（managed>flag>project>user>plugin>builtIn）")
            .isEqualTo("flagSettings");
    }

    @Test
    @DisplayName("setJsonAgents: 空/非法 JSON 不破坏既有 registry（Fail loud 走 log.warn）")
    void setJsonAgents_invalid_json_keeps_registry() throws Exception {
        // WHY: CC main.tsx:2041-2043 safeParseJSON 失败 → logError + 忽略。Java 端 setJsonAgents
        //   必须对非法输入容错，且不抛异常破坏装配（Fail loud 应记录而非崩溃）。
        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3"));
        int before = tool.listAgents().size();

        tool.setJsonAgents(null);
        tool.setJsonAgents(Map.of());
        tool.setJsonAgents(Map.of("bad", Map.of("prompt", "missing description")));

        assertThat(tool.listAgents().size()).isEqualTo(before);
    }

    @Test
    @DisplayName("返工#1 装配级: setAgentsJson(String) 生产配置入口解析 JSON 并入 registry")
    void setAgentsJsonString_assembly_entry_merges_flag_agents() throws Exception {
        // WHY (返工#1): Reflection 指出 setJsonAgents 无生产调用点。本测试证明生产装配路径：
        //   Spring 配置 nexusai.agent.agents-json → setAgentsJsonConfig(String) →
        //   setAgentsJson(String) safeParseJSON 等价 → setJsonAgents(Map) 并入 registry。
        //   即"从启动参数解析 agents JSON 并入 registry"在装配级真实可闭环（非仅方法级单测）。
        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3"));
        String json = "{\"cfg-flag\":{\"description\":\"cfg flag agent\",\"prompt\":\"you are cfg flag\"}}";

        tool.setAgentsJson(json);

        List<AgentDefinition> agents = tool.listAgents();
        AgentDefinition flag = agents.stream()
            .filter(a -> a.agentType().equals("cfg-flag")).findFirst().orElse(null);
        assertThat(flag).as("装配级 setAgentsJson(String) 必须产出 source=flagSettings agent").isNotNull();
        assertThat(flag.source()).isEqualTo("flagSettings");
        assertThat(flag.whenToUse()).isEqualTo("cfg flag agent");
    }

    @Test
    @DisplayName("返工#1 装配级: setAgentsJson(String) 非法 JSON 不破坏 registry（Fail loud）")
    void setAgentsJsonString_invalid_keeps_registry() throws Exception {
        // WHY (返工#1): 生产装配路径必须容错 — 配置字符串非法时 log.warn 忽略，
        //   不能因配置错误导致 SubagentTool bean 装配失败。
        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3"));
        int before = tool.listAgents().size();

        tool.setAgentsJson(null);
        tool.setAgentsJson("");
        tool.setAgentsJson("  ");
        tool.setAgentsJson("not a json");
        tool.setAgentsJson("[1,2,3]");

        assertThat(tool.listAgents().size()).isEqualTo(before);
    }

    @Test
    @DisplayName("返工#4 装配级: setPluginLoader + mergePluginAgents 生产并入 plugin agents")
    void plugin_loader_assembly_merges_plugin_agents() throws Exception {
        // WHY (返工#4): Reflection 指出 loadAllEnabledAgents 无生产调用点。本测试证明生产装配路径：
        //   Spring 注入 PluginLoader → setPluginLoader(loader) 装配期即调 mergePluginAgents()
        //   → loadAllEnabledAgents() 扫 enabled plugins → registry.merge（6 组覆盖）。
        Path temp = java.nio.file.Files.createTempDirectory("odf-c3-plugin");
        Path agentsDir = temp.resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("helper.md"),
            "---\nname: Helper\ndescription: plugin helper\n---\n\nbody");

        com.nexusai.application.agent.plugin.PluginLoader loader =
            new com.nexusai.application.agent.plugin.PluginLoader();
        // 4 参 load 保留 agentsPath（5 参 load 会丢 agentsPath 字段 → loadAllEnabledAgents 恒空）
        loader.load("my-plugin",
            com.nexusai.application.agent.plugin.PluginLoader.InstallSource.PATH, temp, agentsDir);

        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3"));
        tool.setPluginLoader(loader);

        List<AgentDefinition> agents = tool.listAgents();
        AgentDefinition pluginAgent = agents.stream()
            .filter(a -> a.agentType().equals("my-plugin:Helper")).findFirst().orElse(null);
        assertThat(pluginAgent).as("装配级 PluginLoader 注入必须并入 source=plugin agent").isNotNull();
        assertThat(pluginAgent.source()).isEqualTo("plugin");
    }

    @Test
    @DisplayName("ODF-C3R 生产 feed: 装配期枚举已装 enabled plugins → 4 参 load 注册 agentsPath → listAgents 收 plugin agent")
    void plugin_feed_assembly_enumerates_installed_enabled_plugins() throws Exception {
        // WHY (ODF-C3-FEED 闭环): PluginLoader.load(4 参) 此前无生产调用方 → loadAllEnabledAgents 生产
        //   cache 恒空 → mergePluginAgents no-op。本测试证明生产 feed：装配期枚举已装 enabled plugins
        //   （对齐 CC loadAllPluginsCacheOnly，pluginLoader.ts:3198 filter enabled）→ 4 参 load 注册
        //   agentsPath → setPluginLoader 装配期 mergePluginAgents 扫出 plugin agent → listAgents 可见。
        Path temp = java.nio.file.Files.createTempDirectory("odf-c3r-feed");
        Path agentsDir = temp.resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("helper.md"),
            "---\nname: Helper\ndescription: feed plugin helper\n---\n\nbody");

        com.nexusai.application.agent.plugin.InstalledPluginsManager manager =
            new com.nexusai.application.agent.plugin.InstalledPluginsManager();
        // 已装 + enabled + 携带 agentsPath（对齐 CC plugin.agentsPath，loadPluginAgents.ts:250）
        manager.install("feed-plugin", "1.0.0", "marketplace", temp, agentsDir);

        com.nexusai.application.agent.plugin.PluginLoader loader =
            new com.nexusai.application.agent.plugin.PluginLoader();
        loader.setInstalledPluginsManager(manager);
        // 生产 feed：装配期枚举已装 enabled plugins → 4 参 load（@PostConstruct 在 Spring 装配期执行）
        loader.loadInstalledEnabledPlugins();

        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3r"));
        tool.setPluginLoader(loader);

        List<AgentDefinition> agents = tool.listAgents();
        AgentDefinition pluginAgent = agents.stream()
            .filter(a -> a.agentType().equals("feed-plugin:Helper")).findFirst().orElse(null);
        assertThat(pluginAgent).as("生产 feed 装配后 registry.listAgents() 必须含 plugin agent").isNotNull();
        assertThat(pluginAgent.source()).isEqualTo("plugin");
    }

    @Test
    @DisplayName("ODF-C3R 生产 feed 与 setJsonAgents(flag agent) 并存不冲突")
    void plugin_feed_coexists_with_flag_agents() throws Exception {
        // WHY (验收 #4): plugin feed 与 --agents flag 两 producer 独立并入 registry，
        //   6 组覆盖优先级（managed>flag>project>user>plugin>builtIn）下同 type 不互相吞并。
        Path temp = java.nio.file.Files.createTempDirectory("odf-c3r-both");
        Path agentsDir = temp.resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("helper.md"),
            "---\nname: Helper\ndescription: helper\n---\n\nbody");

        com.nexusai.application.agent.plugin.InstalledPluginsManager manager =
            new com.nexusai.application.agent.plugin.InstalledPluginsManager();
        manager.install("feed-plugin", "1.0.0", "marketplace", temp, agentsDir);
        com.nexusai.application.agent.plugin.PluginLoader loader =
            new com.nexusai.application.agent.plugin.PluginLoader();
        loader.setInstalledPluginsManager(manager);
        loader.loadInstalledEnabledPlugins();

        SubagentTool tool = newTool(java.nio.file.Files.createTempDirectory("odf-c3r-both"));
        tool.setPluginLoader(loader);
        tool.setJsonAgents(Map.of("flag-agent", jsonAgent("flag agent")));

        List<AgentDefinition> agents = tool.listAgents();
        assertThat(agents.stream().map(AgentDefinition::agentType))
            .contains("feed-plugin:Helper", "flag-agent");
        AgentDefinition flag = agents.stream()
            .filter(a -> a.agentType().equals("flag-agent")).findFirst().orElseThrow();
        assertThat(flag.source())
            .as("flag producer 独立并入，source=flagSettings 不受 plugin feed 影响")
            .isEqualTo("flagSettings");
        AgentDefinition plugin = agents.stream()
            .filter(a -> a.agentType().equals("feed-plugin:Helper")).findFirst().orElseThrow();
        assertThat(plugin.source()).isEqualTo("plugin");
    }

    // ─────────────────────────── OPD-SP-32 工具池过滤 ───────────────────────────

    /**
     * 测试桩 Tool · 仅承载 name()（对齐 CanUseToolDispatchTest StubTool 模式）。
     * createSubagentToolRegistry 只做 name 过滤，execute 不会触发。
     */
    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                com.nexusai.application.agent.tool.ToolUseBlock call) {
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "stub");
        }
    }

    /** 反射调用私有 createSubagentToolRegistry（对齐 R32B7a1_*Test setAccessible 模式）。 */
    private static ToolRegistry invokeCreateSubagentToolRegistry(
            SubagentTool tool, AgentDefinition agent, boolean isAsync) throws Exception {
        java.lang.reflect.Method m = SubagentTool.class.getDeclaredMethod(
            "createSubagentToolRegistry", AgentDefinition.class, boolean.class);
        m.setAccessible(true);
        return (ToolRegistry) m.invoke(tool, agent, isAsync);
    }

    @Test
    @DisplayName("OPD-SP-32: plan-mode agent 工具池含 ExitPlanMode（CC :88-93 放行）")
    void createSubagentToolRegistry_planAgent_keepsExitPlanMode() throws Exception {
        // WHY: CC agentToolUtils.ts:88-93 — plan-mode agent 的 ExitPlanMode 在
        //   ALL_AGENT_DISALLOWED_TOOLS + async 双过滤前放行（绕过）。Java createSubagentToolRegistry
        //   若传 permissionMode=null → PLAN 分支永不命中 → plan agent 丢失 ExitPlanMode（S4-6 实锤）。
        SubagentTool tool = new SubagentTool(
            List.of(new StubTool(ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME),
                    new StubTool(ToolNameConstants.FILE_READ_TOOL_NAME)),
            null, null, null, "gpt-4", "", null,
            java.nio.file.Files.createTempDirectory("sp32"), List.of());
        AgentDefinition planAgent = AgentDefinition.CustomAgentDefinition
            .builder("planner", "plans", "userSettings", "body")
            .permissionMode("plan").build();

        ToolRegistry registry = invokeCreateSubagentToolRegistry(tool, planAgent, false);

        assertThat(registry.all().stream().map(Tool::name))
            .as("plan-mode agent 必须保留 ExitPlanMode（CC agentToolUtils.ts:88-93 放行）")
            .contains(ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME);
    }

    @Test
    @DisplayName("OPD-SP-32: 非 plan-mode agent 工具池不含 ExitPlanMode（放行不扩散）")
    void createSubagentToolRegistry_nonPlanAgent_dropsExitPlanMode() throws Exception {
        // WHY: CC :94-96 — ExitPlanMode 在 ALL_AGENT_DISALLOWED_TOOLS；非 plan 不命中 :88-93 放行
        //   → 必须拦截，防止 ExitPlanMode 逃逸到普通 worker。
        SubagentTool tool = new SubagentTool(
            List.of(new StubTool(ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME),
                    new StubTool(ToolNameConstants.FILE_READ_TOOL_NAME)),
            null, null, null, "gpt-4", "", null,
            java.nio.file.Files.createTempDirectory("sp32"), List.of());
        AgentDefinition worker = AgentDefinition.CustomAgentDefinition
            .builder("worker", "works", "userSettings", "body").build();

        ToolRegistry registry = invokeCreateSubagentToolRegistry(tool, worker, false);

        assertThat(registry.all().stream().map(Tool::name))
            .as("非 plan agent 不得携带 ExitPlanMode")
            .doesNotContain(ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME)
            .contains(ToolNameConstants.FILE_READ_TOOL_NAME);
    }

    @Test
    @DisplayName("OPD-SP-32: async plan-mode agent 仍保留 ExitPlanMode（CC :88-93 绕过 async 白名单）")
    void createSubagentToolRegistry_asyncPlanAgent_keepsExitPlanMode() throws Exception {
        // WHY: CC :88-93 放行在 async 过滤 :100-113 之前 → plan-mode async agent 也保留
        //   ExitPlanMode。若实现把 plan 检查放在 async 白名单之后，异步 plan agent 会误丢该工具。
        SubagentTool tool = new SubagentTool(
            List.of(new StubTool(ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME)),
            null, null, null, "gpt-4", "", null,
            java.nio.file.Files.createTempDirectory("sp32"), List.of());
        AgentDefinition planAgent = AgentDefinition.CustomAgentDefinition
            .builder("planner", "plans", "userSettings", "body")
            .permissionMode("plan").build();

        ToolRegistry registry = invokeCreateSubagentToolRegistry(tool, planAgent, true);

        assertThat(registry.all().stream().map(Tool::name))
            .as("async plan-mode agent 必须保留 ExitPlanMode（CC :88-93 优先于 async 过滤）")
            .contains(ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME);
    }
}
