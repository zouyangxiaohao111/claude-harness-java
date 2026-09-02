package com.nexusai.application.agent.permission.explainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.telemetry.McpServerToolSanitizer;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 权限解释器 · 对齐 CC {@code generatePermissionExplanation}
 * (Open-ClaudeCode/src/utils/permissions/permissionExplainer.ts:147-250)。
 *
 * <p>核心流程（门控 → 结构化输出 → 主循环模型源 → telemetry）：
 * <ol>
 *   <li>{@link #isPermissionExplainerEnabled()} 门控（CC :139-141，默认开，可 opt-out）</li>
 *   <li>{@link #formatToolInput} + {@link #extractConversationContext} 拼 userPrompt（CC :162-173）</li>
 *   <li>模型源 = 会话主循环模型（CC :175 {@code getMainLoopModel()}）：
 *       读 {@link AgentState#currentModel}（= CC {@code options.mainLoopModel}，由
 *       {@code LlmAgentLoop.getModelForCall} 五层解析落盘）→ 经 {@link ModelConfigResolver}
 *       解析真实 (config, providerType)，解析失败 → null（无降级）
 *       【DEL-WF7-EX-01】旧 {@code modelNameOverride}（nexusai.permission.explainer.model）已删——
 *       CC 恒用主循环模型 getMainLoopModel，无 explainer 独立模型覆盖，用户 2026-08-18 拍板删除</li>
 *   <li>强制结构化输出：{@code tools=[explain_command]} + {@code tool_choice={type:'tool',
 *       name:'explain_command'}}（CC :178-186）→ 取 tool_use 块 → 严格四字段解析</li>
 *   <li>telemetry：成功 → {@code tengu_permission_explainer_generated}；解析失败 → PARSE 错误；
 *       异常 → NETWORK/UNKNOWN 错误（CC :209-247）</li>
 * </ol>
 *
 * <h2>失败语义（无降级）</h2>
 * <p>一切失败返回 null：门控关闭 / 模型解析失败 / 无 tool_use / 四字段非法 / 异常。
 * CC permissionExplainer.ts 所有失败路径（:155-157 门控、:221-228 解析失败、:229-249 异常）
 * 一律 return null，无 fallback 文案。</p>
 *
 * @see PermissionExplanation
 * @see RiskLevel
 * @see ExplainCommandToolSchema
 */
@Component
public class PermissionExplainer {

    private static final Logger log = LoggerFactory.getLogger(PermissionExplainer.class);

    /** CC original: SYSTEM_PROMPT (permissionExplainer.ts:43)。 */
    static final String SYSTEM_PROMPT =
        "Analyze shell commands and explain what they do, why you're running them, and potential risks.";

    /** CC original: querySource 'permission_explainer' (permissionExplainer.ts:185)。 */
    static final String QUERY_SOURCE = "permission_explainer";

    /** CC original: extractConversationContext maxChars 默认 1000 (permissionExplainer.ts:104)。 */
    static final int MAX_CONTEXT_CHARS = 1000;

    /** CC original: ERROR_TYPE_PARSE = 1 (permissionExplainer.ts:24)。 */
    static final int ERROR_TYPE_PARSE = 1;
    /** CC original: ERROR_TYPE_NETWORK = 2 (permissionExplainer.ts:25)。 */
    static final int ERROR_TYPE_NETWORK = 2;
    /** CC original: ERROR_TYPE_UNKNOWN = 3 (permissionExplainer.ts:26)。 */
    static final int ERROR_TYPE_UNKNOWN = 3;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 依赖 ──

    private final LlmProviderFactory providerFactory;
    private final ModelConfigResolver modelConfigResolver;
    private final AnalyticsTracker analyticsTracker;
    /** CC original: permissionExplainerEnabled !== false（默认开）。 */
    private final boolean enabled;

    /**
     * [F3C-MODEL] 会话主 AgentState 注册表 · 解析会话主循环模型源（{@link AgentState#currentModel}
     * = CC {@code options.mainLoopModel}，由 {@code LlmAgentLoop.getModelForCall} 五层解析落盘）。
     * null = 未注入（测试直构 / 无会话态）→ 主循环模型解析失败 → null（对齐 CC 无降级）。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    public PermissionExplainer(
            LlmProviderFactory providerFactory,
            ModelConfigResolver modelConfigResolver,
            AnalyticsTracker analyticsTracker,
            @Value("${nexusai.permission.explainer.enabled:true}") boolean enabled
    ) {
        this.providerFactory = providerFactory;
        this.modelConfigResolver = modelConfigResolver;
        this.analyticsTracker = analyticsTracker;
        this.enabled = enabled;
    }

    /** [F3C-MODEL] 测试注入 · 会话主 AgentState 注册表（生产由 @Autowired 注入）。 */
    void setSessionAgentStateRegistry(SessionAgentStateRegistry registry) {
        this.sessionAgentStateRegistry = registry;
    }

    /**
     * 门控判定 · 对齐 CC {@code isPermissionExplainerEnabled()}
     * (permissionExplainer.ts:139-141) = {@code getGlobalConfig().permissionExplainerEnabled !== false}。
     *
     * @return true = 启用（默认）
     */
    public boolean isPermissionExplainerEnabled() {
        return enabled;
    }

    /**
     * 生成权限解释 · 对齐 CC {@code generatePermissionExplanation}（permissionExplainer.ts:147-250）。
     *
     * <p>一切失败返回 null（无降级文案）。CC 入参 {@code {toolName, toolInput,
     * toolDescription?, messages?, signal}}，Java 以显式参数表达；signal 以 {@link AbortController}
     * 承载（可 null = 无中断能力）。sessionId 为 Java 特有入参（CC 单进程全局 {@code STATE}
     * 无需寻址），用于解析会话主循环模型源（{@link AgentState#currentModel}）。
     *
     * @param sessionId       会话 ID（short；解析会话主循环模型源；可 null = 无会话态 → 模型源解析失败）
     * @param toolName        工具名（如 "Bash" / "Write"）
     * @param toolInput       工具输入（已解析 JSON）
     * @param toolDescription 工具描述（可 null，CC toolDescription?）
     * @param messages        对话历史（可 null/空，CC messages?）
     * @param signal          取消信号（可 null）
     * @return 权限解释；门控关闭/解析失败/异常 → null
     */
    public PermissionExplanation generatePermissionExplanation(
            String sessionId,
            String toolName,
            JsonNode toolInput,
            String toolDescription,
            List<ChatMessageDto> messages,
            AbortController signal
    ) {
        // 门控关闭 → null（CC :155-157）
        if (!isPermissionExplainerEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("权限解释器已禁用，返回 null（对齐 CC isPermissionExplainerEnabled）");
            }
            return null;
        }

        final long startTime = System.currentTimeMillis();
        try {
            final String formattedInput = formatToolInput(toolInput);
            final String conversationContext = (messages != null && !messages.isEmpty())
                ? extractConversationContext(messages, MAX_CONTEXT_CHARS)
                : "";
            final String userPrompt =
                buildUserPrompt(toolName, toolDescription, formattedInput, conversationContext);

            // 模型源 = 会话主循环模型（CC getMainLoopModel() :175）
            final String modelName = resolveMainLoopModelName(sessionId);
            if (modelName == null) {
                log.warn("权限解释器: 主循环模型解析失败（无可用模型），返回 null（对齐 CC 无降级）");
                return null;
            }
            final ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
                log.warn("权限解释器: 模型 {} 配置解析失败/不可用，返回 null（对齐 CC 无降级）", modelName);
                return null;
            }
            final LlmProvider provider =
                providerFactory.getProvider(resolved.config(), resolved.providerType());

            // 强制结构化输出：tools + tool_choice（CC :178-186）
            // [AM-CC-20260825] provider 协议区分（同 classifier 修复）：Anthropic 支持强制 named
            //   tool_choice（CC 语义）；openai_compatible（deepseek）推理模式不支持（400）→ 不传
            //   （null → auto），tools 唯一=explain_command 模型只能选它。
            boolean isAnthropicProtocol = "anthropic".equals(resolved.providerType());
            final LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                List.of(),
                ExplainCommandToolSchema.buildToolsArray(),
                null,
                null,
                null,
                QUERY_SOURCE,
                signal,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                isAnthropicProtocol
                    ? LlmProvider.ChatRequestOptions.ToolChoice.tool(ExplainCommandToolSchema.TOOL_NAME)
                    : null
            );

            final AssistantMessage msg = provider.chatWithOptionsMessage(
                resolved.config(), modelName, SYSTEM_PROMPT, userPrompt, options);

            final long latencyMs = System.currentTimeMillis() - startTime;
            if (log.isDebugEnabled()) {
                log.debug("权限解释器: API 返回 {}ms, toolCalls={}", latencyMs,
                    msg != null && msg.toolCalls() != null ? msg.toolCalls().size() : 0);
            }

            // 找 tool_use 块（CC :194 content.find(c => c.type === 'tool_use')）
            final ToolUseBlock toolUseBlock = (msg != null && msg.toolCalls() != null
                && !msg.toolCalls().isEmpty()) ? msg.toolCalls().get(0) : null;
            if (toolUseBlock != null) {
                final PermissionExplanation explanation = parseToolUseInput(toolUseBlock.input());
                if (explanation != null) {
                    // [IMP-T REWORK] track(EventName,Map) 忽略 metadata → 迁移 logEvent + verified() 包装
                    //   （CC permissionExplainer.ts:209-211 tool_name 带 VERIFIED 标记）。
                    analyticsTracker.logEvent("tengu_permission_explainer_generated",
                        Map.of(
                            "tool_name", AnalyticsTracker.verified(sanitizeToolNameForAnalytics(toolName)),
                            "risk_level", explanation.riskLevel().numericValue(),
                            "latency_ms", latencyMs));
                    if (log.isDebugEnabled()) {
                        log.debug("权限解释器: {} 风险 for {} ({}ms)", explanation.riskLevel(),
                            toolName, latencyMs);
                    }
                    return explanation;
                }
            }

            // 无有效 tool_use / 四字段非法 → PARSE 错误 telemetry + null（CC :221-228）
            // [IMP-T REWORK] track → logEvent + verified()（CC permissionExplainer.ts:222-226）
            analyticsTracker.logEvent("tengu_permission_explainer_error",
                Map.of(
                    "tool_name", AnalyticsTracker.verified(sanitizeToolNameForAnalytics(toolName)),
                    "error_type", ERROR_TYPE_PARSE,
                    "latency_ms", latencyMs));
            if (log.isDebugEnabled()) {
                log.debug("权限解释器: 响应无可解析结构化输出，返回 null");
            }
            return null;
        } catch (Exception e) {
            final long latencyMs = System.currentTimeMillis() - startTime;
            // abort → 静默 null（CC :233-236，不记错误）
            if (signal != null && signal.isCancelled()) {
                if (log.isDebugEnabled()) {
                    log.debug("权限解释器: 请求已中止 for {}", toolName);
                }
                return null;
            }
            log.warn("权限解释器异常（返回 null，对齐 CC 无降级）", e);
            // CC :240-247 error_type = AbortError ? NETWORK : UNKNOWN；Java abort 已单独短路 → UNKNOWN
            // [IMP-T REWORK] track → logEvent + verified()（CC permissionExplainer.ts:240-246）
            analyticsTracker.logEvent("tengu_permission_explainer_error",
                Map.of(
                    "tool_name", AnalyticsTracker.verified(sanitizeToolNameForAnalytics(toolName)),
                    "error_type", ERROR_TYPE_UNKNOWN,
                    "latency_ms", latencyMs));
            return null;
        }
    }

    /**
     * 模型源解析 · 对齐 CC {@code getMainLoopModel()}（model.ts:92-98）五层链结果复用。
     *
     * <p><b>WHY（架构冲突规则七）</b>：CC explainer 用 {@code getMainLoopModel()}（permissionExplainer.ts:175），
     * 即主循环五层链（session override → startup --model → env ANTHROPIC_MODEL → settings.model →
     * built-in default，model.ts:61-78 每层 {@code isModelAllowed} 拒绝跳下层）的<b>解析结果</b>。
     * Java 侧该五层链已由 {@code LlmAgentLoop.getModelForCall} 完整覆盖并每轮落盘到
     * {@link AgentState#currentModel}（= CC {@code options.mainLoopModel}，query.ts:572）。故此处
     * <b>复用 currentModel</b> 而非重造第二份五层链（重造会造出与主循环分叉的第二份模型源，
     * 违规则七/十一）。旧实现走 {@code resolveFastModelName}（fast→main→fallback，等价 CC
     * {@code getSmallFastModel} 小快模型，model.ts:36-38）属错接，已删除。
     *
     * @param sessionId 会话 ID（short；可 null = 无会话态）
     * @return 会话主循环模型名；registry 未注入 / state 未注册 / currentModel 为空
     *         → null（对齐 CC 无降级语义）
     */
    private String resolveMainLoopModelName(String sessionId) {
        if (sessionAgentStateRegistry == null) {
            log.warn("权限解释器: SessionAgentStateRegistry 未注入，无法解析会话主循环模型（返回 null，对齐 CC 无降级）");
            return null;
        }
        AgentState state = sessionAgentStateRegistry.get(sessionId);
        String currentModel = state != null ? state.currentModel() : null;
        if (currentModel == null || currentModel.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("权限解释器: 会话主循环模型未落盘（sessionId={} AgentState.currentModel=null），"
                    + "返回 null（对齐 CC 无降级）", sessionId);
            }
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("权限解释器: 模型来源 = 会话主循环模型（sessionId={} currentModel={}，CC options.mainLoopModel）",
                sessionId, currentModel);
        }
        return currentModel;
    }

    /**
     * 工具名 telemetry 脱敏 · 对齐 CC {@code sanitizeToolNameForAnalytics}
     * （metadata.ts:70-77）：mcp__* → mcp_tool（PII 防护），内置工具名原样。
     *
     * <p>[IMP-7 · OPD-WF7-01-03 拍板] success/parse-error/error 三处 telemetry 的
     * {@code tool_name} 字段必须归一化（CC permissionExplainer.ts:210/222/240
     * {@code tool_name: sanitizeToolNameForAnalytics(toolName)}）。null → ""（CC toolName 恒 string）。
     *
     * @param toolName 原始工具名（可为 null）
     * @return 脱敏后名称（mcp__* → mcp_tool；null → ""）
     */
    private static String sanitizeToolNameForAnalytics(String toolName) {
        return McpServerToolSanitizer.sanitize(toolName == null ? "" : toolName);
    }

    /**
     * 格式化工具输入 · 对齐 CC {@code formatToolInput}（permissionExplainer.ts:86-95）。
     *
     * <p>string → 直传；非 string → {@code jsonStringify(input, null, 2)} 美化；抛错 → String(input)。
     * 无截断。
     */
    public static String formatToolInput(JsonNode input) {
        if (input == null) {
            return "";
        }
        if (input.isTextual()) {
            return input.asText();
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    /**
     * 提取对话上下文 · 对齐 CC {@code extractConversationContext}
     * (permissionExplainer.ts:102-133)。
     *
     * <p>最近 3 条 assistant 消息、仅文本、逐消息按剩余额度截断 + {@code '...'}、
     * {@code '\n\n'} 连接（oldest-first）。
     */
    public static String extractConversationContext(List<ChatMessageDto> messages, int maxChars) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        final List<ChatMessageDto> assistantMsgs = new ArrayList<>();
        for (ChatMessageDto m : messages) {
            if (m != null && m.role() == Role.assistant
                && m.content() != null && !m.content().isBlank()) {
                assistantMsgs.add(m);
            }
        }
        if (assistantMsgs.isEmpty()) {
            return "";
        }
        // 最近 3 条
        final int fromIdx = Math.max(0, assistantMsgs.size() - 3);
        final List<ChatMessageDto> recent = assistantMsgs.subList(fromIdx, assistantMsgs.size());

        // 反向（newest-first）逐条按剩余额度截断 + unshift（oldest-first 输出）
        final List<String> contextParts = new ArrayList<>();
        int totalChars = 0;
        for (int i = recent.size() - 1; i >= 0; i--) {
            final String text = recent.get(i).content();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (totalChars < maxChars) {
                final int remaining = maxChars - totalChars;
                final String truncated = text.length() > remaining
                    ? text.substring(0, remaining) + "..."
                    : text;
                contextParts.add(0, truncated);
                totalChars += truncated.length();
            }
        }
        return String.join("\n\n", contextParts);
    }

    /** 构建 userPrompt · 对齐 CC permissionExplainer.ts:167-173 模板字面量。 */
    static String buildUserPrompt(String toolName, String toolDescription,
                                  String formattedInput, String conversationContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool: ").append(toolName).append('\n');
        if (toolDescription != null && !toolDescription.isBlank()) {
            sb.append("Description: ").append(toolDescription).append('\n');
        }
        sb.append('\n');
        sb.append("Input:\n");
        sb.append(formattedInput).append('\n');
        if (conversationContext != null && !conversationContext.isBlank()) {
            sb.append("\nRecent conversation context:\n").append(conversationContext);
        }
        sb.append('\n');
        sb.append('\n');
        sb.append("Explain this command in context.");
        return sb.toString();
    }

    /**
     * 解析 tool_use 输入 → PermissionExplanation · 对齐 CC
     * {@code RiskAssessmentSchema.safeParse(toolUseBlock.input)}（permissionExplainer.ts:199）。
     *
     * <p>严格四字段：riskLevel 枚举精确匹配 + explanation/reasoning/risk 全非 null；
     * 任一缺失/非法 → null（CC safeParse 失败语义，无宽容解析）。
     */
    static PermissionExplanation parseToolUseInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        final RiskLevel riskLevel = parseRiskLevel(input.path("riskLevel").asText(null));
        if (riskLevel == null) {
            return null;
        }
        final String explanation = input.path("explanation").asText(null);
        if (explanation == null) {
            return null;
        }
        final String reasoning = input.path("reasoning").asText(null);
        if (reasoning == null) {
            return null;
        }
        final String risk = input.path("risk").asText(null);
        if (risk == null) {
            return null;
        }
        return new PermissionExplanation(riskLevel, explanation, reasoning, risk);
    }

    /** riskLevel 精确匹配 · 对齐 CC {@code z.enum(['LOW','MEDIUM','HIGH'])}（大小写敏感，无容错）。 */
    static RiskLevel parseRiskLevel(String text) {
        if (text == null) {
            return null;
        }
        return switch (text) {
            case "LOW" -> RiskLevel.LOW;
            case "MEDIUM" -> RiskLevel.MEDIUM;
            case "HIGH" -> RiskLevel.HIGH;
            default -> null;
        };
    }
}
