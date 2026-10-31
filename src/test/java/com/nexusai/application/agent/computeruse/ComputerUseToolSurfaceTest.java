package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.Tool;

import com.sun.jna.Platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * computer-use <b>工具面</b>单测（Phase 2b）—— 35 个工具齐平 CCB 全集。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li><b>工具名是 API 契约</b>：CCB {@code setup.ts:17-21} 说 API 后端靠
 *       {@code mcp__computer-use__<tool>} 这个名字识别 computer-use 并注入 CU 可用性提示
 *       ⇒ 改名 = 功能静默失效（模型再也调不到）⇒ 名字集合<b>逐字</b>断言；</li>
 *   <li><b>数量对齐</b>：CCB 全集 = 35 个（24 通用 + 11 Windows 专属）
 *       ⇒ 断言「不多不少 35 个」——少一个 = 模型少一条能力；
 *       多一个 = 模型看到了一个本仓根本没实现的工具；</li>
 *   <li><b>顺序也对齐</b>：Windows 专属的 11 个在 CCB 的
 *       {@code caps.platform === 'win32' ? [...] : []} 条件块里（{@code tools.ts:420-836}），
 *       位置在 {@code open_application} 之后、{@code switch_display} 之前
 *       ⇒ 本仓的注册顺序必须一致，否则模型看到的工具排列与 CCB 不同。</li>
 *   <li><b>配置真的注册了它们</b>：工具类写了但没接进 {@code ComputerUseToolConfig}
 *       ⇒ 模型在 LLM tools 数组里看不到 ⇒ 断言注册后的名字集合。</li>
 * </ul>
 */
class ComputerUseToolSurfaceTest {

    /** CCB 全集的 35 个工具名（顺序 = CCB tools.ts 的 buildComputerUseTools 数组顺序）。 */
    private static final List<String> EXPECTED_35 = List.of(
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
            // ── CCB tools.ts:420-836 的 win32 条件块（11 个）──
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
            "mcp__computer-use__mouse_wheel",
            // ── 通用全集继续 ──
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

    private static void setEnabled(ComputerUseToolConfig config, boolean enabled) throws Exception {
        Field field = ComputerUseToolConfig.class.getDeclaredField("computerUseEnabled");
        field.setAccessible(true);
        field.set(config, enabled);
    }

    @Test
    @DisplayName("工具面 = 35 个，名字逐字等于 CCB 全集（顺序也一致）")
    void toolSurfaceIsExactlyCcbSet() {
        assumeTrue(Platform.isWindows(), "computer-use 是 Windows 专属功能");

        ComputerUseToolConfig config = new ComputerUseToolConfig();
        List<Tool> tools;
        try {
            setEnabled(config, true);
            tools = config.computerUseTools(
                    new ComputerUseGeometryStore(),
                    ComputerUsePhase2aFakes.grantStore("sess-1", ComputerUseGrants.empty().toJson()),
                    new ComputerUseLockService(mock(DataSource.class), true),
                    null, null,
                    new ComputerUseMouseState(), new ComputerUseDisplayStore(),
                    new ComputerUseWindowBindingStore());
        } catch (Exception e) {
            throw new IllegalStateException("构建 computer-use 工具面失败: " + e.getMessage(), e);
        }

        assertThat(tools).hasSize(35);
        assertThat(tools.stream().map(Tool::name).toList()).isEqualTo(EXPECTED_35);
    }

    @Test
    @DisplayName("开关 false ⇒ 一个都不注册（模型不可见）")
    void gateOffYieldsNoTools() throws Exception {
        ComputerUseToolConfig config = new ComputerUseToolConfig();
        setEnabled(config, false);
        assertThat(config.computerUseTools(new ComputerUseGeometryStore())).isEmpty();
    }

    @Test
    @DisplayName("每个工具的 schema 都可解析且带 name/description（模型要看得懂怎么调）")
    void everyToolHasUsableSchema() {
        assumeTrue(Platform.isWindows(), "computer-use 是 Windows 专属功能");

        ComputerUseToolConfig config = new ComputerUseToolConfig();
        List<Tool> tools;
        try {
            setEnabled(config, true);
            tools = config.computerUseTools(
                    new ComputerUseGeometryStore(),
                    ComputerUsePhase2aFakes.grantStore("sess-1", ComputerUseGrants.empty().toJson()),
                    new ComputerUseLockService(mock(DataSource.class), true),
                    null, null,
                    new ComputerUseMouseState(), new ComputerUseDisplayStore(),
                    new ComputerUseWindowBindingStore());
        } catch (Exception e) {
            throw new IllegalStateException("构建 computer-use 工具面失败: " + e.getMessage(), e);
        }

        for (Tool tool : tools) {
            assertThat(tool.name()).startsWith("mcp__computer-use__");
            assertThat(tool.description()).as("%s 描述不能为空", tool.name()).isNotBlank();
            assertThat(tool.inputSchema()).as("%s schema 不能为空", tool.name()).isNotNull();
            assertThat(tool.inputSchema().path("type").asText()).isEqualTo("object");
        }
    }
}
