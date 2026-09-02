package com.nexusai.application.agent.lsp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt cache break detection · 对齐 CC services/api/promptCacheBreakDetection.ts.
 *
 * <p>L1 语义: 跟踪 prompt state (system/tools/model/betas/effort) 的 hash, 比较跨 turn 变化,
 *            API response cache_read 突降 >5% 且 >2000 tokens → 视为 cache break, 触发 telemetry.
 *            本类只暴露核心 hash 计算 + recordPromptState/checkResponseForCacheBreak 状态机;
 *            IO/analytics/日志/agent tracking 全部由注入式 supplier 处理.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: MIN_CACHE_MISS_TOKENS=2000; CACHE_TTL_5MIN_MS=300000; CACHE_TTL_1HOUR_MS=3600000;
 *       MAX_TRACKED_SOURCES=10; TRACKED_SOURCE_PREFIXES (5 项);
 *       recordPromptState(snapshot) + checkResponseForCacheBreak(qs, cacheRead, cacheCreation, msgs);
 *       notifyCacheDeletion + notifyCompaction + cleanupAgentTracking + resetPromptCacheBreakDetection.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — record state with model='sonnet' →
 *       record again with model='opus' → checkResponseForCacheBreak with cacheRead drop → 'tengu_prompt_cache_break' event;
 *       reset() 清空全部 previousStateBySource.</li>
 *   <li><b>A3</b>: 状态 — key 不在 TRACKED_SOURCE_PREFIXES → no-op;
 *       pendingChanges=null 当所有字段一致; cleanupAgentTracking(id) → 删除该 key.</li>
 *   <li><b>A4</b>: snapshot=null 字段 → 默认值; cacheRead=null → return; previous null → no-op;
 *       cacheDeletionsPending=true → 跳过 break 检测.</li>
 *   <li><b>A5</b>: 真实场景 — agent 子任务 → recordPromptState 多次 → cache break 时 logEvent
 *       + 写入 diff 文件 + 调试告警; agent 关闭 → cleanupAgentTracking.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Map<string, PreviousState> → Java ConcurrentHashMap;
 *                    TS Bun.hash → Java MessageDigest SHA-256 (CC fallback);
 *                    TS jsonStringify → Java Object.toString (deterministic sort);
 *                    TS class mutable → Java record + ConcurrentHashMap (LRU eviction by capacity).
 */
