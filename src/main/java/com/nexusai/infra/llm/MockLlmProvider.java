package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Mock LLM Provider · 用于开发环境 / 演示 / E2E 测试 / 兜底（未配置 provider 时）。
 *
 * <p>行为：
 * <ul>
 *   <li>{@link #stream}：把固定回复切成 5-7 个 chunk，每 50ms 吐一个，最后完成。
 *       完全忽略 {@code ProviderConfig}（不需要 baseUrl / apiKey）。</li>
 *   <li><b>tool_calls 模拟（Phase 6·s02）</b>：按 user prompt 关键词触发：
 *     <ul>
 *       <li>含 "list" / "ls" / "files" → {@code bash("ls -la")}</li>
 *       <li>含 "read" + 词 → {@code read_file(path)}（启发式取 path）</li>
 *       <li>含 "find" / "glob" / "search" → {@code glob(pattern)}</li>
 *       <li>含 "write" / "create" → {@code write_file(path, content)}</li>
 *     </ul>
 *     第一次调返回 tool_calls（finishReason=tool_calls），后续根据 tool result 决定下一步。
 *   </li>
 *   <li>{@link #chat}：返回 "Mock reply to: " + userMessage</li>
 *   <li>{@link #type()} = "openai_compatible"</li>
 * </ul>
 *
 * <p>工具流程设计（对齐 CC query.ts + s02 README）：
 * <ol>
 *   <li>第一轮 turn：基于 user prompt 匹配 tool_call，模拟"决定调工具"</li>
 *   <li>第二轮 turn：看到 tool result 后，给出最终文字回复</li>
 *   <li>若 history 已含 tool result（role=tool），跳过 tool call 直接给文字</li>
 * </ol>
 *
 * <p>v1 是 fallback；v2 配置了真实 provider 时 LlmProviderFactory 会优先走 OpenAiSdkProvider。
 */
@Component
public class MockLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockLlmProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 模拟的输出 token 数（complete 事件里用） */
    public static final int MOCK_OUTPUT_TOKENS = 42;

    /**
     * 模拟 usage · [DEC-04] 默认 output=MOCK_OUTPUT_TOKENS, 其余 0.
     * 测试可经 {@link #setMockUsage} 注入真实 4 字段值, 验证 provider → AssistantMessage →
     * SubagentResult 全链 usage 透传 (对齐 CC message.usage).
     */
    private volatile AgentUsage mockUsage =
        new AgentUsage(0L, MOCK_OUTPUT_TOKENS, 0L, 0L, null, null, null);

    /** 注入模拟 usage（E3 测试用；null 时回落默认）。 */
    public void setMockUsage(AgentUsage usage) {
        this.mockUsage = usage != null ? usage
            : new AgentUsage(0L, MOCK_OUTPUT_TOKENS, 0L, 0L, null, null, null);
    }

    @Override
    public String type() {
        return "openai_compatible";
    }

    /**
     * [⊕C-1] blocks 唯一重载 · String systemPrompt 兼容路径已删除（发送契约数组态唯一）。
     * Mock 不消费 system 内容（原 9-arg 亦忽略）；null/空 blocks = 不发送 system 语义同真 provider。
     *
     * <p>[对抗核验 H13-GAP-4 v3] AbortController 硬中断：注册 abort listener → 置 aborted
     * 标志 + interrupt worker → worker 在 chunk 间检查 aborted, 中断后以
     * {@link CancellationException} 调 onError（替代 onComplete）。
     */
    @Override
    public void stream(ProviderConfig config,
                       String modelName,
                       List<SystemPromptBlock> systemPromptBlocks,
                       List<ChatMessageDto> history,
                       ArrayNode tools,
                       Integer maxOutputTokensOverride,
                       TaskBudgetParam taskBudget,
                       String effortValue,
                       String querySource,
                       Consumer<String> onChunk,
                       Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete,
                       Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback,
                       com.nexusai.application.agent.tool.AbortController abortController,
                       Consumer<Throwable> onError,
                       Runnable onComplete) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        AtomicReference<String> abortReason = new AtomicReference<>();
        if (abortController != null) {
            abortController.onCancel(ac -> {
                aborted.set(true);
                abortReason.set(ac.reason());
                Thread w = workerRef.get();
                if (w != null) {
                    w.interrupt();
                }
            });
        }
        doStream(history, tools, onChunk, onAssistantMessage, onError, onComplete,
            aborted, workerRef, abortReason);
    }

    /**
     * [CCJ-EXEC-08] 18-arg stream · 带 thinkingConfig 反射（测试可观测性）。
     *
     * <p>把 {@code [thinking=<type>]} 反射到最近一条 user 消息内容尾部（mock 回复回显
     * user 文本，故可观测），沿用 chatWithOptions :231-251 的选项反射先例。随后委托
     * 既有 15-arg stream（abort 硬中断语义保留）。
     */
    @Override
    public void stream(ProviderConfig config,
                       String modelName,
                       String systemPrompt,
                       List<ChatMessageDto> history,
                       ArrayNode tools,
                       Integer maxOutputTokensOverride,
                       TaskBudgetParam taskBudget,
                       String effortValue,
                       LlmProvider.ChatRequestOptions.ThinkingConfig thinkingConfig,
                       Consumer<String> onChunk,
                       Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete,
                       Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback,
                       com.nexusai.application.agent.tool.AbortController abortController,
                       Consumer<Throwable> onError,
                       Runnable onComplete) {
        List<ChatMessageDto> reflected = history;
        if (thinkingConfig != null) {
            reflected = new ArrayList<>(history == null ? List.of() : history);
            for (int i = reflected.size() - 1; i >= 0; i--) {
                ChatMessageDto m = reflected.get(i);
                if (m != null && m.role() != null && "user".equals(m.role().name())
                        && m.content() != null) {
                    reflected.set(i, new ChatMessageDto(
                        m.id(), m.sessionId(), m.role(), m.author(),
                        m.content() + " [thinking=" + thinkingConfig.type() + "]",
                        m.reasoning(), m.toolCalls(), m.finishReason(), m.inputTokens(),
                        m.outputTokens(), m.time(), m.createdAt(), m.toolCallId(),
                        m.assistantMessageId(), m.acceptFeedback(), m.contentBlocks(),
                        m.imagePasteIds(), m.structuredOutput(), m.isMeta(), m.isError())
                        // 合并后 ChatMessageDto record 增 usage 组件；20 参紧凑构造器默认
                        // sourceToolUseID/subtype/isApiErrorMessage/apiError/error/errorDetails=null
                        // （mock thinking 注入场景这些字段为 null），保留 sourceToolUseID 用 with 链
                        .withSourceToolUseID(m.sourceToolUseID()));
                    break;
                }
            }
        }
        // [merge-fix] ⊕C-1 blocks 唯一发送契约：String 兼容链已删（13-arg String 委托目标不在
        //   合并接口），systemPrompt 折为单 block（CacheScope.NULL = 不缓存，join 恒等）路由到
        //   blocks 抽象重载；maxOutputTokensOverride/taskBudget/effortValue 随行透传
        //   （thinkingConfig 反射已完成，无需再传）。
        stream(config, modelName,
            systemPrompt == null ? null : List.of(new SystemPromptBlock(systemPrompt, CacheScope.NULL)),
            reflected, tools, maxOutputTokensOverride, taskBudget, effortValue, null, /* querySource */
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            onStreamingFallback, abortController, onError, onComplete);
    }

    /**
     * 模拟流核心 · 在 worker 线程跑, chunk 间检查 aborted 标志。
     * aborted 触发 → onError(CancellationException) 终止（对齐 CC abort 硬中断）。
     */
    private void doStream(List<ChatMessageDto> history,
                          ArrayNode tools,
                          Consumer<String> onChunk,
                          Consumer<AssistantMessage> onAssistantMessage,
                          Consumer<Throwable> onError,
                          Runnable onComplete,
                          AtomicBoolean aborted,
                          AtomicReference<Thread> workerRef,
                          AtomicReference<String> abortReason) {
        if (tools != null && !tools.isEmpty()) {
            log.debug("MockLlmProvider: tools available: {}", tools.size());
        }
        // 在新线程里跑模拟流 —— 不阻塞调用方（ChatService 已经 @Async，这里再加一层线程安全）
        Thread worker = new Thread(() -> {
            try {
                String userText = lastUserText(history);

                // 检查 history 里是否已有 tool result（如果有 → 给出最终文字回复）
                boolean hasToolResult = history != null && history.stream()
                    .anyMatch(m -> m.role() != null && "tool".equals(m.role().name()));

                // 检查 tools 是否可用
                boolean toolsAvailable = tools != null && !tools.isEmpty();
                String[] toolNames = toolsAvailable ? extractToolNames(tools) : new String[0];

                // 决策：调工具 / 纯文本
                ToolUseBlock toolCall = (toolsAvailable && !hasToolResult)
                    ? matchToolCall(userText, toolNames)
                    : null;

                if (toolCall != null) {
                    // --- turn 1: 返回 tool_calls ---
                    String preamble = pickPreamble(userText);
                    String[] preambleChunks = splitForStream(preamble);
                    StringBuilder acc = new StringBuilder();
                    for (String c : preambleChunks) {
                        sleep(50);
                        if (aborted.get()) {
                            finishAborted(onError, abortReason);
                            return;
                        }
                        onChunk.accept(c);
                        acc.append(c);
                    }
                    List<ToolUseBlock> calls = new ArrayList<>();
                    calls.add(toolCall);
                    if (onAssistantMessage != null) {
                        onAssistantMessage.accept(new AssistantMessage(
                            acc.toString(), "tool_calls", calls, "", null, mockUsage));
                    }
                    onComplete.run();
                } else {
                    // --- 纯文本回复 ---
                    String[] chunks = buildTextChunks(userText, hasToolResult);
                    StringBuilder acc = new StringBuilder();
                    for (String chunk : chunks) {
                        sleep(50);
                        if (aborted.get()) {
                            finishAborted(onError, abortReason);
                            return;
                        }
                        onChunk.accept(chunk);
                        acc.append(chunk);
                    }
                    if (onAssistantMessage != null) {
                        onAssistantMessage.accept(new AssistantMessage(
                            acc.toString(), "stop", List.of(), "", null, mockUsage));
                    }
                    onComplete.run();
                }
            } catch (Throwable t) {
                // 中断（abort interrupt worker）→ CancellationException, 非普通错误
                if (aborted.get() || t instanceof InterruptedException) {
                    finishAborted(onError, abortReason);
                    return;
                }
                log.error("MockLlmProvider stream failed", t);
                onError.accept(t);
            }
        }, "mock-llm-stream");
        worker.setDaemon(true);
        if (workerRef != null) {
            workerRef.set(worker);
        }
        worker.start();
    }

    /** abort 路径统一出口 · 对齐 CC AbortSignal abort → onError(CancellationException, reason 透传). */
    private static void finishAborted(Consumer<Throwable> onError, AtomicReference<String> abortReason) {
        try {
            String reason = abortReason != null ? abortReason.get() : null;
            onError.accept(new java.util.concurrent.CancellationException(
                reason != null ? reason : "mock stream aborted"));
        } catch (Throwable ignore) {
            // 回调异常不传播
        }
    }

    @Override
    public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
        String prefix = "Mock reply to: ";
        String body = (userMessage == null) ? "" : userMessage;
        if (body.length() > 200) body = body.substring(0, 200) + "...";
        return prefix + body;
    }

    /**
     * [对抗核验 H13-GAP-3 v3 + P2-16] 带选项 chat · 反射 options 到回复尾部, 供测试验证选项是否到达
     * provider（对齐 CC queryModelWithoutStreaming 的 tools/outputFormat/thinkingConfig/
     * temperatureOverride/querySource/signal 透传）。
     * 生产语义: mock 不真正请求 LLM, 选项反射是测试可观测性的唯一载体。
     */
    @Override
    public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                  String userMessage, LlmProvider.ChatRequestOptions options) {
        StringBuilder sb = new StringBuilder(chat(config, modelName, systemPrompt, userMessage));
        if (options != null) {
            if (options.outputFormat() != null) {
                sb.append(" [outputFormat=").append(options.outputFormat().type()).append("]");
            }
            if (options.thinkingConfig() != null) {
                sb.append(" [thinking=").append(options.thinkingConfig().type()).append("]");
            }
            if (options.tools() != null && !options.tools().isEmpty()) {
                sb.append(" [tools=").append(options.tools().size()).append("]");
            }
            if (options.history() != null && !options.history().isEmpty()) {
                sb.append(" [history=").append(options.history().size()).append("]");
            }
            // [P2-16] 侧信道 options 反射 — temperature / querySource / abort 可观测性
            //   (对齐现有 outputFormat/thinkingConfig 反射先例; skillImprovement.ts:245/247/238)
            if (options.temperature() != null) {
                sb.append(" [temperature=").append(options.temperature()).append("]");
            }
            if (options.querySource() != null) {
                sb.append(" [querySource=").append(options.querySource()).append("]");
            }
            if (options.skipCacheWrite() != null) {
                sb.append(" [skipCacheWrite=").append(options.skipCacheWrite()).append("]");
            }
            if (options.abortController() != null) {
                sb.append(" [abort=")
                  .append(options.abortController().isCancelled() ? "cancelled" : "active")
                  .append("]");
            }
            if (options.maxTokens() != null) {
                sb.append(" [maxTokens=").append(options.maxTokens()).append("]");
            }
            // [WF3-04 explainer] tool_choice 反射（对齐 CC options.tool_choice 透传可观测性）
            if (options.toolChoice() != null) {
                sb.append(" [toolChoice=").append(options.toolChoice().name()).append("]");
            }
        }
        return sb.toString();
    }

    /**
     * [WF3-04 explainer] 带选项 chat · 返回完整 AssistantMessage。mock 不真正请求 LLM，
     * 强制 tool_choice 时产出 explain_command tool_use 块（四字段），供 explainer 集成测试观测。
     */
    @Override
    public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                   String systemPrompt, String userMessage,
                                                   LlmProvider.ChatRequestOptions options) {
        String content = chatWithOptions(config, modelName, systemPrompt, userMessage, options);
        if (options != null && options.toolChoice() != null) {
            ObjectNode input = JSON.createObjectNode();
            input.put("riskLevel", "LOW");
            input.put("explanation", "Mock explanation");
            input.put("reasoning", "I need to check the file contents");
            input.put("risk", "No significant risk");
            return new AssistantMessage(content, "tool_calls",
                List.of(new ToolUseBlock("toolu_mock_explain", options.toolChoice().name(), input)),
                "");
        }
        return new AssistantMessage(content, "stop", List.of(), "");
    }

    // ============== helper ==============

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("MockLlmProvider stream interrupted");
            throw new RuntimeException("interrupted", ie);
        }
    }

    private static String lastUserText(List<ChatMessageDto> history) {
        if (history == null || history.isEmpty()) return "";
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDto m = history.get(i);
            if (m != null && m.role() != null && "user".equals(m.role().name())
                && m.content() != null) {
                return m.content();
            }
        }
        return "";
    }

    private static String[] extractToolNames(ArrayNode tools) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            JsonNode tool = tools.get(i);
            JsonNode fn = tool.path("function");
            if (!fn.isMissingNode() && fn.has("name")) {
                names.add(fn.get("name").asText());
            } else if (tool.has("name")) {
                names.add(tool.get("name").asText());
            }
        }
        return names.toArray(new String[0]);
    }

    /**
     * 按 user prompt 关键词匹配 → ToolUseBlock。
     * 返回 null 表示不调工具（走纯文本分支）。
     */
    private static ToolUseBlock matchToolCall(String userText, String[] toolNames) {
        String lower = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        if (lower.isBlank()) return null;

        // 准备可用的工具名集合（用于查表）
        java.util.Set<String> available = new java.util.HashSet<>();
        for (String n : toolNames) available.add(n);

        // 0) "task" + create/refactor 关键词 → TaskCreate 模拟 tool_use
        // s12-3.1: MockLlmProvider 触发 TaskCreate，验证 task 工具在 LLM 回路中可达
        // 注意：即使 TaskCreate 不在 tools 列表中，mock 也返回 TaskCreate tool_use
        // （模拟真实 LLM 调用 TaskCreate，tool result 由后续回合执行）
        if (lower.contains("task") && (lower.contains("create") || lower.contains("refactor")
                || lower.contains("todo") || lower.contains("plan"))) {
            String subject = extractPhrase(userText, "task", "refactor the API");
            String desc = userText.length() > 200 ? userText.substring(0, 200) : userText;
            return makeTaskCreateCall(subject, desc);
        }

        // 1) list / ls / files → Bash("ls -la")（工具名对齐 CC 'Bash'）
        if ((lower.contains("list") || lower.contains("ls") || lower.contains("files")
                || lower.contains("目录") || lower.contains("文件"))
            && available.contains("Bash")) {
            return makeToolCall("Bash", "command", "ls -la");
        }

        // 2) find / glob / search → Glob(...)（B2 后主名对齐 CC 'Glob'）
        if ((lower.startsWith("find") || lower.contains("glob")
                || lower.contains("search") || lower.contains("查找") || lower.contains("搜索"))
            && available.contains("Glob")) {
            String pattern = extractQuotedOrWord(userText, "*.java");
            return makeToolCall("Glob", "pattern", pattern);
        }

        // 3) read + path → Read(...)（B2 后主名对齐 CC 'Read'）
        if ((lower.contains("read") || lower.contains("读取") || lower.contains("cat "))
            && available.contains("Read")) {
            String path = extractPath(userText, "pom.xml");
            return makeToolCall("Read", "file_path", path);
        }

        // 4) write / create → Write(...)（B2 后主名对齐 CC 'Write'）
        if ((lower.contains("write") || lower.contains("create") || lower.contains("写"))
            && available.contains("Write")) {
            String path = extractPath(userText, "demo.txt");
            return makeToolCall("Write", "path", path, "content", "Hello from NexusAI mock LLM");
        }

        return null;
    }

    /**
     * 提取包含关键词的短语作为 task subject · s12-3.1
     */
    private static String extractPhrase(String text, String keyword, String fallback) {
        if (text == null) return fallback;
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(keyword);
        if (idx >= 0) {
            int end = Math.min(idx + 80, text.length());
            // 尝试在语义边界截断（句号、换行）
            String snippet = text.substring(idx, end);
            int cut = snippet.indexOf('.');
            if (cut > 0) snippet = snippet.substring(0, cut + 1);
            cut = snippet.indexOf('\n');
            if (cut > 0) snippet = snippet.substring(0, cut);
            return snippet.trim();
        }
        return fallback;
    }

    /** 创建 TaskCreate 工具调用 · s12-3.1 */
    private static ToolUseBlock makeTaskCreateCall(String subject, String description) {
        ObjectNode input = JSON.createObjectNode();
        input.put("subject", subject);
        input.put("description", description);
        return new ToolUseBlock("toolu_mock_tc_" + System.nanoTime(), "TaskCreate", input);
    }

    private static ToolUseBlock makeToolCall(String name, String... kvPairs) {
        ObjectNode input = JSON.createObjectNode();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            input.put(kvPairs[i], kvPairs[i + 1]);
        }
        return new ToolUseBlock("toolu_mock_" + System.nanoTime(), name, input);
    }

    private static String extractQuotedOrWord(String text, String fallback) {
        if (text == null) return fallback;
        // 尝试引号
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\"'`]([^\"'`]+)[\"'`]")
            .matcher(text);
        if (m.find()) return m.group(1);
        // 尝试 .ext 模式
        m = java.util.regex.Pattern.compile("\\S+\\.[a-zA-Z0-9]+").matcher(text);
        if (m.find()) return m.group();
        return fallback;
    }

    private static String extractPath(String text, String fallback) {
        if (text == null) return fallback;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\w./-]+\\.[a-zA-Z0-9]+)")
            .matcher(text);
        if (m.find()) return m.group(1);
        return fallback;
    }

    private static String pickPreamble(String userText) {
        if (userText == null || userText.isBlank()) {
            return "Let me check that for you. ";
        }
        String preview = userText.length() > 40 ? userText.substring(0, 40) + "..." : userText;
        return "I'll look into: \"" + preview + "\". ";
    }

    private static String[] splitForStream(String s) {
        // 切成 ~12 字符一段
        if (s == null || s.isEmpty()) return new String[]{""};
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(i + 12, s.length());
            parts.add(s.substring(i, end));
            i = end;
        }
        return parts.toArray(new String[0]);
    }

    private static String[] buildTextChunks(String userText, boolean hadToolResult) {
        if (hadToolResult) {
            // 第二轮 turn：基于 tool 结果给出总结
            String[] parts = {
                "Got the results. ",
                "Here's what I found",
                " from the tool call — ",
                "this is the mock final response (no real LLM in fallback mode).",
                " Real OpenAI provider would interpret the tool result here."
            };
            return parts;
        }
        if (userText != null && !userText.isBlank()) {
            String preview = userText;
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
            return new String[] {
                "This is ",
                "a **mock** ",
                "response from LLM. ",
                "You said: \"",
                preview,
                "\" — ",
                "(no tool triggered by keywords, plain text reply)."
            };
        }
        return new String[] {
            "This is ",
            "a **mock** ",
            "response from LLM. ",
            "I received your message"
        };
    }
}
