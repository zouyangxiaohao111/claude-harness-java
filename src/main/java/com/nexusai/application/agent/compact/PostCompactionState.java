package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 压缩后标记状态 · 对齐 CC bootstrap/state.ts:256/769-781 的 {@code STATE.pendingPostCompaction}.
 *
 * <p>CC 语义（bootstrap/state.ts，grep -n 自验）:
 * <pre>
 * // Set to true after compaction (auto or manual /compact). Consumed by
 * // logAPISuccess to tag the first post-compaction API call so we can
 * // distinguish compaction-induced cache misses from TTL expiry.
 * pendingPostCompaction: boolean          // :256
 * export function markPostCompaction() { STATE.pendingPostCompaction = true }   // :771
 * export function consumePostCompaction() {                                    // :777
 *   const was = STATE.pendingPostCompaction
 *   STATE.pendingPostCompaction = false
 *   return was
 * }
 * </pre>
 *
 * <p><b>L1 行为</b>: 每次压缩成功路径（auto 全量 / SM / partial / /compact）必须调用
 * {@link #markPostCompaction(String)}（INV-8）；下个 API success 事件消费一次
 * {@link #consumePostCompaction(String)} 返回 {@code isPostCompaction=true} 后自动复位，
 * 让遥测区分"压缩导致的 cache miss"与"TTL 过期导致的 cache miss"（CC logging.ts:452/573）。
 *
 * <h2>方案 1b：会话级布尔（挂 AgentState） vs CC 进程级单布尔</h2>
 * <p><b>CC</b> 是<b>进程级单布尔</b>（{@code getInitialState()} state.ts:422 初始化
 * {@code pendingPostCompaction: false}；单 REPL 进程 = 单会话，跨命令边界存活）。
 * <p><b>Java（方案 1b）</b> 是<b>会话级布尔</b>：布尔挂到每个会话的主
 * {@link AgentState#pendingPostCompaction()}，经 {@link SessionAgentStateRegistry}
 * （{@link LlmAgentLoop} 主会话入口注册，LlmAgentLoop.java:1550-1561）按 sessionId 读写。
 * <b>等价性依据</b>：CC 进程边界 = 会话边界（每进程恰一个 REPL 会话），Java 单 JVM 多会话
 * 按会话隔离即等价"每个会话一个 CC 进程"。选择理由：避免会话 A 压缩导致会话 B 的下个
 * API success 误报 {@code isPostCompaction=true}（旧 ConcurrentHashMap 的按会话隔离语义
 * 保留，但状态宿主从静态 Map 迁到 AgentState —— 对齐 CC 把状态放 State 对象而非模块级 Map）。
 *
 * <h2>resolve 优先级（sessionId → AgentState）</h2>
 * <ol>
 *   <li><b>归一化解析</b>：sessionId（合规 UUID 直解 / 原始 {@code "sess-xxx"} 8 位拼接 /
 *       hash 兜底）经 {@link ChatService#parseSessionUuid} 归一化成<b>与 mark 侧一致的解析 UUID</b>
 *       → {@link SessionAgentStateRegistry#get} 命中注册会话则读写该 AgentState 的
 *       {@code pendingPostCompaction}（会话级）。生产 sessionId 是 {@code "sess-xxxxxxxx"} 格式
 *       （SessionService.generateId 非合法 UUID），mark 侧（LlmAgentLoop:1550 创建
 *       AgentState 时 ChatService:199 已归一化为解析 UUID 并注册 LlmAgentLoop:1557）写解析 UUID key，
 *       consume 侧 MDC 拿到原始 {@code "sess-xxx"} —— 归一化使两侧命中同一注册 AgentState
 *       （修正 0.2.35 首次实施"consume 读进程级默认布尔"的主路径接线断裂）。</li>
 *   <li>sessionId 为 null / blank / registry 未接线（@Component 缺失或 {@code required=false}
 *       注入 null）→ 回落<b>进程级单布尔</b> {@link #defaultPendingPostCompaction}
 *       （CC STATE 等价，日志显式标注每次回落）。归一化后未注册会话（测试 "s1" / "session-1"
 *       hash 兜底 UUID 未注册）与无 MDC 场景均走此回落路径。</li>
 * </ol>
 *
 * <p><b>消费侧 key 优先级</b>（AnthropicSdkProvider.consumePostCompactionAtApiSuccess）：
 * history 有 sessionId 优先 → 否则 {@code RequestContext.sessionId()}（MDC，ChatService 已设
 * {@code "sess-xxx"} 原始串）→ 仍无则 null → 本类回落进程级单布尔。
 *
 * <p>⚠ <b>mark 侧 key 均为解析后 UUID 串</b>（grep 自验）：ToolRegistrationConfig:1232
 * {@code state.sessionId()}（manual /compact）、CompactConversation:377
 * {@code ctx.setSessionId(tuc.sessionId())}（tuc.sessionId=state.sessionId，
 * LlmAgentLoop:3961）→ AutoCompactor:527 effSessionId 与 CompactConversation:305 /
 * PartialCompactConversation:326。consume 侧原始 {@code "sess-xxx"} 经归一化命中同一解析 UUID。
 *
 * <p>⚠ <b>mark 站点</b>（grep -rn 自验 CC）：compact.ts:73（manual /compact）、
 * autoCompact.ts:305（SM auto）、compact.ts:704（compactConversation 全量）、
 * compact.ts:1053（partial）—— mark 均在压缩摘要 API 调用之后，故压缩自身摘要调用不会
 * 抢先消费。Java mark 站点顺序一致（CompactCommand:224 / AutoCompactor:573 /
 * CompactConversation:305 / PartialCompactConversation:326 均在摘要之后）。
 *
 * @see com.nexusai.application.agent.lsp.PromptCacheBreakDetection#notifyCompaction(String, String)
 */
@Component
public final class PostCompactionState {

    private static final Logger log = LoggerFactory.getLogger(PostCompactionState.class);

    /** 会话 AgentState 注册表宿主 · 镜像 PostCompactCleanup.java:84-120 的
     *  {@code @Component + @Autowired(required=false) + 静态 holder} 模式。 */
    private static volatile SessionAgentStateRegistry STATIC_SESSION_REGISTRY;

    /**
     * 进程级回落单布尔 · CC {@code STATE.pendingPostCompaction} 等价（sessionId 无法解析时用）。
     *
     * <p>多个无法解析会话的消费共享（CC 单进程语义等价）；并发未解析会话可能串扰
     * isPostCompaction 归因 —— 每次回落使用均以中文日志显式标注，不静默掩盖。
     */
    private static volatile boolean defaultPendingPostCompaction = false;

    /**
     * Spring 装配入口 · 把会话注册表 bean 写入静态字段，供静态入口
     * {@link #markPostCompaction(String)} / {@link #consumePostCompaction(String)} 解析会话级
     * AgentState。
     *
     * <p>{@code required=false}：单测/无 bean 上下文下允许 null（回落进程级单布尔）。
     */
    public PostCompactionState(@Autowired(required = false) SessionAgentStateRegistry sessionAgentStateRegistry) {
        STATIC_SESSION_REGISTRY = sessionAgentStateRegistry;
        log.info("[PostCompactionState] SessionAgentStateRegistry 装配: {}",
            sessionAgentStateRegistry != null ? "已注入" : "null（回落进程级单布尔）");
    }

    /**
     * 注入/复位会话注册表（幂等）· 镜像 CompactConversation.java:84-88 静态注入面。
     *
     * <p>AutoCompactor 经 @Autowired 在 autoCompactIfNeeded 调用压缩前写入；测试可经此
     * 显式注入 / 传 null 复位。null → 所有调用回落进程级单布尔。
     *
     * @param registry 会话 AgentState 注册表（null → 会话级解析关闭，回落默认布尔）
     */
    public static void setSessionAgentStateRegistry(SessionAgentStateRegistry registry) {
        STATIC_SESSION_REGISTRY = registry;
        log.info("[PostCompactionState] SessionAgentStateRegistry 注入: {}",
            registry != null ? "已注入" : "null（回落进程级单布尔）");
    }

    /**
     * 按 sessionId 解析会话级 AgentState · short 直键 registry 查找。
     *
     * <p>[session-id-short] mark 侧（LlmAgentLoop）与 consume 侧（MDC）sessionId 已统一 short
     * （sess-xxx），registry 键同 short → 直键命中（原 parseSessionUuid 归一化的格式错位根因消除）。
     *
     * <p>回落路径（返回 null → 调用方走进程级单布尔，中文日志显式标注）：
     * <ol>
     *   <li>registry 未接线（@Autowired(required=false) 注入 null / 测试未 setSessionAgentStateRegistry）</li>
     *   <li>sessionId 为 null / blank</li>
     *   <li>short 直键未注册（测试 "s1" / "session-1" 不在 registry）</li>
     * </ol>
     */
    private static AgentState resolveAgentState(String sessionId) {
        SessionAgentStateRegistry registry = STATIC_SESSION_REGISTRY;
        if (registry == null) {
            log.info("[PostCompactionState] resolveAgentState 回落进程级单布尔: "
                    + "SessionAgentStateRegistry 未接线（registry=null）· sessionId={}",
                sessionId);
            return null;
        }
        if (sessionId == null || sessionId.isBlank()) {
            log.info("[PostCompactionState] resolveAgentState 回落进程级单布尔: "
                    + "sessionId 为 null/blank（无 MDC 或 history 无 sessionId）· 返回 null",
                new Object[0]);
            return null;
        }
        AgentState state = registry.get(sessionId);
        if (state == null) {
            log.info("[PostCompactionState] resolveAgentState 回落进程级单布尔: "
                    + "sessionId={} 直键未注册会话 · 返回 null", sessionId);
            return null;
        }
        return state;
    }

    /**
     * 标记压缩已发生 · 对齐 CC bootstrap/state.ts:771 markPostCompaction.
     *
     * <p>下个 API success 事件 {@link #consumePostCompaction(String)} 将返回
     * {@code isPostCompaction=true}，然后自动复位。
     *
     * @param sessionId 会话 ID（null / blank / registry 未接线 / 归一化后未注册 →
     *                  回落进程级单布尔；任一格式经 ChatService.parseSessionUuid 归一化命中注册会话）
     */
    public static void markPostCompaction(String sessionId) {
        AgentState state = resolveAgentState(sessionId);
        if (state != null) {
            state.setPendingPostCompaction(true);
            log.info("[PostCompactionState] markPostCompaction: sessionId={} "
                    + "pendingPostCompaction=true（会话级 AgentState）· 下个 API success 事件带 "
                    + "isPostCompaction=true · CC bootstrap/state.ts:771",
                sessionId);
            return;
        }
        defaultPendingPostCompaction = true;
        log.info("[PostCompactionState] markPostCompaction: sessionId={} "
                + "pendingPostCompaction=true（回落进程级单布尔 · sessionId 无法解析）· CC bootstrap/state.ts:771",
            sessionId);
    }

    /**
     * 消费压缩后标记 · 对齐 CC bootstrap/state.ts:777-781 consumePostCompaction.
     *
     * <p>压缩后返回 true 一次，然后自动复位（下个 API success 事件带 isPostCompaction=true
     * 后复位）。直到下次压缩前恒为 false。
     *
     * @param sessionId 会话 ID（null / blank / registry 未接线 / 归一化后未注册 →
     *                  回落进程级单布尔；任一格式经 ChatService.parseSessionUuid 归一化命中注册会话）
     * @return was —— 是否为压缩后首个 API success 事件（isPostCompaction）
     */
    public static boolean consumePostCompaction(String sessionId) {
        AgentState state = resolveAgentState(sessionId);
        if (state != null) {
            boolean was = state.pendingPostCompaction();
            state.setPendingPostCompaction(false);
            if (was && log.isInfoEnabled()) {
                log.info("[PostCompactionState] consumePostCompaction: sessionId={} "
                        + "isPostCompaction=true（会话级 AgentState）· 压缩后首个 API success 事件元数据"
                        + " · CC bootstrap/state.ts:777",
                    sessionId);
            }
            return was;
        }
        boolean was = defaultPendingPostCompaction;
        defaultPendingPostCompaction = false;
        if (was && log.isInfoEnabled()) {
            log.info("[PostCompactionState] consumePostCompaction: sessionId={} "
                    + "isPostCompaction=true（回落进程级单布尔 · sessionId 无法解析）· 压缩后首个 "
                    + "API success 事件元数据 · CC bootstrap/state.ts:777",
                sessionId);
        }
        return was;
    }

    /**
     * 查询当前是否 pending（测试 / 诊断用）。
     */
    public static boolean isPostCompactionPending(String sessionId) {
        AgentState state = resolveAgentState(sessionId);
        if (state != null) {
            return state.pendingPostCompaction();
        }
        return defaultPendingPostCompaction;
    }

    /**
     * 清理单个会话的标记（会话结束 / 测试）。
     */
    public static void clear(String sessionId) {
        AgentState state = resolveAgentState(sessionId);
        if (state != null) {
            state.setPendingPostCompaction(false);
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactionState] clear: sessionId={} 会话级布尔已复位", sessionId);
            }
            return;
        }
        defaultPendingPostCompaction = false;
    }

    /**
     * 复位进程级回落单布尔（测试用）· 对齐 CC resetSessionMemoryState 的测试清理模式。
     *
     * <p>仅复位回落布尔；已注册 AgentState 上的布尔按会话经 {@link #clear(String)} 复位
     * （registry 无遍历接口，测试用 @AfterEach 逐个 clear）。
     */
    public static void reset() {
        defaultPendingPostCompaction = false;
    }
}
