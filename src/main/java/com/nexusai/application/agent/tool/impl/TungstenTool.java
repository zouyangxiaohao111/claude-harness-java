package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tungsten 工具桩实现（ant-only, not implemented）。
 *
 * <p>仅在 {@code nexusai.user.type=ant} 时注册，用于覆盖 Tool 注册表中的名称；
 * 真实 Tungsten 终端能力尚未实现，工具始终保持禁用并显式返回
 * {@code feature_not_implemented}。
 */
@Component
@ConditionalOnProperty(name = "nexusai.user.type", havingValue = "ant", matchIfMissing = false)
public class TungstenTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TungstenTool.class);

    public static final String NAME = ToolNameConstants.TUNGSTEN_TOOL_NAME;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Tungsten terminal integration (ant-only, not implemented).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        log.warn("TungstenTool 调用但 ant 未启用, 返回 feature_not_implemented");
        return ToolResult.error(call.id(), "feature_not_implemented");
    }
}
