package com.nexusai.application.agent.tool;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [B6 DEL-WFB-03 / D-26 / D-27] AgentToolUtils 各 agent 工具集合的条件语义 + 集合内容断言。
 *
 * <p><b>WHY（意图验证，规则九）</b>: CC constants/tools.ts 的 5 个 agent 工具集合含条件 spread——
 * {@code USER_TYPE==='ant'} 放行 Agent、{@code feature('WORKFLOW_SCRIPTS')} 拦截 Workflow、
 * {@code feature('AGENT_TRIGGERS')} 放行 Cron* 3 项。Java 端以静态工厂镜像条件分支，集合以
 * 保守默认冻结（对齐 CC bundle 常量折叠）。本测试锁定：
 * <ol>
 *   <li>工厂条件分支（RED 断言）：flag 关闭 → 条件项从集合消失</li>
 *   <li>集合内容（复验断言）：ASYNC 16 项含 ToolSearch；COORDINATOR 4 项</li>
 *   <li>单一权威不变量：默认常量含 Workflow（防御性，WorkflowTool 注册时 hook agent 仍被拦截）</li>
 * </ol>
 */
class AgentToolUtilsAgentSetsTest {

    // ── 工厂条件分支（D-26：ant → 放行 Agent / WORKFLOW_SCRIPTS → 拦截 Workflow）──

    @Test
    @DisplayName("buildAllAgentDisallowed(false,false) 不含 Workflow（WORKFLOW_SCRIPTS 关）但含 Agent/基础项")
    void allAgentDisallowedWorkflowScriptsOff() {
        // WHY: CC tools.ts:43 ...(feature('WORKFLOW_SCRIPTS') ? [Workflow] : []) — flag 关时 Workflow 不在集合。
        Assertions.assertThat(ToolNameConstants.buildAllAgentDisallowed(false, false))
            .as("WORKFLOW_SCRIPTS 关 → Workflow 不在 ALL_AGENT_DISALLOWED")
            .doesNotContain(ToolNameConstants.WORKFLOW_TOOL_NAME)
            .contains(ToolNameConstants.TASK_OUTPUT_TOOL_NAME)
            .contains(AgentToolConstants.AGENT_TOOL_NAME);
    }

    @Test
    @DisplayName("buildAllAgentDisallowed(true,false) 不含 Agent（USER_TYPE=ant 放行嵌套 agent）")
    void allAgentDisallowedAntReleasesAgent() {
        // WHY: CC tools.ts:40 ...(USER_TYPE==='ant' ? [] : [Agent]) — ant 时 Agent 从集合移除。
        Assertions.assertThat(ToolNameConstants.buildAllAgentDisallowed(true, false))
            .as("USER_TYPE=ant → Agent 不在 ALL_AGENT_DISALLOWED")
            .doesNotContain(AgentToolConstants.AGENT_TOOL_NAME);
    }

    @Test
    @DisplayName("buildAllAgentDisallowed(false,true) 含 Workflow（WORKFLOW_SCRIPTS 开，WorkflowTool 注册时拦截）")
    void allAgentDisallowedWorkflowScriptsOn() {
        // WHY: CC tools.ts:43 feature('WORKFLOW_SCRIPTS') 开 → Workflow 在集合，防子 agent 递归 workflow。
        Assertions.assertThat(ToolNameConstants.buildAllAgentDisallowed(false, true))
            .as("WORKFLOW_SCRIPTS 开 → Workflow 在 ALL_AGENT_DISALLOWED")
            .contains(ToolNameConstants.WORKFLOW_TOOL_NAME);
    }

    // ── IN_PROCESS cron 条件（D-27：AGENT_TRIGGERS → Cron* 3 项）──

    @Test
    @DisplayName("buildInProcessTeammateAllowed(false) 不含 Cron* 3 项（AGENT_TRIGGERS 关）")
    void inProcessAgentTriggersOff() {
        // WHY: CC tools.ts:83-86 ...(feature('AGENT_TRIGGERS') ? [CronCreate, CronDelete, CronList] : []) —
        // flag 关时 teammate 不得创建/删除/列举 cron。
        Assertions.assertThat(AgentToolUtils.buildInProcessTeammateAllowed(false))
            .as("AGENT_TRIGGERS 关 → Cron* 不在 IN_PROCESS_TEAMMATE")
            .doesNotContain(ToolNameConstants.CRON_CREATE_TOOL_NAME)
            .doesNotContain(ToolNameConstants.CRON_DELETE_TOOL_NAME)
            .doesNotContain(ToolNameConstants.CRON_LIST_TOOL_NAME)
            .contains(ToolNameConstants.TASK_CREATE_TOOL_NAME)
            .contains(ToolNameConstants.SEND_MESSAGE_TOOL_NAME);
    }

