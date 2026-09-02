package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.domain.mcp.McpServerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S04 (B4): 工具池分区排序测试（验收 3）· 对齐 CC {@code tools.ts:354-366} assembleToolPool：
 * builtin 排序前缀 + MCP 排序后缀 + 按名去重（builtin 优先），<b>非全局排序</b>（CC 注释明言
 * flat sort 破坏 prompt-cache 键）。
 *
 * <p>[REWORK-7 适配] 2-arg {@code assembleToolPool(permCtx, mcpTools)} 已删除（IMP-C4 REQ-G3-2-2，
 * 生产 0 调用方）。分区序生产落点拆为两条现役 API：
 * <ul>
 *   <li>builtin 前缀 + MCP 后缀合并 → {@link ToolRegistry#getMergedTools}（{@code [...getTools(permCtx), ...mcpTools]}）；</li>
 *   <li>MCP 分区按名排序 → {@link McpServerService#getCurrentTools()}（按名排序确定性快照，
 *       对齐 CC {@code allowedMcpTools.sort(byName)}）；</li>
 *   <li>builtin 覆盖同名 MCP → {@link ToolRegistry#assembleToolPool(List)} 1-arg（R32-#14 uniqBy
 *       builtin 优先，tools.ts:357-365）。</li>
 * </ul>
 * 三测试分别锁定上述语义，变异即红。
 */
@DisplayName("S04 工具池分区排序（builtin 前缀 + MCP 后缀，非全局）")
class ToolRegistryPartitionSortTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public List<String> aliases() { return List.of(); }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isEnabled() { return true; }
        };
    }

    @Test
    @DisplayName("getMergedTools: 区分性命名下分区序 [Zulu, Alpha, Beta]（全局序会是 [Alpha, Beta, Zulu]）")
    void assembleToolPool_partitionSort_builtinPrefixMcpSuffix() {
        // WHY: 全局排序（旧实现）会得到 [Alpha, Beta, Zulu]；分区排序 = builtin 排序前缀 +
        //   MCP 排序后缀 = [Zulu, Alpha, Beta]。变异点: 回退全局排序 → 本测试红。
        // [IMP-C4] assembleToolPool 收敛为 1 参 int（MCP 刷新）；LLM-facing pool 经
        //   getTools(null) 读取（注册序 = builtin 前缀 + MCP 后缀分区，非全局排序）。
        ToolRegistry registry = new ToolRegistry().register(stub("Zulu"));
        McpServerService service = new McpServerService();
        service.addMcpTool(stub("Alpha"));
        service.addMcpTool(stub("Beta"));

        registry.assembleToolPool(List.of(stub("Alpha"), stub("Beta")));
        List<Tool> pool = registry.getTools(null);

        assertThat(pool.stream().map(Tool::name))
            .as("分区序：builtin 连续前缀 + MCP 后缀（CC tools.ts:362-364 byName）")
            .containsExactly("Zulu", "Alpha", "Beta");
    }

    @Test
    @DisplayName("MCP 分区内按名排序（无 builtin 干扰 · 生产落点 McpServerService.getCurrentTools）")
    void assembleToolPool_sortsWithinMcpPartition() {
        // WHY: S04 (B4) MCP 分区排序的生产落点 = McpServerService.getCurrentTools()（按名排序
        //   确定性快照，对齐 CC assembleToolPool 的 allowedMcpTools.sort(byName)，tools.ts:362-364）——
        //   旧 assembleToolPool 内排序已随 [IMP-C4] 收敛移除。变异点: 回退 values() 顺序 →
        //   顺序随插入序 → 本测试红。
        McpServerService service = new McpServerService();
        service.addMcpTool(stub("Zed"));
        service.addMcpTool(stub("Alpha"));
        service.addMcpTool(stub("Mike"));

        assertThat(service.getCurrentTools().stream().map(Tool::name))
            .as("MCP 分区确定性顺序（按名排序，CC allowedMcpTools.sort(byName)）")
            .containsExactly("Alpha", "Mike", "Zed");
    }

    @Test
    @DisplayName("assembleToolPool(1-arg): 同名 MCP 被 builtin 覆盖（uniqBy 语义，builtin 优先）")
    void assembleToolPool_builtinWinsOnNameConflict() {
        Tool builtin = stub("Alpha");
        ToolRegistry registry = new ToolRegistry().register(builtin);

        // [IMP-C4] assembleToolPool 1 参（builtin 覆盖同名 MCP）；pool 经 getTools(null) 读取
        registry.assembleToolPool(List.of(stub("Alpha"), stub("Beta")));
        List<Tool> pool = registry.getTools(null);

        assertThat(pool.stream().map(Tool::name))
            .as("MCP 同名 Alpha 被跳过（R32-#14 builtin 优先），Beta 追加注册")
            .containsExactly("Alpha", "Beta");
        assertThat(pool.get(0))
            .as("同名冲突时 builtin 实例胜出（CC 注释: built-ins win on name conflict）")
            .isSameAs(builtin);
    }

    @Test
    @DisplayName("getCurrentTools: addMcpTool 乱序插入 → 返回按名排序确定性快照")
    void getCurrentTools_returnsNameSortedSnapshot() {
        // WHY: 旧实现返回 ConcurrentHashMap.values() 顺序（跨 JVM 非确定 → prompt-cache 键不稳定）。
        //   S04 改为按名排序（对齐 CC assembleToolPool 的 allowedMcpTools.sort(byName)）。
        //   变异点: 回退 values() 顺序 → 顺序随插入序（alpha/mike/zed 之外）→ 红。
        McpServerService service = new McpServerService();
        service.addMcpTool(stub("mcp__svr__zed"));
        service.addMcpTool(stub("mcp__svr__alpha"));
        service.addMcpTool(stub("mcp__svr__mike"));

        assertThat(service.getCurrentTools().stream().map(Tool::name))
            .as("MCP 分区确定性顺序（按名排序，池语义无顺序契约）")
            .containsExactly("mcp__svr__alpha", "mcp__svr__mike", "mcp__svr__zed");
    }
}
