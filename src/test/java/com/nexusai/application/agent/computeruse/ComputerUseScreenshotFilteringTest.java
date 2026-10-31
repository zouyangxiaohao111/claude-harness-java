package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Phase 1b · B] {@code screenshotFiltering} 的取值与自洽性单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：{@code screenshotFiltering} 是<b>对模型说的话</b> ——
 * 它告诉模型「截图会不会按白名单过滤」。本仓<b>没有</b>白名单式截图过滤（也没有 CCB 的 macOS
 * 合成器级过滤）⇒ 报 {@code "native"} 是<b>不诚实的</b>（模型会以为截图已被过滤，
 * 从而对未过滤画面产生错误预期）⇒ Phase 1b 改为 {@code "none"}（与 CCB-Windows 同款）。
 *
 * <p>本测试同时锁住一条容易连带失效的语义：<b>{@code screenshotFiltering} 与档位门控彼此独立</b> ——
 * CCB 在 Windows 因 {@code 'none'} 会整段跳过门控，<b>本仓的档位门控仍然生效</b>（有意比 CCB-Windows 更严）。
 */
class ComputerUseScreenshotFilteringTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-filtering";

    @TempDir
    Path tempDir;

    private SessionMapper sessionMapper;
    private SessionRecord sessionRecord;
    private ComputerUseGrantStore grantStore;
    private ComputerUseLockService lockService;

    @BeforeEach
    void setUp() throws Exception {
        sessionMapper = mock(SessionMapper.class);
        sessionRecord = new SessionRecord();
        sessionRecord.setId(SESSION);
        sessionRecord.setComputerUseGrants(null);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(sessionRecord);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
        grantStore = new ComputerUseGrantStore(sessionMapper);

        SQLiteDataSource ds = ComputerUseTestSqlite.create(tempDir, "cu-filtering.db");
        ComputerUseTestSqlite.createLockTable(ds);
        lockService = new ComputerUseLockService(ds, true);
    }

    @Test
    @DisplayName("常量取值 = \"none\"（本仓不做截图过滤 ⇒ 不能报 \"native\"）")
    void constantIsNone() {
        assertThat(ComputerUseRequestAccessResult.SCREENSHOT_FILTERING)
                .as("报 \"native\" 属于对模型说谎（本仓根本没有白名单式过滤）")
                .isEqualTo("none");
    }

    @Test
    @DisplayName("request_access 返回的 JSON 里 screenshotFiltering = \"none\"")
    void resultJsonReportsNone() {
        ComputerUseRequestAccessTool tool = new ComputerUseRequestAccessTool(grantStore, lockService);
        ObjectNode input = JSON.createObjectNode();
        input.putArray("apps").add("Notepad");
        input.put("reason", "理由");

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input),
                ToolUseContext.of((UUID) null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("screenshotFiltering").asText()).isEqualTo("none");
    }

    @Test
    @DisplayName("档位门控仍然生效（screenshotFiltering=\"none\" 不削弱门控 —— 比 CCB-Windows 更严）")
    void tierGateStillAppliesWhenFilteringIsNone() {
        // 会话里 chrome 只有 read 档
        sessionRecord.setComputerUseGrants(new ComputerUseGrants(
                List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                ComputerUseGrantFlags.DEFAULT).toJson());

        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new NoopCapturer(), new NoopMouse(), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, grantStore, () -> "chrome");

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(10).add(20);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__left_click", input),
                ToolUseContext.of((UUID) null, SESSION));

        assertThat(ComputerUseRequestAccessResult.SCREENSHOT_FILTERING).isEqualTo("none");
        assertThat(((ToolResult<?>) result).data().toString())
                .as("read 档点击必须仍被拒（门控与截图过滤彼此独立）")
                .contains("tier");
    }

    @Test
    @DisplayName("截图工具描述与 \"none\" 自洽（明说截图不被过滤）")
    void screenshotToolDescriptionIsConsistent() {
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                new NoopCapturer(), new ComputerUseGeometryStore());
        assertThat(tool.description())
                .contains("screenshots are NOT filtered")
                .contains("all open windows are visible");
    }

    private static final class NoopCapturer implements ComputerUseScreenCapturer {
        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("本用例不需要截屏");
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    private static final class NoopMouse implements ComputerUseMouse {
        @Override
        public void moveAndSettle(int x, int y) {
            throw new AssertionError("read 档点击必须被门控拦下");
        }

        @Override
        public void leftClick(int x, int y) {
            throw new AssertionError("read 档点击必须被门控拦下");
        }
    }
}
