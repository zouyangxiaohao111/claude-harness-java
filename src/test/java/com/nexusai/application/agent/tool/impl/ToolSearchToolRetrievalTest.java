package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolReferenceBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-H H1 · ToolSearchTool 检索算法全套对齐 CC 聚焦测试.
 *
 * <p><b>WHY (意图验证)</b>: 旧实现是朴素子串搜索 + {query,count,results} 输出 + [1,50] 钳制
 * （T1/T2）。本测试锁定 CC ToolSearchTool.ts 完整检索语义——select: 多选、精确名 fast-path、
 * mcp__ 前缀、required/optional 分区打分、词边界、排序、memoize 失效、tool_reference 输出——
 * 任何一项回退到旧朴素行为都会使对应用例 RED。
 */
class ToolSearchToolRetrievalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolSearchTool tool = new ToolSearchTool();

    // ───────────────────────── select: 多选 ─────────────────────────

    @Test
    @DisplayName("select: 逗号多选命中 deferred 工具，未命中项收集为 missing（CC ToolSearchTool.ts:358-406）")
    void select_commaMultiSelect_partialMissing() {
        List<Tool> tools = List.of(
                deferredTool("Read", "read a file"),
                deferredTool("Edit", "edit a file"),
                deferredTool("Grep", "grep lines"));
        ToolSearchTool.ToolSearchOutput out = execute("select:Read,Edit,NoSuch", tools);

        assertThat(out.matches())
                .as("命中项按请求顺序返回（去重）")
                .containsExactly("Read", "Edit");
        assertThat(out.query()).isEqualTo("select:Read,Edit,NoSuch");
        assertThat(out.totalDeferredTools()).isEqualTo(3);
        assertThat(out.pendingMcpServers()).isNull();
    }

    @Test
    @DisplayName("select: 已加载（非 deferred）工具被 select 时仍返回——无害 no-op（CC ToolSearchTool.ts:359-362）")
    void select_toolInFullSetButLoaded_stillReturned() {
        List<Tool> tools = List.of(
                deferredTool("Read", "read a file"),
                nonDeferredTool("Bash", "run commands"));
        ToolSearchTool.ToolSearchOutput out = execute("select:Bash", tools);

        assertThat(out.matches()).as("已加载工具选择是无害 no-op，直接返回其名")
                .containsExactly("Bash");
    }

    @Test
    @DisplayName("select: 全部未命中 → 空 matches + 无 pending（CC ToolSearchTool.ts:383-395）")
    void select_allMissing_returnsEmpty() {
        ToolSearchTool.ToolSearchOutput out = execute("select:Zzz1,Zzz2",
                List.of(deferredTool("Read", "read a file")));

        assertThat(out.matches()).isEmpty();
        assertThat(out.totalDeferredTools()).isEqualTo(1);
    }

    // ───────────────────────── 精确名 fast-path ─────────────────────────

    @Test
    @DisplayName("精确名小写命中 deferred 工具 → 直接返回，不经过打分（CC ToolSearchTool.ts:194-204）")
    void keyword_exactNameFastPath() {
        List<Tool> tools = List.of(
                deferredTool("ReadFile", "read a file"),
                deferredTool("NotebookEdit", "edit notebooks"));

        ToolSearchTool.ToolSearchOutput out = execute("readfile", tools);

        assertThat(out.matches()).as("精确名（大小写不敏感）优先返回")
                .containsExactly("ReadFile");
    }

    @Test
    @DisplayName("精确名命中全集中非 deferred 工具 → 回退全集（CC ToolSearchTool.ts:199-201）")
    void keyword_exactNameFallbackToFullSet() {
        List<Tool> tools = List.of(nonDeferredTool("Bash", "run commands"));

        ToolSearchTool.ToolSearchOutput out = execute("bash", tools);

        assertThat(out.matches()).containsExactly("Bash");
    }

    // ───────────────────────── mcp__ 前缀 ─────────────────────────

    @Test
    @DisplayName("mcp__server 前缀 → 返回该 server 的工具，受 max_results 限制（CC ToolSearchTool.ts:208-216）")
    void keyword_mcpPrefix_filtersByServer() {
        List<Tool> tools = List.of(
                deferredMcpTool("mcp__slack__send_message", "send slack message"),
                deferredMcpTool("mcp__slack__read_channel", "read slack channel"),
                deferredMcpTool("mcp__github__create_issue", "create github issue"));

        ToolSearchTool.ToolSearchOutput out = executeWithMax("mcp__slack", 1, tools);

        assertThat(out.matches())
                .as("只返回 slack server 工具，且 slice(0, max_results)")
                .containsExactly("mcp__slack__send_message");
    }

    // ───────────────────────── 分区打分 / searchHint ─────────────────────────

    @Test
    @DisplayName("+required 必须命中 name/描述，optional 参与打分（CC ToolSearchTool.ts:220-257）")
    void keyword_requiredPlus_requiredMustMatch() {
        List<Tool> tools = List.of(
                deferredMcpTool("mcp__slack__send_message", "send a slack message"),
                deferredMcpTool("mcp__github__create_issue", "create an issue"));

        ToolSearchTool.ToolSearchOutput out = execute("+slack send", tools);

        assertThat(out.matches())
                .as("required 'slack' 必须命中，github 工具被过滤")
                .containsExactly("mcp__slack__send_message");
    }

    @Test
    @DisplayName("searchHint 命中加分（+4），高于描述（+2）（CC ToolSearchTool.ts:282-285）")
    void keyword_searchHint_outranksDescriptionOnly() {
        List<Tool> tools = List.of(
                deferredTool("ReadFile", "read a file", "jupyter notebook"),
                deferredTool("NotebookEdit", "edit notebooks", null));

        // 搜 notebook：ReadFile 靠 searchHint 命中 +4；NotebookEdit 靠名字 part 命中 +10（更高）
        // → 只验证 hint 参与打分：搜仅 hint 可命中的词
        ToolSearchTool.ToolSearchOutput out = execute("jupyter", tools);

        assertThat(out.matches())
                .as("仅 searchHint 含 jupyter 的 ReadFile 命中（name/desc 均不含）")
                .containsExactly("ReadFile");
    }

    // ───────────────────────── 词边界 ─────────────────────────

    @Test
    @DisplayName("描述词边界：term 命中其他单词子串时不误报（CC ToolSearchTool.ts:287-290）")
    void keyword_wordBoundary_noSubstringFalsePositive() {
        List<Tool> tools = List.of(
                deferredTool("ProcessFile", "handle thread safety", null));

        ToolSearchTool.ToolSearchOutput out = execute("read", tools);

        assertThat(out.matches())
                .as("'thread' 含 'read' 子串但非词边界，朴素子串搜索会误命中；词边界 regex 拒绝")
                .isEmpty();
    }

    // ───────────────────────── 排序 ─────────────────────────

    @Test
    @DisplayName("名字 part 命中（10 分）排在描述命中（2 分）之前（CC ToolSearchTool.ts:266-295）")
    void keyword_sorting_namePartBeforeDescription() {
        List<Tool> tools = List.of(
                deferredTool("GrepTool", "read all lines of a file", null),
                deferredTool("ReadFile", "read a file", null));

        ToolSearchTool.ToolSearchOutput out = execute("read", tools);

        assertThat(out.matches())
                .as("ReadFile 名字 part 'read' 精确命中 +10，GrepTool 仅描述 +2 → ReadFile 在前")
                .containsExactly("ReadFile", "GrepTool");
    }

    @Test
    @DisplayName("max_results 截断排序结果（CC ToolSearchTool.ts:298-301 slice(0, maxResults)）")
    void keyword_maxResults_limitsResults() {
        List<Tool> tools = List.of(
                deferredTool("ReadOne", "read one file", null),
                deferredTool("ReadTwo", "read two files", null),
                deferredTool("ReadThree", "read three files", null));

        ToolSearchTool.ToolSearchOutput out = executeWithMax("read", 2, tools);

        assertThat(out.matches()).hasSize(2);
    }

    // ───────────────────────── memoize + 失效 ─────────────────────────

    @Test
    @DisplayName("描述 memoize：deferred 集不变时复用缓存；集变化时失效（CC ToolSearchTool.ts:91-100）")
    void memoize_invalidatesWhenDeferredSetChanges() {
        AtomicReference<String> alphaPrompt = new AtomicReference<>("alpha one");
        Tool alpha = deferredTool("Alpha", alphaPrompt::get, null);
        List<Tool> set1 = List.of(alpha);

        // 首次检索：缓存描述 "alpha one"
        assertThat(execute("alpha one", set1).matches()).containsExactly("Alpha");

        // 描述变化但 deferred 集不变 → 缓存未失效 → 旧描述仍生效（memoize 生效）
        alphaPrompt.set("alpha changed");
        assertThat(execute("changed", set1).matches())
                .as("deferred 集未变，描述缓存未失效，新词不可见")
                .isEmpty();

        // 加入新 deferred 工具（集变化）→ 缓存失效 → 新描述可见
        Tool beta = deferredTool("Beta", "beta doc", null);
        List<Tool> set2 = List.of(alpha, beta);
        assertThat(execute("changed", set2).matches())
                .as("deferred 集变化触发缓存失效，Alpha 新描述可被检索到")
                .containsExactly("Alpha");
    }

    // ───────────────────────── 输出结构 / tool_reference / 空结果 ─────────────────────────

    @Test
    @DisplayName("outputSchema 含 matches/query/total_deferred_tools/pending_mcp_servers（CC ToolSearchTool.ts:37-44）")
    void outputSchema_hasCcShape() {
        JsonNode schema = tool.outputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").has("matches")).isTrue();
        assertThat(schema.get("properties").has("query")).isTrue();
        assertThat(schema.get("properties").has("total_deferred_tools")).isTrue();
        assertThat(schema.get("properties").has("pending_mcp_servers"))
                .as("pending_mcp_servers 可选字段（不 in required）").isTrue();
        assertThat(schema.get("required")).hasSize(3);
        assertThat(schema.get("required").get(0).asText()).isEqualTo("matches");
    }

    @Test
    @DisplayName("Anthropic（Claude，支持 tool_reference）命中 → 纯 tool_reference blocks（CC ToolSearchTool.ts:462-469，格式不变）")
    void mapToBlock_matches_produceToolReferenceBlocks() {
        List<Tool> tools = List.of(deferredTool("Read", "read a file"));
        // [openai-lazy] 带 claude model → Anthropic 分流：命中纯 tool_reference（用户拍板「Anthropic 格式不要变」）
        AgentToolResult<?> result = executeResultWithModel("select:Read", tools, "claude-sonnet-4-5");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolsearch-1", false);
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.isError()).isFalse();
        assertThat(block.content()).isInstanceOf(List.class);

        List<?> content = (List<?>) block.content();
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).isInstanceOf(ContentBlockParam.class);
        ContentBlockParam b = (ContentBlockParam) content.get(0);
        assertThat(b.type()).isEqualTo("tool_reference");
        assertThat(b).isInstanceOf(ToolReferenceBlockParam.class);
        assertThat(((ToolReferenceBlockParam) b).toolName()).isEqualTo("Read");

        // 序列化 shape：{type:"tool_reference", tool_name:"Read"}
        assertThat(MAPPER.valueToTree(b).toString())
                .isEqualTo("{\"type\":\"tool_reference\",\"tool_name\":\"Read\"}");
    }

    @Test
    @DisplayName("openai_compatible（无 tool_reference）命中 → tool_reference + <functions> schema text 块（openai-lazy 扩展）")
    void mapToBlock_matches_openai_appendsFunctionsText() {
        List<Tool> tools = List.of(deferredTool("Read", "read a file"));
        // 无 model（或 deepseek）→ 保守判 openai：命中附带完整 JSONSchema 文本，模型直接拿参数调用
        AgentToolResult<?> result = executeResult("select:Read", tools);

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolsearch-1", false);
        List<?> content = (List<?>) block.content();
        assertThat(content).hasSize(2);
        assertThat(((ContentBlockParam) content.get(0)).type()).isEqualTo("tool_reference");
        ContentBlockParam text = (ContentBlockParam) content.get(1);
        assertThat(text.type()).isEqualTo("text");
        String t = ((ContentBlockParam.TextBlockParam) text).text();
        assertThat(t)
                .as("<functions> 完整 JSONSchema（对齐 CC PROMPT_TAIL 契约）")
                .contains("<functions>")
                .contains("\"name\":\"Read\"")
                .contains("\"parameters\":{}")
                .contains("</functions>");
    }

    @Test
    @DisplayName("空结果 → 纯文本 tool_result（CC ToolSearchTool.ts:448-461）")
    void mapToBlock_emptyMatches_textOutput() {
        AgentToolResult<?> result = executeResult("select:Zzz", List.of(deferredTool("Read", "read a file")));

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolsearch-1", false);
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.content())
                .isInstanceOf(String.class)
                .asString()
                .startsWith("No matching deferred tools found");
    }

    // ───────────────────────── 契约默认值 ─────────────────────────

    @Test
    @DisplayName("契约：isConcurrencySafe/isReadOnly=true、maxResultSizeChars=100_000、userFacingName=''、name=ToolSearch")
    void contract_flags() {
        assertThat(tool.isConcurrencySafe(MAPPER.createObjectNode())).isTrue();
        assertThat(tool.isReadOnly(MAPPER.createObjectNode())).isTrue();
        assertThat(tool.maxResultSizeChars()).isEqualTo(100_000L);
        assertThat(tool.userFacingName()).isEqualTo("");
        assertThat(tool.renderToolUseMessage(MAPPER.createObjectNode())).isNull();
        assertThat(tool.name()).isEqualTo("ToolSearch");
        assertThat(tool.description()).startsWith("Fetches full schema definitions for deferred tools");
        assertThat(tool.description()).endsWith("rank by remaining terms");
    }

    // ───────────────────────── helpers ─────────────────────────

    private ToolSearchTool.ToolSearchOutput execute(String query, List<Tool> tools) {
        return executeWithMax(query, 5, tools);
    }

    private ToolSearchTool.ToolSearchOutput executeWithMax(String query, int maxResults, List<Tool> tools) {
        return (ToolSearchTool.ToolSearchOutput) executeResult(query, maxResults, tools).data();
    }

    private AgentToolResult<?> executeResult(String query, List<Tool> tools) {
        return executeResult(query, 5, tools);
    }

    private AgentToolResult<?> executeResult(String query, int maxResults, List<Tool> tools) {
        return executeResultWithModel(query, maxResults, tools, null);
    }

    /** [openai-lazy] 带模型名执行（model 非 null 且支持 tool_reference → Anthropic 分流纯 tool_reference）。 */
    private AgentToolResult<?> executeResultWithModel(String query, List<Tool> tools, String model) {
        return executeResultWithModel(query, 5, tools, model);
    }

    private AgentToolResult<?> executeResultWithModel(String query, int maxResults, List<Tool> tools, String model) {
        JsonNode input = MAPPER.createObjectNode()
                .put("query", query)
                .put("max_results", maxResults);
        ToolUseBlock call = new ToolUseBlock("toolu_1", "ToolSearch", input);
        ToolUseContext ctx = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                tools, "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "")
                .withEffectiveModelName(model);
        return tool.execute(call, ctx);
    }

    private static Tool deferredTool(String name, String prompt) {
        return deferredTool(name, prompt, null);
    }

    private static Tool deferredTool(String name, String prompt, String searchHint) {
        return deferredTool(name, () -> prompt, searchHint);
    }

    private static Tool deferredTool(String name, java.util.function.Supplier<String> prompt, String searchHint) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return prompt.get(); }
            @Override public String prompt() { return prompt.get(); }
            @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "ok"); }
            @Override public boolean shouldDefer(JsonNode input) { return true; }
            @Override public String searchHint() { return searchHint; }
        };
    }

    private static Tool deferredMcpTool(String name, String prompt) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return prompt; }
            @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "ok"); }
            @Override public boolean shouldDefer(JsonNode input) { return true; }
            @Override public boolean isMcp() { return true; }
        };
    }

    private static Tool nonDeferredTool(String name, String prompt) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return prompt; }
            @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "ok"); }
        };
    }
}
