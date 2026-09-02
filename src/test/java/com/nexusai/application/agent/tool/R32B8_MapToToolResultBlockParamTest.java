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
 * R32-b8 #1 + G2 · {@link Tool#mapToToolResultBlockParam(AgentToolResult)} default 接口验证.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC {@code Tool.ts:557-560 mapToolResultToToolResultBlockParam}
 * 是 CC 端每个 tool 必填字段, 由 toolExecution.ts:1292 消费。
 *
 * <p><b>G2 接线（DEL-G2-01）</b>: production 路径 {@code LlmAgentLoop.toolResultMessage}
 * 已改为经 per-tool {@link Tool#mapToToolResultBlockParam(AgentToolResult)} 构造 tool_result
 * 块（AgentLoopContext 按 toolName 解析 Tool 实例）。default 实现不再是空占位 —— 返回
 * 合法 {@link ToolResultBlockParam}（tool_use_id/type/content/is_error），content 复用
 * {@link ToolResult#renderToolResultPayloadText} 渲染通用文本，保证未 override 工具也有合法块
 * （Java 兜底，CC 契约 per-tool 必填 —— G2.md §9 登记范围扩展决策）。
 *
 * <p><b>关键 invariant</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ul>
 *   <li>Default {@link Tool#mapToToolResultBlockParam(AgentToolResult)} 返回 {@link ToolResultBlockParam}
 *       （tool_use_id 透传 / type='tool_result' / content 非空文本 / is_error 透传）。</li>
 *   <li>未 override 的工具（ReadFileTool / GlobTool / GrepTool）走 default，同样产出合法块。</li>
 *   <li>isError 结果（ToolResult.error）→ is_error=true 透传（对齐 CC tool_result.is_error）。</li>
 * </ul>
 *
 * @see Tool#mapToToolResultBlockParam(AgentToolResult)
 * @see ToolResult
 */
class R32B8_MapToToolResultBlockParamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
        };
    }

    @Test
    @DisplayName("Default mapToToolResultBlockParam 返回合法 tool_result 块 (G2 接线)")
    void defaultProducesValidBlock() {
        // WHY: G2 DEL-G2-01 后 default 不再是空 Map 占位；toolResultMessage 消费该块，
        // 空 content 会破坏 tool_result 契约（G2.md §9）。测试验证 content=tool_use_id 数据透传。
        ToolResult result = ToolResult.success("toolu_test", "hello");
        ToolResultBlockParam block = stubTool("stub").mapToToolResultBlockParam(result, "toolu_test", false);
        assertThat(block)
            .as("Default mapToToolResultBlockParam 必须返回非空 tool_result 块")
            .isNotNull();
        assertThat(block.toolUseId())
            .as("tool_use_id 必须透传（CC Tool.ts:558 tool_use_id: toolUseID）")
            .isEqualTo("toolu_test");
        assertThat(block.type())
            .as("type 必须为 tool_result（CC Tool.ts:559）")
            .isEqualTo("tool_result");
        assertThat(block.content())
            .as("content 必须是 data 文本（CC Tool.ts:560 content）")
            .isEqualTo("hello");
        assertThat(block.isError())
            .as("成功结果 is_error=false（CC tool_result.is_error）")
            .isFalse();
    }

    @Test
    @DisplayName("Default mapToToolResultBlockParam 对 isError 结果透传 is_error=true")
    void defaultErrorResultPropagatesIsError() {
        // WHY: CC tool_result.is_error 语义（toolExecution.ts:1720-1724 错误路径直构块 is_error:true）；
        // Java default 透传 ToolResult.isError，避免错误结果被误判为成功（H13-GAP 对抗核验）。
        ToolResult result = ToolResult.error("toolu_err", "boom");
        ToolResultBlockParam block = stubTool("stub").mapToToolResultBlockParam(result, "toolu_err", true);
        assertThat(block)
            .as("Default 对 isError 结果也返回合法块（content=错误消息）")
            .isNotNull();
        assertThat(block.isError())
            .as("isError 必须透传为 true")
            .isTrue();
        assertThat(block.content())
            .as("content 为错误消息文本")
            .isEqualTo("boom");
    }

    @Test
    @DisplayName("Default mapToToolResultBlockParam 对携带 newMessages 的 ToolResult 同样产出合法块")
    void defaultWorksForExtendedResult() {
        // WHY: 参数类型 AgentToolResult (sealed interface); A1 退役 ExtendedToolResult 后,
        // sealed permits 只剩 ToolResult. Default 实现仍接受任意 ToolResult (含 newMessages 载荷), 不区分.
        ToolResult<?> extended = ToolResult.successWithNewMessages("toolu_x", "data", java.util.List.of());
        ToolResultBlockParam block = stubTool("stub2").mapToToolResultBlockParam(extended, "toolu_x", false);
        assertThat(block)
            .as("Default 对携带 newMessages 的 ToolResult 同样产出合法块 (sealed interface 子类型兼容)")
            .isNotNull();
        assertThat(block.toolUseId()).isEqualTo("toolu_x");
        assertThat(block.content()).isEqualTo("data");
    }

    @Test
    @DisplayName("ToolResult 内联 META_* 常量存在 (DEL-C2: 集中 mapper 已删, 常量内联到 ToolResult)")
    void inlineMetaConstantsPresent() {
        // WHY: DEL-C2 删除集中 mapper 后, image/document base64 契约常量
        // 内联至 ToolResult (对齐 CC Tool.ts:557-560 per-tool 必填方法语义, 无集中 mapper 类).
        assertThat(ToolResult.META_IMAGE_BASE64)
            .as("ToolResult.META_IMAGE_BASE64 内联常量 (值不变)")
            .isEqualTo("image_base64");
        assertThat(ToolResult.META_IMAGE_MEDIA_TYPE)
            .as("ToolResult.META_IMAGE_MEDIA_TYPE 内联常量 (值不变)")
            .isEqualTo("image_media_type");
        assertThat(ToolResult.META_DOCUMENT_BASE64)
            .as("ToolResult.META_DOCUMENT_BASE64 内联常量 (值不变)")
            .isEqualTo("document_base64");
        assertThat(ToolResult.META_DOCUMENT_MEDIA_TYPE)
            .as("ToolResult.META_DOCUMENT_MEDIA_TYPE 内联常量 (值不变)")
            .isEqualTo("document_media_type");
    }

    @Test
    @DisplayName("ReadFileTool 不 override mapToToolResultBlockParam (走 default 合法块)")
    void readFileToolUsesDefault() throws Exception {
        // WHY: ReadFileTool 无 per-tool mapper override（G2 接线后仍走 default）——
        // 测试验证 default 路径产出合法块而非空。
        PathGuard guard = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());
        ReadFileTool tool = new ReadFileTool(guard);
        ToolResult result = ToolResult.success("toolu_read", "file content");
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolu_read", false);
        assertThat(block)
            .as("ReadFileTool 走 default, 产出合法 tool_result 块")
            .isNotNull();
        assertThat(block.content()).isEqualTo("file content");
    }

    @Test
    @DisplayName("GlobTool 不 override mapToToolResultBlockParam (走 default 合法块)")
    void globToolUsesDefault() {
        PathGuard guard = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());
        GlobTool tool = new GlobTool(guard);
        ToolResult result = ToolResult.success("toolu_glob", "match1\nmatch2");
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolu_glob", false);
        assertThat(block)
            .as("GlobTool 走 default, 产出合法 tool_result 块")
            .isNotNull();
        assertThat(block.content()).isEqualTo("match1\nmatch2");
    }

    @Test
    @DisplayName("GrepTool 不 override mapToToolResultBlockParam (走 default 合法块)")
    void grepToolUsesDefault() {
        PathGuard guard = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());
        GrepTool tool = new GrepTool(guard);
        ToolResult result = ToolResult.success("toolu_grep", "matches data");
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolu_grep", false);
        assertThat(block)
            .as("GrepTool 走 default, 产出合法 tool_result 块")
            .isNotNull();
        assertThat(block.content()).isEqualTo("matches data");
    }
}
