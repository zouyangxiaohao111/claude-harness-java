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
 * CtxInspectTool 注册桩 · 对齐 CC {@code tools.ts:110-111, 222}。
 *
 * <p><b>WHY（OPD-10 建注册桩）</b>: CC 中 {@code CtxInspectTool = feature('CONTEXT_COLLAPSE')
 * ? require(...).CtxInspectTool : null}，flag 关时工具为 {@code null}、不进入
 * getAllBaseTools 数组（{@code ...(CtxInspectTool ? [CtxInspectTool] : [])}，tools.ts:222）。
 * Java 端原无该工具（feature 死代码消除），本类为注册桩。
 *
 * <p><b>门控语义</b>: {@link #isEnabled()} = {@code featureFlags.contextCollapse()}
 * （{@code CONTEXT_COLLAPSE} flag · CC tools.ts:110）。该 flag 与 {@link FeatureFlags}
 * 既有 {@code contextCollapse} 字段同源（同一 CC flag），默认全关 → isEnabled()==false 不暴露。
 *
 * <p>CC 门控原名/行号: {@code CONTEXT_COLLAPSE} (Open-ClaudeCode/src/tools.ts:110-111)。
 * CC 注册点: Open-ClaudeCode/src/tools.ts:222。
 */
public class CtxInspectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CtxInspectTool.class);

    /** CC 工具名 · {@code CtxInspectTool.ts:12} CTX_INSPECT_TOOL_NAME='CtxInspect'（G32③ 修正：CC 真源已就位）。 */
    public static final String NAME = ToolNameConstants.CTX_INSPECT_TOOL_NAME;

    private final FeatureFlags featureFlags;

    /** [V52 X1-3] 压缩配置 DB 实时读源（可 null = 未接线 → 回落 FeatureFlags）。 */
    private com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    public CtxInspectTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    public CtxInspectTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
    }

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源注入（可 null）：DB settings.context_collapse_enabled 覆盖
     * FeatureFlags，null 回落。
     *
     * @param settingsResolver 压缩配置实时读源（可 null）
     */
    public void setSettingsResolver(com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
    }

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("CtxInspectTool.name(): 返回 CC 工具名 CTX_INSPECT_TOOL_NAME='CtxInspect'（对齐 tools.ts:110-111）");
        }
        return NAME;
    }

    @Override
    public String description() {
        return "Context inspect tool (stub). 未实现能力——由 CONTEXT_COLLAPSE feature 门控占位注册。";
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
        // [V52 X1-3] DB settings.context_collapse_enabled 有值覆盖 FeatureFlags（null 回落，零行为变化）
        Boolean dbCc = settingsResolver != null ? settingsResolver.contextCollapseEnabled() : null;
        boolean enabled = dbCc != null ? dbCc : this.featureFlags.contextCollapse();
        if (log.isDebugEnabled()) {
            log.debug("CtxInspectTool.isEnabled() = {}（CONTEXT_COLLAPSE 门控，CC tools.ts:110；"
                    + "DB context_collapse_enabled={}）",
                enabled, dbCc != null ? dbCc : "null→FeatureFlags");
        }
        return enabled;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        log.warn("[CtxInspectTool] stub execute invoked: id={}（未实现能力 fail-loud，CONTEXT_COLLAPSE 门控注册桩）", call.id());
        return ToolResult.error(call.id(),
            "CtxInspect 工具能力未实现（OPD-10 注册桩占位）。如需真实现请对齐 CC CtxInspectTool 行为后落地。");
    }
}
