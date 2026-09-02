package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S06 · NotebookEditTool 写权限检查接线（X12）· 对齐 CC {@code NotebookEditTool.ts:125-132}
 * {@code checkPermissions → checkWritePermissionForTool(NotebookEditTool, input,
 * toolPermissionContext)}（filesystem.ts:1205-1412；T06 探查 E-CALL-01/E-CALL-02）。
 *
 * <p><b>RED 验证策略</b>：接线前 NotebookEditTool 无 checkPermissions override（Tool 默认
 * Allow），本测试的 Ask/Deny 用例在旧实现下必然 FAIL；接线后 PASS。
 *
 * <p><b>CC 语义锚点</b>：checkWritePermissionForTool 默认（无规则、非 acceptEdits）→ ask
 * （写操作默认需确认）；allow 需 Edit 桶 allow 规则或 acceptEdits 模式（filesystem.ts:
 * 1360-1375/1377-1393）；deny 规则先于一切（:1219-1239）。
 *
 * <p>路径注意（对齐 WritePermissionCheckerTest 同款约束）：Windows {@code %TEMP%}
 * 含 8.3 短名（{@code ADMINI~1.DES}）会命中 suspicious-Windows-pattern，权限检查用例统一用
 * 项目 {@code target/} 下合成绝对路径；仅 execute 回归用例用 {@code @TempDir} 真实文件。
 */
