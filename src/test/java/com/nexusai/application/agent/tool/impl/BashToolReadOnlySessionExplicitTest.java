package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 3b] BashTool RO-17c（沙箱只读守卫「cwd ≠ orig-cwd」）的<b>两侧会话源</b>都必须来自
 * 显式 {@code ToolUseContext}，且在<b>真实派生线程</b>上的残留 MDC（第三态）不得改变它们。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：RO-17c（readOnlyValidation.ts:1956-1966）是一个
 * <b>成对</b>判定，两侧必须同源：
 * <pre>
 *   cwd        = ctx.effectiveCwd() ?? CwdResolution.getCwd(sessionId)   ← BashTool.fallbackCwd
 *   originalCwd= CwdResolution.getOriginalCwdLayer(sessionId)            ← BashTool.gitReadOnlyGuardBlocked
 * </pre>
 * 批 3b 之前两处都读「当前线程的 ambient 会话槽」（裸 MDC）——该判定在 tool-exec 池线程执行，
 * 该槽恒 null 或残留该池线程上一个任务的<b>别的会话</b> id（第三态）。批 3b 把两侧都改为显式 ctx。
 * [批 3c] 该 ambient 槽已整类删除 ⇒ 「有无残留值」的对照臂消失（见各用例内 `[批 3c]` 注释）。
 *
 * <p><b>为什么直接反射调用私有方法而不是走 {@code isReadOnly}</b>：{@code isReadOnly} 的最后一步
 * {@code BashParser.parseForReadOnly} 对本仓的 git 命令恒 false（git 不在只读白名单，fail-closed），
 * 于是守卫 verdict 的 true/false 会被<b>吸收</b>——走 isReadOnly 的断言在「修复前/修复后」都得到
 * false，是<b>零鉴别力</b>的假绿。故本测试直接对被测方法本体取样（真线程 + 真判定）。
 *
 * <p><b>鉴别力来源</b>：{@code CwdResolution.getCwd(X)} 有 L2（{@code SessionCwdHolder}）层，
 * {@code getOriginalCwdLayer(X)} 没有 —— 让「另一个会话」只有 L2 cwd 时，任何按该会话解析的实现
 * 都会两侧取自不同源 ⇒ 误判 cwd ≠ orig-cwd ⇒ 守卫误触发。本测试断言：两侧输出必须同源。
 */
@DisplayName("批 3b · BashTool RO-17c 成对会话源 = 显式 ctx（真实派生线程）")
class BashToolReadOnlySessionExplicitTest {

    private static final String EXPLICIT_SESSION = "sess-ro17c-explicit-3b";
    private static final String STALE_SESSION = "sess-ro17c-stale-3b";

