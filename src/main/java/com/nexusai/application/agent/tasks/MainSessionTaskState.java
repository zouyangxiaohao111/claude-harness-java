package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * 主会话后台化任务状态载体 · 对齐 CC {@code LocalMainSessionTaskState}
 * （LocalMainSessionTask.ts:55-57）+ {@code LocalAgentTaskState}（LocalAgentTask.tsx:116-148）。
 *
 * <p>CC 真源（LocalMainSessionTask.ts:55-57）：
 * <pre>
 * export type LocalMainSessionTaskState = LocalAgentTaskState & {
 *   agentType: 'main-session'
 * }
 * </pre>
 * 即复用 {@code LocalAgentTaskState} 全字段（agentId/prompt/selectedAgent/agentType/model/
 * abortController/unregisterCleanup/error/result/progress/retrieved/messages/
 * lastReportedToolCount/lastReportedTokenCount/isBackgrounded/pendingMessages/retain/
 * diskLoaded/evictAfter），并以 {@code agentType='main-session'} 区分主会话后台任务。
 *
 * <p><b>Java 端映射原则</b>：
 * <ul>
 *   <li>{@code TaskStateBase}（Task.ts:45-57）11 字段 → 本 record 前 11 字段（id/type/status/.../notified）</li>
 *   <li>{@code selectedAgent/abortController/unregisterCleanup/result} 为 Java 端易失/不可序列化字段，
 *       不放入本载体（注册侧由 MainSessionBackgroundService 以 AgentDefinition/AbortController 直接持有；
 *       CC :114/:116/:136 语义在接线时透传）</li>
 *   <li>{@code messages} 以 {@code List<Map<String,Object>>} 承载（对齐 AgentTranscript.recordSidechainTranscript
 *       的 transcript 消息形状，sessionStorage.ts:995/1042）</li>
 *   <li>{@code progress} 以嵌套 {@link Progress} record 承载（对齐 CC AgentProgress，LocalAgentTask.tsx:33-39）</li>
 * </ul>
 *
 * <p><b>OPD-TP-16 拍板</b>：独立载体，不污染基础 {@link BackgroundTask}（该 record 专责主会话后台化
 * 状态，含 prompt/messages/progress/agentType='main-session' + 's' 前缀 ID）。
 *
 * @param id              CC original: id（Task.ts:46）— 's' 前缀任务 ID
 * @param type            CC original: type（Task.ts:47）— 恒为 {@link TaskType#LOCAL_AGENT}
 * @param status          CC original: status（Task.ts:48）— 'pending'|'running'|'completed'|'failed'|'killed'
 * @param description     CC original: description（Task.ts:49）
 * @param toolUseId       CC original: toolUseId（Task.ts:50，nullable）
 * @param startTime       CC original: startTime（Task.ts:51）
 * @param endTime         CC original: endTime（Task.ts:52，nullable）
 * @param totalPausedMs   CC original: totalPausedMs（Task.ts:53，nullable）
 * @param outputFile      CC original: outputFile（Task.ts:54）— symlink 隔离 per-task transcript 路径
 * @param outputOffset    CC original: outputOffset（Task.ts:55）
 * @param notified        CC original: notified（Task.ts:56）
 * @param agentId         CC original: agentId（LocalAgentTask.tsx:118）— 与 taskId 合一（CC :132 agentId=taskId）
 * @param prompt          CC original: prompt（LocalAgentTask.tsx:119）— 任务描述（register 时 description）
 * @param agentType       CC original: agentType（LocalAgentTask.tsx:121）— 恒为 'main-session'（LocalMainSessionTask.ts:56）
 * @param model           CC original: model（LocalAgentTask.tsx:122，nullable）
 * @param error           CC original: error（LocalAgentTask.tsx:125，nullable）
 * @param progress        CC original: progress（LocalAgentTask.tsx:127，nullable）— 见嵌套 {@link Progress}
 * @param retrieved       CC original: retrieved（LocalAgentTask.tsx:128）
 * @param messages        CC original: messages（LocalAgentTask.tsx:129，nullable）— transcript 消息（Map 形状）
 * @param lastReportedToolCount CC original: lastReportedToolCount（LocalAgentTask.tsx:131）
 * @param lastReportedTokenCount CC original: lastReportedTokenCount（LocalAgentTask.tsx:132）
 * @param isBackgrounded  CC original: isBackgrounded（LocalAgentTask.tsx:134）— 后台任务恒 true（CC :141）
 * @param pendingMessages CC original: pendingMessages（LocalAgentTask.tsx:136）— SendMessage 排队消息
 * @param retain          CC original: retain（LocalAgentTask.tsx:140）— UI 持有中（禁 eviction + 流式追加）
 * @param diskLoaded      CC original: diskLoaded（LocalAgentTask.tsx:143）— 已从 sidechain JSONL 引导读取
 * @param evictAfter      CC original: evictAfter（LocalAgentTask.tsx:147，nullable）— 面板隐藏/GC 截止时间戳
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MainSessionTaskState(
    String id,
    TaskType type,
    BackgroundTaskStatus status,
    String description,
    @Nullable String toolUseId,
    long startTime,
    @Nullable Long endTime,
    @Nullable Long totalPausedMs,
    String outputFile,
    long outputOffset,
    boolean notified,
    String agentId,
    String prompt,
    String agentType,
    @Nullable String model,
    @Nullable String error,
    @Nullable Progress progress,
    boolean retrieved,
    @Nullable List<Map<String, Object>> messages,
    long lastReportedToolCount,
    long lastReportedTokenCount,
    boolean isBackgrounded,
    List<String> pendingMessages,
    boolean retain,
    boolean diskLoaded,
    @Nullable Long evictAfter
) {

    /** CC agentType 常量值（LocalMainSessionTask.ts:56）'main-session' */
    public static final String AGENT_TYPE_MAIN_SESSION = "main-session";

    /** CC :73 's' 前缀（区别于 agent 任务的 'a' 前缀；Task.ts TASK_ID_PREFIXES 无 's'） */
    private static final String MAIN_SESSION_ID_PREFIX = "s";

    /** CC TASK_ID_ALPHABET（LocalMainSessionTask.ts:73 = Task.ts:96）'0123456789abcdefghijklmnopqrstuvwxyz' */
    private static final String TASK_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * 生成主会话任务 ID · 对齐 CC {@code generateMainSessionTaskId}（LocalMainSessionTask.ts:75-82）。
     *
     * <p>'s' 前缀 + 8×base36（randomBytes(8) 每字节 % 36 取字母），不在 Task.ts TASK_ID_PREFIXES
     * 内（CC 独立于 generateTaskId，Task.ts:79-87 仅 b/a/r/t/w/m/d）。
     *
     * @return 's' + 8×base36 共 9 字符
     */
    public static String generateMainSessionId() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        StringBuilder id = new StringBuilder(MAIN_SESSION_ID_PREFIX);
        for (byte b : bytes) {
            id.append(TASK_ID_ALPHABET.charAt((b & 0xFF) % TASK_ID_ALPHABET.length()));
        }
        return id.toString();
    }

    /** 创建一个带新状态的副本（对齐 BackgroundTask.withStatus 约定） */
    public MainSessionTaskState withStatus(BackgroundTaskStatus newStatus) {
        return new MainSessionTaskState(id, type, newStatus, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, prompt, agentType, model, error, progress, retrieved, messages,
            lastReportedToolCount, lastReportedTokenCount, isBackgrounded, pendingMessages,
            retain, diskLoaded, evictAfter);
    }

    /** 创建一个带 endTime 的副本（完成/失败/kill 时设置，CC :190 endTime=Date.now()） */
    public MainSessionTaskState withEndTime(long newEndTime) {
        return new MainSessionTaskState(id, type, status, description, toolUseId,
            startTime, newEndTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, prompt, agentType, model, error, progress, retrieved, messages,
            lastReportedToolCount, lastReportedTokenCount, isBackgrounded, pendingMessages,
            retain, diskLoaded, evictAfter);
    }

    /** 创建一个带 notified=true 的副本（CC 通知后标记，enqueueMainSessionNotification CAS :231-243） */
    public MainSessionTaskState withNotified() {
        return new MainSessionTaskState(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, true,
            agentId, prompt, agentType, model, error, progress, retrieved, messages,
            lastReportedToolCount, lastReportedTokenCount, isBackgrounded, pendingMessages,
            retain, diskLoaded, evictAfter);
    }

    /** 创建一个带 isBackgrounded 的副本（前台化切换，CC foregroundMainSessionTask :296 isBackgrounded:false） */
    public MainSessionTaskState withIsBackgrounded(boolean newIsBackgrounded) {
        return new MainSessionTaskState(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, prompt, agentType, model, error, progress, retrieved, messages,
            lastReportedToolCount, lastReportedTokenCount, newIsBackgrounded, pendingMessages,
            retain, diskLoaded, evictAfter);
    }

    /** 创建一个带新 progress 的副本（CC :456-463 progress 更新，重复值短路由调用方判定） */
    public MainSessionTaskState withProgress(@Nullable Progress newProgress) {
        return new MainSessionTaskState(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, prompt, agentType, model, error, newProgress, retrieved, messages,
            lastReportedToolCount, lastReportedTokenCount, isBackgrounded, pendingMessages,
            retain, diskLoaded, evictAfter);
    }

    /** 创建一个带新 messages 的副本（CC :464 messages: bgMessages 追加） */
    public MainSessionTaskState withMessages(@Nullable List<Map<String, Object>> newMessages) {
        return new MainSessionTaskState(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, prompt, agentType, model, error, progress, retrieved, newMessages,
            lastReportedToolCount, lastReportedTokenCount, isBackgrounded, pendingMessages,
            retain, diskLoaded, evictAfter);
    }

    /**
     * 任务进度 · 对齐 CC {@code AgentProgress}（LocalAgentTask.tsx:33-39）。
     *
     * <p>CC 真源：
     * <pre>
     * export type AgentProgress = {
     *   toolUseCount: number;
     *   tokenCount: number;
     *   lastActivity?: ToolActivity;
     *   recentActivities?: ToolActivity[];
     *   summary?: string;
     * };
     * </pre>
     * startBackgroundSession 更新时 set {@code { tokenCount, toolUseCount, recentActivities }}
     * （CC :456-462，工具轮换时替换 recentActivities 快照）。
     *
     * @param toolUseCount     CC original: toolUseCount（LocalAgentTask.tsx:34）
     * @param tokenCount       CC original: tokenCount（LocalAgentTask.tsx:35）
     * @param lastActivity     CC original: lastActivity（LocalAgentTask.tsx:36，nullable）
     * @param recentActivities CC original: recentActivities（LocalAgentTask.tsx:37，nullable，≤5 shift）
     * @param summary          CC original: summary（LocalAgentTask.tsx:38，nullable）
     */
    public record Progress(
        long toolUseCount,
        long tokenCount,
        @Nullable ToolActivity lastActivity,
        @Nullable List<ToolActivity> recentActivities,
        @Nullable String summary
    ) {}

    /**
     * 工具活动记录 · 对齐 CC {@code ToolActivity}（LocalAgentTask.tsx:24-32）。
     *
     * @param toolName            CC original: toolName（LocalAgentTask.tsx:25）
     * @param input               CC original: input（LocalAgentTask.tsx:26，Record<string, unknown>）
     * @param activityDescription CC original: activityDescription（LocalAgentTask.tsx:28，nullable）
     * @param isSearch            CC original: isSearch（LocalAgentTask.tsx:30，nullable）
     * @param isRead              CC original: isRead（LocalAgentTask.tsx:31，nullable）
     */
    public record ToolActivity(
        String toolName,
        Map<String, Object> input,
        @Nullable String activityDescription,
        @Nullable Boolean isSearch,
        @Nullable Boolean isRead
    ) {}
}
