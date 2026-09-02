package com.nexusai.application.agent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7b · SuggestBackgroundPRTool {@code @ConditionalOnProperty(nexusai.user.type=ant)} 守卫验证.
 *
 * <p><b>WHY (意图验证)</b>: SuggestBackgroundPRTool 是 ant-only 工具 — 仅在
 * {@code nexusai.user.type=ant} 时注册到 Spring 容器 (对齐 CC tools.ts:20-23 require 条件
 * {@code USER_TYPE === 'ant'} + tools.ts:216 注册三元 {@code SuggestBackgroundPRTool ? [...] : []};
 * 无独立 feature 门控). 必须验证:
 * <ul>
 *   <li>默认 (无配置) → bean 不创建 (普通用户不应看到 SuggestBackgroundPR).</li>
 *   <li>{@code nexusai.user.type=ant} → bean 创建 (opt-in, ant 部署可见).</li>
 *   <li>{@code nexusai.user.type != ant} → bean 不创建 (e.g. user, admin, 其他).</li>
 *   <li>property 大小写不敏感 — Spring {@code havingValue="ant"} 严格匹配
 *       (Ant / ANT / anT 均启用).</li>
 * </ul>
 *
 * <p>对比 b7a-1 的 {@code R32B7a1_SleepToolConditionalTest}: SleepTool 用
 * {@code nexusai.feature.proactive}, SuggestBackgroundPRTool 用 {@code nexusai.user.type}
 * (用户类型不是 feature flag, 对齐 CC ant 专属工具). 这是 fail-loud 验证,
 * 防止 ant 部署误启 SuggestBackgroundPR 给普通用户.
 *
 * <p><b>WHY 用 ApplicationContextRunner 而非 @SpringBootTest</b>:
 * ApplicationContextRunner 是 Spring Boot Test 专为 conditional bean 测试设计,
 * 不启动整个应用上下文 (避免 Quartz / DB / WebSocket / Flyway 等耗时依赖).
 *
 * @see SuggestBackgroundPRTool
 */
class R32B7b_SuggestBackgroundPRToolConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SuggestBackgroundPRTool.class);

    @Test
    @DisplayName("默认无配置 → SuggestBackgroundPRTool bean 不创建")
    void suggestBackgroundPrToolAbsentByDefault() {
        // WHY: 普通用户不应能调 SuggestBackgroundPR (ant-only 工具); 默认 user.type 不设置 = 普通用户
        runner.run(ctx -> {
            assertThat(ctx)
                .as("SuggestBackgroundPRTool 默认 opt-out (普通用户不可见)")
                .doesNotHaveBean(SuggestBackgroundPRTool.class);
        });
    }

    @Test
    @DisplayName("nexusai.user.type=ant → SuggestBackgroundPRTool bean 创建")
    void suggestBackgroundPrToolPresentWhenAnt() {
        runner.withPropertyValues("nexusai.user.type=ant")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=ant → SuggestBackgroundPRTool bean 注册 (ant 用户可见)")
                        .hasSingleBean(SuggestBackgroundPRTool.class);
                });
    }

    @Test
    @DisplayName("nexusai.user.type=user → SuggestBackgroundPRTool bean 不创建")
    void suggestBackgroundPrToolAbsentForNonAnt() {
        // WHY: 普通 user 类型不应触发 ant-only tool; 锁定 user 字符串不被误启
        runner.withPropertyValues("nexusai.user.type=user")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=user → SuggestBackgroundPRTool bean 不注册 (user 不应看到 ant tool)")
                        .doesNotHaveBean(SuggestBackgroundPRTool.class);
                });
    }

    @Test
    @DisplayName("nexusai.user.type=admin → SuggestBackgroundPRTool bean 不创建")
    void suggestBackgroundPrToolAbsentForAdmin() {
        // WHY: admin 也不是 ant; 防止 admin 类型被误认为 ant (typo 防御)
        runner.withPropertyValues("nexusai.user.type=admin")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=admin → SuggestBackgroundPRTool bean 不注册 (admin ≠ ant)")
                        .doesNotHaveBean(SuggestBackgroundPRTool.class);
                });
    }

    @Test
    @DisplayName("ant 显式 false (ant=false) → SuggestBackgroundPRTool bean 不创建 (严格字面量)")
    void suggestBackgroundPrToolAbsentForStrictFalse() {
        // WHY: Spring ConditionalOnProperty havingValue="ant" 严格匹配字面量 "ant";
        // 其他任何值 (含 false / Ant / ANT / true) 都不注册.
        runner.withPropertyValues("nexusai.user.type=false")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=false → SuggestBackgroundPRTool bean 不注册 (Spring 严格匹配)")
                        .doesNotHaveBean(SuggestBackgroundPRTool.class);
                });
    }

    @Test
    @DisplayName("ant 大小写不敏感: Ant / ANT 仍启用 (Spring equalsIgnoreCase 行为)")
    void suggestBackgroundPrToolCaseInsensitive() {
        // WHY: Spring @ConditionalOnProperty havingValue 默认使用 equalsIgnoreCase 比较,
        // "Ant" / "ANT" / "ant" 均启用. 这是 Spring 框架行为, 测试锁定它以防
        // 未来 Spring 升级改为 case-sensitive 时 (极少见) 出现意外行为差异.
        for (String variant : new String[]{"Ant", "ANT", "anT", "ant"}) {
            runner.withPropertyValues("nexusai.user.type=" + variant)
                    .run(ctx -> assertThat(ctx)
                            .as("Spring equalsIgnoreCase match: user.type=" + variant
                                + " → bean enabled")
                            .hasSingleBean(SuggestBackgroundPRTool.class));
        }
    }

    @Test
    @DisplayName("ant 注册 → 单例 bean 验证 (SuggestBackgroundPRTool 应是单例)")
    void suggestBackgroundPrToolIsSingleton() {
        // WHY: @Component 默认 scope=singleton; 验证 SuggestBackgroundPRTool 不被注册多次,
        // 防止多实例导致 session 状态分散或 warn 日志重复输出
        runner.withPropertyValues("nexusai.user.type=ant")
                .run(ctx -> {
                    SuggestBackgroundPRTool a = ctx.getBean(SuggestBackgroundPRTool.class);
                    SuggestBackgroundPRTool b = ctx.getBean(SuggestBackgroundPRTool.class);
                    assertThat(a)
                        .as("SuggestBackgroundPRTool 应是单例 (@Component default scope)")
                        .isSameAs(b);
                });
    }
}
