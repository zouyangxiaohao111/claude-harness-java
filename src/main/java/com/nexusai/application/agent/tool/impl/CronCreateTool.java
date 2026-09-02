package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.agent.tool.cron.CronExpressionConverter;
import com.nexusai.application.agent.tool.cron.CronJitter;
import com.nexusai.application.agent.tool.cron.CronToHuman;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * CronCreate 工具 · 对齐 CC {@code CronCreateTool.ts} (P0.1).
 *
 * <p><b>字段映射 (CC → Java)</b>:
 * <ul>
 *   <li>CC {@code cron} (5-field) → Java {@code cron} (6-field 契约文本, 决策#5; Quartz
 *       6-field 兼容由 {@code QuartzScheduleService.buildTrigger} 处理, 5 段转 6 段兼容保留)</li>
 *   <li>CC {@code prompt} → Java {@code command} (ScheduleCreateRequest.command)</li>
 *   <li>CC {@code recurring} (boolean, default true) → Java {@code kind}: true=cron, false=once</li>
 *   <li>CC {@code durable} (boolean, default false) → Java {@code scope} 经 effectiveDurable
 *       （{@code durable && isDurableCronEnabled()}，CC CronCreateTool.ts:120）：
 *       true=DURABLE (DB 持久化), false=SESSION (内存态, kill-switch 强制 session-only,
 *       注入 ctx.sessionId)</li>
 *   <li>CC {@code agentId} (teammate 创建者, runtime-only, cronTasks.ts:69) → Java
 *       {@code ScheduleCreateRequest.agentId}：SESSION 分支读
 *       {@code TeammateContext.getTeammateContext()?.getData().agentId()}（对齐 CC
 *       CronCreateTool.ts:126 getTeammateContext()?.agentId 作 addCronTask 实参；主线程 ctx
 *       恒 null → agentId=null）。DURABLE 分支恒 null（CC durable 路径 push task 不含
 *       agentId，cronTasks.ts:215-217）</li>
 * </ul>
 *
 * <p><b>L1 行为</b>: 创建 schedule job 后返回 id + humanSchedule, 让 LLM 可用 id 调 CronDelete.
 *
 * <p><b>复用</b>: ScheduleService.create (DB + Quartz 注册 + sessionJobs 索引).
 * 不重写 cron 解析 / Quartz 触发 / 跨进程锁.
 * <p><b>CAND-3（r2 整合版 §六）</b>: 工具层 {@code catch(Exception)} + {@code e.getMessage()}
 * 泄漏已删除——异常抛框架层 StreamingToolExecutor 统一错误面（对齐 CC CronCreateTool.ts:117-142
 * call() 无 catch；ToolErrorFormatter.formatError → {@code <ExceptionClass>: <message>}）。</p>
 *
 * <p><b>[IMP-F2] setScheduledTasksEnabled 判定 N/A（组 5-3 / ?-2 闭环）</b>: CC call 末尾
 * {@code setScheduledTasksEnabled(true)}（CronCreateTool.ts:128-133）是<b>会话内 tick 循环使能</b>
 * （useScheduledTasks hook 轮询该 flag 启动定时循环）。Java 为服务端常驻 Quartz 调度器
 * （CronIdleExecutor {@code @Scheduled} 3s 轮询 + ApplicationReady 对账，无 enable flag），
 * 任务创建即注册 Quartz trigger 生效 → 该调用在 Java 架构下语义 N/A，不实现等价调用（grep 全仓
 * setScheduledTasksEnabled 0 命中为预期）。
 *
 * <p><b>[CRON-A5] 门控</b>: 默认注册到 ToolRegistry（matchIfMissing=true，对齐 CC 生产
 * {@code AGENT_TRIGGERS} 编译 true G15）+ 运行时 isEnabled() 委托
 * {@link CronEnabledGates#isKairosCronEnabled()}（对齐 CC prompt.ts:36-45）。
 * 开关: {@code nexusai.feature.agent-trigger-cron=true}（默认 true）。
 */
@Component
@ConditionalOnProperty(name = "nexusai.feature.agent-trigger-cron", havingValue = "true", matchIfMissing = true)
public class CronCreateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CronCreateTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * CC original: DEFAULT_MAX_AGE_DAYS (prompt.ts:8) = DEFAULT_CRON_JITTER_CONFIG.recurringMaxAgeMs / 86400000 = 7 天.
     * 供 tool_result 文本 "Auto-expires after {N} days" 对齐 CC mapToolResult (:151)。
     * 源自 {@link CronJitterProperties#DEFAULTS#recurringMaxAgeMs()}（对齐 CC prompt.ts:8 由默认配置派生）。
     */
    private static final int DEFAULT_MAX_AGE_DAYS =
        (int) (CronJitterProperties.DEFAULTS.recurringMaxAgeMs() / (24L * 3600 * 1000));

    /** 对齐 CC CronCreateTool.ts:25 MAX_JOBS=50 (ScheduleService.MAX_JOBS 同步强制). */
    private final ScheduleService scheduleService;

    /** 运行时门 · CC original: isKairosCronEnabled (prompt.ts:36-45) / isDurableCronEnabled (:56-62). */
    private final CronEnabledGates cronGates;

    /** CRON-F1 · Spring 抖动配置源（application.yml nexusai.cron.jitter.*）· CC original: getCronJitterConfig (cronJitterConfig.ts:67-75)。 */
    private final CronJitterProperties jitterProps;

    /** 2 参便捷构造（测试用）· jitterProps 缺省 → DEFAULTS（对齐 CC DEFAULT_CRON_JITTER_CONFIG）。 */
    public CronCreateTool(ScheduleService scheduleService,
                          CronEnabledGates cronGates) {
        this(scheduleService, cronGates, CronJitterProperties.DEFAULTS);
    }

    @Autowired
    public CronCreateTool(ScheduleService scheduleService,
                          CronEnabledGates cronGates,
                          CronJitterProperties jitterProps) {
        this.scheduleService = scheduleService;
        this.cronGates = cronGates;
        this.jitterProps = jitterProps != null ? jitterProps : CronJitterProperties.DEFAULTS;
    }

    @Override
    public String name() {
        return "CronCreate";
    }

    @Override
    public String description() {
        // [G23②] 对齐 CC CronCreateTool.ts:73-75 description() = buildCronCreateDescription
        //   （prompt.ts:68-72）+ 介质措辞中性化（.claude/scheduled_tasks.json → the scheduled task store）
        return buildCronCreateDescription(isDurableCronEnabled());
    }

    /**
     * [G23②] 工具提示词 · 对齐 CC CronCreateTool.ts:76-78 prompt() = buildCronCreatePrompt
     * （prompt.ts:74-121）逐字移植（jitter 规避 / durable 边界 / 7 天过期全保留），介质措辞中性化：
     * <ul>
     *   <li>{@code .claude/scheduled_tasks.json} → {@code the scheduled task store}（对齐 renderResult
     *       既有 CRON-B4-3 决策 #12 中性化先例）</li>
     *   <li>{@code REPL} → {@code the session}（Java 无 REPL 介质，web 会话等价）</li>
     *   <li>{@code this Claude session} → {@code this session}</li>
     * </ul>
     *
     * <p><b>字段数契约统一（2026-08-29）</b>: Java 决策#5 契约文本为 <b>6 字段</b>（含前导秒，
     * 对齐 Quartz 6 字段存储），inputSchema 描述 / prompt() 指导 / validateInput errorCode 1
     * 全链统一 6 字段（CC 原文为 5 字段 "Uses standard 5-field cron"，prompt.ts:89，被决策#5
     * 覆写为 Java 端 6 字段措辞 + '?' 占位说明）。Java CronExpressionConverter 兼容 5 段
     * （toQuartz6Field 转换）→ 用户输入 5 段 cron 亦可用，但工具说明按 6 字段契约指导。
     */
    @Override
    public String prompt() {
        return buildCronCreatePrompt(isDurableCronEnabled());
    }

    /** durable 门 · CC original: isDurableCronEnabled (prompt.ts:56-62)；null→fail-open（决策#11）。 */
    private boolean isDurableCronEnabled() {
        return cronGates == null || cronGates.isDurableCronEnabled();
    }

    /**
     * 动态描述 · 对齐 CC prompt.ts:68-72 {@code buildCronCreateDescription(durableEnabled)}。
     */
    private static String buildCronCreateDescription(boolean durableEnabled) {
        return durableEnabled
            ? "Schedule a prompt to run at a future time — either recurring on a cron schedule, or once at a specific time. "
                + "Pass durable: true to persist to the scheduled task store; otherwise session-only."
            : "Schedule a prompt to run at a future time within this session — either recurring on a cron schedule, or once at a specific time.";
    }

    /**
     * 动态提示词 · 对齐 CC prompt.ts:74-121 {@code buildCronCreatePrompt(durableEnabled)}
     * 逐字移植（介质措辞中性化后）。
     */
    private static String buildCronCreatePrompt(boolean durableEnabled) {
        String durabilitySection = durableEnabled
            ? "## Durability\n"
                + "\n"
                + "By default (durable: false) the job lives only in this session — nothing is written to disk, "
                + "and the job is gone when the session exits. Pass durable: true to write to the scheduled task "
                + "store so the job survives restarts. Only use durable: true when the user explicitly asks for "
                + "the task to persist (\"keep doing this every day\", \"set this up permanently\"). Most \"remind "
                + "me in 5 minutes\" / \"check back in an hour\" requests should stay session-only."
            : "## Session-only\n"
                + "\n"
                + "Jobs live only in this session — nothing is written to disk, and the job is gone when the session exits.";
        String durableRuntimeNote = durableEnabled
            ? "Durable jobs persist to the scheduled task store and survive session restarts — on next launch they "
                + "resume automatically. One-shot durable tasks that were missed while the session was closed are "
                + "surfaced for catch-up. Session-only jobs die with the process. "
            : "";
        return "Schedule a prompt to be enqueued at a future time. Use for both recurring schedules and one-shot reminders.\n"
            + "\n"
            + "Uses standard 6-field cron in the user's local timezone: second minute hour day-of-month month day-of-week. "
            + "Use '?' for the unused day-of-month or day-of-week field (they are mutually exclusive). "
            + "\"0 0 9 * * *\" means 9am local — no timezone conversion needed.\n"
            + "\n"
            + "## One-shot tasks (recurring: false)\n"
            + "\n"
            + "For \"remind me at X\" or \"at <time>, do Y\" requests — fire once then auto-delete.\n"
            + "Pin minute/hour/day-of-month/month to specific values:\n"
            + "  \"remind me at 2:30pm today to check the deploy\" → cron: \"0 30 14 <today_dom> <today_month> ?\", recurring: false\n"
            + "  \"tomorrow morning, run the smoke test\" → cron: \"0 57 8 <tomorrow_dom> <tomorrow_month> ?\", recurring: false\n"
            + "\n"
            + "## Recurring jobs (recurring: true, the default)\n"
            + "\n"
            + "For \"every N minutes\" / \"every hour\" / \"weekdays at 9am\" requests:\n"
            + "  \"0 */5 * * * *\" (every 5 min), \"0 0 * * * *\" (hourly), \"0 0 9 * * 1-5\" (weekdays at 9am local)\n"
            + "\n"
            + "## Avoid the :00 and :30 minute marks when the task allows it\n"
            + "\n"
            + "Every user who asks for \"9am\" gets `0 9`, and every user who asks for \"hourly\" gets `0 *` — "
            + "which means requests from across the planet land on the API at the same instant. When the user's "
            + "request is approximate, pick a minute that is NOT 0 or 30:\n"
            + "  \"every morning around 9\" → \"0 57 8 * * *\" or \"0 3 9 * * *\" (not \"0 0 9 * * *\")\n"
            + "  \"hourly\" → \"0 7 * * * *\" (not \"0 0 * * * *\")\n"
            + "  \"in an hour or so, remind me to...\" → pick whatever minute you land on, don't round\n"
            + "\n"
            + "Only use minute 0 or 30 when the user names that exact time and clearly means it (\"at 9:00 sharp\", "
            + "\"at half past\", coordinating with a meeting). When in doubt, nudge a few minutes early or late — "
            + "the user will not notice, and the fleet will.\n"
            + "\n"
            + durabilitySection
            + "\n"
            + "## Runtime behavior\n"
            + "\n"
            + "Jobs only fire while the session is idle (not mid-query). " + durableRuntimeNote
            + "The scheduler adds a small deterministic jitter on top of whatever you pick: recurring tasks fire "
            + "up to 10% of their period late (max 15 min); one-shot tasks landing on :00 or :30 fire up to 90 s "
            + "early. Picking an off-minute is still the bigger lever.\n"
            + "\n"
            + "Recurring tasks auto-expire after " + DEFAULT_MAX_AGE_DAYS + " days — they fire one final time, "
            + "then are deleted. This bounds session lifetime. Tell the user about the " + DEFAULT_MAX_AGE_DAYS
            + "-day limit when scheduling recurring jobs.\n"
            + "\n"
            + "Returns a job ID you can pass to CronDelete.";
    }

    /**
     * 搜索提示 · 对齐 CC CronCreateTool.ts:58 searchHint = 'schedule a recurring or one-shot prompt'。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378）。
     */
    @Override
    public String searchHint() {
        return "schedule a recurring or one-shot prompt";
    }

    /**
     * 结果落盘阈值 · 对齐 CC CronCreateTool.ts:59 maxResultSizeChars = 100_000
     * （覆盖 Tool 接口默认 50000）。
     */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /**
     * 延迟执行 · 对齐 CC CronCreateTool.ts:60 shouldDefer = true。
     * Cron 触发类工具不阻塞主循环（延迟到当前 turn 后执行）。
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * 无条件加载 · 对齐 CC prompt.ts:65 alwaysLoad 豁免——cron 工具常驻初始 tools 数组
     * （模型直接可见，无需 ToolSearch 发现），对齐 CC tools.ts:31-37 cronTools 无条件注册语义。
     * 用户拍板 2026-08-29：Java cron 无条件加载而非懒加载。
     */
    @Override
    public boolean alwaysLoad() {
        return true;
    }

    /**
     * 运行时启用 · 对齐 CC CronCreateTool.ts:67-69 isEnabled() = isKairosCronEnabled()。
     * 委托 {@link CronEnabledGates}（agent-trigger-cron && !CLAUDE_CODE_DISABLE_CRON truthy）。
     *
     * <p><b>决策#11（CRON-F2 null 语义）</b>: 门控 null 统一 <b>fail-open</b>（null→开/放行）。
     * CC 布尔链无 null 态（isKairosCronEnabled 恒返回 boolean，GB 默认 true）；Java 侧
     * {@code cronGates == null} 视为门开——与 TestJob.java:99 / CronIdleExecutor.java:131
     * 已 fail-open 的消费方三层统一。生产 @ConfigurationProperties + @DefaultValue 恒注入
     * 非 null，此分支仅防护直接 new 构造/测试路径。
     */
    @Override
    public boolean isEnabled() {
        return cronGates == null || cronGates.isKairosCronEnabled();
    }

    /**
     * 自动分类器输入 · 对齐 CC CronCreateTool.ts:70-72
     * {@code toAutoClassifierInput(input) { return `${input.cron}: ${input.prompt}` }}。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        return input.path("cron").asText("") + ": " + input.path("prompt").asText("");
    }

    /**
     * validateInput 语义校验 · 对齐 CC {@code CronCreateTool.ts:82-116}（顺序 1→2→3→4 固定）.
     *
     * <p>四错误码（errorCode 字符串化 "1".."4"，注入 LLM 可观测）:
     * <ol>
     *   <li><b>1</b> 非法 cron：errorCode1 闸门失败（CC :83-89 parseCronExpression；B5 全 6 字段
     *       委托 Quartz——5 段经 toQuartz6Field 兼容、6 段透传，终值以
     *       {@code CronExpression.isValidExpression} 校验，见下方法体；IMPL-03 追加 '?' 占位
     *       说明——dom/dow 互斥：一个字段用具体值/部分通配时，另一个须用 '?' 占位，✗-G）</li>
     *   <li><b>2</b> 一年内无匹配日期（CC :90-96；IMPL-03/NEW-1 自建 366 天上限——
     *       {@link CronExpressionConverter#hasMatchWithinYear}，CC cron.ts:138
     *       {@code maxIter = 366 * 24 * 60} 分钟等价）</li>
     *   <li><b>3</b> 任务数超 {@link ScheduleService#MAX_JOBS}（CC :97-104）</li>
     *   <li><b>4</b> durable + teammate 冲突（CC :105-114，teammates 不跨 session 持久）</li>
     * </ol>
     *
     * <p>errorCode2-4 消息文本逐字对齐 CC（含 errorCode 字符串）；errorCode1 消息按决策#5 为
     * 6 字段契约文本（'Expected 6 fields: S M H DoM Mon DoW.'，CC CronCreateTool.ts:86 为
     * 5 字段，被决策#5 覆写为 Java 端判断措辞），IMPL-03 追加 '?' 占位说明句（追加式，
     * 决策#5「只补占位提示不逐字改文本」）。错误码 4 的 teammate predicate 用
     * {@link TeammateContext#getTeammateContext()}（对齐 CC 真源 :107 + CronDeleteTool:195 既有
     * 约定；Java 当前无 teammate agentId 字段，WF-B/D 补 → 登记 WF-A-OD-11）。
     *
     * @param input 工具输入 {@code {cron, prompt, recurring?, durable?}}
     * @param ctx   工具调用上下文（本实现不消费；teammate 判定用全局 TeammateContext，对齐 CC :107）
     * @return {@link ValidationResult#pass()} 或 fail("1".."4", CC 精确消息)
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String cron = input == null ? null : input.path("cron").asText(null);
        String cronDisp = cron == null ? "" : cron;
        // errorCode 1 · CC :83-89 parseCronExpression(input.cron) 失败
        //   A4 拍板（B5 全 6 字段，open-decisions.md:156）：errorCode1 闸门全量委托 Quartz 6 字段——
        //   toQuartzCronVariants 将 5 段经 toQuartz6Field 兼容（OPD-Cron-T1-01「写工具兼容 5 段并
        //   也支持 6 段」）/ 6 段透传校验 / dom-dow 双约束拆 2 变体（OR），任一变体须 Quartz
        //   CronExpression.isValidExpression 合法（B5 较 B1 更严：秒/分 61-99 越界亦拒）。
        //   消息契约文本 6 字段（'Expected 6 fields: S M H DoM Mon DoW.'，CC :86 为 5 字段被覆写）
        //   + IMPL-03 追加 '?' 占位说明（dom/dow 互斥：具体 dom 时 dow 用 '?'，反之亦然，✗-G）。
        List<String> gateVariants = CronExpressionConverter.toQuartzCronVariants(cron);
        boolean cronValid = gateVariants != null && !gateVariants.isEmpty()
            && gateVariants.stream().allMatch(CronExpression::isValidExpression);
        if (!cronValid) {
            if (log.isDebugEnabled()) {
                log.debug("CronCreateTool.validateInput: errorCode1 非法 cron，cron=[{}] 变体=[{}] "
                        + "（5 段/6 段任一合法即通过，委托 Quartz 6 字段校验）", cron, gateVariants);
            }
            return ValidationResult.fail("1",
                "Invalid cron expression '" + cronDisp + "'. Expected 6 fields: S M H DoM Mon DoW. "
                + "Use '?' for the unused day-of-month or day-of-week field.");
        }
        // errorCode 2 · CC :90-96 nextCronRunMs === null（一年内无匹配）
        //   IMPL-03（NEW-1 已定方向）: CC cron.ts:138 maxIter=366*24*60 分钟超限即 null → errorCode2；
        //   Quartz getNextValidTimeAfter 无此上限（Feb-29 类稀疏 cron 返回任意未来匹配），
        //   故以 hasMatchWithinYear（next 为 null 或超出 from+366 天，CC_MAX_LOOKAHEAD_MS 等价位）
        //   判定「一年内无匹配」。
        long nowMs = System.currentTimeMillis();
        if (!CronExpressionConverter.hasMatchWithinYear(cron, nowMs)) {
            if (log.isDebugEnabled()) {
                Long next = CronExpressionConverter.nextCronRunMs(cron, nowMs);
                log.debug("CronCreateTool.validateInput: errorCode2 一年内无匹配，cron=[{}] next={} "
                        + "（366 天边界 CC_MAX_LOOKAHEAD_MS={}，next 为 null 或超出即拒）",
                    cron, next, CronExpressionConverter.CC_MAX_LOOKAHEAD_MS);
            }
            return ValidationResult.fail("2",
                "Cron expression '" + cronDisp + "' does not match any calendar date in the next year.");
        }
        // errorCode 3 · CC :97-104 listAllCronTasks().length >= MAX_JOBS
        if (scheduleService.listAll().size() >= ScheduleService.MAX_JOBS) {
            return ValidationResult.fail("3",
                "Too many scheduled jobs (max " + ScheduleService.MAX_JOBS + "). Cancel one first.");
        }
        // errorCode 4 · CC :105-114 input.durable && getTeammateContext()
        boolean durable = input != null && input.path("durable").asBoolean(false);
        if (durable && TeammateContext.getTeammateContext() != null) {
            return ValidationResult.fail("4",
                "durable crons are not supported for teammates (teammates do not persist across sessions)");
        }
        return ValidationResult.pass();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        // CC CronCreateTool.ts:28 z.strictObject → additionalProperties:false（拒绝多余字段）
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.set("cron", JSON.createObjectNode()
            .put("type", "string")
            // 决策#5: LLM 契约文本 6 字段（含前导秒 + DoW '?' 占位），与 DB/Quartz 6 字段存储一致。
            // CC CronCreateTool.ts:31-32 为 5 字段描述（'M H DoM Mon DoW'），被决策#5 覆写。
            .put("description",
                "标准 6 字段 cron 表达式（按本地时间）：\"S M H DoM Mon DoW\" "
                + "（前导为秒字段；day-of-month 与 day-of-week 互斥：一个字段用具体值/部分"
                + "通配时，另一个须用 \"?\" 占位），"
                + "例如 \"0 */5 * * * *\" = 每 5 分钟，"
                + "\"0 30 14 28 2 ?\" = 本地时间 2 月 28 日 14:30 执行一次。"));
        props.set("prompt", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "每次触发时入队的 prompt。"));
        props.set("recurring", JSON.createObjectNode()
            .put("type", "boolean")
            .put("description",
                "true（默认）= 每次 cron 匹配时都触发。false = 仅在下一次匹配时触发一次，"
                + "随后自动删除。"));
        props.set("durable", JSON.createObjectNode()
            .put("type", "boolean")
            .put("description",
                "true = 跨会话持久化。false（默认）= 仅当前会话有效。"));
        List.of("cron", "prompt").forEach(r -> schema.putArray("required").add(r));
        return schema;
    }

    /**
     * 输出 schema · 对齐 CC {@code CronCreateTool.ts:45-52}
     * {@code outputSchema = z.object({ id: z.string(), humanSchedule: z.string(),
     * recurring: z.boolean(), durable: z.boolean().optional() })}。
     *
     * <p>成功路径 CC call 返回 {@code {data: {id, humanSchedule, recurring, durable}}}
     * （CronCreateTool.ts:134-141，durable 为 effectiveDurable），本 schema 描述该输出结构供
     * 输出验证/文档生成使用；tool_result 人类文本由 {@code mapToolResultToToolResultBlockParam}
     * （CronCreateTool.ts:143-153）渲染，与 schema 并存不冲突（同 CronDeleteTool.outputSchema
     * 文档化约定）。recurring/durable 恒返回布尔（effectiveDurable 收敛），但 durable 在 schema
     * 声明为 optional（CC :50 z.boolean().optional()），required 仅含 id/humanSchedule/recurring。
     *
     * @return JSON Schema {@code {type: object, properties: {id: {type: string},
     *         humanSchedule: {type: string}, recurring: {type: boolean}, durable: {type: boolean}},
     *         required: [id, humanSchedule, recurring]}}
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("humanSchedule").put("type", "string");
        props.putObject("recurring").put("type", "boolean");
        props.putObject("durable").put("type", "boolean");
        // CC :47-50 id/humanSchedule/recurring 为 z.string()/z.boolean()（必填）；durable 为
        // z.boolean().optional()（可选）→ required 仅含前 3 项，与 CronListTool.java:178-182 同构。
        List.of("id", "humanSchedule", "recurring").forEach(r -> schema.putArray("required").add(r));
        return schema;
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String cron = input.path("cron").asText(null);
        String prompt = input.path("prompt").asText(null);

        if (cron == null || cron.isBlank()) {
            return ToolResult.error(call.id(), "CronCreateTool: 'cron' field is required");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error(call.id(), "CronCreateTool: 'prompt' field is required");
        }

        // 1. 验证 cron 表达式并生成 Quartz 变体 (决策#5 LLM 契约文本 6-field → Quartz 6-field;
        //    CC 5-field 语义兼容保留; 6 段 strip 解析已由 CRON-B1-2 落 CronExpressionConverter)
        //    CronExpressionConverter.toQuartzCronVariants 承担 5→6 段转换 (CRON-A2a,
        //    对齐 cron.ts:83-101 解析 + OPD-Cron-T1-01/05 5→6 段转换; DoW 7→0 别名 + dom/dow
        //    Quartz "?" 互斥规则由转换器承担). 双约束 (dom 与 dow 均具体) → 2 变体
        //    (dom-only + dow-only, 并集 = CC cron.ts:151-158 OR 语义), 其余 → 单变体.
        List<String> cronVariants = CronExpressionConverter.toQuartzCronVariants(cron);
        if (cronVariants == null || cronVariants.isEmpty()) {
            return ToolResult.error(call.id(),
                "CronCreateTool: invalid cron expression '" + cron + "'. "
                + "Expected 6 fields: S M H DoM Mon DoW. "
                + "Use '?' for the unused day-of-month or day-of-week field.");
        }

        // 2. 字段映射 (CC → Java)
        boolean recurring = input.path("recurring").asBoolean(true);    // CC default true
        boolean durable = input.path("durable").asBoolean(false);       // CC default false
        // CC :120 effectiveDurable = durable && isDurableCronEnabled() — kill-switch 强制 session-only
        //   (门 flips mid-session 时 schema 稳定, 输出 durable 变 false, 对齐 CC :118-120 注释)
        //   决策#11: cronGates null→开（fail-open），对齐 CC isDurableCronEnabled 默认 true
        //   + CronEnabledGates.DEFAULTS=(true,true) + TestJob/CronIdleExecutor 已 fail-open。
        boolean effectiveDurable = durable
            && (cronGates == null || cronGates.isDurableCronEnabled());
        if (log.isDebugEnabled() && cronGates == null) {
            log.debug("CronCreateTool: cronGates=null 走 fail-open（决策#11 门控 null→开），"
                    + "isKairosCronEnabled/isDurableCronEnabled 均视为 true，对齐 CC 无 null 态布尔链");
        }

        ScheduleKind kind;
        String runAt;
        String cronForSchedule;
        // CRON-F1: one-shot 路径预生成 schedule id，供 jitterFrac(taskId) 与落库 id 一致
        // （recurring 保持 null → ScheduleService 服务端 generateId，行为不变）。
        String scheduleId = null;
        if (recurring) {
            kind = ScheduleKind.cron;
            runAt = null;
            // CRON-B2-1: recurring 首触发抖动已接线——QuartzScheduleService.buildTrigger 的 cron
            // 分支追加 kickstart SimpleTrigger（startAt=jitteredNextCronRunMs cronTasks.ts:381-398，
            // 决策 #1），taskId 取落库 id 后 8 hex。此处仅 join 变体串。
            // 双约束 → join 变体串 (VARIANT_SEPARATOR "||" 连接), 由
            // QuartzScheduleService.splitVariants 拆回逐变体注册多 CronTrigger
            // (对齐 CC cron.ts:151-158 dom/dow 任一匹配即触发 = OR)
            cronForSchedule = CronExpressionConverter.joinVariants(cronVariants);
            if (log.isDebugEnabled()) {
                log.debug("CronCreateTool: recurring cron='{}' → {} 变体 cronForSchedule='{}' "
                        + "(OPD-Cron-T1-05 双约束 OR)", cron, cronVariants.size(), cronForSchedule);
            }
        } else {
            // 一次性任务: 用 cron 算出下一次 fire 时间 → ISO 8601 runAt
            //    CRON-F1: 对齐 CC CronCreateTool.ts:90/117-127 + cronTasks.ts:421-445
            //    oneShotJitteredNextCronRunMs —— one-shot 整点(:00/:30，oneShotMinuteMod)提前
            //    lead 秒摊开推理尖峰，任务创建于提前窗内钳到 fromMs（cronTasks.ts:442-444）。
            //    taskId = schedule id 后 8 hex（cronTasks.ts:358-365）。
            kind = ScheduleKind.once;
            scheduleId = "sch-" + UUID.randomUUID().toString().substring(0, 8);
            String taskId = scheduleId.substring(scheduleId.length() - 8);
            Long nextFireMs = CronJitter.oneShotJitteredNextCronRunMs(
                cron, System.currentTimeMillis(), taskId, jitterProps.toConfig());
            if (nextFireMs == null) {
                return ToolResult.error(call.id(),
                    "CronCreateTool: cron '" + cron + "' does not match any calendar date "
                    + "in the next year.");
            }
            runAt = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nextFireMs), ZoneId.systemDefault()).toString();
            // IMP-F2（组 5-3 / DEL-F2-02）：once 存储模型改存 cron —— cron 列落库为用户原始 cron，
            // 与 recurring 同满足 CC "once 任务亦存 cron、无 runAt null 态"（CronListTool.ts:73
            // humanSchedule=cronToHuman(t.cron)）。Quartz 触发仍以 runAt（SimpleTrigger）为准
            // （QuartzScheduleService.buildTrigger kind=once 只读 runAt），cron 列仅承载
            // humanSchedule 显示 + missed 检测（CronExpressionConverter.nextCronRunMs）。CronList 的
            // formatRunAt 独有适配随之删除（对齐 CC）。
            cronForSchedule = cron;
        }

        ScheduleScope scope = effectiveDurable ? ScheduleScope.DURABLE : ScheduleScope.SESSION;
        String sessionId;
        // CC original: CronTask.agentId (CronCreateTool.ts:126 getTeammateContext()?.agentId)。
        // teammate 创建者 agentId 仅在 SESSION（durable=false）路径有值；主线程恒 null。
        // 复用 CronDeleteTool.java:195-199 既有取值模式。TeammateContext 生产端 0 设定
        // （@deprecated stub）→ 生产当前恒 null，待 teammate 运行时接线后非空（OPD-D4-GAP-5 方案 A 边界）。
        String agentId = null;
        // 批次X Q2: DURABLE 任务存 boundProject（创建会话绑定项目）· CC original: 无字段
        // （CC durable 项目锚=文件位置 <projectRoot>/.claude/scheduled_tasks.json，cronTasks.ts:74-83）。
        // 取值 = SessionProjectRoot.getForSession(ctx.sessionId()) —— 创建会话的
        // 绑定项目（对齐 CC STATE.projectRoot 启动/绑定目录 state.ts:511-513/523-525；
        // 中途 cd 不重锚），NOT sessionCwd（sessionCwd 会随会话内 cd 漂移，偏离 CC 启动锚语义）。
        // 无会话上下文（ctx 缺失 / ctx.sessionId()==null，REST 直建 / 测试无 ctx）→ null
        // （fire 兜底 user.dir，已知差异：CC 所有 durable 任务都在会话里创建）。
        String boundProject = null;
        if (scope == ScheduleScope.SESSION) {
            // SESSION scope 必须有 sessionId (CC durable=false 注入 ctx.sessionId)
            if (ctx == null || ctx.sessionId() == null) {
                return ToolResult.error(call.id(),
                    "CronCreateTool: scope=SESSION (durable=false) requires ctx.sessionId; "
                    + "no ToolUseContext provided.");
            }
            sessionId = ctx.sessionId();
            TeammateContext teammate = TeammateContext.getTeammateContext();
            if (teammate != null && teammate.getData().agentId() != null) {
                agentId = teammate.getData().agentId();
            }
        } else {
            // DURABLE: 存创建会话 sessionId（归属对话/注入目标，非 SESSION 生命周期绑定）· [PROBE-DUR
            // 修订 2026-08-22] Java 近似（CRON-D5 单用户）：fire 归创建会话；CC 实际注入挂载 scheduler
            // 的活跃会话（useScheduledTasks.ts:71-82 enqueueForLead 注入挂载会话队列，fire 不带 sessionId）。
            // 主场景（创建会话存活且唯一）二者一致；边角分歧（创建会话已关 → Java headless vs CC 归新
            // 会话）已登记 open decision。创建会话存活时 transcript 归创建会话文件（CronIdleExecutor
            // 创建会话存活判定后 RunRequest 用创建会话 UUID，经 SessionStorage 纯 sessionId 解析自然命中）；
            // 创建会话已关 → fire 照常执行（headless）但不产生会话 transcript。CC durable 落盘 shape
            // 无 sessionId（cronTasks.ts:175/190-218 写盘仅 {id,cron,prompt,createdAt,lastFiredAt?,recurring?,
            // permanent?}），但 Java 多会话 web 服务队列跨线程边界必须显式携带创建会话
            // （对齐 CRON-D5 会话归组）；无会话（REST 直建 / 无 ctx）→ null → fire headless 无 transcript。
            sessionId = null;
            if (ctx != null && ctx.sessionId() != null) {
                // [session-id-short] DURABLE cron 落库 short 直键，与 HTTP 路径 ScheduleService.create
                // 形态统一（消除 F2 双形态根因之一；存量 DB 行读取侧 originalKey 兜底见 SessionKeys）。
                sessionId = ctx.sessionId();
                // 项目锚显式落 boundProject 列（V23）。[session-id-short] SessionProjectRoot（boundProject 层）
                // 以 short 直键为键，ctx.sessionId() 已 short → 直传查询（原 originalKey 反解派生 UUID 已删）。
                boundProject = SessionProjectRoot.getForSession(ctx.sessionId());
                if (boundProject == null && log.isDebugEnabled()) {
                    log.debug("CronCreateTool: DURABLE 创建会话 {} 无绑定项目，boundProject=null "
                            + "（fire 兜底 user.dir，对齐 CC durable 项目锚=文件位置的已知差异）",
                        ctx.sessionId());
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("CronCreateTool: scope={} sessionId={} agentId={} boundProject={} "
                    + "(对齐 CC cronTasks.ts:212 addSessionCronTask 条件透传 agentId；批次X Q2 "
                    + "DURABLE 项目锚=创建会话绑定项目；DURABLE sessionId=创建会话归属对话，"
                    + "fire 存活时 transcript 归创建会话)", scope, sessionId, agentId, boundProject);
        }

        // 3. 构造 ScheduleCreateRequest (name 由 cron 派生; IMPL-06/NEW-5: CC 无 name 字段
        //    —— CronTask 类型仅 {id,cron,prompt,createdAt,...} (cronTasks.ts:30-70),
        //    addCronTask 无去重 (cronTasks.ts:194-219) → 同 cron 二次创建两条均 fire;
        //    V20 已去 schedules.name UNIQUE 约束, name 退化为纯展示字段。展示碰撞 →
        //    scheduleService.nextAvailableName 追加 -N 递增后缀 (截断逻辑移入该方法,
        //    恒 ≤64 对齐 ScheduleCreateRequest @Size(max=64) 契约; 旧 name.substring(0,64) 已删))
        String baseName = (recurring ? "cron:" : "once:") + cron;
        String name = scheduleService.nextAvailableName(baseName);
        if (log.isDebugEnabled() && !name.equals(baseName)) {
            log.debug("CronCreateTool: 派生名 '{}' 展示碰撞，使用递增后缀 '{}'（NEW-5：同 cron "
                    + "二次创建均 fire，对齐 CC addCronTask 无 name/无去重 cronTasks.ts:194-219）",
                baseName, name);
        }
        ScheduleCreateRequest req = new ScheduleCreateRequest(
            name, kind,
            // kind=cron: Quartz 变体 join 串(单约束=1 个 6 段, 双约束=|| 分隔 2 段);
            // kind=once: IMP-F2 改存原始 cron（DEL-F2-02，CronList humanSchedule 恒 cronToHuman）
            cronForSchedule,
            null,
            runAt,
            prompt,                    // prompt → command
            prompt,                    // description 同 prompt
            scope,
            sessionId,
            agentId,
            boundProject,              // 批次X Q2: DURABLE 创建会话绑定项目（SESSION 恒 null）
            scheduleId                 // CRON-F1: one-shot 预生成 id（jitter taskId 依赖）；recurring=null
        );

        // 4. 委托 ScheduleService.create (DB + Quartz + sessionJobs)
        //    CRON-F1: 预生成 scheduleId 经 req.id() 传入（ScheduleService.create 沿用），保证 jitter
        //    taskId 与落库 id 一致；recurring/正常路径 req.id()=null → 服务端 generateId。
        // CAND-3: 无 catch —— 异常直接抛框架层 StreamingToolExecutor 统一错误面
        // （对齐 CC CronCreateTool.ts:117-142 call() 无 try/catch；E1 原则 CronDeleteTool.java:251-252）。
        ScheduleDto dto = scheduleService.create(req);
        log.info("CronCreateTool: created job id={} kind={} scope={} recurring={} effectiveDurable={} agentId={}",
            dto.id(), kind, scope, recurring, effectiveDurable, dto.agentId());
        String humanSchedule = CronToHuman.cronToHuman(cron);
        String text = renderResult(dto, humanSchedule, recurring, effectiveDurable);
        return ToolResult.success(call.id(), text);
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("cron_create_allow"),
            null, false, null, List.of());
    }

    /** 渲染 tool_result 人类文本 · 对齐 CC CronCreateTool.ts:143-153 mapToolResultToToolResultBlockParam.
     *  (C15: kind/scope/cron/sessionId extra 字段已删除; CRON-A2b: durable 参数为 effectiveDurable,
     *  where 子句随 effectiveDurable 分流 Persisted/Session-only).
     *  CRON-B4-3 决策 #12 次级项 (OPD-EL-01)：CC :145 写死 ".claude/scheduled_tasks.json" 介质
     *  泄漏，与 missed 通知同类缺陷，一并覆写为中性 "the scheduled task store"。 */
    private static String renderResult(ScheduleDto dto, String humanSchedule,
                                       boolean recurring, boolean effectiveDurable) {
        String where = effectiveDurable
            ? "Persisted to the scheduled task store"
            : "Session-only (not written to disk, dies when Claude exits)";
        if (recurring) {
            return "Scheduled recurring job " + dto.id() + " (" + humanSchedule + "). " + where
                + ". Auto-expires after " + DEFAULT_MAX_AGE_DAYS
                + " days. Use CronDelete to cancel sooner.";
        }
        return "Scheduled one-shot task " + dto.id() + " (" + humanSchedule + "). " + where
            + ". It will fire once then auto-delete.";
    }
}