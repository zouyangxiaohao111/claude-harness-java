package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.Tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * macOS <b>工具面</b>单测 · computer-use。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li>CCB 把 11 个 Windows 专属工具放在
 *       {@code caps.platform === 'win32' ? [...] : []} 条件块里
 *       （{@code packages/@ant/computer-use-mcp/src/tools.ts:420-836}）
 *       ⇒ <b>CCB 的 macOS 工具面 = 35 − 11 = 24 个</b>。</li>
 *   <li>本仓若在 macOS 上也注册那 11 个 ⇒ 模型会看到 11 个<b>永远调不通</b>的工具
 *       （窗口枚举 / UIA / 浮层 / 终端全是 Windows 专属）⇒ 必须逐字对齐 CCB 的 24 个。</li>
 *   <li>工具名是 <b>API 契约</b>（CCB {@code setup.ts:17-21}）⇒ 名字集合逐字断言。</li>
 * </ul>
 *
 * <p>本测试用 {@link ComputerUseToolConfig#resolvePlatform()} 的覆写点注入 macOS 平台
 * ⇒ <b>不依赖运行机器的操作系统</b>（开发机是 Windows 也能跑）。
 */
class ComputerUseMacOsToolSurfaceTest {

    /** CCB 的 macOS 工具面 = 35 − 11（Windows 专属）= 24 个（顺序 = CCB tools.ts 的顺序）。 */
    private static final List<String> EXPECTED_24 = List.of(
            "mcp__computer-use__request_access",
            "mcp__computer-use__screenshot",
            "mcp__computer-use__zoom",
            "mcp__computer-use__left_click",
            "mcp__computer-use__double_click",
            "mcp__computer-use__triple_click",
            "mcp__computer-use__right_click",
            "mcp__computer-use__middle_click",
            "mcp__computer-use__type",
            "mcp__computer-use__key",
            "mcp__computer-use__scroll",
            "mcp__computer-use__left_click_drag",
            "mcp__computer-use__mouse_move",
            "mcp__computer-use__open_application",
            // ── CCB tools.ts:420-836 的 win32 条件块（11 个）在 macOS 上【不注册】──
            "mcp__computer-use__switch_display",
            "mcp__computer-use__list_granted_applications",
            "mcp__computer-use__read_clipboard",
            "mcp__computer-use__write_clipboard",
            "mcp__computer-use__wait",
            "mcp__computer-use__cursor_position",
            "mcp__computer-use__hold_key",
            "mcp__computer-use__left_mouse_down",
            "mcp__computer-use__left_mouse_up",
            "mcp__computer-use__computer_batch");

    /** 注入 macOS 平台的配置（覆写探测点 ⇒ 不依赖运行机器的 OS）。 */
    private static final class MacOsToolConfig extends ComputerUseToolConfig {
        @Override
        protected ComputerUsePlatform resolvePlatform() {
            return ComputerUsePlatform.of(ComputerUsePlatform.Kind.MACOS);
        }
    }

    private static void setEnabled(ComputerUseToolConfig config, boolean enabled) throws Exception {
        Field field = ComputerUseToolConfig.class.getDeclaredField("computerUseEnabled");
        field.setAccessible(true);
        field.set(config, enabled);
    }

    @Test
    @DisplayName("macOS 工具面 = 24 个（CCB 的 macOS 集合），Windows 专属 11 个不注册")
    void macOsToolSurfaceIsExactlyCcbMacOsSet() throws Exception {
        ComputerUseToolConfig config = new MacOsToolConfig();
        setEnabled(config, true);

        List<Tool> tools = config.computerUseTools(
                new ComputerUseGeometryStore(),
                ComputerUsePhase2aFakes.grantStore("sess-1", ComputerUseGrants.empty().toJson()),
                new ComputerUseLockService(mock(DataSource.class), true),
                null, null,
                new ComputerUseMouseState(), new ComputerUseDisplayStore(),
                new ComputerUseWindowBindingStore());

        assertThat(tools).hasSize(24);
        assertThat(tools.stream().map(Tool::name).toList()).isEqualTo(EXPECTED_24);
    }

    @Test
    @DisplayName("macOS 工具面里不含任何 Windows 专属工具")
    void macOsToolSurfaceHasNoWindowsOnlyTools() throws Exception {
        ComputerUseToolConfig config = new MacOsToolConfig();
        setEnabled(config, true);

        List<Tool> tools = config.computerUseTools(
                new ComputerUseGeometryStore(),
                ComputerUsePhase2aFakes.grantStore("sess-1", ComputerUseGrants.empty().toJson()),
                new ComputerUseLockService(mock(DataSource.class), true),
                null, null,
                new ComputerUseMouseState(), new ComputerUseDisplayStore(),
                new ComputerUseWindowBindingStore());

        List<String> windowsOnly = List.of(
                "mcp__computer-use__window_management",
                "mcp__computer-use__click_element",
                "mcp__computer-use__type_into_element",
                "mcp__computer-use__open_terminal",
                "mcp__computer-use__bind_window",
                "mcp__computer-use__activate_window",
                "mcp__computer-use__prompt_respond",
                "mcp__computer-use__status_indicator",
                "mcp__computer-use__virtual_keyboard",
                "mcp__computer-use__virtual_mouse",
                "mcp__computer-use__mouse_wheel");

        assertThat(tools.stream().map(Tool::name).toList())
                .doesNotContainAnyElementsOf(windowsOnly);
    }
}
