package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.impl.GlobTool;
import com.nexusai.application.agent.tool.impl.GrepTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b8 #2 · {@link Tool.SearchReadKind} 4 态 enum + per-tool override 验证.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC {@code Tool.ts:429-433 isSearchOrReadCommand} 接口契约.
 * CC 端 3 个 builtin tool 实现:
 * <ul>
 *   <li>{@code FileReadTool.ts:382-384} → {@code {isSearch: false, isRead: true}} → {@link Tool.SearchReadKind#IS_READ}</li>
 *   <li>{@code GlobTool.ts:85-87}      → {@code {isSearch: true,  isRead: false}} → {@link Tool.SearchReadKind#IS_SEARCH}</li>
 *   <li>{@code GrepTool.ts:192-194}    → {@code {isSearch: true,  isRead: false}} → {@link Tool.SearchReadKind#IS_SEARCH}</li>
 * </ul>
 *
 * <p><b>D1 校正（按 CC 源）</b>: 任务 brief 曾描述 "Glob → IS_LIST" 是误述; CC
 * {@code GlobTool.ts:85-87} 实际为 {@code isSearch: true, isRead: false} —— Glob
 * 是文件名模式搜索（不是目录列表），应归类为 {@link Tool.SearchReadKind#IS_SEARCH}.
 *
 * <p><b>C2 校正（可选字段不实现）</b>: CC {@code isList?: boolean} 是可选字段,
 * 所有 builtin tool 都不使用. Java 端 4 态 enum 不实现 isList 状态映射字段,
 * {@link Tool.SearchReadKind#IS_LIST} 保留供将来扩展.
 *
 * <p><b>关键 invariant</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ul>
 *   <li>{@link GlobTool#searchReadKind(JsonNode)} = {@link Tool.SearchReadKind#IS_SEARCH}（D1）</li>
 *   <li>{@link ReadFileTool#searchReadKind(JsonNode)} = {@link Tool.SearchReadKind#IS_READ}</li>
 *   <li>{@link GrepTool#searchReadKind(JsonNode)} = {@link Tool.SearchReadKind#IS_SEARCH}</li>
 *   <li>未 override 的工具默认 {@link Tool.SearchReadKind#NONE}（与 CC isSearch=false, isRead=false 一致）</li>
 *   <li>{@link Tool.SearchReadKind} enum 4 个值: NONE / IS_READ / IS_SEARCH / IS_LIST.</li>
 * </ul>
 *
 * @see Tool.SearchReadKind
 * @see Tool#searchReadKind(JsonNode)
 */
class R32B8_IsSearchOrReadEnumTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Tool.SearchReadKind enum 有 4 个常量: NONE / IS_READ / IS_SEARCH / IS_LIST")
    void enumHasFourConstants() {
        // WHY: enum 是 4 态 (NONE/IS_READ/IS_SEARCH/IS_LIST). 任务 brief 误述为 3 态
        // (排除 IS_LIST). C2 校正明确: IS_LIST 是可选字段当前无 builtin tool 使用,
        // 但保留常量供将来扩展 (目录列表类 tool).
        assertThat(Tool.SearchReadKind.values())
            .as("SearchReadKind enum 应有 4 个常量 (C2: 保留 IS_LIST 供将来扩展)")
            .hasSize(4)
            .containsExactly(
                Tool.SearchReadKind.NONE,
                Tool.SearchReadKind.IS_READ,
                Tool.SearchReadKind.IS_SEARCH,
                Tool.SearchReadKind.IS_LIST
            );
    }

    @Test
    @DisplayName("GlobTool.searchReadKind() = IS_SEARCH (D1 校正: 按 CC 源, 不是 IS_LIST)")
    void globToolReturnsIsSearch() {
        // WHY: D1 校正. 任务 brief 描述 "Glob → IS_LIST" 是误述; CC GlobTool.ts:85-87
        // 实际返 {isSearch: true, isRead: false} —— Glob 是文件名模式搜索, 归 IS_SEARCH.
        PathGuard guard = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());
        GlobTool tool = new GlobTool(guard);
        JsonNode input = JSON.createObjectNode().put("pattern", "*.java");
        assertThat(tool.searchReadKind(input))
            .as("GlobTool.searchReadKind 必须 = IS_SEARCH (对齐 CC GlobTool.ts:85-87)")
            .isEqualTo(Tool.SearchReadKind.IS_SEARCH);
    }

    @Test
    @DisplayName("ReadFileTool.searchReadKind() = IS_READ (对齐 CC FileReadTool.ts:382-384)")
    void readFileToolReturnsIsRead() {
        // WHY: 对齐 CC FileReadTool.ts:382-384: {isSearch: false, isRead: true} → IS_READ.
        PathGuard guard = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());
        ReadFileTool tool = new ReadFileTool(guard);
        JsonNode input = JSON.createObjectNode().put("path", "README.md");
        assertThat(tool.searchReadKind(input))
            .as("ReadFileTool.searchReadKind 必须 = IS_READ (对齐 CC FileReadTool.ts:382-384)")
            .isEqualTo(Tool.SearchReadKind.IS_READ);
    }

    @Test
    @DisplayName("GrepTool.searchReadKind() = IS_SEARCH (对齐 CC GrepTool.ts:192-194)")
    void grepToolReturnsIsSearch() {
        // WHY: 对齐 CC GrepTool.ts:192-194: {isSearch: true, isRead: false} → IS_SEARCH.
        PathGuard guard = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());
        GrepTool tool = new GrepTool(guard);
        JsonNode input = JSON.createObjectNode().put("pattern", "TODO");
        assertThat(tool.searchReadKind(input))
            .as("GrepTool.searchReadKind 必须 = IS_SEARCH (对齐 CC GrepTool.ts:192-194)")
            .isEqualTo(Tool.SearchReadKind.IS_SEARCH);
    }

    @Test
    @DisplayName("Default searchReadKind() = NONE (未 override 工具的回退行为)")
    void defaultReturnsNone() {
        // WHY: 对齐 CC Tool.ts:429-433 默认行为: isSearch=false, isRead=false → NONE.
        // 绝大多数 30+ tool 不 override, 应走 default 返回 NONE.
        Tool stub = new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
        };
        JsonNode input = JSON.createObjectNode();
        assertThat(stub.searchReadKind(input))
            .as("Default searchReadKind 必须 = NONE (与 CC isSearch=false, isRead=false 一致)")
            .isEqualTo(Tool.SearchReadKind.NONE);
    }

}