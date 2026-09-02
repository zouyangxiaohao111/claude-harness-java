package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S4 D-02] CommandHookExecutor sync 路径事件接线测试 · 对齐 CC hooks.ts:2446
 * emitHookStarted + :1172-1177 startHookProgressInterval + 各 outcome 分支 emitHookResponse.
 *
 * <p>WHY (T3 D-02): Java sync 路径此前无任何 started/progress/response 事件 (runProcess
 * 纯执行返回), 事件总线生产调用点缺失. S4 补线: sync 路径 (configAsync 早退后) 生成
 * hookId → emitHookStarted → startHookProgressInterval (getOutput 读 stdoutRef/stderrRef) →
 * 各返回点先 stop interval 再 emitHookResponse (aborted→CANCELLED / status==0→SUCCESS /
 * 其余→ERROR).
 *
 * <p><b>已知偏差登记 (S4 风险表)</b>: stdout 首行 async 罕见路径会双发 started — sync 路径
 * 先发 hookId=S, registry 注册侧 (AsyncHookRegistry.java:135, S3 文件不可改) 再发 hookId=R,
 * S 成孤儿 started. 本测试覆盖 config-async 不重复 started (S3 约束), 不覆盖该双发路径.
 *
 * <p><b>进度 interval</b>: 通过 package-private {@code progressIntervalMs} 字段提速 (10ms),
 * 不触碰 HookEventBus (S3 文件).
 */
@DisplayName("[S4 D-02] CommandHookExecutor sync 路径 started/progress/response 接线")
class CommandHookEventEmissionTest {

    /** 内存 fake 进程 · 同 CommandHookExecutorTest.FakeHookProcess. */
    static class FakeHookProcess implements CommandHookExecutor.HookProcess {
        final OutputStream stdinOut = new OutputStream() {
            @Override public void write(int b) { }
        };
        final InputStream stdoutIn;
        final InputStream stderrIn;
        final boolean[] waitForResults;
        volatile boolean destroyed;
        int waitForCalls = 0;

        FakeHookProcess(InputStream stdoutIn, int exitCode, boolean[] waitForResults) {
            this.stdoutIn = stdoutIn;
            this.stderrIn = new java.io.ByteArrayInputStream(new byte[0]);
            this.exitCode = exitCode;
            this.waitForResults = waitForResults;
        }

