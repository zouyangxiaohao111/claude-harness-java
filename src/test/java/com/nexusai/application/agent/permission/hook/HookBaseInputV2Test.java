package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [Wave2-I] v2.1.325 base 字段：prompt_id / effort.level / CLAUDE_EFFORT。 */
class HookBaseInputV2Test {

    private static HookEvent event() {
        return new HookEvent(HookEventType.PRE_TOOL_USE, "s1", "/t.jsonl", "/w",
            null, null, "Bash", null, null, "tid-1", null, null,
            new HookEventData.PreToolUse(null), 1L, "pid-1", "high");
    }

    @Test
    void buildJsonInput_serializesPromptIdAndEffort() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(CommandHookExecutor.buildJsonInput(event()));
        assertThat(root.path("prompt_id").asText()).isEqualTo("pid-1");
        assertThat(root.path("effort").path("level").asText()).isEqualTo("high");
    }

    @Test
    void enrichBaseFields_preservesV2BaseFields() {
        // sessionId 置 null 强制走重建分支（parentTuc 为 null → 省略 session_id）
        HookEvent input = new HookEvent(HookEventType.PRE_TOOL_USE, null, "/t.jsonl", "/w",
            null, null, "Bash", null, null, "tid-1", null, null,
            new HookEventData.PreToolUse(null), 1L, "pid-1", "high");
        HookEvent enriched = CommandHookExecutor.enrichBaseFields(input, null);
        assertThat(enriched.promptId()).isEqualTo("pid-1");
        assertThat(enriched.effortLevel()).isEqualTo("high");
    }

    @Test
    void modelSwitch_requestedModelKeyAlwaysPresent() {
        Map<String, Object> m = new HookEventData.PreModelSwitch(
            "a", "b", null, "command", null, null, null, null, null).toMap();
        assertThat(m).containsKey("requested_model");
        assertThat(m.get("requested_model")).isNull();
    }

    @Test
    void buildEnv_exposesClaudeEffort() {
        Map<String, String> env = CommandHookExecutor.buildEnv(event(), null, null, null,
            java.util.function.Function.identity(), s -> null, s -> null, false, null);
        assertThat(env).containsEntry("CLAUDE_EFFORT", "high");
    }
}
