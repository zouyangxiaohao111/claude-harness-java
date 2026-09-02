package com.nexusai.apis.schedule;

import com.nexusai.model.schedule.dto.RunNowResponse;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
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
 * {@link com.nexusai.domain.schedule.ScheduleService#create}（ScheduleService.java:131-224，
 * 仅 MAX_JOBS 校验/字段校验/落库/Quartz 注册，无功能门）；对比工具路径
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

    @Autowired private ScheduleService scheduleService;

    @GetMapping
    public List<ScheduleDto> list() {
        return scheduleService.listAll();
    }

    @GetMapping("/{id}")
    public ScheduleDto get(@PathVariable String id) {
        return scheduleService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto create(@Valid @RequestBody ScheduleCreateRequest req) {
        return scheduleService.create(req);
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
