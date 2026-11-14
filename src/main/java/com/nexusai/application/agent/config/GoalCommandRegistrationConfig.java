package com.nexusai.application.agent.config;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.goal.GoalService;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.domain.session.SessionGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * [/goal] 目标命令的 slash handler 注册（副作用 bean）· 对齐 CC claude.exe v2.1.212 的
 * goal 斜杠命令。
 *
 * <p><b>WHY 需要独立注册</b>：{@code BuiltInCommands} 只声明命令<b>元数据</b>（name/type/description），
 * 执行 handler 走 {@link UserInputDispatcher#registerSlashCommandResult}（与 /compact 同一范式，
 * 见 {@code ToolRegistrationConfig.compactCommandRegistration}）。本类把两者接起来。
 *
 * <p><b>CC 的 /goal 语义</b>（一手核对 claude.exe v2.1.212）：
 * <ul>
 *   <li>无参 → {@code Goal active: <c> (N turns)} 或 {@code No goal set}</li>
 *   <li>{@code clear|stop|off|reset|none|cancel}（大小写不敏感）→ 清空</li>
 *   <li>其余 → 设定条件（上限 4000 字符，超长拒绝并回报实际长度）</li>
 * </ul>
 */
@Configuration
public class GoalCommandRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(GoalCommandRegistrationConfig.class);

    /**
     * 副作用 @Bean：注册 /goal 的执行 handler。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使副作用
     * 在上下文刷新时必然执行（单例预实例化）—— 与 /compact 的注册同款做法。
     *
     * @param dispatcher   slash 分发器（@Component 单例）
     * @param goalService  目标服务（@Service 单例）
     * @param sessionRegistry 会话 AgentState 注册表（取输出 token 累计值）
     * @return 副作用标记 record（无状态，仅保证注册副作用执行）
     */
    @Bean
    public GoalSlashRegistration goalSlashRegistration(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) GoalService goalService,
            @Autowired(required = false) SessionAgentStateRegistry sessionRegistry,
            @Autowired(required = false) HookRegistry hookRegistry) {
        if (dispatcher == null) {
            log.warn("[GoalCommandRegistrationConfig] UserInputDispatcher 未注入，/goal handler 注册跳过");
            return new GoalSlashRegistration();
        }
        if (goalService == null) {
            log.warn("[GoalCommandRegistrationConfig] GoalService 未注入，/goal handler 注册跳过");
            return new GoalSlashRegistration();
        }
        dispatcher.registerSlashCommandResult("goal",
            (args, sessionId, inFlightUserMessageId) ->
                handleGoal(args, sessionId, goalService, sessionRegistry, hookRegistry));
        log.info("[GoalCommandRegistrationConfig] /goal 已注册为生产 slash command · 对齐 CC goal 斜杠命令");
        return new GoalSlashRegistration();
    }

    /** 副作用 bean 标记 record · 使注册副作用在 context refresh 时执行。 */
    public record GoalSlashRegistration() {}

    /**
     * /goal handler 主体 · 对齐 CC 的 goal 命令 call 语义。
     *
     * @param args           命令名后的参数文本（已 trim；可为空）
     * @param sessionId      会话 ID
     * @param goalService    目标服务
     * @param sessionRegistry 会话 AgentState 注册表（取输出 token 累计值；null → 0）
     * @return local-command-stdout 文本
     */
    private UserInputDispatcher.LocalCommandResult handleGoal(
            String args, String sessionId, GoalService goalService,
            SessionAgentStateRegistry sessionRegistry, HookRegistry hookRegistry) {
        return handleGoalForTest(args, sessionId, goalService, sessionRegistry, hookRegistry);
    }

    /**
     * [/goal handler 主体] 的测试入口 · 对齐 CC 的 goal 命令 call 语义。
     *
     * <p>WHY 暴露：本 handler 是「设定目标 ⇒ 返回 query + kickoff」这条契约的唯一实现点，
     * 而它注册在 {@link UserInputDispatcher} 的 lambda 里（无法直接测）⇒ 抽出同款公开入口。
     *
     * @param args           命令名后的参数文本（可为空）
     * @param sessionId      会话 ID
     * @param goalService    目标服务
     * @param sessionRegistry 会话 AgentState 注册表（取输出 token 累计值；null → 0）
     * @return text（只读状态）/ query（设定目标 + kickoff 提示词 ⇒ 触发一轮执行）
     */
    public UserInputDispatcher.LocalCommandResult handleGoalForTest(
            String args, String sessionId, GoalService goalService,
            SessionAgentStateRegistry sessionRegistry) {
        return handleGoalForTest(args, sessionId, goalService, sessionRegistry, null);
    }

    /**
     * [/goal handler] 带 hooks 政策门的版本 · 对齐 CC v2.1.325 {@code uGt()} 的 {@code T6n()} 门控。
     *
     * @param hookRegistry hooks 注册中心（null = 未接线 → 不做门控）
     */
    public UserInputDispatcher.LocalCommandResult handleGoalForTest(
            String args, String sessionId, GoalService goalService,
            SessionAgentStateRegistry sessionRegistry, HookRegistry hookRegistry) {
        String raw = args == null ? "" : args.trim();

        // ── 无参：显示当前目标（CC：Goal active: <c> (N turns) / No goal set）──
        if (raw.isEmpty()) {
            SessionGoal goal = goalService.getGoal(sessionId);
            if (goal == null) {
                return UserInputDispatcher.LocalCommandResult.text("No goal set");
            }
            return UserInputDispatcher.LocalCommandResult.text(describe(goal));
        }

        // ── 清空意图（CC 别名集合 clear/stop/off/reset/none/cancel，大小写不敏感）──
        if (GoalService.isClearIntent(raw)) {
            String cleared = goalService.clearGoal(sessionId);
            if (cleared == null) {
                return UserInputDispatcher.LocalCommandResult.text("No goal set");
            }
            // 逐字对齐 CC：「Goal cleared: <condition>」（不是裸的 Goal cleared）
            return UserInputDispatcher.LocalCommandResult.text("Goal cleared: " + cleared);
        }

        // ── 设定门 · 对齐 CC v2.1.325 hooks_gate ──
        //   hooks 被策略禁用（disableAllHooks / allowManagedHooksOnly）时 goal 的 Stop hook
        //   根本不会执行 ⇒ 设了也是死目标。CC 直接拒绝并给这句文案（逐字）。
        if (hookRegistry != null && hookRegistry.isHooksRestricted()) {
            return UserInputDispatcher.LocalCommandResult.text(
                "/goal can't run while hooks are restricted (disableAllHooks or "
                + "allowManagedHooksOnly is set in settings or by policy).");
        }

        // ── 设定目标 ──
        //   CC 返回 {type:"query", value:"Goal set: <c>", prompt:kickoff} ⇒ **触发一轮执行**。
        long tokensAtStart = currentOutputTokens(sessionRegistry, sessionId);
        try {
            SessionGoal goal = goalService.setGoal(sessionId, raw, tokensAtStart);
            return UserInputDispatcher.LocalCommandResult.query(
                "Goal set: " + goal.condition(),
                kickoffPrompt(goal.condition()));
        } catch (IllegalArgumentException e) {
            // CC：超长 / 空条件 → 拒绝并回报（不抛到用户）
            log.warn("[goal] 设定目标被拒 session={}: {}", sessionId, e.getMessage());
            return UserInputDispatcher.LocalCommandResult.text(e.getMessage());
        }
    }

    /**
     * kickoff 提示词 · 对齐 CC claude.exe v2.1.212 的 {@code tmo(condition)}（逐字）。
     *
     * <p><b>WHY 需要它</b>：CC 的 {@code /goal} 设定目标后返回 {@code {type:"query", prompt:kickoff}}，
     * 该提示词作为 <b>isMeta 的 user 消息</b>注入本轮 ⇒ 模型看到「目标已激活，立即开始朝它工作」
     * ⇒ <b>目标模式立刻开始执行</b>（否则只记下目标、模型在等下一条消息）。
     *
     * <p>⚠️ 本仓 prompt 型命令的 {@code metaMessageContent} 走
     * {@code ChatService:865-887} 的 isMeta 落库通道（UI 隐藏、模型可见、DB 持久化）——
     * 与 CC 的 {@code messages:[..., {content:b.prompt, isMeta:true}]} 同构。
     */
    static String kickoffPrompt(String condition) {
        return "A session-scoped Stop hook is now active with condition: \"" + condition + "\". "
            + "Briefly acknowledge the goal, then immediately start (or continue) working toward it "
            + "— treat the condition itself as your directive and do not pause to ask the user what to do. "
            + "The hook will block stopping until the condition holds. "
            + "It auto-clears once the condition is met — do not tell the user to run `/goal clear` "
            + "after success; that's only for clearing a goal early.";
    }

    /**
     * 目标状态的人话描述 · 对齐 CC {@code /goal} 无参输出格式
     * （{@code Goal active: <condition> (N turns)}；未评估过则 {@code not yet evaluated}）。
     */
    private String describe(SessionGoal goal) {
        String turns = goal.iterations() == 0
            ? "not yet evaluated"
            : goal.iterations() + (goal.iterations() == 1 ? " turn" : " turns");
        String suffix = goal.lastReason() == null || goal.lastReason().isBlank()
            ? ""
            : "\nLast check: " + goal.lastReason().trim();
        return "Goal active: " + goal.condition() + " (" + turns + ")" + suffix;
    }

    /**
     * 当前输出 token 累计值 · 对齐 CC 的 {@code DA()} 口径
     * （{@code sumBy(modelUsage, 'outputTokens')} —— <b>只算输出 token</b>，不是总量）。
     */
    private long currentOutputTokens(SessionAgentStateRegistry sessionRegistry, String sessionId) {
        if (sessionRegistry == null || sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        try {
            com.nexusai.application.agent.AgentState state = sessionRegistry.get(sessionId);
            return state == null ? 0L : state.sessionOutputTokens();
        } catch (Exception e) {
            log.warn("[goal] 读取输出 token 累计值失败 session={}: {}", sessionId, e.toString());
            return 0L;
        }
    }
}
