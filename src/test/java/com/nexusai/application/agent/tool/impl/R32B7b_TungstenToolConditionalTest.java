package com.nexusai.application.agent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7b · TungstenTool {@code @ConditionalOnProperty(nexusai.user.type=ant)} 守卫验证.
 *
 * <p><b>WHY (意图验证)</b>: TungstenTool 是 ant-only 工具 — 仅在 {@code nexusai.user.type=ant}
 * 时注册到 Spring 容器. 必须验证:
 * <ul>
 *   <li>默认 (无配置) → bean 不创建 (普通用户不应看到 Tungsten).</li>
 *   <li>{@code nexusai.user.type=ant} → bean 创建 (opt-in, ant 部署可见).</li>
 *   <li>{@code nexusai.user.type != ant} → bean 不创建 (e.g. user, admin, 其他).</li>
 *   <li>property 大小写敏感 — Spring {@code havingValue="ant"} 严格匹配.</li>
 * </ul>
 *
 * <p>对比 b7a-1 的 {@code R32B7a1_SleepToolConditionalTest}: SleepTool 用
 * {@code nexusai.feature.proactive}, TungstenTool 用 {@code nexusai.user.type} (用户类型
 * 不是 feature flag). 这是 fail-loud 验证, 防止 ant 部署误启 Tungsten 给普通用户.
 *
 * <p><b>WHY 用 ApplicationContextRunner 而非 @SpringBootTest</b>:
 * ApplicationContextRunner 是 Spring Boot Test 专为 conditional bean 测试设计,
 * 不启动整个应用上下文 (避免 Quartz / DB / WebSocket / Flyway 等耗时依赖).
 *
 * @see TungstenTool
 */
class R32B7b_TungstenToolConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TungstenTool.class);

    @Test
    @DisplayName("默认无配置 → TungstenTool bean 不创建")
    void tungstenToolAbsentByDefault() {
        // WHY: 普通用户不应能调 Tungsten (ant-only 工具); 默认 user.type 不设置 = 普通用户
        runner.run(ctx -> {
            assertThat(ctx)
                .as("TungstenTool 默认 opt-out (普通用户不可见)")
                .doesNotHaveBean(TungstenTool.class);
        });
    }

    @Test
    @DisplayName("nexusai.user.type=ant → TungstenTool bean 创建")
    void tungstenToolPresentWhenAnt() {
        runner.withPropertyValues("nexusai.user.type=ant")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=ant → TungstenTool bean 注册 (ant 用户可见)")
                        .hasSingleBean(TungstenTool.class);
                });
    }

    @Test
    @DisplayName("nexusai.user.type=user → TungstenTool bean 不创建")
    void tungstenToolAbsentForNonAnt() {
        // WHY: 普通 user 类型不应触发 ant-only tool; 锁定 user 字符串不被误启
        runner.withPropertyValues("nexusai.user.type=user")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=user → TungstenTool bean 不注册 (user 不应看到 ant tool)")
                        .doesNotHaveBean(TungstenTool.class);
                });
    }

    @Test
    @DisplayName("nexusai.user.type=admin → TungstenTool bean 不创建")
    void tungstenToolAbsentForAdmin() {
        // WHY: admin 也不是 ant; 防止 admin 类型被误认为 ant (typo 防御)
        runner.withPropertyValues("nexusai.user.type=admin")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=admin → TungstenTool bean 不注册 (admin ≠ ant)")
                        .doesNotHaveBean(TungstenTool.class);
                });
    }

    @Test
    @DisplayName("ant 显式 false (ant=false) → TungstenTool bean 不创建 (严格字面量)")
    void tungstenToolAbsentForStrictFalse() {
        // WHY: Spring ConditionalOnProperty havingValue="ant" 严格匹配字面量 "ant";
        // 其他任何值 (含 false / Ant / ANT / true) 都不注册.
        runner.withPropertyValues("nexusai.user.type=false")
                .run(ctx -> {
                    assertThat(ctx)
                        .as("user.type=false → TungstenTool bean 不注册 (Spring 严格匹配)")
                        .doesNotHaveBean(TungstenTool.class);
                });
    }

    @Test
    @DisplayName("ant 大小写不敏感: Ant / ANT 仍启用 (Spring equalsIgnoreCase 行为)")
    void tungstenToolCaseInsensitive() {
        // WHY: Spring @ConditionalOnProperty havingValue 默认使用 equalsIgnoreCase 比较,
        // "Ant" / "ANT" / "ant" 均启用. 这是 Spring 框架行为, 测试锁定它以防
        // 未来 Spring 升级改为 case-sensitive 时 (极少见) 出现意外行为差异.
        for (String variant : new String[]{"Ant", "ANT", "anT", "ant"}) {
            runner.withPropertyValues("nexusai.user.type=" + variant)
                    .run(ctx -> assertThat(ctx)
                            .as("Spring equalsIgnoreCase match: user.type=" + variant
                                + " → bean enabled")
                            .hasSingleBean(TungstenTool.class));
        }
    }

    @Test
    @DisplayName("ant 注册 → 单例 bean 验证 (TungstenTool 应是单例)")
    void tungstenToolIsSingleton() {
        // WHY: @Component 默认 scope=singleton; 验证 TungstenTool 不被注册多次,
        // 防止多实例导致 session 状态分散或 warn 日志重复输出
        runner.withPropertyValues("nexusai.user.type=ant")
                .run(ctx -> {
                    TungstenTool a = ctx.getBean(TungstenTool.class);
                    TungstenTool b = ctx.getBean(TungstenTool.class);
                    assertThat(a)
                        .as("TungstenTool 应是单例 (@Component default scope)")
                        .isSameAs(b);
                });
    }
}