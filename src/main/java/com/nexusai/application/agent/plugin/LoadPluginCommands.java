package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.skill.SkillFrontmatterFields;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [MPL6] Plugin 命令/技能加载器 · 对齐 CC utils/plugins/loadPluginCommands.ts
 * （{@code getPluginCommands} + {@code getPluginSkills}，memoize 导出符）。
 *
 * <p>CC 真源（loadPluginCommands.ts）：
 * <ul>
 *   <li>{@code getCommandNameFromFile}（:60-97）— 命令名 {@code plugin:ns:name}；SKILL.md 用父目录名，
 *       namespace 取「skill 目录的父目录」相对 baseDir 的子路径（:74-81）；普通 .md 用文件名去 .md（:83-96）</li>
 *   <li>{@code collectMarkdownFiles}（:102-130）+ {@code walkPluginMarkdown}（stopAtSkillDir）
 *       + {@code transformPluginSkillFiles}（:135-167）— 含 SKILL.md 的目录只保留该技能文件</li>
 *   <li>{@code createPluginCommand}（:218-412）— frontmatter 16 字段 + source='plugin'，
 *       loadedFrom = isSkill||isSkillMode ? 'plugin' : undefined（:316），progressMessage loading/running（:322），
 *       isHidden = !userInvocable（:321）</li>
 *   <li>{@code loadSkillsFromDirectory}（:687-838）— skillsPath 自身 SKILL.md 直载（name=plugin:basename）
 *       或子目录 SKILL.md 扫描（name=plugin:entryName）</li>
 *   <li>{@code getPluginCommands} bare 门禁（:414-421）— {@code isBareMode() && inlinePlugins 空 → []}；
 *       commandsMetadata inline/object-mapping（P2-14）：单文件 source 匹配 override（:517-563）+ inline content
 *       命令（:607-668）均实施</li>
 * </ul>
 *
 * <p>生产接线：{@code PluginLoader.loadAllEnabledCommands/loadAllEnabledSkills} 委托本类
 * （CC getPluginCommands/getPluginSkills → loadAllPluginsCacheOnly 产出 enabled plugins 逐插件扫描）。
 */
public final class LoadPluginCommands {

    private static final Logger log = LoggerFactory.getLogger(LoadPluginCommands.class);

    private static final ParseSkillFrontmatter PARSER = new ParseSkillFrontmatter();

    private LoadPluginCommands() {
    }

    /**
     * [MPL6] 扫描全部 enabled plugins 的命令 · 对齐 CC getPluginCommands（loadPluginCommands.ts:414-677）。
     *
     * <p>commandsPath 默认目录扫描 + commandsPaths manifest 附加路径（目录/单 .md）均支持；
     * commandsMetadata（P2-14）：单文件 source 匹配 override（命令名={@code plugin:name} + 四覆盖字段，
     * CC :517-563）+ inline content 命令（无源文件，CC :607-668）；未匹配 metadata 时单文件命令名
     * 回退 {@code plugin:basename}（CC :541-543）。
     *
     * @param enabledPlugins 已启用的插件列表（CC loadAllPluginsCacheOnly → enabled）
     * @return 扫描出的 plugin 命令列表（source='plugin'）
     */
    public static List<Command> loadCommands(List<PluginLoader.LoadedPlugin> enabledPlugins) {
        return loadCommands(enabledPlugins, false);
    }

