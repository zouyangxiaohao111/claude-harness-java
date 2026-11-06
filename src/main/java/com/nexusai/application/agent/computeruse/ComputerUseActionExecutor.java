package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作执行骨架 · <b>对齐 dsh {@code service.ts} 的 {@code actNow} 尾部</b>
 * （settle 轮询 → 抓后置观察 → 组装 {@link ComputerUseActionResult}）。
 *
 * <h2>WHY 需要这一层</h2>
 * <p>dsh 的每个动作返回<b>动作后的新观察</b>（{@code service.ts:636-679}），
 * 让模型能自己判断「动作生效没有」「点的是不是那个元素」。
 * 本仓此前只返回一句文本 ⇒ 模型必须再调一次 observe，多花一轮 token 且可能读到
 * 半更新的界面。
 * <br>⇒ 把「settle → 抓观察 → 组装结果」收敛到一处，4 个元素类工具共用。
 *
 * <h2>settle 语义（逐条对齐 dsh {@code service.ts:636-650}）</h2>
 * <pre>
 * started = now
 * do {
 *   sleep(settleMs)                    // 250ms
 *   latest = observe(app)
 *   if (latest.stateHash != before.stateHash) { settled = true; break }
 * } while (now - started &lt; maxSettleMs)   // 5000ms
 * </pre>
 * <p>⭐ <b>「没变化」不等于「动作失败」</b>：dsh 的 Skill 明确写
 * 「A successful AXPress is never automatically retried just because the tree did not change」
 * ⇒ 本实现<b>只报告</b>（{@code effect.observedStateChanged=false} + note），
 * <b>绝不自动重试动作</b>。
 *
 * <h2>动作串行化（对齐 dsh {@code service.ts:686-697} 的 {@code enqueueAction}）</h2>
 * <p>dsh 按 {@code bundleId:pid} 把同一进程的动作排队，串到<b>上一个动作的后置观察完成</b>。
 * <br>WHY：不排队的话 {@code observe → click → observe} 交错会读到<b>半更新</b>的界面树
 * （比如弹窗刚出现还没画完就被读走）。
 */
