package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [IMP-M-P0-1] AutoMemPaths 路径解析链 · 对齐 CC {@code memdir/paths.ts}.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的 auto-memory 目录是 per-project 作用域
 * ({@code <memoryBase>/projects/<sanitized-git-root>/memory/}) + override/settings 链
 * (CLAUDE_COWORK_MEMORY_PATH_OVERRIDE → settings.autoMemoryDirectory → 默认 per-project) +
 * validateMemoryPath 安全校验 (拒绝相对/根/Windows 盘符根/UNC/null 字节)。旧 Java 用全局
 * {@code ~/.nexusai/memory} (DEFAULT_MEMORY_DIR)，无法区分项目 → 跨项目记忆互相污染 + 无安全校验。
 * 本测试锁定: per-project 路径、override 链、settings 链、4 类非法路径拒绝、尾分隔符契约。
 */
@DisplayName("[IMP-M-P0-1] AutoMemPaths 路径解析链对齐 CC memdir/paths.ts")
class AutoMemPathsTest {

    /** 用注入 supplier 构造隔离实例（不依赖真实 env / 用户 home）。 */
    private static AutoMemPaths paths(String projectRoot, String memoryBase,
                                      String override, String settingsDir) {
        return new AutoMemPaths(
            () -> projectRoot,
            () -> memoryBase,
            () -> override,
            () -> settingsDir);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. getAutoMemPath — per-project 路径 + 尾分隔符契约
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("per-project: <memoryBase>/projects/<sanitized-git-root>/memory/ + 尾分隔符 (paths.ts:223-235)")
    void getAutoMemPath_perProjectShape(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        // WHY: CC 按项目隔离 auto-memory 目录（paths.ts:229-232 join(projectsDir, sanitizePath(getAutoMemBase()), 'memory')）。
        //       所有 worktree 共享同一 git-root → 同一 auto-memory 目录（#24382）。旧 Java 全局 ~/.nexusai/memory
        //       使不同项目共享同一记忆池 → 项目 A 的记忆被项目 B 的 prompt 读到。
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, null);

        String autoMem = paths.getAutoMemPath();

        String sanitized = AutoMemPaths.sanitizePath(projectRoot.toString());
        String expected = memoryBase.resolve("projects").resolve(sanitized).resolve("memory").toString();
        assertThat(autoMem)
            .as("auto-memory 目录必须落在 <memoryBase>/projects/<sanitized-git-root>/memory/ 且以路径分隔符结尾（尾分隔符契约）")
            .startsWith(expected + java.io.File.separator)
            .endsWith(java.io.File.separator);
    }

    @Test
    @DisplayName("git 仓库内 projectRoot → 用 canonical git root 作为项目标识（paths.ts getAutoMemBase:203-205）")
    void getAutoMemPath_usesGitRoot(@TempDir Path memoryBase, @TempDir Path projectRoot) throws Exception {
        // WHY: getAutoMemBase = findCanonicalGitRoot(projectRoot) ?? projectRoot（paths.ts:203-205）。
        //       从 git 仓库子目录启动时，必须用仓库根而非 cwd，否则同一仓库不同子目录启动会产生不同项目 key。
        initGitRepo(projectRoot);
        Path subdir = Files.createDirectories(projectRoot.resolve("src/main"));

        AutoMemPaths paths = paths(subdir.toString(), memoryBase.toString(), null, null);

        String autoMem = paths.getAutoMemPath();
        String sanitized = AutoMemPaths.sanitizePath(projectRoot.toString());
        assertThat(autoMem)
            .as("git 仓库内启动 → 项目标识 = git 根（而非 cwd 子目录）")
            .startsWith(memoryBase.resolve("projects").resolve(sanitized).resolve("memory") + java.io.File.separator);
    }

