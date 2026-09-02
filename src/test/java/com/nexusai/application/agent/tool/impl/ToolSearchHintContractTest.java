package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.domain.schedule.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WF-G G5 · searchHint 提升 Tool 接口契约的反射 + 全量枚举测试.
 *
 * <p><b>WHY (意图验证)</b>: CC Tool.ts:378 {@code searchHint?: string} 是可选的工具定义
 * 字段（3-10 词能力短语，无尾句号），消费方为 CC ToolSearchTool.ts 关键词匹配。Java 侧此前
 * 以 per-tool 局部方法承载（9 处声明、0 生产消费）；本契约测试锁定：① {@code Tool} 接口
 * 必须先声明 {@code searchHint()} 成员（反射断言，接口缺失时 RED）；② default 返回
 * {@code null} 对齐 CC absent 语义；③ 各 override 值逐一对齐 CC 真源（含 todo-write 族
 * TaskGet/TaskList/TaskUpdate——CC TaskGetTool.ts:40 / TaskListTool.ts:35 /
 * TaskUpdateTool.ts:90）；④ 值满足 3-10 词 / 无尾句号约束。
 *
 * <p>Q-1（todo-write WF-D）：TaskUpdateTool 补齐缺失的 searchHint override
 * （CC TaskUpdateTool.ts:90 {@code searchHint: 'update a task'}），本测试同步枚举。
 */
class ToolSearchHintContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final CronEnabledGates GATES = CronEnabledGates.DEFAULTS;

    // ───────────────────────── ① 接口成员反射断言 ─────────────────────────

    @Test
    @DisplayName("Tool 接口必须声明 searchHint() 成员（反射；缺失 → RED）")
    void toolInterface_declaresSearchHintMember() throws Exception {
        // WHY: searchHint 是 CC Tool.ts:378 工具定义契约成员。若接口未提升（仍为 per-tool
        //   局部方法），getMethod 抛 NoSuchMethodException → 测试 RED，阻止回归到旧形态。
        assertThat(Tool.class.getMethod("searchHint"))
            .as("Tool.searchHint() 必须作为接口契约成员存在（CC Tool.ts:378）")
            .isNotNull();
    }

    // ───────────────────────── ② default 返回 null ─────────────────────────

    @Test
    @DisplayName("未 override 的工具 searchHint() 默认返回 null（对齐 CC absent 语义）")
    void defaultSearchHint_returnsNull() {
        // WHY: CC searchHint?: string 是可选字段，未声明的工具 = 无搜索提示（undefined）。
        //   default 返回 null 保证既有工具（如 BashTool 等 20+ 未声明工具）零修改、向后兼容，
        //   同时契约成员已存在供未来 ToolSearch 消费。
        Tool plain = new Tool() {
            @Override public String name() { return "plain"; }
            @Override public String description() { return "plain tool"; }
            @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        assertThat(plain.searchHint())
            .as("未 override → null（CC Tool.ts:378 可选字段 absent 语义）")
            .isNull();
    }

    // ───────────────────────── ③ 全量 override 枚举 ─────────────────────────

    @Test
    @DisplayName("searchHint override 值逐一对齐 CC 真源（含 todo-write 族 TaskGet/TaskList + TaskUpdateTool Q-1 补齐）")
    void allDeclarations_overrideWithCcValues() {
        assertThat(tool("SkillToolImpl").searchHint())
            .as("CC SkillTool.ts:333").isEqualTo("invoke a slash-command skill");
        assertThat(tool("CronCreateTool").searchHint())
            .as("CC CronCreateTool.ts:58").isEqualTo("schedule a recurring or one-shot prompt");
        assertThat(tool("CronDeleteTool").searchHint())
            .as("CC CronDeleteTool.ts:37").isEqualTo("cancel a scheduled cron job");
        assertThat(tool("CronListTool").searchHint())
            .as("CC CronListTool.ts:39").isEqualTo("list active cron jobs");
        assertThat(tool("EnterPlanModeTool").searchHint())
            .as("CC EnterPlanModeTool.ts:38").isEqualTo("switch to plan mode to design an approach before coding");
        assertThat(tool("ExitPlanModeTool").searchHint())
            .as("CC ExitPlanModeV2Tool.ts:149").isEqualTo("present plan for approval and start coding (plan mode only)");
        assertThat(tool("ListMcpResourcesTool").searchHint())
            .as("CC ListMcpResourcesTool.ts:52").isEqualTo("list resources from connected MCP servers");
        assertThat(tool("ReadMcpResourceTool").searchHint())
            .as("CC ReadMcpResourceTool.ts:61").isEqualTo("read a specific MCP resource by URI");
        // todo-write 族（Q-1）：CC TaskGetTool.ts:40 / TaskListTool.ts:35
        assertThat(tool("TaskGetTool").searchHint())
            .as("CC TaskGetTool.ts:40").isEqualTo("retrieve a task by ID");
        assertThat(tool("TaskListTool").searchHint())
            .as("CC TaskListTool.ts:35").isEqualTo("list all tasks");
        // McpServerTool: CC client.ts:1779-1783 空白折叠 _meta['anthropic/searchHint']，
        //   无 meta → null。McpServerTool 为 package-private（agent.mcp 包），由同包
        //   McpServerToolTest.meta_searchHint_* 覆盖，此处不跨包枚举。
        // 第 10 处 TaskUpdateTool（Q-1 补齐）：CC TaskUpdateTool.ts:90 'update a task'
        assertThat(tool("TaskUpdateTool").searchHint())
            .as("CC TaskUpdateTool.ts:90").isEqualTo("update a task");
    }

    // ───────────────────────── ③b TodoWriteTool（Q-1 恢复）─────────────────────────

    @Test
    @DisplayName("TodoWriteTool.searchHint() 逐字对齐 CC TodoWriteTool.ts:33（Q-1 恢复 override）")
    void todoWrite_overrideWithCcValue() {
        // WHY: CC TodoWriteTool.ts:33 searchHint: 'manage the session task checklist'。
        //   Java 曾误删该 override（Tool.java:641-648 默认 null）——Q-1 本轮恢复，供 ToolSearch
        //   关键词匹配（CC Tool.ts:372-378 消费 searchHint）。值必须逐字对齐 CC 真源，不得改写。
        assertThat(tool("TodoWriteTool").searchHint())
            .as("CC TodoWriteTool.ts:33")
            .isEqualTo("manage the session task checklist");
    }

    // ───────────────────────── ④ 值约束（3-10 词 / 无尾句号）─────────────────────────

    @Test
    @DisplayName("全部 override 值为 3-10 词、无尾句号（CC Tool.ts:378 JSDoc 约束）")
    void allValues_are3to10Words_noTrailingPeriod() {
        String[] values = {
            tool("SkillToolImpl").searchHint(),
            tool("CronCreateTool").searchHint(),
            tool("CronDeleteTool").searchHint(),
            tool("CronListTool").searchHint(),
            tool("EnterPlanModeTool").searchHint(),
            tool("ExitPlanModeTool").searchHint(),
            tool("ListMcpResourcesTool").searchHint(),
            tool("ReadMcpResourceTool").searchHint(),
            tool("TodoWriteTool").searchHint(),
            tool("TaskGetTool").searchHint(),
            tool("TaskListTool").searchHint(),
            tool("TaskUpdateTool").searchHint(),
        };
        for (String v : values) {
            assertThat(v.split("\\s+").length)
                .as("3-10 词（值='%s'）", v).isBetween(3, 10);
            assertThat(v)
                .as("无尾句号（值='%s'）", v).doesNotEndWith(".");
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private static Tool tool(String kind) {
        return switch (kind) {
            case "SkillToolImpl" -> new SkillToolImpl(mock(SkillRegistry.class));
            case "CronCreateTool" -> new CronCreateTool(mock(ScheduleService.class), GATES);
            case "CronDeleteTool" -> new CronDeleteTool(mock(ScheduleService.class), GATES);
            case "CronListTool" -> new CronListTool(mock(ScheduleService.class), GATES);
            case "EnterPlanModeTool" -> new EnterPlanModeTool();
            case "ExitPlanModeTool" -> new ExitPlanModeTool();
            case "ListMcpResourcesTool" -> new ListMcpResourcesTool(mock(McpToolPool.class));
            case "ReadMcpResourceTool" -> new ReadMcpResourceTool(mock(McpToolPool.class));
            case "TodoWriteTool" -> new TodoWriteTool();
            case "TaskGetTool" -> new TaskGetTool(mock(TaskService.class));
            case "TaskListTool" -> new TaskListTool(mock(TaskService.class));
            case "TaskUpdateTool" -> new TaskUpdateTool(mock(TaskService.class), null);
            default -> throw new IllegalArgumentException("unknown kind: " + kind);
        };
    }
}
