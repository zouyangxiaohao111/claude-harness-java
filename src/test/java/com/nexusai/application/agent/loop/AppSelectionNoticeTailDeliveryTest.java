package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两种应用通知（用户 2026-11-23 终稿）：
 * <ul>
 *   <li>{@code app_enabled_change}：启动状态（schema 门）变化；
 *       {@code +启动 X（工具…） / -停止 X / 当前已启动：…}；</li>
 *   <li>{@code app_emphasis_change}：本轮重点应用（只强调，不改 schema）；
 *       {@code +新增 X / -移除 X / 当前重点：…}。</li>
 * </ul>
 * 都在变化时一条、无上限、原始 schema 工具名；比较键分别只用排序后的 key 列表。
 */
@DisplayName("应用通知 · 启动变化/重点变化（增量、幂等、无上限）")
class AppSelectionNoticeTailDeliveryTest {

    private static String newSessionId() {
        return "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static AgentState stateWithUserMsg(String sessionId) {
        AgentState state = new AgentState("sys", sessionId, null);
        appendUserMsg(state, sessionId, "hello");
        return state;
    }

    private static void appendUserMsg(AgentState state, String sessionId, String text) {
        state.appendMessage(new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), sessionId, Role.user, "user",
            text, null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of(), null, false));
    }

    private static ChatMessageDto last(AgentState state) {
        List<ChatMessageDto> all = state.rawMessages();
        return all.get(all.size() - 1);
    }

    private static long countSubtype(AgentState state, String subtype) {
        return state.rawMessages().stream()
            .filter(m -> m != null && subtype.equals(m.subtype()))
            .count();
    }

    private static void enabled(AgentState state, List<String> apps, Map<String, List<String>> tools) {
        AgentLoopContext.maybeEmitAppEnabledChange(state, QuerySource.REPL_MAIN_THREAD, apps, tools);
    }

    private static void emphasis(AgentState state, List<String> apps) {
        AgentLoopContext.maybeEmitAppEmphasisChange(state, QuerySource.REPL_MAIN_THREAD, apps);
    }

    @Test
    @DisplayName("首次启动：+启动（附原始工具名）+ 当前已启动；isMeta 附件")
    void enabled_firstEmitsAddedWithTools() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);

        enabled(state, List.of("B-app", "A-app"),
            Map.of("A-app", List.of("mcp__A-app__alpha", "mcp__A-app__beta"),
                "B-app", List.of("mcp__B-app__gamma")));

        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("app_enabled_change");
        assertThat(msg.isMeta()).isTrue();
        assertThat(msg.author()).isEqualTo("attachment");
        assertThat(msg.content()).startsWith("应用启动状态已更新：");
        assertThat(msg.content()).contains("+启动 A-app（工具：mcp__A-app__alpha / mcp__A-app__beta）");
        assertThat(msg.content()).contains("当前已启动：A-app、B-app");
    }

    @Test
    @DisplayName("启动同状态重复调用：零追加（每 tool 轮幂等）")
    void enabled_sameState_noop() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        enabled(state, List.of("A-app"), Map.of());
        int afterFirst = state.rawMessages().size();

        enabled(state, List.of("A-app"), Map.of());
        enabled(state, List.of("A-app"), Map.of());

        assertThat(state.rawMessages()).hasSize(afterFirst);
    }

    @Test
    @DisplayName("启动变化：+启动/-停止（只列差分）+ 当前已启动")
    void enabled_addRemoveIncremental() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        enabled(state, List.of("A-app"), Map.of());

        enabled(state, List.of("A-app", "B-app"),
            Map.of("B-app", List.of("mcp__B__t1")));
        assertThat(last(state).content()).contains("+启动 B-app（工具：mcp__B__t1）")
            .doesNotContain("-停止");

        enabled(state, List.of("A-app"), Map.of());
        String content = last(state).content();
        assertThat(content).contains("-停止 B-app").doesNotContain("+启动");
        assertThat(content).contains("当前已启动：A-app");
    }

    @Test
    @DisplayName("全部停止：-停止 + 当前已启动：无")
    void enabled_allStopped() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        enabled(state, List.of("A-app"), Map.of());

        enabled(state, List.of(), Map.of());

        assertThat(last(state).content())
            .contains("-停止 A-app").contains("当前已启动：无");
    }

    @Test
    @DisplayName("从未启动且当前为空：零追加")
    void enabled_emptyFromStart_noop() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        enabled(state, List.of(), Map.of());

        assertThat(state.rawMessages()).hasSize(before);
    }

    @Test
    @DisplayName("重点首次：+新增 + 当前重点")
    void emphasis_first() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);

        emphasis(state, List.of("B-app", "A-app"));

        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("app_emphasis_change");
        assertThat(msg.content()).startsWith("本轮重点应用已更新：");
        assertThat(msg.content()).contains("+新增 A-app、B-app");
        assertThat(msg.content()).contains("当前重点：A-app、B-app");
    }

    @Test
    @DisplayName("重点变化：+新增/-移除，空前重点写「无」")
    void emphasis_change() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        emphasis(state, List.of("A-app"));

        emphasis(state, List.of("A-app", "B-app"));
        assertThat(last(state).content()).contains("+新增 B-app");

        emphasis(state, List.of("A-app"));
        assertThat(last(state).content()).contains("-移除 B-app")
            .contains("当前重点：A-app");

        emphasis(state, List.of());
        assertThat(last(state).content()).contains("当前重点：无");
    }

    @Test
    @DisplayName("重点同状态：零追加")
    void emphasis_sameState_noop() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        emphasis(state, List.of("A-app"));
        int afterFirst = state.rawMessages().size();

        emphasis(state, List.of("A-app"));

        assertThat(state.rawMessages()).hasSize(afterFirst);
    }

    @Test
    @DisplayName("重点变化不产生启动通知（两种 subtype 独立判据）")
    void emphasisChange_doesNotEmitEnabledNotice() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        enabled(state, List.of("A-app"), Map.of());

        emphasis(state, List.of("A-app"));
        emphasis(state, List.of("A-app", "B-app"));

        assertThat(countSubtype(state, "app_enabled_change")).isEqualTo(1);
        assertThat(countSubtype(state, "app_emphasis_change")).isEqualTo(2);
    }

    @Test
    @DisplayName("启动变化只产生启动通知（不误发重点通知）")
    void enabledChange_doesNotEmitEmphasisNotice() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);

        enabled(state, List.of("A-app"), Map.of());
        enabled(state, List.of("A-app", "B-app"), Map.of());

        assertThat(countSubtype(state, "app_enabled_change")).isEqualTo(2);
        assertThat(countSubtype(state, "app_emphasis_change")).isZero();
    }

    @Test
    @DisplayName("非主线程（SUBAGENT）：两种通知都不注入")
    void subagent_noInjection() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitAppEnabledChange(state, QuerySource.SUBAGENT,
            List.of("A-app"), Map.of());
        AgentLoopContext.maybeEmitAppEmphasisChange(state, QuerySource.SUBAGENT,
            List.of("A-app"));

        assertThat(state.rawMessages()).hasSize(before);
    }

    @Test
    @DisplayName("无会话标识：只记录判据、不追加")
    void noSession_noInjection() {
        AgentState state = new AgentState("sys", null, null);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitAppEnabledChange(state, QuerySource.USER,
            List.of("A-app"), Map.of());

        assertThat(state.rawMessages()).hasSize(before);
    }

    @Test
    @DisplayName("工具池 → 应用：按 server 段分组，工具名保持原始 schema 全名")
    void mcpToolsByServer_groupsWithRawNames() {
        Map<String, List<String>> grouped = AgentLoopContext.mcpToolsByServer(List.of(
            tool("Bash"),
            tool("mcp__A__t2"),
            tool("mcp__A__t1"),
            tool("mcp__computer-use__screenshot"),
            tool("mcp__nexusai-in-chrome__read_page")));

        assertThat(grouped.get("A")).containsExactly("mcp__A__t1", "mcp__A__t2");
        assertThat(grouped.get("computer-use")).containsExactly("mcp__computer-use__screenshot");
        assertThat(grouped.get("nexusai-in-chrome")).containsExactly("mcp__nexusai-in-chrome__read_page");
        assertThat(grouped).doesNotContainKey("Bash");
    }

    @Test
    @DisplayName("工具清单不设上限：单应用 10 个全量列出")
    void tools_allListed() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        List<String> ten = java.util.stream.IntStream.rangeClosed(1, 10)
            .mapToObj(i -> String.format("mcp__A__t%02d", i)).toList();

        enabled(state, List.of("A"), Map.of("A", ten));

        String content = last(state).content();
        assertThat(content).contains("t01", "t08", "t09", "t10").doesNotContain("等 ");
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), name);
            }
        };
    }
}
