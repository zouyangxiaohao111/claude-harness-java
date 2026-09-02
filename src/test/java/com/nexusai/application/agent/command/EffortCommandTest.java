package com.nexusai.application.agent.command;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.common.RequestContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /effort 命令 handler 逻辑测试 · 对齐 CC commands/effort/effort.tsx（全文 Read 实证）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图而非仅行为）：
 * <ol>
 *   <li><b>会话级写入</b>——R2 返工（multi-session-vs-cc-single-session：effort 必须会话级）：
 *       /effort set/auto 写<b>当前会话</b> sessions.effort_level（V31 列，经 SessionMapper）+ 会话
 *       AgentState.effortValue。若命令漏写会话或写错档位 → 该会话档位不结转（跨 run 丢失）/残留。
 *       不再写全局 settings.effortLevel（V29 列，R3 删除）。</li>
 *   <li><b>env 覆盖冲突判定</b>（effort.tsx:32-51）——仅 env 钉死另一档位或显式抑制时提示，env 与
 *       请求一致时噪音豁免（effort.tsx:33-34 注释）；env 三态（缺失/抑制/档位）区分错误会导致
 *       误报或漏报。</li>
 *   <li><b>写失败 fail-loud</b>（effort.tsx:22-26 等价）——会话写失败仅报错不设会话（CC 无
 *       effortUpdate），避免假成功。</li>
 *   <li><b>AgentState 会话级写入</b>——ApplyEffortAndClose（effort.tsx:148-156）setAppState
 *       effortValue 语义：成功/冲突分支都更新会话档位（运行时）。</li>
 * </ol>
 *
 * <p>env 经 {@link EffortCommand#envProvider} 接缝覆写（JDK 9+ System.getenv 只读），
 * finally 还原（同包访问 package-private 字段）。SessionMapper 为 mock；当前会话经
 * RequestContext.sessionId()（MDC）解析。
 */
class EffortCommandTest {

    private SessionMapper sessionMapper;
    private SessionRecord session;
    private SessionAgentStateRegistry registry;
    private EffortCommand command;
    private AgentState state;
    private Function<String, String> originalEnvProvider;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        session = new SessionRecord();
        when(sessionMapper.selectOneById(anyString())).thenReturn(session);
        registry = mock(SessionAgentStateRegistry.class);
        command = new EffortCommand(sessionMapper, registry);
        state = new AgentState("test-system-prompt");
        originalEnvProvider = EffortCommand.envProvider;
        // 默认无 CLAUDE_CODE_EFFORT_LEVEL
        EffortCommand.envProvider = k -> null;
        RequestContext.setSession("00000000-0000-0000-0000-00000000000a");
        when(registry.get(any())).thenReturn(state);
    }

    @AfterEach
    void tearDown() {
        EffortCommand.envProvider = originalEnvProvider;
        RequestContext.clear();
    }

    @Test
    @DisplayName("/effort low → 写当前会话 sessions.effort_level + 会话 AgentState + 成功消息（R2 会话级）")
    void setLow_writesSessionAndState() {
        EffortCommand.EffortCommandResult r = command.handle("low");
        // R2 会话级：不再写 settings，改写当前会话 effort_level（V31 列）
        assertThat(session.getEffortLevel()).isEqualTo("low");
        verify(sessionMapper).update(session);
        assertThat(state.effortValue()).isEqualTo("low");
        assertThat(r.message()).isEqualTo(
            "Set effort level to low: Quick, straightforward implementation with minimal overhead");
        assertThat(r.effortValue()).isEqualTo("low");
    }

    @Test
    @DisplayName("/effort max → 会话级档位写当前会话（max 本就是 CC 会话级，R2 全量落会话）")
    void setMax_writesSession() {
        EffortCommand.EffortCommandResult r = command.handle("max");
        // R2：settings 二分已删（全会话级），max 也写 sessions.effort_level；无 ' (this session only)' 后缀
        assertThat(session.getEffortLevel()).isEqualTo("max");
        verify(sessionMapper).update(session);
        assertThat(state.effortValue()).isEqualTo("max");
        assertThat(r.message()).isEqualTo(
            "Set effort level to max: Maximum capability with deepest reasoning (Opus 4.6 only)");
        assertThat(r.effortValue()).isEqualTo("max");
    }

    @Test
    @DisplayName("/effort auto → 清当前会话 effort_level + 会话 effortValue=null（R2 会话级清除）")
    void auto_clearsSessionAndState() {
        session.setEffortLevel("high");
        EffortCommand.EffortCommandResult r = command.handle("auto");
        // 清会话 effort_level 需显式 update(s, false)（MyBatis-Flex update(entity) 默认忽略 null 字段）
        assertThat(session.getEffortLevel()).isNull();
        verify(sessionMapper).update(session, false);
        assertThat(state.effortValue()).isNull();
        assertThat(r.message()).isEqualTo("Effort level set to auto");
        assertThat(r.effortValue()).isNull();
    }

    @Test
    @DisplayName("/effort foo → 非法参数报错列合法值（CC effort.tsx:112-116）")
    void invalidArg_errorsWithValidOptions() {
        EffortCommand.EffortCommandResult r = command.handle("foo");
        assertThat(r.message()).isEqualTo(
            "Invalid argument: foo. Valid options are: low, medium, high, xhigh, max, ultracode, auto");
        assertThat(r.effortValue()).isNull();
        verify(sessionMapper, never()).update(any(SessionRecord.class));
    }

    @Test
    @DisplayName("/effort help / -h / --help → 用法说明（CC effort.tsx:9 + 173-176）")
    void help_showsUsage() {
        String help = command.handle("help").message();
        assertThat(help).startsWith("Usage: /effort [low|medium|high|max|auto]");
        assertThat(help).contains("- low: Quick, straightforward implementation");
        assertThat(command.handle("-h").message()).isEqualTo(help);
        assertThat(command.handle("--help").message()).isEqualTo(help);
    }

    @Test
    @DisplayName("/effort 无参 → 显示当前档位 auto（CC showCurrentEffort effort.tsx:62-75，兜底 high）")
    void noArgs_showsCurrentAuto() {
        EffortCommand.EffortCommandResult r = command.handle("");
        assertThat(r.message()).isEqualTo("Effort level: auto (currently high)");
        // 展示分支不写会话 / 不写 AgentState
        verify(sessionMapper, never()).update(any(SessionRecord.class));
        verify(sessionMapper, never()).update(any(SessionRecord.class), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("/effort 会话已有档位 → 显示当前档位（含描述）")
    void noArgs_withSessionEffort_showsLevel() {
        state.setEffortValue("high");
        EffortCommand.EffortCommandResult r = command.handle("status");
        assertThat(r.message()).isEqualTo(
            "Current effort level: high (Comprehensive implementation with extensive testing and documentation)");
    }

    @Test
    @DisplayName("env 钉死冲突：CLAUDE_CODE_EFFORT_LEVEL=low + /effort high → 提示 env 胜出，会话仍写（CC effort.tsx:35-51）")
    void envConflict_pinsLow_effortHigh() {
        EffortCommand.envProvider = k -> "low";
        EffortCommand.EffortCommandResult r = command.handle("high");
        assertThat(r.message()).isEqualTo(
            "CLAUDE_CODE_EFFORT_LEVEL=low overrides this session — clear it and high takes over");
        assertThat(session.getEffortLevel()).isEqualTo("high");
        assertThat(r.effortValue()).isEqualTo("high");
        assertThat(state.effortValue()).isEqualTo("high");
    }

    @Test
    @DisplayName("env=auto 显式抑制 + /effort high → 冲突提示（CC null 态，effort.tsx:35-36）")
    void envSuppressAuto_conflictsSet() {
        EffortCommand.envProvider = k -> "auto";
        EffortCommand.EffortCommandResult r = command.handle("high");
        assertThat(r.message()).isEqualTo(
            "CLAUDE_CODE_EFFORT_LEVEL=auto overrides this session — clear it and high takes over");
        assertThat(state.effortValue()).isEqualTo("high");
    }

    @Test
    @DisplayName("env 与请求一致 → 无提示噪音（CC effort.tsx:33-34 注释）")
    void envMatchesRequest_noConflict() {
        EffortCommand.envProvider = k -> "low";
        EffortCommand.EffortCommandResult r = command.handle("low");
        assertThat(r.message()).isEqualTo(
            "Set effort level to low: Quick, straightforward implementation with minimal overhead");
    }

    @Test
    @DisplayName("env 钉死 + /effort auto → 清会话但提示 env 仍控制本会话（CC unsetEffortLevel effort.tsx:90-99）")
    void envPins_auto_clearsButWarns() {
        EffortCommand.envProvider = k -> "max";
        EffortCommand.EffortCommandResult r = command.handle("unset");
        // R2 会话级：消息措辞 settings → session（不再写 settings）
        assertThat(r.message()).isEqualTo(
            "Cleared effort from session, but CLAUDE_CODE_EFFORT_LEVEL=max still controls this session");
        assertThat(session.getEffortLevel()).isNull();
        verify(sessionMapper).update(session, false);
        assertThat(state.effortValue()).isNull();
    }

    @Test
    @DisplayName("会话写失败 → 报错且不写 AgentState（CC effort.tsx:22-26 无 effortUpdate）")
    void sessionWriteFailure_reportsErrorNoState() {
        doThrow(new RuntimeException("db down")).when(sessionMapper).update(any(SessionRecord.class));
        EffortCommand.EffortCommandResult r = command.handle("low");
        assertThat(r.message()).isEqualTo("Failed to set effort level: db down");
        assertThat(r.effortValue()).isNull();
        assertThat(state.effortValue()).isNull();
    }

    @Test
    @DisplayName("ultracode → 会话 ultracode_enabled=true + effort_level=xhigh + AgentState=xhigh（V32）")
    void ultracode_enablesSessionFlagAndEffortXhigh() {
        EffortCommand.EffortCommandResult r = command.handle("ultracode");
        assertThat(r.message()).contains("Ultracode enabled");
        assertThat(r.effortValue()).isEqualTo("xhigh");
        // 会话级：ultracode_enabled=1 + effort_level=xhigh
        assertThat(session.getUltracodeEnabled()).isEqualTo(1);
        assertThat(session.getEffortLevel()).isEqualTo("xhigh");
        // AgentState 运行时 effort=xhigh
        assertThat(state.effortValue()).isEqualTo("xhigh");
        verify(sessionMapper).update(session);
    }
}
