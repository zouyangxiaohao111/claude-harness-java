package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * token 用量族 · 对齐 CC {@code utils/tokens.ts} + {@code services/tokenEstimation.ts} rough 家族。
 *
 * <p><b>IMP-17（REQ-26）</b>: CC token 用量三口径分离（INV-3 · 01 §11 / OD-05/OD-12）——
 * <ol>
 *   <li>{@link #tokenCountWithEstimation}（usage-walk + sibling 回溯）→ 阈值/blocking 输入口径
 *       （query.ts:637，TokenEstimator 域消费方）</li>
 *   <li>{@link #tokenCountFromLastAPIResponse} → compaction API 用量（compact.ts:629
 *       = postCompactTokenCount）</li>
 *   <li>{@link #roughTokenCountEstimationForMessages} → 结果上下文消息载荷粗估（compact.ts:747
 *       = truePostCompactTokenCount）</li>
 * </ol>
 * 三者<b>独立实现互不混用</b>。旧 Java 以单一 {@code len/4+4} char 估算 / CompactConversation 以
 * 简化 content-only rough 混用口径（D-18 双用途），本类为 canonical 宿主（LlmAgentLoop:4744
 * 「token 用量族完整实现归 IMP-17（TokenEstimator 域）」；IMP-16 结转测量源
 * {@code finalContextTokensFromLastResponse} 一并收编，消除双实现漂移）。
 *
 * <p><b>IMP2-19（X-1..X-4）</b>: 4 助手收编本类（消费方核验 0 → N/A 接线登记，公式仍按 CC
 * 锁定）——{@link #messageTokenCountFromLastAPIResponse}（tokens.ts:123，仅 output）/
 * {@link #getCurrentUsage}（tokens.ts:138，4 字段）/{@link #doesMostRecentAssistantMessageExceed200k}
 * （tokens.ts:159，findLast assistant + 200k 阈值）/{@link #getAssistantMessageContentLength}
 * （tokens.ts:183，spinner 字符长度）。{@code CompactConversation.tokenCountWithEstimation}
 * 本地简化版收敛为本类委托（△-1 双端同源）。
 *
 * <p><b>Java 适配</b>:
 * <ul>
 *   <li>{@link ChatMessageDto} 承载 inputTokens/outputTokens + cache 字段（OPD-R2-SM-01：
 *       inputCacheReadTokens/inputCacheCreationTokens，CC BetaUsage 四通道）；provider 侧捕获
 *       cache 值属 S4-2b 已登记 infra 排期，未捕获时 null → 0 与现状等价；
 *       {@link #getTokenCountFromUsage} 按 CC 公式 input+cache_creation+cache_read+output 计算。</li>
 *   <li>{@code getTokenUsage} 的 CC 合成消息（SYNTHETIC_MESSAGES/SYNTHETIC_MODEL，tokens.ts:12-15）
 *       过滤在 Java DTO 不可表示（无 model/合成标记字段），跳过（真实运行 usage 多为 null，
 *       见 SubagentExecutor:2046 S4-2b 缺口，不影响）。</li>
 *   <li>rough 家族 image/document → {@link TokenEstimator#IMAGE_MAX_TOKEN_SIZE}（=2000，
 *       microCompact.ts:38 / tokenEstimation.ts:411）常量复用，避免值漂移。</li>
 * </ul>
 */
public final class Tokens {

    private static final Logger log = LoggerFactory.getLogger(Tokens.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private Tokens() {
        // 静态工具类
    }

    /** CC BetaUsage 镜像 · CC original: BetaUsage (tokens.ts:1)。cache 字段由 DTO 承载（OPD-R2-SM-01），null → 0。 */
    public record Usage(int inputTokens, int outputTokens, int cacheReadInputTokens, int cacheCreationInputTokens) {

        /**
         * 从 assistant 消息构造（null → 0）· CC original: BetaUsage 字段透传
         * （cache_read_input_tokens / cache_creation_input_tokens，tokens.ts:1）。
         *
         * <p>OPD-R2-SM-01：ChatMessageDto 增 cache 字段后，cache 不再恒 0 ——
         * null = provider 侧未捕获（S4-2b 已登记 infra 排期）→ 0，与现状等价。
         */
        public static Usage of(ChatMessageDto m) {
            return new Usage(
                m.inputTokens() == null ? 0 : m.inputTokens(),
                m.outputTokens() == null ? 0 : m.outputTokens(),
                m.inputCacheReadTokens() == null ? 0 : m.inputCacheReadTokens(),
                m.inputCacheCreationTokens() == null ? 0 : m.inputCacheCreationTokens());
        }
    }

    /**
     * 取 assistant 消息的真实 API usage · CC original: getTokenUsage (utils/tokens.ts:7-21)。
     *
     * <p>返回 {@link Usage} 或 null：仅 assistant 且同时携带 inputTokens/outputTokens 时返回；
     * 其余（user/tool/system、assistant 无 usage、null）→ null。
     *
     * @param message 消息
     * @return Usage 或 null
     */
    public static Usage getTokenUsage(ChatMessageDto message) {
        if (message != null && message.role() == Role.assistant
                && message.inputTokens() != null && message.outputTokens() != null) {
            return Usage.of(message);
        }
        return null;
    }

    /**
     * 上下文总 token · CC original: getTokenCountFromUsage (utils/tokens.ts:46-53)。
     * input + cache_creation + cache_read + output（全量窗口，含 cache）。
     *
     * @param usage API usage
     * @return 总 token 数（≥ 0）
     */
    public static int getTokenCountFromUsage(Usage usage) {
        if (usage == null) {
            return 0;
        }
        return usage.inputTokens() + usage.cacheCreationInputTokens()
            + usage.cacheReadInputTokens() + usage.outputTokens();
    }

    /**
     * 最后一次 API 响应总 token · CC original: tokenCountFromLastAPIResponse (utils/tokens.ts:55-66)。
     * 从末尾回扫最近携带 usage 的消息，返回 getTokenCountFromUsage（含 cache）；无 → 0。
     *
     * @param messages 消息列表
     * @return 最近 API 响应 token 数（无 usage → 0）
     */
    public static int tokenCountFromLastAPIResponse(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) {
                int total = getTokenCountFromUsage(usage);
                if (log.isDebugEnabled()) {
                    log.debug("[IMP-17 Tokens] tokenCountFromLastAPIResponse: lastUsageIdx={} result={} · CC tokens.ts:55",
                        i, total);
                }
                return total;
            }
        }
        return 0;
    }

    /**
     * 最终上下文窗口 token（排除 cache）· CC original: finalContextTokensFromLastResponse
     * (utils/tokens.ts:79-112)。回扫最近 usage：有 {@code iterations} 数组取 {@code iterations[-1]}
     * input+output；Java 无 server-side tool loop 的 iterations 数据 → 走顶层 input+output
     * （tokens.ts:107 分支，同样排除 cache）。无 usage → 0（结转不减）。
     *
     * <p><b>消费方</b>: task_budget 跨压缩结转测量源（query.ts:511-514/1141-1145，IMP-16 域），
     * LlmAgentLoop.finalContextTokensFromLastResponse 委托本方法（消除双实现漂移）。
     *
     * @param messages 压缩前消息列表（CC messagesForQuery）
     * @return 最近一次 API 响应最终上下文 token 数（input+output，排除 cache；无 usage → 0）
     */
    public static int finalContextTokensFromLastResponse(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) {
                int total = usage.inputTokens() + usage.outputTokens();
                if (log.isDebugEnabled()) {
                    log.debug("[IMP-17 Tokens] finalContextTokensFromLastResponse: lastUsageIdx={} result={} (input+output, 排除 cache) · CC tokens.ts:79",
                        i, total);
                }
                return total;
            }
        }
        return 0;
    }

    /**
     * 最后一次 API 响应的输出 token · CC original: messageTokenCountFromLastAPIResponse
     * (utils/tokens.ts:123-136)。从末尾回扫最近携带 usage 的 assistant 消息，返回其
     * {@code output_tokens}（<b>仅输出侧</b>，不含 input/cache）；无 usage → 0。
     *
     * <p><b>IMP2-19 X-1</b>: 消费方核验结论 —— Java 生产 0 消费方（探查 §6 X-1，spinner/遥测
     * 未实现对应通道）→ 按 CC 语义实现并登记 N/A 接线（TokenHelpersCcContractTest 锁定公式）。
     *
     * @param messages 消息列表
     * @return 最近 API 响应的 output_tokens（无 usage → 0）
     */
    public static int messageTokenCountFromLastAPIResponse(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) {
                int result = usage.outputTokens();
                if (log.isDebugEnabled()) {
                    log.debug("[IMP2-19 Tokens] messageTokenCountFromLastAPIResponse: lastUsageIdx={} result={} · CC tokens.ts:123",
                        i, result);
                }
                return result;
            }
        }
        return 0;
    }

    /**
     * 最近一次 API 响应的完整 usage 四元组 · CC original: getCurrentUsage
     * (utils/tokens.ts:138-157)。从末尾回扫最近携带 usage 的 assistant 消息，返回
     * {@code {input_tokens, output_tokens, cache_creation_input_tokens ?? 0, cache_read_input_tokens ?? 0}}；
     * 无 usage → null。
     *
     * <p><b>IMP2-19 X-2</b>: 消费方核验结论 —— Java 生产 0 消费方（探查 §6 X-2）→ 按 CC 语义
     * 实现并登记 N/A 接线（TokenHelpersCcContractTest 锁定公式）。
     *
     * @param messages 消息列表
     * @return 最近 API 响应 usage（无 → null）
     */
    public static Usage getCurrentUsage(List<ChatMessageDto> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[IMP2-19 Tokens] getCurrentUsage: lastUsageIdx={} usage={} · CC tokens.ts:138",
                        i, usage);
                }
                return usage;
            }
        }
        return null;
    }

    /**
     * 最近一条 assistant 消息是否超 200k 阈值 · CC original: doesMostRecentAssistantMessageExceed200k
     * (utils/tokens.ts:159-168)。<b>按最后一条 role=assistant 消息</b>（findLast，非 usage 回扫）
     * 判 {@code getTokenCountFromUsage(usage) > 200_000}（严格大于，边界 200_000 不算超）；
     * 无 assistant 消息或无 usage → false。
     *
     * <p><b>IMP2-19 X-3</b>: 消费方核验结论 —— Java 生产 0 消费方（探查 §6 X-3）→ 按 CC 语义
     * 实现并登记 N/A 接线（TokenHelpersCcContractTest 锁定公式与 findLast 语义）。
     *
     * @param messages 消息列表
     * @return 最后 assistant 的 usage 全量 &gt; 200_000
     */
    public static boolean doesMostRecentAssistantMessageExceed200k(List<ChatMessageDto> messages) {
        final int THRESHOLD = 200_000;
        if (messages == null) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                Usage usage = getTokenUsage(m);
                boolean exceeded = usage != null && getTokenCountFromUsage(usage) > THRESHOLD;
                if (log.isDebugEnabled()) {
                    log.debug("[IMP2-19 Tokens] doesMostRecentAssistantMessageExceed200k: lastAsstIdx={} exceeded={} · CC tokens.ts:159",
                        i, exceeded);
                }
                return exceeded;
            }
        }
        return false;
    }

    /**
     * assistant 消息内容字符长度（spinner token 估算：字符/4≈token）· CC original:
     * getAssistantMessageContentLength (utils/tokens.ts:183-199)。统计与流式计数
     * （handleMessageFromStream deltas）一致的内容：text（text_delta）/ thinking（thinking_delta）/
     * redacted_thinking（data）/ tool_use（input_json_delta → jsonStringify(input)）；
     * signature_delta 排除（非模型输出）。
     *
     * <p><b>Java 映射</b>: {@code contentBlocks} 非空 → 逐 block（JsonNode，type 判别同 CC）；
     * contentBlocks 空（Java 扁平模型）→ {@code content}（text）+ {@code reasoning}（thinking）+
     * Σ {@code toolCalls}（arguments JSON 串，即 input_json_delta 载荷）兜底。
     *
     * <p><b>IMP2-19 X-4</b>: 消费方核验结论 —— Java 生产 0 消费方（探查 §6 X-4）→ 按 CC 语义
     * 实现并登记 N/A 接线（TokenHelpersCcContractTest 锁定公式）。
     *
     * @param message assistant 消息
     * @return 内容字符长度（非 assistant / null → 0）
     */
    public static int getAssistantMessageContentLength(ChatMessageDto message) {
        if (message == null || message.role() != Role.assistant) {
            return 0;
        }
        int contentLength = 0;
        List<?> blocks = message.contentBlocks();
        if (blocks != null && !blocks.isEmpty()) {
            for (Object block : blocks) {
                if (block instanceof CharSequence s) {
                    contentLength += s.length();
                    continue;
                }
                JsonNode node = block instanceof JsonNode jn ? jn : JSON.valueToTree(block);
                String type = node.path("type").asText("");
                switch (type) {
                    case "text":
                        contentLength += node.path("text").asText("").length();
                        break;
                    case "thinking":
                        contentLength += node.path("thinking").asText("").length();
                        break;
                    case "redacted_thinking":
                        contentLength += node.path("data").asText("").length();
                        break;
                    case "tool_use":
                        contentLength += jsonStringify(node.get("input")).length();
                        break;
                    default:
                        // signature_delta 等非模型输出块排除（tokens.ts:181 注记）
                        break;
                }
            }
            return contentLength;
        }
        // Java 扁平兜底：content（text）/ reasoning（thinking）/ toolCalls（tool_use arguments）
        if (message.content() != null) {
            contentLength += message.content().length();
        }
        if (message.reasoning() != null) {
            contentLength += message.reasoning().length();
        }
        if (message.toolCalls() != null) {
            for (ToolCallDto tc : message.toolCalls()) {
                if (tc == null) {
                    continue;
                }
                String input = tc.arguments() != null ? tc.arguments() : "{}";
                contentLength += input.length();
            }
        }
        return contentLength;
    }

    /**
     * 当前上下文窗口 token 估算（阈值/blocking 口径）· CC original: tokenCountWithEstimation
     * (utils/tokens.ts:226-261)。
     *
     * <p><b>usage-walk + sibling 回溯</b>: 从末尾回扫最近携带 usage 的 assistant 响应，先向回
     * 回溯到同一次 API 响应（同 {@code message.id} 的 streaming 分块，并行工具调用时分块间交错
     * 插入 tool_result）的首个 sibling，保证交错 tool_result 全部进入估算切片（否则欠估）；
     * 返回 {@code getTokenCountFromUsage(usage)} + 切片后消息的 rough 估算。无 usage → 全量 rough。
     *
     * @param messages 消息列表
     * @return 上下文窗口 token 估算（≥ 0）
     */
    public static int tokenCountWithEstimation(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int i = messages.size() - 1;
        while (i >= 0) {
            ChatMessageDto message = messages.get(i);
            Usage usage = message != null ? getTokenUsage(message) : null;
            if (message != null && usage != null) {
                // sibling 回溯（tokens.ts:232-252）：向回走到同一次 API 响应的首个分块
                String responseId = getAssistantMessageId(message);
                if (responseId != null) {
                    int j = i - 1;
                    while (j >= 0) {
                        ChatMessageDto prior = messages.get(j);
                        String priorId = prior != null ? getAssistantMessageId(prior) : null;
                        if (responseId.equals(priorId)) {
                            // 同一 API 响应的更早分块 → 以此锚定
                            i = j;
                        } else if (priorId != null) {
                            // 遇到不同 API 响应 → 停止回溯
                            break;
                        }
                        // priorId == null：user/tool_result/attachment 消息，可能交错于分块间 → 继续
                        j--;
                    }
                }
                // usage 来自回溯前捕获的 message（tokens.ts:253）；切片从锚定 i 之后开始
                int result = getTokenCountFromUsage(usage)
                    + roughTokenCountEstimationForMessages(messages.subList(i + 1, messages.size()));
                if (log.isDebugEnabled()) {
                    log.debug("[IMP-17 Tokens] tokenCountWithEstimation: anchorIdx={} usage={} result={} · CC tokens.ts:226 usage-walk",
                        i, getTokenCountFromUsage(usage), result);
                }
                return result;
            }
            i--;
        }
        int result = roughTokenCountEstimationForMessages(messages);
        if (log.isDebugEnabled()) {
            log.debug("[IMP-17 Tokens] tokenCountWithEstimation: 无 usage → 全量 rough={} · CC tokens.ts:260",
                result);
        }
        return result;
    }

    /** assistant 消息的 API 响应 id（同一次响应的分块共享）· CC original: getAssistantMessageId (tokens.ts:28-37)。 */
    private static String getAssistantMessageId(ChatMessageDto message) {
        if (message != null && message.role() == Role.assistant
                && message.id() != null && !message.id().isBlank()) {
            return message.id();
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // rough 家族 · CC original: tokenEstimation.ts:203-435
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 字符长度 → token 粗估 · CC original: roughTokenCountEstimation (tokenEstimation.ts:203-208)
     * {@code Math.round(content.length / bytesPerToken)}，bytesPerToken 默认 4。<b>round 非 int 截断</b>。
     *
     * @param content 文本（null → 0）
     * @return 粗估 token 数（≥ 0）
     */
    public static int roughTokenCountEstimation(String content) {
        if (content == null) {
            return 0;
        }
        return Math.round(content.length() / 4.0f);
    }

    /**
     * 消息列表总 rough · CC original: roughTokenCountEstimationForMessages (tokenEstimation.ts:327-339)。
     * Σ 每条消息 rough（含 contentBlocks 逐块 / flat 兜底）。
     *
     * @param messages 消息列表
     * @return 总粗估 token 数（≥ 0）
     */
    public static int roughTokenCountEstimationForMessages(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessageDto m : messages) {
            total += roughTokenCountEstimationForMessage(m);
        }
        return total;
    }

    /**
     * 单条消息 rough · CC original: roughTokenCountEstimationForMessage (tokenEstimation.ts:341-369)。
     *
     * <p>assistant/user/tool 消息：contentBlocks（数组）非空 → 逐 block rough（text/image/tool_result/
     * tool_use/thinking/redacted，tokenEstimation.ts:391-435）；否则 flat 兜底（Java ChatMessageDto
     * 既有模型，与 {@link TokenEstimator#estimateMessageTokens} 同构，<b>无 ×4/3 padding</b>——
     * 该 padding 是 microCompact 的 estimateMessageTokens 专属口径（IMP-13 域））。
     * role=tool 消息纳入（CC tool_result 是 user 类型消息的 content block，tokenEstimation.ts:347
     * 的 user 分支即覆盖其内容；Java 以独立 role=tool 消息承载 → 同映射计数，保证 usage-walk
     * sibling 回溯（tokens.ts:232-252）对交错 tool_result 的估算切片不失真）。其余角色 → 0。
     * Java DTO 无 AttachmentMessage → attachment 分支跳过。
     *
     * @param message 单条消息
     * @return 粗估 token 数（≥ 0）
     */
    public static int roughTokenCountEstimationForMessage(ChatMessageDto message) {
        if (message == null) {
            return 0;
        }
        if (message.role() != Role.assistant && message.role() != Role.user && message.role() != Role.tool) {
            return 0;
        }
        int total = 0;
        List<?> blocks = message.contentBlocks();
        if (blocks != null && !blocks.isEmpty()) {
            for (Object block : blocks) {
                total += roughTokenCountEstimationForBlock(block);
            }
            return total;
        }
        if (message.content() != null && !message.content().isBlank()) {
            total += roughTokenCountEstimation(message.content());
        }
        if (message.reasoning() != null && !message.reasoning().isBlank()) {
            total += roughTokenCountEstimation(message.reasoning());
        }
        if (message.imagePasteIds() != null) {
            total += message.imagePasteIds().size() * TokenEstimator.IMAGE_MAX_TOKEN_SIZE;
        }
        if (message.toolCalls() != null) {
            for (ToolCallDto tc : message.toolCalls()) {
                if (tc == null) {
                    continue;
                }
                String input = tc.arguments() != null ? tc.arguments() : "{}";
                total += roughTokenCountEstimation(tc.name() + input);
            }
        }
        return total;
    }

    /**
     * 单 block rough · CC original: roughTokenCountEstimationForBlock (tokenEstimation.ts:391-435)。
     *
     * <p>string → rough；text → rough(text)；image|document → {@link TokenEstimator#IMAGE_MAX_TOKEN_SIZE}
     * （=2000，tokenEstimation.ts:400-412，与 microCompact IMAGE 同常量避免欠估）；
     * tool_result → content 递归（string|数组）；tool_use → rough(name + jsonStringify(input))；
     * thinking → rough(thinking)；redacted_thinking → rough(data)；其余 → rough(jsonStringify)。
     *
     * @param block content block（JsonNode 或字符串）
     * @return 粗估 token 数（≥ 0）
     */
    public static int roughTokenCountEstimationForBlock(Object block) {
        if (block == null) {
            return 0;
        }
        if (block instanceof CharSequence s) {
            return roughTokenCountEstimation(s.toString());
        }
        JsonNode node = block instanceof JsonNode jn ? jn : JSON.valueToTree(block);
        String type = node.path("type").asText("");
        switch (type) {
            case "text":
                return roughTokenCountEstimation(node.path("text").asText(""));
            case "image":
            case "document":
                return TokenEstimator.IMAGE_MAX_TOKEN_SIZE;
            case "tool_result":
                return roughTokenCountEstimationForToolResult(node);
            case "tool_use":
                return roughTokenCountEstimation(node.path("name").asText("") + jsonStringify(node.get("input")));
            case "thinking":
                return roughTokenCountEstimation(node.path("thinking").asText(""));
            case "redacted_thinking":
                return roughTokenCountEstimation(node.path("data").asText(""));
            default:
                // server_tool_use, web_search_tool_result, etc. — text-like payloads
                return roughTokenCountEstimation(jsonStringify(node));
        }
    }

    /** tool_result block content（string | 数组）→ rough · CC original: tokenEstimation.ts:413-415（递归 content）。 */
    private static int roughTokenCountEstimationForToolResult(JsonNode block) {
        JsonNode content = block.get("content");
        if (content == null || content.isNull()) {
            return 0;
        }
        if (content.isTextual()) {
            return roughTokenCountEstimation(content.asText());
        }
        if (content.isArray()) {
            int sum = 0;
            for (JsonNode item : content) {
                sum += roughTokenCountEstimationForBlock(item);
            }
            return sum;
        }
        return roughTokenCountEstimation(content.toString());
    }

    /** jsonStringify · CC original: jsonStringify (utils/slowOperations.ts)。 */
    public static String jsonStringify(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
