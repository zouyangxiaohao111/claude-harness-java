package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.worktree.GitCommandRunner;
import com.nexusai.application.agent.config.GitInstructionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Git 状态上下文提供者 · 对齐 CC {@code getGitStatus}
 * （CC original: {@code getGitStatus} 进程级 memoize async
 * (Open-ClaudeCode/src/context.ts:36-111)）。
 *
 * <p>完整链（CC 实际 TS 源码行为）：
 * <ol>
 *   <li><b>getIsGit 前置</b>（context.ts:46）：沿 cwd 上溯找 {@code .git} 目录或文件
 *       （worktree/submodule 用文件），非 git → 记日志返回 null（:52-57）</li>
 *   <li><b>并行 5 命令</b>（context.ts:61-77）：branch / defaultBranch / status --short /
 *       log --oneline -n 5 / config user.name，均 stdout.trim()</li>
 *   <li><b>MAX_STATUS_CHARS=2000 截断</b>（:85-89）：status 超限 → substring + 截断提示</li>
 *   <li><b>块组装</b>（:96-103）：intro / Current branch / Main branch / Git user?(条件) /
 *       Status:(clean 兜底) / Recent commits:，块间 {@code '\n\n'}</li>
 *   <li><b>try/catch → null</b>（:104-110）：git 故障不阻断组装</li>
 * </ol>
 *
 * <p><b>会话级缓存（concern #1 决议）</b>：CC lodash memoize 为进程级全局；Spring 多会话服务下
 * 进程级缓存会跨会话串 gitStatus。本类按会话实例构建（随 AgentState 生命周期），
 * {@link #getGitStatus()} 实例级 memoize —— 会话内只算一次，对齐 CAP-SP07-B1 会话级模式。
 *
 * <p><b>测试注入（concern #8 决议）</b>：CC {@code NODE_ENV==='test'} 短路是 CLI 进程产物，
 * Spring 无 NODE_ENV。本类支持构造注入 {@link GitRunner} 假 runner，避免单测真跑 git 子进程。
 *
 * <p><b>git 命令 Java 映射（concern #6 决议）</b>：CC getBranch/getDefaultBranch 读文件系统 watcher
 * （gitFilesystem.ts:500-566）；Java 用等价 git 命令：getBranch={@code git branch --show-current}（
 * detached HEAD 时返回空 → 对齐 CC computeBranch 的 'HEAD' 兜底）；getDefaultBranch 依次
 * {@code git symbolic-ref --quiet refs/remotes/origin/HEAD}（剥前缀）→
 * {@code git show-ref --verify refs/remotes/origin/main|master} → 兜底 'main'
 * （对齐 computeDefaultBranch gitFilesystem.ts:544-566）；其余 3 命令直传 GitCommandRunner。
 */
public class GitStatusProvider {

    private static final Logger log = LoggerFactory.getLogger(GitStatusProvider.class);

    /** CC context.ts:20 MAX_STATUS_CHARS */
    public static final int MAX_STATUS_CHARS = 2000;

    /** CC context.ts:88-89 截断提示（CC original: 内联字符串, context.ts:88） */
    public static final String TRUNCATION_SUFFIX =
        "\n... (truncated because it exceeds 2k characters. If you need more information, run \"git status\" using BashTool)";

    /**
     * 可注入 git 子进程 runner · 测试注入假实现避免真跑 git。
     *
     * <p>CC original: {@code execFileNoThrow(gitExe(), [...])}
     * （context.ts:64/68/74；utils/execFileNoThrow.ts:26-44）。
     */
    @FunctionalInterface
    public interface GitRunner {
        GitCommandRunner.Result run(Path cwd, String... gitArgs);
    }

    /** 默认 runner · 直传 GitCommandRunner（对齐 CC execFileNoThrow，不抛异常） */
    private static final GitRunner DEFAULT_RUNNER = GitCommandRunner::run;

    private final Path cwd;
    private final GitRunner runner;

    /** 会话级 memoize 结果 · CC original: lodash memoize (context.ts:36) */
    private String cachedGitStatus = null;
    private boolean gitStatusComputed = false;

    /**
     * @param cwd    工作目录（null → 走 {@link CwdResolution#getCwd()} 统一入口；测试可注入临时目录）
     * @param runner git 命令 runner（测试注入假实现）
     */
    public GitStatusProvider(Path cwd, GitRunner runner) {
        this.cwd = cwd != null ? cwd : Path.of(CwdResolution.getCwd());
        this.runner = runner != null ? runner : DEFAULT_RUNNER;
    }

    /** 便捷构造：指定 cwd + 真实 GitCommandRunner。 */
    public GitStatusProvider(Path cwd) {
        this(cwd, DEFAULT_RUNNER);
    }

    /**
     * 便捷构造：默认 cwd + 真实 GitCommandRunner。
     *
     * <p><b>WF-1B / G6 / DEL-03</b>：对齐 CC {@code findGitRoot(getCwd())}（git.ts:222）——
     * git 状态锚定从 {@link CwdResolution#getCwd()} 统一入口取 cwd（override ?? sessionCwd ??
     * 绑定项目 ?? user.dir），替代旧 {@code Paths.get("").toAbsolutePath()}=user.dir 直读。
     * 绑定项目/worktree 场景取对仓库；无会话上下文时回落 user.dir（与进程启动 cwd 等价）。
     */
    public GitStatusProvider() {
        this(Path.of(CwdResolution.getCwd()), DEFAULT_RUNNER);
    }

    /**
     * 获取 git 状态块 · 会话级 memoize（对齐 CC lodash memoize，本会话内只算一次）。
     *
     * <p><b>settings 门控（SP-10 △1）</b>：入口在 memoize 检查后、isGit 前置前——
     * {@code !GitInstructionConfig.shouldIncludeGitInstructions()} 时缓存 null 并返回 null
     * （无 gitStatus 块）。对齐 CC {@code shouldIncludeGitInstructions}（gitSettings.ts:13-18
     * 三分支：env truthy → false / env defined falsy → true / 未定义 → settings ?? true，
     * Java 配置通道 {@code nexusai.git.include-instructions}，默认开启）。
     * 门控结果随会话 memoize 冻结（对齐 CC 进程级 memoize 冻结）；{@link #isGit()}
     * 不受门控（SubagentEnvInfo 走 isGit 不受影响）。
     *
     * @return 拼装好的 git 状态块；非 git 仓库、git 命令异常或 settings 门控关闭时 {@code null}
     *         （不阻断上下文组装，CC context.ts:52-57/:104-110）
     */
    public String getGitStatus() {
        if (gitStatusComputed) {
            return cachedGitStatus;
        }
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[GitStatusProvider] getGitStatus 开始: cwd={}", cwd);
        }

        // ── settings 门控（SP-10 △1，gitSettings.ts:13-18）──
        if (!GitInstructionConfig.shouldIncludeGitInstructions()) {
            log.info("[GitStatusProvider] settings 门控关闭（nexusai.git.include-instructions=false 或 env 关闭），跳过 git status（对齐 CC shouldIncludeGitInstructions，cwd={}）", cwd);
            cachedGitStatus = null;
            gitStatusComputed = true;
            return null;
        }

        String result;
        // ── getIsGit 前置（CC context.ts:46）──
        if (!isGit()) {
            log.info("[GitStatusProvider] 非 git 仓库，跳过 git status（对齐 CC context.ts:52-57，cwd={}）", cwd);
            result = null;
        } else {
            result = computeGitStatus(start);
        }

        cachedGitStatus = result;
        gitStatusComputed = true;
        if (log.isDebugEnabled()) {
            log.debug("[GitStatusProvider] getGitStatus 完成: 耗时 {} ms, hasGitStatus={}",
                System.currentTimeMillis() - start, result != null);
        }
        return result;
    }

    /**
     * 是否在 git 仓库中 · 对齐 CC {@code findGitRoot(cwd) !== null}
     * （CC original: {@code getIsGit} (git.ts:218-229) → {@code findGitRoot}
     * (git.ts:27-86，沿 cwd 上溯找 .git 目录/文件)）。
     */
    public boolean isGit() {
        return findGitRoot() != null;
    }

    /**
     * 沿 cwd 上溯查找 git 根 · 对齐 CC {@code findGitRoot}
     * （CC original: git.ts:27-86，.git 可以是目录（普通仓库）或普通文件（worktree/submodule））。
     *
     * @return 含 {@code .git} 的目录；未找到 → null
     */
    public Path findGitRoot() {
        Path current = cwd.toAbsolutePath();
        while (current != null) {
            Path gitPath = current.resolve(".git");
            if (Files.exists(gitPath)) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return null;
    }

    /**
     * worktree 判别 · 对齐 CC {@code resolveCanonicalRoot} 的 worktree 分支
     * （CC original: git.ts:123-183：.git 为普通文件且内容 {@code gitdir:} 前缀、
     * gitdir 目标（相对 .git 所在目录解析）含 commondir 普通文件 → worktree）。
     *
     * <p><b>[SP-11] 语义偏差已修</b>：主消费点 SystemPromptSections.envInfoSimpleCompute 已切 CC
     * 会话级判定（{@code SessionCwdHolder.isWorktreeBound} = CC getCurrentWorktreeSession !== null，
     * prompts.ts:675-681，仅 EnterWorktree 工具进入的会话消费 '!' 子弹）。本 git 级方法保留
     * （死代码不删规则：CC 有 worktree.ts 对应物），供诊断/其他消费。子模块（.git 文件无
     * commondir）→ false，与 CC resolveCanonicalRoot 判别一致。不复制 CC AutoMemPaths 安全校验
     * （commondir 结构/backlink）：本方法非权限边界，纯提示启发式，异常一律 → false。
     *
     * @return true = 当前目录在 git worktree 中（.git 文件 + gitdir 目标含 commondir）
     */
    public boolean isWorktree() {
        Path root = findGitRoot();
        if (root == null) {
            return false;
        }
        Path gitFile = root.resolve(".git");
        try {
            if (!Files.isRegularFile(gitFile)) {
                return false;
            }
            String content = Files.readString(gitFile).trim();
            if (!content.startsWith("gitdir:")) {
                return false;
            }
            Path gitDirTarget = root.resolve(content.substring("gitdir:".length()).trim());
            return Files.isRegularFile(gitDirTarget.resolve("commondir"));
        } catch (Exception e) {
            // 文件读取异常（权限/损坏）→ 按非 worktree 处理，不阻断 env 块
            log.debug("[GitStatusProvider] isWorktree 判定失败，按非 worktree 处理: cwd={} err={}", cwd, e.getMessage());
            return false;
        }
    }


    /**
     * 并行 5 git 命令 + 拼装（CC context.ts:59-103）。
     *
     * <p>try/catch → null（:104-110，git 故障不阻断组装）。
     */
    private String computeGitStatus(long start) {
        try {
            long gitCmdsStart = System.currentTimeMillis();
            CompletableFuture<String> branch = CompletableFuture.supplyAsync(this::fetchBranch);
            CompletableFuture<String> mainBranch = CompletableFuture.supplyAsync(this::fetchDefaultBranch);
            CompletableFuture<String> status = CompletableFuture.supplyAsync(
                () -> runAndTrim("--no-optional-locks", "status", "--short"));
            CompletableFuture<String> recentLog = CompletableFuture.supplyAsync(
                () -> runAndTrim("--no-optional-locks", "log", "--oneline", "-n", "5"));
            CompletableFuture<String> userName = CompletableFuture.supplyAsync(
                () -> runAndTrim("config", "user.name"));

            CompletableFuture.allOf(branch, mainBranch, status, recentLog, userName).join();
            String branchOut = branch.get();
            String mainBranchOut = mainBranch.get();
            String statusOut = status.get();
            String logOut = recentLog.get();
            String userNameOut = userName.get();

            if (log.isDebugEnabled()) {
                log.debug("[GitStatusProvider] 5 git 命令完成: 耗时 {} ms, status.length={}",
                    System.currentTimeMillis() - gitCmdsStart, statusOut.length());
            }

            // status 超 2000 截断（CC context.ts:85-89）
            String truncatedStatus = statusOut.length() > MAX_STATUS_CHARS
                ? statusOut.substring(0, MAX_STATUS_CHARS) + TRUNCATION_SUFFIX
                : statusOut;

            List<String> parts = new ArrayList<>();
            parts.add("This is the git status at the start of the conversation. Note that this status is a snapshot in time, and will not update during the conversation.");
            parts.add("Current branch: " + branchOut);
            parts.add("Main branch (you will usually use this for PRs): " + mainBranchOut);
            if (userNameOut != null && !userNameOut.isEmpty()) {
                parts.add("Git user: " + userNameOut);
            }
            parts.add("Status:\n" + (truncatedStatus.isEmpty() ? "(clean)" : truncatedStatus));
            parts.add("Recent commits:\n" + logOut);
            if (log.isDebugEnabled()) {
                log.debug("[GitStatusProvider] git_status_completed: 总耗时 {} ms, truncated={}",
                    System.currentTimeMillis() - start, statusOut.length() > MAX_STATUS_CHARS);
            }
            return String.join("\n\n", parts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[GitStatusProvider] git status 被中断，返回 null（对齐 CC context.ts:104-110 不阻断组装）: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            // CompletableFuture.join() 抛 CompletionException，.get() 抛 ExecutionException ——
            // 任一 git 子命令异常均归零为 null（CC context.ts:104-110，git 故障不阻断组装）
            log.error("[GitStatusProvider] git status 失败，返回 null（对齐 CC context.ts:104-110 不阻断组装）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 当前分支 · 对齐 CC computeBranch（gitFilesystem.ts:500-510）。
     *
     * <p>Java 用 {@code git branch --show-current}；detached HEAD 输出为空 → 对齐 CC
     * 返回 'HEAD'（CC readGitHead type!=='branch' 时兜底 'HEAD'）。
     */
    private String fetchBranch() {
        String out = runAndTrim("branch", "--show-current");
        return out.isEmpty() ? "HEAD" : out;
    }

    /**
     * 主分支 · 对齐 CC computeDefaultBranch（gitFilesystem.ts:544-566）。
     *
     * <ol>
     *   <li>{@code git symbolic-ref --quiet refs/remotes/origin/HEAD} → 剥 'refs/remotes/origin/' 前缀
     *       （CC readRawSymref :551-555）</li>
     *   <li>失败 → {@code git show-ref --verify refs/remotes/origin/main|master} 依次尝试
     *       （CC :559-564）</li>
     *   <li>都失败 → 'main'（CC :565 兜底）</li>
     * </ol>
     */
    private String fetchDefaultBranch() {
        String symref = runAndTrim("symbolic-ref", "--quiet", "refs/remotes/origin/HEAD");
        if (!symref.isEmpty()) {
            String prefix = "refs/remotes/origin/";
            if (symref.startsWith(prefix)) {
                return symref.substring(prefix.length());
            }
            return symref;
        }
        for (String candidate : new String[] {"main", "master"}) {
            String ref = runAndTrim("show-ref", "--verify", "refs/remotes/origin/" + candidate);
            if (!ref.isEmpty()) {
                return candidate;
            }
        }
        return "main";
    }

    /**
     * 执行 git 命令并 trim stdout · 对齐 CC {@code execFileNoThrow(...).then(({stdout}) => stdout.trim())}
     * （context.ts:64-77）。preserveOutputOnError=false → 非零退出码时 stdout 视为空
     * （execFileNoThrow.ts:134-136）。
     */
    private String runAndTrim(String... gitArgs) {
        GitCommandRunner.Result r = runner.run(cwd, gitArgs);
        return r.isSuccess() ? r.stdout().trim() : "";
    }
}
