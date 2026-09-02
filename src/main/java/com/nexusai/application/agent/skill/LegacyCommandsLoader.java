package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * legacy /commands/ 加载器 · 对齐 CC {@code skills/loadSkillsDir.ts:484-623}
 * （{@code isSkillFile / transformSkillFiles / buildNamespace / getSkillCommandName /
 * getRegularCommandName / getCommandName / loadSkillsFromCommandsDir}）。
 *
 * <h2>CC 对应（snake_case → camelCase，行号标注）</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #isSkillFile}</td><td>{@code isSkillFile}</td><td>loadSkillsDir.ts:484-486</td></tr>
 *   <tr><td>{@link #transformSkillFiles}</td><td>{@code transformSkillFiles}</td><td>loadSkillsDir.ts:493-521</td></tr>
 *   <tr><td>{@link #buildNamespace}</td><td>{@code buildNamespace}</td><td>loadSkillsDir.ts:523-534</td></tr>
 *   <tr><td>{@link #getSkillCommandName}</td><td>{@code getSkillCommandName}</td><td>loadSkillsDir.ts:536-543</td></tr>
 *   <tr><td>{@link #getRegularCommandName}</td><td>{@code getRegularCommandName}</td><td>loadSkillsDir.ts:545-552</td></tr>
 *   <tr><td>{@link #getCommandName}</td><td>{@code getCommandName}</td><td>loadSkillsDir.ts:554-559</td></tr>
 *   <tr><td>{@link #loadSkillsFromCommandsDir}</td><td>{@code loadSkillsFromCommandsDir}</td><td>loadSkillsDir.ts:566-623</td></tr>
 * </table>
 *
 * <h2>产物（CC :600-612 createSkillCommand）</h2>
 * <ul>
 *   <li>loadedFrom='commands_DEPRECATED'（Java {@link CommandLoadedFrom#COMMANDS_DEPRECATED}，
 *       loadSkillsDir.ts:608 —— P2-21 前误折叠为 CommandSource.USER，被 getSlashCommandToolSkills
 *       误放行进斜杠技能集；CC commands.ts:595-597 明确排除）</li>
 *   <li>displayName=undefined（覆盖 frontmatter.name，CC :604）</li>
 *   <li>paths=undefined（CC :609，legacy 命令无条件暴露）</li>
 *   <li>baseDir=skillDirectory（仅 skill 格式，CC :607）</li>
 * </ul>
 *
 * <p>复用 {@link CreateSkillCommand#create} + {@link ParseSkillFrontmatter#parseSkillFrontmatterFields}
 * （与 MCP 共享 builder 同源，loadSkillsDir.ts:1083 对齐，P2-13）。
 */
public final class LegacyCommandsLoader {

    private static final Logger log = LoggerFactory.getLogger(LegacyCommandsLoader.class);

    /** skill 文件名匹配 · CC original: {@code /^skill\.md$/i}（loadSkillsDir.ts:485，大小写不敏感）。 */
    private static final Pattern SKILL_FILE = Pattern.compile("^skill\\.md$", Pattern.CASE_INSENSITIVE);

    private LegacyCommandsLoader() {
        // 静态工具类
    }

    /**
     * 是否为 SKILL.md 文件 · CC original: {@code isSkillFile}（loadSkillsDir.ts:484-486）
     * {@code /^skill\.md$/i.test(basename(filePath))}。
     */
    public static boolean isSkillFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        String base = Paths.get(filePath).getFileName().toString();
        return SKILL_FILE.matcher(base).matches();
    }

    /**
     * 转换 markdown 文件以处理 legacy /commands/ 文件夹中的 "skill" 命令 · CC original:
     * {@code transformSkillFiles}（loadSkillsDir.ts:493-521）。
     *
     * <p>当目录中存在 SKILL.md 时，只加载该文件并取父目录名（CC :489-491）；目录内多 SKILL.md 取第一个
     * 并 warn（CC :509-513）；无 skill 文件则保留全部（CC :515-516）。
     */
    public static List<MarkdownConfigLoader.MarkdownFile> transformSkillFiles(
            List<MarkdownConfigLoader.MarkdownFile> files) {
        Map<String, List<MarkdownConfigLoader.MarkdownFile>> filesByDir = new LinkedHashMap<>();
        for (MarkdownConfigLoader.MarkdownFile file : files) {
            String dir = Paths.get(file.filePath()).getParent().toString();
            filesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(file);
        }

        List<MarkdownConfigLoader.MarkdownFile> result = new ArrayList<>();
        for (Map.Entry<String, List<MarkdownConfigLoader.MarkdownFile>> e : filesByDir.entrySet()) {
            List<MarkdownConfigLoader.MarkdownFile> skillFiles = e.getValue().stream()
                .filter(f -> isSkillFile(f.filePath()))
                .toList();
            if (!skillFiles.isEmpty()) {
                MarkdownConfigLoader.MarkdownFile skillFile = skillFiles.get(0);
                if (skillFiles.size() > 1) {
                    log.warn("目录 {} 中存在多个 SKILL.md，使用 {} (CC loadSkillsDir.ts:509-513)",
                        e.getKey(), Paths.get(skillFile.filePath()).getFileName());
                }
                result.add(skillFile);
            } else {
                result.addAll(e.getValue());
            }
        }
        return result;
    }

    /**
     * 构建 namespace（a:b:c）· CC original: {@code buildNamespace}（loadSkillsDir.ts:523-534）。
     *
     * <p>targetDir 相对 baseDir 的路径段以 ':' 连接；targetDir==baseDir → ''。
     * Java 增防御：targetDir 不在 baseDir 之下 → ''（JS 用 slice 会产生垃圾串，Java 不复制该行为）。
     */
    public static String buildNamespace(String targetDir, String baseDir) {
        if (targetDir == null || baseDir == null) {
            return "";
        }
        String normalizedBase = baseDir.endsWith(File.separator)
            ? baseDir.substring(0, baseDir.length() - 1)
            : baseDir;
        if (targetDir.equals(normalizedBase)) {
            return "";
        }
        if (!targetDir.startsWith(normalizedBase + File.separator)) {
            return "";
        }
        String relativePath = targetDir.substring(normalizedBase.length() + 1);
        if (relativePath.isEmpty()) {
            return "";
        }
        return relativePath.replace(File.separatorChar, ':');
    }

    /**
     * skill 格式命令名（取父目录名 + namespace）· CC original: {@code getSkillCommandName}
     * （loadSkillsDir.ts:536-543）。
     */
    public static String getSkillCommandName(String filePath, String baseDir) {
        java.nio.file.Path skillDir = Paths.get(filePath).getParent();
        java.nio.file.Path parentOfSkillDir = skillDir.getParent();
        String commandBaseName = skillDir.getFileName().toString();
        String namespace = buildNamespace(parentOfSkillDir.toString(), baseDir);
        return namespace.isEmpty() ? commandBaseName : namespace + ":" + commandBaseName;
    }

    /**
     * 普通 .md 命令名（文件名去 .md + namespace）· CC original: {@code getRegularCommandName}
     * （loadSkillsDir.ts:545-552）。
     */
    public static String getRegularCommandName(String filePath, String baseDir) {
        java.nio.file.Path file = Paths.get(filePath);
        String fileName = file.getFileName().toString();
        String commandBaseName = fileName.replaceAll("\\.md$", "");
        String namespace = buildNamespace(file.getParent().toString(), baseDir);
        return namespace.isEmpty() ? commandBaseName : namespace + ":" + commandBaseName;
    }

    /**
     * 统一命令名入口 · CC original: {@code getCommandName}（loadSkillsDir.ts:554-559）——
     * isSkillFile → skill 名（父目录名），否则普通 .md 名（文件名去 .md）。
     */
    public static String getCommandName(MarkdownConfigLoader.MarkdownFile file) {
        return isSkillFile(file.filePath())
            ? getSkillCommandName(file.filePath(), file.baseDir())
            : getRegularCommandName(file.filePath(), file.baseDir());
    }

    /**
     * 从 legacy /commands/ 目录加载技能 · CC original: {@code loadSkillsFromCommandsDir(cwd)}
     * （loadSkillsDir.ts:566-623）。
     *
     * <p>流程：{@link MarkdownConfigLoader#loadMarkdownFilesForSubdir}('commands', cwd) →
     * {@link #transformSkillFiles} → 每文件 getCommandName + parseSkillFrontmatterFields(fallbackLabel='Custom
     * command', CC :597) → createSkillCommand（loadedFrom='commands_DEPRECATED' / displayName=undefined /
     * paths=undefined / baseDir=skillDirectory）。单文件异常 catch → warn + 跳过（CC :613-615）。
     *
     * @param cwd 当前工作目录（项目 .claude/commands 遍历基准）
     * @return legacy 命令技能列表
     */
    public static List<Command> loadSkillsFromCommandsDir(String cwd) {
        try {
            List<MarkdownConfigLoader.MarkdownFile> markdownFiles =
                MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", cwd);
            List<MarkdownConfigLoader.MarkdownFile> processedFiles = transformSkillFiles(markdownFiles);

            List<Command> skills = new ArrayList<>();
            for (MarkdownConfigLoader.MarkdownFile file : processedFiles) {
                try {
                    boolean isSkillFormat = isSkillFile(file.filePath());
                    String skillDirectory = isSkillFormat
                        ? Paths.get(file.filePath()).getParent().toString()
                        : null;
                    String cmdName = getCommandName(file);

                    SkillFrontmatterFields parsed = ParseSkillFrontmatter.parseSkillFrontmatterFields(
                        file.frontmatter(), file.content(), cmdName, "Custom command");

                    Command c = CreateSkillCommand.create(new CreateSkillCommand.Params(
                        cmdName,                                     // skillName
                        null,                                        // displayName: undefined（CC :604）
                        parsed.description(),                        // description
                        parsed.hasUserSpecifiedDescription(),        // hasUserSpecifiedDescription
                        file.content(),                              // markdownContent
                        parsed.allowedTools(),                       // allowedTools
                        parsed.argumentHint(),                       // argumentHint
                        parsed.argumentNames(),                      // argumentNames
                        parsed.whenToUse(),                          // whenToUse
                        parsed.version(),                            // version
                        parsed.model(),                              // model
                        parsed.disableModelInvocation(),             // disableModelInvocation
                        parsed.userInvocable(),                      // userInvocable
                        CommandSource.fromString(file.source()),     // source（CC :606 透传 markdown 源）
                        skillDirectory,                              // baseDir: skillDirectory（CC :607）
                        CommandLoadedFrom.COMMANDS_DEPRECATED,       // loadedFrom='commands_DEPRECATED'（CC :608）
                        parsed.hooks(),                              // hooks
                        parsed.executionContext(),                   // executionContext
                        parsed.agent(),                              // agent
                        null,                                        // paths: undefined（CC :609）
                        parsed.effort(),                             // effort
                        parsed.shell()                               // shell
                    ));
                    skills.add(c);
                } catch (Exception ex) {
                    log.warn("解析 legacy 命令失败（跳过）：{} cause={} (CC loadSkillsDir.ts:613-615)",
                        file.filePath(), ex.toString());
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("legacy /commands/ 加载完成: 原始 {} → 转换后 {} → 命令 {} (cwd={})",
                    markdownFiles.size(), processedFiles.size(), skills.size(), cwd);
            }
            return skills;
        } catch (Exception e) {
            log.warn("legacy /commands/ 加载失败（返回空）：{} cause={} (CC loadSkillsDir.ts:619-622)",
                cwd, e.toString());
            return List.of();
        }
    }
}