        static FakeHookProcess normal(String stdout, int exitCode) {
            return new FakeHookProcess(
                new java.io.ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8)),
                exitCode, new boolean[]{true});
        }

        @Override public OutputStream stdin() { return stdinOut; }
        @Override public InputStream stdout() { return stdoutIn; }
        @Override public InputStream stderr() { return stderrIn; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (waitForCalls < waitForResults.length) {
                return waitForResults[waitForCalls++];
            }
            return waitForResults[waitForResults.length - 1];
        }
        @Override public void destroyForcibly() { destroyed = true; }

        private final int exitCode;
        @Override public int exitValue() { return exitCode; }
    }

    /** 分块 InputStream · 块间 sleep 让进度定时器 tick 到 (模拟管道分块到达). */
    static class ChunkedInputStream extends InputStream {
        private final String[] chunks;
        private final long gapMs;
        private int chunkIdx = 0;
        private int pos = 0;

        ChunkedInputStream(String[] chunks, long gapMs) {
            this.chunks = chunks;
            this.gapMs = gapMs;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (chunkIdx >= chunks.length) {
                return -1;
            }
            if (chunkIdx > 0 && pos == 0 && gapMs > 0) {
                try {
                    Thread.sleep(gapMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] chunk = chunks[chunkIdx].getBytes(StandardCharsets.UTF_8);
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            if (pos >= chunk.length) {
                chunkIdx++;
                pos = 0;
            }
            return n;
        }
    }

    /** 阻塞型 fake 进程 · waitFor 直到 destroyForcibly (abort/超时后返回, 镜像 BlockingHookProcess 模式). */
    static class BlockingProcess extends FakeHookProcess {
        BlockingProcess() {
            super(new java.io.ByteArrayInputStream(new byte[0]), 1, new boolean[]{false});
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!destroyed && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return destroyed;
                }
            }
            return destroyed;
        }
    }


    private CommandHookExecutor newExecutor(FakeHookProcess process, HookEventBus bus) {
        CommandHookExecutor executor = new CommandHookExecutor(
            spec -> process, k -> null, p -> true, () -> "C:/project", id -> "C:/data");
        executor.setHookEventBus(bus);
        executor.progressIntervalMs = 10L; // 测试提速 (S4 允许, 不触碰 HookEventBus)
        return executor;
    }

    private static HookEvent preToolEvent() {
        return HookEvent.toolPre("Bash", null, "s1", null);
    }

    private static CommandHook command(String cmd) {
        return new CommandHook(cmd, null, null, null, null, null, null, null);
    }

    private List<HookEventBus.HookExecutionEvent> collect(HookEventBus bus) {
        List<HookEventBus.HookExecutionEvent> events = new CopyOnWriteArrayList<>();
        bus.registerHookEventHandler(events::add);
        bus.setAllHookEventsEnabled(true);
        return events;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. sync 完成: started → progress → response 同 hookId
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("D-02 sync 完成: started → progress → response 同 hookId, outcome=SUCCESS exit=0")
    void syncCompletion_startedProgressResponse_sameHookId() throws Exception {
        HookEventBus bus = new HookEventBus();
        List<HookEventBus.HookExecutionEvent> events = collect(bus);
        FakeHookProcess fake = new FakeHookProcess(
            new ChunkedInputStream(new String[]{"first-chunk\n", "second-chunk\n"}, 120L),
            0, new boolean[]{true});
        CommandHookExecutor executor = newExecutor(fake, bus);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            command("echo hi"), preToolEvent(), "h", "{}", null, null, null, null, false, null);

        assertThat(result.status()).isEqualTo(0);
        // started 先于 response, 同 hookId
        HookEventBus.HookStartedEvent started = events.stream()
            .filter(e -> e instanceof HookEventBus.HookStartedEvent)
            .map(e -> (HookEventBus.HookStartedEvent) e)
            .findFirst().orElse(null);
        HookEventBus.HookResponseEvent resp = events.stream()
            .filter(e -> e instanceof HookEventBus.HookResponseEvent)
            .map(e -> (HookEventBus.HookResponseEvent) e)
            .findFirst().orElse(null);
        assertThat(started).isNotNull();
        assertThat(resp).isNotNull();
        assertThat(resp.hookId()).isEqualTo(started.hookId());
        assertThat(resp.outcome()).isEqualTo(HookEventBus.HookOutcome.SUCCESS);
        assertThat(resp.exitCode()).isEqualTo(0);
        assertThat(resp.stdout()).contains("second-chunk");
        // 进度事件: 块间 gap 让定时器 tick 到, 逐块增量可见 (CC stdout+=data 语义)
        List<HookEventBus.HookProgressEvent> progress = events.stream()
            .filter(e -> e instanceof HookEventBus.HookProgressEvent)
            .map(e -> (HookEventBus.HookProgressEvent) e)
            .toList();
        assertThat(progress).as("sync 路径必须发进度事件 (D-02)").isNotEmpty();
        assertThat(progress.get(0).hookId()).isEqualTo(started.hookId());
        assertThat(progress.get(0).stdout()).contains("first-chunk");
        // 事件序: started < progress < response
        int startedIdx = events.indexOf(started);
        int firstProgressIdx = events.indexOf(progress.get(0));
        int respIdx = events.indexOf(resp);
        assertThat(startedIdx).isLessThan(firstProgressIdx);
        assertThat(firstProgressIdx).isLessThan(respIdx);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. abort: response outcome=CANCELLED exit=1 同 hookId
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("D-02 abort → response outcome=CANCELLED exit=1 同 started hookId (CC :2473-2483)")
    void syncAbort_cancelledResponse_sameHookId() throws Exception {
        HookEventBus bus = new HookEventBus();
        List<HookEventBus.HookExecutionEvent> events = collect(bus);
        FakeHookProcess fake = new BlockingProcess();
        CommandHookExecutor executor = newExecutor(fake, bus);
        AbortController controller = new AbortController();

        Thread runner = new Thread(() -> executor.execute(
            command("slow"), preToolEvent(), "h", "{}", null, null, null, null, false, controller));
        runner.start();
        Thread.sleep(120); // 让 started 发射 + 进入 wait 循环
        controller.abort("user-interrupt");
        runner.join(3000);
        assertThat(runner.isAlive()).as("abort 后 execute 必须返回").isFalse();
        assertThat(fake.destroyed).isTrue();

        HookEventBus.HookStartedEvent started = events.stream()
            .filter(e -> e instanceof HookEventBus.HookStartedEvent)
            .map(e -> (HookEventBus.HookStartedEvent) e)
            .findFirst().orElse(null);
        HookEventBus.HookResponseEvent resp = events.stream()
            .filter(e -> e instanceof HookEventBus.HookResponseEvent)
            .map(e -> (HookEventBus.HookResponseEvent) e)
            .findFirst().orElse(null);
        assertThat(started).isNotNull();
        assertThat(resp).isNotNull();
        assertThat(resp.hookId()).isEqualTo(started.hookId());
        assertThat(resp.outcome()).isEqualTo(HookEventBus.HookOutcome.CANCELLED);
        assertThat(resp.exitCode()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. config-async: started 单发 + asyncRewake response 同源
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("D-02 config-async non-rewake: started 不重复 (S3 约束, registry 侧单发)")
    void configAsync_nonRewake_singleStarted() throws Exception {
        HookEventBus bus = new HookEventBus();
        List<HookEventBus.HookExecutionEvent> events = collect(bus);
        FakeHookProcess fake = FakeHookProcess.normal("", 0);
        CommandHookExecutor executor = newExecutor(fake, bus);
        AsyncHookRegistry registry = new AsyncHookRegistry(bus);
        executor.setAsyncHookRegistry(registry);

        CommandHookExecutor.CommandHookResult result = executor.execute(
            new CommandHook("long", null, null, null, null, null, true, null),
            preToolEvent(), "h", "{}", null, null, null, null, false, null);

        assertThat(result.backgrounded()).isTrue();
        // config-async 路径: 执行入口 (configAsync 分支, 决策 2-3 / B2) 单发 started;
        // 注册侧 (A2 已删) / asyncRewake 分支 (B2 已删) 不再补发 → 恰好 1 次.
        long startedCount = events.stream()
            .filter(e -> e instanceof HookEventBus.HookStartedEvent)
            .count();
        assertThat(startedCount).as("config-async 不得重复 started (决策 2-3 执行入口单发)").isEqualTo(1);
    }

    @Test
    @DisplayName("D-02 config-async + asyncRewake: started 补发 + response 同源 (completeAsyncRewake)")
    void configAsync_asyncRewake_startedAndResponseSameSource() throws Exception {
        HookEventBus bus = new HookEventBus();
        List<HookEventBus.HookExecutionEvent> events = collect(bus);
        FakeHookProcess fake = FakeHookProcess.normal("done", 0);
        CommandHookExecutor executor = newExecutor(fake, bus);
        executor.setAsyncHookRegistry(new AsyncHookRegistry(bus));
        executor.setNotificationQueue(new com.nexusai.application.agent.tasks.NotificationQueue());

        CommandHookExecutor.CommandHookResult result = executor.execute(
            new CommandHook("reawake", null, null, null, null, null, true, true),
            preToolEvent(), "h", "{}", null, null, null, null, false, null);

        assertThat(result.backgrounded()).isTrue();
        // started 单发 (asyncRewake 分支补线, 注册侧不参与)
        HookEventBus.HookStartedEvent started = events.stream()
            .filter(e -> e instanceof HookEventBus.HookStartedEvent)
            .map(e -> (HookEventBus.HookStartedEvent) e)
            .findFirst().orElse(null);
        assertThat(started).isNotNull();
        assertThat(events.stream().filter(e -> e instanceof HookEventBus.HookStartedEvent).count())
            .isEqualTo(1);
        // 完成回调 (daemon watcher) 异步发 response → 轮询等待, 同 hookId
        HookEventBus.HookResponseEvent resp = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            resp = events.stream()
                .filter(e -> e instanceof HookEventBus.HookResponseEvent)
                .map(e -> (HookEventBus.HookResponseEvent) e)
                .findFirst().orElse(null);
            if (resp != null) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(resp).isNotNull();
        assertThat(resp.hookId()).as("asyncRewake response 必须与 started 同源 (D-02 补线)").isEqualTo(started.hookId());
        assertThat(resp.outcome()).isEqualTo(HookEventBus.HookOutcome.SUCCESS);
    }
}
