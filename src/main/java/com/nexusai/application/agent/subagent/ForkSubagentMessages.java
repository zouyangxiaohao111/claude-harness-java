package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ForkSubagentMessages · 对齐 CC Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:107-169 buildForkedMessages.
 *
 * <p><b>L1 语义</b>: fork 子 agent 的对话构造——缓存共享关键路径。
 * 为保证所有 fork children 的 prompt cache prefix 字节级一致, 必须:
 * <ol>
 *   <li>克隆父 assistantMessage(分配新 uuid, 保留全部 content blocks),防止 transcript 渲染冲突</li>
 *   <li>为每一个 tool_use 块生成相同 placeholder 的 tool_result 块</li>
 *   <li>追加 1 个 text block(由 {@link ForkChildBoilerplate#buildChildMessage} 生成,含 directive)</li>
 * </ol>
 *
 * <p><b>L2 契约 (3 Release Gate)</b>:
 * <ul>
 *   <li><b>B1</b>: tool_result 数量 == tool_use 数量, tool_use_id 一一对应</li>
 *   <li><b>B2</b>: 所有 tool_result placeholder 文本 == "Fork started — processing in background"
 *       (CC forkSubagent.ts:93 FORK_PLACEHOLDER_RESULT 常量)</li>
 *   <li><b>B3 边界</b>: 无 tool_use → 仅返回 1 条 user message(不克隆 assistant)</li>
 * </ul>
 *
 * <p><b>L3 (Java idiom)</b>: TS discriminated union (BetaToolUseBlock / BetaToolResultBlock /
 * BetaTextBlock) → Java sealed interface {@link ContentBlock} + record 实现。{@code AssistantMessage}
 * / {@code UserMessage} 实现 {@link Message}。本地 record 默认 {@code @JsonIgnore}
 * (CC 兼容壳教训:BudgetTracker 不应序列化)。所有字段 JavaDoc 标注 CC 原 snake_case + 行号。
 */
public final class ForkSubagentMessages {

    /** CC forkSubagent.ts:93 FORK_PLACEHOLDER_RESULT — 所有 fork child 必须 byte-identical。 */
    public static final String FORK_PLACEHOLDER_RESULT = "Fork started — processing in background";

    private static final Logger log = LoggerFactory.getLogger(ForkSubagentMessages.class);

    private ForkSubagentMessages() {}

    // ────────────────────────────────────────────────────────────────────────────
    // 本地 records (Java 端 fork 消息类型, 不复用 com.nexusai.infra.llm.AssistantMessage
    // 因为 CC 端 AssistantMessage 是 Anthropic SDK 风格的 BetaBlock[], 而 Java 现有
    // AssistantMessage 是 OpenAI 风格的 streaming 累积器, 两者契约错位 —
    // 按"对齐 CC 不复用现有 record 改造"纪律, 全部新增本地 record)。
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * CC Anthropic SDK BetaBlock 的 Java discriminated union 等价物 · 三种 block 类型:
     * <ul>
     *   <li>{@link BetaToolUseBlock} · type="tool_use" · LLM 发出的工具调用</li>
     *   <li>{@link BetaToolResultBlock} · type="tool_result" · 工具执行结果回传(本类内部构造)</li>
     *   <li>{@link BetaTextBlock} · type="text" · 纯文本块(thinking 或 user 指令)</li>
     * </ul>
     */
    public sealed interface ContentBlock permits BetaToolUseBlock, BetaToolResultBlock, BetaTextBlock {}

    /** CC Open-ClaudeCode/src/types/message.ts AssistantMessage / UserMessage 顶级抽象。 */
    public sealed interface Message permits AssistantMessage, UserMessage {}

    /**
     * CC Open-ClaudeCode/src/types/message.ts AssistantMessage (BetaBlock[]) — Java 端本地等价。
     * <p>{@code @JsonIgnore} 标在 record component 上, 防 Jackson 默认序列化(对齐 BUDGET_TRACKER 教训:
     * 本地状态不应被序列化到外部通道 — fork 消息前缀是 LLM API 构造中间产物, 非 DTO)。
     *
     * @param uuid    CC original: uuid (Open-ClaudeCode/src/types/message.ts AssistantMessage.uuid)
     * @param content CC original: message.content (Open-ClaudeCode/src/types/message.ts AssistantMessage.message.content)
     * @param requestId [RF-1] CC original: requestId (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:723/:778
     *                {@code invokingRequestId: assistantMessage?.requestId}) — 父 assistant message 的
     *                API request_id, 透传到子 agent 上下文做 analytics 归因; null = 未透传
     *                (非 fork / 流式 provider 未捕获 request_id / resume 无父 request_id)
     */
    public record AssistantMessage(@JsonIgnore String uuid, @JsonIgnore List<ContentBlock> content,
                                   @JsonIgnore String requestId)
        implements Message {

        public AssistantMessage {
            if (uuid == null) uuid = "";
            if (content == null) content = List.of();
        }

        /** 兼容 2 参构造（非 fork / 测试 / 无 request_id 路径）· CC assistantMessage.requestId 可为 undefined. */
        public AssistantMessage(String uuid, List<ContentBlock> content) {
            this(uuid, content, null);
        }
    }

    /**
     * CC Open-ClaudeCode/src/types/message.ts UserMessage (BetaBlock[]) — fork 路径合成。
     *
     * @param content CC original: message.content (Open-ClaudeCode/src/types/message.ts UserMessage.message.content)
     */
    public record UserMessage(@JsonIgnore List<ContentBlock> content) implements Message {

        public UserMessage {
            if (content == null) content = List.of();
        }
    }

    /**
     * CC Anthropic SDK BetaToolUseBlock — LLM 发出的工具调用。
     *
     * @param id    CC original: id (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:142 block.id)
     * @param name  CC original: name (Anthropic SDK BetaToolUseBlock.name)
     * @param input CC original: input (Anthropic SDK BetaToolUseBlock.input)
     */
    public record BetaToolUseBlock(@JsonIgnore String id, @JsonIgnore String name, @JsonIgnore JsonNode input)
        implements ContentBlock {

        public BetaToolUseBlock {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("BetaToolUseBlock.id is blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("BetaToolUseBlock.name is blank");
            }
            if (input == null) {
                throw new IllegalArgumentException("BetaToolUseBlock.input is null");
            }
        }
    }

    /**
     * CC Anthropic SDK BetaToolResultBlock — 工具执行结果回传(fork 路径合成)。
     *
     * @param toolUseId CC original: tool_use_id (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:144)
     * @param content   CC original: content (Anthropic SDK BetaToolResultBlock.content — 数组含 text 块)
     */
    public record BetaToolResultBlock(@JsonIgnore String toolUseId, @JsonIgnore List<BetaTextBlock> content)
        implements ContentBlock {

        public BetaToolResultBlock {
            if (toolUseId == null || toolUseId.isBlank()) {
                throw new IllegalArgumentException("BetaToolResultBlock.toolUseId is blank");
            }
            if (content == null) content = List.of();
        }
    }

    /**
     * CC Anthropic SDK BetaTextBlock — 纯文本块(thinking / user 指令)。
     *
     * @param text CC original: text (Anthropic SDK BetaTextBlock.text)
     */
    public record BetaTextBlock(@JsonIgnore String text) implements ContentBlock {

        public BetaTextBlock {
            if (text == null) text = "";
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 主函数: buildForkedMessages
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * 构造 fork child 对话前缀 · 对齐 CC forkSubagent.ts:107-169.
     *
     * <p>WHY 缓存共享: fork 路径下, 多个 child 共享父 history prefix 触发 Anthropic prompt cache。
     * 所有 child 的 API 请求必须 byte-identical 除了最后一条 user message 的 directive 文本。
     * 因此 tool_result 块的 placeholder 文本是 CC 强约束常量(本类 {@link #FORK_PLACEHOLDER_RESULT})。
     *
     * @param directive       CC original: directive (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:108)
     *                        用户为这个 fork child 指定的任务指令, 注入到末尾 text block
     * @param assistantMessage CC original: assistantMessage (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:109)
     *                        父 agent 最后一条 assistant message, 含 thinking + tool_use + text blocks
     * @return [...assistantMessage (cloned), userMessage (tool_results + directive)]
     *         或 边界场景: 无 tool_use → [userMessage (仅 directive)]
     */
    public static List<Message> buildForkedMessages(String directive, AssistantMessage assistantMessage) {
        if (log.isDebugEnabled()) {
            log.debug("[ForkSubagentMessages] 构造 fork 消息前缀, directive 前 50 字符={}",
                directive == null ? "<null>" : directive.substring(0, Math.min(50, directive.length())));
        }

        // 1. 克隆 assistant message (CC forkSubagent.ts:113-120) — 新 uuid 防 transcript 冲突
        AssistantMessage fullAssistantMessage = new AssistantMessage(
            UUID.randomUUID().toString(),
            new ArrayList<>(assistantMessage.content())
        );

        // 2. 收集所有 tool_use 块 (CC forkSubagent.ts:123-125)
        List<BetaToolUseBlock> toolUseBlocks = assistantMessage.content().stream()
            .filter(b -> b instanceof BetaToolUseBlock)
            .map(b -> (BetaToolUseBlock) b)
            .toList();

        // 3. 边界: 无 tool_use → logForDebugging error + 仅返回 user message (CC forkSubagent.ts:127-139)
        if (toolUseBlocks.isEmpty()) {
            if (log.isErrorEnabled()) {
                String preview = directive == null ? "" : directive.substring(0, Math.min(50, directive.length()));
                log.error("[ForkSubagentMessages] assistant message 无 tool_use 块, fork 路径异常, directive 前 50 字符={}",
                    preview);
            }
            return List.of(new UserMessage(List.of(
                new BetaTextBlock(ForkChildBoilerplate.buildChildMessage(directive))
            )));
        }

        // 4. 为每一个 tool_use 构造相同 placeholder 的 tool_result (CC forkSubagent.ts:142-151)
        List<ContentBlock> toolResultBlocks = new ArrayList<>(toolUseBlocks.size());
        for (BetaToolUseBlock block : toolUseBlocks) {
            toolResultBlocks.add(new BetaToolResultBlock(
                block.id(),
                List.of(new BetaTextBlock(FORK_PLACEHOLDER_RESULT))
            ));
        }

        // 5. 合成单条 user message: tool_results + directive text (CC forkSubagent.ts:158-166)
        List<ContentBlock> userContent = new ArrayList<>(toolResultBlocks.size() + 1);
        userContent.addAll(toolResultBlocks);
        userContent.add(new BetaTextBlock(ForkChildBoilerplate.buildChildMessage(directive)));

        if (log.isDebugEnabled()) {
            log.debug("[ForkSubagentMessages] fork 消息前缀构造完成, tool_use={}, tool_result={}, user_message_blocks={}",
                toolUseBlocks.size(), toolResultBlocks.size(), userContent.size());
        }

        return List.of(fullAssistantMessage, new UserMessage(userContent));
    }
}