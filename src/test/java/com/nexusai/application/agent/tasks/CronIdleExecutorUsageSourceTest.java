package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.eventbus.ws.MessageUsageEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D1 usage-source] 队列命令 → 本 run 的 message.usage 来源标记（「谁发起的」唯一可靠判据）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：底部「缓存利用率 / 当前上下文」被同会话后台任务污染
 * （实测 63% 的 message.usage 事件出自 {@code cron-idle-*} 线程，取「最后一条带 usage 的条目」时被
 * 后台任务那轮顶掉）。修复要求事件带来源标记、前端只统计「用户自己的请求」——于是判据必须成立：
 * <b>cron 调度（TestJob fire：mode=prompt + workload=cron）与任务通知（mode=task-notification）都不是
 * 用户自己的请求 → background；busy-queued 是用户自己排的队 → user</b>。
 *
 * <p>为什么这条判据只能在这里成立：run 进 LlmAgentLoop 之后，{@code querySource} 恒
 * {@code REPL_MAIN_THREAD}（RunRequest.sessionFromSessionOverride）、{@code userMessageId} 也拿不到
 * （task-notification 的 {@code cmd.uuid()=null} → 注入消息落随机 UUID；cron fire 的 uuid 形如
 * {@code msg-xxxxxxxx} 与真实用户消息同形）—— 队列项的 mode/workload 是唯一还握着「谁发起的」的地方。
 *
 * <p><b>RED（改回错判哪条红）</b>：把 {@code usageSourceOf} 恒返回 user（或缺了 WORKLOAD_CRON 分支）
 * → 「cron 调度 fire = background」红；把判据反过来（MODE_PROMPT+workload=null 也当 background）
 * → 「busy-queued 用户排队消息 = user」红（会把用户自己排的消息从底部数字里删掉）。
 */
class CronIdleExecutorUsageSourceTest {

    /** 10 参构造：(value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, origin, sessionId)。 */
    private static QueueItem item(String mode, String workload) {
        return new QueueItem("文本", mode, Priority.LATER, null, "u-1", true, workload, false, null, "sess-1");
    }

    @Test
    @DisplayName("MODE_TASK_NOTIFICATION → background（后台任务完成通知，不是用户自己的请求）")
    void taskNotificationIsBackground() {
        assertThat(CronIdleExecutor.usageSourceOf(item(NotificationQueue.MODE_TASK_NOTIFICATION, null)))
            .isEqualTo(MessageUsageEvent.SOURCE_BACKGROUND);
        // 生产形态（BackgroundTaskRunner / RemoteAgentTaskService）：mode=task-notification + workload=null
    }

    @Test
    @DisplayName("cron 调度 fire（TestJob 入队：mode=prompt + workload=cron）→ background")
    void cronWorkloadIsBackground() {
        assertThat(CronIdleExecutor.usageSourceOf(item(NotificationQueue.MODE_PROMPT, NotificationQueue.WORKLOAD_CRON)))
            .isEqualTo(MessageUsageEvent.SOURCE_BACKGROUND);
    }

    @Test
    @DisplayName("busy-queued 用户排队消息（mode=prompt + workload=busy-queued）→ user（用户自己排的队，必须计入）")
    void busyQueuedIsUser() {
        assertThat(CronIdleExecutor.usageSourceOf(item(NotificationQueue.MODE_PROMPT, "busy-queued")))
            .isEqualTo(MessageUsageEvent.SOURCE_USER);
    }

    @Test
    @DisplayName("缺省（未知 mode/workload，含测试直构 2 参项）→ user（缺省用户来源，不隐藏既有数据）")
    void unknownDefaultsToUser() {
        assertThat(CronIdleExecutor.usageSourceOf(new QueueItem("文本", NotificationQueue.MODE_PROMPT)))
            .isEqualTo(MessageUsageEvent.SOURCE_USER);
        assertThat(CronIdleExecutor.usageSourceOf(item(NotificationQueue.MODE_PROMPT, null)))
            .isEqualTo(MessageUsageEvent.SOURCE_USER);
    }
}
