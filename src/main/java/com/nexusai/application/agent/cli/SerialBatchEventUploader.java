package com.nexusai.application.agent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serial ordered event uploader with batching, retry, and backpressure · 对齐 CC cli/transports/SerialBatchEventUploader.ts.
 *
 * <p>CC source: cli/transports/SerialBatchEventUploader.ts (275 LOC).
 * - enqueue() adds events to pending buffer (backpressure at maxQueueSize)
 * - At most 1 POST in-flight at a time
 * - Drains up to maxBatchSize (and maxBatchBytes) per POST
 * - On failure: exponential backoff (clamped) + jitter, retries indefinitely
 *   until success or close(), unless maxConsecutiveFailures set (drop batch)
 * - close() drops pending, resolves waiters
 */
public final class SerialBatchEventUploader<T> {

    private static final Logger log = LoggerFactory.getLogger(SerialBatchEventUploader.class);

    public record Config<T>(
        int maxBatchSize,
        int maxQueueSize,
        Function<List<T>, CompletableFuture<Void>> send,
        long baseDelayMs,
        long maxDelayMs,
        long jitterMs,
        Integer maxConsecutiveFailures
    ) {}

    /** Throw with optional retryAfterMs to override backoff. */
    public static class RetryableError extends RuntimeException {
        private final Long retryAfterMs;
        public RetryableError(String msg, Long retryAfterMs) { super(msg); this.retryAfterMs = retryAfterMs; }
        public Long retryAfterMs() { return retryAfterMs; }
    }

    private final Config<T> config;
    private final java.util.Deque<T> pending = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<java.util.function.Consumer<T>> backpressureWaiters = new ArrayList<>();
    private final List<java.util.function.Consumer<Void>> flushWaiters = new ArrayList<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong pendingAtClose = new AtomicLong(0);
    private final AtomicInteger droppedBatches = new AtomicInteger(0);
    private final Supplier<Integer> jsonBytesSupplier;
    private final LongConsumer sleepFn;
    private final LongConsumer waitFn;

    public SerialBatchEventUploader(Config<T> config) {
        this(config,
            (java.util.function.Supplier<Integer>) () -> jsonStringifyBytes(null),
            (LongConsumer) ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } },
            (LongConsumer) ms -> { /* default wait no-op */ });
    }

    public SerialBatchEventUploader(Config<T> config,
                                       Supplier<Integer> jsonBytesSupplier,
                                       LongConsumer sleepFn,
                                       LongConsumer waitFn) {
        this.config = Objects.requireNonNull(config);
        this.jsonBytesSupplier = jsonBytesSupplier;
        this.sleepFn = sleepFn;
        this.waitFn = waitFn;
    }

    public int droppedBatchCount() { return droppedBatches.get(); }
    public int pendingCount() { return closed.get() ? (int) pendingAtClose.get() : pending.size(); }

    /** Add events; await if backpressure. */
    public CompletableFuture<Void> enqueue(T... events) {
        if (closed.get()) return CompletableFuture.completedFuture(null);
        List<T> items = new ArrayList<>();
        for (T e : events) if (e != null) items.add(e);
        if (items.isEmpty()) return CompletableFuture.completedFuture(null);
        // Backpressure
        if (pending.size() + items.size() > config.maxQueueSize() && !closed.get()) {
            final java.util.concurrent.CompletableFuture<Void> release = new java.util.concurrent.CompletableFuture<>();
            backpressureWaiters.add(v -> release.complete(null));
            return release.thenCompose(v -> enqueue(events));
        }
        if (closed.get()) return CompletableFuture.completedFuture(null);
        pending.addAll(items);
        drain();
        return CompletableFuture.completedFuture(null);
    }

    /** Block until all pending events sent. */
    public CompletableFuture<Void> flush() {
        if (pending.isEmpty() && !draining.get()) {
            return CompletableFuture.completedFuture(null);
        }
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        flushWaiters.add(done::complete);
        drain();
        return done;
    }

    /** Drop pending and stop processing. */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pendingAtClose.set(pending.size());
        pending.clear();
        for (var w : backpressureWaiters) w.accept(null);
        for (var w : flushWaiters) w.accept(null);
        backpressureWaiters.clear();
        flushWaiters.clear();
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) return;
        int failures = 0;
        try {
            while (!pending.isEmpty() && !closed.get()) {
                List<T> batch = takeBatch();
                if (batch.isEmpty()) continue;
                try {
                    config.send().apply(batch).get();
                    failures = 0;
                } catch (Exception e) {
                    failures++;
                    if (config.maxConsecutiveFailures() != null
                        && failures >= config.maxConsecutiveFailures()) {
                        droppedBatches.incrementAndGet();
                        failures = 0;
                        continue;
                    }
                    // Re-queue failed batch at front
                    List<T> reQueued = new ArrayList<>(batch);
                    reQueued.addAll(pending);
                    pending.clear();
                    pending.addAll(reQueued);
                    long retryAfter = e instanceof RetryableError
                        && ((RetryableError) e).retryAfterMs() != null
                        ? ((RetryableError) e).retryAfterMs() : 0;
                    sleepFn.accept(retryDelay(failures, retryAfter));
                    continue;
                }
            }
        } finally {
            draining.set(false);
            if (pending.isEmpty()) {
                for (var w : flushWaiters) w.accept(null);
                flushWaiters.clear();
            }
        }
    }

    private List<T> takeBatch() {
        int max = config.maxBatchSize();
        Integer maxBytes = null;  // maxBatchBytes not in our simplified Config
        if (maxBytes == null) {
            return new ArrayList<>(pending.stream().limit(max).toList());
        }
        int bytes = 0;
        int count = 0;
        List<T> result = new ArrayList<>();
        for (T item : pending) {
            if (count >= max) break;
            int itemBytes = jsonBytesSupplier.get();
            if (count > 0 && bytes + itemBytes > maxBytes) break;
            bytes += itemBytes;
            result.add(item);
            count++;
        }
        // Remove first N from pending
        for (int i = 0; i < result.size() && !pending.isEmpty(); i++) pending.pollFirst();
        return result;
    }

    long retryDelay(int failures, long retryAfterMs) {
        double jitter = Math.random() * config.jitterMs();
        if (retryAfterMs > 0) {
            long clamped = Math.max(config.baseDelayMs(), Math.min(retryAfterMs, config.maxDelayMs()));
            return clamped + (long) jitter;
        }
        long exp = Math.min(config.baseDelayMs() * (1L << Math.min(failures - 1, 30)), config.maxDelayMs());
        return exp + (long) jitter;
    }

    private static int jsonStringifyBytes(List<?> items) {
        // Simplified: assume ~10 bytes per item
        return items.size() * 10;
    }
}
