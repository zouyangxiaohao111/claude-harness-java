package com.nexusai.apis.session;

import com.nexusai.domain.session.SessionModeService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionModeDto;
import com.nexusai.model.session.dto.SessionModeToggleRequest;
import com.zjky.pro.framework.common.pojo.CommonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话「无人值守 / Brief」状态 REST（V329）。
 *
 * <p><b>响应契约</b>（用户裁定）：统一 {@link CommonResult} + 数字 code。
 * 成功 = HTTP 200 + {@code code:0 && success:true}；业务失败 = HTTP 200 + {@code success:false}
 * + 数字码（400 无渠道 / 409 Q8 冲突），前端判据 {@code success && code === 0}。
 * 会话不存在仍走 404（资源不存在，不是业务失败）。
 *
 * <p>唯一状态源与级联见 {@link SessionModeService}。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/mode")
public class SessionModeController {

    private static final Logger log = LoggerFactory.getLogger(SessionModeController.class);

    @Autowired private SessionModeService sessionModeService;

    /** 当前会话模式状态（无人值守 / Brief / 渠道数）。 */
    @GetMapping
    public CommonResult<SessionModeDto> get(@PathVariable String sessionId) {
        return CommonResult.ok(sessionModeService.getState(sessionId));
    }

    /**
     * 开启/退出无人值守。
     *
     * <p>开启硬前置 = 渠道实例 ≥ 1（R16）；无渠道 → 200 + code 400 + 「请先配置渠道」。
     */
    @PutMapping("/unattended")
    public CommonResult<SessionModeDto> setUnattended(@PathVariable String sessionId,
                                                      @RequestBody SessionModeToggleRequest req) {
        Boolean enabled = req == null ? null : req.enabled();
        if (enabled == null) {
            return CommonResult.error(400, "missing required field: enabled");
        }
        try {
            SessionModeDto state = sessionModeService.setUnattended(sessionId, enabled);
            log.info("[SessionModeController] unattended={} session={} channels={}",
                    enabled, sessionId, state.channels());
            return CommonResult.ok(state);
        } catch (ValidationException e) {
            // 业务失败：HTTP 200 + 数字码（前端按 success&&code==0 判）
            log.info("[SessionModeController] 开启无人值守被拒: session={} reason={}",
                    sessionId, e.getMessage());
            return CommonResult.error(400, e.getMessage(),
                    sessionModeService.getState(sessionId));
        }
    }

    /**
     * 切换 Brief（与 SendUserMessage 工具开关、{@code /brief} 同一份会话状态）。
     *
     * <p>[Q8] 无人值守开启时关 Brief：未带 {@code cascadeExitUnattended=true} ⇒
     * 200 + code 409 + 可读文案（前端据此弹确认框），状态不变；带确认 ⇒ 原子级联两状态。
     */
    @PutMapping("/brief")
    public CommonResult<SessionModeDto> setBrief(@PathVariable String sessionId,
                                                 @RequestBody SessionModeToggleRequest req) {
        Boolean enabled = req == null ? null : req.enabled();
        if (enabled == null) {
            return CommonResult.error(400, "missing required field: enabled");
        }
        boolean cascade = req != null && Boolean.TRUE.equals(req.cascadeExitUnattended());
        try {
            SessionModeDto state = sessionModeService.setBrief(sessionId, enabled, cascade);
            log.info("[SessionModeController] brief={} cascade={} session={}", enabled, cascade, sessionId);
            return CommonResult.ok(state);
        } catch (IllegalStateException e) {
            log.info("[SessionModeController] 关闭 Brief 需要确认: session={} reason={}",
                    sessionId, e.getMessage());
            return CommonResult.error(409, e.getMessage(),
                    sessionModeService.getState(sessionId));
        }
    }
}
