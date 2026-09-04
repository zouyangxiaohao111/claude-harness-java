package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AbortController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 压缩进度 STOMP 推送 + 会话可中断状态（2026-09-04 · 对齐 CC REPL spinner + CC Esc 打断）。
 *
 * <p>CC 前端真源（claude-code-best REPL.tsx:3003-3032）把 {@code onCompactProgress} 事件映射为
 * 底部 spinner：{@code hooks_start}（pre/post/session hook 前）变色+文案，{@code compact_start}
 * 显示 {@code "Compacting conversation"}，{@code compact_end} 清空。事件契约 CC
 * {@code Tool.ts:141-156 CompactProgressEvent} union。Java {@link CompactProgressEvent} 已全链
 * emit（单流程恰 5 事件）。本类承担「把进度事件推出当前线程到 STOMP + 让压缩可被会话级打断」：
 *
 * <ol>
 *   <li><b>进度推送</b>：{@link #register}（manual handleCompactCommand / auto LlmAgentLoop
 *       压缩期间）→ {@link CompactConversationContext#getOnCompactProgress()} 未显式设时委托本
 *       注册表 → 推前端 topic。{@link #toFrontendJson} 对齐 CC union + Java 扩展
 *       {@code {type:'compact_progress', chars}}（摘要流式真进度，前端进度条蠕动源）。</li>
 *   <li><b>可中断（CC Esc）</b>：manual /compact 摘要段耗时最长且 CC 中可 Esc 打断。压缩线程
 *       {@link #registerAbort}（当前压缩 AbortController，StreamCompactSummary abort 源经
 *       ToolRegistrationConfig abortControllerSupplier 取）→ {@link #registerSessionAbort}
 *       （会话级登记，供跨线程前端 cancel abort）；{@link #abortForSession} 由 cancelSession
 *       （前端停止/Esc → POST /api/v1/sessions/{sid}/cancel）调用 → abort 会话在飞压缩 →
 *       摘要 provider 硬断流 → 压缩 catch 返回 "Compaction canceled."（对齐 CC compact.ts:126）。</li>
 * </ol>
 *
 * <p>存储：ThreadLocal 承载当前线程（进度推送 + 当前压缩 abort）；会话级 abort 用
 * {@link ConcurrentHashMap}（跨线程前端 cancel 需要）。无注册 → no-op（测试/非 STOMP 路径
 * 行为不回归）。
 */
public final class CompactProgressState {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** STOMP topic 前缀 · 与 token-warning 同构 {@code /topic/sessions/{sid}/...} */
    public static final String TOPIC_PREFIX = "/topic/sessions/";
    /** STOMP topic 后缀 · 前端订阅点（收到 compact_start 转圈 / compact_progress 走条 / compact_end 收起）。 */
    public static final String TOPIC_SUFFIX = "/compact-progress";

    /** 当前线程压缩进度推送上下文 · 对齐 CompactWarningState ThreadLocal 会话隔离模式。 */
    private static final ThreadLocal<Consumer<CompactProgressEvent>> push = new ThreadLocal<>();
    /** 当前压缩 AbortController（摘要中断源）· 压缩线程 register，StreamCompactSummary abort 取。 */
    private static final ThreadLocal<AbortController> currentAbort = new ThreadLocal<>();
    /** 会话级在飞压缩 AbortController · 跨线程（前端 cancel）abort 用。 */
    private static final ConcurrentMap<String, AbortController> sessionAborts = new ConcurrentHashMap<>();

    private CompactProgressState() { /* 工具类不可实例化 */ }

    /** 注册当前线程进度推送（压缩开始前；finally {@link #clear}，ThreadLocal 防串台）。 */
    public static void register(Consumer<CompactProgressEvent> consumer) {
        push.set(consumer);
    }

    /** 清除当前线程进度推送（压缩结束 finally；幂等）。 */
    public static void clear() {
        push.remove();
    }

    /** 当前线程进度推送；无注册 → null（调用方回落 no-op）。 */
    public static Consumer<CompactProgressEvent> current() {
        return push.get();
    }

    /** 注册当前压缩 AbortController（摘要中断源）· 压缩线程；finally {@link #clearAbort}。 */
    public static void registerAbort(AbortController abortController) {
        currentAbort.set(abortController);
    }

    /** 清除当前压缩 AbortController（幂等）。 */
    public static void clearAbort() {
        currentAbort.remove();
    }

    /** 当前压缩 AbortController；无 → null（StreamCompactSummary 回落 NOOP）。 */
    public static AbortController currentAbort() {
        return currentAbort.get();
    }

    /** 会话级登记在飞压缩 AbortController · 供前端 cancel（跨线程 abort）。 */
    public static void registerSessionAbort(String sessionId, AbortController abortController) {
        if (sessionId != null && abortController != null) {
            sessionAborts.put(sessionId, abortController);
        }
    }

    /** 移除会话在飞压缩 AbortController（压缩 finally；幂等）。 */
    public static void removeSessionAbort(String sessionId) {
        if (sessionId != null) {
            sessionAborts.remove(sessionId);
        }
    }

    /**
     * 会话级 abort 在飞压缩 · 由 cancelSession（前端停止键/Esc → POST /sessions/{id}/cancel）
     * 调用。压缩中 → abort('user_cancel')（StreamCompactSummary provider 硬断流）；
     * 无在飞压缩 → false（cancelSession 仅处理 AgentState，行为不回归）。
     *
     * @return true 实际 abort 了在飞压缩
     */
    public static boolean abortForSession(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        AbortController ac = sessionAborts.get(sessionId);
        if (ac != null && !ac.isCancelled()) {
            ac.abort("user_cancel");
            return true;
        }
        return false;
    }

    /** 会话压缩进度 topic · {@code /topic/sessions/{sessionId}/compact-progress}。 */
    public static String topic(String sessionId) {
        return TOPIC_PREFIX + sessionId + TOPIC_SUFFIX;
    }

    /**
     * 事件 → 前端契约 JSON · 对齐 CC {@code CompactProgressEvent} union 载荷：
     * {@code {type:'hooks_start', hookType:'pre_compact'|'post_compact'|'session_start'}} /
     * {@code {type:'compact_start'}} / {@code {type:'compact_end'}}；Java 扩展
     * {@code {type:'compact_progress', chars}}（摘要流式真进度）。未知子类型 → 空对象（不推坏 JSON）。
     */
    public static ObjectNode toFrontendJson(CompactProgressEvent event) {
        ObjectNode node = JSON.createObjectNode();
        if (event instanceof CompactProgressEvent.HooksStart hooks) {
            node.put("type", "hooks_start");
            String hookType = switch (hooks.hookType()) {
                case PRE_COMPACT -> "pre_compact";
                case POST_COMPACT -> "post_compact";
                case SESSION_START -> "session_start";
            };
            node.put("hookType", hookType);
        } else if (event instanceof CompactProgressEvent.CompactStart) {
            node.put("type", "compact_start");
        } else if (event instanceof CompactProgressEvent.CompactEnd) {
            node.put("type", "compact_end");
        } else if (event instanceof CompactProgressEvent.SummaryProgress sp) {
            // Java 扩展事件（非 CC union）：摘要流式已收字符 → 前端真进度条蠕动源
            node.put("type", "compact_progress");
            node.put("chars", sp.chars());
        }
        return node;
    }
}
