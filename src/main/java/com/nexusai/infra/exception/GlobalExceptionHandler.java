package com.nexusai.infra.exception;

import com.nexusai.model.dto.Problem;
import com.nexusai.infra.exception.MaxJobsExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理：把异常转成 RFC 7807 Problem JSON
 *
 * <p>状态码规则：
 * <ul>
 *   <li>NotFoundException → 404</li>
 *   <li>ConflictException → 409</li>
 *   <li>ValidationException → 400</li>
 *   <li><b>UnresolvedProjectRootException → 400</b>（[S2 F-03b 2026-09-14 · 用户裁定 #12]：
 *       会话项目根解析失败 / 无法判定 ⇒ <b>400 而非 500</b> —— 「本该绑定项目却没有」是客户端可见的
 *       请求前提不成立，不是服务端故障。判据与同族 400 一致：与 {@code SessionMemoryExportController}
 *       的「未绑定项目根 ⇒ 400」同一不变量的另一条链）</li>
 *   <li>MethodArgumentNotValidException（@Valid 失败）→ 400 + errors[]</li>
 *   <li>HttpMessageNotReadableException（JSON 解析错）→ 400</li>
 *   <li>Exception（兜底）→ 500</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Problem> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(withInstance(Problem.of(404, "Not Found", ex.getMessage()), req));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Problem> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(withInstance(Problem.of(403, "Forbidden", ex.getMessage()), req));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Problem> handleConflict(ConflictException ex, HttpServletRequest req) {
        // CRON-B4-3 决策 #13：MaxJobsExceededException（ConflictException 子类）带 errorCode "3"，
        // 与工具路径 CronCreateTool.validateInput errorCode3 语义一致；其余冲突无 errorCode → null
        String errorCode = (ex instanceof MaxJobsExceededException mje) ? mje.errorCode() : null;
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(withInstance(Problem.of(409, "Conflict", ex.getMessage(), errorCode), req));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Problem> handleValidation(ValidationException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(withInstance(Problem.of(400, "Validation Failed", ex.getMessage()), req));
    }

    /**
     * 会话项目根解析失败 ⇒ <b>400</b>（[S2 · F-03b 2026-09-14 · 用户裁定 #12 (B2)]）。
     *
     * <p><b>WHY 400 而非 500</b>：该异常表示「请求所依赖的会话项目绑定不成立 / 无法判定」——
     * 客户端可据 400 纠正（补绑定 / 别用未绑定会话），而 500 会让它无法区分「我传错了」与
     * 「后端炸了」。⛔ 不用 409：与计划登记及 {@code SessionMemoryExportController} 同族，
     * 且本仓既有 4xx 惯例里该族一律 400。
     *
     * <p>⚠️ 本 handler 必须<b>新增</b>而不是把 {@code UnresolvedProjectRootException} 做成
     * {@code ValidationException} 子类：后者会打红 {@code CwdResolutionTest} 的 4 处
     * {@code isInstanceOf(IllegalStateException)} 断言，并让 {@code BashTool} 的
     * {@code catch (IllegalStateException shellEx)} 行为漂移（见该类 javadoc）。
     */
    @ExceptionHandler(UnresolvedProjectRootException.class)
    public ResponseEntity<Problem> handleUnresolvedProjectRoot(UnresolvedProjectRootException ex,
                                                              HttpServletRequest req) {
        log.warn("[GlobalExceptionHandler] 会话项目根解析失败 → 400: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(withInstance(Problem.of(400, "Unresolved Project Root", ex.getMessage()), req));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleBeanValidation(MethodArgumentNotValidException ex,
                                                       HttpServletRequest req) {
        List<Problem.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new Problem.FieldError(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
            .toList();
        Problem p = new Problem("about:blank", "Validation Failed", 400,
            "Request body has invalid fields", req.getRequestURI(), null, fieldErrors, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(p);
    }

    @ExceptionHandler(BadGatewayException.class)
    public ResponseEntity<Problem> handleBadGateway(BadGatewayException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(withInstance(Problem.of(502, "Bad Gateway", ex.getMessage()), req));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException ex,
                                                    HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(withInstance(Problem.of(400, "Malformed JSON", ex.getMostSpecificCause().getMessage()),
                req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(withInstance(Problem.of(500, "Internal Server Error",
                ex.getClass().getSimpleName() + ": " + ex.getMessage()), req));
    }

    private static Problem withInstance(Problem p, HttpServletRequest req) {
        return new Problem(p.type(), p.title(), p.status(), p.detail(),
            req.getRequestURI(), p.traceId(), p.errors(), p.errorCode());
    }
}