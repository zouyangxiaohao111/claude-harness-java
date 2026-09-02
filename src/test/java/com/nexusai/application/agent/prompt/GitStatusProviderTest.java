package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.worktree.GitCommandRunner;
import com.nexusai.application.agent.config.GitInstructionConfig;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitStatusProvider 意图测试 · 对齐 CC {@code getGitStatus}（context.ts:36-111）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：getGitStatus 是 context 注入链的核心数据源 ——
 * git 门控/2000 截断/(clean) 兜底/故障降级直接决定 systemContext 的 gitStatus 块形态。
 * 测试钉死这些契约，防止回归到"无截断拼接"或"git 故障抛错阻断组装"等与 CC 相悖的实现。
 * 用假 runner（concern #8 决议）避免单测真跑 git 子进程。
 */
class GitStatusProviderTest {

    @TempDir
    Path tmp;

    /**
     * 假 git runner · 按参数键返回 canned stdout（对齐 CC execFileNoThrow 返回 stdout）。
     */
    private static class FakeRunner implements GitStatusProvider.GitRunner {
        private final Map<String, GitCommandRunner.Result> byKey = new ConcurrentHashMap<>();
        private boolean throwOnRun = false;

        FakeRunner status(String output) {
            byKey.put("status", new GitCommandRunner.Result(0, output, ""));
            return this;
        }

        FakeRunner defaultStatus() {
            byKey.put("status", new GitCommandRunner.Result(0, " M file1\n?? untracked", ""));
            return this;
        }

        FakeRunner throwing() {
            this.throwOnRun = true;
            return this;
        }

        @Override
        public GitCommandRunner.Result run(Path cwd, String... gitArgs) {
            if (throwOnRun) {
                throw new RuntimeException("fake git failure");
            }
            String joined = String.join(" ", gitArgs);
            if (joined.startsWith("branch")) {
                return new GitCommandRunner.Result(0, "feature/x", "");
            }
            if (joined.contains("symbolic-ref")) {
                return new GitCommandRunner.Result(0, "refs/remotes/origin/main", "");
            }
            if (joined.contains("show-ref")) {
                return new GitCommandRunner.Result(1, "", "");
            }
            if (joined.contains("--oneline")) {
                return new GitCommandRunner.Result(0, "abc1234 第五次提交\nabc1233 第四次提交\nabc1232 第三次提交\nabc1231 第二次提交\nabc1230 第一次提交", "");
            }
            if (joined.contains("user.name")) {
                return new GitCommandRunner.Result(0, "zcw", "");
            }
            if (joined.contains("status")) {
                GitCommandRunner.Result r = byKey.get("status");
                return r != null ? r : new GitCommandRunner.Result(0, "", "");
            }
            return new GitCommandRunner.Result(0, "", "");
        }
    }

    private static GitStatusProvider provider(Path cwd, GitStatusProvider.GitRunner runner) {
        return new GitStatusProvider(cwd, runner);
    }

