package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.mcp.WorkspaceTrustState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-04] trust 门控生产接线测试（E4）· Spring 上下文真实装配：
 * {@link WorkspaceTrustState}（mcp 域 @Component）的两个 {@code HeadersHelper.BooleanSupplier}
 * bean → {@link HookRegistry#setTrustGateSuppliers} 注入 → 门控随状态翻转生效。
 *
 * <p>WHY (D9 / OD-13 / EV-L01-030): trust 概念在 mcp 域建模但 hook 链 0 命中（EV-CCE-034）。
 * 本测试证明生产装配闭环：HookRegistry 拿到与 HeadersHelper 同源的 trust supplier 后，
 * {@code WorkspaceTrustState} 状态翻转（默认未接受 → acceptTrustDialog）驱动 hook 跳过/执行。
 * 默认值语义（2026-09-01 用户拍板 A）：trustDialogAccepted=true（Web 默认信任 workspace，
 * 无 CC CLI trust dialog）→ hook 全执行（SessionStart 技能注入 / 权限 hook 恢复）；
 * 非交互会话（setNonInteractiveSession(true)）→ 不跳过（CC hooks.ts:288-291）。
 */
@DisplayName("[IMPL-04] trust 门控生产接线（WorkspaceTrustState → HookRegistry，E4 装配）")
class WorkspaceTrustWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TrustWiringConfig.class);

    @Configuration
    @Import(WorkspaceTrustState.class)
    static class TrustWiringConfig {
        @Bean
        HookRegistry hookRegistry() {
            return new HookRegistry();
        }
    }
    @Test
    @DisplayName("1. 默认状态（Web 默认信任 + 交互式）")
    void defaultState_initialValues() {
        runner.run(ctx -> {
            WorkspaceTrustState state = ctx.getBean(WorkspaceTrustState.class);
            // [2026-09-01 用户拍板 A] Web 后端无 CC trust dialog → 默认信任（hook 全执行）
            assertThat(state.isTrustDialogAccepted()).as("Web 默认：trust 已接受").isTrue();
            assertThat(state.isNonInteractiveSession()).as("web 后端默认交互式").isFalse();
        });
    }

    @Test
    @DisplayName("2. 默认状态（Web 默认信任）→ executeEvent 正常执行（hook 注入恢复）")
    void defaultState_executeEvent_runsHooks() {
        // WHY: [2026-09-01 用户拍板 A] Web 后端无 CC trust dialog → 默认信任 workspace →
        //   SessionStart/权限等 hook 全执行（技能说明注入恢复，如 using-zjkycode）。
        runner.run(ctx -> {
            HookRegistry registry = ctx.getBean(HookRegistry.class);
            AtomicBoolean ran = new AtomicBoolean(false);
            registry.register("wiring-trust-ev",
                event -> {
                    ran.set(true);
                    return GenericHook.HookResult.proceed();
                },
                HookEventType.STOP);

            registry.executeEvent(HookEvent.stop("s1", null, false, null));

            assertThat(ran).as("Web 默认信任 → hook 必须执行").isTrue();
        });
    }

    @Test
    @DisplayName("3. acceptTrustDialog() → executeEvent 正常执行（接受入口生效）")
    void acceptTrustDialog_executeEvent_runsHooks() {
        // WHY: 未来 web 端 trust dialog 接受入口（acceptTrustDialog）接线后，hook 链恢复执行。
        runner.run(ctx -> {
            WorkspaceTrustState state = ctx.getBean(WorkspaceTrustState.class);
            state.acceptTrustDialog();
            HookRegistry registry = ctx.getBean(HookRegistry.class);
            AtomicBoolean ran = new AtomicBoolean(false);
            registry.register("wiring-trust-ev-ok",
                event -> {
                    ran.set(true);
                    return GenericHook.HookResult.proceed();
                },
                HookEventType.STOP);

            registry.executeEvent(HookEvent.stop("s1", null, false, null));

            assertThat(ran).as("接受 trust 后 hook 必须执行").isTrue();
        });
    }

    @Test
    @DisplayName("4. 非交互会话（SDK 等价）→ 不跳过（CC :288-291 trust 隐式）")
    void nonInteractiveSession_neverSkips() {
        // WHY (验收 5): 非交互路径（SDK/-p）trust 隐式 —— 即使 trust 未接受也不跳过。
        runner.run(ctx -> {
            WorkspaceTrustState state = ctx.getBean(WorkspaceTrustState.class);
            state.setNonInteractiveSession(true);
            HookRegistry registry = ctx.getBean(HookRegistry.class);
            AtomicBoolean ran = new AtomicBoolean(false);
            registry.register("wiring-trust-ev-ni",
                event -> {
                    ran.set(true);
                    return GenericHook.HookResult.proceed();
                },
                HookEventType.STOP);

            registry.executeEvent(HookEvent.stop("s1", null, false, null));

            assertThat(ran).as("非交互会话 trust 隐式，hook 必须执行").isTrue();
        });
    }
}
