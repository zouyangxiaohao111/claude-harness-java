package com.nexusai.application.agent;

import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * [R3] 会话级 effort 继承测试 · 对齐 CC resolveAppliedEffort 默认层（effort.ts:152-167
 * {@code env ?? appState.effortValue ?? getDefaultEffortForModel(model)}）的会话级承载。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>恢复会话继承</b>——用户拍板 effort 跟会话走（multi-session-vs-cc-single-session）。
 *       会话 sessions.effort_level 已持久化（/effort low|medium|high 写当前会话，R2）但每次
 *       new AgentState（LlmAgentLoop.doRun）后 effortValue=null → 恢复会话无 effort，除非 doRun
 *       入口把会话 effort_level 注入会话默认。若继承逻辑回归丢失，重启后档位丢失，本测试 RED。</li>
 *   <li><b>未配置不强制</b>——sessions.effort_level=null（新会话 / /effort auto 清除）→ 会话
 *       effortValue 保持 null（= 不注入 effort，走模型默认 / API 默认 high，getDisplayedEffortLevel
 *       兜底 'high' effort.ts:178）。若误把空值当档位继承，会污染会话 effort 语义。</li>
 *   <li><b>无 SessionMapper 容错</b>——POJO 单测 / 单体工具场景不注入 SessionMapper →
 *       sessionMapper==null 跳过继承（对齐 s19-P1-6 容错模式）。若漏判 null 空指针，所有
 *       new LlmAgentLoop 单测崩溃。</li>
 * </ol>
 *
 * <p><b>测试基建</b>: 复用 LlmAgentLoopSessionAgentStateRegistryTest.java:44-75 同款真实 run 模式
 * （裸 {@code new LlmAgentLoop(factory)} + mocked provider 首调 stop）；SessionMapper 经
 * {@code setSessionMapper} setter 注入（[R3] 新增，非反射）。派生 UUID
 * {@code 00000000-0000-0000-0000-abcdef010000} 经 {@code SessionKeys.originalKey} 反解
 * 原始会话键 {@code "sess-abcdef01"}（生产会话为 {@code SessionService.generateId("sess")} 格式）。
 */
class LlmAgentLoopEffortInheritanceTest {

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

    /** 真实 LlmAgentLoop + 注入 mock SessionMapper + mocked provider。 */
    private static LlmAgentLoop loopWithSessionMapper(SessionMapper sessionMapper) {
        LlmProvider provider = stopProvider("r3 response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        if (sessionMapper != null) {
            loop.setSessionMapper(sessionMapper);
        }
        return loop;
    }

    /** 派生 UUID → 经 SessionKeys.originalKey 反解为 "sess-abcdef01" 的稳定会话 UUID。 */
    private static String sessionUuid() {
        return "sess-abcdef01";
    }

    /**
     * 【R3 核心】会话 sessions.effort_level='high'（/effort high 已写当前会话）→ 新/恢复 run 创建的
     * 新 AgentState 继承 effortValue='high'（对齐 CC resolveAppliedEffort 默认层：会话无显式
     * effort 时回落持久化档位）。若继承逻辑回归丢失，恢复会话档位不再生效。
     */
    @Test
    @DisplayName("[R3] 会话 effort_level 已持久化 → 新/恢复会话 AgentState.effortValue 继承该档位")
    void sessionEffortLevel_persisted_inheritsIntoNewSession() {
        SessionRecord session = new SessionRecord();
        session.setEffortLevel("high");
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById("sess-abcdef01")).thenReturn(session);
        LlmAgentLoop loop = loopWithSessionMapper(sessionMapper);

        AgentState state = loop.run(RunRequest.session("r3 query", sessionUuid(), null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state.effortValue())
            .as("恢复会话（新 AgentState）必须继承 sessions.effort_level='high' 作为会话默认，"
                + "否则持久化档位重启后不生效（CC appState.effortValue 默认层）")
            .isEqualTo("high");
    }

    /**
     * 【负向】sessions.effort_level=null（新会话 / /effort auto 清除）→ 会话 effortValue 保持
     * null（= 不注入 effort，走模型默认 / API 默认 high）。误把空值当档位继承会污染会话 effort 语义。
     */
    @Test
    @DisplayName("[R3] 会话 effort_level 未配置 → 新/恢复会话 effortValue 保持 null（不注入，默认 high）")
    void sessionEffortLevel_unset_keepsSessionNull() {
        SessionRecord session = new SessionRecord();
        session.setEffortLevel(null);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById("sess-abcdef01")).thenReturn(session);
        LlmAgentLoop loop = loopWithSessionMapper(sessionMapper);

        AgentState state = loop.run(RunRequest.session("r3 query", sessionUuid(), null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state.effortValue())
            .as("sessions.effort_level=null（新会话 / auto 清除）→ 不注入 effort，effortValue 保持 null（默认 high）")
            .isNull();
    }

    /**
     * 【容错】未注入 SessionMapper（POJO 单测 / 单体工具场景）→ 跳过继承不 NPE，effortValue
     * 保持 null。若漏判 null，所有 new LlmAgentLoop 单测崩溃。
     */
    @Test
    @DisplayName("[R3] 未注入 SessionMapper → 跳过继承不 NPE，effortValue 保持 null")
    void sessionMapperNotInjected_skipsInheritance() {
        LlmAgentLoop loop = loopWithSessionMapper(null);

        AgentState state = loop.run(RunRequest.session("r3 query", sessionUuid(), null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state.effortValue())
            .as("sessionMapper==null（POJO 单测）→ 跳过 effort 继承，effortValue 保持 null")
            .isNull();
    }
}
