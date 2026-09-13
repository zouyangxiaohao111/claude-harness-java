package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 3b] {@link LocalBashTaskRunner#execute(String, String, String)} 的后台进程 cwd
 * 必须取自<b>显式 sessionId</b>，且在<b>真实派生线程</b>（BackgroundTaskRunner 的 executor 等价物）
 * 上的<b>残留 MDC（第三态）</b>不得改变它。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：旧实现 {@code CwdResolution.getCwd(RequestContext.sessionId())}
 * 在 executor 线程执行（提交前无 MDC 回放）⇒ 恒 null ⇒ <b>后台命令永远跑在 user.dir 而不是会话 cwd</b>
 * （cwd-align-ext 的目标语义未达成）；池线程复用残留别会话 MDC 时更会跑进别的会话目录。
 * 批 3b 改为显式入参（{@code BackgroundTaskRunner.spawn} 用 {@code sessionTask.sessionId()}）。
 *
 * <p><b>鉴别力</b>：断言命令的<b>相对路径写入落点</b>（marker 文件出现在显式会话目录、不出现在残留
 * 会话目录）—— 旧实现下会反过来（写进残留会话的 L2 cwd 目录）。
 */
@DisplayName("批 3b · LocalBashTaskRunner cwd 会话源 = 显式入参（派生线程 + 残留 MDC 反向对照）")
class LocalBashTaskRunnerSessionCwdExplicitTest {

    private static final String EXPLICIT_SESSION = "sess-lbr-explicit-3b";
    private static final String STALE_SESSION = "sess-lbr-stale-3b";
    private static final String MARKER = "marker3b.txt";

    private final ExecutorService derived = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "batch3b-lbr");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        derived.shutdownNow();
        RequestContext.clear();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    @Test
    @DisplayName("① 派生线程 + 残留 MDC：后台命令跑在显式会话 cwd（不在残留会话 cwd）")
    void execute_usesExplicitSessionCwd_notResidualMdc(@TempDir Path tmp) throws Exception {
        Path explicitDir = Files.createDirectories(tmp.resolve("explicit-cwd"));
        Path staleDir = Files.createDirectories(tmp.resolve("stale-cwd"));
        SessionProjectRoot.setForSession(EXPLICIT_SESSION, explicitDir.toString());
        // 残留会话只有 CwdResolution 的 L2 层（SessionCwdHolder）——旧实现（读 MDC）会取到它
        SessionCwdHolder.set(STALE_SESSION, staleDir.toString());

        LocalBashTaskRunner runner = new LocalBashTaskRunner();
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> mdcSeen = new AtomicReference<>();
        AtomicReference<LocalBashTaskRunner.BashResult> result = new AtomicReference<>();

        derived.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            RequestContext.set(STALE_SESSION, "msg-stale-3b"); // 第三态：池化线程残留别的会话
            mdcSeen.set(RequestContext.sessionId());
            try {
                result.set(runner.execute("echo 3b-marker > " + MARKER, null, EXPLICIT_SESSION));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                RequestContext.clear();
            }
            return null;
        }).get(60, TimeUnit.SECONDS);

        assertThat(threadName.get())
            .as("必须在派生线程（BackgroundTaskRunner executor 等价物）执行")
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(mdcSeen.get())
            .as("前置条件：派生线程上存在残留 MDC（第三态）")
            .isEqualTo(STALE_SESSION);
        assertThat(result.get().exitCode()).as("bash 命令必须成功（stderr=%s）", result.get().stderr())
            .isZero();
        assertThat(explicitDir.resolve(MARKER))
            .as("相对路径写入必须落在**显式 sessionId** 的会话 cwd（%s）", explicitDir)
            .exists();
        assertThat(staleDir.resolve(MARKER))
            .as("残留 MDC 指向的会话 cwd（%s）不得被采用 —— 旧实现（读 MDC）会写在这里", staleDir)
            .doesNotExist();
    }
}
