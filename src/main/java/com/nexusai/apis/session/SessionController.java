package com.nexusai.apis.session;

import com.nexusai.application.agent.permission.PermissionConfigProvider;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.domain.session.SessionService;
import com.nexusai.domain.stats.StatsService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Session REST 端点 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    @Autowired private SessionService sessionService;

    /**
     * [usage-gateway] 会话级用量聚合（{@code GET /{id}/usage}）· 用量明细的 owner 是 stats 域
     * （与全量统计同一数据源 {@code llm_usage_calls} 与同一聚合口径），此处只做请求映射。
     * {@code required=false}：非 Spring 单测无 bean → null，端点调用会 NPE 但不影响其他用例。
     */
    @Autowired(required = false)
    private StatsService statsService;

    /**
     * [IMP-1 R4] bypassPermissions 数据库开关登录重读 · 对齐 CC {@code /login} 后
     * {@code resetBypassPermissionsCheck()}（bypassPermissionsKillswitch.ts:53）。
     * 方案 A：数据库存开关，启动读一次 + 登录重读。会话创建 = 新会话/新账号边界，
     * 在此 refresh() 使 org 门（{@code tengu_disable_bypass_permissions_mode}）随新账号生效。
     * {@code @Autowired(required=false)}：非 Spring 单测无 bean → null → 跳过。
     */
    @Autowired(required = false)
    private PermissionConfigProvider permissionConfigProvider;

    /**
     * [WF-8 · DEL-AM-05] bypassPermissions killswitch · 对齐 CC
     * {@code bypassPermissionsKillswitch.ts:53 resetBypassPermissionsCheck()}（/login 后复位
     * run-once 旗标，使门检随新 org 重新执行）。
     * {@code @Autowired(required=false)}：非 Spring 单测无 bean → null → 跳过。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.BypassPermissionsKillswitch bypassPermissionsKillswitch;

    @GetMapping
    public List<SessionDto> list() {
        return sessionService.list();
    }

    @GetMapping("/{id}")
    public SessionDto get(@PathVariable String id) {
        return sessionService.getById(id);
    }

    /**
     * [usage-gateway] 会话级用量明细（前端「用量与花费」弹窗「用量」页「子代理（本会话）」行的
     * 权威数据源）· {@code GET /api/v1/sessions/{id}/usage}。
     *
     * <p>返回 {@code {sessionId, totals, byCategory, agents}}：{@code agents[]} 每个节点含
     * {@code agentId}（明细表身份渲染）、{@code taskId}（= 任务注册表 / task 事件的 {@code task_id}，
     * 供前端精确关联；不可换算 → null）、{@code category/parentAgentId/parentTaskId/depth/label}、
     * token 四通道、{@code costYuan}、{@code calls}（**模型调用次数**）、{@code elapsedMs}
     * （模型调用耗时求和，⛔ 不是任务墙钟时长）与估算/中断/失败计数。
     *
     * <p>空会话 / 无明细 → totals 全零 + 空列表（200，fail-soft）；sessionId 空白 → 400
     * （{@link com.nexusai.infra.exception.GlobalExceptionHandler} 把 {@code ValidationException}
     * 映射为 400，与 ops 线手写校验先例一致）。
     */
    @GetMapping("/{id}/usage")
    public StatsService.SessionUsage usage(@PathVariable String id) {
        return statsService.sessionUsage(id);
    }

    /**
     * 新建会话 · [S3 · F-03a 2026-09-14] {@code @Valid} 使 {@code mainProjectId} 的
     * {@code @NotBlank} 生效（空白 ⇒ {@code MethodArgumentNotValidException} ⇒ 400 + {@code errors[]}）。
     *
     * <p>⚠️ {@code @Valid} <b>挡得住 HTTP 请求</b>（curl 走的正是本方法），但<b>挡不住域内直构</b>
     * （{@code SessionServiceTest} 直接 {@code new SessionCreateRequest(...)} 调
     * {@link SessionService#create}）—— 后者由 SessionService 内的档二守卫兜住。两者缺一不可。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto create(@Valid @RequestBody SessionCreateRequest req) {
        // [IMP-1 R4] 会话创建（新账号/新会话边界）→ 重读 bypassPermissions 开关（对齐 CC /login 后
        // resetBypassPermissionsCheck 语义）。provider 未注入（单测）→ 跳过。
        if (permissionConfigProvider != null) {
            permissionConfigProvider.refresh();
        }
        // [WF-8 · DEL-AM-05] /login（会话创建边界）后复位 killswitch run-once 旗标 · 对齐 CC
        // bypassPermissionsKillswitch.ts:53 resetBypassPermissionsCheck()（login.tsx:43-50），
        // 使 LlmAgentLoop 的 checkAndDisableBypassPermissionsIfNeeded 随新 org 重新执行门检。
        // 与上方 permissionConfigProvider.refresh()（重读 DB org 门值）互补：二者缺一不可——
        // 仅复位旗标 → 门检用陈旧门值；仅重读门值 → run-once 旗标仍阻断重检。
        // （原 IMP-MV2-40 登记"机制不同待修"的 △-3 已由本次合入的 permissions_v3 补齐。）
        if (bypassPermissionsKillswitch != null) {
            bypassPermissionsKillswitch.resetBypassPermissionsCheck();
        }
        return sessionService.create(req);
    }

    @PatchMapping("/{id}")
    public SessionDto update(@PathVariable String id,
                             @RequestBody SessionUpdateRequest req) {
        return sessionService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        sessionService.delete(id);
    }
}
