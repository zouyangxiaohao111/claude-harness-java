package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.quartz.Scheduler;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-A5 · CronCreateTool / CronDeleteTool / CronListTool
 * {@code @ConditionalOnProperty(nexusai.feature.agent-trigger-cron)} 守卫验证（默认开反转版）.
 *
 * <p><b>WHY (意图验证)</b>: 3 个 Cron 工具共用同一 feature flag (对齐 CC
 * {@code AGENT_TRIGGERS} 整体启停语义). CC 生产 bundle 将 AGENT_TRIGGERS 编译为 true
 * （cli.js G15）+ GB 'tengu_kairos_cron' 默认 true → <b>默认开启</b>。测试验证:
 * <ul>
 *   <li>默认 (无配置, matchIfMissing=true) → 3 个 bean 都创建 (默认开)</li>
 *   <li>{@code nexusai.feature.agent-trigger-cron=true} → 3 个 bean 都创建</li>
 *   <li>{@code nexusai.feature.agent-trigger-cron=false} → 3 个 bean 都不创建</li>
 *   <li>一个 flag 控制 3 个 Tool (避免一半启用一半禁用的尴尬状态)</li>
 *   <li>回归保护: 默认必须创建 bean（防止未来误改 matchIfMissing=false 导致默认关）</li>
 * </ul>
 *
 * <p><b>WHY 用 ApplicationContextRunner 而非 @SpringBootTest</b>:
 * ApplicationContextRunner 是 Spring Boot Test 专为 conditional bean 测试设计,
 * 不启动整个应用上下文 (避免 Quartz / DB / WebSocket 等耗时依赖).
 *
 * <p><b>ScheduleService / CronEnabledGates / CronJitterProperties 依赖处理</b>: CronCreateTool/
 * CronDeleteTool/CronListTool 注入 ScheduleService (具体类) + CronEnabledGates
 * (@ConfigurationProperties record) + CronJitterProperties (ScheduleService @Autowired
 * required=true 字段, ScheduleService.java:93), 通过 {@code @Configuration} 提供 bean
 * 满足 Spring 装配. mock 行为不参与测试, 仅满足依赖注入.
 *
 * @see CronCreateTool
 * @see CronDeleteTool
 * @see CronListTool
 * @see CronEnabledGates
 */
class R32B7a1_CronToolsConditionalTest {

