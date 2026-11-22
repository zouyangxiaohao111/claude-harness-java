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
            .doesNotContain("TaskOutput")   // claude.exe 2.1.328 已从集合移除
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
    @DisplayName("ASYNC_AGENT_ALLOWED_TOOLS 共 19 项含 ToolSearch + vision_analyze + Artifact + AstGrep")
    void asyncAllowedIncludesToolSearch() {
        // WHY: CC constants/tools.ts:55-71 字面量 16 项 + vision_analyze（PDF 分页读页图）
        //   + Artifact（worker 发布 HTML 产物的通道，CC 协调者把发布委派给 worker）。
        //   + [AST 工具] AstGrep 第 19 项（只读结构化搜索；CC 无 AstGrep，属本仓增强；
        //   AstEdit 可写，⛔ 不进白名单）。
        Assertions.assertThat(AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS)
            .as("ASYNC 白名单必须为 19 项（含 vision_analyze + Artifact + AstGrep）")
            .hasSize(19)
            .contains(ToolNameConstants.TOOL_SEARCH_TOOL_NAME)
            .contains(ToolNameConstants.ENTER_WORKTREE_TOOL_NAME)
            .contains(ToolNameConstants.EXIT_WORKTREE_TOOL_NAME)
            .contains(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains(ToolNameConstants.ARTIFACT_TOOL_NAME)
            .contains(ToolNameConstants.AST_GREP_TOOL_NAME);
    }

    @Test
    @DisplayName("COORDINATOR_MODE_ALLOWED_TOOLS 共 9 项（exe 2.1.328 vfn/E8r + Skill/Workflow/ListAgents + 本仓 SessionCreate）")
    void coordinatorModeMatchesExeWhitelist() {
        // WHY: exe 2.1.328 的协调者过滤 vfn 允许 E8r（9 项，3 项静态未确名）+ PR 订阅等。
        //   本仓取已确名且存在的工具并集，保留旧版 StructuredOutput；
        //   2026-11-20 用户裁定追加 SessionCreate（本仓扩展，CC 无创建会话工具）。
        Assertions.assertThat(AgentToolUtils.COORDINATOR_MODE_ALLOWED_TOOLS)
            .as("COORDINATOR 白名单 = Agent/TaskStop/SendMessage/SessionCreate/Skill/Workflow/ListAgents/StructuredOutput")
            .hasSize(9)
            .contains(AgentToolConstants.AGENT_TOOL_NAME)
            .contains(ToolNameConstants.TASK_STOP_TOOL_NAME)
            .contains(ToolNameConstants.SEND_MESSAGE_TOOL_NAME)
            .contains(ToolNameConstants.SESSION_CREATE_TOOL_NAME)
            .contains(ToolNameConstants.SKILL_TOOL_NAME)
            .contains(ToolNameConstants.WORKFLOW_TOOL_NAME)
            .contains(ToolNameConstants.LIST_AGENTS_TOOL_NAME)
            .contains(ToolNameConstants.LIST_MODELS_TOOL_NAME)
            .contains(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }

    @Test
    @DisplayName("SessionCreate 已在注册表（@Component + COMMON），协调者白名单按名可见")
    void sessionCreateRegisteredAndCoordinatorVisible() {
        // WHY: 需求 3 的判据 —— SessionCreate 若「没有 @Component / 未进注册表」才需要补注册。
        //   本仓它是 @Component（ToolRegistry 的 @Autowired List<Tool> 自动收集）；且未标
        //   @ExclusiveTool ⇒ ToolScope.COMMON，进默认池/通配池。此断言把「已注册」这一事实钉住，
        //   避免后续有人误判成「工具不存在」而重复实现。
        Assertions.assertThat(com.nexusai.application.agent.tool.impl.SessionCreateTool.class
                .isAnnotationPresent(org.springframework.stereotype.Component.class))
            .as("SessionCreateTool 必须带 @Component（Spring 扫描进 ToolRegistry）")
            .isTrue();
        Assertions.assertThat(ToolScope.of(new com.nexusai.application.agent.tool.impl.SessionCreateTool()))
            .as("SessionCreate 非专属工具 ⇒ 默认池可见")
            .isEqualTo(ToolScope.COMMON);
        Assertions.assertThat(AgentToolUtils.COORDINATOR_MODE_ALLOWED_TOOLS)
            .as("协调者白名单含 SessionCreate（本仓扩展）")
            .contains(ToolNameConstants.SESSION_CREATE_TOOL_NAME);
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