public final class PromptCacheBreakDetection {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheBreakDetection.class);

    public static final long MIN_CACHE_MISS_TOKENS = 2_000L;
    public static final long CACHE_TTL_5MIN_MS = 5L * 60L * 1000L;
    public static final long CACHE_TTL_1HOUR_MS = 60L * 60L * 1000L;
    public static final int MAX_TRACKED_SOURCES = 10;

    public static final List<String> TRACKED_SOURCE_PREFIXES = List.of(
        "repl_main_thread", "sdk", "agent:custom", "agent:default", "agent:builtin");

    /** CC PromptStateSnapshot. */
    public record PromptStateSnapshot(
        List<Map<String, Object>> system,
        List<Map<String, Object>> toolSchemas,
        String querySource,
        String model,
        String agentId,
        boolean fastMode,
        String globalCacheStrategy,
        List<String> betas,
        boolean autoModeActive,
        boolean isUsingOverage,
        boolean cachedMCEnabled,
        Object effortValue,
        Object extraBodyParams) {

        public PromptStateSnapshot {
            system = system == null ? List.of() : List.copyOf(system);
            toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
            betas = betas == null ? List.of() : List.copyOf(betas);
        }
    }

    /** CC PendingChanges. */
    public record PendingChanges(
        boolean systemPromptChanged, boolean toolSchemasChanged, boolean modelChanged,
        boolean fastModeChanged, boolean cacheControlChanged, boolean globalCacheStrategyChanged,
        boolean betasChanged, boolean autoModeChanged, boolean overageChanged,
        boolean cachedMCChanged, boolean effortChanged, boolean extraBodyChanged,
        int addedToolCount, int removedToolCount,
        List<String> addedTools, List<String> removedTools, List<String> changedToolSchemas,
        long systemCharDelta,
        String previousModel, String newModel,
        String prevGlobalCacheStrategy, String newGlobalCacheStrategy,
        List<String> addedBetas, List<String> removedBetas,
        String prevEffortValue, String newEffortValue) {}

    /** Break detection result. */
    public record CacheBreakResult(
        boolean detected, String reason,
        PendingChanges changes, long prevCacheRead, long cacheRead, long cacheCreation,
        long timeSinceLastAssistantMsg) {}

    private final java.util.function.Consumer<CacheBreakResult> eventSink;

    /**
     * [IMP-SP-06] feature 门控 · 对齐 CC {@code if (feature('PROMPT_CACHE_BREAK_DETECTION'))}
     * （claude.ts:1469）· OPD-SP-14 默认关 → record/check/notify 为 no-op。
     * <p>默认单参构造 {@code enabled=true}（既有测试 / MicroCompactor 直接调用方不破坏）；
     * 发送边界调用方须经 {@link #gatedBy} 以 {@link FeatureFlags#promptCacheBreakDetection()} 接线。
     */
    private final boolean enabled;

    private static final java.util.Map<String, PreviousState> PREVIOUS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 默认启用（向后兼容：既有测试 / MicroCompactor 直接调用方保持行为）。 */
    public PromptCacheBreakDetection(java.util.function.Consumer<CacheBreakResult> eventSink) {
        this(eventSink, true);
    }

    /**
     * [IMP-SP-06] 显式门控构造 · enabled=false 时 record/check/notify 全部 no-op（OPD-SP-14 默认关）。
     *
     * @param eventSink cache break 事件接收（可 null）
     * @param enabled   {@code PROMPT_CACHE_BREAK_DETECTION} feature 值（FeatureFlags.promptCacheBreakDetection）
     */
    public PromptCacheBreakDetection(java.util.function.Consumer<CacheBreakResult> eventSink, boolean enabled) {
        this.eventSink = eventSink == null ? r -> {} : eventSink;
        this.enabled = enabled;
    }

    public static PromptCacheBreakDetection defaultInstance() {
        return new PromptCacheBreakDetection(r -> {});
    }

    /**
     * [IMP-SP-06] 发送边界工厂 · 以 {@link FeatureFlags#promptCacheBreakDetection()} 作为门控。
     * 关时（默认）record/check 为 no-op；IMP-SP-08 切换 ModelCaller 后经此接线。
     *
     * @param flags 当前 feature flags（null → 默认关）
     */
    public static PromptCacheBreakDetection gatedBy(com.nexusai.application.agent.loop.FeatureFlags flags) {
        boolean on = flags != null && flags.promptCacheBreakDetection();
        return new PromptCacheBreakDetection(r -> {}, on);
    }

    /** CC getTrackingKey. */
    public static String getTrackingKey(String querySource, String agentId) {
        // IMP2-01（V2-S5）：判定入口 canonical 归一——生产传 name() 大写枚举名
        // （REPL_MAIN_THREAD/SDK/COMPACT/FORK）先归一 CC 小写值域再前缀匹配；
        // 小写既有值域幂等（canonicalize 未知名原样）。
        String canonical = com.nexusai.application.agent.QuerySource.canonicalize(querySource);
        if (canonical == null) return null;
        if ("compact".equals(canonical)) return "repl_main_thread";
        for (String prefix : TRACKED_SOURCE_PREFIXES) {
            if (canonical.startsWith(prefix)) {
                return agentId != null && !agentId.isEmpty() ? agentId : canonical;
            }
        }
        return null;
    }

    /** CC isExcludedModel. */
    public static boolean isExcludedModel(String model) {
        return model != null && model.contains("haiku");
    }

    /** CC recordPromptState. */
    public void recordPromptState(PromptStateSnapshot snapshot) {
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptCacheBreak] recordPromptState: noop（PROMPT_CACHE_BREAK_DETECTION 未启用）· CC claude.ts:1469");
            }
            return;
        }
        if (snapshot == null) return;
        String key = getTrackingKey(snapshot.querySource(), snapshot.agentId());
        if (key == null) return;
        try {
            Object strippedSystem = stripCacheControl(snapshot.system());
            Object strippedTools = stripCacheControl(snapshot.toolSchemas());
            long systemHash = computeHash(strippedSystem);
            long toolsHash = computeHash(strippedTools);
            Object cacheControlArr = extractCacheControl(snapshot.system());
            long cacheControlHash = computeHash(cacheControlArr);
            List<String> toolNames = new ArrayList<>();
            for (var t : snapshot.toolSchemas()) {
                Object n = t.get("name");
                toolNames.add(n == null ? "unknown" : n.toString());
            }
            Map<String, Long> perToolHashes = computePerToolHashes(strippedSystem, toolNames);
            long systemCharCount = getSystemCharCount(snapshot.system());
            List<String> sortedBetas = new ArrayList<>(snapshot.betas());
            java.util.Collections.sort(sortedBetas);
            String effortStr = snapshot.effortValue() == null ? "" : snapshot.effortValue().toString();
            long extraBodyHash = snapshot.extraBodyParams() == null ? 0 : computeHash(snapshot.extraBodyParams());

            PreviousState prev = PREVIOUS.get(key);
            if (prev == null) {
                while (PREVIOUS.size() >= MAX_TRACKED_SOURCES) {
                    var it = PREVIOUS.keySet().iterator();
                    if (it.hasNext()) PREVIOUS.remove(it.next());
                    else break;
                }
                PREVIOUS.put(key, new PreviousState(
                    systemHash, toolsHash, cacheControlHash,
                    toolNames, systemCharCount, snapshot.model(),
                    snapshot.fastMode(), snapshot.globalCacheStrategy(),
                    sortedBetas, snapshot.autoModeActive(), snapshot.isUsingOverage(),
                    snapshot.cachedMCEnabled(), effortStr, extraBodyHash,
                    1, null, false, perToolHashes));
                return;
            }
            prev.callCount++;
            PendingChanges pending = computePendingChanges(prev, snapshot,
                systemHash, toolsHash, cacheControlHash, toolNames,
                systemCharCount, sortedBetas, effortStr, extraBodyHash);
            prev.systemHash = systemHash;
            prev.toolsHash = toolsHash;
            prev.cacheControlHash = cacheControlHash;
            prev.toolNames = toolNames;
            prev.systemCharCount = systemCharCount;
            prev.model = snapshot.model();
            prev.fastMode = snapshot.fastMode();
            prev.globalCacheStrategy = snapshot.globalCacheStrategy();
            prev.betas = sortedBetas;
            prev.autoModeActive = snapshot.autoModeActive();
            prev.isUsingOverage = snapshot.isUsingOverage();
            prev.cachedMCEnabled = snapshot.cachedMCEnabled();
            prev.effortValue = effortStr;
            prev.extraBodyHash = extraBodyHash;
            if (pending != null) {
                prev.pendingChanges = pending;
            }
        } catch (Exception ex) {
            log.warn("recordPromptState failed: {}", ex.getMessage());
        }
    }

    /** CC checkResponseForCacheBreak. */
    public void checkResponseForCacheBreak(String querySource, long cacheReadTokens,
            long cacheCreationTokens, Long lastAssistantMsgTimestamp,
            String agentId, String requestId) {
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptCacheBreak] checkResponseForCacheBreak: noop（PROMPT_CACHE_BREAK_DETECTION 未启用）· CC claude.ts:1483");
            }
            return;
        }
        if (querySource == null) return;
        String key = getTrackingKey(querySource, agentId);
        if (key == null) return;
        PreviousState state = PREVIOUS.get(key);
        if (state == null) return;
        if (isExcludedModel(state.model)) return;
        Long prevCacheRead = state.prevCacheReadTokens;
        state.prevCacheReadTokens = cacheReadTokens;
        if (prevCacheRead == null) return;
        if (state.cacheDeletionsPending) {
            state.cacheDeletionsPending = false;
            state.pendingChanges = null;
            return;
        }
        long tokenDrop = prevCacheRead - cacheReadTokens;
        if (cacheReadTokens >= prevCacheRead * 0.95 || tokenDrop < MIN_CACHE_MISS_TOKENS) {
            state.pendingChanges = null;
            return;
        }
        Long timeSinceLast = lastAssistantMsgTimestamp == null
            ? null : System.currentTimeMillis() - lastAssistantMsgTimestamp;
        String reason = buildReason(state.pendingChanges, timeSinceLast);
        CacheBreakResult result = new CacheBreakResult(
            true, reason, state.pendingChanges,
            prevCacheRead, cacheReadTokens, cacheCreationTokens,
            timeSinceLast == null ? -1L : timeSinceLast);
        try {
            eventSink.accept(result);
        } catch (Exception ex) {
            log.warn("event sink failed: {}", ex.getMessage());
        }
        state.pendingChanges = null;
    }

    /** CC notifyCacheDeletion. */
    public void notifyCacheDeletion(String querySource, String agentId) {
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptCacheBreak] notifyCacheDeletion: noop（PROMPT_CACHE_BREAK_DETECTION 未启用）");
            }
            return;
        }
        String key = getTrackingKey(querySource, agentId);
        if (key == null) return;
        PreviousState state = PREVIOUS.get(key);
        if (state != null) state.cacheDeletionsPending = true;
    }

    /** CC notifyCompaction. */
    public void notifyCompaction(String querySource, String agentId) {
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptCacheBreak] notifyCompaction: noop（PROMPT_CACHE_BREAK_DETECTION 未启用）");
            }
            return;
        }
        String key = getTrackingKey(querySource, agentId);
        if (key == null) return;
        PreviousState state = PREVIOUS.get(key);
        if (state != null) {
            state.prevCacheReadTokens = null;
            log.debug("[PromptCacheBreak] notifyCompaction: key={} cache-read 基线已重置（压缩后 "
                    + "cache-read 下降不误报）· CC promptCacheBreakDetection.ts:689-698", key);
        }
    }

    /** CC cleanupAgentTracking. */
    public void cleanupAgentTracking(String agentId) {
        if (agentId != null) PREVIOUS.remove(agentId);
    }

    /** CC resetPromptCacheBreakDetection. */
    public void resetPromptCacheBreakDetection() {
        PREVIOUS.clear();
    }

    /** 暴露状态 — 测试 / 监控用. */
    public int getTrackedSourceCount() {
        return PREVIOUS.size();
    }

    // ---- internals ----

    @SuppressWarnings("unchecked")
    private static Object stripCacheControl(List<Map<String, Object>> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var item : items) {
            if (!item.containsKey("cache_control")) {
                result.add(item);
                continue;
            }
            Map<String, Object> copy = new HashMap<>(item);
            copy.remove("cache_control");
            result.add(copy);
        }
        return result;
    }

    private static Object extractCacheControl(List<Map<String, Object>> system) {
        List<Object> arr = new ArrayList<>();
        for (var b : system) {
            arr.add(b.getOrDefault("cache_control", null));
        }
        return arr;
    }

    private static long computeHash(Object data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(stableString(data).getBytes(StandardCharsets.UTF_8));
            return ((hash[0] & 0xFFL) << 24) | ((hash[1] & 0xFFL) << 16)
                | ((hash[2] & 0xFFL) << 8) | (hash[3] & 0xFFL);
        } catch (Exception ex) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static String stableString(Object data) {
        if (data == null) return "";
        if (data instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>((Map<String, Object>) data);
            StringBuilder sb = new StringBuilder("{");
            for (var e : sorted.entrySet()) {
                sb.append(e.getKey()).append("=").append(stableString(e.getValue())).append(",");
            }
            sb.append("}");
            return sb.toString();
        }
        if (data instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            for (Object e : (List<?>) data) sb.append(stableString(e)).append(",");
            sb.append("]");
            return sb.toString();
        }
        return data.toString();
    }

    private static Map<String, Long> computePerToolHashes(Object stripped, List<String> names) {
        Map<String, Long> hashes = new HashMap<>();
        if (stripped instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String name = i < names.size() ? names.get(i) : "__idx_" + i;
                hashes.put(name, computeHash(list.get(i)));
            }
        }
        return hashes;
    }

    private static long getSystemCharCount(List<Map<String, Object>> system) {
        long total = 0;
        for (var b : system) {
            Object text = b.get("text");
            if (text != null) total += text.toString().length();
        }
        return total;
    }

    private PendingChanges computePendingChanges(PreviousState prev, PromptStateSnapshot snap,
            long systemHash, long toolsHash, long cacheControlHash, List<String> toolNames,
            long systemCharCount, List<String> sortedBetas, String effortStr, long extraBodyHash) {
        boolean sysChanged = systemHash != prev.systemHash;
        boolean toolChanged = toolsHash != prev.toolsHash;
        boolean modelChanged = !java.util.Objects.equals(snap.model(), prev.model);
        boolean fastChanged = snap.fastMode() != prev.fastMode;
        boolean ccChanged = cacheControlHash != prev.cacheControlHash;
        boolean gsChanged = !java.util.Objects.equals(snap.globalCacheStrategy(), prev.globalCacheStrategy);
        boolean betasChanged = !java.util.Objects.equals(sortedBetas, prev.betas);
        boolean autoChanged = snap.autoModeActive() != prev.autoModeActive;
        boolean ovChanged = snap.isUsingOverage() != prev.isUsingOverage;
        boolean cachedMCChanged = snap.cachedMCEnabled() != prev.cachedMCEnabled;
        boolean effortChanged = !java.util.Objects.equals(effortStr, prev.effortValue);
        boolean extraChanged = extraBodyHash != prev.extraBodyHash;
        if (!(sysChanged || toolChanged || modelChanged || fastChanged || ccChanged
            || gsChanged || betasChanged || autoChanged || ovChanged
            || cachedMCChanged || effortChanged || extraChanged)) {
            return null;
        }
        var prevSet = new java.util.HashSet<>(prev.toolNames);
        var newSet = new java.util.HashSet<>(toolNames);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String n : toolNames) if (!prevSet.contains(n)) added.add(n);
        for (String n : prev.toolNames) if (!newSet.contains(n)) removed.add(n);
        var prevBeta = new java.util.HashSet<>(prev.betas);
        var newBeta = new java.util.HashSet<>(sortedBetas);
        List<String> addedBetas = new ArrayList<>();
        List<String> removedBetas = new ArrayList<>();
        for (String b : sortedBetas) if (!prevBeta.contains(b)) addedBetas.add(b);
        for (String b : prev.betas) if (!newBeta.contains(b)) removedBetas.add(b);
        return new PendingChanges(sysChanged, toolChanged, modelChanged, fastChanged, ccChanged,
            gsChanged, betasChanged, autoChanged, ovChanged, cachedMCChanged, effortChanged, extraChanged,
            added.size(), removed.size(), added, removed, List.of(),
            systemCharCount - prev.systemCharCount,
            prev.model, snap.model(),
            prev.globalCacheStrategy, snap.globalCacheStrategy(),
            addedBetas, removedBetas,
            prev.effortValue, effortStr);
    }

    private static String buildReason(PendingChanges changes, Long timeSinceLast) {
        if (changes == null) {
            if (timeSinceLast == null) return "unknown cause";
            if (timeSinceLast > CACHE_TTL_1HOUR_MS) return "possible 1h TTL expiry (prompt unchanged)";
            if (timeSinceLast > CACHE_TTL_5MIN_MS) return "possible 5min TTL expiry (prompt unchanged)";
            return "likely server-side (prompt unchanged, <5min gap)";
        }
        List<String> parts = new ArrayList<>();
        if (changes.modelChanged) parts.add("model changed");
        if (changes.systemPromptChanged) parts.add("system prompt changed");
        if (changes.toolSchemasChanged) parts.add("tools changed");
        if (changes.fastModeChanged) parts.add("fast mode toggled");
        if (changes.betasChanged) parts.add("betas changed");
        if (changes.autoModeChanged) parts.add("auto mode toggled");
        if (changes.effortChanged) parts.add("effort changed");
        if (changes.extraBodyChanged) parts.add("extra body params changed");
        return parts.isEmpty() ? "no specific cause" : String.join(", ", parts);
    }

    /** 内部状态 record. */
    public static final class PreviousState {
        long systemHash, toolsHash, cacheControlHash;
        List<String> toolNames;
        long systemCharCount;
        String model;
        boolean fastMode;
        String globalCacheStrategy;
        List<String> betas;
        boolean autoModeActive;
        boolean isUsingOverage;
        boolean cachedMCEnabled;
        /**
         * [C-31 辨析] lsp 缓存快照 effort 字段 —— 与 skill effort→LLM 管线
         * （AgentState.effortValue → ModelRequest.effortValue → AnthropicSdkProvider
         * output_config.effort）<b>同名不同义</b>：本字段是上次请求的 effort 快照（用于
         * cache-break 检测哈希，claude.ts:1463-1473 recordPromptState），非 skill 管线字段。
         * C-31 不改动 lsp 逻辑，仅登记区别（09-open-decisions.md C-31 闭环注 ⑦）。
         */
        String effortValue;
        long extraBodyHash;
        int callCount;
        PendingChanges pendingChanges;
        Long prevCacheReadTokens;
        boolean cacheDeletionsPending;
        Map<String, Long> perToolHashes;

        PreviousState(long sh, long th, long ch, List<String> tn, long scc, String m,
                boolean fm, String gs, List<String> b, boolean ama, boolean iuo, boolean cmc,
                String ev, long ebh, int cc, PendingChanges pc, boolean cdp,
                Map<String, Long> pth) {
            systemHash = sh; toolsHash = th; cacheControlHash = ch;
            toolNames = tn; systemCharCount = scc; model = m; fastMode = fm;
            globalCacheStrategy = gs; betas = b; autoModeActive = ama;
            isUsingOverage = iuo; cachedMCEnabled = cmc; effortValue = ev;
            extraBodyHash = ebh; callCount = cc; pendingChanges = pc;
            cacheDeletionsPending = cdp; perToolHashes = pth;
            prevCacheReadTokens = null;
        }
    }
}