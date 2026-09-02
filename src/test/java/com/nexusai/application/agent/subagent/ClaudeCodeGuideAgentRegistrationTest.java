package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S1 P1-5] claude-code-guide agent 注册对齐测试.
 *
 * <p>旧 ClaudeCodeGuideAgentDef 仅常量 (死代码), 未注册到 BuiltInAgents.ALL.
 * 本期 create() 构造 BuiltInAgentDefinition 并注册 (对齐 CC builtInAgents.ts:54-61
 * isNonSdkEntrypoint 时 push CLAUDE_CODE_GUIDE_AGENT + claudeCodeGuideAgent.ts:98-120).
 *
 * <p><b>[Session S2]</b>: 静态 ALL Map 已删除（脏代码，CC builtInAgents.ts 无静态 Map），
 * 改经 {@link BuiltInAgents#getBuiltInAgents()} 动态列表验证（CC :22-72 动态装配）。
 */
class ClaudeCodeGuideAgentRegistrationTest {

    @Test
    @DisplayName("getBuiltInAgents() 含 'claude-code-guide' (非 SDK 入口 gate)")
    void claude_code_guide_agent_registered_in_getBuiltInAgents() {
        // WHY: ClaudeCodeGuideAgentDef 旧实现仅常量定义, BuiltInAgents.ALL 不引用, 全仓 grep 无
        // create() 调用 = 死代码. 本期注册后 findAgent("claude-code-guide") 可命中.
        // S2 起走 getBuiltInAgents() 动态列表 (CC builtInAgents.ts:54-61 非 SDK 入口 + claude-code-guide).
        assertThat(BuiltInAgents.getBuiltInAgents())
            .extracting(AgentDefinition::agentType)
            .contains("claude-code-guide");
        AgentDefinition guide = BuiltInAgents.get("claude-code-guide");
        assertThat(guide).isNotNull();
        assertThat(guide.agentType()).isEqualTo("claude-code-guide");
    }

    @Test
    @DisplayName("guide agent: model=haiku + permissionMode=dontAsk (对齐 CC :119-120)")
    void guide_agent_has_model_haiku_permissionMode_dontAsk() {
        // WHY: CC claudeCodeGuideAgent.ts:119 model='haiku', :120 permissionMode='dontAsk'.
        AgentDefinition guide = BuiltInAgents.get("claude-code-guide");
        assertThat(guide).isNotNull();
        assertThat(guide.model()).hasValue("haiku");
        assertThat(guide.permissionMode()).hasValue("dontAsk");
        assertThat(guide.source()).isEqualTo("built-in");
    }

    @Test
    @DisplayName("guide agent: 外部用户 tools=[Glob,Grep,Read,WebFetch,WebSearch] (对齐 CC :108-116)")
    void guide_agent_has_nonEmbedded_tools() {
        // WHY: nexusai guide 为本地项目指南（无在线文档），工具集 = 本地搜索 [Glob,Grep,Read]（去 WebFetch/WebSearch）。
        AgentDefinition guide = BuiltInAgents.get("claude-code-guide");
        assertThat(guide).isNotNull();
        assertThat(guide.tools()).hasValue(List.of("Glob", "Grep", "Read"));
    }
}
