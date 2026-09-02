package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 技能内容加载器 · 加载 SKILL.md 正文（prompt body）
 *
 * <p>对齐 CC PromptCommand.getPromptForCommand() — 返回技能提示文本。
 * 内容来源于：
 * <ul>
 *   <li>SKILL.md body（去除 frontmatter 后的 Markdown）</li>
 *   <li>内存/DB content 字段（如果 SKILL.md 不可用，向后兼容 getContent() 回退）</li>
 * </ul>
 *
 * <p>X1 删除：skill.json v1 兼容加载路径已移除（CC 无此格式，loadSkillsDir.ts:433-444
 * 仅 SKILL.md、ENOENT 即 skip）；内容源 = SKILL.md body（去除 frontmatter）+ 内存/DB
 * content 回退，无 skill.json 读取。
 *
 * <p>对于用户技能（source=USER），从 baseDir/SKILL.md 加载。
 * 对于 bundled 技能，从 content 字段获取（在内存中）。
 *
 * <p><b>P0-4 拆分</b>：CC getPromptForCommand 闭包（loadSkillsDir.ts:344-369）的
 * {@code substituteArguments} 已抽至 {@link ArgumentSubstitution}（镜像 CC 单模块），
 * 本类仅保留 {@code ${CLAUDE_SKILL_DIR}}（{@link #replaceSkillDir}）与
 * {@code ${CLAUDE_SESSION_ID}}（{@link #replaceSessionId}）两个独立步骤。
 * 组合顺序由调用方（{@code SkillToolImpl.doExecute}）保证：prefix → ArgumentSubstitution
 * → replaceSkillDir → replaceSessionId。行为由 {@link SkillContentLoaderSubstitutionTest}
 * 组合级测试锁定。
 *
 * <p><b>P0-5 shell 注入归属</b>：CC getPromptForCommand 闭包（loadSkillsDir.ts:344-396）的
 * <b>最后一步</b> {@code executeShellCommandsInPrompt}（inline {@code !`cmd`} / {@code ```! ```}
 * shell 注入，promptShellExecution.ts:69-143）<b>不归本类</b>——本类是无 ToolUseContext 的
 * 纯文本加载器（loadContent/withBaseDirPrefix/replaceSkillDir/replaceSessionId 均无 ctx/工具/
 * 权限依赖）。shell 注入归 {@link PromptShellExecutor}（新建类），由 {@code SkillToolImpl.doExecute}
 * 在 replaceSessionId 之后调用（含 CommandSource.MCP 安全闸，对齐 CC loadSkillsDir.ts:374-396）。
 */
public class SkillContentLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillContentLoader.class);

    /**
     * 加载技能的完整提示内容 · 对齐 CC PromptCommand.getPromptForCommand()
     *
     * @param skill 技能对象
     * @return 提示文本（body content）
     */
    public String loadContent(Command skill) {
        // 1. 优先从 SKILL.md 文件加载
        if (skill.getContentPath() != null) {
            Path skillMd = Paths.get(skill.getContentPath());
            if (Files.exists(skillMd)) {
                return loadFromFile(skillMd);
            }
        }

        // 2. 从 baseDir + SKILL.md 推断路径
        if (skill.getBaseDir() != null) {
            Path skillMd = Paths.get(skill.getBaseDir(), "SKILL.md");
            if (Files.exists(skillMd)) {
                return loadFromFile(skillMd);
            }
        }

        // 3. 使用 DB content 字段
        return skill.getContent() != null ? skill.getContent() : "";
    }

    /**
     * 从 SKILL.md 加载 body（去除 frontmatter）
     */
    private String loadFromFile(Path skillMd) {
        try {
            String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
            ParseSkillFrontmatter parser = new ParseSkillFrontmatter();
            return parser.extractBody(raw);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 为技能提示添加 baseDir 前缀 · 对齐 CC prependBaseDir()
     *
     * <p>当技能有参考文件时，提示模型文件基础目录。
     */
    public String withBaseDirPrefix(Command skill, String content) {
        if (skill.getBaseDir() != null) {
            return "Base directory for this skill: " + skill.getBaseDir() + "\n\n" + content;
        }
        return content;
    }

    /**
     * 替换 ${CLAUDE_SKILL_DIR} · 对齐 CC loadSkillsDir.ts:359-363（getPromptForCommand 闭包独立步骤）。
     *
     * <p>把 ${CLAUDE_SKILL_DIR} 替换为技能目录绝对路径；Windows（win32）下把反斜杠规范化为
     * 正斜杠，避免 bash {@code !`...`} 块把 {@code \} 当转义（P0-3 修复的回归线）。
     * skillDir 为 null 时不替换（对齐 CC {@code if (baseDir)} 守卫）。
     *
     * @param content  含 ${CLAUDE_SKILL_DIR} 的内容
     * @param skillDir 技能目录绝对路径（可为 null；null 时不替换）
     * @return 替换后的内容
     */
    public String replaceSkillDir(String content, String skillDir) {
        if (skillDir == null) {
            return content;
        }
        String dir = isWindows() ? skillDir.replace('\\', '/') : skillDir;
        String result = content.replace("${CLAUDE_SKILL_DIR}", dir);
        if (log.isDebugEnabled() && !result.equals(content)) {
            log.debug("[SkillContentLoader] replaceSkillDir: 替换 ${CLAUDE_SKILL_DIR} → {} (CC loadSkillsDir.ts:359-363)",
                dir);
        }
        return result;
    }

    /**
     * 替换 ${CLAUDE_SESSION_ID} · 对齐 CC loadSkillsDir.ts:366-369（getPromptForCommand 闭包独立步骤）。
     *
     * @param content   含 ${CLAUDE_SESSION_ID} 的内容
     * @param sessionId 当前会话 ID（可为 null；null 时不替换）
     * @return 替换后的内容
     */
    public String replaceSessionId(String content, String sessionId) {
        if (sessionId == null) {
            return content;
        }
        String result = content.replace("${CLAUDE_SESSION_ID}", sessionId);
        if (log.isDebugEnabled() && !result.equals(content)) {
            log.debug("[SkillContentLoader] replaceSessionId: 替换 ${CLAUDE_SESSION_ID} → {} (CC loadSkillsDir.ts:366-369)",
                sessionId);
        }
        return result;
    }

    /**
     * 替换 ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} · 对齐 CC substitutePluginVariables
     * （pluginOptionsStorage.ts:326-351，getPromptForCommand 闭包 loadPluginCommands.ts:340-343）。
     *
     * <p>P1-4（GAP-PC-1）：plugin 命令 content 链补双变量替换——${CLAUDE_PLUGIN_ROOT} → 插件安装根
     * （win32 反斜杠→正斜杠，避免 shell 转义）；${CLAUDE_PLUGIN_DATA} → {@code getPluginDataDir(source)}
     * （pluginDirectories.ts:119-127，source 非 null 才替换，对齐 CC {@code if (plugin.source)} 守卫
     * :337-341；source 为 null → 保持字面）。
     *
     * <p>pluginRoot 为 null → 保持字面（非 plugin 源命令，无路径可替换；对齐 CC
     * substitutePluginVariables 对 path 缺省不替换）。组合顺序由调用方保证：
     * substituteArguments → replacePluginVariables → replaceUserConfig → replaceSkillDir →
     * replaceSessionId（对齐 CC loadPluginCommands.ts:340-372）。
     *
     * @param content      含 ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} 的内容
     * @param pluginRoot   插件安装根目录绝对路径（可为 null；null 时不替换）
     * @param pluginSource 插件 source（${CLAUDE_PLUGIN_DATA} data 目录计算；可为 null）
     * @return 替换后的内容
     */
    public String replacePluginVariables(String content, String pluginRoot, String pluginSource) {
        if (content == null || pluginRoot == null) {
            return content;
        }
        String root = isWindows() ? pluginRoot.replace('\\', '/') : pluginRoot;
        String out = content.replace("${CLAUDE_PLUGIN_ROOT}", root);
        if (pluginSource != null && !pluginSource.isBlank()) {
            String dataDir = com.nexusai.application.agent.plugin.PluginDirectories.getPluginDataDir(pluginSource);
            dataDir = isWindows() ? dataDir.replace('\\', '/') : dataDir;
            out = out.replace("${CLAUDE_PLUGIN_DATA}", dataDir);
        }
        if (log.isDebugEnabled() && !out.equals(content)) {
            log.debug("[SkillContentLoader] replacePluginVariables: 替换 ${CLAUDE_PLUGIN_ROOT}/DATA → {} / data (CC pluginOptionsStorage.ts:326-351)",
                root);
        }
        return out;
    }

    /** ${user_config.X} 正则 · CC substituteUserConfigInContent（pluginOptionsStorage.ts:385-419）。 */
    private static final java.util.regex.Pattern USER_CONFIG_PATTERN =
        java.util.regex.Pattern.compile("\\$\\{user_config\\.([^}]+)}");

    /**
     * 替换 ${user_config.X} · 对齐 CC substituteUserConfigInContent（pluginOptionsStorage.ts:385-419）。
     *
     * <p>P1-4（GAP-PC-2）：plugin 命令 content 链补 ${user_config.X} 替换——敏感键（sensitiveKeys 命中）
     * → 描述性占位符 {@code [sensitive option 'X' not available in skill content]}（密钥不进模型 prompt，
     * CC :405-413）；已知键（userConfig 命中）→ {@code String(value)}；未知键 → 保持字面（对齐 CC
     * 未知键不抛，:399-402）。空 map + 空 set → 全部保持字面（Java 无 pluginOptionsStorage 等价物时的
     * 既有行为，对齐 CC pluginManifest.userConfig 缺省不替换）。
     *
     * @param content       含 ${user_config.X} 的内容
     * @param userConfig    已保存的 plugin 选项值（可为空 map）
     * @param sensitiveKeys 敏感键集合（命中 → 占位符）
     * @return 替换后的内容
     */
    public String replaceUserConfig(String content, java.util.Map<String, Object> userConfig,
                                    java.util.Set<String> sensitiveKeys) {
        if (content == null) {
            return content;
        }
        java.util.Map<String, Object> opts = userConfig != null ? userConfig : java.util.Map.of();
        java.util.Set<String> sensitive = sensitiveKeys != null ? sensitiveKeys : java.util.Set.of();
        java.util.regex.Matcher m = USER_CONFIG_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if (sensitive.contains(key)) {
                replacement = "[sensitive option '" + key + "' not available in skill content]";
            } else if (opts.containsKey(key)) {
                replacement = String.valueOf(opts.get(key));
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String out = sb.toString();
        if (log.isDebugEnabled() && !out.equals(content)) {
            log.debug("[SkillContentLoader] replaceUserConfig: 替换 ${user_config.X} (CC pluginOptionsStorage.ts:385-419)");
        }
        return out;
    }

    /** 是否 Windows 平台 · 决定 ${CLAUDE_SKILL_DIR} 是否需反斜杠规范化（CC process.platform === 'win32'）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }
}
