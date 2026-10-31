package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 左键点击工具单测 · 验证「模型给的图坐标 → 真实屏幕坐标」这条链。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：这是整个 computer-use 的正确性核心 ——
 * 模型看的是<b>缩放后的截图</b>，给的是该图上的像素；点击必须落到真实屏幕的同一元素上。
 * 断言「点击坐标 = scaleCoord(截图快照)」把这条链锁死，且不依赖真实桌面（假鼠标只记录坐标）。
 */
class ComputerUseLeftClickToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 假鼠标：只记录收到的屏幕坐标。 */
    private static final class RecordingMouse implements ComputerUseMouse {
        final List<int[]> moves = new ArrayList<>();
        final List<int[]> clicks = new ArrayList<>();

        @Override
        public void moveAndSettle(int x, int y) {
            moves.add(new int[]{x, y});
        }

        @Override
        public void leftClick(int x, int y) {
            clicks.add(new int[]{x, y});
        }
    }

    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        private final ComputerUseDisplayGeometry display;

        FakeCapturer(ComputerUseDisplayGeometry display) {
            this.display = display;
        }

        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("点击测试不需要截屏");
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return display;
        }
    }

    private static ObjectNode input(double x, double y) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        return node;
    }

    @Test
    @DisplayName("图坐标按截图快照比例换算成屏幕坐标后点击")
    void clickUsesScaleCoordOnScreenshotSnapshot() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        // 截图 1456x819（模型所见）↔ 显示 1920x1080（物理像素）。
        // 本用例走 ctx-less 路径（execute(call) ⇒ sessionId=null）⇒ 落全局桶。
        store.put(null, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));

        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0)),
                mouse, store);

        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), input(728, 409.5d));
        var result = tool.execute(call);

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
        assertThat(mouse.moves).hasSize(1);
        assertThat(mouse.clicks).hasSize(1);
        // 728 * (1920/1456) = 960；409.5 * (1080/819) = 540
        assertThat(mouse.clicks.get(0)).containsExactly(960, 540);
    }

    @Test
    @DisplayName("会话隔离：别的会话的截图几何不会被借用（多屏下防点错屏）")
    void otherSessionGeometryIsNotBorrowed() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put("sess-A", new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));

        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0)),
                mouse, store);

        // ctx-less 路径（sessionId=null ⇒ 全局桶）不应看到 sess-A 的快照
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), input(728, 0)));

        // [Phase 1d] 无快照 ⇒ 保守不放行（fail loud）⇒ 一次都不点
        assertThat(mouse.clicks).as("⛔ 无本会话快照 ⇒ 不得盲点").isEmpty();
        assertThat(((ToolResult<?>) result).data().toString()).contains("截图");
    }

    @Test
    @DisplayName("无先截图时保守不放行（fail loud，不猜坐标）")
    void clickWithoutSnapshotFailsLoud() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(new ComputerUseDisplayGeometry(0, 960, 540, 2.0d, 0, 0)),
                mouse, store);

        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), input(100, 200));
        var result = tool.execute(call);

        assertThat(mouse.clicks).isEmpty();
        assertThat(((ToolResult<?>) result).data().toString()).contains("截图");
    }

    @Test
    @DisplayName("坐标非法时报错（不点击）")
    void invalidCoordinateIsRejected() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0)),
                mouse, store);

        ObjectNode bad = JSON.createObjectNode();
        bad.putArray("coordinate").add(1); // 长度不足
        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), bad);
        var result = tool.execute(call);

        assertThat(((ToolResult<?>) result).data().toString()).contains("coordinate");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("[Phase 2b] 修饰键已实现：无点击路由接线时显式报错（不静默忽略模型意图）")
    void modifiersRequireWiredRouter() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0)),
                mouse, store);

        ObjectNode node = input(10, 20);
        node.put("text", "shift");
        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), node);
        var result = tool.execute(call);

        // 无快照 ⇒ 先在坐标换算处 fail loud（不盲点）；无论如何不得真的点击
        assertThat(((ToolResult<?>) result).data().toString()).contains("修饰键");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("工具名精确为 mcp__computer-use__left_click（CCB setup.ts:17-21 硬约束）")
    void toolNameIsExact() {
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0)),
                new RecordingMouse(), new ComputerUseGeometryStore());
        assertThat(tool.name()).isEqualTo("mcp__computer-use__left_click");
    }
}
