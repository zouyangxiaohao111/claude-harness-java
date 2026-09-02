package com.nexusai.application.agent.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coalescing uploader for PUT /worker · 对齐 CC cli/transports/WorkerStateUploader.ts.
 *
 * <p>L1 语义: 合并多个 patch 后单次 PUT,避免抖动. 1 in-flight + 1 pending 槽位,
 *            新 patch 合并到 pending (不增长). 失败重试 exponential backoff (clamped).
 *            top-level 键 last-wins;external_metadata/internal_metadata 一层 RFC 7396 merge.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: enqueue(patch) → void (fire-and-forget);
 *       send(body) → boolean (success);3 常量 (baseDelayMs/maxDelayMs/jitterMs);
 *       close() 后 enqueue 不再发送.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — enqueue A → pending=A → drain → send → OK → pending=null;
 *       enqueue A,B → coalesce → send coalesced;send 失败 → retry with backoff → 成功.</li>
 *   <li><b>A3</b>: 状态: IDLE → INFLIGHT → IDLE;
 *       pending patch 数量永远 ≤ 1 (合并).</li>
 *   <li><b>A4</b>: closed 后 enqueue → no-op;
 *       send 抛错 → catch (计入 retry);
 *       top-level key 相同 → last-wins;
 *       metadata key 合并 + null 值保留 (RFC 7396).</li>
 *   <li><b>A5</b>: 真实场景 — 频繁 enqueue worker_status + external_metadata →
 *       合并为单次 PUT;失败 → 重试直到成功.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `Promise<void>` → Java CompletableFuture;
 *                    TS `Math.random()` → ThreadLocalRandom;
 *                    TS RFC 7396 merge → Java Map merge;
 *                    TS `Promise<boolean>` → Java boolean (注入式).
 */
public final class WorkerStateUploader {

    private static final Logger log = LoggerFactory.getLogger(WorkerStateUploader.class);

    private final BiFunction<Map<String, Object>, Long, Boolean> sendFn;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final long jitterMs;

    private final AtomicReference<CompletableFuture<Void>> inflight = new AtomicReference<>();
    private final Object pendingLock = new Object();
    private Map<String, Object> pending;
    private volatile boolean closed = false;

    public WorkerStateUploader(BiFunction<Map<String, Object>, Long, Boolean> sendFn,
                                long baseDelayMs, long maxDelayMs, long jitterMs) {
        this.sendFn = Objects.requireNonNull(sendFn);
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterMs = jitterMs;
    }

    /** CC enqueue — fire-and-forget, coalesces with existing pending. */
    public void enqueue(Map<String, Object> patch) {
        if (closed) return;
        synchronized (pendingLock) {
            pending = pending != null ? coalescePatches(pending, patch) : patch;
        }
        log.debug("[WorkerStateUploader] enqueue: pending={}", pending != null ? pending.get("worker_status") : "null");
        drain();
    }

    /** CC close — drain + 停止 retry. */
    public void close() {
        closed = true;
        synchronized (pendingLock) { pending = null; }
    }

    private void drain() {
        if (closed) return;
        if (inflight.get() != null) return;
        Map<String, Object> payload;
        synchronized (pendingLock) {
            payload = pending;
            pending = null;
        }
        if (payload == null) return;
        log.debug("[WorkerStateUploader] drain sending: {}", payload.get("worker_status"));
        CompletableFuture<Void> f = sendWithRetry(payload).thenRun(() -> {
            log.debug("[WorkerStateUploader] thenRun after send {}", payload.get("worker_status"));
            inflight.set(null);
            boolean needDrain;
            synchronized (pendingLock) {
                needDrain = (pending != null && !closed);
            }
            log.debug("[WorkerStateUploader] thenRun needDrain={}", needDrain);
            if (needDrain) drain();
        });
        inflight.set(f);
    }

    /** CC sendWithRetry — exponential backoff, retries indefinitely until success or close. */
    private CompletableFuture<Void> sendWithRetry(Map<String, Object> initialPayload) {
        return CompletableFuture.runAsync(() -> {
            Map<String, Object> current = initialPayload;
            int failures = 0;
            while (!closed) {
                boolean ok = sendFn.apply(current, 5000L);
                if (ok) return;
                failures++;
                sleep(retryDelay(failures));
                Map<String, Object> p;
                synchronized (pendingLock) {
                    p = pending;
                    pending = null;
                }
                if (p != null && !closed) {
                    current = coalescePatches(current, p);
                }
            }
        });
    }

    long retryDelay(int failures) {
        long exp = Math.min(baseDelayMs * (1L << (failures - 1)), maxDelayMs);
        long jitter = ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
        return exp + jitter;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * CC coalescePatches — top-level last-wins;external_metadata/internal_metadata 一层 RFC 7396 merge.
     */
    public static Map<String, Object> coalescePatches(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (("external_metadata".equals(key) || "internal_metadata".equals(key))
                && merged.get(key) instanceof Map
                && value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existingMeta = (Map<String, Object>) merged.get(key);
                @SuppressWarnings("unchecked")
                Map<String, Object> overlayMeta = (Map<String, Object>) value;
                Map<String, Object> mergedMeta = new LinkedHashMap<>(existingMeta);
                mergedMeta.putAll(overlayMeta);  // overlay wins (incl. null)
                merged.put(key, mergedMeta);
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }
}
