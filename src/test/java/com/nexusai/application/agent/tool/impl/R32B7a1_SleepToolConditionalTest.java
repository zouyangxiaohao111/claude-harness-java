package com.nexusai.application.agent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-1 · SleepTool {@code @ConditionalOnProperty(nexusai.feature.proactive)} 守卫验证.
 *
 * <p><b>WHY (意图验证)</b>: SleepTool 用于 Agent 主动暂停等待异步事件 (对齐 CC
 * {@code SleepTool.ts} + PROACTIVE/KAIROS feature flag). 测试验证:
 * <ul>
 *   <li>默认 (无配置) → bean 不创建 (普通 Agent 不应能 sleep)</li>
 *   <li>{@code nexusai.feature.proactive=true} → bean 创建 (opt-in)</li>
 *   <li>{@code nexusai.feature.proactive=false} → bean 不创建</li>
 *   <li>property 值大小写敏感 (Spring ConditionalOnProperty 默认)</li>
 * </ul>
 *
 * @see SleepTool
 */
class R32B7a1_SleepToolConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SleepTool.class);

    @Test
    @DisplayName("默认无配置 → SleepTool bean 不创建")
    void sleepToolAbsentByDefault() {
        // WHY: 普通 Agent 不应能 sleep (防止 LLM 用 sleep 死循环);
        // SleepTool 仅在 proactive / KAIROS 场景启用 (CC 同模式)
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(SleepTool.class);
        });
    }

    @Test
    @DisplayName("proactive=true → SleepTool bean 创建")
    void sleepToolPresentWhenEnabled() {
        runner.withPropertyValues("nexusai.feature.proactive=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SleepTool.class);
                });
    }

    @Test
    @DisplayName("proactive=false → SleepTool bean 不创建")
    void sleepToolAbsentWhenExplicitlyDisabled() {
        // WHY: 显式 false 与未设置等效 (Spring ConditionalOnProperty havingValue 语义)
        runner.withPropertyValues("nexusai.feature.proactive=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(SleepTool.class);
                });
    }

    @Test
    @DisplayName("proactive=true (单实例) → 单例 bean 验证")
    void sleepToolIsSingleton() {
        // WHY: SleepTool 应是单例 (@Component 默认 scope=singleton),
        // 防止多实例导致 session 状态分散
        runner.withPropertyValues("nexusai.feature.proactive=true")
                .run(ctx -> {
                    SleepTool a = ctx.getBean(SleepTool.class);
                    SleepTool b = ctx.getBean(SleepTool.class);
                    assertThat(a).isSameAs(b);
                });
    }

    @Test
    @DisplayName("proactive 大小写不敏感 (Spring ConditionalOnProperty equalsIgnoreCase 行为)")
    void sleepToolPropertyCaseInsensitive() {
        // WHY: Spring ConditionalOnProperty 使用 equalsIgnoreCase 比较 havingValue;
        // "True" / "TRUE" / "true" 均启用 (避免 .env 大小写差异导致意外禁用)
        for (String truthy : new String[]{"True", "TRUE", "true", "tRuE"}) {
            runner.withPropertyValues("nexusai.feature.proactive=" + truthy)
                    .run(ctx -> assertThat(ctx)
                            .as("Spring case-insensitive match: proactive=" + truthy + " → bean enabled")
                            .hasSingleBean(SleepTool.class));
        }
    }
}