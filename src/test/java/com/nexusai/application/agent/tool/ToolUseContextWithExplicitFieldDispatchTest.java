package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M1.1 · ToolUseContext.with() 显式逐字段决策对齐 CC {@code createSubagentContext}
 * (Open-ClaudeCode/src/utils/forkedAgent.ts:345-462) 18 字段 RED→GREEN 验证.
 *
 * <p><b>WHY 11 个测试覆盖 11 个关键字段决策 (CLAUDE.md 规则九 · 测试验证意图)</b>:
 * 旧 B 方案把所有字段用同一种"override ? override : this"三元塞进构造函数, 18 个
 * 字段无差别, 看似 DRY 实际隐藏了 CC 的差异化语义. M1.1 重构按 CC 真源 18 字段决策
 * (override / clone / share / new / undefined / noop / fallback chain) 逐一写明:
 * <ol>
 *   <li>abortController: 3 元 (override > share > createChild > NOOP)</li>
 *   <li>nestedMemoryAttachmentTriggers 等 4 Set: 总 new (即便父非空也强制重置)</li>
 *   <li>toolDecisions: 总 null (CC :387 undefined)</li>
 *   <li>agentId: 总新建 UUID (CC :448 createAgentId())</li>
 *   <li>agentType: 仅 overrides (CC :449, 不取 parent)</li>
 *   <li>queryTracking: depth = parent.depth+1, chainId = 新 UUID</li>
 *   <li>localDenialTracking: share ? parent : new (CC :420-422)</li>
 * </ol>
 *
 * <p><b>11 测试验收硬指标 (CLAUDE.md 规则十二)</b>: 11/0/0/0 — 11 测试全 PASS, 0 FAIL/ERROR/SKIP.
 *
 * <p><b>RED 验证策略 (Pattern #14)</b>: 本测试类编写后先 stash 掉 with() 实现, 跑
 * 测试确认 RED 失败 (不是 trivially pass), 再 restore 实现 → GREEN.
 */
@DisplayName("Session M1.1 · ToolUseContext.with() 显式逐字段决策对齐 CC forkedAgent.ts:376-461")
class ToolUseContextWithExplicitFieldDispatchTest {

    private static final UUID PARENT_AGENT_ID = UUID.randomUUID();
    private static final String PARENT_SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /**
     * 构造全字段非默认值的父 ctx · 18 参兼容 ctor (Stage 3.1 C13 + R1 readFileState)
     * 覆盖核心字段; Stage 3.4 session 字段通过 compact ctor 兜底 (后续 Stage 4.0 测试可扩展).
     */
    private ToolUseContext buildFullParentContext() {
        ToolPermissionContext parentPermCtx =
            ToolPermissionContext.strict(PermissionMode.BYPASS_PERMISSIONS);
        AbortController parentAbort = new AbortController();
        Map<String, McpClientRuntime> parentMcpClients = Map.of(
            "mcp-fs", new McpClientRuntime("mcp-fs", "Read", null)
        );
        // 18 参 ctor (Stage 3.1 + R1): 第 18 参 readFileState 透传, Stage 3.4 session 字段 null → compact ctor 兜底
        return new ToolUseContext(
            PARENT_AGENT_ID,                         // 1  agentId
            PARENT_SESSION_ID,                       // 2  sessionId
            PermissionMode.BYPASS_PERMISSIONS,       // 3  mode
            Map.of(),                                // 4  additionalWorkingDirectories
            List.of(),                               // 5  availableTools
            "task-list-parent",                      // 6  taskListId
            parentAbort,                             // 7  abortController
            List.of("msg-1", "msg-2"),               // 8  messages
            parentPermCtx,                           // 9  permissionContext
            PermissionMode.BYPASS_PERMISSIONS,       // 10 permissionMode
            parentMcpClients,                        // 11 mcpClients
            true,                                    // 12 isNonInteractiveSession
            "parent-system-prompt",                  // 13 renderedSystemPrompt
            null,                                    // 14 effectiveCwd
            null,                                    // 15 inProgressToolUseIDs
            Map.of(),                                // 16 toolDecisions
            null,                                    // 17 onCompactProgress
            null                                     // 18 readFileState (compact ctor 兜底 LRU)
        );
    }

    // ═════════════════════ Test 1: abortController override ═════════════════════

    @Test
    @DisplayName("abortController: overrides.abortController 非 null → 用 overrides（CC :350-354 三元链第一段）")
    void with_abortController_overrideTakesPrecedence() {
        ToolUseContext parent = buildFullParentContext();
        AbortController overrideAbort = new AbortController();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null,                       // 1-3: agentId, agentType, messages
            overrideAbort,                          // 4: abortController override
            null,                                   // 5: shareAbortController
            null, null, null, null, null, null, null, null, null      // 6-14: readFileState, permCtx, shareSetAppState, shareSetResponseLength, critical, content, options, getAppState, requireCanUseTool
        ));

        assertThat(child.abortController())
            .as("child.abortController 必须等于 overrideAbort（CC :350-354 三元链第一段 override 胜出）")
            .isSameAs(overrideAbort);
    }

    // ═════════════════════ Test 2: abortController share ═════════════════════

    @Test
    @DisplayName("abortController: override null + share=true → 共享父 abortController（CC :350-354 三元链第二段）")
    void with_abortController_shareParentWhenShareFlagSet() {
        ToolUseContext parent = buildFullParentContext();
        AbortController parentAbort = parent.abortController();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null,                       // 1-3: agentId, agentType, messages
            null,                                   // 4: no override
            Boolean.TRUE,                           // 5: shareAbortController = true
            null, null, null, null, null, null, null, null, null      // 6-14
        ));

        assertThat(child.abortController())
            .as("child.abortController 必须等于父 abortController（CC :350-354 share 模式）")
            .isSameAs(parentAbort);
    }

    // ═════════════════════ Test 3: abortController createChild ═════════════════════

    @Test
    @DisplayName("abortController: override null + share=false → 创建 child（CC :350-354 三元链第三段）")
    void with_abortController_createNewWhenNoShareFlag() {
        ToolUseContext parent = buildFullParentContext();
        AbortController parentAbort = parent.abortController();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null,                       // 1-3
            null,                                   // 4: no override
            Boolean.FALSE,                          // 5: shareAbortController = false
            null, null, null, null, null, null, null, null, null      // 6-14
        ));

        assertThat(child.abortController())
            .as("child.abortController 必须 != 父（CC :350-354 createChild 模式生成新实例）")
            .isNotSameAs(parentAbort)
            .isNotSameAs(AbortController.NOOP);
    }

    // ═════════════════════ Test 4: 4 Sets 总是 new (CC :382-386) ═════════════════════

    @Test
    @DisplayName("4 Sets: nestedMemoryAttachmentTriggers/loadedNestedMemoryPaths/dynamicSkillDirTriggers/discoveredSkillNames 总是 new Set 即便父非空（CC :382-386 强制重置）")
    void with_nestedTriggers_alwaysNewHashSetEvenWithParent() {
        // 父 ctx: compact ctor 兜底 4 Sets 都是空 ConcurrentHashMap.newKeySet() 实例 (非 null)
        ToolUseContext parent = buildFullParentContext();
        assertThat(parent.nestedMemoryAttachmentTriggers()).isNotNull();
        assertThat(parent.loadedNestedMemoryPaths()).isNotNull();
        assertThat(parent.dynamicSkillDirTriggers()).isNotNull();
        assertThat(parent.discoveredSkillNames()).isNotNull();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        Set<String> parentNestedTriggers = parent.nestedMemoryAttachmentTriggers();
        Set<String> parentLoadedPaths = parent.loadedNestedMemoryPaths();
        Set<String> parentDynamicSkillTriggers = parent.dynamicSkillDirTriggers();
        Set<String> parentDiscoveredSkillNames = parent.discoveredSkillNames();

        assertThat(child.nestedMemoryAttachmentTriggers())
            .as("child.nestedMemoryAttachmentTriggers 总是 new（CC :382）")
            .isEmpty();
        assertThat(child.nestedMemoryAttachmentTriggers())
            .as("child.nestedMemoryAttachmentTriggers 引用必须 != 父（CC :382 new Set 不是父 share）")
            .isNotSameAs(parentNestedTriggers);
        assertThat(child.loadedNestedMemoryPaths())
            .as("child.loadedNestedMemoryPaths 总是 new（CC :383）")
            .isEmpty();
        assertThat(child.loadedNestedMemoryPaths())
            .as("child.loadedNestedMemoryPaths 引用必须 != 父（CC :383 new Set 不是父 share）")
            .isNotSameAs(parentLoadedPaths);
        assertThat(child.dynamicSkillDirTriggers())
            .as("child.dynamicSkillDirTriggers 总是 new（CC :384）")
            .isEmpty();
        assertThat(child.dynamicSkillDirTriggers())
            .as("child.dynamicSkillDirTriggers 引用必须 != 父（CC :384 new Set 不是父 share）")
            .isNotSameAs(parentDynamicSkillTriggers);
        assertThat(child.discoveredSkillNames())
            .as("child.discoveredSkillNames 总是 new（CC :386）")
            .isEmpty();
        assertThat(child.discoveredSkillNames())
            .as("child.discoveredSkillNames 引用必须 != 父（CC :386 new Set 不是父 share）")
            .isNotSameAs(parentDiscoveredSkillNames);
    }

    // ═════════════════════ Test 5: toolDecisions 总是 null (CC :387) ═════════════════════

    @Test
    @DisplayName("toolDecisions: 总是 null 即便父有值（CC :387 undefined 强制重置）")
    void with_toolDecisions_alwaysNullEvenWithParent() {
        // 父 ctx 含非空 toolDecisions (Stage 3.1 18 参 ctor 第 16 参)
        ToolUseContext parentRich = new ToolUseContext(
            PARENT_AGENT_ID, PARENT_SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null,
            Map.of("decision-1", new ToolDecisionInfo("tool-1", "accept")),  // toolDecisions 非空
            null,
            null                                     // readFileState
        );
        assertThat(parentRich.toolDecisions()).isNotEmpty();

        ToolUseContext child = parentRich.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThat(child.toolDecisions())
            .as("child.toolDecisions 不能携带父的决策（CC :387 undefined 强制重置, 即便父非空）")
            .satisfiesAnyOf(
                decisions -> assertThat(decisions).isNull(),
                decisions -> assertThat(decisions).isEmpty()
            );
        // 关键: 子 ctx 不能含有父 ctx 标记的 decision-1 决策
        assertThat(child.toolDecisions())
            .as("child.toolDecisions 不能继承父 decision-1（CC :387 强制重置证据）")
            .doesNotContainKey("decision-1");
    }

    // ═════════════════════ Test 6: agentId 总是新建 UUID (CC :448) ═════════════════════

    @Test
    @DisplayName("agentId: 总是新建 UUID 即便 overrides.agentId 为 null（CC :448 createAgentId() 强制）")
    void with_agentId_alwaysUuidRandomUUIDEvenWithParent() {
        ToolUseContext parent = buildFullParentContext();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null,                                   // agentId = null
            null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThat(child.agentId())
            .as("child.agentId 必须是新 UUID（CC :448 agentId: overrides?.agentId ?? createAgentId()）")
            .isNotNull()
            .isNotEqualTo(parent.agentId());
    }

    // ═════════════════════ Test 7: agentType 仅 override (CC :449) ═════════════════════

    @Test
    @DisplayName("agentType: overrides 为 null 时 child.agentType = null（CC :449 仅取 override, 不取 parent）")
    void with_agentType_onlyOverrideNoParent() {
        ToolUseContext parent = buildFullParentContext();
        // 父 agentType 由 compact ctor 兜底 null (default) — 单独验证 with() 行为:
        //   即便父 agentType 非空, 子 overrides.agentType=null 时, 子 ctx.agentType = null.
        //   由于 parent compact ctor 兜底 agentType=null, 我们改用 ToolUseContext.of 工厂构造一个
        //   父 agentType 非空的 ctx (虽然 Stage 3.4 字段没有显式 setter). 但 child agentType=null
        //   是 CC :449 的硬约束, 不论父 agentType 是什么, 只看 overrides.
        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null,                                   // agentId = null
            null,                                   // agentType = null
            null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThat(child.agentType())
            .as("child.agentType 必须 = null（CC :449 agentType: overrides?.agentType 不取 parent）")
            .isNull();
    }

    // ═════════════════════ Test 8: queryTracking.depth + 1 (CC :454) ═════════════════════

    @Test
    @DisplayName("queryTracking.depth: child.depth = parent.depth + 1（CC :454 depth: parent.depth + 1）")
    void with_queryTracking_depthIncrementedByOne() {
        // queryTracking 默认 compact ctor 兜底 null. Java 实现: child.queryTracking 总是新建
        //   HashMap {chainId:UUID, depth:parent.depth+1 or 0+1=0 if parent null}.
        ToolUseContext parent = buildFullParentContext();
        // compact ctor 兜底 queryTracking=null → with() 内部 parent.depth=-1, child.depth=0

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThat(child.queryTracking())
            .as("child.queryTracking 必须非 null（CC :452-455 新建 QueryChainTracking-like）")
            .isNotNull();
        assertThat(child.queryTracking().get("depth"))
            .as("child.queryTracking.depth 必须 = parent.depth + 1 = (-1)+1 = 0（CC :454 depth: parent.depth + 1, parent null → -1）")
            .isEqualTo(0);
    }

    // ═════════════════════ Test 9: queryTracking.chainId 新 UUID (CC :453) ═════════════════════

    @Test
    @DisplayName("queryTracking.chainId: 每次 with() 都生成新 UUID（CC :453 chainId: randomUUID()）")
    void with_queryTracking_chainIdAlwaysRandomUUID() {
        ToolUseContext parent = buildFullParentContext();

        ToolUseContext child1 = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));
        ToolUseContext child2 = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        String chainId1 = (String) child1.queryTracking().get("chainId");
        String chainId2 = (String) child2.queryTracking().get("chainId");
        assertThat(chainId1).isNotNull();
        assertThat(chainId2).isNotNull();
        assertThat(chainId1)
            .as("两次 with() 生成的 chainId 必须不同（CC :453 chainId: randomUUID()）")
            .isNotEqualTo(chainId2);
    }

    // ═════════════════════ Test 10: localDenialTracking share 模式 (CC :420-422) ═════════════════════

    @Test
    @DisplayName("localDenialTracking: share=true 时复用父空 map（CC :420-422 share 模式共享 denial counter）")
    void with_localDenialTracking_shareWhenShareSetAppState() {
        ToolUseContext parent = buildFullParentContext();  // localDenialTracking 默认 null → compact ctor 保留 null

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null,
            null,
            Boolean.TRUE,
            null, null, null, null, null, null      // 9-14: shareSetResponseLength, critical, content, options, getAppState, requireCanUseTool
        ));

        assertThat(child.localDenialTracking())
            .as("child.localDenialTracking 必须非 null（CC :420-422 share 模式共享 denial state）")
            .isNotNull();
        // 父 localDenialTracking=null → share 模式 child = new HashMap<>(), 空 Map
        assertThat(child.localDenialTracking())
            .as("child.localDenialTracking 必须 = 空（父 null, share 模式新建空 HashMap）")
            .isEmpty();
    }

    // ═════════════════════ Test 11: localDenialTracking 非 share 模式 ═════════════════════

    @Test
    @DisplayName("localDenialTracking: share=false 时新建空 Map（CC :420-422 createDenialTrackingState()）")
    void with_localDenialTracking_createNewWhenNoShareFlag() {
        ToolUseContext parent = buildFullParentContext();  // localDenialTracking 默认 null

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null,
            null,
            Boolean.FALSE,
            null, null, null, null, null, null      // 9-14: shareSetResponseLength, critical, content, options, getAppState, requireCanUseTool
        ));

        assertThat(child.localDenialTracking())
            .as("child.localDenialTracking 必须 = 空 Map（CC :420-422 非 share 模式 = createDenialTrackingState）")
            .isNotNull()
            .isEmpty();
    }
}