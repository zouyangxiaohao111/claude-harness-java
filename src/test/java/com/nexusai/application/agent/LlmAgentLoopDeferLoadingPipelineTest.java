package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.application.agent.tool.impl.WebSearchTool;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H4] LlmAgentLoop 主循环 defer_loading 管线测试 · 对齐 CC claude.ts:1120-1243
 * （definitive 门控 + deferredToolNames 短路 + filteredTools + willDefer→defer_loading 发射）
 * + claude.ts:1330-1332（delta 门控 prepend）。
 *
 * <p>WHY: 主循环 schema 构建（{@code llmToolsArray}）此前无工具搜索概念——deferred 工具
 * 全量预声明、无 defer_loading 发射、无 beta header。本管线让 Java 具备 CC 动态工具加载
 * 闭环（未发现的 deferred 工具不进 schema；已发现的经 defer_loading 延迟发送）。
 *
 * <p>变异点：删 llmToolsArray 管线（短路/filteredTools/willDefer 任一）→ 对应断言变红；
 * 删 delta prepend → prepend 用例变红。
 */
class LlmAgentLoopDeferLoadingPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUPPORTED_MODEL = "claude-sonnet-4-5";
    /** 非 anthropic 模型（openai_compatible 代理）；其名不在 tool_reference 不支持名单 → isToolSearchEnabled 单独判会为 true。 */
    private static final String OPENAI_COMPAT_MODEL = "deepseek-v4-flash";

    @AfterEach
    void resetEnv() {
        ToolSearchService.envOverride = null;
    }

    /**
     * 10 参构造器 per-turn TUC（对齐 LlmAgentLoopToolsArrayDenyTest + 生产 perTurnTuc 形态）·
     * [R8] 盖章 providerType=anthropic —— 生产 perTurnTuc 由 {@code AgentLoopContext.toolExecContext}
     * 经 {@code ModelConfigResolver.resolveProviderType} 每轮盖章 {@code effectiveProviderType}
     * （门控 AND 的 provider 源）。缺此门控 AND 恒 false → 所有现存 anthropic 用例退化为全发。
     */
    private ToolUseContext tuc(List<Tool> availableTools) {
        return tuc(availableTools, "anthropic");
    }

    /** [R8] 显式 provider 盖章变体（非 anthropic / 未知场景）。 */
    private ToolUseContext tuc(List<Tool> availableTools, String providerType) {
        return new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT, Map.of(),
                availableTools, null, AbortController.NOOP, List.of(),
                (ToolPermissionContext) null, PermissionMode.DEFAULT)
            .withEffectiveProviderType(providerType);
    }

    /** 含 tool_result→tool_reference 块的 user 消息（对齐 CC ToolSearchTool.ts:465-468 发射形状）. */
    private static ChatMessageDto userMsgWithToolReference(String... toolNames) {
        JsonNode toolResult = JSON.createObjectNode()
            .put("type", "tool_result")
            .set("content", JSON.createArrayNode());
        for (String name : toolNames) {
            ((ArrayNode) toolResult.path("content"))
                .add(JSON.createObjectNode().put("type", "tool_reference").put("tool_name", name));
        }
        return new ChatMessageDto("u1", "s1", Role.user, "user", "tool result",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(toolResult), List.of(), null, false, false);
    }

    private static List<String> schemaNames(ArrayNode schema) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonNode fn : schema) {
            result.add(fn.path("function").path("name").asText());
        }
        return result;
    }

    @Test
    @DisplayName("defer_loading 发射：discovered 含 WebSearch → schema 有 WebSearch 且 wrapper 顶层 defer_loading=true（Bash 无）")
    void pipeline_deferLoading_emittedOnDiscoveredDeferredTool() {
        // WHY: CC claude.ts:1208-1209 willDefer + api.ts:223-225 schema.defer_loading=true。
        //   变异点：toOpenAiToolsArray 不写 defer_loading → 本用例红。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());

        LlmAgentLoop.ToolsAssembly assembly = LlmAgentLoop.llmToolsArray(
                tuc(available), QuerySource.USER,
                List.of(userMsgWithToolReference("WebSearch")), SUPPORTED_MODEL);

        assertThat(assembly.useToolSearch()).isTrue();
        List<String> names = schemaNames(assembly.tools());
        assertThat(names).containsExactlyInAnyOrder("Bash", "ToolSearch", "WebSearch");
        JsonNode webSearch = findSchema(assembly.tools(), "WebSearch");
        assertThat(webSearch.path("defer_loading").asBoolean(false))
                .as("discovered deferred 工具 wrapper 顶层写 defer_loading=true（CC api.ts:223-225）")
                .isTrue();
        JsonNode bash = findSchema(assembly.tools(), "Bash");
        assertThat(bash.has("defer_loading"))
                .as("非 deferred 工具不写 defer_loading")
                .isFalse();
    }

    @Test
    @DisplayName("短路 + false 路径：无 deferred 工具 → useToolSearch=false，schema 排除 ToolSearch（claude.ts:1140-1147 + 1170-1172）")
    void pipeline_shortCircuit_noDeferred_excludesToolSearch() {
        // WHY: CC claude.ts:1140-1147 无 deferred 且无 pending MCP → 关闭工具搜索；
        //   useToolSearch=false → 排除 ToolSearch（模型收到 tool_reference 会失败）。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool());

        LlmAgentLoop.ToolsAssembly assembly = LlmAgentLoop.llmToolsArray(
                tuc(available), QuerySource.USER, List.of(), SUPPORTED_MODEL);

        assertThat(assembly.useToolSearch()).isFalse();
        assertThat(schemaNames(assembly.tools()))
                .as("短路后 ToolSearch 不暴露给模型（claude.ts:1170-1172）")
                .containsExactly("Bash");
    }

    @Test
    @DisplayName("filteredTools：deferred 未在 discovered-set → 不进 schema（动态工具加载，claude.ts:1163-1168）")
    void pipeline_filteredTools_dropsUndeclaredDeferred() {
        // WHY: 未预声明的 deferred 工具绝不进 schema（消除全量预声明），仅 tool_reference 已发现才发送。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());

        LlmAgentLoop.ToolsAssembly assembly = LlmAgentLoop.llmToolsArray(
                tuc(available), QuerySource.USER, List.of(), SUPPORTED_MODEL);

        assertThat(assembly.useToolSearch()).isTrue();
        assertThat(schemaNames(assembly.tools()))
                .as("discovered 空 → WebSearch 剔除，Bash/ToolSearch 保留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch");
    }

    @Test
    @DisplayName("[R8] 非 anthropic（openai_compatible）→ 排除 ToolSearch + 全部工具内联、无 defer_loading（CC claude.ts:1170-1172）")
    void pipeline_nonAnthropic_sendsAllInlineNoDeferLoading() {
        // WHY（R8 2026-09-11 回归 CC）：门控 AND = isToolSearchEnabled(...) && toolReferenceUsable(providerType, model)。
        //   非 anthropic provider（openai_compatible）→ toolReferenceUsable=false → useToolSearch=false →
        //   filterToolsForSchema false 分支「排除 ToolSearch + 全量内联」，绝不发 defer_loading（willDefer=false）。
        //   此处刻意让 discovered 含 WebSearch：证明确实未走「deferred 过滤」（否则 WebSearch 会被 defer）。
        //   变异 (i)：把门控 AND 去掉 → 非 anthropic 仍走 true 分支（deepseek 过模型名单、isToolSearchEnabled=true，
        //   deferred 非空不短路）→ ToolSearch 保留 + defer_loading 发射 → 本用例红（退回 09-01 死锁方向）。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());

        LlmAgentLoop.ToolsAssembly assembly = LlmAgentLoop.llmToolsArray(
                tuc(available, "openai_compatible"), QuerySource.USER,
                List.of(userMsgWithToolReference("WebSearch")), OPENAI_COMPAT_MODEL);

        assertThat(assembly.useToolSearch()).isFalse();
        assertThat(schemaNames(assembly.tools()))
                .as("非 anthropic → 排除 ToolSearch，其余（含 deferred WebSearch）全量内联")
                .containsExactlyInAnyOrder("Bash", "WebSearch");
        assertThat(findSchema(assembly.tools(), "WebSearch").has("defer_loading"))
                .as("非 anthropic 绝不发 defer_loading（CC claude.ts:1208-1209 willDefer=false）")
                .isFalse();
        assertThat(findSchema(assembly.tools(), "Bash").has("defer_loading")).isFalse();
    }

    @Test
    @DisplayName("[R8] anthropic + haiku → 排除 ToolSearch + 全量内联（模型不支持 tool_reference）")
    void pipeline_anthropicHaiku_sendsAllInline() {
        // WHY（R8）：anthropic 下 haiku 不解析 tool_reference（toolSearch.ts:200-204）→ toolReferenceUsable=false
        //   → useToolSearch=false → CC 全发。旧实现靠「清空 deferred」豁免到达同一形态；R8 由门控 AND 直接判。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());

        LlmAgentLoop.ToolsAssembly assembly = LlmAgentLoop.llmToolsArray(
                tuc(available, "anthropic"), QuerySource.USER,
                List.of(userMsgWithToolReference("WebSearch")), "claude-haiku-4-5");

        assertThat(assembly.useToolSearch()).isFalse();
        assertThat(schemaNames(assembly.tools()))
                .as("haiku → 排除 ToolSearch，其余全量内联")
                .containsExactlyInAnyOrder("Bash", "WebSearch");
        assertThat(findSchema(assembly.tools(), "WebSearch").has("defer_loading")).isFalse();
    }

    @Test
    @DisplayName("[R8] anthropic + 非 haiku（sonnet）→ 保留懒加载 deferred（discovered 才进 schema）")
    void pipeline_anthropicNonHaiku_keepsDeferredLazyLoad() {
        // WHY（R8）：anthropic × 支持模型 → toolReferenceUsable=true → useToolSearch=true（deferred 非空不短路）
        //   → true 分支：ToolSearch 保留、未 discovered 的 deferred（WebSearch）剔除。此即被删
        //   LlmAgentLoopOpenAiDeferExemptTest 中「anthropic 保留 deferred」语义的管线级替代。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());

        LlmAgentLoop.ToolsAssembly noDiscovery = LlmAgentLoop.llmToolsArray(
                tuc(available, "anthropic"), QuerySource.USER, List.of(), SUPPORTED_MODEL);
        assertThat(noDiscovery.useToolSearch()).isTrue();
        assertThat(schemaNames(noDiscovery.tools()))
                .as("anthropic + sonnet → WebSearch（deferred 未发现）剔除，Bash/ToolSearch 保留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch");

        LlmAgentLoop.ToolsAssembly discovered = LlmAgentLoop.llmToolsArray(
                tuc(available, "anthropic"), QuerySource.USER,
                List.of(userMsgWithToolReference("WebSearch")), SUPPORTED_MODEL);
        assertThat(schemaNames(discovered.tools()))
                .as("discovered 后 WebSearch 进 schema（懒加载发现闭环）")
                .containsExactlyInAnyOrder("Bash", "ToolSearch", "WebSearch");
    }

    @Test
    @DisplayName("delta 门控 prepend：useToolSearch=true 且 delta 未启用 → 队首插入 <available-deferred-tools> 消息（claude.ts:1330-1332）")
    void pipeline_deltaPrepend_metaMessageAtHead() {
        // WHY: claude.ts:1330-1332 每轮 prepend 临时 <available-deferred-tools>（delta attachment
        //   未启用时），让模型知道可动态发现哪些工具；formatDeferredToolLine = tool.name 排序 join。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());
        ChatMessageDto original = userMsgWithToolReference("WebSearch");
        List<ChatMessageDto> messages = new java.util.ArrayList<>(List.of(original));

        LlmAgentLoop.ToolsAssembly assembly = LlmAgentLoop.llmToolsArray(
                tuc(available), QuerySource.USER, messages, SUPPORTED_MODEL);
        List<ChatMessageDto> withDelta = assembly.prependAvailableDeferredTools(messages);

        assertThat(withDelta).hasSize(2);
        assertThat(withDelta.get(0).role()).isEqualTo(Role.user);
        assertThat(withDelta.get(0).isMeta()).isTrue();
        assertThat(withDelta.get(0).content())
                .as("delta prepend 内容 = <available-deferred-tools> 包裹的排序 deferred 名")
                .contains("<available-deferred-tools>")
                .contains("WebSearch")
                .contains("</available-deferred-tools>");
        assertThat(withDelta.get(1)).isSameAs(original);
    }

    @Test
    @DisplayName("2 参旧签名保持旧行为：无工具搜索/无 defer_loading（deny 测试等既有契约不变）")
    void legacyTwoArgSignature_oldBehavior() {
        // ToolSearchTool.isEnabled() → isToolSearchEnabledOptimistic() 读 env → 固定默认 mode(tst) 保确定性。
        ToolSearchService.envOverride = Map.of();
        List<Tool> available = List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());
        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(available), QuerySource.USER);
        assertThat(schemaNames(schema))
                .as("2 参签名无 tool-search 过滤（defer_loading 管线不参与）")
                .contains("Bash", "ToolSearch", "WebSearch");
        assertThat(findSchema(schema, "WebSearch").has("defer_loading")).isFalse();
    }

    private static JsonNode findSchema(ArrayNode schema, String name) {
        for (JsonNode fn : schema) {
            if (name.equals(fn.path("function").path("name").asText())) {
                return fn;
            }
        }
        throw new AssertionError("schema 中找不到工具: " + name);
    }
}
