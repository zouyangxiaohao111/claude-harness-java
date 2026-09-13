package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.memory.AutoMemPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 2 · B 类] spawn 作用域 projectRoot「自赋值空转」清理守卫。
 *
 * <p><b>缺陷</b>：三处 spawn/降级作用域写成
 * {@code captureCurrentProjectRoot()} + {@code setCurrentProjectRoot(刚 capture 的同一个值)} ——
 * 注释声称「注入会话 projectRoot（修 M-05/M-06）」，实语句<b>恒空转</b>
 * （{@code AutoMemPaths:104-121}：capture 返回 ThreadLocal 现值，set 相同值/相同 null 均不改变状态），
 * 会话 projectRoot 的真实注入源在 spawn 之外（{@code StreamingToolExecutor:2465} 调度线程捕获 →
 * 任务体线程注入；{@code SubagentTool:3201/:3745} async/resume worker 注入父值）。已删除该 set，
 * 保留的 capture/restore 成对语义校正为「退出复位」。
 *
 * <p><b>为什么不能写成更强的运行时断言（诚实标注）</b>：删除的语句<b>按构造即无副作用</b>
 * （见用例 1），因此不存在「删掉它导致某运行时行为改变」的可观测差异 —— 任何声称「跑一遍子代理
 * 就能验出删除生效」的断言都是假的鉴别力。故本类由两部分组成：
 * <ol>
 *   <li><b>运行时</b>：证明自赋值恒等（= 删除行为中性）——这是删除的<b>前提</b>，也是唯一可验的运行时性质；</li>
 *   <li><b>源级防回归守卫</b>：断言三处调用点不再出现 {@code setXxx(capture 的同一个变量)} 惯用法，
 *       且成对 capture/restore 仍在。它只守「不重新引入该空转惯用法」，<b>不</b>声称守护运行时行为。</li>
 * </ol>
 */
@DisplayName("[批 2 · B] spawn 作用域 projectRoot 自赋值空转清理守卫")
class SpawnProjectRootScopeHygieneTest {

    /** 自赋值惯用法：setCurrentProjectRoot(<上一个 capture 进 prev* 变量的值>)。 */
    private static final Pattern SELF_ASSIGN_PATTERN =
        Pattern.compile("setCurrentProjectRoot\\s*\\(\\s*prev[A-Za-z0-9_]*\\s*\\)");

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. 运行时：自赋值恒等（删除的前提 · 池线程上验证）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("capture()+set(同一值) 在池线程上恒等（无 set 与有 set 状态一致 → 删除行为中性）")
    void selfAssignProjectRootSet_isNoOpByConstruction() throws Exception {
        // WHY: 这是 B 类删除的唯一可验运行时性质：若某天 AutoMemPaths 语义变成非幂等
        //   （例如 capture 带缓存/规范化），本用例变红 → 提示「空转」结论失效、需重新评估删除。
        pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-spawn-scope-pool"));

        AtomicReference<String> unsetBefore = new AtomicReference<>();
        AtomicReference<String> unsetAfter = new AtomicReference<>();
        AtomicReference<String> setBefore = new AtomicReference<>();
        AtomicReference<String> setAfter = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        pool.execute(() -> {
            try {
                // (a) ThreadLocal 未设置：capture=null → set(null) 仍是「未设置」
                unsetBefore.set(AutoMemPaths.captureCurrentProjectRoot());
                AutoMemPaths.setCurrentProjectRoot(AutoMemPaths.captureCurrentProjectRoot());
                unsetAfter.set(AutoMemPaths.captureCurrentProjectRoot());

                // (b) ThreadLocal 已设置 X：capture=X → set(X) 仍是 X
                AutoMemPaths.setCurrentProjectRoot("/tmp/spawn-scope-probe");
                setBefore.set(AutoMemPaths.captureCurrentProjectRoot());
                AutoMemPaths.setCurrentProjectRoot(AutoMemPaths.captureCurrentProjectRoot());
                setAfter.set(AutoMemPaths.captureCurrentProjectRoot());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                AutoMemPaths.resetCurrentProjectRoot();
                done.countDown();
            }
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertThat(unsetBefore.get()).as("未设置时 capture=null").isNull();
        assertThat(unsetAfter.get()).as("set(capture()) 后仍为 null（未设置）").isNull();
        assertThat(setBefore.get()).as("已设置时 capture=X").isEqualTo("/tmp/spawn-scope-probe");
        assertThat(setAfter.get()).as("set(capture()) 后仍为 X").isEqualTo("/tmp/spawn-scope-probe");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. 源级防回归守卫：三处调用点不再出现自赋值惯用法
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[源级守卫] SubagentExecutor / SubagentTool 无 setCurrentProjectRoot(prev*) 惯用法 + 成对 capture/restore 仍在")
    void spawnScopes_haveNoSelfAssignProjectRootSet() throws Exception {
        String executor = readSource(
            "src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java");
        String tool = readSource(
            "src/main/java/com/nexusai/application/agent/tool/impl/SubagentTool.java");

        assertNoSelfAssign(executor, "SubagentExecutor");
        assertNoSelfAssign(tool, "SubagentTool");

        // 保留的「退出复位」成对结构仍在（防误删：复位是池化线程防串台的真实守卫）
        assertThat(executor)
            .as("SubagentExecutor 的 capture/restore 成对必须保留")
            .contains("AutoMemPaths.captureCurrentProjectRoot()")
            .contains("AutoMemPaths.restoreCurrentProjectRoot(prevSubagentProjectRoot)");
        assertThat(tool)
            .as("SubagentTool 三处 capture/restore 成对必须保留（sync / async / 降级 / resume）")
            .contains("AutoMemPaths.restoreCurrentProjectRoot(prevSyncProjectRoot)")
            .contains("AutoMemPaths.restoreCurrentProjectRoot(prevAsyncProjectRoot)")
            .contains("AutoMemPaths.restoreCurrentProjectRoot(prevFallbackProjectRoot)")
            .contains("AutoMemPaths.restoreCurrentProjectRoot(prevResumeProjectRoot)");
        // 真实注入点仍在（async worker / resume worker 注入父值 — 这些是「同值成对」以外的真注入）
        assertThat(tool)
            .as("async worker 真注入父 projectRoot 仍在")
            .contains("AutoMemPaths.setCurrentProjectRoot(parentProjectRoot)");
    }

    private static void assertNoSelfAssign(String source, String label) {
        Matcher m = SELF_ASSIGN_PATTERN.matcher(source);
        java.util.List<String> hits = new java.util.ArrayList<>();
        while (m.find()) {
            hits.add(m.group());
        }
        assertThat(hits)
            .as("%s 不得再有 setCurrentProjectRoot(prev*) 自赋值空转", label)
            .isEmpty();
    }

    private static String readSource(String relativePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader =
                 new java.io.BufferedReader(new java.io.FileReader(relativePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
