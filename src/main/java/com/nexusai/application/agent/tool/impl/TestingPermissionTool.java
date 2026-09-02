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
 * TestingPermissionTool 注册桩 · 对齐 CC {@code tools.ts:58, 244} + {@code TestingPermissionTool.tsx}。
 *
 * <p><b>WHY（OPD-10 建注册桩）</b>: CC 中 TestingPermissionTool 在 getAllBaseTools 数组内
 * 按 {@code process.env.NODE_ENV === 'test'} 注册（{@code ...(process.env.NODE_ENV === 'test'
 * ? [TestingPermissionTool] : [])}，tools.ts:244），仅 test 环境可用。
 * Java 端原无该工具，本类为注册桩。
 *
 * <p><b>门控语义</b>: {@link #isEnabled()} = {@code featureFlags.testingPermission()}
 * （{@code NODE_ENV==='test'} gate · CC tools.ts:244），默认关。
 * 注意 CC {@code TestingPermissionTool.tsx} isEnabled() 恒 {@code "production"==='test'} 即恒 false
 * （测试专用工具），Java 按 NODE_ENV=test 门控注册点语义对齐（语义差异登记 B5 concerns）。
 *
 * <p><b>inputSchema</b>: CC {@code TestingPermissionTool.tsx:13 inputSchema = z.strictObject({})}
 * （空严格对象，拒绝任何键）→ Java 空 objectNode + additionalProperties=false 对齐。
 *
 * <p>CC 原名/行号: {@code TestingPermissionTool} (Open-ClaudeCode/src/tools.ts:58, 244;
 * Open-ClaudeCode/src/tools/testing/TestingPermissionTool.tsx:13)。
 */
public class TestingPermissionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TestingPermissionTool.class);

    /** CC 工具名 · {@code TestingPermissionTool.tsx:13} NAME='TestingPermission'。 */
    public static final String NAME = ToolNameConstants.TESTING_PERMISSION_TOOL_NAME;

    private final FeatureFlags featureFlags;

    public TestingPermissionTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    public TestingPermissionTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
    }

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("TestingPermissionTool.name(): 返回 CC 工具名 TESTING_PERMISSION_TOOL_NAME='TestingPermission'（对齐 TestingPermissionTool.tsx:13）");
        }
        return NAME;
    }

    @Override
    public String description() {
        return "Test tool that always asks for permission before executing (stub). 未实现能力——由 NODE_ENV==='test' 门控占位注册。";
    }

    @Override
    public JsonNode inputSchema() {
        // 对齐 CC TestingPermissionTool.tsx:13 z.strictObject({})：空严格对象（拒绝任意键）→
        // 空 objectNode + additionalProperties=false。
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = this.featureFlags.testingPermission();
        if (log.isDebugEnabled()) {
            log.debug("TestingPermissionTool.isEnabled() = {}（NODE_ENV==='test' 门控，CC tools.ts:244）", enabled);
        }
        return enabled;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        log.warn("[TestingPermissionTool] stub execute invoked: id={}（未实现能力 fail-loud，NODE_ENV==='test' 门控注册桩）", call.id());
        return ToolResult.error(call.id(),
            "TestingPermission 工具能力未实现（OPD-10 注册桩占位）。如需真实现请对齐 CC TestingPermissionTool 行为后落地。");
    }
}
