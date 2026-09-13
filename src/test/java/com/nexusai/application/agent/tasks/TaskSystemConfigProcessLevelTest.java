package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.impl.TaskCreateTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [批 3c · 2026-09-13] <b>V1/V2 判定 = 进程级</b>真值测试。
 *
 * <p><b>本类取代 {@code TaskSystemConfigMdcInheritanceTest}</b>（同类删除）。旧类锁的是
 * 「含 requestId 的 MDC 线程 → {@code isTodoV2Enabled()==true}」—— 即把<b>裸 MDC 会话槽</b>当作
 * 交互式判据（决策 #65）。批 3c 已删该判据（MDC 第三态：可能读到上一请求残留的、别的会话的 id），
 * 判定提升为<b>进程级</b>，故旧类的夹具形状（测试线程设 MDC → 同线程断言）不再表达任何真实语义，
 * 必须重新表达为下面的进程级真值断言 —— <b>是重新表达，不是删</b>，因为它此前在给那个偏离行为背书
 * （旧断言会阻止任何人把判定改回 CC 的进程级语义）。</p>
 *
 * <h2>CC 真源（自验，不信注释）</h2>
 * <ul>
 *   <li>{@code bootstrap/state.ts:71} 字段 {@code isInteractive: boolean}；{@code :295} 初始
 *       {@code isInteractive: false}；{@code :1039-1040} {@code getIsNonInteractiveSession() =
 *       !STATE.isInteractive}；{@code :1044} {@code getIsInteractive()}；{@code :1048-1050}
 *       {@code setIsInteractive(value)}</li>
 *   <li>{@code main.tsx:958-977} 启动时<b>一次</b>设定：
 *       {@code isNonInteractive = hasPrintFlag || hasInitOnlyFlag || hasSdkUrl ||
 *       (!forceInteractive && !process.stdout.isTTY)} → {@code setIsInteractive(!isNonInteractive)}</li>
 *   <li>{@code utils/tasks.ts:133-139} {@code isTodoV2Enabled() = isEnvTruthy(env
 *       CLAUDE_CODE_ENABLE_TASKS) ? true : !getIsNonInteractiveSession()}</li>
 * </ul>
 * <p>⇒ CC 的判定<b>没有任何逐请求/逐线程/逐会话分量</b>，同一进程内恒返回同一值。</p>
 *
 * <h2>本仓语义（批 3c）</h2>
 * <p>{@link TaskSystemConfig#isInteractive()} 无 {@code nexusai.interactive} sysprop 注入时取
 * 进程级常量 {@code DEFAULT_INTERACTIVE = true}（Web UI 后端 = CC 交互式 REPL 的直接类比）。
 * <b>代价（用户已知悉并批准）</b>：cron/后台轮次不再降级 V1，与 Web 轮次一样走 V2 Task 工具
 * —— 这正是 CC 的行为。唯一降级通道是 sysprop {@code nexusai.interactive=false}。</p>
 */
@DisplayName("批 3c · V1/V2 判定为进程级（取代原 TaskSystemConfigMdcInheritanceTest）")
class TaskSystemConfigProcessLevelTest {

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    @Test
    @DisplayName("无 sysprop 注入 → 进程级默认交互 → isInteractive()/isTodoV2Enabled() 恒 true → V2 工具集")
    void noSysprop_processLevelDefault_isInteractive_v2() {
        TaskSystemConfig.clearForTest();

        assertThat(TaskSystemConfig.isInteractive())
            .as("无 sysprop → 进程级默认 true（TaskSystemConfig.DEFAULT_INTERACTIVE，对齐 CC STATE.isInteractive 进程级语义）")
            .isTrue();
        assertThat(TaskSystemConfig.isTodoV2Enabled())
            .as("进程级交互 → todoV2 默认开").isTrue();
        assertThat(new TodoWriteTool().isEnabled())
            .as("V2 模式下 TodoWrite 不注册").isFalse();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled())
            .as("V2 模式下 Task 工具族注册").isTrue();
    }

    /**
     * 【真线程】进程级语义的判别力所在：判定<b>不随线程变</b>。
     *
     * <p>WHY 必须用真实新线程断言：旧实现（裸 MDC {@code ThreadLocal}）在新线程上恒为
     * {@code false}（不继承），而进程级实现恒与当前线程同值。若有人把判定改回任何
     * 线程局部槽（MDC / ThreadLocal 会话槽），本用例当场转红 —— 这是本类唯一的
     * 「机制级」守卫，且<b>不依赖测试线程上先 set 再读</b>（那种夹具零覆盖力）。
     */
    @Test
    @DisplayName("真线程：全新线程上判定结果与当前线程一致（进程级，非线程局部）")
    void freshThread_seesSameResult_processLevel() throws Exception {
        TaskSystemConfig.clearForTest();
        boolean onCallingThread = TaskSystemConfig.isTodoV2Enabled();

        AtomicReference<Boolean> onFreshThread = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            threadName.set(Thread.currentThread().getName());
            onFreshThread.set(TaskSystemConfig.isTodoV2Enabled());
        }, "batch3c-proclevel-probe");
        worker.start();
        worker.join(5_000);

        assertThat(threadName.get())
            .as("判定必须在真实新线程上执行（非测试线程）—— 验证跨线程面")
            .isEqualTo("batch3c-proclevel-probe");
        // ⚠ 两个断言缺一不可（反向实验实证）：只断言「跨线程同值」是**不够的** ——
        //   若判定退化成一个「谁都没设过」的线程局部槽，调用线程与新线程会**同时**读到 false，
        //   `isEqualTo` 依然成立 ⇒ 该断言对「统一的空槽」零判别力（这正是本仓
        //   「声称守护 X、实际守不住」的失效模式）。故必须**同时**钉住真值。
        assertThat(onFreshThread.get())
            .as("进程级判定的**真值**必须在新线程上成立（无 sysprop → 进程级默认 true）；"
                + "若为 false 说明判定退化成了线程局部槽（新线程不继承），即批 3c 已删的第三态缺陷复发")
            .isTrue();
        assertThat(onFreshThread.get())
            .as("且必须与调用线程同值（跨线程一致 —— 进程级语义）")
            .isEqualTo(onCallingThread);
    }

    @Test
    @DisplayName("唯一降级通道：sysprop nexusai.interactive=false → 进程级降级 V1（TodoWrite 注册、Task 族不注册）")
    void syspropInteractiveFalse_downgradesToV1() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.interactive", "false");

        assertThat(TaskSystemConfig.isInteractive()).isFalse();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isFalse();
        assertThat(new TodoWriteTool().isEnabled()).isTrue();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("sysprop nexusai.interactive=true 显式开启 → V2（覆盖进程级默认的同一通道）")
    void syspropInteractiveTrue_forcesV2() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.interactive", "true");

        assertThat(TaskSystemConfig.isInteractive()).isTrue();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isTrue();
    }
}
