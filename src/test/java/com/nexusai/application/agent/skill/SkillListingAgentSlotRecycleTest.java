package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.ExecAgentHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-12 · 2026-09-11] 子代理 / hook agent <b>结束点必须回收 skill_listing 槽位</b>的接线实测。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：{@link SkillListingSentRegistry} 的键含 agentKey，而
 * hook agent 每次调用都生成新 UUID（{@code ExecAgentHook.generateHookAgentId()}）、子代理每次执行亦为新
 * agentId → 每个 (会话, agent 调用) 都在表中留一份 {@code Set<技能名>}，此前<b>永不回收</b>
 * （只有按 sessionId 前缀的 removeSession/removeSessionEntries）。CC 的 {@code sentSkillNames}
 * （attachments.ts:2676）同样从不删单键，但 CC 一进程一会话、agent 随进程消亡 → 无泄漏面；
 * nexusai 常驻 JVM 必须显式回收，否则随 agent 调用次数无界增长（审计 P2-12）。
 *
 * <p>两层钉死：
 * <ol>
 *   <li><b>行为层</b>（本类 {@code hookAgentEndPoint_recyclesSlot}）：真实跑
 *       {@link ExecAgentHook#exec} 一次，断言该会话槽位数归零 —— 结束点不回收即 RED；</li>
 *   <li><b>源码层</b>（{@code agentEndPoints_callRemoveAgentKey}）：两个结束点（ExecAgentHook 的
 *       finally、SubagentExecutor 的 finally）源码必须调用 {@code removeAgentKey} —— 子代理路径
 *       agentId 随机 + 执行链重，行为层无法直达其 finally，故用源码守卫补足（先例：
 *       {@code SkillListingCompactNoResetGuardTest}）。</li>
 * </ol>
 */
@DisplayName("[P2-12] agent 结束点回收 skill_listing 槽位")
class SkillListingAgentSlotRecycleTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String STRUCTURED_OUTPUT = "StructuredOutput";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
    }

    // ════════════════════════════════════════════════════════════════════════
    // ① 行为层：ExecAgentHook 结束点回收（真实跑 exec）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① ExecAgentHook.exec 结束（含 finally）→ 本会话不再持有该 hook agent 的槽位")
    void hookAgentEndPoint_recyclesSlot() {
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        ScriptableProvider provider = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true))),
            new AssistantMessage("done", "stop", List.of())
        ));
        ExecAgentHook hook = hookWith(provider, sessionId);

        assertThat(SkillListingSentRegistry.sessionSlotCount(sessionId))
            .as("前置：执行前该会话无槽位").isZero();

        hook.exec(new AgentHook("verify $ARGUMENTS", null, null, null, null, null),
            "test-agent-hook", HookEvent.userPromptSubmit(sessionId, "agent-1", "do something"),
            "{}", null, null, sessionId, null, null);

        // WHY 该断言重要：hook agent 每调用一次就是一个新 agentKey，不回收即随调用次数无界增长。
        //   hook agent 无 Skill 工具（effectiveRegistry 仅 SyntheticOutputTool）→ 注入守卫走
        //   「!resume → markInitialized」分支，仍会真实创建槽位（见 LlmAgentLoop.injectSkillListingForRun），
        //   故槽位归零只能来自结束点的 removeAgentKey —— 回退该行即 RED。
        assertThat(SkillListingSentRegistry.sessionSlotCount(sessionId))
            .as("hook agent 结束后必须回收其 skill_listing 槽（ExecAgentHook.exec finally）；"
                + "回退 finally 里的 SkillListingSentRegistry.removeAgentKey 即 RED")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // ② 源码层：两个 agent 结束点都必须接线
    // ════════════════════════════════════════════════════════════════════════

    /** 相对 backend 模块根（surefire 工作目录 = 模块 basedir，与 SkillListingCompactNoResetGuardTest 同款）。 */
    private static final List<String> AGENT_END_POINT_FILES = List.of(
        "src/main/java/com/nexusai/application/agent/permission/hook/ExecAgentHook.java",
        "src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java");

    @Test
    @DisplayName("② 两个 agent 结束点（ExecAgentHook / SubagentExecutor finally）源码必须调用 removeAgentKey")
    void agentEndPoints_callRemoveAgentKey() throws IOException {
        for (String rel : AGENT_END_POINT_FILES) {
            Path f = Path.of(rel);
            assertThat(Files.exists(f))
                .as("结束点源码必须存在（相对 backend 模块根）: %s", f.toAbsolutePath())
                .isTrue();
            assertThat(Files.readString(f))
                .as("%s 必须在 agent 结束点（finally）回收 skill_listing 槽位"
                    + "（SkillListingSentRegistry.removeAgentKey）—— 缺任一处则该 agent 方式的槽位无界泄漏（P2-12）", rel)
                .contains("SkillListingSentRegistry")
                .contains("removeAgentKey");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 夹具（最小复刻 R33H7_ExecAgentHookTest 的 scriptable provider 夹具）
    // ════════════════════════════════════════════════════════════════════════

    /** StructuredOutput tool_call（hook agent 的强制收尾调用）· CC hookResponseSchema。 */
    private ToolUseBlock structuredCall(boolean ok) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("ok", ok);
        return new ToolUseBlock("toolu-struct-" + System.nanoTime(), STRUCTURED_OUTPUT, input);
    }

    /**
     * 构造 ExecAgentHook：contextFactory 注入 scriptable provider + mock SkillCatalog。
     *
     * <p>SkillCatalog 非 null 是<b>前置条件</b>：{@code LlmAgentLoop.injectSkillListingForRun} 第一道守卫
     * {@code ctx.skillCatalog() == null → return}，为 null 则本测试对槽位回收完全无感（假绿）。
     */
    private ExecAgentHook hookWith(ScriptableProvider provider, String sessionId) {
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) { return provider; }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        ReflectionTestUtils.setField(contextFactory, "skillCatalog",
            org.mockito.Mockito.mock(SkillCatalog.class));
        return new ExecAgentHook(objectMapper, contextFactory, new ToolRegistry(), null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, null, null, null);
    }

    /** 按脚本返回 AssistantMessage 的 provider（超出脚本长度时重复最后一条）。 */
    static class ScriptableProvider implements LlmProvider {
        private final List<AssistantMessage> responses;

        ScriptableProvider(List<AssistantMessage> responses) {
            this.responses = responses;
        }

        private final java.util.concurrent.atomic.AtomicInteger callCount =
            new java.util.concurrent.atomic.AtomicInteger();

        @Override public String type() { return "test"; }

        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride,
                           com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            int idx = callCount.getAndIncrement();
            onAssistantMessage.accept(responses.get(Math.min(idx, responses.size() - 1)));
            onComplete.run();
        }
    }
}