    /**
     * [MPL6] 带 bare 门禁的命令扫描 · 对齐 CC getPluginCommands bare 分支
     * （loadPluginCommands.ts:419-421 {@code isBareMode() && inlinePlugins.length === 0 → []}）。
     *
     * <p>Java env 等价：bareMode 布尔由调用方注入（env CLAUDE_CODE_BARE 等），inlinePlugins 等价
     * = enabledPlugins 为空（inline 插件即插件列表本身，--bare 无 --plugin-dir 时列表空）。
     *
     * @param enabledPlugins 已启用的插件列表
     * @param bareMode       bare 模式标志（true 且插件列表空 → 返回空列表）
     * @return 扫描出的 plugin 命令列表
     */
    public static List<Command> loadCommands(List<PluginLoader.LoadedPlugin> enabledPlugins, boolean bareMode) {
        if (bareMode && (enabledPlugins == null || enabledPlugins.isEmpty())) {
            if (log.isDebugEnabled()) {
                log.debug("[LoadPluginCommands] bare 门禁命中（isBareMode && inlinePlugins 空）→ 返回 [] (CC loadPluginCommands.ts:419-421)");
            }
            return List.of();
        }
        List<Command> all = new ArrayList<>();
        for (PluginLoader.LoadedPlugin plugin : enabledPlugins) {
            if (plugin == null) {
                continue;
            }
            // 每插件独立 loadedPaths 作用域（CC :435）
            Set<String> loadedPaths = new HashSet<>();
            // [esc-cancel-ccalign] pluginName 用插件展示名（name@marketplace → name，对齐 CC
            //   parsePluginIdentifier(pluginId).name / pluginLoader.ts:1365 manifest.name）——插件技能
            //   命令名 = pluginName:skillName（loadPluginCommands.ts:80-95），前端 /z /zjkycode: 按
            //   pluginName 前缀匹配；完整 pluginId（含 @marketplace）会导致 /zjkycode: 匹配失败。
            String pName = pluginDisplayName(plugin.name());
            if (plugin.commandsPath() != null) {
                try {
                    loadCommandsFromDirectory(plugin.commandsPath(), pName, plugin.localPath(),
                        sourceName(plugin), false, loadedPaths, all);
                } catch (Exception e) {
                    log.warn("[LoadPluginCommands] 插件 {} 默认 commands 目录加载失败: {} (CC :457-462)",
                        plugin.name(), e.getMessage());
                }
            }
            if (plugin.commandsPaths() != null) {
                for (String commandPath : plugin.commandsPaths()) {
                    if (commandPath == null || commandPath.isBlank()) {
                        continue;
                    }
                    loadCommandsPath(commandPath, plugin, loadedPaths, all);
                }
            }
            // P2-14（GAP-PC-3）：commandsMetadata inline content 命令（无源文件）—— 有源文件的条目
            //   已在上方 commandsPaths 循环经 loadCommandsPath 加载（CC loadPluginCommands.ts:504-601），
            //   本循环仅处理 content && !source 条目（CC :607-668）。
            if (plugin.commandsMetadata() != null && !plugin.commandsMetadata().isEmpty()) {
                loadInlineCommands(plugin, all);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[LoadPluginCommands] 总 plugin 命令加载数: {} (CC :675)", all.size());
        }
        return all;
    }

    /**
     * [MPL6] 带 bare 门禁的技能扫描 · 对齐 CC getPluginSkills bare 分支
     * （loadPluginCommands.ts:843-845 {@code isBareMode() && inlinePlugins.length === 0 → []}）。
     *
     * <p>与 {@link #loadCommands(List, boolean)} 同款门禁（CC getPluginCommands/getPluginSkills
     * 两处 bare 门禁语义一致，loadPluginCommands.ts:419-421/:843-845）。
     *
     * @param enabledPlugins 已启用的插件列表
     * @param bareMode       bare 模式标志（true 且插件列表空 → 返回空列表）
     * @return 扫描出的 plugin 技能列表
     */
    public static List<Command> loadSkills(List<PluginLoader.LoadedPlugin> enabledPlugins, boolean bareMode) {
        if (bareMode && (enabledPlugins == null || enabledPlugins.isEmpty())) {
            if (log.isDebugEnabled()) {
                log.debug("[LoadPluginCommands] bare 门禁命中（isBareMode && inlinePlugins 空）→ 返回 [] (CC loadPluginCommands.ts:843-845)");
            }
            return List.of();
        }
        List<Command> all = new ArrayList<>();
        for (PluginLoader.LoadedPlugin plugin : enabledPlugins) {
            if (plugin == null) {
                continue;
            }
            Set<String> loadedPaths = new HashSet<>();
            // [esc-cancel-ccalign] pluginName 用插件展示名（同 loadCommands :94 语义）
            String pName = pluginDisplayName(plugin.name());
            if (plugin.skillsPath() != null) {
                try {
                    loadSkillsFromDirectory(plugin.skillsPath(), pName, plugin.localPath(),
                        sourceName(plugin), loadedPaths, all);
                } catch (Exception e) {
                    log.warn("[LoadPluginCommands] 插件 {} 默认 skills 目录加载失败: {} (CC :888-893)",
                        plugin.name(), e.getMessage());
                }
            }
            if (plugin.skillsPaths() != null) {
                for (String skillPath : plugin.skillsPaths()) {
                    if (skillPath == null || skillPath.isBlank()) {
                        continue;
                    }
                    try {
                        Path p = Path.of(skillPath);
                        if (Files.isDirectory(p)) {
                            loadSkillsFromDirectory(p, pName, plugin.localPath(),
                                sourceName(plugin), loadedPaths, all);
                        } else {
                            log.warn("[LoadPluginCommands] 插件 {} skillsPaths 非目录（CC 仅支持目录）: {}",
                                plugin.name(), skillPath);
                        }
                    } catch (Exception e) {
                        log.warn("[LoadPluginCommands] 插件 {} skillsPaths {} 加载失败: {} (CC :922-928)",
                            plugin.name(), skillPath, e.getMessage());
                    }
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[LoadPluginCommands] 总 plugin 技能加载数: {} (CC :940)", all.size());
        }
        return all;
    }

    /**
     * commandsPaths 单路径：目录 → loadCommandsFromDirectory；单 .md 文件 → 单文件命令（CC :472-601）。
     *
     * <p>P2-14（DRF-PC-3）：单文件命令做 commandsMetadata source 匹配——metadata.source 相对插件根
     * join 后与 commandPath 相等（CC :517-533）→ 命令名改用 {@code plugin:metadataKey}，并应用 metadata
     * 四覆盖字段（description/argument-hint/model/allowed-tools，CC :544-556）；未匹配 → 文件名回退
     * {@code plugin:basename}（CC :541-543）。
     */
    private static void loadCommandsPath(String commandPath, PluginLoader.LoadedPlugin plugin,
                                         Set<String> loadedPaths, List<Command> all) {
        try {
            Path path = Path.of(commandPath);
            String pName = pluginDisplayName(plugin.name());
            if (Files.isDirectory(path)) {
                loadCommandsFromDirectory(path, pName, plugin.localPath(),
                    sourceName(plugin), false, loadedPaths, all);
            } else if (Files.isRegularFile(path) && commandPath.toLowerCase().endsWith(".md")) {
                if (!loadedPaths.add(path.toAbsolutePath().toString())) {
                    return;
                }
                // 单文件 override：metadata.source 匹配（CC :517-533），未匹配回退 basename（:541-543）
                String commandName = pName + ":" + fileNameWithoutMd(path);
                PluginLoader.CommandMetadata metadataOverride = null;
                Map<String, PluginLoader.CommandMetadata> metadata = plugin.commandsMetadata();
                if (metadata != null) {
                    Path normPath = path.toAbsolutePath().normalize();
                    for (Map.Entry<String, PluginLoader.CommandMetadata> e : metadata.entrySet()) {
                        PluginLoader.CommandMetadata m = e.getValue();
                        if (m != null && m.source() != null && !m.source().isBlank()
                                && normPath.equals(plugin.localPath().resolve(m.source()).normalize())) {
                            commandName = pName + ":" + e.getKey();
                            metadataOverride = m;
                            break;
                        }
                    }
                }
                loadCommandFromFile(path, commandName, pName, plugin.localPath(),
                    sourceName(plugin), false, false, metadataOverride, all);
            }
        } catch (Exception e) {
            log.warn("[LoadPluginCommands] 插件 {} commandsPaths {} 加载失败: {} (CC :589-595)",
                plugin.name(), commandPath, e.getMessage());
        }
    }

    /**
     * 插件展示名 · 对齐 CC {@code parsePluginIdentifier(pluginId).name}（pluginLoader.ts:144）——
     * LoadedPlugin.name 承载完整 pluginId（name@marketplace，供 cache/hook 区分 marketplace），
     * 命令/技能 pluginName 前缀取 @ 前段（等价 manifest.name 默认场景），前端 /z /zjkycode: 按
     * pluginName 前缀匹配（CC 插件技能命令名 = pluginName:skillName，loadPluginCommands.ts:80-95）。
     */
    private static String pluginDisplayName(String raw) {
        if (raw == null) {
            return null;
        }
        int at = raw.indexOf('@');
        return at > 0 ? raw.substring(0, at) : raw;
    }

    /**
     * 从目录扫描命令 · 对齐 CC loadCommandsFromDirectory（:169-213）+
     * walkPluginMarkdown（stopAtSkillDir）+ transformPluginSkillFiles（:135-167）。
     *
     * <p>含 SKILL.md 的目录视为技能目录：只保留该 SKILL.md 文件（同目录其它 .md 丢弃，CC
     * transformPluginSkillFiles 语义），命令名经 getCommandNameFromFile（SKILL.md 用父目录名）。
     */
    private static void loadCommandsFromDirectory(Path commandsDir, String pluginName, Path pluginPath,
                                                  String sourceName, boolean isSkillMode,
                                                  Set<String> loadedPaths, List<Command> all) {
        if (!Files.isDirectory(commandsDir)) {
            return;
        }
        // 1. walkPluginMarkdown(stopAtSkillDir=true)：技能目录收集本目录 .md，不递归子目录
        List<Path> files = new ArrayList<>();
        walkPluginMarkdown(commandsDir, files);
        // 2. transformPluginSkillFiles：按目录分组，含 SKILL.md 的目录只保留该技能文件
        Map<Path, List<Path>> byDir = new LinkedHashMap<>();
        for (Path f : files) {
            byDir.computeIfAbsent(f.getParent(), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<Path, List<Path>> e : byDir.entrySet()) {
            List<Path> dirFiles = e.getValue();
            List<Path> skillFiles = dirFiles.stream().filter(LoadPluginCommands::isSkillFile).toList();
            if (!skillFiles.isEmpty()) {
                Path skill = skillFiles.get(0);
                if (skillFiles.size() > 1 && log.isDebugEnabled()) {
                    log.debug("[LoadPluginCommands] 目录 {} 含多个 SKILL.md，采用 {}（CC :154-158）",
                        e.getKey(), skill.getFileName());
                }
                loadCommandFromFile(skill, commandsDir, pluginName, pluginPath,
                    sourceName, true, isSkillMode, loadedPaths, all);
            } else {
                for (Path f : dirFiles) {
                    loadCommandFromFile(f, commandsDir, pluginName, pluginPath,
                        sourceName, false, isSkillMode, loadedPaths, all);
                }
            }
        }
    }

    /**
     * 单命令文件加载 · CC createPluginCommand 前置：parseFrontmatter + getCommandNameFromFile。
     *
     * @param file        命令文件（.md 或 SKILL.md）
     * @param baseDir     命令命名 baseDir（CC loadCommandsFromDirectory baseDir=commandsPath）
     * @param pluginName  插件名（命令名前缀）
     * @param pluginPath  插件安装目录（${CLAUDE_PLUGIN_ROOT} 替换上下文，可为 null）
     * @param isSkill     文件是否 SKILL.md
     * @param isSkillMode 是否经 skills 加载链（isSkillMode=true 时 getPromptForCommand 前缀/
     *                    CLAUDE_SKILL_DIR 替换激活，CC :328-370）
     */
    private static void loadCommandFromFile(Path file, Path baseDir, String pluginName, Path pluginPath,
                                            String sourceName, boolean isSkill, boolean isSkillMode,
                                            Set<String> loadedPaths, List<Command> all) {
        if (!loadedPaths.add(file.toAbsolutePath().toString())) {
            return; // 重复路径跳过（CC isDuplicatePath）
        }
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[LoadPluginCommands] 读取失败: {} (plugin={}): {}", file, pluginName, e.getMessage());
            return;
        }
        Map<String, Object> fm = PARSER.parse(raw);
        String content = PARSER.extractBody(raw);
        String commandName = getCommandNameFromFile(file, baseDir, pluginName);
        Command c = createPluginCommand(commandName, file, fm, content, pluginName, pluginPath,
            sourceName, isSkill, isSkillMode);
        if (c != null) {
            all.add(c);
        }
    }

    /**
     * 加载单命令文件（命令名已定）· 供 commandsPaths 单文件路径使用。
     *
     * <p>P2-14（DRF-PC-3）：{@code metadataOverride} 非空 → frontmatter 解析后应用 commandsMetadata
     * 四覆盖字段（CC :544-556），命令名已由调用方按 metadata key 决定。
     */
    private static void loadCommandFromFile(Path file, String commandName, String pluginName, Path pluginPath,
                                            String sourceName, boolean isSkill, boolean isSkillMode,
                                            PluginLoader.CommandMetadata metadataOverride,
                                            List<Command> all) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[LoadPluginCommands] 读取失败: {} (plugin={}): {}", file, pluginName, e.getMessage());
            return;
        }
        Map<String, Object> fm = PARSER.parse(raw);
        if (metadataOverride != null) {
            fm = applyCommandMetadataOverrides(fm, metadataOverride);
        }
        String content = PARSER.extractBody(raw);
        Command c = createPluginCommand(commandName, file, fm, content, pluginName, pluginPath,
            sourceName, isSkill, isSkillMode);
        if (c != null) {
            all.add(c);
        }
    }

    /**
     * 从技能目录加载技能 · 对齐 CC loadSkillsFromDirectory（loadPluginCommands.ts:687-838）。
     *
     * <p>skillsPath 自身含 SKILL.md → 直接技能（name=plugin:basename(skillsPath)，CC :699-757）；
     * 否则扫描子目录，含 SKILL.md 者逐个加载（name=plugin:entryName，CC :759-835）。
     */
    private static void loadSkillsFromDirectory(Path skillsPath, String pluginName, Path pluginPath,
                                                String sourceName, Set<String> loadedPaths,
                                                List<Command> all) {
        if (!Files.isDirectory(skillsPath)) {
            return;
        }
        Path directSkill = skillsPath.resolve("SKILL.md");
        if (Files.isRegularFile(directSkill)) {
            if (loadedPaths.add(directSkill.toAbsolutePath().toString())) {
                loadSkillFile(directSkill, pluginName, pluginPath, sourceName, all);
            }
            return;
        }
        List<Path> entries;
        try (java.util.stream.Stream<Path> stream = Files.list(skillsPath)) {
            entries = stream.toList();
        } catch (IOException e) {
            log.warn("[LoadPluginCommands] 扫描技能目录失败: {} (plugin={}): {}", skillsPath, pluginName, e.getMessage());
            return;
        }
        for (Path entry : entries) {
            if (!Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                continue;
            }
            Path skillFile = entry.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) {
                continue;
            }
            if (loadedPaths.add(skillFile.toAbsolutePath().toString())) {
                loadSkillFile(skillFile, pluginName, pluginPath, sourceName, all);
            }
        }
    }

