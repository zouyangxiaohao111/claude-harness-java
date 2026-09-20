package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 A4b] {@link PermissionContextBuilder} 的 <b>Spring 构造器解析</b>守护测试。
 *
 * <h2>为什么必须新建这个测试</h2>
 * <p>缺陷（已实证 + 已修）：{@code PermissionContextBuilder} 是 {@code @Component}，且有<b>两个</b>
 * public 构造器 —— 无参 {@link PermissionContextBuilder#PermissionContextBuilder()}（{@code loaders =
 * emptyList()}）与 1 参 {@link PermissionContextBuilder#PermissionContextBuilder(List)}。1 参构造器
 * <b>原本没有 {@code @Autowired}</b> ⇒ Spring 的
 * {@code AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors} 在「多候选且无一标注」
 * 时返回 null ⇒ 容器回落<b>无参构造器</b> ⇒ {@code loaderCount() == 0} ⇒
 * <b>user / project / local / policy(企业管控) settings 的 {@code permissions.allow/deny/ask} 全部不加载</b>
 * （用户手写的 deny 规则静默失效 —— 安全相关）。
 *
 * <h2>既有测试为什么守不住</h2>
 * <p>{@code DangerousStripRestoreTest:66-69} 用 {@code @Bean PermissionContextBuilder
 * permissionContextBuilder() { return new PermissionContextBuilder(List.of(new StubLoader())); }}
 * —— <b>{@code @Bean} 工厂方法返回的是已经 new 好的对象，容器从不做构造器选择</b>，故对该缺陷
 * 结构上完全无感（这正是它静默三个批次的原因之一）。本测试改以 <b>bean class 方式注册</b>
 * （{@code @Import(PermissionContextBuilder.class)}）：容器从 class 建 bean definition 并<b>自己选构造器</b>，
 * 与 {@code @ComponentScan} 走同一条 {@code determineCandidateConstructors} 路径。
 *
 * <h2>反向断言（强制反向实验）</h2>
 * <p>移除 1 参构造器上的 {@code @Autowired} ⇒ 本测试的两个方法<b>必须变红</b>
 * （{@code loaderCount()==0}、deny 规则不落地）。已实测，见批 A4b 报告。
 */
class PermissionContextBuilderSpringWiringTest {

    private static final String SESSION_ID = "sess-" + UUID.randomUUID().toString().substring(0, 8);
    private static final UUID AGENT_ID = UUID.randomUUID();

    /** 两条 stub loader 规则（USER_SOURCE allow + PROJECT_SOURCE deny）。 */
    private static final PermissionRule USER_ALLOW = new PermissionRule(
        PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW,
        new PermissionRuleValue("Bash", "npm run test:*"));
    private static final PermissionRule PROJECT_DENY = new PermissionRule(
        PermissionRuleSource.PROJECT_SETTINGS, PermissionBehavior.DENY,
        new PermissionRuleValue("Bash", "rm -rf *"));

    /**
     * ⭐ 关键：{@code PermissionContextBuilder} 以 <b>bean class</b> 注册（{@code @Import} 而非
     * {@code @Bean} 工厂方法）—— 只有这样才能走到容器的构造器解析。
     */
    @Configuration
    @Import(PermissionContextBuilder.class)
    static class WiringConfig {

        @Bean
        PermissionSourceLoader userSettingsLoader() {
            return new StubLoader(PermissionRuleSource.USER_SETTINGS, List.of(USER_ALLOW));
        }

        @Bean
        PermissionSourceLoader projectSettingsLoader() {
            return new StubLoader(PermissionRuleSource.PROJECT_SETTINGS, List.of(PROJECT_DENY));
        }
    }

    /** 真 loader 桩（非 mock）：source 固定、load() 返回固定规则集。 */
    record StubLoader(PermissionRuleSource source, List<PermissionRule> rules)
        implements PermissionSourceLoader {
        @Override
        public List<PermissionRule> load() {
            return rules;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WiringConfig.class);

    @Test
    @DisplayName("A4b 守护: Spring 必须以 1 参构造器装配全部 PermissionSourceLoader bean（loaderCount==2）")
    void springResolvesAutowiredOneArgConstructor() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            PermissionContextBuilder builder = ctx.getBean(PermissionContextBuilder.class);
            assertThat(builder.loaderCount())
                .as("容器必须以 @Autowired 的 1 参构造器注入 2 个 loader bean；"
                    + "==0 即回归为「回落无参构造器」缺陷（权限规则全不加载，含 deny 静默失效）")
                .isEqualTo(2);
            assertThat(builder.loaders())
                .extracting(PermissionSourceLoader::source)
                .containsExactlyInAnyOrder(
                    PermissionRuleSource.USER_SETTINGS, PermissionRuleSource.PROJECT_SETTINGS);
        });
    }

    @Test
    @DisplayName("A4b 守护: 容器装配的 builder 真的把 loader 规则装进 ToolPermissionContext（deny 生效）")
    void containerWiredBuilderActuallyLoadsRules() {
        runner.run(ctx -> {
            PermissionContextBuilder builder = ctx.getBean(PermissionContextBuilder.class);
            AgentState state = new AgentState("system", SESSION_ID, AGENT_ID);

            ToolPermissionContext permCtx = builder.buildPermissionContext(
                state, false, PermissionMode.DEFAULT, false, false);

            assertThat(flatten(permCtx, PermissionBehavior.DENY))
                .as("user settings 之外的 deny 规则必须被加载 —— 这是「用户 permissions.deny 不生效」"
                    + "这一安全缺陷的端到端守卫")
                .contains(PROJECT_DENY);
            assertThat(flatten(permCtx, PermissionBehavior.ALLOW))
                .as("allow 规则同样必须被加载")
                .contains(USER_ALLOW);
        });
    }

    /** 把 3 桶之一的 Map&lt;Source, Set&lt;Rule&gt;&gt; 展平成 Set 便于断言。 */
    private static Set<PermissionRule> flatten(ToolPermissionContext ctx, PermissionBehavior behavior) {
        var bucket = switch (behavior) {
            case ALLOW -> ctx.alwaysAllowRules();
            case DENY -> ctx.alwaysDenyRules();
            case ASK -> ctx.alwaysAskRules();
        };
        return bucket.values().stream().flatMap(Set::stream)
            .collect(java.util.stream.Collectors.toSet());
    }
}
