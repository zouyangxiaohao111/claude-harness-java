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
 * SuggestBackgroundPR 工具桩实现（not implemented）。
 *
 * <p>仅在 {@code nexusai.user.type=ant} 时注册（对齐 CC tools.ts:20-23 require 条件
 * {@code USER_TYPE === 'ant'} + tools.ts:216 注册三元 {@code SuggestBackgroundPRTool ? [...] : []}），
 * 用于覆盖 Tool 注册表中的名称；真实后台 PR 建议能力尚未实现（CC 行为源码缺失，
 * src/tools/SuggestBackgroundPRTool/ 目录不存在，受控占位残留（DEL-TOOL-10-08，探查-tool.md
 * TOOL-10「删除/保留占位二选一」待 owner 拍板），工具始终保持禁用并显式返回
 * {@code feature_not_implemented}。
 */
@Component
@ConditionalOnProperty(name = "nexusai.user.type", havingValue = "ant", matchIfMissing = false)
public class SuggestBackgroundPRTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SuggestBackgroundPRTool.class);

    public static final String NAME = ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Suggest a background pull request (not implemented).";
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
        log.warn("SuggestBackgroundPRTool 调用但功能未实现, 返回 feature_not_implemented");
        return ToolResult.error(call.id(), "feature_not_implemented");
    }
}
