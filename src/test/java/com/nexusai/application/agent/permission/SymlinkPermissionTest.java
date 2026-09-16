package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S08 · symlink 权限检查 focused 测试（CC {@code fsOperations.ts:288-382
 * getPathsForPermissionCheck} + filesystem.ts pathsToCheck 消费链）。
 *
 * <p><b>验证的验收标准</b>：
 * <ol>
 *   <li>权限检查覆盖 original + symlink 解析后全路径（deny 规则可经解析落点命中）；</li>
 *   <li>悬空 symlink / 越界 symlink 拒绝（fail-closed：acceptEdits 自动放行不适用于
 *       越界目标，CC pathInAllowedWorkingPath filesystem.ts:683-707）；</li>
 *   <li>工作目录判定双侧解析：任一展开路径越界 → 不在工作目录内。</li>
 * </ol>
 *
 * <p><b>RED→GREEN</b>：用例 1-5 在 S08 前（仅检查原始字符串）必然 FAIL（deny 不命中 →
 * 工作目录内 Allow 放行）；S08 后 PASS。
 *
 * <p><b>环境说明</b>：真实 symlink 创建需要特权/开发者模式（本机 mklink 探测无特权），
 * 相关用例以 {@code assumeTrue} 守卫——无特权环境跳过、有特权环境（CI/Linux/开发者模式）
 * 运行；fail-closed 不变式另有无需 symlink 的直接断言兜底（见
 * {@link #workingDirFailClosed_anyPathOutside}）。
 */
@DisplayName("S08 · symlink 权限检查（CC getPathsForPermissionCheck + pathsToCheck 链）")
class SymlinkPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 最小 Tool 桩：检查器只消费 {@code tool.name()}（CC getPath 语义由 input 承载）。 */
    private static final class StubReadTool implements Tool {
        @Override public String name() { return "read_file"; }
        @Override public String description() { return "S08 测试桩"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
    }
    /**
     * 13 参工厂：显式 effectiveCwd。
     *
     * <p>⚠️ effectiveCwd <b>不是</b>权限链的工作目录锚（锚 =
     * {@code CwdResolution.getOriginalCwdLayer(ctx.sessionId())}，见
     * {@link #workingDirFailClosed_anyPathOutside}）；它只作 {@code expandPath} 的相对路径 baseDir
     * 与规则匹配基准。传 null 时由 TUC 构造器回填 {@code CwdResolution.getCwd(sessionId)}
     * （{@code ToolUseContext.java:451-453}）。⛔ 旧 javadoc 写「null 会被 ToolUseContext 归一为进程 CWD
     * （:311-312）」——行号与机制<b>都不对</b>：那是<b>按会话</b>解析（会 fail-loud），只在单测
     * sessionless 环境下才恒等于进程 {@code user.dir}。
     */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext rulesCtx(
            PermissionMode mode,
            Map<PermissionRuleSource, Set<PermissionRule>> allow,
            Map<PermissionRuleSource, Set<PermissionRule>> deny,
            Map<PermissionRuleSource, Set<PermissionRule>> ask) {
        return ToolPermissionContext.of(mode, allow, deny, ask, Map.of());
    }

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
            String toolName, String content) {
        return new PermissionRule(source, behavior, PermissionRuleValue.withContent(toolName, content));
    }

    /** 无 symlink 创建特权 → 跳过（Windows 需管理员/开发者模式；Linux/CI 正常创建）。 */
    private static void assumeCanCreateSymlink(Path dir) {
        Path probe = dir.resolve("s08-probe-link");
        try {
            Files.createSymbolicLink(probe, dir.resolve("s08-probe-target"));
            Files.deleteIfExists(probe);
        } catch (Exception e) {
            assumeTrue(false, "无 symlink 创建特权，跳过 symlink 用例: " + e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. original+symlink 全路径检查（验收标准 1）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("读 deny 规则命中 symlink 解析后落点 → Deny（S08 前：仅查原始路径 → Allow）")
    void symlinkTarget_readDenyRule_deniesViaLink(@TempDir Path workspace) throws Exception {
        assumeCanCreateSymlink(workspace);
        Path secret = workspace.resolve("secret.txt");
        Files.writeString(secret, "s3cr3t");
        Path link = workspace.resolve("link.txt");
        Files.createSymbolicLink(link, secret);

        // deny 规则只匹配解析后落点（**/secret.txt），不匹配 link.txt 本身
        PermissionRule deny = rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            "Read", "**/secret.txt");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(deny)), Map.of()), workspace);
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new StubReadTool();

        PermissionResult result = checker.check(tool, input(link.toString()), ctx);

        assertThat(result)
            .as("CC: read deny 遍历 pathsToCheck（filesystem.ts:1084-1101）→ symlink 目标命中 deny")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("写 deny 规则命中 symlink 解析后落点 → Deny（S08 前：仅查原始路径 → 工作目录内 Allow）")
    void symlinkTarget_writeDenyRule_deniesViaLink(@TempDir Path workspace) throws Exception {
        assumeCanCreateSymlink(workspace);
        Path target = workspace.resolve("w.txt");
        Files.writeString(target, "x");
        Path link = workspace.resolve("w-link.txt");
        Files.createSymbolicLink(link, target);

        PermissionRule deny = rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            "Edit", "**/w.txt");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(deny)), Map.of()), workspace);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new StubReadTool();

        PermissionResult result = checker.check(tool, input(link.toString()), ctx);

        assertThat(result)
            .as("CC: edit deny 遍历 pathsToCheck（filesystem.ts:1219-1239）→ symlink 目标命中 deny")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("symlink 链（entry → mid → final）：deny 命中最终目标 → Deny（CC fsOperations.ts:310-369 全链收集）")
    void symlinkChain_denyOnFinalTarget(@TempDir Path workspace) throws Exception {
        assumeCanCreateSymlink(workspace);
        Path finalFile = workspace.resolve("final.txt");
        Files.writeString(finalFile, "chain");
        Path mid = workspace.resolve("mid.txt");
        Files.createSymbolicLink(mid, finalFile);
        Path entry = workspace.resolve("entry.txt");
        Files.createSymbolicLink(entry, mid);

        PermissionRule deny = rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            "Read", "**/final.txt");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(deny)), Map.of()), workspace);
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new StubReadTool();

        PermissionResult result = checker.check(tool, input(entry.toString()), ctx);

        assertThat(result)
            .as("CC: getPathsForPermissionCheck 收集全部中间目标（fsOperations.ts:277-283 注释）→ 最终目标 deny 命中")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. 悬空 / 越界 symlink 拒绝（验收标准 2，fail-closed）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("悬空 symlink（link 存在、目标不存在）：deny 命中 readlink 目标 → Deny（CC fsOperations.ts:325-339）")
    void danglingSymlink_writeDenyRule_denies(@TempDir Path cwd, @TempDir Path outside) throws Exception {
        assumeCanCreateSymlink(cwd);
        Path target = outside.resolve("escape-target.txt"); // 悬空（目标不存在）
        Path link = cwd.resolve("evil.txt");
        Files.createSymbolicLink(link, target);

        PermissionRule deny = rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            "Edit", "**/escape-target.txt");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(deny)), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new StubReadTool();

        PermissionResult result = checker.check(tool, input(link.toString()), ctx);

        assertThat(result)
            .as("CC: 悬空 symlink 经最深祖先解析暴露真实落点（fsOperations.ts:325-339）→ deny 命中")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("越界 symlink（目录 symlink 指向工作目录外）：acceptEdits 模式不得自动放行 → Ask（fail-closed）")
    void symlinkEscapingWorkingDir_acceptEditsNotAutoAllowed(
            @TempDir Path cwd, @TempDir Path outside) throws Exception {
        assumeCanCreateSymlink(cwd);
        Path linkDir = cwd.resolve("data");
        Files.createSymbolicLink(linkDir, outside);

        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.ACCEPT_EDITS, Map.of(), Map.of(), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new StubReadTool();

        PermissionResult result = checker.check(
            tool, input(linkDir.resolve("new.txt").toString()), ctx);

        assertThat(result)
            .as("CC: pathInAllowedWorkingPath 要求全部展开路径在工作目录内（filesystem.ts:702-706）→ 越界目标不自动放行")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. fail-closed 不变式（无需 symlink 特权，恒运行）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("工作目录判定 fail-closed：任一展开路径越界 → 不在工作目录内")
    void workingDirFailClosed_anyPathOutside(@TempDir Path outside) {
        // ⭐ 工作目录锚 = CwdResolution.getOriginalCwdLayer(ctx.sessionId())（对齐 CC
        //   allWorkingDirectories = getOriginalCwd()，filesystem.ts:666-674），⛔ **不是**
        //   ctx.effectiveCwd()（后者只作 expandPath 的相对路径 baseDir / 规则匹配基准）。
        //   单测环境该锚恒 = 归一化进程 user.dir（本 maven 模块目录）：NoDatabaseSessionProjectRootExtension
        //   对任意 sessionId 答 sessionlessEnvironment() ⇒ 走无会话命名出口 getOriginalCwdLayerForNonSession()。
        //   ⛔ 旧夹具把「界内」路径写成相对 target/s08-cwd-*、把「界外」也写在 target/ 下 ——
        //   二者**都在** user.dir 锚内 ⇒ 第二条断言恒假红（夹具前提与实现不符，非实现缺陷）。
        //   现「界内」基准直接写成 user.dir（与实现独立表述），「界外」用 @TempDir（java.io.tmpdir 之下）。
        Path anchor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        // ctx.effectiveCwd() 仍传相对 target/ 路径：它不是锚，只影响 expandPath 基准；
        // 若有人把锚改回读 effectiveCwd，则下方「界内」断言会因 effectiveCwd=target/s08-cwd-* 而反转 ⇒ 红。
        Path cwd = Paths.get("target", "s08-cwd-" + UUID.randomUUID().toString().substring(0, 8));
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd);

        Path inside = anchor.resolve("s08-in-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
        Path out = outside.resolve("s08-out-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
        // 夹具前提自检（fail-loud）：@TempDir 必须真在锚之外，否则本用例静默失去鉴别力
        assertThat(out.startsWith(anchor))
            .as("夹具前提：@TempDir 路径 %s 必须在工作目录锚 %s 之外", out, anchor)
            .isFalse();

        // 全部在内 → true
        assertThat(ReadPermissionChecker.isInWorkingDir(List.of(inside.toString()), ctx))
            .as("全部展开路径在工作目录锚（单测 = 进程 user.dir）内 → true")
            .isTrue();
        // 混入越界路径 → false（CC pathsToCheck.every 语义）
        assertThat(ReadPermissionChecker.isInWorkingDir(
            List.of(inside.toString(), out.toString()), ctx))
            .as("任一展开路径越界 → false（fail-closed）")
            .isFalse();
        // 全部越界 → false
        assertThat(ReadPermissionChecker.isInWorkingDir(
            List.of(out.toString()), ctx))
            .isFalse();
    }
}
