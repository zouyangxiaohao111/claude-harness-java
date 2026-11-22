package com.nexusai.apis.session;

import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.infra.exception.ForbiddenException;
import com.zjky.pro.framework.common.pojo.CommonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 跨会话待收件（held）审批端点 · C 批安全适配（依赖 A 的
 * {@link PeerMessageRouter#listHeld}/{@link PeerMessageRouter#approveHeld}/
 * {@link PeerMessageRouter#rejectHeld}）。
 *
 * <h2>安全模型</h2>
 * <ul>
 *   <li><b>主体归属</b>：{@link PeerMessageRouter.ApprovalPrincipal#sessionId()} 取<b>请求路径</b>
 *       的会话 id（服务端路由值），⛔ 不取 body 自报；router 侧还会再比对 held 项的
 *       {@code targetSessionId}，错归属一律不 remove、不 approve。</li>
 *   <li><b>可信 UI</b>：默认安装 local-user 验证器，{@link PeerUiApprovalAuthority#getUserId()}
 *       缺省返回 {@code "default"}，审批主体 = {@code sessionId + requestId + userId}。
 *       当前桌面部署下本端点正常工作（列表 / 接受一次 / 拒绝）；后期接真实认证只需替换验证器。
 *       ⛔ 不因为「真实认证尚未接入」就 403；也不把本机 HTTP 请求本身当成「peer 可以自证」。</li>
 *   <li><b>不可解 pending</b>：本端点只操作 held 消息，与权限审批 pending 队列分离。</li>
 *   <li>未实现「一次接受 = 永久规则」：批准只投递该条消息，权限规则/配置零写入。</li>
 * </ul>
 *
 * <h2>响应契约（2026-11-20 主人裁定：统一 CommonResult + 数字码）</h2>
 * <p>本控制器全部 JSON 响应统一用 {@link CommonResult} 包一层：
 * <pre>{ "code": 0, "msg": "成功", "data": {...}, "success": true }</pre>
 * 业务失败仍返回 <b>HTTP 200</b>（用 {@code success=false} + 数字 {@code code} 表达），前端只按
 * {@code success} 判断成败。映射（⛔ 固定，前端已按此实现）：
 * <ul>
 *   <li>{@code ACCEPTED} → 0；{@code HELD} → 423；{@code NO_LIVE_SESSION} → 404；
 *       {@code REFUSED} → 403；{@code NOT_DELIVERED} → 500；{@code NOT_FOUND} → 404；
 *       {@code APPROVAL_REJECTED} → 900；其它投递状态兜底 500（{@code data.code} 保留字符串码）。</li>
 *   <li>{@code GET /peer-inbox}：0 + data 数组（pending / <b>rejected</b>；⛔ 不含 accepted/expired
 *       终态，也⛔ 不再返回 approved —— 严格 CC 语义下批准即结算，不存在已批准待投递的行）。
 *       已拒绝行只用于展示（前端按 status 渲染为不可操作卡片），
 *       ⛔ 不参与 pending 计数（前端按 {@code status==='pending'} 统计，后端不提供 total/unread 字段）。</li>
 *   <li>{@code POST /seen}：0 + data {@code {updated:N}}。</li>
 *   <li>{@code POST /reject-all}：0 + data {@code {code:"REJECTED", rejected:N}}（无 pending → N=0）。</li>
 *   <li>只有在「审批主体不可信 / 端点未接线」这类鉴权与装配问题上，HTTP 状态才非 200：
 *       <b>403 + 同一个 CommonResult 体</b>（{@code code=403}）；请求体无法解析 → 400 + CommonResult。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions/{id}/peer-inbox")
public class PeerInboxController {

    private static final Logger log = LoggerFactory.getLogger(PeerInboxController.class);

    /** A 的路由器（生产 @Component）。required=false 容错（测试直构不走 Spring）。 */
    @Autowired(required = false)
    private PeerMessageRouter router;

    /** 测试/接线用 setter。 */
    public void setRouter(PeerMessageRouter router) {
        this.router = router;
    }

    /**
     * 待收件视图（只读投影；body 是不可信文本，前端按纯文本展示）。
     *
     * <p>status = {@code pending}（未处理）/ {@code rejected}（用户已拒绝，仅展示、不可操作）。
     * ⛔ 不返回 {@code approved}（批准即结算：成功直接 accepted、失败直接 expired，都不在列表里）；
     * {@code expired} 也不在列表里（非人工决定的静默终结，发送方另有落库失败通知）。
     * seenAt = null 表示未读（重启后不重复提醒的依据）。
     * admission = auto / approved（null = 未准入/未知，pending 行恒 null）；
     * admissionReason = mode-match / self-sent / user-approved / explicit-accept（可 null）。
     * fromMode = null/空 表示<b>发送方未声明模式</b>（⛔ 不补成 default）。
     * bodyLines / bodyChars = 正文总行数 / 总字数（预览规模；正文仍整段返回，前端可自行截断）。
     */
    public record HeldView(String messageId, String targetSessionId, String fromSessionId,
                           String fromName, String fromAddress, String fromMode,
                           String summary, String reason, Instant heldAt, String body,
                           String sourceTitle, String sourceKind, String status, Instant seenAt,
                           String admission, String admissionReason,
                           int bodyLines, int bodyChars) {}

    /**
     * 接受/拒绝结果（HTTP 恒 200，业务结果由 {@code code} 表达）。
     *
     * @param code      业务码（闭集）：{@code ACCEPTED} / {@code REJECTED} / {@code HELD} /
     *                  {@code REFUSED} / {@code NO_LIVE_SESSION} / {@code SOURCE_NOT_LIVE} /
     *                  {@code NOT_DELIVERED} / {@code NOT_WIRED} / {@code APPROVAL_REJECTED} /
     *                  {@code NOT_FOUND}；前端<b>只按 code 判断</b>，仅 ACCEPTED / REJECTED 视为达成
     * @param messageId 消息 id
     * @param status    同 code 的状态名（历史字段，前端已用）
     * @param detail    一句可读原因
     */
    public record HeldActionResult(String code, String messageId, String status, String detail) {}

    /** 标记已读请求；messageIds 缺省/空数组 = 该会话全部未读 pending。 */
    public record SeenRequest(List<String> messageIds) {}

    /** 标记已读结果（幂等：第二次同参返回 0）。 */
    public record SeenResult(int updated) {}

    /** 批量拒绝结果（无待处理项 → 0，不报错；HTTP 恒 200）。 */
    public record RejectAllResult(String code, int rejected) {}

    /** 只读列出本会话的 held 项（错归属/更严验证器拒绝 → 403 + CommonResult，不泄漏他人条目）。 */
    @GetMapping
    public CommonResult<List<HeldView>> list(@PathVariable String id) {
        PeerMessageRouter.ApprovalPrincipal principal = principal(id, null);
        if (!principal.trustedUi()) {
            log.warn("PEER INBOX list 拒绝：审批主体不可信 session={} user={}",
                id, PeerUiApprovalAuthority.getUserId());
            throw new ForbiddenException("Listing cross-session held messages requires a trusted UI "
                + "authentication that is not configured in this desktop build.");
        }
        if (router == null) {
            throw new ForbiddenException("peer routing is not wired");
        }
        return CommonResult.ok(router.listHeldViews(id, principal).stream()
            .map(PeerInboxController::toView).toList());
    }

    /**
     * 标记已读（幂等；不推事件，前端本地面板已展开/本地置已读即可）。
     *
     * <p>归属：路径 id 是唯一真源（request body 不参与归属判定）；WHERE 里带
     * target_session_id ⇒ 传别人的 messageIds 不会改到他人行。
     */
    @PostMapping("/seen")
    public CommonResult<SeenResult> seen(@PathVariable String id,
                                        @RequestBody(required = false) SeenRequest body) {
        PeerMessageRouter.ApprovalPrincipal principal = principal(id, null);
        requireTrusted(principal, id, null, "seen");
        List<String> ids = body == null ? null : body.messageIds();
        int updated = router.markSeen(id, ids, principal);
        log.info("PEER INBOX seen: session={} requested={} updated={} user={}",
            id, ids == null || ids.isEmpty() ? "all" : ids.size(),
            updated, PeerUiApprovalAuthority.getUserId());
        return CommonResult.ok(new SeenResult(updated));
    }

    /**
     * 接受一条 held 消息（先归属 + 可信主体，再做投递）。
     *
     * <p><b>契约（2026-11-20 主人裁定：统一 CommonResult + 数字码）</b>：HTTP <b>恒 200</b>，
     * 结果由 {@link CommonResult} 表达 —— {@code success=true/code=0} 才是已投递；业务失败用
     * {@code success=false} + 数字 {@code code}（另在 {@code data.code} 保留字符串状态名）。
     * ⛔ 不用 409 之类状态码代替业务语义。
     * <ul>
     *   <li>{@code ACCEPTED} → 0（前端移除该行、pending-1）；</li>
     *   <li>{@code HELD} → 423；{@code REFUSED} → 403；{@code NO_LIVE_SESSION} → 404；
     *       {@code NOT_DELIVERED} → 500；{@code APPROVAL_REJECTED} → 900：均未达成。</li>
     * </ul>
     *
     * <p><b>批准即结算（严格 CC 语义）</b>：批准是一次性动作 —— 成功直接 accepted；失败
     * （refused / 目标会话关闭 / 投递 dropped）行直接终态（expired）+ 发送方收到失败通知，
     * <b>行的 pending 已消失、不可再次批准</b>（前端只需移除该行并展示原因，
     * 不需要「重试」入口）。{@code APPROVAL_REJECTED} 只表示「行不存在 / 已被处理」（重复点击）。
     */
    @PostMapping("/{messageId}/approve")
    public CommonResult<HeldActionResult> approve(@PathVariable String id,
                                                  @PathVariable String messageId) {
        PeerMessageRouter.ApprovalPrincipal principal = principal(id, messageId);
        requireTrusted(principal, id, messageId, "approve");
        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal);
        if (receipt.status() != PeerMessageRouter.Status.ACCEPTED) {
            log.warn("PEER INBOX approve 未达成：session={} messageId={} status={} detail={}",
                id, messageId, receipt.status(), receipt.detail());
        } else {
            log.info("PEER INBOX approve: session={} messageId={} user={} status={}",
                id, messageId, PeerUiApprovalAuthority.getUserId(), receipt.status());
        }
        return approveEnvelope(receipt.status(), messageId, receipt.detail());
    }

    /**
     * 批量拒绝本会话的<b>全部</b> pending 待收件（用户已裁定允许批量拒绝，前端做二次确认）。
     *
     * <p><b>归属前置</b>：路径 {@code id} 是唯一归属真源，且服务端准入必须可信（不可信 → 403，
     * 不执行任何写）；主体会话与路径不一致时 router 返回 0。⛔ 不跨会话。
     * <b>幂等</b>：无待处理项返回 {@code {code:"REJECTED", rejected:0}}，不报错；再次调用同样 0。
     * <b>语义</b>：拒绝是终态，不可恢复，不写会话历史、不唤醒目标；不新增「稍后/忽略」状态。
     */
    @PostMapping("/reject-all")
    public CommonResult<RejectAllResult> rejectAll(@PathVariable String id) {
        PeerMessageRouter.ApprovalPrincipal principal = principal(id, null);
        requireTrusted(principal, id, null, "reject-all");
        int rejected = router.rejectAll(id, principal);
        log.info("PEER INBOX reject-all: session={} rejected={} user={}",
            id, rejected, PeerUiApprovalAuthority.getUserId());
        return CommonResult.ok(new RejectAllResult("REJECTED", rejected));
    }

    /**
     * 拒绝（丢弃）一条 held 消息；错归属不删除。
     *
     * <p>统一 CommonResult：成功 {@code code=0 / success=true / data.status=REJECTED}；
     * 不存在/已被处理/错归属 {@code code=404 / success=false / data.code=NOT_FOUND}
     * （HTTP 仍 200 —— 业务失败不是传输失败）。
     */
    @PostMapping("/{messageId}/reject")
    public CommonResult<HeldActionResult> reject(@PathVariable String id,
                                                 @PathVariable String messageId) {
        PeerMessageRouter.ApprovalPrincipal principal = principal(id, messageId);
        requireTrusted(principal, id, messageId, "reject");
        boolean removed = router.rejectHeld(messageId, principal);
        if (!removed) {
            log.warn("PEER INBOX reject 未达成：不存在/已被处理/错归属 session={} messageId={}",
                id, messageId);
            return CommonResult.error(404, "消息不存在",
                new HeldActionResult("NOT_FOUND", messageId, "NOT_FOUND",
                    "no pending held message with this id for the requesting session"));
        }
        log.info("PEER INBOX reject: session={} messageId={} user={}",
            id, messageId, PeerUiApprovalAuthority.getUserId());
        return CommonResult.ok(new HeldActionResult("REJECTED", messageId, "REJECTED",
            "held message removed"));
    }

    // ───────────────────────────── envelope mapping ─────────────────────────────

    /**
     * 审批结果 → 统一返回体（数字码固定，前端按 {@code success}/{@code code} 判断；
     * {@code data.code} 保留字符串状态名与 messageId/status/detail）。
     *
     * <p>包级可见以便非 Spring 单测直接钉死码表（router 的显式批准路径不再产生 HELD，
     * 该分支仍须保持映射，避免以后策略回退时前端判读漂移）。
     */
    static CommonResult<HeldActionResult> approveEnvelope(PeerMessageRouter.Status status,
                                                          String messageId, String detail) {
        HeldActionResult data = new HeldActionResult(status.name(), messageId, status.name(), detail);
        return switch (status) {
            case ACCEPTED -> CommonResult.ok(data);
            case HELD -> CommonResult.error(423, "消息仍待确认，目标会话稍后处理", data);
            case NO_LIVE_SESSION -> CommonResult.error(404, "目标会话不在线", data);
            case REFUSED -> CommonResult.error(403, "入站策略已拒绝", data);
            case NOT_DELIVERED -> CommonResult.error(500, "未送达", data);
            case APPROVAL_REJECTED -> CommonResult.error(900, "该消息已被处理或归属不符", data);
            default -> CommonResult.error(500, "未送达", data);
        };
    }

    /**
     * 审批主体不可信 / 端点未接线 ⇒ <b>HTTP 403，body 仍是 CommonResult</b>（{@code code=403}）。
     *
     * <p>只作用于本控制器（共 5 个端点），不影响其它控制器的 RFC 7807 错误体。
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<CommonResult<Void>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(CommonResult.error(403, ex.getMessage()));
    }

    /** 请求体 JSON 无法解析 ⇒ HTTP 400 + CommonResult（保持状态语义）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
            .body(CommonResult.error(400, "请求体解析失败：" + ex.getMostSpecificCause().getMessage()));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /**
     * 审批主体：sessionId = 路径值；userId = 默认本地用户（后期可换真实认证）；
     * trustedUi = 服务端准入结论（默认 local-user ⇒ true）。
     */
    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId, String messageId) {
        PeerUiApprovalAuthority.Admission admission =
            PeerUiApprovalAuthority.admit(
                PeerUiApprovalAuthority.Source.HTTP_PEER_INBOX, sessionId, messageId);
        return new Principal(sessionId, messageId, admission.trusted(), admission.userId());
    }

    private void requireTrusted(PeerMessageRouter.ApprovalPrincipal principal, String sessionId,
                                String messageId, String action) {
        if (!principal.trustedUi()) {
            log.warn("PEER INBOX {} 拒绝：审批主体不可信 session={} messageId={} user={}",
                action, sessionId, messageId, PeerUiApprovalAuthority.getUserId());
            throw new ForbiddenException("Cross-session held message " + action
                + " requires a trusted UI authentication that is not configured in this desktop build.");
        }
        if (router == null) {
            throw new ForbiddenException("peer routing is not wired");
        }
    }

    private static HeldView toView(PeerMessageRouter.HeldView v) {
        return new HeldView(
            v.messageId(),
            v.targetSessionId(),
            v.sourceSessionId(),
            v.fromName(),
            v.fromAddress(),
            v.fromMode(),
            v.summary(),
            v.reason(),
            parseInstant(v.createdAt()),
            v.body(),
            v.sourceTitle(),
            v.sourceKind(),
            v.status(),
            parseInstant(v.seenAt()),
            v.admission(),
            v.admissionReason(),
            v.bodyLines(),
            v.bodyChars());
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 不可变审批主体（不携带任何 token；token 只在 A 的 registry）。userId 进日志/归属。 */
    private record Principal(String sessionId, String requestId, boolean trustedUi, String userId)
        implements PeerMessageRouter.ApprovalPrincipal {}
}
