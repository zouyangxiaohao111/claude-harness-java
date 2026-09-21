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
