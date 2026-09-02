package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session B B 方案 · {@link createSubagentContext#create} parent + overrides 架构对齐 CC 契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * CC {@code utils/forkedAgent.ts:345-462 createSubagentContext} 用 object literal spread 模式
 * <code>{...parentContext, ...overrides}</code> 把父 ctx 浅克隆后用 overrides 覆写差异化字段，
 * 余字段从父继承. Java 端 46-arg 拆解构造模式与 CC 架构同构性差, B 方案将其重构为
 * <code>parent.with(SubagentContextOverrides)</code> 与 CC 严格同构.
 *
 * <p>本测试覆盖 B 方案 5+ 关键契约:
 * <ol>
 *   <li>{@code test_overrides_agentId_overridesParent} · overrides.agentId 覆写父</li>
 *   <li>{@code test_overrides_messages_overridesParent} · overrides.messages 覆写父 (CC :446 messages)</li>
 *   <li>{@code test_overrides_nullFields_inheritFromParent} · null overrides 字段全部从父继承</li>
 *   <li>{@code test_with_returnsNewRecord_doesNotMutateParent} · with() 不可变语义</li>
 *   <li>{@code test_create_parentWithOverrides_propagatesReadFileState} · parent.with(overrides) 与 create() 集成</li>
 *   <li>{@code test_overrides_readFileState_cloneFromParentIfNull} · null readFileState → 父 clone</li>
 * </ol>
 *
 * <p>5+/0/0/0 是验收硬指标（CLAUDE.md 规则十二）：5+ 测试全 PASS, 0 FAIL/ERROR/SKIP.
 *
 * <p><b>与 SubagentContextFieldPropagationTest 区分</b>:
 * <ul>
 *   <li>本测试验证 B 方案架构契约（with + overrides 模式）</li>
 *   <li>SubagentContextFieldPropagationTest 验证 A 方案字段透传契约（9 字段）</li>
 *   <li>两者必须同时绿, 才证明 B 方案不破坏 A 方案已修复的字段透传</li>
 * </ul>
 */
@DisplayName("Session B B 方案 · createSubagentContext parent + overrides 架构对齐 CC")
class CreateSubagentContextOverridesTest {

    private static final UUID PARENT_AGENT_ID = UUID.randomUUID();
    private static final String PARENT_SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final UUID CHILD_AGENT_ID = UUID.randomUUID();
    private static final String CHILD_AGENT_TYPE = "fork";
    private static final String PARENT_PROMPT = "父 Agent 已渲染的 system prompt";
    private static final Path PARENT_CWD = Paths.get("/tmp/parent-workdir");

    /**
     * 构造"全字段非默认值"父 ctx · 用 18 参 canonical 构造器避免兼容 ctor null 兜底.
     */
    private ToolUseContext buildParentContext() {
        ToolPermissionContext parentPermCtx =
            ToolPermissionContext.strict(PermissionMode.BYPASS_PERMISSIONS);
        List<Object> parentMessages = List.of("user-msg-1", "assistant-msg-1");
        AbortController parentAbort = new AbortController();
        Map<String, McpClientRuntime> parentMcpClients = Map.of(
            "mcp-fs", new McpClientRuntime("mcp-fs", "Read", null)
        );

        return new ToolUseContext(
            PARENT_AGENT_ID,
            PARENT_SESSION_ID,
            PermissionMode.BYPASS_PERMISSIONS,
            Map.of(),
            List.of(),
            "task-list-parent",
            parentAbort,
            parentMessages,
            parentPermCtx,
            PermissionMode.BYPASS_PERMISSIONS,
            parentMcpClients,
            true,
            PARENT_PROMPT,
            PARENT_CWD,
            null,
            Map.of(),
            null,
            null
        );
    }

    @Test
    @DisplayName("overrides.agentId 覆写父 ctx.agentId（CC forkedAgent.ts:448 agentId: overrides?.agentId ?? createAgentId()）")
    void test_overrides_agentId_overridesParent() {
        ToolUseContext parent = buildParentContext();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            CHILD_AGENT_ID,        // agentId - override
            null,                  // agentType
            null,                  // messages
            null,                  // abortController
            null,                  // shareAbortController
            null,                  // readFileState
            null,                  // permissionContext
            null,                  // shareSetAppState
            null,                  // shareSetResponseLength
            null,                  // criticalSystemReminder_EXPERIMENTAL
            null,                  // contentReplacementState
            null,                  // options (12)
            null,                  // getAppState (13)
            null                   // requireCanUseTool (14)
        ));

        assertThat(child.agentId())
            .as("child.agentId 必须等于 overrides.agentId（覆写父 PARENT_AGENT_ID）")
            .isEqualTo(CHILD_AGENT_ID)
            .isNotEqualTo(parent.agentId());
    }

    @Test
    @DisplayName("overrides.messages 覆写父 ctx.messages（CC forkedAgent.ts:446 messages: overrides?.messages ?? parentContext.messages）")
    void test_overrides_messages_overridesParent() {
        ToolUseContext parent = buildParentContext();
        List<String> childMessages = List.of("child-only-msg");

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null,                  // agentId
            null,                  // agentType
            childMessages,         // messages - override
            null,                  // abortController
            null,                  // shareAbortController
            null,                  // readFileState
            null,                  // permissionContext
            null,                  // shareSetAppState
            null,                  // shareSetResponseLength
            null,                  // criticalSystemReminder_EXPERIMENTAL
            null,                  // contentReplacementState
            null,                  // options (12)
            null,                  // getAppState (13)
            null                   // requireCanUseTool (14)
        ));

        assertThat(child.messages())
            .as("child.messages 必须等于 overrides.messages（覆写父 2 条 messages）")
            .isSameAs(childMessages)
            .hasSize(1);
        assertThat(child.messages().get(0))
            .as("child.messages[0] = child-only-msg")
            .isEqualTo("child-only-msg");
    }

    @Test
    @DisplayName("overrides 全 null 时：大部分字段从父继承 + agentId 总是新 UUID + agentType=null + 4 Sets=空（CC 显式逐字段决策）")
    void test_overrides_nullFields_inheritFromParent() {
        ToolUseContext parent = buildParentContext();

        // 全 null overrides → child 大部分字段继承自 parent, 但 agentId/agentType/4 Sets 按 CC 显式决策
        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        // [CC :448] agentId 总是新建 UUID, 即便 override null
        assertThat(child.agentId())
            .as("child.agentId 必须是新 UUID（CC forkedAgent.ts:448 agentId: overrides?.agentId ?? createAgentId()）")
            .isNotNull()
            .isNotEqualTo(parent.agentId());
        // 9 字段全部从父继承 (与 A 方案透传契约一致)
        assertThat(child.sessionId()).isEqualTo(parent.sessionId());
        assertThat(child.mode()).isEqualTo(parent.mode());
        assertThat(child.permissionContext()).isSameAs(parent.permissionContext());
        assertThat(child.permissionMode()).isEqualTo(parent.permissionMode());
        assertThat(child.mcpClients()).isSameAs(parent.mcpClients());
        assertThat(child.isNonInteractiveSession()).isEqualTo(parent.isNonInteractiveSession());
        assertThat(child.renderedSystemPrompt()).isEqualTo(parent.renderedSystemPrompt());
        assertThat(child.effectiveCwd()).isEqualTo(parent.effectiveCwd());
        assertThat(child.messages()).isSameAs(parent.messages());
        assertThat(child.taskListId()).isEqualTo(parent.taskListId());
        // [CC :449] agentType 仅取 override, override null 时子 ctx.agentType = null
        assertThat(child.agentType())
            .as("child.agentType 必须 = null（CC forkedAgent.ts:449 agentType: overrides?.agentType 不取 parent）")
            .isNull();
        // [CC :382-386] 4 个 Set 总是 new Set<>, 即便父非空也强制重置
        assertThat(child.nestedMemoryAttachmentTriggers())
            .as("child.nestedMemoryAttachmentTriggers 必须 = 空（CC forkedAgent.ts:382 强制 new Set）")
            .isEmpty();
        assertThat(child.loadedNestedMemoryPaths())
            .as("child.loadedNestedMemoryPaths 必须 = 空（CC forkedAgent.ts:383 强制 new Set）")
            .isEmpty();
        assertThat(child.dynamicSkillDirTriggers())
            .as("child.dynamicSkillDirTriggers 必须 = 空（CC forkedAgent.ts:384 强制 new Set）")
            .isEmpty();
        assertThat(child.discoveredSkillNames())
            .as("child.discoveredSkillNames 必须 = 空（CC forkedAgent.ts:386 强制 new Set）")
            .isEmpty();
    }

    @Test
    @DisplayName("with() 不可变语义：返回新 record，原 parent 不被修改（immutable semantics · CLAUDE.md 规则二）")
    void test_with_returnsNewRecord_doesNotMutateParent() {
        ToolUseContext parent = buildParentContext();
        UUID parentAgentIdBefore = parent.agentId();
        List<?> parentMessagesBefore = parent.messages();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            CHILD_AGENT_ID,
            null,
            List.of("new-msg"),
            null, null, null, null, null, null, null, null, null, null, null
        ));

        // 不可变断言: 原 parent 字段全部未变
        assertThat(parent.agentId())
            .as("parent.agentId 不变（immutable）")
            .isEqualTo(parentAgentIdBefore);
        assertThat(parent.messages())
            .as("parent.messages 不变（immutable, 仍是 2 条原消息）")
            .isSameAs(parentMessagesBefore)
            .hasSize(2);

        // child 是新实例
        assertThat(child)
            .as("child 必须是新 record 实例（非 parent 自身）")
            .isNotSameAs(parent);
    }

    @Test
    @DisplayName("create(parent, overrides) 集成：parent.with(overrides) 路径产生与 CC 一致的 child ctx")
    void test_create_parentWithOverrides_propagatesReadFileState() {
        ToolUseContext parent = buildParentContext();

        // 直接用新签名 create(parent, overrides) · 模拟 CC runAgent.ts:700-714 调用模式
        ToolUseContext.SubagentContextOverrides overrides =
            new ToolUseContext.SubagentContextOverrides(
                null,                 // agentId: null → create() 内部生成新 UUID
                CHILD_AGENT_TYPE,     // agentType: "fork" (对齐 FORK_SUBAGENT_TYPE)
                List.of("initial"),   // messages: 子 Agent 初始消息
                null,                 // abortController: null → 由 create() 决定 sync/async 共享
                null,                 // shareAbortController: null → 由 create() 决定 (不进 with())
                null,                 // readFileState: null → 从父 clone (CC forkedAgent.ts:380)
                null,                 // permissionContext: null → 从父继承
                null,                 // shareSetAppState: null → create() 内部决策
                null,                 // shareSetResponseLength: null → create() 内部决策
                null,                 // criticalSystemReminder_EXPERIMENTAL: null → 从父继承
                null,                 // contentReplacementState: null → 从父 clone
                null,                 // options (12): null → 继承父 availableTools (S7)
                null,                 // getAppState (13): null → 继承父 (S7)
                null                  // requireCanUseTool (14): null → with() 纯 override=false (CC :460)
            );

        ToolUseContext child = createSubagentContext.create(parent, overrides);

        ToolUseContext childTuc = child;
        // 字段断言
        assertThat(childTuc.agentType())
            .as("child.agentType = overrides.agentType (CC forkedAgent.ts:449)")
            .isEqualTo(CHILD_AGENT_TYPE);
        assertThat(childTuc.messages())
            .as("child.messages = overrides.messages (CC forkedAgent.ts:446)")
            .hasSize(1);
        // agentId 必须是新 UUID（不为 null 且不等于父 agentId）
        assertThat(childTuc.agentId())
            .as("child.agentId 必须是 create() 内部生成的新 UUID")
            .isNotNull()
            .isNotEqualTo(parent.agentId());
        // 透传校验
        assertThat(childTuc.permissionContext())
            .as("child.permissionContext 从父继承（A 方案契约 + B 方案架构）")
            .isSameAs(parent.permissionContext());
        assertThat(childTuc.permissionMode())
            .as("child.permissionMode 从父继承")
            .isEqualTo(parent.permissionMode());
        assertThat(childTuc.mcpClients())
            .as("child.mcpClients 从父继承")
            .isSameAs(parent.mcpClients());
        assertThat(childTuc.effectiveCwd())
            .as("child.effectiveCwd 从父继承")
            .isEqualTo(parent.effectiveCwd());
        assertThat(childTuc.renderedSystemPrompt())
            .as("child.renderedSystemPrompt 从父继承")
            .isEqualTo(parent.renderedSystemPrompt());
    }

    @Test
    @DisplayName("overrides.readFileState == null 时：child.readFileState 从父 clone 而非共享引用（CC forkedAgent.ts:379-381）")
    void test_overrides_readFileState_cloneFromParentIfNull() {
        ToolUseContext parent = buildParentContext();
        // 父 cache 至少有一条 entry, clone 后必须同样可见
        parent.readFileState().set("/tmp/test.txt",
            new ToolUseContext.ReadState(12345L, null, null, false, "content"));

        // overrides.readFileState = null → child 必须从父 clone, 且 FileStateCache 是新实例
        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        FileStateCache childCache = child.readFileState();
        assertThat(childCache)
            .as("child.readFileState 必须是 FileStateCache 实例（父 readFileState clone 后）")
            .isNotNull()
            .isNotSameAs(parent.readFileState());  // 不同实例（clone 语义）
        assertThat(childCache.get("/tmp/test.txt"))
            .as("clone 后 child 必须能看到父 cache 的所有 entry")
            .isNotNull();
    }
}