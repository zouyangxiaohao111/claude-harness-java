package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SDK 事件队列 — 对齐 CC utils/sdkEventQueue.ts（134L）。
 *
 * <p>承载后台任务对 SDK/STOMP 通道的 4 类事件（{@code type:'system'}）：
 * task_started / task_progress / task_notification / session_state_changed。
 * <ul>
 *   <li>{@link #enqueueSdkEvent} 对齐 CC :77-87（非交互 gate + 满 MAX_QUEUE_SIZE shift）</li>
 *   <li>{@link #drainSdkEvents} 对齐 CC :89-101（全取 + 补 uuid/session_id）</li>
 *   <li>{@link #emitTaskTerminatedSdk} 对齐 CC :114-134（终态 bookend）</li>
 *   <li>{@link #emitTaskProgress} 对齐 CC utils/task/sdkProgress.ts:10-36</li>
 * </ul>
 *
 * <p><b>gate 语义（CC 实际源码）</b>：enqueueSdkEvent 先判
 * {@code getIsNonInteractiveSession()}（state.ts:1057-1059 = {@code !STATE.isInteractive}）。
 * CC 全仓无 setIsInteractive 生产调用（isInteractive 恒 false）→ gate 实际恒放行。
 * Java 侧 volatile 默认 true（= 非交互，恒放行），保留 setter 供未来 TUI 等价模式接线。
 *
 * <p><b>线程安全</b>：CC 单进程单线程；Java 为后台 worker 线程 enqueue + 主循环 drain，
 * 队列访问 synchronized 保证 splice/shift 原子性。
 *
 * <p><b>多会话适配</b>：CC 单会话进程 drain 时取全局 {@code getSessionId()}；Java 多会话共用
 * 进程级队列，drain 由具体会话 turn 发起，session_id 由 {@link #drainSdkEvents(String)} 入参
 * （= 当前 drain 会话 id，语义一致）。跨会话 drain 理论窗口已在 W3-01-execute 风险登记。
 *
 * <p><b>任务归属（RK-w5-2，WF5-03c）</b>：前台主 loop 与后台派生 loop（startBackgroundSession）
 * 并发运行，共用本进程级单例队列。若无归属隔离，后台 loop 全取会把前台/他会话任务事件盖成
 * 后台 session_id（跨会话错标，前端按 session_id 过滤即漏本会话任务）。故：
 * <ul>
 *   <li>{@link #enqueueSdkEvent} 入队时打标 {@code ownerTaskId}（从事件自身 {@code task_id} 提取；
 *       {@code session_state_changed} 无 task 归属 → null）</li>
 *   <li>{@link #drainSdkEvents(String, String)} 按 ownerTaskId 过滤——后台 loop 传自身 taskId 只取本
 *       任务事件；前台 loop 传 null 全量取（本会话全事件归属，语义对齐 CC 单会话全量 drain）</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SdkEventQueue {

    private static final Logger log = LoggerFactory.getLogger(SdkEventQueue.class);

    /** CC MAX_QUEUE_SIZE = 1000（sdkEventQueue.ts:74） */
    public static final int MAX_QUEUE_SIZE = 1000;

    /** 队列（进程级单例）· 对齐 CC module 级 {@code queue: SdkEvent[]}（sdkEventQueue.ts:75） */
    private final List<TaggedEvent> queue = new ArrayList<>();

    /**
     * 入队条目 · RK-w5-2 任务归属打标（WF5-03c）。
     *
     * <p>前台主 loop 与后台派生 loop（startBackgroundSession）并发共享本进程级单例队列，
     * 若不做归属隔离，任一 loop 全量 drain 会把其他 loop/他会话任务事件盖成本 loop session_id
     * （跨会话错标，前端按 session_id 过滤即漏本会话任务）。故入队时从事件自身
     * {@code task_id} 提取 {@code ownerTaskId}；{@code session_state_changed} 无 task 归属 → null。
     */
    private record TaggedEvent(String ownerTaskId, SdkEvent event) {
    }

    /** 从事件自身提取归属任务 id（CC 事件均带 {@code task_id}；session_state_changed 无 → null） */
    private static String ownerTaskIdOf(SdkEvent e) {
        if (e instanceof TaskStartedEvent t) {
            return t.taskId();
        }
        if (e instanceof TaskProgressEvent t) {
            return t.taskId();
        }
        if (e instanceof TaskNotificationEvent t) {
            return t.taskId();
        }
        return null;
    }

    /** 非交互会话 gate · 对齐 CC {@code !STATE.isInteractive}（默认 true = 非交互 = 放行） */
    private volatile boolean nonInteractiveSession = true;

    // ────────────────────────────────────────────────────────────────────
    // 4 类事件 record（CC sdkEventQueue.ts:6-72，type:'system'）
    // ────────────────────────────────────────────────────────────────────

    /** CC SdkEvent 联合类型 · 只序列化出站，无需 @JsonTypeInfo */
    public sealed interface SdkEvent permits TaskStartedEvent, TaskProgressEvent,
            TaskNotificationEvent, SessionStateChangedEvent {
        /** 事件子类型（task_started/task_progress/...），供日志区分 */
        String subtype();
    }

    /** CC TaskStartedEvent（sdkEventQueue.ts:6-15）· framework.ts registerTask :104-116 发射 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStartedEvent(
            @JsonProperty("type") String type,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("description") String description,
            @JsonProperty("task_type") String taskType,
            @JsonProperty("workflow_name") String workflowName,
            @JsonProperty("prompt") String prompt
    ) implements SdkEvent {
        /** 便利构造器：type/subtype 固定 */
        public TaskStartedEvent(String taskId, String toolUseId, String description,
                String taskType, String workflowName, String prompt) {
            this("system", "task_started", taskId, toolUseId, description,
                    taskType, workflowName, prompt);
        }
    }

    /** CC TaskProgressEvent（sdkEventQueue.ts:17-34）· sdkProgress.ts:10-36 发射 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskProgressEvent(
            @JsonProperty("type") String type,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("description") String description,
            @JsonProperty("usage") TaskUsage usage,
            @JsonProperty("last_tool_name") String lastToolName,
            @JsonProperty("summary") String summary,
            @JsonProperty("workflow_progress") List<JsonNode> workflowProgress
    ) implements SdkEvent {
        /** 便利构造器：type/subtype 固定 */
        public TaskProgressEvent(String taskId, String toolUseId, String description,
                TaskUsage usage, String lastToolName, String summary, List<JsonNode> workflowProgress) {
            this("system", "task_progress", taskId, toolUseId, description,
                    usage, lastToolName, summary, workflowProgress);
        }
    }

    /** CC TaskNotificationSdkEvent（sdkEventQueue.ts:41-54）· emitTaskTerminatedSdk :114-134 发射 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskNotificationEvent(
            @JsonProperty("type") String type,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("status") String status,
            @JsonProperty("output_file") String outputFile,
            @JsonProperty("summary") String summary,
            @JsonProperty("usage") TaskUsage usage
    ) implements SdkEvent {
        /** 便利构造器：type/subtype 固定 */
        public TaskNotificationEvent(String taskId, String toolUseId, String status,
                String outputFile, String summary, TaskUsage usage) {
            this("system", "task_notification", taskId, toolUseId, status,
                    outputFile, summary, usage);
        }
    }

    /** CC SessionStateChangedEvent（sdkEventQueue.ts:62-66）· sessionState.ts:128 发射 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionStateChangedEvent(
            @JsonProperty("type") String type,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("state") String state
    ) implements SdkEvent {
        /** 便利构造器：type/subtype 固定 */
        public SessionStateChangedEvent(String state) {
            this("system", "session_state_changed", state);
        }
    }

    /** CC TaskProgressEvent.usage / TaskNotificationSdkEvent.usage 内部对象 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskUsage(
            @JsonProperty("total_tokens") int totalTokens,
            @JsonProperty("tool_uses") int toolUses,
            @JsonProperty("duration_ms") long durationMs
    ) {
    }

    /** drain 产物 · 对齐 CC {@code SdkEvent & {uuid, session_id}}（sdkEventQueue.ts:89-91） */
    public record DrainedSdkEvent(String uuid, String sessionId, SdkEvent event) {
    }

    /** emitTaskTerminatedSdk 选项 · 对齐 CC opts（sdkEventQueue.ts:117-122） */
    public record TaskTerminatedOpts(String toolUseId, String summary, String outputFile, TaskUsage usage) {
        /** output_file/summary 缺省 ''（CC :130-131 opts?.outputFile ?? ''） */
        public TaskTerminatedOpts {
            outputFile = outputFile != null ? outputFile : "";
            summary = summary != null ? summary : "";
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 队列操作
    // ────────────────────────────────────────────────────────────────────

    /**
     * 入队 SDK 事件 — 对齐 CC enqueueSdkEvent（sdkEventQueue.ts:77-87）。
     *
     * <p>gate：非交互会话才入队（CC 注释：SDK 事件仅 headless/streaming 消费，
     * TUI 模式会堆积到 cap 永不被读）。Java 默认非交互恒放行（对齐 CC 实际源码
     * 无 setIsInteractive 调用）。
     *
     * @param event 4 类事件之一
     */
    public void enqueueSdkEvent(SdkEvent event) {
        if (event == null) {
            return;
        }
        if (!nonInteractiveSession) {
            if (log.isDebugEnabled()) {
                log.debug("SdkEventQueue.enqueueSdkEvent: 交互会话 gate 拦截 subtype={}", event.subtype());
            }
            return;
        }
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE_SIZE) {
                queue.remove(0); // CC :83-85 queue.shift()
            }
            queue.add(new TaggedEvent(ownerTaskIdOf(event), event));
        }
    }

    /**
     * 全量取出 + 补 uuid/session_id — 对齐 CC drainSdkEvents（sdkEventQueue.ts:89-101）。
     *
     * <p>CC splice(0) 全取后逐条补 {@code uuid: randomUUID()} + {@code session_id:
     * getSessionId()}。Java 多会话：session_id 由当前 drain 会话传入（CC 单会话取全局）。
     *
     * <p><b>前台主 loop 使用</b>：传 null（ownerTaskId）全量取，对齐 CC 单会话全量 drain 语义
     * ——本会话全事件归属本会话，不被后台 loop 抢走。
     *
     * @param sessionId 当前 drain 会话 id（可为 null → 不补 session_id 字段）
     * @return 空时返回空列表（对齐 CC :92-94 提前返回）
     */
    public List<DrainedSdkEvent> drainSdkEvents(String sessionId) {
        return drainSdkEvents(sessionId, null);
    }

    /**
     * 按任务归属过滤取出 + 补 uuid/session_id — RK-w5-2 归属坑修复（WF5-03c）。
     *
     * <p><b>后台派生 loop 使用</b>：传自身 taskId，只取出归属本任务的事件（task_started /
     * task_progress / task_notification），其余（前台任务 / 他会话任务）留队不误吞。
     * 前台主 loop 传 null 全量取（见 {@link #drainSdkEvents(String)}）。
     *
     * <p>对齐 CC：CC 单进程单会话无归属需求（sdkEventQueue.ts:89-101 全取）；Java 前台/后台
     * 两 loop 并发共享进程级单例队列，必须按 ownerTaskId 隔离，否则后台 loop 会把前台/他会话
     * 任务事件盖成后台 session_id（跨会话错标，前端按 session_id 过滤即漏本会话任务）。
     *
     * @param sessionId   当前 drain 会话 id（可为 null → 不补 session_id 字段）
     * @param ownerTaskId 只取出归属该任务的事件；null = 全量取
     * @return 空时返回空列表（对齐 CC :92-94 提前返回）
     */
    public List<DrainedSdkEvent> drainSdkEvents(String sessionId, String ownerTaskId) {
        List<SdkEvent> snapshot;
        synchronized (queue) {
            if (queue.isEmpty()) {
                return List.of();
            }
            if (ownerTaskId == null) {
                // 前台主 loop：全量取（CC 单会话全量 drain 语义）
                snapshot = queue.stream().map(TaggedEvent::event).collect(java.util.stream.Collectors.toList());
                queue.clear();
            } else {
                // 后台 loop：按任务归属过滤，其余留队
                snapshot = new ArrayList<>();
                List<TaggedEvent> kept = new ArrayList<>();
                for (TaggedEvent te : queue) {
                    if (ownerTaskId.equals(te.ownerTaskId())) {
                        snapshot.add(te.event());
                    } else {
                        kept.add(te);
                    }
                }
                queue.clear();
                queue.addAll(kept);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("SdkEventQueue.drainSdkEvents: 取出 {} 条 SDK 事件 (sessionId={}, ownerTaskId={})",
                snapshot.size(), sessionId, ownerTaskId);
        }
        return snapshot.stream()
                .map(e -> new DrainedSdkEvent(UUID.randomUUID().toString(), sessionId, e))
                .toList();
    }

    /**
     * 终态 bookend 事件 — 对齐 CC emitTaskTerminatedSdk（sdkEventQueue.ts:114-134）。
     *
     * <p>registerTask 已发 task_started，本方法是闭合 bookend。CC 注释明言：XML
     * task-notification 解析路径与直接发射二选一（双发）；Java 无 print.ts XML→SDK 解析，
     * XML 通知仅供模型（通知队列 drain），SDK task_notification 必须直接发射供前端消费。
     *
     * @param taskId 任务 id
     * @param status 终态 'completed' | 'failed' | 'stopped'
     * @param opts   选项（toolUseId/summary/outputFile/usage，均可空）
     */
    public void emitTaskTerminatedSdk(String taskId, String status, TaskTerminatedOpts opts) {
        String toolUseId = opts != null ? opts.toolUseId() : null;
        String outputFile = opts != null ? opts.outputFile() : "";
        String summary = opts != null ? opts.summary() : "";
        TaskUsage usage = opts != null ? opts.usage() : null;
        enqueueSdkEvent(new TaskNotificationEvent(taskId, toolUseId, status, outputFile, summary, usage));
        if (log.isDebugEnabled()) {
            log.debug("SdkEventQueue.emitTaskTerminatedSdk: taskId={}, status={}, summaryLen={}",
                    taskId, status, summary != null ? summary.length() : 0);
        }
    }

    /**
     * 进度事件 — 对齐 CC sdkProgress.ts emitTaskProgress（:10-36）。
     *
     * <p>usage.duration_ms = Date.now() - startTime（CC :30）；totalTokens/toolUses 由调用方
     * 从自身状态推导（CC 注释：accepts already-computed primitives）。
     *
     * @param taskId      任务 id
     * @param toolUseId   关联 tool_use id（可空）
     * @param description 任务描述
     * @param startTime   CC TaskStateBase.startTime（用于 duration_ms）
     * @param totalTokens 累计 token 数
     * @param toolUses    累计工具调用数
     * @param lastToolName 最近工具名（可空）
     * @param summary     进度摘要（可空）
     */
    public void emitTaskProgress(String taskId, String toolUseId, String description,
            long startTime, int totalTokens, int toolUses, String lastToolName, String summary) {
        long durationMs = System.currentTimeMillis() - startTime;
        enqueueSdkEvent(new TaskProgressEvent(taskId, toolUseId, description,
                new TaskUsage(totalTokens, toolUses, durationMs), lastToolName, summary, null));
    }

    /** 当前队列深度（测试/观测用） */
    public int size() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** 清空队列（测试用） */
    public void clear() {
        synchronized (queue) {
            queue.clear();
        }
    }

    /** 非交互会话标记（对齐 CC getIsNonInteractiveSession，默认 true） */
    public boolean isNonInteractiveSession() {
        return nonInteractiveSession;
    }

    /** 设置交互会话标记（CC 无生产调用方；保留供 TUI 等价模式接线/测试） */
    public void setNonInteractiveSession(boolean nonInteractiveSession) {
        this.nonInteractiveSession = nonInteractiveSession;
    }

    // ────────────────────────────────────────────────────────────────────
    // 出站序列化（扁平化：uuid/session_id 与事件字段平级，对齐 CC drain 产物 JSON）
    // ────────────────────────────────────────────────────────────────────

    /**
     * 将 drain 产物展平为扁平 JSON 节点列表 — 出站专用。
     *
     * <p>CC 在 print.ts 把 {@code SdkEvent & {uuid, session_id}} 整体写入输出流：
     * uuid/session_id 与 type/subtype/task_id 等事件字段<b>平级</b>（sdkEventQueue.ts:96-100
     * spread 展开），非嵌套。Java 侧在此显式展开：valueToTree(event) 后 put uuid/session_id。
     *
     * <p>返回 {@code List<JsonNode>} 而非 JSON 文本：STOMP SimpMessagingTemplate 经 Jackson
     * 消息转换器把对象转 JSON（String 负载会被当普通文本/二次转义，故必须传对象）。
     *
     * @param drained drainSdkEvents 产物
     * @param mapper  序列化用 ObjectMapper（record 自带 @JsonInclude NON_NULL）
     * @return 扁平事件节点列表（空 → 空列表）
     */
    public static List<JsonNode> toFlatJsonNodes(List<DrainedSdkEvent> drained, ObjectMapper mapper) {
        if (drained == null || drained.isEmpty()) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>(drained.size());
        for (DrainedSdkEvent d : drained) {
            ObjectNode node = mapper.valueToTree(d.event());
            node.put("uuid", d.uuid());
            if (d.sessionId() != null) {
                node.put("session_id", d.sessionId());
            }
            nodes.add(node);
        }
        return nodes;
    }
}
