package com.nexusai.common;

import com.nexusai.application.agent.skill.NexusaiPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级 projectRoot 载体 · 对齐 CC {@code bootstrap/state.ts} per-session projectRoot（ODF-A1）。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：
 * <ul>
 *   <li>{@code State.projectRoot} state.ts:45-50 —— 注释即语义：「Stable project root - set once at
 *       startup (including by --worktree flag), never updated by mid-session EnterWorktreeTool. Use
 *       for project identity (history, skills, sessions) not file operations.」</li>
 *   <li>{@code getInitialState()} state.ts:261-279 —— 启动时 {@code realpathSync(cwd())} 冻结为
 *       {@code projectRoot}（:271 resolvedCwd = realpathSync(rawCwd)；:279 projectRoot: resolvedCwd）</li>
 *   <li>{@code getProjectRoot()} state.ts:511-513 —— 返回稳定 projectRoot，会话中不更新
 *       （mid-session EnterWorktreeTool 不得调用 setProjectRoot，skills/history 锚定启动时 cwd）</li>
 *   <li>{@code setProjectRoot()} state.ts:523-525 —— 仅 --worktree 启动 flag 使用</li>
 * </ul>
 *
 * <p><b>为什么引入</b>：旧 Java memory 路径解析链（AutoMemPaths/AgentMemoryDirectory/LlmAgentLoop
 * workspaceDir）恒读 {@code System.getProperty("user.dir")} 单例 → 同一 JVM 内不同 cwd 会话解析到
 * 同一 memory 目录（跨项目记忆污染），违反 CC per-session per-cwd 语义。本类提供按 sessionId 登记的
 * 会话级 projectRoot，生产链经注入 supplier 消费，不再直接读 user.dir。
 *
 * <p><b>解析顺序</b>（{@link #resolve()}）：
 * <ol>
 *   <li>当前线程显式注入（{@link #setCurrent(String)} · 对齐 CC cwd 概念，session 处理线程入口设置）</li>
 *   <li>{@code RequestContext.sessionId()} 绑定的会话 projectRoot（{@link #setForSession(String, String)}
 *       登记 · 对齐 CC 启动冻结，会话创建/绑定项目时登记）</li>
 *   <li>{@code CLAUDE_PROJECT_DIR} env 非空用之，否则 {@link NexusaiPaths#getAppConfigHomeDir()}
 *       （ODF-A1 回落链 · 对齐 AutoMemPaths:110-120；绝不读 JVM user.dir —— 同一 JVM 内不同 cwd
 *       会话不得解析到同一进程目录）</li>
 * </ol>
 *
 * <p><b>冻结语义</b>（OPD-SPR-03 · CC stable identity）：{@link #setForSession(String, String)} 首写胜，
 * 会话已冻结（已登记 projectRoot）时 rebind 不覆盖；{@link #clearSession(String)} 解除冻结后可再绑定。
 *
 * <p><b>生产接线</b>（IMP-B 闭环 ODF-A1 §8 阻塞项）：ProjectSessionBindingService.bind() →
 * {@link #setForSession(String, String)}（session.mainProjectId → ProjectRecord.path）；
 * unbind() → {@link #clearSession(String)}。
 */
public final class SessionProjectRoot {

    private static final Logger log = LoggerFactory.getLogger(SessionProjectRoot.class);

    /** sessionId → projectRoot（会话绑定登记 · CC state.ts:269-279 启动冻结语义）。 */
    private static final ConcurrentHashMap<String, String> BY_SESSION = new ConcurrentHashMap<>();

    /** ODF-A1 无会话回落源 env（同 AutoMemPaths.CLAUDE_PROJECT_DIR_ENV · 避免跨层依赖仅取常量；
     *  nexusai 命名 NEXUSAI_PROJECT_DIR，决策 D1/D6 自有根语义）。 */
    private static final String CLAUDE_PROJECT_DIR_ENV = "NEXUSAI_PROJECT_DIR";

    /** 当前线程显式注入的 projectRoot（对齐 CC cwd 概念 · session 处理线程入口设置/清除）。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionProjectRoot() {}

    /**
     * 解析当前线程对应的会话级 projectRoot。
     *
     * @return 当前注入 → session 绑定 → CLAUDE_PROJECT_DIR env ?? config home（恒非 null）
     */
    public static String resolve() {
        String cur = CURRENT.get();
        if (cur != null && !cur.isEmpty()) {
            return cur;
        }
        String sessionId = RequestContext.sessionId();
        if (sessionId != null) {
            String bound = BY_SESSION.get(sessionId);
            if (bound != null && !bound.isEmpty()) {
                return bound;
            }
        }
        String env = System.getenv(CLAUDE_PROJECT_DIR_ENV);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return NexusaiPaths.getAppConfigHomeDir();
    }

    /**
     * 会话绑定 projectRoot · 首写胜（对齐 CC stable identity · OPD-SPR-03）：会话已冻结时不覆盖，
     * rebind 尝试仅记 debug；clearSession 解除冻结后可再绑定。
     */
    public static void setForSession(String sessionId, String projectRoot) {
        if (sessionId == null || projectRoot == null || projectRoot.isEmpty()) {
            return;
        }
        // [2026-08-24 cwd 污染修复] 绑定路径校验：绝对路径 + 目录存在，无效拒绝（不绑定污染 cwd——
        //   否则 CwdResolution 返回无效路径致工具全失败）
        if (!isValidProjectRoot(projectRoot)) {
            log.warn("[SessionProjectRoot] 拒绝绑定无效项目根（需绝对路径且目录存在）: sessionId={} "
                + "projectRoot={}", sessionId, projectRoot);
            return;
        }
        String prev = BY_SESSION.putIfAbsent(sessionId, projectRoot);
        if (prev == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionProjectRoot] 会话绑定 projectRoot: sessionId={} projectRoot={}", sessionId, projectRoot);
            }
        } else if (log.isDebugEnabled() && !prev.equals(projectRoot)) {
            log.debug("[SessionProjectRoot] 会话已冻结不覆盖（CC stable identity · OPD-SPR-03）: "
                + "sessionId={} frozen={} attempt={}", sessionId, prev, projectRoot);
        }
    }

    /** 绑定项目根有效性校验 · [2026-08-24 cwd 污染修复] 绝对路径 + 目录存在；相对/不存在路径
     *  （如「抓包流程」）拒绝绑定，防 CwdResolution 返回无效 cwd 致工具全失败。 */
    private static boolean isValidProjectRoot(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(projectRoot);
            return p.isAbsolute() && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取会话绑定 projectRoot（null = 未冻结 · OPD-SPR-03 允许未冻结查询）。 */
    public static String getForSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return BY_SESSION.get(sessionId);
    }

    /** 清除会话绑定（会话销毁/解绑项目时调用）。 */
    public static void clearSession(String sessionId) {
        if (sessionId != null) {
            BY_SESSION.remove(sessionId);
        }
    }

    /** 当前线程显式注入 projectRoot（null 等价清除）。 */
    public static void setCurrent(String projectRoot) {
        if (projectRoot == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(projectRoot);
        }
    }

    /** 清除当前线程注入（会话处理结束 finally）。 */
    public static void clearCurrent() {
        CURRENT.remove();
    }

    /** 测试钩子：清空全部登记（含 current）。 */
    public static void reset() {
        CURRENT.remove();
        BY_SESSION.clear();
    }
}
