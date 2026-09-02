package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.team.SwarmPermissionSync;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Swarm worker 权限 handler · 对齐 CC {@code hooks/toolPermission/handlers/swarmWorkerHandler.ts:40-156}。
 *
 * <p>当 CC 作为 swarm worker 运行时，权限请求经 mailbox 中继到 leader。L1 语义（grep 自验 CC，不信注释）：
 * <ol>
 *   <li>守卫 {@code !isAgentSwarmsEnabled() || !isSwarmWorker()} → null（fall through 到本地 UI）（:43-45）</li>
 *   <li>BASH_CLASSIFIER → 试 classifier auto-approve（:52-57）—— 启发式已随 O18 删除，恒 null）</li>
 *   <li>createPermissionRequest → <b>先注册 callback 再 send</b>（防 race）（:71-123）</li>
 *   <li>pendingWorkerRequest 指示器 set/clear（:126-133）</li>
 *   <li>abort listener → cancelAndAbort（:137-146）</li>
 *   <li>mailbox 发送抛错 → catch → null（fall through 本地 UI）（:150-155）</li>
 * </ol>
 *
 * <p>L3 (Java idiom)：TS async/await + AbortController → Java CompletableFuture + claim 守卫
 * （{@code AtomicBoolean(false)} + {@code compareAndSet(false,true)}，对齐 CC claim()
 * PermissionContext.ts:88-92）；TS {@code createPermissionRequest} → {@link SwarmPermissionSync}；
 * TS {@code registerPermissionCallback} → {@link SwarmPermissionPoller}；TS {@code sendPermissionRequestViaMailbox}
 * → {@link SwarmPermissionSync#sendPermissionRequestViaMailbox}。
 */
@Component
public final class SwarmWorkerPermissionHandler {

    private static final Logger log = LoggerFactory.getLogger(SwarmWorkerPermissionHandler.class);

    private final Supplier<Boolean> swarmsEnabledSupplier;
    private final Supplier<Boolean> isSwarmWorkerSupplier;
    private final Supplier<Boolean> bashClassifierFeature;
    private final ClassifierRunner classifierRunner;
    private final MailboxSender mailboxSender;
    private final CallbackRegistrar callbackRegistrar;
    private final PendingSetter pendingSetter;
    private final PendingClearer pendingClearer;

    public SwarmWorkerPermissionHandler(Supplier<Boolean> swarmsEnabledSupplier,
                                        Supplier<Boolean> isSwarmWorkerSupplier,
                                        Supplier<Boolean> bashClassifierFeature,
                                        ClassifierRunner classifierRunner,
                                        MailboxSender mailboxSender,
                                        CallbackRegistrar callbackRegistrar,
                                        PendingSetter pendingSetter,
                                        PendingClearer pendingClearer) {
        this.swarmsEnabledSupplier = Objects.requireNonNull(swarmsEnabledSupplier);
        this.isSwarmWorkerSupplier = Objects.requireNonNull(isSwarmWorkerSupplier);
        this.bashClassifierFeature = Objects.requireNonNull(bashClassifierFeature);
        this.classifierRunner = classifierRunner;
        this.mailboxSender = mailboxSender;
        this.callbackRegistrar = callbackRegistrar;
        this.pendingSetter = pendingSetter;
        this.pendingClearer = pendingClearer;
    }

    /**
     * Spring 生产构造器 · 接线真实 swarm 链（RV-07 整链对齐）。
     *
     * <ol>
     *   <li>swarms 启用 → {@link TaskSystemConfig#isAgentSwarmsEnabled()}（对齐 CC agentSwarmsEnabled.ts）</li>
     *   <li>isSwarmWorker → {@link SwarmPermissionSync#isSwarmWorker()}（team 上下文派生，对齐 CC
     *       permissionSync.ts:596-601，替换旧 {@code nexusai.swarm.worker} 属性）</li>
     *   <li>BASH_CLASSIFIER → {@link BashClassifierFeature}（[OPD-WF7-02-01] runner 已完整接线
     *       awaitClassifierAutoApproval 等价，非恒 null stub；feature 关 → tryClassifier 返回 null）</li>
     *   <li>mailbox 转发 → {@link SwarmPermissionSync#sendPermissionRequestViaMailbox}（真实文件/mailbox）</li>
     *   <li>回调注册 → {@link SwarmPermissionPoller#registerPermissionCallback}（真实 registry）</li>
     *   <li>pending 指示器 → Java 无 UI appState 载体（CC setAppState），no-op + warn（不假实现）</li>
     * </ol>
     *
     * @param bashClassifierFeature BASH_CLASSIFIER 特性开关（bean，可为 null → 关闭）
     */
    @Autowired
    public SwarmWorkerPermissionHandler(
            @Autowired(required = false) BashClassifierFeature bashClassifierFeature) {
        this(
            TaskSystemConfig::isAgentSwarmsEnabled,
            SwarmPermissionSync::isSwarmWorker,
            bashClassifierFeature != null ? bashClassifierFeature::isEnabled : () -> false,
            (pending, updatedInput, toolUseId) -> {
                // [OPD-WF7-02-01] awaitClassifierAutoApproval 等价 —— 完整 runner（非恒 null stub）:
                //   CC swarmWorkerHandler.ts:52-57 — feature 开时先试 classifier auto-approve 再转发 leader。
                boolean featureOn = bashClassifierFeature != null && bashClassifierFeature.isEnabled();
                if (log.isDebugEnabled()) {
                    log.debug("[SwarmWorker] tryClassifier: featureOn={} pending={}",
                        featureOn, pending != null ? pending.command() : null);
                }
                return tryClassifier(pending, updatedInput, featureOn);
            },
            request -> {
                // 身份派生（teamName/workerName）→ 完整 SwarmPermissionRequest → mailbox 转发
                SwarmPermissionSync.SwarmPermissionRequest full = SwarmPermissionSync.createPermissionRequest(
                    request.id(), request.toolName(), request.toolUseId(), request.input(),
                    request.description(), List.of());
                SwarmPermissionSync.sendPermissionRequestViaMailbox(full);
            },
            (requestId, toolUseId, onAllow, onReject) ->
                    SwarmPermissionPoller.registerPermissionCallback(requestId, toolUseId, onAllow, onReject),
            pending -> { /* Java 无 UI pendingWorkerRequest 载体（CC setAppState），no-op */ },
            () -> { /* Java 无 UI pendingWorkerRequest 载体（CC setAppState），no-op */ });
    }

    /**
     * PermissionDecision 最小子集 · 对齐 CC PermissionDecision（behavior + updatedInput +
     * permissionUpdates；swarmWorkerHandler.ts:84-97 handleUserAllow(finalInput, permissionUpdates, ...)）。
     *
     * @param behavior           CC original: behavior（allow/reject）
     * @param updatedInput       CC original: updatedInput / finalInput
     * @param permissionUpdates  CC original: permissionUpdates（[REV-FIX-6 gap3] leader allow 时透传的
     *                           「Always allow」规则，决策下游 apply；无则空列表）
     */
    public record PermissionDecision(String behavior, Map<String, Object> updatedInput,
                                     List<Object> permissionUpdates) {
        public static PermissionDecision allow(Map<String, Object> updatedInput) {
            return allow(updatedInput, List.of());
        }
        public static PermissionDecision allow(Map<String, Object> updatedInput, List<Object> permissionUpdates) {
            return new PermissionDecision("allow", updatedInput, permissionUpdates);
        }
        public static PermissionDecision reject() {
            return new PermissionDecision("reject", null, List.of());
        }
    }

    /** Pending indicator · 对齐 CC pendingWorkerRequest（swarmWorkerHandler.ts:126-133）。 */
    public record PendingRequest(String toolName, String toolUseId, String description) {}

    /**
     * Classifier runner (注入).
     *
     * <p>[OPD-WF7-02-01] 签名升级：CC swarmWorkerHandler.ts:52-57
     * {@code ctx.tryClassifier?.(params.pendingClassifierCheck, updatedInput)} 携带完整
     * PendingClassifierCheck（command/cwd/descriptions）+ toolUseID（matchedRule 登记）。
     */
    @FunctionalInterface
    public interface ClassifierRunner {
        PermissionDecision tryClassifier(PermissionResult.PendingClassifierCheck pendingCheck,
                                         Map<String, Object> updatedInput,
                                         String toolUseId);
    }

    /**
     * 轻量权限请求 · 对齐 CC createPermissionRequest 的 worker 侧最小子集（id/toolName/toolUseId/
     * input/description）。身份派生（teamName/workerName）由生产 {@link #MailboxSender} 完成。
     */
    public record PermissionRequest(String id, String toolName, String toolUseId,
                                     Map<String, Object> input, String description) {}

    /** Mailbox sender (注入) · 对齐 CC sendPermissionRequestViaMailbox（fire-and-forget）。 */
    @FunctionalInterface
    public interface MailboxSender {
        void send(PermissionRequest request);
    }

    /** Callback registrar (注入) · 对齐 CC registerPermissionCallback。 */
    @FunctionalInterface
    public interface CallbackRegistrar {
        /**
         * 注册权限回调 · 对齐 CC registerPermissionCallback（swarmWorkerHandler.ts:81-120）。
         *
         * @param onAllow CC onAllow(allowedInput, permissionUpdates, ...) — [REV-FIX-6 gap3] 经
         *                {@link SwarmPermissionPoller.AllowResult} 透传 permissionUpdates
         */
        void register(String requestId, String toolUseId,
                      Consumer<SwarmPermissionPoller.AllowResult> onAllow,
                      Consumer<String> onReject);
    }

    /** Pending setter/clearer (注入). */
    @FunctionalInterface
    public interface PendingSetter { void set(PendingRequest pending); }
    @FunctionalInterface
    public interface PendingClearer { void clear(); }

    /** CC handleSwarmWorkerPermission — 主链（:40-156）。 */
    public CompletableFuture<PermissionDecision> handle(Params params) {
        if (!swarmsEnabledSupplier.get() || !isSwarmWorkerSupplier.get()) {
            return null;
        }

        // 1. 试 classifier auto-approve（CC :52-57）
        //    [OPD-WF7-02-01] 完整 runner + [D7 对齐] 无内层 catch：CC swarmWorkerHandler.ts
        //    的 classifier 抛错向外传播 → useCanUseTool catch → cancelAndAbort；Java gate
        //    外层 try/catch（:1197）等价处理。旧内层 log.warn 后继续 mailbox 流为行为漂移（删除）。
        if (bashClassifierFeature.get() && classifierRunner != null) {
            PermissionDecision classifierResult = classifierRunner.tryClassifier(
                params.pendingClassifierCheck(), params.updatedInput(), params.toolUseId());
            if (classifierResult != null) {
                return CompletableFuture.completedFuture(classifierResult);
            }
        }

        // 2. create request → register callback（先于 send）→ send via mailbox → pending
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        // CC createResolveOnce.claim()（PermissionContext.ts:88-92）：false=未 claim，compareAndSet(false,true)
        AtomicBoolean claimed = new AtomicBoolean(false);

        try {
            // 轻量请求：id 由 generateRequestId 生成，身份派生交生产 MailboxSender（CC :71-77 createPermissionRequest）
            PermissionRequest request = new PermissionRequest(
                SwarmPermissionSync.generateRequestId(),
                params.toolName(), params.toolUseId(), params.input(), params.description());

            // Register callback BEFORE sending request（CC :79-120 防 race）
            callbackRegistrar.register(request.id(), request.toolUseId(),
                outcome -> {
                    if (!claimed.compareAndSet(false, true)) return;
                    pendingClearer.clear();
                    // CC :94-97 finalInput = allowedInput 非空 ? allowedInput : 原 input
                    Map<String, Object> finalInput =
                        (outcome.updatedInput() != null && !outcome.updatedInput().isEmpty())
                            ? outcome.updatedInput() : params.input();
                    // [REV-FIX-6 gap3] 透传 permissionUpdates（CC handleUserAllow(finalInput, permissionUpdates, ...)）
                    decisionFuture.complete(PermissionDecision.allow(finalInput, outcome.permissionUpdates()));
                },
                feedback -> {
                    if (!claimed.compareAndSet(false, true)) return;
                    pendingClearer.clear();
                    decisionFuture.complete(PermissionDecision.reject());
                });

            // Now that callback is registered, send request（CC :123）
            mailboxSender.send(request);

            // pending 指示器（CC :126-133）
            pendingSetter.set(new PendingRequest(params.toolName(), params.toolUseId(), params.description()));
        } catch (Exception e) {
            // CC :150-155 — mailbox 发送失败 fall through 到本地 UI
            log.warn("[SwarmWorker] mailbox send failed, fall through to local: {}", e.getMessage());
            return null;
        }
        return decisionFuture;
    }

    public record Params(String toolName, String toolUseId, Map<String, Object> input,
                          String description, Map<String, Object> updatedInput,
                          PermissionResult.PendingClassifierCheck pendingClassifierCheck) {}

    /**
     * [OPD-WF7-02-01] swarm classifier runner · 对齐 CC PermissionContext.ts:176-215
     * {@code tryClassifier}（awaitClassifierAutoApproval 等价）。
     *
     * <p>CC swarmWorkerHandler.ts:52-57：feature 开时先试 classifier auto-approve（bash），
     * 命中即 return classifierResult（不再转发 leader）；未命中 fall through mailbox 流。
     * pending 为空（非 Bash / 无投机载体）→ null。
     *
     * @param pendingCheck         待分类器检查（command/cwd/descriptions；null → null）
     * @param updatedInput         CC original: updatedInput（tryClassifier 第 2 参）
     * @param bashClassifierEnabled CC feature('BASH_CLASSIFIER') 等价门
     * @return allow 决策（swarm PermissionDecision 无 decisionReason/source 字段，仅 behavior+updatedInput）；
     *         未命中 / 门关 / 无 pending → null
     */
    static PermissionDecision tryClassifier(PermissionResult.PendingClassifierCheck pendingCheck,
                                            Map<String, Object> updatedInput,
                                            boolean bashClassifierEnabled) {
        // CC PermissionContext.ts:180-182 — 无 pending → null（非 Bash 工具无 pending 载体）
        if (pendingCheck == null) {
            return null;
        }
        SpeculativeClassifier.SpeculativeClassifierResult classifierResult =
            CoordinatorPermissionHandler.awaitClassifierAutoApproval(
                pendingCheck.command(), pendingCheck.cwd(), pendingCheck.descriptions());
        // feature('BASH_CLASSIFIER') && matches && confidence==='high'（bashPermissions.ts:1575-1585）
        if (classifierResult == null || !classifierResult.matches()
                || !"high".equals(classifierResult.confidence())
                || !bashClassifierEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("[SwarmWorker] classifier 未命中（stub 恒 matches:false）或门关: command={}",
                    pendingCheck.command());
            }
            return null;
        }
        // CC :202-212 allow(updatedInput ?? input) — updatedInput null → gate 回退原 input
        return PermissionDecision.allow(updatedInput);
    }
}
