package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [Wave3-input] v2.1.325 观测字段：mcp_server / duration_ms / source / background_tasks / session_crons。 */
class HookInputFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolPostCarriesMcpServerAndDuration() throws Exception {
        Map<String, Object> mcp = Map.of("name", "filesystem");
        HookEvent e = HookEvent.toolPost("mcp__filesystem__read", MAPPER.createObjectNode(),
            MAPPER.createObjectNode(), "s1", null, "tid-1", mcp, 42L);
        assertThat(e.data()).containsEntry("mcp_server", mcp).containsEntry("duration_ms", 42L);
        var wire = MAPPER.readTree(CommandHookExecutor.buildJsonInput(e));
        assertThat(wire.path("mcp_server").path("name").asText()).isEqualTo("filesystem");
        assertThat(wire.path("duration_ms").asLong()).isEqualTo(42L);
    }

    @Test
    void toolPreAndPermissionEventsCarryMcpServer() throws Exception {
        Map<String, Object> mcp = Map.of("name", "fs");
        assertThat(HookEvent.toolPre("mcp__fs__read", MAPPER.createObjectNode(), "s1", null, "t", mcp)
            .data()).containsEntry("mcp_server", mcp);
        assertThat(HookEvent.permissionRequest("mcp__fs__read", MAPPER.createObjectNode(), List.of(),
            "s1", null, mcp).data()).containsEntry("mcp_server", mcp);
        assertThat(HookEvent.permissionDenied("mcp__fs__read", MAPPER.createObjectNode(), "no",
            "t", "s1", null, mcp).data()).containsEntry("mcp_server", mcp);
    }

    @Test
    void postToolUseFailureCarriesMcpServerAndDuration() {
        HookEvent e = HookEvent.toolPostFailure("mcp__fs__read", MAPPER.createObjectNode(),
            MAPPER.createObjectNode(), "boom", false, "t", "s1", null,
            Map.of("name", "fs"), 7L);
        assertThat(e.data()).containsEntry("duration_ms", 7L);
    }

    @Test
    void userPromptSubmitCarriesSourceAndTitle() {
        HookEvent e = HookEvent.userPromptSubmit("s1", null, "hi", "user", "My session");
        assertThat(e.data()).containsEntry("source", "user").containsEntry("session_title", "My session");
    }

    @Test
    void stopCarriesBackgroundTasksAndCrons() {
        List<Map<String, Object>> tasks = List.of(Map.of("id", "b1", "type", "shell"));
        List<Map<String, Object>> crons = List.of(Map.of("id", "c1", "schedule", "0 9 * * *"));
        HookEvent e = HookEvent.stop("s1", null, false, "done", null, null, tasks, crons);
        assertThat(e.data()).containsEntry("background_tasks", tasks).containsEntry("session_crons", crons);
    }

    @Test
    void subagentStopCarriesBackgroundTasks() {
        List<Map<String, Object>> tasks = List.of(Map.of("id", "a1", "type", "subagent"));
        HookEvent e = HookEvent.subagentStop("a1", "reviewer", "s1", false, "/t/agent-a1.jsonl",
            "done", null, tasks, null);
        assertThat(e.data()).containsEntry("background_tasks", tasks);
    }

    @Test
    void sessionStartCarriesExtendedFields() {
        HookEvent e = HookEvent.sessionStart("s1", null, "fork", null, "m",
            "T", 12L, 1000L, true, null);
        assertThat(e.data())
            .containsEntry("source", "fork")
            .containsEntry("session_title", "T")
            .containsEntry("seconds_since_last_response", 12L)
            .containsEntry("context_tokens", 1000L)
            .containsEntry("prompt_cache_likely_expired", true);
    }

    @Test
    void fromMapRoundTripsNewFields() {
        Map<String, Object> src = Map.of(
            "agent_type", "x",
            "mcp_server", Map.of("name", "fs"),
            "duration_ms", 5L);
        HookEventData data = HookEventData.fromMap(HookEventType.POST_TOOL_USE, src);
        assertThat(data).isInstanceOf(HookEventData.PostToolUse.class);
        assertThat(data.toMap()).containsEntry("duration_ms", 5L);
    }

    @Test
    void promptSourceMapping() throws Exception {
        var m = com.nexusai.application.agent.LlmAgentLoop.class
            .getDeclaredMethod("hookPromptSource", com.nexusai.application.agent.QuerySource.class);
        m.setAccessible(true);
        assertThat(m.invoke(null, com.nexusai.application.agent.QuerySource.REPL_MAIN_THREAD))
            .isEqualTo("user");
        assertThat(m.invoke(null, com.nexusai.application.agent.QuerySource.SDK)).isEqualTo("sdk");
        assertThat(m.invoke(null, com.nexusai.application.agent.QuerySource.SUBAGENT)).isEqualTo("system");
        assertThat(m.invoke(null, new Object[]{null})).isNull();
    }
}
