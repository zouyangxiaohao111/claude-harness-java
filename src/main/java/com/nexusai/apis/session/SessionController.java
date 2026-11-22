package com.nexusai.apis.session;

import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.application.agent.permission.PermissionConfigProvider;
import com.nexusai.application.session.SessionForkService;
import com.nexusai.infra.exception.ForbiddenException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.session.dto.ForkSessionRequest;
import com.nexusai.model.session.dto.ForkSessionResponse;
import com.nexusai.model.session.dto.PlanExitRequest;
import com.nexusai.model.session.dto.PlanExitResponse;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.SessionPermissionOverlay;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.domain.session.SessionService;
import com.nexusai.domain.stats.StatsService;
import com.zjky.pro.framework.common.pojo.CommonResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Session REST 端点 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    @Autowired private SessionService sessionService;
    @Autowired private SessionForkService sessionForkService;

    /** [plan-approval] 会话权限列写入（V75 SESSION setMode）。非 Spring 单测为 null。 */
    @Autowired(required = false)
    private SessionMapper sessionMapper;

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

    /**
     * 分叉会话 · 从某条消息所在轮末截断复制出一个新会话。
     *
     * <p>入参 {@code throughMessageId} = 该轮最后一条消息（前端取轮末行）；后端按
     * {@code messages.seq <= pivotSeq} 复制。标题追加 {@code " 分叉"}，自动跳转由前端完成。
     *
     * @param id  源会话 ID
     * @param req 分叉点（该轮最后一条消息）
     * @return 新会话 + 复制的消息条数（201）
     */
    @PostMapping("/{id}/fork")
    @ResponseStatus(HttpStatus.CREATED)
    public ForkSessionResponse fork(@PathVariable String id,
                                    @Valid @RequestBody ForkSessionRequest req) {
        return sessionForkService.fork(id, req.throughMessageId());
    }

    /**
     * [plan-approval] 计划审批「退出并执行」· 对齐 CC ExitPlanMode 审批后的两条写入：
     * 恢复会话权限模式；若 clearContext=true 则另建会话在干净上下文里执行。
     *
     * <p>为什么需要端点而不是前端两次调用：{@code session_permission_rules}(V75) 的
     * SESSION setMode 在 per-turn 合并中优先于 {@code permission_mode} 列，前端 PATCH 列
     * 清不掉它 —— 不在这里改，原会话下一条消息会再次进入 plan。
     *
     * <p>本端点<b>不发送消息、不调用模型</b>：新会话创建后由前端沿用既有发送链路发出
     * 「Implement the following plan」。
     *
     * @param id  正在退出 plan 的会话
     * @param req 计划正文 + 执行方式 + 是否清空上下文
     * @return 继续执行的目标会话（外层统一 CommonResult；成功 code=0/success=true，data=原响应）
     */
    @PostMapping("/{id}/plan/exit")
    public ResponseEntity<CommonResult<PlanExitResponse>> exitPlan(@PathVariable String id,
                                                                   @RequestBody PlanExitRequest req) {
        // 统一返回体：成功 200 + {code:0,success:true,data:{sessionId,cleared}}；参数错误 400、
        // 提权被拒 403、会话不存在 404 时 HTTP 状态保持语义，body 同样是 CommonResult
        // （⛔ 只有本方法包；本控制器其它方法的成功体与错误体不动）。
        try {
            return ResponseEntity.ok(CommonResult.ok(exitPlanChecked(id, req)));
        } catch (ValidationException ex) {
            return ResponseEntity.badRequest().body(CommonResult.error(400, ex.getMessage()));
        } catch (ForbiddenException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CommonResult.error(403, ex.getMessage()));
        } catch (NotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(CommonResult.error(404, ex.getMessage()));
        }
    }

    /** {@link #exitPlan} 的业务主体（校验 + 写 SESSION setMode + 可选建新会话）。 */
    private PlanExitResponse exitPlanChecked(String id, PlanExitRequest req) {
        if (req == null || req.mode() == null || req.mode().isBlank()) {
            throw new ValidationException("mode is required");
        }
        // 显式校验原串：PermissionMode.fromString 对未知串折叠成 DEFAULT（会静默放行错值）
        if (!java.util.Set.of("acceptEdits", "default", "bypassPermissions", "dontAsk", "auto")
                .contains(req.mode())) {
            throw new ValidationException("mode must be one of: acceptEdits, default, bypassPermissions, dontAsk, auto");
        }
        // [缺陷2 · 2026-11-20 E2E 第二轮] 会话必须存在：不存在的会话 → NotFoundException → HTTP 404 +
        // CommonResult{success:false, code:404}，且必须在写任何权限规则之前失败（⛔ 不得对幽灵会话
        // 静默成功、也不得给不存在的 session_id 落 SESSION setMode 行）。
        SessionDto src = sessionService.getById(id);
        // ── [C批 · 跨会话安全] 本端点可直写 SESSION setMode（含 bypassPermissions/dontAsk/auto）──
        //   它没有模型参与，但本地任意进程（包括 peer 回合里的 Bash/curl）都能发同样的 HTTP 请求。
        //   ⚠️ [语义重排] 默认门不是「未配置可信 UI ⇒ 全部 403」——默认已安装 local-user
        //   验证器（userId 缺省 "default"），正常用户（origin=null）零变化。只有两类请求被拒：
        //     ① 服务端可归因为 peer / 非可信主体（PeerUiApprovalAuthority.Attributor 判定）；
        //     ② 未来安装更严验证器后，提权模式必须携带可验证的 UI 凭据（未通过 → 拒）。
        //   ⛔ 这里不把「本地 HTTP 请求」本身当成 peer 自证，也不伪造 token 校验。
        boolean privilegedMode = java.util.Set.of("bypassPermissions", "dontAsk", "auto")
                .contains(req.mode());
        boolean peerAttributed = PeerUiApprovalAuthority.isPeerAttributed(
            PeerUiApprovalAuthority.Source.HTTP_PLAN_EXIT, id, null);
        boolean requiresVerifiedUi = PeerUiApprovalAuthority.isConfigured()
            && !PeerUiApprovalAuthority.isTrustedUi(
                PeerUiApprovalAuthority.Source.HTTP_PLAN_EXIT, id, null);
        if (privilegedMode && (peerAttributed || requiresVerifiedUi)) {
            log.warn("[SessionController] plan/exit 拒绝提权模式写入：{} session={} user={} "
                    + "peerAttributed={} trustedUiMissing={}", req.mode(), id,
                PeerUiApprovalAuthority.getUserId(), peerAttributed, requiresVerifiedUi);
            throw new ForbiddenException(
                "Switching a session to '" + req.mode() + "' from this endpoint is not allowed for this "
                    + "request: peer-attributed escalation requests are refused, and a stricter UI "
                    + "verification (if installed) must pass. Use the desktop UI instead.");
        }
        PermissionMode mode = PermissionMode.fromString(req.mode());
        // 源会话：恢复执行模式（V75 SESSION setMode）
        persistPlanExitMode(id, mode);

        if (!Boolean.TRUE.equals(req.clearContext())) {
            return new PlanExitResponse(id, false);
        }
        if (src == null || src.mainProjectId() == null || src.mainProjectId().isBlank()) {
            throw new ValidationException("session has no bound project");
        }
        SessionDto created = sessionService.create(new SessionCreateRequest(
            planTitle(req.plan(), src.title()), src.model(), src.modelName(),
            src.mainProjectId(), null));
        // 新会话同样以执行模式启动，避免沿用全局 settings 的 plan
        persistPlanExitMode(created.id(), mode);
        return new PlanExitResponse(created.id(), true);
    }

    /** 写目标会话的 SESSION setMode；mapper 缺失时显式告警（不静默假装成功）。 */
    private void persistPlanExitMode(String sessionId, PermissionMode mode) {
        if (sessionMapper == null) {
            throw new ValidationException("session permission store unavailable");
        }
        SessionPermissionOverlay.persistSessionUpdates(sessionMapper, sessionId,
            List.of(new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, mode)),
            "plan_exit");
    }

    /** 新会话标题：计划第一个非空行（去 Markdown 标记），截断 40 字符；无计划用「计划执行」。 */
    private static String planTitle(String plan, String fallback) {
        if (plan != null) {
            for (String line : plan.split("\\R")) {
                String cleaned = line.replaceAll("^[#>*\\-\\s]+", "").trim();
                if (!cleaned.isEmpty()) {
                    return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
                }
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback + " · 执行计划" : "计划执行";
    }

    /**
     * 更新会话 · {@code PATCH}（本仓原生）与 {@code PUT}（源侧 WeKnora 用这个动词）同实现。
     *
     * <p>⚠️ 语义是<b>部分更新</b>：{@link SessionUpdateRequest} 全字段可选，未传即不改。
     * 源侧 PUT 收的是完整 Session 对象，但 service 层同样只覆盖传入字段
     * ⇒ 两种动词在本仓行为一致，⛔ 不改整体替换（那会静默清空未传字段）。
     *
     * @param id  会话 ID
     * @param req 更新请求（全字段可选）
     * @return 更新后的会话
     */
    @RequestMapping(value = "/{id}",
                    method = {RequestMethod.PATCH, RequestMethod.PUT})
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
