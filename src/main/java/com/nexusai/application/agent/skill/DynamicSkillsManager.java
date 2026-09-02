package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.infra.util.GitIgnoreHelper;
import com.nexusai.infra.util.GitIgnoreMatcher;
import com.nexusai.infra.util.PluginOnlyPolicy;
import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 动态技能 / 条件技能管理器 · 对齐 CC {@code skills/loadSkillsDir.ts:820-1075}
 * （{@code dynamicSkillDirs} / {@code dynamicSkills} / {@code conditionalSkills} /
 * {@code activatedConditionalSkillNames} 4 模块级状态 + A18-A24 导出函数）。
 *
 * <h2>CC 对应（snake_case → camelCase，行号标注）</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #discoverSkillDirsForPaths}</td><td>{@code discoverSkillDirsForPaths}</td><td>loadSkillsDir.ts:861-915</td></tr>
 *   <tr><td>{@link #addSkillDirectories}</td><td>{@code addSkillDirectories}</td><td>loadSkillsDir.ts:923-975</td></tr>
 *   <tr><td>{@link #activateConditionalSkillsForPaths}</td><td>{@code activateConditionalSkillsForPaths}</td><td>loadSkillsDir.ts:997-1058</td></tr>
 *   <tr><td>{@link #getDynamicSkills}</td><td>{@code getDynamicSkills}</td><td>loadSkillsDir.ts:981-983</td></tr>
 *   <tr><td>{@link #getConditionalSkillCount}</td><td>{@code getConditionalSkillCount}</td><td>loadSkillsDir.ts:1063-1065</td></tr>
 *   <tr><td>{@link #clearDynamicSkills}</td><td>{@code clearDynamicSkills}</td><td>loadSkillsDir.ts:1070-1075</td></tr>
 * </ul>
 *
 * <h2>T7 遥测</h2>
 * <p>动态技能变更的 2 个触发点（file_operation / conditional_paths）经
 * {@link Telemetry#recordEvent(String, Map)} 上报 {@code tengu_dynamic_skills_changed}
 * （对齐 CC loadSkillsDir.ts:962-969 / :1044-1051，属性名沿用 CC camelCase）。
 *
 * <h2>onDynamicSkillsLoaded 监听（skillsLoaded.emit 等价，多监听 + unsubscribe）</h2>
 * <p>{@code addSkillDirectories} / {@code activateConditionalSkillsForPaths} 变更后触发所有监听，
 * 由 {@link SkillRegistry#setDynamicSkillsManager} 注册 {@code refresh()}（对齐 CC
 * loadSkillsDir.ts:974/:1054 {@code skillsLoaded.emit()} → commands.ts:523-531
 * {@code clearCommandMemoizationCaches}）。△-6：多监听 + 返回 unsubscribe（CC onDynamicSkillsLoaded :839-851）。
 *
 * <h2>依赖注入（POJO 兼容 · 全部 setter）</h2>
 * <ul>
 *   <li>{@link SkillsLoader} — 复用目录加载（addSkillDirectories 每目录 loadSkillsFromSkillsDir 等价）</li>
 *   <li>{@link Telemetry} — T7 遥测</li>
 *   <li>{@code gitExec} — git check-ignore 执行器（默认 ProcessBuilder，测试可注入桩）</li>
 *   <li>{@code settingsSupplier} — plugin-only policy settings 读取器（默认 Map::of = 不锁定，Web 恒启用 projectSettings 语义）</li>
 * </ul>
 */
@Component
public class DynamicSkillsManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillsManager.class);

    /** T7 遥测事件名 · CC original: {@code tengu_dynamic_skills_changed}（loadSkillsDir.ts:962/:1044） */
    public static final String TENGU_DYNAMIC_SKILLS_CHANGED = "tengu_dynamic_skills_changed";

    /**
     * T7 变更来源枚举 · CC original: source 属性（loadSkillsDir.ts:963-968 / :1045-1050）
     */
    public enum DynamicSkillChangeSource {
        /** 文件工具写/读触发的目录发现 · CC original: {@code 'file_operation'} */
        FILE_OPERATION("file_operation"),
        /** 条件技能 paths 匹配文件路径触发激活 · CC original: {@code 'conditional_paths'} */
        CONDITIONAL_PATHS("conditional_paths");

        private final String ccValue;

        DynamicSkillChangeSource(String ccValue) {
            this.ccValue = ccValue;
        }

        public String ccValue() {
            return ccValue;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4 个模块级状态 · CC original: loadSkillsDir.ts:821-829（ConcurrentHashMap 保证并发安全）
    // ════════════════════════════════════════════════════════════════════════

    /** 已检查过的 .claude/skills 目录（hit/miss 都记录，避免每次 Read/Write/Edit 重复 stat）·
     *  CC original: {@code dynamicSkillDirs}（loadSkillsDir.ts:821） */
    private final Set<String> dynamicSkillDirs = ConcurrentHashMap.newKeySet();
    /** 动态发现的技能 · CC original: {@code dynamicSkills}（loadSkillsDir.ts:822） */
    private final Map<String, Command> dynamicSkills = new ConcurrentHashMap<>();
    /** 待激活的条件技能（paths frontmatter 未激活）· CC original: {@code conditionalSkills}（loadSkillsDir.ts:827） */
    private final Map<String, Command> conditionalSkills = new ConcurrentHashMap<>();
    /** 已激活的条件技能名（session 内跨 cache clear 存活）· CC original: {@code activatedConditionalSkillNames}（loadSkillsDir.ts:829） */
    private final Set<String> activatedConditionalSkillNames = ConcurrentHashMap.newKeySet();

    // ════════════════════════════════════════════════════════════════════════
    // 依赖注入（POJO 兼容 · setter）· CC 对齐: CC 模块级闭包捕获（loadSkillsDir.ts）
    // ════════════════════════════════════════════════════════════════════════

    private SkillsLoader skillsLoader = new SkillsLoader();
    private Telemetry telemetry;
    private BiFunction<String[], String, GitIgnoreHelper.ExecResult> gitExec = GitIgnoreMatcherDefault::gitCheckIgnore;
    private Supplier<Map<String, Object>> settingsSupplier = Map::of;
    /** 变更监听器列表（多监听）· CC original: {@code skillsLoaded} signal 订阅列表（createSignal，loadSkillsDir.ts:832）。 */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * 默认 git check-ignore 执行器（ProcessBuilder 实现）· 对齐 CC isPathGitignored
     * （loadSkillsDir.ts:892 git check-ignore，exit 0=ignored，exit 128=fail open）。
     */
    private static final class GitIgnoreMatcherDefault {
        private GitIgnoreMatcherDefault() {}

        static GitIgnoreHelper.ExecResult gitCheckIgnore(String[] args, String cwd) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("git");
                if (args != null) {
                    Collections.addAll(cmd, args);
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (cwd != null && !cwd.isBlank()) {
                    pb.directory(new File(cwd));
                }
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = p.waitFor();
                return new GitIgnoreHelper.ExecResult(exitCode, stdout, "");
            } catch (Exception e) {
                // 非 git 仓库 / git 不可用 → fail open（CC exit 128 → false）
                if (log.isDebugEnabled()) {
                    log.debug("DynamicSkillsManager git check-ignore 执行失败, fail-open: {}", e.toString());
                }
                return new GitIgnoreHelper.ExecResult(128, "", "");
            }
        }
    }

    // ── POJO 兼容构造器（测试可直接 new）──
    public DynamicSkillsManager() {
    }

    // ── Spring 注入（required=false，无 bean 时保持默认）──

    /** 注入 SkillsLoader（复用目录加载，projectSettings 语义）· 对齐 CC loadSkillsFromSkillsDir（loadSkillsDir.ts:940-942）。 */
    @Autowired(required = false)
    public void setSkillsLoader(SkillsLoader loader) {
        if (loader != null) {
            this.skillsLoader = loader;
        }
    }

    /** 注入 Telemetry（T7 遥测）· 参考 SkillImprovementHook.java:304 recordEvent 先例。 */
    @Autowired(required = false)
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * 注入 git check-ignore 执行器（测试可注入桩）· CC original: execGitCheckIgnore（loadSkillsDir.ts:892）。
     */
    public void setGitExec(BiFunction<String[], String, GitIgnoreHelper.ExecResult> gitExec) {
        if (gitExec != null) {
            this.gitExec = gitExec;
        }
    }

    /**
     * 注入 plugin-only policy settings 读取器 · 对齐 CC isRestrictedToPluginOnly('skills')
     * （loadSkillsDir.ts:926 + PluginOnlyPolicy.java:53）。默认 Map::of = 不锁定。
     */
    public void setSettingsSupplier(Supplier<Map<String, Object>> settingsSupplier) {
        if (settingsSupplier != null) {
            this.settingsSupplier = settingsSupplier;
        }
    }

    /**
     * 注册变更监听（skillsLoaded.subscribe 等价）· 对齐 CC onDynamicSkillsLoaded
     * （loadSkillsDir.ts:839-851）：多监听 + 返回 unsubscribe 函数 + 每监听 try/catch
     * （△-6：旧 setOnChange 单监听 → 改多监听，unsubscribe 由消费方持返值清理）。
     *
     * @param callback 变更回调（null 忽略，返回 no-op unsubscribe）
     * @return 反注册函数（unsubscribe），调用后该监听不再触发
     */
    public Runnable onDynamicSkillsLoaded(Runnable callback) {
        if (callback == null) {
            return () -> {};
        }
        changeListeners.add(callback);
        return () -> changeListeners.remove(callback);
    }

    // ════════════════════════════════════════════════════════════════════════
    // A18: discoverSkillDirsForPaths · 对齐 CC loadSkillsDir.ts:861-915
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从文件路径向上走到 cwd（不含 cwd 本身）发现 .claude/skills 目录。
     *
     * <p>对齐 CC loadSkillsDir.ts:861-915：
     * <ul>
     *   <li>起点 = 文件父目录（CC :871 dirname(filePath)）</li>
     *   <li>上界 = cwd，前缀+分隔符判断避免 /project-backup 误匹配 /project（CC :876）</li>
     *   <li>每层 join(currentDir, '.claude', 'skills')（CC :877）</li>
     *   <li>dynamicSkillDirs 去重（hit/miss 都记录，CC :882）</li>
     *   <li>stat 存在后 gitignore 拦截（CC :892 isPathGitignored(currentDir)，block node_modules）</li>
     *   <li>返回 deeper-first 排序（CC :912-914）</li>
     * </ul>
     *
     * @param filePaths 文件路径列表
     * @param cwd       当前工作目录（发现上界，不含 cwd 本身）
     * @return 新发现的技能目录（deeper-first）
     */
    public List<String> discoverSkillDirsForPaths(List<String> filePaths, Path cwd) {
        if (filePaths == null || filePaths.isEmpty() || cwd == null) {
            return List.of();
        }
        String resolvedCwd = cwd.toAbsolutePath().normalize().toString();
        String sep = File.separator;
        Set<String> newDirs = new LinkedHashSet<>();
        for (String filePath : filePaths) {
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            Path current;
            try {
                Path file = Paths.get(filePath).toAbsolutePath().normalize();
                current = file.getParent();
            } catch (Exception e) {
                continue;
            }
            // 上界: cwd + 分隔符（CC :876 prefix+separator，不含 cwd 本身）
            while (current != null && current.toString().startsWith(resolvedCwd + sep)) {
                // 决策 D1/D6：每层 nexusai 技能目录优先 + claude 回落（项目 .claude/skills 已导入 .nexusai/skills）
                addSkillDirIfExists(current.resolve(NexusaiPaths.getProjectDirName()).resolve("skills"), filePath, resolvedCwd,
                    newDirs, dynamicSkillDirs);
                addSkillDirIfExists(current.resolve(".claude").resolve("skills"), filePath, resolvedCwd,
                    newDirs, dynamicSkillDirs);
                Path parent = current.getParent();
                if (parent == null || parent.equals(current)) {
                    break; // 到达根（CC :906）
                }
                current = parent;
            }
        }
        // deeper-first sort · CC :912-914 sort(b.split(pathSep).length - a.split(pathSep).length)
        List<String> result = new ArrayList<>(newDirs);
        result.sort(Comparator.comparingInt((String s) -> -s.split("[\\\\/]").length));
        return result;
    }

    /**
     * 记录一个技能目录（nexusai/claude skills 共用）· 已存在目录加入 newDirs（gitignore 拦截），
     * 不存在则仅记 miss（dynamicSkillDirs 去重，向上继续）。
     *
     * @param skillDir       待检查技能目录（.nexusai/skills 或 .claude/skills）
     * @param filePath       触发文件路径（日志）
     * @param resolvedCwd    解析后 cwd（gitignore 判定基准）
     * @param newDirs        发现的技能目录输出集合（Set，避免跨源重复）
     * @param dynamicSkillDirs 已见目录去重集合
     */
    private void addSkillDirIfExists(Path skillDir, String filePath, String resolvedCwd,
                                     Set<String> newDirs, Set<String> dynamicSkillDirs) {
        if (dynamicSkillDirs.add(skillDir.toString())) {
            if (Files.isDirectory(skillDir)) {
                // gitignore 拦截（CC :892 isPathGitignored(currentDir)）——只挡已存在目录
                if (!isPathGitignored(skillDir.getParent().toString(), resolvedCwd)) {
                    newDirs.add(skillDir.toString());
                    if (log.isDebugEnabled()) {
                        log.debug("[DynamicSkillsManager] 发现技能目录: {} (from {})", skillDir, filePath);
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("[DynamicSkillsManager] 跳过 gitignored 技能目录: {}", skillDir);
                    }
                }
            }
            // 目录不存在 → 已记录 miss，向上继续（CC :899-901）
        }
    }

    /** isPathGitignored · 对齐 CC loadSkillsDir.ts:892（git check-ignore，exit 0=ignored，128 fail-open）。 */
    private boolean isPathGitignored(String filePath, String cwd) {
        try {
            return GitIgnoreHelper.isPathGitignored(filePath, cwd, gitExec);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("DynamicSkillsManager isPathGitignored 异常, fail-open: {}", e.toString());
            }
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // A19: addSkillDirectories · 对齐 CC loadSkillsDir.ts:923-975
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从给定目录加载技能并合并进 dynamicSkills。目录离文件越近（更深）优先级越高。
     *
     * <p>对齐 CC loadSkillsDir.ts:923-975：
     * <ul>
     *   <li>门控 {@code !isSettingSourceEnabled('projectSettings') || isRestrictedToPluginOnly('skills')}
     *       → return（CC :925-927；Java 恒启用 projectSettings，仅 plugin-only 门控，concern #31）</li>
     *   <li>每目录加载（CC :940-942）</li>
     *   <li>reverse 处理：shallower 先让 deeper 覆盖（CC :945-951）</li>
     *   <li>新增&gt;0 → T7 遥测（CC :961-969）</li>
     *   <li>onChange 触发（CC :974 skillsLoaded.emit()）</li>
     * </ul>
     *
     * @param dirs 技能目录（deeper-first 排序）
     */
    public void addSkillDirectories(List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return;
        }
        if (!isProjectSettingsEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[DynamicSkillsManager] 动态技能发现跳过: projectSettings 禁用或 plugin-only policy (CC loadSkillsDir.ts:925-927)");
            }
            return;
        }
        Set<String> previousSkillNames = new HashSet<>(dynamicSkills.keySet());
        List<List<Command>> loaded = new ArrayList<>(dirs.size());
        for (String dir : dirs) {
            // CC :940-942 loadSkillsFromSkillsDir(dir, 'projectSettings') — Java 复用
            // SkillsLoader 无条件变体（动态目录不分离条件技能，对齐 CC addSkillDirectories）
            List<Command> cmds;
            try {
                cmds = skillsLoader.loadFromDirectoryUnconditional(dir);
            } catch (Exception e) {
                log.warn("[DynamicSkillsManager] 加载技能目录失败: {} cause={}", dir, e.toString());
                cmds = List.of();
            }
            loaded.add(cmds != null ? cmds : List.of());
        }
        // reverse 处理 · CC :945-951（shallower 先 → deeper 后 put 覆盖）
        for (int i = loaded.size() - 1; i >= 0; i--) {
            for (Command skill : loaded.get(i)) {
                if (skill != null) {
                    dynamicSkills.put(skill.getName(), skill);
                }
            }
        }
        int newSkillCount = 0;
        for (List<Command> l : loaded) {
            newSkillCount += l.size();
        }
        if (newSkillCount > 0) {
            long addedSkills = dynamicSkills.keySet().stream()
                .filter(n -> !previousSkillNames.contains(n))
                .count();
            if (log.isDebugEnabled()) {
                log.debug("[DynamicSkillsManager] 动态发现 {} 个技能 from {} 目录 (新增 {})",
                    newSkillCount, dirs.size(), addedSkills);
            }
            if (addedSkills > 0) {
                recordChanged(DynamicSkillChangeSource.FILE_OPERATION,
                    previousSkillNames.size(), dynamicSkills.size(), addedSkills, dirs.size());
            }
        }
        fireOnChange();
    }

    // ════════════════════════════════════════════════════════════════════════
    // A20: activateConditionalSkillsForPaths · 对齐 CC loadSkillsDir.ts:997-1058
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 激活 paths 匹配给定文件的条件技能。激活后移入 dynamicSkills，对模型可用。
     *
     * <p>对齐 CC loadSkillsDir.ts:997-1058：
     * <ul>
     *   <li>gitignore-style 匹配（CC :1012 ignore().add(skill.paths)）</li>
     *   <li>相对路径化（CC :1014-1016 relative(cwd, filePath) 或原路径）</li>
     *   <li>相对路径空 / {@code ..} 开头 / 绝对 → skip（CC :1021-1027）</li>
     *   <li>命中 → dynamicSkills.set + conditionalSkills.delete + activated 记录 + break（CC :1029-1038）</li>
     *   <li>命中&gt;0 → T7 遥测 + onChange（CC :1043-1054）</li>
     * </ul>
     *
     * @param filePaths 被操作的文件路径
     * @param cwd       当前工作目录（匹配相对路径基准）
     * @return 新激活的技能名列表
     */
    public List<String> activateConditionalSkillsForPaths(List<String> filePaths, Path cwd) {
        if (conditionalSkills.isEmpty() || filePaths == null || filePaths.isEmpty() || cwd == null) {
            return List.of();
        }
        List<String> activated = new ArrayList<>();
        // 快照迭代（避免 ConcurrentHashMap 迭代中 remove 的弱一致问题）
        for (Map.Entry<String, Command> entry : new ArrayList<>(conditionalSkills.entrySet())) {
            String name = entry.getKey();
            Command skill = entry.getValue();
            if (skill == null || skill.getPaths() == null || skill.getPaths().isEmpty()) {
                continue;
            }
            GitIgnoreMatcher matcher = new GitIgnoreMatcher(skill.getPaths());
            for (String filePath : filePaths) {
                String relativePath = toRelative(cwd, filePath);
                // CC :1021-1027 空 / .. 开头 / 绝对路径 → skip（ignore() 会 throw）
                if (relativePath == null || relativePath.isEmpty()
                    || relativePath.startsWith("..")
                    || relativePath.startsWith("/")
                    || relativePath.matches("^[A-Za-z]:.*")) {
                    continue;
                }
                if (matcher.ignores(relativePath)) {
                    dynamicSkills.put(name, skill);
                    conditionalSkills.remove(name);
                    activatedConditionalSkillNames.add(name);
                    activated.add(name);
                    if (log.isDebugEnabled()) {
                        log.debug("[DynamicSkillsManager] 激活条件技能 '{}' (matched path: {})", name, relativePath);
                    }
                    break;
                }
            }
        }
        if (!activated.isEmpty()) {
            recordChanged(DynamicSkillChangeSource.CONDITIONAL_PATHS,
                dynamicSkills.size() - activated.size(), dynamicSkills.size(), activated.size(), 0);
            fireOnChange();
        }
        return activated;
    }

    // ════════════════════════════════════════════════════════════════════════
    // A21-A24: 查询 / 清理 / 登记
    // ════════════════════════════════════════════════════════════════════════

    /** 获取全部动态技能 · CC original: {@code getDynamicSkills}（loadSkillsDir.ts:981-983）。 */
    public List<Command> getDynamicSkills() {
        return Collections.unmodifiableList(new ArrayList<>(dynamicSkills.values()));
    }

    /** 待激活条件技能数量 · CC original: {@code getConditionalSkillCount}（loadSkillsDir.ts:1063-1065）。 */
    public int getConditionalSkillCount() {
        return conditionalSkills.size();
    }

    /** 清除动态技能状态（测试用）· CC original: {@code clearDynamicSkills}（loadSkillsDir.ts:1070-1075）。 */
    public void clearDynamicSkills() {
        dynamicSkillDirs.clear();
        dynamicSkills.clear();
        conditionalSkills.clear();
        activatedConditionalSkillNames.clear();
        if (log.isDebugEnabled()) {
            log.debug("[DynamicSkillsManager] clearDynamicSkills() 清除 4 状态 (CC loadSkillsDir.ts:1070-1075)");
        }
    }

    /**
     * 清除条件技能状态 · CC original: {@code clearSkillCaches} 的条件状态双清
     * （loadSkillsDir.ts:809-810 {@code conditionalSkills.clear() + activatedConditionalSkillNames.clear()}）。
     *
     * <p>M27 实施（R2I-DEC-6 / R2D-DEC-1）：由 {@link SkillRegistry#refresh()} 调用 —— 对齐 CC
     * {@code clearSkillCaches}（loadSkillsDir.ts:806-811）双清语义。refresh 后重新加载时，带 paths
     * frontmatter 的技能重新进入条件分离（未激活态），已激活名集合一并重置。CC :828-829 注释
     * 「survives cache clears within a session」仅指常规加载周期，clearSkillCaches 显式双清（:809-810）。
     *
     * <p>与 {@link #clearDynamicSkills()}（CC clearDynamicSkills :1070-1075，清 4 态）不同：
     * 本方法<b>不清</b> {@code dynamicSkillDirs} / {@code dynamicSkills} —— CC clearSkillCaches 不清
     * 动态技能池，refresh 路径不得清空已激活动态技能（否则 skill 热更新后动态技能消失，偏离 CC）。
     */
    public void clearConditionalState() {
        conditionalSkills.clear();
        activatedConditionalSkillNames.clear();
        if (log.isDebugEnabled()) {
            log.debug("[DynamicSkillsManager] clearConditionalState() 双清条件状态 2 态 (CC loadSkillsDir.ts:809-810)");
        }
    }

    /**
     * 登记条件技能 · CC original: {@code conditionalSkills.set(name, skill)}（loadSkillsDir.ts:788-790，
     * getSkillDirCommands 条件分离后存储）。
     */
    public void registerConditional(Command skill) {
        if (skill == null || skill.getName() == null) {
            return;
        }
        conditionalSkills.put(skill.getName(), skill);
    }

    /**
     * 条件技能是否已激活 · CC original: {@code activatedConditionalSkillNames.has(name)}
     * （loadSkillsDir.ts:779 条件分离时判断，已激活 → 不再分离为 conditional）。
     */
    public boolean isActivatedConditionalSkill(String name) {
        return name != null && activatedConditionalSkillNames.contains(name);
    }

    /** 已激活条件技能名集合（SkillsLoader 条件分离 + 测试用）。 */
    public Set<String> activatedConditionalSkillNames() {
        return Collections.unmodifiableSet(activatedConditionalSkillNames);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 内部辅助
    // ════════════════════════════════════════════════════════════════════════

    /** addSkillDirectories 门控 · 对齐 CC loadSkillsDir.ts:925-927（Java 恒启用 projectSettings）。 */
    private boolean isProjectSettingsEnabled() {
        return !PluginOnlyPolicy.isRestrictedToPluginOnly(PluginOnlyPolicy.SURFACE_SKILLS, settingsSupplier);
    }

    /** T7 遥测 · 对齐 CC logEvent('tengu_dynamic_skills_changed', {...})（loadSkillsDir.ts:962-969/:1044-1051）。 */
    private void recordChanged(DynamicSkillChangeSource source, int previousCount,
                               int newCount, long addedCount, int directoryCount) {
        if (telemetry == null) {
            return;
        }
        telemetry.recordEvent(TENGU_DYNAMIC_SKILLS_CHANGED, Map.of(
            "source", source.ccValue(),
            "previousCount", previousCount,
            "newCount", newCount,
            "addedCount", addedCount,
            "directoryCount", directoryCount));
        if (log.isInfoEnabled()) {
            log.info("[DynamicSkillsManager] 动态技能变更: source={} previous={} new={} added={} dirs={}",
                source.ccValue(), previousCount, newCount, addedCount, directoryCount);
        }
    }

    /** onChange 触发 · 对齐 CC skillsLoaded.emit()（loadSkillsDir.ts:974/:1054，每监听 try/catch，异常不中断其他监听）。 */
    private void fireOnChange() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("[DynamicSkillsManager] onDynamicSkillsLoaded 监听器异常: {}", e.toString());
            }
        }
    }

    /**
     * 文件路径 → cwd 相对路径 · CC original: {@code isAbsolute(filePath) ? relative(cwd, filePath) : filePath}
     * （loadSkillsDir.ts:1014-1016）。跨盘符（Windows）返回 null（CC 等价 relative() 抛错 → isAbsolute 检查 skip）。
     */
    private static String toRelative(Path cwd, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path fp = Paths.get(filePath);
        if (fp.isAbsolute()) {
            try {
                return cwd.toAbsolutePath().normalize()
                    .relativize(fp.toAbsolutePath().normalize()).toString();
            } catch (IllegalArgumentException e) {
                return null; // 跨盘符
            }
        }
        return filePath;
    }
}
