package com.nexusai.application.agent.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * PostSamplingHookRegistry · 对齐 CC utils/hooks/postSamplingHooks.ts.
 *
 * <p>L1 语义: REPL-side post-sampling hook 注册中心 — 模型 sampling 完成后调用,
 * hook 错误不中断 (logError + continue)。
 *
 * <p>[Session H12] REPLHookContext 语义接入 · 对齐 CC postSamplingHooks.ts:11-22:
 * <ul>
 *   <li>{@link PostSamplingHook#onSampled(PostSamplingContext)} 接收完整
 *       {@link PostSamplingContext} (REPLHookContext 等价: messages/systemPrompt/
 *       userContext/systemContext/toolUseContext/querySource), 非无参回调 —
 *       hook 需要读取消息历史做门控 (如 SkillImprovementHook 数 user 消息)。</li>
 *   <li>executeAll 异步化 (CompletableFuture) · 对齐 CC {@code executePostSamplingHooks}
 *       是 {@code async} (postSamplingHooks.ts:45), hook 内部可做 LLM 侧信道查询而不阻塞
 *       主链。</li>
 * </ul>
 *
 * <p><b>[IMP-HOOKS-S7] 执行模型收敛（H5/D2/D9/D10）</b> · CC 真源
 * postSamplingHooks.ts:45-70 {@code executePostSamplingHooks}：
 * <ul>
 *   <li><b>H5 注册序串行</b>：{@code for (const hook of postSamplingHooks) { await hook(context) }}
 *       —— 批内 hook 逐条 await，互不重叠。Java 端以单线程 daemon 执行器 +
 *       {@link #runNext} 递归串行链表达（旧 runAsync 并行 + allOf + ForkJoinPool.commonPool
 *       占用整段删除）。</li>
 *   <li><b>D2 无条件隔离</b>：{@code catch (error) { logError(toError(error)) }} 后 continue
 *       —— 任何 {@link Throwable}（含 Error 子类）都不中断后续 hook、不向调用方传播。
 *       Java 注入式 errorLogger 签名收敛为 {@code BiConsumer<Integer, Throwable>}；
 *       errorLogger=null 时走内部 slf4j 兜底日志（CC logError 恒记日志，无"传播"模式）。</li>
 *   <li><b>D9 活数组遍历</b>：JS ArrayIterator 在迭代中 push 的元素本轮可见 —— Java 端
 *       {@code HOOKS.size()} 逐次重读，执行中 register 的新 hook 本轮执行（旧快照整段删除）。</li>
 *   <li><b>D10 跨批串行</b>：单线程执行器使多个 executeAll 批次按到达序 FIFO 排队、互不重叠
 *       —— CC {@code sequential()}（sequential.ts:19-56，sessionMemory.ts:272）的注册序队列
 *       等价表达；所有 post-sampling hook（含 extractSessionMemory）跨批串行。</li>
 *   <li><b>D11 子代理范围</b>：CC 子代理经 forkedAgent.ts:545-555 复用 query() 触发
 *       executePostSamplingHooks，但各 hook 内 querySource 门控拦截（sessionMemory.ts:278-281
 *       注释佐证）；Java 子代理走 ProductionForkedQuery 专用 fork loop，无 executeAll 调用 ——
 *       门控等价，可观察行为一致（无代码改动，本注释为登记）。</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: register(hook) + clearAll (test) + executeAll(context, errorLogger)→CompletableFuture&lt;Void&gt;;内部 ArrayList</li>
 *   <li><b>A2 Golden Trace</b>: register N hooks → executeAll 注册序串行调用;某 hook 抛异常 → 隔离,后续继续</li>
 *   <li><b>A3 状态/内部 mutable</b>: ArrayList 后台封;并发 register 不安全 (CC 同样)</li>
 *   <li><b>A4 边界</b>: null hook 跳过 (silent);hook 抛异常 → 记日志</li>
 *   <li><b>A5 业务场景</b>: 模型输出后触发 display-render / 持久化 / skill 改进检测 钩子</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS mutable array → Java ArrayList + register/clearAll static state;
 * TS callback 异常 + logError continue → Java try/catch + 注入式 error logger;
 * TS async → CompletableFuture + 单线程 ExecutorService.
 */
public final class PostSamplingHookRegistry {

    private static final Logger log = LoggerFactory.getLogger(PostSamplingHookRegistry.class);

    /**
     * Post-sampling hook · 对齐 CC {@code type PostSamplingHook = (context: REPLHookContext) => Promise<void> | void}
     * (postSamplingHooks.ts:20-22).
     *
     * <p>[H12] 参数从无参 → {@link PostSamplingContext} (REPLHookContext 等价),
     * 让 hook 能读取消息历史 / querySource 做门控。
     */
    public interface PostSamplingHook {
        void onSampled(PostSamplingContext context);
    }

    private static final List<PostSamplingHook> HOOKS = new ArrayList<>();

    /**
     * 单线程 daemon 执行器 · [IMP-HOOKS-S7] 批内/跨批串行链的载体（D10）。
     * 静态常驻：多个 executeAll 批次共享同一线程 → 按到达序 FIFO、互不重叠
     * （CC sequential 队列等价）。测试通过 join future + clearAll 保证隔离。
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "post-sampling-hooks");
        t.setDaemon(true);
        return t;
    });

    private PostSamplingHookRegistry() {}

    /** Register a hook (no-op if null). Not thread-safe (CC same). */
    public static void register(PostSamplingHook hook) {
        if (hook != null) HOOKS.add(hook);
    }

    /** Test-only: clear all hooks. */
    public static void clearAll() {
        HOOKS.clear();
    }

    /**
     * Execute all registered hooks in registration order (serial, isolated).
     *
     * <p>[IMP-HOOKS-S7] 语义与 CC {@code executePostSamplingHooks}（postSamplingHooks.ts:45-70）
     * 逐条对应：
     * <ol>
     *   <li>注册序串行：每个 hook 作为一个任务提交到单线程执行器，前一条完成后
     *       {@link #runNext} 才提交下一条 —— 批内区间互不重叠（CC for...of + await）；</li>
     *   <li>无条件隔离：hook 抛任何 {@link Throwable}（含 AssertionError 等 Error 子类）
     *       → {@code errorLogger.accept(i, ex)}（null → 内部 slf4j warn 兜底）→ 继续下一条
     *       （CC catch(error) → logError(toError(error)) → continue）；</li>
     *   <li>活数组遍历：{@code i >= HOOKS.size()} 逐条重读 —— 执行中 register 的新 hook
     *       本轮可见（CC ArrayIterator 活数组语义）；</li>
     *   <li>返回 future 恒正常完成（CC async 函数 resolve；错误永不传播）。</li>
     * </ol>
     *
     * <p>调用方（LlmAgentLoop 每轮 sampling 后）fire-and-forget；测试必须 join 等待确定性。
     *
     * <p><b>[SM-05] 串行化（DRIFT-3）</b>：CC postSamplingHooks.ts:62-68 {@code for (const hook
     * of postSamplingHooks) { await hook(context) }} —— 逐 hook await（串行），非并行；
     * 旧实现并行 CompletableFuture 使提取 hook 相邻轮次可并发（双 fork 同写 summary.md）。
     * 现改为单 CompletableFuture 内按注册序串行执行（fire-and-forget 语义不变）。
     *
     * @param context     [H12] REPLHookContext 等价 · postSamplingHooks.ts:53-60
     * @param errorLogger hook 索引 + 异常消费器（可 null；null → 内部 slf4j 兜底日志）
     * @return 全部 hook 完成后的 CompletableFuture（恒正常完成）
     */
    public static CompletableFuture<Void> executeAll(
            PostSamplingContext context,
            BiConsumer<Integer, Throwable> errorLogger) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        runNext(0, context, errorLogger, done);
        return done;
    }

    /**
     * 串行链下一跳 · 提交一个执行步骤到单线程执行器，完成后递归提交下一跳。
     *
     * @param i           当前 hook 索引（注册序）
     * @param context     hook 上下文
     * @param errorLogger 异常消费器（可 null）
     * @param done        批次完成 future（最后一个 hook 后 complete）
     */
    private static void runNext(
            int i,
            PostSamplingContext context,
            BiConsumer<Integer, Throwable> errorLogger,
            CompletableFuture<Void> done) {
        try {
            EXECUTOR.execute(() -> {
                try {
                    // 活数组遍历：size() 逐次重读（D9）—— 执行中 register 的 hook 本轮可见
                    if (i >= HOOKS.size()) {
                        done.complete(null);
                        return;
                    }
                    PostSamplingHook hook = HOOKS.get(i);
                    try {
                        hook.onSampled(context);
                    } catch (Throwable ex) {
                        // D2 无条件隔离 · CC postSamplingHooks.ts:65-67 catch(error) → logError → continue
                        if (errorLogger != null) {
                            errorLogger.accept(i, ex);
                        } else {
                            log.warn("[PostSamplingHook] hook#{} 异常已隔离: {}", i, ex.toString());
                        }
                    }
                    runNext(i + 1, context, errorLogger, done);
                } catch (Throwable fatal) {
                    // 防御：执行器关闭/拒绝时 future 不能悬挂（CC 无取消路径，恒 resolve）
                    done.completeExceptionally(fatal);
                }
            });
        } catch (Throwable fatal) {
            // EXECUTOR.execute 本身抛 RejectedExecutionException 等 → future 显式失败
            done.completeExceptionally(fatal);
        }
    }
}
