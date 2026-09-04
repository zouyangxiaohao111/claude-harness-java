package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.FindRelevantMemories;
import com.nexusai.application.agent.memory.MemoryAge;
import com.nexusai.application.agent.memory.MemoryPrefetcher;
import com.nexusai.application.agent.memory.MemoryScanner;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-M-R2-P0-RETRIEVE] relevant-memories 预取注入的 loop 级契约测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: DRF-7/G-28（注入位置 = 消息数组末尾）与
 * MEM-08/G-27（无 querySource 门控）都是 LlmAgentLoop 消费点内的可观测行为，只能经真实 loop
 * 驱动验证：
 * <ol>
 *   <li><b>DRF-7</b>: provider 收到的消息数组中 relevant_memories meta 消息必须位于<b>末尾</b>
 *       （CC query.ts:1611 toolResults.push + :1585/:1716 组装）；旧 addAll(0, …) 前置 → RED。</li>
 *   <li><b>MEM-08</b>: querySource=SUBAGENT 的 turn 同样启动预取并注入（CC query.ts:301-304 无
 *       门控，forkedAgent.ts:545/runAgent.ts:748 直调 query()）；旧显式跳过 → RED。</li>
 * </ol>
 *
 * <p><b>测试构造</b>: 真实 LlmAgentLoop + mocked 主 provider（第 1 轮 tool_calls → 工具轮 →
 * 第 2 轮 stop）+ 真实 MemoryPrefetcher（memDir 含 a.md + stub side-query 选中 a.md）。
 * 确定性：side-query 阻塞到主 provider 首轮调用后放行（保证首轮消费点跳过、第二轮注入），
 * 与 CC 零等待消费语义一致（query.ts:1592-1598 未 settle 跳过后轮重试）。
 */
@DisplayName("[IMP-M-R2-P0-RETRIEVE] relevant-memories 注入位置与作用域（DRF-7/MEM-08）")
class LlmAgentLoopMemoryPrefetchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class BlockingSideQuery extends MockLlmProvider {
        final CountDownLatch firstMainCall = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        int sideQueryCalls;

