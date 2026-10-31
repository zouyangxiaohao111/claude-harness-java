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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code mcp__computer-use__type} 的单测（Phase 1c）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：本工具的核心承诺有两条，都是<b>安全/正确性</b>承诺，
 * 不是"行为细节"：
 * <ol>
 *   <li><b>特殊字符必须原样打出</b>：CCB 的 Windows 文本注入走
 *       {@code [System.Windows.Forms.SendKeys]::SendWait('...')}，而 {@code SendKeys} 把
 *       {@code + ^ % ~ ( ) { } [ ]} 当<b>特殊键标记</b> ⇒ 文本里的 {@code +} 会被当成 Shift、
 *       {@code %} 被当成 Alt。本仓改用 {@code KEYEVENTF_UNICODE} 逐字符注入来修这个缺陷。
 *       <br>⇒ 若哪天改回按键注入，本测试必须变红（否则缺陷静默回归）。</li>
 *   <li><b>不得走剪贴板快路径</b>：CCB 在 Windows 上其实也不走（执行器忽略
 *       {@code viaClipboard}）⇒ 本仓同样不做 ⇒ 也就不会有"读回校验 / 100ms 等待 /
 *       剪贴板恢复"这套副作用。</li>
 * </ol>
 */
class ComputerUseTypeToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    /** 记录注入内容的假键盘（不碰真实桌面）。 */
    private static final class RecordingKeyboard implements ComputerUseKeyboard {
        final List<Integer> typed = new ArrayList<>();
        final List<ComputerUseKeyEvent> keyEvents = new ArrayList<>();

        @Override
        public void typeChar(int codePoint) {
            typed.add(codePoint);
        }

        @Override
        public void sendKeyEvent(ComputerUseKeyEvent event) {
            keyEvents.add(event);
        }
    }

    /** 记录休眠时长的假时钟（单测零耗时）。 */
    private static final class RecordingSleeper implements ComputerUseSleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    /** 前台应用未知（⇒ 放行，CCB toolCalls.ts:502-506 同款）。 */
    private static final class NullResolver implements ComputerUseForegroundAppResolver {
        @Override
        public String currentAppId() {
            return null;
        }
    }

    /** 固定返回某个前台应用的解析器。 */
    private record FixedResolver(String appId) implements ComputerUseForegroundAppResolver {
        @Override
        public String currentAppId() {
            return appId;
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

    private void setGrants(String json) {
        record.setComputerUseGrants(json);
    }

    private ComputerUseTypeTool tool(ComputerUseGrantStore store,
                                     ComputerUseForegroundAppResolver resolver) {
        return new ComputerUseTypeTool(keyboard, sleeper, store, resolver);
    }

    private static ObjectNode input(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", text);
        return node;
    }

    @Test
    @DisplayName("工具面逐字对齐 CCB：name / description / schema")
    void toolSurfaceMatchesCcbVerbatim() {
        ComputerUseTypeTool tool = tool(null, null);

        assertThat(tool.name()).isEqualTo("mcp__computer-use__type");
        assertThat(tool.description())
                .isEqualTo("Type text into whatever currently has keyboard focus. "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing. "
                        + "Newlines are supported. For keyboard shortcuts use `key` instead.");
        assertThat(tool.inputSchema().path("required").get(0).asText()).isEqualTo("text");
        assertThat(tool.inputSchema().path("properties").path("text").path("description").asText())
                .isEqualTo("Text to type.");
        assertThat(tool.inputSchema().path("properties").path("text").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("特殊字符 + ^ % ~ ( ) { } [ ] 原样打出，绝不被当特殊键（本仓对 CCB SendKeys 缺陷的修正）")
    void specialCharactersAreTypedAsPlainCharacters() {
        String text = "a+b^c%d~e(f)g{h}i[j]k";
        ComputerUseTypeTool tool = tool(null, null);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", input(text)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Typed " + text.length() + " grapheme(s).");
        assertThat(keyboard.keyEvents)
                .as("特殊字符绝不能变成按键事件（+ 不能变 Shift、% 不能变 Alt）")
                .isEmpty();
        assertThat(keyboard.typed)
                .as("每个字符都必须走 Unicode 路径逐个注入")
                .containsExactly(text.chars().boxed().toArray(Integer[]::new));
    }

    @Test
    @DisplayName("不做剪贴板快路径：工具不持有任何剪贴板依赖（结构断言）")
    void noClipboardDependencyExists() {
        for (Field field : ComputerUseTypeTool.class.getDeclaredFields()) {
            String typeName = field.getType().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            assertThat(typeName)
                    .as("type 工具不得持有剪贴板相关依赖（字段: " + field.getName() + "）")
                    .doesNotContain("clipboard");
        }
    }

    @Test
    @DisplayName("即使授予了 clipboardWrite 且文本多行 ⇒ 仍逐字形注入（走 Return 键），不走剪贴板粘贴")
    void multilineTextDoesNotUseClipboardEvenWhenGranted() {
        setGrants(new ComputerUseGrants(List.of(),
                new ComputerUseGrantFlags(false, true, false)).toJson());
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseTypeTool tool = tool(store, new NullResolver());

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", input("ab\ncd")));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Typed 5 grapheme(s).");
        assertThat(keyboard.typed).containsExactly((int) 'a', (int) 'b', (int) 'c', (int) 'd');
        assertThat(keyboard.keyEvents)
                .as("换行必须走 Return 键（不是字符注入，更不是剪贴板粘贴）")
                .hasSize(2);
        assertThat(keyboard.keyEvents.get(0).name()).isEqualTo("return");
        assertThat(keyboard.keyEvents.get(0).down()).isTrue();
        assertThat(keyboard.keyEvents.get(1).down()).isFalse();
    }

    @Test
    @DisplayName("换行走 Return 键、制表符走 Tab 键（CCB toolCalls.ts:2537-2546）")
    void controlCharactersRouteToNamedKeys() {
        ComputerUseTypeTool tool = tool(null, null);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", input("\r\n\t")));

        assertThat(((ToolResult<?>) result).data())
                .as("CRLF 是一个字形簇（UAX #29 GB3）⇒ 只有 1 个 Return + 1 个 Tab")
                .isEqualTo("Typed 2 grapheme(s).");
        assertThat(keyboard.keyEvents).hasSize(4);
        assertThat(keyboard.keyEvents.get(0).name()).isEqualTo("return");
        assertThat(keyboard.keyEvents.get(0).down()).isTrue();
        assertThat(keyboard.keyEvents.get(1).name()).isEqualTo("return");
        assertThat(keyboard.keyEvents.get(1).down()).isFalse();
        assertThat(keyboard.keyEvents.get(2).name()).isEqualTo("tab");
        assertThat(keyboard.keyEvents.get(3).name()).isEqualTo("tab");
        assertThat(keyboard.typed).as("控制字符不按普通字符注入").isEmpty();
    }

    @Test
    @DisplayName("每个字形簇之前 sleep 8ms（125Hz USB 轮询节奏，CCB toolCalls.ts:715/2558）")
    void sleeps8msBeforeEachGrapheme() {
        ComputerUseTypeTool tool = tool(null, null);

        tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", input("abc")));

        assertThat(sleeper.sleeps).containsExactly(8L, 8L, 8L);
    }

    @Test
    @DisplayName("ZWJ emoji 是一个字形簇（不被拆散），码元按序注入")
    void zwjEmojiStaysOneGrapheme() {
        String emoji = "👨‍👩‍👧";
        ComputerUseTypeTool tool = tool(null, null);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", input(emoji)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Typed 1 grapheme(s).");
        assertThat(keyboard.typed)
                .as("一个 emoji 的所有码元按序注入（ZWJ 也在内），由应用自行合成")
                .hasSize(emoji.codePointCount(0, emoji.length()));
    }

    @Test
    @DisplayName("text 非字符串 ⇒ 报错 \"text\" must be a string.（CCB requireString 文案）")
    void nonStringTextIsRejected() {
        ComputerUseTypeTool tool = tool(null, null);
        ObjectNode bad = JSON.createObjectNode();
        bad.put("text", 42);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", bad));

        assertThat(((ToolResult<?>) result).data().toString()).contains("must be a string");
        assertThat(keyboard.typed).isEmpty();
    }

    @Test
    @DisplayName("前台应用不在白名单 ⇒ 拒绝（CCB runInputActionGates），且一个字都不打")
    void foregroundAppNotAllowedIsRejected() {
        setGrants(ComputerUseGrants.empty().toJson());
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseTypeTool tool = tool(store, new FixedResolver("chrome"));

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__type", input("hello")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("not in the allowed applications");
        assertThat(keyboard.typed).isEmpty();
        assertThat(keyboard.keyEvents).isEmpty();
    }
}
