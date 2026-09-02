package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.bubble.PermissionBubbleService;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [H9 v3 Gap② + Gap③] ToolPermissionGate @Autowired 构造器真实 Spring 上下文启动测试.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: v2 对抗复验判定 H9 PARTIAL — 缺口② "gate @Autowired
 * 构造器无真实 Spring 上下文启动测试"（只有反射断言构造器存在，从未证明 Spring 能真实实例化）。
 * 缺口③ "coordinator/classifier 依赖 H10 接线（classifierRunner 恒 null）" — gate 默认实例的
 * {@code coordinatorHandler} 内 {@code classifierRunner = (check, updatedInput) -> null}，
 * 只有生产注入真实 {@link CoordinatorPermissionHandler} bean 才能拿到真实 classifierRunner。
 *
 * <p><b>本测试用 {@link ApplicationContextRunner} 真实拉起 Spring 上下文</b>（import 真实
 * {@code @Component} permission 类 + mock 外部基础设施），证明：
 * <ol>
 *   <li>gap②: {@code ctx.getBean(ToolPermissionGate.class)} 成功 — @Autowired 9 参构造器
 *       被 Spring 真实调用（4 个必填 bean: pipeline/prompter/bubbleService/
 *       decisionLogger 全部解析），不再走 createSpringBean 静态工厂 fallback。</li>
 *   <li>gap③: gate bean 注入的 {@code coordinatorHandler} 是真实
 *       {@link CoordinatorPermissionHandler} bean（非默认 null-runner 实例）；classifier
 *       启发式已随 O18 删除（CC 外部构建恒禁用），classifierRunner 恒 null。</li>
 * </ol>
 *
 * @see ToolPermissionGate
 * @see CoordinatorPermissionHandler
 * @since H9 v3 缺口修复
 */
@DisplayName("[H9 v3 Gap②③] ToolPermissionGate Spring 上下文启动 + 真实 handler 接线")
class ToolPermissionGateSpringContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GateSpringConfig.class)
            .withPropertyValues("nexusai.feature.bash-classifier=true");

    @Test
    @DisplayName("gate bean 经 @Autowired 构造器在 Spring 上下文真实实例化 (Gap②)")
    void gateBeanInstantiatedBySpringAutowiredConstructor() {
        // WHY: gap② — 此前只有反射断言 gate 存在 @Autowired 构造器，无真实 Spring 上下文启动测试。
        //   Spring 实例化 gate 需要 4 个必填 bean (pipeline/prompter/bubbleService/
        //   decisionLogger)。若任一解析失败 → NoSuchBeanDefinitionException
        //   → 本测试红。GREEN 证明 @Autowired 构造器被 Spring 真实调用（不再走 createSpringBean fallback）。
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ToolPermissionGate.class))
                .as("gate bean 必须被 Spring 上下文创建 (不再走 createSpringBean 静态工厂)")
                .isNotNull();
        });
    }

    @Test
    @DisplayName("gate.coordinatorHandler 是真实 bean (非默认 null-runner 实例, Gap③)")
    void gateCoordinatorHandlerIsRealBean() throws Exception {
        // WHY: gap③ — gate 默认实例 (coordinatorHandler==null 时) 的 classifierRunner 恒返回 null，
        //   coordinator 分支的 classifier 步生产不可达。生产注入真实 CoordinatorPermissionHandler bean
        //   后，gate 字段必须指向该 bean (非默认实例)。
        runner.run(ctx -> {
            CoordinatorPermissionHandler realBean = ctx.getBean(CoordinatorPermissionHandler.class);
            ToolPermissionGate gate = ctx.getBean(ToolPermissionGate.class);
            Object injected = readField(gate, "coordinatorHandler");
            assertThat(injected)
                .as("gate.coordinatorHandler 必须注入真实 bean (非默认 null-runner 实例)")
                .isSameAs(realBean);
        });
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static Object readField(Object target, String fieldName) throws Exception {
        Field f = ToolPermissionGate.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    @Configuration
    @Import({
        ToolPermissionGate.class,
        PermissionPipeline.class,
        PermissionMessageGenerator.class,
        WebSocketPermissionPrompter.class,
        PermissionBubbleService.class,
        CoordinatorPermissionHandler.class,
        SwarmWorkerPermissionHandler.class,
        InteractiveHandler.class,
        PermissionDecisionLogger.class,
        BashClassifierFeature.class,
        SafeToolWhitelist.class,
        DenialTracker.class,
        AutoModeGate.class
    })
    static class GateSpringConfig {
        /** WebSocketPermissionPrompter 的 @Autowired 必填依赖. */
        @Bean
        SimpMessagingTemplate wsTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }
}
