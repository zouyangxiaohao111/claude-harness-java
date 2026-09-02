package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * s13 后台任务基础设施 Spring 装配
 *
 * <p>创建 NotificationQueue → TaskFrameworkService → BackgroundTaskRunner 单例 Bean 链
 */
@Configuration
public class TaskConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaskConfiguration.class);

    @Bean
    public NotificationQueue notificationQueue() {
        log.info("TaskConfiguration: creating NotificationQueue bean");
        return new NotificationQueue();
    }

    /**
     * SDK 事件队列 · 对齐 CC utils/sdkEventQueue.ts（进程级 module 队列）
     * — OPD-TS-22：后台任务接入 enqueueSdkEvent 等价物，发 4 类 SDK 事件。
     */
    @Bean
    public SdkEventQueue sdkEventQueue() {
        log.info("TaskConfiguration: creating SdkEventQueue bean");
        return new SdkEventQueue();
    }

    @Bean
    public TaskFrameworkService taskFrameworkService(SdkEventQueue sdkEventQueue) {
        log.info("TaskConfiguration: creating TaskFrameworkService bean (sdkEventQueue={})",
            sdkEventQueue != null);
        return new TaskFrameworkService(sdkEventQueue);
    }

    /**
     * Dream 任务注册表 · OPD-TP-09（registerDreamTask + addDreamTurn + complete/fail/kill）。
     *
     * <p>落统一 store 走 TaskFrameworkService（registerTask → SDK task_started；
     * updateTaskState → 终态可 evict）。kill 的锁回退 seam（rollbackConsolidationLock）由
     * AutoDreamConsolidator 装配（ToolRegistrationConfig.autoDreamConsolidator）注入。
     */
    @Bean
    public DreamTaskRegistry dreamTaskRegistry(TaskFrameworkService taskFrameworkService) {
        log.info("TaskConfiguration: creating DreamTaskRegistry bean");
        return new DreamTaskRegistry(taskFrameworkService);
    }

    /**
     * [IMPL-10] DEL-L03-02: BackgroundTaskRunner 的 HookRegistry 注入已移除
     * （TaskCompleted/TeammateIdle 发射已删除，CC 无 background-task 完成触发路径）。
     *
     * <p>OPD-TP-09：注入 {@link DreamTaskRegistry} 供 TaskStop 按 type 分发到 dream
     * （getTaskByType('dream') → DreamTask.kill 等价）。
     *
     * <p>OPD-TS-25：注入 {@link MonitorMcpTaskRunner} 供 TaskStop 按 type 分发到 monitor_mcp
     * （getTaskByType('monitor_mcp') → MonitorMcpTask.kill 等价）+ subagent 结束
     * killMonitorMcpTasksForAgent（CC runAgent.ts:852-861）。
     */
    @Bean
    public BackgroundTaskRunner backgroundTaskRunner(
            NotificationQueue notificationQueue,
            TaskFrameworkService taskFrameworkService,
            SdkEventQueue sdkEventQueue,
            DreamTaskRegistry dreamTaskRegistry,
            com.nexusai.application.agent.remote.RemoteAgentTaskService remoteAgentTaskService,
            MonitorMcpTaskRunner monitorMcpTaskRunner,
            com.nexusai.application.agent.team.SpawnInProcess spawnInProcess,
            org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate) {
        log.info("TaskConfiguration: creating BackgroundTaskRunner bean (sdkEventQueue={}, dreamRegistry={}, remoteAgentTaskService={}, monitorRunner={})",
            sdkEventQueue != null, dreamTaskRegistry != null, remoteAgentTaskService != null, monitorMcpTaskRunner != null);
        BackgroundTaskRunner runner =
            new BackgroundTaskRunner(notificationQueue, taskFrameworkService, sdkEventQueue);
        runner.setDreamTaskRegistry(dreamTaskRegistry);
        // M-9 remote_agent kill 分发：stopTask REMOTE_AGENT 委托 RemoteAgentTaskService.kill
        // （RemoteTaskConfiguration 已定义该 bean；无循环依赖 —— RemoteAgentTaskService 不依赖本 runner）
        runner.setRemoteAgentTaskService(remoteAgentTaskService);
        runner.setMonitorMcpTaskRunner(monitorMcpTaskRunner);
        // [IMP-G3] in_process_teammate kill 分发：stopTask IN_PROCESS_TEAMMATE 委托
        // SpawnInProcess.registry().kill（对齐 CC stopTask.ts:57-65 getTaskByType('in_process_teammate')
        // → spawnInProcess.ts:227-328 killInProcessTeammate）。无循环依赖 —— SpawnInProcess 依赖
        // SubagentExecutor/TaskFrameworkService 等，均不依赖本 runner（SubagentExecutor 的
        // setBackgroundTaskRunner 为普通 setter 非 @Autowired，Spring 不自动注入）。
        runner.setSpawnInProcess(spawnInProcess);
        // [cron-task-inject-align C8 · 决策8] 注入 STOMP 模板 → emitTerminatedSdk 直推 /topic/tasks
        // （空闲路径 / 无 turn 无 SDK drain 时前端仍收到结构化 task_notification）。无循环依赖 ——
        // SimpMessagingTemplate 是 WebSocket 基础设施 bean，不依赖本 runner。
        runner.setWsTemplate(wsTemplate);
        return runner;
    }
}
