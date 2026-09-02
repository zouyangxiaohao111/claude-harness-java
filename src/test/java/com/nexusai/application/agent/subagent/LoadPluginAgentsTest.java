package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ODF-C3] LoadPluginAgents 等价类测试 · 对齐 CC loadPluginAgents.ts:231-344
 * (manifest {@code agentsPath} 目录扫描 + {@code agentsPaths} 附加路径)。
 *
 * <p>验证：① 临时插件目录 agents/*.md 递归扫描出 pluginName 前缀 agent（source='plugin'）；
 * ② manifest 附加路径目录/单文件均支持；③ namespace 子目录前缀（walkPluginMarkdown.ts:21-68）；
 * ④ 缺 name/description 的无效文件跳过（Fail loud）。
 */
class LoadPluginAgentsTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("默认 agentsPath 目录扫描：pluginName 前缀 + source='plugin'（loadPluginAgents.ts:250-262）")
    void load_default_agents_directory_namespaces_plugin_name() throws Exception {
        // WHY: CC loadPluginAgents.ts:250-262 — plugin.agentsPath 目录下的 .md 全部加载，
        //   agentType = [pluginName, ...namespace, baseAgentName].join(':')（:119-121），
        //   source='plugin'（:212）。Java 若只解析不打 plugin 前缀，与 built-in/自定义同名冲突。
        Path agentsDir = tempDir.resolve("my-plugin").resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("helper.md"),
            "---\nname: Helper\ndescription: helper agent\n---\n\nbody content");
        Files.writeString(agentsDir.resolve("categorizer.md"),
            "---\nname: Categorizer\ndescription: categorizes things\nmodel: haiku\n---\n\nbody");

        List<AgentDefinition> agents = LoadPluginAgents.load(agentsDir, "my-plugin");

        assertThat(agents.stream().map(AgentDefinition::agentType))
            .contains("my-plugin:Helper", "my-plugin:Categorizer");
        for (AgentDefinition a : agents) {
            assertThat(a.source())
                .as("plugin 目录加载的 agent 必须 source='plugin'（loadPluginAgents.ts:212）")
                .isEqualTo("plugin");
        }
        AgentDefinition cat = agents.stream()
            .filter(a -> a.agentType().equals("my-plugin:Categorizer")).findFirst().orElseThrow();
        assertThat(cat.whenToUse()).isEqualTo("categorizes things");
        assertThat(cat.model()).hasValue("haiku");
    }

    @Test
    @DisplayName("namespace 子目录递归：sub/foo.md → plugin:sub:Foo（walkPluginMarkdown.ts:48-57）")
    void load_recursive_subdirectory_namespace() throws Exception {
        // WHY: CC walkPluginMarkdown 递归扫描，子目录路径进入 namespace 数组
        //   （root/foo/bar/file.md → ['foo','bar']），agentType 前缀串联。
        Path agentsDir = tempDir.resolve("ns-plugin").resolve("agents");
        Files.createDirectories(agentsDir.resolve("special"));
        Files.writeString(agentsDir.resolve("special/nested.md"),
            "---\nname: Nested\ndescription: nested agent\n---\n\nbody");

        List<AgentDefinition> agents = LoadPluginAgents.load(agentsDir, "ns-plugin");

        assertThat(agents.stream().map(AgentDefinition::agentType))
            .as("子目录必须进 namespace 前缀（walkPluginMarkdown.ts:52 namespace.push）")
            .contains("ns-plugin:special:Nested");
    }

    @Test
    @DisplayName("manifest 附加路径：目录与单文件均支持（loadPluginAgents.ts:276-309）")
    void load_additional_paths_directory_and_single_file() throws Exception {
        // WHY: CC loadPluginAgents.ts:276-309 — agentsPaths 可指向目录（loadAgentsFromDirectory）
        //   或单 .md 文件（loadAgentFromFile）。Java 若只支持目录，manifest 单文件 agent 丢失。
        Path pluginRoot = tempDir.resolve("add-plugin");
        Path extraDir = pluginRoot.resolve("extra-agents");
        Files.createDirectories(extraDir);
        Files.writeString(extraDir.resolve("extra-one.md"),
            "---\nname: ExtraOne\ndescription: extra one\n---\n\nbody");
        Path singleFile = pluginRoot.resolve("standalone.md");
        Files.writeString(singleFile,
            "---\nname: Standalone\ndescription: standalone agent\n---\n\nbody");

        List<AgentDefinition> agents = LoadPluginAgents.load(
            pluginRoot.resolve("agents"), "add-plugin", List.of(extraDir.toString(), singleFile.toString()));

        assertThat(agents.stream().map(AgentDefinition::agentType))
            .as("agentsPaths 目录 + 单文件都必须加载（loadPluginAgents.ts:288/296）")
            .contains("add-plugin:ExtraOne", "add-plugin:Standalone");
    }

    @Test
    @DisplayName("无 frontmatter 文件跳过；缺 description 用 'Agent from X plugin' 兜底（loadPluginAgents.ts:123-127）")
    void load_skips_non_frontmatter_but_falls_back_when_to_use() throws Exception {
        // WHY: CC loadAgentFromFile 无 frontmatter → 无法解析返回 null（跳过）；缺 description 时
        //   whenToUse 兜底 'Agent from ${pluginName} plugin'（:123-127），agent 仍加载（区别于
        //   .claude/agents/ 的 parseAgentFromMarkdown:561 缺 description 返回 null —— 插件宽松）。
        Path agentsDir = tempDir.resolve("bad-plugin").resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("nodesc.md"),
            "---\nname: NoDesc\n---\n\nbody");
        Files.writeString(agentsDir.resolve("readme.md"),
            "not an agent frontmatter");

        List<AgentDefinition> agents = LoadPluginAgents.load(agentsDir, "bad-plugin");

        // readme.md 无 frontmatter → 跳过；nodesc.md 缺 description → 兜底加载
        assertThat(agents.stream().map(AgentDefinition::agentType))
            .as("无 frontmatter 文件跳过，缺 description 用兜底 whenToUse 加载")
            .containsExactly("bad-plugin:NoDesc");
        assertThat(agents.get(0).whenToUse())
            .as("缺 description → 'Agent from ${pluginName} plugin' 兜底（loadPluginAgents.ts:123-127）")
            .isEqualTo("Agent from bad-plugin plugin");
    }

    @Test
    @DisplayName("agentType 取 frontmatter name（loadPluginAgents.ts:106-121 缺 name 用 basename 兜底）")
    void load_uses_frontmatter_name_with_basename_fallback() throws Exception {
        // WHY: CC :106-108 baseAgentName = frontmatter.name ?? basename(.md)。
        //   缺 name 时用文件名作 base，避免 agent 类型为空。
        Path agentsDir = tempDir.resolve("fn-plugin").resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("named.md"),
            "---\nname: CustomName\ndescription: has name\n---\n\nbody");
        Files.writeString(agentsDir.resolve("unnamed.md"),
            "---\ndescription: no explicit name\n---\n\nbody");

        List<AgentDefinition> agents = LoadPluginAgents.load(agentsDir, "fn-plugin");

        assertThat(agents.stream().map(AgentDefinition::agentType))
            .as("frontmatter.name 优先，缺 name 用 basename 兜底（loadPluginAgents.ts:106-108）")
            .contains("fn-plugin:CustomName", "fn-plugin:unnamed");
    }

    @Test
    @DisplayName("PluginAgentDefinition builder 全字段构建（对齐 CC loadAgentFromFile 返回字段）")
    void plugin_agent_definition_builder_full_fields() {
        // WHY: PluginAgentDefinition 是 sealed record 的第三个实现，无 builder 时 LoadPluginAgents
        //   只能靠 23 参 canonical 构造器（可读性差 + 易错位）。Builder 提供 fluent 等价（对齐
        //   CustomAgentDefinition.Builder 既有范式，CLAUDE.md 规则 11）。
        AgentDefinition def = AgentDefinition.PluginAgentDefinition.builder(
                "p:Agent", "desc", "p", "system prompt")
            .tools(List.of("Bash"))
            .model("haiku")
            .memory("project")
            .build();

        assertThat(def.source()).isEqualTo("plugin");
        assertThat(def.agentType()).isEqualTo("p:Agent");
        assertThat(def.whenToUse()).isEqualTo("desc");
        assertThat(def.tools()).hasValue(List.of("Bash"));
        assertThat(def.model()).hasValue("haiku");
        assertThat(def.memory()).hasValue("project");
        assertThat(def.getSystemPrompt(null, List.of())).isEqualTo("system prompt");

        // [REWORK-5 R-C C-5] IMP-SUB-22 安全边界不变量锁：插件是第三方 marketplace 代码，
        //   CC loadPluginAgents.ts:153-168 不解析这些字段（逐 agent 声明会越权）→ Builder 已删公开
        //   暴露（#8 收窄边界），record 组件恒 Optional.empty()（满足 sealed interface 访问器契约）。
        //   若有人重新暴露 setter 让插件声明这些字段，本测试即红（规则九：意图锁在行为变更时报错）。
        assertThat(def.mcpServers()).isEmpty();
        assertThat(def.hooks()).isEmpty();
        assertThat(def.permissionMode()).isEmpty();
        assertThat(def.criticalSystemReminder_EXPERIMENTAL()).isEmpty();
        assertThat(def.requiredMcpServers()).isEmpty();
        assertThat(def.initialPrompt()).isEmpty();
        assertThat(def.pendingSnapshotUpdate()).isEmpty();
        assertThat(def.omitClaudeMd()).isEmpty();
    }

    @Test
    @DisplayName("PluginLoader.loadAgents 委托扫描（plugin agents 目录接线）")
    void pluginLoader_delegates_to_loadPluginAgents() throws Exception {
        // WHY: PluginLoader 是 Spring @Component 装配入口（CC loadPluginAgents.ts:233 loadAllPluginsCacheOnly
        //   产出 enabled plugins），loadAgents() 委托 LoadPluginAgents 让 plugin agents 可经 Spring bean 触达。
        Path agentsDir = tempDir.resolve("pl-plugin").resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("a.md"),
            "---\nname: A\ndescription: agent a\n---\n\nbody");

        com.nexusai.application.agent.plugin.PluginLoader loader = new com.nexusai.application.agent.plugin.PluginLoader();
        List<AgentDefinition> agents = loader.loadAgents(agentsDir, "pl-plugin");

        assertThat(agents.stream().map(AgentDefinition::agentType))
            .as("PluginLoader.loadAgents 必须委托 LoadPluginAgents 产出 plugin agent")
            .contains("pl-plugin:A");
    }

    @Test
    @DisplayName("systemPrompt ${CLAUDE_PLUGIN_ROOT} 替换为插件本地路径（loadPluginAgents.ts:110-113）")
    void load_substitutes_claude_plugin_root_in_system_prompt() throws Exception {
        // WHY: CC loadPluginAgents.ts:110-113 substitutePluginVariables({path: pluginPath}) — 插件 agent
        //   引用 bundled 文件（${CLAUDE_PLUGIN_ROOT}/...）时 prompt 必须解析为实际安装路径（win32 归一化为 '/'），
        //   否则 shell 命令/文件引用指向字面占位符失效。Java 若不替换则与 CC 语义漂移。
        Path pluginRoot = tempDir.resolve("root-plugin");
        Path agentsDir = pluginRoot.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("tool.md"),
            "---\nname: Tool\ndescription: tool agent\n---\n\nread ${CLAUDE_PLUGIN_ROOT}/scripts/tool.sh");

        List<AgentDefinition> agents = LoadPluginAgents.load(agentsDir, "root-plugin", List.of(), pluginRoot);

        AgentDefinition tool = agents.stream()
            .filter(a -> a.agentType().equals("root-plugin:Tool")).findFirst().orElseThrow();
        assertThat(tool.getSystemPrompt(null, List.of()))
            .as("${CLAUDE_PLUGIN_ROOT} 必须替换为插件本地路径（loadPluginAgents.ts:113 substitutePluginVariables）")
            .isEqualTo("read " + pluginRoot.toString().replace('\\', '/') + "/scripts/tool.sh");
    }

    @Test
    @DisplayName("systemPrompt ${user_config.X} 替换为配置值（loadPluginAgents.ts:117-121）")
    void load_substitutes_user_config_placeholders() throws Exception {
        // WHY: CC loadPluginAgents.ts:117-121 substituteUserConfigInContent — 插件 agent 可嵌入配置的
        //   用户名/端点（${user_config.X}）；Java 若只替换 CLAUDE_PLUGIN_ROOT，用户配置引用失效。
        Path pluginRoot = tempDir.resolve("uc-plugin");
        Path agentsDir = pluginRoot.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("conn.md"),
            "---\nname: Conn\ndescription: connector\n---\n\nconnect as ${user_config.user} to ${user_config.endpoint}");

        List<AgentDefinition> agents = LoadPluginAgents.load(
            agentsDir, "uc-plugin", List.of(), pluginRoot,
            Map.of("user", "alice", "endpoint", "https://api.example.com"), Set.of());

        AgentDefinition conn = agents.stream()
            .filter(a -> a.agentType().equals("uc-plugin:Conn")).findFirst().orElseThrow();
        assertThat(conn.getSystemPrompt(null, List.of()))
            .as("${user_config.X} 必须替换为用户配置值（loadPluginAgents.ts:118-121）")
            .isEqualTo("connect as alice to https://api.example.com");
    }

    @Test
    @DisplayName("sensitive user_config 替换为占位符、未知键保持字面（substituteUserConfigInContent 语义）")
    void load_sensitive_and_unknown_user_config_handling() throws Exception {
        // WHY: CC pluginOptionsStorage.ts:385-419 substituteUserConfigInContent — 敏感键替换为描述性
        //   占位符（密钥不进模型 prompt），未知键保持字面（与未设 ${VAR} 一致）。Java 若不区分敏感键，
        //   会把密钥写入子 Agent 的 prompt 上下文，属安全漂移。
        Path pluginRoot = tempDir.resolve("sec-plugin");
        Path agentsDir = pluginRoot.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("sec.md"),
            "---\nname: Sec\ndescription: secure\n---\n\ntoken=${user_config.token} other=${user_config.unknown}");

        List<AgentDefinition> agents = LoadPluginAgents.load(
            agentsDir, "sec-plugin", List.of(), pluginRoot,
            Map.of("token", "secret-value"), Set.of("token"));

        AgentDefinition sec = agents.stream()
            .filter(a -> a.agentType().equals("sec-plugin:Sec")).findFirst().orElseThrow();
        assertThat(sec.getSystemPrompt(null, List.of()))
            .as("敏感键替换为占位符、未知键保持字面（substituteUserConfigInContent 语义）")
            .isEqualTo("token=[sensitive option 'token' not available in skill content] "
                + "other=${user_config.unknown}");
    }

    @Test
    @DisplayName("pluginPath 为 null → ${CLAUDE_PLUGIN_ROOT} 保持字面（不替换，容错路径）")
    void load_null_plugin_path_leaves_root_literal() throws Exception {
        // WHY: 既有 3 参 load（无 pluginPath 上下文）不传插件路径；此时 ${CLAUDE_PLUGIN_ROOT} 保持字面
        //   （无路径可替换即不替换），不能抛异常破坏既有扫描路径。
        Path agentsDir = tempDir.resolve("npp-plugin").resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("lit.md"),
            "---\nname: Lit\ndescription: literal\n---\n\npath=${CLAUDE_PLUGIN_ROOT}/x");

        List<AgentDefinition> agents = LoadPluginAgents.load(agentsDir, "npp-plugin");

        AgentDefinition lit = agents.stream()
            .filter(a -> a.agentType().equals("npp-plugin:Lit")).findFirst().orElseThrow();
        assertThat(lit.getSystemPrompt(null, List.of()))
            .as("pluginPath null → 占位符保持字面（无路径不替换）")
            .isEqualTo("path=${CLAUDE_PLUGIN_ROOT}/x");
    }

    @Test
    @DisplayName("PluginLoader.loadAllEnabledAgents 以 localPath 为 ${CLAUDE_PLUGIN_ROOT} 替换上下文")
    void pluginLoader_threads_local_path_as_substitution_context() throws Exception {
        // WHY: PluginLoader.load(4 参) 缓存插件后 loadAllEnabledAgents 必须把 localPath 作为 pluginPath
        //   传入 LoadPluginAgents（CC loadPluginAgents.ts:234 plugin.path → loadAgentFromFile:92 pluginPath），
        //   否则生产 feed 填充 cache 后 ${CLAUDE_PLUGIN_ROOT} 替换仍不生效。
        Path pluginRoot = tempDir.resolve("local-plugin");
        Path agentsDir = pluginRoot.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("b.md"),
            "---\nname: B\ndescription: agent b\n---\n\nrun ${CLAUDE_PLUGIN_ROOT}/bin/b");

        com.nexusai.application.agent.plugin.PluginLoader loader =
            new com.nexusai.application.agent.plugin.PluginLoader();
        loader.load("local-plugin", com.nexusai.application.agent.plugin.PluginLoader.InstallSource.PATH,
            pluginRoot, agentsDir);

        List<AgentDefinition> agents = loader.loadAllEnabledAgents();

        AgentDefinition b = agents.stream()
            .filter(a -> a.agentType().equals("local-plugin:B")).findFirst().orElseThrow();
        assertThat(b.getSystemPrompt(null, List.of()))
            .as("loadAllEnabledAgents 必须以 localPath 为 ${CLAUDE_PLUGIN_ROOT} 替换上下文（plugin.path 语义）")
            .isEqualTo("run " + pluginRoot.toString().replace('\\', '/') + "/bin/b");
    }
}
