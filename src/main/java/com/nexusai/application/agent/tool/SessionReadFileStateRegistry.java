package com.nexusai.application.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>[批 edit-gate-session-scope · B] 会话级 readFileState 注册表</b>（进程内静态表 · 键 = sessionId）。
 *
 * <h2>本类修复的缺陷（只覆盖「作用域」这一维）</h2>
 * <pre>
 * 改造前：LlmAgentLoop.buildBaseToolUseContext 传 readFileState=null
 *   → ToolUseContext 构造器每次 run 新建一张 FileStateCache（run 级）
 *   → 同一个会话的两条消息（两个 run）互不可见对方 Read 过的文件
 *   ⇒ 用户在上一轮 Read 过、本轮直接 Edit：门禁报 errorCode 6/2/9
 *      「File has not been read yet.」
 * </pre>
 * CC 两版都是<b>会话级</b>：2.1.88 {@code REPL.tsx:1955} {@code useRef}（活到会话结束）、
 * 2.1.278 发行产物 {@code class Moe{readFileState; this.readFileState=VE(LC)}}（VE=createFileStateCache）。
 * 本类把 readFileState 的作用域从「run」提到「session」，经
 * {@link ToolUseContext#createFileStateCache()} 保持同一双限 LRU 口径。
 *
 * <h2>骨架对齐本仓先例</h2>
 * 逐方法对齐 {@code com.nexusai.application.agent.attachment.SessionChangedFilesBaselineRegistry}
 * （{@code private static final Map<String, FileStateCache>} + forSession/peek/evict/size/resetForTest
 * + 同一个 {@code SessionService#delete} evict 接线点）。<b>它是本仓既有的「同形先例」</b>，
 * 语义不同（它是变更检测基线，本类是 dedup 缓存本体）故不合并。
 *
 * <h2>为什么静态表能跨 run 生效</h2>
 * {@code LlmAgentLoop} 是 {@code @Scope("prototype")}（{@code LlmAgentLoop.java:199-200}），
 * {@code ChatService.java:906} 每条 send 走 {@code loopProvider.getObject()} ⇒ 每条消息一个新
 * loop 实例、run 内对象全部随之丢弃。<b>跨 run 复用只能靠进程内静态表</b>（与
 * {@code SessionChangedFilesBaselineRegistry} 同一理由）。故本表按 sessionId 键控
 * ⇒ 「跨 run 生效」与「这个会话热不热」无关。
 *
 * <h2>⛔ 覆盖边界（如实登记，不当成交付了 CC 的 resume 能力）</h2>
 * 静态表只能覆盖<b>同一 JVM 内</b>的「跨 run / resume」。CC 2.1.278 另有一条
 * <b>跨进程</b>恢复通路：resume 时从会话消息历史重放 Read 调用重建缓存
 * （发行产物 {@code restoreReadFileState(h,v,...)} → {@code mergeReadFileStateFrom} →
 * {@code s6r}(=mergeFileStateCaches) + {@code aHt}(遍历 messages 筛 tool_use)）。
 * <b>本类不实现该通路</b>：后端 JVM 重启后本表为空，缓存不恢复。
 * 这是已登记的能力缺口，属于独立批次（不在「作用域」这一维内）。
 *
 * <h2>local-only 红线</h2>
 * 纯内存进程内状态，只承载「路径 → 已读字节快照」；<b>绝不</b>序列化 / 绝不经 STOMP /
 * WebSocket / EventPublisher / outbound DTO 外发（与 {@code FileStateCache} 同款约束）。
 *
 * <h2>无会话标识（sessionId 为 null/空白）</h2>
 * ⛔ <b>不得</b>落到共享键（会把所有无会话调用方串成一桶）。
 * {@link #forSession(String)} 返回 {@code null} ⇒ {@link ToolUseContext} 构造器的
 * {@code readFileState == null} 兜底分支接管（每 ctx 新建）= 改造前行为。
 * 这是诚实的降级，不是静默跳过。
 */
public final class SessionReadFileStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionReadFileStateRegistry.class);

    /** sessionId → 该会话的 readFileState（一个会话恒一份，跨 run 不销毁）。 */
    private static final Map<String, FileStateCache> CACHES = new ConcurrentHashMap<>();

    private SessionReadFileStateRegistry() {
    }

    /**
     * 取（必要时首建）该会话的 readFileState · 同 sessionId 恒返回同一实例（跨 run 不销毁）。
     *
     * <p>容量与 {@link ToolUseContext#createFileStateCache()} 同口径（5000 条 + 25MB 双限真 LRU，
     * 对齐目标 CC 2.1.278: {@code LC=5000} / {@code T=26214400}）—— 会话化只改<b>作用域</b>，不改容量。
     *
     * @param sessionId 会话 id（short 形态 sess-xxx）
     * @return 该会话的 readFileState；{@code null}/空白会话 id ⇒ {@code null}（调用方退回每 ctx 新建）
     */
    public static FileStateCache forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // ⛔ 无会话不共享键：返回 null（构造器兜底新建 ⇒ 与改造前同行为）。
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·会话级] 无会话标识（null/空白）⇒ 不做会话化，"
                    + "由 ToolUseContext 构造器每次新建（改造前行为）");
            }
            return null;
        }
        FileStateCache cache = CACHES.computeIfAbsent(sessionId,
            id -> ToolUseContext.createFileStateCache());
        if (log.isDebugEnabled()) {
            log.debug("[readFileState·会话级] 会话缓存命中（跨 run 复用同一张表）: sessionId={} 现有 {} 条",
                sessionId, cache.size());
        }
        return cache;
    }

    /**
     * 取该会话的 readFileState（<b>已存在才有</b>，⛔ 不创建）· 同一性守卫专用
     * （否则为「查一个还没跑过的会话」凭空建表 = 泄漏）。
     *
     * <p>唯一生产调用点：{@code SubagentExecutor} 子代理 cleanup 阶段清空自己的
     * readFileState 之前，先确认「我拿到的这张」不是会话表本身（若是 ⇒ fail-loud 跳过）。
     *
     * @param sessionId 会话 id（null/空白 ⇒ null）
     * @return 已注册的 readFileState；未注册 ⇒ null
     */
    public static FileStateCache peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return CACHES.get(sessionId);
    }

    /**
     * 会话终结 → 移除该会话的 readFileState（CC 一进程一会话、进程退出即释放；
     * 本仓常驻 JVM 必须显式回收，否则随「会话数」无界累积）。
     *
     * <p>接线点：{@code SessionService#delete}（与 {@code SessionChangedFilesBaselineRegistry.evict} /
     * {@code SessionPromptCacheRegistry.evict} 同一街区）。null/空白/未知会话 → no-op
     * （不抛、不阻塞删除主流程）。
     *
     * @param sessionId 会话 id
     */
    public static void evict(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        FileStateCache removed = CACHES.remove(sessionId);
        if (removed != null) {
            log.info("[readFileState·会话级] 会话终结 → 会话级 readFileState 已移除: sessionId={} 原有 {} 条",
                sessionId, removed.size());
        } else if (log.isDebugEnabled()) {
            log.debug("[readFileState·会话级] evict 未命中（该会话未建过 readFileState）: sessionId={}", sessionId);
        }
    }

    /** 当前注册的会话数（测试 / 审计用）。 */
    public static int size() {
        return CACHES.size();
    }

    /**
     * 清空全部会话 readFileState（<b>仅测试用</b>）· 使静态表在用例之间归零
     * （对齐 {@code SessionChangedFilesBaselineRegistry.resetForTest} 先例）。
     */
    public static void resetForTest() {
        List<String> ids = new ArrayList<>(CACHES.keySet());
        for (String id : ids) {
            evict(id);
        }
        if (log.isDebugEnabled()) {
            log.debug("[readFileState·会话级] resetForTest: 已清空 {} 个会话 readFileState", ids.size());
        }
    }
}
