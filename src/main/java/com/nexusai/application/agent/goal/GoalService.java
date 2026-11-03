package com.nexusai.application.agent.goal;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PromptHook;
import com.nexusai.domain.session.SessionGoal;
import com.nexusai.domain.session.SessionService;
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

    /** 该会话的 prompt Stop hook 是否已挂（内存态判据）。 */
    public boolean isArmed(String sessionId) {
        return sessionId != null && armedConditions.containsKey(sessionId);
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
                "目标条件长度超过上限 " + SessionGoal.MAX_CONDITION_LENGTH + " 字符（实际 " + condition.length() + "）");
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
        SessionGoal updated = g.withIterations(g.iterations() + 1).withReason(reason);
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
     * <p>⚠️ <b>为什么达成后列要置 null 而不是留 phase=achieved</b>：列是<b>真源</b>，
     * 读侧（GET /goal、resume 恢复）都按「列里有值 = 该会话有目标」解释 ⇒ 若列里留着
     * {@code phase=achieved}，切会话/F5 会把一条<b>已结束</b>的目标重新拉回前端（绿条复活），
     * resume 也会看到一条「曾经达成」的目标。达成这件事由两处承载：
     * <ul>
     *   <li><b>即时</b>：本次 {@code publish(sessionId, achieved)} 推 STOMP ⇒ 前端显绿条</li>
     *   <li><b>历史</b>：{@code goal_status} 溯源行落进该轮「已完成」折叠区（messages，append-only）</li>
     * </ul>
     * ⇒ 列只存「当前还在追的目标」，终态不驻留。
     *
     * @param sessionId 会话 ID
     * @return 完成后的目标快照（供调用方落溯源行）；无目标 → null
     */
    public SessionGoal recordAchieved(String sessionId) {
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        SessionGoal achieved = g.withIterations(g.iterations() + 1).withPhase(SessionGoal.Phase.ACHIEVED);
        disarm(sessionId);
        // 列置 null（终态不驻留 · 见 javadoc）—— 但先把 achieved 快照推给前端（绿条）
        publish(sessionId, achieved);
        sessionService.setGoal(sessionId, null);
        log.info("[GoalService] 目标达成, session={} 第 {} 轮（列已清 · 终态只留推送与溯源行）",
            sessionId, achieved.iterations());
        return achieved;
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

    /** 终态统一处理：更新列 + 摘 hook。 */
    private SessionGoal finish(String sessionId, SessionGoal.Phase phase) {
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        SessionGoal updated = g.withIterations(g.iterations() + 1).withPhase(phase);
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
     * 轮首恢复目标 · 对齐 CC {@code restoreGoalFromTranscript}（但真源读列而非反推 transcript）。
     *
     * <p>CC 规则：
     * <ol>
     *   <li>内存已 armed ⇒ 跳过（fresh 守卫，防重复挂 hook）</li>
     *   <li>目标已 met/failed ⇒ 不恢复</li>
     *   <li>否则重新注册 prompt hook，并<b>重置</b> {@code iterations=0} / {@code setAt=now} /
     *       {@code tokensAtStart=当前}</li>
     * </ol>
     *
     * @param sessionId 会话 ID
     * @param currentOutputTokens 当前输出 token 累计值（CC {@code DA()} 口径）
     * @return 恢复后的目标；无需恢复 / 无目标 → null
     */
    public SessionGoal restoreForResume(String sessionId, long currentOutputTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        // fresh 守卫：已 armed 且条件一致 ⇒ 不重复挂（照 ChatService.restoreWorktreeForResume 的形状）
        String armed = armedConditions.get(sessionId);
        SessionGoal g = getGoal(sessionId);
        if (g == null) {
            return null;
        }
        if (armed != null && armed.equals(g.condition())) {
            return g;
        }
        // 已完成 / 已放弃的目标不复活
        if (!g.isActive()) {
            return null;
        }
        // 重置计时与 token 基线（CC 同款）
        SessionGoal restored = new SessionGoal(
            g.condition(), SessionGoal.Phase.ACTIVE, 0, System.currentTimeMillis(), currentOutputTokens, null);
        sessionService.setGoal(sessionId, restored);
        arm(sessionId, restored.condition());
        publish(sessionId, restored);
        log.info("[GoalService] restoreForResume: session={} 目标已恢复（重置 setAt/tokensAtStart 基线）", sessionId);
        return restored;
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
    public com.nexusai.model.session.dto.ChatMessageDto buildTerminalTraceMessage(SessionGoal goal) {
        boolean met = goal.phase() == SessionGoal.Phase.ACHIEVED;
        String content = met
            ? "◎ 目标已达成 · " + goal.iterations() + " 轮"
            : "◎ 目标无法达成 · " + goal.iterations() + " 轮";
        return new com.nexusai.model.session.dto.ChatMessageDto(
            java.util.UUID.randomUUID().toString(),
            null,
            com.nexusai.model.session.dto.Role.user,
            "attachment",
            content,
            null, null, null, null, null, null, null,
            null, null, null,
            java.util.List.of(), java.util.List.of(), null,
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
        log.info("[goal] evaluation deferred — background work still running (session={})", sessionId);
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
