package com.nexusai.application.agent.config;

import com.nexusai.application.agent.settings.SupportedSettings;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.tool.ConfigToolPrompt;
import com.nexusai.application.agent.tool.impl.ConfigToolImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [WF-B4] · ConfigToolAutoConfiguration 条件矩阵验证（单条件）.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC {@code tools.ts:214}
 * {@code ...(process.env.USER_TYPE === 'ant' ? [ConfigTool] : [])} — 仅凭
 * {@code USER_TYPE=ant} 单条件注册，<b>无</b> 额外 opt-in
 * （WF-B4 已删除 Java 端自造的 enable 开关 @ConditionalOnProperty，
 * CC 无此门控，OPD-12 用户已拍板）。
 *
 * <p>条件（单条）：{@code nexusai.user.type = ant}（CC env USER_TYPE=ant）。
 * 验证矩阵:
 * <ul>
 *   <li>user.type=ant → ConfigToolImpl + ConfigToolPrompt 都创建</li>
 *   <li>user.type 缺失 / 非 ant（default/admin 等）→ 不创建</li>
 *   <li>ANT 大写 → 创建（Spring @ConditionalOnProperty 默认 ignoreCase）</li>
 * </ul>
 *
 * <p>用 {@link ApplicationContextRunner} 而非 @SpringBootTest —
 * 避免 Quartz/DB/WebSocket 耗时依赖，加速上下文启动。
 *
 * @see ConfigToolAutoConfiguration
 */
class R32B7a2_ConfigToolAutoConfigurationConditionalTest {

    @TempDir
    java.nio.file.Path tempDir;

    private String originalUserHome;

    /** 决策 D1：nexusai.home 已废弃（不再注入），FileConfigStorage 缺省路径 = user.home 派生。
     *  覆写 user.home 隔离测试写盘（防污染真实 ~/.nexusai），用后恢复。 */
    @BeforeEach
    void isolateUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("user.type=ant → ConfigToolImpl + ConfigToolPrompt 都创建（单条件激活）")
    void antUserCreatesBoth() {
        // WHY: CC tools.ts:214 唯一激活场景 — USER_TYPE=ant。无需额外 opt-in。
        runner.withPropertyValues("nexusai.user.type=ant")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(ConfigToolImpl.class);
                assertThat(ctx).hasSingleBean(ConfigToolPrompt.class);
                // 验证 bean 类型 — 不只是 bean 计数
                ConfigToolImpl tool = ctx.getBean(ConfigToolImpl.class);
                assertThat(tool.name()).isEqualTo("Config");
            });
    }

    @Test
    @DisplayName("user.type 缺失 → ConfigToolImpl 不创建")
    void userTypeMissingDoesNotEnable() {
        // WHY: nexusai.user.type 未设置 → havingValue="ant" 匹配失败 → 不注册。
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ConfigToolImpl.class);
            assertThat(ctx).doesNotHaveBean(ConfigToolPrompt.class);
        });
    }

    @Test
    @DisplayName("user.type 非 'ant' (default/admin/user/空) → ConfigToolImpl 不创建")
    void nonAntUserTypeDoesNotEnable() {
        // WHY: 只有 ant 是 CC env USER_TYPE=ant 的对应；其他值不应误触发。
        for (String type : new String[]{"default", "admin", "user", ""}) {
            runner.withPropertyValues("nexusai.user.type=" + type)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ConfigToolImpl.class);
                    assertThat(ctx).doesNotHaveBean(ConfigToolPrompt.class);
                });
        }
    }

    @Test
    @DisplayName("ANT (大写) → ConfigToolImpl 创建 (Spring Boot 3.x havingValue 默认大小写不敏感)")
    void antUppercaseAlsoEnables() {
        // WHY: Spring Boot 3.x 的 @ConditionalOnProperty 默认 ignoreCase=true
        // （与 Spring Framework 的默认不同）。"ANT" 等同 "ant"。
        runner.withPropertyValues("nexusai.user.type=ANT")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(ConfigToolImpl.class);
            });
    }

    /**
     * 测试配置: 显式注册 {@link ConfigToolAutoConfiguration} + 提供 mock SupportedSettings /
     * FileConfigStorage bean 满足依赖.
     *
     * <p>WHY: 避开 {@code @ComponentScan} (会触发整个应用扫描, 加载 Quartz/DB),
     * 仅验证条件装配. mock SupportedSettings/FileConfigStorage 满足依赖注入.
     */
    @Configuration
    @org.springframework.context.annotation.Import(ConfigToolAutoConfiguration.class)
    static class TestConfig {
        @Bean
        SupportedSettings supportedSettings() {
            // 最小可用 SupportedSettings (无 feature flag 启用 → 只暴露基础 settings)
            java.util.function.BooleanSupplier allFalse = () -> false;
            java.util.function.Supplier<java.util.List<String>> modelOpts =
                () -> java.util.List.of("sonnet", "opus", "haiku");
            java.util.function.Function<String,
                java.util.concurrent.CompletableFuture<SupportedSettings.ValidationResult>> validator =
                model -> java.util.concurrent.CompletableFuture.completedFuture(
                    new SupportedSettings.ValidationResult(true, null));
            java.util.function.Supplier<String> nullStr = () -> null;
            return new SupportedSettings(
                allFalse, allFalse, allFalse, allFalse, allFalse, allFalse, allFalse,
                modelOpts, validator, nullStr,
                java.util.List.of("normal", "vim"),
                java.util.List.of("iterm2", "terminal_bell", "notifications_disabled"),
                java.util.List.of("tmux", "in-process", "auto"),
                java.util.List.of("dark", "light", "dark-daltonized", "light-daltonized"),
                java.util.List.of("dark", "light", "dark-daltonized", "light-daltonized", "system"));
        }

        @Bean
        FileConfigStorage fileConfigStorage() {
            // 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），nexusai.home 已废弃。
            //   null properties → 缺省路径 = user.home 派生（测试经 isolateUserHome 隔离到临时目录）。
            return new FileConfigStorage(null);
        }
    }
}
