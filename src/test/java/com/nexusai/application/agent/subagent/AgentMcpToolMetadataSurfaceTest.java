package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S05 B11/B12/B13/B14] AgentMcpTool per-tool 元数据表面 · 对齐 CC fetchToolsForClient
 * （client.ts:1779-1785 searchHint/alwaysLoad、:1801-1803 toAutoClassifierInput、
 * :1972-1976 userFacingName）+ MCPTool.ts:67-69 → terminal.ts:119-131 isResultTruncated。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 旧执行面未 override 这些表面
 * （默认 searchHint()=null / userFacingName()=name() / isResultTruncated()=false /
 * toAutoClassifierInput 缺省），Java 端应具备 CC 全部 per-tool 覆盖。本测试锁各表面
 * 语义（全部委托共享 {@link com.nexusai.application.agent.mcp.McpToolExecutionSupport}，
 * 单一包装面 Q-09-R2-2）。
 */
@DisplayName("[S05 B11-B14] AgentMcpTool 元数据表面（searchHint/autoClassifier/userFacingName/isResultTruncated）")
class AgentMcpToolMetadataSurfaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AgentMcpServers.McpToolChannel NOOP_CHANNEL = new AgentMcpServers.McpToolChannel() {
        @Override
        public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
            return CompletableFuture.completedFuture(MAPPER.createObjectNode());
        }
        @Override
        public void resetSession() {}
    };

    private static AgentMcpTool tool(JsonNode annotations, JsonNode meta) {
        return new AgentMcpTool("fs", "read", "mcp__fs__read",
            MAPPER.createObjectNode(), annotations, meta, "Read",
            NOOP_CHANNEL, 60_000, null);
    }

    // ── B11 searchHint（CC client.ts:1779-1783）──

    @Test
    @DisplayName("searchHint：_meta.anthropic/searchHint 空白折叠 + trim")
    void searchHint_collapsesWhitespaceAndTrims() {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("anthropic/searchHint", "  multi\nline\t hint  ");
        assertThat(tool(null, meta).searchHint()).isEqualTo("multi line hint");
    }

    @Test
    @DisplayName("searchHint：非 string / 空串 → null（CC undefined）")
    void searchHint_nonStringOrEmpty_isNull() {
        ObjectNode nonString = MAPPER.createObjectNode();
        nonString.putObject("anthropic/searchHint");
        assertThat(tool(null, nonString).searchHint()).isNull();

        ObjectNode empty = MAPPER.createObjectNode();
        empty.put("anthropic/searchHint", "   \n  ");
        assertThat(tool(null, empty).searchHint()).isNull();

        assertThat(tool(null, null).searchHint()).isNull();
    }

    @Test
    @DisplayName("alwaysLoad：_meta.anthropic/alwaysLoad === true 才 true（CC :1785）")
    void alwaysLoad_metaFlag() {
        ObjectNode yes = MAPPER.createObjectNode();
        yes.put("anthropic/alwaysLoad", true);
        assertThat(tool(null, yes).alwaysLoad()).isTrue();

        ObjectNode no = MAPPER.createObjectNode();
        no.put("anthropic/alwaysLoad", false);
        assertThat(tool(null, no).alwaysLoad()).isFalse();

        ObjectNode notBool = MAPPER.createObjectNode();
        notBool.put("anthropic/alwaysLoad", "yes");
        assertThat(tool(null, notBool).alwaysLoad()).isFalse();

        assertThat(tool(null, null).alwaysLoad()).isFalse();
    }

    // ── B13 userFacingName（CC client.ts:1972-1976）──

    @Test
    @DisplayName("userFacingName：annotations.title 优先，否则 tool 名，统一 `${server} - ${name} (MCP)`")
    void userFacingName_titlePriority() {
        ObjectNode withTitle = MAPPER.createObjectNode();
        withTitle.put("title", "Read File");
        assertThat(tool(withTitle, null).userFacingName()).isEqualTo("fs - Read File (MCP)");

        ObjectNode noTitle = MAPPER.createObjectNode();
        noTitle.put("readOnlyHint", true);
        assertThat(tool(noTitle, null).userFacingName()).isEqualTo("fs - read (MCP)");

        assertThat(tool(null, null).userFacingName()).isEqualTo("fs - read (MCP)");
    }

    // ── B14 isResultTruncated（MCPTool.ts:67-69 → terminal.ts:119-131）──

    @Test
    @DisplayName("isResultTruncated：>3 换行且第 4 个换行后仍有内容；尾随换行不算")
    void isResultTruncated_lineBoundaries() {
        AgentMcpTool t = tool(null, null);
        // 3 换行（4 行）→ 不截断
        assertThat(t.isResultTruncated("a\nb\nc\nd")).isFalse();
        // 4 换行且第 4 个换行后还有内容 → 截断
        assertThat(t.isResultTruncated("a\nb\nc\nd\ne")).isTrue();
        // 4 换行但尾随换行（终止符）→ 不截断
        assertThat(t.isResultTruncated("a\nb\nc\nd\n")).isFalse();
        assertThat(t.isResultTruncated(null)).isFalse();
        assertThat(t.isResultTruncated("single line")).isFalse();
    }

    // ── B12 toAutoClassifierInput（CC client.ts:1801-1803 + :1733-1741）──

    @Test
    @DisplayName("toAutoClassifierInput：k=v 空格拼接 + JS String() 语义")
    void toAutoClassifierInput_jsStringSemantics() {
        AgentMcpTool t = tool(null, null);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", "/tmp/a.txt");
        input.put("count", 3);
        assertThat(t.toAutoClassifierInput(input)).isEqualTo("file_path=/tmp/a.txt count=3");

        ObjectNode nested = MAPPER.createObjectNode();
        nested.putArray("ids").add(1).add(2);
        nested.putObject("obj");
        assertThat(t.toAutoClassifierInput(nested))
            .isEqualTo("ids=1,2 obj=[object Object]");

        ObjectNode nullVal = MAPPER.createObjectNode();
        nullVal.putNull("k");
        assertThat(t.toAutoClassifierInput(nullVal)).isEqualTo("k=null");
    }

    @Test
    @DisplayName("toAutoClassifierInput：空输入 → 回退 toolName（CC keys.length>0 分支）")
    void toAutoClassifierInput_emptyInput_fallsBackToToolName() {
        AgentMcpTool t = tool(null, null);
        assertThat(t.toAutoClassifierInput(MAPPER.createObjectNode())).isEqualTo("read");
        assertThat(t.toAutoClassifierInput(null)).isEqualTo("read");
    }

    // ── inputJSONSchema 直通 ──

    @Test
    @DisplayName("inputJSONSchema：inputSchema 原样透传（null → null）")
    void inputJSONSchema_passthrough() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        AgentMcpTool withSchema = new AgentMcpTool("fs", "read", "mcp__fs__read",
            schema, null, null, "Read", NOOP_CHANNEL, 60_000, null);
        assertThat(withSchema.inputJSONSchema()).isSameAs(schema);

        AgentMcpTool without = new AgentMcpTool("fs", "read", "mcp__fs__read",
            null, null, null, "Read", NOOP_CHANNEL, 60_000, null);
        assertThat(without.inputJSONSchema()).isNull();
    }
}
