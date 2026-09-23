package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [T2 · 写侧 auto-memory carve-out 基址可达性] {@link WritePermissionChecker} 写
 * {@code <memoryBase>/projects/<slug>/memory/*.md} 必须静默 allow。
 *
 * <h2>被测分支（CC 真源）</h2>
 * <p>CC {@code checkEditableInternalPath} 的 memdir 写分支：{@code !hasAutoMemPathOverride() &&
 * isAutoMemPath(p)} → {@code {behavior:'allow', reason:'auto memory files are allowed for writing'}}
 * （仓内既有注释口径 {@code filesystem.ts:1572-1581}，见 {@code PathValidation.java} 的
 * 「auto-mem（CC :1572-1581，!hasAutoMemPathOverride 门）」注释）。CC 该句式在
 * {@code checkEditableInternalPath} 与 {@code checkReadableInternalPath} 各有一份，读侧
 * Java 已由 {@code ReadPermissionChecker.java:284-285} 的 {@code withAutoMem(...)} wither 供上基址，
 * <b>写侧此前没有</b>（{@code WritePermissionChecker} 全类无 AutoMemPaths 字段）。
 *
 * <h2>WHY（规则九 · 测试验证意图，不是只验证行为）</h2>
 * <p>{@link PathValidationEnv#fromToolUseContext} 是 read/write <b>共用</b>的纯静态派生，工厂里
 * {@code hasAutoMemPathOverride=false} / {@code autoMemBaseDir=null} 是硬编码（PathValidationEnv.java
 * 工厂尾部实参），唯一填充口是 {@link PathValidationEnv#withAutoMem}。而
 * {@link PathValidation#isAutoMemPath} 在 {@code base == null} 时<b>恒返回 false</b>
 * ⇒ 写侧那条 CC carve-out <b>结构性不可达</b>：写 {@code memory/MEMORY.md}（auto-memory 提取链的
 * 落盘动作）永远穿到 1.7 {@code checkPathSafetyForAutoEdit}，因路径段含动态项目目录名
 * {@code .nexusai} 被判危险文件 → {@code Ask(SafetyCheck("Path is a sensitive file"))}。
 * <b>后果</b>：模型每写一次记忆都要弹一次窗，用户不得不在「记忆提取」这件后台琐事上反复交互
 * （打断 + 记忆写入被拒则提取链整体失效）。本测试就是钉住「写侧 carve-out 真能命中」这个意图。
 *
 * <h2>本测试的「扰动生效」自证（为什么不是恒绿）</h2>
 * <ol>
 *   <li>{@link #autoMemEntrypointWrite_isAllowed_withWiredAutoMemPaths} 装配 {@code setAutoMemPaths}
 *       ⇒ 必须 allow；</li>
 *   <li>{@link #autoMemEntrypointWrite_withoutWiredAutoMemPaths_isNotAllowed} <b>不</b>装配同一
 *       checker（其余输入逐字节相同）⇒ 必须【不是 allow】。二者构成对照：若把
 *       {@code WritePermissionChecker.check} 里的 {@code .withAutoMem(autoMemPaths)} 摘掉，
 *       第 1 条立刻转红（实施报告附改前跑红的原始输出）；若 withAutoMem 恒返回带基址的 env
 *       （即基址被伪造、绕过注入槽），第 2 条转红。</li>
 * </ol>
 *
 * <h2>反向护栏（防放宽过头）</h2>
 * <p>{@link #pathOutsideMemoryDir_isNotCarvedOut} 用一个<b>不在</b> {@code memory} 目录下的
 * {@code .nexusai} 路径（{@code <memoryBase>/projects/<slug>/tool-results/o.txt}）断言：
 * 核心层 {@code checkEditableInternalPath} 仍 passthrough、端到端仍落 {@code Ask}。
 * ⚠️ 该路径刻意避开 {@code memory} 前缀（{@code .../memory-other/x.md} 会因
 * {@code PathValidation.isAutoMemPath} 的 {@code startsWith} 无分隔符边界而被误放行 ——
 * 已登记缺陷，与本批「不放宽判定」无关）。
 */
@DisplayName("[T2] WritePermissionChecker 写侧 auto-memory carve-out（CC checkEditableInternalPath memdir 写分支）")
class WritePermissionCheckerAutoMemWriteCarveOutTest {

    // ── [S2 · F-09/F-20] 夹具 DB 姿态显式声明（本夹具不接 DB 回源）──
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛，
    //   而 PathValidationEnv.fromToolUseContext 会对该抛 fail-closed 兜住 —— 那会让本测试测到
    //   「解析失败态」而不是「有基址态」。见 SessionProjectRootTestSupport 类 javadoc。
    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    /**
     * 把本用例的合成 sessionId 绑定到 {@code projectRoot}（= CC {@code getProjectRoot()} ·
     * {@code SessionProjectRoot.lookup} → {@code projects.path}），<b>替代</b> {@code @BeforeEach} 的
     * 「本夹具不接 DB」声明（那份声明会让 {@code CwdResolution.getProjectRoot} 走 sessionless 出口 ⇒
     * 返回进程 {@code user.dir}，即<b>不是</b>本用例的项目根）。
     *
     * <p><b>WHY 必须显式绑定（2026-09-22 · 对齐 CC getAutoMemBase）</b>：{@code PathValidationEnv
     * .withAutoMem} 的 slug 锚自本批起 = {@link PathValidationEnv#sessionProjectRoot}（对齐 CC
     * {@code getAutoMemBase()} = {@code findCanonicalGitRoot(getProjectRoot()) ?? getProjectRoot()}），
     * ⛔ 不再是 {@code effectiveCwd}。夹具若不绑定，{@code sessionProjectRoot} 会是 {@code user.dir}
     * ⇒ 派生出的 base 与本用例 {@code @TempDir} 项目根不同源 ⇒ <b>正例假红</b>。
     * 同时也说明「{@code effectiveCwd} 恰好等于项目根」这种夹具姿态<b>遮蔽了</b>本批要治的分裂场景
     * —— 分裂场景见
     * {@link #autoMemEntrypointWrite_isAllowed_whenEffectiveCwdDivergesFromProjectRoot}。
     */
    private static void bindSessionProjectRoot(Path projectRoot) {
        SessionProjectRoot.setDbResolver(
            sid -> SessionProjectRoot.Lookup.bound(projectRoot.toString()));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 13 参工厂：显式 effectiveCwd（= 本用例的会话项目根）。 */
    private static ToolUseContext ctx(Path effectiveCwd) {
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    /**
     * 隔离夹具：注入 supplier 构造 POJO AutoMemPaths（不读真实 env / 用户 home）。
     *
     * <p>{@code override}/{@code settingsDir} 恒 null ⇒ {@code hasAutoMemPathOverride()=false}
     * （与生产默认形态一致：CC 的写 carve-out 带 {@code !hasAutoMemPathOverride} 门）。
     */
    private static AutoMemPaths pojoAutoMemPaths(Path projectRoot, Path memoryBase) {
        return new AutoMemPaths(
            () -> projectRoot.toString(),
            () -> memoryBase.toString(),
            () -> null,
            () -> null);
    }

    /**
     * 夹具前提自检（照 {@code PathValidationTest} 的自检风格）：派生形状必须是
     * {@code <memoryBase>/projects/<sanitizePath(getAutoMemBase(projectRoot))>/memory/}（尾分隔符）。
     *
     * <p>WHY：{@code getAutoMemBase} 会先做 {@code findCanonicalGitRoot(projectRoot)}（git.ts:97-183）
     * —— 若 {@code @TempDir} 恰好落在某个 git 仓库内，slug 会变成<b>仓库根</b>的 slug。断言派生形状
     * 用同一个 AutoMemPaths 实例算出，避免测试把 slug 写死成 {@code sanitizePath(projectRoot)}
     * 而在 git 仓库内假红/假绿。
     */
    private static void assertFixtureShape(AutoMemPaths pojo, Path projectRoot, Path memoryBase) {
        String autoMem = pojo.getAutoMemPath(projectRoot.toString());
        String expected = memoryBase.resolve("projects")
            .resolve(AutoMemPaths.sanitizePath(pojo.getAutoMemBase(projectRoot.toString())))
            .resolve("memory").toString() + java.io.File.separator;
        assertThat(pojo.getAutoMemBase(projectRoot.toString()))
            .as("夹具前提：projectRoot 必须是有效项目（⛔ 不等于 memoryBase / configHome，否则 A′ 判定返回 null）")
            .isNotNull();
        assertThat(autoMem)
            .as("夹具前提：auto-memory 目录形状 = <memoryBase>/projects/<slug>/memory/ + 尾分隔符")
            .isEqualTo(expected)
            .endsWith(java.io.File.separator);
        // ⛔ 夹具前提：memoryBase 不得等于 config home（isEligibleProjectRoot 会拒），
        //   也不得等于 projectRoot。
        assertThat(java.nio.file.Paths.get(memoryBase.toString()).toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(java.nio.file.Paths.get(
                    NexusaiPaths.getAppConfigHomeDir()).toAbsolutePath().normalize().toString()))
            .as("夹具前提：memoryBase 不得等于 config home（~/.{appName}）")
            .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. 正例：接线后写 MEMORY.md → allow（CC :1572-1581 文案逐字）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("写 <autoMem>/MEMORY.md + 装配 setAutoMemPaths → Allow(Other(\"auto memory files are allowed for writing\"))")
    void autoMemEntrypointWrite_isAllowed_withWiredAutoMemPaths(
            @TempDir Path memoryRoot, @TempDir Path projectRoot) {
        // WHY：auto-memory 的落盘动作（memory 提取链写 MEMORY.md）必须静默放行。若此处转红 ⇒
        //   写侧 carve-out 的基址又断了（base==null ⇒ isAutoMemPath 恒 false）⇒ 每次写记忆弹窗、
        //   后台记忆提取被用户交互打断。这正是本批（T2）要修的病。
        Path memoryBase = memoryRoot.resolve(NexusaiPaths.getProjectDirName());
        AutoMemPaths pojo = pojoAutoMemPaths(projectRoot, memoryBase);
        assertFixtureShape(pojo, projectRoot, memoryBase);
        // 夹具姿态：sessionProjectRoot = projectRoot（= CC getProjectRoot()），本用例 effectiveCwd 亦 = 它。
        bindSessionProjectRoot(projectRoot);

        String autoMem = pojo.getAutoMemPath(projectRoot.toString());
        // 尾分隔符 + resolve ⇒ <autoMem>/MEMORY.md（Path.of 归一化掉尾分隔符）
        String memoryFile = Path.of(autoMem).normalize().resolve("MEMORY.md").toString();

        WritePermissionChecker checker = new WritePermissionChecker();
        checker.setAutoMemPaths(pojo);
        Tool tool = new WriteFileTool(new PathGuard(projectRoot));

        PermissionResult result = checker.check(tool, input(memoryFile), ctx(projectRoot));

        assertThat(result)
            .as("写 auto-memory 文件必须 allow（CC filesystem.ts:1572-1581 memdir 写 carve-out）；"
                + "落 Ask ⇒ 记忆提取链每次都被用户交互打断")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("reason 必须是 CC 逐字文案（decisionReason=Other('auto memory files are allowed for writing')）")
            .isEqualTo(new PermissionDecisionReason.Other("auto memory files are allowed for writing"));
    }

    @Test
    @DisplayName("核心层直断：checkEditableInternalPath(<autoMem>/MEMORY.md) → allowed + 同文案")
    void coreCheckEditableInternalPath_autoMemFile_isAllowed(
            @TempDir Path memoryRoot, @TempDir Path projectRoot) {
        // WHY：端到端 allow 可能被 1.5 之外的更早步骤遮住（那样修复其实没生效）。本用例直接钉住
        //   被改动的那一行分派（WritePermissionChecker.check step 1.5 → checkEditableInternalPath），
        //   使「carve-out 本身可达」与「checker 接线」两件事各自有独立证据。
        Path memoryBase = memoryRoot.resolve(NexusaiPaths.getProjectDirName());
        AutoMemPaths pojo = pojoAutoMemPaths(projectRoot, memoryBase);
        bindSessionProjectRoot(projectRoot);
        ToolUseContext tuc = ctx(projectRoot);
        PathValidationEnv env = PathValidationEnv.fromToolUseContext(tuc).withAutoMem(pojo);

        String memoryFile = Path.of(pojo.getAutoMemPath(projectRoot.toString()))
            .normalize().resolve("MEMORY.md").toString();

        PathValidation.InternalPathResult r =
            PathValidation.checkEditableInternalPath(memoryFile, env);
        assertThat(r.allowed())
            .as("核心层必须命中 auto-mem 写 carve-out（基址经 withAutoMem 填入后 isAutoMemPath 才可达）")
            .isTrue();
        assertThat(r.decisionReason())
            .as("reason 逐字对齐 CC")
            .isEqualTo(new PermissionDecisionReason.Other("auto memory files are allowed for writing"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1′. ⭐ 本批核心判据：分裂场景（effectiveCwd ≠ sessionProjectRoot）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐分裂场景：effectiveCwd ≠ sessionProjectRoot（模拟 bash cd / worktree）⇒ 写 <项目根 slug>/memory/MEMORY.md 仍必须 Allow")
    void autoMemEntrypointWrite_isAllowed_whenEffectiveCwdDivergesFromProjectRoot(
            @TempDir Path memoryRoot, @TempDir Path projectRoot, @TempDir Path divergedCwd) {
        // WHY（本用例钉住的意图 · 规则九）：真实症状不是「基址没填」，而是「基址的 slug 锚取错了源」。
        //   2026-09-22 实机：会话 sess-80e95674 的冻结项目根 = D:\code\ai_project\nexusai，但模型跑过
        //   `cd "D:/code/ai_project/ai-agent-client-svn"` 后 effectiveCwd 变成那个 SVN 工作副本 ⇒
        //   旧实现（base = getAutoMemPath(effectiveCwd)）算出的 slug 与真实记忆目录不同源 ⇒
        //   写 memory/*.md 恒落 SafetyCheck Ask，用户每写一次记忆弹一次窗。
        //   ⛔ 故本用例的扰动点就是「两个 cwd 字段不同源」，其余输入与正例逐字节同构。
        Path memoryBase = memoryRoot.resolve(NexusaiPaths.getProjectDirName());
        AutoMemPaths pojo = pojoAutoMemPaths(projectRoot, memoryBase);
        assertFixtureShape(pojo, projectRoot, memoryBase);
        // sessionProjectRoot = projectRoot（介质同源），effectiveCwd = divergedCwd（模拟 cd 后的 cwd）
        bindSessionProjectRoot(projectRoot);

        ToolUseContext tuc = ctx(divergedCwd);
        PathValidationEnv env = PathValidationEnv.fromToolUseContext(tuc).withAutoMem(pojo);

        // ① 夹具前提自检（防「恒绿」）：两个字段必须真的不同源，否则本用例测不到分裂（扰动无效）。
        assertThat(env.sessionProjectRoot())
            .as("夹具前提：sessionProjectRoot 必须已被绑定（非 null/空白）")
            .isNotBlank();
        assertThat(env.effectiveCwd())
            .as("夹具前提：effectiveCwd 必须已填（模拟 cd 后的会话 cwd）")
            .isNotBlank();
        String rootSlug = AutoMemPaths.sanitizePath(pojo.getAutoMemBase(env.sessionProjectRoot()));
        String cwdSlug = AutoMemPaths.sanitizePath(pojo.getAutoMemBase(env.effectiveCwd()));
        assertThat(cwdSlug)
            .as("夹具前提：分裂场景必须真的分裂（root slug=%s / cwd slug=%s）—— 二者相同则本用例恒定绿",
                rootSlug, cwdSlug)
            .isNotEqualTo(rootSlug);

        // ② 真实落盘形状：<memoryBase>/projects/<sanitizePath(项目根)>/memory/MEMORY.md
        String autoMem = pojo.getAutoMemPath(projectRoot.toString());
        String memoryFile = Path.of(autoMem).normalize().resolve("MEMORY.md").toString();

        // ③ 核心层（被改代码的直接分派点）：auto-mem 写 carve-out 必须命中
        assertThat(PathValidation.checkEditableInternalPath(memoryFile, env).allowed())
            .as("分裂场景下核心层必须命中 auto-mem 写 carve-out（锚取错源 ⇒ 结构性不命中）")
            .isTrue();
        assertThat(PathValidation.checkEditableInternalPath(memoryFile, env).decisionReason())
            .as("reason 逐字对齐 CC")
            .isEqualTo(new PermissionDecisionReason.Other("auto memory files are allowed for writing"));

        // ④ 端到端（用户可见症状）：Edit/Write 记忆文件必须静默 allow、不弹窗
        WritePermissionChecker checker = new WritePermissionChecker();
        checker.setAutoMemPaths(pojo);
        Tool tool = new WriteFileTool(new PathGuard(projectRoot));
        PermissionResult result = checker.check(tool, input(memoryFile), ctx(divergedCwd));
        assertThat(result)
            .as("分裂场景下端到端必须 Allow（落 Ask ⇒ 每次写记忆都弹窗 = 用户报告的 0.1.19 症状）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("reason 必须是 CC 逐字文案")
            .isEqualTo(new PermissionDecisionReason.Other("auto memory files are allowed for writing"));

        // ⑤ 反向鉴别器（证明「锚来自稳定项目根」而非「碰巧放行」）：由 effectiveCwd 派生的那条
        //    【假】记忆目录不得被 carve-out 放行 —— 旧实现正是拿它当基址。
        String cwdDerivedFile = Path.of(pojo.getAutoMemPath(divergedCwd.toString()))
            .normalize().resolve("MEMORY.md").toString();
        assertThat(PathValidation.checkEditableInternalPath(cwdDerivedFile, env).allowed())
            .as("effectiveCwd 派生的假记忆目录不得被放行（若放行 ⇒ 基址仍取自 effectiveCwd）")
            .isFalse();

        // ⑥ 字段形态（诊断性 · 放在最后，与 AutoMemPathPrefixBoundaryTest「先行为后契约」同款）：
        //    autoMemBaseDir 必须 = 稳定项目根派生的记忆目录（带 CC 契约的尾分隔符）。
        assertThat(env.autoMemBaseDir())
            .as("基址必须由【稳定项目根】派生（⛔ 不是 effectiveCwd）")
            .isEqualTo(Path.of(autoMem).normalize().toString() + java.io.File.separator);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1″. ⭐ 真实路径形态复核（实机 0.1.19 症状路径）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * <b>真实路径形态复核</b>（⛔ 不用 {@code @TempDir} 当 memoryBase/项目根）：
     * {@code ~/.nexusai/projects/<主仓根 sanitize 后的 slug>/memory/<某.md>}。
     *
     * <p>夹具姿态 = 实机 0.1.19 那一刻：{@code memoryBase} = 真实配置主目录
     * （{@link NexusaiPaths#getAppConfigHomeDir()} = {@code ~/.nexusai}）、项目根 = 真实 git 仓库
     * canonical 根（{@code findCanonicalGitRoot(user.dir)}，worktree 经 gitdir/commondir 归一后即主仓根
     * ⇒ slug 与实机一致），而会话 cwd 漂到<b>仓库外</b>（{@code @TempDir}，非 git ⇒ 模拟实机的
     * {@code cd "D:/code/ai_project/ai-agent-client-svn"} SVN 工作副本）。
     *
     * <p>本用例<b>不</b>读写任何文件（carve-out 是纯路径判定）——{@code AutoMemPaths} 只做路径计算。
     */
    @Test
    @DisplayName("⭐真实路径形态：~/.nexusai/projects/<主仓根 slug>/memory/x.md + cwd 漂到仓库外 ⇒ 必须 Allow")
    void autoMemWrite_realConfigHomeAndRepoSlug_isAllowed_whenCwdDriftedOutsideRepo(
            @TempDir Path driftedCwd) {
        // 夹具前提 1：必须在 git 检出内跑（否则无从复现「主仓根 slug」）⇒ fail-loud，⛔ 不静默跳过。
        String repoRoot = AutoMemPaths.findCanonicalGitRoot(System.getProperty("user.dir"));
        assertThat(repoRoot)
            .as("夹具前提：本测试须跑在 git 检出内（user.dir=%s）—— 否则无法复现实机 slug",
                System.getProperty("user.dir"))
            .isNotNull();
        // 夹具前提 2：漂移 cwd 必须在任何 git 仓库之外，否则 canonical 归一后 slug 可能又相同 ⇒ 测不到分裂。
        assertThat(AutoMemPaths.findCanonicalGitRoot(driftedCwd.toString()))
            .as("夹具前提：漂移 cwd 必须是非 git 目录（模拟实机的 SVN 工作副本）")
            .isNull();

        String configHome = NexusaiPaths.getAppConfigHomeDir();
        AutoMemPaths pojo = new AutoMemPaths(() -> repoRoot, () -> configHome, () -> null, () -> null);
        bindSessionProjectRoot(Path.of(repoRoot));

        // 真实路径形态（⛔ 不是 @TempDir）：~/.nexusai/projects/<主仓根 slug>/memory/<某.md>
        String memoryFile = Path.of(pojo.getAutoMemPath(repoRoot))
            .normalize().resolve("reference_svn_client_mirror.md").toString();
        assertThat(memoryFile)
            .as("夹具前提：路径形态必须是 <configHome>/projects/<slug>/memory/<file>")
            .startsWith(configHome)
            .contains("projects" + java.io.File.separator)
            .contains("memory" + java.io.File.separator);
        assertThat(pojo.getAutoMemBase(repoRoot))
            .as("夹具前提：项目根 canonical 归一后即仓库根（实机 slug 来源）")
            .isEqualTo(repoRoot);

        WritePermissionChecker checker = new WritePermissionChecker();
        checker.setAutoMemPaths(pojo);
        Tool tool = new WriteFileTool(new PathGuard(Path.of(repoRoot)));

        PermissionResult result = checker.check(tool, input(memoryFile), ctx(driftedCwd));

        assertThat(result)
            .as("真实路径形态 + cwd 已漂 ⇒ 仍必须 Allow（旧实现在此落 SafetyCheck Ask = 实机每次弹窗）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("reason 必须是 CC 逐字文案")
            .isEqualTo(new PermissionDecisionReason.Other("auto memory files are allowed for writing"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. 对照（扰动生效自证）：不装配 autoMemPaths ⇒ 同一路径【不是 allow】
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("对照：同一路径 + 不装配 setAutoMemPaths ⇒ 非 Allow（证明基址注入是承重的）")
    void autoMemEntrypointWrite_withoutWiredAutoMemPaths_isNotAllowed(
            @TempDir Path memoryRoot, @TempDir Path projectRoot) {
        // WHY：本用例是「扰动生效」的常驻自证 —— 输入与正例逐字节相同，唯一差别是 checker 未装配
        //   AutoMemPaths（withAutoMem(null) 直接 return this ⇒ autoMemBaseDir 仍为工厂硬编码 null）。
        //   它证明正例的绿不是恒绿（不是「本来就放行」），也证明 env 的基址只能来自注入槽，
        //   ⛔ 不是被别处伪造出来的。改前跑红输出见实施报告（正例红、本用例绿 = 病根定位正确）。
        Path memoryBase = memoryRoot.resolve(NexusaiPaths.getProjectDirName());
        AutoMemPaths pojo = pojoAutoMemPaths(projectRoot, memoryBase);

        String memoryFile = Path.of(pojo.getAutoMemPath(projectRoot.toString()))
            .normalize().resolve("MEMORY.md").toString();

        WritePermissionChecker checker = new WritePermissionChecker(); // ⛔ 不调 setAutoMemPaths
        Tool tool = new WriteFileTool(new PathGuard(projectRoot));

        PermissionResult result = checker.check(tool, input(memoryFile), ctx(projectRoot));

        assertThat(result)
            .as("未注入基址 ⇒ 写 carve-out 结构性不可达 ⇒ 必须【不是】allow（否则说明基址另有来路，"
                + "本批的 withAutoMem 接线就不是承重的）")
            .isNotInstanceOf(PermissionResult.Allow.class);
        assertThat(result)
            .as("实际落 1.7 safety Ask（路径段含动态项目目录名 .{appName} ⇒"
                + " isDangerousFilePathToAutoEdit）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. 反向护栏：不在 memory 目录下的 .nexusai 路径不得被 carve-out 放行
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("反向护栏：<autoMem>/../tool-results/o.txt（非 memory 目录）⇒ 核心 passthrough + 端到端 Ask")
    void pathOutsideMemoryDir_isNotCarvedOut(
            @TempDir Path memoryRoot, @TempDir Path projectRoot) {
        // WHY（防放宽过头）：本批只补基址可达性、⛔ 不放宽路径判定。若有人把判定改成
        //   「startsWith(base 的父目录)」或把 base 取成 memoryBase 根，这条路径会被静默写
        //   —— 即模型可无弹窗写 ~/.nexusai 下的任意文件（含 DB / settings）。本用例是那条红线的哨兵。
        Path memoryBase = memoryRoot.resolve(NexusaiPaths.getProjectDirName());
        AutoMemPaths pojo = pojoAutoMemPaths(projectRoot, memoryBase);
        // 绑定稳定项目根 ⇒ base 真的落在本用例项目根派生的 slug 上，guardPath 才是「同 slug 同级」的
        // ⭐ 有意义对照（不绑定则 sessionProjectRoot=user.dir ⇒ base 在别处 ⇒ 本断言恒绿 = 扰动无效）。
        bindSessionProjectRoot(projectRoot);
        String autoMem = pojo.getAutoMemPath(projectRoot.toString());
        // 与 autoMem 同级但不在 memory 目录内（同 slug 目录下）：
        String guardPath = Path.of(autoMem).normalize().getParent()
            .resolve("tool-results").resolve("o.txt").toString();

        // (i) 核心层：carve-out 不命中（passthrough）——直接钉住被改代码的边界
        PathValidationEnv env = PathValidationEnv.fromToolUseContext(ctx(projectRoot)).withAutoMem(pojo);
        assertThat(PathValidation.checkEditableInternalPath(guardPath, env).allowed())
            .as("非 memory 目录下的 .nexusai 路径不得被 auto-mem 写 carve-out 放行（判定未放宽）")
            .isFalse();

        // (ii) 端到端：仍落 Ask(SafetyCheck)
        WritePermissionChecker checker = new WritePermissionChecker();
        checker.setAutoMemPaths(pojo);
        Tool tool = new WriteFileTool(new PathGuard(projectRoot));

        PermissionResult result = checker.check(tool, input(guardPath), ctx(projectRoot));

        assertThat(result)
            .as("非 memory 目录下的 .nexusai 路径仍须走用户确认（不得静默写）")
            .isInstanceOf(PermissionResult.Ask.class);
        // ⚠️ 只断言「是 SafetyCheck」不钉具体文案：1.7 三道安全检查（可疑 Windows 模式 / claude 配置 /
        //   危险文件目录，WritePermissionChecker.checkPathSafetyForAutoEdit）的先命中者取决于临时目录
        //   形状（本机 %TEMP% 含 8.3 短名时会先命中可疑 Windows 模式）。三者都是 SafetyCheck，
        //   「不得静默写」这一承重语义与先命中哪道无关。
        assertThat(((PermissionResult.Ask) result).reason())
            .as("实际落 1.7 safety Ask（SafetyCheck），⛔ 不是 auto-mem 的 Allow(Other)")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }
}
