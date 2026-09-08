package com.nexusai.application.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * [C 级 2026-09-07 后端重启副作用补回 · 对齐 CC sessionStart.ts] 进程级 SessionStart「冷启动」判据注册表。
 *
 * <p><b>背景</b>：nexusai web 端每 user 消息 = 一次新 {@code LlmAgentLoop.run}。CC 会话开始只发生在
 * {@code startup/resume/clear/compact} 四个进程边界（sessionStart.ts），同进程内轮不重跑 hook；而 Java
 * 需在 JVM 内多次 run 之间表达「该会话本进程是否已跑过 SessionStart」——JVM 重启即自然清空（进程内存）。
 * 判据 = JVM 冷/热，而非历史空不空：老会话在后端重启后的首条消息须重跑 SessionStart hook 副作用
 * （watchPaths 等动态监听重建），但不得重复注入（避免堆副本，见 LlmAgentLoop §14 V1 存在即跳过）。
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>{@link #markSeen} = {@code putIfAbsent(key, TRUE) == null} → 冷（本进程首次跑该会话）；否则热。</li>
 *   <li>key = {@code streamSessionId}（会话 short id）——主线程与后台任务（setTaskStreamContext 透传同一
 *       sessionId，见 LlmAgentLoop）同 key，互不覆盖；真子代理不调 {@code LlmAgentLoop.run} 不涉及。</li>
 *   <li>{@link #remove}：/clear 清空会话时点移除该会话 key → 下 run 恢复冷（对齐 CC clear 边界：下个会话
 *       起点再触发 SessionStart）。</li>
 *   <li>{@link #reset}：仅供「重启模拟」测试（@VisibleForTesting）；生产进程重启天然清空，无需调用。</li>
 * </ul>
 *
 * <p><b>local-only 红线</b>：纯进程内存，绝不持久化 / 绝不外发（CC isLoggableMessage 过滤
 * CLAUDE_CODE_SAVE_HOOK_ADDITIONAL_CONTEXT 只控落盘 transcript，本表不落盘）。
 */
public final class SessionStartSeenRegistry {

    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>();

    private SessionStartSeenRegistry() {
    }

    /**
     * 登记本会话「SessionStart 已跑」并返回是否冷启动。
     *
     * @param sessionKey 会话 short id（{@code streamSessionId}）；null/空白 → 无去重键 → 恒返回 {@code true}
     *                   （无会话 id 的 run 无冷热概念，每次照旧触发，对齐 pre-B 无会话路径行为）
     * @return {@code true}=冷（本进程首次、putIfAbsent 成功）→ 应执行 SessionStart hook 链；
     *         {@code false}=热（同进程已跑过）→ 应整段跳过
     */
    public static boolean markSeen(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return true;
        }
        return SEEN.putIfAbsent(sessionKey, Boolean.TRUE) == null;
    }

    /**
     * 移除会话 key（/clear 清空会话时点调用）：下 run 恢复冷 → 重跑 SessionStart hook 副作用；
     * 注入去重仍由 §14 V1「存在即跳过」独立兜底（见 LlmAgentLoop），故恒 0 或 1 份、绝无 2 份。
     */
    public static void remove(String sessionKey) {
        if (sessionKey != null && !sessionKey.isBlank()) {
            SEEN.remove(sessionKey);
        }
    }

    /**
     * 清空全表（@VisibleForTesting：重启模拟测试用）。生产进程重启天然清空（JVM 内存），不供生产调用。
     */
    public static void reset() {
        SEEN.clear();
    }
}
