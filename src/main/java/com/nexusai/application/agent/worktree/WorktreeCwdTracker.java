package com.nexusai.application.agent.worktree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * s18 P1-10: wt_ctx cwd 切换 — 对齐 CC utils/worktree.ts:156 currentWorktreeSession.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>每个 session 维护当前活跃 worktree 路径 (per sessionId)</li>
 *   <li>进入 worktree → setCwd(sessionId, worktreePath)</li>
 *   <li>退出 worktree → clearCwd(sessionId)</li>
 *   <li>工具执行时 → getCwd(sessionId) 作为 workdir 覆盖</li>
 * </ul>
 *
 * <p>教学版实现: 内存 ConcurrentHashMap (CC 持久化到 ~/.claude/worktree-config.json).
 *
 * <p><b>[WF-2] 持久化边界</b>：本类是<b>纯内存缓存</b>（等价 CC {@code currentWorktreeSession}
 * 模块级变量，仅供同会话内 Enter→Exit 快速读写）。跨会话 resume 恢复所需的 transcript
 * worktree-state 持久化（对齐 CC {@code sessionStorage.ts:2889 saveWorktreeState}）由
 * {@code SessionStorage.writeWorktreeState/readWorktreeState} 承担，接线在
 * {@code EnterWorktreeTool} / {@code ExitWorktreeTool}（Enter 写 full worktreeSession、
 * Exit 写 null）。原因是 full worktreeSession 需 worktreeName/worktreeBranch/hookBased，
 * 仅工具调用点持有，本类只缓存 originalCwd + cwd 两态。
 *
 * <p><b>[RESIDUAL-FIX 残留 2]</b>：本类新增 {@code sessionWorktree} 三态，缓存完整
 * worktree 会话对象（对齐 CC {@code currentWorktreeSession} 模块级变量的 session 维度等价），
 * 供 resume 复刻 {@code restoreWorktreeSession}（worktree.ts:167-169）恢复完整会话对象语义。
 *
 * <p><b>[misc1 · 2026-09-15 · 已删] 原 {@code public static int activeSessionCount()}</b> ——
 * 删除判据（{@code dead-code-decision-rule} 两条同时成立，<b>均经实测</b>，故非漏实现）：
 * <ol>
 *   <li><b>0 真实调用方（含测试 / 反射）</b>：在本类之外，{@code src/main/java} +
 *       {@code src/test/java} 对该符号名命中 4 处，<b>全部是 {@code SubagentTool} 的注释</b>
 *       （:2165 / :2171 / :3096 / :3350）；本类内除声明外无其它引用。其旧 javadoc 自陈
 *       「用于监控」<b>不成立</b> —— 没有消费方就没有可观测性。</li>
 *   <li><b>CC 无对应物</b>：{@code claude-code-best/src/utils/worktree.ts:156} 与
 *       {@code Open-ClaudeCode/src/utils/worktree.ts:156} 均为
 *       {@code let currentWorktreeSession: WorktreeSession | null = null} —— <b>单会话模块级
 *       变量</b>（CC 单进程单会话），既无 session 维度表、也无任何计数访问器；CC 另有
 *       {@code bootstrap/state.ts:975 getSessionCounter()}，但那是 OpenTelemetry
 *       {@code AttributedCounter} 指标工厂（metrics 通道），与「活跃 worktree 会话数」无关。</li>
 * </ol>
 * 同款先例：批 {@code acc8} 删 {@code SubagentTool.agentRegistry()} 0 参重载。如需恢复，见 git 历史。
 */
public final class WorktreeCwdTracker {

    private static final Logger log = LoggerFactory.getLogger(WorktreeCwdTracker.class);

    private static final Map<String, Path> sessionCwd = new ConcurrentHashMap<>();

    /**
     * [RESIDUAL-FIX 残留 2] 完整 worktree 会话对象 · 对齐 CC worktree.ts:140-154
     * {@code WorktreeSession}（剔除了 Java 无对应物的 originalBranch/originalHeadCommit/
     * tmuxSessionName 等 optional 字段）。
     *
     * <p>CC {@code restoreWorktreeSession(session)}（worktree.ts:167-169）恢复的是完整会话
     * 对象（{@code currentWorktreeSession = session}），而非仅 cwd/originalCwd 两态。本 record
     * 承载完整 worktree 会话，供 resume 时复刻该语义。
     */
    public record WorktreeSession(
            String worktreePath,
            String worktreeBranch,
            String worktreeName,
            boolean hookBased,
            String sessionId) {
    }

