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
 * <p><b>防无界增长</b>：{@link #evict(String)} 已接在会话终止路径（
 * {@code SessionService#delete} 会话删除 / {@code CommandController} /clear）——/clear 或会话
 * 删除后释放该会话快照，下轮重新快照（等价 CC reset 后新 turn 语义）。
 *
 * <p>与 {@code SessionAgentStateRegistry.remove(String)} 的区别：本表存的是<b>可重建</b>的
 * git status 快照，/clear 与删除都清（clear 后重新快照）；而后者持有不可重建的会话 STATE，
 * /clear 不清、仅会话删除时移除（CC clearConversation 只清 caches，进程与 STATE 继续）。
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
     * <p>{@code computeIfAbsent} 原子单飞：并发首触只建一次（⇒ <b>首个调用方传的
     * {@code sessionCwd} 胜</b>；同会话后续传入不同值不生效，这是「会话开始一次快照、会话内不更新」
     * 的应有语义，对齐 CC context.ts:97）。
     *
     * <p><b>[r10b · 裁定 ③ · 裁定 #10 第二域] 本方法不再自调 {@code CwdResolution}</b>：
     * 调用方<b>手里已有 sessionId</b>（{@code LlmAgentLoop.doRun} 的回合边界）却让本缓存类反查会话态
     * = 反查站点 ⇒ 按裁定 #10 口径改为<b>边界解析 ⇒ 形参下传</b>（同 D10 的槽形态）。本类退化为
     * <b>纯会话级缓存</b>（key = sessionId，值 = 显式 cwd 构建的 provider）。
     *
     * <p>⚠️ <b>{@code sessionCwd} 必须是 {@code getCwd} 语义</b>（{@code CwdResolution#getCwd}
     * 的返回值：L1 = sessionCwd 层，<b>bash {@code cd} 可覆盖</b>），理由是 CC 的 git 判定正是
     * {@code findGitRoot(getCwd())}（git.ts:218-222）。
     * <p>⛔ <b>不得用 {@code LlmAgentLoop.workspaceDir} / {@code runExplicitCwd} 顶替</b>：
     * 那两处是 <b>boundProject / originalCwd 语义</b>（启动锚，不被 {@code cd} 覆盖）。
     * 在发生过 {@code cd} 的会话里两者必然不同值 ⇒ 顶替会让 git 快照锚到 cd 前的目录。
     * 该不可互换性有实测装置：{@code PromptSessionSlotsTest#p0_divergedFixture_*}（DIVERGED 夹具）。
     *
     * @param sessionId   会话 ID（short 形态 sess-xxx）
     * @param sessionCwd  会话当前工作目录（{@code CwdResolution.getCwd(sessionId)} 的返回值，非 null）
     * @return 会话级 provider；null/blank sessionId → null（调用方回落每 run new）
     */
    public GitStatusProvider getForSession(String sessionId, java.nio.file.Path sessionCwd) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return providers.computeIfAbsent(sessionId, id -> new GitStatusProvider(sessionCwd));
    }

    /**
     * 释放会话级 git status 快照 · 会话终止 / /clear 时接线，防 per-session 内存累积。
     *
     * <p>移除后同 sessionId 再次 {@link #getForSession(String, java.nio.file.Path)} 懒建新实例
     * （重新快照，等价 CC reset 后新 turn）。null/未知会话 no-op。
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
