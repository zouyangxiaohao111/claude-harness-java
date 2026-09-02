package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-06] ?-EX-05 E4 验证 · ExecAgentHook Spring 实例化可达性（02-gap-analysis §4 闭环）。
 *
 * <p><b>WHY（?-EX-05 / EV-EX-014/015）</b>: ExecAgentHook 是 {@code @Component}，7 参构造器含
 * 无注解的 {@code String defaultFastModel} 参数（无 @Value/@Qualifier/工厂）→ Spring 上下文
 * 无法解析 String bean → 上下文启动失败（若被扫描）。同时生产上下文无 {@code ProviderConfig}
 * bean。E4 验证：构造器注解补齐（@Autowired(required=false) + @Value("${nexusai.hook.fastModel:}")，
 * 对齐 HookRegistry:365 同款模式）后，Spring 可解析 String 参数、缺省依赖注入 null、且
 * {@code HookRegistry.setExecAgentHook}（@Autowired(required=false) setter）注入成功。
 *
 * <p><b>RED 证据（注解补齐前）</b>: {@code @Import(ExecAgentHook.class)} 上下文 refresh 抛
 * {@code UnsatisfiedDependencyException}（ProviderConfig + String 无可解析 bean）——E4 判定
 * ?-EX-05 成立 → 补注入方案（本任务范围内）→ 重验 GREEN；生产偏差面（providerConfig null →
 * 配置 agent hook 无法发起 LLM）登记 09 §?-EX-06。
 */
@DisplayName("[IMPL-06] ExecAgentHook Spring 装配可达性（?-EX-05 E4）")
class ExecAgentHookSpringWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AgentHookWiringConfig.class);

    @Import({ExecAgentHook.class, AgentLoopContextFactory.class, ToolRegistry.class, HookRegistry.class})
    static class AgentHookWiringConfig {
        // Spring Boot 生产上下文由 JacksonAutoConfiguration 提供 ObjectMapper（ApplicationContextRunner
        // 无自动装配 → 显式注册，等价生产）。
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
    @Test
    @DisplayName("1. Spring 上下文可构造 ExecAgentHook（String 经 @Value 解析，ProviderConfig/Telemetry 缺省 null）")
    void springContext_constructsExecAgentHook() {
        // WHY (?-EX-05): 7 参构造器 String 参数必须可解析（否则上下文启动失败）。
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ExecAgentHook.class);
            ExecAgentHook bean = ctx.getBean(ExecAgentHook.class);
            assertThat(bean).isNotNull();
        });
    }

    @Test
    @DisplayName("2. nexusai.hook.fastModel 属性 → @Value 注入 defaultFastModel（生产属性通道）")
    void springContext_resolvesFastModelProperty() throws Exception {
        // WHY (?-EX-05): @Value("${nexusai.hook.fastModel:}") 与 HookRegistry.defaultFastModel
        // 同一属性通道（CC getSmallFastModel 等价）；属性未配置 → 空串（同 HookRegistry 缺省）。
        new ApplicationContextRunner()
            .withPropertyValues("nexusai.hook.fastModel=haiku-prod")
            .withUserConfiguration(AgentHookWiringConfig.class)
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                ExecAgentHook bean = ctx.getBean(ExecAgentHook.class);
                assertThat(defaultFastModelOf(bean)).isEqualTo("haiku-prod");
            });
    }

    @Test
    @DisplayName("3. HookRegistry.setExecAgentHook（@Autowired(required=false)）注入成功（装配闭环）")
    void springContext_injectsExecAgentHookIntoHookRegistry() throws Exception {
        // WHY (?-EX-05): 生产装配闭环 —— HookRegistry 经 setter 拿到 ExecAgentHook 后
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HookRegistry registry = ctx.getBean(HookRegistry.class);
            Object executor = execAgentHookFieldOf(registry);
            assertThat(executor)
                .as("HookRegistry.execAgentHook 必须注入（非 null；@Lazy 断路注入为懒代理）")
                .isNotNull()
                .isInstanceOf(ExecAgentHook.class);
        });
    }

    private static String defaultFastModelOf(ExecAgentHook bean) throws Exception {
        Field f = ExecAgentHook.class.getDeclaredField("defaultFastModel");
        f.setAccessible(true);
        return (String) f.get(bean);
    }

    private static Object execAgentHookFieldOf(HookRegistry registry) throws Exception {
        Field f = HookRegistry.class.getDeclaredField("execAgentHook");
        f.setAccessible(true);
        return f.get(registry);
    }
}
