package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPD-TP-21: kill → worker abortController 通道 · 对齐 CC LocalAgentTask.tsx:281-303
 * （killAsyncAgent → task.abortController?.abort() 直接中断 worker）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 该通道为何重要：
 * <ul>
 *   <li><b>TaskStop 必须能主动打断 worker</b>：CC killAsyncAgent 调 {@code task.abortController.abort()}
 *       （LocalAgentTask.tsx:288）→ runAgent.ts:808 检查 {@code signal.aborted} 抛 AbortError →
 *       查询循环直接退出。Java 旧实现 killAsyncAgent（BackgroundTaskRunner.java:375）只标 KILLED +
 *       通知，不触达 worker → 用户在 turn 中 kill 后，worker 继续跑完剩余 LLM 轮次（无法即时中断），
 *       且 SubagentExecutor 内部为 async 自建 {@code new AbortController()}（SubagentExecutor.java:938-939）
 *       与任务完全解耦，kill 无通道可达。</li>
 *   <li><b>通道必须 task-scoped 且幂等</b>：注册时创建、kill 时 abort、终态后清理；重复 kill 短路
 *       （only-if-running 已保证）。</li>
 * </ul>
 *
 * <p>RED 基线：{@link BackgroundTaskRunner#taskAbortController(String)} 尚不存在（无注册表）——
 * registerAsyncAgent 不创建 controller → 本测试断言失败，即为该通道缺失的证据。
 */
@DisplayName("[OPD-TP-21] kill→worker abortController 通道（registerAsyncAgent 创建 + killAsyncAgent abort）")
class TaskStopAbortChannelTest {

    /** 测试上下文：runner + 可 drain 的 NotificationQueue */
    private record Ctx(BackgroundTaskRunner runner, NotificationQueue queue) {}

    private Ctx newCtx() {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        return new Ctx(new BackgroundTaskRunner(nq, service, sdk), nq);
    }

    private static final String TYPE = "general-purpose";

    @Test
    @DisplayName("registerAsyncAgent 创建 task-scoped AbortController（CC taskState.abortController, LocalAgentTask.tsx:123/486-495）")
    void registerAsyncAgent_shouldCreateAndStoreTaskAbortController() {
        // WHY: CC registerAsyncAgent 创建 controller 并存到 taskState.abortController（:486-495），
        //   killAsyncAgent 靠它打断 worker。Java 若无此注册表 → kill 无通道 → 用户 kill 后 worker
        //   无法即时中断（OPD-TP-21 核心缺口）。
        Ctx ctx = newCtx();
        UUID agentId = UUID.randomUUID();

        ctx.runner.registerAsyncAgent(agentId, "调研任务", "prompt", TYPE, null, null);

        AbortController controller = ctx.runner.taskAbortController(agentId.toString());
        assertThat(controller).as("registerAsyncAgent 必须创建并保存 task-scoped AbortController").isNotNull();
        assertThat(controller.isCancelled()).as("新注册任务 controller 未取消").isFalse();
    }

    @Test
    @DisplayName("killAsyncAgent 后 abort 该 controller（CC LocalAgentTask.tsx:288 task.abortController.abort()）")
    void killAsyncAgent_shouldAbortWorkerController() {
        // WHY: CC killAsyncAgent 在标 KILLED 后调 task.abortController.abort()（:288）→ worker
        //   查询循环经 state.cancelled() 退出（SubagentExecutor.java:2553/2669-2675）→ 复用
        //   "aborted" 结果走 finalizeKilled 链。若 killAsyncAgent 只标状态不 abort，worker 无法被
        //   主动打断，TaskStop 对运行中子 agent 失效。
        Ctx ctx = newCtx();
        UUID agentId = UUID.randomUUID();
        ctx.runner.registerAsyncAgent(agentId, "调研任务", "prompt", TYPE, null, null);
        // kill 前捕获引用（CC :295 标 KILLED 后 abortController: undefined，但 abort() 已先行触发
        //   —— worker 持有该引用，观察点在 kill 前捕获的对象上）
        AbortController controller = ctx.runner.taskAbortController(agentId.toString());
        assertThat(controller).as("registerAsyncAgent 必须已创建 controller").isNotNull();

        boolean killed = ctx.runner.killAsyncAgent(agentId.toString());

        assertThat(killed).isTrue();
        assertThat(controller.isCancelled()).as("killAsyncAgent 必须 abort 该 controller 以直接中断 worker").isTrue();
    }

    @Test
    @DisplayName("kill 后二次 kill 幂等短路（only-if-running 守卫），controller 仅 abort 一次")
    void killAsyncAgent_secondCall_idempotentShortCircuit() {
        // WHY: 幂等是 CC killAsyncAgent 的原子 CAS 语义（LocalAgentTask.tsx:282-283 only-if-running）——
        //   重复 kill 不重复 abort/不重复通知。worker 完成回调与 kill 并发时也不会二次触发。
        Ctx ctx = newCtx();
        UUID agentId = UUID.randomUUID();
        ctx.runner.registerAsyncAgent(agentId, "调研任务", "prompt", TYPE, null, null);
        AbortController controller = ctx.runner.taskAbortController(agentId.toString());
        assertThat(controller).isNotNull();

        assertThat(ctx.runner.killAsyncAgent(agentId.toString())).isTrue();
        assertThat(ctx.runner.killAsyncAgent(agentId.toString())).isFalse();

        // abort() 幂等（AtomicBoolean CAS）：二次 kill 不触发二次 abort，但仍保持已取消
        assertThat(controller.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("registerAsyncAgent 传父 controller 时创建 child（父 abort 级联 · CC createChildAbortController LocalAgentTask.tsx:488）")
    void registerAsyncAgent_withParent_createsChildCascadingController() {
        // WHY: CC registerAsyncAgent parentAbortController 非 null → createChildAbortController（:488），
        //   父 abort 级联到子（如 in-process teammate 场景）。Java 端同样语义——父 abort 后 child
        //   立即 abort，保证父退出时不遗留子 worker。
        Ctx ctx = newCtx();
        AbortController parent = new AbortController();
        UUID agentId = UUID.randomUUID();

        ctx.runner.registerAsyncAgent(agentId, "调研任务", "prompt", TYPE, parent, null);

        AbortController controller = ctx.runner.taskAbortController(agentId.toString());
        assertThat(controller).isNotNull();
        assertThat(controller.isCancelled()).isFalse();
        // 父 abort → child 级联 abort（CC createChildAbortController 单向级联）
        parent.abort("parent_aborted");
        assertThat(controller.isCancelled()).as("父 abort 必须级联到子 controller").isTrue();
    }
}
