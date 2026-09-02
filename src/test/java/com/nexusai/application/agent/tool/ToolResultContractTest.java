package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 数据契约前置 — 严格对齐 CC ToolResult&lt;T&gt; (Tool.ts:321-336) + agentId nullable (Tool.ts:245).
 *
 * <p><b>规则九: 测试验证意图 (WHY), 不是 WHAT</b>. 每个测试的 WHY 注释说明该行为为何重要,
 * 业务逻辑变更时若测试仍绿则测试设计错误.
 *
 * <p>CC 真源 (主代理 grep 实证 Pattern #2/#9):
 * <ul>
 *   <li>Tool.ts:322 data:T — 工具结构化输出</li>
 *   <li>Tool.ts:331-335 mcpMeta { _meta?, structuredContent? } — MCP 透传, never sent to model</li>
 *   <li>Tool.ts:245 agentId?: AgentId — sub-agent context 可省</li>
 * </ul>
 */
class ToolResultContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════════
    // mcpMeta 通道 (CC Tool.ts:331-335)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void mcpMeta_shouldCarryMetaAndStructuredContent() {
        // WHY: MCP SDK 返回的 _meta + structuredContent 是 MCP 透传到 SDK 消费者的正通道
        //      (messages.ts:483 "never sent to model"). 若无 mcpMeta 字段, 这些信息被丢弃
        //      或被错误塞进 LLM tool_result (违反 never-to-model). mcpMeta 字段是 A1 对齐 CC 的核心新增.
        Map<String, Object> meta = Map.of("server_call_id", "abc");
        JsonNode structured = MAPPER.valueToTree(Map.of("ok", true));
        ToolResult.McpMeta mcpMeta = new ToolResult.McpMeta(meta, structured);

        ToolResult<JsonNode> r = new ToolResult<>(structured, null, null, mcpMeta);

        assertThat(r.mcpMeta()).isNotNull();
        assertThat(r.mcpMeta().meta().get("server_call_id")).isEqualTo("abc");
        assertThat(r.mcpMeta().structuredContent().get("ok").asBoolean()).isTrue();
    }

    @Test
    void mcpMeta_defaultNull_whenNoMcp() {
        // WHY: 非 MCP 工具不强塞 mcpMeta (CC Tool.ts:331 optional). 若默认非空会污染
        //      非 MCP 路径的 SDK 消费者透传, 且 subagent 抑制逻辑 (toolExecution.ts:1464)
        //      依赖 mcpMeta 默认 undefined/null.
        ToolResult<String> r = ToolResult.success("id", "ok");

        assertThat(r.mcpMeta()).isNull();
    }

    @Test
    void mcpMeta_null_doesNotSerialize() throws Exception {
        // WHY: mcpMeta=null 序列化时不应出现字段 (@JsonInclude NON_NULL). 若出现,
        //      outbound DTO (ChatMessageDto / STOMP) 会带空 mcpMeta 污染 wire 格式.
        ToolResult<String> r = ToolResult.success("id", "ok");
        String json = MAPPER.writeValueAsString(r);

        assertThat(json).doesNotContain("mcpMeta");
    }

    // ════════════════════════════════════════════════════════════════════════
    // data:T 泛型通道 (CC Tool.ts:322) — 替代旧 content:String + 退役 metadata
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void data_stringChannel_dropInForOldContent() {
        // WHY: 60+ 文本工具调用点用 success(id, stringContent). data 必须能 drop-in 替代旧
        //      content():String (语义未变, 仅改名). 若 data() 返回非 String 或抛错, 全工程编译断.
        ToolResult<String> r = ToolResult.success("id", "hello");

        assertThat(r.data()).isEqualTo("hello");
        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("success data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
    }

    @Test
    void data_genericChannel_carriesStructuredJsonNode() {
        // WHY: 严格对齐 CC data:T — 结构化输出工具 (FileRead image/pdf) 应走泛型通道
        //      而非旧 metadata Map 旁路 (s05-P2-9 假代码, CC 无对应). typed factories 返回
        //      ToolResult<JsonNode>, 结构化字段折入 data (内联 META_* 常量 key).
        JsonNode structured = MAPPER.valueToTree(Map.of("read_file_output_type", "image",
            "image_base64", "BASE64...", "image_media_type", "image/png"));
        ToolResult<JsonNode> r = new ToolResult<>(structured, null, null, null);

        assertThat(r.data()).isInstanceOf(JsonNode.class);
        assertThat(r.data().get("read_file_output_type").asText()).isEqualTo("image");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 向后兼容 ctor 链 (退役 metadata 后 3/4 参 ctor 不破)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void ctorChain_fourFieldRecordContract() {
        // WHY: [IMP-C2] 4 字段契约下 record 仅含 (data, newMessages, contextModifier, mcpMeta)；
        //      toolUseId/isError/errorCategory 已删（对齐 CC ToolResult 4 字段, Tool.ts:321-336）。
        //      60+ 工具调用点经工厂方法（success/error）编译通过，record 直接构造走 4 参。
        ToolResult<String> r3 = new ToolResult<>("c", null, null, null);

        assertThat(r3.data()).isEqualTo("c");
        assertThat(r3.newMessages()).isEmpty();
        assertThat(r3.contextModifier()).isNull();
        assertThat(r3.mcpMeta()).isNull();
    }

    @Test
    void factory_successWithNewMessages_carriesMessages() {
        // WHY: 退役 ExtendedToolResult 后, newMessages 折入 ToolResult (CC Tool.ts:323).
        //      SkillTool 用 successWithNewMessages 注入技能指令到对话历史 (跨 turn 持久).
        //      若该工厂缺失, SkillTool 对 LLM 零有效内容 (P0-1 dead code regression).
        ToolResult<String> r = ToolResult.successWithNewMessages("id", "skill-meta-json",
            List.of()); // 空 newMessages (实际 SkillTool 传技能消息)

        assertThat(r.newMessages()).isNotNull();
        assertThat(r.data()).isEqualTo("skill-meta-json");
    }

    @Test
    void factory_successWithStructuredOutput_carriesOutput() {
        // WHY: 退役 ExtendedToolResult.withStructuredOutput 后, SyntheticOutputTool 用此工厂
        //      透传结构化输出到 AgentState.recordStructuredOutput (b14 本地暂存 → provider).
        //      若缺失, 结构化输出工具退化.
        Map<String, Object> output = Map.of("result", "validated");
        ToolResult<Map<String, Object>> r = ToolResult.successWithStructuredOutput("id", "summary", output);

        assertThat(ToolResult.presentationMeta(r)).containsEntry("result", "validated");
        assertThat(r.data().get("summary")).isEqualTo("summary");
    }

    // ════════════════════════════════════════════════════════════════════════
    // agentId nullable + 默认 UUID (CC Tool.ts:245 agentId?: AgentId)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void agentId_defaultUuid_whenOmitted() {
        // WHY: CC Tool.ts:245 agentId?: AgentId 可选 (仅 subagent 设). sub-agent context
        //      创建时 CC 可省 agentId, Java 之前强制必填 → 创建失败/抛 NPE (与 C session
        //      ResumeAgent/sub-agent context 冲突). 默认 UUID 兜底让 sub-agent context 可省.
        //      若 canonical ctor 不默认 UUID, 此测试必红 (Pattern #14 RED 证: 回退 UUID 行 → 红).
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = new ToolUseContext(
            null,                       // agentId 省略
            sessionId,
            PermissionMode.DEFAULT,
            Map.<String, ToolUseContext.AdditionalWorkingDirectory>of());

        assertThat(ctx.agentId()).isNotNull();   // 默认 UUID
        assertThat(ctx.sessionId()).isEqualTo(sessionId);
    }
}