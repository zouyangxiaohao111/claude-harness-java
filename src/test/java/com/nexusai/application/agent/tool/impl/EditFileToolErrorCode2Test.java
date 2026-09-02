package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EditFileTool validateInput errorCode 2 + meta.actualOldString 透传 · 对齐 CC FileEditTool.ts。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>errorCode 2 deny 门禁</b>（CC FileEditTool.ts:158-174）：编辑落在权限 deny 规则覆盖的
 *       目录时，validateInput 必须在 errorCode 1（old==new）之后、读文件之前拒绝，返回逐字文案
 *       "File is in a directory that is denied by your permission settings."。此门禁若被删除，
 *       被 deny 的文件会绕过 validateInput 层直接进入执行，把安全裁决推迟到更晚的管线。</li>
 *   <li><b>meta.actualOldString 透传</b>（CC FileEditTool.ts:361）：成功路径须携带
 *       findActualString 算得的真实匹配子串（弯引号归一化后），供下游形状对齐。透传缺失时
 *       meta 断言应红。</li>
 *   <li><b>null-safe</b>：permCtx==null（ToolUseContext.of 不注入 permissionContext 的 POJO 测试
 *       路径）必须跳过 deny 检查不 NPE。</li>
 * </ol>
 */
@DisplayName("EditFileTool validateInput errorCode 2 deny + meta.actualOldString 透传")
class EditFileToolErrorCode2Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode editInput(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return input;
    }

    private static ToolUseBlock readCallWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    private static ToolUseContext ctxFor(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("e2-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("e2-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    private static PermissionRule denyEditRule(String ruleContent) {
        return new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Edit", ruleContent));
    }

    /**
     * 文件系统根锚定的 deny 规则内容（CC patternWithRoot filesystem.ts:860-892）。
     *
     * <p>WHY（OPD-WF5-FS-052 root-relative 重构后）: Edit 路径规则走
     * {@code matchesEditPathRuleRootRelative} —— 裸绝对路径（无 {@code //} 前缀）按 CC
     * patternWithRoot 无前缀分支被当作 cwd 相对 → 在测试进程 cwd（模块根）下永不命中
     * {@code @TempDir} 下的文件。必须用 {@code //} 前缀锚定文件系统根（Windows 盘符形
     * {@code //c/...}，CC :867-887）才能命中绝对路径。
     *
     * @param dir 工作区目录（{@code @TempDir}）
     * @return {@code //<root>/<posixPath>/**}（覆盖目录内全部内容）
     */
    private static String toFsRootGlob(Path dir) {
        String abs = dir.toAbsolutePath().toString().replace('\\', '/');
        if (abs.matches("^[a-zA-Z]:.*")) {
            // C:/Users/... → //c/Users/.../**（CC filesystem.ts:867-887 Windows 盘符根）
            return "//" + abs.substring(0, 1).toLowerCase() + abs.substring(2) + "/**";
        }
        return "//" + abs + "/**";
    }

    private static ToolUseContext ctxWithDeny(Path workspace, String ruleContent) {
        Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
        deny.put(PermissionRuleSource.SESSION, Set.of(denyEditRule(ruleContent)));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());
        return ctxFor(workspace).withPermissionContext(permCtx, PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("deny 命中 → validateInput 返回 errorCode=2 + 逐字文案（CC FileEditTool.ts:158-174）")
    void denyRuleHit_returnsErrorCode2_withVerbatimMessage(@TempDir Path workspace) throws Exception {
        Path f = workspace.resolve("a.txt");
        Files.writeString(f, "hello\n");
        // root-relative 匹配（OPD-WF5-FS-052）下裸绝对路径不命中 → 用 // 根锚定 glob 覆盖 workspace
        String denyContent = toFsRootGlob(workspace);

        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxWithDeny(workspace, denyContent);

        Tool.ValidationResult vr = editTool.validateInput(
            editInput("a.txt", "hello", "CHANGED"), ctx);

        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).as("deny 命中必须返回 errorCode=2").isEqualTo("2");
        assertThat(vr.message())
            .as("文案必须逐字对齐 CC FileEditTool.ts:171")
            .isEqualTo("File is in a directory that is denied by your permission settings.");
    }

    @Test
    @DisplayName("permCtx==null → deny 检查 null-safe 跳过，读后通过（不 NPE）")
    void nullPermissionContext_skipsDenyCheck_andPasses(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);   // ToolUseContext.of 未注入 permissionContext → permCtx == null

        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

        Tool.ValidationResult vr = editTool.validateInput(
            editInput("a.txt", "hello", "CHANGED"), ctx);

        assertThat(vr.ok()).as("permCtx==null 必须跳过 deny 检查不 NPE").isTrue();
    }

    @Test
    @DisplayName("deny 规则覆盖其它路径 → 本路径不命中，读后通过（证明 deny 匹配是路径特定的）")
    void denyRuleForOtherPath_doesNotBlockThisPath(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        // deny 规则覆盖的是另一个文件路径 → a.txt 不应被 deny 命中
        ToolUseContext ctx = ctxWithDeny(workspace, workspace.resolve("other.txt").toAbsolutePath().normalize().toString());

        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

        Tool.ValidationResult vr = editTool.validateInput(
            editInput("a.txt", "hello", "CHANGED"), ctx);

        assertThat(vr.ok()).as("deny 规则只拦其覆盖路径，本路径应通过").isTrue();
    }

    @Test
    @DisplayName("成功收尾 pass() 3 字段契约（IMP-C4 DC-A1-02 删 meta，对齐 CC Tool.ts:95-101）")
    void successPath_returnsThreeFieldValidationResult(@TempDir Path workspace) throws Exception {
        // WHY (规则九): IMP-C4 DC-A1-02 删除 ValidationResult.meta + passWithMeta（EV-A1-007）——
        //   CC Tool.ts:95-101 类型只声明 {result:true} | {result:false,message,errorCode}，
        //   meta 属 TS 结构化多余字段死权重（toolExecution.ts:683-733 只消费 result/message/errorCode）。
        //   变异点: passWithMeta 复活 / meta 分量回归 → 反射组件数或成功态断言红.
        Files.writeString(workspace.resolve("a.txt"), "hello world\n");
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);

        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

        Tool.ValidationResult vr = editTool.validateInput(
            editInput("a.txt", "hello", "CHANGED"), ctx);

        assertThat(vr.ok())
            .as("成功路径必须通过（CC FileEditTool.ts:361 return { result: true }）")
            .isTrue();
        // ValidationResult record 仅 3 分量 (ok/errorCode/message)，meta 分量已删（DC-A1-02）
        assertThat(Arrays.stream(Tool.ValidationResult.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .as("ValidationResult 3 字段契约（CC Tool.ts:95-101）")
            .containsExactlyInAnyOrder("ok", "errorCode", "message");
    }
}
