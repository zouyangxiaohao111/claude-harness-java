package com.nexusai.application.agent.tool;

import com.nexusai.NexusAiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 (OPD-TOOL-02-1) · 生产注册接线断裂修复验证。
 *
 * <p><b>WHY（意图验证，规则九）</b>: {@link ToolRegistrationConfig#todoTaskTools()} 返回
 * {@code List<Tool>}（bean 类型是 List 而非 Tool），Spring 集合注入
 * {@code @Autowired List<Tool>} 不展开集合 bean 元素（EV-RG-023 E4 实证）→
 * {@link com.nexusai.application.agent.tool.impl.TodoWriteTool} 无独立 @Component/@Bean Tool
 * 通道，仅存在于 todoTaskTools List，若不接线 {@link ToolRegistry#registerAll(List)}，则
 * TodoWrite 永不进入生产 ToolRegistry —— 对齐 CC tools.ts:208 TodoWriteTool 无条件注册（扁平
 * 工具数组，逐个工具实例）的语义被破坏。
 *
 * <p>本测试用全量 {@code @SpringBootTest} 启动生产上下文，断言
 * {@link ToolRegistry#all()} 含 name()=="TodoWrite"，即证明 registerAll 生产接线已生效
 * （而非仅注册到某个测试专用 registry）。
 */
@SpringBootTest(classes = NexusAiApplication.class)
class ToolRegistryProductionRegistrationTest {

    static {
        // 固定 V1：TaskSystemConfig.isTodoV2Enabled() 读 JVM sysprop（非 Spring property）。
        // 默认已 V1（isInteractive 默认 false → isTodoV2Enabled false → TodoWrite.isEnabled true），
        // 显式固定防部署环境 nexusai.tasks.enabled / nexusai.interactive=true 翻 V2 致 TodoWrite 不注册。
        System.setProperty("nexusai.tasks.enabled", "false");
        System.setProperty("nexusai.interactive", "false");
    }

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("生产 ToolRegistry.all() 含 TodoWrite（todoTaskTools @Bean 经 registerAll 接入）")
    void todoWriteReachesProductionRegistry() {
        ToolRegistry registry = ctx.getBean(ToolRegistry.class);

        boolean hasTodoWrite = registry.all().stream()
            .anyMatch(t -> "TodoWrite".equals(t.name()));

        assertThat(hasTodoWrite)
            .as("ToolRegistry.all() 必须含 TodoWrite：todoTaskTools @Bean(List<Tool>) 必须经 "
                + "registerAll 接入生产注册表（对齐 CC tools.ts:208 TodoWriteTool 无条件注册）。"
                + "若此处为 false，说明 registerAll 生产接线断裂（B5 OPD-TOOL-02-1）。")
            .isTrue();
    }
}
