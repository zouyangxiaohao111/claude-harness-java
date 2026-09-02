package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter.ParsedMarkdown;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Markdown 配置加载器 · 对齐 CC {@code utils/markdownConfigLoader.ts:234-430}
 * （{@code getProjectDirsUpToHome} + {@code loadMarkdownFilesForSubdir}）。
 *
 * <h2>CC 对应（snake_case → camelCase，行号标注）</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #getProjectDirsUpToHome(String, String)}</td><td>{@code getProjectDirsUpToHome}</td><td>markdownConfigLoader.ts:234-289</td></tr>
 *   <tr><td>{@link #loadMarkdownFilesForSubdir(String, String)}</td><td>{@code loadMarkdownFilesForSubdir}</td><td>markdownConfigLoader.ts:297-430</td></tr>
 *   <tr><td>{@link #clearCache()}</td><td>{@code loadMarkdownFilesForSubdir.cache.clear()}</td><td>loadSkillsDir.ts:808</td></tr>
 *   <tr><td>{@link MarkdownFile}</td><td>{@code MarkdownFile}</td><td>markdownConfigLoader.ts:40-46</td></tr>
 * </table>
 *
 * <h2>偏离（Java 简化，登记 concerns）</h2>
 * <ul>
 *   <li><b>去重键 realpath</b>：CC loadMarkdownFilesForSubdir 用 dev:ino（lstat bigint，:159-172），Java
 *       标准 API 无跨平台 dev/ino（fileKey 在 Windows 不可靠），改用 realpath（与 getSkillDirCommands
 *       的 getFileIdentity realpath 同构，CC loadSkillsDir.ts:118-124），first-wins 语义不变。
 *       <b>[P3-1 决策记录 · 规则七显式择一]</b>：2026-08-04 实测 —— Node lstat 在 Windows NTFS 返回非零
 *       dev/ino（dev=310269562/ino=57702370225775063），CC 去重在本平台真实生效；而 Java
 *       {@code BasicFileAttributes.fileKey()} 实测恒 null（D:\ 与 temp 均 null）→ 若对齐 fileKey 将
 *       永久 fail-open 不去重，既 RED 现有 dedupFirstWins 测试（:127-142）又偏离 CC 本平台真实行为。
 *       故维持 realpath：与 CC 实际去重行为等价（同一物理文件折叠为 1），平台无关。</li>
 *   <li><b>worktree 主仓回退</b>（CC :320-335 findCanonicalGitRoot 回退）：△-4 已补齐
 *       （{@link #applyWorktreeFallback} + {@link #findCanonicalRoot}，含 CC git.ts:142-170 backlink
 *       安全校验，FIX-B1/拍板#3）。</li>
 *   <li><b>resolveStopBoundary</b>（CC :191-220）：Java 无 findGitRoot 工具，自实现 .git 祖先查找
 *       （子模块/嵌套仓库边界简化，单仓库语义一致）。</li>
 *   <li><b>tengu_dir_search 遥测</b>（CC :416）：✗-2 已补齐（{@link #emitDirSearchTelemetry}，
 *       经 {@link #setTelemetry} 静态桥接；未注入遥测适配层 → 跳过，对齐 CC logEvent best-effort）。
 *       <b>[拍板#7 · NG-LD-1 关闭]</b>：生产接线已由本类 {@code @Component} 构造器桥接
 *       （Spring 启动注入 Telemetry → 静态字段，对齐 PostCompactionState / MemoryBareModeConfig
 *       静态桥接模式），不再依赖组合根手动调用 setTelemetry。</li>
 * </ul>
 */
@Component
public final class MarkdownConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownConfigLoader.class);

    private static final ParseSkillFrontmatter PARSER = new ParseSkillFrontmatter();

    /**
     * 文件搜索超时预算 · CC original: {@code AbortSignal.timeout(3000)}（markdownConfigLoader.ts:559，
     * loadMarkdownFiles ripgrep/native 搜索 3s 上限）。P2-3 实证后决定：Java walkFileTree 同步遍历
     * 以墙钟 deadline 施加同等上限（慢盘/网络挂载/超大目录防挂起，见 {@link #listMarkdownFiles}）。
     *
     * <p>包可见可变字段（非 final）：同包测试 {@code MarkdownConfigLoaderTest} 直接覆写为负值模拟
     * "已超时" 验证截断路径（Java 25 反射不可改 static final），测试结束复位 3000L。
     */
    static long FILE_SEARCH_TIMEOUT_MS = 3000L;

    /**
     * memoize 缓存 · CC original: {@code loadMarkdownFilesForSubdir = memoize(...)}（markdownConfigLoader.ts:297，
     * resolver :429 {@code (subdir, cwd) => `${subdir}:${cwd}`}）。键 = "{subdir}:{cwd}"。
     */
    private static final ConcurrentHashMap<String, List<MarkdownFile>> MEMOIZE_CACHE = new ConcurrentHashMap<>();

    /**
     * tengu_dir_search 遥测适配层（静态桥接）· CC original: {@code logEvent('tengu_dir_search', ...)}
     * （markdownConfigLoader.ts:416）。经 {@link #setTelemetry} 桥接（含 {@code @Component} 构造器
     * 生产注入）；null（POJO/测试未注入）→ 遥测跳过（对齐 CC logEvent best-effort 语义）。
     */
    private static volatile Telemetry telemetry;

    /**
     * 注入遥测适配层（静态桥接，对齐 MemoryBareModeConfig 静态桥接模式）· null 空安全（遥测关闭）。
     * 生产接线由本类 {@code @Component} 构造器在 Spring 启动时注入（拍板#7，NG-LD-1 关闭）；
     * 本静态方法保留供测试/POJO 场景显式注入或复位（null）。
     */
    public static void setTelemetry(Telemetry telemetry) {
        MarkdownConfigLoader.telemetry = telemetry;
    }

    /**
     * Markdown 文件元数据 · CC original: {@code MarkdownFile}（markdownConfigLoader.ts:40-46）
     * <pre>
     * type MarkdownFile = {
     *   filePath: string;     // 文件绝对路径
     *   baseDir: string;      // 所属配置目录（managed/user/project）
     *   frontmatter: FrontmatterData; // 解析后的 YAML frontmatter
     *   content: string;      // 去除 frontmatter 后的正文
     *   source: SettingSource // 'policySettings' | 'userSettings' | 'projectSettings'
     * }
     * </pre>
     */
    public record MarkdownFile(
            String filePath,
            String baseDir,
            Map<String, Object> frontmatter,
            String content,
            String source) {}

    /**
     * Spring 生产接线构造器 · 对齐 PostCompactionState / MemoryBareModeConfig 静态桥接模式
     * （{@code @Component + @Autowired(required=false) + 静态字段桥接}）。
     *
     * <p>✗-2 遥测接线（拍板#7 · NG-LD-1 关闭）：本类为静态工具类（全静态方法），但以
     * {@code @Component} 使 Spring 容器启动时实例化本 bean 并把 {@link Telemetry} bean 注入到
     * 静态 {@link #telemetry} 字段 —— 使 tengu_dir_search 在生产真实发射（此前 setTelemetry
     * 无生产调用方 → 生产 telemetry 恒 null → 事件不发射）。required=false 容错：无 Telemetry
     * bean 的裁剪上下文 → null → 遥测跳过（对齐 CC logEvent best-effort）。测试经
     * {@link #setTelemetry} 直接注入/复位。
     *
     * @param telemetry Telemetry bean（可 null）
     */
    @Autowired(required = false)
    public MarkdownConfigLoader(Telemetry telemetry) {
        MarkdownConfigLoader.telemetry = telemetry;
        if (telemetry != null) {
            log.info("MarkdownConfigLoader 生产接线：Telemetry 注入成功 → tengu_dir_search 遥测启用"
                + "（CC markdownConfigLoader.ts:416，拍板#7）");
        }
    }

    /**
     * 从 cwd 向上逐层收集 {@code .nexusai/<subdir>}（优先）+ {@code .claude/<subdir>}（回落）目录
     * （最具体在前）· CC original: {@code getProjectDirsUpToHome(subdir, cwd)}（markdownConfigLoader.ts:234-289）。
     * <b>[T3 内容读兼容]</b>：D6 项目 .claude/ 一次性导入 .nexusai/ 后 nexusai 项目内容权威，
     * 逐层先扫 {@code .nexusai/<subdir>} 再扫 {@code .claude/<subdir>}（同层 nexusai 在前，去重时 nexusai 赢）。
     *
     * <p>语义：
     * <ul>
     *   <li>home 本身不检查（CC :245-251「don't check it, as it's loaded separately as userDir」），
     *       normalize 比较 break</li>
     *   <li>每层 statSync 存在性过滤（CC :260-265，isFsInaccessible 静默 / 其余 rethrow）；
     *       Java 用 Files.isDirectory（缺失返回 false，访问不可达 SecurityException 静默）</li>
     *   <li>git root 处理完 break（CC :267-275，阻止仓库外父目录 .claude 泄漏进项目）</li>
     *   <li>parent==current break（CC :278-283 根保护）</li>
     * </ul>
     *
     * @param subdir 子目录名（'skills' | 'commands'，CC ClaudeConfigDirectory :29-36）
     * @param cwd    起始目录
     * @return 存在的 {@code .claude/<subdir>} 目录列表（从 cwd 向上，最具体在前）
     */
    public static List<String> getProjectDirsUpToHome(String subdir, String cwd) {
        String home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize().toString();
        String gitRoot = findStopBoundary(cwd);
        String current = Path.of(cwd).toAbsolutePath().normalize().toString();
        List<String> dirs = new ArrayList<>();

        while (true) {
            // home 本身不检查（CC :245-251，normalize 比较 break）
            if (pathEquals(current, home)) {
                break;
            }
            // T3: D6 项目 .claude/ 内容一次性导入 .nexusai/ 后，nexusai 项目内容权威 —— 逐层优先扫
            //   .nexusai/<subdir>，回落 .claude/<subdir>（同层 nexusai 在前，realpath/name 去重时 nexusai 赢）
            String nexusaiSubdir = Paths.get(current, NexusaiPaths.getProjectDirName(), subdir).toString();
            String claudeSubdir = Paths.get(current, ".claude", subdir).toString();
            try {
                if (Files.isDirectory(Paths.get(nexusaiSubdir))) {
                    dirs.add(nexusaiSubdir);
                }
            } catch (SecurityException e) {
                // isFsInaccessible 等价 → 静默（CC :263-265）
                if (log.isDebugEnabled()) {
                    log.debug("访问 .nexusai/{} 不可达（静默跳过）：{} cause={}",
                        subdir, nexusaiSubdir, e.toString());
                }
            }
            try {
                if (Files.isDirectory(Paths.get(claudeSubdir))) {
                    dirs.add(claudeSubdir);
                }
            } catch (SecurityException e) {
                // isFsInaccessible 等价 → 静默（CC :263-265）
                if (log.isDebugEnabled()) {
                    log.debug("访问 .claude/{} 不可达（静默跳过）：{} cause={}",
                        subdir, claudeSubdir, e.toString());
                }
            }
            // git root 处理完 break（CC :267-275）
            if (gitRoot != null && pathEquals(current, gitRoot)) {
                break;
            }
            Path parent = Paths.get(current).getParent();
            if (parent == null) {
                break;
            }
            String parentStr = parent.toString();
            if (parentStr.equals(current)) {
                break; // 根保护（CC :278-283）
            }
            current = parentStr;
        }
        if (log.isDebugEnabled()) {
            log.debug("getProjectDirsUpToHome('{}', cwd={}) → {} 个目录：{}", subdir, cwd, dirs.size(), dirs);
        }
        return dirs;
    }

    /**
     * 加载 managed/user/project 三类的 markdown 文件 · CC original:
     * {@code loadMarkdownFilesForSubdir(subdir, cwd)}（markdownConfigLoader.ts:297-430）。
     *
     * <p>语义：
     * <ul>
     *   <li>memoize（键 = "{subdir}:{cwd}"，CC :428-430），{@link #clearCache()} 显式失效</li>
     *   <li>managed 恒载（CC :337-345 policySettings）+ user 条件载（:346-356）+ project up-to-home（:357-372）；
     *       Java 恒启用 user/projectSettings（无 isSettingSourceEnabled 概念，concern #2）</li>
     *   <li><b>[T3 双目录]</b> user 源拆两层（nexusai 自有根优先 + claude 回落），project 逐层
     *       {@code .nexusai} 优先 + {@code .claude} 回落；subdir ∈ {skills,commands,agents} 加
     *       name first-wins 去重层（{@link #dedupByName}，nexusai 覆盖 claude）</li>
     *   <li>realpath 去重 first-wins（managed &gt; nexusai用户 &gt; claude用户 &gt; project，CC :377-414，dev:ino 简化见类 JavaDoc）</li>
     *   <li>tengu_dir_search 遥测（CC :416-424，✗-2 已接）+ worktree 主仓回退（CC :320-335，△-4 已补）</li>
     * </ul>
     *
     * @param subdir 子目录名（'skills' | 'commands'）
     * @param cwd    项目遍历起始目录
     * @return 去重后的 MarkdownFile 列表（不保证顺序稳定）
     */
    public static List<MarkdownFile> loadMarkdownFilesForSubdir(String subdir, String cwd) {
        String key = subdir + ":" + cwd;
        List<MarkdownFile> cached = MEMOIZE_CACHE.get(key);
        if (cached != null) {
            if (log.isDebugEnabled()) {
                log.debug("loadMarkdownFilesForSubdir 命中 memoize（键={}，{} 文件）", key, cached.size());
            }
            return cached;
        }
        // CC :302 searchStartTime —— 仅缓存未命中（真实搜索）时计时（memoize 命中不发射遥测）
        long searchStartTime = System.currentTimeMillis();

        // T3: 内容读兼容（nexusai 复刻版 .claude 改造）—— 用户源拆两层：
        //   nexusai 自有根（~/.{appName}/<subdir>，NexusaiPaths）优先 + claude（~/.claude/<subdir>）回落。
        String nexusaiUserDir = Paths.get(NexusaiPaths.getAppConfigHomeDir(), subdir).toString();
        String claudeUserDir = Paths.get(ClaudePaths.getClaudeConfigHomeDir(), subdir).toString();
        String managedDir = Paths.get(ClaudePaths.getManagedFilePath(), ".claude", subdir).toString();
        List<String> projectDirs = getProjectDirsUpToHome(subdir, cwd);

        // △-4: worktree 主仓回退（CC markdownConfigLoader.ts:320-335）
        applyWorktreeFallback(subdir, cwd, projectDirs);

        List<MarkdownFile> managedFiles = loadMarkdownFiles(managedDir, "policySettings");
        List<MarkdownFile> nexusaiUserFiles = loadMarkdownFiles(nexusaiUserDir, "userSettings");
        List<MarkdownFile> claudeUserFiles = loadMarkdownFiles(claudeUserDir, "userSettings");
        List<MarkdownFile> userFiles = new ArrayList<>();
        userFiles.addAll(nexusaiUserFiles);
        userFiles.addAll(claudeUserFiles);
        List<MarkdownFile> projectFiles = new ArrayList<>();
        for (String projectDir : projectDirs) {
            projectFiles.addAll(loadMarkdownFiles(projectDir, "projectSettings"));
        }

        // 合并 + realpath 去重：优先级 managed > nexusai用户 > claude用户 > project（CC :377-414）
        List<MarkdownFile> allFiles = new ArrayList<>();
        allFiles.addAll(managedFiles);
        allFiles.addAll(nexusaiUserFiles);
        allFiles.addAll(claudeUserFiles);
        allFiles.addAll(projectFiles);
        List<MarkdownFile> deduplicated = dedupByRealpath(allFiles, subdir);
        // T3: name first-wins 去重层（subdir ∈ {skills, commands, agents}）—— 必须在 realpath
        //   去重之后（先折叠 symlink 同物理文件，保留既有语义，再按逻辑名折叠双目录同内容；
        //   nexusai 用户根与 claude 用户根是不同物理目录，realpath 不折叠，需 name 层）。
        if ("skills".equals(subdir) || "commands".equals(subdir) || "agents".equals(subdir)) {
            deduplicated = dedupByName(deduplicated, subdir);
        }

        // ✗-2: tengu_dir_search 遥测（CC markdownConfigLoader.ts:416-424 无条件发射，仅真实搜索时）
        emitDirSearchTelemetry(subdir, searchStartTime, managedFiles, userFiles, projectFiles, projectDirs);

        if (log.isDebugEnabled()) {
            log.debug("loadMarkdownFilesForSubdir('{}', cwd={}) 加载完成: managed={} user={} project={} → 去重 {}",
                subdir, cwd, managedFiles.size(), userFiles.size(), projectFiles.size(), deduplicated.size());
        }
        MEMOIZE_CACHE.put(key, deduplicated);
        return deduplicated;
    }

    /**
     * tengu_dir_search 遥测 · CC original: markdownConfigLoader.ts:416-424
     * {@code logEvent('tengu_dir_search', {durationMs, managedFilesFound, userFilesFound,
     * projectFilesFound, projectDirsSearched, subdir})}。telemetry 未注入（POJO/测试）→ 跳过。
     */
    private static void emitDirSearchTelemetry(String subdir, long searchStartTime,
                                               List<MarkdownFile> managedFiles,
                                               List<MarkdownFile> userFiles,
                                               List<MarkdownFile> projectFiles,
                                               List<String> projectDirs) {
        Telemetry t = telemetry;
        if (t == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("durationMs", System.currentTimeMillis() - searchStartTime);
        attrs.put("managedFilesFound", managedFiles.size());
        attrs.put("userFilesFound", userFiles.size());
        attrs.put("projectFilesFound", projectFiles.size());
        attrs.put("projectDirsSearched", projectDirs.size());
        attrs.put("subdir", subdir);
        t.recordEvent("tengu_dir_search", attrs);
    }

    /**
     * 清空 memoize 缓存 · CC original: {@code clearSkillCaches} 内
     * {@code loadMarkdownFilesForSubdir.cache?.clear?.()}（loadSkillsDir.ts:806-811）。
     * 磁盘变更后调用（SkillRegistry.refresh / P1-16 watcher），否则 legacy 命令陈旧。
     */
    public static void clearCache() {
        int size = MEMOIZE_CACHE.size();
        MEMOIZE_CACHE.clear();
        if (log.isDebugEnabled()) {
            log.debug("MarkdownConfigLoader 清空 memoize 缓存（{} 键，CC loadSkillsDir.ts:808）", size);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 内部辅助
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 递归加载目录下所有 .md 文件 · 等价 CC loadMarkdownFiles（ripgrep {@code rg --files -g '*.md'}
     * 递归扫描；Java 用 Files.walk 递归，SKILL.md 位于子目录（skillA/SKILL.md）也能发现）。
     * 目录不存在 → 空列表（CC :413-419 readdir 失败 → []）。
     */
    private static List<MarkdownFile> loadMarkdownFiles(String dir, String source) {
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<MarkdownFile> result = new ArrayList<>();
        for (Path f : listMarkdownFiles(root)) {
            try {
                String raw = Files.readString(f, StandardCharsets.UTF_8);
                ParsedMarkdown pm = PARSER.parseFrontmatter(raw, f.toString());
                result.add(new MarkdownFile(
                    f.toAbsolutePath().normalize().toString(),
                    root.toAbsolutePath().normalize().toString(),
                    pm.frontmatter() != null ? pm.frontmatter() : new LinkedHashMap<>(),
                    pm.content(),
                    source));
            } catch (IOException e) {
                log.warn("读取 markdown 文件失败（跳过）：{} cause={}", f, e.toString());
            }
        }
        return result;
    }

    /**
     * 递归列出目录下所有 .md 文件（follow symlink + 循环检测）· 对齐 CC loadMarkdownFiles
     * （ripgrep {@code --files --hidden --follow --no-ignore --glob '*.md'}，markdownConfigLoader.ts:564-568，
     * {@code --follow} = follow symlink；循环检测对齐 native findMarkdownFilesNative 的 visitedDirs，
     * markdownConfigLoader.ts:451-539）。△-5 补齐：旧 {@code Files.walk} 不 follow symlink →
     * walkFileTree(FOLLOW_LINKS) + realpath 循环检测。
     */
    private static List<Path> listMarkdownFiles(Path root) {
        List<Path> files = new ArrayList<>();
        Set<String> visitedDirs = new HashSet<>();
        // P2-3: 文件搜索 3s 超时（实证后决定）· 对齐 CC loadMarkdownFiles（markdownConfigLoader.ts:559
        //   {@code AbortSignal.timeout(3000)}）。实证：典型 .claude/skills 树（~1000 文件）100-200ms，
        //   大型树（20000 文件）1.7-2.8s、深树（100 层）1.6s+ —— 慢盘/网络挂载/超大目录可触及 3s。
        //   Java 同步 walkFileTree 无 AbortSignal，用墙钟 deadline 在遍历中检查：超时 → TERMINATE
        //   中止遍历 + warn，返回已收集的部分结果（与 CC 语义不同——CC AbortSignal.timeout(3000) →
        //   ripGrep 抛 AbortError → loadMarkdownFiles catch isFsInaccessible 不命中 → rethrow → 该源整体
        //   失败；Java 无异常逃逸，超时仅截断剩余遍历）。
        final long deadline = System.currentTimeMillis() + FILE_SEARCH_TIMEOUT_MS;
        try {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (System.currentTimeMillis() > deadline) {
                            log.warn("文件搜索超时（{}ms），中止遍历 {}（对齐 CC AbortSignal.timeout(3000) 超时语义；"
                                    + "该源返回已收集 {} 个文件）",
                                FILE_SEARCH_TIMEOUT_MS, root, files.size());
                            return FileVisitResult.TERMINATE;
                        }
                        // 循环检测（symlink 环）· CC native walk :463-482 dev:ino/realpath visitedDirs
                        String key = realpathKey(dir);
                        if (!visitedDirs.add(key)) {
                            if (log.isDebugEnabled()) {
                                log.debug("跳过已访问目录（循环 symlink）：{}", dir);
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (System.currentTimeMillis() > deadline) {
                            log.warn("文件搜索超时（{}ms），中止遍历 {}（对齐 CC AbortSignal.timeout(3000) 超时语义；"
                                    + "该源返回已收集 {} 个文件）",
                                FILE_SEARCH_TIMEOUT_MS, root, files.size());
                            return FileVisitResult.TERMINATE;
                        }
                        // 按路径名（symlink 名）匹配 *.md（CC --glob '*.md' 匹配路径，非目标）
                        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        if (log.isDebugEnabled()) {
                            log.debug("跳过不可访问路径（follow symlink 失败）：{} cause={}", file, exc.toString());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            log.warn("递归列出目录失败：{} cause={}", root, e.toString());
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    /** realpath 解析（fail-open 返回 normalize 绝对路径）· 循环检测 key。 */
    private static String realpathKey(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    /**
     * realpath 去重 first-wins · CC original: markdownConfigLoader.ts:377-414（dev:ino 简化，见类 JavaDoc）。
     * 无法解析（fail-open null）→ 保留。
     */
    private static List<MarkdownFile> dedupByRealpath(List<MarkdownFile> files, String subdir) {
        Set<String> seen = new HashSet<>();
        List<MarkdownFile> result = new ArrayList<>();
        int removed = 0;
        for (MarkdownFile file : files) {
            String fileId;
            try {
                fileId = Paths.get(file.filePath()).toRealPath().toString();
            } catch (IOException e) {
                fileId = null;
            }
            if (fileId == null) {
                result.add(file);
                continue;
            }
            if (seen.add(fileId)) {
                result.add(file);
            } else {
                removed++;
                if (log.isDebugEnabled()) {
                    log.debug("跳过重复文件 '{}' (same realpath already loaded)：{}", file.filePath(), subdir);
                }
            }
        }
        if (removed > 0 && log.isDebugEnabled()) {
            log.debug("loadMarkdownFilesForSubdir('{}') 去重移除 {} 个文件（same realpath via symlinks）", subdir, removed);
        }
        return result;
    }

    /**
     * T3: name first-wins 去重层（内容读兼容 · nexusai 复刻版 .claude 改造）。
     *
     * <p>必须在 {@link #dedupByRealpath} 之后执行：优先级 = 列表序（managed &gt; nexusai用户 &gt;
     * claude用户 &gt; project），按子目录去重键（{@link #markdownName}）first-wins —— 同键时
     * 前源（nexusai）覆盖后源（claude），nexusai 无同名则 claude 正常加载（后源同名丢弃）。
     *
     * @param files  realpath 去重后的文件列表（已按优先级排序）
     * @param subdir 子目录名（'skills' | 'commands' | 'agents'）
     * @return name 去重后的文件列表
     */
    private static List<MarkdownFile> dedupByName(List<MarkdownFile> files, String subdir) {
        Set<String> seen = new HashSet<>();
        List<MarkdownFile> result = new ArrayList<>();
        int removed = 0;
        for (MarkdownFile file : files) {
            String name = markdownName(file, subdir);
            if (name == null || name.isBlank() || seen.add(name)) {
                result.add(file);
            } else {
                removed++;
                if (log.isDebugEnabled()) {
                    log.debug("跳过同名文件 '{}' (name first-wins，nexusai 优先)：{}", file.filePath(), subdir);
                }
            }
        }
        if (removed > 0 && log.isDebugEnabled()) {
            log.debug("loadMarkdownFilesForSubdir('{}') name 去重移除 {} 个文件（同 name，nexusai 优先）", subdir, removed);
        }
        return result;
    }

    /**
     * 子目录去重键 · 供 {@link #dedupByName} 折叠双目录同内容。
     *
     * <p>agents → 优先 frontmatter {@code name}（agentType 身份，加前缀防与路径键串扰），缺失回落
     * 子目录相对路径；其余（skills/commands）→ <subdir> 后的<b>完整相对路径</b>（如
     * {@code skills/foo/SKILL.md} → {@code foo/SKILL.md}、{@code commands/bar.md} → {@code bar.md}）。
     *
     * <p>用完整相对路径而非仅首段：nexusai 与 claude 镜像（同相对路径）仍折叠、nexusai 在前 → nexusai
     * 赢；同时避免同一技能目录内多个 .md（如 SKILL.md + README.md）被误折叠删除（它们相对路径不同）。
     */
    private static String markdownName(MarkdownFile file, String subdir) {
        if ("agents".equals(subdir)) {
            Object name = file.frontmatter().get("name");
            if (name instanceof String s && !s.isBlank()) {
                return "agent:" + s;
            }
        }
        Path p = Paths.get(file.filePath());
        String fileName = p.getFileName() != null ? p.getFileName().toString() : "";
        // 定位 <subdir> 段，取其后全部路径段（完整相对路径）作为去重键
        List<String> segs = new ArrayList<>();
        Path cur = p;
        while (cur != null && cur.getFileName() != null) {
            segs.add(cur.getFileName().toString());
            cur = cur.getParent();
        }
        java.util.Collections.reverse(segs); // 根 → 文件
        int subdirIdx = -1;
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).equals(subdir)) {
                subdirIdx = i;
                break;
            }
        }
        if (subdirIdx >= 0 && subdirIdx + 1 < segs.size()) {
            return String.join("/", segs.subList(subdirIdx + 1, segs.size()));
        }
        return fileName;
    }

    /**
     * 向上查找最近的 .git 祖先目录作为停止边界 · 等价 CC resolveStopBoundary（markdownConfigLoader.ts:191-220）
     * 的单仓库简化版（findGitRoot(cwd) 核心：从 cwd 向上找含 .git 的祖先）。
     * 无 .git → null（向上走到 home）。
     */
    private static String findStopBoundary(String cwd) {
        Path current = Path.of(cwd).toAbsolutePath().normalize();
        while (true) {
            if (Files.exists(current.resolve(".git"))) {
                return current.toString();
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                return null;
            }
            current = parent;
        }
    }

    /**
     * worktree 主仓回退 · CC original: loadMarkdownFilesForSubdir 内 worktree fallback
     * （markdownConfigLoader.ts:320-335）。worktree 的 .git 是文件（{@code gitdir: ...}），
     * {@link #findStopBoundary} 停在 worktree 根看不到主仓；当 worktree 根无 {@code .claude/<subdir>}
     * 时回退加载主仓副本（sparse-checkout 下命令/技能不丢失，anthropics/claude-code#29599/#28182）。
     * 常规仓库（canonicalRoot == gitRoot）→ 无回退。
     */
    private static void applyWorktreeFallback(String subdir, String cwd, List<String> projectDirs) {
        String gitRoot = findStopBoundary(cwd);
        if (gitRoot == null) {
            return;
        }
        String canonicalRoot = findCanonicalRoot(gitRoot);
        if (canonicalRoot == null || gitRoot.equals(canonicalRoot)) {
            return; // 常规仓库（非 worktree）→ 无回退
        }
        // T3: worktree 已有 .nexusai/<subdir> 或 .claude/<subdir> 任一 → 不重复加载主仓（CC :317-318）
        String worktreeNexusaiSubdir = Paths.get(gitRoot, NexusaiPaths.getProjectDirName(), subdir).toString();
        String worktreeClaudeSubdir = Paths.get(gitRoot, ".claude", subdir).toString();
        boolean worktreeHasSubdir = projectDirs.stream()
            .anyMatch(dir -> pathEquals(dir, worktreeNexusaiSubdir) || pathEquals(dir, worktreeClaudeSubdir));
        if (worktreeHasSubdir) {
            return;
        }
        // 主仓副本：.nexusai 优先（D6 导入后 nexusai 权威），.claude 回落（name 去重时 nexusai 赢）
        String mainNexusaiSubdir = Paths.get(canonicalRoot, NexusaiPaths.getProjectDirName(), subdir).toString();
        boolean nexusaiAdded = projectDirs.stream().anyMatch(dir -> dir.equals(mainNexusaiSubdir));
        if (!nexusaiAdded) {
            projectDirs.add(mainNexusaiSubdir);
            if (log.isDebugEnabled()) {
                log.debug("worktree 主仓回退：追加主仓 .nexusai/{} → {}（CC markdownConfigLoader.ts:320-335，T3 nexusai 优先）",
                    subdir, mainNexusaiSubdir);
            }
        }
        String mainClaudeSubdir = Paths.get(canonicalRoot, ".claude", subdir).toString();
        boolean claudeAdded = projectDirs.stream().anyMatch(dir -> dir.equals(mainClaudeSubdir));
        if (!claudeAdded) {
            projectDirs.add(mainClaudeSubdir);
            if (log.isDebugEnabled()) {
                log.debug("worktree 主仓回退：追加主仓 .claude/{} → {}（CC markdownConfigLoader.ts:320-335，T3 claude 回落）",
                    subdir, mainClaudeSubdir);
            }
        }
    }

    /**
     * 解析 worktree .git 文件 → 主仓根 · CC original: {@code findCanonicalGitRoot}（git.ts:123-183）。
     * 常规仓库 .git 是目录 → 返回 gitRoot；worktree .git 是文件（{@code gitdir: ...}）→ 沿
     * gitdir → commondir 链解析主仓工作目录。submodule（无 commondir）/解析失败 → 返回 gitRoot。
     *
     * <p>FIX-B1（拍板#3）：补齐 CC git.ts:142-170 backlink 安全校验（2 条件：worktrees 结构 + gitdir
     * 回指 realpath(gitRoot)/.git），防恶意仓库伪造 .git+commondir 指向受信仓库 .git/worktrees/<subdir>
     * 把受信内容加载进攻击者会话。任一条件不成立或读失败 → 返回 gitRoot（fail-closed，无 worktree 回退）。
     */
    private static String findCanonicalRoot(String gitRoot) {
        Path gitPath = Paths.get(gitRoot, ".git");
        if (Files.isDirectory(gitPath)) {
            return gitRoot; // 常规仓库
        }
        try {
            String content = Files.readString(gitPath, StandardCharsets.UTF_8).trim();
            if (!content.startsWith("gitdir:")) {
                return gitRoot;
            }
            Path worktreeGitDir = Paths.get(gitRoot)
                .resolve(content.substring("gitdir:".length()).trim())
                .toAbsolutePath().normalize();
            Path commondirFile = worktreeGitDir.resolve("commondir");
            if (!Files.exists(commondirFile)) {
                return gitRoot; // submodule 无 commondir（CC git.ts:137-138）
            }
            String commonDirRel = Files.readString(commondirFile, StandardCharsets.UTF_8).trim();
            Path commonDir = worktreeGitDir.resolve(commonDirRel).toAbsolutePath().normalize();

            // SECURITY（CC git.ts:142-170）：.git 文件与 commondir 均来自克隆/下载的仓库（攻击者可控）。
            // 未校验时恶意仓库可把 commondir 指向受信仓库的 .git/worktrees/<subdir>，绕过信任对话把
            // 受信 .claude/settings.json 内容（hook 等）加载进攻击者会话。两条件必须同时成立
            // （git worktree add 实际生成的结构，CC :147-155）：
            //   1. worktreeGitDir 必须是 <commonDir>/worktrees 的直接子目录（CC :156-158）
            //      → commondir 文件必须位于解析出的 common dir 内，而非攻击者仓库内
            //   2. <worktreeGitDir>/gitdir 回指 realpath(gitRoot)/.git（CC :165-170）
            //      → 攻击者不能靠猜路径借用受信仓库的现有 worktree 条目
            Path worktreeGitDirParent = worktreeGitDir.getParent();
            Path expectedWorktreesDir = commonDir.resolve("worktrees").normalize();
            if (worktreeGitDirParent == null
                    || !pathEquals(worktreeGitDirParent.toString(), expectedWorktreesDir.toString())) {
                if (log.isDebugEnabled()) {
                    log.debug("findCanonicalRoot: 拒绝 worktree 结构（worktreeGitDir 非 <commonDir>/worktrees 直接子目录）："
                        + " {}（CC git.ts:156-158）", worktreeGitDir);
                }
                return gitRoot;
            }
            // git 用 strbuf_realpath() 写 gitdir（symlink 已解析），而 gitRoot 仅词法解析；先 realpath
            // gitRoot 目录再 join '.git' —— 不 realpath .git 文件本身（CC :160-164 注释），否则符号链接
            // .git 会让攻击者借用受害者的 back-link。
            Path gitdirFile = worktreeGitDir.resolve("gitdir");
            if (!Files.exists(gitdirFile)) {
                return gitRoot; // 无 back-link → 非合法 worktree（CC :165 读失败 catch → gitRoot）
            }
            Path backlink = Paths.get(Files.readString(gitdirFile, StandardCharsets.UTF_8).trim()).toRealPath();
            Path gitRootReal = Paths.get(gitRoot).toRealPath();
            Path expectedBacklink = gitRootReal.resolve(".git").normalize();
            if (!pathEquals(backlink.toString(), expectedBacklink.toString())) {
                if (log.isDebugEnabled()) {
                    log.debug("findCanonicalRoot: 拒绝 back-link（gitdir 不回指 realpath(gitRoot)/.git）："
                        + " {} != {}（CC git.ts:165-170）", backlink, expectedBacklink);
                }
                return gitRoot;
            }
            if (!".git".equals(commonDir.getFileName().toString())) {
                return commonDir.toString(); // bare-repo worktree（CC git.ts:173-174）
            }
            return commonDir.getParent().toString(); // 主仓工作目录（CC git.ts:176 dirname(commonDir)）
        } catch (IOException | RuntimeException e) {
            return gitRoot;
        }
    }

    /** 路径等价比较（Windows 驱动器字母大小写不敏感 + 分隔符归一化，CC normalizePathForComparison 等价）。 */
    private static boolean pathEquals(String a, String b) {
        return normalizeForCompare(a).equals(normalizeForCompare(b));
    }

    private static String normalizeForCompare(String p) {
        if (p == null) {
            return "";
        }
        String s = p.replace('\\', '/');
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return win ? s.toLowerCase(Locale.ROOT) : s;
    }
}
