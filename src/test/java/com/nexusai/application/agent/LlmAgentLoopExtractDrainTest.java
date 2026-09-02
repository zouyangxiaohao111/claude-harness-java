package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-M-R2-P1-EX] extract drain 触发点 loop 级契约测试（EX-01/OPD-R2-EX-01）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC print.ts:962-969 仅在 headless（-p/SDK）
 * 退出路径 drain（响应 flush 后、shutdown 前）；交互式 REPL 不等待，fork 与下轮并发。
 * Java Web 端（非交互会话语义）对齐：每轮退出不阻塞 —— LlmAgentLoop 轮次退出处不得同步等待
 * in-flight 提取（旧 drainPendingExtraction(60_000) 每轮阻塞至多 60s → Web 会话 turn 结束延迟，
 * 并发模型/延迟语义偏移，EV-026）。本测试经真实 loop + 阻塞式提取 fork 验证：
 * <ol>
 *   <li><b>每轮退出不阻塞</b>: 提取 fork 阻塞在 latch 上（模拟慢提取）时，loop.run() 必须
 *       照常返回（旧 drain 代码 → loop 卡在 60s 同步等待 → join 超时 RED）。</li>
 *   <li><b>提取仍在后台运行</b>: loop 返回后 fork 已启动（awaitStarted）且未完成（latch
 *       未释放），release 后完成 —— headless 类退出路径 drain 能力保留于
 *       ExtractMemoriesAgent @PreDestroy shutdown / drainPendingExtraction API。</li>
 * </ol>
 */
@DisplayName("[IMP-M-R2-P1-EX] extract drain 触发点（EX-01：每轮退出不阻塞）")
class LlmAgentLoopExtractDrainTest {

    /** 阻塞式提取 fork：首调阻塞在 release latch（慢提取模拟）。 */
    static final class BlockingExtractQuery implements RunForkedAgent.ForkedQuery {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean released;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ForkedAgentResult(params.messages(), ForkedAgentResult.ForkUsage.empty());
        }

        boolean awaitStarted(long ms) throws InterruptedException {
            return started.await(ms, TimeUnit.MILLISECONDS);
        }

        void release() {
            released = true;
            release.countDown();
        }

        boolean isReleased() {
            return released;
        }
    }

    /**
     * 驱动真实 loop 单轮（主 provider 纯文本 stop）· s09 触发提取（env 门全开 +
     * setExtractMemoriesAgent 注入阻塞 fork）。loop.run() 在独立线程执行，join(5s) 断言
     * 每轮退出不阻塞（旧 drain 代码下 join 超时）。
     *
     * @return loop.run() 返回的终态（从 loop 线程带回）
     */
    private static AgentState runTurnWithBlockingExtraction(Path memDir, BlockingExtractQuery query)
            throws Exception {
        MemoryStorage storage = new MemoryStorage(memDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmProvider mainProvider = mock(LlmProvider.class);
        doAnswer(inv -> {
            // 单轮纯文本 stop → needsFollowUp=false → 正常退出（s09 可达）。
            // blocks 重载 18-arg 参数位：9=onChunk 10=onAssistantMessage 16=onComplete
            // （LlmProvider.java:340-356 + ModelCaller.call 逐字段透传）。
            Consumer<String> onChunk = inv.getArgument(9);
            onChunk.accept("Done");
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            onMsg.accept(new AssistantMessage("Done", "stop", List.of()));
            Runnable onComplete = inv.getArgument(16);
            onComplete.run();
            return null;
        }).when(mainProvider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(factory.getProvider(any(), any())).thenReturn(mainProvider);

        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        loop.setExtractMemoriesAgent(agent);

        // agentId=null = 主线程会话（ChatService:207-210 同款）；s09 门控 state.agentId()==null
        RunRequest request = RunRequest.session("hello", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null,
            ProviderConfig.empty(), "test-model", null, null);
        AtomicReference<AgentState> stateRef = new AtomicReference<>();
        Thread loopThread = new Thread(() -> stateRef.set(loop.run(request)), "ex-drain-loop");
        loopThread.start();
        loopThread.join(5000);
        assertThat(loopThread.isAlive())
            .as("loop.run() 必须在 5s 内返回（每轮退出不阻塞 · EX-01，旧 drain 下 join 超时）")
            .isFalse();
        return stateRef.get();
    }

    @Test
    @DisplayName("EX-01: 提取 fork 在跑时 loop.run() 返回（每轮退出不阻塞）；fork 后台继续，release 后完成")
    void turnExit_doesNotBlockOnInflightExtraction(@TempDir Path memDir) throws Exception {
        // WHY: 旧 LlmAgentLoop:4818-4821 每轮退出同步 drainPendingExtraction(60_000) ——
        //   提取 fork 未完成时 turn 结束阻塞至多 60s（Web 会话延迟）；CC 交互式不等待、
        //   仅 headless 退出路径 drain（print.ts:962-969）。本测试的阻塞 fork 即 RED 触发器：
        //   旧代码下 loop 线程 join(5s) 超时，新代码（轮次退出不 drain）立即返回。
        String enabled = "NEXUSAI_EXTRACT_MEMORIES";
        String nonInteractive = "NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE";
        System.setProperty(enabled, "true");
        System.setProperty(nonInteractive, "true");
        try {
            BlockingExtractQuery query = new BlockingExtractQuery();
            AgentState state = runTurnWithBlockingExtraction(memDir, query);

            assertThat(state).as("run() 必须返回非 null 终态").isNotNull();
            assertThat(query.awaitStarted(2000))
                .as("s09 必须已触发提取（fire-and-forget 后台启动）").isTrue();
            assertThat(query.isReleased())
                .as("loop 返回时提取 fork 必须仍在运行（latch 未释放）—— 每轮退出未等待它").isFalse();

            // release 后提取完成（drain 能力保留于 @PreDestroy shutdown / drainPendingExtraction）
            query.release();
        } finally {
            System.clearProperty(enabled);
            System.clearProperty(nonInteractive);
        }
    }
}