    /**
     * 单个技能文件加载 · 对齐 CC loadPluginCommands.ts:720-757（direct）与 :800-835（子目录）。
     *
     * <p>技能名 = {@code plugin:basename(技能目录)}（CC :726/:806），isSkill=true + isSkillMode=true
     * → loadedFrom='plugin'、progressMessage='loading'（CC :316/:322）。
     */
    private static void loadSkillFile(Path skillFile, String pluginName, Path pluginPath, String sourceName,
                                      List<Command> all) {
        String raw;
        try {
            raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[LoadPluginCommands] 读取技能失败: {} (plugin={}): {}", skillFile, pluginName, e.getMessage());
            return;
        }
        Map<String, Object> fm = PARSER.parse(raw);
        String content = PARSER.extractBody(raw);
        Path dir = skillFile.getParent();
        String base = dir != null ? dir.getFileName().toString() : "";
        String skillName = pluginName + ":" + base;
        Command c = createPluginCommand(skillName, skillFile, fm, content, pluginName, pluginPath,
            sourceName, true, true);
        if (c != null) {
            all.add(c);
        }
    }

    /**
     * 创建 plugin Command · 对齐 CC createPluginCommand（loadPluginCommands.ts:218-412）。
     *
     * <p>关键对齐点：
     * <ul>
     *   <li>description 双段流程（:230-239，coerce ?? extractDescriptionFromMarkdown，fallback 标签
     *       'Plugin skill'/'Plugin command'）</li>
     *   <li>allowed-tools 先做 ${CLAUDE_PLUGIN_ROOT} 替换再解析（:241-261）</li>
     *   <li>source='plugin'（:315）；loadedFrom = isSkill||isSkillMode ? 'plugin' : undefined（:316）</li>
     *   <li>isHidden = !userInvocable（:321）；progressMessage = loading/running（:322）</li>
     *   <li>content = 去除 frontmatter 后的 markdown 正文；baseDir = 文件目录（供既有
     *       SkillContentLoader 做 base-dir 前缀 + ${CLAUDE_SKILL_DIR} 替换，CC :328-370 延迟到 prompt 时）</li>
     * </ul>
     */
    private static Command createPluginCommand(String commandName, Path filePath, Map<String, Object> fm,
                                               String content, String pluginName, Path pluginPath,
                                               String sourceName, boolean isSkill, boolean isSkillMode) {
        return createPluginCommand(commandName, filePath.toString(),
            isSkillMode && filePath.getParent() != null
                ? filePath.getParent().toAbsolutePath().toString() : null,
            filePath.toAbsolutePath().toString(),
            fm, content, pluginName, pluginPath, sourceName, isSkill, isSkillMode);
    }

