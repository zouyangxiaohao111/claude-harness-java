package com.nexusai.application.agent.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * [H7-arch Phase 5 P4 C7] 默认 Haiku 实现 · 对齐 CC {@code generateToolUseSummary}
 * (services/toolUseSummary/toolUseSummaryGenerator.ts:45-97)。
 *
 * <p><b>WHY</b>: CC 用 {@code queryHaiku} 快模型生成工具使用总结（fire-and-forget，
 * 主链不等）。Java 端复用 {@link LlmProviderFactory#chatWithOptions}（与
 * {@code LlmAgentLoop.triggerSkillCatalogHaikuSummaryAsync} 同模式）。失败/无 provider →
 * 返回 completedFuture(null)，绝不阻塞主链。
 *
 * <p><b>[W9-01 OPD-TS-29] bean 注册</b>: {@code @Component} 生产注册（构造参数
 * LlmProviderFactory + ModelConfigResolver 均为 Spring bean），解除
 * {@code AgentLoopContextFactory:101 @Autowired(required=false)} 双闸死锁（此前全仓无
 * {@code @Bean} / {@code new}，生产 bean 恒 null → gate 恒不触发）。
 *
 * <p><b>[W9-01 OPD-TS-29] options 承载</b>: {@code chatWithOptions} 携带 CC queryHaiku
 * options（toolUseSummaryGenerator.ts:73-80）：querySource='tool_use_summary_generation' /
 * enablePromptCaching=true / agents=[] / hasAppendSystemPrompt=false / mcpTools=[] /
 * isNonInteractiveSession 透传；thinkingConfig=disabled（queryHaiku.ts:3262 强制）。
 * enablePromptCaching 由 AnthropicSdkProvider.buildMessageParams 覆盖模型级 caching gate
 * （CC claude.ts:1374-1375）。
 *
 * <p><b>CC 契约对齐</b>:
 * <ul>
 *   <li>tools.length == 0 → null (toolUseSummaryGenerator.ts:51-53)</li>
 *   <li>buildToolSummaries: {@code Tool: name\nInput: truncateJson(input,300)\nOutput: truncateJson(output,300)}
 *       工具间空行连接 (:57-63)</li>
 *   <li>truncateJson: JSON 序列化 → ≤300 原样 / 超长 slice(0,297)+"..." / 序列化失败
 *       "[unable to serialize]"；null → "null"（jsonStringify(null)="null"，:102-112）</li>
 *   <li>contextPrefix: {@code User's intent (from assistant's last message): lastAssistantText.slice(0,200)} (:65-67)</li>
 *   <li>precedingToolUseIds = toolUseBlocks.map(id)（query.ts:1437）+ createToolUseSummaryMessage
 *       (messages.ts:5105-5116)</li>
 *   <li>失败 catch → null (非关键路径, 日志不刷屏) (:90-96)</li>
 * </ul>
 */
