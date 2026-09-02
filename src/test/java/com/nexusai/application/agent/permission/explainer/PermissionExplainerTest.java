package com.nexusai.application.agent.permission.explainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PermissionExplainer E3 意图测试 · 对齐 CC permissionExplainer.ts。
 *
 * <p>验证 WHY（而非仅 WHAT）：
 * <ul>
 *   <li>门控关闭 → null，且不触发任何 LLM 调用（CC :155-157 早退）</li>
 *   <li>成功 → 四字段全必填（CC :28-33 + zod 严格 :77-84）</li>
 *   <li>解析失败 → null 无降级（CC :221-228）</li>
 *   <li>异常 → null 无降级（CC :229-249，旧 Java fallback MEDIUM 已删）</li>
 *   <li>riskLevel 严格枚举，无大小写容错（CC z.enum）</li>
 * </ul>
 */
class PermissionExplainerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 构造器辅助 ──

    private static PermissionExplainer explainer(
            boolean enabled,
            LlmProviderFactory providerFactory,
            ModelConfigResolver resolver,
            AnalyticsTracker analytics) {
        // [DEL-WF7-EX-01] modelNameOverride 已删（CC 恒用主循环模型）——构造器 4 参，
        // 需主循环模型源的测试经 registerMainLoopModelSession 注入会话态
        return new PermissionExplainer(providerFactory, resolver, analytics, enabled);
    }

    /** 注册一个 currentModel=mainLoopModel 的会话并注入 explainer（对齐 CC getMainLoopModel 五层结果落盘）。 */
    private static PermissionExplainer explainerWithMainLoopModel(
            boolean enabled,
            LlmProviderFactory providerFactory,
            ModelConfigResolver resolver,
            AnalyticsTracker analytics,
            String mainLoopModel,
            String sessionId) {
        PermissionExplainer explainer = explainer(enabled, providerFactory, resolver, analytics);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = mock(AgentState.class);
        when(state.currentModel()).thenReturn(mainLoopModel);
        registry.register(sessionId, state);
        explainer.setSessionAgentStateRegistry(registry);
        return explainer;
    }

    private static ModelConfigResolver.ResolvedModel usableResolved() {
        return new ModelConfigResolver.ResolvedModel(
            new ProviderConfig("https://example.com", "sk-test"), "openai_sdk");
    }

    private static ChatMessageDto assistant(String content) {
        return new ChatMessageDto(null, null, Role.assistant, null, content,
            null, null, null, null, null, null, null, null, null, null,
            List.of(), List.of());
    }

    private static ObjectNode fourFieldInput() {
        ObjectNode n = JSON.createObjectNode();
        n.put("riskLevel", "HIGH");
        n.put("explanation", "Deletes the file permanently");
        n.put("reasoning", "I need to remove an obsolete file");
        n.put("risk", "Data loss is irreversible");
        return n;
    }

    // ── 1. 门控关闭 → null，不触发 LLM ──

    @Test
    void disabledGateReturnsNullWithoutCallingProvider() {
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        PermissionExplainer explainer = explainer(false, providerFactory, resolver, analytics);

        PermissionExplanation result = explainer.generatePermissionExplanation(
            null, "Bash", JSON.createObjectNode(), null, null, null);

        assertNull(result);
        assertFalse(explainer.isPermissionExplainerEnabled());
        verify(providerFactory, never()).getProvider(any(), any());
        verify(resolver, never()).resolve(any());
        verify(analytics, never()).logEvent(any(), anyMap());
    }

    // ── 2. 成功 → 四字段全必填 ──

    @Test
    void successReturnsAllFourFieldsAndEmitsGeneratedTelemetry() {
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("test-model")).thenReturn(usableResolved());
        ToolUseBlock toolUse = new ToolUseBlock("toolu_1", "explain_command", fourFieldInput());
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any()))
            .thenReturn(new AssistantMessage("", "tool_calls", List.of(toolUse), ""));

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PermissionExplainer explainer =
            explainerWithMainLoopModel(true, providerFactory, resolver, analytics, "test-model", sessionId);
        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "Bash", JSON.createObjectNode(), null, null, null);

        assertNotNull(result);
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals("Deletes the file permanently", result.explanation());
        assertEquals("I need to remove an obsolete file", result.reasoning());
        assertEquals("Data loss is irreversible", result.risk());
        verify(analytics).logEvent(eq("tengu_permission_explainer_generated"), anyMap());
    }

    // ── 3. 解析失败（缺字段）→ null 无降级 ──

    @Test
    void parseFailureReturnsNullWithoutFallback() {
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("test-model")).thenReturn(usableResolved());
        // 缺 risk 字段 → 四字段不全 → 解析失败
        ObjectNode missingRisk = JSON.createObjectNode();
        missingRisk.put("riskLevel", "LOW");
        missingRisk.put("explanation", "x");
        missingRisk.put("reasoning", "y");
        ToolUseBlock toolUse = new ToolUseBlock("toolu_1", "explain_command", missingRisk);
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any()))
            .thenReturn(new AssistantMessage("", "tool_calls", List.of(toolUse), ""));

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PermissionExplainer explainer =
            explainerWithMainLoopModel(true, providerFactory, resolver, analytics, "test-model", sessionId);
        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "Bash", JSON.createObjectNode(), null, null, null);

        assertNull(result);
        verify(analytics).logEvent(eq("tengu_permission_explainer_error"), anyMap());
    }

    // ── 3.5 [IMP-7 · OPD-WF7-01-03] mcp__*→mcp_tool 归一化（success/parse-error 三处之二）──

    @Test
    void successSanitizesMcpToolNameForAnalytics() {
        // WHY: CC permissionExplainer.ts:210 `tool_name: sanitizeToolNameForAnalytics(toolName)`
        //   （metadata.ts:70-77）：MCP 工具名形如 mcp__<server>__<tool>，server alias 可能暴露
        //   用户特定配置（IP/路径/凭据），CC 视为 PII-medium → telemetry 遮蔽为 'mcp_tool'。
        //   旧 Java 直传原始名（MCP 名泄漏到 analytics）——OPD-WF7-01-03 拍板补归一化。
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("test-model")).thenReturn(usableResolved());
        ToolUseBlock toolUse = new ToolUseBlock("toolu_1", "explain_command", fourFieldInput());
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any()))
            .thenReturn(new AssistantMessage("", "tool_calls", List.of(toolUse), ""));

        // F3C-MODEL：reach telemetry 路径必须先解析会话主循环模型（CC getMainLoopModel() 恒可解析，
        //   Java 等价=注入会话态 currentModel），否则 resolveMainLoopModelName(null) 早退 null（无降级）。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PermissionExplainer explainer =
            explainerWithMainLoopModel(true, providerFactory, resolver, analytics, "test-model", sessionId);
        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "mcp__github__create_issue", JSON.createObjectNode(), null, null, null);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
            ArgumentCaptor.forClass((Class) Map.class);
        verify(analytics).logEvent(eq("tengu_permission_explainer_generated"), captor.capture());
        // [IMP-T REWORK] tool_name 经 AnalyticsTracker.verified() 包装 → 断言需解包
        assertEquals("mcp_tool",
            ((AnalyticsTracker.VerifiedString) captor.getValue().get("tool_name")).value(),
            "CC metadata.ts:70-77 mcp__* → mcp_tool（PII 防护）");
    }

    @Test
    void parseErrorSanitizesMcpToolNameForAnalytics() {
        // WHY: CC permissionExplainer.ts:222 `tool_name: sanitizeToolNameForAnalytics(toolName)`
        //   —— 解析失败路径同样必须归一化 MCP 名（success/parse-error/error 三处一致）。
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("test-model")).thenReturn(usableResolved());
        // 缺 risk 字段 → 四字段不全 → 解析失败
        ObjectNode missingRisk = JSON.createObjectNode();
        missingRisk.put("riskLevel", "LOW");
        missingRisk.put("explanation", "x");
        missingRisk.put("reasoning", "y");
        ToolUseBlock toolUse = new ToolUseBlock("toolu_1", "explain_command", missingRisk);
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any()))
            .thenReturn(new AssistantMessage("", "tool_calls", List.of(toolUse), ""));

        // F3C-MODEL：reach telemetry 路径必须先解析会话主循环模型（CC getMainLoopModel() 恒可解析，
        //   Java 等价=注入会话态 currentModel），否则 resolveMainLoopModelName(null) 早退 null（无降级）。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PermissionExplainer explainer =
            explainerWithMainLoopModel(true, providerFactory, resolver, analytics, "test-model", sessionId);
        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "mcp__github__create_issue", JSON.createObjectNode(), null, null, null);

        assertNull(result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
            ArgumentCaptor.forClass((Class) Map.class);
        verify(analytics).logEvent(eq("tengu_permission_explainer_error"), captor.capture());
        // [IMP-T REWORK] tool_name 经 AnalyticsTracker.verified() 包装 → 断言需解包
        assertEquals("mcp_tool",
            ((AnalyticsTracker.VerifiedString) captor.getValue().get("tool_name")).value(),
            "CC metadata.ts:70-77 解析失败路径同样归一化");
        assertEquals(1, captor.getValue().get("error_type"),
            "CC permissionExplainer.ts:222 ERROR_TYPE_PARSE=1");
    }

    // ── 4. 异常 → null 无降级 ──

    @Test
    void exceptionReturnsNullWithoutFallback() {
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("test-model")).thenReturn(usableResolved());
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("provider down"));

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PermissionExplainer explainer =
            explainerWithMainLoopModel(true, providerFactory, resolver, analytics, "test-model", sessionId);
        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "Bash", JSON.createObjectNode(), null, null, null);

        assertNull(result);
        verify(analytics).logEvent(eq("tengu_permission_explainer_error"), anyMap());
    }

    // ── 5. 严格四字段解析（纯方法）──

    @Test
    void parseToolUseInputIsStrict() {
        assertNotNull(PermissionExplainer.parseToolUseInput(fourFieldInput()));

        // 缺 explanation
        ObjectNode missing = JSON.createObjectNode();
        missing.put("riskLevel", "LOW");
        missing.put("reasoning", "y");
        missing.put("risk", "z");
        assertNull(PermissionExplainer.parseToolUseInput(missing));

        // riskLevel 非法（无大小写容错）
        ObjectNode badRisk = fourFieldInput();
        badRisk.put("riskLevel", "CRITICAL");
        assertNull(PermissionExplainer.parseToolUseInput(badRisk));

        // riskLevel 小写也不容错
        ObjectNode lowerRisk = fourFieldInput();
        lowerRisk.put("riskLevel", "high");
        assertNull(PermissionExplainer.parseToolUseInput(lowerRisk));

        assertNull(PermissionExplainer.parseToolUseInput(null));
    }

    // ── 6. 对话上下文：最近 3 条 assistant + 截断 ──

    @Test
    void extractConversationContextTakesLast3AssistantMessages() {
        List<ChatMessageDto> messages = List.of(
            assistant("first old message"),
            assistant("second"),
            assistant("third"),
            assistant("fourth recent"));
        String ctx = PermissionExplainer.extractConversationContext(messages, 1000);
        assertTrue(ctx.contains("second"));
        assertTrue(ctx.contains("third"));
        assertTrue(ctx.contains("fourth recent"));
        assertFalse(ctx.contains("first old message"));
    }

    @Test
    void extractConversationContextTruncatesToBudget() {
        List<ChatMessageDto> messages = List.of(assistant("aaaaaaaaaa")); // 10 chars
        String ctx = PermissionExplainer.extractConversationContext(messages, 5);
        assertEquals("aaaaa...", ctx);
    }

    // ── 7. 工具输入格式化：string 直传 ──

    @Test
    void formatToolInputPassesThroughString() {
        assertEquals("hello", PermissionExplainer.formatToolInput(JSON.getNodeFactory().textNode("hello")));
    }

    @Test
    void riskLevelNumericValuesAlignCc() {
        assertEquals(1, RiskLevel.LOW.numericValue());
        assertEquals(2, RiskLevel.MEDIUM.numericValue());
        assertEquals(3, RiskLevel.HIGH.numericValue());
    }

    @Test
    void explainCommandToolSchemaRequiresAllFourFields() {
        JsonNode tools = ExplainCommandToolSchema.buildToolsArray();
        JsonNode tool = tools.get(0);
        assertEquals("function", tool.path("type").asText());
        assertEquals("explain_command", tool.path("function").path("name").asText());
        JsonNode required = tool.path("function").path("parameters").path("required");
        assertTrue(required.isArray());
        assertEquals(4, required.size());
        assertTrue(required.toString().contains("explanation"));
        assertTrue(required.toString().contains("reasoning"));
        assertTrue(required.toString().contains("risk"));
        assertTrue(required.toString().contains("riskLevel"));
    }

    // ── 8. [F3C-MODEL] explainer 模型源走会话主循环模型（五层解析结果，非 fast 小快模型）──

    @Test
    void explainerModelSourceUsesSessionMainLoopModel() {
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = mock(AgentState.class);
        when(state.currentModel()).thenReturn("claude-sonnet-4-5");
        registry.register(sessionId, state);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("claude-sonnet-4-5")).thenReturn(usableResolved());
        ToolUseBlock toolUse = new ToolUseBlock("toolu_1", "explain_command", fourFieldInput());
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any()))
            .thenReturn(new AssistantMessage("", "tool_calls", List.of(toolUse), ""));

        // modelNameOverride 置空 → 模型源走会话主循环模型（AgentState.currentModel = CC options.mainLoopModel）
        PermissionExplainer explainer = new PermissionExplainer(providerFactory, resolver, analytics, true);
        explainer.setSessionAgentStateRegistry(registry);

        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "Bash", JSON.createObjectNode(), null, null, null);

        assertNotNull(result);
        // WHY：explainer 必须用主循环模型（CC getMainLoopModel 五层结果，permissionExplainer.ts:175），
        //   绝非 getSmallFastModel 小快模型（旧 resolveFastModelName 错接）
        verify(resolver).resolve("claude-sonnet-4-5");
        verify(resolver, never()).resolveFastModelName(any());
    }

    @Test
    void explainerReturnsNullWhenSessionMainLoopModelMissing() {
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = mock(AgentState.class);
        when(state.currentModel()).thenReturn(null); // 五层链未落盘 → null
        registry.register(sessionId, state);

        PermissionExplainer explainer = new PermissionExplainer(providerFactory, resolver, analytics, true);
        explainer.setSessionAgentStateRegistry(registry);

        PermissionExplanation result = explainer.generatePermissionExplanation(
            sessionId, "Bash", JSON.createObjectNode(), null, null, null);

        assertNull(result);
        verify(resolver, never()).resolve(any());
        verify(providerFactory, never()).getProvider(any(), any());
    }

    // ── 9. [DEL-WF7-EX-01] modelNameOverride 删除：构造器不得再接受 nexusai.permission.explainer.model ──

    @Test
    void constructorAcceptsNoExplainerModelOverride() {
        // RED→GREEN：删除前构造器第 5 参携带 @Value("${nexusai.permission.explainer.model:}")
        // → 本测试遍历构造器参数发现该注解 → 抛错（RED）。删除后注解消失 → 通过（GREEN）。
        // WHY（规则九 · 验证意图）：CC permissionExplainer.ts:175 恒用 getMainLoopModel()
        // （主循环五层链，model.ts:61-98），无 explainer 独立模型覆盖；Java 旧实现
        // nexusai.permission.explainer.model 为 Java 独有（DEL-WF7-EX-01，用户 2026-08-18 拍板删除）。
        for (java.lang.reflect.Constructor<?> ctor : PermissionExplainer.class.getConstructors()) {
            for (java.lang.reflect.Parameter p : ctor.getParameters()) {
                for (java.lang.annotation.Annotation a : p.getAnnotations()) {
                    if (a instanceof org.springframework.beans.factory.annotation.Value v
                            && v.value() != null
                            && v.value().contains("permission.explainer.model")) {
                        throw new AssertionError(
                            "PermissionExplainer 构造器不得再携带 nexusai.permission.explainer.model "
                                + "参数（DEL-WF7-EX-01：CC 恒用主循环模型 getMainLoopModel，无独立模型覆盖）");
                    }
                }
            }
        }
    }
}
