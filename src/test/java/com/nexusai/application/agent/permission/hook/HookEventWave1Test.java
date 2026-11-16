package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Wave1-A] v2.1.325 新增 6 事件的工厂载荷 + matcher 取值 · 对齐 claude.exe 事件数组 {@code E2}
 * 与 {@code Eon} matcher 分支：UserPromptExpansion→command_name、DirectoryAdded→source、
 * PreModelSwitch→to_model；PostToolBatch / MessageDisplay 无 matchQuery（全部 matcher 放行）。
 *
 * <p><b>WHY</b>: 若 matcher 取错字段，配置里的 hook 永远匹配不到 —— 表现为「配了没反应」，
 * 且不会报错，是最难发现的一类静默失效。
 */
@DisplayName("[Wave1-A] 新增事件工厂载荷与 matcher 映射")
class HookEventWave1Test {

    // ── 引擎构造 helper ────────────────────────────────────────────────────

    private HookMatcherEngine engineWith(HookEventType event, String matcher) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(event,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                matcher, HookSource.USER_SETTINGS, null)));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        return new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
    }

    // ── 1. 工厂载荷 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("PostToolBatch 工厂 → tool_calls 原样承载")
    void postToolBatchFactory() {
        Map<String, Object> call = new HashMap<>();
        call.put("tool_name", "Bash");
        call.put("tool_use_id", "t1");
        HookEvent event = HookEvent.postToolBatch(List.of(call), "s1", null);

        assertThat(event.type()).isEqualTo(HookEventType.POST_TOOL_BATCH);
        assertThat(event.dataRecord()).isInstanceOf(HookEventData.PostToolBatch.class);
        assertThat(event.data()).containsKey("tool_calls");
    }

    @Test
    @DisplayName("UserPromptExpansion 工厂 → snake_case 五键")
    void userPromptExpansionFactory() {
        HookEvent event = HookEvent.userPromptExpansion(
            "slash_command", "deploy", "prod", "user", "/deploy prod", "s1", null);

        assertThat(event.type()).isEqualTo(HookEventType.USER_PROMPT_EXPANSION);
        assertThat(event.dataRecord()).isInstanceOf(HookEventData.UserPromptExpansion.class);
        assertThat(event.data())
            .containsEntry("expansion_type", "slash_command")
            .containsEntry("command_name", "deploy")
            .containsEntry("command_args", "prod")
            .containsEntry("command_source", "user")
            .containsEntry("prompt", "/deploy prod");
    }

    @Test
    @DisplayName("PreModelSwitch 工厂 → snake_case 九键（含 requested_model）")
    void preModelSwitchFactory() {
        HookEvent event = HookEvent.preModelSwitch(
            "claude-a", "claude-b", "claude-b", "command",
            1234L, Boolean.TRUE, "5m", 0.42d, "catalog", "s1");

        assertThat(event.type()).isEqualTo(HookEventType.PRE_MODEL_SWITCH);
        assertThat(event.dataRecord()).isInstanceOf(HookEventData.PreModelSwitch.class);
        assertThat(event.data())
            .containsEntry("from_model", "claude-a")
            .containsEntry("to_model", "claude-b")
            .containsEntry("requested_model", "claude-b")
            .containsEntry("source", "command")
            .containsEntry("context_tokens", 1234L)
            .containsEntry("prompt_cache_warm", Boolean.TRUE)
            .containsEntry("cache_ttl", "5m")
            .containsEntry("estimated_cache_write_usd", 0.42d)
            .containsEntry("pricing", "catalog");
    }

    @Test
    @DisplayName("PostModelSwitch 工厂 → 同 PreModelSwitch 字段集")
    void postModelSwitchFactory() {
        HookEvent event = HookEvent.postModelSwitch(
            "claude-a", "claude-b", null, "auto",
            null, null, null, null, null, "s1");

        assertThat(event.type()).isEqualTo(HookEventType.POST_MODEL_SWITCH);
        assertThat(event.dataRecord()).isInstanceOf(HookEventData.PostModelSwitch.class);
        assertThat(event.data())
            .containsEntry("from_model", "claude-a")
            .containsEntry("to_model", "claude-b")
            .containsEntry("source", "auto")
            // [Wave2-I] CC requested_model 是 nullable 但键始终存在（显式 null）
            .containsKey("requested_model")
            .containsEntry("requested_model", null);
    }

    @Test
    @DisplayName("DirectoryAdded 工厂 → directory / source")
    void directoryAddedFactory() {
        HookEvent event = HookEvent.directoryAdded("/tmp/x", "slash_command", "s1");

        assertThat(event.type()).isEqualTo(HookEventType.DIRECTORY_ADDED);
        assertThat(event.dataRecord()).isInstanceOf(HookEventData.DirectoryAdded.class);
        assertThat(event.data())
            .containsEntry("directory", "/tmp/x")
            .containsEntry("source", "slash_command");
    }

    @Test
    @DisplayName("MessageDisplay 工厂 → final 序列化为 CC 键名 final")
    void messageDisplayFactory() {
        HookEvent event = HookEvent.messageDisplay("turn-1", "msg-1", 2, Boolean.TRUE, "hello", "s1");

        assertThat(event.type()).isEqualTo(HookEventType.MESSAGE_DISPLAY);
        assertThat(event.dataRecord()).isInstanceOf(HookEventData.MessageDisplay.class);
        assertThat(event.data())
            .containsEntry("turn_id", "turn-1")
            .containsEntry("message_id", "msg-1")
            .containsEntry("index", 2)
            .containsEntry("final", Boolean.TRUE)
            .containsEntry("delta", "hello")
            .doesNotContainKey("final_flush");
    }

    // ── 2. matcher 映射 ────────────────────────────────────────────────────

    @Test
    @DisplayName("UserPromptExpansion matcher 取 command_name")
    void userPromptExpansionMatcherUsesCommandName() {
        HookMatcherEngine engine = engineWith(HookEventType.USER_PROMPT_EXPANSION, "deploy");

        HookEvent hit = HookEvent.userPromptExpansion(
            "slash_command", "deploy", "", null, "/deploy", "s1", null);
        HookEvent miss = HookEvent.userPromptExpansion(
            "slash_command", "other", "", null, "/other", "s1", null);

        assertThat(engine.getMatchingHooks(hit, List.of())).hasSize(1);
        assertThat(engine.getMatchingHooks(miss, List.of())).isEmpty();
    }

    @Test
    @DisplayName("DirectoryAdded matcher 取 source（不是 directory）")
    void directoryAddedMatcherUsesSource() {
        HookMatcherEngine engine = engineWith(HookEventType.DIRECTORY_ADDED, "slash_command");

        HookEvent hit = HookEvent.directoryAdded("/tmp/x", "slash_command", "s1");
        HookEvent miss = HookEvent.directoryAdded("/tmp/x", "register_repo_root", "s1");

        assertThat(engine.getMatchingHooks(hit, List.of())).hasSize(1);
        assertThat(engine.getMatchingHooks(miss, List.of())).isEmpty();
    }

    @Test
    @DisplayName("PreModelSwitch matcher 取 to_model")
    void preModelSwitchMatcherUsesToModel() {
        HookMatcherEngine engine = engineWith(HookEventType.PRE_MODEL_SWITCH, "claude-b");

        HookEvent hit = HookEvent.preModelSwitch(
            "claude-a", "claude-b", null, "command", null, null, null, null, null, "s1");
        HookEvent miss = HookEvent.preModelSwitch(
            "claude-a", "claude-c", null, "command", null, null, null, null, null, "s1");

        assertThat(engine.getMatchingHooks(hit, List.of())).hasSize(1);
        assertThat(engine.getMatchingHooks(miss, List.of())).isEmpty();
    }

    @Test
    @DisplayName("PostToolBatch / MessageDisplay 无 matchQuery → matcher 不参与过滤")
    void noMatchQueryEventsPassAllMatchers() {
        HookMatcherEngine batchEngine = engineWith(HookEventType.POST_TOOL_BATCH, "Bash");
        HookMatcherEngine displayEngine = engineWith(HookEventType.MESSAGE_DISPLAY, "Bash");

        HookEvent batch = HookEvent.postToolBatch(List.of(), "s1", null);
        HookEvent display = HookEvent.messageDisplay("t", "m", 0, false, "x", "s1");

        assertThat(batchEngine.getMatchingHooks(batch, List.of())).hasSize(1);
        assertThat(displayEngine.getMatchingHooks(display, List.of())).hasSize(1);
    }
}