    // ════════════════════════════════════════════════════════════════
    // 2. override / settings 链
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CLAUDE_COWORK_MEMORY_PATH_OVERRIDE 生效：getAutoMemPath 直接返回 override + hasAutoMemPathOverride (paths.ts:161-166/194-196)")
    void getAutoMemPath_overrideWins(@TempDir Path memoryBase, @TempDir Path projectRoot,
                                     @TempDir Path overrideDir) {
        // WHY: Cowork 用 CLAUDE_COWORK_MEMORY_PATH_OVERRIDE 将 memory 重定向到 space-scoped mount
        //       （paths.ts:154-159）——per-session cwd 含 VM 进程名会导致每次会话不同 project-key。
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), overrideDir.toString(), null);

        assertThat(paths.hasAutoMemPathOverride())
            .as("设置了合法 override → hasAutoMemPathOverride()==true")
            .isTrue();
        assertThat(paths.getAutoMemPath())
            .as("override 设置后 getAutoMemPath 直接返回 override 路径（尾分隔符契约）")
            .isEqualTo(overrideDir + java.io.File.separator);
    }

    @Test
    @DisplayName("非法 override（相对路径）→ 视为未设置，回落默认 per-project (paths.ts:161-166 validateMemoryPath)")
    void getAutoMemPath_relativeOverrideIgnored(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        // WHY: override 是 SDK 程序化设置，必须绝对路径（validateMemoryPath expandTilde=false）；相对路径是危险候选
        //       （会被当作 CWD 相对解释）。非法 override 必须静默回落默认，不得让 getAutoMemPath 返回危险路径。
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), "../evil", null);

        assertThat(paths.hasAutoMemPathOverride()).isFalse();
        assertThat(paths.getAutoMemPath())
            .as("非法 override 必须被忽略，回落 per-project 默认")
            .startsWith(memoryBase.resolve("projects").toString());
    }

    @Test
    @DisplayName("settings.autoMemoryDirectory 生效且排除 projectSettings（paths.ts:179-186）")
    void getAutoMemPath_settingsDirWins(@TempDir Path memoryBase, @TempDir Path projectRoot,
                                        @TempDir Path settingsDir) {
        // WHY: settings.json autoMemoryDirectory 是用户显式选择（可信源 policy/local/user），
        //       必须排除 projectSettings（.claude/settings.json 仓库内、攻击者可控制，paths.ts:172-177 安全注释）。
        //       注入 supplier 代表「仅可信源读取」——settings 链优先于默认 per-project。
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, settingsDir.toString());

        assertThat(paths.getAutoMemPath())
            .as("settings.autoMemoryDirectory 设置后优先于默认 per-project 路径")
            .isEqualTo(settingsDir + java.io.File.separator);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. validateMemoryPath — 4 类非法路径拒绝
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateMemoryPath 拒绝相对路径（../foo，paths.ts:102/139-148）")
    void validateMemoryPath_rejectsRelative() {
        // WHY: 相对路径会被当作 CWD 相对解释（paths.ts:102 安全注释）；作为 read-allowlist root 危险。
        assertThat(AutoMemPaths.validateMemoryPath("../foo", false)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("foo/bar", false)).isNull();
    }

    @Test
    @DisplayName("validateMemoryPath 拒绝根/近根路径（/ 或长度 < 3，paths.ts:103/141）")
    void validateMemoryPath_rejectsRoot() {
        assertThat(AutoMemPaths.validateMemoryPath("/", false)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("/a", false)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("//", false)).isNull();
    }

    @Test
    @DisplayName("validateMemoryPath 拒绝 Windows 盘符根（C:\\ → C:，paths.ts:104/142）")
    void validateMemoryPath_rejectsDriveRoot() {
        assertThat(AutoMemPaths.validateMemoryPath("C:\\", false)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("C:", false)).isNull();
    }

    @Test
    @DisplayName("validateMemoryPath 拒绝 UNC（\\\\\\\\server\\\\share，paths.ts:105/143）")
    void validateMemoryPath_rejectsUnc() {
        assertThat(AutoMemPaths.validateMemoryPath("\\\\server\\share", false)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("//server/share", false)).isNull();
    }

    @Test
    @DisplayName("validateMemoryPath 拒绝 null 字节（paths.ts:106/145）")
    void validateMemoryPath_rejectsNullByte() {
        assertThat(AutoMemPaths.validateMemoryPath("/tmp/foo\0bar", false)).isNull();
    }

    @Test
    @DisplayName("validateMemoryPath 接受绝对路径并返回唯一尾分隔符（paths.ts:149）")
    void validateMemoryPath_acceptsAbsolute(@TempDir Path dir) {
        String result = AutoMemPaths.validateMemoryPath(dir.toString(), false);
        assertThat(result).isNotNull().endsWith(java.io.File.separator);
        assertThat(result).doesNotEndWith("//").doesNotEndWith("\\\\");
    }

    @Test
    @DisplayName("validateMemoryPath 空/null 输入 → undefined（paths.ts:113）")
    void validateMemoryPath_emptyIsUndefined() {
        assertThat(AutoMemPaths.validateMemoryPath(null, false)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("", false)).isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // 4. isAutoMemPath / entrypoint / daily-log
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isAutoMemPath: 目录内命中 + 前缀攻击（/memory-evil）不命中 (paths.ts:274-278)")
    void isAutoMemPath_containsAndPrefixAttack(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, null);
        String autoMem = paths.getAutoMemPath();

        assertThat(paths.isAutoMemPath(autoMem + "MEMORY.md"))
            .as("auto-memory 目录内文件必须命中")
            .isTrue();
        assertThat(paths.isAutoMemPath(autoMem.substring(0, autoMem.length() - 1) + "-evil/MEMORY.md"))
            .as("前缀攻击 /memory-evil 不得命中（尾分隔符契约）")
            .isFalse();
    }

    @Test
    @DisplayName("getAutoMemEntrypoint = <autoMemPath>/MEMORY.md (paths.ts:257-259)")
    void getAutoMemEntrypoint(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, null);
        assertThat(paths.getAutoMemEntrypoint())
            .as("entrypoint 必须落在 <autoMemPath>/MEMORY.md")
            .isEqualTo(paths.getAutoMemPath() + "MEMORY.md");
    }

    @Test
    @DisplayName("getAutoMemDailyLogPath = <autoMemPath>/logs/YYYY/MM/YYYY-MM-DD.md (paths.ts:246-251)")
    void getAutoMemDailyLogPath(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, null);
        LocalDate date = LocalDate.of(2026, 8, 5);
        String expected = paths.getAutoMemPath() + "logs" + java.io.File.separator + "2026" + java.io.File.separator
            + "08" + java.io.File.separator + "2026-08-05.md";
        assertThat(paths.getAutoMemDailyLogPath(date)).isEqualTo(expected);
    }

    // ════════════════════════════════════════════════════════════════
    // 5. sanitizePath
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sanitizePath: 非字母数字 → '-'，超长截断 + hash 后缀 (sessionStoragePortable.ts:311-319)")
    void sanitizePath_replacesAndTruncates() {
        assertThat(AutoMemPaths.sanitizePath("/Users/foo/my-project"))
            .as("非字母数字全部替换为连字符")
            .isEqualTo("-Users-foo-my-project");
        String longPath = "a".repeat(250);
        String result = AutoMemPaths.sanitizePath(longPath);
        assertThat(result)
            .as("超长路径必须截断到 200 + 稳定 hash 后缀（保证唯一且不超 255 字节限制）")
            .startsWith("a".repeat(200) + "-")
            .hasSizeGreaterThan(201);
        // 同一输入 → 同一输出（确定性）
        assertThat(result).isEqualTo(AutoMemPaths.sanitizePath(longPath));
    }

    @Test
    @DisplayName("sanitizePath: 超长路径 hash 后缀 = CC Bun.hash(wyhash v4 final) base36 (OPD-CM5-C-06)")
    void sanitizePath_longPathBunHashEquivalence() {
        // WHY: OPD-CM5-C-06 要求超长路径 hash 对齐 CC 生产（Bun runtime）。旧 djb2（CC Node 回退）
        // 对 >200 字符项目根与 CC 生产产出不同目录名（探查 △2）。此断言钉死字节级等价：
        //   Bun.hash("a"*250) = wyhash(utf8("a"*250), seed=0) = 0xed42241490215cdb → base36 "3lw0osi78587v"
        // 该 u64 经独立 Node 脚本按 wyhash v4 final 计算，并用 Bun 官方测试向量
        // （Bun.hash("hello world") === 0x668d5e431c3b2573n，test/js/bun/util/hash.test.js）交叉验证算法。
        assertThat(AutoMemPaths.sanitizePath("a".repeat(250)))
            .as("hash 后缀必须与 CC Bun.hash base36 一致（跨运行方目录名一致）")
            .isEqualTo("a".repeat(200) + "-3lw0osi78587v");
        // 真实项目根形态（含 '/' 与 '-'），>200 字符：wyhash = 0x6539bc286fab36e1 → base36 "1jf0bams1qdb5"
        assertThat(AutoMemPaths.sanitizePath(
            "/Users/foo/my-project-long-path-that-is-really-long-exceeds-200-characters-"
                + "abcdefghijklmnopqrstuvwxyz0123456789-abcdefghijklmnopqrstuvwxyz0123456789-"
                + "abcdefghijklmnopqrstuvwxyz0123456789-abcdefghijklmnopqrstuvwxyz0123456789-"
                + "abcdefghijklmnopqrstuvwxyz0123456789"))
            .endsWith("-1jf0bams1qdb5");
    }

    @Test
    @DisplayName("sanitizePath: 48 倍数长度边界 hash 对齐 Bun（len=240=5×48，OPD-CM5-C-06 返工）")
    void sanitizePath_len48MultipleBoundaryHash() {
        // WHY: 返工发现 len 恰为 48 倍数（240=5×48）时，原实现用 i>=48 整段消费 48 字节后
        // 尾部 a/b 双读已消费字节，产出与 Bun 不同 hash；修正为 wyhash.h if(_unlikely(i>48))
        // 严格大于语义。此断言钉死边界，防回归 —— 若仍用 >=48，本用例红（错误 base36）。
        // Bun 1.4.0 实测：Bun.hash("a".repeat(240)) = 0x60d81faf1aae715c → base36 "1h0nr1d4dj5uk"
        assertThat(AutoMemPaths.sanitizePath("a".repeat(240)))
            .as("48 倍数长度必须与 Bun 字节级一致（边界双读修正）")
            .isEqualTo("a".repeat(200) + "-1h0nr1d4dj5uk");
    }

    // ════════════════════════════════════════════════════════════════
    // 6. findCanonicalGitRoot
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findCanonicalGitRoot: 非 git 目录 → null；git 仓库 → 仓库根 (git.ts:97-109/195)")
    void findCanonicalGitRoot(@TempDir Path notRepo, @TempDir Path repo) throws Exception {
        assertThat(AutoMemPaths.findCanonicalGitRoot(notRepo.toString()))
            .as("非 git 目录必须返回 null（调用方回退 projectRoot）")
            .isNull();
        initGitRepo(repo);
        assertThat(AutoMemPaths.findCanonicalGitRoot(repo.toString()))
            .as("git 仓库根必须被识别")
            .isEqualTo(repo.toString());
    }

    // ════════════════════════════════════════════════════════════════
    // 7. ODF-A1 per-session projectRoot 注入
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ODF-A1 per-session: 同一 JVM 两个不同 cwd 会话解析出两个不同 memory 目录（路径断言）")
    void perSession_twoCwdTwoMemoryDirs(@TempDir Path memoryBase,
                                        @TempDir Path projectA,
                                        @TempDir Path projectB) {
        // WHY: 旧 Java 单例 user.dir 使同一 JVM 所有会话共享同一 memory 目录 → 项目 A 的记忆
        //      被项目 B 的 prompt 读到（跨项目污染）。CC 每进程 projectRoot = realpath(cwd)
        //      （state.ts:269-279）；Web 后端每个会话绑定不同项目目录 → 必须各自解析独立 memory 目录。
        AutoMemPaths pathsA = paths(projectA.toString(), memoryBase.toString(), null, null);
        AutoMemPaths pathsB = paths(projectB.toString(), memoryBase.toString(), null, null);

        String autoMemA = pathsA.getAutoMemPath();
        String autoMemB = pathsB.getAutoMemPath();

        assertThat(autoMemA)
            .as("会话 A（cwd=projectA）memory 目录落在 projectA 名下")
            .startsWith(memoryBase.resolve("projects").resolve(AutoMemPaths.sanitizePath(projectA.toString()))
                .toString() + java.io.File.separator)
            .endsWith(java.io.File.separator);
        assertThat(autoMemB)
            .as("会话 B（cwd=projectB）memory 目录落在 projectB 名下")
            .startsWith(memoryBase.resolve("projects").resolve(AutoMemPaths.sanitizePath(projectB.toString()))
                .toString() + java.io.File.separator)
            .endsWith(java.io.File.separator);
        assertThat(autoMemA)
            .as("两会话 memory 目录必须互不相同（per-session 隔离）")
            .isNotEqualTo(autoMemB);
    }

    @Test
    @DisplayName("ODF-A1 holder: defaultInstance 按会话 projectRoot 惰性解析（setCurrentProjectRoot 生效）")
    void defaultInstance_readsSessionHolder(@TempDir Path projectA, @TempDir Path projectB) {
        // WHY: AutoMemPaths bean 是单例，但 projectRoot 经 supplier 惰性读 ThreadLocal ——
        //      生产会话在 LlmAgentLoop.run() 入口 setCurrentProjectRoot 注入后，同一 bean
        //      实例即可解析出各自会话的 memory 目录（对齐 CC STATE.projectRoot 全局态）。
        AutoMemPaths paths = AutoMemPaths.defaultInstance();
        try {
            AutoMemPaths.setCurrentProjectRoot(projectA.toString());
            String autoMemA = paths.getAutoMemPath();
            AutoMemPaths.setCurrentProjectRoot(projectB.toString());
            String autoMemB = paths.getAutoMemPath();

            assertThat(autoMemA)
                .as("注入 projectA 后 defaultInstance 解析到 projectA 的 memory 目录")
                .contains(AutoMemPaths.sanitizePath(projectA.toString()));
            assertThat(autoMemB)
                .as("注入 projectB 后 defaultInstance 解析到 projectB 的 memory 目录（memoize 按 projectRoot 重算）")
                .contains(AutoMemPaths.sanitizePath(projectB.toString()));
            assertThat(autoMemA).as("两会话 memory 目录不同").isNotEqualTo(autoMemB);
        } finally {
            AutoMemPaths.setCurrentProjectRoot(null);
        }
    }

    @Test
    @DisplayName("ODF-A1-R2 并发隔离: 两线程交错 setCurrentProjectRoot/getAutoMemPath 各解析本会话目录（ThreadLocal 隔离）")
    void perSession_concurrentIsolation(@TempDir Path projectA, @TempDir Path projectB) throws Exception {
        // WHY (规则九 · 验证并发意图而非仅顺序行为): 返工前 currentSessionProjectRoot 是 static
        //      volatile 单值 —— 会话 A 写 holder=A 后会话 B 可覆盖 holder=B，A 的后续内存读解析到
        //      B 的目录（跨会话污染，语义不等价 CC：CC 单进程单会话无并发）。ThreadLocal 使每会话
        //      线程持有独立 projectRoot。本测试用<b>共享</b> defaultInstance（= 生产单例 bean 场景）
        //      两线程经 CyclicBarrier 同时起跑、交错 200 轮注入/读取，断言各线程始终解析到
        //      本会话 sanitized 目录。static volatile 下该测试会红（后写覆盖），ThreadLocal 下恒绿。
        AutoMemPaths shared = AutoMemPaths.defaultInstance();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);

        java.util.function.BiConsumer<String, String> session = (myRoot, otherRoot) -> {
            try {
                barrier.await();
                for (int i = 0; i < 200; i++) {
                    AutoMemPaths.setCurrentProjectRoot(myRoot);
                    // 交错点：让另一线程有机会覆盖（static volatile 下此处会串台）
                    if ((i & 1) == 0) {
                        Thread.yield();
                    }
                    String path = shared.getAutoMemPath();
                    if (!path.contains(AutoMemPaths.sanitizePath(myRoot))) {
                        throw new AssertionError(
                            "会话 " + myRoot + " 第 " + i + " 轮解析到非本会话目录: " + path);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                AutoMemPaths.resetCurrentProjectRoot();
                done.countDown();
            }
        };

        Thread tA = new Thread(() -> session.accept(projectA.toString(), projectB.toString()), "odf-a1-session-A");
        Thread tB = new Thread(() -> session.accept(projectB.toString(), projectA.toString()), "odf-a1-session-B");
        tA.start();
        tB.start();
        boolean finished = done.await(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(finished)
            .as("并发隔离测试 30s 内必须完成（两线程 200 轮 × 2）")
            .isTrue();
        assertThat(failure.get())
            .as("并发隔离失败（ThreadLocal 未按会话线程隔离 → 跨会话污染）")
            .isNull();
    }

    @Test
    @DisplayName("ODF-A1: 同一 git 仓库内两个子目录会话仍共享 canonical memory 目录（findCanonicalGitRoot 保留）")
    void perSession_sameRepoSharedCanonical(@TempDir Path memoryBase,
                                            @TempDir Path repo,
                                            @TempDir Path otherRepo) throws Exception {
        // WHY: getAutoMemBase = findCanonicalGitRoot(projectRoot) ?? projectRoot（paths.ts:203-205）。
        //      per-session 注入不得破坏 worktree/子目录共享语义 —— 同一仓库的子目录（不同 cwd 会话）
        //      必须共享同一 canonical memory 目录（#24382）。
        initGitRepo(repo);
        Path moduleA = Files.createDirectories(repo.resolve("moduleA"));
        Path moduleB = Files.createDirectories(repo.resolve("moduleB"));
        initGitRepo(otherRepo);
        Path otherSrc = Files.createDirectories(otherRepo.resolve("src"));

        AutoMemPaths pathsA = paths(moduleA.toString(), memoryBase.toString(), null, null);
        AutoMemPaths pathsB = paths(moduleB.toString(), memoryBase.toString(), null, null);
        AutoMemPaths pathsOther = paths(otherSrc.toString(), memoryBase.toString(), null, null);

        assertThat(pathsA.getAutoMemPath())
            .as("同一仓库两子目录 → 同一 canonical memory 目录（共享语义保留）")
            .isEqualTo(pathsB.getAutoMemPath());
        assertThat(pathsA.getAutoMemPath())
            .as("不同仓库 → 不同 memory 目录")
            .isNotEqualTo(pathsOther.getAutoMemPath());
    }


    // ════════════════════════════════════════════════════════════════
    // OPD-R2-01 · tilde 平凡余部拒绝（paths.ts:122-135）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-R2-01: tilde 平凡余部拒绝 — ~/、~/foo/..、~/.、~/.. 均 null（Node normalize 语义）")
    void validateMemoryPath_tildeTrivialRemaindersRejected() {
        // WHY: 反例 ~/foo/..（EV-005）：Java Paths.get("foo/..").normalize()="" ≠ "."/".." 逃过检查
        //      → 解析为 $HOME → isAutoMemPath 全匹配 $HOME + 读写 carve-out 静默放行（D1，high）。
        //      实测（JDK 25）：Paths.get(".").normalize()=""（Node '.'）、normalize("foo/..")=""（Node '.'）
        //      → 空串判定覆盖 `~/`、`~/.`、`~/foo/..`；".." 判定覆盖 `~/..`、`~/foo/../..`。
        assertThat(AutoMemPaths.validateMemoryPath("~/", true)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("~/.", true)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("~/..", true)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("~/foo/..", true)).isNull();
        assertThat(AutoMemPaths.validateMemoryPath("~/foo/../..", true)).isNull();
        // 非平凡余部正常展开到 $HOME（CC :134 join(homedir(), rest)）
        assertThat(AutoMemPaths.validateMemoryPath("~/foo", true))
            .isNotNull()
            .startsWith(System.getProperty("user.home"));
    }

    @Test
    @DisplayName("OPD-R2-01 反例: settings 含 ~/foo/.. → 拒绝并回落 per-project（isAutoMemPath 不匹配 $HOME，carve-out 不放大）")
    void getAutoMemPath_tildeTraversalSettingNotExpandedToHome(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, "~/foo/..");
        String autoMem = paths.getAutoMemPath();
        assertThat(autoMem)
            .as("settings 含平凡余部 tilde → validateMemoryPath 拒绝 → 回落 per-project 默认（绝不解析为 $HOME）")
            .startsWith(memoryBase.resolve("projects").toString());
        assertThat(paths.isAutoMemPath(System.getProperty("user.home")))
            .as("$HOME 不得命中 isAutoMemPath（读写 carve-out 不放大到全 home）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // OPD-R2-06 · NFC 归一化（paths.ts:149/232、git.ts:174/176、state.ts:271/274）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-R2-06: validateMemoryPath 输出 NFC（分解形 café → 合成形字节，paths.ts:149）")
    void validateMemoryPath_nfcOutput(@TempDir Path dir) {
        String decomposed = dir.toAbsolutePath() + java.io.File.separator + "cafe\u0301";
        String result = AutoMemPaths.validateMemoryPath(decomposed, false);
        assertThat(result).isNotNull();
        assertThat(result)
            .as("分解形组合标记（U+0301）必须被 NFC 合成（旧实现无 NFC → 字节路径不同）")
            .doesNotContain("\u0301");
        assertThat(result).startsWith(
            java.text.Normalizer.normalize(decomposed, java.text.Normalizer.Form.NFC));
    }

    @Test
    @DisplayName("OPD-R2-06: getAutoMemPath 输出 NFC（分解形 memoryBase → 合成形字节，paths.ts:232）")
    void getAutoMemPath_nfcOutput(@TempDir Path dir, @TempDir Path projectRoot) {
        String decomposedBase = dir.toAbsolutePath() + java.io.File.separator + "cafe\u0301";
        AutoMemPaths paths = paths(projectRoot.toString(), decomposedBase, null, null);
        String autoMem = paths.getAutoMemPath();
        assertThat(autoMem)
            .as("getAutoMemPath 路径产出必须 NFC（CC (...+sep).normalize('NFC')）")
            .doesNotContain("\u0301");
        assertThat(autoMem).startsWith(
            java.text.Normalizer.normalize(decomposedBase, java.text.Normalizer.Form.NFC)
                + java.io.File.separator + "projects");
    }

    // ════════════════════════════════════════════════════════════════
    // OPD-R2-12 · getAutoMemPath 缓存按 projectRoot 独立槽（EV-036）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-R2-12: getAutoMemPath 缓存按 projectRoot 独立槽 — 共享实例并发交错无错配")
    void getAutoMemPath_cacheIsolatedPerProjectRoot(@TempDir Path memoryBase,
                                                    @TempDir Path projectA,
                                                    @TempDir Path projectB) throws Exception {
        //      线程 B（projectRoot==旧 key）命中旧 key 取到 A 的新 value → 跨会话路径错配
        //      （isAutoMemPath 前缀判定/注入目录错误，medium，EV-036）。CC lodash memoize 按 key
        //      独立槽（paths.ts:223-235）→ ConcurrentHashMap 每 projectRoot 独立槽，无跨槽错配。
        //      本测试用生产 defaultInstance（ThreadLocal projectRoot 注入）+ 两线程交错 300 轮：
        //      每线程首轮固化本会话路径，后续轮必须恒等（同 root 槽缓存稳定）且含本会话 sanitized 名。
        AutoMemPaths shared = AutoMemPaths.defaultInstance();
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.BiConsumer<String, String> session = (myRoot, otherRoot) -> {
            try {
                barrier.await();
                AutoMemPaths.setCurrentProjectRoot(myRoot);
                String first = shared.getAutoMemPath();
                if (!first.contains(AutoMemPaths.sanitizePath(myRoot))) {
                    throw new AssertionError("首轮即解析到非本会话目录: " + first);
                }
                for (int i = 0; i < 300; i++) {
                    AutoMemPaths.setCurrentProjectRoot(myRoot);
                    if ((i & 1) == 0) {
                        Thread.yield();
                    }
                    String path = shared.getAutoMemPath();
                    if (!path.equals(first)) {
                        throw new AssertionError(
                            "会话 " + myRoot + " 第 " + i + " 轮缓存槽错配: 期望 " + first + " 实得 " + path);
                    }
                    if (!path.contains(AutoMemPaths.sanitizePath(myRoot))) {
                        throw new AssertionError(
                            "会话 " + myRoot + " 第 " + i + " 轮取到非本会话路径: " + path);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                AutoMemPaths.resetCurrentProjectRoot();
                done.countDown();
            }
        };
        Thread tA = new Thread(() -> session.accept(projectA.toString(), projectB.toString()), "cache-slot-A");
        Thread tB = new Thread(() -> session.accept(projectB.toString(), projectA.toString()), "cache-slot-B");
        tA.start();
        tB.start();
        boolean finished = done.await(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(finished)
            .as("缓存隔离并发测试 30s 内必须完成（两线程 300 轮 × 2）")
            .isTrue();
        assertThat(failure.get())
            .as("缓存按 projectRoot 独立槽失败（跨会话错配）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // OPD-R2-05 · sanitizePath ASCII 字符集（sessionStoragePortable.ts:312）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-R2-05: sanitizePath 字符集 [^a-zA-Z0-9]（ASCII）— 中文/重音 → '-'")
    void sanitizePath_asciiCharset() {
        // WHY: 旧实现 Character.isLetterOrDigit（Unicode）保留中文/重音 → 非 ASCII 项目根目录
        //      产出与 CC 不同的目录名（D4，EV-018）。CC 一律替换为 '-'。
        assertThat(AutoMemPaths.sanitizePath("/Users/王/项目")).isEqualTo("-Users-----");
        assertThat(AutoMemPaths.sanitizePath("café")).isEqualTo("caf-");
        // ASCII 不受影响（既有用例保持）
        assertThat(AutoMemPaths.sanitizePath("/Users/foo/my-project")).isEqualTo("-Users-foo-my-project");
    }

    // ════════════════════════════════════════════════════════════════
    // OPD-R2-04 · getAutoMemDailyLogPath 无参重载（paths.ts:246）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-R2-04: getAutoMemDailyLogPath() 无参重载 = 今日路径（CC date 缺省=今天）")
    void getAutoMemDailyLogPath_noArgDefaultsToday(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        AutoMemPaths paths = paths(projectRoot.toString(), memoryBase.toString(), null, null);
        assertThat(paths.getAutoMemDailyLogPath())
            .isEqualTo(paths.getAutoMemDailyLogPath(LocalDate.now()));
    }

    // ════════════════════════════════════════════════════════════════
    // OPD-R2-02 / G-03 · settings 源建模（local={projectRoot}/.claude/settings.local.json
    // + autoMemoryEnabled 项目级 opt-out）
    // ════════════════════════════════════════════════════════════════

    private static final com.fasterxml.jackson.databind.ObjectMapper SETTINGS_JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    @DisplayName("OPD-R2-02: autoMemoryDirectory 本地源 = {projectRoot}/.nexusai/settings.local.json（D2 复刻版 · 对齐 settings.ts:283-287/304-305）")
    void getAutoMemPath_localSettingsAtProjectRoot(@TempDir Path projectRoot, @TempDir Path configHome) throws Exception {
        // WHY: 旧实现读 {configHome}/settings.local.json —— CC 中该文件不存在（localSettings =
        //      {cwd}/.claude/settings.local.json，settings.ts:244-246/304-305）→ 本地源配置静默失效
        //      （D2，medium）。D2 复刻版 local 源改 {projectRoot}/.nexusai/settings.local.json
        //      （AutoMemPaths.java:829，弃 claude settings 档），按 per-session projectRoot 定位。
        // D1/D6 全动态：项目级目录名 = NexusaiPaths.getProjectDirName()。
        //   ⚠️ 时序：setAppNameOverride 必须先于夹具创建（夹具目录名随 appName 动态，先建会用默认 nexusai → .nexusai，读时 override 后 .nexusai-test-<rand> 不匹配）。
        AutoMemPaths paths = AutoMemPaths.defaultInstance();
        ClaudePaths.setConfigDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        // 清 DB 桥接（readAutoMemoryDirectorySetting 的 fromDb 优先，防前用例 mapper 泄漏致 local 源不命中）
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        try {
            Path nexusaiDir = Files.createDirectories(projectRoot.resolve(NexusaiPaths.getProjectDirName()));
            Path memDir = Files.createDirectories(projectRoot.resolve("custom-mem"));
            Files.writeString(nexusaiDir.resolve("settings.local.json"),
                "{\"autoMemoryDirectory\": " + SETTINGS_JSON.writeValueAsString(memDir.toString()) + "}");
            AutoMemPaths.setCurrentProjectRoot(projectRoot.toString());
            assertThat(paths.getAutoMemPath())
                .as("local 源必须从 {projectRoot}/.nexusai/settings.local.json 读取")
                .isEqualTo(memDir.toString() + java.io.File.separator);
        } finally {
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
            paths.clearCache();
        }
    }

    @Test
    @DisplayName("G-03: nexusai user settings autoMemoryEnabled=false → 全局关闭（D2 · 弃 claude settings 档）")
    void autoMemoryEnabled_projectOptOut(@TempDir Path projectRoot, @TempDir Path configHome) throws Exception {
        // WHY: D2 复刻版 gate 仅读 DB + nexusai user settings（BundledSkillEnabledGates.java:188-189）；
        //      CC 项目级 opt-out（.claude/settings.json）已废弃（D2 变更，EV-035/△-10 原由不再适用）。
        //      nexusai user settings opt-out（~/.{appName}/settings.json autoMemoryEnabled=false）须生效。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        // G5：user.home 隔离到 tempDir + 唯一 appName，使 NexusaiPaths 根落临时目录（防写真实 ~/.nexusai）
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        Files.writeString(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json"),
            "{\"autoMemoryEnabled\": false}");
        try {
            assertThat(BundledSkillEnabledGates.isAutoMemoryEnabled())
                .as("nexusai user settings opt-out（autoMemoryEnabled=false）必须生效")
                .isFalse();
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
            System.setProperty("user.home", originalUserHome);   // G5：复位 user.home
        }
    }

    @Test
    @DisplayName("G-03/OPD-R2-02: autoMemoryEnabled 优先级 DB settings 列 > nexusai user settings（D2 · 弃 claude 三档序）")
    void autoMemoryEnabled_mergeOrder(@TempDir Path projectRoot, @TempDir Path configHome) throws Exception {
        // WHY: D2 复刻版 gate 仅读 DB + nexusai user settings（BundledSkillEnabledGates.java:182-190）。
        //      CC 的 local>project>user 三档序已不适用（D2 弃 claude settings 档）——
        //      等价改测 DB settings 列优先于 nexusai user settings 文件（V34 列主控先例）。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        // user: true（DB 未配置时回落文件）
        Files.writeString(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json"),
            "{\"autoMemoryEnabled\": true}");
        // G5：唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        try {
            // DB 未桥接 → 回落 nexusai user settings 文件：true
            assertThat(BundledSkillEnabledGates.isAutoMemoryEnabled())
                .as("DB 未配置 → nexusai user settings 文件 true 生效")
                .isTrue();
            // DB settings 列 false → 优先于 user 文件 true（DB 主控）
            com.nexusai.repository.settings.entity.SettingsRecord rec =
                new com.nexusai.repository.settings.entity.SettingsRecord();
            rec.setId(1);
            rec.setAutoMemoryEnabled(false);
            com.nexusai.repository.settings.mapper.SettingsMapper mapper =
                org.mockito.Mockito.mock(com.nexusai.repository.settings.mapper.SettingsMapper.class);
            org.mockito.Mockito.when(mapper.selectOneById(1)).thenReturn(rec);
            BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
            assertThat(BundledSkillEnabledGates.isAutoMemoryEnabled())
                .as("DB settings 列优先级高于 nexusai user settings 文件")
                .isFalse();
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // G-04/G-08/G-62/G-65 + OPD-R2-07/10 · TeamMemPaths / TeamMemSecretGuard
    // ════════════════════════════════════════════════════════════════

    private static TeamMemPaths teamPaths(Path projectRoot, Path memoryBase,
                                          boolean autoMem, boolean teamMem) {
        // IMP-CM-09 双门控：autoMemory=autoMem, feature('TEAMMEM')=teamMem, runtime(tengu_herring_clock)=true
        return new TeamMemPaths(
            new AutoMemPaths(() -> projectRoot.toString(), () -> memoryBase.toString(), () -> null, () -> null),
            () -> autoMem, () -> teamMem, () -> true);
    }

    @Test
    @DisplayName("G-65: auto-memory 关 + TEAMMEM 开 → 守卫仍生效（feature('TEAMMEM') && isTeamMemPath，不要求 auto-memory）")
    void guard_activeWhenAutoMemoryOffTeamMemOn(@TempDir Path projectRoot, @TempDir Path memoryBase) {
        TeamMemPaths paths = teamPaths(projectRoot, memoryBase, false, true);
        TeamMemSecretGuard guard = new TeamMemSecretGuard(paths);
        String err = guard.checkTeamMemSecrets(paths.getTeamMemPath() + "MEMORY.md",
            "token: ghp_012345678901234567890123456789012345");
        assertThat(err)
            .as("auto-memory 关闭 + TEAMMEM 开启：CC 仍守卫（EV-TMS-34/M-1；旧 isTeamMemFile 含 isTeamMemoryEnabled → 不守卫）")
            .contains("GitHub PAT");
    }

    @Test
    @DisplayName("G-65: TEAMMEM 关 → 守卫惰性（feature gate 关闭，路径命中 team 目录也不扫描）")
    void guard_inertWhenTeamMemOff(@TempDir Path projectRoot, @TempDir Path memoryBase) {
        TeamMemPaths paths = teamPaths(projectRoot, memoryBase, true, false);
        TeamMemSecretGuard guard = new TeamMemSecretGuard(paths);
        assertThat(guard.checkTeamMemSecrets(paths.getTeamMemPath() + "MEMORY.md",
            "token: ghp_012345678901234567890123456789012345"))
            .as("feature('TEAMMEM') 关闭 → checkTeamMemSecrets 惰性返回 null")
            .isNull();
    }

    @Test
    @DisplayName("OPD-R2-07: team 判定大小写敏感 — Team/MEMORY.md 大小写变体不命中（CC teamMemPaths.ts:214-220）")
    void isTeamMemPath_caseSensitive(@TempDir Path projectRoot, @TempDir Path memoryBase) {
        // WHY: 旧全链 toComparable（Windows 小写折叠）→ 对 CC 拒绝的大小写变体 accept-more
        //      （G-08/G-62，EV-025b/026）。CC 大小写敏感 → 变体不命中。
        TeamMemPaths paths = teamPaths(projectRoot, memoryBase, true, true);
        String teamDir = paths.getTeamMemPath();
        assertThat(paths.isTeamMemPath(teamDir + "MEMORY.md")).isTrue();
        String variant = teamDir.substring(0, teamDir.length() - "team".length() - 1)
            + "Team" + java.io.File.separator;
        assertThat(paths.isTeamMemPath(variant + "MEMORY.md"))
            .as("大小写变体（team → Team）不得命中（旧小写折叠会命中）")
            .isFalse();
    }

    @Test
    @DisplayName("G-04: realpathDeepestExisting symlink 循环（ELOOP）→ PathTraversalError fail-closed")
    void realpathDeepestExisting_loopFailsClosed(@TempDir Path projectRoot, @TempDir Path memoryBase,
                                                 @TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("loopA");
        Path b = tmp.resolve("loopB");
        try {
            Files.createSymbolicLink(a, Paths.get("loopB"));
            Files.createSymbolicLink(b, Paths.get("loopA"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            // 平台不支持 symlink（Windows 无开发者模式）→ 跳过（fail-closed 分支由 E2 静态
            // 证据覆盖：非 {ENOENT,ELOOP,ENOTDIR,ENAMETOOLONG} 一律抛 PathTraversalError）
            return;
        }
        TeamMemPaths paths = teamPaths(projectRoot, memoryBase, true, true);
        assertThatThrownBy(() -> paths.realpathDeepestExisting(a.toString()))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
    }

    @Test
    @DisplayName("OPD-R2-10 登记: %E4.. 型 key（合法 hex、非法 UTF-8）Java fail-closed 拒绝（CC URIError→字面回退接受；差异登记受控）")
    void sanitizePathKey_invalidUtf8PercentKeptFailClosed() {
        // WHY: CC decodeURIComponent('%E4..') 抛 URIError（%E4 非法 UTF-8 首字节）→ decoded=key
        //      回退 → 字面 '%E4..' 不含 '..'/'/' → 接受（teamMemPaths.ts:28-35）。Java
        //      decodeUriComponent 逐对解码出 'ä..' → 命中 '..' → 拒绝。Java 更严（fail-closed），
        //      接受集与 CC 不同（EV-021，△-9）→ 任务内取证后定：保留 fail-closed（防御方向，登记受控）。
        assertThatThrownBy(() -> TeamMemPaths.sanitizePathKey("%E4.."))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
        // 畸形百分号编码（%ZZ）两侧一致：回退字面接受
        assertThat(TeamMemPaths.sanitizePathKey("%ZZ")).isEqualTo("%ZZ");
    }

    @Test
    @DisplayName("OPD-R2-08: symlink 路径访问的 worktree → 回链 realpath 后归并主仓（git.ts:165-170）")
    void resolveCanonicalRoot_backlinkRealpath(@TempDir Path dir) throws Exception {
        Path mainRepo = dir.resolve("main");
        Files.createDirectories(mainRepo);
        initGitRepo(mainRepo);
        runGit(mainRepo, "config", "user.email", "test@example.com");
        runGit(mainRepo, "config", "user.name", "test");
        Files.writeString(mainRepo.resolve("a.txt"), "x");
        runGit(mainRepo, "add", "a.txt");
        runGit(mainRepo, "commit", "-m", "init");
        Path worktree = dir.resolve("wt");
        runGit(mainRepo, "worktree", "add", worktree.toString(), "-b", "test-branch");
        Path link = dir.resolve("wt-link");
        try {
            Files.createSymbolicLink(link, worktree.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            // 平台不支持 symlink → 跳过（OPD-R2-08 修复依 CC git.ts:165-170 realpathSync E2 证据）
            return;
        }
        String canonical = AutoMemPaths.findCanonicalGitRoot(link.toString());
        assertThat(canonical)
            .as("经 symlink 访问的 worktree 必须归并到主仓根（回链 realpath 比对）")
            .isEqualTo(mainRepo.toString());
    }

    /** 在目录中执行 git 命令（工作目录 = dir）。 */
    private static void runGit(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertThat(code).as("git %s failed: %s", String.join(" ", args), out).isEqualTo(0);
    }

    // ── helpers ──

    /** 在目录中执行 git init（初始化测试用临时 git 仓库）。 */
    private static void initGitRepo(Path dir) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "init"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertThat(code).as("git init failed: %s", out).isEqualTo(0);
    }
}
