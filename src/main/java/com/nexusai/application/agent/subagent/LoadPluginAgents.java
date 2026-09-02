package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * [ODF-C3] Plugin agents 加载器 · 对齐 CC utils/plugins/loadPluginAgents.ts
 * ({@code loadAgentsFromDirectory} + {@code loadAgentFromFile} + walkPluginMarkdown).
 *
 * <p>CC 真源（loadPluginAgents.ts）：
 * <ul>
 *   <li>{@code loadAgentsFromDirectory}（:37-51）— agentsPath 目录递归扫描 .md</li>
 *   <li>{@code loadAgentFromFile}（:54-226）— frontmatter 解析 + plugin 命名空间前缀</li>
 *   <li>agentType = {@code [pluginName, ...namespace, baseAgentName].join(':')}（:119-121）</li>
 *   <li>source = 'plugin'（:212）· filename = baseAgentName（:211）· plugin = sourceName</li>
 *   <li>agentsPaths 附加路径（:276-309）：目录（loadAgentsFromDirectory）或单 .md 文件（loadAgentFromFile）</li>
 *   <li>permissionMode/hooks/mcpServers 有意不解析（:197-209 插件安全边界）</li>
 * </ul>
 *
 * <p>walkPluginMarkdown（walkPluginMarkdown.ts:21-68）：递归扫描，子目录路径进 namespace 数组
 * （root/foo/bar/file.md → ['foo','bar']），Java 端用递归 + List&lt;String&gt; namespace 等价。
 *
 * <p>生产接线：{@code PluginLoader.loadAgents} 委托本类（CC loadPluginAgents.ts:233
 * loadAllPluginsCacheOnly 产出 enabled plugins → 每 plugin 扫描 agentsPath）。
 */
public final class LoadPluginAgents {

    private static final Logger log = LoggerFactory.getLogger(LoadPluginAgents.class);

    private static final List<String> VALID_MEMORY_SCOPES = List.of("user", "project", "local");

    private LoadPluginAgents() {
    }

    /**
     * 扫描默认 agents 目录（CC loadPluginAgents.ts:250-262 plugin.agentsPath）。
     *
     * @param agentsPath 插件 agents 目录（manifest {@code agentsPath}）
     * @param pluginName 插件名（agentType 前缀 + plugin 字段）
     * @return 扫描出的 plugin agents 列表（无效文件跳过）
     */
    public static List<AgentDefinition> load(Path agentsPath, String pluginName) {
        return load(agentsPath, pluginName, List.of());
    }

    /**
     * 扫描默认 agents 目录 + manifest 附加路径（CC loadPluginAgents.ts:250-309）。
     *
     * <p>附加路径（agentsPaths）可为目录（loadAgentsFromDirectory）或单 .md 文件
     * （loadAgentFromFile）。默认目录为空时仅扫附加路径。
     *
     * @param agentsPath      默认 agents 目录（可为不存在路径，跳过）
     * @param pluginName      插件名
     * @param additionalPaths manifest 附加路径（目录或单 .md 文件）
     * @return 扫描出的 plugin agents 列表
     */
    public static List<AgentDefinition> load(Path agentsPath, String pluginName,
                                             List<String> additionalPaths) {
        return load(agentsPath, pluginName, additionalPaths, null, Map.of(), Set.of());
    }

