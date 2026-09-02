package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.hook.PermissionBehavior;
import com.nexusai.application.agent.tool.AgentToolUtils;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.GlobTool;
import com.nexusai.application.agent.tool.impl.GrepTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session D · ToolRegistry/Constants 装配层对齐测试 · CC 真源:
 * {@code Open-ClaudeCode/src/tools.ts} + {@code constants/tools.ts} +
 * {@code tools/REPLTool/constants.ts} + {@code tools/SyntheticOutputTool/SyntheticOutputTool.ts} +
 * {@code utils/hooks.ts:349}.
 *
 * <p><b>WHY (规则九)</b>: D session 修复 5 个装配层缺口 —
 * 3 预设函数 (parseToolPreset/getToolsForDefaultPreset/getMergedTools) 缺失 /
 * assembleToolPool 不剔 SPECIAL_TOOLS (CC tools.ts:307 specialTools 过滤) /
 * SYNTHETIC_OUTPUT_TOOL_NAME 值错 (CC 真值 'StructuredOutput') /
 * COORDINATOR_MODE_ALLOWED_TOOLS + REPL_ONLY_TOOLS 常量集缺失 /
 * ToolHooks 死代码未清. 每个测试锁定一个 CC 行为, 变异即红.
 */
@DisplayName("Session D · ToolRegistry/Constants 装配层对齐 CC")
class ToolRegistryAlignmentTest {

    // ── stub 工具: 最小 Tool 实现 (name/description/inputSchema/execute) ──

    private static Tool stub(String name, boolean enabled) {
        return stub(name, enabled, List.of());
    }

