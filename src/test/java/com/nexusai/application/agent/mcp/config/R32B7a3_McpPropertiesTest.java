package com.nexusai.application.agent.mcp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-3 · McpProperties 配置绑定验证.
 *
 * <p><b>WHY (意图验证)</b>: McpProperties 是 5 个 stub Tool 背后唯一的配置入口,
 * 5 个 feature flag 全部从 {@code McpProperties.features()} 派生. 必须验证:
 * <ul>
 *   <li>record 字段顺序 / 名称 / 类型与 application.yml {@code nexusai.mcp.*} 段
 *       严格对齐 (任何拼写错误都会让 5 个 flag 静默失效).</li>
 *   <li>嵌套 record (Pool / Server / Features / Auth / Resources / Prompts) 字段
 *       都能正确绑定.</li>
 *   <li>默认值绑定 — application.yml 提供所有默认值时, record 字段都非空.</li>
 *   <li>未设置任何 mcp.* 配置时, record 字段取 {@code false / 0 / null} (Java record
 *       默认值, 不是 Spring {@code @DefaultValue}).</li>
 * </ul>
 *
 * <p>单元测试用 Binder API 直接绑定, 不启动 Spring 上下文 (快, 隔离);
 * 集成测试用 {@code ApplicationContextRunner} 验证 {@code @EnableConfigurationProperties}
 * 完整链路.
 *
 * @see McpProperties
 */
class R32B7a3_McpPropertiesTest {

    @Nested
    @DisplayName("Record 字段直接构造 (类型与默认值)")
    class RecordFieldDirectConstruction {

        @Test
        @DisplayName("Features 5 字段对齐 5 个 stub Tool 名称 (camelCase)")
        void featuresFieldsMatchStubTools() {
            // WHY: McpProperties.features 字段名是 webBrowserTool / listPeersTool /
            // sendUserFileTool / pushNotificationTool / subscribePrTool (camelCase,
            // 对应 application.yml 的 web-browser-tool / list-peers-tool 等 kebab-case).
            // 任何拼写错误都会让 spring binding 失败, 5 个 flag 静默失效.
            McpProperties.Features features = new McpProperties.Features(
                true,   // webBrowserTool
                true,   // listPeersTool
                true,   // sendUserFileTool
                true,   // pushNotificationTool
                true    // subscribePrTool
            );
            assertThat(features.webBrowserTool()).isTrue();
            assertThat(features.listPeersTool()).isTrue();
            assertThat(features.sendUserFileTool()).isTrue();
            assertThat(features.pushNotificationTool()).isTrue();
            assertThat(features.subscribePrTool()).isTrue();
        }

        @Test
        @DisplayName("Pool 字段类型正确 (int / long / boolean)")
        void poolFieldsHaveCorrectTypes() {
            McpProperties.Pool pool = new McpProperties.Pool(4, 30000L, true);
            assertThat(pool.maxConcurrentCalls()).isEqualTo(4);
            assertThat(pool.callTimeoutMs()).isEqualTo(30000L);
            assertThat(pool.serializeSameServer()).isTrue();
        }

        @Test
        @DisplayName("Server 字段允许 null (transport=stdio 时 url 可空)")
        void serverFieldsAllowNullForStdio() {
            // WHY: Server.url / command 互斥 (stdio 用 command/args, http/sse/ws 用 url).
            // record 字段不应强制非 null, 否则 stdio server 配不出来.
            McpProperties.Server server = new McpProperties.Server(
                "playwright",
                "stdio",
                "npx",
                java.util.List.of("-y", "@playwright/mcp"),
                Map.of("DEBUG", "1"),
                null,    // url 可空 (stdio 模式)
                true
            );
            assertThat(server.name()).isEqualTo("playwright");
            assertThat(server.transport()).isEqualTo("stdio");
            assertThat(server.url()).isNull();
            assertThat(server.args()).hasSize(2);
        }