    /**
     * [ODF-C3 返工#2] 带 pluginPath 替换上下文的扫描 · 对齐 CC loadPluginAgents.ts:110-118
     * （{@code substitutePluginVariables(markdownContent, {path: pluginPath, source: sourceName})}）。
     *
     * <p>{@code pluginPath} 为插件安装目录（CC {@code plugin.path}），用于替换 {@code ${CLAUDE_PLUGIN_ROOT}}
     * 占位符；{@code pluginName} 为 {@code source}（CC {@code plugin.source}）。占位符替换发生在
     * frontmatter body 上，插件 agent 因此可引用 bundled 文件（${CLAUDE_PLUGIN_ROOT}/...）。
     *
     * @param agentsPath      默认 agents 目录（可为不存在路径，跳过）
     * @param pluginName      插件名（agentType 前缀 + ${CLAUDE_PLUGIN_DATA} source 上下文）
     * @param additionalPaths manifest 附加路径（目录或单 .md 文件）
     * @param pluginPath      插件安装目录（null → ${CLAUDE_PLUGIN_ROOT} 保持字面，容错既有调用）
     * @return 扫描出的 plugin agents 列表
     */
    public static List<AgentDefinition> load(Path agentsPath, String pluginName,
                                             List<String> additionalPaths, Path pluginPath) {
        return load(agentsPath, pluginName, additionalPaths, pluginPath, Map.of(), Set.of());
    }

