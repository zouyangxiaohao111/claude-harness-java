package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolNameConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Coordinator worker 权限 handler · 对齐 CC hooks/toolPermission/handlers/coordinatorHandler.ts.
 *
 * <p>L1 语义: Coordinator worker (子代理调度者) 的权限流.
 *            对自动化检查 (hooks + classifier) 顺序 await,
 *            解析则返回 PermissionDecision;否则返回 null 让上层 fall through 到交互 dialog.
 *            Hook 失败 / classifier 抛错 → logError + 返回 null (非阻断,让用户决定).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: handle(...) → Optional&lt;PermissionDecision&gt; (empty=fall through);
 *       2 步顺序: runHooks → tryClassifier (BASH_CLASSIFIER feature 门控);
 *       异常分类: Error → logError 原对象;非 Error → logError(new Error(prefix + str)).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — runHooks 返回非空 → return hookResult;
 *       runHooks 返回空 + classifier 启用 + classifierResult 非空 → return classifierResult;
 *       两者都空 / 异常 → return empty (fall through).</li>
 *   <li><b>A3</b>: 状态机: NOT_RUN → HOOKS_RUN → CLASSIFIER_RUN → DONE;
 *       try/catch 包整段,异常 → logError + fall through (非阻断).</li>
 *   <li><b>A4</b>: hooks 返回 null → 继续 classifier;classifier feature off → 跳过;classifier 返回 null → fall through;
 *       exception in runHooks/tryClassifier → catch + fall through (不抛).</li>
 *   <li><b>A5</b>: 真实场景 — coordinator worker 派发 bash 命令 →
 *       hook (allow rule) → 立刻 return;否则 classifier（O18 已删启发式，
 *       CC 外部构建恒禁用）→ 恒 fall through 到 dialog 让用户决定.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `async/await` → Java 同步调用 (gate 阻塞链, callers async-friendly);
 *                    TS `feature('BASH_CLASSIFIER')` → 注入式 BooleanSupplier;
 *                    TS `ctx.runHooks(...)` → 注入式 HooksRunner ([Session S07] 生产化:
 *                    真实执行 PermissionRequest hooks, 修复 H9-GAP-1/X1);
 *                    TS `ctx.tryClassifier?.(...)` → 注入式 ClassifierRunner (Optional 链式).
 */
