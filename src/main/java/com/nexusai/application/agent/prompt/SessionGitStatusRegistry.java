package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 {@link GitStatusProvider} 注册表 · 会话内共享一次 git status 快照（对齐 CC
 * context.ts:97 原话「会话开始一次快照、会话内不更新」）。
 *
 * <p><b>WHY（cache-hit-fix B 批）</b>：CC git status = 进程级 memoize（context.ts:36-111），
 * 一次会话内始终同一块；Java 端 {@code LlmAgentLoop.loop()}（:4094）每 run new
 * GitStatusProvider（prototype per-run）→ 每次用户消息重抓 git status → system prompt 尾字节
 * 变化 → 破坏 deepseek 单前缀缓存（命中率损失）。本注册表按 sessionId 缓存一个
 * {@link GitStatusProvider}（内部 {@code getGitStatus} 实例级 memoize，会话内只算一次），
 * 由 {@code LlmAgentLoop.doRun} 建 mainCtx 后注入 sessionState，loop() 跨 run 复用同一实例，
 * system 尾字节稳定 → 前缀缓存保持命中。
 *
 * <p><b>防无界增长</b>：{@link #evict(String)} 供会话终止路径接线（SessionService.delete /
 * CommandController /clear，仿 {@code SessionAgentStateRegistry.remove} 先例）——/clear 或
 * 会话删除后释放该会话快照，下轮重新快照（等价 CC reset 后新 turn 语义）。
 *
 * <p><b>并发</b>：ConcurrentHashMap —— 多会话并行 turn（每会话独立 key）无锁并发安全。
 *
 * <p><b>local-only 红线</b>：纯内存进程内注册表，绝不序列化 / 绝不经 STOMP / WebSocket /
 * outbound DTO 外发（同 SessionAgentStateRegistry local-only 约束）。
 */
@Component
public class SessionGitStatusRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionGitStatusRegistry.class);

    /** sessionId（short 形态 sess-xxx）→ 会话级 GitStatusProvider。 */
    private final ConcurrentHashMap<String, GitStatusProvider> providers = new ConcurrentHashMap<>();

    /**
     * 取（或懒建）会话级 GitStatusProvider · 同 sessionId 恒返回同一实例。
     *
     * <p>{@code computeIfAbsent} 原子单飞：并发首触只建一次。无参构造 cwd 走
     * {@code CwdResolution.getCwd()}（绑定项目/worktree 场景取对仓库，对齐 CC findGitRoot(getCwd())）。
     *
     * @param sessionId 会话 ID（short 形态 sess-xxx）
     * @return 会话级 provider；null/blank sessionId → null（调用方回落每 run new）
     */
    public GitStatusProvider getForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return providers.computeIfAbsent(sessionId, id -> new GitStatusProvider());
    }

    /**
     * 释放会话级 git status 快照 · 会话终止 / /clear 时接线，防 per-session 内存累积。
     *
     * <p>移除后同 sessionId 再次 {@link #getForSession(String)} 懒建新实例（重新快照，
     * 等价 CC reset 后新 turn）。null/未知会话 no-op。
     */
    public void evict(String sessionId) {
        if (sessionId != null) {
            GitStatusProvider removed = providers.remove(sessionId);
            if (removed != null && log.isDebugEnabled()) {
                log.debug("[SessionGitStatusRegistry] 释放会话 git status 快照: sessionId={}", sessionId);
            }
        }
    }

    /** 当前缓存会话数（测试 / 审计用）。 */
    public int size() {
        return providers.size();
    }
}
