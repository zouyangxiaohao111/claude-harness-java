package com.nexusai.application.agent.subagent;

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
 * Session B P0-2 · {@link createSubagentContext#create} 父→子字段透传契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * CC {@code utils/forkedAgent.ts:345-462 createSubagentContext} 把父 ctx 的关键字段
 * （permissionContext / permissionMode / mcpClients / abortController / messages /
 * agentType / effectiveCwd / isNonInteractiveSession / renderedSystemPrompt）
 * 透传给子 ctx。Java 端原实现只传 6/15 字段（§F.5.4 G4 探查文档），导致：
 * <ol>
 *   <li><b>子 Agent 拿不到父 permissionContext</b> → 子 Agent 所有权限检查都退化到 default mode，
 *       PermissionDecisionReason 6/11 type 触发率为 0%</li>
 *   <li><b>子 Agent 拿不到父 abortController</b> → 用 NOOP 兜底，父 abort() 不会传播到子 Agent，
 *       父 cancel 时子 Agent 继续跑（资源泄漏 + UI 残留）</li>
 *   <li><b>子 Agent 拿不到父 messages / mcpClients / renderedSystemPrompt</b> → 子 Agent
 *       无法继续父 conversation + 看不到 MCP 工具 + system prompt 漂移（行为对齐破缺）</li>
 * </ol>
 *
 * <p>本测试覆盖 9 字段透传契约 + abortController 三态（sync 共享 / async 独立 / 默认 createChild），测试用例：
 * <ol>
 *   <li>{@code test_permissionContext_propagatedFromParent} · permissionContext（最关键）</li>
 *   <li>{@code test_permissionMode_mcpClients_isNonInteractive_propagatedFromParent} · 3 字段</li>
 *   <li>{@code test_renderedSystemPrompt_effectiveCwd_propagatedFromParent} · 2 环境字段</li>
 *   <li>{@code test_abortController_syncSharesParentReference} · sync: 显式 override=父引用（CC runAgent.ts:527-528）</li>
 *   <li>{@code test_abortController_asyncGetsIndependentUnlinkedController} · async: 显式 override=独立控制器（CC runAgent.ts:526-527）</li>
 *   <li>{@code test_abortController_defaultNoOverride_createChildLinkedToParent} · 默认: 父.createChild()（CC forkedAgent.ts:354）</li>
 *   <li>{@code test_messages_propagatedAndAgentTypeFromDefinition} · messages + agentType</li>
 * </ol>
 *
 * <p>7/0/0/0 是验收硬指标（CLAUDE.md 规则十二）：7 个测试全 PASS，0 FAIL/ERROR/SKIP。
 * <p>[B 返工 R-1] async 独立断言为 RED→GREEN 双证测试：修复前 create() 无 async 分支，
 * 忽略 overrides.abortController() 恒共享父引用，async 测试必失败；修复后独立控制器透传。
 */
@DisplayName("Session B P0-2 · createSubagentContext 父→子 9 字段透传契约")
class SubagentContextFieldPropagationTest {

    private static final UUID PARENT_AGENT_ID = UUID.randomUUID();
    private static final String PARENT_SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final String PARENT_PROMPT = "父 Agent 已渲染的 system prompt 字节（与父 prompt cache 绑定）";
    private static final Path PARENT_CWD = Paths.get("/tmp/parent-workdir");
    private static final String CHILD_AGENT_TYPE = "fork";

    /**
     * 构造一个"全字段非默认值"父 ctx · 用于检验子 ctx 是否真正继承了父的 9 个字段.
     *
     * <p>用 Stage 3.4 45 字段 canonical 构造器（避开兼容 ctor 的 null 兜底）保证
     * 所有 Stage 3.2/3.3/3.4 字段可控.
     */
    private ToolUseContext buildParentContextWithAllFieldsSet() {
        // parentPermissionContext: 用 strict() 工厂但 mode=BYPASS 标记身份（避免与子 default 混淆）
        ToolPermissionContext parentPermCtx = ToolPermissionContext.strict(PermissionMode.BYPASS_PERMISSIONS);
        // parentMessages: 2 条消息（user + assistant）· 用于验证 messages 透传引用同一 List
        List<Object> parentMessages = List.of("user-msg-1", "assistant-msg-1");
        // parentAbort: 独立实例（非 NOOP）· 验证 abortController 透传后等于 ownAbort
        AbortController parentAbort = new AbortController();
        // parentMcpClients: 1 个 MCP server · 用于验证 mcpClients 透传 Map
        Map<String, McpClientRuntime> parentMcpClients = Map.of(
            "mcp-fs", new McpClientRuntime("mcp-fs", "Read", null)
        );

        // 18-arg ctor (含 readFileState) · Stage 3.2 C2 + Stage 3.3 UI + Stage 3.4 session 全部传 null
        //   → compact ctor 兜底 (CLAUDE.md 规则三：外科手术式改动不动 18+ 字段)
        return new ToolUseContext(
            PARENT_AGENT_ID,                         // 1  agentId
            PARENT_SESSION_ID,                       // 2  sessionId
            PermissionMode.BYPASS_PERMISSIONS,       // 3  mode
            Map.of(),                                // 4  additionalWorkingDirectories
            List.of(),                               // 5  availableTools
            "task-list-parent",                      // 6  taskListId
            parentAbort,                             // 7  abortController ← 关键
            parentMessages,                          // 8  messages ← 关键
            parentPermCtx,                           // 9  permissionContext ← 关键
            PermissionMode.BYPASS_PERMISSIONS,       // 10 permissionMode ← 关键
            parentMcpClients,                        // 11 mcpClients ← 关键
            true,                                    // 12 isNonInteractiveSession ← 关键
            PARENT_PROMPT,                           // 13 renderedSystemPrompt ← 关键
            PARENT_CWD,                              // 14 effectiveCwd ← 关键
            null,                                    // 15 inProgressToolUseIDs
            Map.of(),                                // 16 toolDecisions
            null,                                    // 17 onCompactProgress
            null                                     // 18 readFileState
        );
    }

    @Test
    @DisplayName("permissionContext: 父 ctx 的 ToolPermissionContext 必须透传到子 ctx（关键权限根因）")
    void test_permissionContext_propagatedFromParent() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();

        ToolUseContext child =
            createChild(parent);

        ToolUseContext childTuc = child;
        // 关键断言: 子 ctx 的 permissionContext 必须等于父的同一实例引用
        //   原 Java 实现: pass null → 子 ctx permissionContext() == null (PermissionDecisionReason 6/11 触发率 0%)
        //   对齐 CC: forkedAgent.ts:445-446 messages/options 透传模式
        assertThat(childTuc.permissionContext())
            .as("子 ctx.permissionContext 必须透传父的 permissionContext 引用（CC forkedAgent.ts:445-446 字段透传模式）")
            .isSameAs(parent.permissionContext());
    }

    @Test
    @DisplayName("permissionMode + mcpClients + isNonInteractiveSession: 3 字段透传（permission context bundle）")
    void test_permissionMode_mcpClients_isNonInteractive_propagatedFromParent() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();

        ToolUseContext child =
            createChild(parent);

        ToolUseContext childTuc = child;
        assertThat(childTuc.permissionMode())
            .as("子 ctx.permissionMode 必须透传父值 BYPASS_PERMISSIONS（原 Java 硬编码 DEFAULT）")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(childTuc.mcpClients())
            .as("子 ctx.mcpClients 必须透传父的 MCP 客户端 Map（原 Java 硬编码 Map.of()）")
            .isSameAs(parent.mcpClients())
            .containsKey("mcp-fs");
        assertThat(childTuc.isNonInteractiveSession())
            .as("子 ctx.isNonInteractiveSession 必须透传父值 true（原 Java 硬编码 false）")
            .isTrue();
    }

    @Test
    @DisplayName("renderedSystemPrompt + effectiveCwd: 2 环境字段透传（fork prompt cache 关键）")
    void test_renderedSystemPrompt_effectiveCwd_propagatedFromParent() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();

        ToolUseContext child =
            createChild(parent);

        ToolUseContext childTuc = child;
        assertThat(childTuc.renderedSystemPrompt())
            .as("子 ctx.renderedSystemPrompt 必须透传父的字节（fork subagent prompt cache 关键，CC Tool.ts:299）")
            .isEqualTo(PARENT_PROMPT);
        assertThat(childTuc.effectiveCwd())
            .as("子 ctx.effectiveCwd 必须透传父的工作目录（原 Java 硬编码 null → 兜底 user.dir）")
            .isEqualTo(PARENT_CWD);
    }

    @Test
    @DisplayName("sync abortController: 显式 override=父引用 → 子 ctx 共享父 AbortController（CC runAgent.ts:527-528）")
    void test_abortController_syncSharesParentReference() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();

        // sync 模式（isAsync=false）: SubagentExecutor 显式传父 abortController 引用 ·
        //   对齐 CC runAgent.ts:527-528 agentAbortController = toolUseContext.abortController
        ToolUseContext child = createSubagentContext.create(
            parent,
            new ToolUseContext.SubagentContextOverrides(
                null, CHILD_AGENT_TYPE, null, parent.abortController(), null, null,
                null, null, null, null, null, null, null, null));

        ToolUseContext childTuc = child;
        assertThat(childTuc.abortController())
            .as("sync 子 ctx.abortController 必须共享父 abortController 引用（CC runAgent.ts:527-528）")
            .isSameAs(parent.abortController())
            .isNotSameAs(AbortController.NOOP);
    }

    @Test
    @DisplayName("async abortController: 显式 override=独立控制器 → 子 ctx 用独立 unlinked 控制器，父 abort 不级联（CC runAgent.ts:526-527）")
    void test_abortController_asyncGetsIndependentUnlinkedController() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();
        AbortController asyncOverride = new AbortController();

        // async 模式（isAsync=true）: SubagentExecutor 显式传 new AbortController() ·
        //   对齐 CC runAgent.ts:526-527 async → new AbortController()（unlinked, 独立运行）
        ToolUseContext child = createSubagentContext.create(
            parent,
            new ToolUseContext.SubagentContextOverrides(
                null, CHILD_AGENT_TYPE, null, asyncOverride, null, null,
                null, null, null, null, null, null, null, null));

        ToolUseContext childTuc = child;
        assertThat(childTuc.abortController())
            .as("async 子 ctx.abortController 必须 = 调用方传入的独立控制器（CC runAgent.ts:526-527）")
            .isSameAs(asyncOverride)
            .isNotSameAs(parent.abortController())
            .isNotSameAs(AbortController.NOOP);

        // unlinked 语义: 父 abort 不得级联到 async 子控制器（CC runAgent.ts:522-523 "runs independently"）
        parent.abortController().abort("parent-cancel");
        assertThat(asyncOverride.isCancelled())
            .as("async 子控制器必须独立: 父 abort 不级联（CC runAgent.ts:522-523 unlinked）")
            .isFalse();
    }

    @Test
    @DisplayName("默认 abortController: 无显式 override + 无 share 标志 → 父.createChild()，父 abort 级联到子（CC forkedAgent.ts:354）")
    void test_abortController_defaultNoOverride_createChildLinkedToParent() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();

        // 无 abortController override + shareAbortController=null → CC forkedAgent.ts:350-354 第三段
        //   createChildAbortController(parent) — 独立对象但父 abort 级联（CC utils/abortController.ts:67-95）
        ToolUseContext child = createChild(parent);

        ToolUseContext childTuc = child;
        assertThat(childTuc.abortController())
            .as("默认子 ctx.abortController 必须是父.createChild() 新实例（CC forkedAgent.ts:354，非共享引用）")
            .isNotSameAs(parent.abortController())
            .isNotSameAs(AbortController.NOOP);

        // createChild 级联语义: 父 abort → 子立即 abort
        parent.abortController().abort("parent-cancel");
        assertThat(childTuc.abortController().isCancelled())
            .as("createChild 子控制器必须随父 abort 级联取消（CC utils/abortController.ts:67-95）")
            .isTrue();
    }

    @Test
    @DisplayName("messages + agentType: messages 透传父 ctx（继续父 conversation）；agentType 来自 agent definition 参数")
    void test_messages_propagatedAndAgentTypeFromDefinition() {
        ToolUseContext parent = buildParentContextWithAllFieldsSet();

        ToolUseContext child =
            createChild(parent);

        ToolUseContext childTuc = child;
        // 透传校验: messages 字段是 List<?> 通配符 — 用 size + get(0) 内容比对
        //   不能直接 containsExactly(String...) 因 List<?> element type 推断丢失
        assertThat(childTuc.messages())
            .as("子 ctx.messages 必须透传父的 messages 列表（原 Java 硬编码 List.of()）")
            .hasSize(2);
        assertThat(childTuc.messages().get(0))
            .as("子 ctx.messages[0] 应透传父的 user-msg-1")
            .isEqualTo("user-msg-1");
        assertThat(childTuc.messages().get(1))
            .as("子 ctx.messages[1] 应透传父的 assistant-msg-1")
            .isEqualTo("assistant-msg-1");
        assertThat(childTuc.agentType())
            .as("子 ctx.agentType 必须 = 创建参数 CHILD_AGENT_TYPE='fork'（原 Java 硬编码 null）")
            .isEqualTo(CHILD_AGENT_TYPE);
    }

    /**
     * 2 参 create (parent + overrides) 便捷构造 · [B-1] 6 参 deprecated 壳删除后的唯一入口.
     *
     * <p>overrides 仅带 agentType=CHILD_AGENT_TYPE (CC forkedAgent.ts:449 agentType 仅 override),
     * 其余字段 null → 从父继承 / create() 内部决策（abortController 默认走 CC :354
     * createChildAbortController(parent)，messages 继承父、readFileState 从父 clone）。
     * [B 返工 R-2] 14 参 canonical（10/11/12 参向后兼容便利构造器已删除，无兼容壳）。
     */
    private static ToolUseContext createChild(ToolUseContext parent) {
        return createSubagentContext.create(
            parent,
            new ToolUseContext.SubagentContextOverrides(
                null, CHILD_AGENT_TYPE, null, null, null, null,
                null, null, null, null, null, null, null, null));
    }
}