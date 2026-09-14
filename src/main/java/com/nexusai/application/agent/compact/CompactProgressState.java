package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AbortController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer; // [批 5a] 仅 javadoc 引用（载体已删）

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
 *   <li><b>进度推送</b>：sink 由调用方经 {@code CompactConversationContext.setOnCompactProgress}
 *       显式装箱（{@code buildAutoContext} 从 ToolUseContext 透传；manual handleCompactCommand /
 *       auto LlmAgentLoop 压缩期间）—— [批 5a] 本类原 {@code register} 静态注册表已删，
 *       不再有「未显式设时委托本注册表」的回落。本类只提供 {@link #topic} 与
 *       {@link #toFrontendJson}：后者对齐 CC union + Java 扩展
 *       {@code {type:'compact_progress', chars}}（摘要流式真进度，前端进度条蠕动源）。</li>
 *   <li><b>可中断（CC Esc）</b>：manual /compact 摘要段耗时最长且 CC 中可 Esc 打断。摘要中断源
 *       由调用方经 {@code CompactConversationContext.setAbortController} 显式携带
 *       （[批 5a] 原 {@code registerAbort} ThreadLocal 槽位已删；CC 对应物 =
 *       {@code context.abortController}）→ {@link #registerSessionAbort}
 *       （会话级登记，供跨线程前端 cancel abort）；{@link #abortForSession} 由 cancelSession
 *       （前端停止/Esc → POST /api/v1/sessions/{sid}/cancel）调用 → abort 会话在飞压缩 →
 *       摘要 provider 硬断流 → 压缩 catch 返回 "Compaction canceled."（对齐 CC compact.ts:126）。</li>
 * </ol>
 *
 * <p>存储（[批 5a] 改造后）：<b>仅会话级 abort</b> 用 {@link ConcurrentHashMap}（跨线程前端
 * cancel 需要）。进度推送 sink 与摘要中断源<b>不再经本类</b> —— 改由
 * {@link CompactConversationContext} 显式携带（CC {@code context.onCompactProgress} /
 * {@code context.abortController} 对应物，见下方「两个 ThreadLocal 载体已删除」段）。
 * 无在飞压缩 → {@link #abortForSession} 返 false（测试/非 STOMP 路径行为不回归）。
 */
public final class CompactProgressState {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** STOMP topic 前缀 · 与 token-warning 同构 {@code /topic/sessions/{sid}/...} */
    public static final String TOPIC_PREFIX = "/topic/sessions/";
    /** STOMP topic 后缀 · 前端订阅点（收到 compact_start 转圈 / compact_progress 走条 / compact_end 收起）。 */
    public static final String TOPIC_SUFFIX = "/compact-progress";

    /** 会话级在飞压缩 AbortController · 跨线程（前端 cancel）abort 用。 */
    private static final ConcurrentMap<String, AbortController> sessionAborts = new ConcurrentHashMap<>();

    private CompactProgressState() { /* 工具类不可实例化 */ }

    // ════════════════════════════════════════════════════════════════════
    // [批 5a] 两个 ThreadLocal 载体已删除（push / currentAbort）
    // ════════════════════════════════════════════════════════════════════
    // 原实现：`ThreadLocal<Consumer<CompactProgressEvent>> push`（进度推送 sink）与
    // `ThreadLocal<AbortController> currentAbort`（摘要中断源）—— 进程内隐式通道，
    // 「ThreadLocal 不跨线程」使其在派生线程（子代理 queryLoop 的工具池、asyncWorker）
    // 上恒空，靠 3 处补偿注册（LlmAgentLoop.run / ToolRegistrationConfig manual /
    // PartialCompactService partial / SubagentExecutor 子代理）维持。
    //
    // CC 真源（无 ThreadLocal、无补偿注册）：
    //   · 进度 sink → `context.onCompactProgress`（Tool.ts:239 可选字段；REPL.tsx:3000 显式设）
    //   · 摘要中断源 → `context.abortController.signal`（compact.ts:418/:1347）
    // ⇒ 两者都改由 {@link CompactConversationContext} 显式携带（Java 的 `context` 对应物）：
    //   进度 sink 经 `ctx.setOnCompactProgress(...)`（buildAutoContext 从 ToolUseContext 透传，
    //   与 CC context 链同构）；中断源经 `ctx.setAbortController(...)`（buildAutoContext /
    //   PartialCompactService / ToolRegistrationConfig manual 三路显式赋值）。
    //   StreamCompactSummary 消费侧经 `summarize(…, ctx)` 读取，⛔ 不再有 ThreadLocal 回放。

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
