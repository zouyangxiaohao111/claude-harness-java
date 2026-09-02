package com.nexusai.application.agent;

import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
 * [GAP-3 · P1-6] LlmAgentLoop 注册守卫 else-分支经真实 run + 真 SessionAgentStateRegistry 实测。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: LlmAgentLoop.java:1655-1660 注册守卫按
 * agentId 分流注册 AgentState —— 主会话（agentId==null）按 sessionId 注册，后台化主会话任务
 * （agentId=agentUuid，MainSessionBackgroundService.java:341）按 agentUuid 注册（CC
 * LocalMainSessionTask.ts:364-375 runWithAgentContext{agentId:taskId}）。若 else-分支（:1659）
 * 回归丢失，后台 AgentState 不落 registry key=agentUuid → SkillTool 写入侧经 resolver
 * （registry::get）命中不到 → skill 归因 null-agent（processSlashCommand.tsx:885）→ /clear
 * preservedAgentIds={task.agentId()} 匹配不到（state.ts:1543-1555）→ 后台 skill 被误清。
 *
 * <p>既有 EVD-B（SkillToolInvokedSkillWriteTest.java:198-199）仅<b>手动</b> register(sessionUuid,
 * sharedState)+register(agentUuid, bgState)，不依赖 LlmAgentLoop 本身——删 else-分支仍绿，无法
 * 守护守卫。本测试经<b>真实 LlmAgentLoop.run</b>（agentId 非 null）+ 真 SessionAgentStateRegistry
 * 断言后台 AgentState 真实注册（key=agentUuid），删 else-分支即 RED。
 *
 * <p><b>测试基建</b>: 复用 LlmAgentLoopUnifiedQueuePromptTest.java:89 同款真实 run 模式（裸
 * {@code new LlmAgentLoop(factory)} + mocked provider 首调 stop）；registry 经 ReflectionTestUtils
 * 注入私有 {@code @Autowired(required=false)} 字段（LlmAgentLoop.java:1142 无 setter，
 * CommandControllerBuiltInCommandsTest.java:76 同款用法）。
 */
class LlmAgentLoopSessionAgentStateRegistryTest {

    /** provider 首调返回 stop 纯文本 → loop 正常退出（对齐既有真实 run 测试）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 真实 LlmAgentLoop + 注入真 registry + mocked provider。 */
    private static LlmAgentLoop loopWithRegistry(SessionAgentStateRegistry registry) {
        LlmProvider provider = stopProvider("bg response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        // 私有 @Autowired(required=false) 字段无 setter → 反射注入（spring-test，与
        // CommandControllerBuiltInCommandsTest:76 同款）。未注入则守卫整体不命中（sessionAgentStateRegistry==null）。
        ReflectionTestUtils.setField(loop, "sessionAgentStateRegistry", registry);
        return loop;
    }

    /**
     * 【GAP-3 核心 · else-分支】后台化主会话任务真实 run（agentId=agentUuid 非 null）
     * → 后台 AgentState 真实注册进 registry，key=agentUuid（且不落 sessionId key）。
     *
     * <p>对齐 MainSessionBackgroundService.java:341（agentId=agentUuid）+ CC
     * LocalMainSessionTask.ts:369（agentId: taskId）。若 else-分支（:1659）被删，run 后
     * registry.get(agentUuid) 为 null → 断言失败 RED（后台 skill 将归因 null-agent 被 /clear 误清）。
     */
    @Test
    @DisplayName("[GAP-3] 后台 loop（agentId 非 null）真实 run 后后台 AgentState 注册进 registry，key=agentUuid")
    void backgroundLoop_agentIdNonNull_realRun_registersStateUnderAgentUuid() {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        LlmAgentLoop loop = loopWithRegistry(registry);

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);   // 主会话 sessionId（MainSessionBackgroundService:338）
        UUID agentUuid = UUID.randomUUID();     // 后台任务 agentId（MainSessionBackgroundService:339 agentUuid）

        // MainSessionBackgroundService:341 同款 run 契约：sessionId + agentId=agentUuid 非 null
        AgentState state = loop.run(RunRequest.session("background query", sessionUuid, agentUuid,
            ProviderConfig.empty(), "test-model", null, null));

        // 断言 1：后台 AgentState 真实注册（key=agentUuid）——else-分支生效
        assertThat(registry.get(agentUuid))
            .as("后台 loop 的 AgentState 必须按 agentUuid 注册进 registry（else-分支 :1659），"
                + "否则 SkillTool resolver 命中不到 → 后台 skill 归因 null-agent 被 /clear 误清")
            .isNotNull()
            .isSameAs(state);   // run 内新建的同一 AgentState 实例（register 的就是它）
        assertThat(registry.get(agentUuid).agentId())
            .as("注册的 AgentState 携带 agentId=agentUuid（对齐 CC LocalMainSessionTask.ts:369 agentId:taskId）")
            .isEqualTo(agentUuid);

        // 断言 2：不落 sessionId key —— 证明走 else 分支而非 if 分支（主/后台互不覆盖，LlmAgentLoop:1654 注释语义）
        assertThat(registry.get(sessionUuid))
            .as("后台 loop 不得按 sessionId 注册（else-分支语义），主会话 key 不覆盖")
            .isNull();
    }

    /**
     * 【镜像 · if-分支】主会话真实 run（agentId==null）→ 按 sessionId 注册（守卫 if-分支 :1657）。
     *
     * <p>对齐 CC 主会话 addInvokedSkill 经 sessionId 解析主 AgentState（SkillTool 写入侧
     * 经 ctx.sessionId() 寻址）。守护 if-分支不回归；与 GAP-3 核心断言共同锁住整个 if/else 分流。
     */
    @Test
    @DisplayName("[GAP-3] 主会话 loop（agentId==null）真实 run 后按 sessionId 注册进 registry")
    void mainSession_agentIdNull_realRun_registersStateUnderSessionId() {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        LlmAgentLoop loop = loopWithRegistry(registry);

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // agentId=null → 走 if 分支（主会话按 sessionId 注册，LlmAgentLoop:1657）
        AgentState state = loop.run(RunRequest.session("main query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(registry.get(sessionUuid))
            .as("主会话（agentId==null）必须按 sessionId 注册进 registry（if-分支 :1657）")
            .isNotNull()
            .isSameAs(state);
    }
}
