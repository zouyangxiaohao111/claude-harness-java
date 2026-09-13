package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * STREAM_EXECUTOR 虚拟线程「跨线程状态传播」实证。
 *
 * <h2>[批 3c · 2026-09-13] 本类的 MDC 部分已被删除（不是静默删断言，是主题消失）</h2>
 * <p>原类名 {@code LlmAgentLoopMdcReplayTest}，含两个用例：① MDC 回放（断言虚拟线程内
 * 虚拟线程内的会话标识等于 loop 线程值（旧 MDC 槽，已删））；② projectRoot 回放（现存的这一个）。
 * 用例 ① 的<b>主题随批 3c 消失</b>：会话标识不再走任何环境态会话槽（MDC 工具类、其清理 Filter、
 * logback {@code %X{sessionId}} 前缀已一并删除），改为显式传参 + 进程级判定 ⇒ 「虚拟线程回放 MDC」
 * 这件事本身已不存在，装置也从 {@code LlmAgentLoop} 中删除。
 * <b>它不是被删掉的证据</b>：替换它的守卫是
 * {@code ProviderSessionIdWiringGuardTest}（判据已加强为「注入链上不得读任何环境态会话槽」，
 * 覆盖 {@code RequestContext} / {@code MDC.get} / {@code AutoMemPaths.currentSessionProjectRoot}），
 * 以及 {@code TaskSystemConfigProcessLevelTest.freshThread_seesSameResult_processLevel}
 * （真线程断言判定不随线程变 —— 若判定退化回线程局部槽即转红）。
 *
 * <h2>现存用例 ②：projectRoot 回放（仍承重 · 归批 4）</h2>
 * <p>{@code AutoMemPaths.CURRENT_PROJECT_ROOT} 是本仓<b>现存最后一个环境态会话槽</b>（见该字段
 * javadoc 的「批 4 待收敛」清单）。{@code STREAM_EXECUTOR} 是虚拟线程池
 * （{@code Executors.newVirtualThreadPerTaskExecutor()}，LlmAgentLoop:177-178），
 * <b>虚拟线程不继承创建线程的 ThreadLocal</b>，故 loop 线程捕获会话 projectRoot → 任务体开头
 * {@code setCurrentProjectRoot} → {@code finally restoreCurrentProjectRoot}，
 * 使流式链路（{@code provider.stream} 所在线程）内的内存/路径解析命中会话绑定 P 而非回落值。
 *
 * <p>本测试<b>驱动真实 queryLoop</b>（同 WiringOrderTest 路径，mock {@code provider.stream}
 * 在虚拟线程执行），在虚拟线程内捕获 {@code AutoMemPaths.currentSessionProjectRoot()}。
 * <b>回归保护</b>：若该回放被移除，本测试变红（虚拟线程读到回落值）。
 */
class LlmAgentLoopStreamThreadReplayTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @Test
    @DisplayName("STREAM_EXECUTOR 虚拟线程 projectRoot 回放: stream 内 currentSessionProjectRoot()==loop 线程注入值（IMP-A F3）")
    void streamExecutorThread_seesReplayedProjectRoot() throws Exception {
        // ── 1. 模拟 run() 入口 resolveSessionProjectRoot 注入（ODF-A1）：loop 线程持有会话 projectRoot ──
        Path sessionRoot = Files.createDirectories(tempDir.resolve("session-proj"));
        AutoMemPaths.setCurrentProjectRoot(sessionRoot.toString());
        try {
            // ── 2. provider mock · stream 在 STREAM_EXECUTOR 虚拟线程内执行（F3 回放消费侧同线程取值）──
            AtomicReference<String> capturedProjectRootInStreamThread = new AtomicReference<>(null);
            LlmProvider provider = Mockito.mock(LlmProvider.class);
            Mockito.doAnswer(inv -> {
                // 在虚拟线程内捕获 projectRoot（post-compaction consume 等消费链同线程读
                // AutoMemPaths.currentSessionProjectRoot()；虚拟线程不继承 ThreadLocal → 无回放则为回落值）
                capturedProjectRootInStreamThread.set(AutoMemPaths.currentSessionProjectRoot());
                java.util.function.Consumer<String> onChunk = inv.getArgument(9);
                java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
                Runnable onComplete = inv.getArgument(16);
                onChunk.accept("response");
                onMsg.accept(new AssistantMessage("response", "end_turn", List.of()));
                onComplete.run();
                return null;
            }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any());
            LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
            when(factory.getProvider(any(), any())).thenReturn(provider);

            AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
            LoopDeps deps = new LoopDeps() {
                @Override public AgentLoopContext context() { return ctx; }
                @Override public boolean isMainLoop() { return true; }
            };

            // ── 3. 驱动真实 queryLoop（同 projectRoot 回放测试路径）──
            AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
            QueryParams callerParams1 = QueryParams.forLoop(state.rawMessages(), null,
                    ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                    QuerySource.USER, "test-model", null, null, null, null, null,
                    deps, ProviderConfig.empty());
            LoopResult result = LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams1.deps().context(), callerParams1, state),
                state, new ArrayList<>());

            // ── 4. 断言: 流线程读到 loop 线程注入的会话 projectRoot（F3 回放生效）──
            assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
            assertThat(capturedProjectRootInStreamThread.get())
                .as("STREAM_EXECUTOR 虚拟线程必须经 F3 回放拿到会话 projectRoot（否则读回落值 CLAUDE_PROJECT_DIR env ?? config-home）")
                .isEqualTo(sessionRoot.toString());
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
        }
    }
}
