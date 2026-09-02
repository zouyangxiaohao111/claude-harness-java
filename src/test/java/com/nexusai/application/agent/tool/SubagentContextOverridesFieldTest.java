package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session S7 · SubagentContextOverrides 三字段补齐 (options/getAppState/requireCanUseTool)
 * RED→GREEN 验证 · 对齐 CC forkedAgent.ts:260-304.
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>:
 * CC {@code SubagentContextOverrides} 13 字段含 options (:262) / getAppState (:274) /
 * requireCanUseTool (:299), Java 旧 record 11 字段缺失这三项 → with() 无法表达 3 种覆写意图
 * (工具集切换 / 权限上下文隔离 / 强制 canUseTool)。本测试验证 S7 补齐后 with() 正确应用。
 *
 * <p><b>字段数断言: 14</b> (CC 13 + Java permissionContext extra, 见 concern S7-6)。
 */
@DisplayName("Session S7 · SubagentContextOverrides 三字段补齐对齐 CC forkedAgent.ts:262/274/299")
class SubagentContextOverridesFieldTest {

    private static final UUID PARENT_AGENT_ID = UUID.randomUUID();
    private static final String PARENT_SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** 构造父 ctx (全字段非默认, 18 参兼容 ctor) — 与 ToolUseContextWithExplicitFieldDispatchTest 同构. */
    private ToolUseContext buildParent() {
        return new ToolUseContext(
            PARENT_AGENT_ID, PARENT_SESSION_ID, PermissionMode.BYPASS_PERMISSIONS,
            Map.of(), List.of(), "task-list-parent", new AbortController(), List.of("msg-1"),
            ToolPermissionContext.strict(PermissionMode.BYPASS_PERMISSIONS),
            PermissionMode.BYPASS_PERMISSIONS, Map.of(), true, "parent-prompt", null, null,
            Map.of(), null, null
        );
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public JsonNode inputSchema() {
                return null; // 测试仅验证 availableTools 覆写, 不调用 execute/inputSchema
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                return new ToolResult<>("ok", null, null, null);
            }
        };
    }

    @Test
    @DisplayName("with(): overrides.options.tools 覆写 child.availableTools（CC forkedAgent.ts:445 options override ?? parent）")
    void with_honorsOptionsOverride_whenPresent() {
        // WHY: CC :262 options?: ToolUseContext['options'] 允许子 agent 覆写 tools 集,
        //   Java 无 options 字段, with() 将 options.tools 映射为 availableTools (concern S7-5 option b).
        ToolUseContext parent = buildParent();
        Tool toolA = tool("Bash");
        Tool toolB = tool("Read");
        Map<String, Object> options = Map.of("tools", List.of(toolA, toolB));

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null,
            options,      // options (12)
            null,         // getAppState (13)
            null          // requireCanUseTool (14)
        ));

        assertThat(child.availableTools())
            .as("child.availableTools 必须 = options.tools (CC :445 override 胜出)")
            .containsExactly(toolA, toolB);
    }

    @Test
    @DisplayName("with(): overrides.getAppState 覆写 child.getAppState（CC forkedAgent.ts:274/358 override ?? parent）")
    void with_honorsGetAppStateOverride_whenPresent() {
        // WHY: CC :274 getAppState 允许子 agent 覆写 (如 createModifiedGetAppState 加 allowed tools),
        //   权限上下文按 agent 隔离; Java 旧实现 with() 恒透传父 getAppState 无法隔离.
        ToolUseContext parent = buildParent();
        Function<Map<String, Object>, Map<String, Object>> overrideGetAppState = s -> {
            Map<String, Object> m = new HashMap<>(s);
            m.put("child-only", true);
            return m;
        };

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null,
            null,                     // options
            overrideGetAppState,      // getAppState
            null                      // requireCanUseTool
        ));

        assertThat(child.getAppState())
            .as("child.getAppState 必须 = override (CC :358 override 胜出)")
            .isSameAs(overrideGetAppState);
        // 父 getAppState 默认 s->s (compact ctor), override 必须不同实例
        assertThat(parent.getAppState())
            .as("父 getAppState 默认 s->s, 与 override 必须不同实例")
            .isNotSameAs(overrideGetAppState);
    }

    @Test
    @DisplayName("with(): overrides.requireCanUseTool=true 强制 child + null 兜底父值（CC forkedAgent.ts:299/460 仅 override）")
    void with_honorsRequireCanUseToolOverride_whenPresent() {
        // WHY: CC :299 requireCanUseTool=true 强制 canUseTool 即使 hooks auto-approve,
        //   用于 speculation overlay file path rewriting; Java :1234 自承"等 Stage 4.0", S7 补齐.
        ToolUseContext parent = buildParent();
        assertThat(parent.requireCanUseTool()).as("父 requireCanUseTool 默认 false").isFalse();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, Boolean.TRUE   // requireCanUseTool = true
        ));
        assertThat(child.requireCanUseTool())
            .as("child.requireCanUseTool 必须 = override true (CC :460 override 胜出)")
            .isTrue();

        // 反向: override null → 兜底父值 (Java primitive boolean 需 parent fallback)
        ToolUseContext childNoOverride = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null
        ));
        assertThat(childNoOverride.requireCanUseTool())
            .as("override null → child 兜底父值 false")
            .isFalse();
    }

    @Test
    @DisplayName("overridesRecord 字段数 = 14（CC 13 + Java permissionContext extra, concern S7-6）")
    void overridesRecord_has14Fields_not11() {
        // WHY: CC 13 字段 (forkedAgent.ts:260-304) + Java permissionContext extra (CC 经 getAppState
        //   透传, forkedAgent.ts:362-374) = 14. 旧 Java 11 字段 = 契约错位, with() 无法表达 3 种覆写意图.
        assertThat(ToolUseContext.SubagentContextOverrides.class.getRecordComponents())
            .as("SubagentContextOverrides 必须含 options/getAppState/requireCanUseTool (14 字段)")
            .hasSize(14);
    }
}