    private static Tool stub(String name, boolean enabled, List<String> aliases) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public List<String> aliases() { return aliases; }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isEnabled() { return enabled; }
        };
    }

    private static Tool stub(String name) {
        return stub(name, true);
    }

    // ── 1. parseToolPreset (CC tools.ts:165-171) ──

    @Test
    @DisplayName("parseToolPreset: 'default' 大小写不敏感规范化 (CC tools.ts:165-171 toLowerCase)")
    void parseToolPreset_normalizesCaseInsensitively() {
        // WHY: CC parseToolPreset 先 toLowerCase 再校验 TOOL_PRESETS includes.
        //   变异点: 去掉 toLowerCase → 'Default' 返回 null → 本测试红.
        assertThat(ToolRegistry.parseToolPreset("default")).isEqualTo("default");
        assertThat(ToolRegistry.parseToolPreset("Default")).isEqualTo("default");
        assertThat(ToolRegistry.parseToolPreset("DEFAULT")).isEqualTo("default");
    }

    @Test
    @DisplayName("parseToolPreset: 未识别预设返回 null (CC tools.ts:167-168)")
    void parseToolPreset_unknownReturnsNull() {
        // WHY: CC 对未知预设返回 null (调用方降级到默认 preset).
        assertThat(ToolRegistry.parseToolPreset("unknown")).isNull();
        assertThat(ToolRegistry.parseToolPreset(null)).isNull();
    }

    // ── 2. getToolsForDefaultPreset (CC tools.ts:179-183) ──

    @Test
    @DisplayName("getToolsForDefaultPreset: 仅返回 isEnabled 工具名 (CC tools.ts:181 filter isEnabled)")
    void getToolsForDefaultPreset_filtersDisabled() {
        // WHY: CC getAllBaseTools().filter(t => t.isEnabled()).map(name).
        //   变异点: 去掉 isEnabled 过滤 → 禁用工具名出现 → 红.
        ToolRegistry registry = new ToolRegistry()
            .register(stub("Alpha", true))
            .register(stub("Beta", false));
        assertThat(registry.getToolsForDefaultPreset())
            .containsExactly("Alpha");
    }

    // ── 3. getMergedTools (CC tools.ts:383-389) ──

    @Test
    @DisplayName("getMergedTools: builtin(剔除 special) + MCP 全量拼接, 不去重不排序 (CC tools.ts:383-389)")
    void getMergedTools_concatsBuiltinAndMcpWithoutDedupOrSort() {
        // WHY: CC getMergedTools = [...getTools(permCtx), ...mcpTools] —
        //   token 计数/tool search 场景需要全量; 与 assembleToolPool 的 uniqBy+sort 语义分离.
        //   变异点: 误用 assembleToolPool 语义 (去重/排序) → 顺序/数量变 → 红.
        ToolRegistry registry = new ToolRegistry()
            .register(stub(ToolNameConstants.LIST_MCP_RESOURCES_TOOL_NAME)) // special, 应从 builtin 剔除
            .register(stub("Zulu"));                                         // 故意逆字典序, 验证不排序
        List<Tool> mcp = List.of(stub("McpA"), stub("McpB"));

        List<Tool> merged = registry.getMergedTools(null, mcp);

        // builtin 部分: Zulu 保留 (注册序), ListMcpResources 剔除 (specialTools, CC tools.ts:307)
        assertThat(merged.stream().map(Tool::name))
            .as("getMergedTools = getTools(permCtx) + mcpTools 全量拼接 (注册序, 不排序)")
            .containsExactly("Zulu", "McpA", "McpB");
    }

    // ── 4. assembleToolPool SPECIAL_TOOLS 剔除 (CC tools.ts:345-367 + 内部 getTools :307) ──

    @Test
    @DisplayName("getTools(permCtx): builtin 剔除 SPECIAL_TOOLS (CC tools.ts:307 specialTools 过滤)")
    void assembleToolPool_excludesSpecialToolsFromBuiltin() {
        // WHY: D session 修复 — 原实现直接用 tools.values() (不过滤 SPECIAL_TOOLS),
        //   ListMcpResources/ReadMcpResource/StructuredOutput 泄漏进 LLM-facing pool.
        //   [REWORK-7] 2-arg assembleToolPool 已删, LLM-facing builtin pool 现由
        //   getTools(permCtx) 承担 (SPECIAL_TOOLS 剔除)。
        //   变异点: 回退到 raw tools.values() → special 工具出现 → 红.
        ToolRegistry registry = new ToolRegistry()
            .register(stub(ToolNameConstants.LIST_MCP_RESOURCES_TOOL_NAME))
            .register(stub("NormalTool"));

        // [IMP-C4 REQ-G3-2-2] assembleToolPool 已收敛为 1 参 int（生产 MCP 刷新唯一入口，J-8），
        //   LLM-facing pool 装配由 getTools()（SPECIAL_TOOLS 剔除 + deny + isEnabled）承担。
        registry.assembleToolPool(List.of());
        List<Tool> pool = registry.getTools(null);

        assertThat(pool.stream().map(Tool::name))
            .as("builtin 必须经 getTools() 过滤 SPECIAL_TOOLS (CC tools.ts:307)")
            .containsExactly("NormalTool");
    }

    @Test
    @DisplayName("assembleToolPool(1-arg): builtin 覆盖同名 MCP + 注册序 (CC tools.ts:362-366 uniqBy)")
    void assembleToolPool_builtinOverridesMcpAndSorts() {
        // WHY: CC uniqBy('name') 保插入序 (builtin 在前); S04 B4 分区排序 (builtin 前缀 +
        //   MCP 后缀, tools.ts:354-366) —— Bash 在 builtin 分区、Zed 在 MCP 分区, 本用例
        //   Bash/Zed 顺序无法区分全局/分区序, 分区序专测见 ToolRegistryPartitionSortTest.
        //   [REWORK-7] 2-arg assembleToolPool 已删, builtin 优先语义现由 1-arg
        //   assembleToolPool(List) (R32-#14, tools.ts:357-365) 承担。
        //   变异点: MCP 覆盖 builtin → 名字集合或顺序变 → 红.
        Tool builtinBash = stub("Bash");
        ToolRegistry registry = new ToolRegistry().register(builtinBash);

        // [IMP-C4] assembleToolPool 1 参（builtin 覆盖同名 MCP + 注册序）；pool 经 getTools 读取
        registry.assembleToolPool(List.of(stub("Bash"), stub("Zed")));
        List<Tool> pool = registry.getTools(null);

        assertThat(pool.stream().map(Tool::name))
            .as("builtin Bash 优先于同名 MCP Bash（uniqBy builtin 在前）")
            .containsExactly("Bash", "Zed");
        assertThat(pool.get(0))
            .as("同名冲突时 builtin 实例胜出 (CC 注释: built-ins win on name conflict)")
            .isSameAs(builtinBash);
    }

    // ── 5. SYNTHETIC_OUTPUT_TOOL_NAME 真值 (CC SyntheticOutputTool.ts:20) ──

    @Test
    @DisplayName("SYNTHETIC_OUTPUT_TOOL_NAME 值 = 'StructuredOutput' (CC SyntheticOutputTool.ts:20 真源)")
    void syntheticOutputToolName_matchesCCOriginal() {
        // WHY: D session 修正 — 原值 'SyntheticOutput' (旧名) 导致 ASYNC_AGENT_ALLOWED_TOOLS
        //   白名单永远匹配不到真实工具. 变异点: 改回 'SyntheticOutput' → 红.
        assertThat(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME)
            .as("CC 常量名 SYNTHETIC_OUTPUT_TOOL_NAME, 值是真名 'StructuredOutput'")
            .isEqualTo("StructuredOutput");
        assertThat(ToolNameConstants.SPECIAL_TOOLS)
            .as("specialTools = {ListMcpResources, ReadMcpResource, StructuredOutput} (CC tools.ts:300-307)")
            .contains("StructuredOutput");
        // [IMP-C4 DC-A1-03] ALL_NAMES / SOURCE_TO_CONSTANT 已删除（CC 无聚合映射表，生产零消费者）；
        //   SPECIAL_TOOLS 含 StructuredOutput 已在上方断言，删除该冗余断言。
        // [REWORK-7] ALL_NAMES 聚合集已删 (IMP-C4 DC-A1-03) — "StructuredOutput" 在工具名集合
        //   的成员资格已由上方 SPECIAL_TOOLS 断言覆盖（CC specialTools 即 LLM-facing 过滤源）。
    }

    // ── 6. COORDINATOR_MODE_ALLOWED_TOOLS (CC constants/tools.ts:107-112) ──

    @Test
    @DisplayName("COORDINATOR_MODE_ALLOWED_TOOLS = {Agent, TaskStop, SendMessage, StructuredOutput} (CC constants/tools.ts:107-112)")
    void coordinatorModeAllowedTools_matchesCC() {
        // WHY: constants/tools.ts 第 5 集 — coordinator 模式仅输出 + agent 管理工具.
        //   变异点: 少/多一个成员 → 红.
        assertThat(AgentToolUtils.COORDINATOR_MODE_ALLOWED_TOOLS)
            .containsExactlyInAnyOrder(
                AgentToolConstants.AGENT_TOOL_NAME,
                ToolNameConstants.TASK_STOP_TOOL_NAME,
                ToolNameConstants.SEND_MESSAGE_TOOL_NAME,
                ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }

    // ── 7. REPL_ONLY_TOOLS (CC REPLTool/constants.ts:37-46) ──

    @Test
    @DisplayName("REPL_ONLY_TOOLS = {Read, Write, Edit, Glob, Grep, Bash, NotebookEdit, Agent} (CC REPLTool/constants.ts:37-46)")
    void replOnlyTools_matchesCC() {
        // WHY: REPL 模式开启时这 8 个基础工具对 LLM 直接调用隐藏.
        //   变异点: 少/多一个成员 → 红.
        assertThat(ToolNameConstants.REPL_ONLY_TOOLS)
            .containsExactlyInAnyOrder(
                ToolNameConstants.FILE_READ_TOOL_NAME,
                ToolNameConstants.FILE_WRITE_TOOL_NAME,
                ToolNameConstants.FILE_EDIT_TOOL_NAME,
                ToolNameConstants.GLOB_TOOL_NAME,
                ToolNameConstants.GREP_TOOL_NAME,
                ToolNameConstants.BASH_TOOL_NAME,
                ToolNameConstants.NOTEBOOK_EDIT_TOOL_NAME,
                AgentToolConstants.AGENT_TOOL_NAME);
    }

    // ── 8. PermissionBehavior 4 态 (CC hooks.ts:349 union) ──

    @Test
    @DisplayName("PermissionBehavior 保留 4 态含 PASSTHROUGH (CC hooks.ts:349 permissionBehavior union 含 'passthrough')")
    void permissionBehavior_matchesCCFourStateUnion() {
        // WHY: D.md/V2 声称 'CC 无 PASSTHROUGH' 已实证过时 (Pattern #9) —
        //   CC hooks.ts:349 union = 'ask'|'deny'|'allow'|'passthrough', types/permissions.ts:256
        //   也有 passthrough 态. 变异点: 删 PASSTHROUGH → 红.
        assertThat(PermissionBehavior.values())
            .containsExactlyInAnyOrder(
                PermissionBehavior.ALLOW,
                PermissionBehavior.DENY,
                PermissionBehavior.ASK,
                PermissionBehavior.PASSTHROUGH);
    }

    // ── 9. ToolHooks 死代码删除验证 ──

    @Test
    @DisplayName("ToolHooks.java 整文件删除 + LlmAgentLoop.resolveHookPermissionDecision 7 参入口存在并接线 (D P1-2)")
    void toolHooksDeadCode_fullyRemoved() {
        // WHY: R32-D ToolHooks.java 全文件 0 生产调用 (gitnexus 实证唯一 incoming 是
        //   LlmAgentLoop 死包装, 该包装 0 调用) → 整类删除. CC toolHooks.ts 语义由
        //   HookRegistry + AggregatedHookResult 16 字段承载.
        //   [D P1-2 N1 修正] LlmAgentLoop.resolveHookPermissionDecision 7 参静态方法
        //   (对齐 CC toolExecution.ts:921-929 调用点 + toolHooks.ts:332-433 定义) 已新建
        //   <b>并接线</b>至 StreamingToolExecutor 主循环 (委托 HookPermissionResolver,
        //   单一实现无双轨) — 不再是 0 caller 死包装, 故断言翻转: 方法必须存在.
        //   死代码防护由 ResolveHookPermissionDecisionTest (静态入口行为) +
        //   ToolHooksPermissionTest (解析器不变量) 行为级锁定.
        //   变异点: 重新引入死类 → 红; 删除 7 参入口 → 红.
        assertThatThrownBy(() -> Class.forName("com.nexusai.application.agent.hook.ToolHooks"))
            .as("ToolHooks 类必须不存在 (已整文件删除)")
            .isInstanceOf(ClassNotFoundException.class);

        boolean entryExists = Arrays.stream(com.nexusai.application.agent.LlmAgentLoop.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch("resolveHookPermissionDecision"::equals);
        assertThat(entryExists)
            .as("LlmAgentLoop.resolveHookPermissionDecision 7 参静态入口必须存在 (D P1-2 新建并接线)")
            .isTrue();
    }

    // ── B2 (Session B2 · 5 文件工具名 snake_case → CC 大小写 + alias 通道修复) ──

    @Test
    @DisplayName("5 文件工具 name() = CC 大小写 (Read/Edit/Write/Glob/Grep)，aliases 空（真源无 aliases 声明）")
    void fileToolNames_ccCaseWithLegacyAliases() {
        // WHY: CC FILE_READ_TOOL_NAME='Read'(FileReadTool/prompt.ts:5) / 'Edit'(FileEditTool/constants.ts:2) /
        //   'Write'(FileWriteTool/prompt.ts:3) / 'Glob'(GlobTool/prompt.ts:1) / 'Grep'(GrepTool/prompt.ts:4).
        //   [tool_v3 CC 对齐] 真源无 aliases 声明，不保留旧 snake_case 兼容壳 → aliases() 继承
        //   Tool 基类默认 List.of()（空）。旧断言（aliases 含 read_file/edit_file/...）已随
        //   兼容壳删除而失效；registry 级 aliasMap 通道仍由显式 aliases() 的工具（stub）覆盖
        //   （见 init_buildsAliasMap_forEarlyTools）。
        //   变异点: name() 回退 snake_case / 重新引入兼容 alias → 红.
        PathGuard guard = new PathGuard(Path.of("."));
        assertThat(new ReadFileTool(guard).name())
            .as("主名对齐 CC 'Read'")
            .isEqualTo("Read");
        assertThat(new ReadFileTool(guard).aliases())
            .as("CC 真源无 aliases 声明 → aliases 为空（无兼容壳）")
            .isEmpty();
        assertThat(new EditFileTool(guard).name()).isEqualTo("Edit");
        assertThat(new EditFileTool(guard).aliases()).isEmpty();
        assertThat(new WriteFileTool(guard).name()).isEqualTo("Write");
        assertThat(new WriteFileTool(guard).aliases()).isEmpty();
        assertThat(new GlobTool(guard).name()).isEqualTo("Glob");
        assertThat(new GlobTool(guard).aliases()).isEmpty();
        assertThat(new GrepTool(guard).name()).isEqualTo("Grep");
        assertThat(new GrepTool(guard).aliases()).isEmpty();
    }

    @Test
    @DisplayName("init() 走 register 建 aliasMap: 早期 bean 旧名经 alias 可 get/dispatch (OPD-09)")
    void init_buildsAliasMap_forEarlyTools() {
        // WHY: init() 若直 put tools 绕过 register → aliasMap 空 → async 子 agent 丢 4 工具
        //   (LLM 历史 transcript 调 read_file 经 dispatch 找不到). 变异点: init 直 put tools /
        //   dispatch 无 alias 兜底 → 红.
        // stub 主名 Read + alias read_file —— 隔离验证 earlyTools → init → register → aliasMap 接线,
        // 不引入真实 ReadFileTool 空输入执行错误.
        ToolRegistry registry = new ToolRegistry(
            List.of(stub("Read", true, List.of("read_file"))));
        registry.init();
        assertThat(registry.get("read_file"))
            .as("早期 bean 主名 Read, 旧名 read_file 经 aliasMap 可查")
            .isPresent();
        assertThat(registry.has("read_file"))
            .as("has(name) 含 alias 语义")
            .isTrue();
        ToolResult<?> r = registry.dispatch(
            new ToolUseBlock("b2-1", "read_file", JsonNodeFactory.instance.objectNode()));
        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2 4 字段契约) → isError 由执行器按 data 推导
        //   (LlmAgentLoop.isToolErrorData)。stub 成功 data="ok" → 非 error。
        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("dispatch(read_file) 经 alias 命中 stub 而非 No such tool")
            .isFalse();
    }
}
