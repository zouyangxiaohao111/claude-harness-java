package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * s13 后台任务基础设施 Spring 装配
 *
 * <p>创建 NotificationQueue → TaskFrameworkService → BackgroundTaskRunner 单例 Bean 链
 */
@Configuration
public class TaskConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaskConfiguration.class);

    /**
     * 出站序列化用 ObjectMapper · 与 {@code LlmAgentLoop.JSON}（:310 同款裸实例）**同一种序列化** ——
     * record 自带 {@code @JsonProperty} + {@code @JsonInclude(NON_NULL)}，无需 registerModules。
     * ⛔ 不另造第二套序列化（否则同一 topic 上两个分支的字段形态会不一致）。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

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
            org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate,
            // [第 5 环] 主会话后台化任务 TaskStop 分发：stopTask → stopMainSessionTask →
            // MainSessionBackgroundService.killMainSessionTask（对齐 CC stopTask.ts:38-65
            // getTaskByType('local_agent').kill —— CC 主会话任务与子代理任务同 registry 同型）。
            //
            // ⚠️ 形参用 @Lazy：**直接**方向无环（第 1 步已读码核：MainSessionBackgroundService 的字段
            //   无 BackgroundTaskRunner），但**间接**路径在 @Bean 形参解析期会成环 ——
            //   BackgroundTaskRunner(@Bean 形参) → MainSessionBackgroundService → ChatService
            //   （@Autowired 字段）→ ToolRegistry → 工具 bean（BashTool:440
            //   `@Autowired(required=false) BackgroundTaskRunner`）→ 回到本 runner。
            //   @Bean 形参在 createBeanInstance 期解析，拿不到早期引用 ⇒ 非 @Lazy 会
            //   BeanCurrentlyInCreationException。本仓对同一族环路已有先例与同样处理
            //   （ToolRegistrationConfig:709/:718-725 subagentExecutor 的 @Lazy ToolRegistry /
            //   @Lazy BackgroundTaskRunner，注释明写「merge 后 5 个 @SpringBootTest 上下文加载失败」）。
            //   @Lazy 只延迟解析时点、不改语义：setter 存的是解析代理，stopTask 真正调用时才取真 bean。
            @org.springframework.context.annotation.Lazy
            MainSessionBackgroundService mainSessionBackgroundService) {
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
        // [单通道出站 · 入队即投递] SdkEventQueue 的投递器接线（本批唯一注入点）。
        //   WHY 在此而不在 sdkEventQueue() @Bean：那里拿不到 wsTemplate，硬加形参会**新增 bean 依赖**
        //   （本仓有 5 个 @SpringBootTest 上下文因新增形参加载失败的前例）；本方法已有 wsTemplate 形参
        //   ⇒ 零新增依赖。无循环依赖 —— SimpMessagingTemplate 是 WebSocket 基础设施 bean，不依赖本 runner。
        //   效果：四类 SDK 事件（task_started / task_progress / task_notification / session_state_changed）
        //   在**入队点**立即以**扁平 JSON 数组**形态推 /topic/tasks（前端 useChatSocket.ts:447
        //   `Array.isArray(raw) ? raw : [raw]` 两形态都吃，:683 分支按 subtype/session_id 消费）。
        //   对齐 CC 2.1.281 结构：Cf/Tke 注册入队监听器（exe 220377666 / 安装点 220593897）
        //   + Zp.enqueue 末尾 this.enqueueListener?.()（220265752）。
        //   ⚠️ 唯一注入点 ⇒ 所有 `new SdkEventQueue()` 的测试直构天然是 null 钩子（纯队列语义，
        //   40+ 处测试构造零改动）；LlmAgentLoop turn 顶部 drain 退化为幂等兜底。
        sdkEventQueue.setDeliverer(drained -> {
            List<JsonNode> nodes = SdkEventQueue.toFlatJsonNodes(drained, JSON);
            if (!nodes.isEmpty()) {
                wsTemplate.convertAndSend("/topic/tasks", nodes);
            }
        });
        log.info("TaskConfiguration: SdkEventQueue 投递器已装配 → /topic/tasks（入队即投递 · 单通道出站）");
        // [第 5 环] 主会话后台化任务 stopTask 分发（stopMainSessionTask → killMainSessionTask）。
        //   判别器 = frameworkService.getMainSessionTask（mainSessionStore 归属），
        //   ⛔ 不是 type（主会话与子代理同为 local_agent）、⛔ 不是 taskId 前缀（私有常量）。
        runner.setMainSessionBackgroundService(mainSessionBackgroundService);
        log.info("TaskConfiguration: BackgroundTaskRunner 第 5 环已接线 mainSessionBackgroundService={}",
            mainSessionBackgroundService != null);
        return runner;
    }
}
