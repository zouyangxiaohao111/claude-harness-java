package com.nexusai.application.agent.tool;

import com.nexusai.NexusAiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENABLE_LSP_TOOL 注册门控 · 真实全上下文运行时闭环（RV-D-03 NG-1）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: RV-D-03 NG-1 缺口 = Java 无
 * ENABLE_LSP_TOOL 注册门控等价物，LspTool 无条件 {@code @Bean} 注册，仅靠
 * {@link com.nexusai.application.agent.tool.impl.LspTool#isEnabled()} 运行时兜底。
 * CC 是<b>两层门控</b>：注册层（tools.ts:224 {@code isEnvTruthy(process.env.ENABLE_LSP_TOOL)
 * ? [LSPTool] : []}）+ 运行时层（LSPTool.ts:137-139 {@code isEnabled() = isLspConnected()}）。
 *
 * <p>本测试用全量 {@code @SpringBootTest(classes = NexusAiApplication.class)} 加载<b>真实生产
 * 上下文</b>（62 个 {@code ToolRegistrationConfig} @Bean + 真实 {@code LspManager} + 真实
 * {@code ToolRegistry}），断言 <b>注册层</b>门控在两条方向都真实生效：
 * <ul>
 *   <li><b>默认态</b>：ENABLE_LSP_TOOL 未设 → {@code ToolRegistry.has("LSP")} == false
 *       （等价 CC env 未设 → LSPTool 不进 getAllBaseTools）。</li>
 *   <li><b>truthy 态</b>：ENABLE_LSP_TOOL=1 → {@code ToolRegistry.has("LSP")} == true
 *       （注册层门控通过；运行时是否可调用仍由 isEnabled=LspManager.isLspConnected 决定）。</li>
 * </ul>
 *
 * <p><b>为何用 {@link ToolRegistry#has(String)}（原始注册表）而非 {@link ToolRegistry#all()}
 * （isEnabled 过滤）</b>：{@code all()} 按 {@code isEnabled()} 过滤（R32-#11，对齐 CC
 * tools.ts:181 getToolsForDefaultPreset），而 LspTool.isEnabled() = LspManager.isLspConnected()
 * 在无 LSP server 时恒 false —— 用 {@code all()} 断言会<b>恒通过</b>（无论注册门控是否生效），
 * 无法区分「未注册」与「已注册但运行时禁用」。{@code has()} 直接查 {@code tools} 原始
 * 注册表（{@code register()} 以 {@code tool.name()} 为 key），精确反映注册层门控，与
 * isEnabled 运行时层正交。这是「生产可达」的闭环证据：若删除 {@code @Conditional}，
 * 默认态断言立即变红（LspTool 无条件注册 → has("LSP")==true）。
 *
 * <p><b>确定性</b>：每个 {@code @Nested} 类用 {@code properties} 显式固定 ENABLE_LSP_TOOL
 * （inline test property 优先级高于 OS env / -D sysprop），避免 CI/IDE 预设 ENABLE_LSP_TOOL
 * 导致「默认态」断言漂移。
 *
 * <p>CC 原名/行号: {@code ENABLE_LSP_TOOL}（Open-ClaudeCode/src/tools.ts:224）;
 * {@code isEnvTruthy}（Open-ClaudeCode/src/utils/envUtils.ts:32-37）;
 * {@code isEnabled()}（Open-ClaudeCode/src/tools/LSPTool/LSPTool.ts:137-139）。
 */
class LspToolRegistrationGatingTest {

    /** 默认态：ENABLE_LSP_TOOL 未设（显式空，确定性）→ LspTool 不进生产 registry。 */
    @Nested
    @SpringBootTest(classes = NexusAiApplication.class, properties = "ENABLE_LSP_TOOL=")
    class DefaultGating {

        @Autowired
        private ToolRegistry registry;

        @Test
        @DisplayName("ENABLE_LSP_TOOL 未设（默认）→ ToolRegistry 未注册 LSP（对齐 CC tools.ts:224 env 未设 → LSPTool 不进 getAllBaseTools）")
        void lspToolNotRegisteredByDefault() {
            assertThat(registry.has("LSP"))
                .as("ENABLE_LSP_TOOL 未设 → EnableLspToolCondition.matches()==false → lspTool() @Bean 不创建 → "
                    + "LspTool 不进 @Lazy List<Tool> → 不进 ToolRegistry（对齐 CC tools.ts:224）。"
                    + "若此处为 true，说明 @Conditional 注册门控断裂（RV-D-03 NG-1 未修复）。")
                .isFalse();
        }
    }

    /** truthy 态：ENABLE_LSP_TOOL=1 → LspTool 进生产 registry（注册层门控通过）。 */
    @Nested
    @SpringBootTest(classes = NexusAiApplication.class, properties = "ENABLE_LSP_TOOL=1")
    class TruthyGating {

        @Autowired
        private ToolRegistry registry;

        @Test
        @DisplayName("ENABLE_LSP_TOOL=1 → ToolRegistry 注册 LSP（注册层门控通过；运行时 isEnabled 仍由 LspManager.isLspConnected 决定）")
        void lspToolRegisteredWhenTruthy() {
            assertThat(registry.has("LSP"))
                .as("ENABLE_LSP_TOOL=1 → EnableLspToolCondition.matches()==true → lspTool() @Bean 创建 → "
                    + "LspTool 进 @Lazy List<Tool> → 进 ToolRegistry（对齐 CC tools.ts:224 truthy 分支）。"
                    + "若此处为 false，说明 @Conditional 未接线或 isEnvTruthy 语义错误（漏 1/yes/on）。")
                .isTrue();
        }
    }
}
