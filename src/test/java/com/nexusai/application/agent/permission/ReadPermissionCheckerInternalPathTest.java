package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryPromptBuilder;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPD-WF5-02-02 · ReadPermissionChecker 内部可读路径白名单接入核心测试。
 *
 * <p><b>RED→GREEN 语义</b>：改动前 Java 仅有 agent-memory/auto-mem/bundled-skills 3 读 carve-out，
 * session-memory / plan / tool-results 等路径落到兜底 ask（探查 EV-FS-039h）；改动后经
 * {@link PathValidation#checkReadableInternalPath} 静默 allow（对齐 CC filesystem.ts:1611-1777）。
 *
 * <p><b>配置根 seam（2026-09-16 修正）</b>：session-memory 与 plans 的<b>白名单基址</b>是
 * nexusai 自有根 {@code NexusaiPaths.getAppConfigHomeDir()}（{@code ~/.{appName}} = {@code ~/.nexusai}）
 * —— 见 {@link PathValidationEnv#sessionMemoryDir()}（{@code Path.of(nexusaiConfigHomeDir,
 * "session-memory")}）与 {@link PathValidationEnv#plansPrefix()}。{@link PathValidationEnv}
 * 的 {@code claudeConfigHomeDir}（{@code ~/.claude}）只是只读兼容根（D3/D4 读取回落源），
 * <b>不</b>参与这两个分支。
 * ⛔ 旧测试覆写的是 {@link ClaudePaths#setConfigDirOverride}（另一个根）⇒ 白名单基址仍指向真实
 * {@code ~/.nexusai} ⇒ 分支不命中，两条用例只能靠 step6 working-dir 兜底放行
 * （reason=Other("read permission default allow")，与断言的 CC 文案不符）⇒ 恒红。
 * ⚠️ [批 E1 · O-2b] 上述「step6 落点 = Other(...)」是**改前**读数；step6 的 reason 已改
 * {@code Mode(PermissionMode.DEFAULT)}（CC filesystem.ts:1146-1149）⇒ 判「是否被 step6 兜住」
 * 的锚同步换成 Mode(DEFAULT)（见 {@link #toolResults_read_allow}）。
 * <b>白名单分支本身是活的</b>（{@code ReadPermissionChecker:285} → {@code checkReadableInternalPath}
 * → {@code if (internal.allowed()) return Allow(...)}），故本类只改测试 seam。
 *
 * <p>现经 {@link NexusaiPaths#setConfigHomeDirOverride} 把自有根隔离到本模块 {@code target/} 下
 * （{@code tearDown} 复位），避免命中真实用户目录。
 */
@DisplayName("OPD-WF5-02-02 · ReadPermissionChecker 内部可读路径白名单接入核心")
class ReadPermissionCheckerInternalPathTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIG_DIR = "target/wf6-internal-" + UUID.randomUUID();

    /**
     * 白名单基址 = nexusai 自有根（{@link NexusaiPaths#getAppConfigHomeDir()}）⇒ 覆写该 seam
     * （⛔ 不是 {@link ClaudePaths#setConfigDirOverride}，那是 {@code ~/.claude} 只读兼容根）。
     */
    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(Paths.get(CONFIG_DIR).toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext emptyRulesCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.of());
    }

    private static Path cwdDir() {
        return Paths.get("target", "wf6-internal-cwd-" + UUID.randomUUID()).toAbsolutePath();
    }

    private static ReadPermissionChecker checker() {
        return new ReadPermissionChecker(new WritePermissionChecker());
    }

    /** 白名单基址 = 被 {@link NexusaiPaths#setConfigHomeDirOverride} 覆写的 nexusai 自有根。 */
    private static Path configHome() {
        return NexusaiPaths.getAppConfigHomePath();
    }

    @Test
    @DisplayName("[E2] session-memory 文件读 → 静默 Allow（真实介质形状，CC filesystem.ts:1620-1629）")
    void sessionMemory_read_allow() {
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), emptyRulesCtx(), PermissionMode.DEFAULT,
            Map.of(), false, "", cwdDir());
        // [批 E2] 夹具改为**真实介质形状**（SessionMemoryService.resolvePath:2272-2283）：
        //   {configHome}/projects/{slug}/{sessionId}/session-memory/summary.md。
        //   ⛔ 旧夹具 {configHome}/session-memory/summary.md 是**假门**（实测该目录 0 文件；
        //   真实 summary.md 50 个全在 projects/{slug}/{sid}/ 下），旧断言只因 project-dir 宽口
        //   兜住才「绿」且 reason 实为 project-dir 文案 ⇒ 改前必红。
        //   此处 slug 直接用**介质自己的派生**（SessionStorage.sessionProjectDir）—— 既是夹具又是同源守卫。
        Path sessionMemoryFile = SessionStorage.sessionProjectDir(tuc.sessionId())
            .resolve(tuc.sessionId()).resolve("session-memory").resolve("summary.md");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(sessionMemoryFile.toString()), tuc);

        assertThat(result)
            .as("CC checkReadableInternalPath session-memory 分支（filesystem.ts:1620-1629）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("必须由 session-memory 分支命中（⛔ 不是 project-dir / 兜底路径）")
            .isEqualTo(new PermissionDecisionReason.Other("Session memory files are allowed for reading"));
    }

    @Test
    @DisplayName("plan 文件读 → 静默 Allow（CC filesystem.ts:1645-1654）")
    void planFile_read_allow() {
        // env.plansPrefix() = {nexusaiConfigHomeDir}/plans/{sessionId}（NexusaiPaths.getAppConfigHomeDir，
        // 本类经 setConfigHomeDirOverride 隔离到 CONFIG_DIR）；读分支 auto-allow。
        Path configHomeDir = Paths.get(CONFIG_DIR).toAbsolutePath();
        // 用任意 sessionId 构造 plan 路径；checker 的 env 用 ctx.sessionId()，故此处须用同一 sessionId。
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), emptyRulesCtx(), PermissionMode.DEFAULT,
            Map.of(), false, "", cwdDir());
        Path planFile = configHomeDir.resolve("plans").resolve(tuc.sessionId().toString() + ".md");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(planFile.toString()), tuc);

        assertThat(result)
            .as("CC checkReadableInternalPath plan 分支（filesystem.ts:1645-1654）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isEqualTo(new PermissionDecisionReason.Other("Plan files for current session are allowed for reading"));
    }

    @Test
    @DisplayName("tool-results 文件读 → 静默 Allow（CC filesystem.ts:1656-1674）")
    void toolResults_read_allow() {
        Path cwd = cwdDir();
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), emptyRulesCtx(), PermissionMode.DEFAULT,
            Map.of(), false, "", cwd);
        // Java 真实介质 = {nexusaiConfigHomeDir}/projects/{sanitizePath(effectiveCwd)}/{sessionId}/tool-results
        // （PathValidationEnv.toolResultsDir():180-189，S2 迁移后与 ToolResultStorage 同根）。
        // ⛔ 旧夹具写成 {cwd}/{sessionId}/tool-results —— 那是「工作目录内」的路径，压根不命中白名单分支：
        //   本用例当时只因断言仅查 `isInstanceOf(Allow)` 而被 step6 working-dir allow 兜住 ⇒ **假绿**
        //   （2026-09-16 实测：临时补一条 reason 断言即失败，落点 reason = Other("read permission default allow")）。
        //   ⚠️ [批 E1 · O-2b] 上述实测读数为**改前**记录；step6 的 reason 已改 Mode(PermissionMode.DEFAULT)
        //   ⇒ 本用例的反向判别锚随之换成 Mode(DEFAULT)（否则该断言失去鉴别力 = 恒绿）。
        Path toolResults = configHome().resolve("projects")
            .resolve(AutoMemPaths.sanitizePath(cwd.toAbsolutePath().normalize().toString()))
            .resolve(tuc.sessionId().toString()).resolve("tool-results").resolve("o.txt");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwd)),
            input(toolResults.toString()), tuc);

        assertThat(result)
            .as("CC checkReadableInternalPath tool-results 分支（filesystem.ts:1656-1674）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        // ⭐ [批 E2 · 分支可达性】三段式结论（每一段都有实测读数）：
        //   ① 收窄前：projectDir() = {configHome}/projects/（**整个 projects 根**）在
        //      checkReadableInternalPath 中**先于** tool-results 分支，而 toolResultsDir() 位于
        //      projects/ 之下 ⇒ 实测命中 project-dir 分支（reason="Project directory files…"）
        //      ⇒ tool-results 分支**结构性不可达**（2026-09-16 批 O-4 实测登记）。
        //   ② 只留「稳定根半」时（E2 首轮）：本夹具的 tool-results 落点用 effectiveCwd 派生 slug，
        //      与稳定根 slug 分裂 ⇒ 该分支**首次可达**（当时本行断言的是
        //      "Tool result files are allowed for reading"）。
        //   ③ ⭐ [方案 ① 2026-09-16 用户裁定] 加入 cwd 半后：projectDir() 的 cwd 半
        //      （{sanitizePath(effectiveCwd)}，对齐 CC isProjectDirPath = getProjectDir(getCwd())）
        //      与 toolResultsDir() **恒同 slug** ⇒ tool-results 根恒 ⊂ cwd 半 ⇒ 该分支
        //      **在本仓恒不可达**（CC 因 tool-results 用 getOriginalCwd() 而仅在 cd 后可达）。
        //      ⇒ 本行按**实际**放行分支断言 project-dir，并保留 E1 的反向判别锚。
        //      ⛔ 这是「登记不修」项：本批不重排分支顺序（CC 顺序亦为 project-dir 在前）。
        assertThat(((PermissionResult.Allow) result).reason())
            .as("由内部白名单 project-dir 分支放行（tool-results 分支被其 cwd 半结构性遮蔽，"
                + "对齐 CC 分支顺序）；且不是 step6 工作目录兜底 Mode(PermissionMode.DEFAULT)（E1 反向判别锚）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"))
            // [E1 · O-2b] 反向判别锚：step6「工作目录内 → allow」= Mode(PermissionMode.DEFAULT)
            .isNotEqualTo(new PermissionDecisionReason.Mode(PermissionMode.DEFAULT));
    }

    @Test
    @DisplayName("[批 E1 · O-2b] agent-memory 读 carve-out → Allow(Other)（⛔ 不得被并成 step6 的 Mode(DEFAULT)）")
    void agentMemoryReadCarveOut_staysOtherNotModeDefault(@TempDir Path memoryBase) {
        // agent-memory 读 carve-out（ReadPermissionChecker:301-308）与 step6 工作目录放行
        //   （:311-317）此前共用同一个 defaultAllow(input) 构造器 ⇒ 二者 reason 类型被迫相同。
        //   CC 两者类型不同：carve-out = `other`（filesystem.ts:1709-1718）／step6 = `mode`
        //   （:1146-1149）⇒ [批 E1 · O-2b] 拆成 defaultAllow / agentMemoryReadAllow 两条腿。
        //   ⛔ 本用例守「第二条腿」：把 carve-out 改回 defaultAllow（= 重新合并）⇒ 本用例红。
        // 命中分支 = AgentMemoryDirectory.isAgentMemoryPath 的 user 段（agentMemory.ts:74）：
        //   normalized.startsWith(join(memoryBase,'agent-memory') + sep) —— 纯路径判定，无需文件存在。
        Path target = memoryBase.resolve("agent-memory").resolve("myagent").resolve("MEMORY.md");
        // 夹具前提（fail-loud）：目标必须落在工作目录锚之外，否则 step6 会先 Allow，
        //   carve-out 分支不可达 ⇒ 本用例静默空转。
        Path anchor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        assertThat(target.toAbsolutePath().normalize().startsWith(anchor))
            .as("夹具前提：@TempDir 下的 agent-memory 目标 %s 必须在工作目录锚 %s 之外"
                + "（否则 step6 先放行 ⇒ 本用例对 carve-out 零鉴别力）", target, anchor)
            .isFalse();

        ReadPermissionChecker checker = checker();
        checker.setAgentMemoryDirectory(new AgentMemoryDirectory(
            () -> memoryBase.toString(),                              // cwdSupplier（user 段不读）
            () -> memoryBase,                                         // memoryBaseSupplier
            () -> null,                                               // remoteMemoryDirSupplier
            () -> memoryBase,                                         // projectRootSupplier
            AutoMemPaths::sanitizePath,
            p -> { },
            () -> null,
            () -> true,
            MemoryPromptBuilder.productionDefault()));

        PermissionResult result = checker.check(new ReadFileTool(new PathGuard(cwdDir())),
            input(target.toString()), ctx(emptyRulesCtx(), cwdDir()));

        assertThat(result)
            .as("agent-memory carve-out 未命中时该路径在工作目录外 ⇒ 走兜底 Ask ⇒ 本条红"
                + "（Allow 即证明是 carve-out 放的，不是 step6）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("⭐ agent-memory 腿的 reason 必须仍是 Other（CC filesystem.ts:1712-1716 type:'other'）"
                + "—— 与 step6 腿的 Mode(DEFAULT) 类型必须不同")
            .isEqualTo(new PermissionDecisionReason.Other("Agent memory files are allowed for reading"))
            .isNotEqualTo(new PermissionDecisionReason.Mode(PermissionMode.DEFAULT));
    }

    @Test
    @DisplayName("[E2] 别的项目（别的 slug）下的 project 文件读 → 内部白名单不放行")
    void otherProject_notAllowedByInternalWhitelist() {
        // ⭐ 宽口守卫（批 O-4 实测：configHome/projects/ 下 **662** 个 slug 目录全被旧 project-dir
        //   宽口静默放行）。本项目 slug 由稳定项目根派生，此处构造**另一个** slug。
        // 断言口径说明：本类的 CONFIG_DIR 在模块目录（= 单测的工作目录锚）之下 ⇒ 内部白名单未命中后
        //   会落到步骤 6「工作目录内 → allow」。该 reason 的**类型** [批 E1 · O-2b] 已由
        //   Other("read permission default allow") 改为 Mode(PermissionMode.DEFAULT)
        //   （CC filesystem.ts:1146-1149 type:'mode'；原 Other 串现只属 agent-memory 腿）
        //   ⇒ 精确断言 reason == Mode(DEFAULT) = 「内部白名单**没有**放行它，只有 step6 兜住」
        //   （旧行为下 reason 为 "Project directory files are allowed for reading" ⇒ 本用例改前必红）。
        Path otherProject = configHome().resolve("projects").resolve("C--some-other-project")
            .resolve("sess-other").resolve("session.jsonl");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(otherProject.toString()), ctx(emptyRulesCtx(), cwdDir()));

        assertThat(((PermissionResult.Allow) result).reason())
            .as("跨项目读取不得由内部白名单分支放行（对齐 CC filesystem.ts:284-291 只放当前项目 slug）"
                + "—— 落到 step6 工作目录兜底即证明白名单未放行")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.DEFAULT));
    }

    @Test
    @DisplayName("普通目录外文件读 → 仍兜底 Ask（内部白名单不误放行）")
    void ordinaryOutsideFile_stillAsk(@TempDir Path outsideDir) {
        // ⭐ 「工作目录外」必须相对**真实工作目录锚**成立：锚 = CwdResolution.getOriginalCwdLayer(
        //   ctx.sessionId())（对齐 CC allWorkingDirectories = getOriginalCwd()），⛔ 不是 ctx.effectiveCwd()。
        //   单测环境该锚恒 = 归一化进程 user.dir（本 maven 模块目录）。
        //   ⛔ 旧夹具用相对 target/wf6-outside-* —— 该路径实际落在 <user.dir>/target 下 = **锚内**
        //   ⇒ step6 working-dir allow 放行，返回 Allow(Other) 而非 Ask ⇒ 恒红（夹具前提与实现不符）。
        Path anchor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path outside = outsideDir.resolve("a.txt").toAbsolutePath();
        // 夹具前提自检（fail-loud）：@TempDir 必须真在锚之外
        assertThat(outside.startsWith(anchor))
            .as("夹具前提：@TempDir 路径 %s 必须在工作目录锚 %s 之外", outside, anchor)
            .isFalse();

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(outside.toString()), ctx(emptyRulesCtx(), cwdDir()));

        assertThat(result)
            .as("非内部路径 + 工作目录外 → 兜底 ask（fail-closed 不因新白名单误放行）")
            .isInstanceOf(PermissionResult.Ask.class);
    }
}