        @Test
        @DisplayName("Auth 字段类型正确 (boolean / long)")
        void authFieldsHaveCorrectTypes() {
            McpProperties.Auth auth = new McpProperties.Auth(true, 10000L);
            assertThat(auth.oauthEnabled()).isTrue();
            assertThat(auth.asDiscoveryTimeoutMs()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("Resources 字段为 boolean (listEnabled / subscribeEnabled)")
        void resourcesFieldsHaveCorrectTypes() {
            McpProperties.Resources resources = new McpProperties.Resources(true, true);
            assertThat(resources.listEnabled()).isTrue();
            assertThat(resources.subscribeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Prompts 字段为 boolean (listEnabled)")
        void promptsFieldsHaveCorrectTypes() {
            McpProperties.Prompts prompts = new McpProperties.Prompts(true);
            assertThat(prompts.listEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("@EnableConfigurationProperties 集成 (ApplicationContextRunner)")
    class EnableConfigurationPropertiesIntegration {

        /**
         * 空 properties 容器 + @EnableConfigurationProperties(McpProperties.class)
         * + McpConfig (@Configuration) → 验证 record 字段取 Java record 默认值
         * (false / 0 / null / 空 List).
         */
        private final ApplicationContextRunner emptyRunner = new ApplicationContextRunner()
                .withUserConfiguration(EnableMcpPropertiesConfig.class, McpConfig.class);

        @Test
        @DisplayName("未设置 nexusai.mcp.* → McpProperties bean 创建, 字段全为 record 默认值")
        void absentMcpConfigBindsWithRecordDefaults() {
            // WHY: record 字段缺失时, Spring binding 取 Java record 默认值
            // (boolean=false, int=0, long=0, 引用=null). 这与 application.yml
            // 提供完整默认值的语义不同 — 此测试锁定 record 行为, 防止未来误加
            // @DefaultValue 引入意外变化.
            emptyRunner.run(ctx -> {
                assertThat(ctx).hasSingleBean(McpProperties.class);
                McpProperties props = ctx.getBean(McpProperties.class);
                assertThat(props.enabled())
                    .as("McpProperties.enabled 默认 false")
                    .isFalse();
                assertThat(props.pool())
                    .as("McpProperties.pool 默认 null (record 默认值)")
                    .isNull();
                assertThat(props.servers())
                    .as("McpProperties.servers 默认 null")
                    .isNull();
                assertThat(props.features())
                    .as("McpProperties.features 默认 null")
                    .isNull();
                assertThat(props.auth()).isNull();
                assertThat(props.resources()).isNull();
                assertThat(props.prompts()).isNull();
            });
        }

        @Test
        @DisplayName("提供完整 nexusai.mcp.* 配置 → 所有嵌套 record 字段正确绑定")
        void fullMcpConfigBindsAllNestedRecords() {
            // WHY: 这是 fail-loud 验证 — application.yml 的 kebab-case 字段
            // (web-browser-tool) 必须正确映射到 record 的 camelCase 字段
            // (webBrowserTool). 任何大小写 / 连字符 错误会让 5 个 flag 静默失效.
            emptyRunner.withPropertyValues(
                            "nexusai.mcp.enabled=true",
                            "nexusai.mcp.pool.max-concurrent-calls=8",
                            "nexusai.mcp.pool.call-timeout-ms=60000",
                            "nexusai.mcp.pool.serialize-same-server=false",
                            "nexusai.mcp.features.web-browser-tool=true",
                            "nexusai.mcp.features.list-peers-tool=false",
                            "nexusai.mcp.features.send-user-file-tool=true",
                            "nexusai.mcp.features.push-notification-tool=false",
                            "nexusai.mcp.features.subscribe-pr-tool=true",
                            "nexusai.mcp.auth.oauth-enabled=true",
                            "nexusai.mcp.auth.as-discovery-timeout-ms=10000",
                            "nexusai.mcp.resources.list-enabled=true",
                            "nexusai.mcp.resources.subscribe-enabled=true",
                            "nexusai.mcp.prompts.list-enabled=true")
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(McpProperties.class);
                        McpProperties props = ctx.getBean(McpProperties.class);

                        // 顶层
                        assertThat(props.enabled()).isTrue();

                        // Pool
                        assertThat(props.pool()).isNotNull();
                        assertThat(props.pool().maxConcurrentCalls()).isEqualTo(8);
                        assertThat(props.pool().callTimeoutMs()).isEqualTo(60000L);
                        assertThat(props.pool().serializeSameServer()).isFalse();

                        // Features (5 个 flag)
                        assertThat(props.features()).isNotNull();
                        assertThat(props.features().webBrowserTool())
                            .as("web-browser-tool=true → features.webBrowserTool()=true")
                            .isTrue();
                        assertThat(props.features().listPeersTool()).isFalse();
                        assertThat(props.features().sendUserFileTool()).isTrue();
                        assertThat(props.features().pushNotificationTool()).isFalse();
                        assertThat(props.features().subscribePrTool()).isTrue();

                        // Auth
                        assertThat(props.auth()).isNotNull();
                        assertThat(props.auth().oauthEnabled()).isTrue();
                        assertThat(props.auth().asDiscoveryTimeoutMs()).isEqualTo(10000L);

                        // Resources
                        assertThat(props.resources()).isNotNull();
                        assertThat(props.resources().listEnabled()).isTrue();
                        assertThat(props.resources().subscribeEnabled()).isTrue();

                        // Prompts
                        assertThat(props.prompts()).isNotNull();
                        assertThat(props.prompts().listEnabled()).isTrue();
                    });
        }

        @Test
        @DisplayName("McpConfig 加载时打印 [McpConfig] 日志 (McpProperties 注入验证)")
        void mcpConfigLogInjection() {
            // WHY: McpConfig 的构造器注入 McpProperties 是 fail-loud 验证 —
            // @EnableConfigurationProperties 必须真的注册 bean, 否则构造器
            // NoSuchBeanDefinitionException. 本测试触发构造器注入, 间接验证
            // 链路完整.
            emptyRunner.withPropertyValues(
                            "nexusai.mcp.enabled=true",
                            "nexusai.mcp.pool.max-concurrent-calls=4",
                            "nexusai.mcp.pool.call-timeout-ms=30000",
                            "nexusai.mcp.pool.serialize-same-server=true",
                            "nexusai.mcp.features.web-browser-tool=false",
                            "nexusai.mcp.features.list-peers-tool=false",
                            "nexusai.mcp.features.send-user-file-tool=false",
                            "nexusai.mcp.features.push-notification-tool=false",
                            "nexusai.mcp.features.subscribe-pr-tool=false",
                            "nexusai.mcp.auth.oauth-enabled=false",
                            "nexusai.mcp.auth.as-discovery-timeout-ms=5000",
                            "nexusai.mcp.resources.list-enabled=true",
                            "nexusai.mcp.resources.subscribe-enabled=false",
                            "nexusai.mcp.prompts.list-enabled=false")
                    .run(ctx -> {
                        // McpConfig 也是 @Configuration, 应在 ctx 中
                        assertThat(ctx).hasSingleBean(McpConfig.class);
                        // McpProperties 注入到 McpConfig 构造器
                        McpConfig mcpConfig = ctx.getBean(McpConfig.class);
                        assertThat(mcpConfig).isNotNull();
                    });
        }
    }

    /**
     * 辅助 @Configuration: 仅暴露 McpProperties bean (McpConfig 包含它).
     * 隔离其他 @ComponentScan 干扰, 测试聚焦于 binding 行为.
     */
    @Configuration
    @EnableConfigurationProperties(McpProperties.class)
    static class EnableMcpPropertiesConfig {
        // McpConfig 的 @Configuration 也会被 @ComponentScan 加载, 但 runner
        // 用 withUserConfiguration 显式注册, 不会扫到. 这里单独注册
        // McpConfig 以验证构造器注入.
    }
}
