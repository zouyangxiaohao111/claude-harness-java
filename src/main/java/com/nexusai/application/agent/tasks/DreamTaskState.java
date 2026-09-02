package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AbortController;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Dream 任务状态载体 — 对齐 CC tasks/DreamTask/DreamTask.ts:15-41 DreamTaskState
 *
 * <p>CC 源码（DreamTask.ts:15-41，真源）：
 * <pre>
 * export type DreamTaskState = TaskStateBase & {
 *   type: 'dream'
 *   phase: DreamPhase            // 'starting' | 'updating'
 *   sessionsReviewing: number
 *   filesTouched: string[]       // Edit/Write tool_use 观察路径（INCOMPLETE 反射）
 *   turns: DreamTurn[]           // 助手文本响应，工具调用折叠为计数，不含 prompt
 *   abortController?: AbortController
 *   priorMtime: number           // kill 时回退锁 mtime 用（同 fork 失败路径）
 * }
 * </pre>
 *
 * <p><b>与 BackgroundTask 的关系（Java 双存储）</b>：CC 中 DreamTaskState 直接存
 * AppState.tasks 单一注册表；Java 侧 BackgroundTask 无 dream 特有字段，故本 record 在
 * {@link DreamTaskRegistry} 自有 store 保存富状态，另构 BackgroundTask 经
 * {@link TaskFrameworkService#registerTask} 落统一 store + 发 SDK task_started。
 *
 * @param id                CC TaskStateBase.id（generateTaskId('dream') → 'd' + 8 base36，DreamTask.ts:60）
 * @param type               CC DreamTaskState.type，恒为 {@link TaskType#DREAM}
 * @param status             CC TaskStateBase.status（registerDreamTask 覆盖为 running，DreamTask.ts:64）
 * @param description        CC TaskStateBase.description（'dreaming'，DreamTask.ts:62）
 * @param startTime          CC TaskStateBase.startTime（createTaskStateBase Date.now()，Task.ts:114）
 * @param endTime            CC TaskStateBase.endTime（终态置值，可空）
 * @param notified           CC TaskStateBase.notified（终态立即 true —— dream UI-only，eviction 需 terminal+notified）
 * @param phase              CC DreamTaskState.phase（DreamTask.ts:27/65）——不解析 prompt，首个 Edit/Write tool_use 到达才翻 updating
 * @param sessionsReviewing  CC DreamTaskState.sessionsReviewing（DreamTask.ts:28/66）——被 consolidation 审阅的 session 数
 * @param filesTouched       CC DreamTaskState.filesTouched（DreamTask.ts:35/67）——Edit/Write 观察路径，Set 去重，INCOMPLETE 反射
 * @param turns              CC DreamTaskState.turns（DreamTask.ts:37/68）——助手响应折叠计数，MAX_TURNS=30 截断
 * @param abortController    CC DreamTaskState.abortController（DreamTask.ts:38/69）——fork 的 controller，终态清空
 * @param priorMtime         CC DreamTaskState.priorMtime（DreamTask.ts:40/70）——kill 时回退 consolidation lock mtime
 */
public record DreamTaskState(
    String id,
    TaskType type,
    BackgroundTaskStatus status,
    String description,
    long startTime,
    @Nullable Long endTime,
    boolean notified,
    DreamPhase phase,
    int sessionsReviewing,
    List<String> filesTouched,
    List<DreamTurn> turns,
    @Nullable AbortController abortController,
    long priorMtime
) {

    /**
     * 单条助手 turn · 对齐 CC DreamTask.ts:15-18 DreamTurn
     *
     * @param text         助手文本响应（不含 prompt）
     * @param toolUseCount 工具调用折叠为计数
     */
    public record DreamTurn(String text, int toolUseCount) {
    }

    /**
     * dream 阶段 · 对齐 CC DreamTask.ts:23 DreamPhase
     *
     * <p>无阶段检测——dream prompt 有 4 阶段结构（orient/gather/consolidate/prune）但不解析；
     * 仅当首个 Edit/Write tool_use 落点时从 'starting' 翻 'updating'（DreamTask.ts:21-23/96）。
     */
    public enum DreamPhase {
        /** CC 'starting'（DreamTask.ts:23/65） */
        STARTING,
        /** CC 'updating'（DreamTask.ts:23/96）——首个 Edit/Write 落点后 */
        UPDATING
    }

    /** 紧凑构造函数：非空校验 + 防御性 copy */
    public DreamTaskState {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (description == null) {
            description = "";
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase cannot be null");
        }
        filesTouched = filesTouched == null ? List.of() : List.copyOf(filesTouched);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
