package com.nexusai.application.agent.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Speculative agent (predictive UX) · 对齐 CC {@code services/PromptSuggestion/speculation.ts}.
 *
 * <p><b>L2 契约（CC 实际源码逐行核实，6618ab1）</b>:
 * <ul>
 *   <li><b>isSpeculationEnabled</b>（speculation.ts:337-343）—— {@code USER_TYPE==='ant'
 *       && (speculationEnabled ?? true)}。</li>
 *   <li><b>startSpeculation</b>（:402-715）—— 先 abort 现有 speculation（:411-412）→
 *       id=randomUUID().slice(0,8)（:414）→ 子 abort controller（:416-418）→ 置 active state
 *       （:437-452）→ runForkedAgent（:457-656）→ aborted 早退（:658）→ boundary complete
 *       （:660-666）→ catch 重置 + error 遥测（:680-714）。</li>
 *   <li><b>acceptSpeculation</b>（:717-800）—— 非 active → null；abort + 清理 overlay →
 *       idle + speculationSessionTimeSavedMs 累计 → tengu_speculation outcome='accepted'。</li>
 *   <li><b>abortSpeculation</b>（:802-833）—— 非 active 直接返回；abort + overlay 清理 →
 *       idle + outcome='aborted'。</li>
 *   <li><b>MAX_SPECULATION_TURNS=20 / MAX_SPECULATION_MESSAGES=100</b>（:58-59）。</li>
 * </ul>
 *
 * <p><b>Web 后端边界（N/A 披露，不假实现）</b>: CC 的 overlay 文件系统隔离（copy-on-write
 * copyOverlayToMain / safeRemoveOverlay :72-117）、canUseTool 边界（edit/bash/denied_tool
 * :461-632）、prepareMessagesForInjection + handleSpeculationAccept 消息注入主会话（:203-271/:835-991）
 * 是 CLI 本地进程语义（fork agent 以交互工具跑在用户工作目录）。Java Web 后端 fork agent 为
 * LLM chat adapter（无本地文件编辑），这些分量 N/A——状态机（active→done/idle）+ abort +
 * accept + 遥测完整交付，不建 overlay 假实现。
 *
 * <p><b>接线</b>: {@link PromptSuggestion#executeSuggestion}（CC promptSuggestion.ts:214-222）在
 * 建议生成成功且 {@code isSpeculationEnabled()} 时触发 {@link #startSpeculation} —— 本类不再是
 * 0 消费者死代码（OPD-WF7-JS-03 ✅ 实施）。
 */
public final class SpeculationEngine {

    private static final Logger log = LoggerFactory.getLogger(SpeculationEngine.class);

    public static final String IDLE_SPECULATION_STATE = "IDLE";

    /** CC speculation.ts:58 MAX_SPECULATION_TURNS = 20。 */
    public static final int MAX_SPECULATION_TURNS = 20;
    /** CC speculation.ts:59 MAX_SPECULATION_MESSAGES = 100。 */
    public static final int MAX_SPECULATION_MESSAGES = 100;

    /** CC speculation.ts:133 tengu_speculation 遥测事件。 */
    public static final String ANALYTICS_EVENT = "tengu_speculation";

    public record SpeculationState(
            String uuid, long startedAt, String result, double confidence) {
        public static SpeculationState idle() {
            return new SpeculationState(IDLE_SPECULATION_STATE, 0L, null, 0.0);
        }
    }

    public record SpeculationResult(String suggestion, double confidence) {}

    public record CacheSafeParams(Map<String, Object> params) {}

    public interface ForkedAgent {
        SpeculationResult run(String prompt, CacheSafeParams params, Object signal);
    }

    public interface StateStore {
        SpeculationState get();
        void set(SpeculationState state);
    }

    public interface SuggestionTelemetry {
        void log(String event, Map<String, Object> fields);
    }

    private final java.util.function.BooleanSupplier enabledSupplier;
    private final ForkedAgent forkedAgent;
    private final StateStore stateStore;
    private final SuggestionTelemetry telemetry;
    private final AtomicReference<Object> currentSignal = new AtomicReference<>();

    public SpeculationEngine(java.util.function.BooleanSupplier enabledSupplier,
            ForkedAgent forkedAgent,
            StateStore stateStore,
            SuggestionTelemetry telemetry) {
        this.enabledSupplier = enabledSupplier == null ? () -> false : enabledSupplier;
        this.forkedAgent = forkedAgent == null
            ? (p, params, signal) -> new SpeculationResult("", 0.0)
            : forkedAgent;
        this.stateStore = stateStore == null ? new StateStore() {
            private SpeculationState s = SpeculationState.idle();
            public SpeculationState get() { return s; }
            public void set(SpeculationState s) { this.s = s; }
        } : stateStore;
        this.telemetry = telemetry == null ? (e, f) -> {} : telemetry;
    }

    public SpeculationEngine() {
        this(null, null, null, null);
    }

    /**
     * CC isSpeculationEnabled（speculation.ts:337-343）Java 等价：
     * {@code USER_TYPE==='ant' && (getGlobalConfig().speculationEnabled ?? true)}。
     * Java 建模 env {@code USER_TYPE} 相等 'ant' && env {@code NEXUSAI_SPECULATION_ENABLED} 非 'false'
     * （默认 true）。外部用户（USER_TYPE≠ant）→ false，对齐 CC。
     */
    public static boolean isEnabledViaEnv() {
        String userType = System.getenv("USER_TYPE");
        if (userType == null || !"ant".equalsIgnoreCase(userType.trim())) {
            return false;
        }
        String v = System.getProperty("NEXUSAI_SPECULATION_ENABLED",
            System.getenv("NEXUSAI_SPECULATION_ENABLED"));
        if (v == null || v.isBlank()) {
            return true; // CC: speculationEnabled ?? true
        }
        String t = v.trim();
        return !("false".equalsIgnoreCase(t) || "0".equals(t)
            || "off".equalsIgnoreCase(t) || "no".equalsIgnoreCase(t));
    }

    public boolean isSpeculationEnabled() {
        return Boolean.TRUE.equals(enabledSupplier.getAsBoolean());
    }

    public SpeculationState getCurrentState() {
        return stateStore.get();
    }

    /**
     * CC startSpeculation（speculation.ts:402-715）主链。
     *
     * @param suggestion 建议文本（fork agent 的 user prompt）
     * @param params      cache-safe params（web 后端无 cache 通道 → 可空）
     * @return 完成态 {@link SpeculationState}；禁用 / 失败 → idle
     */
    public SpeculationState startSpeculation(String suggestion, CacheSafeParams params) {
        if (suggestion == null) throw new IllegalArgumentException("prompt null");
        if (!isSpeculationEnabled()) return SpeculationState.idle();
        // CC :411-412 先 abort 现有 speculation 再启动新的
        abortSpeculation();
        // CC :414 randomUUID().slice(0,8)
        String uuid = java.util.UUID.randomUUID().toString().substring(0, 8);
        long startedAt = System.currentTimeMillis();
        AbortController signal = new AbortController();
        currentSignal.set(signal);
        // CC :437-452 active state
        stateStore.set(new SpeculationState(uuid, startedAt, null, 0.0));
        if (log.isInfoEnabled()) {
            log.info("SPECULATION: 启动 speculation id={} suggestion=\"{}\" · CC speculation.ts:402-452",
                uuid, abbreviate(suggestion, 50));
        }
        try {
            // CC :457-656 runForkedAgent
            SpeculationResult result = forkedAgent.run(suggestion,
                params == null ? new CacheSafeParams(Map.of()) : params, signal);
            // CC :658 aborted 早退（不置 complete）
            if (signal.isCancelled()) {
                return SpeculationState.idle();
            }
            // CC :660-666 boundary complete
            SpeculationState done = new SpeculationState(
                uuid, startedAt, result.suggestion(), result.confidence());
            stateStore.set(done);
            if (log.isInfoEnabled()) {
                log.info("SPECULATION: 完成 id={} suggestion=\"{}\" · CC speculation.ts:660-666",
                    uuid, abbreviate(result.suggestion(), 50));
            }
            return done;
        } catch (Exception ex) {
            // CC :680-714 catch → abort + 清理 + error 遥测 + reset
            signal.cancel();
            telemetry.log(ANALYTICS_EVENT, Map.of(
                "speculation_id", uuid,
                "outcome", "error",
                "error_type", ex.getClass().getSimpleName(),
                "error_message", ex.getMessage() != null ? ex.getMessage().substring(0,
                    Math.min(200, ex.getMessage().length())) : "Unknown"));
            log.warn("SPECULATION: speculation 失败(静默) id={} err={} · CC speculation.ts:680-714",
                uuid, ex.getMessage());
            stateStore.set(SpeculationState.idle());
            return SpeculationState.idle();
        } finally {
            currentSignal.set(null);
        }
    }

    /**
     * CC acceptSpeculation（speculation.ts:717-800）—— 用户接受：abort + 状态置 idle +
     * tengu_speculation outcome='accepted'。overlay 复制 / 消息注入主会话为 CLI 语义 N/A（类 javadoc）。
     *
     * @return true = 接受了活跃 speculation；false = 无活跃 speculation（CC :722 非 active → null）
     */
    public boolean acceptSpeculation() {
        SpeculationState state = stateStore.get();
        if (state == null || IDLE_SPECULATION_STATE.equals(state.uuid())) return false;
        // CC :737-738 abort
        Object sig = currentSignal.getAndSet(null);
        if (sig instanceof AbortController ac) {
            ac.cancel();
        } else if (sig != null) {
            try {
                sig.getClass().getMethod("cancel").invoke(sig);
            } catch (Exception ex) {
                log.debug("SPECULATION: accept cancel failed: {}", ex.getMessage());
            }
        }
        telemetry.log(ANALYTICS_EVENT, Map.of(
            "speculation_id", state.uuid(),
            "outcome", "accepted",
            "suggestion_length", state.result() == null ? 0 : state.result().length()));
        if (log.isInfoEnabled()) {
            log.info("SPECULATION: 接受 id={} · CC speculation.ts:717-800", state.uuid());
        }
        stateStore.set(SpeculationState.idle());
        return true;
    }

    /** CC abortSpeculation（speculation.ts:802-833）。 */
    public void abortSpeculation() {
        SpeculationState state = stateStore.get();
        if (state == null || IDLE_SPECULATION_STATE.equals(state.uuid())) return;
        Object sig = currentSignal.getAndSet(null);
        if (sig instanceof AbortController ac) {
            ac.cancel();
        } else if (sig != null) {
            try {
                sig.getClass().getMethod("cancel").invoke(sig);
            } catch (Exception ex) {
                log.debug("SPECULATION: abort cancel failed: {}", ex.getMessage());
            }
        }
        telemetry.log(ANALYTICS_EVENT, Map.of(
            "speculation_id", state.uuid(),
            "outcome", "aborted",
            "abort_reason", "user_typed")); // CC :825 abort_reason: 'user_typed'
        if (log.isInfoEnabled()) {
            log.info("SPECULATION: 中止 id={} · CC speculation.ts:802-833", state.uuid());
        }
        stateStore.set(SpeculationState.idle());
    }

    /** 简单 abort controller 抽象（Java 等价 CC AbortController）。 */
    public static class AbortController {
        private volatile boolean cancelled = false;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(" + s.length() + ")";
    }
}