    private final ExecutorService derived = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "batch3b-bash");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        derived.shutdownNow();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    private static BashTool toolWithSandboxEnabled() {
        BashTool tool = new BashTool();
        try {
            Field f = BashTool.class.getDeclaredField("sandboxManager");
            f.setAccessible(true);
            // 沙箱启用（RO-17c 的前置闸）
            f.set(tool, new SandboxManager(true, true, true, List.of(), () -> true, () -> true));
        } catch (Exception e) {
            throw new IllegalStateException("反射注入 BashTool.sandboxManager 失败", e);
        }
        return tool;
    }

    private static ToolUseContext ctxWithoutEffectiveCwd(String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()),
            PermissionMode.DEFAULT);
    }

    private static boolean guardFired(BashTool tool, ToolUseContext ctx) throws Exception {
        Method m = BashTool.class.getDeclaredMethod("gitReadOnlyGuardBlocked", String.class, ToolUseContext.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(tool, "git status", ctx);
    }

    private static String cwdBasis(BashTool tool, ToolUseContext ctx) throws Exception {
        Method m = BashTool.class.getDeclaredMethod("fallbackCwd", ToolUseContext.class);
        m.setAccessible(true);
        return (String) m.invoke(tool, ctx);
    }

    /**
     * 在派生线程求值。
     *
     * <p>[批 3c] 原签名带 {@code staleSessionOrNull} / {@code mdcSeen} 两参，用于「线程内写残留
     * ambient 会话值 + 捕获该线程读到的值」这一反向对照装置 —— ambient 会话槽（裸 MDC）已整类
     * 删除 ⇒ 装置与相关前置条件断言删除，本方法只保留「在派生线程求值」的语义。
     */
    private static <T> T onDerivedThread(ExecutorService pool,
                                         AtomicReference<String> threadName,
                                         Supplier<T> body) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        pool.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            try {
                out.set(body.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("派生线程任务 10s 未完成");
        }
        if (err.get() != null) {
            throw new AssertionError("派生线程任务抛异常", err.get());
        }
        return out.get();
    }

    @Test
    @DisplayName("① fallbackCwd：cwd 取自显式 ctx 会话（不读任何 ambient 会话）—— 另一会话的 L2 cwd 不得被采用")
    void fallbackCwd_explicitCtxSession_notResidualMdc(@TempDir Path tmp) throws Exception {
        Path explicitProject = Files.createDirectories(tmp.resolve("explicit-project"));
        Path staleL2Cwd = Files.createDirectories(tmp.resolve("stale-l2-cwd"));
        SessionProjectRoot.setForSession(EXPLICIT_SESSION, explicitProject.toString());
        // 另一会话只有 CwdResolution 的 L2 层（SessionCwdHolder）—— 若实现按 ambient 会话解析会取到它
        SessionCwdHolder.set(STALE_SESSION, staleL2Cwd.toString());

        BashTool tool = toolWithSandboxEnabled();
        ToolUseContext ctx = ctxWithoutEffectiveCwd(EXPLICIT_SESSION);

        AtomicReference<String> threadName = new AtomicReference<>();
        // [批 3c] 语义消失：原此处在该派生线程上写「别的会话」的裸 MDC 值当反向对照，并断言该诱饵
        //   确实存在（前置条件）。ambient 会话槽已整类删除 ⇒ 装置与前置条件断言删除；
        //   「另一会话持有自己的 L2 cwd」这一诱饵仍保留，故下面的结果断言仍有鉴别力。
        String cwd = onDerivedThread(derived, threadName,
            () -> {
                try {
                    return cwdBasis(tool, ctx);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

        assertThat(threadName.get())
            .as("必须在派生线程（tool-exec 池线程等价物）求值")
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(cwd)
            .as("兜底 cwd 必须来自显式 ctx 会话（boundProject=%s）；不得采用另一会话的 L2 cwd %s",
                explicitProject, staleL2Cwd)
            .isEqualTo(CwdResolution.normalizeCwd(explicitProject.toString()));
    }

    @Test
    @DisplayName("② RO-17c 判定（ctx=null，即 isReadOnly 真实路径）：无会话 ⇒ cwd/originalCwd 同源 ⇒ 不触发守卫")
    void ro17c_ctxNull_verdictWithNoSession(@TempDir Path tmp) throws Exception {
        Path staleL2Cwd = Files.createDirectories(tmp.resolve("stale-l2-cwd"));
        // 另一会话只有 CwdResolution 的 L2 层（SessionCwdHolder）——它不得影响无会话路径的判定。
        SessionCwdHolder.set(STALE_SESSION, staleL2Cwd.toString());

        BashTool tool = toolWithSandboxEnabled();

        AtomicReference<String> tA = new AtomicReference<>();
        // [批 3c] 语义消失：原用例是「对照臂（无残留 MDC）vs 实验臂（有残留 MDC）」两条臂，
        //   断言残留值不得把守卫误触发为 true。ambient 会话槽已整类删除 ⇒ 两条臂在结构上完全等价，
        //   实验臂与「前置条件：有残留 MDC」断言删除；保留臂仍钉住「ctx=null ⇒ cwd 与 originalCwd
        //   同源（同为 user.dir）⇒ 守卫不触发」这一实质契约。
        Boolean fired = onDerivedThread(derived, tA, () -> {
            try {
                return guardFired(tool, null);   // ctx=null：isReadOnly 的真实调用形态
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(tA.get())
            .as("必须在派生线程（tool-exec 池线程等价物）求值")
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(fired)
            .as("无会话（ctx=null）⇒ cwd/originalCwd 同为 user.dir ⇒ 不触发 RO-17c"
                + "（另一会话的 L2 cwd=%s 不得参与）", staleL2Cwd)
            .isFalse();
    }
}
