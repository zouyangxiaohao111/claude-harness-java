package com.nexusai.application.agent.permission.source;

import com.nexusai.infra.util.GitIgnoreHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code <项目>/.nexusai/settings.local.json} 的 gitignore 守护 · 对齐 CC
 * {@code utils/git/gitignore.ts:53-98 addFileGlobRuleToGitignore}（调用点
 * {@code utils/settings/settings.ts:508-514}）。
 *
 * <h2>CC 真源（逐字，2026-09-18 复核）</h2>
 * <pre>
 * // settings.ts:508-514（updateSettingsForSource 写盘成功之后）
 * if (source === 'localSettings') {
 *   // Okay to add to gitignore async without awaiting
 *   void addFileGlobRuleToGitignore(
 *     getRelativeSettingsFilePathForSource('localSettings'),   // = join('.claude','settings.local.json')
 *     getOriginalCwd(),
 *   )
 * }
 * </pre>
 * <pre>
 * // gitignore.ts:53-98
 * export async function addFileGlobRuleToGitignore(filename, cwd = getCwd()) {
 *   if (!(await dirIsInGitRepo(cwd))) return
 *   const gitignoreEntry = `**&#47;${filename}`
 *   const testPath = filename.endsWith('/') ? `${filename}sample-file.txt` : filename
 *   if (await isPathGitignored(testPath, cwd)) return        // 已被任何 ignore 源覆盖 → 不重复加
 *   const globalGitignorePath = getGlobalGitignorePath()      // ~/.config/git/ignore
 *   await mkdir(dirname(globalGitignorePath), { recursive: true })
 *   ... content.includes(entry) ? return : appendFile(path, `\n${entry}\n`)
 *   ... ENOENT → writeFile(path, `${entry}\n`)
 * }   // 整体 try/catch → logError(error)，**永不抛**
 * </pre>
 *
 * <h3>三个要点（照 CC，不是自创）</h3>
 * <ol>
 *   <li><b>写到全局 ignore 文件</b> {@code ~/.config/git/ignore}，<b>不是</b>项目 {@code .gitignore}。
 *       理由：{@code local} 的语义是「个人、不共享」—— 往项目 {@code .gitignore} 写反而会产生一个
 *       需要提交的仓库改动，与目的自相矛盾；全局 ignore 一次覆盖该用户的所有项目。</li>
 *   <li><b>时机 = localSettings 文件写盘成功之后</b>（CC 是 async fire-and-forget；本仓为同步 POJO
 *       调用，等价语义 = 不阻断写盘 —— 见下）。</li>
 *   <li><b>失败永不抛</b>（CC 整体 try/catch + logError）。本仓按铁律「不许静默失效」提到 WARN，
 *       并给出可手工执行的补救指令。</li>
 * </ol>
 *
 * <h2>本仓等价物</h2>
 * <ul>
 *   <li>{@code filename} = {@code <projectDirName>/settings.local.json}
 *       （{@code .claude} → {@code .nexusai}，由 {@link com.nexusai.application.agent.skill.NexusaiPaths#getProjectDirName()} 动态给）</li>
 *   <li>{@code entry} = {@code **&#47;}<b>&lt;projectDirName&gt;</b>{@code /settings.local.json}</li>
 *   <li>{@code projectDirName} = {@code NexusaiPaths.getProjectDirName()}（生产 {@code .nexusai}）</li>
 *   <li>{@code cwd} = <b>该次写盘解析出的项目根</b>（读/写同址的同一值，见
 *       {@link LocalSettingsLoader#resolveProjectRoot(String)}）</li>
 * </ul>
 *
 * <p>注入全部为函数式接缝（与 {@link com.nexusai.infra.util.GitIgnoreHelper} 同风格），
 * 生产用默认实现（真 {@code git check-ignore} + 真文件 IO + 真 {@code ~/.config/git/ignore}），
 * 测试注入桩 ⇒ <b>任何测试都不会碰开发者 home 下的真实 ignore 文件</b>。
 */
@Component
public class LocalSettingsGitignore {

    private static final Logger log = LoggerFactory.getLogger(LocalSettingsGitignore.class);

    /** localSettings 的文件名（与 {@link LocalSettingsLoader} 同值；该常量在那边是 private）。 */
    static final String SETTINGS_LOCAL_FILE_NAME = "settings.local.json";

    /** git 子命令执行器 · 对齐 CC {@code execFileNoThrowWithCwd}（{@code git check-ignore}）。 */
    private BiFunction<String[], String, GitIgnoreHelper.ExecResult> gitExec =
        LocalSettingsGitignore::execGitCheckIgnore;

    /** 目录是否在 git 仓库内 · 对齐 CC {@code dirIsInGitRepo}（{@code git.ts:253} → {@code findGitRoot}）。 */
    private Predicate<String> dirIsInGitRepoFn = LocalSettingsGitignore::dirIsInGitRepo;

    /** 全局 ignore 文件路径 · 对齐 CC {@code getGlobalGitignorePath}（{@code gitignore.ts:43-45}）。 */
    private Supplier<String> globalGitignorePathSupplier =
        () -> GitIgnoreHelper.getGlobalGitignorePath(System.getProperty("user.home"));

    // ── 测试接缝 ──────────────────────────────────────────────────────────

    public void setGitExec(BiFunction<String[], String, GitIgnoreHelper.ExecResult> gitExec) {
        if (gitExec != null) {
            this.gitExec = gitExec;
        }
    }

    public void setDirIsInGitRepoFn(Predicate<String> fn) {
        if (fn != null) {
            this.dirIsInGitRepoFn = fn;
        }
    }

    public void setGlobalGitignorePathSupplier(Supplier<String> supplier) {
        if (supplier != null) {
            this.globalGitignorePathSupplier = supplier;
        }
    }

    /**
     * 确保 {@code <projectRoot>/<projectDirName>/settings.local.json} 被 git 忽略
     * （对齐 CC {@code addFileGlobRuleToGitignore}）。<b>永不抛</b>。
     *
     * @param projectRoot   该次写盘解析出的项目根（绝对路径；null/空白 ⇒ 记 WARN 跳过）
     * @param projectDirName 项目级配置目录名（{@code .nexusai}；null/空白 ⇒ 记 WARN 跳过）
     */
    public void ensureIgnored(String projectRoot, String projectDirName) {
        try {
            if (projectRoot == null || projectRoot.isBlank()
                    || projectDirName == null || projectDirName.isBlank()) {
                log.warn("[gitignore] settings.local.json 已写盘但项目根/项目目录名缺失，"
                    + "无法加入忽略列表（请手工把 **/{}/{} 加进全局 ignore）root={} dirName={}",
                    projectDirName, SETTINGS_LOCAL_FILE_NAME, projectRoot, projectDirName);
                return;
            }
            // ① 不在 git 仓库 ⇒ 无事可做（CC gitignore.ts:58-60）
            if (!dirIsInGitRepoFn.test(projectRoot)) {
                if (log.isDebugEnabled()) {
                    log.debug("[gitignore] {} 不在 git 仓库内，跳过 settings.local.json 忽略守护", projectRoot);
                }
                return;
            }
            String filename = projectDirName + "/" + SETTINGS_LOCAL_FILE_NAME;
            String entry = "**/" + filename;
            // ② 已被任何 ignore 源覆盖（项目 .gitignore / .git/info/exclude / 全局）⇒ 不重复加
            //    （CC gitignore.ts:62-71：用相对路径从 cwd 跑 `git check-ignore`）
            if (GitIgnoreHelper.isPathGitignored(filename, projectRoot, gitExec)) {
                if (log.isDebugEnabled()) {
                    log.debug("[gitignore] {} 已被现有 ignore 规则覆盖，跳过", filename);
                }
                return;
            }
            // ③ 追加到全局 ignore（CC gitignore.ts:73-95）
            String globalPathRaw = globalGitignorePathSupplier.get();
            Path globalPath = Paths.get(globalPathRaw);
            String content = Files.exists(globalPath) ? Files.readString(globalPath) : null;
            if (content != null && content.contains(entry)) {
                if (log.isDebugEnabled()) {
                    log.debug("[gitignore] 全局 ignore 已含 {}，跳过", entry);
                }
                return;
            }
            if (globalPath.getParent() != null) {
                Files.createDirectories(globalPath.getParent());
            }
            Files.writeString(globalPath, (content == null ? "" : "\n") + entry + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[gitignore] 已把 {} 加入全局 ignore（{}）：该文件是本机个人配置，不应进团队仓库",
                entry, globalPath);
        } catch (Exception e) {
            // CC 是 logError 吞掉；本仓铁律「不许静默失效」⇒ WARN + 手工补救指令（不阻断写盘）
            log.warn("[gitignore] 把 {}/{} 加入全局 ignore 失败（不阻断本次写盘，"
                + "请手工把它加进 ~/.config/git/ignore，否则该文件可能被误提交）root={} err={}",
                projectDirName, SETTINGS_LOCAL_FILE_NAME, projectRoot, e.toString());
        }
    }

    // ── 默认实现（生产）────────────────────────────────────────────────────

    /** {@code git check-ignore <relPath>}（cwd=项目根）· exit 0=ignored / 1=not / 128=不在仓库（fail open）。 */
    static GitIgnoreHelper.ExecResult execGitCheckIgnore(String[] args, String cwd) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            if (args != null) {
                java.util.Collections.addAll(cmd, args);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new java.io.File(cwd));
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();
            return new GitIgnoreHelper.ExecResult(exitCode, stdout, "");
        } catch (Exception e) {
            // git 不可用 / 非仓库 → exit 128 ⇒ isPathGitignored 返回 false（fail open，CC 同）
            if (log.isDebugEnabled()) {
                log.debug("[gitignore] git check-ignore 执行失败, fail-open: {}", e.toString());
            }
            return new GitIgnoreHelper.ExecResult(128, "", "");
        }
    }

    /**
     * 目录是否在 git 仓库内 · 对齐 CC {@code findGitRoot}（{@code git.ts:27-52}）：
     * 从 {@code startPath} 逐级向上找 {@code .git}（<b>目录或文件</b> —— worktree / submodule 里
     * {@code .git} 是文件），找到即 true。纯文件系统判定，不起子进程。
     */
    static boolean dirIsInGitRepo(String startPath) {
        if (startPath == null || startPath.isBlank()) {
            return false;
        }
        Path current;
        try {
            current = Paths.get(startPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return false;
        }
        while (current != null) {
            Path dotGit = current.resolve(".git");
            if (Files.isDirectory(dotGit) || Files.isRegularFile(dotGit)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
