package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [RF-2 ②/③] 前台子代理任务登记 + 后台化切换（registerAgentForeground / backgroundAgentTask /
 * backgroundAll / unregisterAgentForeground）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：CC sync 子代理在 query loop 启动前经
 * {@code registerAgentForeground} 登记为前台任务（isBackgrounded=false，LocalAgentTask.tsx:565），
 * 该 taskId（= agentId）作为 {@code summaryTaskId} 守卫 sync 摘要门（AgentTool.tsx:852
 * {@code summaryTaskId && sdk}）；用户 Ctrl+B 经 {@code backgroundAll} 把前台任务转后台
 * （isBackgrounded=true，LocalShellTask.tsx:390-410）。Java 此前无此机制 → summaryTaskId 恒 null →
 * sync 摘要被抑制（open-decisions C-1 受控残留）。本测试锁定四方法的状态语义：
 * <ul>
 *   <li>前台登记 isBackgrounded=false，后台化后 isBackgrounded=true（幂等）</li>
 *   <li>backgroundAll 只切前台任务，不碰已后台化/其他类型</li>
 *   <li>unregisterAgentForeground 仅注销前台任务，后台化任务不注销</li>
 * </ul>
 */
@DisplayName("[RF-2] BackgroundTaskRunner 前台登记 + 后台化切换")
class BackgroundTaskRunnerForegroundBackgroundTest {

    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(
        mock(NotificationQueue.class), framework);

    @Test
    @DisplayName("registerAgentForeground 登记前台任务 isBackgrounded=false，taskId=agentId 合一")
    void registerAgentForeground_registersForeground() {
        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAgentForeground(agentId, "研究项目结构", "prompt", "general-purpose", null);

        assertThat(task.id()).isEqualTo(agentId.toString());
        assertThat(task.isBackgrounded()).isFalse();
        assertThat(task.type()).isEqualTo(TaskType.LOCAL_AGENT);
        // 落入统一 store（CC registerTask → state.tasks，framework.ts:77-117）
        assertThat(framework.getTask(agentId.toString())).isPresent();
    }

    @Test
    @DisplayName("backgroundAgentTask 前台 → 后台切换，幂等（已后台化再切 false）")
    void backgroundAgentTask_flipsAndIsIdempotent() {
        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAgentForeground(agentId, "写测试", "prompt", "general-purpose", null);

        assertThat(runner.backgroundAgentTask(task.id())).isTrue();
        assertThat(runner.backgroundAgentTask(task.id())).isFalse(); // 已后台化，幂等 false
        assertThat(framework.getTask(task.id()).orElseThrow().isBackgrounded()).isTrue();
    }

    @Test
    @DisplayName("backgroundAll 后台化所有前台任务，已后台化/非 local_agent 不受影响")
    void backgroundAll_backgroundsOnlyForegroundAgents() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        BackgroundTask t1 = runner.registerAgentForeground(a1, "A", "p", "general-purpose", null);
        BackgroundTask t2 = runner.registerAgentForeground(a2, "B", "p", "general-purpose", null);
        runner.registerAgentForeground(a3, "C", "p", "general-purpose", null);
        runner.backgroundAgentTask(t1.id()); // 预先后台化 t1

        int count = runner.backgroundAll();

        assertThat(count).isEqualTo(2); // t2 + t3；t1 已后台化不重复计入
        assertThat(framework.getTask(t2.id()).orElseThrow().isBackgrounded()).isTrue();
    }

    @Test
    @DisplayName("unregisterAgentForeground 注销前台任务；后台化任务不注销")
    void unregisterAgentForeground_onlyRemovesForeground() {
        UUID fg = UUID.randomUUID();
        UUID bg = UUID.randomUUID();
        BackgroundTask foreground = runner.registerAgentForeground(fg, "前台", "p", "general-purpose", null);
        BackgroundTask background = runner.registerAgentForeground(bg, "转后台", "p", "general-purpose", null);
        runner.backgroundAgentTask(background.id());

        assertThat(runner.unregisterAgentForeground(foreground.id())).isTrue();
        assertThat(runner.unregisterAgentForeground(background.id())).isFalse(); // 后台化不注销
        assertThat(framework.getTask(foreground.id())).isEmpty();
    }
}