    private static void mkdirGit(Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".git"));
    }

    // ── getIsGit 前置：非 git 仓库 → null（CC context.ts:52-57）──

    @Test
    @DisplayName("非 git 仓库 → getGitStatus() 返回 null，不跑 git 命令（context.ts:52-57）")
    void notGitRepo_returnsNull() {
        Path noGitDir = tmp.resolve("no-git");
        GitStatusProvider p = provider(noGitDir, new FakeRunner().defaultStatus());

        assertThat(p.isGit()).as("无 .git 目录 → 非 git").isFalse();
        assertThat(p.getGitStatus()).as("非 git → null，不阻断组装").isNull();
    }

    // ── git 仓库：完整块 + (clean) 兜底 ──

    @Test
    @DisplayName("git 仓库 → 含 Current branch/Main branch/Git user/Status/Recent commits 块；空 status → (clean)（context.ts:96-103）")
    void gitRepo_buildsFullBlock() throws Exception {
        Path gitDir = tmp.resolve("repo");
        mkdirGit(gitDir);
        GitStatusProvider p = provider(gitDir, new FakeRunner().status(""));

        String block = p.getGitStatus();

        assertThat(block).as("intro 行固定").startsWith("This is the git status at the start of the conversation.");
        assertThat(block).as("Current branch 行").contains("Current branch: feature/x");
        assertThat(block).as("Main branch 行").contains("Main branch (you will usually use this for PRs): main");
        assertThat(block).as("Git user 行（userName 非空）").contains("Git user: zcw");
        assertThat(block).as("空 status → (clean) 兜底").contains("Status:\n(clean)");
        assertThat(block).as("Recent commits 块").contains("Recent commits:\nabc1234");
    }

    @Test
    @DisplayName("git 仓库非空 status → 进入 Status 块（非 clean）；首行前导空格被 trim（对齐 CC stdout.trim()，context.ts:66）")
    void gitRepo_nonEmptyStatus_rendered() throws Exception {
        Path gitDir = tmp.resolve("repo2");
        mkdirGit(gitDir);
        GitStatusProvider p = provider(gitDir, new FakeRunner().status(" M file1\n?? untracked"));

        String block = p.getGitStatus();
        assertThat(block).as("非空 status 原样渲染，不误判 clean").contains("Status:\nM file1\n?? untracked");
        assertThat(block).as("CC stdout.trim() 剥离前导空格（' M' → 'M'）").doesNotContain("Status:\n M file1");
    }

    // ── 会话级 memoize：只算一次（CC lodash memoize，本会话内冻结）──

    @Test
    @DisplayName("会话级 memoize：多次调用只跑一次 git 命令（对齐 CC lodash memoize，context.ts:36）")
    void memoized_runnerCalledOnce() throws Exception {
        Path gitDir = tmp.resolve("repo3");
        mkdirGit(gitDir);
        CountingRunner runner = new CountingRunner();
        GitStatusProvider p = provider(gitDir, runner);

        p.getGitStatus();
        p.getGitStatus();

        assertThat(runner.runCount()).as("memoize 后只执行一轮 5 命令").isEqualTo(5);
    }

    /** 计数 runner：每次 run 累加。 */
    private static final class CountingRunner implements GitStatusProvider.GitRunner {
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        int runCount() {
            return count.get();
        }

        @Override
        public GitCommandRunner.Result run(Path cwd, String... gitArgs) {
            count.incrementAndGet();
            String joined = String.join(" ", gitArgs);
            if (joined.startsWith("branch")) {
                return new GitCommandRunner.Result(0, "main", "");
            }
            if (joined.contains("symbolic-ref")) {
                return new GitCommandRunner.Result(0, "refs/remotes/origin/main", "");
            }
            if (joined.contains("--oneline")) {
                return new GitCommandRunner.Result(0, "c1 提交", "");
            }
            if (joined.contains("user.name")) {
                return new GitCommandRunner.Result(0, "", "");
            }
            return new GitCommandRunner.Result(0, "", "");
        }
    }

    // ── MAX_STATUS_CHARS=2000 截断（CC context.ts:85-89）──

    @Test
    @DisplayName("status 超 2000 字符 → 截断 + 截断提示，含 BashTool 指引（context.ts:85-89）")
    void statusOver2000_truncatedWithSuffix() throws Exception {
        Path gitDir = tmp.resolve("repo4");
        mkdirGit(gitDir);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            big.append('x');
        }
        GitStatusProvider p = provider(gitDir, new FakeRunner().status(big.toString()));

        String block = p.getGitStatus();
        String statusLine = block.substring(block.indexOf("Status:\n") + "Status:\n".length(),
            block.indexOf("\n\nRecent commits"));
        assertThat(statusLine).as("截断后 = 2000 前缀 + 截断提示（CC context.ts:85-89 是 substring+拼接，非截到 2000 就停）")
            .isEqualTo("x".repeat(GitStatusProvider.MAX_STATUS_CHARS) + GitStatusProvider.TRUNCATION_SUFFIX);
        assertThat(statusLine).as("含截断提示与 BashTool 指引").endsWith(
            "\n... (truncated because it exceeds 2k characters. If you need more information, run \"git status\" using BashTool)");
    }

    @Test
    @DisplayName("status 恰 2000 字符 → 不截断")
    void statusExactly2000_notTruncated() throws Exception {
        Path gitDir = tmp.resolve("repo5");
        mkdirGit(gitDir);
        String exact = "y".repeat(GitStatusProvider.MAX_STATUS_CHARS);
        GitStatusProvider p = provider(gitDir, new FakeRunner().status(exact));

        String block = p.getGitStatus();
        String statusLine = block.substring(block.indexOf("Status:\n") + "Status:\n".length(),
            block.indexOf("\n\nRecent commits"));
        assertThat(statusLine).as("恰 2000 不截断（CC 用 > 非 >=）").isEqualTo(exact);
    }

    // ── 故障降级：git 命令异常 → null（CC context.ts:104-110）──

    @Test
    @DisplayName("git 命令抛异常 → getGitStatus() 返回 null（不阻断组装，context.ts:104-110）")
    void gitFailure_returnsNull() throws Exception {
        Path gitDir = tmp.resolve("repo6");
        mkdirGit(gitDir);
        GitStatusProvider p = provider(gitDir, new FakeRunner().throwing());

        assertThat(p.getGitStatus()).as("git 故障 → null，上下文组装不中断").isNull();
    }

    // ── SP-10 △1：settings 门控（gitSettings.ts:13-18 shouldIncludeGitInstructions 三分支）──

    @Test
    @DisplayName("settings 门控关闭（nexusai.git.include-instructions=false）→ getGitStatus() 返回 null（SP-10 △1，gitSettings.ts:13-18）")
    void settingsOff_returnsNull() throws Exception {
        Path gitDir = tmp.resolve("repo8");
        mkdirGit(gitDir);
        GitInstructionConfig.setConfiguredForTest(false);
        try {
            assertThat(provider(gitDir, new FakeRunner().defaultStatus()).getGitStatus())
                .as("settings=false → 门控短路，无 gitStatus 块")
                .isNull();
        } finally {
            GitInstructionConfig.reset();
        }
    }

    @Test
    @DisplayName("守卫：settings 未配置（默认）→ getGitStatus() 正常返回块（CC settings ?? true）")
    void settingsDefault_returnsBlock() throws Exception {
        Path gitDir = tmp.resolve("repo9");
        mkdirGit(gitDir);
        GitInstructionConfig.reset();

        assertThat(provider(gitDir, new FakeRunner().defaultStatus()).getGitStatus())
            .as("默认开启（对齐 CC settings ?? true）→ gitStatus 块正常")
            .contains("Current branch: feature/x");
    }

    // ── defaultBranch 兜底链（CC computeDefaultBranch gitFilesystem.ts:544-566）──

    @Test
    @DisplayName("origin/HEAD symref 缺失 → 依次探测 main/master → 兜底 main（gitFilesystem.ts:559-565）")
    void defaultBranch_fallbackToMain() throws Exception {
        Path gitDir = tmp.resolve("repo7");
        mkdirGit(gitDir);
        GitStatusProvider p = provider(gitDir, new FakeRunner() {
            @Override
            public GitCommandRunner.Result run(Path cwd, String... gitArgs) {
                String joined = String.join(" ", gitArgs);
                if (joined.startsWith("branch")) {
                    return new GitCommandRunner.Result(0, "main", "");
                }
                if (joined.contains("symbolic-ref")) {
                    return new GitCommandRunner.Result(1, "", ""); // symref 缺失
                }
                if (joined.contains("show-ref")) {
                    return new GitCommandRunner.Result(1, "", ""); // main/master 都缺失
                }
                if (joined.contains("--oneline")) {
                    return new GitCommandRunner.Result(0, "c1 提交", "");
                }
                return new GitCommandRunner.Result(0, "", "");
            }
        });

        assertThat(p.getGitStatus()).as("symref 与 main/master 均缺失 → 兜底 main").contains("Main branch (you will usually use this for PRs): main");
    }

    // ════════════════════════════════════════════════════════════════
    // WF-1B / G6 / DEL-03：无参构造走 CwdResolution（对齐 CC findGitRoot(getCwd()) git.ts:222）
    // ════════════════════════════════════════════════════════════════

    /**
     * WHY（规则九 · 测试验证意图）：CC {@code getIsGit}（git.ts:222）调用 {@code findGitRoot(getCwd())}
     * —— {@code getCwd()} 无参取全局 STATE.cwd（cwd.ts:26-32）。Java 端无参构造旧实现固定
     * {@code Paths.get("").toAbsolutePath()=user.dir}（JVM 启动目录），在绑定项目/worktree 场景会
     * 锚定错仓库（G6）。无参构造必须走 {@link CwdResolution#getCwd()} —— 绑定项目层覆盖 user.dir
     * 时取对仓库。本测试钉死"无参构造锚定绑定项目根"语义，防回归到 user.dir 直读。
     */
    @Test
    @DisplayName("无参构造 → findGitRoot 锚定 SessionProjectRoot 绑定项目（对齐 CC findGitRoot(getCwd()) git.ts:222）")
    void noArgConstructor_walksCwdResolution_boundProjectOverridesUserDir() throws Exception {
        Path boundProject = tmp.resolve("bound-project");
        mkdirGit(boundProject);
        String sessionId = "wf-1b-git-" + UUID.randomUUID();
        RequestContext.setSession(sessionId);
        SessionProjectRoot.setForSession(sessionId, boundProject.toString());
        try {
            GitStatusProvider p = new GitStatusProvider(); // 无参

            assertThat(p.isGit()).as("绑定项目含 .git → 无参构造走 CwdResolution → isGit=true").isTrue();
            assertThat(p.findGitRoot()).as("findGitRoot 锚定绑定项目根（非 user.dir）")
                .isEqualTo(boundProject.toRealPath());
        } finally {
            SessionProjectRoot.clearSession(sessionId);
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("无参构造 cwd 与 CwdResolution.getCwd() 一致（无绑定回落 user.dir，对齐 CC getOriginalCwd 进程启动兜底）")
    void noArgConstructor_cwdEqualsCwdResolution() {
        // 无 sessionId/无绑定 → CwdResolution.getCwd() 回落 user.dir；无参构造须与之一致
        GitStatusProvider noArg = new GitStatusProvider();
        GitStatusProvider viaResolution = new GitStatusProvider(Path.of(CwdResolution.getCwd()));

        assertThat(noArg.findGitRoot()).as("无参构造 findGitRoot 与 CwdResolution.getCwd() 派生一致")
            .isEqualTo(viaResolution.findGitRoot());
    }
}