@Component
public final class CoordinatorPermissionHandler {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorPermissionHandler.class);
    /** PermissionUpdate → Map 转换用 (permission_suggestions hook 载荷). */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_FACTORY =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final BooleanSupplier bashClassifierFeature;
    private final HooksRunner hooksRunner;
    private final ClassifierRunner classifierRunner;
    private final ErrorLogger errorLogger;

    public CoordinatorPermissionHandler(BooleanSupplier bashClassifierFeature,
                                         HooksRunner hooksRunner,
                                         ClassifierRunner classifierRunner,
                                         ErrorLogger errorLogger) {
        this.bashClassifierFeature = Objects.requireNonNull(bashClassifierFeature);
        this.hooksRunner = Objects.requireNonNull(hooksRunner);
        this.classifierRunner = Objects.requireNonNull(classifierRunner);
        this.errorLogger = Objects.requireNonNull(errorLogger);
    }

    /**
     * [canUseTool v2 + H9 + S07 + OPD-WF7-02-01] Spring 生产构造器 · 接线真实 bash classifier 特性
     * + 完整分类执行器 + PermissionRequest hooksRunner.
     *
     * <p>对齐 CC coordinatorHandler.ts:26-62：
     * <ol>
     *   <li>runHooks — [Session S07] 生产化：{@link #runPermissionRequestHooks} 经
     *       {@link HookRegistry#executeEvent} 真实执行 PermissionRequest hooks 并采纳
     *       {@code permissionRequestResult}（对齐 CC {@code ctx.runHooks},
     *       PermissionContext.ts:216-263；allow → 决策 allow, deny → 决策 deny + hook 消息,
     *       无决策 → null fall through 到 classifier）。修复 H9-GAP-1/X1：
     *       hookRegistry 未接线（null）时保持 fall through（不假实现）。</li>
     *   <li>tryClassifier — [OPD-WF7-02-01] 补完整实现（awaitClassifierAutoApproval 等价,
     *       PermissionContext.ts:176-215）：tool=Bash && pendingClassifierCheck 非空 →
     *       consume 投机结果或 classifyBashCommand → matches && confidence=high && feature 开
     *       → matchedRule 登记 + allow(source=CLASSIFIER, decisionReason=classifier)。feature 关
     *       （缺省 false）→ handle 门控跳过（O53 BashClassifierFeature, 与 OPD-WF4-DEC-05
     *       统一门联动）；未来开 feature 即生效（runner 已完整接线, 不再恒 null stub）。</li>
     * </ol>
     *
     * @param bashClassifierFeature BASH_CLASSIFIER 特性开关（bean，可为 null → 关闭）
     * @param hookRegistry          hook 注册中心（bean，可为 null → hooksRunner fall through，
     *                              与 H9 前行为一致）
     */
    @Autowired
    public CoordinatorPermissionHandler(
            @Autowired(required = false) BashClassifierFeature bashClassifierFeature,
            @Autowired(required = false) HookRegistry hookRegistry) {
        this(
            bashClassifierFeature != null ? () -> bashClassifierFeature.isEnabled() : () -> false,
            params -> runPermissionRequestHooks(hookRegistry, params),
            (pending, updatedInput, toolUseId) -> {
                // [OPD-WF7-02-01] awaitClassifierAutoApproval 等价 —— 完整 runner（非恒 null stub）:
                //   未来开 BASH_CLASSIFIER 即生效（与 OPD-WF4-DEC-05 统一 BashClassifierFeature 门联动）。
                boolean featureOn = bashClassifierFeature != null && bashClassifierFeature.isEnabled();
                if (log.isDebugEnabled()) {
                    log.debug("PERMISSION coordinator tryClassifier: tool={} featureOn={} pending={}",
                        pending != null ? pending.toolName() : null, featureOn,
                        pending != null ? pending.input() : null);
                }
                return tryClassifier(pending, updatedInput, toolUseId, featureOn);
            },
            th -> log.warn("协调器自动化检查异常 (fall through): {}", th.getMessage()));
    }

    /**
     * [Session S07] 生产 HooksRunner · 对齐 CC {@code ctx.runHooks} (PermissionContext.ts:216-263).
     *
     * <p>行为映射:
     * <ul>
     *   <li>执行 PermissionRequest hooks (HookEvent 全参工厂: tool_name/tool_input/
     *       permission_suggestions/permission_mode/tool_use_id, 对齐 CC
     *       executePermissionRequestHooks hooks.ts:4157-4192);</li>
     *   <li>聚合结果 (HookRegistry.executeEvent 折叠, 首非空 permissionRequestResult) 中
     *       {@code Allow} → 决策 allow (source=HOOK, gate 发 granted_by_permission_hook);
     *       {@code Deny} → 决策 deny + hook message (缺省 "Permission denied by hook",
     *       对齐 CC PermissionContext.ts:251-258);</li>
     *   <li>无决策 / hookRegistry 未接线 / 缺 hook 上下文 → null (fall through 到 classifier
     *       或交互 dialog)。</li>
     * </ul>
     *
     * <p>[hooks_v3 WF3-X6] deny.interrupt 会话级中断已接线: 本方法把 Deny.interrupt 透传
     * 进 {@link PermissionDecision#interrupt()}, 由 {@code ToolPermissionGate} coordinator
     * deny 分支持 ctx 调 {@code abortIfPossible(ctx)} — 对齐 CC PermissionContext.ts:245-250
     * (deny && interrupt → abortController.abort()). 原 concern S07 "未表达" 已闭环
     * (交叉核验 X-WF7-06 不变量 B)。
     *
     * @param hookRegistry hook 注册中心 (可为 null)
     * @param params       本次权限请求上下文 (含 tool/input/session)
     * @return hook 决策 (allow/deny) 或 null (未表态)
     */
    private static PermissionDecision runPermissionRequestHooks(HookRegistry hookRegistry, Params params) {
        if (hookRegistry == null) {
            return null;
        }
        if (params.toolName() == null || params.input() == null) {
            return null;
        }
        HookEvent event = HookEvent.permissionRequest(
            params.toolName(), params.input(), toPermissionSuggestionMaps(params.suggestions()),
            params.permissionMode(), params.toolUseId(), params.sessionId(), params.agentId());
        GenericHook.HookResult result = hookRegistry.executeEvent(event);
        PermissionRequestResult prr = result != null ? result.permissionRequestResult() : null;
        if (prr instanceof PermissionRequestResult.Allow allow) {
            // [hooks_v3 WF3-X6] 透传 Allow.updatedInput / updatedPermissions 至 gate:
            //   CC PermissionContext.ts:233-239 handleHookAllow — finalInput =
            //   decision.updatedInput ?? updatedInput ?? input, 并 persistPermissions(
            //   decision.updatedPermissions) (PermissionContext.ts:324-325)。gate 持 ctx
            //   负责 apply+persist; 此处仅携带不落盘 (对齐 interactive/headless 分流)。
            Map<String, Object> updatedInput = allow.updatedInput();
            List<PermissionUpdate> updatedPermissions =
                WebSocketPermissionPrompter.toPermissionUpdateList(allow.updatedPermissions());
            if (log.isInfoEnabled()) {
                log.info("PERMISSION coordinator hook ALLOW: tool={} toolUseId={} hasUpdatedInput={} updatedPermissions={}",
                    params.toolName(), params.toolUseId(), updatedInput != null, updatedPermissions.size());
            }
            // [IMP-HOOKS-S6 CCJ-T6-18] decisionReason 携带 Hook("PermissionRequest",...) —
            //   经 gate 透传到 executor 注入点产 hook_permission_decision attachment.
            return new PermissionDecision("allow", "Permission request hook approved", Source.HOOK,
                new PermissionDecisionReason.Hook("PermissionRequest", null, "allow"),
                updatedInput, updatedPermissions, false);
        }
        if (prr instanceof PermissionRequestResult.Deny deny) {
            String message = deny.message() != null && !deny.message().isBlank()
                ? deny.message() : "Permission denied by hook";
            // [hooks_v3 WF3-X6] 透传 Deny.interrupt 至 gate: CC PermissionContext.ts:245-250
            //   deny && interrupt → abortController.abort() 会话级中断。runPermissionRequestHooks
            //   无 ctx/abortController, 由 gate 持 ctx 在消费决策时 abort (可观察等价)。
            boolean interrupt = Boolean.TRUE.equals(deny.interrupt());
            if (log.isInfoEnabled()) {
                log.info("PERMISSION coordinator hook DENY: tool={} toolUseId={} message={} interrupt={}",
                    params.toolName(), params.toolUseId(), message, interrupt);
            }
            // [IMP-HOOKS-S6 CCJ-T6-18] 同上, deny 决策 reason 带 hookName 归因.
            return new PermissionDecision("deny", message, Source.HOOK,
                new PermissionDecisionReason.Hook("PermissionRequest", null, message),
                null, List.of(), interrupt);
        }
        if (log.isDebugEnabled()) {
            log.debug("PERMISSION coordinator hooks 无决策 ({} 个 hook 结果), fall through: tool={}",
                result != null ? 1 : 0, params.toolName());
        }
        return null;
    }

    /**
     * [Session S07] suggestions → HookEvent.permissionSuggestions · CC original:
     * {@code permission_suggestions} (coreSchemas.ts:431). 元素为 {@link PermissionUpdate}
     * record, 经 Jackson 转为 Map 供 hook stdin JSON 序列化.
     */
    private static List<Map<String, Object>> toPermissionSuggestionMaps(List<Object> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(suggestions.size());
        for (Object suggestion : suggestions) {
            if (suggestion instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                out.add(typed);
            } else {
                out.add(JSON_FACTORY.convertValue(suggestion, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            }
        }
        return out;
    }

    /**
     * 决策来源归因 · 对齐 CC PermissionContext.ts:45-53 PermissionApprovalSource
     * (hook / classifier) 判别联合.
     *
     * <p><b>WHY (H9-GAP-1)</b>: CC 的 runHooks 决策 source 是 {@code {type:'hook'}}
     * (PermissionContext.ts:241/329), tryClassifier 决策 source 是 {@code {type:'classifier'}}
     * (PermissionContext.ts:204) — 两个独立遥测事件 (granted_by_permission_hook vs
     * granted_by_classifier)。Java 端 gate 据此细分遥测, 不能一律按 hook 上报.
     */
    public enum Source { HOOK, CLASSIFIER }

    /**
     * CC PermissionDecision — 扩展带 source 归因 + hook decisionReason.
     *
     * <p>[IMP-HOOKS-S6 CCJ-T6-18] 第 4 字段 decisionReason (PermissionDecisionReason, 可空):
     *   PermissionRequest hook 的决策归因 (PermissionDecisionReason.Hook("PermissionRequest",...))
     *   经 gate 透传到 executor 注入点 — CC toolExecution.ts:979-993 依据
     *   decisionReason.type==='hook' && hookName==='PermissionRequest' 产
     *   hook_permission_decision attachment; 旧 record 无 reason 字段导致 coordinator
     *   路径归因在 gate 边界丢失 (gate 只能合成 Other("coordinator_denied")).
     *   classifier 决策 decisionReason 恒 null (gate 保持 Other 归因, 不混同 hook).
     *
     * <p>[hooks_v3 WF3-X6] 扩展第 5-7 字段 (updatedInput / updatedPermissions / interrupt):
     *   对齐 CC {@code PermissionRequestResult.Allow.updatedInput / updatedPermissions} 与
     *   {@code Deny.interrupt} 的消费端接线 — runPermissionRequestHooks 把 hook 决策携带的
     *   这三个字段透传给 gate (gate 持有 ctx 才能 apply+persist / abort), 修复
     *   coordinator 路径 "hook 批准的规则变更静默丢弃" + "deny.interrupt 会话级 abort 未表达"
     *   (交叉核验 X-WF7-06)。classifier / 旧调用路径这三个字段为 null/空/false.
     *
     * <p>2 参兼容构造器默认 {@link Source#HOOK} (H9 前骨架 record 行为,
     * HooksRunner 产生的决策天然是 hook source).
     */
    public record PermissionDecision(String decision, String reason, Source source,
                                     PermissionDecisionReason decisionReason,
                                     Map<String, Object> updatedInput,
                                     List<PermissionUpdate> updatedPermissions,
                                     boolean interrupt) {
        public PermissionDecision {
            if (source == null) {
                throw new IllegalArgumentException("PermissionDecision.source is null");
            }
            updatedPermissions = updatedPermissions == null ? List.of() : updatedPermissions;
        }

        /** 4 参兼容构造器 · updatedInput=null / updatedPermissions=空 / interrupt=false. */
        public PermissionDecision(String decision, String reason, Source source,
                                  PermissionDecisionReason decisionReason) {
            this(decision, reason, source, decisionReason, null, List.of(), false);
        }

        /** 3 参兼容构造器 · decisionReason 默认 null (classifier/旧调用). */
        public PermissionDecision(String decision, String reason, Source source) {
            this(decision, reason, source, null);
        }

        /** 2 参兼容构造器 · source 默认 HOOK, decisionReason null. */
        public PermissionDecision(String decision, String reason) {
            this(decision, reason, Source.HOOK, null);
        }
    }

    /** CC PendingClassifierCheck — 简化. */
    public record PendingClassifierCheck(String toolName, String input) {}

    /**
     * CC CoordinatorPermissionParams · 对齐 coordinatorHandler.ts:8-14.
     *
     * <p>[Session S07] 扩展 5 字段 (toolName/input/toolUseId/sessionId/agentId):
     * 生产 hooksRunner 需要完整 hook 上下文执行 PermissionRequest hooks
     * (CC ctx.runHooks 的 tool/toolUseID/input 实参, PermissionContext.ts:216-230);
     * 旧 4 参仅够 classifier 步 (pending/updatedInput), hook 步因缺上下文恒 fall through.
     */
    public record Params(
        PendingClassifierCheck pendingClassifierCheck,
        Map<String, Object> updatedInput,
        List<Object> suggestions,
        String permissionMode,
        String toolName,
        JsonNode input,
        String toolUseId,
        String sessionId,
        String agentId
    ) {}

    /**
     * Hook 执行器 (注入) · 对齐 CC {@code ctx.runHooks} (PermissionContext.ts:216-263).
     *
     * <p>[Session S07] 签名从 3 参 (mode/suggestions/updatedInput) 升级为整 Params:
     * 执行 PermissionRequest hooks 必须携带 tool/input/toolUseId/session/agent 上下文,
     * 3 参无法表达 CC runHooks 的调用实参 (tool.name/toolUseID/input, coordinatorHandler.ts:33-37).
     */
    @FunctionalInterface
    public interface HooksRunner {
        PermissionDecision run(Params params);
    }

    /**
     * Classifier 执行器 (注入;CC 端可选链式).
     *
     * <p>[OPD-WF7-02-01] 第三参 {@code toolUseId}：CC tryClassifier 内部
     * {@code setClassifierApproval(toolUseID, matchedRule)}（PermissionContext.ts:199）需要
     * toolUseID 登记 matchedRule（CC 调用侧 feature('TRANSCRIPT_CLASSIFIER') 门控；Java 侧
     * setClassifierApproval null 参回退 BASH_CLASSIFIER 门，差异见 tryClassifier 代码注释
     * gate owner 复核）；旧接口无 toolUseId → runner 无法对齐该登记语义。
     */
    @FunctionalInterface
    public interface ClassifierRunner {
        PermissionDecision tryClassifier(PendingClassifierCheck pendingCheck,
                                         Map<String, Object> updatedInput,
                                         String toolUseId);
    }

    /** Error logger (注入;CC logError). */
    @FunctionalInterface
    public interface ErrorLogger {
        void log(Throwable t);
    }

    /** CC handleCoordinatorPermission — 主链. */
    public Optional<PermissionDecision> handle(Params params) {
        try {
            // 1. Hooks first (fast, local)
            PermissionDecision hookResult = hooksRunner.run(params);
            if (hookResult != null) {
                return Optional.of(hookResult);
            }

            // 2. Classifier (slow, inference — bash only)
            if (bashClassifierFeature.getAsBoolean()) {
                PermissionDecision classifierResult = classifierRunner.tryClassifier(
                    params.pendingClassifierCheck(),
                    params.updatedInput(),
                    params.toolUseId()
                );
                if (classifierResult != null) {
                    return Optional.of(classifierResult);
                }
            }
        } catch (Throwable error) {
            // 异常 fall through 到 dialog (非阻断)
            // Error → 原对象 logError;非 Error → 包装带 prefix
            if (error instanceof Exception ex) {
                errorLogger.log(ex);
            } else {
                errorLogger.log(new RuntimeException(
                    "Automated permission check failed: " + String.valueOf(error), error));
            }
        }

        // 3. Neither resolved (or checks failed) → fall through
        return Optional.empty();
    }

    /**
     * [OPD-WF7-02-01] coordinator classifier runner · 对齐 CC PermissionContext.ts:176-215
     * {@code tryClassifier}（awaitClassifierAutoApproval + matchedRule）。
     *
     * <p>CC 行为链（grep 自验，不信注释）：
     * <ol>
     *   <li>{@code tool.name !== BASH_TOOL_NAME || !pendingClassifierCheck → null}
     *       （PermissionContext.ts:180-182）；</li>
     *   <li>{@code awaitClassifierAutoApproval(pending, signal, isNonInteractiveSession)}
     *       （bashPermissions.ts:1555-1587）— consume 投机结果，无则 classifyBashCommand
     *       stub → 仅当 {@code feature('BASH_CLASSIFIER') && matches && confidence==='high'}
     *       返回 classifier decision reason；</li>
     *   <li>matchedRule 从 reason {@code /^Allowed by prompt rule: "(.+)"$/} 提取 →
     *       {@code setClassifierApproval(toolUseID, matchedRule)}（CC 调用侧
     *       feature('TRANSCRIPT_CLASSIFIER') 门控，PermissionContext.ts:191-201；Java 侧
     *       null 参回退 BASH_CLASSIFIER 门 —— 差异见代码注释 gate owner 复核）；</li>
     *   <li>{@code logPermissionDecision(accept, classifier)} — Java 由 gate 消费
     *       {@code Source.CLASSIFIER} 时上报 granted_by_classifier（gate :980-983）；</li>
     *   <li>return {@code allow(updatedInput ?? input, userModified:false, decisionReason)}。</li>
     * </ol>
     *
     * <p>Java 表达：classifyBashCommand 为 stub（SpeculativeClassifier.classifyBashCommand
     * 恒 matches:false，对齐 CC 外部构建）→ 生产 runner 结构完整但恒返回 null（fall through
     * 交互弹窗）；未来替换真实分类器即自动生效，与 OPD-WF4-DEC-05 统一 BashClassifierFeature 门联动。
     *
     * @param pendingCheck         待分类器检查（toolName + command；null → null）
     * @param updatedInput         CC original: updatedInput（tryClassifier 第 2 参）
     * @param toolUseId            CC original: toolUseID（setClassifierApproval 登记键）
     * @param bashClassifierEnabled CC feature('BASH_CLASSIFIER') 等价门（handle 已前置门控，双保险）
     * @return allow(source=CLASSIFIER) 决策；未命中 / 门关 / 非 Bash → null
     */
    static PermissionDecision tryClassifier(PendingClassifierCheck pendingCheck,
                                            Map<String, Object> updatedInput,
                                            String toolUseId,
                                            boolean bashClassifierEnabled) {
        // CC PermissionContext.ts:180-182 — tool 非 Bash 或 无 pending → null
        if (pendingCheck == null || !ToolNameConstants.BASH_TOOL_NAME.equals(pendingCheck.toolName())) {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION coordinator tryClassifier 跳过（非 Bash 或 无 pending）: tool={}",
                    pendingCheck != null ? pendingCheck.toolName() : null);
            }
            return null;
        }
        // awaitClassifierAutoApproval（bashPermissions.ts:1555-1587）
        SpeculativeClassifier.SpeculativeClassifierResult classifierResult =
            awaitClassifierAutoApproval(pendingCheck.input(), "", List.of());
        // feature('BASH_CLASSIFIER') && matches && confidence==='high'（bashPermissions.ts:1575-1585）
        if (classifierResult == null || !classifierResult.matches()
                || !"high".equals(classifierResult.confidence())
                || !bashClassifierEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION coordinator classifier 未命中（stub 恒 matches:false）或门关: command={}",
                    pendingCheck.input());
            }
            return null;
        }
        String matchedRule = classifierResult.matchedDescription();
        // CC PermissionContext.ts:191-201 调用侧以 feature('TRANSCRIPT_CLASSIFIER') 门控
        //   matchedRule → setClassifierApproval（CC TRANSCRIPT 门；且 setClassifierApproval 内部
        //   另有 feature('BASH_CLASSIFIER') 门，classifierApprovals.ts:23-25）。
        //   Java 侧 setClassifierApproval 第 3 参 null → 回退静态 BASH_CLASSIFIER 门
        //   （bashClassifierGate，ClassifierApprovals.java:89-92），未复刻 CC 调用侧 TRANSCRIPT 门。
        //   【gate owner 复核登记】CC 双门（TRANSCRIPT 调用侧 + BASH 内部）vs Java 单门（仅 BASH）——
        //   未来真实分类器接线时需对齐该差异（当前为文档级修正，零行为变更）。
        if (matchedRule != null) {
            ClassifierApprovals.setClassifierApproval(toolUseId, matchedRule, null);
        }
        String decisionReasonText =
            "Allowed by prompt rule: \"" + (matchedRule != null ? matchedRule : "") + "\"";
        // CC :202-212 return allow(updatedInput ?? input, userModified:false, decisionReason)
        //   updatedInput null → gate 消费端回退原 input（cd.updatedInput() != null ? valueToTree : input）
        return new PermissionDecision("allow", decisionReasonText, Source.CLASSIFIER,
            new PermissionDecisionReason.Classifier("bash_allow", decisionReasonText),
            updatedInput, List.of(), false);
    }

    /**
     * [OPD-WF7-02-01] awaitClassifierAutoApproval 等价 · 对齐 CC bashPermissions.ts:1555-1587。
     *
     * <p>consume 投机结果（bashPermissions.ts:1561-1563），无则 classifyBashCommand stub
     * （bashClassifier.ts:40-51）。返回原始分类结果供调用方做 matches/confidence 判定。
     * 供 coordinator + swarm 两 runner 共享（同包静态）。
     *
     * <p>【DEC-WF7-02-04 EV-086】旧 {@code SPECULATIVE_WAIT_TIMEOUT_MS=2s} 有界等待已删——
     * CC 直接 {@code await speculativeResult} 无超时（bashPermissions.ts:1562-1563），取消由
     * abort signal 驱动（gate 消费路径 isAborted 统一处理，ToolPermissionGate:935/1038）。
     * 投机 future 以 AbortException 完成时显式重抛（禁止吞 AbortException 转 deny，对齐 CC
     * await 拒绝语义，coordinatorHandler catch → logError → fall through 交互弹窗）；
     * 普通 ExecutionException → null（同样 fall through，对齐 CC coordinatorHandler catch）。
     *
     * @param command      bash 命令（投机 Map key）· CC original: {@code command}
     * @param cwd          工作目录 · CC original: {@code cwd}（coordinator 路径 gate 未透传 → 空串）
     * @param descriptions allow 描述 · CC original: {@code descriptions}（stub 忽略）
     * @return 分类结果（stub 恒 matches:false）；投机 await 普通异常/中断 → null；AbortException 透传
     */
    static SpeculativeClassifier.SpeculativeClassifierResult awaitClassifierAutoApproval(
            String command, String cwd, List<String> descriptions) {
        CompletableFuture<SpeculativeClassifier.SpeculativeClassifierResult> speculative =
            SpeculativeClassifier.consumeSpeculativeClassifierCheck(command);
        if (speculative != null) {
            try {
                return speculative.get();
            } catch (ExecutionException e) {
                // 禁止把 AbortException 吞掉转 deny —— 用户中止意图透传（对齐 CC await 拒绝语义）
                if (e.getCause() instanceof AbortException abortException) {
                    throw abortException;
                }
                if (log.isDebugEnabled()) {
                    log.debug("PERMISSION awaitClassifierAutoApproval 投机结果不可用: command={} err={}",
                        command, e.toString());
                }
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        // CC classifyBashCommand(..., signal, ...)（bashPermissions.ts:1564-1571）——coordinator
        // 路径 gate 持有 abort signal（isAborted 预检/后检），此处传默认 NOOP（永未取消）
        // 对齐 CC default AbortSignal；stub 忽略全部实参。
        return SpeculativeClassifier.classifyBashCommand(
            command, cwd, descriptions, "allow", AbortController.NOOP, false).join();
    }

}