    /**
     * session → 完整 worktree 会话对象（对齐 CC currentWorktreeSession 的 session 维度）。
     */
    private static final Map<String, WorktreeSession> sessionWorktree = new ConcurrentHashMap<>();

    /**
     * gap1-originalCwd: session → 进入 worktree 前的用户原始目录（前端传入）。
     * <p>与 {@code sessionCwd}（活跃 worktree 路径）区分：{@code sessionOriginalCwd} 是
     * 进入前目录（对齐 CC worktree.ts:712 getCwd() 捕获），key 同为 sessionId UUID 串。
     * 进入时 EnterWorktreeTool 落值，退出时 ExitWorktreeTool 读取后清除。
     */
    private static final Map<String, String> sessionOriginalCwd = new ConcurrentHashMap<>();

    private WorktreeCwdTracker() {
        // utility class
    }

    /**
     * 设置 session 进入 worktree 前的原始目录（前端传入）· CC original: worktree.ts:712
     * {@code const originalCwd = getCwd()}（createWorktreeForSession 入口捕获）。
     */
    public static void setOriginalCwd(String sessionId, String originalCwd) {
        if (sessionId == null) {
            return;
        }
        if (originalCwd == null || originalCwd.isBlank()) {
            return;
        }
        sessionOriginalCwd.put(sessionId, originalCwd);
        log.info("[WorktreeCwdTracker] setOriginalCwd session={} originalCwd={}", sessionId, originalCwd);
    }

    /**
     * 读取 session 进入 worktree 前的原始目录（null = 未设置，调用方回退 user.dir）。
     */
    public static String getOriginalCwd(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionOriginalCwd.get(sessionId);
    }

    /**
     * 清除 session 原始目录（退出 worktree 后）。
     */
    public static void clearOriginalCwd(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String removed = sessionOriginalCwd.remove(sessionId);
        if (removed != null) {
            log.info("[WorktreeCwdTracker] clearOriginalCwd session={} removed={}", sessionId, removed);
        }
    }

    /**
     * 设置 session 当前活跃 worktree 路径.
     */
    public static void setCwd(String sessionId, Path worktreePath) {
        if (sessionId == null || worktreePath == null) {
            return;
        }
        sessionCwd.put(sessionId, worktreePath);
        log.info("[WorktreeCwdTracker] setCwd session={} cwd={}", sessionId, worktreePath);
    }

    /**
     * 清除 session 活跃 worktree (退出 worktree).
     */
    public static void clearCwd(String sessionId) {
        if (sessionId == null) return;
        Path removed = sessionCwd.remove(sessionId);
        if (removed != null) {
            log.info("[WorktreeCwdTracker] clearCwd session={} removed={}", sessionId, removed);
        }
    }

    /**
     * 读取 session 当前 worktree 路径 (null = 无活跃 worktree).
     */
    public static Path getCwd(String sessionId) {
        if (sessionId == null) return null;
        return sessionCwd.get(sessionId);
    }

    /**
     * [RESIDUAL-FIX 残留 2] 设置 session 完整 worktree 会话对象 · 对齐 CC worktree.ts:167-169
     * {@code restoreWorktreeSession(session)}（{@code currentWorktreeSession = session}）。
     * session 为 null 时等价清空（对齐 CC {@code restoreWorktreeSession(null)}）。
     */
    public static void setWorktreeSession(String sessionId, WorktreeSession session) {
        if (sessionId == null) {
            return;
        }
        if (session == null) {
            clearWorktreeSession(sessionId);
            return;
        }
        sessionWorktree.put(sessionId, session);
        log.info("[WorktreeCwdTracker] setWorktreeSession session={} worktreePath={} "
                + "worktreeName={} worktreeBranch={} hookBased={}", sessionId,
                session.worktreePath(), session.worktreeName(), session.worktreeBranch(),
                session.hookBased());
    }

    /**
     * [RESIDUAL-FIX 残留 2] 读取 session 完整 worktree 会话对象（null = 无活跃会话）·
     * 对齐 CC worktree.ts:158-160 {@code getCurrentWorktreeSession()}。
     */
    public static WorktreeSession getWorktreeSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionWorktree.get(sessionId);
    }

    /**
     * [RESIDUAL-FIX 残留 2] 清除 session 完整 worktree 会话对象（退出 worktree 后）·
     * 对齐 CC {@code restoreWorktreeSession(null)}。
     */
    public static void clearWorktreeSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        WorktreeSession removed = sessionWorktree.remove(sessionId);
        if (removed != null) {
            log.info("[WorktreeCwdTracker] clearWorktreeSession session={} removed={}",
                    sessionId, removed.worktreePath());
        }
    }
}
