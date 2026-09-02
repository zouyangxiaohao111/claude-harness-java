package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PowerShellTool 注册期门控测试 · 对齐 CC tools.ts:150-156 {@code getPowerShellTool()}
 * 注册期条件 {@code isPowerShellToolEnabled()}（shellToolUtils.ts:17-22）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 决策 #65 加注册期门控 —— 非 Windows /
 * 未启用时不注册 PowerShellTool（对齐 CC getPowerShellTool() 返回 null → 不进 getAllBaseTools）。
 * CC 真源（shellToolUtils.ts:17-22，grep 自验，不信注释）：
 * <pre>
 * export function isPowerShellToolEnabled(): boolean {
 *   if (getPlatform() !== 'windows') return false
 *   return process.env.USER_TYPE === 'ant'
 *     ? !isEnvDefinedFalsy(process.env.CLAUDE_CODE_USE_POWERSHELL_TOOL)
 *     : isEnvTruthy(process.env.CLAUDE_CODE_USE_POWERSHELL_TOOL)
 * }
 * </pre>
 * 本测试用 {@code ApplicationContextRunner} + 可注入 {@code os.name / USER_TYPE /
 * CLAUDE_CODE_USE_POWERSHELL_TOOL} 的 probe 配置，锁定注册层门控四因子组合：
 * <ol>
 *   <li><b>平台因子</b>：非 Windows（os.name 无 "win"）→ 不注册，不看 env/USER_TYPE。</li>
 *   <li><b>USER_TYPE=ant</b>：Windows + ant + env 未显式 falsy → 注册（ant 默认开）。</li>
 *   <li><b>外部 opt-in</b>：Windows + 非 ant + env truthy（1/true/yes/on）→ 注册。</li>
 *   <li><b>外部默认关</b>：Windows + 非 ant + env 缺省/falsy → 不注册（CC 外部默认关）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 删除 {@code @Conditional(PowerShellToolRegistrationCondition.class)} 或
 * 改 isPowerShellToolEnabled 语义（如只认 "true" 丢 1/yes/on，或漏 USER_TYPE ant 分支）→ 本测试 fail。
 *
 * @see PowerShellToolRegistrationCondition
 */
class PowerShellToolRegistrationConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PsProbeConfig.class);

    @Test
    @DisplayName("非 Windows 平台 → 不注册（CC shellToolUtils.ts:18 getPlatform()!=='windows'）")
    void nonWindows_notRegistered() {
        runner.withSystemProperties("os.name=Linux")
            .run(ctx -> assertThat(ctx)
                .as("os.name=Linux 必须不注册（平台因子短路，CC shellToolUtils.ts:18）")
                .doesNotHaveBean(PsProbeMarker.class));
    }

    @Test
    @DisplayName("Windows + USER_TYPE=ant + env 未设 → 注册（ant 默认开，CC shellToolUtils.ts:19-20）")
    void windowsAnt_defaultOn() {
        runner.withSystemProperties("os.name=Windows 11", "USER_TYPE=ant")
            .run(ctx -> assertThat(ctx)
                .as("Windows+ant+env 未设必须注册（ant 默认开 = !isEnvDefinedFalsy(null) → true）")
                .hasSingleBean(PsProbeMarker.class));
    }

    @Test
    @DisplayName("Windows + USER_TYPE=ant + env=0 → 不注册（ant opt-out，isEnvDefinedFalsy('0') → false 分支）")
    void windowsAnt_explicitFalsy_notRegistered() {
        runner.withSystemProperties("os.name=Windows 11", "USER_TYPE=ant",
                "CLAUDE_CODE_USE_POWERSHELL_TOOL=0")
            .run(ctx -> assertThat(ctx)
                .as("Windows+ant+env=0 必须不注册（ant opt-out，CC shellToolUtils.ts:20 !isEnvDefinedFalsy('0') → false）")
                .doesNotHaveBean(PsProbeMarker.class));
    }

    @Test
    @DisplayName("Windows + 外部 + env ∈ {1,true,yes,on} → 注册（外部 opt-in，isEnvTruthy 四值）")
    void windowsExternal_optIn() {
        for (String truthy : new String[]{"1", "true", "yes", "on"}) {
            runner.withSystemProperties("os.name=Windows 11",
                    "CLAUDE_CODE_USE_POWERSHELL_TOOL=" + truthy)
                .run(ctx -> assertThat(ctx)
                    .as("Windows+外部+env=" + truthy + " 必须注册（外部 opt-in，CC shellToolUtils.ts:21 isEnvTruthy）")
                    .hasSingleBean(PsProbeMarker.class));
        }
    }

    @Test
    @DisplayName("Windows + 外部 + env 缺省/falsy → 不注册（外部默认关）")
    void windowsExternal_defaultOff() {
        // 缺省
        runner.withSystemProperties("os.name=Windows 11")
            .run(ctx -> assertThat(ctx)
                .as("Windows+外部+env 缺省必须不注册（外部默认关，CC shellToolUtils.ts:21 isEnvTruthy(null)=false）")
                .doesNotHaveBean(PsProbeMarker.class));
        // 显式 falsy（0/false/no/off）
        for (String falsy : new String[]{"0", "false", "no", "off"}) {
            runner.withSystemProperties("os.name=Windows 11",
                    "CLAUDE_CODE_USE_POWERSHELL_TOOL=" + falsy)
                .run(ctx -> assertThat(ctx)
                    .as("Windows+外部+env=" + falsy + " 必须不注册（isEnvTruthy 非四值）")
                    .doesNotHaveBean(PsProbeMarker.class));
        }
    }

    @Test
    @DisplayName("isEnvTruthy/isEnvDefinedFalsy 委托共享实现（TaskSystemConfig，规则七复用）语义锚定")
    void truthyFalsySharedSemantics() {
        // Condition 已委托共享 TaskSystemConfig.isEnvTruthy/isEnvDefinedFalsy，此处锚定共享实现语义
        // （对齐 CC envUtils.ts:32-37 isEnvTruthy {1,true,yes,on} / :39-47 isEnvDefinedFalsy {0,false,no,off}）。
        assertThat(TaskSystemConfig.isEnvTruthy("1")).isTrue();
        assertThat(TaskSystemConfig.isEnvTruthy("yes")).isTrue();
        assertThat(TaskSystemConfig.isEnvDefinedFalsy("0")).isTrue();
        assertThat(TaskSystemConfig.isEnvDefinedFalsy("off")).isTrue();
        assertThat(TaskSystemConfig.isEnvTruthy(null)).isFalse();
        assertThat(TaskSystemConfig.isEnvDefinedFalsy(null)).isFalse();
    }

    /** 代表性 probe 配置：验证 @Conditional(PowerShellToolRegistrationCondition.class) 机制本身。 */
    @Configuration(proxyBeanMethods = false)
    static class PsProbeConfig {
        @Bean
        @Conditional(PowerShellToolRegistrationCondition.class)
        PsProbeMarker psProbeBean() {
            return new PsProbeMarker();
        }
    }

    /** 门控 probe 的 bean 标记类型（避免与内建 bean 类型冲突）。 */
    static final class PsProbeMarker {
    }
}
