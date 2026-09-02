package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENABLE_LSP_TOOL 注册门控测试 · 对齐 CC tools.ts:224
 * {@code ...(isEnvTruthy(process.env.ENABLE_LSP_TOOL) ? [LSPTool] : [])} + envUtils.ts:32-37
 * isEnvTruthy（四值 truthy {1,true,yes,on}）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: RV-D-03 NG-1 缺口 = Java 无 ENABLE_LSP_TOOL
 * 注册门控等价物，LspTool 无条件 {@code @Bean} 注册，仅靠 {@link LspTool#isEnabled()} 运行时兜底。
 * CC 是<b>两层门控</b>：注册层（tools.ts:224 env truthy 才进 getAllBaseTools）+ 运行时层
 * （LSPTool.ts:137-139 isLspConnected）。本测试验证：
 * <ol>
 *   <li><b>isEnvTruthy 语义逐字对齐</b> — {1,true,yes,on}（含大小写/空白）→ true；null/空/0/false/no/off → false。
 *       （Condition 已委托共享 {@link TaskSystemConfig#isEnvTruthy}，此处断言该共享实现。）</li>
 *   <li><b>@Conditional 门控机制 probe</b>（辅助证据） — ENABLE_LSP_TOOL 开 → bean 存在；关/缺省 → bean 不存在。</li>
 *   <li><b>lspTool() @Bean 真接线 source-assertion</b>（辅助证据） — 断言 {@code @Conditional(EnableLspToolCondition.class)}。</li>
 * </ol>
 *
 * <p><b>证据分层（修正版）</b>：② probe（marker bean）+ ③ source-assertion 仅作「机制 + 接线唯一性」辅助，
 * <b>不</b>再充当「生产可达」唯一兜底。真实生产上下文闭环证据在
 * {@code com.nexusai.application.agent.tool.LspToolRegistrationGatingTest}（@SpringBootTest 全量
 * 生产上下文 + {@code ToolRegistry.has("LSP")} 原始注册表断言）。
 *
 * <p><b>RED teeth</b>: 删除 {@code @Conditional} 或改 isEnvTruthy 语义（如只认 "true"）→ 本测试 fail。
 */
class EnableLspToolConditionTest {

    // ──────────────── ① isEnvTruthy 语义单测（对齐 CC envUtils.ts:32-37） ────────────────
    // Condition 已委托共享 TaskSystemConfig.isEnvTruthy（public static，规则七复用），
    // 此处直接断言该共享实现，确保其语义逐字对齐 CC 四值 {1,true,yes,on}。

    @Test
    @DisplayName("isEnvTruthy: 4 truthy 值（1/true/yes/on，含大小写/空白）→ true")
    void isEnvTruthyFourTruthyValues() {
        String[] truthy = {"1", "true", "yes", "on", " 1 ", "TRUE", "Yes", "ON", "TrUe", "\tyes\n"};
        for (String v : truthy) {
            assertThat(TaskSystemConfig.isEnvTruthy(v))
                .as("CC envUtils.ts:32-37 isEnvTruthy 认 ['1','true','yes','on']（trim+lowercase），值='" + v + "' 必须 true")
                .isTrue();
        }
    }

    @Test
    @DisplayName("isEnvTruthy: 5 falsy 值（null/空/0/false/no/off）→ false")
    void isEnvTruthyFalsyValues() {
        String[] falsy = {null, "", "   ", "0", "false", "no", "off", "random", "2"};
        for (String v : falsy) {
            assertThat(TaskSystemConfig.isEnvTruthy(v))
                .as("isEnvTruthy 非 {1,true,yes,on} 必须 false（值='" + v + "'）")
                .isFalse();
        }
    }

    // ──────────────── ② @Conditional 生产可达门控（ApplicationContextRunner probe） ────────────────

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LspProbeConfig.class);

    @Test
    @DisplayName("ENABLE_LSP_TOOL 未设（默认）→ 门控 bean 不存在（对齐 CC env 未设 → LSPTool 不进 getAllBaseTools）")
    void probeAbsentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LspProbeMarker.class));
    }

    @Test
    @DisplayName("ENABLE_LSP_TOOL ∈ {1,true,yes,on} → 门控 bean 存在（注册层门控通过）")
    void probePresentWhenTruthy() {
        for (String truthy : new String[]{"1", "true", "yes", "on"}) {
            runner.withPropertyValues("ENABLE_LSP_TOOL=" + truthy)
                .run(ctx -> assertThat(ctx)
                    .as("ENABLE_LSP_TOOL=" + truthy + " 必须启用（isEnvTruthy 四值）")
                    .hasSingleBean(LspProbeMarker.class));
        }
    }

    @Test
    @DisplayName("ENABLE_LSP_TOOL ∈ {0,false,no,off} → 门控 bean 不存在（非 truthy 不注册）")
    void probeAbsentWhenFalsy() {
        for (String falsy : new String[]{"0", "false", "no", "off"}) {
            runner.withPropertyValues("ENABLE_LSP_TOOL=" + falsy)
                .run(ctx -> assertThat(ctx)
                    .as("ENABLE_LSP_TOOL=" + falsy + " 必须禁用（非 isEnvTruthy 四值）")
                    .doesNotHaveBean(LspProbeMarker.class));
        }
    }

    // ──────────────── ③ lspTool() @Bean 真接线 source-assertion ────────────────

    @Test
    @DisplayName("ToolRegistrationConfig.lspTool() @Bean 携带 @Conditional(EnableLspToolCondition.class)（RV-D-03 NG-1 接线）")
    void lspToolBeanCarriesConditional() throws Exception {
        // 归一化 CRLF→LF，避免 Windows 行尾导致 contains 误判（与源码真实换行无关的健壮断言）
        String config = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/config/ToolRegistrationConfig.java"))
            .replace("\r\n", "\n");
        assertThat(config)
            .as("lspTool() @Bean 必须带 @Conditional(EnableLspToolCondition.class) 实现 ENABLE_LSP_TOOL 注册门控（CC tools.ts:224）")
            .contains("@Conditional(EnableLspToolCondition.class)\n    public Tool lspTool()");
    }

    /** 代表性 probe 配置：验证 @Conditional(EnableLspToolCondition.class) 机制本身（非全 ToolRegistrationConfig 重上下文）。 */
    @Configuration(proxyBeanMethods = false)
    static class LspProbeConfig {
        @Bean
        @Conditional(EnableLspToolCondition.class)
        LspProbeMarker lspProbeBean() {
            return new LspProbeMarker();
        }
    }

    /** 门控 probe 的 bean 标记类型（避免与 String 等内建 bean 类型冲突）。 */
    static final class LspProbeMarker {
    }
}