@DisplayName("S06 · NotebookEditTool 写权限接线（CC NotebookEditTool.ts:125-132 checkWritePermissionForTool）")
class NotebookEditToolPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 工作目录（合成绝对路径；同一测试内必须复用同一实例，否则 input 与 ctx 目录不一致）。 */
    private static Path nbWorkspace() {
        return Paths.get("target", "s06-nb-" + rand()).toAbsolutePath();
    }

    /** 工作目录外路径所在目录（合成绝对路径，兄弟目录）。 */
    private static Path nbOutside() {
        return Paths.get("target", "s06-nb-out-" + rand()).toAbsolutePath();
    }

    private static NotebookEditTool wiredTool() {
        NotebookEditTool tool = new NotebookEditTool();
        tool.setPermissionChecker(new WritePermissionChecker());
        return tool;
    }

    private static JsonNode input(String notebookPath) {
        ObjectNode node = JSON.createObjectNode();
        node.put("notebook_path", notebookPath);
        node.put("edit_mode", "replace");
        return node;
    }

    private static JsonNode inputWithoutPath() {
        ObjectNode node = JSON.createObjectNode();
        node.put("edit_mode", "replace");
        return node;
    }

    private static ToolPermissionContext permCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static ToolPermissionContext permCtxWithDeny(Path dir) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(),
            Map.of(PermissionRuleSource.SESSION, Set.of(rule(PermissionBehavior.DENY, toGlob(dir)))),
            Map.of(), Map.of());
    }

    private static ToolPermissionContext permCtxWithAllow(Path dir) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION, Set.of(rule(PermissionBehavior.ALLOW, toGlob(dir)))),
            Map.of(), Map.of(), Map.of());
    }

    /** Edit 桶 content 规则（CC 写规则桶 = Edit；filesystem.ts matchingRuleForInput toolType='edit'）。 */
    private static PermissionRule rule(PermissionBehavior behavior, String content) {
        return new PermissionRule(
            PermissionRuleSource.SESSION, behavior,
            PermissionRuleValue.withContent("Edit", content));
    }

    /** glob 规则内容：绝对路径转 '/' + '/**'（Windows PathMatcher 下 '/' 即分隔符）。 */
    private static String toGlob(Path dir) {
        return dir.toAbsolutePath().toString().replace('\\', '/') + "/**";
    }

    /** 13 参工厂：显式 effectiveCwd（null 会被 ToolUseContext 归一为进程 CWD）。 */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    @AfterEach
    void clearToolCheckCache() {
        // 1c 层 ThreadLocal cache（CheckLayer1c:81 put）—— per-call 隔离，防跨测试污染
        ToolCheckCache.clear();
    }

    @Test
    @DisplayName("工作目录内 + Edit allow 规则 → Allow（checkWritePermissionForTool allow 路径）")
    void inWorkingDirWithAllowRule_allowed() {
        Path ws = nbWorkspace();
        JsonNode input = input(ws.resolve("notes.ipynb").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(permCtxWithAllow(ws), ws));

        assertThat(result)
            .as("Edit 桶 allow 规则命中 → allow（CC filesystem.ts:1377-1393）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("工作目录内 + Edit allow 规则 → 1c 层集成：PermissionPipeline 全链 Allow")
    void inWorkingDirWithAllowRule_pipelineAllows() {
        Path ws = nbWorkspace();
        JsonNode input = input(ws.resolve("notes.ipynb").toString());
        NotebookEditTool tool = wiredTool();
        ToolPermissionContext permCtx = permCtxWithAllow(ws);
        ToolUseContext tctx = ctx(permCtx, ws);

        PermissionResult result = new PermissionPipeline().check(
            tool, new ToolUseBlock("call-nb", "NotebookEdit", input), input, tctx, permCtx);

        assertThat(result)
            .as("1c 层必须转发工具 Allow（CC permissions.ts:1208-1223）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("工作目录内、无规则 → Ask（CC 语义：写操作默认需确认，不静默放行）")
    void inWorkingDirNoRules_ask() {
        Path ws = nbWorkspace();
        JsonNode input = input(ws.resolve("notes.ipynb").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(permCtx(), ws));

        assertThat(result)
            .as("checkWritePermissionForTool 默认（无规则、非 acceptEdits）→ ask（CC :1395-1411 兜底）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("工作目录外 notebook_path → Ask（兜底，不静默放行）")
    void outsideWorkingDir_ask() {
        Path ws = nbWorkspace();
        JsonNode input = input(nbOutside().resolve("notes.ipynb").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(permCtx(), ws));

        assertThat(result)
            .as("工作目录外写必须 Ask（CC filesystem.ts:1395-1411 兜底 ask），不得静默放行")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("工作目录外 notebook_path → 1c 层集成：pipeline 兜底 Ask（fail-closed）")
    void outsideWorkingDir_pipelineAsk() {
        Path ws = nbWorkspace();
        JsonNode input = input(nbOutside().resolve("notes.ipynb").toString());
        NotebookEditTool tool = wiredTool();
        ToolPermissionContext permCtx = permCtx();
        ToolUseContext tctx = ctx(permCtx, ws);

        PermissionResult result = new PermissionPipeline().check(
            tool, new ToolUseBlock("call-nb", "NotebookEdit", input), input, tctx, permCtx);

        assertThat(result)
            .as("拒绝路径 fail-closed：Ask 必须经 1d/1e/1f/3 层兜底到 Ask，不得被放行")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("edit deny 规则命中 notebook_path → Deny（fail-closed 规则路径，CC :1219-1239）")
    void editDenyRule_deny() {
        Path ws = nbWorkspace();
        // 规则 glob 匹配 ws/**（绝对路径）→ notebook_path 绝对路径在 ws 内 → edit deny 命中（先于安全检查）
        JsonNode input = input(ws.resolve("notes.ipynb").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(permCtxWithDeny(ws), ws));

        assertThat(result)
            .as("edit 桶 deny 规则必须拒绝（CC checkWritePermissionForTool 步骤 1）")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("缺 notebook_path → Ask（checker 提取不到路径 = 缺少 path）")
    void missingPath_ask() {
        Path ws = nbWorkspace();
        JsonNode input = inputWithoutPath();
        PermissionResult result = wiredTool().checkPermissions(input, ctx(permCtx(), ws));

        assertThat(result)
            .as("缺路径不得放行，必须 ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Allow.updatedInput 还原为原 input（path 适配副本不泄漏到下游）")
    void allow_updatedInputRestoredToOriginal() {
        Path ws = nbWorkspace();
        JsonNode input = input(ws.resolve("notes.ipynb").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(permCtxWithAllow(ws), ws));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.updatedInput())
            .as("updatedInput 必须为原 input（CC 决策不携带 updatedInput；S06 数据流纯净）")
            .isEqualTo(input);
        assertThat(allow.updatedInput().has("path"))
            .as("path 适配副本不得泄漏（否则弹窗 displayInput / hook 全替换会多出字段）")
            .isFalse();
    }

    @Test
    @DisplayName("permissionChecker 未注入 → fail-loud ISE（不再静默 Allow，Pattern #11）")
    void missingChecker_failsLoud() {
        NotebookEditTool tool = new NotebookEditTool();
        Path ws = nbWorkspace();
        JsonNode input = input(ws.resolve("notes.ipynb").toString());

        assertThatThrownBy(() -> tool.checkPermissions(input, ctx(permCtx(), ws)))
            .as("依赖缺失静默 Allow = 门禁绕过；对齐 ReadFileTool fail-loud ISE 模式")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("permissionChecker 未注入");
    }

    @Test
    @DisplayName("ctx null → IAE（checker fail-loud，CC checkWritePermissionForTool 无 null 守卫）")
    void nullCtx_failsLoud() {
        Path ws = nbWorkspace();
        JsonNode input = input(ws.resolve("notes.ipynb").toString());

        assertThatThrownBy(() -> wiredTool().checkPermissions(input, null))
            .as("ctx null = 调用方 bug → IAE（对齐 WritePermissionChecker fail-loud 模式）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ctx is null");
    }

    @Test
    @DisplayName("execute 行为零回归：notebook 编辑主流程不受 checkPermissions 影响")
    void execute_stillEdits(@TempDir Path workspace) throws Exception {
        Path nb = workspace.resolve("notes.ipynb");
        Files.writeString(nb, "{\"nbformat\":4,\"nbformat_minor\":5,\"cells\":[]}");
        ObjectNode input = JSON.createObjectNode();
        input.put("notebook_path", nb.toString());
        input.put("edit_mode", "insert");
        input.put("cell_type", "code");
        input.put("new_source", "print(1)");

        var result = new NotebookEditTool().execute(
            new ToolUseBlock("call-1", "NotebookEdit", input));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(String.valueOf(result.data())).contains("notebook updated");
        assertThat(Files.readString(nb)).contains("print(1)");
    }
}
