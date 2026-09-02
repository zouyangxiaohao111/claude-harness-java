package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.HookEventBus.HookExecutionEvent;
import com.nexusai.application.agent.permission.hook.HookEventBus.HookStartedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HookEventStartupWiring 启动接线测试 · D-07 (E-HOOKS-T3-61).
 *
 * <p>对齐 CC {@code main.tsx:1229-1233}:
 * {@code if (includeHookEvents || isEnvTruthy(process.env.CLAUDE_CODE_REMOTE)) { setAllHookEventsEnabled(true); }}
 * — Java 无 SDK includeHookEvents 等价 (决策 09#1 D-04 N/A), 接线只表达
 * CLAUDE_CODE_REMOTE 分量. 断言可观察效果: env truthy → 白名单外事件 (PreToolUse)
 * 过开关进 buffer (无 handler 时), 注册后回放; env falsy → 白名单外事件不过
 * shouldEmit、不进 buffer (CC hookEvents.ts:83-91/:98), 保持 CC 缺省
 * (仅 SessionStart/Setup).
 */
@DisplayName("[D-07] HookEventStartupWiring CLAUDE_CODE_REMOTE 启动接线")
class HookEventStartupWiringTest {

    private HookEventBus bus;
    private HookEventStartupWiring wiring;
    private List<HookExecutionEvent> events;

    @BeforeEach
    void setUp() {
        bus = new HookEventBus();
        wiring = new HookEventStartupWiring(bus);
        events = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        bus.clearHookEventState();
        bus.shutdown();
    }

    @Test
    @DisplayName("supplier=true → run() 开全量开关: 白名单外事件过开关入 buffer, 注册 handler 后回放")
    void remoteTruthy_run_enablesAllHookEvents() {
        // WHY (D-07): CC main.tsx:1232-1233 在 CCR 模式下无条件 setAllHookEventsEnabled(true),
        //      否则 PreToolUse 等 25 种事件被 shouldEmit 白名单挡住 (CC :179-183 注释
        //      "Without this, only SessionStart and Setup events are emitted").
        wiring.setEnvRemoteSupplier(() -> true);

        wiring.run(new DefaultApplicationArguments(new String[0]));

        // 无 handler 时 emit → 过开关进 buffer (白名单外事件不丢)
        bus.emitHookStarted("h1", "startupHook", "PreToolUse");
        bus.registerHookEventHandler(events::add);

        assertThat(events).hasSize(1);
        assertThat(((HookStartedEvent) events.get(0)).hookId()).isEqualTo("h1");
        assertThat(((HookStartedEvent) events.get(0)).hookEvent()).isEqualTo("PreToolUse");
    }

    @Test
    @DisplayName("supplier=false → run() 不开开关: 白名单外事件不进 buffer (CC 缺省)")
    void remoteFalsy_run_keepsWhitelistOnly() {
        // WHY (D-07 反例防护): 非 CCR 模式保持 CC 缺省 — shouldEmit 不过直接 return
        // 不进 buffer (hookEvents.ts:98), 与 HookEventBusTest.emitStarted_notEnabled_* 同语义;
        // 防止接线把开关误设为恒 true.
        wiring.setEnvRemoteSupplier(() -> false);

        wiring.run(new DefaultApplicationArguments(new String[0]));

        bus.emitHookStarted("h2", "startupHook", "PreToolUse");
        bus.registerHookEventHandler(events::add);

        assertThat(events).isEmpty();
    }
}
