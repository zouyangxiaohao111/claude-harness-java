package com.nexusai.application.agent.context;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 · {@code ClaudemdEngine} 的 eager-load one-shot 必须<b>按会话键控</b>
 * · 对齐 CC {@code claudemd.ts:1092 nextEagerLoadReason} / {@code :1099 shouldFireHook}。
 *
 * <p><b>WHY（可观测语义判据）</b>：这两个 one-shot 决定下一次缓存 miss 时
 * {@code InstructionsLoaded} hook 的 {@code load_reason}（并发给<b>该会话</b>的 hook 事件）。
 * CC 侧是 <b>module-level {@code let}</b> —— 在 CC（单进程单会话）里「模块态」恰好就是「会话态」。
 * 本仓 <b>一 JVM 多会话 + 引擎是 {@code @Bean} 单例</b> ⇒ 原单份 {@code volatile} 会让
 * <b>A 会话压缩置的 'compact' 被 B 会话的下一次缓存 miss 消费</b>：B 的 hook 以
 * {@code load_reason='compact'} 误发（B 根本没压缩），且 B 自己本该发的 'session_start' 被吞掉。
 * 可观测单元 = 该会话的 hook 事件 ⇒ 键 = 显式 sessionId。
 *
 * <p><b>对照（不可会话化的反例）</b>：{@code hasLoggedInitialLoad}（同名文件的 :238 一带）承载
 * {@code tengu_claudemd__initial_load}，语义是「<b>本进程</b>首次加载」⇒ 会话化会改变<b>指标基数</b>
 * （每 JVM 一条 → 每会话一条）⇒ 按 T15 判据<b>不</b>会话化。两条判据的差别已写进生产代码注释。
 */
class ClaudemdEngineCrossSessionIsolationTest {

    private static final String SESSION_A = "sess-claudemd-a";
    private static final String SESSION_B = "sess-claudemd-b";
    /** 夹具自检专用会话（不参与 one-shot 断言，避免消耗 A/B 的 one-shot 态）。 */
    private static final String SESSION_SANITY = "sess-claudemd-sanity";

    @TempDir
    Path workspace;

    private String originalUserHome;

    private static final AtomicInteger APP_NAME_SEQ = new AtomicInteger();

