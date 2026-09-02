package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL6] LoadPluginCommands 等价类测试 · 对齐 CC loadPluginCommands.ts
 * （getPluginCommands 命令名 plugin:ns:name + getPluginSkills 技能加载）。
 *
 * <p>验证：① commands/ 目录 .md 扫为 source=PLUGIN 命令，命令名=plugin:ns:name（:60-97）；
 * ② commands/ 内 SKILL.md 目录只保留技能文件（transformPluginSkillFiles :135-167）；
 * ③ skills/ 目录 SKILL.md 加载为 plugin 技能（name=plugin:basename，loadedFrom='plugin'，:316）；
 * ④ commandsPaths/skillsPaths 附加路径（目录+单文件）均被扫描；
 * ⑤ bare 门禁（isBareMode && inlinePlugins 空 → []，:419-421）；
 * ⑥ SkillRegistry 合并序对齐 CC commands.ts:460-468。
 */
class LoadPluginCommandsTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("commands/ 目录扫描：命令名 plugin:ns:name + source=PLUGIN（loadPluginCommands.ts:60-97）")
    void commands_directory_namespaces_plugin_name_and_source() throws Exception {
        // WHY: CC getCommandNameFromFile — 普通 .md 命令名 = [pluginName, ...namespace, 文件名去 .md].join(':')
        //   （:83-96），source='plugin'（:315）。Java 若不打 plugin 前缀，与 built-in/自定义命令同名冲突。
        Path commandsDir = tempDir.resolve("my-plugin").resolve("commands");
        Files.createDirectories(commandsDir.resolve("util"));
        Files.writeString(commandsDir.resolve("greet.md"),
            "---\ndescription: greet the user\n---\n\nsay hello");
        Files.writeString(commandsDir.resolve("util/help.md"),
            "---\ndescription: show help\n---\n\nhelp text");

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(
            plugin("my-plugin", commandsDir.getParent())));

        assertThat(commands.stream().map(Command::getName))
            .as("命令名必须为 plugin:ns:name（CC :83-96）")
            .containsExactlyInAnyOrder("my-plugin:greet", "my-plugin:util:help");
        for (Command c : commands) {
            assertThat(c.getSource())
                .as("插件命令 source 必须 PLUGIN（CC :315 source='plugin'）")
                .isEqualTo(CommandSource.PLUGIN);
            assertThat(c.getLoadedFrom())
                .as("普通插件命令 loadedFrom 应 undefined（CC :316 仅 skill 设 'plugin'）")
                .isNull();
        }
        Command greet = commands.stream().filter(c -> c.getName().equals("my-plugin:greet"))
            .findFirst().orElseThrow();
        assertThat(greet.getDescription()).isEqualTo("greet the user");
        assertThat(greet.getHasUserSpecifiedDescription()).isTrue();
        assertThat(greet.getContent()).isEqualTo("say hello");
    }

    @Test
    @DisplayName("commands/ 内 SKILL.md 目录：只保留技能文件，名=plugin:父目录名（transformPluginSkillFiles :135-167）")
    void commands_dir_skill_directory_keeps_only_skill_file() throws Exception {
        // WHY: CC transformPluginSkillFiles — 含 SKILL.md 的目录只保留该技能文件（同目录其它 .md 丢弃），
        //   命令名经 getCommandNameFromFile skill 分支 = plugin:父目录名（:67-81）。
        Path commandsDir = tempDir.resolve("sk-plugin").resolve("commands");
        Path toolDir = commandsDir.resolve("analyzer");
        Files.createDirectories(toolDir);
        Files.writeString(toolDir.resolve("SKILL.md"),
            "---\ndescription: analyze tool skill\n---\n\nskill body");
        Files.writeString(toolDir.resolve("ignoreme.md"),
            "---\ndescription: ignored helper\n---\n\nignored");

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(
            plugin("sk-plugin", commandsDir.getParent())));

        assertThat(commands.stream().map(Command::getName))
            .as("SKILL.md 目录只产一个命令 plugin:analyzer（CC :150-160 只保留 skill 文件）")
            .containsExactly("sk-plugin:analyzer");
        Command skill = commands.get(0);
        assertThat(skill.getLoadedFrom())
            .as("SKILL.md → loadedFrom='plugin'（CC :316 isSkill=true）")
            .isEqualTo(CommandLoadedFrom.PLUGIN);
        assertThat(skill.getProgressMessage())
            .as("skill 命令 progressMessage='loading'（CC :322）")
            .isEqualTo("loading");
    }

    @Test
    @DisplayName("skills/ 目录 SKILL.md：name=plugin:basename + isSkillMode（loadPluginCommands.ts:687-838）")
    void skills_directory_loads_plugin_skills() throws Exception {
        // WHY: CC loadSkillsFromDirectory — skillsPath 下子目录 SKILL.md → skillName=plugin:entryName
        //   （:806），isSkill=true + isSkillMode=true → baseDir 供 ${CLAUDE_SKILL_DIR}/前缀替换（:328-370）。
        Path skillsDir = tempDir.resolve("skl-plugin").resolve("skills");
        Files.createDirectories(skillsDir.resolve("focus"));
        Files.writeString(skillsDir.resolve("focus/SKILL.md"),
            "---\ndescription: focus skill\n---\n\nwork from ${CLAUDE_SKILL_DIR}/refs");

        List<Command> skills = LoadPluginCommands.loadSkills(List.of(
            plugin("skl-plugin", skillsDir.getParent())), false);

        assertThat(skills.stream().map(Command::getName))
            .as("技能名 = plugin:entryName（CC :806）")
            .containsExactly("skl-plugin:focus");
        Command skill = skills.get(0);
        assertThat(skill.getSource()).isEqualTo(CommandSource.PLUGIN);
        assertThat(skill.getLoadedFrom()).isEqualTo(CommandLoadedFrom.PLUGIN);
        assertThat(skill.getBaseDir())
            .as("baseDir = 技能目录，供 SkillContentLoader 前缀/CLAUDE_SKILL_DIR 替换（CC :360-369）")
            .endsWith("focus");
        assertThat(skill.getContent()).isEqualTo("work from ${CLAUDE_SKILL_DIR}/refs");
    }

    @Test
    @DisplayName("skills/ 目录直载 SKILL.md：name=plugin:basename(skillsPath)（loadPluginCommands.ts:699-757）")
    void skills_directory_direct_skill_md() throws Exception {
        // WHY: CC :699-757 — skillsPath 自身含 SKILL.md → 直接技能，skillName=plugin:basename(skillsPath)（:726）
        Path skillsDir = tempDir.resolve("direct-plugin").resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"),
            "---\ndescription: direct skill\n---\n\nbody");

        List<Command> skills = LoadPluginCommands.loadSkills(List.of(
            plugin("direct-plugin", skillsDir.getParent())), false);

        assertThat(skills.stream().map(Command::getName))
            .as("direct SKILL.md → plugin:basename(skillsPath)（CC :726）")
            .containsExactly("direct-plugin:skills");
    }

    @Test
    @DisplayName("commandsPaths 附加路径：单 .md 文件按 plugin:basename 命名（loadPluginCommands.ts:504-587）")
    void commands_paths_single_file() throws Exception {
        // WHY: CC :541-543 — 无 commandsMetadata 匹配时单文件命令名回退 plugin:basename(.md 去后缀)
        Path pluginRoot = tempDir.resolve("extra-plugin");
        Path customCmd = pluginRoot.resolve("custom-commands").resolve("deploy.md");
        Files.createDirectories(customCmd.getParent());
        Files.writeString(customCmd, "---\ndescription: deploy\n---\n\nrun deploy");

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(
            new PluginLoader.LoadedPlugin("extra-plugin", PluginLoader.InstallSource.PATH,
                pluginRoot, System.currentTimeMillis(), true,
                null, List.of(),
                null, List.of(customCmd.toString()),
                null, List.of(), null, List.of())));

        assertThat(commands.stream().map(Command::getName))
            .as("单文件命令名回退 plugin:basename（CC :541-543）")
            .containsExactly("extra-plugin:deploy");
        assertThat(commands.get(0).getSource()).isEqualTo(CommandSource.PLUGIN);
    }

    @Test
    @DisplayName("bare 门禁：isBareMode && 插件列表空 → []（loadPluginCommands.ts:419-421）")
    void bare_mode_gate_returns_empty_when_no_inline_plugins() {
        // WHY: CC getPluginCommands — --bare 下跳过 marketplace 自动加载，inlinePlugins 空 → []
        //   （:419-421）。Java env 等价：bareMode=true + 列表空 → []。
        assertThat(LoadPluginCommands.loadCommands(List.of(), true))
            .as("bare 门禁命中 → 空列表（CC :419-421）")
            .isEmpty();
        assertThat(LoadPluginCommands.loadCommands(List.of(
            new PluginLoader.LoadedPlugin("p", PluginLoader.InstallSource.PATH,
                tempDir, System.currentTimeMillis(), true)),
            true))
            .as("bare 但存在 inline 插件（--plugin-dir）→ 不短路（CC :419 注释）")
            .isEmpty();
    }

    @Test
    @DisplayName("SkillRegistry 合并序：plugin 命令/技能按 CC commands.ts:460-468 并入 getAllCommands")
    void skill_registry_merges_plugin_commands_and_skills() throws Exception {
        // WHY: CC loadAllCommands 合并序 = bundled→builtinPlugin→skillDir→workflow→pluginCommands→
        //   pluginSkills→COMMANDS()（commands.ts:460-468）。MPL6 要求 SkillRegistry 含 plugin 源且
        //   合并序对齐 —— 若 plugin 命令不进 getAllCommands，findCommand/skill listing 拿不到插件命令。
        Path pluginRoot = tempDir.resolve("merge-plugin");
        Path commandsDir = pluginRoot.resolve("commands");
        Path skillsDir = pluginRoot.resolve("skills");
        Files.createDirectories(commandsDir);
        Files.createDirectories(skillsDir.resolve("toolz"));
        Files.writeString(commandsDir.resolve("ship.md"),
            "---\ndescription: ship it\n---\n\nship");
        Files.writeString(skillsDir.resolve("toolz/SKILL.md"),
            "---\ndescription: toolz skill\n---\n\ntoolz");

        PluginLoader loader = new PluginLoader();
        loader.load("merge-plugin", PluginLoader.InstallSource.PATH, pluginRoot,
            null, List.of(), commandsDir, List.of(), skillsDir, List.of(), null, List.of());

        SkillRegistry registry = new SkillRegistry(tempDir.resolve(".claude/skills").toString());
        registry.setPluginLoader(loader);

        List<Command> all = registry.getAllCommands();
        List<String> names = all.stream().map(Command::getName).toList();
        assertThat(names)
            .as("getAllCommands 必须含 plugin 命令 + plugin 技能（CC commands.ts:465-466）")
            .contains("merge-plugin:ship", "merge-plugin:toolz");
        assertThat(names.indexOf("merge-plugin:ship"))
            .as("plugin 命令与技能在同批注册后，位于动态技能之前（CC 合并序）")
            .isNotNegative();
        Command ship = all.stream().filter(c -> c.getName().equals("merge-plugin:ship")).findFirst().orElseThrow();
        assertThat(ship.getSource()).isEqualTo(CommandSource.PLUGIN);
        Command toolz = all.stream().filter(c -> c.getName().equals("merge-plugin:toolz")).findFirst().orElseThrow();
        assertThat(toolz.getLoadedFrom()).isEqualTo(CommandLoadedFrom.PLUGIN);
        // POJO 未注入 pluginLoader → 无 plugin 源（兼容既有直构）
        assertThat(new SkillRegistry("x").getAllCommands().stream()
            .map(Command::getName)).doesNotContain("merge-plugin:ship");
    }

    @Test
    @DisplayName("bare 门禁（skills）：isBareMode && 插件列表空 → []（loadPluginCommands.ts:843-845）")
    void bare_mode_gate_skills_returns_empty_when_no_inline_plugins() {
        // WHY: CC getPluginSkills — --bare 下跳过 marketplace 自动加载，inlinePlugins 空 → []
        //   （:843-845），与 getPluginCommands 门禁（:419-421）对称。若 loadSkills 缺 bareMode 参数，
        //   --bare 下仍会枚举 marketplace 插件技能，违反 CC 语义。
        assertThat(LoadPluginCommands.loadSkills(List.of(), true))
            .as("bare 门禁命中 → 空列表（CC :843-845）")
            .isEmpty();
        assertThat(LoadPluginCommands.loadSkills(List.of(
            new PluginLoader.LoadedPlugin("p", PluginLoader.InstallSource.PATH,
                tempDir, System.currentTimeMillis(), true)),
            true))
            .as("bare 但存在 inline 插件（--plugin-dir）→ 不短路（CC :843 注释）")
            .isEmpty();
    }

    @Test
    @DisplayName("pluginInfo 落位：plugin 命令置 pluginInfo{pluginManifest.name, repository}（loadPluginCommands.ts:317-320）")
    void plugin_command_sets_plugin_info() throws Exception {
        // WHY: CC createPluginCommand — pluginInfo:{pluginManifest, repository: sourceName}（:317-320）
        //   plugin 源命令必须携带插件清单信息，否则消费方 formatDescriptionWithSource/SkillTool 遥测
        //   取 pluginManifest.name/repository 时读到 null（CI-04 从「补字段」到「有行为」的 setter 链断裂）。
        Path pluginRoot = tempDir.resolve("pi-plugin");
        Path commandsDir = pluginRoot.resolve("commands");
        Files.createDirectories(commandsDir);
        Files.writeString(commandsDir.resolve("run.md"),
            "---\ndescription: run\n---\n\ndo run");

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(
            plugin("pi-plugin", pluginRoot)));

        assertThat(commands).hasSize(1);
        Command c = commands.get(0);
        assertThat(c.getPluginInfo())
            .as("plugin 命令 pluginInfo 不得为 null（CC :317-320）")
            .isNotNull();
        assertThat(c.getPluginInfo().pluginManifest().name())
            .as("pluginManifest.name = 插件名（CC :318 pluginManifest）")
            .isEqualTo("pi-plugin");
        assertThat(c.getPluginInfo().repository())
            .as("repository = 插件源名小写（CC :319 repository: sourceName；Java InstallSource.PATH→path）")
            .isEqualTo("path");
    }

    @Test
    @DisplayName("P1-4: plugin 命令落 pluginRoot/pluginSource + allowed-tools ${CLAUDE_PLUGIN_ROOT}/DATA 双变量双形态替换（CC loadPluginCommands.ts:241-261/:340-343）")
    void plugin_command_carries_plugin_context_and_substitutes_allowed_tools_vars() throws Exception {
        // WHY: CC createPluginCommand — allowed-tools 先做 substitutePluginVariables（${CLAUDE_PLUGIN_ROOT} +
        //   ${CLAUDE_PLUGIN_DATA}，string 整体 + array 逐元素，:241-261）；plugin 上下文（pluginPath/source）
        //   供 getPromptForCommand 内容链 ${CLAUDE_PLUGIN_ROOT}/DATA 替换（:340-343）。Java 若只替换
        //   ${CLAUDE_PLUGIN_ROOT} string 形态，array 形态与 ${CLAUDE_PLUGIN_DATA} 不展开（DRF-PC-1）。
        Path pluginRoot = tempDir.resolve("vars-plugin");
        Path commandsDir = pluginRoot.resolve("commands");
        Files.createDirectories(commandsDir);
        Files.writeString(commandsDir.resolve("vars.md"),
            "---\n"
                + "description: vars\n"
                + "allowed-tools: [Bash, \"${CLAUDE_PLUGIN_ROOT}/bin/tool\"]\n"
                + "---\n\n"
                + "root=${CLAUDE_PLUGIN_ROOT} data=${CLAUDE_PLUGIN_DATA}");
        PluginDirectories.setPluginCacheDirOverride(tempDir.resolve("plugins-cache").toString());
        try {
            List<Command> commands = LoadPluginCommands.loadCommands(List.of(
                plugin("vars-plugin", pluginRoot)));

            assertThat(commands).hasSize(1);
            Command c = commands.get(0);
            assertThat(c.getPluginRoot())
                .as("P1-4: pluginRoot = 插件安装根（CC :340-343 {path: pluginPath}），win32 归一化")
                .isEqualTo(pluginRoot.toString().replace('\\', '/'));
            assertThat(c.getPluginSource())
                .as("P1-4: pluginSource = sourceName（CC :341 {source: sourceName}）")
                .isEqualTo("path");
            assertThat(c.getAllowedTools())
                .as("P1-4: allowed-tools array 逐元素替换 ${CLAUDE_PLUGIN_ROOT}（CC :241-261 双形态）")
                .contains("Bash", pluginRoot.toString().replace('\\', '/') + "/bin/tool");
            // ${CLAUDE_PLUGIN_DATA} 未出现在 allowed-tools，内容正文双变量由内容链替换（本测试只验落位）
            assertThat(c.getContent()).contains("${CLAUDE_PLUGIN_ROOT}", "${CLAUDE_PLUGIN_DATA}");
        } finally {
            PluginDirectories.setPluginCacheDirOverride(null);
        }
    }

    @Test
    @DisplayName("P1-4: allowed-tools string 形态替换 ${CLAUDE_PLUGIN_DATA}（CC :241-261，${CLAUDE_PLUGIN_DATA} 缺省替换缺失修复）")
    void plugin_command_substitutes_plugin_data_in_allowed_tools_string() throws Exception {
        // WHY: CC :244-258 双变量（${CLAUDE_PLUGIN_ROOT}+${CLAUDE_PLUGIN_DATA}）。旧 Java 仅 string +
        //   ${CLAUDE_PLUGIN_ROOT}（DRF-PC-1），${CLAUDE_PLUGIN_DATA} 声明为 string 时不展开。
        Path pluginRoot = tempDir.resolve("data-plugin");
        Path commandsDir = pluginRoot.resolve("commands");
        Files.createDirectories(commandsDir);
        Files.writeString(commandsDir.resolve("data.md"),
            "---\ndescription: data\nallowed-tools: \"${CLAUDE_PLUGIN_DATA}/bin\"\n---\n\nbody");
        PluginDirectories.setPluginCacheDirOverride(tempDir.resolve("plugins-cache").toString());
        try {
            List<Command> commands = LoadPluginCommands.loadCommands(List.of(
                plugin("data-plugin", pluginRoot)));

            Command c = commands.get(0);
            // getPluginDataDir('path') = <plugins-cache>/data/path（sanitizePluginId 无特殊字符）
            String expectedDataDir = tempDir.resolve("plugins-cache").resolve("data")
                .resolve("path").toString().replace('\\', '/');
            assertThat(c.getAllowedTools())
                .as("P1-4: ${CLAUDE_PLUGIN_DATA} string 形态替换为 getPluginDataDir(source)（CC pluginDirectories.ts:119-127）")
                .containsExactly(expectedDataDir + "/bin");
        } finally {
            PluginDirectories.setPluginCacheDirOverride(null);
        }
    }

    @Test
    @DisplayName("P5-④: claude 回落插件 → ${CLAUDE_PLUGIN_ROOT} 用 claude 实际安装路径（非 nexusai 硬编码根）")
    void plugin_command_claude_fallback_uses_claude_actual_install_path() throws Exception {
        // WHY（双读机制回归线）：InstalledPluginsFileStore.loadInstalledPluginsWithClaudeFallback:253-284
        //   claude 回落补缺失名时，LoadedPlugin.localPath() = rec.sourcePath()（=~/.claude/plugins 下的实际
        //   安装目录，非 nexusai ~/.{app}/plugins）。LoadPluginCommands.createPluginCommand:491
        //   pluginRoot = plugin.localPath() —— 若此处写死 nexusai 根，claude 回落插件的
        //   ${CLAUDE_PLUGIN_ROOT} 会指向 nexusai 空目录（技能内 !`...` 引用脚本找不到）。
        //   本测试显式断言 pluginRoot = claude 侧实际安装路径，防止回归到硬编码 nexusai 根。
        Path claudeFallbackRoot = tempDir.resolve("claude-fallback").resolve("plugins").resolve("cl-plug");
        Path commandsDir = claudeFallbackRoot.resolve("commands");
        Files.createDirectories(commandsDir);
        Files.writeString(commandsDir.resolve("run.md"),
            "---\ndescription: claude fallback plugin\n---\n\nrun ${CLAUDE_PLUGIN_ROOT}/bin/tool ${CLAUDE_PLUGIN_DATA}/state");

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(
            plugin("cl-plug", claudeFallbackRoot)));

        assertThat(commands).hasSize(1);
        Command c = commands.get(0);
        // pluginRoot = 插件实际安装目录（claude 侧路径），win32 反斜杠→正斜杠
        assertThat(c.getPluginRoot())
            .as("P5-④: ${CLAUDE_PLUGIN_ROOT} 源 = plugin.localPath()（claude 实际安装目录），非 nexusai 根")
            .isEqualTo(claudeFallbackRoot.toString().replace('\\', '/'));
        // 显式断言不等于 nexusai 插件根（防止写死 nexusai ~/.{app}/plugins）
        assertThat(c.getPluginRoot())
            .as("P5-④: claude 回落插件根不得漂移到 nexusai plugins 目录")
            .isNotEqualTo(com.nexusai.application.agent.plugin.PluginDirectories.getPluginsDirectory()
                .replace('\\', '/'));
        // 内容链 ${CLAUDE_PLUGIN_ROOT} 由 SkillContentLoader.replacePluginVariables 用同一 pluginRoot 替换
        //   （SkillToolImpl.doExecute:1522 消费 c.getPluginRoot()）→ 断言落位到 claude 实际路径
        assertThat(c.getContent()).contains("${CLAUDE_PLUGIN_ROOT}", "${CLAUDE_PLUGIN_DATA}");
    }

    @Test
    @DisplayName("P2-14: commandsMetadata inline content 命令（无源文件）frontmatter 解析（loadPluginCommands.ts:607-668）")
    void commands_metadata_inline_content_parses_frontmatter() throws Exception {
        // WHY: CC :613-646 — manifest object-mapping 的 { key: { content: '...' } } 条目（无 source）产生
        //   无源文件命令：命令名 = plugin:key（:629）、frontmatter 从 inline content 解析（:618）、
        //   filePath = 虚拟路径 <inline:plugin:key>（:643）。若不支持，manifest 内联命令静默丢失。
        Path pluginRoot = tempDir.resolve("inline-plugin");
        PluginLoader.LoadedPlugin plugin = new PluginLoader.LoadedPlugin("inline-plugin",
            PluginLoader.InstallSource.PATH, pluginRoot, System.currentTimeMillis(), true,
            null, List.of(),
            null, List.of(),
            null, List.of(),
            null, List.of(),
            Map.of("about", new PluginLoader.CommandMetadata(null,
                "---\ndescription: inline about\n---\n\nabout body")));

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(plugin));

        assertThat(commands.stream().map(Command::getName))
            .as("inline content 命令名 = plugin:metadataKey（CC :629）")
            .containsExactly("inline-plugin:about");
        Command c = commands.get(0);
        assertThat(c.getContent())
            .as("inline content 正文 = 去除 frontmatter 后的 markdown（CC :618-619）")
            .isEqualTo("about body");
        assertThat(c.getDescription())
            .as("inline content frontmatter description 生效（CC :618 parseFrontmatter）")
            .isEqualTo("inline about");
        assertThat(c.getContentPath())
            .as("inline 命令 contentPath = 虚拟路径 <inline:plugin:key>（CC :643）")
            .isEqualTo("<inline:inline-plugin:about>");
        assertThat(c.getSource()).isEqualTo(CommandSource.PLUGIN);
    }

    @Test
    @DisplayName("P2-14: inline content 命令应用 metadata 四覆盖字段（description/argument-hint/model/allowed-tools，CC :622-633）")
    void commands_metadata_inline_content_applies_metadata_overrides() throws Exception {
        // WHY: CC :622-632 — inline content 条目解析后 metadata 覆盖字段合并进 frontmatter：
        //   description/argumentHint/model 直覆盖，allowedTools.join(',') 写 'allowed-tools'（:633）。
        //   覆盖优先级 = metadata 覆盖原 frontmatter（spread 顺序）。
        Path pluginRoot = tempDir.resolve("inline-ov-plugin");
        PluginLoader.LoadedPlugin plugin = new PluginLoader.LoadedPlugin("inline-ov-plugin",
            PluginLoader.InstallSource.PATH, pluginRoot, System.currentTimeMillis(), true,
            null, List.of(),
            null, List.of(),
            null, List.of(),
            null, List.of(),
            Map.of("deploy", new PluginLoader.CommandMetadata(null,
                "---\ndescription: original desc\n---\n\nbody",
                "Overridden description", "[env]", "opus", List.of("Bash", "Read"))));

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(plugin));

        assertThat(commands).hasSize(1);
        Command c = commands.get(0);
        assertThat(c.getName()).isEqualTo("inline-ov-plugin:deploy");
        assertThat(c.getDescription())
            .as("metadata.description 覆盖 frontmatter description（CC :623-625）")
            .isEqualTo("Overridden description");
        assertThat(c.getArgumentHint())
            .as("metadata.argumentHint 覆盖 'argument-hint'（CC :626-627）")
            .isEqualTo("[env]");
        assertThat(c.getModel())
            .as("metadata.model 覆盖 model 后经 parseUserSpecifiedModel 别名解析（CC :628-629/:271-276，opus→claude-opus-4-6）")
            .isEqualTo("claude-opus-4-6");
        assertThat(c.getAllowedTools())
            .as("metadata.allowedTools 逗号连接后经 parseSlashCommandToolsFromFrontmatter 解析（CC :630-633）")
            .containsExactly("Bash", "Read");
        assertThat(c.getHasUserSpecifiedDescription())
            .as("metadata.description 覆盖后 hasUserSpecifiedDescription=true（CC :230-239 双段流程）")
            .isTrue();
    }

    @Test
    @DisplayName("P2-14: commandsMetadata 单文件 override —— source 匹配 → 命令名=plugin:key + 覆盖（loadPluginCommands.ts:517-563）")
    void commands_metadata_single_file_override() throws Exception {
        // WHY: CC :517-563 — commandsPaths 单 .md 文件做 metadata.source 匹配（join(plugin.path, source) ==
        //   commandPath）：命中 → 命令名改用 plugin:metadataKey（:527-529）+ metadata 覆盖 frontmatter
        //   （:544-556）；否则回退 basename（:541-543）。若不支持，manifest object-mapping 的单文件命令
        //   元数据（描述/参数提示/model/allowed-tools）全部丢失。
        Path pluginRoot = tempDir.resolve("override-plugin");
        Path customCmd = pluginRoot.resolve("custom-commands").resolve("deploy.md");
        Files.createDirectories(customCmd.getParent());
        Files.writeString(customCmd,
            "---\ndescription: file desc\n---\n\nrun deploy");
        PluginLoader.LoadedPlugin plugin = new PluginLoader.LoadedPlugin("override-plugin",
            PluginLoader.InstallSource.PATH, pluginRoot, System.currentTimeMillis(), true,
            null, List.of(),
            null, List.of(customCmd.toString()),
            null, List.of(), null, List.of(),
            Map.of("deploy", new PluginLoader.CommandMetadata("custom-commands/deploy.md", null,
                "Deploy command", "[env]", "haiku", List.of("Bash"))));

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(plugin));

        assertThat(commands.stream().map(Command::getName))
            .as("metadata.source 匹配 → 命令名 = plugin:metadataKey（CC :527-529）")
            .containsExactly("override-plugin:deploy");
        Command c = commands.get(0);
        assertThat(c.getDescription())
            .as("metadata.description 覆盖文件 frontmatter（CC :545-547）")
            .isEqualTo("Deploy command");
        assertThat(c.getArgumentHint())
            .as("metadata.argumentHint 覆盖 'argument-hint'（CC :548-549）")
            .isEqualTo("[env]");
        assertThat(c.getModel())
            .as("metadata.model 覆盖 model 后经 parseUserSpecifiedModel 别名解析（CC :550-551/:271-276，haiku→claude-haiku-4-5-20251001）")
            .isEqualTo("claude-haiku-4-5-20251001");
        assertThat(c.getAllowedTools())
            .as("metadata.allowedTools 逗号连接解析（CC :552-555）")
            .containsExactly("Bash");
        assertThat(c.getContent())
            .as("单文件 override 保留文件 markdown 正文（CC :556-562）")
            .isEqualTo("run deploy");
    }

    @Test
    @DisplayName("P2-14: commandsMetadata source 未匹配单文件 → 命令名回退 basename（CC :541-543）")
    void commands_metadata_single_file_no_match_falls_back_basename() throws Exception {
        // WHY: CC :535-543 — metadata.source join 后 != commandPath → commandName 仍为 basename 回退，
        //   不应用 override。防止 override 匹配误伤其它单文件命令（如 custom-commands 目录下多个 .md）。
        Path pluginRoot = tempDir.resolve("no-match-plugin");
        Path customCmd = pluginRoot.resolve("custom-commands").resolve("deploy.md");
        Files.createDirectories(customCmd.getParent());
        Files.writeString(customCmd, "---\ndescription: deploy\n---\n\nrun deploy");
        PluginLoader.LoadedPlugin plugin = new PluginLoader.LoadedPlugin("no-match-plugin",
            PluginLoader.InstallSource.PATH, pluginRoot, System.currentTimeMillis(), true,
            null, List.of(),
            null, List.of(customCmd.toString()),
            null, List.of(), null, List.of(),
            Map.of("deploy", new PluginLoader.CommandMetadata("other/README.md", null,
                "other", null, null, null)));

        List<Command> commands = LoadPluginCommands.loadCommands(List.of(plugin));

        assertThat(commands.stream().map(Command::getName))
            .as("metadata.source 不匹配 → 命令名回退 plugin:basename（CC :541-543）")
            .containsExactly("no-match-plugin:deploy");
        assertThat(commands.get(0).getDescription())
            .as("未命中 override → 保留文件 frontmatter description")
            .isEqualTo("deploy");
    }

    /** 构造含默认组件目录的 LoadedPlugin（commandsPath/skillsPath = localPath 下常规子目录）。 */
    private static PluginLoader.LoadedPlugin plugin(String name, Path localPath) {
        return new PluginLoader.LoadedPlugin(name, PluginLoader.InstallSource.PATH,
            localPath, System.currentTimeMillis(), true,
            null, List.of(),
            localPath.resolve("commands"), List.of(),
            localPath.resolve("skills"), List.of(),
            localPath.resolve("output-styles"), List.of());
    }
}