    /**
     * [ODF-C3 返工#2] 带完整替换上下文的扫描 · 对齐 CC loadPluginAgents.ts:110-121
     * （{@code substitutePluginVariables} + {@code substituteUserConfigInContent}）。
     *
     * <p>{@code userConfig}/{@code sensitiveKeys} 对应 CC 的 {@code pluginOptionsStorage}
     * {@code loadPluginOptions(source)} 与 {@code pluginManifest.userConfig} schema：
     * {@code ${user_config.KEY}} 命中配置值则替换；敏感键替换为描述性占位符（密钥不进模型 prompt）；
     * 未知键保持字面（对齐 CC substituteUserConfigInContent 未知键不抛）。
     *
     * @param agentsPath      默认 agents 目录（可为不存在路径，跳过）
     * @param pluginName      插件名
     * @param additionalPaths manifest 附加路径
     * @param pluginPath      插件安装目录（null → ${CLAUDE_PLUGIN_ROOT} 保持字面）
     * @param userConfig      用户配置值（pluginOptionsStorage 保存值）
     * @param sensitiveKeys   敏感键集合（命中 → 占位符，密钥不进 prompt）
     * @return 扫描出的 plugin agents 列表
     */
    public static List<AgentDefinition> load(Path agentsPath, String pluginName,
                                             List<String> additionalPaths, Path pluginPath,
                                             Map<String, Object> userConfig,
                                             Set<String> sensitiveKeys) {
        List<AgentDefinition> agents = new ArrayList<>();
        if (agentsPath != null && Files.isDirectory(agentsPath)) {
            try {
                loadAgentsFromDirectory(agentsPath, pluginName, List.of(), pluginPath,
                    userConfig, sensitiveKeys, agents);
            } catch (IOException e) {
                log.warn("[LoadPluginAgents] 默认 agents 目录 {} 加载失败: {} (plugin={})",
                    agentsPath, e.getMessage(), pluginName);
            }
        }
        if (additionalPaths != null) {
            for (String agentPathStr : additionalPaths) {
                if (agentPathStr == null || agentPathStr.isBlank()) {
                    continue;
                }
                Path agentPath = Path.of(agentPathStr);
                try {
                    if (Files.isDirectory(agentPath)) {
                        loadAgentsFromDirectory(agentPath, pluginName, List.of(), pluginPath,
                            userConfig, sensitiveKeys, agents);
                    } else if (Files.isRegularFile(agentPath)
                            && agentPath.getFileName().toString().toLowerCase().endsWith(".md")) {
                        AgentDefinition agent = loadAgentFromFile(agentPath, pluginName, List.of(),
                            pluginPath, userConfig, sensitiveKeys);
                        if (agent != null) {
                            agents.add(agent);
                            if (log.isDebugEnabled()) {
                                log.debug("[LoadPluginAgents] 加载 plugin agent 单文件: {} (plugin={})",
                                    agent.agentType(), pluginName);
                            }
                        }
                    }
                } catch (IOException e) {
                    log.warn("[LoadPluginAgents] 附加路径 {} 加载失败: {} (plugin={})",
                        agentPathStr, e.getMessage(), pluginName);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[LoadPluginAgents] 扫描完成: plugin={} agents={}", pluginName, agents.size());
        }
        return agents;
    }

    /**
     * 递归扫描目录下全部 .md · 对齐 CC loadAgentsFromDirectory（:37-51）+
     * walkPluginMarkdown（walkPluginMarkdown.ts:21-68）。
     *
     * @param dir       当前目录
     * @param pluginName 插件名
     * @param namespace  子目录路径栈（root/foo/bar → ['foo','bar']）
     * @param agents    收集列表（原地追加）
     */
    private static void loadAgentsFromDirectory(Path dir, String pluginName,
                                                List<String> namespace, Path pluginPath,
                                                Map<String, Object> userConfig,
                                                Set<String> sensitiveKeys,
                                                List<AgentDefinition> agents) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.toList();
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    List<String> childNs = new ArrayList<>(namespace);
                    childNs.add(entry.getFileName().toString());
                    loadAgentsFromDirectory(entry, pluginName, childNs, pluginPath,
                        userConfig, sensitiveKeys, agents);
                } else if (Files.isRegularFile(entry)
                        && entry.getFileName().toString().toLowerCase().endsWith(".md")) {
                    AgentDefinition agent = loadAgentFromFile(entry, pluginName, namespace,
                        pluginPath, userConfig, sensitiveKeys);
                    if (agent != null) {
                        agents.add(agent);
                        if (log.isDebugEnabled()) {
                            log.debug("[LoadPluginAgents] 加载 plugin agent: {} 来自 {} (plugin={})",
                                agent.agentType(), entry, pluginName);
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析单个 plugin agent .md 文件 · 对齐 CC loadAgentFromFile（loadPluginAgents.ts:54-226）。
     *
     * <p>关键对齐点：
     * <ul>
     *   <li>baseAgentName = frontmatter.name ?? basename(.md)（:106-108）</li>
     *   <li>agentType = [pluginName, ...namespace, baseAgentName].join(':')（:119-121）</li>
     *   <li>whenToUse = description ?? when-to-use ?? 'Agent from ${pluginName} plugin'（:123-127）</li>
     *   <li>tools/skills 经 ParseSkillFrontmatter 统一管线（parseAgentToolsFromFrontmatter /
     *       parseSlashCommandToolsFromFrontmatter，CC markdownConfigLoader.ts:113-126；原
     *       loadAgentsDir.parseTools 自建实现已删除 DEL-01）</li>
     *   <li>memory 启用 + tools 显式非通配 → 注入 Write/Edit/Read（:189-206）</li>
     *   <li>permissionMode/hooks/mcpServers 不解析（:197-209 安全边界）</li>
     * </ul>
     */
    private static AgentDefinition loadAgentFromFile(Path file, String pluginName,
                                                     List<String> namespace, Path pluginPath,
                                                     Map<String, Object> userConfig,
                                                     Set<String> sensitiveKeys) throws IOException {
        String content = Files.readString(file);
        // P1-1: 接入统一 frontmatter-config 管线（CC loadPluginAgents.ts:80 复用 parseFrontmatter）
        //   —— 原 loadAgentsDir.parseFrontmatter（自建 SnakeYAML）已删除（DEL-01），统一走
        //   ParseSkillFrontmatter.parseFrontmatter。
        ParseSkillFrontmatter.ParsedMarkdown parsed =
            ParseSkillFrontmatter.parseFrontmatterStatic(content, file.toString());
        Map<String, Object> fm = parsed.frontmatter();
        if (fm.isEmpty()) {
            log.warn("[LoadPluginAgents] {} 无 frontmatter, 跳过 (非 agent 文件, plugin={})",
                file, pluginName);
            return null;
        }
        String baseAgentName = asString(fm.get("name"));
        if (baseAgentName == null) {
            // CC :106-108 basename 兜底
            baseAgentName = fileNameWithoutMd(file);
        }
        // CC :119-121 [pluginName, ...namespace, baseAgentName].join(':')
        List<String> nameParts = new ArrayList<>();
        nameParts.add(pluginName);
        nameParts.addAll(namespace);
        nameParts.add(baseAgentName);
        String agentType = String.join(":", nameParts);

        // CC :123-127 whenToUse = description ?? when-to-use ?? `Agent from ${pluginName} plugin`
        String whenToUse = asString(fm.get("description"));
        if (whenToUse == null) {
            whenToUse = asString(fm.get("when-to-use"));
        }
        if (whenToUse == null) {
            whenToUse = "Agent from " + pluginName + " plugin";
        }

        // CC :110-121 占位符替换（substitutePluginVariables + substituteUserConfigInContent）
        String body = parsed.content().trim();
        body = substitutePluginVariables(body, pluginPath, pluginName);
        body = substituteUserConfigInContent(body, userConfig, sensitiveKeys);

        AgentDefinition.PluginAgentDefinition.Builder builder =
            AgentDefinition.PluginAgentDefinition.builder(agentType, whenToUse, pluginName, body)
                .filename(baseAgentName);

        // tools · CC :98 parseAgentToolsFromFrontmatter（缺=undefined=全部, 空=[], '*'=undefined=全部）
        //   P1-1 接入统一管线：复用 ParseSkillFrontmatter.parseAgentToolsFromFrontmatter
        //   （markdownConfigLoader.ts:113-126），替代 loadAgentsDir.parseTools。
        List<String> tools = ParseSkillFrontmatter.parseAgentToolsFromFrontmatter(fm, "tools");
        // skills · CC :99 parseSlashCommandToolsFromFrontmatter（缺/空=[]，恒非 undefined → 恒设置）
        builder.skills(ParseSkillFrontmatter.parseSlashCommandToolsFromFrontmatter(fm.get("skills")));
        // color · CC :133
        Object colorObj = fm.get("color");
        if (colorObj != null) builder.color(colorObj.toString());
        // model · CC :135-140（trim；'inherit' 小写保留）
        Object modelObj = fm.get("model");
        if (modelObj instanceof String ms && !ms.trim().isEmpty()) {
            String trimmed = ms.trim();
            builder.model("inherit".equals(trimmed.toLowerCase()) ? "inherit" : trimmed);
        }
        // background · CC :147（'true'/true -> true）
        Object bg = fm.get("background");
        if (Boolean.TRUE.equals(bg) || "true".equals(bg)) builder.background(true);
        // memory · CC :152-158（user/project/local）
        String memory = null;
        Object memoryObj = fm.get("memory");
        if (memoryObj != null) {
            memory = memoryObj.toString();
            if (VALID_MEMORY_SCOPES.contains(memory)) {
                builder.memory(memory);
            } else {
                log.warn("[LoadPluginAgents] {} 非法 memory '{}'，有效: {} (对齐 loadPluginAgents.ts:157-159)",
                    file, memory, VALID_MEMORY_SCOPES);
                memory = null;
            }
        }
        // isolation · CC :161（worktree）
        Object isoObj = fm.get("isolation");
        if (isoObj != null && "worktree".equals(isoObj.toString())) {
            builder.isolation("worktree");
        }
        // effort · CC :162-166
        Object effortObj = fm.get("effort");
        if (effortObj != null) builder.effort(effortObj.toString());
        // maxTurns · CC :172 parsePositiveIntFromFrontmatter（正整数；非法 → undefined + warn :173-177）
        Object mtRaw = fm.get("maxTurns");
        Integer maxTurns = ParseSkillFrontmatter.parsePositiveIntFromFrontmatter(mtRaw);
        if (mtRaw != null && maxTurns == null) {
            log.warn("[LoadPluginAgents] {} maxTurns 非法 '{}'，必须为正整数 (对齐 loadPluginAgents.ts:173-177)",
                file, mtRaw);
        }
        if (maxTurns != null) builder.maxTurns(maxTurns);
        // disallowedTools · CC :180-183（缺=undefined 不设置；'*'=undefined 不设置）
        List<String> disallowedTools =
            ParseSkillFrontmatter.parseAgentToolsFromFrontmatter(fm, "disallowedTools");
        if (disallowedTools != null) builder.disallowedTools(disallowedTools);
        // permissionMode/hooks/mcpServers 有意不解析（CC :197-209 插件安全边界）

        // memory 启用 + tools 显式非通配 → 注入 Write/Edit/Read（CC :189-206）
        if (memory != null && tools != null
                && com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            builder.tools(injectMemoryTools(tools));
        } else if (tools != null) {
            builder.tools(tools);
        }

        return builder.build();
    }

    /**
     * [ODF-C3 返工#2] ${CLAUDE_PLUGIN_ROOT} 替换 · 对齐 CC pluginOptionsStorage.ts:326-351
     * substitutePluginVariables({path: pluginPath, source: sourceName})。
     *
     * <p>${CLAUDE_PLUGIN_ROOT} → 插件安装目录（win32 归一化 '\\'→'/'，避免 shell 转义）；
     * pluginPath 为 null → 保持字面（无路径可替换）。${CLAUDE_PLUGIN_DATA} 依赖 plugins-dir 常量，
     * Java 侧尚未建立该基础设施，保持字面 —— 登记为 pending（见 progress §5）。
     */
    private static String substitutePluginVariables(String content, Path pluginPath,
                                                    String pluginName) {
        String out = content;
        if (pluginPath != null) {
            String normalized = pluginPath.toString().replace('\\', '/');
            out = out.replace("${CLAUDE_PLUGIN_ROOT}", normalized);
        }
        return out;
    }

    /** 敏感键占位符 · 对齐 CC substituteUserConfigInContent 敏感键文案（pluginOptionsStorage.ts:407-412） */
    private static final Pattern USER_CONFIG_PATTERN =
        Pattern.compile("\\$\\{user_config\\.([^}]+)}");

    /**
     * [ODF-C3 返工#2] ${user_config.KEY} 替换 · 对齐 CC pluginOptionsStorage.ts:385-419
     * substituteUserConfigInContent（skill/agent 正文安全变体）。
     *
     * <p>敏感键 → 描述性占位符（密钥不进模型 prompt）；命中配置值 → 替换为 String(value)；
     * 未知键 → 保持字面（对齐 CC 未知键不抛）。空 map + 空 set → 全部保持字面（既有调用无 userConfig）。
     */
    private static String substituteUserConfigInContent(String content,
                                                        Map<String, Object> userConfig,
                                                        Set<String> sensitiveKeys) {
        Matcher m = USER_CONFIG_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if (sensitiveKeys != null && sensitiveKeys.contains(key)) {
                replacement = "[sensitive option '" + key + "' not available in skill content]";
            } else if (userConfig != null && userConfig.containsKey(key)) {
                replacement = String.valueOf(userConfig.get(key));
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** CC :211 filename = basename without .md */
    private static String fileNameWithoutMd(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String asString(Object obj) {
        return obj == null ? null : obj.toString();
    }

    /** memory 启用时补 Write/Edit/Read · 对齐 CC loadAgentsDir.ts:666-672（同源工具常量） */
    private static List<String> injectMemoryTools(List<String> tools) {
        LinkedHashSet<String> set = new LinkedHashSet<>(tools);
        set.add(com.nexusai.application.agent.tool.ToolNameConstants.FILE_WRITE_TOOL_NAME);
        set.add(com.nexusai.application.agent.tool.ToolNameConstants.FILE_EDIT_TOOL_NAME);
        set.add(com.nexusai.application.agent.tool.ToolNameConstants.FILE_READ_TOOL_NAME);
        return new ArrayList<>(set);
    }
}