final class ComputerUseActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseActionExecutor.class);

    /** settle 间隔 · dsh {@code config.ts:90} {@code settleMs: 250}。 */
    public static final long SETTLE_MS = 250L;

    /** settle 上限 · dsh {@code config.ts:91} {@code maxSettleMs: 5000}。 */
    public static final long MAX_SETTLE_MS = 5_000L;

    /** 每进程的动作队列尾（dsh {@code service.ts:686-697} 的 {@code actionTails}）。 */
    private static final Map<String, CompletableFuture<Void>> ACTION_TAILS = new ConcurrentHashMap<>();

    private ComputerUseActionExecutor() {
        throw new AssertionError("utility class");
    }

    /**
     * 把一段动作放进「同一进程串行」的队列里执行。
     *
     * <p>同进程的动作按调用顺序排队；不同进程<b>可并行</b>（dsh 同款：key = bundleId:pid）。
     *
     * <p>⭐ <b>「取尾 + 挂尾」必须原子</b>（2026-11-02 实测踩到）：两个线程同时进来时，
     * 若都读到 {@code null} 就会各自立即执行 ⇒ <b>同进程动作并行</b>，
     * 后置观察可能读到半更新的树（正是本机制要防的事）。
     * <br>⇒ 用 {@code synchronized} 把「读 previous + 挂新 tail」圈成临界区；
     * <b>动作本体在临界区外执行</b>（否则会把所有 app 都串起来）。
     *
     * @param appKey 进程键（{@code bundleId:pid}）
     * @param action 动作体
     * @param <T>    返回类型
     * @return 动作结果
     */
    static <T> T enqueue(String appKey, java.util.concurrent.Callable<T> action) {
        String key = appKey == null || appKey.isEmpty() ? "unknown" : appKey;
        CompletableFuture<T> current;
        CompletableFuture<Void> tail;
        synchronized (ACTION_TAILS) {
            CompletableFuture<Void> previous = ACTION_TAILS.get(key);
            if (previous == null) {
                current = CompletableFuture.supplyAsync(() -> call(action));
            } else {
                current = previous.handle((ignored, error) -> null)
                        .thenCompose(ignored -> CompletableFuture.supplyAsync(() -> call(action)));
            }
            tail = current.handle((ignored, error) -> null);
            ACTION_TAILS.put(key, tail);
        }
        // 队列排空后清掉键，避免 map 无限增长（dsh 同款：finally 里 delete）
        // ⛔ 用 remove(key, value) 的**值比较**语义：后来的 tail 可能已替换本 tail，
        //   此时不能把别人的队列摘掉
        tail.whenComplete((ignored, error) -> ACTION_TAILS.remove(key, tail));
        try {
            return current.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Exception checked) {
                throw new IllegalStateException(checked.getMessage(), checked);
            }
            throw e;
        }
    }

    /** 动作体调用（把受检异常包成 CompletionException）。 */
    private static <T> T call(java.util.concurrent.Callable<T> action) {
        try {
            return action.call();
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    /**
     * settle 轮询：等界面稳定，并返回后置观察。
     *
     * @param uiaService  AX/UIA 服务
     * @param selector    观察对象（后置观察用<b>同一个</b>对象 ⇒ 不该漂移到别的应用）
     * @param beforeHash  动作前观察的 stateHash
     * @param maxDepth    最大深度
     * @return 后置观察 + 是否检测到变化
     */
    static SettleResult settleAndObserve(ComputerUseUiaService uiaService,
                                         ComputerUseAppSelector selector,
                                         String beforeHash,
                                         int maxDepth) {
        long started = System.currentTimeMillis();
        ComputerUseAccessibilitySnapshot latest = null;
        boolean changed = false;
        try {
            do {
                sleep(SETTLE_MS);
                latest = uiaService.captureSnapshot(0L, maxDepth, selector);
                if (latest.stateHash() != null && !latest.stateHash().isEmpty()
                        && !latest.stateHash().equals(beforeHash)) {
                    changed = true;
                    break;
                }
            } while (System.currentTimeMillis() - started < MAX_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[computer-use] settle 轮询被中断", e);
        } catch (RuntimeException e) {
            // ⚠️ 后置观察失败**不能**让整个动作失败（动作本身已经执行了）
            //   ⇒ 记日志、返回 null，让工具层如实报告「拿不到后置观察」
            log.warn("[computer-use] settle 后置观察失败（动作已执行，状态未知）: {}", e.getMessage());
            return new SettleResult(null, false, System.currentTimeMillis() - started);
        }
        return new SettleResult(latest, changed, System.currentTimeMillis() - started);
    }

    /**
     * settle 结果。
     *
     * @param snapshot     后置观察（null = 拿不到）
     * @param stateChanged 是否检测到状态变化
     * @param elapsedMs    耗时
     */
    record SettleResult(ComputerUseAccessibilitySnapshot snapshot, boolean stateChanged, long elapsedMs) {
    }

    /**
     * 动作完成后组装结构化结果（对齐 dsh {@code service.ts:636-679}）。
     *
     * <p><b>流程</b>：settle 轮询 → 抓后置观察 → 记进 store（成为新的 observationId）
     * → 组装 {@link ComputerUseActionResult}。
     *
     * <p>⭐ <b>后置观察用「动作前那个应用」定位</b>（bundleId + pid），
     * 而不是前台应用 —— 否则动作把前台抢走时，后置观察会读到**另一个应用**的树，
     * 模型据此判断「生效没有」就会得到完全错误的结论。
     *
     * @param uiaService   AX/UIA 服务
     * @param snapshotStore 观察存储（后置观察登记进去，模型可用新 observationId 继续）
     * @param sessionId    会话 id
     * @param action       动作名（click / set-value / type-text / perform-action）
     * @param channel      通道（accessibility / coordinates / keyboard）
     * @param resolution   目标解析结果（可为 null）
     * @param before       动作前的观察（提供应用身份与 stateHash；可为 null ⇒ 前台应用）
     * @param maxDepth     最大深度
     * @param tcc          权限检查（可为 null）
     * @return 结构化结果（后置观察拿不到时，{@code observation} 为 null）
     */
    static ComputerUseActionResult buildResult(ComputerUseUiaService uiaService,
                                                ComputerUseUiSnapshotStore snapshotStore,
                                                String sessionId,
                                                String action,
                                                String channel,
                                                ComputerUseTargetResolver.Resolution resolution,
                                                ComputerUseUiSnapshotStore.Observation before,
                                                int maxDepth,
                                                MacOsTccChecker tcc) {
        ComputerUseAppSelector selector = before == null
                ? ComputerUseAppSelector.frontmost()
                : new ComputerUseAppSelector(before.bundleId(), before.pid(), "");
        String beforeHash = before == null ? "" : before.stateHash();

        SettleResult settled = settleAndObserve(uiaService, selector, beforeHash, maxDepth);

        ComputerUseUiSnapshotStore.Observation post = null;
        ComputerUseAccessibilitySnapshot postSnapshot = settled.snapshot();
        if (postSnapshot != null && snapshotStore != null) {
            List<ComputerUseUiSnapshotStore.ObservedElement> elements =
                    ComputerUseObserveTool.toObservedElements(postSnapshot.nodes());
            post = snapshotStore.record(
                    sessionId,
                    postSnapshot.appName(),
                    postSnapshot.bundleId(),
                    postSnapshot.pid(),
                    postSnapshot.windowTitle(),
                    postSnapshot.stateHash(),
                    postSnapshot.text(),
                    postSnapshot.truncated(),
                    elements,
                    true);
        }
        ComputerUseActionResult.Effect effect = settled.stateChanged()
                ? ComputerUseActionResult.Effect.changed(settled.elapsedMs())
                : ComputerUseActionResult.Effect.unchanged(settled.elapsedMs());
        String activation = postSnapshot != null && postSnapshot.frontmost()
                ? ComputerUseActionResult.ACTIVATION_ALREADY_FRONTMOST
                : ComputerUseActionResult.ACTIVATION_NOT_REQUESTED;
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 动作后置观察: action={} 状态变化={} 耗时={}ms 后置观察={}",
                    action, settled.stateChanged(), settled.elapsedMs(),
                    post == null ? "拿不到" : post.observationId());
        }
        return new ComputerUseActionResult(
                action,
                channel,
                activation,
                false,
                // ⚠️ 如实报 none：macOS 的指针投递走全局 HID 流（实测公开的
                //   CGEventPostToPid 对指针事件无效 ⇒ 定向投递必须走原生侧车）
                ComputerUseActionResult.POINTER_ROUTING_NONE,
                resolution,
                effect,
                post);
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
