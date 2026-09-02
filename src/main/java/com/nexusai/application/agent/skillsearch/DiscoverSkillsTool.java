package com.nexusai.application.agent.skillsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DiscoverSkills 工具空壳 · 对齐 CC {@code tools/DiscoverSkillsTool/}（本 checkout 缺失）。
 *
 * <p><b>CC 源缺失硬约束（C-30 · 例外项，TOOLS-07 保留缺失声明）</b>: {@code Open-ClaudeCode/src/tools/DiscoverSkillsTool/}
 * 目录在本 checkout 缺失（已 ls 复验 MISSING，<b>真实缺失</b>）——与 services/skillSearch/*.ts（真源存在）
 * 不同，此处<b>不可</b>blanket 改为「已存在」。其 {@code prompt.js} 被 {@code constants/prompts.ts:90-93}
 * require 消费（唯一消费点）：
 * {@code DISCOVER_SKILLS_TOOL_NAME = feature('EXPERIMENTAL_SKILL_SEARCH') ? require('../tools/DiscoverSkillsTool/prompt.js').DISCOVER_SKILLS_TOOL_NAME : null}
 * —— flag 关闭时工具名常量折叠为 null（DCE）。
 *
 * <p><b>DISCOVER_SKILLS_TOOL_NAME 真实值未知</b>: CC prompt.js 缺失 → 工具名占位常量
 * {@value #DISCOVER_SKILLS_TOOL_NAME} 可能偏离 CC 真实工具名，TODO 待上游源码到位后修正，不伪造。
 *
 * <p><b>feature-gated 注册</b>: 对齐 CC flag-off → {@code DISCOVER_SKILLS_TOOL_NAME===null}
 * （prompts.ts:90-93），Java 端 ToolRegistrationConfig 仅 {@code featureFlags.skillPrefetch()=true}
 * 时注册本工具（默认 ALL_DISABLED → 不注册）；{@link #isEnabled()} 委托 feature flag 双保险。
 */
public class DiscoverSkillsTool implements Tool {

    /**
     * DISCOVER_SKILLS_TOOL_NAME 占位 · CC original: {@code DISCOVER_SKILLS_TOOL_NAME}
     * （prompts.ts:90-93 require DiscoverSkillsTool/prompt.js）。
     *
     * <p>TODO: 真实工具名待上游 tools/DiscoverSkillsTool/ 补充后对齐（当前为占位猜测值，不假定 CC 契约）。
     */
    public static final String DISCOVER_SKILLS_TOOL_NAME = "discover_skills";

    private static final Logger log = LoggerFactory.getLogger(DiscoverSkillsTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** feature flag · 默认全关（对齐 CC EXPERIMENTAL_SKILL_SEARCH flag-off → 工具 null）。 */
    private final FeatureFlags featureFlags;

    public DiscoverSkillsTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    /**
     * @param featureFlags feature flag（{@code skillPrefetch} = EXPERIMENTAL_SKILL_SEARCH 映射）；
     *                     null → ALL_DISABLED（对齐 CC flag-off）。
     */
    public DiscoverSkillsTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
    }

    @Override
    public String name() {
        return DISCOVER_SKILLS_TOOL_NAME;
    }

    @Override
    public String description() {
        // TODO 空壳描述：待上游 tools/DiscoverSkillsTool/ 补充后对齐；不伪造发现能力描述。
        return "Discover skills relevant to the current task (skill-search). "
            + "C-30 skeleton: CC source missing, feature-gated.";
    }

    @Override
    public JsonNode inputSchema() {
        // TODO 空壳 inputSchema：待上游 DiscoverSkillsTool/index.ts 补充后对齐；当前无参数。
        return JSON.createObjectNode().put("type", "object")
            .set("properties", JSON.createObjectNode());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        // TODO 空壳 execute：CC 源缺失 → 返回占位结果，不伪造发现行为
        //   （参考 CS-DEL-1 stripReinjectedAttachments 无条件 no-op 前科）。
        if (log.isDebugEnabled()) {
            log.debug("[DiscoverSkillsTool] execute 占位返回 · CC 源 tools/DiscoverSkillsTool/ 缺失，feature-gated · prompts.ts:90-93");
        }
        return ToolResult.success(call.id(),
            "Skill discovery is not yet implemented (C-30 skeleton, feature-gated).");
    }

    @Override
    public boolean isEnabled() {
        // 委托 feature flag：EXPERIMENTAL_SKILL_SEARCH 映射 = FeatureFlags.skillPrefetch()
        // （对齐 CC flag-off 时工具不存在 → 不可用）。
        return featureFlags.skillPrefetch();
    }
}