    /**
     * 核心 createPluginCommand · contentPath/baseDir 已解析（P2-14：inline content 走虚拟路径
     * {@code <inline:plugin:name>}，CC loadPluginCommands.ts:643）。
     *
     * @param displayPath 日志展示路径（文件路径或 inline 虚拟路径）
     * @param baseDir     baseDir 字符串（仅 isSkillMode 设置；inline 命令 isSkillMode=false → null）
     * @param contentPath Command.contentPath（inline 为虚拟路径）
     */
    private static Command createPluginCommand(String commandName, String displayPath, String baseDir,
                                               String contentPath, Map<String, Object> fm, String content,
                                               String pluginName, Path pluginPath, String sourceName,
                                               boolean isSkill, boolean isSkillMode) {
        try {
            String validated = ParseSkillFrontmatter.coerceDescriptionToString(
                fm.get("description"), commandName, null);
            Command c = new Command();
            c.setName(commandName);
            c.setDescription(validated != null
                ? validated
                : ParseSkillFrontmatter.extractDescriptionFromMarkdown(
                    content, isSkill ? "Plugin skill" : "Plugin command"));
            c.setHasUserSpecifiedDescription(validated != null);
            c.setSource(CommandSource.PLUGIN);
            if (isSkill || isSkillMode) {
                c.setLoadedFrom(CommandLoadedFrom.PLUGIN); // CC :316
            }
            // CC :317-320 pluginInfo: { pluginManifest, repository: sourceName }——
            // plugin 源命令置插件清单信息（消费方 formatDescriptionWithSource 取 pluginManifest.name、
            // SkillTool 遥测取 name+repository）
            c.setPluginInfo(new Command.PluginInfo(new Command.PluginManifest(pluginName), sourceName));
            // content + 路径。baseDir 仅在 isSkillMode（skills 加载链）设置 —— CC getPromptForCommand
            //   的 base-dir 前缀 + ${CLAUDE_SKILL_DIR} 替换仅在 config.isSkillMode 时激活（:328-370）；
            //   commands/ 目录普通命令（含其内 SKILL.md，isSkillMode=false）不设 baseDir → 无前缀/无替换（对齐 CC）。
            c.setContent(content);
            c.setContentPath(contentPath);
            if (baseDir != null) {
                c.setBaseDir(baseDir);
            }
            c.setProgressMessage(isSkill || isSkillMode ? "loading" : "running"); // CC :322
            // P1-4（DRF-PC-1 + GAP-PC-1）：plugin 上下文落 Command —— ${CLAUDE_PLUGIN_ROOT} 内容替换
            //   需要 plugin.localPath（CC loadPluginCommands.ts:340-343 substitutePluginVariables 的
            //   {path: pluginPath}），${CLAUDE_PLUGIN_DATA} 需要 source（CC getPluginDataDir(source)，
            //   pluginDirectories.ts:119-127）；${user_config.X} 需要 pluginOptionsStorage 选项
            //   （CC :348-354）。Java 侧 plugin 域暂无 pluginOptionsStorage 等价物，userConfig 保持空
            //   map → 未知键保持字面（对齐 CC substituteUserConfigInContent 未知键不抛，:399-402）。
            c.setPluginRoot(pluginPath != null ? pluginPath.toString().replace('\\', '/') : null);
            c.setPluginSource(sourceName);

            // allowed-tools 先替换 ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} 再解析（CC :241-261）
            //   P1-4（DRF-PC-1）：CC 双变量双形态 —— string 整体替换 + array 逐元素替换。
            Map<String, Object> fm2 = fm;
            Object allowedTools = fm.get("allowed-tools");
            if (allowedTools instanceof String s && pluginPath != null
                    && (s.contains("${CLAUDE_PLUGIN_ROOT}") || s.contains("${CLAUDE_PLUGIN_DATA}"))) {
                fm2 = new HashMap<>(fm);
                fm2.put("allowed-tools", substitutePluginVariables(s, pluginPath, sourceName));
            } else if (allowedTools instanceof List<?> list && pluginPath != null) {
                boolean changed = false;
                List<Object> substituted = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof String toolStr
                            && (toolStr.contains("${CLAUDE_PLUGIN_ROOT}") || toolStr.contains("${CLAUDE_PLUGIN_DATA}"))) {
                        substituted.add(substitutePluginVariables(toolStr, pluginPath, sourceName));
                        changed = true;
                    } else {
                        substituted.add(item);
                    }
                }
                if (changed) {
                    fm2 = new HashMap<>(fm);
                    fm2.put("allowed-tools", substituted);
                }
            }
            // 16 字段统一解析（复用 ParseSkillFrontmatter，CC loadSkillsDir.ts:185-265）
            SkillFrontmatterFields parsed = ParseSkillFrontmatter.parseSkillFrontmatterFields(
                fm2, content, commandName, isSkill ? "Plugin skill" : "Plugin command");
            if (parsed.displayName() != null) {
                c.setDisplayName(parsed.displayName());
            }
            c.setAllowedTools(parsed.allowedTools());
            c.setModel(parsed.model());
            c.setContext(parsed.executionContext() != null ? parsed.executionContext() : "inline");
            c.setAgent(parsed.agent());
            c.setVersion(parsed.version());
            c.setArgumentHint(parsed.argumentHint());
            c.setWhenToUse(parsed.whenToUse());
            c.setEffort(parsed.effort());
            c.setHooks(parsed.hooks());
            c.setUserInvocable(parsed.userInvocable());
            c.setDisableModelInvocation(parsed.disableModelInvocation());
            c.setShell(parsed.shell());
            c.setArgNames(parsed.argumentNames().isEmpty() ? null : parsed.argumentNames());
            c.setIsHidden(!Boolean.TRUE.equals(c.getUserInvocable())); // CC :321
            return c;
        } catch (Exception e) {
            log.warn("[LoadPluginCommands] 创建命令失败 {}: {} (CC :403-410 catch → null)",
                displayPath, e.getMessage());
            return null;
        }
    }

    /**
     * 加载 commandsMetadata inline content 命令 · 对齐 CC loadPluginCommands.ts:607-668。
     *
     * <p>仅处理 {@code content && !source} 条目（有 source 的已在 commandsPaths 循环加载）。
     * 命令名={@code plugin:metadataKey}（:629）；frontmatter 从 inline content 解析，虚拟路径
     * {@code <inline:plugin:name>}（:643），filePath=baseDir（inline 无源文件，:644）；应用 metadata
     * 四覆盖字段（:622-632）；isSkillMode=false → baseDir 不设（对齐 CC 命令 isSkillMode 语义）。
     */
    private static void loadInlineCommands(PluginLoader.LoadedPlugin plugin, List<Command> all) {
        Map<String, PluginLoader.CommandMetadata> metadata = plugin.commandsMetadata();
        if (metadata == null) {
            return;
        }
        for (Map.Entry<String, PluginLoader.CommandMetadata> e : metadata.entrySet()) {
            PluginLoader.CommandMetadata m = e.getValue();
            if (m == null || m.content() == null) {
                continue;
            }
            if (m.source() != null && !m.source().isBlank()) {
                continue; // 有源文件条目由 commandsPaths 单文件加载（CC :613 仅处理 !source）
            }
            String pName = pluginDisplayName(plugin.name());
            String commandName = pName + ":" + e.getKey();
            String virtualPath = "<inline:" + commandName + ">";
            try {
                Map<String, Object> fm = applyCommandMetadataOverrides(
                    PARSER.parse(m.content()), m);
                Command c = createPluginCommand(commandName, virtualPath, null, virtualPath,
                    fm, PARSER.extractBody(m.content()),
                    pName, plugin.localPath(), sourceName(plugin), false, false);
                if (c != null) {
                    all.add(c);
                    if (log.isDebugEnabled()) {
                        log.debug("[LoadPluginCommands] 加载 inline content 命令 {}/{}（CC loadPluginCommands.ts:607-668）",
                            plugin.name(), commandName);
                    }
                }
            } catch (Exception ex) {
                log.warn("[LoadPluginCommands] 插件 {} inline 命令 {} 加载失败: {}（CC :664-667 catch → 跳过）",
                    plugin.name(), e.getKey(), ex.getMessage());
            }
        }
    }

    /**
     * 应用命令元数据覆盖到 frontmatter · 对齐 CC loadPluginCommands.ts:544-556 / :622-632。
     *
     * <p>覆盖字段映射：{@code description}→description、{@code argumentHint}→'argument-hint'、
     * {@code model}→model、{@code allowedTools}→'allowed-tools'（逗号连接，CC :552-555/:630-633
     * {@code allowedTools.join(',')}）。仅覆盖非空字段，其余保留原 frontmatter。
     */
    private static Map<String, Object> applyCommandMetadataOverrides(Map<String, Object> fm,
                                                                     PluginLoader.CommandMetadata metadata) {
        if (metadata == null) {
            return fm;
        }
        Map<String, Object> merged = new HashMap<>(fm);
        if (metadata.description() != null) {
            merged.put("description", metadata.description());
        }
        if (metadata.argumentHint() != null) {
            merged.put("argument-hint", metadata.argumentHint());
        }
        if (metadata.model() != null) {
            merged.put("model", metadata.model());
        }
        if (metadata.allowedTools() != null && !metadata.allowedTools().isEmpty()) {
            merged.put("allowed-tools", String.join(",", metadata.allowedTools()));
        }
        return merged;
    }

    /**
     * 命令名 · 对齐 CC getCommandNameFromFile（loadPluginCommands.ts:60-97）。
     *
     * <p>SKILL.md：commandBaseName = 技能目录名，namespace = 「skill 目录的父目录」相对 baseDir 的子路径
     * （:67-81）；普通文件：commandBaseName = 文件名去 .md，namespace = 文件目录相对 baseDir（:83-96）。
     */
    private static String getCommandNameFromFile(Path filePath, Path baseDir, String pluginName) {
        boolean isSkill = isSkillFile(filePath);
        if (isSkill) {
            Path skillDirectory = filePath.getParent();
            Path parentOfSkillDir = skillDirectory != null ? skillDirectory.getParent() : null;
            String commandBaseName = skillDirectory != null ? skillDirectory.getFileName().toString() : "";
            String namespace = namespaceOf(baseDir, parentOfSkillDir);
            return namespace.isEmpty()
                ? pluginName + ":" + commandBaseName
                : pluginName + ":" + namespace + ":" + commandBaseName;
        }
        Path fileDirectory = filePath.getParent();
        String commandBaseName = fileNameWithoutMd(filePath);
        String namespace = namespaceOf(baseDir, fileDirectory);
        return namespace.isEmpty()
            ? pluginName + ":" + commandBaseName
            : pluginName + ":" + namespace + ":" + commandBaseName;
    }

    /** namespace 子路径（相对 baseDir，'/' 拼接为 ':'）· CC :74-81/:88-92 relativePath.split('/').join(':')。 */
    private static String namespaceOf(Path baseDir, Path dir) {
        if (dir == null || baseDir == null || dir.equals(baseDir) || !dir.startsWith(baseDir)) {
            return "";
        }
        Path rel = baseDir.relativize(dir);
        StringBuilder sb = new StringBuilder();
        for (Path segment : rel) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(segment.toString());
        }
        return sb.toString();
    }

    /** walkPluginMarkdown（stopAtSkillDir=true）· 对齐 CC walkPluginMarkdown.ts:21-69。 */
    private static void walkPluginMarkdown(Path rootDir, List<Path> files) {
        scanDir(rootDir, files);
    }

    private static void scanDir(Path dir, List<Path> files) {
        List<Path> entries;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            entries = stream.toList();
        } catch (IOException e) {
            log.warn("[LoadPluginCommands] 扫描目录失败 {}: {}（单个坏目录不中断插件加载，CC walkPluginMarkdown.ts:60-65）",
                dir, e.getMessage());
            return;
        }
        boolean hasSkill = entries.stream()
            .anyMatch(p -> Files.isRegularFile(p) && isSkillFile(p));
        if (hasSkill) {
            // 技能目录：收集本目录 .md，不递归子目录（CC walkPluginMarkdown.ts:33-46）
            for (Path entry : entries) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().toLowerCase().endsWith(".md")) {
                    files.add(entry);
                }
            }
            return;
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                scanDir(entry, files);
            } else if (Files.isRegularFile(entry) && entry.getFileName().toString().toLowerCase().endsWith(".md")) {
                files.add(entry);
            }
        }
    }

    /** 是否 SKILL.md（CC :53-55 /^skill\.md$/i）。 */
    private static boolean isSkillFile(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        return name.equalsIgnoreCase("SKILL.md");
    }

    /** 文件名去 .md（CC basename().replace(/\.md$/,'')）。 */
    private static String fileNameWithoutMd(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        return name.toLowerCase().endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} 替换 · 对齐 CC substitutePluginVariables
     * （pluginOptionsStorage.ts:326-351）。
     *
     * <p>P1-4（DRF-PC-1）：双变量双形态补齐——${CLAUDE_PLUGIN_ROOT} → 插件安装根（win32 归一化）；
     * ${CLAUDE_PLUGIN_DATA} → {@code getPluginDataDir(source)}（pluginDirectories.ts:119-127，
     * {@code join(getPluginsDirectory(), 'data', sanitizePluginId(source))}，source 非 null 才替换；
     * source 为 null → 保持字面，对齐 CC {@code if (plugin.source)} 守卫 :337-341）。
     */
    private static String substitutePluginVariables(String content, Path pluginPath, String source) {
        if (pluginPath == null) {
            return content;
        }
        String out = content.replace("${CLAUDE_PLUGIN_ROOT}", pluginPath.toString().replace('\\', '/'));
        if (source != null && !source.isBlank()) {
            String dataDir = PluginDirectories.getPluginDataDir(source).replace('\\', '/');
            out = out.replace("${CLAUDE_PLUGIN_DATA}", dataDir);
        }
        return out;
    }

    /**
     * 插件源名（小写）· CC repository = plugin.source（loadPluginCommands.ts:319）。
     *
     * <p>Java 侧 {@code LoadedPlugin} 仅存 {@code InstallSource} 枚举（path/git/marketplace/npm），
     * 无 CC 的 pluginId（{@code name@marketplace}）字符串，取枚举名小写作 repository 投影。
     * 完整 marketplace 标识需扩 LoadedPlugin 字段（plugin 域），本期不扩（消费方 SkillToolImpl
     * plugin 遥测字段块属 ALIGN-ST-1，跨任务）。
     */
    private static String sourceName(PluginLoader.LoadedPlugin plugin) {
        return plugin.source() == null ? null : plugin.source().name().toLowerCase();
    }
}
