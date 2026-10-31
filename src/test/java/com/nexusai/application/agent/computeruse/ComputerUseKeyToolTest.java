package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code mcp__computer-use__key} 的单测（Phase 1c）。
 *
 * <p><b>WHY（规则 9）</b>：本工具的语义承诺有三条：
 * <ol>
 *   <li><b>组合键派发顺序</b>：修饰键<b>按下顺序 = 输入顺序</b>、<b>逆序释放</b>
 *       （CCB {@code src/utils/computerUse/win32/windowMessage.ts:341-366}）。
 *       顺序错了 ⇒ "ctrl+shift+a" 可能变成 "shift+ctrl+a"，某些应用里语义完全不同。</li>
 *   <li><b>{@code repeat} 真的重复</b>：CCB {@code executorCrossPlatform.ts:296-303}
 *       （{@code for (let i = 0; i < n; i++)}）。</li>
 *   <li><b>系统级组合键必须被拦</b>（除非授予 {@code systemKeyCombos}）——
 *       工具描述明写了这条，行为必须兑现（否则等于对模型说谎）。</li>
 * </ol>
 */
class ComputerUseKeyToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    /** 记录按键事件的假键盘。 */
    private static final class RecordingKeyboard implements ComputerUseKeyboard {
        final List<ComputerUseKeyEvent> events = new ArrayList<>();

        @Override
        public void typeChar(int codePoint) {
            throw new UnsupportedOperationException("key 工具不应走字符注入路径");
        }

        @Override
        public void sendKeyEvent(ComputerUseKeyEvent event) {
            events.add(event);
        }
    }

    /** 记录休眠时长的假时钟。 */
    private static final class RecordingSleeper implements ComputerUseSleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    private SessionMapper sessionMapper;
    private SessionRecord record;
    private RecordingKeyboard keyboard;
    private RecordingSleeper sleeper;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId(SESSION);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(record);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
        keyboard = new RecordingKeyboard();
        sleeper = new RecordingSleeper();
    }

    private void setFlags(ComputerUseGrantFlags flags) {
        record.setComputerUseGrants(new ComputerUseGrants(List.of(), flags).toJson());
    }

    private ComputerUseKeyTool tool(ComputerUseGrantStore store) {
        return new ComputerUseKeyTool(keyboard, sleeper, store, null);
    }

    private static ObjectNode input(String text, Object repeat) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", text);
        if (repeat != null) {
            if (repeat instanceof Integer integer) {
                node.put("repeat", integer);
            } else {
                node.put("repeat", ((Number) repeat).doubleValue());
            }
        }
        return node;
    }

    @Test
    @DisplayName("工具面逐字对齐 CCB：name / description / schema")
    void toolSurfaceMatchesCcbVerbatim() {
        ComputerUseKeyTool tool = tool(null);

        assertThat(tool.name()).isEqualTo("mcp__computer-use__key");
        assertThat(tool.description())
                .isEqualTo("Press a key or key combination (e.g. \"return\", \"escape\", \"cmd+a\", "
                        + "\"ctrl+shift+tab\"). "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing. "
                        + "System-level combos (quit app, switch app, lock screen) require the "
                        + "`systemKeyCombos` grant — without it they return an error. All other combos work.");
        assertThat(tool.inputSchema().path("required").get(0).asText()).isEqualTo("text");
        assertThat(tool.inputSchema().path("properties").path("text").path("description").asText())
                .isEqualTo("Modifiers joined with \"+\", e.g. \"cmd+shift+a\".");
        assertThat(tool.inputSchema().path("properties").path("repeat").path("type").asText())
                .isEqualTo("integer");
        assertThat(tool.inputSchema().path("properties").path("repeat").path("minimum").asInt()).isEqualTo(1);
        assertThat(tool.inputSchema().path("properties").path("repeat").path("maximum").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("组合键派发顺序：修饰键按输入顺序按下、主键 down/up、修饰键逆序释放")
    void modifierOrderingIsInputOrderDownReverseUp() {
        ComputerUseKeyTool tool = tool(null);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("ctrl+shift+a", null)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Key pressed.");
        assertThat(keyboard.events).hasSize(6);
        assertThat(keyboard.events.get(0).name()).isEqualTo("ctrl");
        assertThat(keyboard.events.get(0).down()).isTrue();
        assertThat(keyboard.events.get(1).name()).isEqualTo("shift");
        assertThat(keyboard.events.get(1).down()).isTrue();
        assertThat(keyboard.events.get(2).name()).isEqualTo("a");
        assertThat(keyboard.events.get(2).down()).isTrue();
        assertThat(keyboard.events.get(3).name()).isEqualTo("a");
        assertThat(keyboard.events.get(3).down()).isFalse();
        assertThat(keyboard.events.get(4).name())
                .as("修饰键逆序释放（最后按下的最先释放）")
                .isEqualTo("shift");
        assertThat(keyboard.events.get(5).name()).isEqualTo("ctrl");
    }

    @Test
    @DisplayName("单键（return）⇒ 只有主键的 down/up 两个事件")
    void singleNamedKeyProducesDownUpOnly() {
        ComputerUseKeyTool tool = tool(null);

        tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("return", null)));

        assertThat(keyboard.events).hasSize(2);
        assertThat(keyboard.events.get(0).name()).isEqualTo("return");
        assertThat(keyboard.events.get(0).virtualKey()).isEqualTo(0x0d);
        assertThat(keyboard.events.get(1).down()).isFalse();
    }

    @Test
    @DisplayName("repeat=3 ⇒ 真的派发 3 次，重复之间 sleep 8ms（CCB executorCrossPlatform.ts:302）")
    void repeatIsEffective() {
        ComputerUseKeyTool tool = tool(null);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("a", 3)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Key pressed.");
        assertThat(keyboard.events).hasSize(6);
        assertThat(sleeper.sleeps).containsExactly(8L, 8L);
    }

    @Test
    @DisplayName("repeat 非法值 ⇒ 逐字报错（CCB toolCalls.ts:2577-2590）")
    void invalidRepeatIsRejected() {
        ComputerUseKeyTool tool = tool(null);

        var zero = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("a", 0)));
        assertThat(((ToolResult<?>) zero).data().toString()).contains("repeat must be a positive integer");

        var tooMany = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("a", 101)));
        assertThat(((ToolResult<?>) tooMany).data().toString()).contains("repeat exceeds maximum of 100");

        var fractional = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("a", 2.5)));
        assertThat(((ToolResult<?>) fractional).data().toString())
                .contains("repeat must be a positive integer");

        assertThat(keyboard.events).isEmpty();
    }

    @Test
    @DisplayName("repeat 写成 1.0 也合法（JS 无 int/float 之分，Number.isInteger(1.0)=true）")
    void integralDoubleRepeatIsAccepted() {
        ComputerUseKeyTool tool = tool(null);
        ObjectNode node = JSON.createObjectNode();
        node.put("text", "a");
        node.put("repeat", 1.0d);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", node));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Key pressed.");
        assertThat(keyboard.events).hasSize(2);
    }

    @Test
    @DisplayName("系统级组合键（alt+F4 / win+L / cmd+D）⇒ 未授予 systemKeyCombos 时被拦")
    void systemCombosAreBlockedWithoutGrant() {
        setFlags(ComputerUseGrantFlags.DEFAULT);
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseKeyTool tool = tool(store);

        for (String seq : new String[]{"alt+f4", "win+l", "cmd+d", "ctrl+alt+delete"}) {
            keyboard.events.clear();
            var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input(seq, null)),
                    ToolUseContext.of(null, SESSION));
            assertThat(((ToolResult<?>) result).data().toString())
                    .as("必须报黑名单错（模型的修法是去申请授权位，而不是换焦点）")
                    .contains("system-level shortcut");
            assertThat(keyboard.events).as("被拦时不得派发任何按键").isEmpty();
        }
    }

    @Test
    @DisplayName("授予 systemKeyCombos 后 ⇒ 系统级组合键放行")
    void systemCombosPassWithGrant() {
        setFlags(new ComputerUseGrantFlags(false, false, true));
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseKeyTool tool = tool(store);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("alt+f4", null)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Key pressed.");
        assertThat(keyboard.events).hasSize(4);
    }

    @Test
    @DisplayName("普通组合键不被黑名单误伤（ctrl+c / cmd+a / shift+tab）")
    void ordinaryCombosAreNotBlocked() {
        setFlags(ComputerUseGrantFlags.DEFAULT);
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseKeyTool tool = tool(store);

        for (String seq : new String[]{"ctrl+c", "cmd+a", "shift+tab"}) {
            keyboard.events.clear();
            var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input(seq, null)),
                    ToolUseContext.of(null, SESSION));
            assertThat(((ToolResult<?>) result).data().toString())
                    .as("普通组合键必须放行: " + seq)
                    .doesNotContain("system-level shortcut");
            assertThat(keyboard.events).isNotEmpty();
        }
    }

    @Test
    @DisplayName("未知键名 ⇒ 明确报错（fail loud），不静默忽略")
    void unknownKeyNameIsRejected() {
        ComputerUseKeyTool tool = tool(null);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", input("ctrl+nosuchkey", null)));

        assertThat(((ToolResult<?>) result).data().toString()).contains("未知键名");
        assertThat(keyboard.events).isEmpty();
    }

    @Test
    @DisplayName("text 缺失 ⇒ 报错 'text is required'（CCB toolCalls.ts:2573）")
    void missingTextIsRejected() {
        ComputerUseKeyTool tool = tool(null);
        ObjectNode empty = JSON.createObjectNode();

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__key", empty));

        assertThat(((ToolResult<?>) result).data().toString()).contains("text is required");
        assertThat(keyboard.events).isEmpty();
    }
}
