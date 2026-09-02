package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.repository.oauth_account.mapper.AccountOAuthTokenMapper;
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
 * R32-b7a-1 · 全局 feature flag 矩阵验证 (5 个 conditional tool 组合测试).
 *
 * <p><b>WHY (意图验证)</b>: 5 个 conditional tool 各有不同的 feature flag,
 * 必须验证:
 * <ul>
 *   <li>每个 flag 独立控制自己的 tool 群 (CronCreate/Delete/List 共用 cron flag,
 *       SleepTool 用 proactive flag, RemoteTriggerTool 用 agent-trigger-remote flag)</li>
 *   <li>flag 互不干扰 (开启 cron 不会误启 SleepTool)</li>
 *   <li>全开场景验证 (5 个 bean 同时创建)</li>
 *   <li>关停场景验证 (显式 false 都应隐藏; cron 默认开, 需显式 false 才隐藏)</li>
 * </ul>
 *
 * <p>此测试是其他 3 个 conditional 测试的"综合矩阵",
 * 防止 flag 命名笔误 / 注解参数错位导致互相影响.
 */
class R32B7a1_ConditionalOnPropertyMatrixTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MatrixTestConfig.class);

    @Test
    @DisplayName("全 flag 开启 → 5 个 bean 都创建")
    void allFlagsOnCreatesAllBeans() {
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-cron=true",
                        "nexusai.feature.proactive=true",
                        "nexusai.feature.agent-trigger-remote=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CronCreateTool.class);
                    assertThat(ctx).hasSingleBean(CronDeleteTool.class);
                    assertThat(ctx).hasSingleBean(CronListTool.class);
                    assertThat(ctx).hasSingleBean(SleepTool.class);
                    assertThat(ctx).hasSingleBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("仅 cron=true → 仅 Cron 3 个 bean 创建, Sleep/Remote 不创建")
    void cronOnlyEnablesOnlyCron() {
        runner.withPropertyValues("nexusai.feature.agent-trigger-cron=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CronCreateTool.class);
                    assertThat(ctx).hasSingleBean(CronDeleteTool.class);
                    assertThat(ctx).hasSingleBean(CronListTool.class);
                    assertThat(ctx).doesNotHaveBean(SleepTool.class);
                    assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("仅 proactive=true (agent-trigger-cron 显式 false) → 仅 SleepTool 创建")
    void proactiveOnlyEnablesOnlySleep() {
        // WHY: agent-trigger-cron 默认开 (matchIfMissing=true) → 本场景须显式 false 关掉 Cron,
        // 否则 default-open 语义下 Cron bean 会存在 (对齐 CC 默认开)
        runner.withPropertyValues(
                        "nexusai.feature.proactive=true",
                        "nexusai.feature.agent-trigger-cron=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SleepTool.class);
                    assertThat(ctx).doesNotHaveBean(CronCreateTool.class);
                    assertThat(ctx).doesNotHaveBean(CronDeleteTool.class);
                    assertThat(ctx).doesNotHaveBean(CronListTool.class);
                    assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("仅 agent-trigger-remote=true (agent-trigger-cron 显式 false) → 仅 RemoteTriggerTool 创建")
    void remoteOnlyEnablesOnlyRemote() {
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-remote=true",
                        "nexusai.feature.agent-trigger-cron=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RemoteTriggerTool.class);
                    assertThat(ctx).doesNotHaveBean(CronCreateTool.class);
                    assertThat(ctx).doesNotHaveBean(CronDeleteTool.class);
                    assertThat(ctx).doesNotHaveBean(CronListTool.class);
                    assertThat(ctx).doesNotHaveBean(SleepTool.class);
                });
    }

    @Test
    @DisplayName("显式 false 不应启用 (各 flag 独立验证)")
    void explicitFalseDoesNotEnable() {
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-cron=false",
                        "nexusai.feature.proactive=false",
                        "nexusai.feature.agent-trigger-remote=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CronCreateTool.class);
                    assertThat(ctx).doesNotHaveBean(CronDeleteTool.class);
                    assertThat(ctx).doesNotHaveBean(CronListTool.class);
                    assertThat(ctx).doesNotHaveBean(SleepTool.class);
                    assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("非空字符串 'enabled' / 'yes' / '1' 不应启用 (非严格 'true')")
    void nonStrictTrueDoesNotEnable() {
        // WHY: Spring ConditionalOnProperty havingValue="true" 是严格值匹配,
        // 仅字面量 "true" 启用 (大小写不敏感); "enabled" / "yes" / "1" 都不启用.
        // 锁定该行为防止未来 Spring 升级放宽 havingValue 匹配规则 (误启).
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-cron=enabled",
                        "nexusai.feature.proactive=yes",
                        "nexusai.feature.agent-trigger-remote=1")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CronCreateTool.class);
                    assertThat(ctx).doesNotHaveBean(SleepTool.class);
                    assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("cron + remote 开启, proactive 关闭 → Cron + Remote 创建, Sleep 不创建")
    void cronAndRemoteEnabledSleepDisabled() {
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-cron=true",
                        "nexusai.feature.agent-trigger-remote=true",
                        "nexusai.feature.proactive=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CronCreateTool.class);
                    assertThat(ctx).hasSingleBean(CronDeleteTool.class);
                    assertThat(ctx).hasSingleBean(CronListTool.class);
                    assertThat(ctx).hasSingleBean(RemoteTriggerTool.class);
                    assertThat(ctx).doesNotHaveBean(SleepTool.class);
                });
    }

    /**
     * 测试配置: 显式注册 5 个 conditional Tool + 提供 mock ScheduleService 满足依赖.
     *
     * <p>WHY: 避开 {@code @ComponentScan} (会触发整个应用扫描), 加速上下文启动.
     * CronJitterProperties 用 {@link CronJitterProperties#DEFAULTS} (对齐 CC
     * DEFAULT_CRON_JITTER_CONFIG 缺省语义), AccountOAuthTokenService 用 Mockito mock
     * 测试不触发 execute, mock 仅满足装配). AccountOAuthTokenService 自身 @Autowired
     * AccountOAuthTokenMapper (AccountOAuthTokenService.java:34), 须同步补 mapper stub.
     */
    @Configuration
    @Import({CronCreateTool.class, CronDeleteTool.class, CronListTool.class,
             SleepTool.class, RemoteTriggerTool.class})
    static class MatrixTestConfig {
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
        AccountOAuthTokenService accountOAuthTokenService() {
            // RemoteTriggerTool @Autowired 构造器依赖 (RemoteTriggerTool.java:117-122);
            // conditional 测试不触发 execute → readLatest 不被调用, mock 仅满足装配
            return Mockito.mock(AccountOAuthTokenService.class);
        }

        @Bean
        AccountOAuthTokenMapper accountOAuthTokenMapper() {
            // AccountOAuthTokenService @Autowired AccountOAuthTokenMapper 字段注入
            // (AccountOAuthTokenService.java:34, required=true) —— mock 实例经 Spring 后置
            // 注入仍会扫描继承字段注解, 须同步补 mapper stub (同
            // R32B7a1_RemoteTriggerToolConditionalTest.java:38-39 既有模式)
            return Mockito.mock(AccountOAuthTokenMapper.class);
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
            ScheduleService svc = new ScheduleService();
            ReflectionTestUtils.setField(svc, "scheduleMapper", scheduleMapper);
            ReflectionTestUtils.setField(svc, "quartzScheduleService", quartzScheduleService);
            return svc;
        }
    }
}