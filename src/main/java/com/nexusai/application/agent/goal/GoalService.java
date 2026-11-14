package com.nexusai.application.agent.goal;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PromptHook;
import com.nexusai.domain.session.SessionGoal;
import com.nexusai.domain.session.SessionService;
import com.nexusai.domain.stats.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标（/goal）服务 · 对齐 CC 的 {@code activeGoal} 机制。
 *
 * <p><b>CC 的机制（一手核对 claude.exe v2.1.212）</b>：
 * <ul>
 *   <li>{@code /goal <condition>} → 往会话注册一个 <b>prompt 型 Stop hook</b>
 *       （{@code sessionHooksRegistry.add(sessionId, "Stop", "", {type:"prompt", prompt:condition})}）
 *       + 进程内 {@code activeGoal={condition, iterations, setAt, tokensAtStart}}</li>
 *   <li>每轮结束时该 hook 调 <b>small fast model</b> 判停，返回
 *       {@code {ok:true}} / {@code {ok:false,reason}} / {@code {ok:false,impossible:true,reason}}</li>
 *   <li>{@code ok:false} → block（{@code blockingError="[condition]: reason"}），
 *       reason 作为 isMeta 消息注入下一轮；{@code ok:true} / {@code impossible} → 摘 hook、目标结束</li>
 *   <li>无参 {@code /goal} 显示 {@code Goal active: <c> (N turns)}；清空别名
 *       {@code clear/stop/off/reset/none/cancel}；条件上限 4000 字符</li>
 * </ul>
 *
 * <p><b>本仓与 CC 的差异（multi-session-vs-cc-single-session 铁律）</b>：
 * CC 是单进程 CLI，{@code activeGoal} 纯内存；本仓 Web 端每 send 新建 LlmAgentLoop
 * （prototype 作用域）⇒ 内存态活不过下一条用户消息 ⇒ <b>真源落 {@code sessions.goal} 列</b>
 * （V253），内存只放「已挂 hook」的缓存。
 *
 * <p>⚠️ <b>为什么不用 transcript 反推</b>（CC 的做法）：压缩会剪掉旧的 goal attachment，
 * 一旦「目标设定」那条落在压缩边界之前就再也恢复不出来。列独立于消息生命周期，
 * {@code /compact} 动不了它 ⇒ 恢复永远可靠。
 *
 * @see SessionGoal
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);

    /** 清空目标的别名集合 · 对齐 CC {@code new Set(["clear","stop","off","reset","none","cancel"])}。 */
    private static final java.util.Set<String> CLEAR_ALIASES =
        java.util.Set.of("clear", "stop", "off", "reset", "none", "cancel");

    private final SessionService sessionService;
    /**
     * Hook 注册中心 · <b>经 ObjectProvider 懒解析</b>（不是直接注入）。
     *
     * <p><b>WHY 必须懒解析</b>：直接注入会形成 Spring 循环依赖
     * {@code AgentLoopContextFactory → GoalService → HookRegistry → … → AgentLoopContextFactory}
     * （HookRegistry 在一条很深的工具链上：SubagentExecutor → SpawnInProcess →
     * BackgroundTaskRunner → BashTool → SubagentTool → ResumeService → CommandController）。
     * ⇒ 启动期直接失败「Relying upon circular references is discouraged」。
     *
     * <p>⇒ 运行时才取 bean：{@code goalServiceProvider.getIfAvailable()}。
     * 可空：未接线 → 目标机制不生效（老路径行为不变）。
     */
    private final org.springframework.beans.factory.ObjectProvider<HookRegistry> hookRegistryProvider;
    /** [goal V253] 出站推送（会话级 topic /topic/sessions/{sid}/goal）· 可空（未接线 → 不推）。 */
    private final GoalEventPublisher eventPublisher;

    /**
     * 已挂 hook 的会话 → 条件原文（<b>缓存，不是真源</b>）。
     *
     * <p>真源永远是 {@code sessions.goal} 列；本 Map 只用于回答「这个会话的 hook 挂了吗、
     * 挂的是哪个条件」，避免重复 add / 忘记 remove。
     */
    private final Map<String, String> armedConditions = new ConcurrentHashMap<>();

    /**
     * 本轮被「后台任务门控」摘掉 hook 的会话（评估窗口结束后复原）。
     *
     * <p>对应 CC 的 finally 复原语义（claude.exe v2.1.212：摘掉 hook → 求值 → finally 挂回）。
     */
    private final java.util.Set<String> deferredSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GoalService(SessionService sessionService,
                      org.springframework.beans.factory.ObjectProvider<HookRegistry> hookRegistryProvider,
                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                      GoalEventPublisher eventPublisher) {
        this.sessionService = sessionService;
        this.hookRegistryProvider = hookRegistryProvider;
        this.eventPublisher = eventPublisher;
    }

    /** Hook 注册中心（懒解析；未接线 → null ⇒ 目标机制不生效）。 */
    private HookRegistry hookRegistry() {
        return hookRegistryProvider == null ? null : hookRegistryProvider.getIfAvailable();
    }

    /** 输出 token 聚合（可选注入；未接线 → 终态附件的 tokens 记 null，不回落 0）。 */
    private StatsService statsService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStatsService(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 推送目标状态（publisher 未接线 → 跳过；前端会在下次拉取时拿到真值）。 */
    private void publish(String sessionId, SessionGoal goal) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.emitGoal(sessionId, goal);
        } catch (Exception e) {
            log.warn("[GoalService] 目标状态推送失败 session={}: {}", sessionId, e.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 查询
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取会话当前目标（真源 = {@code sessions.goal} 列）。
     *
     * @param sessionId 会话 ID
     * @return 目标状态；无目标 → null
     */
    public SessionGoal getGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionService.getGoal(sessionId);
        } catch (Exception e) {
            log.warn("[GoalService] getGoal 读取失败 session={}: {}", sessionId, e.toString());
            return null;
        }
    }

    /** 该会话是否处于「目标推进中」。 */
    public boolean hasActiveGoal(String sessionId) {
        SessionGoal g = getGoal(sessionId);
        return g != null && g.isActive();
    }

    /**
     * 该会话的 prompt Stop hook 是否真的挂在注册表上。
     *
     * <p>⚠️ <b>不能只看 {@link #armedConditions} 这个内存标记</b>：hook 会被本类之外的路徑摘掉
     * （{@code HookRegistry.executeSessionEndHooks} 的 finally 会 {@code clearSessionHooks}
     * 清空整张表，见 {@code HookRegistry:4737}）。内存标记不知道这件事 ⇒ 单看内存会得出
     * 「还挂着」的假象：{@code restoreForResume} 的 fresh 守卫据此提前返回、不补挂，
     * 目标 hook 就此永久丢失（真机 sess-189e74fa：21:24:57 被清理后直到用户手动发消息都没再挂上）。
     *
     * <p>注册表未接线时（单元测试 / 非容器上下文）无从核实 ⇒ 沿用内存标记（老行为）。
     */
    public boolean isArmed(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        String condition = armedConditions.get(sessionId);
        if (condition == null) {
            return false;
        }
        HookRegistry registry = hookRegistry();
        if (registry == null) {
            return true;
        }
        return isHookRegistered(registry, sessionId, condition);
    }

    /**
     * 注册表里是否真有一个 prompt == condition 的 Stop hook。
     *
     * <p>按文本精确匹配（{@link #arm} 把 {@code prompt} 设成目标条件原文），
     * 与 {@link #isGoalHookResult} 同一判据 —— 用户自配的 prompt hook 不会被误认成 goal hook。
     */
    private boolean isHookRegistered(HookRegistry registry, String sessionId, String condition) {
        if (registry == null || condition == null) {
            return false;
        }
        try {
            Map<HookEventType, java.util.List<
                com.nexusai.application.agent.permission.hook.SessionHookStore.SessionDerivedHookMatcher>> all =
                registry.getSessionHooks(sessionId, HookEventType.STOP);
            if (all == null) {
                return false;
            }
            java.util.List<com.nexusai.application.agent.permission.hook.SessionHookStore.SessionDerivedHookMatcher>
                matchers = all.get(HookEventType.STOP);
            if (matchers == null) {
                return false;
            }
            for (var m : matchers) {
                if (m == null || m.hooks() == null) {
                    continue;
                }
                for (var h : m.hooks()) {
                    if (h instanceof PromptHook ph && condition.equals(ph.prompt())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            // 核实失败 ⇒ 不作「已丢失」处理（fail-soft：宁可少补挂一次，也不要在注册表异常时
            //   反复 add 出重复 hook）
            log.warn("[goal] 查询 Stop hook 注册表失败（按仍在挂载处理）session={}: {}", sessionId, e.toString());
            return true;
        }
    }

    /**
     * 确保「有活跃目标 ⇒ 注册表里就有对应的 prompt Stop hook」这条不变式成立。
     *
     * <p><b>WHY 需要主动补挂</b>：{@code HookRegistry.executeSessionEndHooks} 在每次
     * {@code LlmAgentLoop.run()} 结束时（finally）无条件 {@code clearSessionHooks}，
     * 会把 goal hook 一起清掉。对齐 CC 的做法是 SessionEnd 只在「/clear」与「进程关闭」时执行
     * （{@code claude-code-best/src/utils/gracefulShutdown.ts:468}、
     * {@code src/commands/clear/conversation.ts:91}），本仓按 run 执行属于结构性偏差。
     * 在不改动 SessionEnd 语义的前提下，用本方法在每轮<b>开始前</b>把不变式补回来 ——
     * 覆盖主路径、cron 后台轮、check-in 轮全部 run 形态（它们都经 {@code LlmAgentLoop.doRun}）。
     *
     * <p>幂等：已挂着就只同步内存标记，不重复 add。
     *
     * <p><b>不补挂的两种情况</b>：
     * <ul>
     *   <li>延迟评估中（{@link #deferredSessions}）—— {@link #deferEvaluationForBackground}
     *       是<b>故意</b>摘掉的，补回来会让子代理未跑完时就评估；</li>
     *   <li>目标已暂停 / 已终态 —— 暂停态是「等用户发消息」的显式状态，不该自动重挂。</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     */
    public void ensureArmed(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (deferredSessions.contains(sessionId)) {
            return;
        }
        SessionGoal g = getGoal(sessionId);
        if (g == null || !g.isActive()) {
            // 无目标 / 已暂停 / 已终态 ⇒ 顺手清掉可能残留的内存标记（防止它再骗过 fresh 守卫）
            armedConditions.remove(sessionId);
            return;
        }
        HookRegistry registry = hookRegistry();
        if (registry == null) {
            return;
        }
        if (isHookRegistered(registry, sessionId, g.condition())) {
            armedConditions.put(sessionId, g.condition());
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[goal] 目标 hook 不在注册表里（多由 SessionEnd 清理所致）⇒ 补挂 session={}", sessionId);
        }
        arm(sessionId, g.condition());
    }

    /**
     * 判断某个 hook 结果<b>是否来自本会话的 goal hook</b> · 对齐 CC 的目标账本归属。
     *
     * <p><b>WHY 必须按 hook 身份判定，不能只看「是 prompt hook」</b>：
     * 本仓的 Stop 事件上可能同时挂着<b>用户自己配的</b> prompt Stop hook（settings.json /
     * 技能 frontmatter）。若把「任一 prompt hook 的 blocking / impossible 结果」都当成 goal 的结果，
     * 则：用户那条「每次结束必须写总结」的 hook 一 block ⇒ 目标迭代数被误加、
     * lastReason 被写成跟目标无关的话；用户那条 hook 若判 impossible ⇒ <b>目标被直接判死</b>。
     *
     * <p><b>判定依据</b>：goal hook 的 {@code prompt} 就是<b>目标条件原文</b>
     * （{@link #arm} 传 {@code prompt = condition}）⇒ 用「hook 的 prompt 文本 == 当前目标条件」
     * 精确锁定这一个 hook。{@link HookResult#hook()} 携带的是<b>注册表里的那个实例</b>
     * （{@code withHook(promptHook)} 透传，{@code injectHookCommandAndDuration} 也保留该字段），
     * 不是执行时新建的副本 ⇒ 文本比对可靠。
     *
     * @param sessionId 会话 ID
     * @param result    hook 结果（可 null）
     * @return true = 该结果来自本会话的 goal hook
     */
    public boolean isGoalHookResult(String sessionId, GenericHook.HookResult result) {
        if (sessionId == null || result == null) {
            return false;
        }
        if (!(result.hook() instanceof PromptHook ph)) {
            return false;
        }
        String goalPrompt = armedConditions.get(sessionId);
        if (goalPrompt == null) {
            // 内存态丢失（进程重启 / 从未 arm）⇒ 回落列里的条件
            SessionGoal g = getGoal(sessionId);
            goalPrompt = g == null ? null : g.condition();
        }
        return goalPrompt != null && goalPrompt.equals(ph.prompt());
    }

    // ════════════════════════════════════════════════════════════════════
    // Stop hook 结果的统一消费（两处执行点共用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 一个 Stop hook 结果对目标的最终影响。
     *
     * @param kind        影响类型
     * @param trace       需要落进本轮「已完成」折叠区的溯源消息（无 → null）
     */
    public record GoalVerdict(Kind kind, com.nexusai.model.session.dto.ChatMessageDto trace) {
        /** 影响类型。 */
        public enum Kind {
            /** 与本会话目标无关（用户的其它 prompt hook / 非 goal hook）。 */
            IGNORED,
            /** 评估器判达成。 */
            MET,
            /** 评估器判无法达成。 */
            IMPOSSIBLE,
            /** 评估器判未达成（需要继续 block 重入）。 */
            NOT_MET,
            /** 评估器自身报错（对齐 CC {@code $e="error"}）。 */
            EVALUATOR_ERROR,
            /** 评估器超时 / 被取消。 */
            CANCELLED
        }
    }

    /**
     * 判断并消费一个 Stop hook 结果 · <b>两处执行点共用这一份语义</b>。
     *
     * <p><b>WHY 必须抽成方法（B2）</b>：本仓 Stop hook 有两个执行点 —— 循环内纯文本分支
     * （{@code LlmAgentLoop:8994} 附近）与循环退出后的 §14 兜底（{@code :9500} 之后）。
     * 旧实现只在循环内那段消费 goal 三态，§14 段完全没有 ⇒ 走 §14 的轮次即使评估器
     * 判了达成也不回写终态，UI 永远显示「还在追」。两处共用本方法后结构上不可能再漂移。
     *
     * <p><b>WHY 判据必须按 hook 身份（B1）</b>：对齐 CC v2.1.318 ——
     * <pre>
     * let ft=(pe)=&gt;(h.sessionHooksRegistry.get(z(),"Stop").get("Stop")??[])
     *   .flatMap(ve=&gt;ve.hooks).some(ve=&gt;xhr(ve,pe))?pe:void 0;
     * ...
     * if(Oe.hookEvent==="Stop"&amp;&amp;ve){ ... if(tt?.condition===ve.prompt){...} }
     * </pre>
     * 旧实现用「任意 PromptHook 型 SUCCESS」当达成判据 ⇒ 用户自己配的 prompt Stop hook
     * 一旦返回成功，目标就被误判为达成（反之用户 hook 的 blocking 也会被当成目标未达成）。
     *
     * @param sessionId 会话 ID
     * @param result    单个 hook 结果（可 null）
     * @return 影响结论（{@code IGNORED} = 与本会话目标无关）
     */
    public GoalVerdict consumeStopHookResult(String sessionId, GenericHook.HookResult result) {
        if (sessionId == null || result == null) {
            return new GoalVerdict(GoalVerdict.Kind.IGNORED, null);
        }
        // [goal-observability] 入口留痕：本方法是 goal 与 Stop hook 的**唯一**汇合点，
        //   而下面两处提前 return（身份不符 / 目标已非 live）此前**一条日志都没有**
        //   ⇒ 日志里看到「hook 跑了但目标没动」时无从分辨是哪种原因。
        //   两条分支各打一条 info（含 sessionId + 判定依据），成功分支沿用既有日志。
        // ① 身份校验：不是本会话 goal hook 的结果 ⇒ 与本目标无关
        if (!isGoalHookResult(sessionId, result)) {
            if (log.isInfoEnabled()) {
                log.info("[goal] Stop hook 结果与本会话目标无关 ⇒ 跳过（session={} outcome={} 来源={}）",
                    sessionId, result.outcome(),
                    result.hook() == null ? null : result.hook().type());
            }
            return new GoalVerdict(GoalVerdict.Kind.IGNORED, null);
        }
        SessionGoal g = getGoal(sessionId);
        if (g == null || !g.isLive()) {
            // 身份已确认是本会话的 goal hook，但目标已终态/已清 ⇒ 结果按无效丢弃。
            // ⚠️ 这条与上一条必须区分：前者是「别的 hook」，本条是「本目标的 hook 但目标已收尾」。
            if (log.isInfoEnabled()) {
                log.info("[goal] goal hook 有结果但目标已非活跃 ⇒ 丢弃（session={} phase={} outcome={}）",
                    sessionId, g == null ? null : g.phase(), result.outcome());
            }
            return new GoalVerdict(GoalVerdict.Kind.IGNORED, null);
        }
        // ② impossible（评估器判「条件根本不可能达成」）
        if (Boolean.TRUE.equals(result.impossible())) {
            SessionGoal done = recordImpossible(sessionId);
            log.info("[goal] 评估器判定条件不可能达成 → 目标停止推进（session={}）", sessionId);
            return new GoalVerdict(GoalVerdict.Kind.IMPOSSIBLE,
                done == null ? null : buildTerminalTraceMessage(sessionId, done));
        }
        // ③ 评估器自身失败（非本目标的 blocking 语义）
        //   [B6 2026-11-04] 对齐 CC v2.1.318：error 不是「目标失败」，而是「这次判定没做成」——
        //   CC 走 su()/kg() 的 {kind:"retry", cause:"goal_check"}，按 60/300/900 秒三档重试。
        //   本仓的等价做法：写 deferredSince（让 GoalCheckinScheduler 的下一拍重新触发一轮），
        //   并把 checkinCount 当重试计数用（超过 MAX_AUTO_RETRIES 就停）。
        if (result.outcome() == GenericHook.HookOutcome.NON_BLOCKING_ERROR) {
            scheduleEvaluatorRetry(sessionId, g);
            return new GoalVerdict(GoalVerdict.Kind.EVALUATOR_ERROR, null);
        }
        if (result.outcome() == GenericHook.HookOutcome.CANCELLED) {
            log.info("[goal] 评估器被取消 / 超时，目标保持不变（session={}）", sessionId);
            return new GoalVerdict(GoalVerdict.Kind.CANCELLED, null);
        }
        // ④ 达成：prompt hook 返回 SUCCESS 且非 impossible
        if (result.outcome() == GenericHook.HookOutcome.SUCCESS) {
            SessionGoal done = recordAchieved(sessionId);
            log.info("[goal] 评估器判定条件已满足 → 目标达成（session={}）", sessionId);
            return new GoalVerdict(GoalVerdict.Kind.MET,
                done == null ? null : buildTerminalTraceMessage(sessionId, done));
        }
        // ⑤ 未达成：blockingError ⇒ iterations++ + lastReason
        if (result.blockingError() != null) {
            recordNotMet(sessionId, result.stopReason());
            return new GoalVerdict(GoalVerdict.Kind.NOT_MET, null);
        }
        return new GoalVerdict(GoalVerdict.Kind.IGNORED, null);
    }

    /**
     * 评估器报错后的自动重试排程 · 对齐 CC v2.1.318 的 {@code su()} 重试分支。
     *
     * <p>CC 原文要点：
     * <pre>
     * if(M.kind==="retry"){
     *   if(x&gt;=Fi){ ... announced:"gave_up"
     *     yield Mw(`Goal paused after ${Fi} automatic retries · ${J} · send a message to continue`,"warning"); return }
     *   let K=Di[x]??Di[0];                                   // 60s / 300s / 900s
     *   $a({goal:S,cause:J,...}, Math.round(K*(1+Math.random()*yg)))   // 排下一次重试
     *   yield Mw(`Goal still active · ${J} · retrying in ${Math.round(K/60000)} min (${x+1}/${Fi}) · ...`,"notice")
     * }
     * </pre>
     *
     * <p><b>本仓的落地形态</b>：CC 用 {@code setTimeout} 直接排程；本仓是 Web 多会话
     * ⇒ 写 {@code retryAt} + {@code retryCount}（<b>不复用</b> {@code checkinCount}，
     * 两者是不同语义的退避），由 {@link GoalCheckinScheduler} 的 tick 在到期后重新触发一轮。
     *
     * <p><b>不可恢复错误</b>（auth / disk_full / billing / context_limit / model_unavailable）
     * ⇒ CC 走 {@code ru()} 直接清目标并给 warning；这里同样清，避免无限重试白烧。
     *
     * @param sessionId 会话 ID
     * @param goal      当前目标快照
     */
    private void scheduleEvaluatorRetry(String sessionId, SessionGoal goal) {
        try {
            int attempts = goal.retryCount() == null ? 0 : goal.retryCount();
            if (attempts >= GoalCheckinConfig.MAX_AUTO_RETRIES) {
                // [goal-gave-up 2026-11-04] 对齐 CC su() 的 gave_up 分支：
                //   `if(x>=Fi){ ... announced:"gave_up"
                //     yield Mw(`Goal paused after ${Fi} automatic retries · ${J} ·
                //               send a message to continue`,"warning"); return }`
                //   ⛔ 旧实现只打一条 warn 就 return，目标静默留在 ACTIVE —— 用户看到一个
                //   永远不推进的目标头，既不知道评估器一直在失败、也没有任何继续的入口。
                //   ⇒ 转 PAUSED：保留 condition / iterations / lastReason，发一条消息即
                //   resumeFromPause 恢复追踪（灰条 +「已暂停 · 发消息继续」本身就是告知）。
                //   countIteration=false：这几轮评估器全失败，没有任何一轮真正得出过结论。
                String reason = "评估器连续 " + attempts + " 次失败，目标已暂停 · 发消息继续";
                recordPaused(sessionId, reason, false);
                log.warn("[goal] 评估器连续失败 {} 次（上限 {}），已暂停目标（发消息可继续）session={}",
                    attempts, GoalCheckinConfig.MAX_AUTO_RETRIES, sessionId);
                return;
            }
            long now = System.currentTimeMillis();
            long nextMs = GoalCheckinConfig.retryIntervalMs(attempts);
            // 用独立字段 retryAt/retryCount 承载（⛔ 不复用 checkinCount：那是后台延迟
            //   check-in 的语义，混用会让两种退避互相污染）。
            SessionGoal updated = goal.withRetry(now + nextMs, attempts + 1);
            sessionService.setGoal(sessionId, updated);
            publish(sessionId, updated);
            log.warn("[goal] 评估器执行失败，{} 分钟后自动重试（第 {}/{} 次）session={}",
                Math.round(nextMs / 60_000.0), attempts + 1, GoalCheckinConfig.MAX_AUTO_RETRIES, sessionId);
        } catch (Exception e) {
            log.warn("[goal] 排评估器重试失败（目标保持不变）session={}: {}", sessionId, e.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 设置 / 清除
    // ════════════════════════════════════════════════════════════════════

    /**
     * 设置目标（{@code /goal <condition>}）· 对齐 CC {@code Sor()}。
     *
     * <p>CC 语义：先移除本会话<b>所有</b> matcher 为空的 prompt Stop hook（避免旧目标残留），
     * 再挂新的 prompt hook + 写 activeGoal。
     *
     * @param sessionId 会话 ID
     * @param condition 目标条件原文
     * @param tokensAtStart 设定时的输出 token 累计值（CC {@code DA()} 口径）
     * @return 设定成功返回新目标；条件非法（空 / 超长）返回 null
     */
    public SessionGoal setGoal(String sessionId, String condition, long tokensAtStart) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("目标条件不能为空");
        }
        if (condition.length() > SessionGoal.MAX_CONDITION_LENGTH) {
            // CC：超长直接拒绝并回报实际长度（bor=4000）
            throw new IllegalArgumentException(
                // 逐字对齐 CC：Goal condition is limited to 4000 characters
                "Goal condition is limited to " + SessionGoal.MAX_CONDITION_LENGTH + " characters");
        }

        // 1. 先摘掉旧目标（若有）—— CC Sor() 先 remove 所有旧 prompt Stop hook
        disarm(sessionId);

        // 2. 写真源列（真源 = 列，内存只是缓存）
        SessionGoal goal = new SessionGoal(
            condition.trim(),
            SessionGoal.Phase.ACTIVE,
            0,
            System.currentTimeMillis(),
            tokensAtStart,
            null);
        sessionService.setGoal(sessionId, goal);

        // 3. 挂 prompt Stop hook
        arm(sessionId, goal.condition());

        // 4. 推前端（目标头立即出现）
        publish(sessionId, goal);

        log.info("[GoalService] setGoal: session={} condition={} 字符, 已挂 Stop hook",
            sessionId, goal.condition().length());
        return goal;
    }

    /**
     * 清除目标（{@code /goal clear} 及别名）· 对齐 CC {@code Aor()}。
     *
     * @param sessionId 会话 ID
     * @return 被清除的目标条件；本来就没有目标 → null
     */
    public String clearGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionGoal existing = getGoal(sessionId);
        disarm(sessionId);
        sessionService.setGoal(sessionId, null);
        publish(sessionId, null);
        if (existing != null) {
            log.info("[GoalService] clearGoal: session={} 已清除目标（{} 字符）",
                sessionId, existing.condition().length());
        }
        return existing == null ? null : existing.condition();
    }

    /**
     * 判断入参是否为「清空」意图 · 对齐 CC 的别名集合（大小写不敏感）。
     *
     * @param raw 用户入参（可 null）
     * @return true = 应执行清空
     */
    public static boolean isClearIntent(String raw) {
        return raw != null && CLEAR_ALIASES.contains(raw.trim().toLowerCase());
    }

    // ════════════════════════════════════════════════════════════════════
    // 评估结果回写
    // ════════════════════════════════════════════════════════════════════

    /**
     * 评估器判定「未达成」后的回写 · 对齐 CC 的 iterations++ 与 reason 记录。
     *
     * <p>CC：{@code iterations+1}、{@code lastReason=reason}，并把 reason 作为
     * {@code blockingError="[condition]: reason"} 注入下一轮（那部分由 hook 管线负责）。
     *
     * @param sessionId 会话 ID
     * @param reason    评估器给出的 reason
     * @return 更新后的目标；无目标 → null
     */
    public SessionGoal recordNotMet(String sessionId, String reason) {
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        // [B6 2026-11-04] 评估器**成功给出了判定**（未达成也是判定）⇒ 清掉重试计数。
        //   retryCount 的唯一归零点就是这里 / recordAchieved / recordImpossible ——
        //   ⛔ 不在「重试到点消费」时清（那样上限永远到不了 = 无限重试）。
        SessionGoal updated = g.withIterations(g.iterations() + 1).withReason(reason).clearRetryFully();
        sessionService.setGoal(sessionId, updated);
        publish(sessionId, updated);
        log.info("[GoalService] 目标未达成, session={} 第 {} 轮, reason={}",
            sessionId, updated.iterations(), reason);
        return updated;
    }

    /**
     * 评估器判定「达成」→ 目标完成，摘 hook。
     *
     * <p><b>CC 语义（claude.exe v2.1.212 · 回合账本段）</b>：met 或 impossible 时
     * {@code yield {type:"active_goal", value:undefined}} ⇒ <b>清活动目标</b>，
     * 并 append {@code goal_status}（met:true / failed:true）。
     *
     * <p><b>终态留列（与 impossible 一致）</b>：「达成」是既成事实，DB 里应留权威痕迹 ——
     * 否则 F5 后只剩推送痕迹、无权威状态可读。
     *
     * <p><b>为什么留列不会让绿条复活</b>（曾担心的问题）：绿条复活的前提是「读侧把列里
     * 非 active 的终态也当成目标」。本仓三处读侧都<b>只认 active</b>：
     * <ul>
     *   <li>{@link #restoreForResume}：{@code if (!g.isActive()) return null} ⇒ 终态不复活；</li>
     *   <li>前端 {@code GoalHead}：{@code phase==='achieved'} 时显示绿条 + 6 秒后自行清列
     *       （绿条是一次性提示，不是常驻目标）；</li>
     *   <li>{@code phase==='impossible'} 时红条<b>常驻</b>，等用户手动清（UI 裁定）。</li>
     * </ul>
     * ⇒ 列存「该会话的目标及其当前阶段」，阶段决定读侧怎么显示，不是「有没有目标」。
     *
     * @param sessionId 会话 ID
     * @return 完成后的目标快照（供调用方落溯源行）；无目标 → null
     */
    public SessionGoal recordAchieved(String sessionId) {
        // [goal V253] 终态**留列**（与 impossible 一致）——
        //   「达成」是既成事实，DB 里应留痕（否则 F5 后只剩推送痕迹、无权威状态）。
        //   前端绿条淡出后由前端自行清列（见 GoalHead 的 6 秒定时器）。
        return finish(sessionId, SessionGoal.Phase.ACHIEVED);
    }

    /**
     * 评估器判定「无法达成」→ 目标停止推进，摘 hook（<b>不自动消失</b>，等用户处理）。
     *
     * <p><b>⚠️ 与 CC 的一处有意差异</b>：CC 在 met / impossible 两种结局下都
     * {@code yield {type:"active_goal", value:undefined}} 清活动目标（CC 的 goal UI 是命令式的，
     * 终态立即消失）。本仓 UI 裁定「<b>无法达成 = 红条常驻</b>，等用户处理」（见
     * {@code goal-mode-ui.html} 与 P5 溯源行）⇒ impossible 态<b>留在列里</b>，
     * 让前端 GET /goal 能拉回红条、用户手动 × 才清。
     *
     * <p>这不会让目标复活：{@link #restoreForResume} 判 {@code !isActive()} ⇒ 终态不重挂 hook，
     * 恢复时只当「曾经失败过」。
     *
     * @param sessionId 会话 ID
     * @return 结束后的目标；无目标 → null
     */
    public SessionGoal recordImpossible(String sessionId) {
        return finish(sessionId, SessionGoal.Phase.IMPOSSIBLE);
    }

    /**
     * 每轮终态分流 · 对齐 CC v2.1.325 的 {@code Pc() / ch(reason) / mh(goalPass)}
     * （claude.exe 偏移 ≈329,278,xxx）。
     *
     * <p><b>WHY 需要这一层</b>：Stop hook 只在「本轮走到 Stop 事件」时给出 goal 判定；
     * 若本轮以 API 错误 / 模型错误 / 中止 / 上下文超限等终态结束，CC 仍会把 reason 过一遍
     * 分流表（none / retry / pause / clear），本仓此前没有任何对应分支 ⇒ 目标停在 ACTIVE、
     * hook 可能已被 SessionEnd 摘掉，用户看到「目标还在但不再推进」。
     *
     * <p>分流口径（逐条对齐 {@code ch(reason)}）：
     * <ul>
     *   <li>NORMAL —— Stop hook 链路已处理，这里不重复；</li>
     *   <li>ABORTED / MAX_TURNS / INTERRUPTED —— none（对齐 aborted_* / max_turns）；</li>
     *   <li>STOP_HOOK_PREVENTED / HOOK_STOPPED —— pause(hook_stopped)；</li>
     *   <li>BLOCKING_LIMIT / PROMPT_TOO_LONG —— clear(context_limit)；</li>
     *   <li>MODEL_ERROR —— pause(turn_error)；</li>
     *   <li>STOP_HOOK_BLOCKING_LIMIT_EXCEEDED —— pause(goal_check_capped)；</li>
     *   <li>其余（IMAGE_ERROR / STREAM_ERROR / STREAM_TIMEOUT / MAX_OUTPUT_TOKENS /
     *       STRUCTURED_OUTPUT_RETRIES_EXCEEDED / NO_ASSISTANT_TEXT）—— retry。</li>
     * </ul>
     *
     * <p>⛔ 不照抄 CC 的 {@code isNonInteractiveSession} 短路：本仓 Web 后端所有会话都算
     * 非交互，照抄会让整层永不生效。
     *
     * @param sessionId 会话 ID
     * @param reason    本轮 ExitReason（可 null）
     */
    public void handleTurnTerminal(String sessionId, AgentState.ExitReason reason) {
        if (sessionId == null || sessionId.isBlank() || reason == null) {
            return;
        }
        SessionGoal goal = getGoal(sessionId);
        // 只处理推进态：PAUSED 已是「等用户发消息」的显式状态，重复暂停只会反复 publish；
        // 终态（ACHIEVED / IMPOSSIBLE）不再推进。
        if (goal == null || !goal.isActive()) {
            return;
        }
        switch (reason) {
            case NORMAL, ABORTED, MAX_TURNS, INTERRUPTED -> {
                // CC aborted_streaming / aborted_tools / max_turns → {kind:"none"}：不动目标
            }
            case STOP_HOOK_PREVENTED, HOOK_STOPPED -> {
                recordPaused(sessionId, "本轮被 hook 提前结束 · 发消息继续", false);
            }
            case BLOCKING_LIMIT, PROMPT_TOO_LONG -> {
                if (clearGoal(sessionId) != null) {
                    log.warn("[goal] 上下文达到上限，目标已清除（需重新 /goal）session={}", sessionId);
                }
            }
            case MODEL_ERROR -> {
                recordPaused(sessionId, "模型调用失败，本轮未完成 · 发消息继续", false);
            }
            case STOP_HOOK_BLOCKING_LIMIT_EXCEEDED -> {
                recordPaused(sessionId, "目标检查连续未通过，本轮已结束 · 发消息继续", false);
            }
            default -> scheduleEvaluatorRetry(sessionId, goal);
        }
    }

    /**
     * 评估器反复判定未达成、撞 Stop-hook block-cap ⇒ <b>暂停</b>目标（不报错终止）。
     *
     * <p><b>CC 语义（claude.exe v2.1.318）</b>：
     * <pre>
     * not_met → 继续 block + 重入，最多 CLAUDE_CODE_STOP_HOOK_BLOCK_CAP（默认 8）次
     *   ∥
     * 撞 cap → 打 warning「A hook blocked the turn from ending N consecutive times —
     *          overriding and ending turn」→ return {reason:"completed", goalPass:"not_met"}
     *   ∥
     * su()/kg()/bg("not_met") → {kind:"pause", cause:"goal_check_capped"}
     *   → 目标 pause + 提示「Goal paused · goal checks kept finding it unmet this turn ·
     *      send a message to continue」
     * </pre>
     *
     * <p><b>本仓旧实现的偏离（本次修复）</b>：撞 cap 时 {@code setError(...) +
     * STOP_HOOK_BLOCKING_LIMIT_EXCEEDED + break} = <b>报错终止</b>，用户看到的是失败而不是
     * 「目标暂停，发消息继续」。
     *
     * <p><b>为什么暂停而不是清空</b>：暂停保留了 condition / iterations / lastReason，
     * 用户发下一条消息即可继续（{@link #resumeFromPause}）；清空则要重新 /goal。
     *
     * @param sessionId 会话 ID
     * @param reason    暂停原因（写进 lastReason，前端「最近一次判定」显示）
     * @return 暂停后的目标；无目标 → null
     */
    public SessionGoal recordPaused(String sessionId, String reason) {
        return recordPaused(sessionId, reason, true);
    }

    /**
     * 暂停目标 · 可选是否把本次暂停算作一轮评估。
     *
     * @param sessionId      会话 ID
     * @param reason         暂停原因（写进 lastReason，前端「最近一次判定」显示）
     * @param countIteration 本次暂停是否算一轮评估：
     *                       <ul>
     *                         <li>{@code true} —— 评估器<b>给出了结论</b>（判未达成撞 cap）。
     *                             调用点：{@code LlmAgentLoop} 的 goal_check_capped 分支。</li>
     *                         <li>{@code false} —— 评估器<b>连续报错放弃</b>，没有任何一轮真正
     *                             评出结论。调用点：{@code scheduleEvaluatorRetry} 的 gave_up
     *                             分支（对齐 CC {@code su()} 的 {@code announced:"gave_up"}）。</li>
     *                       </ul>
     * @return 暂停后的目标；无目标（含期间已被清除）→ null
     */
    public SessionGoal recordPaused(String sessionId, String reason, boolean countIteration) {
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        SessionGoal progressed = countIteration ? g.withIterations(g.iterations() + 1) : g;
        SessionGoal updated = progressed
            .withReason(reason)
            .withPhase(SessionGoal.Phase.PAUSED)
            // [B6 2026-11-04] 暂停 = 评估器成功判了一轮的结论 ⇒ 清重试计数
            //   （否则下一次用户发消息继续时，会带着上一轮的 retryCount 起算）
            .clearRetryFully();
        sessionService.setGoal(sessionId, updated);
        // 暂停态摘 hook：本轮不再续评。用户下一条消息触发 resumeFromPause 才重挂。
        disarm(sessionId);
        publish(sessionId, updated);
        log.info("[GoalService] 目标暂停 phase=PAUSED, session={} 第 {} 轮, reason={}",
            sessionId, updated.iterations(), reason);
        return updated;
    }

    /**
     * 用户发了新消息 ⇒ 暂停态自动恢复为推进态并重挂 Stop hook · 对齐 CC。
     *
     * <p><b>CC 语义</b>：pause 只是 {@code bg()→{kind:"pause"}} 的行为层结论，
     * {@code activeGoal} 本身还在（hook 也在），所以用户下一条消息进入新一轮时目标自然继续。
     * 本仓 pause 是显式 phase 值且摘了 hook ⇒ 必须在新一轮开始时显式恢复。
     *
     * <p><b>何时调用</b>：{@code LlmAgentLoop} 每轮入口 / {@code ChatService} 恢复目标时。
     * 只对 {@code PAUSED} 生效（ACTIVE 已挂着 hook，不需要重挂；终态不复活）。
     *
     * @param sessionId           会话 ID
     * @param currentOutputTokens 当前输出 token 累计值（CC {@code DA()} 口径；重挂时重置基线）
     * @return 恢复后的目标；无需恢复 → null
     */
    public SessionGoal resumeFromPause(String sessionId, long currentOutputTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionGoal g = getGoal(sessionId);
        if (g == null || !g.isPaused()) {
            return null;
        }
        SessionGoal resumed = new SessionGoal(
            g.condition(), SessionGoal.Phase.ACTIVE, g.iterations(),
            System.currentTimeMillis(), currentOutputTokens, null);
        sessionService.setGoal(sessionId, resumed);
        arm(sessionId, resumed.condition());
        publish(sessionId, resumed);
        log.info("[GoalService] 目标从暂停恢复为推进态（用户发来新消息）session={} 已评估 {} 轮",
            sessionId, resumed.iterations());
        return resumed;
    }

    /** 终态统一处理：更新列 + 摘 hook。 */
    private SessionGoal finish(String sessionId, SessionGoal.Phase phase) {
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        // [B6 2026-11-04] 终态 ⇒ 清重试排程与计数（评估器成功给出了终局判定）
        SessionGoal updated = g.withIterations(g.iterations() + 1).withPhase(phase).clearRetryFully();
        sessionService.setGoal(sessionId, updated);
        disarm(sessionId);
        publish(sessionId, updated);
        log.info("[GoalService] 目标结束 phase={}, session={} 第 {} 轮", phase, sessionId, updated.iterations());
        return updated;
    }

    // ════════════════════════════════════════════════════════════════════
    // 恢复（每轮开头）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 轮首恢复目标 · 语义 = <b>只把「内存态」读回来</b>（真正的目标数据在 {@code sessions.goal} 列）。
     *
     * <p><b>CC 语义（src/services/goal/goalState.ts）</b>：CC 的 goal 是纯内存 {@code Map}，
     * 进程重启就没了；{@code turnsExecuted} 的<b>唯一归零点</b>是
     * {@code setGoal()}（新目标）与 {@code continueGoalFromMaxTurns()}（用户显式
     * {@code /goal continue}）；{@code resumeGoal()} 只重置 {@code startTime}，
     * <b>保留</b> {@code turnsExecuted}。
     *
     * <p>⛔ <b>本方法不得重置任何计数/基线</b>。此前它在重挂 hook 时顺手写了
     * {@code iterations=0 / setAt=now / tokensAtStart=当前}，而它挂在
     * {@code ChatService.processUserMessage}（<b>每条用户消息</b>）上 ⇒
     * 评估器刚记下的「第 N 轮」与「已运行时长」被每条消息清零 ⇒
     * 目标头恒显示「第 1 轮」、时间恒从最新一条消息起算（真机日志
     * 09:35:20 记第 1 轮 → 09:40:02 / 09:44:41 两次 restore 归零）。
     *
     * <p>CC 的规则逐条对应：
     * <ol>
     *   <li>内存已 armed 且注册表里真有这个 hook ⇒ 跳过（fresh 守卫）</li>
     *   <li>目标已 met/failed ⇒ 不恢复（终态不复活）</li>
     *   <li>否则<b>仅</b>重新注册 prompt hook，状态字段原样保留</li>
     * </ol>
     *
     * @param sessionId 会话 ID
     * @param currentOutputTokens 当前输出 token 累计值（本方法已不再使用，保留入参以兼容调用点）
     * @return 恢复后的目标；无需恢复 / 无目标 → null
     */
    public SessionGoal restoreForResume(String sessionId, long currentOutputTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        // fresh 守卫：已 armed 且条件一致 ⇒ 不重复挂（照 ChatService.restoreWorktreeForResume 的形状）
        //   ⚠️ 判据必须核实注册表：hook 可能被 SessionEnd 的 clearSessionHooks 摘掉，而内存标记
        //   不知道 ⇒ 只看内存会「以为还挂着」直接返回，目标 hook 永久丢失。
        String armed = armedConditions.get(sessionId);
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        if (armed != null && armed.equals(g.condition()) && isArmed(sessionId)) {
            return g;
        }
        if (armed != null) {
            // 内存说有、注册表说没有（或条件已过期）⇒ 丢掉过期标记，走下面重新挂
            armedConditions.remove(sessionId);
        }
        // [B7 · 2026-11-04] 暂停态 ⇒ 用户发来消息即为「继续」，恢复推进（对齐 CC）：
        //   CC 的 pause 只是行为层结论（activeGoal + hook 都还在），用户下一条消息自然续评；
        //   本仓 pause 是显式 phase 且摘了 hook ⇒ 必须在这里显式恢复，而不是要求 /goal resume。
        if (g.isPaused()) {
            return resumeFromPause(sessionId, currentOutputTokens);
        }
        // 已完成 / 已放弃的目标不复活
        if (!g.isActive()) {
            return null;
        }
        // ⛔ 只重挂 hook，**不重写目标状态**：iterations / setAt / tokensAtStart / lastReason
        //   全部原样保留。CC 的 turnsExecuted 只在 setGoal（新目标）与 /goal continue 归零，
        //   resume 时保留（goalState.ts resumeGoal 只重置 startTime）。
        //   ⇒ 这里连 setGoal/publish 都不需要：列里的值本来就是正确的，多写一次反而制造
        //   「读取-写到一半进程挂掉」的窗口。
        arm(sessionId, g.condition());
        log.info("[GoalService] restoreForResume: session={} 目标 hook 已重挂（第 {} 轮，状态原样保留）",
            sessionId, g.iterations());
        return g;
    }

    // ════════════════════════════════════════════════════════════════════
    // 溯源消息（达成 / 无法达成 落进本轮「已完成」折叠区）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构造目标终态的溯源消息 · 对齐 CC 的 goal_status 附件语义。
     *
     * <p><b>形态</b>：{@code role=user + isMeta=true + author='attachment' + subtype='goal_status'}。
     * 前端 {@code isSystemInjectedRow} 的 {@code isMeta} 分支已天然覆盖 {@code author='attachment'}
     * ⇒ 这行自动进该轮「已完成」折叠区，<b>不</b>开新轮、<b>不</b>当用户气泡。
     *
     * <p><b>WHY 要有这一行</b>：目标头（输入框上的胶囊）达成后会淡出消失，但「这轮是按目标
     * 跑完的」这件事应当留在 transcript 里可回溯。
     *
     * @param goal 目标终态快照
     * @return 可 append 到消息流的消息
     */
    public com.nexusai.model.session.dto.ChatMessageDto buildTerminalTraceMessage(
            String sessionId, SessionGoal goal) {
        boolean met = goal.phase() == SessionGoal.Phase.ACHIEVED;
        boolean failed = goal.phase() == SessionGoal.Phase.IMPOSSIBLE;
        String content = met
            ? "◎ 目标已达成 · " + goal.iterations() + " 轮"
            : "◎ 目标无法达成 · " + goal.iterations() + " 轮";
        // [goal-align 2026-11-14] 对齐 CC v2.1.325 的 goal_status 附件字段：
        //   {type, condition, met, failed, reason, iterations, durationMs, tokens}。
        //   durationMs = 评估结束 - 设定时刻；tokens = 目标期间 output token 合计
        //   （与「用量与花费」页同源；取不到记 null，⛔ 不回落 0）。
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "goal_status");
        payload.put("condition", goal.condition());
        payload.put("met", met);
        payload.put("failed", failed);
        if (goal.lastReason() != null && !goal.lastReason().isBlank()) {
            payload.put("reason", goal.lastReason());
        }
        payload.put("iterations", goal.iterations());
        payload.put("durationMs", Math.max(0L, System.currentTimeMillis() - goal.setAt()));
        Long tokens = null;
        if (statsService != null && sessionId != null && !sessionId.isBlank()) {
            try {
                tokens = statsService.outputTokensSince(sessionId, goal.setAt());
            } catch (Exception e) {
                log.warn("[goal] goal_status tokens 聚合失败（记 null）session={}: {}", sessionId, e.toString());
            }
        }
        if (tokens != null) {
            payload.put("tokens", tokens);
        }
        return new com.nexusai.model.session.dto.ChatMessageDto(
            java.util.UUID.randomUUID().toString(),
            null,
            com.nexusai.model.session.dto.Role.user,
            "attachment",
            content,
            null, null, null, null, null, null, null,
            null, null, null,
            java.util.List.of(), java.util.List.of(), payload,   // 第 18 参 = structuredOutput
            true,                              // isMeta = true（系统注入，不是用户气泡）
            false, null,
            "goal_status",                    // subtype（前端 systemRowLabel 据此出人话）
            false, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 后台任务门控（CC 的 goal 门控）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 后台工作未归位时<b>临时摘掉</b>目标 hook · 对齐 CC claude.exe v2.1.212。
     *
     * <p>CC 的做法（原文）：
     * <pre>
     * if (有未完成的 local_agent / remote_agent / local_bash) {
     *   摘掉 goal 的 prompt Stop hook
     *   log("[goal] evaluation deferred — background work still running")
     *   // …同一段代码的 finally 里 sessionHooksRegistry.add(...) 挂回
     * }
     * </pre>
     * ⇒ 本轮<b>不评估</b>目标（不烧 LLM），等后台归位后的下一轮才真正判停。
     *
     * <p>不这么做的话：子代理还没跑完，评估器读 transcript 发现「证据不足」→ 判 not-met →
     * reason 注入下一轮 ⇒ 模型被误导（「证据不足」其实只是「还没跑完」）+ 白烧一轮 LLM。
     *
     * @param sessionId 会话 ID
     * @return true = 已摘掉（进入延迟评估）
     */
    public boolean deferEvaluationForBackground(String sessionId) {
        String condition = armedConditions.get(sessionId);
        if (condition == null) {
            return false;
        }
        HookRegistry registry = hookRegistry();
        if (registry == null) {
            return false;
        }
        PromptHook promptHook = new PromptHook(condition, null, null, null, null, null);
        registry.removeSessionHook(sessionId, HookEventType.STOP, promptHook);
        deferredSessions.add(sessionId);
        // [B5 2026-11-04] 把延迟起点写进列 ⇒ GoalCheckinScheduler 才能跨请求看到它。
        //   对齐 CC v2.1.318：`let ve=Qa(j,pe,Date.now()); Ke=ve.nextGoal;` 里
        //   Qa() 产出的 nextGoal 带 deferredSince/checkinCount/lastDeferralPassAt 三字段。
        //   ⚠️ 只写 deferredSince（首次进入延迟时），不重置已有计数 —— 否则退避永远回不到 2x/4x。
        try {
            SessionGoal g = getGoal(sessionId);
            if (g != null && g.deferredSince() == null) {
                long now = System.currentTimeMillis();
                SessionGoal updated = g.withDeferral(now, 0, now);
                sessionService.setGoal(sessionId, updated);
                publish(sessionId, updated);
            }
        } catch (Exception e) {
            log.warn("[goal] 写入延迟评估起点失败（不影响本轮不评估）session={}: {}",
                sessionId, e.toString());
        }
        log.info("[goal] evaluation deferred — background work still running (session={})", sessionId);
        return true;
    }

    /**
     * 后台工作已归位 ⇒ 清掉延迟态（对齐 CC 的 {@code else if(j.deferredSince !== void 0)} 分支）。
     *
     * <p><b>WHY 必须清</b>：不清的话 {@code GoalCheckinScheduler} 会一直认为该目标还在等后台任务，
     * 按退避节奏无谓地注入 check-in 提示（用户看到多余的一轮）。
     *
     * @param sessionId 会话 ID
     * @return true = 确实清掉了一个延迟态
     */
    public boolean clearDeferralIfAny(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        SessionGoal g = getGoal(sessionId);
        if (g == null || g.deferredSince() == null) {
            return false;
        }
        SessionGoal cleared = g.clearDeferral();
        sessionService.setGoal(sessionId, cleared);
        publish(sessionId, cleared);
        log.info("[goal] 后台工作已归位 ⇒ 清除延迟态 session={}", sessionId);
        return true;
    }

    /**
     * 评估窗口结束 ⇒ 挂回被延迟的目标 hook · 对齐 CC 的 finally 复原。
     *
     * @param sessionId 会话 ID
     * @return true = 本轮曾被延迟（本次复原了 hook）
     */
    public boolean resumeDeferredEvaluation(String sessionId) {
        if (!deferredSessions.remove(sessionId)) {
            return false;
        }
        SessionGoal g = getGoal(sessionId);
        if (g == null || !g.isActive()) {
            return true;   // 目标已结束（清空/达成/放弃）→ 不必挂回
        }
        String condition = armedConditions.get(sessionId);
        if (condition == null) {
            // 内存 armed 态丢失（理论上不该发生）→ 用列里的条件补挂
            arm(sessionId, g.condition());
        } else {
            HookRegistry registry = hookRegistry();
            if (registry == null) {
                return true;
            }
            PromptHook promptHook = new PromptHook(condition, null, null, null, null, null);
            registry.addSessionHook(sessionId, HookEventType.STOP, "", promptHook, null, null);
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // hook 挂载
    // ════════════════════════════════════════════════════════════════════

    /**
     * 挂上 prompt Stop hook。
     *
     * <p>⚠️ <b>matcher 必须传空串</b>：CC 的 {@code Eor()} 只认
     * {@code matcher==="" && skillRoot===undefined} 的 prompt hook；我们这边同理 ——
     * 空 matcher 对 Stop 事件（无匹配字段）必然通过（HookMatcherEngine.matchesMatcher：
     * {@code matchQuery==null → 全部 matcher 通过}），非空 matcher 反而可能不命中。
     */
    private void arm(String sessionId, String condition) {
        PromptHook promptHook = new PromptHook(
            condition,   // prompt = 目标条件原文
            null,        // if
            null,        // timeout → 走 30s 默认（CC execPromptHook.ts:55）
            null,        // model → 走 default fast model（CC :79）
            null,        // statusMessage
            null);       // once = false（CC 的 goal hook 不是 once）
        HookRegistry registry = hookRegistry();
        if (registry == null) {
            log.warn("[GoalService] HookRegistry 未接线，目标 hook 无法挂载（session={}）", sessionId);
            return;
        }
        registry.addSessionHook(sessionId, HookEventType.STOP, "", promptHook, null, null);
        armedConditions.put(sessionId, condition);
    }

    /**
     * 摘掉 prompt Stop hook。
     *
     * <p>⚠️ 必须用<b>同一个</b> PromptHook 实例（或字段全等的实例）才能摘掉 ——
     * {@code removeSessionHook} 走 {@code isHookEqual} 比较。
     */
    private void disarm(String sessionId) {
        String condition = armedConditions.remove(sessionId);
        if (condition == null) {
            return;
        }
        HookRegistry registry = hookRegistry();
        if (registry == null) {
            return;
        }
        PromptHook promptHook = new PromptHook(condition, null, null, null, null, null);
        registry.removeSessionHook(sessionId, HookEventType.STOP, promptHook);
    }
}
