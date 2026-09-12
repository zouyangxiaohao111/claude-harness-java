package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.loop.SubagentLoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P2-23 · 2026-09-11] 子代理 resume 的 skill_listing 续跑语义（不再恒 resume=false）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：{@code SkillListingSentRegistry.decide} 的
 * {@code resume} 入参决定分支：{@code false} = 「全新会话首 run」→ {@code sent.clear()} + 整份注入
 * （SkillListingSentRegistry.java:148-153 分支 2）；{@code true} 且未初始化 = 抑制（分支 3）/
 * 已初始化 = 只发增量（分支 4）。
 *
 * <p>子代理/hook agent 经中间 queryLoop 重载<b>恒传 false</b>（P2-23 现象）。对<b>同一 agentId 二次
 * 续跑</b>（async 子代理 resume，{@code SubagentExecutor} 的 {@code forkParams.agentIdOverride()} 路径）
 * 就会重发整份清单 ~4K token 并打断前缀缓存；CC 真源是按 {@code agentId} 键控的 {@code sent}
 * （attachments.ts:2676-2678）——同 agentId 二次跑时 {@code newSkills = 全量 − sent = 空}
 * → {@code return []}，<b>不注入</b>（attachments.ts:2799-2803）。
 *
 * <p><b>RED tooth（回退哪一行 → 本类哪条断言红）</b>：
 * <ul>
 *   <li>{@code LlmAgentLoop.queryLoop(…, boolean skillListingResume)} 把入参丢弃（或改回经 3 参重载
 *       硬传 false）→ {@link #subagentResume_doesNotReinjectFullListing()} 红（续跑 run 会再注入整份）；</li>
 *   <li>{@code SubagentExecutor} 不再透传真实 resume（回落到 3 参重载）→
 *       {@link #subagentExecutorPassesRealResumeFlagToQueryLoop()} 红（源码接线锚定）；</li>
 *   <li>首 run 的全量语义被破坏（把 false 也当 resume）→
 *       {@link #subagentFreshRun_injectsFullListing()} 红。</li>
 * </ul>
 */
class SkillListingSubagentResumeWiringTest {

    private static final String SESSION_KEY = "sess-subresume";
    /** 子代理 agentId（keys 为 sessionId + '\0' + agentId.toString()）。 */
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b7");

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
    }

    // ────────────────────────────────────────────────────────────────────
    // 1. 消费端：queryLoop(…, skillListingResume) 真实驱动 decide 分支
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-23: 子代理续跑（resume=true，槽位未初始化）→ 不重发整份清单")
    void subagentResume_doesNotReinjectFullListing() {
        SkillListingSentRegistry.reset();
        Harness h = new Harness();

        AgentState state = h.subagentState();
        // 前置：该 agentKey 未初始化（P2-12 removeAgentKey 在上一 run 结束点回收了槽位）——
        //   这正是「续跑」的真实形态：进程内已发过一次，但槽位已被回收。
        SkillListingSentRegistry.removeAgentKey(SESSION_KEY, AGENT_ID.toString());

        LlmAgentLoop.queryLoop(h.params(state), state, new ArrayList<>(), /*skillListingResume=*/true);

        assertThat(listingContent(state.rawMessages()))
            .as("续跑 run 不得重发整份清单（CC 同 agentId sent 非空 → newSkills 空 → 不注入，"
                + "attachments.ts:2799-2803）；恒 resume=false 的实现会在此注入整份 → RED")
            .isNull();
    }

    @Test
    @DisplayName("P2-23 回归锁: 子代理首 run（resume=false）→ 仍注入整份清单（turn-0 listing 保证不变）")
    void subagentFreshRun_injectsFullListing() {
        SkillListingSentRegistry.reset();
        Harness h = new Harness();

        AgentState state = h.subagentState();
        LlmAgentLoop.queryLoop(h.params(state), state, new ArrayList<>(), /*skillListingResume=*/false);

        assertThat(listingContent(state.rawMessages()))
            .as("全新子代理（新 agentId · sent 空）必须得到自己的首份整份清单 "
                + "（CC attachments.ts:2672-2676「subagents get their own turn-0 listing」）")
            .isNotNull()
            .contains("commit")
            .contains("review");
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. 接线锚定：SubagentExecutor 必须透传真实 resume（不是写死 false）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-23 接线: SubagentExecutor 把 resume 判据透传给 queryLoop（非 3 参硬传 false）")
    void subagentExecutorPassesRealResumeFlagToQueryLoop() throws Exception {
        // WHY: 消费端（上两条）只证明 queryLoop 会正确消费该标志；此处锚定「子代理路径真的把标志
        //   传进来」。回退到 3 参重载（= 恒 false）即本断言 RED —— 这正是 P2-23 的原始缺陷形态。
        //   源码锚定延用本仓既有手法（LlmAgentLoopDriftAndLazinessTest 同款：读源文件断言调用形态）。
        List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java"));
        assertThat(lines)
            .as("SubagentExecutor 必须以 4 参（含 skillListingResume）调 queryLoop")
            .anyMatch(l -> l.contains("LlmAgentLoop.queryLoop(queryParams, state, consumedCommandUuids, skillListingResume)"));
        assertThat(lines)
            .as("resume 判据必须来自 forkParams.resumedMessages()（resume 专属字段），不得写死 false")
            .anyMatch(l -> l.contains("subagentResume = forkParams != null && forkParams.resumedMessages() != null"));
    }

    // ────────────────────────────────────────────────────────────────────
    // harness
    // ────────────────────────────────────────────────────────────────────

    /** 复用 LlmAgentLoopSkillListingInjectTest 的桩法：mock provider + mock SkillCatalog。 */
    private static final class Harness {

        private final AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();

        Harness() {
            LlmProvider provider = mock(LlmProvider.class);
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            LlmProviderFactory factory = mock(LlmProviderFactory.class);
            when(factory.getProvider(any(), any())).thenReturn(provider);
            contextFactory.setLlmProviderFactory(factory);

            SkillCatalog catalog = mock(SkillCatalog.class);
            List<Command> commands = List.of(cmd("commit"), cmd("review"));
            when(catalog.getModelInvocableCommandsForListing()).thenReturn(commands);
            when(catalog.getModelInvocableCommands()).thenReturn(commands);
            when(catalog.getCharBudget(any())).thenReturn(1000);
            when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
                List<Command> passed = inv.getArgument(0);
                return passed.stream().map(Command::getName).collect(java.util.stream.Collectors.joining("\n"));
            });
            ReflectionTestUtils.setField(contextFactory, "skillCatalog", catalog);
        }

        /** 子代理 AgentState（agentId != null = agentKey 非主线程 ''）。 */
        AgentState subagentState() {
            AgentState state = new AgentState("sys", SESSION_KEY, AGENT_ID);
            state.appendMessage(new ChatMessageDto("u-1", SESSION_KEY, Role.user, "user", "子任务指令",
                null, List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false, null));
            return state;
        }

        /** 子代理 QueryParams（base TUC 含 Skill 工具 → hasSkillToolInAvailableTools=true）。 */
        QueryParams params(AgentState state) {
            Tool skillTool = mock(Tool.class);
            when(skillTool.name()).thenReturn("Skill");
            ToolUseContext tuc = ToolUseContext.of(AGENT_ID, SESSION_KEY, PermissionMode.DEFAULT,
                List.of(skillTool));
            return QueryParams.forLoop(
                // [prompt-assembly-A] systemPrompt 字段 String → List<String>（vestigial，恒传 List.of()）
                state.rawMessages(), java.util.List.of(), tuc,
                QuerySource.SUBAGENT, "test-model", 4,
                null, null, null, null,
                new SubagentLoopDeps(contextFactory.shared(null)), ProviderConfig.empty());
        }

        private static Command cmd(String name) {
            Command c = new Command();
            c.setName(name);
            c.setType("prompt");
            return c;
        }
    }

    /** 清单消息内容（subtype=skill_listing）；无 → null。 */
    private static String listingContent(List<ChatMessageDto> msgs) {
        if (msgs == null) {
            return null;
        }
        for (ChatMessageDto m : msgs) {
            if (m != null && "skill_listing".equals(m.subtype())) {
                return m.content();
            }
        }
        return null;
    }
}
