package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.plugin.BuiltinPluginRegistry;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-9 getModelInvocableCommands 过滤语义测试 · 对齐 CC getSkillToolCommands（commands.ts:563-581）
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>Plugin/MCP 命令必须显式描述才进模型 catalog</b>——CC 注释 commands.ts:571-573
 *       「Plugin/MCP commands still require an explicit description to appear in the listing」。
 *       Java 旧过滤链 {@code source ∈ {BUNDLED, USER, PLUGIN}} 对 PLUGIN 无条件放行 = 无显式描述
 *       的 plugin 命令污染 SkillTool listing。用例 (c) 是 RED 于旧架构的关键失败证据：改造前
 *       PLUGIN 源恒放行必 fail，改造后无描述/无 whenToUse 被排除。</li>
 *   <li><b>bundled/user 免显式描述自动放行</b>——CC :574-576 loadedFrom∈{bundled,skills,
 *       commands_DEPRECATED}（markdown 首行自动推导描述）；Java source∈{BUNDLED,USER} 在 allowlist。
 *       用例 (a)(b)。</li>
 *   <li><b>MCP 永不进 getSkillToolCommands</b>——CC「MCP live outside getCommands」commands.ts:541-546，
 *       接入由 P2-9 + X23 独立通道。用例 (f)。</li>
 *   <li><b>type + disableModelInvocation 前置维度</b>——CC :568-569。用例 (g) + 默认 prompt type。</li>
 * </ol>
 */
class SkillRegistryModelInvocableFilterTest {

