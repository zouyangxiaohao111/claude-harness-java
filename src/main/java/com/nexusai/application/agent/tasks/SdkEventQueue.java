package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.common.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>多会话适配（C2 根修 · 队列换 per-session 桶）</b>：CC 里 {@code queue} 是<b>模块级数组</b>
 * （sdkEventQueue.ts:75）、{@code enqueueSdkEvent} 收事件<b>不收会话</b>（:77/:86）、
 * {@code drainSdkEvents} <b>零参数</b>全取（:89/:95），出站 {@code session_id} 来自模块级 STATE
 * 单例（:99 → bootstrap/state.ts:429-431）。CC 之所以永远不会错乱归属，只因 <b>进程 = 会话</b>——
 * 「队列属于会话」是单会话假设<b>免费送</b>的不变量。本仓多会话共用一进程，该不变量不存在：
 * 进程级单 List 下「谁先 drain，队列里的东西就被谁取走并盖上它的会话键」= 子代理事件错乱归属到
 * 别的会话（用户症状）。
 *
 * <p>根修 = 把该不变量<b>显式化</b>（抄结构，不抄行为）：
 * <ul>
 *   <li>{@link #enqueueSdkEvent(String, SdkEvent)} <b>入队即定死归属</b>（会话键非空由上游保证）</li>
 *   <li>每个会话一个<b>桶</b>；桶内 FIFO；{@code MAX_QUEUE_SIZE} 变<b>每桶</b>上限
 *       （CC 的 1000 是「每队列」，而 CC 进程=会话 ⇒ 每队列 ≡ 每会话，语义一致）</li>
 *   <li>{@link #drainSdkEvents(String)} 只取出<b>本会话桶</b>并移除（镜像 CC :95 {@code queue.splice(0)}）</li>
 *   <li>桶回收 = 「会话非运行态 + 超宽限 ⇒ 丢弃该桶 + log.warn」——CC 里队列随<b>进程</b>消亡，本仓
 *       无进程退出，故以「会话不再运行」为等价触发点（见 {@link #reclaimStaleBucketsLocked()}）</li>
 * </ul>
 *
 * <p><b>⛔ 已删除的旧机制</b>：ownerTaskId 任务归属打标 + 双参 {@code drainSdkEvents(sessionId,
 * ownerTaskId)} 按任务过滤（RK-w5-2/WF5-03c）。那是「队列非会话桶」假设下的补丁，只覆盖了
 * {@code setTaskStreamContext} 一条 taskId 来源，而子代理 loop / fork / hook / teammate 全走全量取分支
 * （日志量级：6848 条 drain 里 3823 条来自 async-subagent 线程）⇒ 补丁面远小于漏面。C2 以会话桶
 * 从结构上取代之，任务维度整块删除。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SdkEventQueue {

    private static final Logger log = LoggerFactory.getLogger(SdkEventQueue.class);

    /** CC MAX_QUEUE_SIZE = 1000（sdkEventQueue.ts:74）· C2 起语义 = <b>每会话桶</b>上限 */
    public static final int MAX_QUEUE_SIZE = 1000;

    /**
     * 桶宽限期（ms）· 会话处于<b>非运行态</b>且桶超过此时长未被触碰 ⇒ 回收该桶。
     *
     * <p><b>取值理由</b>：本仓无「进程退出」事件，等价触发点是「会话不再运行」；但会话在两个 turn
     * 之间本就是非运行态，而桶里的任务事件正是留给<b>下一个 turn 顶部 drain</b> 的（LlmAgentLoop
     * 每 turn drain 一次）。故宽限必须显著大于「一个 turn + 用户思考间隔」，取 30 分钟：
     * 足够兜住任何正常交互节奏，同时把「永久残留」变成有界残留。即便真被回收，任务卡片的持久来源
     * 是 REST {@code /tasks}（TaskController 读统一 store，按 sessionId 过滤），丢的只是瞬态 SDK 书签。
     */
    public static final long BUCKET_GRACE_MS = 30 * 60 * 1000L;

    /** 桶内队列锁 · bySession 结构 + 每个 Bucket 内容的<b>全部</b>可变访问都在此锁内。 */
    private final Object lock = new Object();

    /**
     * 每会话桶 · CC :75 是模块级单数组（CC 进程=会话 ⇒ 天然单桶）。
     * 本仓多会话共进程 ⇒ 一个会话一个桶，桶内 FIFO。
     */
    private final Map<String, Bucket> bySession = new LinkedHashMap<>();

    /**
     * 会话活跃探针（判「非运行态」）· 复用本仓既有判据
     * {@link LlmAgentLoop#isSessionRunning(String)}（run() 入口 markRunning / finally markIdle 计数），
     * ⛔ 不另造判据。测试可替换（见 {@link #setSessionActiveProbe}）。
     */
    private volatile java.util.function.Predicate<String> sessionActiveProbe = LlmAgentLoop::isSessionRunning;

    /** 宽限期（可测替换 · 默认 {@link #BUCKET_GRACE_MS}） */
    private volatile long bucketGraceMs = BUCKET_GRACE_MS;

    /**
     * 单会话桶 · 桶内 FIFO + 最后触碰时间（回收判据）。
     *
     * <p>{@code lastTouchedMs} 在入队时刷新（桶里是否有事件、事件多老，决定回收时 log 的丢弃条数）。
     */
    private static final class Bucket {
        private final List<SdkEvent> events = new ArrayList<>();
        private long lastTouchedMs = System.currentTimeMillis();
    }

    /** 非交互会话 gate · 对齐 CC {@code !STATE.isInteractive}（默认 true = 非交互 = 放行） */
    private volatile boolean nonInteractiveSession = true;

    /**
     * 投递器钩子（可空）· <b>[单通道出站 · 入队即投递]</b> —— 队列的唯一出口。
     *
     * <p><b>WHY（本批为何要这一钩子）</b>：本仓原先有<b>两条</b>任务终态通道 —— ①队列桶（等该会话
     * 自己的 turn 顶部 {@code drainSdkEvents} 取）与 ②{@code BackgroundTaskRunner} 里一段直推。
     * 会话正在跑 turn 时<b>两条都到</b>（同一终态推两次 → 前端 toast 弹两次，toast 不幂等）；
     * 会话空闲时只有直推一条能到（桶里那条要等 30 分钟宽限被回收）。两条通道**互补又重叠**，
     * 结构上无法靠删掉一条来收敛。
     *
     * <p>根修 = 抄 CC 2.1.281 的「取一次、同一批喂出口」<b>结构</b>（⛔ 不是抄意图）：
     * CC 的 {@code Zp.enqueue()} 末尾就调 {@code this.enqueueListener?.()}（exe 偏移 200265752），
     * 该监听器由 {@code Cf({drain:Bo,emit})} 注册（偏移 220377666 / 安装点 220593897），
     * 而 {@code Bo} 首行 {@code let h=rKe()} 内部就是 {@code splice(0)} 破坏性取（偏移 220592956）。
     * 即 <b>CC 的 drain 本来就是入队驱动、CC 的队列本来就是按 key 分桶</b>（{@code queuesByKey}，
     * 另有 {@code drainForSession}）—— 本仓的「入队即投递」正是 CC 的结构，不是自创适配。
     *
     * <p><b>语义</b>：非 null 时，{@link #enqueueSdkEvent} 在锁内<b>取走该会话整桶</b>（破坏性，
     * 镜像 CC {@code splice(0)}），锁外交给本钩子出站；桶已被取空 ⇒ 任何**后续**的取
     * （{@code LlmAgentLoop} turn 顶部 drain）只会拿到空 ⇒ <b>正常路径下不存在双投</b>。
     *
     * <p>⚠️ <b>「不可能重复」是过头断言，这里显式收回</b>：本类 {@link #deliverNow} 自己登记的
     * <b>残余窗口</b>（出站已写出之后投递器才抛异常 ⇒ 回灌 ⇒ 该批被下一次入队或下一个 turn 顶部
     * drain 再取一次）是<b>结构上真实存在</b>的重复路径。准确表述 =
     * 「正常路径只有一条取走路径；唯一例外是 requeue-after-send，它以『罕见重复』换『投递器一挂
     * 不静默丢』」。⛔ 后来的读者<b>不要</b>拿这句话当保证去做删除或简化。
     *
     * <p><b>默认 null = 纯队列语义</b>（只入桶、不取桶）⇒ 所有 {@code new SdkEventQueue()} 的
     * 测试直构天然走老路径（40+ 处测试零改动），投递行为只在 Spring 装配侧开启。
     *
     * <p>⛔ 本体<b>不引 Spring</b>（只引 JDK 的 {@code Consumer}）—— 出站实现（{@code SimpMessagingTemplate}）
     * 由装配侧 {@code TaskConfiguration.backgroundTaskRunner} 以 lambda 注入。
     */
    @JsonIgnore
    private volatile java.util.function.Consumer<List<DrainedSdkEvent>> deliverer;

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
     * 入队 SDK 事件（<b>入队即定死会话归属</b>）— CC 形态 {@code enqueueSdkEvent(event)}
     * （sdkEventQueue.ts:77-87）在本仓的显式化：CC 靠「进程=会话」免费获得归属，本仓必须显式传键。
     *
     * <p>gate：非交互会话才入队（CC 注释：SDK 事件仅 headless/streaming 消费，
     * TUI 模式会堆积到 cap 永不被读）。Java 默认非交互恒放行（对齐 CC 实际源码
     * 无 setIsInteractive 调用）。
     *
     * <p><b>非真会话键 fail-loud（≥WARN + 丢弃）</b>：上游必须保证键是<b>真会话</b>。三分判据
     * （null / 空白串 / {@link SessionKeys#NO_SESSION} 哨兵）与 {@code WebSocketPermissionPrompter}
     * 侧同口径 —— ①null 出站会落 {@code "session_id": null}、②空白串同效，前端
     * {@code evt.session_id ?? sessionIdRef.current} 会把它们<b>错标到当前打开的会话</b>（比丢弃更坏）；
     * ③哨兵（「确无会话」标记）没有真会话的 loop 会以该键 drain，入队只能滞留到宽限回收
     * （结构性不可消费）。故此处拦下并留痕，⛔ 不伪造会话（不塞 {@code no-session} 哨兵、不用 null
     * 当键、不回落当前会话）。
     *
     * <p><b>[单通道出站 · 入队即投递]</b>：{@link #deliverer} 非 null 时，本方法在锁内<b>取走该会话
     * 整桶并移除</b>，锁外交投递器出站。这样「投递时机」从「等该会话自己的 loop 来 drain」前移到
     * 入队点 ⇒ <b>一条通道同时覆盖活跃会话与空闲会话</b>，且同一批事件只被取走一次 ⇒ 物理上不可能重复。
     * 兜底仍在（{@link #drainSdkEvents(String)}，钩子缺位/投递失败回灌时才有货）。
     *
     * @param sessionId 归属会话 id（<b>真会话键</b> · 非 null/空白/哨兵，由上游显式透传）
     * @param event     4 类事件之一
     */
    public void enqueueSdkEvent(String sessionId, SdkEvent event) {
        if (event == null) {
            return;
        }
        if (!nonInteractiveSession) {
            if (log.isDebugEnabled()) {
                log.debug("SdkEventQueue.enqueueSdkEvent: 交互会话 gate 拦截 subtype={}", event.subtype());
            }
            return;
        }
        // [C2 收口 · 单点会话键三分判据] 与 WebSocketPermissionPrompter:807-808 的
        //   null / blank / isNoSession 三分支**同口径对齐**（该处已正确，⛔ 不动它）：
        //   ① null ② 空白串 ③ SessionKeys.NO_SESSION 哨兵（「确无会话」标记，形态上明确不是会话键）。
        //   三者一律 fail-loud 丢弃（⛔ 不静默、⛔ 不伪造会话、⛔ 不回落当前会话）。
        //   哨兵为何也拦：它没有**真会话**的 loop 会以该键 drain（loop 的 drain 键 = state.sessionId()，
        //   只有确无会话的 cron headless run 才以该键起轮），事件只能滞留到 30min 宽限回收；且 C2 出站
        //   契约（toFlatJsonNodes 的 session_id 恒非空）与前端 `evt.session_id ?? 当前会话` 都建立在
        //   「键是真会话」之上。日志区分三者原因，便于排查是「漏传」还是「确无会话」。
        if (sessionId == null) {
            log.warn("SdkEventQueue.enqueueSdkEvent: 会话键为 null → 丢弃 subtype={}（C2：入队即定死归属，"
                + "null 出站会被前端 `session_id ?? 当前会话` 错标；⛔ 不伪造会话、⛔ 不静默）",
                event.subtype());
            return;
        }
        if (sessionId.isBlank()) {
            log.warn("SdkEventQueue.enqueueSdkEvent: 会话键为空白串（长度={}）→ 丢弃 subtype={}（C2：与 null "
                + "同口径，空键会被前端错标到当前会话；⛔ 不伪造会话、⛔ 不静默）",
                sessionId.length(), event.subtype());
            return;
        }
        if (SessionKeys.isNoSession(sessionId)) {
            log.warn("SdkEventQueue.enqueueSdkEvent: 会话键是「确无会话」哨兵 {} → 丢弃 subtype={}"
                + "（C2 收口：判据与 WebSocketPermissionPrompter 三分支对齐 —— 哨兵不是会话键，"
                + "没有真会话的 loop 会以该键 drain，事件只会滞留到宽限回收；⛔ 不伪造会话、⛔ 不静默）",
                SessionKeys.NO_SESSION, event.subtype());
            return;
        }
        // [入队即投递] 取走动作**必须在锁内**完成（与 drainSdkEvents 的「取出即移除」互斥 ——
        //   两边都是破坏性取，同一批事件只可能被一方取到）；投递（IO）**必须在锁外**。
        //   钩子为 null ⇒ 一个字都不取，维持既有「留在桶里等 drain」语义（纯队列 · 测试直构路径）。
        List<SdkEvent> taken = null;
        synchronized (lock) {
            Bucket bucket = bySession.computeIfAbsent(sessionId, k -> new Bucket());
            if (bucket.events.size() >= MAX_QUEUE_SIZE) {
                bucket.events.remove(0); // CC :83-85 queue.shift()（C2：上限落到每会话桶）
            }
            bucket.events.add(event);
            bucket.lastTouchedMs = System.currentTimeMillis();
            if (this.deliverer != null) {
                // 取走**整桶**（不只本条）：桶里可能还有交付失败回灌的旧事件 —— 一并投出，避免滞留。
                // remove 而非留空桶：镜像 drainSdkEvents 的 remove 语义（⛔ 不留空壳桶泄漏）。
                taken = new ArrayList<>(bucket.events);
                bySession.remove(sessionId);
            }
        }
        // 数据流日志（C2）：会话键 + 事件子类型成对打出，跨会话串桩时可直接比对。
        log.info("SdkEventQueue.enqueueSdkEvent: sessionId={} subtype={}", sessionId, event.subtype());
        if (taken != null) {
            deliverNow(sessionId, taken);
        }
    }

    /**
     * 把「入队时取走的整桶」交给投递器（⛔ 锁外调用 —— 不持锁做 IO）。
     *
     * <p><b>失败回灌（⭐ 没有它就是「两条要求互相抵消」的空操作）</b>：投递器抛异常时必须把整批
     * <b>按原序</b>回灌到桶头 —— 否则「drain 退化为兜底」这条要求会被「破坏性取走」抵消成
     * 「投递器一挂，事件就凭空消失」。回灌后由 {@code LlmAgentLoop} turn 顶部 drain 或下一次
     * 入队（会再取一次整桶）接手。
     *
     * <p>钩子为 null（取走后、投递前被摘除）同样回灌 —— 与「不取桶」的语义对齐。
     *
     * <p>⚠️ <b>残余窗口（已知，接受）</b>：若投递器在 {@code convertAndSend} <b>真正发出之后</b>才抛
     * （例如 broker 侧异常），回灌会让同一批在下一个 turn 顶部再推一次 ⇒ 那一帧重复。
     * 本仓选择「宁可罕见重复，不可静默丢失」——丢终态会让任务卡片永久停在「进行中」
     * （正是本批要修的症状），而重复只是多一次 toast。窗口 = 「出站已写 + 紧接抛异常」，非零但极窄。
     *
     * @param sessionId 归属会话（真会话键，入队处已 fail-loud 保证）
     * @param taken     锁内取走的整桶快照（原序）
     */
    private void deliverNow(String sessionId, List<SdkEvent> taken) {
        java.util.function.Consumer<List<DrainedSdkEvent>> d = this.deliverer;
        if (d == null) {
            requeueAtHead(sessionId, taken);
            log.warn("SdkEventQueue.deliverNow: 投递器在取桶后被摘除 → 回灌桶头 sessionId={} 条数={}"
                + "（⛔ 不静默丢弃，交兜底 drain 接手）", sessionId, taken.size());
            return;
        }
        List<DrainedSdkEvent> drained = taken.stream()
                .map(e -> new DrainedSdkEvent(UUID.randomUUID().toString(), sessionId, e))
                .toList();
        try {
            d.accept(drained);
            // 数据流日志：会话键 + 条数 + 出口（单通道出站，跨会话串桩/双投排查都靠它）。
            log.info("SdkEventQueue.deliverNow: 入队即投递 sessionId={} 条数={} → 投递器"
                + "（单通道出站 · CC 对照 Cf/Tke 入队监听器 exe:220377666/220593897）",
                sessionId, drained.size());
        } catch (Exception e) {
            requeueAtHead(sessionId, taken);
            log.warn("SdkEventQueue.deliverNow: 投递器抛异常 → 回灌桶头待兜底 drain sessionId={} 条数={}"
                + " 原因={}（兜底 = LlmAgentLoop turn 顶部 drainSdkEvents；⛔ 不静默丢弃）",
                sessionId, taken.size(), e.getMessage());
        }
    }

    /** 把取走的整批按原序回灌到该会话桶头（投递失败的兜底 · 调用方不持锁）。 */
    private void requeueAtHead(String sessionId, List<SdkEvent> taken) {
        synchronized (lock) {
            Bucket bucket = bySession.computeIfAbsent(sessionId, k -> new Bucket());
            bucket.events.addAll(0, taken);
            bucket.lastTouchedMs = System.currentTimeMillis();
        }
    }

    /**
     * 取出<b>本会话桶</b>并移除 + 补 uuid/session_id — 对齐 CC drainSdkEvents
     * （sdkEventQueue.ts:89-101）。
     *
     * <p><b>[单通道出站 · 本方法已从「主通道」降级为「兜底」]</b>：{@link #deliverer} 装上后，
     * 事件在<b>入队点</b>就被取走并投出（{@link #enqueueSdkEvent}），桶<b>通常已是空的</b> ⇒ 本方法
     * 通常取到空 ⇒ no-op（幂等）。它仍然必须保留，因为它是两种情况下的**唯一消费点**：
     * <ol>
     *   <li>投递器缺位（非 Spring 单测直构 / {@code wsTemplate} 为 null 的装配）——纯队列语义；</li>
     *   <li>投递失败被 {@link #deliverNow} <b>回灌</b>到桶头（此时桶里有货，下一个 turn 顶部推出）。</li>
     * </ol>
     * ⛔ 删掉它 = 兜底真的会死（回灌的事件再也没有消费者）。
     *
     * <p>CC {@code queue.splice(0)}（:95）全取后逐条补 {@code uuid: randomUUID()} +
     * {@code session_id: getSessionId()}（:99，值取模块级 STATE 单例）。本仓把「取哪个队列」
     * 显式化为「取哪个会话的桶」——{@link #bySession} 取出并 remove，语义等价于 CC 的
     * 「把整个队列取空」：<b>别人的桶一个字节都不碰</b>（这正是 C2 关掉「错乱归属」的结构点）。
     *
     * <p>取出后顺带 {@link #reclaimStaleBucketsLocked()} 回收非运行态会话的空闲桶
     * （最小侵入：drain 是队列被活跃会话维护的唯一自然时机，等价 CC 进程退出时的队列消亡）。
     * ⚠️ 注意：投递前移到入队点后，桶的「新建 → 立刻取空」使活跃会话<b>不再留桶</b>，
     * 回收点仍必须在（钩子为 null 的纯队列语义下它是唯一回收点）。
     *
     * @param sessionId 当前 drain 会话 id（非空；空键 ⇒ 空结果，⛔ 不回落全量取）
     * @return 空时返回空列表（对齐 CC :92-94 提前返回）
     */
    public List<DrainedSdkEvent> drainSdkEvents(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("SdkEventQueue.drainSdkEvents: 会话键为空 → 无可取桶（⛔ 不回落全量取："
                + "那正是 C2 之前的跨会话互吞来源）");
            return List.of();
        }
        List<SdkEvent> snapshot;
        synchronized (lock) {
            Bucket bucket = bySession.remove(sessionId);
            snapshot = (bucket != null) ? bucket.events : List.of();
            reclaimStaleBucketsLocked();
        }
        if (log.isDebugEnabled()) {
            log.debug("SdkEventQueue.drainSdkEvents: 取出 {} 条 SDK 事件 (sessionId={})",
                snapshot.size(), sessionId);
        }
        return snapshot.stream()
                .map(e -> new DrainedSdkEvent(UUID.randomUUID().toString(), sessionId, e))
                .toList();
    }

    /**
     * 回收「非运行态 + 超宽限」会话的桶（C2 · 照 CC 翻译）。
     *
     * <p>CC 的等价物 = <b>队列随进程消亡</b>：CC 里 queue 是模块级数组、进程=会话，会话结束即进程
     * 结束 ⇒ 队列连同未取事件一起消失。本仓常驻 JVM 无进程退出 ⇒ 等价触发点 = 「会话不在运行」
     * （判据复用 {@link LlmAgentLoop#isSessionRunning(String)}），并加宽限 {@link #bucketGraceMs}
     * 容忍「turn 之间本就非运行态」的正常窗口（桶里的任务事件是留给下一个 turn 顶部 drain 的）。
     *
     * <p>调用点 = {@link #drainSdkEvents(String)}（见该处理由）。<b>⛔ 绝不静默丢弃</b>：
     * 桶里有事件时必 {@code log.warn}（打 sessionKey + 丢弃条数）；空桶只 debug。
     *
     * <p>调用方必须已持 {@link #lock}。
     */
    private void reclaimStaleBucketsLocked() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, Bucket>> it = bySession.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> entry = it.next();
            String sessionKey = entry.getKey();
            Bucket bucket = entry.getValue();
            if (sessionActiveProbe.test(sessionKey)) {
                continue; // 运行中会话的桶：绝不回收（可能正在累积给本次 turn 尾部 drain 的事件）
            }
            if (now - bucket.lastTouchedMs < bucketGraceMs) {
                continue; // 宽限内：可能只是 turn 间隙，留给下一个 turn 顶部 drain
            }
            it.remove();
            if (bucket.events.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("SdkEventQueue: 回收非运行态会话的空桶 sessionKey={}（无事件丢弃）", sessionKey);
                }
            } else {
                log.warn("SdkEventQueue: 回收非运行态会话的桶 sessionKey={} 丢弃 {} 条 SDK 事件"
                    + "（会话非运行态 + 超宽限 {}ms ⇒ 对齐 CC「队列随进程/会话消亡」；⛔ 不静默）",
                    sessionKey, bucket.events.size(), bucketGraceMs);
            }
        }
    }

    /**
     * 终态 bookend 事件 — 对齐 CC emitTaskTerminatedSdk（sdkEventQueue.ts:114-134）。
     *
     * <p>registerTask 已发 task_started，本方法是闭合 bookend。CC 注释明言：XML
     * task-notification 解析路径与直接发射二选一（双发）；Java 无 print.ts XML→SDK 解析，
     * XML 通知仅供模型（通知队列 drain），SDK task_notification 必须直接发射供前端消费。
     *
     * @param sessionId 归属会话 id（C2：入队归属键 · 由调用方显式给出，通常 = task.sessionId()）
     * @param taskId 任务 id
     * @param status 终态 'completed' | 'failed' | 'stopped'
     * @param opts   选项（toolUseId/summary/outputFile/usage，均可空）
     */
    public void emitTaskTerminatedSdk(String sessionId, String taskId, String status,
            TaskTerminatedOpts opts) {
        String toolUseId = opts != null ? opts.toolUseId() : null;
        String outputFile = opts != null ? opts.outputFile() : "";
        String summary = opts != null ? opts.summary() : "";
        TaskUsage usage = opts != null ? opts.usage() : null;
        enqueueSdkEvent(sessionId, new TaskNotificationEvent(taskId, toolUseId, status, outputFile, summary, usage));
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
     * @param sessionId   归属会话 id（C2：入队归属键 · 值来自 SubagentExecutor.maybeStartSummary
     *                    的 sessionId 形参，经 AgentProgressTracker 透传到 agent-summary 定时线程）
     * @param taskId      任务 id
     * @param toolUseId   关联 tool_use id（可空）
     * @param description 任务描述
     * @param startTime   CC TaskStateBase.startTime（用于 duration_ms）
     * @param totalTokens 累计 token 数
     * @param toolUses    累计工具调用数
     * @param lastToolName 最近工具名（可空）
     * @param summary     进度摘要（可空）
     */
    public void emitTaskProgress(String sessionId, String taskId, String toolUseId, String description,
            long startTime, int totalTokens, int toolUses, String lastToolName, String summary) {
        long durationMs = System.currentTimeMillis() - startTime;
        enqueueSdkEvent(sessionId, new TaskProgressEvent(taskId, toolUseId, description,
                new TaskUsage(totalTokens, toolUses, durationMs), lastToolName, summary, null));
    }

    /** 当前队列深度（全部会话桶合计 · 测试/观测用） */
    public int size() {
        synchronized (lock) {
            int total = 0;
            for (Bucket b : bySession.values()) {
                total += b.events.size();
            }
            return total;
        }
    }

    /** 清空全部会话桶（测试用） */
    public void clear() {
        synchronized (lock) {
            bySession.clear();
        }
    }

    /** 替换会话活跃探针（测试用 · 生产恒为 {@link LlmAgentLoop#isSessionRunning(String)}） */
    void setSessionActiveProbe(java.util.function.Predicate<String> probe) {
        this.sessionActiveProbe = probe;
    }

    /** 替换桶宽限期（测试用 · 生产恒为 {@link #BUCKET_GRACE_MS}） */
    void setBucketGraceMsForTest(long graceMs) {
        this.bucketGraceMs = graceMs;
    }

    /** 某会话桶当前事件数（测试/观测用；无该桶 → 0） */
    int sizeOf(String sessionId) {
        synchronized (lock) {
            Bucket b = bySession.get(sessionId);
            return b != null ? b.events.size() : 0;
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

    /**
     * 装配投递器（单一注入点 = {@code TaskConfiguration.backgroundTaskRunner}）。
     *
     * <p>传 null ⇒ 退回纯队列语义（事件留在桶里等 {@link #drainSdkEvents}）。
     * ⛔ 本类只收一个 {@code Consumer}（JDK 类型）—— 出站实现（STOMP / 日志 / 测试记录器）
     * 全由装配侧决定，队列本体不引 Spring。
     */
    public void setDeliverer(java.util.function.Consumer<List<DrainedSdkEvent>> deliverer) {
        this.deliverer = deliverer;
    }

    /** 当前投递器（测试/观测用 · 生产 = TaskConfiguration 装配的 STOMP 投递器，未装配为 null） */
    java.util.function.Consumer<List<DrainedSdkEvent>> deliverer() {
        return deliverer;
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
            // C2 · 出站 session_id 恒非空：会话键由 enqueue 侧保证（空键已在入队处 fail-loud 丢弃），
            //   故此处不再有「null 则省略字段」的分支（旧语义：省略 → 前端 `evt.session_id ??
            //   当前会话` 把事件错标到当前打开的会话）。仍可能为空的路径在此 fail-loud（≥WARN + 丢弃），
            //   ⛔ 绝不带空键出站。
            if (d.sessionId() == null || d.sessionId().isBlank()) {
                log.warn("SdkEventQueue.toFlatJsonNodes: 空会话键出站被拦截丢弃 subtype={}（C2 出站契约："
                    + "session_id 恒非空；上游 enqueue 已保证，此处为兜底 fail-loud）", d.event().subtype());
                continue;
            }
            ObjectNode node = mapper.valueToTree(d.event());
            node.put("uuid", d.uuid());
            node.put("session_id", d.sessionId());
            nodes.add(node);
        }
        return nodes;
    }
}
