package com.nexusai.apis.schedule;

import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.exception.UnresolvedProjectRootException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.schedule.dto.RunNowResponse;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.ScheduleUpdateRequest;
import com.nexusai.domain.schedule.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Schedule REST 端点（Java 独有产品面 · OPD-Cron-09-8 拍板保留，open-decisions.md:95）。
 *
 * <p><b>① Java 独有面声明</b>：CC 桌面端 cron 面 = 三工具 + hook，全仓无 REST 面——
 * {@code CronCreate/CronDelete/CronList} 三工具（isEnabled 门控，CronCreateTool.ts:67-69
 * {@code isEnabled() { return isKairosCronEnabled() }}）+ {@code useScheduledTasks} hook
 * （useScheduledTasks.ts:61 launch 守卫 {@code if (!isKairosCronEnabled()) return} +
 * cronScheduler.ts:231 isKilled 每 tick 门 {@code if (isKilled?.()) return}）。CC 全仓
 * 无 schedule REST 路由（src/server 无任何 schedules 路由，仅 OAuth/MCP 回调 server）；
 * 本控制器为 OPD-Cron-09-8 拍板保留的 Java 独有 REST 产品面（前端查看/执行定时任务）。
 *
 * <p><b>② NEW-13 门控差异（有意差异，拍板保持现状）</b>：本类无 {@code CronEnabledGates}
 * 引用、无 {@code @ConditionalOnProperty}——POST create 直通
 * {@link com.nexusai.domain.schedule.ScheduleService#create(ScheduleCreateRequest, String)}
 * （仅 MAX_JOBS 校验/字段校验/落库/Quartz 注册，无功能门）；对比工具路径
 * {@code CronCreateTool.isEnabled()}（CronCreateTool.java:162-164，对齐 CC :67-69）受
 * {@code nexusai.feature.agent-trigger-cron} 门控（CronEnabledGates.java:22/:76-78）。
 * <b>拍板（NEW-13, 2026-08-15 IMPL-13）</b>：REST create 不加门控，保持现状；无 CC 基准
 * （Java 独有 REST 面），如需门控由后续拍板（open-decisions.md NEW-13 行）。
 *
 * <p><b>③ 鉴权 ≠ 功能门控</b>：{@code BearerTokenAuthFilter} 双 pattern fail-closed
 * （BearerTokenAuthFilterConfig.java:62-63 注册 {@code /api/v1/schedules} +
 * {@code /api/v1/schedules/*} 双 pattern；:42 {@code nexusai.security.require-oauth-auth}
 * 默认 true=deny-all，对齐 CC auth.ts:1960）是账号级 OAuth 鉴权，与功能开关
 * （agent-trigger-cron）无关——鉴权恒生效，不构成功能门控。
 *
 * <p><b>④ 唯一受功能门控的 REST 子路径</b>：POST {@code /{id}/run} → runNow →
 * {@code TestJob.fire} 内部 gate（TestJob.java:128，对齐 CC cronScheduler.ts:231 isKilled
 * 每 tick），门关返回 false 不执行。另注：create 超 MAX_JOBS 返回 409 + errorCode3
 * （决策#13，ScheduleService.java:132-144），与工具路径 validateInput errorCode3
 * （CronCreateTool.ts:97-104）语义一致。
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ScheduleController.class);

    @Autowired private ScheduleService scheduleService;

    @GetMapping
    public List<ScheduleDto> list() {
        return scheduleService.listAll();
    }

    @GetMapping("/{id}")
    public ScheduleDto get(@PathVariable String id) {
        return scheduleService.getById(id);
    }

    /**
     * POST /api/v1/schedules · 创建。
     *
     * <p><b>[cwd3 · 用户裁定 2026-09-15 步骤 1a] REST 直建 DURABLE 必须带 sessionId，且必须能从它
     * 解析出 boundProject</b>（用户原话：「REST 直建 DURABLE 时 必须带上 sessionId 没有 id 不允许
     * 建立」+「有 sessionId ⇒ 必须能从它解析出 boundProject，解析不到 ⇒ 400」）。
     *
     * <p><b>三段判据（全在 REST 边界，⛔ 不下沉进 {@link SessionProjectRoot}/{@code CwdResolution}）</b>：
     * <ol>
     *   <li><b>(a) 缺 id</b>：{@code DURABLE} 且 {@code sessionId} 为 null/空白 ⇒
     *       {@link ValidationException} ⇒ 400 {@code Validation Failed}；</li>
     *   <li><b>(b) 假 id</b>：{@code DURABLE} 且 {@code sessionId == SessionKeys.NO_SESSION} ⇒ 同抛
     *       —— 裁定说的是「必须带 id」，<b>哨兵不是一个 id</b>，否则「显式传哨兵」即可绕过 (a)；</li>
     *   <li><b>(c) 解析不到</b>：{@code DURABLE} 且 id 非空非哨兵 ⇒
     *       {@link SessionProjectRoot#lookup(String)} 必须给出绑定项目根，否则
     *       {@link UnresolvedProjectRootException} ⇒ 400 {@code Unresolved Project Root}。
     *       ⚠️ 用 {@code lookup} 而非 {@code getForSession}（后者把「绑定 / 未绑定 / 无此会话 /
     *       解析失败」四态压成 null ⇒ 无法给出可辨识错误文案）；也⛔ 不用
     *       {@code CwdResolution.getCwd}（它还会读 sessionCwd 层，而本处要的是<b>启动锚</b>）。</li>
     * </ol>
     *
     * <p><b>⭐ 防伪造 boundProject</b>：判据 (c) 通过后用 {@code lk.projectRoot()} <b>重建请求体</b>
     * 再交给 service ⇒ 客户端在请求体里塞的 {@code boundProject} 一律被<b>解析值覆盖</b>
     * （原实现把 {@code req.boundProject()} 直接落库 ⇒ 调用方可把任意路径写成任务的项目锚）。
     *
     * <p><b>[P11a] 已解析锚再以形参显式下传</b>：重建请求体之外，本方法还把<b>同一个</b>
     * {@code lk.projectRoot()} 作为 {@code ScheduleService#create(req, resolvedProjectRoot)} 的
     * 第二形参传入。⛔ 这不是「把请求字段交回来」——形参承载的是<b>本方法刚解析出的服务端值</b>；
     * 目的是消除同一条链上的<b>重复解析</b>（收口前 service 对同一 sessionId 又 lookup 一次并丢弃
     * 这里的值 = 同一事实两个来源）。守护：
     * {@code ScheduleCreateAnchorSingleResolutionTest}（lookup 只被调用一次）。
     *
     * <p><b>为什么落点在 Controller 而不是 service</b>：用户裁定原文限定「REST 直建」；且工具路径
     * （{@code CronCreateTool}）与 8 处直调 service 的单测各有自己的语义（工具路径无 HTTP ⇒ 用
     * {@link SessionKeys#NO_SESSION} 哨兵声明无会话），把 REST 的 400 语义塞进 service 会让两边
     * 互相污染。service 侧另做<b>sentinel-aware 归一取值</b>（见
     * {@link com.nexusai.domain.schedule.ScheduleService#create(ScheduleCreateRequest, String)}）
     * 保证「锚只能来自 sessionId 解析（上游下传的已解析值，或本类自解析）」。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto create(@Valid @RequestBody ScheduleCreateRequest req) {
        // [acc8 · 用户裁定 · 对齐 CC] 缺省 scope 从 DURABLE 改为 **SESSION**（CC cron 默认 session-only，
        //   见 ScheduleService.create 内注释的 CC 真源行号）。⚠️ **必须与 ScheduleService.create 同改**
        //   —— 否则本处判 DURABLE、下游判 SESSION，「同一能力两套判据」。
        ScheduleScope scope = req.scope() == null ? ScheduleScope.SESSION : req.scope();
        if (scope != ScheduleScope.DURABLE) {
            return scheduleService.create(req);
        }
        String sessionId = req.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[ScheduleController] POST /schedules 拒绝：DURABLE 缺 sessionId（用户裁定 1a：没有 id 不允许建立）");
            throw new ValidationException(
                "scope=DURABLE requires non-empty 'sessionId' (REST create)");
        }
        if (SessionKeys.isNoSession(sessionId)) {
            log.warn("[ScheduleController] POST /schedules 拒绝：DURABLE 的 sessionId 为「确无会话」哨兵 {} "
                + "（哨兵不是会话 id，不得用于直建任务）", SessionKeys.NO_SESSION);
            throw new ValidationException(
                "scope=DURABLE requires a real sessionId, not the no-session sentinel");
        }
        SessionProjectRoot.Lookup lk = SessionProjectRoot.lookup(sessionId);
        if (lk.resolutionFailed() || lk.projectRoot() == null || lk.projectRoot().isBlank()) {
            log.warn("[ScheduleController] POST /schedules 拒绝：DURABLE sessionId={} 解析不到绑定项目根 "
                + "(sessionKnown={} resolutionFailed={})", sessionId, lk.sessionKnown(), lk.resolutionFailed());
            throw new UnresolvedProjectRootException(
                "scope=DURABLE: sessionId=" + sessionId + " 解析不到绑定项目根（sessionKnown="
                    + lk.sessionKnown() + " resolutionFailed=" + lk.resolutionFailed()
                    + "）—— 请确认该会话存在且已绑定项目（sessions.main_project_id → projects.path）");
        }
        // 用解析值重建请求（只换 boundProject）⇒ 客户端伪造的 boundProject 被覆盖
        ScheduleCreateRequest normalized = new ScheduleCreateRequest(
            req.name(), req.kind(), req.cron(), req.intervalSeconds(), req.runAt(), req.command(),
            req.description(), scope, sessionId, req.agentId(), lk.projectRoot(), req.id());
        if (log.isInfoEnabled()) {
            log.info("[ScheduleController] POST /schedules DURABLE 会话锚已由 sessionId 解析并覆盖请求体: "
                + "sessionId={} boundProject={}（客户端传入的 boundProject={} 被丢弃）",
                sessionId, lk.projectRoot(), req.boundProject());
        }
        // [P11a] 把**本方法刚解析出的锚**显式下传（第二形参）⇒ service 不再对同一 sessionId
        //   重复解析（收口前 service 又查一遍并丢弃这里的值 = 同链两个来源）。
        //   ⛔ 传的是 lk.projectRoot()（服务端解析结果），**不是** req.boundProject()（客户端字段）。
        return scheduleService.create(normalized, lk.projectRoot());
    }

    /**
     * POST /{id} 部分更新 · 对齐 CC RemoteTriggerTool.ts:120-126 {@code update=POST base/{trigger_id}}
     * （FIX-3 / RV-C-03 G3/G4）。部分更新（全字段可选，只改非 null 字段），id 走路径变量。
     */
    @PostMapping("/{id}")
    public ScheduleDto update(@PathVariable String id, @RequestBody ScheduleUpdateRequest req) {
        return scheduleService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        scheduleService.delete(id);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<RunNowResponse> run(@PathVariable String id) {
        // CRON-B4-4（决策 #15 / OPD-EL-04）：REST runNow 同步返回 fire-then-delete 结果（200 + 结果体），
        // 与工具路径 one-shot 语义一致（CronCreateTool.ts:152 "fire once then auto-delete"）。
        // 旧实现返回 202 ACCEPTED（异步、无删除结果），偏离 CC 语义（已移除）。
        return ResponseEntity.ok(scheduleService.runNow(id));
    }
}
