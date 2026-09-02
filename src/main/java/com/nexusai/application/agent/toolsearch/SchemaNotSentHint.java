package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [Session H P2-1] SchemaNotSentHint · 对齐 CC Open-ClaudeCode/src/services/tools/toolExecution.ts:572-597
 * {@code buildSchemaNotSentHint} 完整版 4 道乐观门.
 *
 * <h2>用途</h2>
 * <p>当 schema 验证（{@code ToolInputValidator.validateSchema}）失败时, 若 tool 因
 * deferred 机制 schema 未发到 LLM, 客户端解析器拒绝 typed parameters（数组/数字/布尔
 * 序列化为字符串). 此时 hint 追加到 errorContent, 告诉 LLM "schema not sent, 请加载
 * 工具后重试" (CC 真源 toolExecution.ts:619-630 Zod 失败 + hint 注入).
 *
 * <h2>CC 4 道乐观门 (toolExecution.ts:587-591)</h2>
 * <ol>
 *   <li>{@code isToolSearchEnabledOptimistic()} — feature gate
 *       (CC toolSearch.ts:270-320 + getToolSearchMode :172-198)</li>
 *   <li>{@code isToolSearchToolAvailable(tools)} — ToolSearch 工具在工具列表
 *       (CC toolSearch.ts:330-334)</li>
 *   <li>{@code isDeferredTool(tool)} — tool 是 deferred
 *       (CC tools/ToolSearchTool/prompt.ts:62-108)</li>
 *   <li>{@code !extractDiscoveredToolNames(messages).has(tool.name)} — tool 不在
 *       discovered set (CC toolSearch.ts:545-592)</li>
 * </ol>
 * 任一门命中 → {@code null}（调用方不追加 hint）; 4 道门全过 → hint 文案（CC 三段结构:
 * schema 未发送 / 字符串化 / 加载工具后重试）。
 *
 * <h2>已知限制 (结构对齐 CC 的固有结果, fail loud 记录)</h2>
 * <ul>
 *   <li>gate1 provider 子检查 (CC: {@code getAPIProvider()==='firstParty'} &&
 *       {@code !isFirstPartyAnthropicBaseUrl()}) Java 无 API provider 抽象 → N/A,
 *       仅镜像同名 env（{@code CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS} /
 *       {@code ENABLE_TOOL_SEARCH}）;</li>
 *   <li>gate3 FORK_SUBAGENT Agent / KAIROS Brief / KAIROS SendUserFile 子规则
 *       (CC prompt.ts:76-105) Java 无对应 feature → N/A;</li>
 *   <li>gate4 discovered set 为真扫描：{@link Role#user}（contentBlocks 内 tool_result
 *       包裹块）+ {@link Role#tool}（扁平化 tool_result，contentBlocks 直接承载项）消息内的
 *       {@code tool_reference.tool_name} (CC toolSearch.ts:545-592；Role.tool 为 CC user
 *       消息内 tool_result 的 Java 扁平化，Provider 翻译回 role=user，LLM 视角即 CC user
 *       消息)。boundary 携带路径
 *       ({@code compactMetadata.preCompactDiscoveredTools}) Java ChatMessageDto 层不可
 *       表达（boundary 消息不携带 compactMetadata，同
 *       {@link com.nexusai.application.agent.compact.PartialCompactConversation} 限制）→
 *       登记 N/A。H4 引入 defer_loading 后 MCP/deferred 工具经 tool_reference 发现才发送
 *       → 生产 tool_result 可含 tool_reference → discovered 非空 → gate4 拦截生效
 *       （是否注入取决于实际消息历史, 不再恒放行）;</li>
 *   <li>H4 起 MCP/deferred 工具 schema 经 {@code willDefer} 延迟发送（defer_loading:true,
 *       claude.ts:1208-1209）→ 4 道门全过后 hint 反映真实"未发送"状态（对齐 CC
 *       toolExecution.ts:590-596 语义）。</li>
 * </ul>
 *
 * @see Tool#isMcp()
 * @see Tool#alwaysLoad()
 * @see Tool#shouldDefer(JsonNode)
 * @see com.nexusai.application.agent.permission.ToolInputValidator#validateSchema
 */
public final class SchemaNotSentHint {

    private static final Logger log = LoggerFactory.getLogger(SchemaNotSentHint.class);

    /**
     * Hint 模板 · CC 三段英文原文 (toolExecution.ts:592-596):
     * (1) schema 未发送 (2) 字符串化问题 (3) 加载工具后重试.
     *
     * <p>三段全量回切 CC 英文原文, 不保留 H-3 中文本地化（DEL-H-05）——CC 原文含
     * {@code select:<toolName>} 查询指引, Java ToolSearchTool 重写后 (H1) 已支持
     * select: 精确名匹配, 无需再本地化.
     */
    private static final String HINT_TEMPLATE =
        "\n\nThis tool's schema was not sent to the API — it was not in the discovered-tool set derived from message history. "
      + "Without the schema in your prompt, typed parameters (arrays, numbers, booleans) get emitted as strings and the client-side parser rejects them. "
      + "Load the tool first: call " + ToolNameConstants.TOOL_SEARCH_TOOL_NAME + " with query \"select:%s\", then retry this call.";

    private SchemaNotSentHint() {
        // 工具类, 禁止实例化
    }

    /**
     * 对齐 CC {@code buildSchemaNotSentHint(tool, messages, tools): string | null}
     * (toolExecution.ts:578-597).
     *
     * <p>4 道乐观门顺序执行, 任一命中返回 {@code null}; 全过返回 hint 文案.
     *
     * @param tool  当前 schema 失败的 tool
     * @param ctx   工具调用上下文 (提供 availableTools + messages)
     * @param input tool 本次调用输入 (Java {@code shouldDefer(JsonNode)} 入参)
     * @return hint 文本 (注入 tool_result error), 或 {@code null} (不注入)
     */
    public static String build(Tool tool, ToolUseContext ctx, JsonNode input) {
        if (tool == null) {
            return null;
        }
        if (ctx == null) {
            return null;
        }
        // 第 1 道门: feature gate (CC toolExecution.ts:587 isToolSearchEnabledOptimistic)
        if (!ToolSearchService.isToolSearchEnabledOptimistic()) {
            if (log.isDebugEnabled()) {
                log.debug("SchemaNotSentHint: 第 1 道门命中 (ToolSearch feature 未启用), "
                    + "tool={} 不注入 hint", tool.name());
            }
            return null;
        }
        // 第 2 道门: ToolSearch 工具在工具列表 (CC toolExecution.ts:588 isToolSearchToolAvailable)
        if (!ToolSearchService.isToolSearchToolAvailable(ctx.availableTools())) {
            if (log.isDebugEnabled()) {
                log.debug("SchemaNotSentHint: 第 2 道门命中 (工具列表无 ToolSearch), "
                    + "tool={} 不注入 hint", tool.name());
            }
            return null;
        }
        // 第 3 道门: tool 是 deferred (CC toolExecution.ts:589 isDeferredTool)
        if (!ToolSearchService.isDeferredTool(tool, input)) {
            if (log.isDebugEnabled()) {
                log.debug("SchemaNotSentHint: 第 3 道门命中 (tool 非 deferred), "
                    + "tool={} 不注入 hint", tool.name());
            }
            return null;
        }
        // 第 4 道门: tool 在 discovered set → 返回 null (CC toolExecution.ts:590-591)
        // discovered set 来自 extractDiscoveredToolNames 真扫描 (toolSearch.ts:545-592):
        // user 消息 tool_result 内容内的 tool_reference.tool_name.
        Set<String> discovered = extractDiscoveredToolNames(ctx.messages());
        if (discovered.contains(tool.name())) {
            if (log.isDebugEnabled()) {
                log.debug("SchemaNotSentHint: 第 4 道门命中 (tool 已在 discovered set), "
                    + "tool={} 不注入 hint", tool.name());
            }
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("SchemaNotSentHint: 4 道门全过, tool={} 注入 hint "
                + "(discovered set 大小={})", tool.name(), discovered.size());
        }
        return String.format(HINT_TEMPLATE, tool.name());
    }

    /**
     * 对齐 CC {@code extractDiscoveredToolNames(messages)} (toolSearch.ts:545-592) · 第 4 道门.
     *
     * <p>CC 扫描消息历史: system {@code compact_boundary} 的
     * {@code compactMetadata.preCompactDiscoveredTools} + user 消息 tool_result 内容内的
     * {@code tool_reference.tool_name}, 返回 discovered tool name 集合.
     *
     * <p>Java 映射（对照 {@code PartialCompactConversation#extractDiscoveredToolNames}
     * 真扫描 :474-504）：{@code ctx.messages()} 为 {@code List<?>}，元素经
     * {@code instanceof ChatMessageDto} 守卫（生产可能含非 ChatMessageDto 元素, 防
     * ClassCastException）。
     *
     * <p><b>扫描角色（对齐 CC user 消息语义）</b>: CC 中 tool_result 块存于 user 消息
     * （messages.ts:505 createUserMessage {@code role:'user'}），content 数组内嵌
     * tool_reference（toolSearch.ts:568-578）。Java 把 CC user 消息内的 tool_result 扁平化为
     * {@link Role#tool} ChatMessageDto（{@code LlmAgentLoop.toolResultMessage} 工厂，
     * {@code ToolResultStorage} 候选提取一致；Provider 翻译 Role.tool → role=user，LLM 视角
     * 即 CC user 消息，见 AnthropicSdkProvider.buildSdkMessages）—— 因此两个角色都扫:
     * <ul>
     *   <li>{@link Role#user}：{@code contentBlocks} 内含 type==tool_result 包裹块，块内
     *       content 数组扫 tool_reference.tool_name（既有路径，行为不变）;</li>
     *   <li>{@link Role#tool}：扁平化 tool_result，{@code contentBlocks} 即
     *       tool_result.content 数组 —— 主形状为直接 tool_reference 项，兼容形状为
     *       tool_result 包裹块；两分支均以 {@code isToolReferenceWithName} 判定，无语义重叠。</li>
     * </ul>
     *
     * <p><b>boundary 携带路径 N/A</b>: {@code compact_boundary} 消息的
     * {@code compactMetadata.preCompactDiscoveredTools} 在 Java ChatMessageDto 层不可
     * 表达（boundary ChatMessageDto 不携带 compactMetadata）—— 同
     * {@code PartialCompactConversation} 限制, 登记 N/A, 不模拟.
     *
     * @param messages 消息历史 (CC {@code Message[]}, Java {@code List<?>})
     * @return discovered tool name 集合 (无 → 空集, 非 null)
     */
    public static Set<String> extractDiscoveredToolNames(List<?> messages) {
        Set<String> discovered = new HashSet<>();
        if (messages == null) {
            return discovered;
        }
        for (Object messageObj : messages) {
            // 生产 messages 可能含非 ChatMessageDto 元素 → instanceof 守卫防 ClassCastException
            if (!(messageObj instanceof ChatMessageDto message)) {
                continue;
            }
            // CC toolSearch.ts:563 只扫含 tool_result 的消息。Java 侧 Role.user（contentBlocks
            // 内含 tool_result 包裹块）与 Role.tool（CC user 消息内 tool_result 的扁平化，
            // contentBlocks 即 tool_result.content 数组）同属 CC user 消息语义，一并扫描。
            if (message.role() != Role.user && message.role() != Role.tool) {
                continue;
            }
            if (message.contentBlocks() == null) {
                continue;
            }
            // [hint-scan-role] Role.tool 为扁平化 tool_result：contentBlocks 直接承载
            // tool_reference 项（主形状），也可能包裹 tool_result（兼容形状）→ 两分支都扫
            boolean flattenedToolResult = message.role() == Role.tool;
            for (Object blockObj : message.contentBlocks()) {
                if (!(blockObj instanceof JsonNode block)) {
                    continue;
                }
                if (flattenedToolResult && isToolReferenceWithName(block)) {
                    // Role.tool 扁平化主形状：content 项即 tool_reference.tool_name
                    if (log.isDebugEnabled()) {
                        log.debug("SchemaNotSentHint: Role.tool 扁平化 tool_reference 命中 "
                            + "tool_name={} toolCallId={}", block.path("tool_name").asText(),
                            message.toolCallId());
                    }
                    discovered.add(block.path("tool_name").asText());
                    continue;
                }
                // tool_result 包裹形状（Role.user 主路径 + Role.tool 兼容）:
                // CC toolSearch.ts:572-578 tool_result 内容内 tool_reference.tool_name
                if (!isToolResultBlockWithContent(block)) {
                    continue;
                }
                for (JsonNode item : block.path("content")) {
                    if (isToolReferenceWithName(item)) {
                        discovered.add(item.path("tool_name").asText());
                    }
                }
            }
        }
        if (log.isDebugEnabled() && !discovered.isEmpty()) {
            log.debug("SchemaNotSentHint: 从消息历史发现 {} 个已加载工具 (discovered set): {}",
                discovered.size(), discovered);
        }
        return discovered;
    }

    /** 对齐 CC {@code isToolReferenceBlock} (toolSearch.ts:479-486) · {@code type=='tool_reference'}. */
    static boolean isToolReferenceBlock(Object obj) {
        return obj instanceof JsonNode node
            && node.isObject()
            && "tool_reference".equals(node.path("type").asText(""));
    }

    /**
     * 对齐 CC {@code isToolReferenceWithName} (toolSearch.ts:491-499) ·
     * tool_reference 块且 {@code tool_name} 为字符串.
     */
    static boolean isToolReferenceWithName(Object obj) {
        return obj instanceof JsonNode node
            && isToolReferenceBlock(node)
            && node.path("tool_name").isTextual();
    }

    /**
     * 对齐 CC {@code isToolResultBlockWithContent} (toolSearch.ts:513-522) ·
     * tool_result 块且 {@code content} 为数组.
     */
    static boolean isToolResultBlockWithContent(Object obj) {
        return obj instanceof JsonNode node
            && node.isObject()
            && "tool_result".equals(node.path("type").asText(""))
            && node.path("content").isArray();
    }
}
