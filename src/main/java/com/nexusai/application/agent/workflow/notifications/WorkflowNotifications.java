package com.nexusai.application.agent.workflow.notifications;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.workflow.WorkflowService;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * workflow 状态变更通知桥 · CC original: {@code notifications.ts}
 * (Open-ClaudeCode/src/workflow/notifications.ts:1-88)。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 行为意图）</b>：引擎经 {@code progressEmitter.emit({type:'run_done'})}
 * 发事件、store 归约记录 status；但旧实现没有把 running → completed/failed/killed 的状态迁移
 * 桥接到宿主通知机制——WorkflowTool 返回文本里"notified on completion"的承诺落了空。
 * 本类订阅 {@link WorkflowService#subscribe}，watch status 迁移（running → 终态），经注入的
 * notifier 回调发宿主通知（默认 = enqueuePendingNotification task-notification 模式）。
 *
 * <p><b>首见只记录不通知</b>（notifications.ts:51-56）：安装时已存在的历史 run（含
 * loadPersistedRuns 水合进 store 的终态 run）只登记 prevStatus，不当作新通知误发。
 *
 * <p>Java 侧默认 notifier 走 {@link NotificationQueue#enqueuePendingNotification}
 * （{@code QueueItem(xml, MODE_TASK_NOTIFICATION)}，priority 由入队方法补 LATER）——
 * 对齐 CC notifications.ts:37-39 defaultNotifier。
 */
public final class WorkflowNotifications {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNotifications.class);

    /** 通知 XML 的 task-type · CC original: WORKFLOW_TASK_TYPE (notifications.ts:25)。 */
    public static final String WORKFLOW_TASK_TYPE = "local_workflow";

    /**
     * 终态集合 · CC original: TERMINAL_STATUSES (notifications.ts:30-34)。
     * 仅从这些状态发通知（running → completed/failed/killed 迁移才触发）。
     */
    private static final Set<RunProgress.Status> TERMINAL_STATUSES =
            Set.of(RunProgress.Status.COMPLETED, RunProgress.Status.FAILED, RunProgress.Status.KILLED);

    private WorkflowNotifications() {
    }

    /**
     * 通知器抽象 · CC original: {@code WorkflowNotifier = (message: string) => void}
     * (notifications.ts:28)（测试可注入 spy）。
     */
    @FunctionalInterface
    public interface WorkflowNotifier {
        /** 发一条任务通知消息（XML 文本）。 */
        void notify(String message);
    }

    /**
     * 安装状态变更通知 · CC original: {@code installWorkflowNotifications(service, notify)}
     * (notifications.ts:41-69)。
     *
     * <p>逻辑（逐行对齐）：
     * <ol>
     *   <li>{@code prevStatus Map} 记录每个 runId 的上次 status（notifications.ts:45）。</li>
     *   <li>{@code service.subscribe}：每次 store 快照变更遍历 listRuns（notifications.ts:47-48）。</li>
     *   <li>首见该 run → 只记录当前 status 不通知（防把历史 run 当新通知，notifications.ts:51-56）。</li>
     *   <li>status 变更 且 进入终态 → {@code notify(buildMessage(run))}（notifications.ts:58-60）。</li>
     * </ol>
     *
     * @param service WorkflowService（订阅 + listRuns）
     * @param notify  通知器（默认经 NotificationQueue task-notification 出站；测试注入 spy）
     * @return 卸载 Runnable（退订 + 清 prevStatus，notifications.ts:65-68）
     */
    public static Runnable installWorkflowNotifications(WorkflowService service, WorkflowNotifier notify) {
        Map<String, RunProgress.Status> prevStatus = new ConcurrentHashMap<>();

        Runnable unsubscribe = service.subscribe(() -> {
            // notifications.ts:47-48 每次快照变更遍历全部 run
            for (RunProgress run : service.listRuns()) {
                RunProgress.Status prev = prevStatus.get(run.runId());
                // notifications.ts:51-56 首见只记录不通知（历史 run 不作为新通知）
                if (prev == null) {
                    prevStatus.put(run.runId(), run.status());
                    continue;
                }
                // notifications.ts:58-60 状态变更 + 进入终态 → 发通知
                if (prev != run.status() && TERMINAL_STATUSES.contains(run.status())) {
                    notify.notify(buildMessage(run));
                    if (log.isDebugEnabled()) {
                        log.debug("WorkflowNotifications 终态通知：runId={} status={}（notifications.ts:58-60）",
                                run.runId(), run.status());
                    }
                }
                prevStatus.put(run.runId(), run.status());
            }
        });

        return () -> {
            // notifications.ts:65-68 卸载：退订 + 清 prevStatus
            unsubscribe.run();
            prevStatus.clear();
        };
    }

    /**
     * 生产便捷安装 · 默认 notifier 经 {@link NotificationQueue#enqueuePendingNotification}
     * task-notification 模式出站（对齐 CC notifications.ts:37-39）。
     *
     * @param service        WorkflowService
     * @param notificationQueue Spring 单例通知队列（TaskConfiguration.notificationQueue）
     * @return 卸载 Runnable
     */
    public static Runnable installWorkflowNotifications(WorkflowService service, NotificationQueue notificationQueue) {
        return installWorkflowNotifications(service, notifierUsing(notificationQueue));
    }

    /**
     * 默认通知器 · CC original: notifications.ts:37-39
     * {@code message => enqueuePendingNotification({value: message, mode: 'task-notification'})}。
     * priority 由入队方法补 LATER（后台通知不饿死用户输入，messageQueueManager.ts:142-149）。
     *
     * @param queue 通知队列
     * @return 入队 notifier
     */
    public static WorkflowNotifier notifierUsing(NotificationQueue queue) {
        return message -> {
            if (queue == null) {
                log.warn("[workflow warn] 通知队列未注入，workflow 终态通知丢弃：{}（notifications.ts:37-39）",
                        message);
                return;
            }
            queue.enqueuePendingNotification(
                    new NotificationQueue.QueueItem(message, NotificationQueue.MODE_TASK_NOTIFICATION));
        };
    }

    /**
     * 构建任务通知 XML · CC original: {@code buildMessage(run)} (notifications.ts:71-88)。
     *
     * <p>summary 文案（notifications.ts:72-80）：
     * <ul>
     *   <li>completed → {@code Workflow "{workflowName}" completed successfully}</li>
     *   <li>failed → {@code Workflow "{workflowName}" failed: {error}}</li>
     *   <li>killed → {@code Workflow "{workflowName}" was stopped}</li>
     * </ul>
     *
     * <p>XML（notifications.ts:82-87，无缩进、逐字对齐）：
     * <pre>
     * &lt;task-notification&gt;
     * &lt;task-id&gt;{runId}&lt;/task-id&gt;
     * &lt;task-type&gt;local_workflow&lt;/task-type&gt;
     * &lt;status&gt;{status}&lt;/status&gt;
     * &lt;summary&gt;{summary}&lt;/summary&gt;
     * &lt;/task-notification&gt;
     * </pre>
     *
     * <p>status tag 值取小写枚举名（completed/failed/killed，notifications.ts:85 原样拼 run.status；
     * Java 枚举名大写 → toLowerCase 对齐 CC 字符串）。
     *
     * @param run 进入终态的 run
     * @return task-notification XML
     */
    public static String buildMessage(RunProgress run) {
        // notifications.ts:72-77 statusText
        String statusText = switch (run.status()) {
            case COMPLETED -> "completed successfully";
            case FAILED -> "failed";
            case KILLED -> "was stopped";
            default -> run.status().name().toLowerCase();
        };
        // notifications.ts:78-79 failed + error → ": {error}"
        String errorSuffix = (run.status() == RunProgress.Status.FAILED && run.error() != null)
                ? ": " + run.error() : "";
        // notifications.ts:80 summary
        String summary = "Workflow \"" + run.workflowName() + "\" " + statusText + errorSuffix;

        // notifications.ts:82-87 XML（逐字，无缩进）
        return "<task-notification>\n"
                + "<task-id>" + run.runId() + "</task-id>\n"
                + "<task-type>" + WORKFLOW_TASK_TYPE + "</task-type>\n"
                + "<status>" + run.status().name().toLowerCase() + "</status>\n"
                + "<summary>" + summary + "</summary>\n"
                + "</task-notification>";
    }
}