        @Override
        public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                      String userMessage, LlmProvider.ChatRequestOptions options) {
            sideQueryCalls++;
            try {
                assertThat(firstMainCall.await(5, TimeUnit.SECONDS))
                    .as("主 provider 首轮调用必须发生（side-query 阻塞锚点）").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            String result = "{\"selected_memories\": [\"a.md\"]}";
            completed.countDown();
            return result;
        }

        /** 主 provider 首轮响应前等待 side-query 完成（确定性：第二轮消费点必见 settledAt）。 */
        void awaitCompleted() {
            try {
                assertThat(completed.await(5, TimeUnit.SECONDS))
                    .as("side-query 必须在主 provider 首轮响应前完成").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
    private static MemoryPrefetcher buildPrefetcher(Path memDir, BlockingSideQuery sideProvider) {
        LlmProviderFactory sideFactory = new LlmProviderFactory() {
            @Override
            public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return sideProvider;
            }

            @Override
            public LlmProvider getProvider(ProviderConfig config) {
                return sideProvider;
            }
        };
        AutoMemPaths paths = new AutoMemPaths(
            () -> memDir.toString(),
            () -> memDir.toString(),
            () -> memDir.toString(),
            () -> null);
        return new MemoryPrefetcher(
            new FindRelevantMemories(sideFactory, "sonnet", new MemoryScanner(),
                new ModelConfigResolver() {
                    @Override
                    public String resolveFastModelName(String fallbackModelName) {
                        return "claude-sonnet";
                    }

                    @Override
                    public com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolve(String modelName) {
                        return new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                            new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk");
                    }
                }),
            paths,
            new MemoryAge(),
            () -> true,
            () -> true,
            null,
            null);
    }

    /**
     * 驱动真实 loop 跑 2 轮 + 确定性预取注入。
     *
     * <p>side-query 首调阻塞到主 provider 首轮调用 → 首轮消费点必然跳过（settledAt=0），
     * 主 provider 放行 side-query 后第 2 轮消费点注入 —— 与 CC 零等待消费语义一致。
     *
     * @return 每轮主 provider 收到的消息数组（arg 3 = history）
     */
    private static List<List<ChatMessageDto>> runTwoTurns(Path memDir, QuerySource querySource,
                                                          BlockingSideQuery side) throws Exception {
        RunObservation obs = runTwoTurnsInternal(memDir, querySource, side);
        return obs.histories();
    }

    /** 单次 run 的观察结果（histories + 退出原因，供失败诊断）。 */
    private record RunObservation(List<List<ChatMessageDto>> histories, AgentState.ExitReason exitReason) {}

    private static RunObservation runTwoTurnsInternal(Path memDir, QuerySource querySource,
                                                      BlockingSideQuery side) throws Exception {
        Files.writeString(memDir.resolve("a.md"),
            "---\ntype: project\ndescription: alpha config\n---\nbody\n");

        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmProvider mainProvider = mock(LlmProvider.class);
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        RunRequest request = querySource == QuerySource.SUBAGENT
            ? new RunRequest("configure the system now", ProviderConfig.empty(), "test-model",
                QuerySource.SUBAGENT, null, null, null, null, null, null, null, null, null, null, false, null, null, null)
            : RunRequest.session("configure the system now", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                ProviderConfig.empty(), "test-model", null, null);
        final boolean[] firstRound = {true};
        doAnswer(inv -> {
            histories.add(new ArrayList<>((List<ChatMessageDto>) inv.getArgument(3)));
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            // 放行 side-query（主 provider 首轮调用 = 预取阻塞锚点已到）并等待其完成 ——
            // 保证第 2 轮消费点必见 settledAt（确定性注入）
            side.firstMainCall.countDown();
            side.awaitCompleted();
            if (firstRound[0]) {
                firstRound[0] = false;
                // 第 1 轮：tool_calls → 工具轮（未知 Bash → error result）→ 第 2 轮
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("Let me check", "tool_calls",
                    List.of(new ToolUseBlock("toolu_mem_1", "Bash", input))));
            } else {
                // 第 2 轮：纯文本 stop → needsFollowUp=false → 退出
                onMsg.accept(new AssistantMessage("Done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(mainProvider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(factory.getProvider(any(), any())).thenReturn(mainProvider);

        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        loop.setMemoryPrefetcher(buildPrefetcher(memDir, side));

        AgentState state = loop.run(request);
        assertThat(state).as("run() 必须返回非 null state").isNotNull();
        assertThat(side.sideQueryCalls).as("side-query 必须真实执行").isEqualTo(1);
        return new RunObservation(histories, state.exitReason());
    }
    @Test
    @DisplayName("DRF-7: relevant_memories 注入消息位于 provider 消息数组末尾（CC toolResults.push）")
    void memoryInjection_appendedAtEnd(@TempDir Path memDir) throws Exception {
        List<List<ChatMessageDto>> histories = runTwoTurns(memDir, QuerySource.USER, new BlockingSideQuery());

        assertThat(histories).as("必须 2 轮 LLM 调用").hasSize(2);
        assertThat(histories.get(0).stream().anyMatch(m -> "relevant_memories".equals(m.subtype())))
            .as("第 1 轮（prefetch 未 settle）不得注入 · CC query.ts:1592-1598 零等待跳过")
            .isFalse();
        assertThat(histories.get(1).get(histories.get(1).size() - 1).subtype())
            .as("relevant_memories 必须位于消息数组末尾（CC :1611 push → :1716 组装；旧 addAll(0,…) 前置 → RED）")
            .isEqualTo("relevant_memories");
    }
    @Test
    @DisplayName("MEM-08: querySource=SUBAGENT 同样启动预取（无 querySource 门控，CC query.ts:301-304）")
    void subagentQuerySource_stillStartsPrefetch(@TempDir Path memDir) throws Exception {
        // SUBAGENT turn 的工具轮在无完整子代理装配（availableTools 缺失）时以 STREAM_ERROR 单轮
        // 退出 —— MEM-08 的可观测证据 = 预取已启动（side-query 真实发起）：旧 querySource 门控
        // （querySource != SUBAGENT && != FORK 跳过）下 startPrefetch 根本不调用 → sideQueryCalls=0。
        RunObservation obs = runTwoTurnsInternal(memDir, QuerySource.SUBAGENT, new BlockingSideQuery());

        assertThat(obs.histories()).as("SUBAGENT turn 至少 1 轮 LLM 调用").isNotEmpty();
        assertThat(obs.exitReason()).as("诊断锚点：SUBAGENT 单轮（STREAM_ERROR）属装配限制，非断言目标")
            .isEqualTo(AgentState.ExitReason.STREAM_ERROR);
    }
}
