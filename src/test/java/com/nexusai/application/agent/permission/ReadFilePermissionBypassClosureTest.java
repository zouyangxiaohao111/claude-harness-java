package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session M.4.4 收尾 · 读权限链路依赖缺失 fail-loud（Pattern #11 关闭，不再静默 Allow）。
 *
 * <p><b>对齐锚点</b>:
 * <ol>
 *   <li>CC {@code utils/permissions/filesystem.ts:1030-1193} {@code checkReadPermissionForTool} —
 *       {@code toolPermissionContext} 为必填类型参数，函数体直接解构使用，无 null 守卫
 *       （null 解构即 TypeError = fail，而非放行）——Java 旧实现的
 *       {@code ctx/permCtx == null → defaultAllow} 属 Java 自创 bypass；</li>
 *   <li>Java 既有 fail-loud 范例 {@code PermissionContextBuilder.java:177}：
 *       {@code if (state == null) throw new IllegalArgumentException("state is null")}；</li>
 *   <li>Java 既有依赖缺失范例 {@code ReadPermissionChecker}（读权限检查器注入）：
 *       {@code permissionChecker == null → throw new IllegalStateException(...)}。</li>
 * </ol>
 *
 * <p><b>RED 验证策略</b>（Pattern #14）: 旧实现（defaultAllow 回退）下本测试必须 FAIL；
 * 改造后 PASS。
 */
@DisplayName("Session M.4.4 收尾 · 读权限依赖缺失 fail-loud（不再静默 Allow）")
class ReadFilePermissionBypassClosureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input() {
        return JSON.createObjectNode().put("path", "a.txt");
    }

    @Test
    @DisplayName("ReadPermissionChecker: ctx==null 抛 IllegalArgumentException, 不再 defaultAllow")
    void readPermissionChecker_nullCtx_failsLoud(@TempDir Path workspace) {
        ReadPermissionChecker checker = new ReadPermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(workspace));

        assertThatThrownBy(() -> checker.check(tool, input(), null))
            .as("CC checkReadPermissionForTool 无 null 守卫; ctx==null = 调用方 bug → IAE（对齐 PermissionContextBuilder:177）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ctx is null");
    }

    @Test
    @DisplayName("ReadPermissionChecker: permissionContext==null 抛 IllegalArgumentException, 不再 defaultAllow")
    void readPermissionChecker_nullPermCtx_failsLoud(@TempDir Path workspace) {
        ReadPermissionChecker checker = new ReadPermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        // ToolUseContext.of(UUID, UUID, PermissionMode) 的 11 参构造第 9 位传 null →
        // permissionContext()==null（compact ctor 不兜底该字段）
        ToolUseContext ctx =
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT);

        assertThatThrownBy(() -> checker.check(tool, input(), ctx))
            .as("permCtx 缺失 = 权限管线未装配 → fail-loud IAE（对齐 PermissionContextBuilder:177）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("permissionContext is null");
    }

    @Test
    @DisplayName("ReadFileTool: permissionChecker 未注入抛 IllegalStateException, 不再 defaultAllow")
    void readFileTool_nullPermissionChecker_failsLoud(@TempDir Path workspace) {
        // 1 参便捷构造器（测试用）: permissionChecker 未注入 → 调用期 fail-loud
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));
        ToolUseContext ctx =
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT);

        assertThatThrownBy(() -> tool.checkPermissions(input(), ctx))
            .as("依赖缺失静默 Allow = Pattern #11 bypass; 对齐 ReadFileTool fail-loud ISE 模式")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("permissionChecker 未注入");
    }
}
