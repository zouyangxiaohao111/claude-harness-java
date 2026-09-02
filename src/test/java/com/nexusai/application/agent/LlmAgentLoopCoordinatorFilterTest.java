package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolUtils;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [A1] 主循环工具装配路径 coordinator 过滤 · 对齐 CC main.tsx:1872-1877（headless 路径：
 * getTools(permCtx) 之后 applyCoordinatorToolFilter）+ toolPool.ts:35-41。
 *
 * <p>WHY: coordinator 模式开启时主循环只应暴露编排白名单
 * （{@link AgentToolUtils#COORDINATOR_MODE_ALLOWED_TOOLS} = Agent/TaskStop/SendMessage/
 * StructuredOutput）∪ PR 活动订阅工具（后缀命中 subscribe_pr_activity/unsubscribe_pr_activity，
 * CC toolPool.ts:11-14）——coordinator 负责编排 worker，不应看到 Bash/Read/Edit 等实质执行工具。
 * 过滤点 = {@link LlmAgentLoop#llmToolsArray} deny 过滤后（CC getTools tools.ts:310 之后），
 * 即 Java 主循环工具池定稿点。
 *
 * <p>变异点：去掉 llmToolsArray 中的 coordinator 过滤（或门控失效）→ Bash/Read/Edit/普通 MCP
 * 工具重新出现在 schema → 测试变红。
 *
 * <p><b>StructuredOutput 特注</b>：StructuredOutput（SYNTHETIC_OUTPUT_TOOL_NAME）在
 * {@code COORDINATOR_MODE_ALLOWED_TOOLS} 内、coordinator 过滤<b>保留</b>它（{@link #poolRetainsStructuredOutput}），
 * 但 USER 主循环 schema 阶段经既有 SPECIAL_TOOLS 过滤（toOpenAiToolsArray）剔除 —— 生产主循环
 * 在结构化输出启用时经 llmToolsArray 之后 post-hoc 追加（LlmAgentLoop appendStructuredOutputToolToSchema，
 * 对齐 CC main.tsx:1885-1891）。故 schema 断言按 USER 实际可见集（{Agent,TaskStop,SendMessage} ∪ PR 工具）验，
 * 池级断言按 coordinator 白名单全集验。
 */
class LlmAgentLoopCoordinatorFilterTest {

    /** 仅按名 mock 的 Tool（isEnabled=true 保证 schema 阶段可见）。 */
    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    /** 10 参构造器 per-turn TUC：availableTools + 空权限上下文（对齐 LlmAgentLoopToolsArrayDenyTest 形态）。 */
    private static ToolUseContext tuc(List<Tool> availableTools) {
        return new ToolUseContext(
                UUID.randomUUID(),
                "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT,
                Map.of(),
                availableTools,
                null,
                AbortController.NOOP,
                List.of(),
                null,
                PermissionMode.DEFAULT);
    }

    /** 模拟"全量 all()"工具池：coordinator 白名单 4 项 + 实质执行工具 + PR 订阅 MCP + 普通 MCP。 */
    private static List<Tool> fullAllPool() {
        return List.of(
                tool("Agent"),
                tool("TaskStop"),
                tool("SendMessage"),
                tool("StructuredOutput"),
                tool("Bash"),
                tool("Read"),
                tool("Edit"),
                tool("WebSearch"),
                tool("github.com.mycorp.subscribe_pr_activity"),
                tool("pr_events.unsubscribe_pr_activity"),
                tool("mcp__random.server.tool"));
    }

    private static List<String> schemaNames(ArrayNode schema) {
        return java.util.stream.StreamSupport.stream(schema.spliterator(), false)
                .map(n -> n.path("function").path("name").asText())
                .toList();
    }

    @BeforeEach
    void enableCoordinatorMode() {
        // CoordinatorMode 2 参构造器（featureFlagSupplier, envSupplier）→ isCoordinatorMode()=true
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> true, () -> "1"));
    }

    @AfterEach
    void resetCoordinatorMode() {
        // null → 复位默认（feature 恒关 → isCoordinatorMode()=false），避免污染其他测试
        LlmAgentLoop.setCoordinatorMode(null);
        // [G24] bare 判定静态桥复位（MemoryBareModeConfig 是 static 桥接，防跨测试污染）
        MemoryBareModeConfig.reset();
    }

    @Test
    @DisplayName("coordinator 模式开启 → 主循环 llmToolsArray schema 仅含白名单（USER 可见集）∪ PR 订阅工具")
    void llmToolsArray_coordinatorMode_schemaOnlyCoordinatorAllowed() {
        // WHY: coordinator 编排 worker，不应在 LLM schema 暴露 Bash/Read/Edit/普通 MCP 等实质执行工具
        //   （CC main.tsx:1872-1877 headless 路径 getTools 之后 applyCoordinatorToolFilter）。
        //   变异点：删除 llmToolsArray 的 coordinator 过滤 → Bash/Read/Edit/WebSearch/mcp__ 重现 → 红。
        List<Tool> available = fullAllPool();

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(available), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("coordinator 模式主循环 schema = 白名单 USER 可见集 ∪ PR 订阅工具（CC main.tsx:1872-1877）")
                .containsExactlyInAnyOrder(
                        "Agent", "TaskStop", "SendMessage",
                        "github.com.mycorp.subscribe_pr_activity",
                        "pr_events.unsubscribe_pr_activity")
                .doesNotContain("Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("coordinator 模式关闭（默认）→ 主循环 llmToolsArray schema 保留全量工具，不裁剪")
    void llmToolsArray_coordinatorModeOff_poolUntouched() {
        // WHY: coordinator 过滤必须仅开启时生效；默认关（feature 恒关）不得裁剪任何工具
        //   （CC feature('COORDINATOR_MODE') 关 → mergeAndFilterTools/main.tsx 均不 apply）。
        //   变异点：门控误判（恒开）→ 默认场景 Bash/Read 被误删 → 红。
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("coordinator 模式关闭 → schema 保留全量（含 Bash/Read/Edit/mcp__，不含 SPECIAL StructuredOutput）")
                .contains("Agent", "TaskStop", "SendMessage", "Bash", "Read", "Edit", "WebSearch",
                        "github.com.mycorp.subscribe_pr_activity", "mcp__random.server.tool")
                .doesNotContain("StructuredOutput"); // SPECIAL_TOOLS 过滤（与 coordinator 无关）
    }

    @Test
    @DisplayName("coordinator 模式开启 + SUBAGENT 来源 → 不裁剪（coordinator 过滤仅顶层循环，对齐 CC runAgent.ts）")
    void llmToolsArray_coordinatorMode_subagentPoolNotTrimmed() {
        // WHY: CC applyCoordinatorToolFilter 只在顶层入口（main.tsx/REPL mergeAndFilterTools）应用，
        //   worker/subagent 经 runAgent.ts filterToolsForAgent 装配工具池、不裁剪；Java 主循环与
        //   子 agent 共用 llmToolsArray，若不对 SUBAGENT 排除，coordinator 模式会把 worker 工具池
        //   错误裁剪为编排白名单 → worker 拿不到 Bash/Read/Edit → 分工失效。
        //   变异点：去掉 querySource 顶层门 → SUBAGENT 来源也被裁剪 → 红。
        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.SUBAGENT);

        assertThat(schemaNames(schema))
                .as("SUBAGENT 来源在 coordinator 模式不裁剪（worker 保留实质执行工具）")
                .contains("Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool")
                .doesNotContain("StructuredOutput"); // SPECIAL_TOOLS 过滤（与 coordinator 无关）
    }

    @Test
    @DisplayName("bare 模式开启（Web 精简模式）→ 主循环 llmToolsArray schema 仅含 [Bash, Read, Edit]")
    void llmToolsArray_bareMode_schemaOnlyBashReadEdit() {
        // WHY: CC getTools SIMPLE 分支（tools.ts:272-298 CLAUDE_CODE_SIMPLE）把 LLM 可见工具池裁剪为
        //   simpleTools=[Bash,Read,Edit]（tools.ts:287）——Web 精简模式只暴露三个实质执行工具，
        //   WebSearch/Agent/mcp__ 等不应进入 LLM schema。
        //   变异点：删除 llmToolsArray 的 bare 裁剪 → WebSearch/mcp__/Agent 重现 → 红。
        // 门控：bare 判定 = MemoryBareModeConfig.isBareMode()（构造器注入 nexusai.memory.bare-mode=true）。
        new MemoryBareModeConfig(true);
        // coordinator 关闭（隔离 bare 变量，防 coordinator 裁剪干扰断言）。
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("bare 模式主循环 schema = [Bash, Read, Edit]（CC tools.ts:287 simpleTools）")
                .containsExactlyInAnyOrder("Bash", "Read", "Edit")
                .doesNotContain("Agent", "TaskStop", "SendMessage", "WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("bare 模式关闭（默认）→ 主循环 llmToolsArray schema 保留全量工具，不裁剪")
    void llmToolsArray_bareModeOff_poolUntouched() {
        // WHY: bare 裁剪必须仅 isBareMode() 开启时生效；关闭（nexusai.memory.bare-mode=false）
        //   不得裁剪任何工具（CC CLAUDE_CODE_SIMPLE 未置位 → getTools 走全量分支 tools.ts:300-325）。
        //   变异点：门控误判（恒开）→ 默认场景 Bash/Read/WebSearch 被误删 → 红。
        new MemoryBareModeConfig(false);
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("bare 模式关闭 → schema 保留全量（含 Bash/Read/Edit/WebSearch/mcp__，不含 SPECIAL StructuredOutput）")
                .contains("Agent", "TaskStop", "SendMessage", "Bash", "Read", "Edit", "WebSearch",
                        "github.com.mycorp.subscribe_pr_activity", "mcp__random.server.tool")
                .doesNotContain("StructuredOutput"); // SPECIAL_TOOLS 过滤（与 bare 无关）
    }

    @Test
    @DisplayName("bare + coordinator 叠加（USER 顶层）→ coordinator 白名单胜出（CC tools.ts:291-296 追加编排三工具）")
    void llmToolsArray_barePlusCoordinator_whitelistWins() {
        // WHY: CC getTools SIMPLE 分支在 coordinator 同时开启时把 [Agent, TaskStop, SendMessage]
        //   追加进 simpleTools（tools.ts:291-296），随后 main.tsx:1872-1877 applyCoordinatorToolFilter
        //   把顶层循环池裁为编排白名单——coordinator 负责编排 worker，自身不直接执行 Bash/Read/Edit。
        //   Java 顺序：bare 裁剪先（保留 Bash/Read/Edit + coordinator 追加三工具）→ deny → coordinator 过滤。
        //   变异点：bare 裁剪漏掉 coordinator 追加 → coordinator 白名单（Agent/TaskStop/SendMessage）
        //   被 bare 误删 → 顶层 schema 只剩 PR 订阅工具/空 → 红。
        new MemoryBareModeConfig(true);
        // @BeforeEach 已开 coordinator（feature=true + env=1）→ bare 裁剪追加编排三工具。

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("bare+coordinator 顶层 schema = coordinator 白名单（Agent/TaskStop/SendMessage）")
                .containsExactlyInAnyOrder("Agent", "TaskStop", "SendMessage")
                .doesNotContain("Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("bare 模式开启 + SUBAGENT 来源 → 裁剪为 [Bash, Read, Edit]（bare 非 coordinator 门，子 agent 同受裁剪）")
    void llmToolsArray_bareMode_subagentBareTrimmed() {
        // WHY: CC getTools SIMPLE 分支对所有调用方一致生效（main/subagent/hook 均经 getTools，
        //   tools.ts:271-298），bare 裁剪不设 querySource 门；SUBAGENT 在 bare 下同样只见
        //   Bash/Read/Edit（worker 只需实质执行工具）。coordinator 裁剪才设顶层门（A1），
        //   此处 coordinator 关，仅验 bare 分支。
        //   变异点：bare 裁剪误设 querySource 门（仅 USER）→ SUBAGENT 漏裁剪 → WebSearch 重现 → 红。
        new MemoryBareModeConfig(true);
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.SUBAGENT);

        assertThat(schemaNames(schema))
                .as("SUBAGENT 来源在 bare 模式同样裁剪为 [Bash, Read, Edit]")
                .containsExactlyInAnyOrder("Bash", "Read", "Edit")
                .doesNotContain("WebSearch", "mcp__random.server.tool");
    }

    // ════════════════════════════════════════════════════════════════════
    // [V33] 会话级 bare 判定（用户 2026-08-23 拍板：bareMode 随会话走）
    // llmToolsArray 读当前会话 sessions.bare_mode（DB）→ 回落全局判定
    // ════════════════════════════════════════════════════════════════════

    /**
     * 桥接会话 mapper（经 public bridgeSessionMapper 实例方法写静态字段）+ 覆写全局配置。
     *
     * <p>测试 TUC 的 sessionId 为随机 UUID → 归一化后原样当 DB 键 → {@code selectOneById(anyString())}
     * 命中 mock；本方法返回的 SessionRecord.bareMode 即该"当前会话"的判定输入。
     */
    private static void bridgeSessionBareMode(Integer bareMode, Boolean globalConfig) {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord r = new SessionRecord();
        r.setBareMode(bareMode);
        when(mapper.selectOneById(anyString())).thenReturn(r);
        new MemoryBareModeConfig(globalConfig).bridgeSessionMapper(mapper);
    }

    @Test
    @DisplayName("会话 bare_mode=1 → llmToolsArray 裁剪为 [Bash, Read, Edit]（会话级覆盖胜出，全局配置 false 也生效）")
    void llmToolsArray_sessionBareModeTrue_trimsToBashReadEdit() {
        // WHY: 用户拍板 bareMode 随会话走 —— 会话显式开启 bare（V33 列 1）→ LLM 工具池必须裁剪为
        //   simpleTools=[Bash,Read,Edit]（CC tools.ts:287），即使全局 nexusai.memory.bare-mode=false。
        //   变异点：llmToolsArray 仍读全局 isBareMode()（未接会话级）→ 全局 false → WebSearch/mcp__ 重现 → 红。
        bridgeSessionBareMode(1, false);   // 会话 bare=1 + 全局配置 false
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("会话 bare_mode=1 → schema = [Bash, Read, Edit]（会话级覆盖全局 false）")
                .containsExactlyInAnyOrder("Bash", "Read", "Edit")
                .doesNotContain("Agent", "TaskStop", "SendMessage", "WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("会话 bare_mode=0 → llmToolsArray 保留全量（会话显式关闭可压过全局配置 true）")
    void llmToolsArray_sessionBareModeFalse_fullPoolUntouched() {
        // WHY: 会话显式关闭 bare（V33 列 0）→ 该会话必须全量工具池；全局配置 true 不得覆盖会话级
        //   （对齐 effort/ultracode 会话级语义）。变异点：会话级判定未接线 → 回落全局 true → 误裁剪 → 红。
        bridgeSessionBareMode(0, true);    // 会话 bare=0 + 全局配置 true
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("会话 bare_mode=0 → schema 保留全量（会话级关闭压过全局 true）")
                .contains("Agent", "TaskStop", "SendMessage", "Bash", "Read", "Edit", "WebSearch",
                        "github.com.mycorp.subscribe_pr_activity", "mcp__random.server.tool")
                .doesNotContain("StructuredOutput");
    }

    @Test
    @DisplayName("会话 bare_mode=null → 回落全局判定（配置 true → 裁剪为 [Bash, Read, Edit]）")
    void llmToolsArray_sessionBareModeNull_fallsBackToGlobalConfig() {
        // WHY: 会话未显式设置 bare（null）→ 回落 nexusai.memory.bare-mode 配置 / env CLAUDE_CODE_SIMPLE
        //   / false（对齐 CC isBareMode + Java 会话级覆盖优先级）。变异点：null 误判 false → 全局 true 部署
        //   精简模式失效 → 红。
        bridgeSessionBareMode(null, true);  // 会话 bare=null + 全局配置 true
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("会话 bare_mode=null → 回落全局 true → schema = [Bash, Read, Edit]")
                .containsExactlyInAnyOrder("Bash", "Read", "Edit")
                .doesNotContain("WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("applyCoordinatorToolFilter 池级保留 StructuredOutput（coordinator 白名单成员）")
    void poolRetainsStructuredOutput() {
        // WHY: StructuredOutput 在 COORDINATOR_MODE_ALLOWED_TOOLS（CC constants/tools.ts:107-112），
        //   池级必须保留 —— 生产结构化输出经 llmToolsArray 后 post-hoc 追加（CC main.tsx:1885-1891）。
        //   变异点：白名单漏 StructuredOutput → 结构化输出 coordinator 场景不可用 → 红。
        List<Tool> filtered = AgentToolUtils.applyCoordinatorToolFilter(fullAllPool());

        assertThat(filtered.stream().map(Tool::name))
                .as("coordinator 过滤池级保留白名单全集 ∪ PR 订阅工具")
                .containsExactlyInAnyOrder(
                        "Agent", "TaskStop", "SendMessage", "StructuredOutput",
                        "github.com.mycorp.subscribe_pr_activity",
                        "pr_events.unsubscribe_pr_activity");
    }
}
