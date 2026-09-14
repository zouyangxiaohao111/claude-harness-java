package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 压缩警告抑制状态 · 对齐 CC {@code services/compact/compactWarningState.ts}（18 行）
 * + STOMP 抑制态通道（decisions-log §32「前端联动 · token_warning 事件契约」）。
 *
 * <p><b>WHY 存在（IMP-10，与 IMP-09 共拥 + IMP-BACK-3 R3 读侧接线）</b>: CC 在压缩成功后调用
 * {@code suppressCompactWarning()}（compact.ts:75/115/202）抑制"Context left until
 * auto-compact"警告；新 microcompact/compact 开始调用 {@code clearCompactWarningSuppression()}
 * 复位（microCompact.ts:259）。CC 读侧由前端 {@code TokenWarning.tsx} 订阅
 * {@code compactWarningStore.getState()}（compactWarningHook.ts:11-16）——Java 端此前读侧
 * {@code isCompactWarningSuppressed(String)} 无生产消费者（A4 探查 ✗-R3 悬空），本类在
 * IMP-BACK-3 补 STOMP 通道把抑制态暴露给前端（decisions-log §32 token_warning）。
 *
 * <p><b>存储</b>: Java 无 CC {@code createStore} 抽象，用<b>按 sessionId 键控的
 * {@link java.util.concurrent.ConcurrentHashMap} 模块态</b>镜像 CC {@code compactWarningStore}
 * 模块态语义（CC 单进程单会话 ⇒ 模块态 ≡ 会话态；本仓一 JVM 多会话 ⇒ 键控）；订阅面 {@link #subscribe} 对齐
 * CC {@code createStore.subscribe}（store.ts:19-21，值变化才通知 listener，store.ts:14-17）。
 *
 * <h2>CC 对齐（grep -n 自验 2026-08-04 + 2026-08-23 IMP-BACK-3，compactWarningState.ts / store.ts）</h2>
 * <table>
 *   <tr><th>本方法</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>suppressCompactWarning(sessionId, pushCtx)</td><td>suppressCompactWarning()</td><td>compactWarningState.ts:11</td></tr>
 *   <tr><td>clearCompactWarningSuppression(sessionId, pushCtx)</td><td>clearCompactWarningSuppression()</td><td>compactWarningState.ts:16</td></tr>
 *   <tr><td>isCompactWarningSuppressed(sessionId)</td><td>compactWarningStore.getState()</td><td>compactWarningState.ts:8</td></tr>
 *   <tr><td>subscribe()</td><td>compactWarningStore.subscribe()</td><td>store.ts:19-21</td></tr>
 *   <tr><td>publishTokenWarning(pushCtx,…)</td><td>TokenWarning.tsx 载荷（后端定，decisions-log §32）</td><td>TokenWarning.tsx:87-178</td></tr>
 * </table>
 *
 * <p><b>消费方</b>: {@code CompactCommand} 成功收尾链（IMP-10）；microcompact 链式入口
 * （IMP-09 重建后接线）；STOMP 抑制态通道触发点 1/2/3（decisions-log §32）。
 *
 * <p><b>STOMP 通道 3 触发点</b>（decisions-log §32「前端联动 · token_warning 事件契约」）：
 * <ol>
 *   <li><b>压缩成功 → suppressed=true</b>：{@link #suppressCompactWarning(String, SessionPushContext)} 状态 false→true
 *       时经会话推送上下文推 {@code AgentEvent.TokenWarning(suppressed=true)}（5 个生产调用点：
 *       CompactCommand:252/:286/:424 + MicroCompactor:407/:524 不变，推送自动发生）；</li>
 *   <li><b>新压缩开始 → suppressed=false</b>：{@link #clearCompactWarningSuppression(String, SessionPushContext)} 状态
 *       true→false 时推 {@code TokenWarning(suppressed=false)}（1 个生产调用点：MicroCompactor:246）；</li>
 *   <li><b>上下文接近阈值 → 推 token 用量</b>：{@link #publishTokenWarning} 由 LlmAgentLoop
 *       blocking-limit 预检（calculateTokenWarningState 处）显式调用，携带
 *       tokenUsage/contextWindow/percentLeft 完整数据。</li>
 * </ol>
 *
 * <p><b>会话推送上下文（[批 5a-2] 显式化）</b>: 原用 {@link ThreadLocal} 承载当前线程的
 * STOMP 推送上下文（对齐 {@code CacheSafeParamsHolder} 线程隔离模式），靠 LlmAgentLoop
 * {@code run()} / manual / 子代理三处成对注册维持。现改为 {@link SessionPushContext}
 * **显式参数**传入三个会触发推送的方法（{@link #suppressCompactWarning(String, SessionPushContext)} /
 * {@link #clearCompactWarningSuppression(String, SessionPushContext)} / {@link #publishTokenWarning}）——
 * 构造点 = 值已在作用域内的两处（{@code LlmAgentLoop.loop} 的
 * {@code (ctx.wsTemplate(), state.sessionId())}；{@code CompactCommandContext.warningPushContext()}）。
 * 无推送上下文（非 STOMP 路径/单测）⇒ 跳过推送并 <b>≥WARN</b>，store 状态与订阅者仍推进（行为不回归）。
 */
public final class CompactWarningState {

    private static final Logger log = LoggerFactory.getLogger(CompactWarningState.class);

    /**
     * 警告抑制标志 · 对齐 CC {@code compactWarningStore}（默认 false）的**按会话键控**等价物。
     *
     * <p><b>WHY 必须按会话键控（本批修复的跨会话缺陷）</b>：CC 的 {@code compactWarningStore}
     * 是 <b>module-level store</b>（compactWarningState.ts:6-9 {@code let} + {@code createStore}）
     * —— 在 CC 里安全（单进程单会话，且前端直接订阅该模块 store）。本仓是<b>一 JVM 多会话</b> +
     * STOMP 推送按会话寻址 ⇒ 原实现的单份 {@code AtomicBoolean} 有两条可观测缺陷：
     * <ol>
     *   <li><b>推送丢失</b>：A 会话压缩置 true 后，B 会话压缩的 CAS(false→true) 失败 ⇒
     *       {@code publishSuppressedChange} 不执行 ⇒ <b>B 会话前端收不到
     *       {@code token_warning(suppressed=true)}</b>（推送只在状态变化时发）。</li>
     *   <li><b>误清他人抑制</b>：A 会话 microcompact 触发 clear(true→false) 会把<b>全局</b>抑制态复位
     *       ⇒ B 会话前端「Context low」警告在 B 自己未复位时被错误解除。</li>
     * </ol>
     * 可观测单元 = <b>该会话</b>的 {@code token_warning} STOMP 事件与其前端横幅态 ⇒ 键 = 显式 sessionId。
     *
     * <p><b>键缺失语义</b>：sessionId 为 null/blank → 归入<b>无会话桶</b>（键 {@code ""}），
     * 只与同为无会话的调用共享，⛔ 不与真实会话互窃；该情形由调用方的 pushCtx==null
     * 分支照常 <b>≥WARN</b> 可观测。
     *
     * <p><b>容量</b>：转 true 建条目、转 false <b>删条目</b>（absent ≡ false）⇒ 条目数 ≤ 当前处于
     * 抑制态的会话数（压缩窗口内），不随会话总数增长。
     */
    private static final java.util.Map<String, Boolean> suppressedBySession =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 会话键归一 · null/blank → 无会话桶（{@code ""}）。 */
    private static String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "" : sessionId;
    }

    /** 订阅监听器集 · 对齐 CC createStore 的 {@code Set<Listener>}（store.ts:12） */
    private static final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    private CompactWarningState() { /* 工具类不可实例化 */ }

    // ════════════════════════════════════════════════════════════════════
    // [批 5a-2] ThreadLocal<SessionPushContext> pushContext 载体已删除
    // ════════════════════════════════════════════════════════════════════
    // 原实现：`private static final ThreadLocal<SessionPushContext> pushContext` +
    //   registerPushContext/clearPushContext —— 进程内隐式通道，靠 3 处成对注册
    //   （LlmAgentLoop.run() / ToolRegistrationConfig manual / SubagentExecutor 子代理）
    //   维持；「ThreadLocal 不跨线程」使子代理路径必须额外补偿注册。
    //
    // ⚠️ CC 无对应物（这是本载体与前三个载体的关键差别）：CC `compactWarningState.ts` 是
    //   **session-less 模块 store**（`let` + createStore），`suppressCompactWarning()` /
    //   `clearCompactWarningSuppression()` **无参**、无 STOMP 概念 —— CC 单进程单会话，前端直接
    //   订阅模块 store。Java 是多会话 Web，push 必须带 sessionId + sender ⇒ **必须自造显式通道**。
    //
    // ⇒ 本批改为：push 上下文作为**显式参数**传入三个会触发推送的方法（见下），
    //   构造点 = 值已在作用域内的两处（`LlmAgentLoop.loop` 的 (ctx.wsTemplate(), state.sessionId())；
    //   `CompactCommand` 的 CompactCommandContext.warningPushContext()）。
    //   取不到（null）⇒ **跳过推送并 ≥WARN**（(b) 类，禁只 DEBUG / 禁零日志）。

    /**
     * 会话 STOMP 推送上下文 · LlmAgentLoop {@code run()} 每会话线程注册。
     *
     * @param sessionId 会话 ID（AgentEvent.TokenWarning.sessionId 载荷）
     * @param sender    STOMP 事件发送器（收到 TokenWarning 后 convertAndSend 到前端订阅 topic）
     */
    public record SessionPushContext(String sessionId, Consumer<AgentEvent.TokenWarning> sender) {
        public SessionPushContext {
            if (sessionId == null) {
                throw new IllegalArgumentException("SessionPushContext.sessionId required");
            }
            if (sender == null) {
                throw new IllegalArgumentException("SessionPushContext.sender required");
            }
        }
    }

    /**
     * 订阅警告抑制状态变更 · 对齐 CC {@code compactWarningStore.subscribe}（store.ts:19-21）。
     *
     * <p>CC setState 仅在值实际变化时触发 listener（store.ts:14-17 {@code if (Object.is(next, prev)) return}），
     * 本方法语义一致：{@code suppressCompactWarning}/{@code clearCompactWarningSuppression} 仅在
     * {@link #transition(String, boolean)} 返回「状态确实变化」时通知订阅者。
     *
     * @param listener 状态变更回调（参数 = 最新抑制态 true=已抑制）
     * @return 取消订阅 Runnable（CC subscribe 返回 unsubscribe，store.ts:20-21）
     */
    public static Runnable subscribe(Consumer<Boolean> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("CompactWarningState.subscribe: listener required");
        }
        listeners.add(listener);
        if (log.isDebugEnabled()) {
            log.debug("[CompactWarningState] 订阅抑制态变更: listener={} 当前订阅数={}", listener, listeners.size());
        }
        return () -> listeners.remove(listener);
    }

    /**
     * 压缩成功后抑制警告 · 对齐 CC {@code suppressCompactWarning()}
     * （compactWarningState.ts:11-13，compact.ts:75/115/202）。
     *
     * <p><b>触发点 1（decisions-log §32）</b>: 状态 false→true 时（CAS 成功）通知订阅者 +
     * 若 {@code pushCtx} 非 null 则推 {@code TokenWarning(suppressed=true)}。幂等：已为 true 再调用
     * 不重复通知/推送（对齐 CC setState 值未变不触发，store.ts:14-17）。
     *
     * @param sessionId 显式会话标识（**必传**：抑制态按会话键控，见 {@link #suppressedBySession}；
     *                  null/blank → 无会话桶）
     * @param pushCtx   会话推送上下文（[批 5a-2] 显式参数，取代 ThreadLocal 载体）；
     *                  null = 本路径无 STOMP 通道 ⇒ 跳过推送并 **WARN**（(b) 类，禁只 DEBUG）
     */
    public static void suppressCompactWarning(String sessionId, SessionPushContext pushCtx) {
        if (transition(sessionId, true)) {
            notifyListeners(true);
            publishSuppressedChange(sessionId, pushCtx, true);
        }
        if (log.isDebugEnabled()) {
            log.debug("[CompactWarningState] suppressCompactWarning: sessionId={} 已抑制 compact 警告 (suppressed={})",
                sessionId, isCompactWarningSuppressed(sessionId));
        }
    }

    /**
     * 新 microcompact/compact 开始前复位 · 对齐 CC {@code clearCompactWarningSuppression()}
     * （compactWarningState.ts:16-18，microCompact.ts:259）。
     *
     * <p><b>触发点 2（decisions-log §32）</b>: 状态 true→false 时（CAS 成功）通知订阅者 +
     * 若 {@code pushCtx} 非 null 则推 {@code TokenWarning(suppressed=false)}。
     *
     * @param sessionId 显式会话标识（**必传**：只复位<b>本会话</b>的抑制态；null/blank → 无会话桶）
     * @param pushCtx   会话推送上下文（显式参数）；null ⇒ 跳过推送并 **WARN**
     */
    public static void clearCompactWarningSuppression(String sessionId, SessionPushContext pushCtx) {
        if (transition(sessionId, false)) {
            notifyListeners(false);
            publishSuppressedChange(sessionId, pushCtx, false);
        }
        if (log.isDebugEnabled()) {
            log.debug("[CompactWarningState] clearCompactWarningSuppression: sessionId={} 复位警告抑制 (suppressed={})",
                sessionId, isCompactWarningSuppressed(sessionId));
        }
    }

    /**
     * 原子状态转移（按会话）· 对齐 CC {@code setState} 的「值变化才通知」
     * （store.ts:14-17 {@code if (Object.is(next, prev)) return}）。
     *
     * <p>转 {@code true} 建条目、转 {@code false} <b>删条目</b>（absent ≡ false，天然有界）。
     *
     * @return true = 状态确实变化（调用方应通知订阅者 + 推送）
     */
    private static boolean transition(String sessionId, boolean next) {
        String key = sessionKey(sessionId);
        boolean[] changed = {false};
        suppressedBySession.compute(key, (k, prev) -> {
            boolean current = Boolean.TRUE.equals(prev);
            if (current == next) {
                return prev;
            }
            changed[0] = true;
            return next ? Boolean.TRUE : null;
        });
        return changed[0];
    }

    /**
     * 当前是否处于警告抑制状态 · 对齐 CC {@code compactWarningStore.getState()}。
     *
     * @param sessionId 显式会话标识（null/blank → 无会话桶，见 {@link #suppressedBySession}）
     * @return true=该会话已抑制（本会话压缩成功后）
     */
    public static boolean isCompactWarningSuppressed(String sessionId) {
        return Boolean.TRUE.equals(suppressedBySession.get(sessionKey(sessionId)));
    }

    /**
     * <b>触发点 3（decisions-log §32）</b>：上下文接近阈值 → 推 token 用量。
     *
     * <p>显式推送 {@code AgentEvent.TokenWarning} STOMP 事件，携带完整 token 数据；由
     * LlmAgentLoop blocking-limit 预检（{@code calculateTokenWarningState} 处）在上下文接近
     * 阈值时调用。字段对齐：
     * <ul>
     *   <li>{@code suppressed} —— 对齐 CC compactWarningStore（compactWarningState.ts:8）;</li>
     *   <li>{@code tokenUsage} —— 对齐 CC TokenWarning.tsx:10 props tokenUsage;</li>
     *   <li>{@code contextWindow} —— 对齐 CC {@code getEffectiveContextWindowSize}（autoCompact.ts:33-49）;</li>
     *   <li>{@code percentLeft} —— 对齐 CC displayPercentLeft（TokenWarning.tsx:127/:154），
     *       可选（null 时前端自行计算）。</li>
     * </ul>
     *
     * <p>无会话推送上下文（非 STOMP 路径）时**跳过并 WARN**（(b) 类：合法跳过但必须可观测，
     * 禁只 DEBUG —— 原实现此处只写 DEBUG，是本载体两处日志违例之一）。
     *
     * @param pushCtx       会话推送上下文（[批 5a-2] 显式参数；sessionId 亦取自此处）
     * @param suppressed    当前警告抑制态（isCompactWarningSuppressed）
     * @param tokenUsage    当前 token 用量
     * @param contextWindow 有效上下文窗口（effectiveWindow）
     * @param percentLeft   剩余百分比（可 null）
     */
    public static void publishTokenWarning(SessionPushContext pushCtx, boolean suppressed, long tokenUsage,
                                           long contextWindow, Integer percentLeft) {
        if (pushCtx == null || pushCtx.sender() == null) {
            log.warn("[CompactWarningState] publishTokenWarning: 无会话推送上下文 → 跳过 STOMP 推送"
                    + "（非 STOMP 路径或未接线；store 状态与订阅者仍已推进）: "
                    + "suppressed={} tokenUsage={} contextWindow={} percentLeft={}",
                suppressed, tokenUsage, contextWindow, percentLeft);
            return;
        }
        String sessionId = pushCtx.sessionId();
        AgentEvent.TokenWarning warning =
            AgentEvent.TokenWarning.of(sessionId, suppressed, tokenUsage, contextWindow, percentLeft);
        pushCtx.sender().accept(warning);
        log.info("[CompactWarningState] 推 token_warning STOMP: session={} suppressed={} tokenUsage={} "
                + "contextWindow={} percentLeft={} · decisions-log §32",
            sessionId, suppressed, tokenUsage, contextWindow, percentLeft);
    }

    /**
     * 触发点 1/2 内部推送：抑制态变化时经会话推送上下文推 TokenWarning（token 数据由触发点 3 补充）。
     *
     * @param pushCtx 会话推送上下文（null ⇒ 跳过并 **WARN**）
     * @param value   最新抑制态
     */
    private static void publishSuppressedChange(String sessionId, SessionPushContext pushCtx, boolean value) {
        if (pushCtx == null) {
            // (b) 类：无 STOMP 推送通道 —— 仅写 store + 通知订阅者，不推 STOMP（行为不回归）。
            // ⛔ 原实现此处**零日志**（本载体两处日志违例之二）⇒ 现 ≥WARN 可观测。
            log.warn("[CompactWarningState] sessionId={} 抑制态变更({})无会话推送上下文 → 跳过 STOMP 推送"
                    + "（非 STOMP 路径或未接线；store 与订阅者仍已推进）", sessionId, value);
            return;
        }
        publishTokenWarning(pushCtx, value, 0L, 0L, null);
    }

    private static void notifyListeners(boolean value) {
        for (Consumer<Boolean> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException e) {
                // 订阅者异常不得阻断 store 状态推进（对齐 CC listener 不捕获但 Java 隔离单点故障）
                log.warn("[CompactWarningState] 订阅者通知异常: {}", e.toString());
            }
        }
    }

    /**
     * 测试重置 · 清空抑制态 + 订阅者（对齐 AutoModeState.resetForTesting 惯例）。
     *
     * <p><b>WHY</b>: 模块态 {@link AtomicBoolean}/{@link CopyOnWriteArrayList} 跨测试残留会污染断言
     * （前例：AutoModeState / OfficialMcpRegistry resetForTesting）。
     * [批 5a-2] 推送上下文已不再有模块态槽位（改显式参数）⇒ 无需清理。
     */
    public static void resetForTesting() {
        suppressedBySession.clear();
        listeners.clear();
        if (log.isDebugEnabled()) {
            log.debug("[CompactWarningState] 测试重置完成");
        }
    }
}
