package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TerminalCaptureTool 注册桩 · 对齐 CC {@code tools.ts:113-115, 223}。
 *
 * <p><b>WHY（OPD-10 建注册桩）</b>: CC 中 {@code TerminalCaptureTool = feature('TERMINAL_PANEL')
 * ? require(...).TerminalCaptureTool : null}，flag 关时工具为 {@code null}、不进入
 * getAllBaseTools 数组（{@code ...(TerminalCaptureTool ? [TerminalCaptureTool] : [])}，tools.ts:223）。
 * Java 端原无该工具（feature 死代码消除），本类为注册桩。
 *
 * <p><b>门控语义</b>: {@link #isEnabled()} = {@code featureFlags.terminalPanel()}
 * （{@code TERMINAL_PANEL} flag · CC tools.ts:113），默认全关 → isEnabled()==false 不暴露。
 *
 * <p>CC 门控原名/行号: {@code TERMINAL_PANEL} (Open-ClaudeCode/src/tools.ts:113-115)。
 * CC 注册点: Open-ClaudeCode/src/tools.ts:223。
 */
public class TerminalCaptureTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TerminalCaptureTool.class);

    /** CC 工具名 · {@code TerminalCaptureTool/prompt.ts:1} TERMINAL_CAPTURE_TOOL_NAME='TerminalCapture'（G32③ 修正：CC 真源已就位）。 */
    public static final String NAME = ToolNameConstants.TERMINAL_CAPTURE_TOOL_NAME;

    private final FeatureFlags featureFlags;

    public TerminalCaptureTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    public TerminalCaptureTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
    }

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("TerminalCaptureTool.name(): 返回 CC 工具名 TERMINAL_CAPTURE_TOOL_NAME='TerminalCapture'（对齐 tools.ts:113-115）");
        }
        return NAME;
    }

    @Override
    public String description() {
        return "Terminal capture tool (stub). 未实现能力——由 TERMINAL_PANEL feature 门控占位注册。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = this.featureFlags.terminalPanel();
        if (log.isDebugEnabled()) {
            log.debug("TerminalCaptureTool.isEnabled() = {}（TERMINAL_PANEL 门控，CC tools.ts:113）", enabled);
        }
        return enabled;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        log.warn("[TerminalCaptureTool] stub execute invoked: id={}（未实现能力 fail-loud，TERMINAL_PANEL 门控注册桩）", call.id());
        return ToolResult.error(call.id(),
            "TerminalCapture 工具能力未实现（OPD-10 注册桩占位）。如需真实现请对齐 CC TerminalCaptureTool 行为后落地。");
    }
}