    @Test
    @DisplayName("buildInProcessTeammateAllowed(true) 含 Cron* 3 项（AGENT_TRIGGERS 开，对齐 CC 生产 bundle）")
    void inProcessAgentTriggersOn() {
        // WHY: CC 生产 bundle G15 中 AGENT_TRIGGERS 编译 true → Cron* 3 项在集合。
        Assertions.assertThat(AgentToolUtils.buildInProcessTeammateAllowed(true))
            .as("AGENT_TRIGGERS 开 → Cron* 在 IN_PROCESS_TEAMMATE")
            .contains(ToolNameConstants.CRON_CREATE_TOOL_NAME)
            .contains(ToolNameConstants.CRON_DELETE_TOOL_NAME)
            .contains(ToolNameConstants.CRON_LIST_TOOL_NAME);
    }

    // ── 集合内容复验（D-26 ASYNC 16 项含 ToolSearch；OPD-03 COORDINATOR 4 项）──

    @Test
    @DisplayName("ASYNC_AGENT_ALLOWED_TOOLS 共 17 项含 ToolSearch + vision_analyze（PDF 分页子代理读页图）")
    void asyncAllowedHas16IncludingToolSearch() {
        // WHY: CC constants/tools.ts:55-71 字面量 16 项 + [PDF 分页子代理修复] vision_analyze 补进
        //   第 17 项（Java 文本模型 deepseek 看 PDF 页图/附件图靠 vision_analyze 代理视觉模型——
        //   fork 异步子代理 >20 页 PDF NEEDS_SUBAGENT 分页依赖；isReadOnly+isConcurrencySafe 异步安全）。
        //   含 ToolSearch 是 hook agent 反递归/补全工具的必要白名单。
        Assertions.assertThat(AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS)
            .as("ASYNC 白名单必须为 17 项（含 vision_analyze 供 PDF 分页子代理读页图）")
            .hasSize(17)
            .contains(ToolNameConstants.TOOL_SEARCH_TOOL_NAME)
            .contains(ToolNameConstants.ENTER_WORKTREE_TOOL_NAME)
            .contains(ToolNameConstants.EXIT_WORKTREE_TOOL_NAME)
            .contains(ToolNameConstants.VISION_ANALYZE_TOOL_NAME);
    }

    @Test
    @DisplayName("COORDINATOR_MODE_ALLOWED_TOOLS 共 4 项（Agent/TaskStop/SendMessage/StructuredOutput）")
    void coordinatorModeHas4() {
        // WHY: CC constants/tools.ts:107-112 — coordinator 模式仅 4 工具（agent 管理 + 输出）。
        Assertions.assertThat(AgentToolUtils.COORDINATOR_MODE_ALLOWED_TOOLS)
            .as("COORDINATOR 白名单必须为 4 项")
            .hasSize(4)
            .contains(AgentToolConstants.AGENT_TOOL_NAME)
            .contains(ToolNameConstants.TASK_STOP_TOOL_NAME)
            .contains(ToolNameConstants.SEND_MESSAGE_TOOL_NAME)
            .contains(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }

    // ── 单一权威不变量（DEL-WFB-03）──

    @Test
    @DisplayName("默认常量 = buildAllAgentDisallowed(false,true)：含 Agent + Workflow（防御性不变量，hook agent 不暴露）")
    void defaultConstantMatchesFactoryDefault() {
        // WHY: 常量在类加载时以保守默认冻结（对齐 CC bundle 常量折叠）。含 Workflow 为防御性：
        // WORKFLOW_SCRIPTS 开启、WorkflowTool 注册时 ExecAgentHook/AgentToolUtils 仍拦截 Workflow
        //（WorkflowTool.java:26-27 既有设计 + ToolFeatureStubGatingTest#workflowInAllAgentDisallowed）。
        Assertions.assertThat(ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS)
            .as("默认常量与工厂默认分支一致")
            .containsExactlyInAnyOrderElementsOf(
                ToolNameConstants.buildAllAgentDisallowed(false, true))
            .contains(AgentToolConstants.AGENT_TOOL_NAME)
            .contains(ToolNameConstants.WORKFLOW_TOOL_NAME);
    }
}
