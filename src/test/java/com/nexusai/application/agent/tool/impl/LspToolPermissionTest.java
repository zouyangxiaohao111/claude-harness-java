package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S06 · LspTool 读权限检查接线（X11）· 对齐 CC {@code LSPTool.ts:210-217}
 * {@code checkPermissions → checkReadPermissionForTool(LSPTool, input, toolPermissionContext)}
 * （filesystem.ts:1030-1193；T06 探查 E-CALL-01/E-CALL-02）。
 *
 * <p><b>RED 验证策略</b>：接线前 LspTool 无 checkPermissions override（Tool 默认 Allow），
 * 本测试的 Ask/Deny 用例在旧实现下必然 FAIL；接线后 PASS。
 *
 * <p>路径注意（对齐 WritePermissionCheckerTest 同款约束）：Windows {@code %TEMP%}
 * 含 8.3 短名（{@code ADMINI~1.DES}）会命中 suspicious-Windows-pattern，测试统一用
 * 项目 {@code target/} 下合成绝对路径；权限检查器为纯字符串判定，不需要真实文件。
 */
@DisplayName("S06 · LspTool 读权限接线（CC LSPTool.ts:210-217 checkReadPermissionForTool）")
class LspToolPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 工作目录（合成绝对路径；同一测试内必须复用同一实例，否则 input 与 ctx 目录不一致）。 */
    private static Path lspWorkspace() {
        return Paths.get("target", "s06-lsp-" + rand()).toAbsolutePath();
    }

    /** 工作目录外路径所在目录（合成绝对路径，兄弟目录）。 */
    private static Path lspOutside() {
        return Paths.get("target", "s06-lsp-out-" + rand()).toAbsolutePath();
    }

    /** 生产装配等价：ReadPermissionChecker 双参构造（step5 edit-implies-read 依赖 WritePermissionChecker）。 */
    private static LspTool wiredTool() {
        LspTool tool = new LspTool(new LspManager());
        tool.setPermissionChecker(
            new ReadPermissionChecker(new WritePermissionChecker()));
        return tool;
    }

    private static JsonNode input(String filePath) {
        ObjectNode node = JSON.createObjectNode();
        node.put("operation", "hover");
        node.put("filePath", filePath);
        node.put("line", 1);
        node.put("character", 1);
        return node;
    }

    private static JsonNode inputWithoutPath() {
        ObjectNode node = JSON.createObjectNode();
        node.put("operation", "hover");
        node.put("line", 1);
        node.put("character", 1);
        return node;
    }

    private static ToolPermissionContext emptyPermCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
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
    @DisplayName("工作目录内 filePath → 读权限检查放行（直接委托 ReadPermissionChecker）")
    void inWorkingDir_allowed() {
        Path ws = lspWorkspace();
        JsonNode input = input(ws.resolve("Main.java").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(emptyPermCtx(), ws));

        assertThat(result)
            .as("工作目录内读取必须放行（CC filesystem.ts:1136-1151 路径在工作目录内 → allow）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("工作目录内 filePath → 1c 层集成：PermissionPipeline 全链 Allow（不再 1c 恒放行）")
    void inWorkingDir_pipelineAllows() {
        Path ws = lspWorkspace();
        JsonNode input = input(ws.resolve("Main.java").toString());
        LspTool tool = wiredTool();
        ToolPermissionContext permCtx = emptyPermCtx();
        ToolUseContext tctx = ctx(permCtx, ws);

        // 生产链：PermissionPipeline 10 层 → 1c（CheckLayer1c_ToolCheck）→ tool.checkPermissions
        PermissionResult result = new PermissionPipeline().check(
            tool, new ToolUseBlock("call-lsp", "lsp", input), input, tctx, permCtx);

        assertThat(result)
            .as("1c 层必须转发工具 Allow（CC permissions.ts:1208-1223）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("工作目录外 filePath → Ask（兜底，不静默放行）")
    void outsideWorkingDir_ask() {
        Path ws = lspWorkspace();
        JsonNode input = input(lspOutside().resolve("Main.java").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(emptyPermCtx(), ws));

        assertThat(result)
            .as("工作目录外读取必须 Ask（CC filesystem.ts:1178-1193 兜底 ask），不得静默放行")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("工作目录外 filePath → 1c 层集成：pipeline 兜底 Ask（fail-closed）")
    void outsideWorkingDir_pipelineAsk() {
        Path ws = lspWorkspace();
        JsonNode input = input(lspOutside().resolve("Main.java").toString());
        LspTool tool = wiredTool();
        ToolPermissionContext permCtx = emptyPermCtx();
        ToolUseContext tctx = ctx(permCtx, ws);

        PermissionResult result = new PermissionPipeline().check(
            tool, new ToolUseBlock("call-lsp", "lsp", input), input, tctx, permCtx);

        assertThat(result)
            .as("拒绝路径 fail-closed：Ask 必须经 1d/1e/1f/3 层兜底到 Ask，不得被放行")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("UNC 路径 → Ask（防 NTLM 凭据泄露，CC filesystem.ts:1050-1064）")
    void uncPath_ask() {
        Path ws = lspWorkspace();
        JsonNode input = input("\\\\server\\share\\a.java");
        PermissionResult result = wiredTool().checkPermissions(input, ctx(emptyPermCtx(), ws));

        assertThat(result)
            .as("UNC 路径必须 ask（CC 防御纵深第一道）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("缺 filePath → Ask（checker 提取不到路径 = 缺少 path）")
    void missingFilePath_ask() {
        Path ws = lspWorkspace();
        JsonNode input = inputWithoutPath();
        PermissionResult result = wiredTool().checkPermissions(input, ctx(emptyPermCtx(), ws));

        assertThat(result)
            .as("缺路径不得放行，必须 ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Allow.updatedInput 还原为原 input（path 适配副本不泄漏到下游）")
    void allow_updatedInputRestoredToOriginal() {
        Path ws = lspWorkspace();
        JsonNode input = input(ws.resolve("Main.java").toString());
        PermissionResult result = wiredTool().checkPermissions(input, ctx(emptyPermCtx(), ws));

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
        LspTool tool = new LspTool(new LspManager());
        Path ws = lspWorkspace();
        JsonNode input = input(ws.resolve("Main.java").toString());

        assertThatThrownBy(() -> tool.checkPermissions(input, ctx(emptyPermCtx(), ws)))
            .as("依赖缺失静默 Allow = 门禁绕过；对齐 ReadFileTool fail-loud ISE 模式")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("permissionChecker 未注入");
    }

    @Test
    @DisplayName("ctx null → IAE（checker fail-loud，CC checkReadPermissionForTool 无 null 守卫）")
    void nullCtx_failsLoud() {
        Path ws = lspWorkspace();
        JsonNode input = input(ws.resolve("Main.java").toString());

        assertThatThrownBy(() -> wiredTool().checkPermissions(input, null))
            .as("ctx null = 调用方 bug → IAE（对齐 ReadPermissionChecker fail-loud 模式）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ctx is null");
    }

    @Test
    @DisplayName("execute 行为零回归：LSP 未连接 → 显式错误（isEnabled 门控不变）")
    void execute_notConnected_errors() {
        // 新 LspManager 未 initialize → isLspConnected()=false → isEnabled()=false
        LspTool tool = new LspTool(new LspManager());
        var result = tool.execute(new ToolUseBlock("call-1", "lsp",
            input(lspWorkspace().resolve("Main.java").toString())));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("LSP 未连接必须显式报错，不假装执行（LspTool A3 状态机）")
            .isTrue();
        assertThat(String.valueOf(result.data())).contains("LSP not connected");
    }

    @Test
    @DisplayName("shouldDefer 恒 true（CC LSPTool.ts:136 shouldDefer: true；defer_loading 而非立即进 schema）")
    void shouldDefer_true() {
        LspTool tool = wiredTool();
        JsonNode input = input(lspWorkspace().resolve("Main.java").toString());

        assertThat(tool.shouldDefer(input))
            .as("LSP 工具必须延迟加载（CC LSPTool.ts:136 shouldDefer: true 静态字面量），"
                + "不占首轮 schema，经 ToolSearch 检索 searchHint 后加载，"
                + "减少首轮 schema 体积——若回退为 default false 会立即进 schema（✗-24 缺口）")
            .isTrue();
    }
}
