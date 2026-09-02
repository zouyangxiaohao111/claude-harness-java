package com.nexusai.application.agent.tool.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-A5 · {@link CronEnabledGates} 门控单测.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC prompt.ts 双门 ——
 * <ul>
 *   <li>isKairosCronEnabled (:36-45) = feature('AGENT_TRIGGERS')（默认 true，G15 生产编译 true）
 *       AND !CLAUDE_CODE_DISABLE_CRON truthy AND GB 'tengu_kairos_cron' 默认 true；</li>
 *   <li>isDurableCronEnabled (:56-62) = GB 'tengu_kairos_cron_durable' 默认 true，
 *       不 consult AGENT_TRIGGERS / CLAUDE_CODE_DISABLE_CRON。</li>
 * </ul>
 *
 * <p><b>默认开</b>: DEFAULTS 无参构造 = (true, true) —— 防止未来误改构造器默认导致
 * cron 工具默认禁用（探查 R-1 静默失效回归保护）。
 */
class CronEnabledGatesTest {

    @Test
    @DisplayName("默认开: agent-trigger-cron=true + cron-durable=true → 双门都开")
    void defaultsAreOpen() {
        CronEnabledGates gates = CronEnabledGates.DEFAULTS;
        assertThat(gates.agentTriggerCron())
            .as("CC feature('AGENT_TRIGGERS') 生产编译 true (cli.js G15)")
            .isTrue();
        assertThat(gates.cronDurable())
            .as("CC GB 'tengu_kairos_cron_durable' 默认 true (prompt.ts:56-62)")
            .isTrue();
        assertThat(gates.isKairosCronEnabled())
            .as("isKairosCronEnabled 默认 true (prompt.ts:36-45)")
            .isTrue();
        assertThat(gates.isDurableCronEnabled())
            .as("isDurableCronEnabled 默认 true (prompt.ts:56-62)")
            .isTrue();
    }

    @Test
    @DisplayName("agent-trigger-cron=false → isKairosCronEnabled 关闭, isDurableCronEnabled 不受影响")
    void kairosGateClosesWhenAgentTriggerCronOff() {
        // CC prompt.ts:37 feature('AGENT_TRIGGERS')=false → isKairosCronEnabled()=false
        CronEnabledGates gates = new CronEnabledGates(false, true);
        assertThat(gates.isKairosCronEnabled()).isFalse();
        // isDurableCronEnabled 不 consult AGENT_TRIGGERS (prompt.ts:56-62) — 独立门
        assertThat(gates.isDurableCronEnabled()).isTrue();
    }

    @Test
    @DisplayName("cron-durable=false → isDurableCronEnabled 关闭, isKairosCronEnabled 不受影响")
    void durableGateClosesIndependently() {
        // CC: durable kill-switch 比 isKairosCronEnabled 更窄 (prompt.ts:47-54),
        // 只强制 durable:false, 不关整个 scheduler
        CronEnabledGates gates = new CronEnabledGates(true, false);
        assertThat(gates.isDurableCronEnabled()).isFalse();
        assertThat(gates.isKairosCronEnabled()).isTrue();
    }

    @Test
    @DisplayName("全关 → 双门都关 (部署 kill-switch)")
    void bothClosedWhenBothOff() {
        CronEnabledGates gates = new CronEnabledGates(false, false);
        assertThat(gates.isKairosCronEnabled()).isFalse();
        assertThat(gates.isDurableCronEnabled()).isFalse();
    }

    @Test
    @DisplayName("Spring Binder: yml nexusai.feature.agent-trigger-cron/cron-durable 绑定 (kebab→camel)")
    void springBinderBindsYmlProperties() {
        // WHY: 生产默认开依赖 @ConfigurationProperties(prefix="nexusai.feature") 把 yml 的
        // agent-trigger-cron / cron-durable 绑定到 record 组件; 若 relaxed binding 失效,
        // 生产 yml:179-180 默认 true 不会生效, isEnabled 退化为 false (工具全隐藏).
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.feature.agent-trigger-cron", "true");
        env.setProperty("nexusai.feature.cron-durable", "true");
        CronEnabledGates gates = Binder.get(env)
                .bind("nexusai.feature", Bindable.of(CronEnabledGates.class))
                .orElse(CronEnabledGates.DEFAULTS);
        assertThat(gates.agentTriggerCron()).as("yml:179 agent-trigger-cron → agentTriggerCron").isTrue();
        assertThat(gates.cronDurable()).as("yml:180 cron-durable → cronDurable").isTrue();
        assertThat(gates.isKairosCronEnabled()).isTrue();
        assertThat(gates.isDurableCronEnabled()).isTrue();
    }

    @Test
    @DisplayName("Spring Binder: 属性缺省 → @DefaultValue(\"true\") 保证默认开")
    void springBinderDefaultsOpenWhenPropertyAbsent() {
        // WHY: @DefaultValue("true") 让用户删 yml 项时仍默认开 (与三工具 matchIfMissing=true
        // 默认注册语义一致), 防止删配置后静默降级为关.
        MockEnvironment env = new MockEnvironment();
        CronEnabledGates gates = Binder.get(env)
                .bind("nexusai.feature", Bindable.of(CronEnabledGates.class))
                .orElse(CronEnabledGates.DEFAULTS);
        assertThat(gates.agentTriggerCron()).as("缺省 → @DefaultValue true").isTrue();
        assertThat(gates.cronDurable()).as("缺省 → @DefaultValue true").isTrue();
        assertThat(gates.isKairosCronEnabled()).isTrue();
        assertThat(gates.isDurableCronEnabled()).isTrue();
    }
}
