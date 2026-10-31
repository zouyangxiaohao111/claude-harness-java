package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionResult;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 点击工具的<b>档位门控</b>单测（Phase 1a）。
 *
 * <p><b>WHY（规则 9）</b>：档位门控是「只读授权不许点」这条安全边界的落地处。
 * 若门控恒放行，则 {@code request_access} 给浏览器发的 read 档就形同虚设 ——
 * 模型仍然能点进用户的邮箱 / 网银。
 *
 * <p>三条断言：
 * <ol>
 *   <li>前台应用<b>不在白名单</b> ⇒ 拒绝（CCB toolCalls.ts:578-583）</li>
 *   <li>在白名单但档位是 read ⇒ 拒绝（CCB tierSatisfies）</li>
 *   <li>在白名单且档位 click/full ⇒ 放行（点击真的发生）</li>
 * </ol>
 */
class ComputerUseLeftClickToolTierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    /** 记录点击的假鼠标。 */
    private static final class RecordingMouse implements ComputerUseMouse {
        final List<int[]> clicks = new ArrayList<>();

        @Override
        public void moveAndSettle(int x, int y) {
            // noop
        }

        @Override
        public void leftClick(int x, int y) {
            clicks.add(new int[]{x, y});
        }
    }

    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("点击测试不需要截屏");
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
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
    private RecordingMouse mouse;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId(SESSION);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(record);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
        mouse = new RecordingMouse();
    }

    private void setGrants(String json) {
        record.setComputerUseGrants(json);
    }

    private ComputerUseLeftClickTool tool(String foregroundAppId) {
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        // [Phase 1d] 换算必须基于【截图时刻】的几何快照（冷启动 ⇒ fail loud）⇒ 本测试
        //   预置一份「图像 1920x1080 = 显示 1920x1080」的快照（比例 = 1，像素态恒等）。
        ComputerUseGeometryStore geometryStore = new ComputerUseGeometryStore();
        geometryStore.put(SESSION, new ComputerUseScreenshotGeometry(1920, 1080, 1920, 1080, 0, 0));
        return new ComputerUseLeftClickTool(new FakeCapturer(), mouse, geometryStore,
                ComputerUseCoordinateMode.PIXELS, store, new FixedResolver(foregroundAppId));
    }

    private static ObjectNode input(double x, double y) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        return node;
    }

    @Test
    @DisplayName("checkPermissions 返回 Allow（免弹窗）—— CCB setup.ts:25-32 把 CU 工具放进 allowedTools")
    void checkPermissionsAllowsWithoutPrompt() {
        PermissionResult result = tool("chrome").checkPermissions(input(1, 1), ToolUseContext.of(null, SESSION));
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("前台应用不在白名单 ⇒ 拒绝（不点击）")
    void notGrantedAppIsRejected() {
        setGrants(ComputerUseGrants.empty().toJson());

        var result = tool("chrome").execute(new ToolUseBlock("c1", "mcp__computer-use__left_click", input(10, 10)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("not in the allowed applications");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("白名单内但档位为 read ⇒ 拒绝（tier 门控）")
    void readTierRejectsClick() {
        setGrants(new ComputerUseGrants(List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                ComputerUseGrantFlags.DEFAULT).toJson());

        var result = tool("chrome").execute(new ToolUseBlock("c1", "mcp__computer-use__left_click", input(10, 10)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("白名单内且档位为 click ⇒ 放行（点击真的发生）")
    void clickTierAllowsClick() {
        setGrants(new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "click")),
                ComputerUseGrantFlags.DEFAULT).toJson());

        var result = tool("notepad").execute(new ToolUseBlock("c1", "mcp__computer-use__left_click", input(10, 10)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
        assertThat(mouse.clicks).hasSize(1);
    }

    @Test
    @DisplayName("前台应用未知 ⇒ 放行（CCB toolCalls.ts:502-506 同款：登录窗口等极少数情况）")
    void unknownForegroundAppPassesThrough() {
        setGrants(ComputerUseGrants.empty().toJson());

        var result = tool(null).execute(new ToolUseBlock("c1", "mcp__computer-use__left_click", input(10, 10)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
        assertThat(mouse.clicks).hasSize(1);
    }

    @Test
    @DisplayName("子代理调用同样受档位门控约束（授权是会话级，不因调用者身份放宽）")
    void subagentIsAlsoGated() {
        setGrants(new ComputerUseGrants(List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                ComputerUseGrantFlags.DEFAULT).toJson());

        var result = tool("chrome").execute(new ToolUseBlock("c1", "mcp__computer-use__left_click", input(10, 10)),
                ToolUseContext.of(UUID.randomUUID(), SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(mouse.clicks).isEmpty();
    }
}
