package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-A-R2 · backfilledInput（file_path 绝对化）透传 → 相对/~ 路径命中 read deny 规则。
 *
 * <p><b>验证的验收标准（WHY 安全缺口）</b>：权限内容规则（deny/ask）以
 * <b>文件系统根锚定的绝对 glob</b> 形式登记（CC 写 {@code //…}；⛔ 裸绝对路径在 CC
 * {@code patternWithRoot} 下表达的是 <b>source 根相对</b>路径，见 {@link #toFsRootGlob}），
 * 而 LLM 传入的 {@code file_path} 可能是 {@code ~} 或相对路径。若相对/~ 路径不展开为绝对，
 * 就匹配不到该 deny/ask glob → 落入 ask 兜底，绕过权限门。
 *
 * <p>CC 的机制是 <b>backfill</b>（而非 checker 二次展开）：{@code toolExecution.ts:781-793}
 * 在 hook/canUseTool 观察前把 {@code processedInput} 换成 backfill clone（
 * {@code FileReadTool.ts:388-393 backfillObservableInput} 把 {@code file_path} 展开为绝对），
 * 权限门收到的是绝对路径（{@code toolExecution.ts:921-936} resolveHookPermissionDecision
 * 第 3 参 processedInput）。Java 等价 = StreamingToolExecutor 把 {@code backfilledInput}
 * 透传给 permission 门（grep 复验 hard_metrics），checker 用原始 path 展开
 * （filesystem.ts:1048 {@code getPathsForPermissionCheck(path)}，与 CC 对称）。
 *
 * <p>本测试锁定 <b>backfill 绝对化 + checker 根锚定匹配</b> 的集成安全属性：相对/~ 路径
 * 经 {@code backfillObservableInput} 绝对化后，命中 read deny 规则（防相对/~ 路径绕过）。
 * StreamingToolExecutor 接线点（{@code permissionInput = backfilledInput}）由主 agent
 * grep 复验（hard_metrics）。
 *
 * <p>[P19] 本类两条夹具原先用「单 `/` 绝对串 glob」表达 deny 规则 —— 那是 P19 前 read 桶
 * 无根锚的模型；CC 语义下（两桶共用 {@code patternWithRoot}）必须用 {@code //} 前缀，
 * 否则规则会锚到 cwd 而漏掉目标文件（这正是本测试要防的绕过面）。
 */
@DisplayName("FIX-A-R2 · backfill 绝对化透传 → 相对/~ 路径命中 read deny")
class ReadPermissionCheckerRelativePathExpandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
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

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 目标目录（绝对化：相对展开基座必须是绝对路径，镜像 CC getCwd() 绝对语义）。 */
    private static Path targetDir() {
        return Paths.get("target", "rpr-" + rand()).toAbsolutePath();
    }

    /**
     * 规则内容 → <b>文件系统根锚定</b>的 glob（CC patternWithRoot filesystem.ts:860-892）。
     *
     * <p>WHY（[P19] read 桶 root-relative 化后）：read 桶路径规则与 edit 桶共用同一份
     * {@code matchesPathRuleRootRelative}（CC 两桶共用 {@code patternWithRoot}，
     * filesystem.ts:943）⇒ 裸绝对路径（单 `/`）按 CC 语义被当作 <b>source 根</b>
     * （session → cwd）相对，<b>不</b>表达「文件系统上的绝对路径」。要表达后者必须用
     * {@code //} 前缀（Windows 盘符形 {@code //c/…}，CC :867-887）。
     *
     * @param dir 目录（{@code @TempDir} 或 target 下临时目录）
     * @return {@code //<root>/<posixPath>/**}
     */
    private static String toFsRootGlob(Path dir) {
        String abs = dir.toAbsolutePath().toString().replace('\\', '/');
        if (abs.matches("^[a-zA-Z]:.*")) {
            return "//" + abs.substring(0, 1).toLowerCase() + abs.substring(2) + "/**";
        }
        return "//" + abs + "/**";
    }

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
            String toolName, String content) {
        return new PermissionRule(source, behavior, PermissionRuleValue.withContent(toolName, content));
    }

    // ──────────────────────────────────────────────────────────────────────
    // backfill 绝对化 → 相对/~ 路径命中 read deny
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("相对 file_path 经 backfill 绝对化后命中 read deny 文件系统根 glob → Deny(Rule)")
    void relativeFilePath_backfilled_thenHitsReadDeny() {
        Path dir = targetDir();
        PathGuard guard = new PathGuard(dir);
        ReadFileTool tool = new ReadFileTool(guard);
        PermissionRule deny = rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "Read", toFsRootGlob(dir));
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(deny)), Map.of()), dir);
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());

        // 镜像 StreamingToolExecutor 把 backfilledInput（file_path 绝对化）透传给 permission 门
        JsonNode backfilled = tool.backfillObservableInput(input("secret/a.txt"));

        PermissionResult result = checker.check(tool, backfilled, ctx);

        assertThat(result)
            .as("CC: 相对 file_path 经 backfill 绝对化后应命中 read deny 规则（防相对路径绕过权限门）")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .as("CC: decisionReason=Rule(deny rule)（filesystem.ts:1081-1101）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("~ file_path 经 backfill 绝对化后命中 read deny 文件系统根 glob → Deny(Rule)")
    void tildeFilePath_backfilled_thenHitsReadDeny() {
        Path home = Paths.get(System.getProperty("user.home", "."));
        PathGuard guard = new PathGuard(targetDir());
        ReadFileTool tool = new ReadFileTool(guard);
        PermissionRule deny = rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "Read", toFsRootGlob(home));
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(deny)), Map.of()), targetDir());
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());

        JsonNode backfilled = tool.backfillObservableInput(input("~/secret.txt"));

        PermissionResult result = checker.check(tool, backfilled, ctx);

        assertThat(result)
            .as("CC: ~ file_path 经 backfill 绝对化后应命中 read deny 规则（防 ~ 路径绕过权限门）")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .as("CC: decisionReason=Rule(deny rule)（filesystem.ts:1081-1101）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }
}
