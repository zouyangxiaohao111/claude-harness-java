package com.nexusai.application.agent.tool;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.function.Function;

/**
 * Agent 工具结果接口 · 严格对齐 CC {@code ToolResult<T>} (Open-ClaudeCode/src/Tool.ts:321-336).
 *
 * <p>CC 真源 (主代理 grep 实证): CC ToolResult&lt;T&gt; = { data:T, newMessages?, contextModifier?, mcpMeta? }.
 * Java record (final) 不能继承, 故用 sealed interface 模拟; 泛型 &lt;T&gt; 承载 CC data:T.
 *
 * <p><b>[IMP-C2] 4 字段契约</b>（组 2-1 拍板：删 toolUseId/isError/errorCategory/structuredOutput）：
 * 接口仅暴露 CC 4 字段。tool_use_id/is_error 由 mapper 签名参数透传/推导
 * （CC toolExecution.ts:1292 {@code mapToolResultToToolResultBlockParam(result.data, toolUseID)}），
 * 不存于结果；errorCategory 走 OTel 通道；structuredOutput 走 SyntheticOutput/compact 改道通道。
 *
 * <h2>A1 退役 ExtendedToolResult (用户批准 · 严格 CC)</h2>
 * CC 的 newMessages + contextModifier + (MCP) structuredContent 已在 ToolResult&lt;T&gt; 内
 * (Tool.ts:323/330/331-335), 旧 Java 的 ExtendedToolResult 是拆分产物, 现字段折入 ToolResult&lt;T&gt;,
 * sealed permit 收窄为只许 {@link ToolResult}.
 *
 * <p>dispatch 路径: Tool.execute 返回 AgentToolResult&lt;?&gt;; 消费侧直接读
 * {@link #data()} / {@link #newMessages()} / {@link #contextModifier()} / {@link #mcpMeta()},
 * 不再用 instanceof ExtendedToolResult 分流.
 *
 * @param <T> CC original: T (Tool.ts:322) — 工具结构化输出类型
 */
public sealed interface AgentToolResult<T> permits ToolResult {

    /** CC original: data (Tool.ts:322) — 工具结构化输出 (替代旧 content:String). */
    T data();

    /** CC original: newMessages (Tool.ts:323) — 注入对话历史的额外消息. 默认空 List. */
    default List<ChatMessageDto> newMessages() {
        return List.of();
    }

    /** CC original: contextModifier (Tool.ts:330) — 仅 concurrency-safe=false 工具生效. 默认 null. */
    default Function<ToolUseContext, ToolUseContext> contextModifier() {
        return null;
    }

    /** CC original: mcpMeta (Tool.ts:331-335) — MCP 透传元数据, never sent to model. 默认 null. */
    default ToolResult.McpMeta mcpMeta() {
        return null;
    }
}
