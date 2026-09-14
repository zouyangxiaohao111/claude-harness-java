package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [F-18 · G5-fake-guards] <b>调用点</b>覆盖：{@code doRun → resolveSessionProjectRoot(params.boundProject())}
 * 的写入端以 {@code run(RunRequest)} 为唯一入口。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：本仓一 JVM 多会话 ⇒ 会话项目根必须按 sessionId
 * 解析；而 cron DURABLE fire 的 {@code streamSessionId} 可为 null（创建会话已关 → headless），故额外
 * 需要一条与会话无关的<b>显式项目锚</b>通道 {@code RunRequest.boundProject()}。这条值传递腿是
 * <b>本仓架构特有</b>（CC 的 projectRoot 是模块级 process-global，不存在「按 run 携带的锚逐 run 解析」
 * 的调用点；方案自标 {@code ccHasCounterpart=false}），只能由「经 {@code run()} 走一遍」证明。
 *
 * <p><b>被补的缺口（本类存在的全部理由）</b>：既有两个反射用例
 * （{@link AutoMemoryCronAnchorPlumbingTest}、{@link LlmAgentLoopSessionProjectRootFreezeTest}）
 * 都<b>直接反射调 {@code resolveSessionProjectRoot(String)}</b> —— 它们覆盖被调用方<b>内部</b>，
 * 但 {@code doRun} 里那一行
 * {@code resolveSessionProjectRoot(params.boundProject())}（LlmAgentLoop.java:2443）<b>从未被任何
 * 用例执行过</b>：若该调用点把实参写成 {@code null} / 或不再传 {@code params.boundProject()}，
 * 两个既有反射用例<b>仍全绿</b>（它们不经过 run），而生产锚会静默失效。本类的第一条断言必须以
 * {@code run()} 为唯一入口，正是为堵这个洞。
 *
 * <p><b>RED 条件（反向实验 · 打调用点）</b>：把 {@code LlmAgentLoop:2443} 改为
 * {@code resolveSessionProjectRoot(null)} ⇒ 本类主用例的 {@code workspaceDir} 断言必红
 * （锚分支不再命中 → {@code streamSessionId} 为 null → 提前 return → {@code workspaceDir} 保持 null），
 * 而两个既有反射用例<b>仍全绿</b>。第二反向实验：把 {@code RunRequest.withBoundProject} 改成忽略入参
 * （{@code return this}）⇒ 主用例同样必红（覆盖 RunRequest 侧的半条腿）。
 */
@DisplayName("[F-18] run(RunRequest.boundProject) → workspaceDir 调用点接线")
class LlmAgentLoopRunBoundProjectWiringTest {

    @TempDir
    Path tempDir;

    /**
     * 主用例：<b>以 {@code run()} 为唯一入口</b>把显式项目锚喂进 loop ⇒ {@code workspaceDir} 锚该值。
     *
     * <p>驱动形态对齐 CC durable cron fire 的 headless 面：{@code RunRequest.forTest(...)} 的
     * {@code sessionId=null}（无会话键）—— 此时若锚未抵达，解析器<b>没有任何会话通道可以兜底</b>
     * （{@code resolveSessionProjectRoot} 在 {@code streamSessionId} 为 null 时直接 return），
     * ⇒ 断言具备判别力：锚不是「顺带被会话路径解析出来」的。
     */
    @Test
    @DisplayName("run(RunRequest.withBoundProject) ⇒ workspaceDir == 归一化锚值（唯一入口是 run）")
    void run_withBoundProject_workspaceDirEqualsAnchor() throws java.io.IOException {
        Path anchor = Files.createDirectories(tempDir.resolve("proj-anchor-run"));
        Path expected = canonical(anchor);
        LlmAgentLoop loop = newLoop();

        // ── 前置断言：构造期无锚 → workspaceDir 为 null（默认值即「无有效项目」）──
        assertThat(loop.workspaceDir())
            .as("前置：构造期（未 run）workspaceDir 必须为 null —— 否则下面的断言可能是"
                + "「恒非 null 就绿」的假装置")
            .isNull();

        AgentState state = loop.run(
            RunRequest.forTest("hello", "test-model", null).withBoundProject(anchor.toString()));

        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        assertThat(loop.workspaceDir())
            .as("doRun 必须把 params.boundProject() 交给 resolveSessionProjectRoot（LlmAgentLoop:2443）"
                + "⇒ 显式锚分支命中 ⇒ workspaceDir = 归一化锚值。"
                + "反向实验：把 :2443 改成 resolveSessionProjectRoot(null) ⇒ 本断言红"
                + "（无 streamSessionId 时提前 return，workspaceDir 保持 null）")
            .isEqualTo(expected);

        // ── 第二次断言（下游腿）：锚必须真的登记进 auto-memory 消费的载体 ──
        assertThat(buildSessionStateReflectively(loop).explicitProjectAnchor())
            .as("run() 之后 buildSessionStateFromInstance 透传的 explicitProjectAnchor 必须 = 锚"
                + "（auto-memory 在 headless fire 下唯一能拿到项目根的载体；只到 workspaceDir 不够）")
            .isEqualTo(expected);
    }

