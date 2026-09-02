package com.nexusai.domain.schedule;

import com.nexusai.model.schedule.dto.RunNowResponse;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.ScheduleUpdateRequest;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.infra.exception.MaxJobsExceededException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.agent.tool.cron.CronExpressionConverter;
import com.nexusai.application.agent.tool.cron.CronJitter;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.infra.schedule.TestJob;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Schedule 业务逻辑（C4：Quartz 集成版）
 *
 * <p>CRUD + Quartz 同步：
 * <ul>
 *   <li>create：DB 插行 → registerSchedule；Quartz 失败则回滚 DB</li>
 *   <li>delete：先 unregisterSchedule（不存在也 ok），再删 DB，并同步 sessionJobs 索引</li>
 *   <li>run：once 同步 fire-then-delete 返回 200+结果体（决策 #15 / OPD-EL-04，对齐工具路径
 *       fire-then-delete 语义 CronCreateTool.ts:152）；recurring triggerNow 异步触发，deleted=false</li>
 *   <li>markFired：fire 后批量回写 lastRunAt（对齐 CC markCronTasksFired，CRON-F4）</li>
 * </ul>
 * <p>注：update() 因 FIX-3 / RV-C-03 G3/G4 重新引入 —— CC cronTasks.ts 无 update 工具
 * （C07 裁定：cronTasks.ts 无 update 工具，属 cron 域删除候选），但 RemoteTriggerTool 属 remote-trigger 域
 * （RemoteTriggerTool.ts:120-126 update=POST base/{trigger_id}），Java 把两者塌到同一
 * ScheduleController，故补 POST /{id} 端点需重开 partial-update 能力（对齐 remote-trigger 域）。</p>
 *
 * <p>{@code command} 字段：v1 默认 "test"，存进 DB 但不再被实际消费（所有 schedule 都
 * 路由到 {@code TestJob}）。v2 接入真实执行器后再按 command 派发。
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    /** s14-P1-4: 强制 MAX_JOBS 上限 · 对齐 CC CronCreateTool.ts:25 MAX_JOBS=50 */
    public static final int MAX_JOBS = 50;

    /**
     * s14-P1-5: session-scoped 任务索引 · 对齐 CC cronScheduler.ts:246-247
     * (session tasks 走 removeSessionCronTasks 同步内存清理).
     *
     * <p>sessionId → 该 session 创建的 SESSION-scope schedule id 列表.
     * DURABLE-scope 任务不入此映射 (CC 同理: file tasks 走 disk + chokidar).
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> sessionJobs =
        new ConcurrentHashMap<>();

    /**
     * CRON-B4: 已表面（surfaced）的 missed 任务 id 集合 · CC original: missedAsked
     * (Open-ClaudeCode/src/utils/cronScheduler.ts:167 {@code const missedAsked = new Set<string>()}).
     *
     * <p>与 CC 模块级闭包语义一致：load(initial) 每次启动扫描时过滤已问过的 id
     * （cronScheduler.ts:196 {@code !missedAsked.has(t.id)}），命中后 add（:200）。
     * Java 侧放单例实例字段，跨调用共享；WF-D 接线需确认启动时新建/重置语义。
     */
    private final Set<String> missedAsked = ConcurrentHashMap.newKeySet();

    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private QuartzScheduleService quartzScheduleService;
    /**
     * CRON-B2-2: one-shot jitter 配置源（application.yml nexusai.cron.jitter.*）· 决策 #2
     * （OPD-Cron-F1-b）。非 Spring 单测注入失败（null）→ {@link #resolveJitteredRunAt} fail-open
     * 到 {@link CronJitterProperties#DEFAULTS}（对齐 QuartzScheduleService:183-185 同一模式）。
     */
    @Autowired private CronJitterProperties jitterProps;
    /**
     * CRON-B4-4（决策 #15 / OPD-EL-04）：once 任务同步 fire 入口 · 经 TestJob 共享 fire body
     * （gate→getById→route/enqueue→applyFireLifecycle）内联执行，对齐工具路径 fire-then-delete
     * 语义（CronCreateTool.ts:152 "fire once then auto-delete"）。
     *
     * <p>循环依赖：TestJob {@code @Autowired} ScheduleService，本字段用 {@code @Lazy} +
     * {@code required=false} 破环（延迟到 runNow once 路径首次调用才实例化真实 bean）。
     * 非 Spring 单测不注入（null）→ once 路径 fail-loud log.error + executed=false。
     */
    @Lazy @Autowired(required = false)
    private TestJob testJob;

    /**
     * IMPL-10 (NEW-12): P2 遥测 · 对齐 CC logEvent (cronScheduler.ts:205-212 missed /
     * :288-292 fire / :308-312 expired)。无 bean（非 Spring 单测手动 new）→ null →
     * 静默跳过，不影响删除/通知/返回语义。
     */
    @Autowired(required = false)
    private Telemetry telemetry;

    public List<ScheduleDto> listAll() {
        List<ScheduleRecord> all = scheduleMapper.selectAll();
        // F5 修复 (follow-up.md F5): 反查 sessionJobs 以补充 scope/sessionId (对齐 getById line 95-100).
        // 原实现 hardcode ScheduleScope.DURABLE + null sessionId, 导致 SESSION-scope 任务
        // 在 listAll() 结果里被错误标为 DURABLE, CronListTool ctx 过滤逻辑 (line 108)
        // 看不到 SESSION-scope 任务.
        // CRON-B2: 优先读 DB scope/session_id 列（重启后可辨识，R-1 僵尸修复），
        // sessionJobs 仅兜底 scope 列为 NULL 的存量旧行
        return all.stream().map(r -> toDto(r, lookupScope(r), lookupSessionId(r)))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * IMPL-06/NEW-5: 派生展示名碰撞处理 —— base 已被占用时追加 -N 递增后缀。
     *
     * <p>对齐 CC：CronTask 无 name 字段（cronTasks.ts:30-70 类型仅
     * {id,cron,prompt,createdAt,lastFiredAt?,recurring?,permanent?,durable?,agentId?}），
     * addCronTask 无任何 name 写入/去重（cronTasks.ts:194-219）→ 同 cron 二次创建
     * 两条均 fire，任务身份语义 = id。V20 已去除 schedules.name UNIQUE 约束
     * （SQLite 表重建，DEL-UNIQUE），name 退化为 Java REST 契约展示字段
     * （ScheduleCreateRequest {@code @Size(max=64)} 必填，无唯一性）。
     * 本方法仅为展示可区分性服务：占用集来自 {@link #listAll()}（DB 列权威，
     * MAX_JOBS=50 上限 → O(50) 扫描零成本；含 DURABLE+SESSION 全量）。
     *
     * <p>base 未占用 → 原样返回；占用 → 按 -2/-3... 递增探测，每次从 base 重算
     * （base 超 64-tag 长度时先截断再拼，恒 ≤64，对齐 @Size(max=64) 契约）。
     * 非 null 契约：工具路径恒传非 null，null → fail-loud NPE。
     */
    public String nextAvailableName(String base) {
        Objects.requireNonNull(base, "nextAvailableName: base must not be null");
        Set<String> used = listAll().stream()
            .map(ScheduleDto::name)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String tag = "-" + i;
            int keep = Math.max(0, 64 - tag.length());
            String candidate = (base.length() > keep ? base.substring(0, keep) : base) + tag;
            if (!used.contains(candidate)) {
                if (log.isDebugEnabled()) {
                    log.debug("[Schedule] nextAvailableName: 展示名 '{}' 已被占用，改用递增后缀 '{}' "
                            + "（NEW-5：同 cron 二次创建均 fire，对齐 CC addCronTask 无去重 cronTasks.ts:194-219）",
                        base, candidate);
                }
                return candidate;
            }
        }
    }

    public ScheduleDto getById(String id) {
        ScheduleRecord s = scheduleMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Schedule " + id + " not found");
        // CRON-B2: 优先读 DB scope/session_id 列，sessionJobs 仅兜底旧行
        ScheduleDto dto = toDto(s, lookupScope(s), lookupSessionId(s));
        // IMPL-05（✗-C P2 读容错）: 非法 kind 脏行 → toDto 返回 null → 404（脏行 = 不可见资源，
        // 与 listAll 跳过语义一致；对齐 CC cronTasks.ts:108-137 坏条目不阻塞整文件）
        if (dto == null) throw new NotFoundException("Schedule " + id + " not found");
        return dto;
    }

    /**
     * 创建 schedule：DB 插行 → Quartz 注册（注册失败回滚 DB，见方法体 try/catch）。
     *
     * <p>IMPL-06/NEW-5: {@code name} 无唯一性语义、仅为展示字段 —— CC CronTask 无 name
     * （cronTasks.ts:30-70），addCronTask 无去重（cronTasks.ts:194-219），V20 已移除
     * schedules.name UNIQUE 约束 → 同 name 可重复创建（不同 id 均 201 落库），不再撞
     * DataIntegrityViolation（旧 500 已消除）。展示碰撞由工具路径
     * {@link #nextAvailableName} 递增后缀处理；REST 路径 name 由调用方提供，重复创建走正常路径。
     */
    public ScheduleDto create(ScheduleCreateRequest req) {
        // s14-P1-4: 检查 MAX_JOBS 上限 (对齐 CC CronCreateTool.ts:25 MAX_JOBS=50)
        int currentCount = scheduleMapper.selectAll().size();
        if (currentCount >= MAX_JOBS) {
            // CRON-F6: 拒绝消息逐字对齐 CC CronCreateTool.ts:98-104 validateInput errorCode3
            // （REST 直达用户，不得泄漏内部 CC 源码行号）
            // CRON-B4-3 决策 #13：抛 MaxJobsExceededException（ConflictException 子类）→ 全局处理器
            // 映射 409 + RFC7807 Problem errorCode:"3"，与工具路径 errorCode3 语义一致。
            // 旧实现抛 IllegalStateException → HTTP 500，偏离 CC 语义（已移除）。
            log.warn("[Schedule] create() 已达 MAX_JOBS={} 上限（当前 {} 个任务），拒绝创建新任务",
                MAX_JOBS, currentCount);
            throw new MaxJobsExceededException(
                "Too many scheduled jobs (max " + MAX_JOBS + "). Cancel one first.");
        }
        ScheduleKind kind = req.kind();
        if (kind == null) {
            throw new ValidationException("Schedule 'kind' is required (cron|once|interval)");
        }
        validateKindFields(kind, req);
        // s14-P1-5: SESSION scope 必须带 sessionId (CC addSessionCronTask 强绑定 session)
        ScheduleScope scope = req.scope() == null ? ScheduleScope.DURABLE : req.scope();
        String sessionId = req.sessionId();
        if (scope == ScheduleScope.SESSION && (sessionId == null || sessionId.isBlank())) {
            throw new ValidationException("scope=SESSION requires non-empty 'sessionId'");
        }

        ScheduleRecord s = new ScheduleRecord();
        // CRON-F1: req.id() 为 CronCreateTool one-shot 预生成 id（jitter taskId 依赖，见
        // CronJitter.jitterFrac）· 正常（REST/other）恒 null → 服务端 generateId，行为不变。
        String scheduleId = req.id() != null ? req.id() : generateId("sch");
        s.setId(scheduleId);
        s.setName(req.name());
        s.setKind(kind.name());
        s.setCron(req.cron());
        s.setIntervalSeconds(req.intervalSeconds());
        // CRON-B2-2（决策 #2 / OPD-Cron-F1-b）：REST 直建 one-shot（kind=once, req.id()==null，
        // 原始墙钟 runAt）统一应用 one-shot jitter；工具路径（req.id()!=null）已在
        // CronCreateTool:368 jitter 过，此处跳过不双 jitter。
        s.setRunAt(resolveJitteredRunAt(kind, scheduleId, req));
        s.setCommand(req.command() == null || req.command().isBlank() ? "test" : req.command());
        s.setDescription(req.description() != null ? req.description() : "");
        s.setLastRunAt(null);
        s.setLastRunStatus(null);
        // CRON-B2: createdAt 数值锚点必落库 · CC original: CronTask.createdAt
        // (Open-ClaudeCode/src/utils/cronTasks.ts:208 createdAt: Date.now())
        s.setCreatedAt(System.currentTimeMillis());
        // CRON-B2: durable→scope 语义落库 · CC original: CronTask.durable
        // (cronTasks.ts:175 strip durable；cronTasks.ts:211-213 durable=false→session 内存)。
        // Java 侧用户拍板维持 SQLite 只补字段（OPD-Cron-02）：SESSION 仍落库 + Quartz 注册，
        // scope 列落库使重启后 selectAll 可按 scope=SESSION 辨识（R-1 僵尸修复）。
        s.setScope(scope.name());
        // session_id 无条件落库：SESSION = 生命周期绑定（cleanupBySession/sweep 按 scope 过滤）；
        // DURABLE = 归属对话/注入目标（CronCreateTool 填创建会话，fire 存活时 transcript 归创建会话，
        // 已关 headless 无 transcript）——两语义共用一列，生命周期判定始终以 scope 列为权威。
        s.setSessionId(sessionId);
        // CRON-D4: teammate agentId 落库 · CC original: CronTask.agentId (cronTasks.ts:69)。
        // create 由 CronCreateTool 从 TeammateContext 填充（CronCreateTool.ts:126），
        // DURABLE/主线程 为 null。V9 agent_id 列（OPD-D4-GAP-5 方案 A）。
        s.setAgentId(req.agentId());
        // 批次X Q2: DURABLE 任务存 boundProject（创建会话绑定项目）· CC original: 无字段
        // （CC durable 项目锚=文件位置 cronTasks.ts:74-83；Java 全局单表须显式落列 V23）。
        // 仅 DURABLE scope 透传 req.boundProject()（CronCreateTool 填创建会话绑定项目）；
        // SESSION 恒 null（其项目锚由 sessionId 恢复路径承载，两路径清晰分离）。
        // 无会话 REST 直建 DURABLE（boundProject=null）→ 列留 null，fire 兜底 user.dir
        // （已知差异：CC 所有 durable 任务都在会话里创建）。
        //
        // [cron-durable-session-fire] DURABLE 的 session_id 语义 = 归属对话/注入目标（非 SESSION 的
        // 生命周期绑定）：CronCreateTool DURABLE 分支现在也存创建会话 sessionId（有会话时），
        // fire 时 CronIdleExecutor 据此判定创建会话存活 → transcript 归创建会话文件；已关 →
        // headless 无 transcript。cleanupBySession 只按 scope=SESSION 过滤（ScheduleService:941），
        // DURABLE 行带 sessionId 不会被误删（生命周期绑定仍归 SESSION）。
        if (scope == ScheduleScope.DURABLE) {
            s.setBoundProject(req.boundProject());
        }
        // CRON-B2: permanent 默认 false · CC original: CronTask.permanent
        // (cronTasks.ts:57 工具不可设，缺省 false=不豁免 7 天过期)。
        // 【设计 TODO · CRON-F6】CC 基线 permanent 0 写入者：cronTasks.ts:341 注释引用的
        // install.ts writeIfMissing() 写入口在当前源码快照缺失（find -name install.ts 为空），
        // 唯一 permanent:true 出现为 :136 序列化 round-trip —— Java 恒 false 与 CC 对齐。
        // 豁免路径已由 ScheduleServiceAgedTest.permanentNeverAged（:75-81）覆盖。
        // 不暴露 CronCreate permanent 入口（不发明 CC 没有的能力），留待 assistant-mode
        // （catch-up/morning-checkin/dream）接入时再补写入口。
        s.setPermanent(Boolean.FALSE);
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] create() 构建 ScheduleRecord id={} scope={} sessionId={} agentId={} "
                    + "boundProject={} createdAt={} permanent={} (对齐 CC cronTasks.ts:208/:212)",
                s.getId(), scope, sessionId, s.getAgentId(), s.getBoundProject(), s.getCreatedAt(), s.getPermanent());
        }

        scheduleMapper.insert(s);
        log.info("[Schedule] create() 已落库 id={} scope={} sessionId={} agentId={} boundProject={} createdAt={}",
            s.getId(), scope, sessionId, s.getAgentId(), s.getBoundProject(), s.getCreatedAt());
        // 注册到 Quartz（失败则回滚 DB 行）
        try {
            quartzScheduleService.registerSchedule(s);
        } catch (Exception e) {
            log.error("[Schedule] Quartz register failed, rolling back DB row id={}: {}",
                s.getId(), e.getMessage());
            scheduleMapper.deleteById(s.getId());
            throw e;
        }
        // s14-P1-5: SESSION scope 任务登记到 sessionJobs (CC removeSessionCronTasks 内存索引)
        if (scope == ScheduleScope.SESSION) {
            sessionJobs.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(s.getId());
            log.info("[Schedule] s14-P1-5: SESSION-scoped job registered id={} session={}",
                s.getId(), sessionId);
        }
        // IMPL-05: 写路径已强制 kind 非 null 合法（:139-141 校验 + validateKindFields），
        // 此处 toDto 必不返回 null（非法行仅人工干预/旧数据可达，见 parseKind）
        return toDto(s, scope, sessionId);
    }

    public void delete(String id) {
        ScheduleRecord s = scheduleMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Schedule " + id + " not found");
        // 先卸 Quartz（job 不存在也 ok）
        quartzScheduleService.unregisterSchedule(id);
        scheduleMapper.deleteById(id);
        // D-6 修复：DB 删除成功后同步 sessionJobs 索引（对齐 CC removeCronTasks
        // session-store-first，cronTasks.ts:231-240；删除失败不误删索引）
        removeFromSessionJobs(id);
        log.info("[Schedule] delete() 已删除 id={}", id);
    }

    /**
     * 部分更新 · 对齐 CC RemoteTriggerTool.ts:120-126 {@code update=POST base/{trigger_id}}
     * （FIX-3 / RV-C-03 G3/G4，remote-trigger 域 update 能力，非 cron 域 cronTasks.ts 无 update 的 C07 裁定）。
     *
     * <p>语义（partial update，全字段可选，只改非 null 字段）：
     * <ol>
     *   <li>按 id 读既有记录（不存在 → {@link NotFoundException}）；</li>
     *   <li>仅覆盖请求体中非 null 字段（name/kind/cron/intervalSeconds/runAt/command/
     *       description/scope/sessionId/agentId）；</li>
     *   <li><b>createdAt 保留</b>（不触碰，对齐 CC CronTask.createdAt 创建即锚定不可漂移）；
     *       lastRunAt/lastRunStatus/permanent 亦保留（不随 update 重置）；</li>
     *   <li>写回 DB（{@code scheduleMapper.update} 整行写，与 {@link #runNow} 同模式）；</li>
     *   <li>Quartz 重注册（{@link QuartzScheduleService#registerSchedule} 幂等 reschedule，
     *       cron/kind 变化后重新挂 trigger；buildTrigger 校验 kind 字段，非法即抛）。</li>
     * </ol>
     *
     * @param id  待更新调度 id
     * @param req 部分更新请求（全字段可选）
     * @return 更新后的 {@link ScheduleDto}
     */
    public ScheduleDto update(String id, ScheduleUpdateRequest req) {
        ScheduleRecord s = scheduleMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Schedule " + id + " not found");

        // partial update：仅覆盖非 null 字段（全字段可选）
        if (req.name() != null) s.setName(req.name());
        if (req.kind() != null) s.setKind(req.kind().name());
        if (req.cron() != null) s.setCron(req.cron());
        if (req.intervalSeconds() != null) s.setIntervalSeconds(req.intervalSeconds());
        if (req.runAt() != null) s.setRunAt(req.runAt());
        if (req.command() != null) s.setCommand(req.command());
        if (req.description() != null) s.setDescription(req.description());
        if (req.scope() != null) s.setScope(req.scope().name());
        if (req.sessionId() != null) s.setSessionId(req.sessionId());
        if (req.agentId() != null) s.setAgentId(req.agentId());
        // 批次X Q2: boundProject 为创建时项目锚（对齐 CC STATE.projectRoot 启动冻结语义，
        // B 探查 §7.3：锚来源=创建会话绑定项目，fire 恢复=写回执行线程项目上下文），
        // 不随 update 漂移 —— ScheduleUpdateRequest 无 boundProject 字段（partial update 仅
        // 覆盖创建后可变的执行参数，锚不变，对齐 CC 中途 cd/EnterWorktreeTool 永不重锚 state.ts:519-525）。
        // createdAt / lastRunAt / lastRunStatus / permanent 均保留（partial update 不触碰）

        scheduleMapper.update(s);
        log.info("[Schedule] update() 已回写 id={}（Quartz 重注册，createdAt 保留）", id);
        // Quartz 重注册（cron/kind 可能变化；registerSchedule 幂等 reschedule）
        try {
            quartzScheduleService.registerSchedule(s);
        } catch (Exception e) {
            // 不回滚 DB（字段已回写，reconcileQuartzAtStartup 启动对账可补注册）；fail-loud 上抛
            log.error("[Schedule] update() Quartz 重注册失败 id={}: {}", id, e.getMessage());
            throw e;
        }

        if (log.isDebugEnabled()) {
            log.debug("[Schedule] update() 完成 id={} kind={} scope={}（对齐 CC RemoteTriggerTool.ts:120-126）",
                id, s.getKind(), s.getScope());
        }
        ScheduleDto dto = toDto(s, lookupScope(s), lookupSessionId(s));
        // IMPL-05（✗-C P2 读容错）: 更新的是非法 kind 脏行 → toDto 返回 null → 404（不可见资源）。
        // 注（IMPL-05 反思 C1）: 脏行场景下 registerSchedule 会先抛 ValidationException(400)
        // （:337 先于本判空执行），本判空仅防御 future 重构，不可达行为按 registerSchedule 400 计。
        if (dto == null) throw new NotFoundException("Schedule " + id + " not found");
        return dto;
    }

    /** helper: cron/interval 视为 recurring, once 不算. (Java 侧由 kind 推导 CC 的 t.recurring, B1 DDL 无 recurring 列) */
    private boolean isRecurringKind(String kind) {
        if (kind == null) return false;
        String k = kind.toLowerCase();
        return "cron".equals(k) || "interval".equals(k) || "recurring".equals(k);
    }

    /**
     * 单条记录是否已过期（aged）· 对齐 CC cronScheduler.ts:53-60 isRecurringTaskAged.
     *
     * <p>纯函数（无副作用、不访问 DB/时间源），供 fire-then-delete 决策与单测使用.
     * CC 原文 (Open-ClaudeCode/src/utils/cronScheduler.ts:58-59)：
     * <pre>
     * if (maxAgeMs === 0) return false
     * return Boolean(t.recurring && !t.permanent && nowMs - t.createdAt >= maxAgeMs)
     * </pre>
     *
     * <p>三个不变量（与 CC 完全一致）：
     * <ul>
     *   <li>{@code maxAgeMs == 0} → 永不 aged（CC cronTasks.ts:343 注释 "0 = unlimited"）</li>
     *   <li>permanent 豁免：{@code permanent=true} 的任务永不 aged</li>
     *   <li>边界 {@code >=}（非 {@code >}）：ageMs 恰好等于 maxAgeMs 也算 aged（S-02）</li>
     * </ul>
     *
     * <p>字段映射：CC {@code t.recurring} ← {@code isRecurringKind(kind)}（非 CC 直接字段）；
     * CC {@code t.permanent} ← {@link ScheduleRecord#getPermanent()}；
     * CC {@code t.createdAt} ← {@link ScheduleRecord#getCreatedAt()}（B1 补结构化字段，取代旧反射读）。
     *
     * @param s       调度记录（null → false）
     * @param nowMs   当前时间（epoch ms，调用方传入便于测试）
     * @param maxAgeMs 过期阈值 ms（0 = 不限，永不 aged）
     * @return true = 已 aged，应在下次 fire 后删除
     */
    public boolean isRecurringTaskAged(ScheduleRecord s, long nowMs, long maxAgeMs) {
        if (s == null) return false;
        if (maxAgeMs == 0) return false;                        // CC cronScheduler.ts:58 maxAgeMs === 0
        if (!isRecurringKind(s.getKind())) return false;        // CC t.recurring
        if (Boolean.TRUE.equals(s.getPermanent())) return false; // CC !t.permanent (cronScheduler.ts:59)
        Long createdAt = s.getCreatedAt();
        if (createdAt == null) return false;                    // 旧行 createdAt 未写 → 不老化 (B1 兼容)
        return nowMs - createdAt >= maxAgeMs;                   // CC cronScheduler.ts:59 nowMs - t.createdAt >= maxAgeMs
    }

    /**
     * 运行时可调 recurring 过期窗口 · CC original: {@code jitterCfg.recurringMaxAgeMs}
     * (cronScheduler.ts:243 {@code getJitterConfig?.() ?? DEFAULT_CRON_JITTER_CONFIG} 每 tick 读一次配置；
     * :302 fire 时刻 {@code isRecurringTaskAged(t, now, jitterCfg.recurringMaxAgeMs)} 用配置值判定)。
     *
     * <p>与 {@link #resolveJitteredRunAt} 同一 fail-open 模式：jitterProps null（非 Spring 单测）→
     * {@link CronJitterProperties#DEFAULTS} 回退（对齐 CC 无 getJitterConfig 时回退
     * {@code DEFAULT_CRON_JITTER_CONFIG.recurringMaxAgeMs=7d}，cronTasks.ts:354）。
     * {@code 0} = 永不 aged（CC cronTasks.ts:343 注释 "0 = unlimited"），由 {@link #isRecurringTaskAged}
     * 的 {@code maxAgeMs == 0} 分支兜底。
     *
     * @return recurring 过期阈值 ms（0 = 不限）
     */
    private long resolveRecurringMaxAgeMs() {
        CronJitterProperties props = jitterProps != null ? jitterProps : CronJitterProperties.DEFAULTS;
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] resolveRecurringMaxAgeMs: recurringMaxAgeMs={} 来源={}",
                props.recurringMaxAgeMs(),
                jitterProps != null ? "配置 nexusai.cron.jitter.recurringMaxAgeMs" : "DEFAULTS 回退（未注入 jitterProps）");
        }
        return props.recurringMaxAgeMs();
    }

    /**
     * fire 后删除决策（fire-then-delete 支持方法）· 对齐 CC cronScheduler.ts:315/325-344.
     *
     * <p>CC 决策点（cronScheduler.ts:315）：
     * <pre>
     * if (t.recurring &amp;&amp; !aged) {
     *   // reschedule + markFired
     * } else {
     *   // 删除：session → removeSessionCronTasks 同步；file → removeCronTasks 异步
     * }
     * </pre>
     *
     * <p>本方法镜像该单一决策点：recurring 且未 aged → 保留（返回 false，供调用方 reschedule）；
     * one-shot 或已 aged 的 recurring → 删除（返回 true）。删除统一走
     * {@link #deleteRowAndUnregister}：unregister + deleteById + sessionJobs 全列表同步移除
     * （对齐 CC removeSessionCronTasks 内存同步，cronScheduler.ts:329）。CC 无独立后台清理循环
     * （cleanupExpiredRecurring 后台扫描已废弃），aged 只在 fire 时刻判定。
     *
     * <p>接线：已接线 CRON-F4（TestJob#applyFireLifecycle fire 路径，含 runNow 触发）在任务触发后调用本方法。
     *
     * @param id 调度 id（不存在 → 记录 warn 并返回 false，无删除动作）
     * @return true = 已删除（one-shot fire 后 / aged recurring fire 后）；false = 保留（recurring 未 aged）
     */
    public boolean deleteAfterFire(String id) {
        ScheduleRecord s = scheduleMapper.selectOneById(id);
        if (s == null) {
            log.warn("[Schedule] deleteAfterFire: 任务不存在 id={}，无删除动作", id);
            return false;
        }
        long now = System.currentTimeMillis();
        boolean recurring = isRecurringKind(s.getKind());
        long maxAgeMs = resolveRecurringMaxAgeMs();
        boolean aged = isRecurringTaskAged(s, now, maxAgeMs);
        if (recurring && !aged) {
            // CC cronScheduler.ts:315 if (t.recurring && !aged) → reschedule（保留）
            if (log.isDebugEnabled()) {
                log.debug("[Schedule] deleteAfterFire: recurring 未 aged，保留并 reschedule id={} kind={} "
                        + "ageMs={} maxAgeMs={}",
                    id, s.getKind(),
                    s.getCreatedAt() == null ? null : now - s.getCreatedAt(),
                    maxAgeMs);
            }
            return false;
        }
        // CC cronScheduler.ts:325-344 else 分支：one-shot 或 aged recurring → 删除
        if (recurring) {
            // IMPL-10 (NEW-12): expired 事件 · CC cronScheduler.ts:302 aged 判定通过 → :308-312
            // logEvent —— 仅 aged recurring 删除路径发射；one-shot 删除不发（CC :315-344 无）
            emitExpiredTelemetry(id, s.getCreatedAt(), now);
        }
        String reason = recurring
            ? "aged recurring（maxAgeMs=" + maxAgeMs + "，fire 后删除）"
            : "one-shot（fire 后自动删除）";
        deleteRowAndUnregister(s, reason);
        return true;
    }

    /**
     * IMPL-10 (NEW-12): expired 事件遥测 · CC original: {@code logEvent(
     * 'tengu_scheduled_task_expired', {taskId, ageHours})}（cronScheduler.ts:308-312，
     * check() 内 aged 判定 :302 {@code isRecurringTaskAged} 通过后、删除分支前发射；
     * one-shot 删除路径不发 —— CC :315-344 仅 aged 分支含 logEvent）。
     *
     * <p>本 helper 仅在 {@link #deleteAfterFire} 的 {@code recurring && aged} 删除分支调用
     * （调用方保证），此时 createdAt 恒非 null（{@link #isRecurringTaskAged} :383 已判）。
     * {@code ageHours = floor((now - createdAt) / 1000 / 60 / 60)} —— long 除法即 CC
     * {@code Math.floor} 语义（cronScheduler.ts:304 同式）。载荷 key 逐字保留：taskId /
     * ageHours。telemetry null（未注入）→ 静默跳过，不影响删除/返回语义。
     *
     * @param id        任务 id（CC 载荷 taskId）
     * @param createdAt 创建时间 epoch ms（非 null，调用方保证）
     * @param now       当前时间 epoch ms
     */
    private void emitExpiredTelemetry(String id, Long createdAt, long now) {
        if (telemetry == null) {
            return;
        }
        long ageHours = (now - createdAt) / 1000 / 60 / 60;
        telemetry.recordEvent("tengu_scheduled_task_expired",
            Map.of("taskId", id, "ageHours", ageHours));
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] IMPL-10: tengu_scheduled_task_expired 发射 taskId={} ageHours={} "
                    + "（对齐 CC cronScheduler.ts:308-312）",
                id, ageHours);
        }
    }

    /**
     * fire-then-delete 统一删除入口 · 对齐 CC cronScheduler.ts:325-344 删除路径
     * （session → removeSessionCronTasks 同步；file → removeCronTasks 异步）。
     *
     * <p>删除 = Quartz unregister + DB 删行 + sessionJobs 全列表同步移除
     * （对齐 CC removeSessionCronTasks 内存同步，cronScheduler.ts:329）。
     * 与既有 {@link #delete(String)} 一致：DB 删除成功后均同步 sessionJobs
     * （delete() 见 D-6 修复，已调 removeFromSessionJobs）。
     *
     * @param s      待删除的调度记录
     * @param reason 删除原因（写日志）
     */
    private void deleteRowAndUnregister(ScheduleRecord s, String reason) {
        quartzScheduleService.unregisterSchedule(s.getId());
        scheduleMapper.deleteById(s.getId());
        // sessionJobs 全列表同步移除（CC removeSessionCronTasks 同步内存清理）
        removeFromSessionJobs(s.getId());
        log.info("[Schedule] deleteAfterFire 删除任务 id={} kind={} reason={}",
            s.getId(), s.getKind(), reason);
    }

    /**
     * 从 sessionJobs 全列表同步移除该 id（对齐 CC removeSessionCronTasks 内存同步，
     * cronTasks.ts:231-240 removeCronTasks session-store-first）。
     * 空列表随即移除映射，避免内存泄漏；仅 DB 删除成功后调用，失败不误删索引。
     *
     * @param id 调度 id
     */
    private void removeFromSessionJobs(String id) {
        for (var entry : sessionJobs.entrySet()) {
            if (entry.getValue().remove(id) && entry.getValue().isEmpty()) {
                sessionJobs.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * CRON-B4: missed 任务检测（纯函数）· 对齐 CC cronTasks.ts:453-458 findMissedTasks。
     *
     * <p>CC 原文（Open-ClaudeCode/src/utils/cronTasks.ts:453-458）：每条任务
     * {@code next = nextCronRunMs(t.cron, t.createdAt); return next !== null && next < nowMs}。
     * Java 侧改由 {@link CronExpressionConverter#nextCronRunMs(String, long)} 统一解析器计算
     * （Session CRON-B1-2 替代私有副本 DEL-1 + B5 全 6 字段委托 Quartz）：统一解析器
     * 直接支持 6 段 Quartz 与 '||' OR 变体存储串（CronCreateTool joinVariants 产物），
     * 修复工具创建任务恒不判 missed（OPD-RV-R4）。
     *
     * <p>等价不变量：
     * <ul>
     *   <li>missed = {@code nextCronRunMs(cron, createdAt) < nowMs}（严格小于）</li>
     *   <li><b>recurring 也计入</b>：CC 函数内无 recurring 过滤（recurring 过滤在
     *       {@link #findMissedForStartup} 层，cronScheduler.ts:196）</li>
     *   <li><b>NEW-16（已拍板 2026-08-15 IMPL-12）：recurring missed fire 不补跑</b>：
     *       检测虽含 recurring（与 CC 一致），但 Java 对 recurring missed fire 不补跑——Quartz
     *       CronTrigger misfire=DO_NOTHING（QuartzScheduleService buildTrigger cron 分支）在
     *       停机/阻塞跨过 fire 点（>misfire 阈值 60s 缺省，application.yml 未覆盖）时丢弃该次
     *       fire，下次按原 cron 排程；CC 对等场景 check() 首见 tick 补跑一次
     *       （cronScheduler.ts:264-270 锚点 lastFiredAt??createdAt → :283 now&gt;=next → fire →
     *       :315-324 从 now 重排防连环 catch-up）。方向=少执行（Java 执行次数 ≤ CC），不违反
     *       OPD-Cron-09-2「先问后执行」（该决策约束 one-shot 启动表面确认
     *       cronScheduler.ts:195-216；CC recurring 亦不表面 :189-191/:196）</li>
     *   <li>无效 cron / 无匹配 → nextCronRunMs 返回 null → 不计 missed
     *       （Quartz getNextValidTimeAfter 无下一有效时间即 null）</li>
     *   <li>createdAt 为 null（B1 旧行）→ 不判 missed（无锚点）</li>
     * </ul>
     *
     * @param records 全量调度记录
     * @param nowMs   当前时间（epoch ms，调用方传入便于测试）
     * @return missed 任务列表（含 recurring；调用方按需二次过滤）
     */
    public List<ScheduleRecord> findMissedTasks(List<ScheduleRecord> records, long nowMs) {
        if (records == null || records.isEmpty()) return List.of();
        List<ScheduleRecord> missed = new ArrayList<>();
        for (ScheduleRecord r : records) {
            Long createdAt = r.getCreatedAt();
            if (createdAt == null) continue;  // B1 旧行无锚点，不判 missed
            Long next = CronExpressionConverter.nextCronRunMs(r.getCron(), createdAt);
            if (next != null && next < nowMs) missed.add(r);
        }
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] findMissedTasks 共 {} 条记录，missed {} 条 nowMs={} "
                    + "(对齐 CC cronTasks.ts:453-458)",
                records.size(), missed.size(), nowMs);
        }
        return missed;
    }

    /**
     * CRON-B4: 启动时表面（surface）missed one-shot 任务 · 对齐 CC cronScheduler.ts:194-204 load(initial).
     *
     * <p>CC 过滤链（cronScheduler.ts:195-197）：
     * <pre>
     * const missed = findMissedTasks(next, now).filter(
     *   t =&gt; !t.recurring &amp;&amp; !missedAsked.has(t.id) &amp;&amp; (!filter || filter(t)),
     * )
     * </pre>
     * 只对 one-shot 表面（CC cronScheduler.ts:196 !t.recurring 过滤；CC 中 recurring 由 check()
     * 首见 tick 补跑一次 cronScheduler.ts:264-283，Java 无 check() 等价——recurring missed fire
     * 被 Quartz DO_NOTHING 丢弃不补跑，差异登记 NEW-16 少执行方向，见 QuartzScheduleService
     * buildTrigger cron 分支注释）；missedAsked 防重复（:200 add）。
     * Java 侧无独立 filter 参数（WF-D 接线时按需二次过滤）。
     *
     * @param records 全量调度记录
     * @param nowMs   当前时间（epoch ms）
     * @return 本次应表面的 missed one-shot 任务（已登记入 {@link #missedAsked}）
     */
    public List<ScheduleRecord> findMissedForStartup(List<ScheduleRecord> records, long nowMs) {
        List<ScheduleRecord> oneShot = new ArrayList<>();
        for (ScheduleRecord r : findMissedTasks(records, nowMs)) {
            if (isRecurringKind(r.getKind())) continue;      // CC !t.recurring (cronScheduler.ts:196)
            if (missedAsked.contains(r.getId())) continue;   // CC !missedAsked.has(t.id)
            oneShot.add(r);
            missedAsked.add(r.getId());                      // CC :200 missedAsked.add(t.id)
        }
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] findMissedForStartup 表面 missed one-shot {} 条 "
                    + "(对齐 CC cronScheduler.ts:194-204)", oneShot.size());
        }
        return oneShot;
    }

    /**
     * CRON-B4: missed 任务表面后删除（surface-then-delete）· 对齐 CC
     * cronScheduler.ts:218-223 {@code removeCronTasks(missed.map(t => t.id), dir)}.
     *
     * <p>CC 在表面 missed 之后立即从 JSON 删除（通知文本声称 "already been removed"），
     * 避免用户确认前 check() 重放原始 prompt。Java 侧等价 = {@link #deleteRowAndUnregister}
     * （unregister + deleteById + sessionJobs 同步，对齐 CC removeSessionCronTasks 内存清理）。
     * 破坏性操作：WF-D 接线前不得误调。
     *
     * @param ids missed 任务 id 集合（不存在 → 记录 warn 并跳过，幂等）
     * @return 实际删除条数
     */
    public int removeMissedTasks(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int deleted = 0;
        for (String id : ids) {
            ScheduleRecord s = scheduleMapper.selectOneById(id);
            if (s == null) {
                log.warn("[Schedule] removeMissedTasks: 任务不存在 id={}，跳过", id);
                continue;
            }
            deleteRowAndUnregister(s, "missed one-shot 表面后删除");
            deleted++;
        }
        log.info("[Schedule] removeMissedTasks 表面后删除 {} 条 missed 任务", deleted);
        return deleted;
    }

    /**
     * CRON-B4: missed 任务通知文本 · 对齐 CC cronScheduler.ts:542-565
     * buildMissedTaskNotification.
     *
     * <p>CRON-B4-3 决策 #12 (OPD-EL-01)：CC :544 header 写死
     * &quot;already been removed from .claude/scheduled_tasks.json.&quot;（介质泄漏），用户拍板
     * 覆写为中性 &quot;already been removed from the scheduled task store.&quot;。未来审计勿修回 CC 字面。
     *
     * <p>CC 行为（fence 防自注入 + AskUserQuestion 先问后执行）：
     * <ul>
     *   <li>header 随单复数切换：&quot;Do NOT execute ... yet. First use the AskUserQuestion
     *       tool ... Only execute if the user confirms.&quot;（cronScheduler.ts:545-549）</li>
     *   <li>每个任务一个 block：meta + fence 包裹 prompt；fence = 反引号串，长度 =
     *       max(3, prompt 内最长反引号 run + 1)，防止 prompt 内 ``` 提前闭合 fence
     *       （cronScheduler.ts:555-562）</li>
     *   <li>Java 侧 cronToHuman 缺失（T1-03 待拍板）：meta 用原始 cron + ISO created 占位</li>
     * </ul>
     *
     * @param missed 已确认的 missed one-shot 任务
     * @return 通知文本（应交给 AskUserQuestion 展示，用户确认后才执行）
     */
    public String buildMissedTaskNotification(List<ScheduleRecord> missed) {
        boolean plural = missed.size() > 1;
        String header = "The following one-shot scheduled task"
            + (plural ? "s were" : " was")
            + " missed while Claude was not running. "
            + (plural ? "They have" : "It has")
            // CRON-B4-3 决策 #12 (OPD-EL-01)：CC cronScheduler.ts:544 写死
            // ".claude/scheduled_tasks.json" 介质被用户拍板覆写为中性 "the scheduled task store"
            + " already been removed from the scheduled task store.\n\n"
            + "Do NOT execute " + (plural ? "these prompts" : "this prompt") + " yet. "
            + "First use the AskUserQuestion tool to ask whether to run "
            + (plural ? "each one" : "it") + " now. "
            + "Only execute if the user confirms.";
        List<String> blocks = new ArrayList<>();
        for (ScheduleRecord t : missed) {
            String cron = t.getCron() == null ? "" : t.getCron();
            String created = t.getCreatedAt() == null
                ? "unknown"
                : Instant.ofEpochMilli(t.getCreatedAt()).toString();
            String meta = "[" + cron + ", created " + created + "]";
            String prompt = t.getCommand() == null ? "" : t.getCommand();
            String fence = "`".repeat(Math.max(3, longestBacktickRun(prompt) + 1));
            blocks.add(meta + "\n" + fence + "\n" + prompt + "\n" + fence);
        }
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] buildMissedTaskNotification 构造通知文本，missed {} 条 "
                    + "(对齐 CC cronScheduler.ts:542-565)", missed.size());
        }
        return header + "\n\n" + String.join("\n\n", blocks);
    }

    /**
     * CRON-F5: 启动时表面（surface）missed one-shot 调度任务 · 对齐 CC cronScheduler.ts:194-227 load(initial).
     *
     * <p>CC 编排链（cronScheduler.ts:194-227 load(initial)）：
     * <ol>
     *   <li><b>仅 DURABLE 任务参与</b> —— CC 只加载 file-backed tasks（cronScheduler.ts:159-161
     *       "File-backed tasks only. Session tasks (durable: false) are NOT loaded here"）。
     *       Java 等价：lookupScope == DURABLE（SESSION 任务仅 session 生命周期，启动时不存在）</li>
     *   <li><b>findMissedForStartup 过滤</b> one-shot + missedAsked 防重复 + next&lt;now
     *       （cronScheduler.ts:195-197 + cronTasks.ts:453-458）</li>
     *   <li><b>空 → Optional.empty</b>（无 missed 无通知）</li>
     *   <li><b>buildMissedTaskNotification 构建通知</b>（cronScheduler.ts:542-565）—— header 指示
     *       "先 AskUserQuestion 问用户再执行，不自动执行"（对应 onFire(buildMissedTaskNotification)）</li>
     *   <li><b>removeMissedTasks 表面后删除</b>（cronScheduler.ts:218-223 removeCronTasks）——
     *       surface-then-delete 固有权衡：通知已入队后任务即删，防 check() 重放原始 prompt</li>
     * </ol>
     *
     * <p>破坏性提示：通知返回后由调用方入队（队列注入 agent 确认流程），任务此刻已删；
     * 应用若在入队前崩溃则任务丢失（对齐 CC surface-then-delete 固有权衡，风险登记）。
     *
     * @param nowMs 当前时间（epoch ms，测试注入确定值）
     * @return 通知文本（应交给 AskUserQuestion 展示，用户确认后才执行）；无 missed → {@link Optional#empty()}
     */
    public Optional<String> surfaceMissedForStartup(long nowMs) {
        List<ScheduleRecord> all = scheduleMapper.selectAll();
        // CC file-backed only (cronScheduler.ts:159-161)：仅 DURABLE 参与 missed 检测
        List<ScheduleRecord> durable = new ArrayList<>();
        for (ScheduleRecord r : all) {
            if (lookupScope(r) == ScheduleScope.DURABLE) durable.add(r);
        }
        List<ScheduleRecord> missed = findMissedForStartup(durable, nowMs);
        if (missed.isEmpty()) {
            log.info("[Schedule] surfaceMissedForStartup 无 missed one-shot 任务（DURABLE {} 条，"
                + "总 {} 条）（对齐 CC cronScheduler.ts:194-227）", durable.size(), all.size());
            return Optional.empty();
        }
        // IMPL-10 (NEW-12): missed 事件 · CC cronScheduler.ts:205-212 logEvent 在 onMissed/onFire
        // (:213-217) 与 removeCronTasks (:218-223) 之前发射 —— 与通知 sink 无关，missed 检测到即发
        emitMissedTelemetry(missed);
        String notification = buildMissedTaskNotification(missed);
        // surface-then-delete · CC cronScheduler.ts:218-223 removeCronTasks（通知构建后立即删）
        removeMissedTasks(missed.stream().map(ScheduleRecord::getId).toList());
        log.info("[Schedule] surfaceMissedForStartup 表面 {} 条 missed one-shot 并已删除，"
            + "通知文本 {} 字符（对齐 CC cronScheduler.ts:218-223）", missed.size(), notification.length());
        return Optional.of(notification);
    }

    /**
     * IMPL-10 (NEW-12): missed 事件遥测 · CC original: {@code logEvent(
     * 'tengu_scheduled_task_missed', {count, taskIds})}（cronScheduler.ts:205-212，
     * load(initial) missed 检测后、onMissed/onFire :213-217 与 removeCronTasks :218-223
     * 之前发射 —— 与通知 sink 是否存在无关，missed 检测到即发）。
     *
     * <p>{@code taskIds} 按 missed 列表顺序逗号 join（CC :207-211
     * {@code missed.map(t => t.id).join(',')}）。载荷 key 逐字保留：count / taskIds。
     * 调用方保证 missed 非空。telemetry null（未注入）→ 静默跳过，不影响通知/删除语义。
     *
     * @param missed 已检测到的 missed one-shot 任务列表（非空）
     */
    private void emitMissedTelemetry(List<ScheduleRecord> missed) {
        if (telemetry == null) {
            return;
        }
        String taskIds = missed.stream().map(ScheduleRecord::getId).collect(Collectors.joining(","));
        telemetry.recordEvent("tengu_scheduled_task_missed",
            Map.of("count", missed.size(), "taskIds", taskIds));
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] IMPL-10: tengu_scheduled_task_missed 发射 count={} taskIds={} "
                    + "（对齐 CC cronScheduler.ts:205-212）",
                missed.size(), taskIds);
        }
    }

    /**
     * 立即触发任务 · 决策 #15 / OPD-EL-04（对齐工具路径 fire-then-delete 语义，
     * CronCreateTool.ts:152 "fire once then auto-delete"）。
     *
     * <p><b>once</b>：同步 fire-then-delete —— {@link #runOnceSynchronously} 内联调
     * {@code TestJob.fireSynchronously}（gate→getById→route/enqueue→applyFireLifecycle，
     * deleteAfterFire 同步删行），返回 executed+deleted。不走 triggerNow（异步入队 + worker
     * 晚读已删行 = fire 丢失竞态），deleteRowAndUnregister 已 unregister Quartz job → 无双发。
     *
     * <p><b>recurring（cron/interval）</b>：保持 triggerNow 异步触发 + 写 lastRunAt/lastRunStatus，
     * deleted 恒 false。recurring fire-then-delete 由 worker 线程 applyFireLifecycle 在 fire 时刻
     * 判定（CC cronScheduler.ts:315 recurring &amp;&amp; !aged → 保留），响应与 worker 完成存在
     * 时序差（幂等无破坏）。
     *
     * @param id 调度 id（不存在 → NotFoundException）
     * @return executed=是否已触发；deleted=是否已删除（仅 once）；output=结果文案
     */
    public RunNowResponse runNow(String id) {
        ScheduleRecord s = scheduleMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Schedule " + id + " not found");
        // 决策 #15 / OPD-EL-04：once 任务 fire-then-delete 同步返回；recurring 保持异步
        if (!isRecurringKind(s.getKind())) {
            return runOnceSynchronously(id);
        }
        boolean fired = quartzScheduleService.triggerNow(id);
        String now = OffsetDateTime.now().toString();
        s.setLastRunAt(now);
        s.setLastRunStatus(fired ? "ok" : "error");
        scheduleMapper.update(s);
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] runNow: recurring 任务已异步触发 id={} fired={} lastRunAt={}",
                id, fired, now);
        }
        return new RunNowResponse(fired, false,
            fired ? "Recurring job triggered (async, fires on next schedule)"
                : "Quartz trigger failed");
    }

    /**
     * 决策 #15 / OPD-EL-04：once 任务同步 fire-then-delete。
     *
     * <p>同步调 {@link TestJob#fireSynchronously}（共享 fire body，deleteAfterFire 在返回前已同步
     * 删行），随后 {@code selectOneById(id)==null} 判定 deleted —— 「已观测删除完成」语义
     * （取已删除而非确定性将删，用户拍板 OPD-EL-04）。fire 后删行释放 MAX_JOBS 配额
     * （对齐 CronCreateTool.ts:152 "fire once then auto-delete"）。
     *
     * <p>TestJob 未注入（非 Spring 单测）→ fail-loud log.error + executed=false，不静默吞错。
     *
     * @param id once 任务 id
     * @return fire-then-delete 结果（executed/deleted/output）
     */
    private RunNowResponse runOnceSynchronously(String id) {
        if (testJob == null) {
            log.error("[Schedule] runNow: once 任务 id={} 的 TestJob 未注入，无法同步 fire（fail-loud）", id);
            return new RunNowResponse(false, false, "once job not fired: TestJob not injected");
        }
        boolean fired = testJob.fireSynchronously(id);
        boolean deleted = scheduleMapper.selectOneById(id) == null;
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] runNow: once 同步 fire-then-delete id={} fired={} deleted={} "
                    + "（对齐 CronCreateTool.ts:152 fire once then auto-delete）",
                id, fired, deleted);
        }
        if (fired && deleted) {
            log.info("[Schedule] runNow: once 任务已同步 fire 并删除 id={}（释放 MAX_JOBS 配额）", id);
            return new RunNowResponse(true, true,
                "one-shot fired and deleted (fire once then auto-delete)");
        }
        if (fired) {
            log.warn("[Schedule] runNow: once 任务已 fire 但行未删除 id={}（fire 路径异常）", id);
            return new RunNowResponse(true, false, "one-shot fired but not deleted");
        }
        log.warn("[Schedule] runNow: once 任务未 fire id={}（门控/注入/数据异常）", id);
        return new RunNowResponse(false, false, "one-shot not fired");
    }

    /**
     * 批量回写 lastRunAt · 对齐 CC markCronTasksFired.
     *
     * <p>CC 原文 (Open-ClaudeCode/src/utils/cronTasks.ts:261-278)：
     * <pre>
     * if (ids.length === 0) return
     * const idSet = new Set(ids)
     * const tasks = await readCronTasks(dir)
     * let changed = false
     * for (const t of tasks) {
     *   if (idSet.has(t.id)) { t.lastFiredAt = firedAt; changed = true }
     * }
     * if (!changed) return
     * await writeCronTasks(tasks, dir)
     * </pre>
     *
     * <p>等价不变量：
     * <ul>
     *   <li>ids 空 → 0 行更新（对齐 {@code ids.length===0 return}）</li>
     *   <li>N fire = 1 write：单条 SQL 批量写（对齐 CC 一次 read-modify-write）。
     *       BaseMapper 无 updateBatch，故用 {@code updateByQuery} + {@code QueryWrapper.in} 单 SQL</li>
     *   <li>无命中 → 影响行数 0 = no-op（对齐 {@code if (!changed) return}）</li>
     *   <li>CC {@code lastFiredAt}（epoch ms number）→ Java {@code lastRunAt}
     *       （{@code OffsetDateTime.toString()}，格式与 {@link #runNow} 一致，保证 parseDateTime 可解析）
     *       + {@code lastRunStatus="ok"}</li>
     * </ul>
     *
     * <p>接线：已接线 CRON-F4（TestJob#applyFireLifecycle fire 路径，deleteAfterFire=false 保留时调用）。
     *
     * @param ids     本次 fire 的任务 id 集合
     * @param firedAt fire 时刻（写 lastRunAt）
     * @return 受影响行数（0 = 无命中 no-op）
     */
    public int markFired(Collection<String> ids, OffsetDateTime firedAt) {
        if (ids == null || ids.isEmpty()) return 0;  // CC cronTasks.ts:266 ids.length===0 → return
        ScheduleRecord patch = new ScheduleRecord();
        patch.setLastRunAt(firedAt.toString());
        patch.setLastRunStatus("ok");
        // ignoreNulls=true：仅更新 lastRunAt/lastRunStatus，patch 其余 null 字段不写
        int rows = scheduleMapper.updateByQuery(patch, true,
            QueryWrapper.create().in("id", ids));
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] markFired 批量回写 lastRunAt ids={} firedAt={} rows={} "
                    + "(对齐 CC cronTasks.ts:261-278 markCronTasksFired)",
                ids.size(), firedAt, rows);
        }
        return rows;
    }

    /**
     * 清理某 session 的所有 SESSION-scope 任务 · 决策 #7 / OPD-Cron-D5（DB 回退）。
     *
     * <p>CC 对齐：{@code removeSessionCronTasks}（cronScheduler.ts:329 同步内存删 /
     * state.ts:1307-1315 返回实际删除数）语义 = 会话结束即清理。CC session 任务仅存进程内存
     * （cronTasks.ts:211-213 addSessionCronTask，durable=false 不写盘 cronTasks.ts:57-63），
     * Java 侧拍板（OPD-Cron-02）SESSION 仍落 SQLite，故<b>重启后进程内存 sessionJobs 索引为
     * 空</b>——旧实现只读内存索引会漏删，DB 行残留、closeSession 无法清理（复验版 §13 R-2）。
     * 本方法改以 DB 列 {@code scope=SESSION && session_id=?} 为权威，逐行
     * unregister + deleteById + 索引同步。
     *
     * <p>调用时机：session 结束（chat session 关闭/超时）。signature 不变，唯一调用方
     * ChatService.closeSession（gitnexus impact = LOW，2 调用链节点）。
     *
     * @param sessionId 会话 id
     * @return 删除的数量（DB 命中且删除成功的行数）
     */
    public int cleanupBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        // DB 为权威（重启后内存索引为空，R-2 缺陷修复）；scope=SESSION && session_id=? 精确定位
        List<ScheduleRecord> rows = scheduleMapper.selectListByQuery(
            QueryWrapper.create().eq("scope", ScheduleScope.SESSION.name()).eq("session_id", sessionId));
        if (rows.isEmpty()) {
            // 索引卫生（幂等）：DB 无行时仍移除内存索引项（含 scope 列 NULL 旧行归属）
            sessionJobs.remove(sessionId);
            if (log.isDebugEnabled()) {
                log.debug("[Schedule] cleanupBySession session={} DB 无 SESSION 行，清理内存索引后返回 0"
                        + "（对齐 CC state.ts:1307-1315 removeSessionCronTasks 无命中 return 0）",
                    sessionId);
            }
            return 0;
        }
        log.info("[Schedule] cleanupBySession session={} 命中 DB 行 {} 条，逐行 unregister+delete"
            + "（对齐 CC cronScheduler.ts:329 removeSessionCronTasks 同步清理）",
            sessionId, rows.size());
        int deleted = 0;
        for (ScheduleRecord s : rows) {
            try {
                quartzScheduleService.unregisterSchedule(s.getId());
                scheduleMapper.deleteById(s.getId());
                removeFromSessionJobs(s.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("[Schedule] cleanupBySession delete failed id={} session={}: {}",
                    s.getId(), sessionId, e.getMessage());
            }
        }
        // 索引卫生（幂等）：删除后整体移除该 session 的索引项（残留旧行引用一并清理）
        sessionJobs.remove(sessionId);
        return deleted;
    }

    /**
     * 启动清扫所有无活动会话的 SESSION-scope 任务 · 决策 #7 / OPD-Cron-D5（启动清扫）。
     *
     * <p>CC 对齐：SESSION = 随进程死（cronTasks.ts:59-63 durable=false 仅内存；
     * cronScheduler.ts:376-378 每 tick 读内存 session 任务；:325-330 fire 后同步内存删）。
     * CC 无启动清扫（进程死亡任务即消失），Java 因 SESSION 仍落库（OPD-Cron-02）需补偿：
     * 启动时删除所有 {@code scope=SESSION} 且 {@code sessionId ∉ activeSessionIds} 的任务
     * （unregister + deleteById），阻止跨重启残留继续 fire（复验版 §4 D5）。
     *
     * <p>幂等（deleteById / unregisterSchedule 对不存在 job「也 ok」，见 QuartzScheduleService:201）；
     * DURABLE 恒不动（scope 过滤同 surfaceMissedForStartup ScheduleService:513 同款，
     * R1 破坏性删库安全闸）。activeSessionIds 空集（启动时 RUNNING_SESSIONS 为空）→ 全量清扫；
     * 参数化供测试注入受控集合，兼顾未来会话注册表出现时只清孤儿。
     *
     * @param activeSessionIds 活动会话 id 集合（其任务保留）；null 视为空集
     * @return 删除的任务数
     */
    public int sweepSessionTasksAtStartup(Set<String> activeSessionIds) {
        Set<String> active = activeSessionIds == null ? Set.of() : activeSessionIds;
        List<ScheduleRecord> all = scheduleMapper.selectAll();
        int deleted = 0;
        for (ScheduleRecord r : all) {
            if (lookupScope(r) != ScheduleScope.SESSION) continue;
            if (active.contains(r.getSessionId())) continue;
            try {
                quartzScheduleService.unregisterSchedule(r.getId());
                scheduleMapper.deleteById(r.getId());
                removeFromSessionJobs(r.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("[Schedule] sweepSessionTasksAtStartup 启动清扫失败 id={} session={}: {}",
                    r.getId(), r.getSessionId(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("[Schedule] sweepSessionTasksAtStartup 启动清扫删除 {} 条 SESSION 任务"
                + "（对齐 CC SESSION=随进程死，OPD-Cron-D5）", deleted);
        }
        return deleted;
    }

    /**
     * CRON-B3-2（决策 #8 / open-decisions.md R-1 补充）：启动全量对账 DB schedules ↔ QRTZ。
     *
     * <p>CC 对齐：cronScheduler.ts:179-227 load(initial) 全量重建语义 —— CC 启动时以权威存储
     * （scheduled_tasks.json）全量重建内存任务；Java 等价 = 启动时以 DB（scheduleMapper.selectAll）
     * 为权威、对 QRTZ（持久调度器）全量对账。QRTZ JDBC 损坏 / 崩溃中间态会造成两侧不一致：
     * <ul>
     *   <li><b>DB 有任务 QRTZ 缺 trigger</b>（job 缺失 或 job 在 trigger 空，
     *       {@link QuartzScheduleService#hasRegistered}）→ 补 registerSchedule（防僵尸 = DB 有任务
     *       QRTZ 不 fire）</li>
     *   <li><b>QRTZ 有 job DB 无记录</b>（孤儿，如 delete 崩溃中间态）→ warn（决策 #8 字面只 warn，
     *       不自动删）</li>
     * </ul>
     *
     * <p><b>不 gate cronGates</b>（与 B3-1 SESSION sweep 同判点）：数据完整性对账非执行路径，
     * 定时关闭也要保证 DB/QRTZ 一致（否则重开后僵尸/孤儿并存）。
     *
     * <p>调用时序：必须在 {@link #surfaceMissedForStartup}（surface-then-delete）之后运行 ——
     * 否则会把已表面删除的 missed one-shot 重新注册进 QRTZ，Quartz once SimpleTrigger
     * startAt=过去 + misfire=NextWithRemainingCount 可能立即 fire 自动执行，违反 OPD-Cron-09-2
     * 「先问后执行」（CronIdleExecutor.surfaceMissedAtStartup 内顺序保证）。
     *
     * @return 补注册数（DB 有任务但 QRTZ 未完整注册、本次重新挂上的条数）
     */
    public int reconcileQuartzAtStartup() {
        List<ScheduleRecord> all = scheduleMapper.selectAll();
        Set<String> dbIds = all.stream()
            .map(ScheduleRecord::getId)
            .collect(Collectors.toSet());
        int reRegistered = 0;
        // 1) DB 有任务 QRTZ 缺 trigger（job 缺失或 job 在 trigger 空）→ 补注册（防僵尸）
        for (ScheduleRecord s : all) {
            if (quartzScheduleService.hasRegistered(s.getId())) continue;
            try {
                quartzScheduleService.registerSchedule(s);
                reRegistered++;
                log.info("[Schedule] reconcileQuartzAtStartup 补注册 id={} kind={}"
                        + "（对齐 CC load() 全量重建 cronScheduler.ts:179-227，防僵尸）",
                    s.getId(), s.getKind());
            } catch (Exception e) {
                log.warn("[Schedule] reconcileQuartzAtStartup 补注册失败 id={}: {}",
                    s.getId(), e.getMessage());
            }
        }
        // 2) QRTZ 有 job DB 无记录 → 孤儿 warn（决策 #8 只报 warn 不删）
        List<String> orphans = quartzScheduleService.listRegisteredJobIds().stream()
            .filter(id -> !dbIds.contains(id))
            .toList();
        for (String orphan : orphans) {
            log.warn("[Schedule] reconcileQuartzAtStartup 孤儿 job（QRTZ 有 job 但 DB 无记录）"
                + "jobKey=schedule-{}，决策 #8 只报 warn 不自动删除", orphan);
        }
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] reconcileQuartzAtStartup 对账完成：DB {} 条，补注册 {}，孤儿 {} 个",
                all.size(), reRegistered, orphans.size());
        }
        return reRegistered;
    }

    // ============== helpers ==============

    /**
     * CRON-B2-2（决策 #2 / OPD-Cron-F1-b）: 计算 once 分支落库 runAt · REST 直建 one-shot 统一
     * 应用 one-shot jitter。
     *
     * <p>CC original: oneShotJitteredNextCronRunMs (Open-ClaudeCode/src/utils/cronTasks.ts:421-445)
     * + 调度层施抖 (cronScheduler.ts:271-276)。CC 在调度层以 {@code cron+createdAt} 确定性重算
     * jittered fire 时间；Java 架构以 runAt 墙钟为准（工具路径 CronCreateTool:368 已在 create 时
     * 算好 jitter 后 ISO 写 runAt），故本方法对<b>未 jitter 过</b>的 REST 直建 runAt 施加同一
     * backward-lead 数学（复用 {@link CronJitter#jitterOneShotFireTime}，与工具路径同一 jitter 数学）。
     *
     * <p>判别符：{@code req.id()!=null} = 工具路径（CronCreateTool 恒传预生成 scheduleId，
     * {@code ScheduleCreateRequest.id} {@code @JsonIgnore} 保证 REST 无法注入）已 jitter → 原样跳过
     * 不双 jitter；{@code req.id()==null} = REST 原始入口 → 施抖。
     *
     * <p>t1 来源取 runAt epoch 而非 runAt→cron 往返（F1-b concerns）：往返会截秒（cron 分钟粒度）且
     * 当前分钟内/过期 runAt 会跳到下一次匹配致 fire 漂移；core 保留钉死时刻，仅对整点 mark 施加
     * 同一 lead（与 CC "人挑整半点才抖" cronTasks.ts:435 逐字等价）。
     *
     * @param kind 调度 kind（非 once → 原样返回；cron/interval 无 runAt）
     * @param id   落库 id（taskId = id 后 8 hex，与工具路径 CronCreateTool:367 同源）
     * @param req  原始请求（读 req.id() / req.runAt()）
     * @return 落库 runAt（ISO 8601）；非 once / 工具路径 / runAt 不可 parse / jitterProps 缺失 → 原样
     */
    private String resolveJitteredRunAt(ScheduleKind kind, String id, ScheduleCreateRequest req) {
        if (kind != ScheduleKind.once) {
            return req.runAt();
        }
        // 工具路径已 jitter（CronCreateTool:368 oneShotJitteredNextCronRunMs 已把 jitter 后 ISO 写 runAt）
        if (req.id() != null) {
            return req.runAt();
        }
        String raw = req.runAt();
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Instant pinned;
        try {
            pinned = OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            // 校验层只保证非空；非法 ISO 仍原样落库（Quartz parseDate 阶段再报错，行为不变）
            log.warn("[Schedule] resolveJitteredRunAt: runAt 非 ISO 8601 不可 parse，原样落库 id={} runAt={}",
                id, raw);
            return raw;
        }
        // jitterProps null（非 Spring 单测）→ DEFAULTS fail-open（对齐 QuartzScheduleService:183-185）
        CronJitterProperties props = jitterProps != null ? jitterProps : CronJitterProperties.DEFAULTS;
        String taskId = (id != null && id.length() > 8) ? id.substring(id.length() - 8) : id;
        long now = System.currentTimeMillis();
        long jittered = CronJitter.jitterOneShotFireTime(
            pinned.toEpochMilli(), now, taskId, props.toConfig());
        String result = OffsetDateTime.ofInstant(Instant.ofEpochMilli(jittered), ZoneId.systemDefault())
            .toString();
        if (log.isDebugEnabled()) {
            log.debug("[Schedule] resolveJitteredRunAt: REST 直建 once 施抖 id={} taskId={} pinned={} "
                    + "jittered={}（对齐 CC cronTasks.ts:421-445 oneShotJitteredNextCronRunMs）",
                id, taskId, raw, result);
        }
        return result;
    }

    private static void validateKindFields(ScheduleKind kind, ScheduleCreateRequest req) {
        switch (kind) {
            case cron -> {
                if (req.cron() == null || req.cron().isBlank()) {
                    throw new ValidationException("kind=cron requires 'cron' field");
                }
            }
            case once -> {
                if (req.runAt() == null || req.runAt().isBlank()) {
                    throw new ValidationException("kind=once requires 'runAt' field (ISO 8601)");
                }
            }
            case interval -> {
                if (req.intervalSeconds() == null || req.intervalSeconds() <= 0) {
                    throw new ValidationException("kind=interval requires positive 'intervalSeconds'");
                }
            }
        }
    }

    /**
     * IMPL-05（✗-C P2 读容错）: 解析记录的 kind 列 · 对齐 CC cronTasks.ts:108-137 读容错
     * （坏条目不阻塞整列表，逐条守卫 + 跳过）。非法 kind 值（仅人工干预/旧数据可达，
     * V1:118 kind NOT NULL 无 CHECK 约束）→ log.warn 并返回 null = 该行「不可见」，
     * 由调用方决定跳过（listAll filter）或 404（getById/update）。
     * kind==null（V1 NOT NULL 防御分支，实际不存在）→ 返回 null，行仍可见（kind=null DTO）。
     */
    private ScheduleKind parseKind(ScheduleRecord s) {
        if (s.getKind() == null) {
            return null;
        }
        try {
            return ScheduleKind.valueOf(s.getKind());
        } catch (IllegalArgumentException e) {
            log.warn("[Schedule] 非法 kind 脏行 id={} kind={}，跳过该行（对齐 CC cronTasks.ts:108-137 坏条目不阻塞整列表）",
                s.getId(), s.getKind());
            return null;
        }
    }

    /**
     * s14-P1-5: 带 scope/sessionId 的 toDto (create/listAll/getById 路径)。
     * CRON-D4: agentId 由 ScheduleRecord.agentId 透传（create 落库 / DB 列回填），
     * 替换旧硬编码 null（CRON-A3 登记）。
     *
     * <p>IMPL-05（✗-C P2 读容错）: 非法 kind 脏行返回 null（跳过标记）——调用方必须判空：
     * listAll filter 掉、getById/update 转 404。合法行（含 kind 列 NULL 防御分支）照常构造。
     */
    private ScheduleDto toDto(ScheduleRecord s, ScheduleScope scope, String sessionId) {
        // IMPL-05: 脏行 → null = 跳过标记；kind==null（NULL 防御分支）不误伤，仍返回 kind=null DTO
        ScheduleKind kind = parseKind(s);
        if (kind == null && s.getKind() != null) {
            return null;
        }
        return new ScheduleDto(
            s.getId(),
            s.getName(),
            kind,
            s.getCron(),
            s.getIntervalSeconds(),
            s.getRunAt(),
            s.getCommand(),
            s.getDescription(),
            parseDateTime(s.getLastRunAt()),
            s.getLastRunStatus(),
            scope,
            sessionId,
            s.getAgentId(),          // CRON-D4: CC CronTask.agentId (cronTasks.ts:69)；由 create→toDto 填充
            s.getBoundProject()      // 批次X Q2: V23 bound_project 列回填（create→toDto / DB 列回填）
        );
    }

    /**
     * CRON-B2 helper: 解析记录 scope · 优先读 DB scope 列（重启后可辨识，R-1 僵尸修复），
     * sessionJobs 仅兜底 scope 列为 NULL 的存量旧行（V8 迁移前的行）。
     *
     * @return SESSION | DURABLE（scope 列非法时按 DURABLE 处理）
     */
    private ScheduleScope lookupScope(ScheduleRecord s) {
        if (s.getScope() != null && !s.getScope().isBlank()) {
            try {
                return ScheduleScope.valueOf(s.getScope());
            } catch (IllegalArgumentException e) {
                log.warn("[Schedule] lookupScope: 非法 scope 值 id={} scope={}，按 DURABLE 处理",
                    s.getId(), s.getScope());
            }
        }
        // 旧行兜底：sessionJobs 内存索引（V8 前创建、scope 列为 NULL 的行）
        for (var entry : sessionJobs.entrySet()) {
            if (entry.getValue().contains(s.getId())) return ScheduleScope.SESSION;
        }
        return ScheduleScope.DURABLE;
    }

    /**
     * CRON-B2 helper: 解析记录 sessionId · 优先读 DB session_id 列，
     * sessionJobs 仅兜底 session_id 为 NULL 的存量旧行。
     */
    private String lookupSessionId(ScheduleRecord s) {
        if (s.getSessionId() != null && !s.getSessionId().isBlank()) {
            return s.getSessionId();
        }
        for (var entry : sessionJobs.entrySet()) {
            if (entry.getValue().contains(s.getId())) return entry.getKey();
        }
        return null;
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** CRON-B4: prompt 内最长连续反引号 run 长度（fence 长度判定，对齐 CC :558 match(/`+/g)）. */
    private static int longestBacktickRun(String s) {
        if (s == null || s.isEmpty()) return 0;
        int max = 0, cur = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '`') {
                cur++;
                if (cur > max) max = cur;
            } else {
                cur = 0;
            }
        }
        return max;
    }
}
