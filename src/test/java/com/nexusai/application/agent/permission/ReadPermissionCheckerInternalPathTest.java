package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
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
    @DisplayName("session-memory 文件读 → 静默 Allow（CC filesystem.ts:1620-1629）")
    void sessionMemory_read_allow() {
        Path sessionMemoryFile = Paths.get(CONFIG_DIR, "session-memory", "summary.md").toAbsolutePath();

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(sessionMemoryFile.toString()), ctx(emptyRulesCtx(), cwdDir()));

        assertThat(result)
            .as("CC checkReadableInternalPath session-memory 分支（filesystem.ts:1620-1629）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
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
        Path toolResults = configHome().resolve("projects")
            .resolve(AutoMemPaths.sanitizePath(cwd.toAbsolutePath().normalize().toString()))
            .resolve(tuc.sessionId().toString()).resolve("tool-results").resolve("o.txt");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwd)),
            input(toolResults.toString()), tuc);

        assertThat(result)
            .as("CC checkReadableInternalPath tool-results 分支（filesystem.ts:1656-1674）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        // ⚠️ 不点名声明白名单内的哪一个分支：本仓 projectDir() = {configHome}/projects/（**projects 根**，
        //    PathValidationEnv:166-169）在 checkReadableInternalPath 中**先于** tool-results 分支，
        //    而 toolResultsDir() 位于 projects/ 之下 ⇒ 该路径实测命中的是 project-dir 分支
        //    （reason="Project directory files are allowed for reading"），**tool-results 分支结构性不可达**。
        //    （2026-09-16 实测；已登记为发现，⛔ 本批只改测试、不动 src/main。）
        //    故此处只断言「由内部白名单放行，而非兜底路径」——对分支重排稳健，且能抓住
        //    「白名单不再命中 ⇒ 落到 step6/兜底」的回归（那是本用例真正要守的东西）。
        assertThat(((PermissionResult.Allow) result).reason())
            .as("必须由内部白名单分支放行（step6 working-dir 兜底的 Other(\"read permission default allow\") = 未覆盖）")
            .isNotEqualTo(new PermissionDecisionReason.Other("read permission default allow"));
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
