package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H10] HookEventBus · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks/hookEvents.ts} (193 行全文).
 *
 * <p>WHY (规则九 · 测试验证意图): hook 事件总线是 async hook 对外广播的唯一通道,
 * 丢失/错序/漏发都会让下游 (UI/SDK) 无法追踪 hook 生命周期:
 * <ol>
 *   <li>handler 未注册时事件不能丢 → 测试 1/2 验证 pending buffer + 回放</li>
 *   <li>白名单默认过滤非 SessionStart/Setup 事件 (降噪) → 测试 3/8 验证</li>
 *   <li>includeHookEvents 开启后全量放开 → 测试 4 验证</li>
 *   <li>started/response 必须按序同 handler 收到 (hookId 关联) → 测试 5 验证</li>
 *   <li>buffer 无限增长 = 内存泄漏 → 测试 6 验证 100 上限丢最旧</li>
 *   <li>进度定时器重复刷屏/停不掉 = 线程泄漏 + 噪声 → 测试 7 验证去重与 stop</li>
 * </ol>
 *
 * @since Session H10
 */
@DisplayName("[H10] HookEventBus 对齐 CC hookEvents.ts")
class HookEventBusTest {

    private HookEventBus bus;
    private List<HookEventBus.HookExecutionEvent> events;

    @BeforeEach
    void setUp() {
        bus = new HookEventBus();
        events = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        bus.clearHookEventState();
    }

