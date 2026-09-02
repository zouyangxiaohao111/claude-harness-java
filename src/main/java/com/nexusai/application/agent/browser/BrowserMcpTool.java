package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * nexusai-in-chrome 浏览器工具的 {@link Tool} 适配器 · 对齐 CCB {@code browserTools.ts} BROWSER_TOOLS。
 *
 * <p>每个实例对应一个浏览器工具（{@code name = mcp__nexusai-in-chrome__<tool>}），description +
 * inputSchema 逐字对齐 CCB（见 {@link BrowserToolSpec}）。execute 走 {@link BrowserChannel}
 * 转发通道（WS 批次实现）；通道未注入（null）→ <b>fail loud</b> 返回
 * {@link #EXTENSION_NOT_CONNECTED_MESSAGE}（「浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展」）。
 *
 * <p><b>本阶段范围</b>：工具面 —— 模型可见可调、入参校验、通道转发预留。真实浏览器动作执行
 * （click/type/navigate/console/network 等）由后续 WS 批次在 {@link BrowserChannel} 实现内落地。
 */
final class BrowserMcpTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserMcpTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 扩展未连接时的 fail-loud 文案（对齐任务契约：「浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展」）。 */
    static final String EXTENSION_NOT_CONNECTED_MESSAGE =
            "浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展";

    private final String name;        // 全名：mcp__nexusai-in-chrome__<tool>
    private final String toolName;    // 原名（无前缀，如 "read_page"）
    private final BrowserToolSpec spec;
    private final JsonNode inputSchema;
    private final BrowserChannel channel;   // 可 null（未接线 → execute fail loud）

    /**
     * @param spec    工具定义（name/description/inputSchema/只读标记，对齐 CCB）
     * @param channel 转发通道；{@code null} = 未注入实现 → execute fail loud
     */
    BrowserMcpTool(BrowserToolSpec spec, BrowserChannel channel) {
        this.spec = spec;
        this.toolName = spec.toolName();
        this.name = BrowserToolRegistry.TOOL_PREFIX + spec.toolName();
        this.inputSchema = parseSchema(spec.inputSchemaJson(), spec.ccRef());
        this.channel = channel;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return spec.description();
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    /** 只读判定 · 对齐 spec（读类工具 true → 可并发 + 免写入权限检查）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return spec.readOnly();
    }

    /** 并发安全判定 · 对齐 spec（读类工具 true；写类 computer/form_input/navigate/upload 等 false）。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return spec.concurrencySafe();
    }

    /**
     * 执行：优先转发通道；通道未注入 → fail loud 返回「浏览器扩展未连接」。
     *
     * <p><b>多会话并行</b>：从 {@link RequestContext#sessionId()} 取当前会话，透传给
     * {@link BrowserChannel#send(String, String, Map)} —— 扩展按 sessionId 定位/创建该会话的
     * tab 组（对齐 CCB tabs_context_mcp「每个会话自己的 tab 组」）；结果回传经 callId 匹配，
     * 与 sessionId 无关。
     *
     * <p><b>fail loud（规则十二）</b>：本阶段 WS 通道未实现，模型调用浏览器工具时不得静默
     * 吞掉或假成功 —— 必须返回明确错误文案，让模型/用户知道需要先连接 NexusAI in Chrome 扩展。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        BrowserChannel ch = this.channel;
        if (ch == null) {
            log.warn("BrowserMcpTool: {} 调用但浏览器扩展未连接（BrowserChannel 未注入）→ fail loud 返回「{}」",
                name, EXTENSION_NOT_CONNECTED_MESSAGE);
            return ToolResult.error(call.id(), EXTENSION_NOT_CONNECTED_MESSAGE);
        }
        Map<String, Object> args;
        try {
            // call.input() 为已解析 JsonNode（ToolUseBlock record），按扁平 Map 转发给扩展
            args = JSON.convertValue(call.input(), MAP_TYPE);
        } catch (Exception e) {
            log.error("BrowserMcpTool: {} 入参转 Map 失败: {}", name, e.getMessage(), e);
            return ToolResult.error(call.id(), "浏览器工具入参解析失败: " + e.getMessage());
        }
        try {
            String sessionId = RequestContext.sessionId();
            String result = ch.send(sessionId, toolName, args);
            if (log.isDebugEnabled()) {
                log.debug("BrowserMcpTool: {} 转发成功（channel 返回 {} 字符）", name,
                    result == null ? 0 : result.length());
            }
            return ToolResult.success(call.id(), result);
        } catch (Exception e) {
            log.error("BrowserMcpTool: {} 转发失败: {}", name, e.getMessage(), e);
            return ToolResult.error(call.id(), "浏览器工具调用失败: " + e.getMessage());
        }
    }

    /** 解析 CCB inputSchema JSON 文本 → JsonNode；解析失败抛 IllegalStateException（fail fast，定义错误必现）。 */
    private static JsonNode parseSchema(String json, String ccRef) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(
                "BrowserMcpTool schema 解析失败 " + ccRef + ": " + e.getMessage(), e);
        }
    }
}
