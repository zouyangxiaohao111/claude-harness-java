package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token Estimator · 对齐 CC {@code services/compact/microCompact.ts:164 estimateMessageTokens}
 * block 口径 + {@code :38 IMAGE_MAX_TOKEN_SIZE} + {@code :41-50 COMPACTABLE_TOOLS}。
 *
 * <p><b>IMP-13（REQ-14）</b>: 旧实现为 {@code content.length()/4+4} char 估算（非 block 统计，
 * 03 ⊕7）且 IMAGE=1024、COMPACTABLE_TOOLS 含 'Shell' 死条目（D-20/D-21）。本实现重建为 CC block
 * 口径：text→rough / tool_result→string|数组 / image≈2000 / thinking→文本 / tool_use→name+input，
 * 最后 ×4/3 ceil 保守 padding（microCompact.ts:164-205）。
 *
 * <p><b>IMP-17（REQ-26，OD-05/OD-12）</b>: 本组件扩展为 CC <b>usage-walk + block 统计</b>——
 * {@link #estimateMessageTokens}（block 统计 + ×4/3，IMP-13 域）保留；
 * {@link #tokenCountWithEstimation} / {@link #tokenCountFromLastAPIResponse} /
 * {@link #finalContextTokensFromLastResponse}（usage-walk 用量族）委托 canonical 宿主
 * {@link Tokens}（utils/tokens.ts 镜像）。blocking-limit 消费方（LlmAgentLoop:2698）改走
 * {@link #tokenCountWithEstimation} 统一窗口（D-18 双用途解除 · CC query.ts:637）。
 */
@Component
public class TokenEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 图片/文档块近似 token 数 · CC original: IMAGE_MAX_TOKEN_SIZE
     * (Open-ClaudeCode/src/services/compact/microCompact.ts:38) = 2000。
     * <p>D-20 修复：旧值 1024 与 CC 漂移。
     */
    public static final int IMAGE_MAX_TOKEN_SIZE = 2000;

    /**
     * 可压缩工具集 · CC original: COMPACTABLE_TOOLS
     * (Open-ClaudeCode/src/services/compact/microCompact.ts:41-50) 9 成员：
     * {@code [FILE_READ(Read), ...SHELL_TOOL_NAMES(Bash, PowerShell), GREP(Grep), GLOB(Glob),
     * WEB_SEARCH(WebSearch), WEB_FETCH(WebFetch), FILE_EDIT(Edit), FILE_WRITE(Write)]}。
     * <p>D-21 修复：删除 'Shell' 死条目（非工具注册表工具名），补 'PowerShell'（CC shellToolUtils.ts:6）。
     */
    public static final java.util.Set<String> COMPACTABLE_TOOLS = java.util.Set.of(
        "Read", "Bash", "PowerShell", "Grep", "Glob", "WebSearch", "WebFetch", "Edit", "Write"
    );

    /**
     * 估算单条消息 token 数 · Java 便捷重载（CC 端无单消息版），等价于对单消息列表执行 CC
     * {@code estimateMessageTokens(messages[])} 聚合口径：raw 估算后仅一次 ×4/3 ceil。
     *
     * <p><b>角色过滤 + MC-05b（OPD-CM5-A-12）</b>: 对齐 CC microCompact.ts:167-170
     * {@code if (message.type !== 'user' && message.type !== 'assistant') continue}。CC 的
     * tool_result 内嵌于 user 消息 content 被计数（microCompact.ts:179-180
     * {@code calculateToolResultTokens}）；Java 将其扁平化为独立 {@code Role.tool} 消息
     * （{@code LlmAgentLoop.toolResultMessage} 工厂），因此 Role.tool 消息一并计入（等效 CC
     * user 消息内 tool_result 块）。system 等其余角色按 0 计入。
     * {@link #calculateToolResultTokens}（MicroCompactor time-based MC tokensSaved，CC
     * microCompact.ts:481 block 级累计）仍为无 padding 的 raw 访问口。
     *
     * <p><b>block 映射（Java 模型）</b>:
     * <ul>
     *   <li>{@code contentBlocks}（R32-b9，List&lt;JsonNode&gt;）存在时逐 block 统计：
     *       text→rough / tool_result→string|数组 / image|document→2000 / thinking→rough(thinking) /
     *       redacted_thinking→rough(data) / tool_use→rough(name+input) / else→rough(json)。</li>
     *   <li>{@code contentBlocks} 为空时 flat 兜底：{@code content}→rough（tool_result 扁平文本 /
     *       user 文本）、{@code reasoning}→rough（thinking）、{@code imagePasteIds}→2000×数量、
     *       {@code toolCalls}→rough(name+arguments)（tool_use）。</li>
     * </ul>
     *
     * @param msg 单条消息
     * @return 估算 token 数（≥ 0；非 user/assistant/tool 消息 → 0）
     */
    public int estimateMessageTokens(ChatMessageDto msg) {
        // 角色过滤对齐 CC microCompact.ts:168-170（user/assistant 计入）+ MC-05b/OPD-CM5-A-12：
        // Role.tool 是 Java 对 CC user 消息内 tool_result 块的扁平化表示（LlmAgentLoop.toolResultMessage
        // 工厂），按 CC 语义（microCompact.ts:179-180 calculateToolResultTokens）计入。
        if (msg == null || (msg.role() != Role.user && msg.role() != Role.assistant
            && msg.role() != Role.tool)) {
            return 0;
        }
        // 单消息便捷重载 = CC 对单消息列表的聚合口径（microCompact.ts:203-204）
        return (int) Math.ceil(estimateRawMessageTokens(msg) * (4.0 / 3.0));
    }

    /**
     * 工具结果 token 估算（<b>无 ×4/3 padding</b>）· 对齐 CC {@code calculateToolResultTokens}
     * （microCompact.ts:138-157）：string content → rough；数组 content → Σ(text→rough /
     * image|document→2000)。
     *
     * <p><b>V2-S2（IMP2-12）</b>: CC time-based MC 的 {@code tokensSaved} 对每条被清除
     * tool_result block 以本函数累计（microCompact.ts:481），<b>不</b>走
     * {@code estimateMessageTokens} 的 ×4/3 保守 padding（高估 ~33%）。本方法为
     * {@link #estimateRawMessageTokens} 的 public raw 访问口——对 Role.tool 消息（本方法唯一
     * 消费面，MicroCompactor time-based 清除循环）flat 兜底即 CC string 分支、contentBlocks
     * 逐块即 CC 数组分支（tool_result 包装块经 {@code calculateToolResultBlockTokens} 递归）。
     *
     * @param msg 被清除的 tool_result 消息（Role.tool；其他角色按 raw 估算返回）
     * @return raw token 估算（≥ 0，无 padding）
     */
    public int calculateToolResultTokens(ChatMessageDto msg) {
        return estimateRawMessageTokens(msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    // usage-walk 用量族（IMP-17 · REQ-26 · OD-05/OD-12 · 委托 canonical 宿主 Tokens）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 当前上下文窗口 token 估算（阈值/blocking 输入口径）· 对齐 CC
     * {@code tokenCountWithEstimation}（utils/tokens.ts:226-261）usage-walk + sibling 回溯。
     *
     * <p><b>消费方</b>: LlmAgentLoop blocking-limit 预检（query.ts:637
     * {@code tokenCountWithEstimation(messagesForQuery) - snipTokensFreed}）——IMP-17 解除
     * estimateMessageTokens 双用途（D-18），blocking 改走 usage-walk + IMP-06 统一窗口
     * （computeBlockingLimit）。
     *
     * <p><b>A5-2</b>: 1 参 = anthropic 语义（4 项和）；deepseek 等 openai_compatible 调用点请用
     * {@link #tokenCountWithEstimation(List, boolean)} 传 isAnthropic（本 bean 无模型上下文，
     * anthropic 由调用点按 effectiveModel 判定注入）。
     *
     * @param messages 组装后请求消息列表（CC messagesForQuery）
     * @return 上下文窗口 token 估算（≥ 0）
     */
    public int tokenCountWithEstimation(List<ChatMessageDto> messages) {
        return Tokens.tokenCountWithEstimation(messages);
    }

    /**
     * 当前上下文窗口 token 估算 · 协议分派重载（A5-2 · deepseek 双计修复）。
     *
     * @param messages  组装后请求消息列表
     * @param anthropic 协议判定：true=4 项和；false=仅 input+output
     * @return 上下文窗口 token 估算（≥ 0）
     */
    public int tokenCountWithEstimation(List<ChatMessageDto> messages, boolean anthropic) {
        return Tokens.tokenCountWithEstimation(messages, anthropic);
    }

    /**
     * 最后一次 API 响应总 token（compaction API 用量 = postCompactTokenCount）· 对齐 CC
     * {@code tokenCountFromLastAPIResponse}（utils/tokens.ts:55-66）。
     *
     * <p><b>A5-2</b>: 1 参 = anthropic 语义；deepseek 调用点请用
     * {@link #tokenCountFromLastAPIResponse(List, boolean)} 传 isAnthropic。
     *
     * @param messages 消息列表
     * @return 最近 API 响应 token 数（含 cache；无 usage → 0）
     */
    public int tokenCountFromLastAPIResponse(List<ChatMessageDto> messages) {
        return Tokens.tokenCountFromLastAPIResponse(messages);
    }

    /**
     * 最后一次 API 响应总 token · 协议分派重载（A5-2 · deepseek 双计修复）。
     *
     * @param messages  消息列表
     * @param anthropic 协议判定：true=4 项和；false=仅 input+output
     * @return 最近 API 响应 token 数（无 usage → 0）
     */
    public int tokenCountFromLastAPIResponse(List<ChatMessageDto> messages, boolean anthropic) {
        return Tokens.tokenCountFromLastAPIResponse(messages, anthropic);
    }

    /**
     * 最终上下文窗口 token（排除 cache）· 对齐 CC {@code finalContextTokensFromLastResponse}
     * （utils/tokens.ts:79-112）。task_budget 跨压缩结转测量源（query.ts:511-514/1141-1145，
     * IMP-16 域）。
     *
     * @param messages 压缩前消息列表
     * @return 最近一次 API 响应最终上下文 token（input+output，排除 cache；无 usage → 0）
     */
    public int finalContextTokensFromLastResponse(List<ChatMessageDto> messages) {
        return Tokens.finalContextTokensFromLastResponse(messages);
    }

    /**
     * 估算消息列表总 token 数 · 对齐 CC {@code estimateMessageTokens(messages[])}
     * （microCompact.ts:164-205）<b>聚合口径</b>：全部消息 raw 求和后仅一次
     * {@code Math.ceil(total × 4/3)}。
     *
     * <p><b>角色过滤 + MC-05b（OPD-CM5-A-12）</b>: 对齐 CC microCompact.ts:167-170——user/assistant
     * 计入聚合，system 跳过。CC 中 tool_result 是 user 消息内的 content block（从不独立成消息，
     * microCompact.ts:177-180 tool_result block 分支）；Java 真实消息流将 tool_result 扁平化为独立
     * {@code Role.tool} 消息（{@code LlmAgentLoop.toolResultMessage} 工厂，ToolResultStorage:586-588），
     * 因此 Role.tool 消息一并计入（等效 CC user 消息内 tool_result 块）。tool_result 亦可作为 user
     * 消息内 content block 经 {@link #estimateRawMessageTokens}→{@code calculateToolResultBlockTokens}
     * 逐 block 累计。独立 {@link #calculateToolResultTokens}（MicroCompactor time-based MC tokensSaved）
     * 不走本聚合层，仍为无 padding 的 raw 访问口。
     *
     * <p><b>WHY（相对逐条 ceil 求和）</b>: CC 在 for 循环内逐 block 累加 raw token，循环结束
     * 后才执行一次 {@code Math.ceil(totalTokens * (4/3))}（microCompact.ts:204）。若逐条
     * ×4/3 ceil 再求和，每条消息的 ceil 上取整会累积高估（≤ 每条 1 token），与 CC 口径漂移。
     *
     * @param messages 消息列表
     * @return 估算 token 数（≥ 0；仅 user/assistant/tool 消息计入）
     */
    public int estimateMessageTokens(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int totalRaw = 0;
        for (ChatMessageDto m : messages) {
            // 角色过滤对齐 CC microCompact.ts:168-170（user/assistant 计入）+ MC-05b/OPD-CM5-A-12：
            // Role.tool 是 Java 对 CC user 消息内 tool_result 块的扁平化表示，计入聚合。
            if (m == null || (m.role() != Role.user && m.role() != Role.assistant
                && m.role() != Role.tool)) {
                continue;
            }
            totalRaw += estimateRawMessageTokens(m);
        }
        // Pad estimate by 4/3 to be conservative since we're approximating
        return (int) Math.ceil(totalRaw * (4.0 / 3.0));
    }

    /**
     * 单条消息 raw token 估算（不含 ×4/3 padding）· CC {@code estimateMessageTokens} 逐 block
     * 累计（microCompact.ts:165-201）的 Java 模型等价。单消息/列表重载共享，padding 统一在
     * 外层聚合后仅执行一次（CC microCompact.ts:204）。
     */
    private int estimateRawMessageTokens(ChatMessageDto msg) {
        if (msg == null) {
            return 0;
        }
        int total = 0;
        List<?> blocks = msg.contentBlocks();
        if (blocks != null && !blocks.isEmpty()) {
            for (Object block : blocks) {
                total += estimateBlockTokens(block);
            }
        } else {
            if (msg.content() != null && !msg.content().isBlank()) {
                total += roughTokenCountEstimation(msg.content());
            }
            if (msg.reasoning() != null && !msg.reasoning().isBlank()) {
                total += roughTokenCountEstimation(msg.reasoning());
            }
            if (msg.imagePasteIds() != null) {
                total += msg.imagePasteIds().size() * IMAGE_MAX_TOKEN_SIZE;
            }
            if (msg.toolCalls() != null) {
                for (ToolCallDto tc : msg.toolCalls()) {
                    if (tc == null) {
                        continue;
                    }
                    String input = tc.arguments() != null ? tc.arguments() : "{}";
                    total += roughTokenCountEstimation(tc.name() + input);
                }
            }
        }
        return total;
    }

    /** 单个 content block 的 token 估算 · 对齐 CC estimateMessageTokens 的 block 分支。 */
    private int estimateBlockTokens(Object block) {
        if (block == null) {
            return 0;
        }
        JsonNode node = block instanceof JsonNode jn ? jn : JSON.valueToTree(block);
        String type = node.path("type").asText("");
        switch (type) {
            case "text":
                return roughTokenCountEstimation(node.path("text").asText(""));
            case "tool_result":
                return calculateToolResultBlockTokens(node);
            case "image":
            case "document":
                return IMAGE_MAX_TOKEN_SIZE;
            case "thinking":
                return roughTokenCountEstimation(node.path("thinking").asText(""));
            case "redacted_thinking":
                return roughTokenCountEstimation(node.path("data").asText(""));
            case "tool_use":
                return roughTokenCountEstimation(node.path("name").asText("") + jsonStringify(node.get("input")));
            default:
                // server_tool_use, web_search_tool_result, etc.
                return roughTokenCountEstimation(node.toString());
        }
    }

    /**
     * tool_result block 的 token 估算 · 对齐 CC microCompact.ts:138-157 的 tool_result
     * block 计数：string content→rough；数组 content→Σ(text→rough / image|document→2000)。
     */
    private int calculateToolResultBlockTokens(JsonNode block) {
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
                String t = item.path("type").asText("");
                if ("text".equals(t)) {
                    sum += roughTokenCountEstimation(item.path("text").asText(""));
                } else if ("image".equals(t) || "document".equals(t)) {
                    sum += IMAGE_MAX_TOKEN_SIZE;
                }
            }
            return sum;
        }
        return roughTokenCountEstimation(content.toString());
    }

    /** roughTokenCountEstimation · CC original: roughTokenCountEstimation (tokenEstimation.ts:203)，round(len/4)。委托 canonical {@link Tokens}（IMP-17 避免双实现漂移）。 */
    private static int roughTokenCountEstimation(String text) {
        return Tokens.roughTokenCountEstimation(text);
    }

    /** jsonStringify · CC original: jsonStringify (utils/slowOperations.ts)。委托 canonical {@link Tokens}（IMP-17 避免双实现漂移）。 */
    private static String jsonStringify(JsonNode node) {
        return Tokens.jsonStringify(node);
    }
}