    /** 写一个最小 SKILL.md（frontmatter name + 可选额外字段）· 对齐 SkillsLoader.loadFromSkillMd */
    private static void writeSkill(Path root, String dir, String name, String extraFrontmatter) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        StringBuilder fm = new StringBuilder("---\nname: ").append(name).append('\n');
        if (extraFrontmatter != null) {
            fm.append(extraFrontmatter).append('\n');
        }
        fm.append("---\n# ").append(name).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), fm.toString());
    }

    /** 注册一个含单个 skill 的内置插件（source=PLUGIN，hasUserSpecifiedDescription 默认 false） */
    private static void registerPluginSkill(SkillRegistry registry, BuiltinPluginRegistry.SkillDefinition skill) {
        BuiltinPluginRegistry pr = new BuiltinPluginRegistry(() -> BuiltinPluginRegistry.Settings.EMPTY);
        pr.registerBuiltinPlugin(new BuiltinPluginRegistry.BuiltinPluginDefinition(
            "p1-9-plugin", "P1-9 test plugin", "1.0.0", true,
            List.of(), null, null, null, false, true,
            List.of(), List.of(), List.of(skill), () -> true));
        registry.setBuiltinPluginRegistry(pr);
    }

    @Test
    @DisplayName("USER 技能带显式 description frontmatter → 进模型可调用清单")
    void userSkill_withExplicitDescription_included(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "user-desc", "user-desc", "description: 显式描述");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .contains("user-desc");
    }

    @Test
    @DisplayName("USER 技能无 description（markdown 首行自动推导）→ allowlist 保留进清单")
    void userSkill_withoutDescription_autoDerived_included(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "user-nodesc", "user-nodesc", null);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .contains("user-nodesc");
    }

    @Test
    @DisplayName("builtinPlugin 技能无显式描述 → 进模型可调用清单（CC builtinPlugins.ts:149-150 loadedFrom='bundled' allowlist）")
    void builtinPluginSkill_withoutDescription_included(@TempDir Path tempDir) throws Exception {
        // P2-21 语义修正：BuiltinPluginRegistry 映射 CC builtinPlugins.ts —— 其技能 source='bundled' +
        // loadedFrom='bundled'（builtinPlugins.ts:149-150），getSkillToolCommands allowlist
        // （commands.ts:574 loadedFrom==='bundled'）自动放行，无需显式描述。旧 Java 误标 source=PLUGIN
        // 使其被排除（P1-9 断言即据此），改后对齐 CC。
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registerPluginSkill(registry, new BuiltinPluginRegistry.SkillDefinition(
            "plugin-cmd", "plugin desc", List.of(), null, null, false, true, "prompt text"));

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .contains("plugin-cmd");
    }

    @Test
    @DisplayName("P2-15: builtinPlugin skillToCommand 字段契约（hasUserSpecifiedDescription/isHidden/progressMessage/model/hooks/context/agent/isEnabled，CC builtinPlugins.ts:132-159）")
    void builtinPluginSkill_fullFieldContract(@TempDir Path tempDir) throws Exception {
        // WHY: DRF-PC-4 — CC skillDefinitionToCommand 置 hasUserSpecifiedDescription:true（:137）、
        //   model（:144）、isHidden:!(userInvocable??true)（:161）、progressMessage:'running'（:162）、
        //   hooks/context/agent/isEnabled（:157-160）。Java toCommand 若漏映射，消费方
        //   formatDescriptionWithSource/skill 管理读到缺省 null（字段契约缺失）。
        // isEnabled 用翻转 supplier：首次（getAllCommands 过滤）求值 true 使命令可见，二次求值 false
        //   证明 supplier 被惰性携带（CC :160 definition.isEnabled 直传函数，每次调用新鲜求值）。
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        java.util.concurrent.atomic.AtomicInteger gate = new java.util.concurrent.atomic.AtomicInteger();
        registerPluginSkill(registry, new BuiltinPluginRegistry.SkillDefinition(
            "plugin-full", "plugin desc", List.of("Bash"), "hint", "when needed", false, true,
            "prompt text", "claude-3-5", "{\"PreToolUse\":\"cmd\"}", "fork", "agent-x",
            () -> gate.incrementAndGet() == 1));

        Command c = registry.findCommandIncludingMcp("plugin-full");
        assertThat(c).isNotNull();
        assertThat(c.getHasUserSpecifiedDescription())
            .as("P2-15: hasUserSpecifiedDescription=true（CC builtinPlugins.ts:137）")
            .isTrue();
        assertThat(c.getModel())
            .as("P2-15: model 透传（CC :144 definition.model）")
            .isEqualTo("claude-3-5");
        assertThat(c.getIsHidden())
            .as("P2-15: isHidden=!(userInvocable??true)（CC :161）")
            .isFalse();
        assertThat(c.getProgressMessage())
            .as("P2-15: progressMessage='running'（CC :162）")
            .isEqualTo("running");
        assertThat(c.getHooks())
            .as("P2-15: hooks 透传（CC :157 definition.hooks）")
            .isEqualTo("{\"PreToolUse\":\"cmd\"}");
        assertThat(c.getContext())
            .as("P2-15: context 透传（CC :158 definition.context）")
            .isEqualTo("fork");
        assertThat(c.getAgent())
            .as("P2-15: agent 透传（CC :159 definition.agent）")
            .isEqualTo("agent-x");
        assertThat(c.getIsEnabled())
            .as("P2-15: isEnabled supplier 被携带（CC :160 definition.isEnabled 直传）")
            .isNotNull();
        // 二次求值 → false：supplier 惰性 + 新鲜求值（CC :160 + commands.ts:478 注释）
        assertThat(c.isCommandEnabled())
            .as("P2-15: isEnabled 惰性 supplier 新鲜求值（CC :160）")
            .isFalse();
    }

    @Test
    @DisplayName("marketplace plugin-loaded 命令（source=PLUGIN + loadedFrom=PLUGIN）无显式描述且无 whenToUse → 排除（CC :571-573）")
    void pluginLoadedCommand_withoutDescriptionOrWhenToUse_excluded(@TempDir Path tempDir) throws Exception {
        // P1-9 意图保留：CC 「Plugin/MCP commands still require an explicit description to appear in the
        // listing」（commands.ts:571-573）针对 loadedFrom='plugin' 命令（marketplace 插件）—— loadedFrom
        // ∉ {bundled,skills,commands_DEPRECATED}，须 hasUserSpecifiedDescription/whenToUse 才进清单。
        Command plugin = new Command();
        plugin.setName("plugin-loaded-cmd");
        plugin.setDescription("plugin desc");
        plugin.setSource(CommandSource.PLUGIN);       // CC command.ts:32 source: 'plugin'
        plugin.setLoadedFrom(CommandLoadedFrom.PLUGIN); // CC loadedFrom: 'plugin'（loadSkillsDir.ts:70）
        SkillRegistry registry = new FixtureRegistry(List.of(plugin));

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .doesNotContain("plugin-loaded-cmd");
    }

    @Test
    @DisplayName("marketplace plugin-loaded 命令带 whenToUse → 进模型可调用清单（CC :574-578 hasUserSpecifiedDescription||whenToUse）")
    void pluginLoadedCommand_withWhenToUse_included(@TempDir Path tempDir) throws Exception {
        Command plugin = new Command();
        plugin.setName("plugin-when");
        plugin.setDescription("plugin desc");
        plugin.setWhenToUse("当用户请求 X 时使用");
        plugin.setSource(CommandSource.PLUGIN);
        plugin.setLoadedFrom(CommandLoadedFrom.PLUGIN);
        SkillRegistry registry = new FixtureRegistry(List.of(plugin));

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .contains("plugin-when");
    }

    @Test
    @DisplayName("marketplace plugin-loaded 命令 hasUserSpecifiedDescription=true → 进模型可调用清单（CC :577）")
    void pluginLoadedCommand_hasUserSpecifiedDescription_included(@TempDir Path tempDir) throws Exception {
        Command plugin = new Command();
        plugin.setName("plugin-hud");
        plugin.setDescription("plugin desc");
        plugin.setSource(CommandSource.PLUGIN);
        plugin.setLoadedFrom(CommandLoadedFrom.PLUGIN);
        plugin.setHasUserSpecifiedDescription(Boolean.TRUE);
        SkillRegistry registry = new FixtureRegistry(List.of(plugin));

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .contains("plugin-hud");
    }

    /** 覆写 getAllCommands 直接喂入任意 loadedFrom 命令（隔离源码判别，对齐 SlashCommandToolSkillsTest.FixtureRegistry） */
    private static final class FixtureRegistry extends SkillRegistry {
        private final List<Command> cmds;

        FixtureRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }

    @Test
    @DisplayName("P2-9 分离 + thread-in：本地视图不含 MCP，listing 视图含 MCP（CC attachments.ts:2680-2682）")
    void mcpCommand_separatedLocal_excludedFromLocal_includedInListing(@TempDir Path tempDir) throws Exception {
        Command mcp = new Command();
        mcp.setName("mcp-cmd");
        mcp.setDescription("mcp desc");
        mcp.setSource(CommandSource.MCP);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(new StaticMcpServerService(List.of(mcp)));

        // 分离：getModelInvocableCommands（纯本地，对齐 CC getSkillToolCommands commands.ts:563-581）
        // 从数据源上不含 MCP（MCP live outside getCommands commands.ts:541-546）
        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .doesNotContain("mcp-cmd");
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("mcp-cmd");
        // thread-in：getModelInvocableCommandsForListing 合并本地 + MCP（对齐 CC attachments.ts:2677-2682
        // localCommands=getSkillToolCommands + mcpSkills=getMcpSkillCommands → uniqBy name）→ MCP 技能进 listing
        assertThat(registry.getModelInvocableCommandsForListing())
            .extracting(Command::getName)
            .contains("mcp-cmd");
    }

    @Test
    @DisplayName("disableModelInvocation=true → 排除（CC commands.ts:569）")
    void disableModelInvocation_true_excluded(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-disabled", "skill-disabled", "disable-model-invocation: true");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.getModelInvocableCommands())
            .extracting(Command::getName)
            .doesNotContain("skill-disabled");
    }

    /** 静态 MCP 命令注入（隔离 MCP 源验证）· McpServerService 为具体类，子类覆写即可。 */
    private static final class StaticMcpServerService extends McpServerService {
        private final List<Command> commands;

        StaticMcpServerService(List<Command> commands) {
            this.commands = commands;
        }

        @Override
        public List<Command> getMcpSkillCommands() {
            return commands;
        }
    }
}
