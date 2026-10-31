package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册面单测 · computer-use 工具「开关关时不注册、开时名字精确、且真的进 LLM tools 数组」。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li>开关默认 false ⇒ 工具<b>不注册</b> ⇒ 模型看不到 {@code mcp__computer-use__*}
 *       （能控制桌面的工具生产默认关，对齐 CC {@code feature('CHICAGO_MCP')} 口径）；</li>
 *   <li>工具名必须<b>逐字</b>是 {@code mcp__computer-use__screenshot} /
 *       {@code mcp__computer-use__left_click} —— CCB {@code setup.ts:17-21} 说 API 后端靠这个名字
 *       识别 computer-use 并注入 CU 可用性提示；改名 = 功能静默失效；</li>
 *   <li>注册进 {@link ToolRegistry} 后必须出现在 {@code toOpenAiToolsArray()} 里
 *       ⇒ 模型真的看得见（这是「截屏 → 模型看得见」的前半段）。</li>
 * </ul>
 */
class ComputerUseToolConfigTest {

    /** 假截屏器（不加载 JNA native，纯单测）。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("注册面测试不需要截屏");
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    /** 假鼠标。 */
    private static final class FakeMouse implements ComputerUseMouse {
        @Override
        public void moveAndSettle(int x, int y) {
        }

        @Override
        public void leftClick(int x, int y) {
        }
    }

    private static void setEnabled(ComputerUseToolConfig config, boolean enabled) throws Exception {
        Field field = ComputerUseToolConfig.class.getDeclaredField("computerUseEnabled");
        field.setAccessible(true);
        field.set(config, enabled);
    }

    @Test
    @DisplayName("开关 false → 不产出任何工具（模型不可见）")
    void gateOff_yieldsNoTools() throws Exception {
        ComputerUseToolConfig config = new ComputerUseToolConfig();
        setEnabled(config, false);
        List<Tool> tools = config.computerUseTools(new ComputerUseGeometryStore());
        assertThat(tools).isEmpty();
    }

    @Test
    @DisplayName("工具名逐字为 mcp__computer-use__screenshot / mcp__computer-use__left_click")
    void toolNamesAreExact() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        List<Tool> tools = List.of(
                new ComputerUseScreenshotTool(new FakeCapturer(), store),
                new ComputerUseLeftClickTool(new FakeCapturer(), new FakeMouse(), store));
        assertThat(tools.stream().map(Tool::name).toList())
                .containsExactly("mcp__computer-use__screenshot", "mcp__computer-use__left_click");
    }

    @Test
    @DisplayName("注册进 ToolRegistry 后出现在 LLM tools 数组里（模型可见）")
    void registeredToolsAppearInLlmToolsArray() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ComputerUseScreenshotTool(new FakeCapturer(), store));
        registry.register(new ComputerUseLeftClickTool(new FakeCapturer(), new FakeMouse(), store));

        assertThat(registry.has("mcp__computer-use__screenshot")).isTrue();
        assertThat(registry.has("mcp__computer-use__left_click")).isTrue();

        ArrayNode array = registry.toOpenAiToolsArray();
        boolean sawScreenshot = false;
        boolean sawLeftClick = false;
        for (int i = 0; i < array.size(); i++) {
            ObjectNode node = (ObjectNode) array.get(i);
            String fnName = node.path("function").path("name").asText("");
            if ("mcp__computer-use__screenshot".equals(fnName)) {
                sawScreenshot = true;
            }
            if ("mcp__computer-use__left_click".equals(fnName)) {
                sawLeftClick = true;
            }
        }
        assertThat(sawScreenshot).isTrue();
        assertThat(sawLeftClick).isTrue();
    }
}
