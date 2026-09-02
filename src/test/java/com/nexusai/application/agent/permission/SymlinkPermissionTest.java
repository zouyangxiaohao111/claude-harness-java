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
    /** 13 参工厂：显式 effectiveCwd（null 会被 ToolUseContext 归一为进程 CWD）。 */
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
    void workingDirFailClosed_anyPathOutside() {
        Path cwd = Paths.get("target", "s08-cwd-" + UUID.randomUUID().toString().substring(0, 8));
        Path outside = Paths.get("target", "s08-out-" + UUID.randomUUID().toString().substring(0, 8));
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd);

        // 全部在内 → true
        assertThat(ReadPermissionChecker.isInWorkingDir(
            List.of(cwd.resolve("a.txt").toString()), ctx))
            .as("全部展开路径在 cwd 内 → true")
            .isTrue();
        // 混入越界路径 → false（CC pathsToCheck.every 语义）
        assertThat(ReadPermissionChecker.isInWorkingDir(
            List.of(cwd.resolve("a.txt").toString(), outside.resolve("b.txt").toString()), ctx))
            .as("任一展开路径越界 → false（fail-closed）")
            .isFalse();
        // 全部越界 → false
        assertThat(ReadPermissionChecker.isInWorkingDir(
            List.of(outside.resolve("b.txt").toString()), ctx))
            .isFalse();
    }
}
