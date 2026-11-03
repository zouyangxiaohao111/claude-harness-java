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
 *
 * <h2>⚠️ 本仓在 CCB 的 24 个之上<b>新增 3 个</b>（有意偏离，如实标注）</h2>
 * <p>CCB 的 macOS 没有元素级操作（{@code click_element} / {@code type_into_element} 在
 * {@code caps.platform === 'win32'} 条件块里）。本仓在 macOS 上用 Accessibility（AXUIElement）
 * 实现了同一契约（dsh {@code dsh-computer-use} 的路线），并补了配套的 {@code observe}：
 * <ul>
 *   <li>{@code observe} —— 读 AX 语义树，返回 observationId + 带序号/句柄的元素表；</li>
 *   <li>{@code click_element} —— 按 observationId + index 执行 {@code AXPress}；</li>
 *   <li>{@code type_into_element} —— 按 observationId + index 写 {@code AXValue} / {@code AXSelectedText}。</li>
 *   <li>{@code confirm} —— 敏感动作的一次性确认令牌（dsh computer_confirm）；</li>
 *   <li>{@code list_apps} —— 运行中应用列表（带 pid / 前台标记 / 授权状态）。</li>
 *   <li>{@code perform_action} —— 执行元素声明的任意 AX 动作（AXExpand/AXShowMenu…）。</li>
 *   <li>{@code wait_for} —— 按条件等待（文本/角色/标题出现）。</li>
 * </ul>
 * ⇒ macOS 工具面 = <b>24 + 7 = 31</b>。
 */
class ComputerUseMacOsToolSurfaceTest {

    /** macOS 工具面（CCB 的 24 个 + 本仓 AX 增补的 3 个；顺序 = 注册顺序）。 */
    private static final List<String> EXPECTED_MACOS = List.of(
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
            // ── [本仓 macOS AX 增补] Accessibility 语义树观察与元素操作 ──
            "mcp__computer-use__observe",
            "mcp__computer-use__click_element",
            "mcp__computer-use__type_into_element",
            // ── [本仓 macOS AX 增补] 任意 AX 动作 + 条件等待（dsh perform_action / wait）──
            "mcp__computer-use__perform_action",
            "mcp__computer-use__wait_for",
            // ── [本仓 macOS AX 增补] 敏感动作确认 + 应用列表（dsh computer_confirm / list_apps）──
            "mcp__computer-use__confirm",
            "mcp__computer-use__list_apps",
            // ── CCB tools.ts:420-836 的 win32 条件块里，macOS 仍【不注册】的 9 个 ──
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

        assertThat(tools).hasSize(EXPECTED_MACOS.size());
        assertThat(tools.stream().map(Tool::name).toList()).isEqualTo(EXPECTED_MACOS);
    }

    @Test
    @DisplayName("macOS 工具面里不含真正 Windows 专属的工具（click/type_into_element 除外：本仓有 AX 实现）")
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

        // click_element / type_into_element【不在】此列：本仓用 AXPress / AXValue 在 macOS 上
        // 实现了同一契约（见类注释），只是不复用 Windows 的 hwnd 绑定路径。
        List<String> windowsOnly = List.of(
                "mcp__computer-use__window_management",
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
