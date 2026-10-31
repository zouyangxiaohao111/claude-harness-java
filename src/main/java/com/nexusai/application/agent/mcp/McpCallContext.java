package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 调用上下文信封 · 会话型内建 MCP server（如 computer-use）随 {@code tools/call} 传递。
 *
 * <p><b>WHY 需要它</b>：MCP {@code tools/call} 的 params 只有 {@code name} + {@code arguments}
 * （对齐 MCP 规范），<b>没有会话标识</b>。而 computer-use 的工具实现必须拿到
 * {@code ToolUseContext}（{@code ctx.sessionId()} 决定授权位 / 截图几何分桶，
 * {@code ctx.effectiveModelName()} / {@code ctx.effectiveProviderType()} 决定视觉能力闸，
 * {@code ctx.agentId()} 决定主线程 / 子代理）。
 * <br>CCB 的解法是「建 server 时闭包绑定 session context」（{@code mcpServer.ts:89-253}
 * 的 {@code bindSessionContext}）；本仓的 MCP server 是<b>进程级单例</b>（{@link McpToolPool}
 * 按 server 名缓存连接），多个会话共享 ⇒ 不能在建 server 时绑定。
 * <br>⇒ 采用<b>显式传参</b>：把调用点的会话标识随请求带过去（对齐本仓铁律
 * 「会话态一律显式传参，不经 ThreadLocal/MDC」）。
 *
 * <p><b>wire 形状</b>：{@code tools/call} params 的<b>同级</b>键 {@code _callContext}
 * （<b>不塞进 {@code arguments}</b> ⇒ 工具入参 schema 保持纯净、模型可见参数不变）。
 * 该键是本仓内建 server 的私有扩展；外部 MCP server 走标准 params（不含该键）⇒ 零影响。
 *
 * @param sessionId           会话 id（主线程 / 子代理共用同一会话 id）
 * @param agentId             子代理 id（主线程为 null）
 * @param effectiveModelName  本轮生效模型名（视觉能力闸用；可空 ⇒ 判不出）
 * @param effectiveProviderType 本轮 provider 类型（视觉能力闸用；可空 ⇒ 判不出）
 */
public record McpCallContext(
        String sessionId,
        String agentId,
        String effectiveModelName,
        String effectiveProviderType) {

    /**
     * {@code tools/call} params 中的信封键名 · 与 {@code name} / {@code arguments} 同级。
     *
     * <p>下划线前缀 = 私有扩展，与 MCP 规范保留字段（{@code _meta} 等）风格一致。
     */
    public static final String PARAM_NAME = "_callContext";

    /**
     * 从工具上下文抽取信封 · 会话型 server 的 {@code McpServerTool.execute} 调用。
     *
     * @param ctx 工具调用上下文（null ⇒ 返回 null ⇒ 不带信封）
     * @return 信封；ctx 为 null 或 sessionId 为空 ⇒ null
     */
    public static McpCallContext from(ToolUseContext ctx) {
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return null;
        }
        return new McpCallContext(
                ctx.sessionId(),
                ctx.agentId() == null ? null : ctx.agentId().toString(),
                ctx.effectiveModelName(),
                ctx.effectiveProviderType());
    }

    /**
     * 信封 → wire map（{@link #PARAM_NAME} 的值）。
     *
     * @return 可 JSON 序列化的 map（键序固定）
     */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("sessionId", sessionId);
        wire.put("agentId", agentId);
        wire.put("effectiveModelName", effectiveModelName);
        wire.put("effectiveProviderType", effectiveProviderType);
        return wire;
    }

    /**
     * wire map → 信封。
     *
     * @param node {@code params._callContext} 节点（可为 null / 缺失）
     * @return 信封；节点缺失或 sessionId 为空 ⇒ null（调用方据此判定「无会话上下文」）
     */
    public static McpCallContext fromWire(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        String sessionId = node.path("sessionId").asText("");
        if (sessionId.isBlank()) {
            return null;
        }
        return new McpCallContext(
                sessionId,
                emptyToNull(node.path("agentId").asText("")),
                emptyToNull(node.path("effectiveModelName").asText("")),
                emptyToNull(node.path("effectiveProviderType").asText("")));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
