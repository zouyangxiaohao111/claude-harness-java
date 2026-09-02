package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
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
 * [OD-17 再思考 · 线程断点] STREAM_EXECUTOR 虚拟线程 MDC 回放实证。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：OD-17 消费侧
 * {@code AnthropicSdkProvider.consumePostCompactionAtApiSuccess}（doStream 尾部）运行在
 * LlmAgentLoop:3150 STREAM_EXECUTOR 虚拟线程上（{@code Executors.newVirtualThreadPerTaskExecutor()}，
 * LlmAgentLoop:177-178）。虚拟线程<b>不继承创建线程的 ThreadLocal</b>（logback MDC 为普通
 * ThreadLocal），故无 MDC 回放时流线程 {@code RequestContext.sessionId()=null} → consume 归一化
 * 后仍拿 null → 回落进程级默认布尔，isPostCompaction 永不触发。LlmAgentLoop:3150 已修复：loop
 * 线程（ChatService:120 已设 MDC，raw "sess-xxx"）捕获 MDC context map → 虚拟线程入口
 * setContextMap 回放 → finally clear。
 *
 * <p>本测试<b>驱动真实 queryLoop</b>（同 WiringOrderTest 路径，mock provider.stream 在虚拟线程执行），
 * 在 STREAM_EXECUTOR 虚拟线程内捕获 {@code RequestContext.sessionId()}：
 * <ol>
 *   <li>测试线程设 MDC（模拟 ChatService:120 入口）</li>
 *   <li>queryLoop 内 STREAM_EXECUTOR 虚拟线程执行 provider.stream（同 consume 所在线程）</li>
 *   <li>断言虚拟线程内 sessionId 非 null 且等于原始 "sess-xxx" —— MDC 回放生效实证</li>
 * </ol>
 *
 * <p>回归保护：若 LlmAgentLoop MDC 回放被移除，本测试变红（虚拟线程 sessionId=null）。
 */
class LlmAgentLoopMdcReplayTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @Test
    @DisplayName("STREAM_EXECUTOR 虚拟线程 MDC 回放: stream 内 RequestContext.sessionId() 非 null 且等于原始 sess-xxx（OD-17 线程断点闭环）")
    void streamExecutorThread_seesReplayedMdc() {
        // ── 1. 模拟 ChatService:120 入口设 MDC（原始 "sess-xxx" 生产格式）──
        String rawSessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RequestContext.set(rawSessionId, "msg-od17-mdc-replay");
        try {
            // ── 2. provider mock · stream 在 STREAM_EXECUTOR 虚拟线程内执行（同 consume 所在线程）──
            AtomicReference<String> capturedSessionIdInStreamThread = new AtomicReference<>(null);
            LlmProvider provider = Mockito.mock(LlmProvider.class);
            Mockito.doAnswer(inv -> {
                // 在虚拟线程内捕获 MDC（consumePostCompactionAtApiSuccess 同线程取 RequestContext.sessionId()）
                capturedSessionIdInStreamThread.set(RequestContext.sessionId());
                java.util.function.Consumer<String> onChunk = inv.getArgument(9);
                java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
                Runnable onComplete = inv.getArgument(16);
                onChunk.accept("response");
                onMsg.accept(new AssistantMessage("response", "end_turn", List.of()));
                onComplete.run();
                return null;
            }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
            when(factory.getProvider(any(), any())).thenReturn(provider);

            AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
            LoopDeps deps = new LoopDeps() {
                @Override public AgentLoopContext context() { return ctx; }
                @Override public boolean isMainLoop() { return true; }
            };

            // ── 3. 驱动真实 queryLoop（同 WiringOrderTest 路径）──
            AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
            LoopResult result = LlmAgentLoop.queryLoop(
                QueryParams.forLoop(state.messages(), null,
                    ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                    QuerySource.USER, "test-model", null, null, null, null, null,
                    deps, ProviderConfig.empty()),
                state, new ArrayList<>());

            // ── 4. 断言: 流线程（STREAM_EXECUTOR 虚拟线程）MDC 已回放，sessionId 非 null 且等于原始值 ──
            assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
            assertThat(capturedSessionIdInStreamThread.get())
                .as("STREAM_EXECUTOR 虚拟线程必须经 MDC 回放拿到原始 sessionId（否则 consume 归一化后拿 null 回落进程级布尔，OD-17 线程断点）")
                .isEqualTo(rawSessionId);
        } finally {
            RequestContext.clear();
        }
    }

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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
            when(factory.getProvider(any(), any())).thenReturn(provider);

            AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
            LoopDeps deps = new LoopDeps() {
                @Override public AgentLoopContext context() { return ctx; }
                @Override public boolean isMainLoop() { return true; }
            };

            // ── 3. 驱动真实 queryLoop（同 MDC 回放测试路径）──
            AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
            LoopResult result = LlmAgentLoop.queryLoop(
                QueryParams.forLoop(state.messages(), null,
                    ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                    QuerySource.USER, "test-model", null, null, null, null, null,
                    deps, ProviderConfig.empty()),
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