    @AfterEach
    void restoreOverrides() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        // 复位为 null = 撤销 override（对齐 ClaudemdEngineTest:141-143 的复位写法）
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);
    }

    @Test
    @DisplayName("T8: A 会话 reset('compact') 不被 B 会话的缓存 miss 消费（B 仍报 session_start）")
    void resetInSessionA_doesNotLeakToSessionB() throws Exception {
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        ClaudemdEngine engine = newEngine(captured);
        assertFixtureLoadsProjectMemory(engine);

        // A 会话压缩 → 置 one-shot reason='compact'（会话键 = A）
        engine.resetGetMemoryFilesCache("compact", SESSION_A);

        // A 会话下一次缓存 miss → 必须以 'compact' 发射（且 session_id=A）
        engine.getMemoryFiles(false, SESSION_A);
        awaitTrue(() -> captured.stream().anyMatch(e -> isFire(e, SESSION_A, "compact")), 3000);
        assertThat(captured).as("A 会话自身必须拿到 'compact'（本批改动不得破坏正常路径）")
            .anyMatch(e -> isFire(e, SESSION_A, "compact"));

        // B 会话首次加载 → 必须以 'session_start' 发射；⛔ 不得消费 A 的 'compact'
        engine.getMemoryFiles(false, SESSION_B);
        awaitTrue(() -> captured.stream().anyMatch(e -> SESSION_B.equals(e.sessionId())), 3000);
        assertThat(captured)
            .as("B 会话未压缩 ⇒ 必须以 'session_start' 发射（原单份 volatile 实现下 B 会误报 'compact'）")
            .anyMatch(e -> isFire(e, SESSION_B, "session_start"));
        assertThat(captured)
            .as("B 会话不得以 'compact' 发射（跨会话 one-shot 串扰）")
            .noneMatch(e -> isFire(e, SESSION_B, "compact"));
    }

    @Test
    @DisplayName("T8: 同会话 one-shot 幂等（消费一次后缓存 miss 不再发射）")
    void sameSession_isOneShot() throws Exception {
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        ClaudemdEngine engine = newEngine(captured);
        assertFixtureLoadsProjectMemory(engine);

        engine.getMemoryFiles(false, SESSION_A);
        awaitTrue(() -> captured.stream().anyMatch(e -> SESSION_A.equals(e.sessionId())), 3000);
        captured.clear();

        // 清缓存但不 reset one-shot ⇒ 缓存 miss 也不得再发（会话 A 的 fired 闸已落）
        engine.clearMemoryFileCaches();
        engine.getMemoryFiles(false, SESSION_A);
        Thread.sleep(200);
        assertThat(captured).as("同会话 one-shot 已消费 ⇒ 缓存 miss 也不再发射").isEmpty();

        // 而 reset 之后必须恢复发射能力（证明上面的空是 one-shot 而非「永不发」）
        engine.resetGetMemoryFilesCache("compact", SESSION_A);
        engine.clearMemoryFileCaches();
        engine.getMemoryFiles(false, SESSION_A);
        awaitTrue(() -> captured.stream().anyMatch(e -> isFire(e, SESSION_A, "compact")), 3000);
        assertThat(captured).as("reset 后同会话恢复发射（正向对照）")
            .anyMatch(e -> isFire(e, SESSION_A, "compact"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 夹具自检：扫描根下必须真能扫到 Project CLAUDE.md。
     * ⛔ 无此自检时，「hook 一条都没发」会被超时断言表现为假红（无法区分「载体错」与「夹具没文件」）。
     */
    private static void assertFixtureLoadsProjectMemory(ClaudemdEngine engine) {
        assertThat(engine.getMemoryFiles(false, SESSION_SANITY))
            .as("夹具自检：扫描根下必须扫到 Project CLAUDE.md（否则 hook 无文件可发射）")
            .isNotEmpty();
    }

    private static boolean isFire(HookEvent e, String sessionId, String loadReason) {
        // ⚠️ sessionId 是 HookEvent 的**顶层组件**（不是 data() 的 KV 键 —— data() 只含事件特定载荷）
        return HookEventType.INSTRUCTIONS_LOADED.equals(e.type())
            && sessionId.equals(e.sessionId())
            && loadReason.equals(e.data().get("load_reason"));
    }

    /** 构造隔离引擎：扫描根按 sessionId 派生（每会话各自的工作区）+ 捕获 InstructionsLoaded hook。 */
    private ClaudemdEngine newEngine(List<HookEvent> captured) throws Exception {
        Path managed = Files.createTempDirectory("t8-managed");
        Path autoMem = Files.createTempDirectory("t8-auto-mem");
        Path memoryBase = Files.createTempDirectory("t8-memory-base");
        Path userHome = Files.createTempDirectory("t8-user-home");
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", userHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-t8-" + APP_NAME_SEQ.incrementAndGet());
        String configHome = NexusaiPaths.getAppConfigHomeDir();
        ClaudePaths.setConfigDirOverride(configHome);
        ClaudePaths.setManagedFilePathOverride(managed.toString());

        for (String sid : List.of(SESSION_A, SESSION_B, SESSION_SANITY)) {
            Path sessionRoot = Files.createDirectories(workspace.resolve(sid));
            Files.writeString(sessionRoot.resolve("CLAUDE.md"), "# " + sid + "\n");
        }

        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> workspace.toString(),
            () -> memoryBase.toString(),
            () -> autoMem.toString() + java.io.File.separator,
            () -> null);
        MemoryFileDetection detection = new MemoryFileDetection(
            autoMemPaths, () -> configHome, () -> true, () -> false, () -> true);

        HookRegistry registry = new HookRegistry();
        registry.register("t8-capture",
            event -> {
                captured.add(event);
                return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed();
            },
            HookEventType.INSTRUCTIONS_LOADED);

        ClaudemdEngine engine = new ClaudemdEngine(autoMemPaths, detection,
            // 扫描根按 sessionId 派生 ⇒ 每会话一个缓存键（否则 B 命中 A 的缓存、根本不会 miss）
            sessionId -> workspace.resolve(sessionId == null ? "null" : sessionId).toString(),
            () -> true, () -> true, () -> true, () -> false, () -> List.of());
        engine.setHookRegistry(registry);
        return engine;
    }

    private static void awaitTrue(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("异步条件超时未满足 (timeoutMs=" + timeoutMs + ")");
    }
}