@Component
public class HaikuToolUseSummaryGenerator implements ToolUseSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(HaikuToolUseSummaryGenerator.class);

    /**
     * [W9-01] CC TOOL_USE_SUMMARY_SYSTEM_PROMPT 原文（toolUseSummaryGenerator.ts:15-24）：
     * git-commit-subject 风格 10 行规则 + 5 示例。替换旧 1 行 "You summarize tool executions..."。
     */
    private static final String SYSTEM_PROMPT = """
        Write a short summary label describing what these tool calls accomplished. It appears as a single-line row in a mobile app and truncates around 30 characters, so think git-commit-subject, not sentence.

        Keep the verb in past tense and the most distinctive noun. Drop articles, connectors, and long location context first.

        Examples:
        - Searched in auth/
        - Fixed NPE in UserService
        - Created signup endpoint
        - Read config.json
        - Ran failing tests""";

    /** CC truncateJson 用的 JSON 序列化器（slowOperations.ts:170-185 jsonStringify = JSON.stringify）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC truncateJson(value, 300) maxLength。 */
    private static final int MAX_TOOL_INPUT_OUTPUT_CHARS = 300;
    /** CC lastAssistantText.slice(0, 200)（toolUseSummaryGenerator.ts:66）。 */
    private static final int MAX_CONTEXT_CHARS = 200;
    /** CC queryHaiku querySource（toolUseSummaryGenerator.ts:74）。 */
    private static final String QUERY_SOURCE = "tool_use_summary_generation";

    private final LlmProviderFactory llmProviderFactory;
    /** [RV14B-WIRE-04] 共享配置解析器 · Haiku 模型名 → 真实 (config, providerType)（null → warn+skip 不落 mock）。 */
    private final ModelConfigResolver modelConfigResolver;

    public HaikuToolUseSummaryGenerator(LlmProviderFactory llmProviderFactory) {
        this(llmProviderFactory, null);
    }

    @Autowired
    public HaikuToolUseSummaryGenerator(LlmProviderFactory llmProviderFactory,
                                        ModelConfigResolver modelConfigResolver) {
        this.llmProviderFactory = llmProviderFactory;
        this.modelConfigResolver = modelConfigResolver;
    }

    @Override
    public CompletableFuture<AttachmentMessageDto> generateToolUseSummaryAsync(
            AgentState state,
            List<ToolUseBlock> toolUseBlocks,
            List<ChatMessageDto> messagesWithToolResults,
            String lastAssistantText,
            boolean isNonInteractiveSession) {
        // 对齐 CC toolUseSummaryGenerator.ts:51-53 tools.length === 0 → null
        if (toolUseBlocks == null || toolUseBlocks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String summary = callHaiku(toolUseBlocks, messagesWithToolResults, lastAssistantText,
                    isNonInteractiveSession);
                if (summary == null || summary.isBlank()) {
                    return null;
                }
                // 对齐 CC query.ts:1437 toolUseIds = toolUseBlocks.map(block => block.id) +
                // createToolUseSummaryMessage(summary, toolUseIds) (messages.ts:5105-5116)
                List<String> precedingToolUseIds = toolUseBlocks.stream().map(ToolUseBlock::id).toList();
                AttachmentMessageDto attachment = new AttachmentMessageDto(
                    null, "attachment", "tool_use_summary", summary, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, 0, false, null, null, null, false, false, null,
                    null, null, null, precedingToolUseIds);
                if (log.isInfoEnabled()) {
                    log.info("[C7 tool_use_summary] Haiku 摘要生成成功: chars={} precedingToolUseIds={} · CC messages.ts:5105-5116",
                        summary.length(), precedingToolUseIds.size());
                }
                return attachment;
            } catch (Exception e) {
                // 对齐 CC :90-96 catch → null: 摘要非关键路径, 失败静默降级
                log.warn("[C7 tool_use_summary] Haiku 生成失败, 降级 null: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * [W9-01] 同步调用 Haiku · 对齐 CC {@code queryHaiku}（toolUseSummaryGenerator.ts:69-81）。
     * 经 {@code chatWithOptions} 承载 CC options；旧 4 参 {@code chat()} 不携带 options 已弃用。
     */
    private String callHaiku(List<ToolUseBlock> toolUseBlocks,
                             List<ChatMessageDto> messagesWithToolResults,
                             String lastAssistantText,
                             boolean isNonInteractiveSession) {
        if (llmProviderFactory == null) {
            return null;
        }
        // 对齐 CC buildToolSummaries: Tool: name\nInput: truncateJson(input,300)\nOutput: truncateJson(output,300)
        // （:57-63，工具间空行连接——CC map().join('\n\n')）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolUseBlocks.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            ToolUseBlock block = toolUseBlocks.get(i);
            String output = findToolResult(block.id(), messagesWithToolResults);
            sb.append("Tool: ").append(block.name()).append('\n');
            sb.append("Input: ").append(truncateJson(block.input(), MAX_TOOL_INPUT_OUTPUT_CHARS)).append('\n');
            sb.append("Output: ").append(truncateJson(output, MAX_TOOL_INPUT_OUTPUT_CHARS));
        }
        // 对齐 CC :65-67 contextPrefix = "User's intent (from assistant's last message): " +
        //   lastAssistantText.slice(0,200) + "\n\n"
        String contextPrefix = (lastAssistantText != null && !lastAssistantText.isBlank())
            ? "User's intent (from assistant's last message): "
                + (lastAssistantText.length() > MAX_CONTEXT_CHARS
                    ? lastAssistantText.substring(0, MAX_CONTEXT_CHARS) : lastAssistantText) + "\n\n"
            : "";
        // 对齐 CC :71 userPrompt = contextPrefix + "Tools completed:\n\n" + toolSummaries + "\n\nLabel:"
        String userPrompt = contextPrefix + "Tools completed:\n\n" + sb + "\n\nLabel:";

        // [RV14B-WIRE-04] 真实配置解析：fast 模型名 → DB 名 → (config, providerType)；
        //   解析失败 → 返回 null（warn+skip 不落 mock，对齐 CC queryHaiku 失败即无结果）。
        ModelConfigResolver.ResolvedModel resolved = resolveHaikuModelConfig();
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("[C7 tool_use_summary] 模型配置解析失败，跳过（warn+skip 不落 mock，RV14B-GATE-01）");
            return null;
        }
        String modelName = resolveHaikuModelName();
        try {
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                List.of(),   // history — CC queryHaiku messages=[userPrompt] 单条（Java 经 userMessage 表达）
                null,        // tools — CC queryHaiku.ts:3264 tools:[]
                null,        // outputFormat — 未设
                LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(), // CC queryHaiku.ts:3262 thinkingConfig:{type:'disabled'}
                null,        // temperature — 未设
                QUERY_SOURCE, // CC toolUseSummaryGenerator.ts:74 querySource:'tool_use_summary_generation'
                null,        // abortController — Java fire-and-forget 无 signal 通道
                null,        // maxTokens — 未设（provider 回落模型缺省）
                null,        // skipCacheWrite — CC 未设（= false，写 cache）
                Boolean.TRUE, // CC :75 enablePromptCaching: true
                List.of(),   // CC :76 agents: []
                Boolean.FALSE, // CC :78 hasAppendSystemPrompt: false
                List.of(),   // CC :79 mcpTools: []
                isNonInteractiveSession); // CC :77 isNonInteractiveSession 透传
            return llmProviderFactory.getProvider(resolved.config(), resolved.providerType()).chatWithOptions(
                resolved.config(),
                modelName,
                SYSTEM_PROMPT,
                userPrompt,
                options
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * [RV14B-WIRE-04] 解析 Haiku 模型真实配置 · fast 模型名 → DB 名 → (config, providerType)。
     *
     * <p>对齐 CC claude.ts:3278 {@code queryHaiku({ model: getSmallFastModel() })} +
     * model.ts:36-37 {@code getSmallFastModel() = ANTHROPIC_SMALL_FAST_MODEL || getDefaultHaikuModel()}。
     * 解析失败 → null → 调用方返回 null（warn+skip）。
     *
     * @return 真实 (config, providerType)；解析失败 / resolver 未注入 → null
     */
    private ModelConfigResolver.ResolvedModel resolveHaikuModelConfig() {
        if (modelConfigResolver == null) {
            log.warn("[C7 tool_use_summary] ModelConfigResolver 未注入，跳过 Haiku 配置解析（warn+skip 不落 mock）");
            return null;
        }
        String modelName = resolveHaikuModelName();
        if (modelName == null || modelName.isBlank()) return null;
        return modelConfigResolver.resolve(modelName);
    }

    /**
     * [RV14B-WIRE-04] 解析 Haiku 模型 DB 名。
     *
     * @return DB 可用 fast 模型名；resolver 未注入 → fallback 字面量（测试兜底）
     */
    private String resolveHaikuModelName() {
        if (modelConfigResolver == null) return "claude-haiku-4-5-20251001";
        String fastName = modelConfigResolver.resolveFastModelName("claude-haiku-4-5-20251001");
        return fastName != null && !fastName.isBlank() ? fastName : "claude-haiku-4-5-20251001";
    }

    /** 从 messagesWithToolResults 中找该 tool_use_id 对应的 tool_result content。 */
    private static String findToolResult(String toolUseId, List<ChatMessageDto> messages) {
        if (messages == null) return null;
        for (ChatMessageDto m : messages) {
            if (m.role() == Role.tool && toolUseId.equals(m.toolCallId())) {
                return m.content();
            }
        }
        return null;
    }

    /**
     * [W9-01] 对齐 CC truncateJson(value, maxLength)（toolUseSummaryGenerator.ts:102-112）：
     * <ul>
     *   <li>JSON 序列化（CC jsonStringify = JSON.stringify，slowOperations.ts:170-185）</li>
     *   <li>≤300 原样 / 超长 slice(0,297)+"..."</li>
     *   <li>null → JSON.stringify(null) = "null"（旧实现返回 "[unable to serialize]" 已修正）</li>
     *   <li>序列化失败（循环引用等）→ "[unable to serialize]"</li>
     * </ul>
     *
     * @param value 任意值（input 为 JsonNode；output 为 tool_result content String）
     */
    private static String truncateJson(Object value, int maxLength) {
        try {
            String str = JSON.writeValueAsString(value);
            if (str.length() <= maxLength) {
                return str;
            }
            return str.substring(0, maxLength - 3) + "...";
        } catch (Exception e) {
            return "[unable to serialize]";
        }
    }
}