    /** 等待条件成立 (最多 waitMs) · 进度定时器等异步路径用. */
    private boolean awaitUntil(java.util.function.BooleanSupplier cond, long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    private long progressCount(String hookId) {
        return events.stream()
            .filter(e -> e instanceof HookEventBus.HookProgressEvent)
            .map(e -> (HookEventBus.HookProgressEvent) e)
            .filter(e -> e.hookId().equals(hookId)).count();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1-2. pending buffer + 回放 (CC :57-81)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无 handler → 事件进 buffer 不丢 (CC :72-81)")
    void emitStarted_withoutHandler_bufferedNotLost() throws Exception {
        // WHY: SDK/UI 可能晚于 hook 执行才挂监听 — 事件在 handler 注册前产生也必须留存
        //      (CC pendingEvents buffer), 否则"先执行后订阅"场景的事件永久丢失.
        bus.emitHookStarted("h1", "hookA", "SessionStart");

        assertThat(events).isEmpty();
        bus.registerHookEventHandler(events::add);

        assertThat(events).hasSize(1);
        HookEventBus.HookStartedEvent started = (HookEventBus.HookStartedEvent) events.get(0);
        assertThat(started.hookId()).isEqualTo("h1");
        assertThat(started.hookName()).isEqualTo("hookA");
        assertThat(started.hookEvent()).isEqualTo("SessionStart");
    }

    @Test
    @DisplayName("registerHookEventHandler → 按产生顺序回放 buffer (CC :61-70)")
    void registerHandler_replaysBufferInOrder() {
        // WHY: 回放顺序必须 = 产生顺序 (CC splice(0) 全量按序回放) — 乱序回放会让
        //      下游把 response 配到错误的 started 上, hookId 关联失效.
        bus.emitHookStarted("h1", "a", "SessionStart");
        bus.emitHookStarted("h2", "b", "SessionStart");

        bus.registerHookEventHandler(events::add);

        assertThat(events).hasSize(2);
        assertThat(((HookEventBus.HookStartedEvent) events.get(0)).hookId()).isEqualTo("h1");
        assertThat(((HookEventBus.HookStartedEvent) events.get(1)).hookId()).isEqualTo("h2");
    }

    @Test
    @DisplayName("registerHookEventHandler(null) 保留 pending buffer; 再注册非 null handler 按序回放 (D-08, CC :61-70)")
    void registerNull_keepsBuffer_replaysAfterReRegister() {
        // WHY (D-08): CC null 注册仅置空 eventHandler, pendingEvents 保留 (hookEvents.ts:64-69
        //      {@code eventHandler = handler; if (handler && pendingEvents.length > 0) splice(0) 回放}) —
        //      注销再注册场景旧事件必须仍按序回放; buffer 只由 clearHookEventState 清空 (CC :188-192).
        //      Java 旧实现 null 注册即清空 buffer → 注销期间事件永久丢失.
        bus.emitHookStarted("h1", "a", "SessionStart");
        bus.emitHookStarted("h2", "b", "SessionStart");

        bus.registerHookEventHandler(null);
        // null 注册 (注销) 后产生的事件继续进 buffer
        bus.emitHookStarted("h3", "c", "SessionStart");

        bus.registerHookEventHandler(events::add);

        assertThat(events).hasSize(3);
        assertThat(((HookEventBus.HookStartedEvent) events.get(0)).hookId()).isEqualTo("h1");
        assertThat(((HookEventBus.HookStartedEvent) events.get(1)).hookId()).isEqualTo("h2");
        assertThat(((HookEventBus.HookStartedEvent) events.get(2)).hookId()).isEqualTo("h3");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3-4. 白名单 / 全量开关 (CC :83-91, :184-186)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("emitHookStarted(PreToolUse) 未 enable → 不 emit 不进 buffer (CC :93-98)")
    void emitStarted_notEnabled_notEmittedNotBuffered() {
        // WHY: 白名单外事件 (PreToolUse 等 25 种) 默认不 emit (CC shouldEmit) — 否则每个
        //      工具调用都刷事件, 噪声淹没 SessionStart/Setup 生命周期信号, 也撑爆 buffer.
        bus.emitHookStarted("h3", "toolHook", "PreToolUse");

        bus.registerHookEventHandler(events::add);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("setAllHookEventsEnabled(true) → PreToolUse 可 emit (CC :184-186)")
    void setAllHookEventsEnabled_allowsOtherEvents() {
        // WHY: SDK includeHookEvents / CLAUDE_CODE_REMOTE 模式需要放开全部事件 —
        //      白名单是默认降噪开关, 不是能力上限 (CC :179-183 注释语义).
        bus.setAllHookEventsEnabled(true);

        bus.emitHookStarted("h4", "toolHook", "PreToolUse");
        bus.registerHookEventHandler(events::add);

        assertThat(events).hasSize(1);
        assertThat(((HookEventBus.HookStartedEvent) events.get(0)).hookId()).isEqualTo("h4");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5-6. 全链路 + buffer 上限 (CC :93-177, :20)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("emitHookStarted → emitHookResponse 全链路: 同 handler 依次收到 started/response")
    void startedThenResponse_fullChain() {
        // WHY: started → response 必须由同一 handler 按序收到 (CC :93-106 / :153-177) —
        //      否则下游无法把"开始"与"结果"配对, hookId 关联语义失效.
        bus.setAllHookEventsEnabled(true);
        bus.registerHookEventHandler(events::add);

        bus.emitHookStarted("h5", "chainHook", "PreToolUse");
        bus.emitHookResponse(new HookEventBus.HookResponseData(
            "h5", "chainHook", "PreToolUse", "out", "out", "", 0, HookEventBus.HookOutcome.SUCCESS));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(HookEventBus.HookStartedEvent.class);
        assertThat(events.get(1)).isInstanceOf(HookEventBus.HookResponseEvent.class);
        HookEventBus.HookResponseEvent resp = (HookEventBus.HookResponseEvent) events.get(1);
        assertThat(resp.hookId()).isEqualTo("h5");
        assertThat(resp.outcome()).isEqualTo(HookEventBus.HookOutcome.SUCCESS);
        assertThat(resp.stdout()).isEqualTo("out");
        assertThat(resp.output()).isEqualTo("out");
    }

    @Test
    @DisplayName("100 条 buffer 满 → 丢最旧 (CC :20, :77-79)")
    void bufferOverflow_dropsOldest() {
        // WHY: buffer 无上限 = 内存泄漏; CC MAX_PENDING_EVENTS=100 + shift 丢最旧 —
        //      handler 注册时最关心最近的 hook 执行, 丢旧保新 (CC :77-79).
        for (int i = 0; i < 105; i++) {
            bus.emitHookStarted("h" + i, "n" + i, "SessionStart");
        }

        bus.registerHookEventHandler(events::add);

        assertThat(events).hasSize(100);
        // 前 5 条被丢, 从 h5 开始
        assertThat(((HookEventBus.HookStartedEvent) events.get(0)).hookId()).isEqualTo("h5");
        assertThat(((HookEventBus.HookStartedEvent) events.get(99)).hookId()).isEqualTo("h104");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7-8. 进度定时器 / ALWAYS_EMITTED (CC :124-151, :18)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("startHookProgressInterval: 输出变化 → emit; 无变化 → 去重; stop() 后停止 (CC :124-151)")
    void progressInterval_emitsOnChange_dedups_stops() throws Exception {
        // WHY: 进度定时器是 async hook 长耗时场景的唯一进度通道 (CC :124-151):
        //      <ul>
        //        <li>相同输出重复 emit = 每 tick 刷屏 → 必须去重 (CC :136-137)</li>
        //        <li>hook 完成后必须 stop → 否则定时器泄漏 (CC :150 return clearInterval)</li>
        //      </ul>
        bus.setAllHookEventsEnabled(true);
        // PreToolUse 需全量开关, 且必须先注册 handler — 否则 progress 事件进 buffer 而非 events
        bus.registerHookEventHandler(events::add);

        // Phase A: 恒定输出 → 只 emit 首次 (首 tick 从 lastEmittedOutput='' 触发一次, 之后去重)
        Runnable stopA = bus.startHookProgressInterval("p1", "progHook", "PreToolUse",
            () -> new HookEventBus.HookProgressOutput("same", "", "same"), 30);
        try {
            assertThat(awaitUntil(() -> progressCount("p1") >= 1, 3000)).isTrue();
            Thread.sleep(150); // 多个 tick 周期
            assertThat(progressCount("p1")).isEqualTo(1); // 恒定输出 → 只有首次
        } finally {
            stopA.run();
        }

        // Phase B: 输出变化 → 持续 emit; stop 后不再增长
        AtomicInteger n = new AtomicInteger();
        Runnable stopB = bus.startHookProgressInterval("p2", "progHook2", "PreToolUse",
            () -> new HookEventBus.HookProgressOutput("out-" + n.incrementAndGet(), "", "out-" + n.get()), 30);
        try {
            assertThat(awaitUntil(() -> progressCount("p2") >= 2, 3000)).isTrue();
        } finally {
            stopB.run();
        }
        long afterStop = progressCount("p2");
        Thread.sleep(150);
        assertThat(progressCount("p2")).isEqualTo(afterStop); // stop 后不再 emit
    }

    @Test
    @DisplayName("Setup 在 ALWAYS_EMITTED 白名单 → 未 enable 也可 emit (CC :18, :83-91)")
    void setup_alwaysEmitted_withoutEnable() {
        // WHY: Setup 与 SessionStart 同属 ALWAYS_EMITTED (CC :18) — 安装/配置阶段的 hook
        //      事件即使未开 includeHookEvents 也必须可见, 否则环境初始化问题无法观测
        //      (CC 注释: 原始 allowlist 向后兼容的低噪声生命周期事件).
        bus.emitHookStarted("h8", "setupHook", "Setup");

        bus.registerHookEventHandler(events::add);
        assertThat(events).hasSize(1);
        assertThat(((HookEventBus.HookStartedEvent) events.get(0)).hookEvent()).isEqualTo("Setup");
    }
}
