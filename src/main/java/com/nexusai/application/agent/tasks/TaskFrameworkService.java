package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务框架服务 — 对齐 CC framework.ts: registerTask / updateTaskState / pollTasks /
 * generateTaskAttachments / applyTaskOffsetsAndEvictions / evictTerminalTask
 *
 * <p>s13 轻量版本: 内存存储 + TaskService 集成。
 *
 * <p><b>offset-evict 语义（OPD-TS-21）</b>：本类 {@code store} 对齐 CC {@code state.tasks}，
 * 是 generateTaskAttachments / applyTaskOffsetsAndEvictions 的操作对象：
 * <ul>
 *   <li>running 任务经 {@link #getTaskOutputDelta} 增量读 → offset 推进（只推进不通知，附件恒空）</li>
 *   <li>终态 && notified 任务 → evict（饿式 {@link #evictTerminalTask} + 惰性
 *       {@link #generateTaskAttachments} 双保险，对齐 CC framework.ts:123 "lazy GC as safety net"）</li>
 *   <li>应用 patch 时重查 fresh state（TOCTOU：delta 磁盘读期间任务可能已终态）</li>
 * </ul>
 */
public class TaskFrameworkService {

    private static final Logger log = LoggerFactory.getLogger(TaskFrameworkService.class);

    /**
     * getTaskOutputDelta 默认单次读取字节上限 · 对齐 CC DEFAULT_MAX_READ_BYTES
     * (Open-ClaudeCode/src/utils/task/diskOutput.ts:23 {@code 8 * 1024 * 1024 // 8MB})
     */
    public static final long DEFAULT_MAX_READ_BYTES = 8L * 1024L * 1024L;

    private final ConcurrentHashMap<String, BackgroundTask> store = new ConcurrentHashMap<>();

    /**
     * 主会话后台任务独立载体 store（OPD-TP-16：不污染基础 BackgroundTask）· 对齐 CC
     * {@code state.tasks} 中 LocalMainSessionTaskState 的存在（framework.ts:77-117 registerTask 的 map 写入）。
     *
     * <p>CC state.tasks 为单一异质 map（LocalMainSessionTaskState 直接入 map）；Java 因 record 类型系统，
     * 用 {@link BackgroundTask} 投影（本类 {@link #store}，框架层 TaskStop/offset-evict/完成读取走既有
     * BackgroundTask 通道）+ 本 {@code MainSessionTaskState} 全量（含 agentType/prompt/messages/progress）
     * 双写等价（RK-w5-1 已登记）。
     */
    private final ConcurrentHashMap<String, MainSessionTaskState> mainSessionStore = new ConcurrentHashMap<>();

    /** SDK 事件队列（可为 null —— 测试直构无 bean 时不发射 SDK 事件）· OPD-TS-22 task_started 通道 */
    private final SdkEventQueue sdkEventQueue;

    public TaskFrameworkService() {
        this(null);
    }

    public TaskFrameworkService(SdkEventQueue sdkEventQueue) {
        this.sdkEventQueue = sdkEventQueue;
    }

    /** 增量读取结果 · 对齐 CC getTaskOutputDelta 返回 {@code {content, newOffset}} */
    public record TaskOutputDelta(String content, long newOffset) {
    }

    /**
     * generateTaskAttachments 产出 · 对齐 CC framework.ts:158-206 返回
     * {@code {attachments, updatedTaskOffsets, evictedTaskIds}}。
     * attachments 恒空（CC 不产附件通知，各 task 类型自管完成通知）。
     */
    public record TaskAttachmentsResult(
        List<BackgroundTask> attachments,
        Map<String, Long> updatedTaskOffsets,
        List<String> evictedTaskIds
    ) {
    }

    /**
     * 注册任务 — 对齐 CC framework.ts:77-117 registerTask
     *
     * <p><b>task_started SDK 事件（OPD-TS-22/TP-18）</b>：对齐 CC framework.ts:104-116，
     * 非 replacement（resume 替换）才发射，避免重复开始事件。字段映射：
     * task_id=task.id / tool_use_id=task.toolUseId / description / task_type=type.getTypeString()
     * （"local_bash" 等小写，对齐 CC TaskType 枚举值）/ workflow_name/prompt Java 侧无对应字段 → null（NON_NULL 省略）。
     */
    public void registerTask(BackgroundTask task) {
        boolean isReplacement = store.containsKey(task.id());
        store.put(task.id(), task);
        log.info("TaskFrameworkService.registerTask: id={}, type={}, description='{}'",
            task.id(), task.type().getTypeString(), task.description());
        // CC framework.ts:101-116: replacement (resume) 非新开始，跳过避免双发
        if (isReplacement) {
            if (log.isDebugEnabled()) {
                log.debug("TaskFrameworkService.registerTask: id={} 为 replacement (resume)，跳过 task_started", task.id());
            }
            return;
        }
        if (sdkEventQueue != null) {
            sdkEventQueue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent(
                task.id(), task.toolUseId(), task.description(),
                task.type().getTypeString(), null, null));
        }
    }

    /**
     * 注册主会话后台任务 — 对齐 CC framework.ts:77-117 registerTask 对 LocalMainSessionTaskState 的处理
     * （LocalMainSessionTask.ts:150 调用）+ CC framework.ts:116 {@code prompt} 入 task_started。
     *
     * <p><b>双写</b>：{@code projection}（BackgroundTask，TaskStateBase 11 字段投影）入 {@link #store}，
     * 保证框架层（TaskStop/offset-evict/completeMainSessionTask 的 getTask 读取）零改动；
     * {@code state}（完整 MainSessionTaskState，OPD-TP-16 独立载体）入 {@link #mainSessionStore}。
     * 投影与全量由调用方同步构造（MainSessionBackgroundService.registerMainSessionTask），id 合一。
     *
     * <p><b>task_started SDK（CC framework.ts:104-116）</b>：isReplacement（resume 替换）跳过避免双发；
     * 非 replacement 才发射，且 {@code prompt = state.prompt()}（CC :116 'prompt' in task → task.prompt，
     * main-session 任务有 prompt 字段，区别于 BackgroundTask 路径 prompt=null）。
     *
     * @param state      完整 MainSessionTaskState 载体（'s' 前缀 id）
     * @param projection BackgroundTask 投影（同一 taskId 的 TaskStateBase 视图，含 agentId UUID 视图）
     */
    public void registerMainSessionTask(MainSessionTaskState state, BackgroundTask projection) {
        boolean isReplacement = store.containsKey(state.id()) || mainSessionStore.containsKey(state.id());
        store.put(state.id(), projection);
        mainSessionStore.put(state.id(), state);
        log.info("TaskFrameworkService.registerMainSessionTask: id={}, agentType={}, prompt='{}', isBackgrounded={}",
            state.id(), state.agentType(), state.prompt(), state.isBackgrounded());
        // CC framework.ts:101-116: replacement (resume) 非新开始，跳过避免双发
        if (isReplacement) {
            if (log.isDebugEnabled()) {
                log.debug("TaskFrameworkService.registerMainSessionTask: id={} 为 replacement (resume)，跳过 task_started", state.id());
            }
            return;
        }
        if (sdkEventQueue != null) {
            sdkEventQueue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent(
                state.id(), state.toolUseId(), state.description(),
                state.type().getTypeString(), null, state.prompt()));
        }
    }

    /** 获取主会话后台任务完整载体（OPD-TP-16 独立 store）· 投影层经 {@link #getTask} */
    public Optional<MainSessionTaskState> getMainSessionTask(String taskId) {
        return Optional.ofNullable(mainSessionStore.get(taskId));
    }

    /**
     * 更新主会话后台任务完整载体（CC updateTaskState 的 main-session 通道）。
     *
     * <p>调用方（completeMainSessionTask 等）在更新 BackgroundTask 投影时同步传本方法更新全量载体，
     * 避免 mainSessionStore 状态滞留 RUNNING（RK-w5-1 生命周期同步待办）。
     */
    public void updateMainSessionTask(String taskId, MainSessionTaskState newState) {
        MainSessionTaskState existing = mainSessionStore.get(taskId);
        if (existing != null) {
            mainSessionStore.put(taskId, newState);
            if (log.isDebugEnabled()) {
                log.debug("TaskFrameworkService.updateMainSessionTask: id={}, status={}, isBackgrounded={}",
                    taskId, newState.status().getStatusString(), newState.isBackgrounded());
            }
        }
    }

    /**
     * 更新任务状态 — 对齐 CC framework.ts:49-62 updateTaskState（整体替换 record）。
     *
     * <p>CC 的 updater 产出全新 task 后整体写回（{@code [taskId]: updated}）。旧 Java 实现仅
     * withStatus 合并，notified 不落地 shadow store —— 终态任务在 store 里 notified 恒 false，
     * 使 evictTerminalTask / generateTaskAttachments 的 notified 检查永远不通过（泄露）。
     * 现改为整体替换，调用方传入已 withNotified 的终态 record。
     *
     * @param newState 替换后的完整任务 record（含最新 status/notified/outputOffset）
     */
    public void updateTaskState(String taskId, BackgroundTask newState) {
        BackgroundTask existing = store.get(taskId);
        if (existing != null) {
            store.put(taskId, newState);
            if (log.isDebugEnabled()) {
                log.debug("TaskFrameworkService.updateTaskState: id={}, status={}, notified={}, outputOffset={}",
                    taskId, newState.status().getStatusString(), newState.notified(), newState.outputOffset());
            }
        }
    }

    /**
     * 轮询已完成/失败/killed 的任务 — 对齐 CC framework.ts:255-269 pollTasks
     *
     * <p>CC pollTasks 为死代码（grep 全 CC src 仅定义无调用，EVD-W302-06）。
     * OPD-TP-04 要求两端对称保留定义、不改语义，故此处保留旧实现。
     */
    public List<BackgroundTask> pollTasks() {
        List<BackgroundTask> terminal = new ArrayList<>();
        for (BackgroundTask task : store.values()) {
            if (task.status().isTerminal() && !task.notified()) {
                terminal.add(task);
            }
        }
        return terminal;
    }

    /** 获取单个任务 */
    public Optional<BackgroundTask> getTask(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    /**
     * [RF-2 ②] 原始移除任务（非终态 evict）· 对齐 CC {@code unregisterAgentForeground}
     * （LocalAgentTask.tsx:664-678）对 {@code state.tasks} 的裸移除
     * （{@code const { [taskId]: removed, ...rest } = prev.tasks}）。
     *
     * <p>前台任务完成（未后台化）直接注销——无终态/notified 前置（区别于 {@link #evictTerminalTask}
     * 的 running→终态→notified 三闸）。同步清理双 store（主会话任务与前台子代理任务同 store 双写）。
     */
    public void removeTask(String taskId) {
        store.remove(taskId);
        mainSessionStore.remove(taskId);
        log.info("TaskFrameworkService.removeTask: id={} (RF-2 unregisterAgentForeground)", taskId);
    }

    /** 列出所有任务 */
    public List<BackgroundTask> listAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * 饿式 evict 终态任务 — 对齐 CC framework.ts:124-147 evictTerminalTask。
     *
     * <p>前置三闸（缺一不删）：存在 + 终态 + notified；再加 retain/evictAfter 保留宽限。
     * CC :123 注释 "The lazy GC in generateTaskAttachments() remains as a safety net."，
     * 饿式路径没走到时由 generateTaskAttachments 惰性兜底。
     */
    public void evictTerminalTask(String taskId) {
        BackgroundTask task = store.get(taskId);
        if (task == null) return;
        if (!task.status().isTerminal()) return;
        if (!task.notified()) return;
        if (isRetained(task)) return;
        store.remove(taskId);
        // 主会话任务双写 store 同步 evict（OPD-TP-16 独立载体不滞留）
        mainSessionStore.remove(taskId);
        log.info("TaskFrameworkService.evictTerminalTask: id={}", taskId);
    }

    /**
     * 生成任务附件 — 对齐 CC framework.ts:158-206 generateTaskAttachments。
     *
     * <p>语义（EVD-W302-02）：
     * <ul>
     *   <li>attachments 恒空 —— CC 不在 generateTaskAttachments 产附件通知
     *       （framework.ts:199-202：completed 由各 task 类型 enqueuePendingNotification 自管，
     *       避免与内联附件双发）</li>
     *   <li>notified && 终态 → evictedTaskIds（惰性 GC）</li>
     *   <li>notified && pending → 跳过（保留在 map，父已知道）</li>
     *   <li>running → getTaskOutputDelta；仅当 delta.content 非空才推进 offset</li>
     * </ul>
     *
     * @return 附件（恒空）+ offset patch + 待 evict 列表
     */
    public TaskAttachmentsResult generateTaskAttachments() {
        List<BackgroundTask> attachments = new ArrayList<>();
        Map<String, Long> updatedTaskOffsets = new LinkedHashMap<>();
        List<String> evictedTaskIds = new ArrayList<>();

        for (BackgroundTask task : store.values()) {
            if (task.notified()) {
                switch (task.status()) {
                    case COMPLETED, FAILED, KILLED -> {
                        // 终态 && notified → 已被消费，可 GC (framework.ts:174-179)
                        evictedTaskIds.add(task.id());
                        continue;
                    }
                    case PENDING -> {
                        // 未运行，父已知道 — 保留 (framework.ts:181-184)
                        continue;
                    }
                    case RUNNING -> {
                        // 落入下方 running 逻辑 (framework.ts:185-186 break)
                    }
                }
            }
            if (task.status() == BackgroundTaskStatus.RUNNING) {
                TaskOutputDelta delta = getTaskOutputDelta(task.id(), task.outputOffset());
                // 仅内容非空才推进 offset (framework.ts:193-196 if (delta.content))
                if (!delta.content().isEmpty()) {
                    updatedTaskOffsets.put(task.id(), delta.newOffset());
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("TaskFrameworkService.generateTaskAttachments: offsets={}, evictions={}",
                updatedTaskOffsets.size(), evictedTaskIds.size());
        }
        return new TaskAttachmentsResult(attachments, updatedTaskOffsets, evictedTaskIds);
    }

    /**
     * 应用 offset patch 与 evictions — 对齐 CC framework.ts:213-249 applyTaskOffsetsAndEvictions。
     *
     * <p>合并时<b>重查 fresh state</b>（非 generateTaskAttachments 的快照）——TOCTOU：
     * delta 异步磁盘读期间任务可能 completed，旧快照 spread 会覆盖该转变（zombify）；
     * resume 可能替换了任务（fresh 不存在则跳过）。
     *
     * @param updatedTaskOffsets 只含 offset patch（非完整 task）
     * @param evictedTaskIds     待 evict 任务 id 列表
     */
    public void applyTaskOffsetsAndEvictions(
            Map<String, Long> updatedTaskOffsets, List<String> evictedTaskIds) {
        boolean hasOffsets = updatedTaskOffsets != null && !updatedTaskOffsets.isEmpty();
        boolean hasEvictions = evictedTaskIds != null && !evictedTaskIds.isEmpty();
        if (!hasOffsets && !hasEvictions) {
            return;
        }
        boolean changed = false;
        if (hasOffsets) {
            for (Map.Entry<String, Long> entry : updatedTaskOffsets.entrySet()) {
                BackgroundTask fresh = store.get(entry.getKey());
                // 重查 fresh state — 任务可能在 delta 读取期间已终态 (framework.ts:227-232)
                if (fresh != null && fresh.status() == BackgroundTaskStatus.RUNNING) {
                    store.put(fresh.id(), fresh.withOutputOffset(entry.getValue()));
                    changed = true;
                }
            }
        }
        if (hasEvictions) {
            for (String id : evictedTaskIds) {
                BackgroundTask fresh = store.get(id);
                // 重查 fresh state — resume 可能已替换任务 (framework.ts:236-243)
                if (fresh == null || !fresh.status().isTerminal() || !fresh.notified()) {
                    continue;
                }
                // retain/evictAfter 保留宽限 (framework.ts:241-243)
                if (isRetained(fresh)) {
                    continue;
                }
                store.remove(id);
                // 主会话任务双写 store 同步 evict（OPD-TP-16 独立载体不滞留）
                mainSessionStore.remove(id);
                changed = true;
                log.info("TaskFrameworkService.applyTaskOffsetsAndEvictions: 惰性 evict 终态任务 id={}", id);
            }
        }
        if (changed && log.isDebugEnabled()) {
            log.debug("TaskFrameworkService.applyTaskOffsetsAndEvictions: 已应用 offsets={}, evictions={}",
                updatedTaskOffsets.size(), evictedTaskIds.size());
        }
    }

    /**
     * 增量读取任务输出 — 对齐 CC diskOutput.ts:304-330 getTaskOutputDelta（默认 8MB cap）。
     *
     * <p>语义（EVD-W302-04）：
     * <ul>
     *   <li>文件大小 ≤ fromOffset → 无新内容，offset 不变</li>
     *   <li>读取 min(size-offset, maxBytes) 字节 → newOffset = fromOffset + bytesRead</li>
     *   <li>任何错误（ENOENT 或读失败）→ 空增量 + offset 不变（CC catch 全量吞掉）</li>
     * </ul>
     */
    public TaskOutputDelta getTaskOutputDelta(String taskId, long fromOffset) {
        return getTaskOutputDelta(taskId, fromOffset, DEFAULT_MAX_READ_BYTES);
    }

    /**
     * 增量读取任务输出 — 对齐 CC getTaskOutputDelta(taskId, fromOffset, maxBytes)。
     *
     * @param maxBytes 单次读取字节上限（对齐 CC DEFAULT_MAX_READ_BYTES 默认 8MB）
     */
    public TaskOutputDelta getTaskOutputDelta(String taskId, long fromOffset, long maxBytes) {
        BackgroundTask task = store.get(taskId);
        if (task == null || task.outputFile() == null || task.outputFile().isBlank()) {
            return new TaskOutputDelta("", fromOffset);
        }
        Path path = Path.of(task.outputFile());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            // readFileRange: size <= offset → return null → 空增量 + offset 不变
            if (size <= fromOffset) {
                return new TaskOutputDelta("", fromOffset);
            }
            long bytesToRead = Math.min(size - fromOffset, maxBytes);
            ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
            long totalRead = 0;
            while (totalRead < bytesToRead) {
                int read = channel.read(buffer, fromOffset + totalRead);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }
            buffer.flip();
            String content = StandardCharsets.UTF_8.decode(buffer).toString();
            if (log.isDebugEnabled()) {
                log.debug("TaskFrameworkService.getTaskOutputDelta: id={}, from={}, read={}, newOffset={}",
                    taskId, fromOffset, totalRead, fromOffset + totalRead);
            }
            return new TaskOutputDelta(content, fromOffset + totalRead);
        } catch (IOException e) {
            // CC getTaskOutputDelta catch 全量吞掉：ENOENT 静默 + 其他 logError（diskOutput.ts:322-329）
            if (e instanceof NoSuchFileException) {
                if (log.isDebugEnabled()) {
                    log.debug("TaskFrameworkService.getTaskOutputDelta: id={} 输出文件不存在(ENOENT), 返回空增量", taskId);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("TaskFrameworkService.getTaskOutputDelta: id={} 输出读取失败({}), 返回空增量", taskId, e.getMessage());
            }
            return new TaskOutputDelta("", fromOffset);
        }
    }

    /**
     * retain/evictAfter 保留宽限 · 对齐 CC framework.ts:138/241
     * {@code 'retain' in task && (task.evictAfter ?? Infinity) > Date.now()}。
     *
     * <p>Java 侧 BackgroundTask 无 retain/evictAfter 字段（CC 仅 LocalAgentTaskState 有此字段），
     * 故本方法恒返回 false —— 结构上对应 CC 中非 LocalAgent 任务"无 retain"的分支。保留此判断
     * 以对齐语义边界，未来若 BackgroundTask 增加 retain 字段可直接落地。
     */
    private boolean isRetained(BackgroundTask task) {
        return false;
    }
}
