package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-3 · WebBrowserTool 真实现行为验证（G11+G32① 重写后新契约）。
 *
 * <p><b>WHY (意图验证)</b>: WebBrowserTool 已按 CC {@code WebBrowserTool.ts} 从 fail-loud 注册桩
 * 重写为真实现，测试须锁定新契约，防止回退到旧 stub 语义:
 * <ul>
 *   <li><b>name() = PascalCase</b> {@code "WebBrowser"}（CC WebBrowserTool.ts:6
 *       {@code WEB_BROWSER_TOOL_NAME='WebBrowser'}；旧 snake_case {@code "web_browser"} 已废弃）。</li>
 *   <li><b>description() 真实现描述</b>（不再含 "stub"/"未实现" 占位词）。</li>
 *   <li><b>inputSchema additionalProperties=false</b>（CC {@code z.strictObject}，拒绝任意键）。</li>
 *   <li><b>execute 返回真结果</b>: 缺 url → error「missing required input: url」；未知 action →
 *       成功结果（content 含 "Unknown action"，不抛异常）。</li>
 * </ul>
 *
 * <p>本测试类不验证 @ConditionalOnProperty 行为 — 由 R32B7a3_StubToolsConditionalMatrixTest
 * 用 {@link org.springframework.boot.test.context.runner.ApplicationContextRunner} 验证.
 *
 * @see WebBrowserTool
 */
class R32B7a3_WebBrowserToolTest {

    private final WebBrowserTool tool = new WebBrowserTool();

    @Test
    @DisplayName("name() 返回 'WebBrowser' (对齐 CC WebBrowserTool.ts:6 WEB_BROWSER_TOOL_NAME)")
    void nameAlignsWithCc() {
        // WHY: G11 改名后 name() 必须 = CC 真名 'WebBrowser'（PascalCase），
        // 旧 snake_case 'web_browser' 已废弃。ToolRegistry 按 name 查表分发，命名不一致 = 调不到.
        assertThat(tool.name())
            .as("WebBrowserTool name 必须 = ToolNameConstants.WEB_BROWSER_TOOL_NAME")
            .isEqualTo(ToolNameConstants.WEB_BROWSER_TOOL_NAME)
            .isEqualTo("WebBrowser");
    }

    @Test
    @DisplayName("description() 真实现描述（非 stub，无未实现占位词）")
    void descriptionIsReal() {
        // WHY: G32① 重写后 description 是真实能力描述，不再提示 "stub/未实现".
        // LLM 工具列表据此判断工具能力，残留占位词会误导 LLM 误用.
        String desc = tool.description();
        assertThat(desc).isNotBlank();
        assertThat(desc).doesNotContain("stub").doesNotContain("未实现");
        assertThat(desc).isEqualTo("Fetch and read web page content via HTTP");
    }

    @Test
    @DisplayName("inputSchema() additionalProperties=false (CC z.strictObject)")
    void inputSchemaIsStrict() {
        // WHY: CC WebBrowserTool.ts:8-18 z.strictObject({url, action?}) 拒绝任意键.
        // additionalProperties=false 防 LLM 传任意字段被静默忽略.
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        // url 必填（CC :8-18 url 无 optional）
        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.toString()).contains("url");
    }

    @Test
    @DisplayName("isEnabled() 默认 true (Tool 基类 default true, CC descriptor 无 override)")
    void isEnabledDefaultsTrue() {
        // WHY: CC WebBrowserTool 无 isEnabled override → 默认启用（feature 门控 bean 已创建即 LLM 可见）.
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("execute 缺 url → error 'missing required input: url'（不抛异常）")
    void executeMissingUrlReturnsError() {
        // WHY: CC WebBrowserTool call 缺 url 时返回错误（不抛）—— 对齐 fail-loud 契约.
        ToolUseBlock call = new ToolUseBlock("call-wb-1", "WebBrowser",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult<?> result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(String.valueOf(tr.data()))
            .as("缺 url 时必须提示 missing required input")
            .contains("missing required input: url");
    }

    @Test
    @DisplayName("execute 未知 action → 成功结果含 'Unknown action'（不抛异常、不发网络请求）")
    void executeUnknownActionReturnsSuccess() {
        // WHY: CC WebBrowserTool.ts:91-97 未知 action 返回成功结果（content 含 Unknown action），
        // 不发 HTTP 请求、不抛异常. 验证该分支无需网络即可命中.
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("url", "https://example.com");
        input.put("action", "bogus-action");
        ToolUseBlock call = new ToolUseBlock("call-wb-2", "WebBrowser", input);
        AgentToolResult<?> result = tool.execute(call, null);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("未知 action 是成功结果而非 error")
            .isFalse();
        assertThat(String.valueOf(tr.data())).contains("Unknown action");
    }

    @Test
    @DisplayName("NAME 常量 = 'WebBrowser' public static final")
    void nameConstantExposed() {
        // WHY: 组件可能引用 WebBrowserTool.NAME 做覆盖匹配; NAME 必须 public static final 防误改.
        assertThat(WebBrowserTool.NAME)
            .as("WebBrowserTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("WebBrowser");
    }
}