    /**
     * 对照（修正后装置）：<b>新建 loop 实例</b> + 无锚 run ⇒ 其 {@code workspaceDir} 不等于锚。
     *
     * <p><b>WHY 必须新建实例（复核更正，按原字面落地会引入一个新的红测试）</b>：{@code resolveSessionProjectRoot}
     * 的<b>无锚 + 无 sessionId</b> 分支在 {@code streamSessionId} 为 null 时<b>直接 return 且不触碰
     * workspaceDir</b>（LlmAgentLoop.java:11183-11190）—— 同一实例第二次 run 后 {@code workspaceDir}
     * 仍保留首次 run 的锚 ⇒「同实例再 run 无锚 ⇒ isNotEqualTo(anchor)」<b>必失败</b>。
     * 且全类 {@code this.workspaceDir = } 仅 5 处赋值，<b>无一处赋 null</b> ⇒ 锚不可能被「跑回 null」。
     */
    @Test
    @DisplayName("对照：新建 loop 实例 + 无锚 run ⇒ workspaceDir 不等于锚（排除「恒非 null 就绿」）")
    void run_withoutBoundProject_freshLoop_workspaceDirNotAnchor() throws java.io.IOException {
        Path anchor = Files.createDirectories(tempDir.resolve("proj-anchor-control"));
        Path expected = canonical(anchor);
        // 有锚的实例：证明锚确实会落到实例上（同一断言装置的正向半边）
        LlmAgentLoop anchored = newLoop();
        anchored.run(RunRequest.forTest("hello", "test-model", null).withBoundProject(anchor.toString()));
        assertThat(anchored.workspaceDir())
            .as("正向半边：装置在有锚时确实能检出锚（证明下面的否定断言具备判别力、非恒绿）")
            .isEqualTo(expected);

        // 无锚的**新**实例：同一个 RunRequest 形状，唯一变量 = 锚
        LlmAgentLoop fresh = newLoop();
        fresh.run(RunRequest.forTest("hello", "test-model", null));

        assertThat(fresh.workspaceDir())
            .as("无锚 run 不得得到锚值（锚不是「任何 run 都会产生」的常量）")
            .isNotEqualTo(expected);
        assertThat(fresh.workspaceDir())
            .as("无锚 + 无 streamSessionId ⇒ 无有效项目：workspaceDir 保持 null"
                + "（⛔ 绝不回落 configHome / user.dir 冒充项目根）")
            .isNull();
    }

    // ── helpers ──────────────────────────────────────────────

    /**
     * 真实 loop + mocked provider（无 toolRegistry）· 驱动形态镜像
     * {@code LlmAgentLoopRunRequestContractTest:56-91}。
     *
     * <p>⚠️ provider 桩必须逐参对齐 {@code LlmProvider.stream} 的<b>当前</b> arity：本仓该接口有
     * 3 个重载 = 19（blocks 抽象）/19（String+thinkingConfig）/20（blocks+thinkingConfig）
     * ⇒ 位置索引的桩按 {@code args.length == 20 ? big : small} 判档，否则静默不命中。
     * 这里的 19 参桩命中<b>抽象 blocks 重载</b>（位置 3 为 {@code anyList()}），
     * 取 {@code getArgument(9/10/16)} = onChunk / onAssistantMessage / onComplete。
     */
    private static LlmAgentLoop newLoop() {
        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return new LlmAgentLoop(factory);
    }

    /**
     * 与生产 {@code LlmAgentLoop.normalizeSessionProjectRoot} 同形的归一（realpath + NFC）。
     *
     * <p>⚠️ 两边必须同做：Windows 上 {@code @TempDir} 的短路径（8.3）/ 大小写与
     * {@code toRealPath()} 产出不一致 ⇒ 只归一一边会假红。
     */
    private static Path canonical(Path p) throws java.io.IOException {
        return Path.of(ClaudePaths.normalizeNfc(p.toRealPath().toString()));
    }

    /** 反射读实例透传点（{@code buildSessionStateFromInstance} 是 private）。 */
    private static AgentLoopContext.LoopSessionState buildSessionStateReflectively(LlmAgentLoop loop) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("buildSessionStateFromInstance");
            m.setAccessible(true);
            return (AgentLoopContext.LoopSessionState) m.invoke(loop);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 buildSessionStateFromInstance 失败", e);
        }
    }
}
