package com.nexusai.application.agent.workflow.notifications;

import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.workflow.LaunchResult;
import com.nexusai.application.agent.workflow.LaunchInput;
import com.nexusai.application.agent.workflow.WorkflowPorts;
import com.nexusai.application.agent.workflow.WorkflowService;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowNotifications 测试 · 对齐 CC notifications.ts（W-3d）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>首见只记录不通知</b>（notifications.ts:51-56）— 安装时已存在的历史 run（含
 *       loadPersistedRuns 水合的终态 run）不得当作新通知误发；否则每次面板打开都轰炸一遍。</li>
 *   <li><b>仅 running → 终态迁移触发</b>（notifications.ts:58-60）— 同状态重复事件不重发
 *       （幂等）；这是"WorkflowTool 返回文本承诺 notified on completion"的兑现点。</li>
 *   <li><b>XML 逐字对齐</b>（notifications.ts:71-88）— status tag 取小写终态字符串，
 *       summary 含 workflowName + failed error 后缀；下游模型解析依赖此结构。</li>
 * </ol>
 */
class WorkflowNotificationsTest {

    /** 驱动状态变更的最小 fake service · 对齐 service.ts listRuns/subscribe 契约。 */
    static final class FakeService implements WorkflowService {
        private final List<Runnable> listeners = new ArrayList<>();
        private final List<RunProgress> runs = new ArrayList<>();

        void setRuns(List<RunProgress> next) {
            runs.clear();
            runs.addAll(next);
            for (Runnable listener : new ArrayList<>(listeners)) {
                listener.run();
            }
        }

        @Override public WorkflowPorts ports() { return null; }
        @Override public CompletableFuture<LaunchResult> launch(LaunchInput input, ToolUseContext ctx, Object canUseTool) {
            return null;
        }
        @Override public void kill(String runId) { }
        @Override public boolean killAgent(String runId, int agentId) { return false; }
        @Override public void shutdown() { }
        @Override public List<RunProgress> listRuns() { return runs; }
        @Override public RunProgress getRun(String runId) {
            return runs.stream().filter(r -> r.runId().equals(runId)).findFirst().orElse(null);
        }
        @Override public CompletableFuture<RunProgress> getRunAsync(String runId) {
            return CompletableFuture.completedFuture(getRun(runId));
        }
        @Override public void loadPersistedRuns() { }
        @Override public Runnable subscribe(Runnable listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
        @Override public List<String> listNamed(String workflowDir) { return List.of(); }
    }

    private static RunProgress run(String runId, RunProgress.Status status) {
        return run(runId, status, null);
    }

    private static RunProgress run(String runId, RunProgress.Status status, String error) {
        return RunProgress.builder()
                .runId(runId)
                .workflowName("wf")
                .status(status)
                .phases(List.of())
                .declaredPhases(List.of())
                .currentPhase(null)
                .agents(List.of())
                .agentCount(0)
                .returnValue(null)
                .error(error)
                .startedAt(1000L)
                .description(null)
                .updatedAt(2000L)
                .build();
    }

    @Test
    @DisplayName("首见 run 只记录不通知（历史 run 不当新通知）")
    void firstSeenRun_recordsWithoutNotifying() {
        FakeService service = new FakeService();
        List<String> notifications = new ArrayList<>();
        WorkflowNotifications.installWorkflowNotifications(service, notifications::add);

        // 安装后首次快照：running 只是登记 prevStatus
        service.setRuns(List.of(run("r-1", RunProgress.Status.RUNNING)));
        assertThat(notifications).isEmpty();

        // 历史终态 run 水合进 store（loadPersistedRuns 场景）→ 首见只记录
        service.setRuns(List.of(run("r-2", RunProgress.Status.COMPLETED)));
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("running → completed 迁移发一次通知，同状态重复不重发")
    void runningToCompleted_notifiesExactlyOnce() {
        FakeService service = new FakeService();
        List<String> notifications = new ArrayList<>();
        WorkflowNotifications.installWorkflowNotifications(service, notifications::add);

        service.setRuns(List.of(run("r-1", RunProgress.Status.RUNNING)));
        assertThat(notifications).isEmpty();

        service.setRuns(List.of(run("r-1", RunProgress.Status.COMPLETED)));
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).contains("<status>completed</status>")
                .contains("Workflow \"wf\" completed successfully");

        // 同状态重复快照 → 不重发（幂等）
        service.setRuns(List.of(run("r-1", RunProgress.Status.COMPLETED)));
        assertThat(notifications).hasSize(1);
    }

    @Test
    @DisplayName("running → failed 携带 error 后缀")
    void runningToFailed_notifiesWithErrorSuffix() {
        FakeService service = new FakeService();
        List<String> notifications = new ArrayList<>();
        WorkflowNotifications.installWorkflowNotifications(service, notifications::add);

        service.setRuns(List.of(run("r-1", RunProgress.Status.RUNNING)));
        service.setRuns(List.of(run("r-1", RunProgress.Status.FAILED, "boom")));

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).contains("Workflow \"wf\" failed: boom");
    }

    @Test
    @DisplayName("running → killed 迁移发通知")
    void runningToKilled_notifies() {
        FakeService service = new FakeService();
        List<String> notifications = new ArrayList<>();
        WorkflowNotifications.installWorkflowNotifications(service, notifications::add);

        service.setRuns(List.of(run("r-1", RunProgress.Status.RUNNING)));
        service.setRuns(List.of(run("r-1", RunProgress.Status.KILLED)));

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).contains("Workflow \"wf\" was stopped")
                .contains("<status>killed</status>");
    }

    @Test
    @DisplayName("卸载后不再发通知")
    void unsubscribe_stopsNotifications() {
        FakeService service = new FakeService();
        List<String> notifications = new ArrayList<>();
        Runnable unsubscribe = WorkflowNotifications.installWorkflowNotifications(service, notifications::add);

        service.setRuns(List.of(run("r-1", RunProgress.Status.RUNNING)));
        unsubscribe.run();
        service.setRuns(List.of(run("r-1", RunProgress.Status.COMPLETED)));

        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("buildMessage 逐字对齐 CC XML 结构（task-id/task-type/status/summary 小写终态）")
    void buildMessage_matchesCcXmlStructure() {
        String message = WorkflowNotifications.buildMessage(
                run("run-42", RunProgress.Status.FAILED, "agent crashed"));

        assertThat(message).isEqualTo("""
                <task-notification>
                <task-id>run-42</task-id>
                <task-type>local_workflow</task-type>
                <status>failed</status>
                <summary>Workflow "wf" failed: agent crashed</summary>
                </task-notification>""");
    }
}