    /**
     * ScheduleService 是具体类, 注入会触发完整 Spring 装配 (DB + Quartz),
     * 单元测试不需 ScheduleService 的实际行为, 只验证 conditional 守卫.
     * 用 {@code withUserConfiguration} 显式注册 3 个 Cron Tool 类 (避开 @ComponentScan),
     * 并提供 mock ScheduleService bean 满足依赖.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("默认无配置 → 3 个 Cron Tool bean 都创建 (默认开)")
    void cronToolsPresentByDefault() {
        // WHY: matchIfMissing=true → 默认注册; 对齐 CC 生产 AGENT_TRIGGERS 编译 true +
        // GB 'tengu_kairos_cron' 默认 true (prompt.ts:36-45). 默认关会让 cron 工具在
        // 未显式配置的部署中全部静默失效 (探查 R-1).
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CronCreateTool.class);
            assertThat(ctx).hasSingleBean(CronDeleteTool.class);
            assertThat(ctx).hasSingleBean(CronListTool.class);
        });
    }

    @Test
    @DisplayName("agent-trigger-cron=true → 3 个 Cron Tool bean 都创建")
    void cronToolsPresentWhenEnabled() {
        runner.withPropertyValues("nexusai.feature.agent-trigger-cron=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CronCreateTool.class);
                    assertThat(ctx).hasSingleBean(CronDeleteTool.class);
                    assertThat(ctx).hasSingleBean(CronListTool.class);
                });
    }

    @Test
    @DisplayName("agent-trigger-cron=false → 3 个 Cron Tool bean 都不创建")
    void cronToolsAbsentWhenExplicitlyDisabled() {
        // WHY: 显式 false 应关闭 bean 注册 (kill-switch 语义, 对齐 CC 部署关停)
        runner.withPropertyValues("nexusai.feature.agent-trigger-cron=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CronCreateTool.class);
                    assertThat(ctx).doesNotHaveBean(CronDeleteTool.class);
                    assertThat(ctx).doesNotHaveBean(CronListTool.class);
                });
    }

    @Test
    @DisplayName("agent-trigger-cron=true → 3 个 bean 都创建 (single source of truth)")
    void singleFlagControlsAllThree() {
        // WHY: CronCreate/Delete/List 必须共用同一 flag (CC AGENT_TRIGGERS 语义),
        // 不能出现"create 启用但 delete 禁用"的不一致状态
        runner.withPropertyValues("nexusai.feature.agent-trigger-cron=true")
                .run(ctx -> {
                    // 用 getBeanNamesForType 而非 bean name (bean name 在 @Import 场景下可能与默认命名不同)
                    assertThat(ctx.getBeanNamesForType(CronCreateTool.class))
                            .as("CronCreateTool bean present").isNotEmpty();
                    assertThat(ctx.getBeanNamesForType(CronDeleteTool.class))
                            .as("CronDeleteTool bean present").isNotEmpty();
                    assertThat(ctx.getBeanNamesForType(CronListTool.class))
                            .as("CronListTool bean present").isNotEmpty();
                    assertThat(ctx.getBean(CronCreateTool.class)).isNotNull();
                    assertThat(ctx.getBean(CronDeleteTool.class)).isNotNull();
                    assertThat(ctx.getBean(CronListTool.class)).isNotNull();
                });
    }

    @Test
    @DisplayName("默认配置下 Cron 工具必须被注册 (回归保护, 防误改 matchIfMissing=false)")
    void cronToolsRegisteredByDefault() {
        // WHY: 防止未来误改 @ConditionalOnProperty 为 matchIfMissing=false 导致
        // cron 工具默认禁用, 偏离 CC 生产默认开启语义 (AGENT_TRIGGERS G15 true)
        runner.run(ctx -> {
            assertThat(ctx.getBeanNamesForType(CronCreateTool.class))
                    .as("CronCreateTool 默认注册 (对齐 CC AGENT_TRIGGERS 生产 true)")
                    .isNotEmpty();
            assertThat(ctx.getBeanNamesForType(CronDeleteTool.class)).isNotEmpty();
            assertThat(ctx.getBeanNamesForType(CronListTool.class)).isNotEmpty();
        });
    }

    /**
     * 测试配置: 显式注册 3 个 Cron Tool + 提供 mock ScheduleService 满足依赖.
     *
     * <p>WHY: 避开 {@code @ComponentScan} (会触发整个应用扫描), 加速上下文启动.
     * mock ScheduleService 仅满足 Spring 装配, 不参与 cron conditional 验证.
     * CronEnabledGates 用 {@link CronEnabledGates#DEFAULTS} (默认 true, 对齐 yml 默认开).
     */
    @Configuration
    @Import({CronCreateTool.class, CronDeleteTool.class, CronListTool.class})
    static class TestConfig {
        @Bean
        ScheduleMapper scheduleMapper() {
            return Mockito.mock(ScheduleMapper.class);
        }

        @Bean
        Scheduler scheduler() {
            return Mockito.mock(Scheduler.class);
        }

        @Bean
        CronEnabledGates cronEnabledGates() {
            return CronEnabledGates.DEFAULTS;
        }

        @Bean
        CronJitterProperties cronJitterProperties() {
            // 对齐 CC DEFAULT_CRON_JITTER_CONFIG 缺省语义 (cronJitterConfig.ts:67-74:
            // GB 无值或 safeParse 失败 → DEFAULT_CRON_JITTER_CONFIG); 测试上下文无
            // nexusai.cron.jitter.* 配置, 注入 DEFAULTS 即等价缺省装配 (CronJitterProperties.java:86)
            return CronJitterProperties.DEFAULTS;
        }

        @Bean
        QuartzScheduleService quartzScheduleService(Scheduler scheduler) {
            QuartzScheduleService mock = Mockito.mock(QuartzScheduleService.class);
            ReflectionTestUtils.setField(mock, "scheduler", scheduler);
            return mock;
        }

        @Bean
        ScheduleService scheduleService(ScheduleMapper scheduleMapper,
                                        QuartzScheduleService quartzScheduleService) {
            // 构造真实 ScheduleService 实例 + 注入 mock deps; 这样 Spring 的
            // @Autowired 处理不会触发, 因为字段已经被构造函数注入
            ScheduleService svc = new ScheduleService();
            ReflectionTestUtils.setField(svc, "scheduleMapper", scheduleMapper);
            ReflectionTestUtils.setField(svc, "quartzScheduleService", quartzScheduleService);
            return svc;
        }
    }
}
