package com.nexusai.apis.permission;

import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.infra.util.AutoModeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auto-mode 门检状态只读 REST 端点 · 对齐 CC {@code verifyAutoModeGateAccess} 组件消费面
 * （permissionSetup.ts:1283-1362 {@code isAutoModeGateEnabled} /
 * {@code getAutoModeEnabledState} / {@code getAutoModeUnavailableReason} /
 * {@code hasAutoModeOptInAnySource}）。
 *
 * <p><b>WHY 存在（DEL-AM-04 · 接线非删除）</b>: CC 端 {@code verifyAutoModeGateAccess} 组件（React
 * hook）消费上述门检函数并在 UI 展示 auto-mode 可用状态/原因；Java 端 {@link AutoModeGate}
 * 的 {@code getEnabledState()}/{@code getUnavailableReason()}/{@code hasOptIn()} 此前<b>生产零调用</b>
 * （仅测试）。本端点补齐 nexusai-ui 可查门检状态的 web 数据面 —— 门检三方法经本端点成为生产消费者，
 * 非死代码；前端对接登记 {@code 待前端对接.md §18}。
 *
 * <p><b>语义</b>: 只读、无副作用；返回 CC 等价联合值域 —— {@code enabledState} =
 * {@code 'enabled'|'opt-in'|'disabled'}（CC getAutoModeEnabledState:1328-1333）、
 * {@code unavailableReason} = {@code 'settings'|'circuit-breaker'|'model'} 或 null（可用，
 * CC getAutoModeUnavailableReason:1294-1301）、{@code hasOptIn} =
 * CC hasAutoModeOptInAnySource（CLI flag || 可信 userSettings）、{@code circuitBroken} =
 * CC autoModeState.isAutoModeCircuitBroken（permissionSetup.ts:1099 断路器状态）。
 *
 * <p><b>gate bean 未装配</b>（非 Spring 测试场景不应实例化）：抛 503 fail-loud（显式失败惯例）。
 */
@RestController
@RequestMapping("/api/v1/permissions/auto-mode-gate")
public class AutoModeGateController {

    private static final Logger log = LoggerFactory.getLogger(AutoModeGateController.class);

    /** Auto-mode 门检状态响应 · nexusai-ui 查询面（camelCase，对齐 web DTO 惯例）。 */
    public record AutoModeGateStatus(
            String enabledState,
            String unavailableReason,
            boolean hasOptIn,
            boolean isEnabled,
            boolean circuitBroken) {
    }

    @Autowired(required = false)
    private AutoModeGate autoModeGate;

    @Autowired(required = false)
    private YoloClassifier yoloClassifier;

    /**
     * 读取 auto-mode 门检状态 · 对齐 CC verifyAutoModeGateAccess 消费面。
     *
     * @return 门检状态（enabledState/unavailableReason/hasOptIn/isEnabled/circuitBroken）
     */
    @GetMapping
    public AutoModeGateStatus get() {
        if (autoModeGate == null) {
            log.warn("auto-mode gate 状态查询失败：AutoModeGate bean 未装配（Spring DI 缺失），抛 503");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AutoModeGate bean is not wired");
        }
        String enabledState = autoModeGate.getEnabledState();
        String unavailableReason = autoModeGate.getUnavailableReason(yoloClassifier);
        boolean hasOptIn = autoModeGate.hasOptIn();
        boolean isEnabled = autoModeGate.isEnabled();
        boolean circuitBroken = AutoModeState.isAutoModeCircuitBroken();
        if (log.isDebugEnabled()) {
            log.debug("读取 auto-mode 门检状态: enabledState={} unavailableReason={} hasOptIn={} "
                    + "isEnabled={} circuitBroken={}",
                enabledState, unavailableReason, hasOptIn, isEnabled, circuitBroken);
        }
        return new AutoModeGateStatus(enabledState, unavailableReason, hasOptIn, isEnabled, circuitBroken);
    }
}
