package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async hook 注册表 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks/AsyncHookRegistry.ts} (309 行全文).
 *
 * <p><b>WHY (Session H10)</b>: async hook 后台化后, 进程结果必须有人轮询读取并交付 —
 * CC 用 {@code pendingHooks} Map + {@code checkForAsyncHookResponses} 轮询 (由
 * attachments.ts:3465 调用), Java 端等价实现:
 * <ul>
 *   <li>{@link #registerPendingAsyncHook} — 注册 (CC :30-83)</li>
 *   <li>{@link #checkForAsyncHookResponses} — 轮询检查 (CC :113-268): 快照迭代 +
 *       allSettled 异常隔离 → Java 逐 hook try/catch</li>
 *   <li>{@link #finalizeHook} — 完成收尾 + response 事件 (CC :91-111)</li>
 *   <li>{@link #removeDeliveredAsyncHooks} — 交付失败清理 (CC :270-279)</li>
 *   <li>{@link #finalizePendingAsyncHooks} — 应用退出收尾 (CC :281-301)</li>
 *   <li>{@link #clearAllAsyncHooks} — 测试工具 (CC :304-309)</li>
 * </ul>
 *
 * <p><b>事件驱动 drain (T3-⊕1, 决策 09#4)</b>: CC 无任何定时轮询 — async 响应由主线程
 * 每 LLM 调用前主动 {@code checkForAsyncHookResponses()} (attachments.ts:3465) 消费;
 * Java 端等价为 {@link HookRegistry#collectAsyncHookResponses()} 委托 (LLM loop 每轮
 * drain, LlmAgentLoop.java:3127). 本类不持有调度器/消费者注入, 全部调用为主动 API.
 *
 * <p><b>并发模型</b>: pendingHooks 用 ConcurrentHashMap; checkForAsyncHookResponses
 * 先快照再处理 (CC :141-142), 处理中改 map 不干扰迭代. 单例 @Component.
 *
 * <p><b>日志</b>: slf4j + 中文, debug 用 {@code if (log.isDebugEnabled())} 包裹.
 *
 * @see PendingAsyncHook
 * @see HookEventBus
 * @since Session H10
 */
@Component
public class AsyncHookRegistry {

    private static final Logger log = LoggerFactory.getLogger(AsyncHookRegistry.class);

    /** 默认 asyncTimeout · 对齐 CC {@code asyncResponse.asyncTimeout || 15000} (AsyncHookRegistry.ts:51). */
    public static final long DEFAULT_ASYNC_TIMEOUT_MS = 15000L;

    /**
     * 单个 async hook 的交付结果 · 对齐 CC checkForAsyncHookResponses 返回值
     * (AsyncHookRegistry.ts:113-125 数组元素).
     *
     * @param processId CC original: processId (:115)
     * @param response  CC original: response (:116); 逐行解析出的 sync 响应 (空对象 = 无)
     * @param hookName  CC original: hookName (:117)
     * @param hookEvent CC original: hookEvent (:118); CC 事件名 (PascalCase 字符串)
     * @param toolName  CC original: toolName (:119); nullable
     * @param pluginId  CC original: pluginId (:120); nullable
     * @param stdout    CC original: stdout (:121); hook 输出全量
     * @param stderr    CC original: stderr (:122)
     * @param exitCode  CC original: exitCode (:123); nullable
     */
    public record AsyncHookResponse(
        String processId,
        HookJSONOutput.SyncHookOutput response,
        String hookName,
        String hookEvent,
        String toolName,
        String pluginId,
        String stdout,
        String stderr,
        Integer exitCode
    ) {
    }

    /** 注册池 · 对齐 CC {@code pendingHooks} (AsyncHookRegistry.ts:28 Map). */
    private final Map<String, PendingAsyncHook> pendingHooks = new ConcurrentHashMap<>();

    /** 事件总线 · 进度定时器 + response 广播 (CC hookEvents.ts 各 emit). */
    private final HookEventBus hookEventBus;

    private final ObjectMapper objectMapper;
    /**
     * 构造注入 · 事件总线必填 (AsyncHookRegistry 的全部对外信号走总线).
     *
     * <p>T3-⊕1 (决策 09#4): 轮询消费者注入与调度器已删除 — CC 事件驱动无轮询
     * (attachments.ts:3465 主动 drain), 主动调用 API 即全部消费面.
     */
    @Autowired
    public AsyncHookRegistry(HookEventBus hookEventBus) {
        this.hookEventBus = hookEventBus;
        // E4-1 (OPD-WF2-PRS-03): FAIL_ON_TRAILING_TOKENS — 对齐 CC jsonParse = JSON.parse
        // (slowOperations.ts:204-211) 严格拒绝尾随 token. Jackson readTree 默认只取首值不校验
        // EOF ({"continue":true}{"x":1} 交付首值 / "{} extra" 交付 {}) — 与 CC 抛 SyntaxError
        // → 跳过该行 方向相反. 本字段仅 parseSyncResponseFromLines 使用, 开启后尾随内容行被
        // catch 拒绝 → 扫描下一行, 精确镜像 CC AsyncHookRegistry.ts:198/:206-210.
        this.objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 / 查询 (CC :30-89)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * <p>流程 (CC 行号): timeout = {@code asyncResponse.asyncTimeout || 15000} (:51) →
     * 先 startHookProgressInterval 再入池 (:55-68 顺序 — 进度定时器的 getOutput 闭包
     * 捕获 processId, 首个 tick 时已入池).
     *
     * <p><b>started 语义 (hooks_v3 决策 2-3 / D-WF5-05)</b>: CC 注册侧<b>不发 started</b> —
     * {@code registerPendingAsyncHook} (AsyncHookRegistry.ts:30-83) 无任何 emitHookStarted;
     * started 在 hook <b>执行入口</b> emit (hooks.ts:2297 http / :2446 command — executeHooks
     * 层 emitHookStarted 先于 execHttpHook/execCommandHook), 与 {@link #finalizeHook} 的
     * emitHookResponse 同 hookId (AsyncHookRegistry.ts:14/:102, started→response 配对).
     * Java 端等价执行入口为 CommandHookExecutor (sync :578 / asyncRewake :786) + HookRegistry
     * 工具链 (:694) — 注册侧不重复发 started, 消除「执行入口 S + 注册侧 R」双发导致的
     * 孤儿 started (WF5-02 ⊕-1 / S4). 进度定时器仍在本注册侧启动 (CC :55-68).
     *
     * @param processId    CC original: processId (:31); "async_hook_" + pid
     * @param hookId       CC original: hookId (:32); UUID, 事件总线关联标识 (response 关联)
     * @param asyncResponse CC original: asyncResponse (:33); async 声明 (含 asyncTimeout)
     * @param hookName     CC original: hookName (:34)
     * @param hookEvent    CC original: hookEvent (:35); CC 事件名 (PascalCase 字符串)
     * @param command      CC original: command (:36)
     * @param shellCommand CC original: shellCommand (:37); 进程抽象, 可 null (无进程 → 直接移除)
     * @param toolName     CC original: toolName (:38); nullable
     * @param pluginId     CC original: pluginId (:39); nullable
     */
    public void registerPendingAsyncHook(String processId, String hookId,
                                         HookJSONOutput.AsyncHookOutput asyncResponse,
                                         String hookName, String hookEvent, String command,
                                         PendingAsyncHook.AsyncHookProcess shellCommand,
                                         String toolName, String pluginId) {
        // CC :51 asyncResponse.asyncTimeout || 15000 — H10-4: 只存不用 (CC 无消费点)
        long timeout = asyncResponse != null && asyncResponse.asyncTimeout() != null
            ? asyncResponse.asyncTimeout() : DEFAULT_ASYNC_TIMEOUT_MS;
        if (log.isInfoEnabled()) {
            log.info("HOOK 注册 async hook {} ({}) timeout={}ms", processId, hookName, timeout);
        }
        // CC :55-68: 先启动进度定时器 (getOutput 闭包按 processId 从池中取)
        Runnable stopProgressInterval = hookEventBus.startHookProgressInterval(
            hookId, hookName, hookEvent,
            () -> {
                PendingAsyncHook hook = pendingHooks.get(processId);
                if (hook == null || hook.shellCommand() == null) {
                    // CC :60-63: 无 taskOutput → 空输出
                    return new HookEventBus.HookProgressOutput("", "", "");
                }
                String stdout = hook.shellCommand().stdout();
                String stderr = hook.shellCommand().stderr();
                String out = (stdout != null ? stdout : "") + (stderr != null ? stderr : "");
                return new HookEventBus.HookProgressOutput(stdout, stderr, out);
            });
        // CC :69-82: 入池 (responseAttachmentSent 初始 false)
        pendingHooks.put(processId, new PendingAsyncHook(
            processId, hookId, hookName, hookEvent, toolName, pluginId,
            System.currentTimeMillis(), timeout, command, false, shellCommand, stopProgressInterval));
    }

    /**
     * 未交付的挂起 hook · 等价 CC {@code getPendingAsyncHooks} (AsyncHookRegistry.ts:85-89):
     * 过滤 {@code responseAttachmentSent} (已标记交付的不再视为挂起).
     *
     * @return 未交付 hook 快照 (按注册顺序的集合视图)
     */
    public List<PendingAsyncHook> getPendingAsyncHooks() {
        return pendingHooks.values().stream()
            .filter(hook -> !hook.responseAttachmentSent())
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 轮询检查 (CC :113-268)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 检查全部挂起 hook 并交付完成的响应 · 等价 CC
     * {@code checkForAsyncHookResponses} (AsyncHookRegistry.ts:113-268).
     *
     * <p>关键语义 (CC 行号):
     * <ul>
     *   <li>先快照再处理 (:141-142) — 处理中会改 map</li>
     *   <li>逐 hook try/catch 隔离异常 (:144, :236-246 allSettled) — 单个失败不
     *       影响其他 hook 的已应用副作用 (responseAttachmentSent / finalizeHook)</li>
     *   <li>无 shellCommand → remove (:152-158); killed → remove + cleanup (:162-169);
     *       未 completed → skip (:171-173); responseAttachmentSent || 空 stdout → remove
     *       (:175-181)</li>
     *   <li>逐行解析 (:183-212): 首个 {@code {} 开头行, 不含 'async' 键 → sync 响应,
     *       break; async 键行跳过</li>
     *   <li>responseAttachmentSent=true → finalizeHook (:214-215)</li>
     *   <li>SessionStart 完成 → sessionStartCompleted (:220, :253) → invalidateSessionEnvCache
     *       (:257-262) — Java 无 session env cache, 仅日志对齐 (concern H10-3)</li>
     * </ul>
     *
     * @return 交付的响应列表 (CC :126-136 数组等价)
     */
    public List<AsyncHookResponse> checkForAsyncHookResponses() {
        List<AsyncHookResponse> responses = new ArrayList<>();
        if (log.isDebugEnabled()) {
            log.debug("HOOK 注册表共 {} 个 async hook 待检查", pendingHooks.size());
        }
        // CC :141-142: 快照 — 处理过程中 map 会被删改
        List<PendingAsyncHook> hooks = new ArrayList<>(pendingHooks.values());
        boolean sessionStartCompleted = false;
        for (PendingAsyncHook hook : hooks) {
            AsyncHookResponse response;
            try {
                response = checkOneHook(hook);
            } catch (Exception e) {
                // CC :236-246 allSettled 隔离: 单 hook 失败仅记录, 已应用的副作用不回滚
                if (log.isErrorEnabled()) {
                    log.error("HOOK 检查 async hook {} 抛异常: {}", hook.processId(), e.toString());
                }
                continue;
            }
            if (response == null) {
                continue;
            }
            responses.add(response);
            // CC :253 isSessionStart: hookEvent === 'SessionStart'
            if ("SessionStart".equals(response.hookEvent())) {
                sessionStartCompleted = true;
            }
        }
        if (sessionStartCompleted) {
            // CC :257-262 invalidateSessionEnvCache — Java 无 session env cache 机制,
            // 仅日志对齐 (concern H10-3)
            if (log.isInfoEnabled()) {
                log.info("HOOK SessionStart hook 完成 → 使 session env 缓存失效 (Java 无此机制, 仅日志对齐 CC)");
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK 本轮检查返回 {} 个 async 响应", responses.size());
        }
        return responses;
    }

    /**
     * 单个 hook 的检查与交付 · CC :145-233 单回调等价.
     *
     * @return 交付的响应; null = 未交付 (remove/skip)
     */
    private AsyncHookResponse checkOneHook(PendingAsyncHook hook) {
        PendingAsyncHook.AsyncHookProcess shellCommand = hook.shellCommand();
        String stdout = shellCommand != null && shellCommand.stdout() != null ? shellCommand.stdout() : "";
        String stderr = shellCommand != null && shellCommand.stderr() != null ? shellCommand.stderr() : "";
        if (log.isDebugEnabled()) {
            log.debug("HOOK 检查 hook {} ({}) - responseAttachmentSent: {}, stdout 长度: {}",
                hook.processId(), hook.hookName(), hook.responseAttachmentSent(), stdout.length());
        }

        // CC :152-158: 无 shellCommand → remove (stopProgressInterval + 出池)
        if (shellCommand == null) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK hook {} 无进程包装, 从注册表移除", hook.processId());
            }
            hook.stopProgressInterval().run();
            pendingHooks.remove(hook.processId());
            return null;
        }

        // CC :162-169: killed → remove + cleanup
        if ("killed".equals(shellCommand.status())) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK hook {} 状态 killed, 移除并清理", hook.processId());
            }
            hook.stopProgressInterval().run();
            shellCommand.cleanup();
            pendingHooks.remove(hook.processId());
            return null;
        }

        // CC :171-173: 未 completed → skip, 留在池中
        if (!"completed".equals(shellCommand.status())) {
            return null;
        }

        // CC :175-181: 已交付过 || stdout 空 → remove (无响应可交付)
        if (hook.responseAttachmentSent() || stdout.trim().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK hook {} 已交付或 stdout 为空, 移除 (stdout={} bytes)",
                    hook.processId(), stdout.length());
            }
            hook.stopProgressInterval().run();
            pendingHooks.remove(hook.processId());
            return null;
        }

        // CC :188-189: execResult.code — 已确认 completed 才可取
        int exitCode = shellCommand.exitCode();

        // CC :191-212: 逐行解析 sync 响应 (async 声明行跳过)
        HookJSONOutput.SyncHookOutput response = parseSyncResponseFromLines(stdout, hook.processId());

        // CC :214-215: 先标记已交付再 finalize — finalizeHook 异常时 flag 不回滚,
        // 由 removeDeliveredAsyncHooks 清理 (CC :236-246 allSettled 语义)
        hook.setResponseAttachmentSent(true);
        finalizeHook(hook, exitCode, exitCode == 0
            ? HookEventBus.HookOutcome.SUCCESS : HookEventBus.HookOutcome.ERROR);

        // CC :217-232 / :250-254: 收集响应 + 出池
        pendingHooks.remove(hook.processId());
        return new AsyncHookResponse(hook.processId(), response, hook.hookName(), hook.hookEvent(),
            hook.toolName(), hook.pluginId(), stdout, stderr, exitCode);
    }

    /**
     * 逐行解析 sync 响应 · 等价 CC :191-212.
     *
     * <p>WHY: async hook 的 stdout 首行是 {@code {"async":true}} 声明, 之后的某行才是
     * sync 响应. 判定 (CC :199): {@code !('async' in parsed)} — <b>键存在性</b>判定
     * (含 async:false 的行同样跳过, 镜像 CC 不按真值判定).
     *
     * <p><b>raw-accept+break (OPD-WF2-PRS-06)</b>: CC :199-205 {@code response = parsed}
     * 直接赋 raw parsed, <b>不做 schema 校验</b> — 首个可解析非 async 行即使类型偏差
     * (如 {@code {"continue":"false"}}) 也接受为响应并 break 停止扫描. 本方法改用
     * {@link HookOutputParser#deserializeHookJSONOutputLenient} (仅 readTree + async 键
     * 存在性, 类型偏差字段静默置 null); 旧实现走严格 {@code deserializeHookJSONOutput} →
     * 类型偏差行 throw → 跳过 → 交付后续行/空, 方向与 CC 相反.
     *
     * <p><b>尾随 token 拒绝 (E4-1 / OPD-WF2-PRS-03)</b>: CC {@code jsonParse} = {@code JSON.parse}
     * (slowOperations.ts:204-211) 严格拒绝尾随 token — {@code {"continue":true}{"x":1}} /
     * {@code {} extra} 抛 SyntaxError → 该行跳过 ({@code catch} → 下一行, :206-210). 本方法
     * 的 {@code objectMapper} 已开启 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}
     * (构造器 :98), 使 readTree 对尾随内容行抛异常 → 落 {@code catch} → 扫描下一行, 与 CC
     * 逐行 skip-and-continue 语义一致; 干净 JSON 行不受影响.
     *
     * @param stdout  hook 输出全量
     * @param processId 日志关联
     * @return 首个可解析非 async 行 (raw 接受); 无 → 空 SyncHookOutput (CC :191 {@code let response = {}})
     */
    private HookJSONOutput.SyncHookOutput parseSyncResponseFromLines(String stdout, String processId) {
        if (log.isDebugEnabled()) {
            log.debug("HOOK 处理 {} 的 {} 行 stdout", processId, stdout.split("\n", -1).length);
        }
        for (String line : stdout.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(trimmed);
                if (!parsed.has("async")) {
                    // CC :199-205 raw-accept+break: 首个可解析非 async 行直接接受为响应,
                    // 不做 schema 校验 (类型偏差字段静默置 null), 立即返回
                    HookJSONOutput.SyncHookOutput sync =
                        HookOutputParser.deserializeHookJSONOutputLenient(parsed);
                    if (log.isDebugEnabled()) {
                        log.debug("HOOK 找到 {} 的 sync 响应: {}", processId, trimmed.substring(0, Math.min(100, trimmed.length())));
                    }
                    return sync;
                }
                // async 键行 → 跳过 (CC :199 之后自然落到下一行)
            } catch (Exception e) {
                // CC :206-210: 解析失败仅记录, 继续下一行
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 解析 {} 的 JSON 行失败: {}", processId, trimmed);
                }
            }
        }
        // CC :191: 无 sync 行 → 空对象
        return new HookJSONOutput.SyncHookOutput(null, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // finalize / 清理 (CC :91-111, :270-309)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 完成收尾 · 等价 CC {@code finalizeHook} (AsyncHookRegistry.ts:91-111):
     * stopProgressInterval → 读 stdout/stderr → cleanup → emitHookResponse
     * ({@code outcome: exitCode===0?'success':'error'}, :94).
     *
     * @param hook    目标 hook
     * @param exitCode 退出码
     * @param outcome  CC 三值 outcome (:94)
     */
    private void finalizeHook(PendingAsyncHook hook, int exitCode, HookEventBus.HookOutcome outcome) {
        hook.stopProgressInterval().run();
        PendingAsyncHook.AsyncHookProcess shellCommand = hook.shellCommand();
        String stdout = shellCommand != null && shellCommand.stdout() != null ? shellCommand.stdout() : "";
        String stderr = shellCommand != null && shellCommand.stderr() != null ? shellCommand.stderr() : "";
        if (shellCommand != null) {
            shellCommand.cleanup();
        }
        // CC :101-110 emitHookResponse — Java 端由总线广播 (shouldEmit 不过则仅日志)
        hookEventBus.emitHookResponse(new HookEventBus.HookResponseData(
            hook.hookId(), hook.hookName(), hook.hookEvent(),
            stdout + stderr, stdout, stderr, exitCode, outcome));
        if (log.isInfoEnabled()) {
            log.info("HOOK finalize async hook {} ({}) exit={} outcome={}",
                hook.processId(), hook.hookName(), exitCode, outcome.ccName());
        }
    }

    /**
     * 移除已交付 hook · 等价 CC {@code removeDeliveredAsyncHooks} (AsyncHookRegistry.ts:270-279):
     * 仅删 {@code responseAttachmentSent === true} 的 — 交付失败 (finalizeHook 抛异常) 残留
     * 的 hook 由此清理; 未交付 hook 不能误删 (结果还在路上).
     *
     * @param processIds CC original: processIds (:270)
     */
    public void removeDeliveredAsyncHooks(List<String> processIds) {
        for (String processId : processIds) {
            PendingAsyncHook hook = pendingHooks.get(processId);
            if (hook != null && hook.responseAttachmentSent()) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 移除已交付 hook {}", processId);
                }
                hook.stopProgressInterval().run();
                pendingHooks.remove(processId);
            }
        }
    }

    /**
     * 收尾全部挂起 hook · 等价 CC {@code finalizePendingAsyncHooks} (AsyncHookRegistry.ts:281-301):
     * completed → 按真实退出码 finalize (0→success, 其他→error); 否则未 killed 先 kill,
     * finalize(1, 'cancelled'); 最后清池.
     *
     * <p>WHY: 应用退出/会话结束必须收尾 — 挂起进程不 kill = 进程泄漏; 不 finalize =
     * 下游永远等不到 response.
     */
    public void finalizePendingAsyncHooks() {
        List<PendingAsyncHook> hooks = new ArrayList<>(pendingHooks.values());
        for (PendingAsyncHook hook : hooks) {
            try {
                PendingAsyncHook.AsyncHookProcess shellCommand = hook.shellCommand();
                if (shellCommand != null && "completed".equals(shellCommand.status())) {
                    // CC :285-291: completed → 真实退出码
                    int code = shellCommand.exitCode();
                    finalizeHook(hook, code, code == 0
                        ? HookEventBus.HookOutcome.SUCCESS : HookEventBus.HookOutcome.ERROR);
                } else {
                    // CC :293-295: 未 completed → 未 killed 先 kill
                    if (shellCommand != null && !"killed".equals(shellCommand.status())) {
                        shellCommand.kill();
                    }
                    // CC :296: finalizeHook(1, 'cancelled')
                    finalizeHook(hook, 1, HookEventBus.HookOutcome.CANCELLED);
                }
            } catch (Exception e) {
                if (log.isErrorEnabled()) {
                    log.error("HOOK finalizePendingAsyncHooks 处理 {} 失败: {}",
                        hook.processId(), e.toString());
                }
            }
        }
        // CC :300: 最后清池
        pendingHooks.clear();
    }

    /**
     * 清空全部 hook · 等价 CC {@code clearAllAsyncHooks} (AsyncHookRegistry.ts:304-309)
     * 测试工具: 停全部进度定时器 + 清池.
     */
    public void clearAllAsyncHooks() {
        for (PendingAsyncHook hook : pendingHooks.values()) {
            try {
                hook.stopProgressInterval().run();
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 清池时停进度定时器失败 ({}): {}", hook.processId(), e.toString());
                }
            }
        }
        pendingHooks.clear();
    }

    /**
     * Spring 销毁收尾 · finalize 全部挂起 hook (CC finalizePendingAsyncHooks 由退出路径
     * 调用, AsyncHookRegistry.ts:281-301; 否则退出时挂起进程孤儿化, 下游拿不到 cancelled
     * 信号). T3-⊕1: 轮询调度器已删除, 无额外收尾.
     */
    @PreDestroy
    public void shutdown() {
        try {
            finalizePendingAsyncHooks();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("HOOK 销毁时 finalize 挂起 hook 失败: {}", e.toString());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("AsyncHookRegistry 销毁: 挂起 hook 已收尾");
        }
    }
}
