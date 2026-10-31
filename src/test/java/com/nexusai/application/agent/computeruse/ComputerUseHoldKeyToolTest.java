package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code hold_key} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>逆序释放</b>：CCB {@code executorCrossPlatform.ts:305-313} 是
 *       「正序按下 → 等待 → <b>逆序</b>释放」⇒ 断言事件序列；</li>
 *   <li><b>异常路径不卡键</b>：按住期间抛异常 ⇒ 已按下的键必须被释放
 *       ⇒ 断言「Shift 没有被留在按下态」；</li>
 *   <li><b>黑名单先于门控</b>：CCB {@code toolCalls.ts:3737-3745}（与 {@code key} 工具同款顺序）
 *       ⇒ 未授予 {@code systemKeyCombos} 时，{@code alt+f4} 必须报「系统级快捷键」
 *       而不是「前台应用不在白名单」。</li>
 * </ul>
 */
class ComputerUseHoldKeyToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode input(String text, double duration) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", text);
        node.put("duration", duration);
        return node;
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:923-926")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(
                new ComputerUsePhase2aFakes.FakeKeyboard(), new ComputerUsePhase2aFakes.RecordingSleeper(),
                null, null);

        assertThat(tool.name()).isEqualTo("mcp__computer-use__hold_key");
        assertThat(tool.description()).isEqualTo(
                "Press and hold a key or key combination for the specified duration, then release. "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing. "
                        + "System-level combos require the `systemKeyCombos` grant.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:927-939（text + duration；required 两者）")
    void schemaIsVerbatim() {
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(
                new ComputerUsePhase2aFakes.FakeKeyboard(), new ComputerUsePhase2aFakes.RecordingSleeper(),
                null, null);
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("text").path("description").asText())
                .isEqualTo("Key or chord to hold, e.g. \"space\", \"shift+down\".");
        assertThat(schema.path("properties").path("duration").path("type").asText()).isEqualTo("number");
        assertThat(schema.path("properties").path("duration").path("description").asText())
                .isEqualTo("Duration in seconds (0–100).");
        assertThat(schema.path("required")).hasSize(2);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("text");
        assertThat(schema.path("required").get(1).asText()).isEqualTo("duration");
    }

    @Test
    @DisplayName("正序按下 → 等待 → 逆序释放（CCB executorCrossPlatform.ts:305-313）")
    void pressesInOrderReleasesInReverse() {
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        ComputerUsePhase2aFakes.RecordingSleeper sleeper = new ComputerUsePhase2aFakes.RecordingSleeper();
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(keyboard, sleeper, null, null);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("shift+down", 0.5d)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Key held.");
        assertThat(keyboard.events).hasSize(4);
        assertThat(keyboard.events.get(0).name()).isEqualTo("shift");
        assertThat(keyboard.events.get(0).down()).isTrue();
        assertThat(keyboard.events.get(1).name()).isEqualTo("down");
        assertThat(keyboard.events.get(1).down()).isTrue();
        assertThat(keyboard.events.get(2).name()).isEqualTo("down");
        assertThat(keyboard.events.get(2).down()).isFalse();
        assertThat(keyboard.events.get(3).name()).isEqualTo("shift");
        assertThat(keyboard.events.get(3).down()).isFalse();
        // 等待时长 = duration × 1000
        assertThat(sleeper.slept).containsExactly(500L);
    }

    @Test
    @DisplayName("异常路径 ⇒ 已按下的键被逆序释放（绝不卡键）")
    void failureReleasesPressedKeys() {
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        // 第 2 个事件（第 2 个键按下）时抛异常
        keyboard.failAtEventIndex = 1;
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(keyboard,
                new ComputerUsePhase2aFakes.RecordingSleeper(), null, null);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("shift+down", 0.5d)));

        assertThat(((ToolResult<?>) result).data().toString()).contains("按键保持失败");
        // shift 已按下（第 0 个事件）⇒ 必须被释放（第 1 个事件 = shift up）
        assertThat(keyboard.events).hasSize(2);
        assertThat(keyboard.events.get(1).name()).isEqualTo("shift");
        assertThat(keyboard.events.get(1).down()).isFalse();
    }

    @Test
    @DisplayName("系统级组合键（alt+f4）无 systemKeyCombos 授权 ⇒ 拦截（黑名单先于门控）")
    void systemComboIsBlockedWithoutGrant() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "full")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(keyboard,
                new ComputerUsePhase2aFakes.RecordingSleeper(), grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("notepad"));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("alt+f4", 0.1d)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("system-level shortcut");
        assertThat(keyboard.events).isEmpty();
    }

    @Test
    @DisplayName("已授予 systemKeyCombos ⇒ 放行 alt+f4")
    void systemComboAllowedWithGrant() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "full")),
                        new ComputerUseGrantFlags(true, false, true)).toJson());
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(keyboard,
                new ComputerUsePhase2aFakes.RecordingSleeper(), grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("notepad"));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("alt+f4", 0.1d)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Key held.");
        assertThat(keyboard.events).hasSize(4);
    }

    @Test
    @DisplayName("duration > 100 ⇒ 报错（CCB toolCalls.ts:3728-3733）")
    void durationOverLimitIsRejected() {
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(keyboard,
                new ComputerUsePhase2aFakes.RecordingSleeper(), null, null);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("shift", 101d)));

        assertThat(((ToolResult<?>) result).data().toString()).contains("too long");
        assertThat(keyboard.events).isEmpty();
    }

    @Test
    @DisplayName("read 档被拒（hold_key 属 keyboard 档 ⇒ 需 full）")
    void readTierRejectsHoldKey() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "read")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        ComputerUseHoldKeyTool tool = new ComputerUseHoldKeyTool(keyboard,
                new ComputerUsePhase2aFakes.RecordingSleeper(), grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("notepad"));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("shift", 0.1d)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(keyboard.events).isEmpty();
    }
}
