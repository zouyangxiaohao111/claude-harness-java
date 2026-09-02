package com.nexusai.application.agent.workflow.wiring;

import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.impl.WorkflowTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowToolWiring 测试 · 对齐 CC wiring.ts（W-3e）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>进程单例</b>（wiring.ts:60-65）— tools.ts 注册与 PermissionRequest 按引用匹配需
 *       同一 Tool 实例；若每次新建，PermissionRequest 的引用匹配（switch by reference）落空，
 *       权限决策与注册表看到不同工具。</li>
 *   <li><b>懒解析不触发 service 初始化</b>（wiring.ts:14-19）— 模块加载期调用
 *       {@code createWorkflowToolCore()} 不得立即解析 ports（会触发 service 实例化 → 模块级
 *       副作用在 bootstrap 完成前拿到错误路径）；name() 不触达 descriptor/service 即验证。</li>
 *   <li><b>isEnabled 反映首次构建 flags</b>（wiring.ts:34 isEnabled → descriptor().isEnabled()）—
 *       WORKFLOW_SCRIPTS 门控语义透传到工具自身（双保险，CC tools.ts:129 + Tool.isEnabled）。</li>
 * </ol>
 */
class WorkflowToolWiringTest {

    /** 21 字段全参构造：第 15 位 workflowScripts=true（WORKFLOW_SCRIPTS 开）。 */
    private static final FeatureFlags WORKFLOW_ON = new FeatureFlags(
            false, false, false, false, false, false, false, false, false, false,
            false, false, false, false, true, false, false, false, false, false, false);

    @Test
    @DisplayName("createWorkflowToolCore 返回同一进程单例（引用匹配）")
    void createWorkflowToolCore_returnsSameSingleton() {
        WorkflowToolWiring.resetForTests();
        Tool a = WorkflowToolWiring.createWorkflowToolCore();
        Tool b = WorkflowToolWiring.createWorkflowToolCore();
        assertThat(a).isSameAs(b);
    }

    @Test
    @DisplayName("单例包装的 name 是 Workflow 工具名")
    void createWorkflowToolCore_returnsWorkflowToolName() {
        WorkflowToolWiring.resetForTests();
        Tool tool = WorkflowToolWiring.createWorkflowToolCore();
        assertThat(tool.name()).isEqualTo(WorkflowTool.NAME);
    }

    @Test
    @DisplayName("首次构建的 flags 决定 isEnabled（WORKFLOW_SCRIPTS 门控透传）")
    void isEnabled_reflectsFirstConstructedFlags() {
        WorkflowToolWiring.resetForTests();
        Tool tool = WorkflowToolWiring.createWorkflowToolCore(WORKFLOW_ON);
        assertThat(tool.isEnabled()).isTrue();
        // 单例已定型：后续无参调用返回同一实例（flags 不覆盖）
        assertThat(WorkflowToolWiring.createWorkflowToolCore()).isSameAs(tool);
    }

    @Test
    @DisplayName("默认 ALL_DISABLED → isEnabled false")
    void isEnabled_defaultsFalse_whenAllDisabled() {
        WorkflowToolWiring.resetForTests();
        assertThat(WorkflowToolWiring.createWorkflowToolCore().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("name() 不触达 descriptor/service —— 模块加载期懒解析不炸")
    void name_doesNotResolveServiceOnConstruction() {
        WorkflowToolWiring.resetForTests();
        // Spring 未装配（进程单例未登记）时，若 name() 触发 service 解析会抛
        // IllegalStateException；懒解析保证构造/元信息访问零副作用
        Tool tool = WorkflowToolWiring.createWorkflowToolCore();
        assertThat(tool.name()).isEqualTo(WorkflowTool.NAME);
        assertThat(tool.description()).isEqualTo("Execute a workflow script that orchestrates multiple subagents to complete a task");
    }
}
