package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [fix-toolcall-fault B · 返工 R1] **入口级**状态保真测试 · 真实走 {@code executeStreaming}/{@code execute}。
 *
 * <p><b>WHY（规则九 · 验证意图 · 审查员 Blocker 1）</b>：修复曾只落在静态缝上 ——
 * {@code executeStreaming} 的唯一 return 点把 {@code loopResult.status()=="failed"} 塌缩回
 * {@code completed}（原 {@code wasAborted ? aborted : completed}）⇒ SubagentTool 的 failed 分支
 * （{@code finalizeFailed} → task 落 FAILED）与 SDK bookend 的 failed 分支**在生产不可达**，
 * 测试全绿但功能未交付。本用例从**生产入口**（真实 22 步前段 + 真实 queryLoop 调用 + 真实 Step 22
 * 收尾 + 真实 return）断言 status==failed —— 去掉 {@code :2872} 的状态保真分支 → 本用例红。
 *
 * <p><b>零产出 throw 家族的构造</b>：{@code agentToolUtils.ts:297-300}（连一条 assistant 消息都没有
 * → throw）→ catch → {@code failAsyncAgent}（:671）在 Java 的等价位 = queryLoop 抛异常且
 * {@code state.rawMessages()} 无 assistant 文本。此处用 mock 的 {@link AgentLoopContextFactory}
 * （{@code shared()} 抛异常）精确构造该情形 —— 与生产「依赖/装配异常穿出循环」同路径，
 * 不依赖任何 Spring 容器。
 *
 * <p>最小装配实证：{@code new ToolRegistry()} + mock contextFactory 即可走通前段（空依赖由既有
 * null-safe 路径承载）；其余字段走构造器缺省（providerConfig=null ⇒ LlmProviderFactory 落
 * MockLlmProvider，见 SubagentExecutorProviderResolutionTest 记载）。
 */
@DisplayName("[fix-toolcall-fault B·R1] 入口级 status 保真（executeStreaming/execute 真返回）")
class SubagentExecutorEntryStatusTest {

    /** 最小入口装配（与 @Bean 形态同构：providerConfig=null）。 */
    private static SubagentExecutor entryExecutor(AgentLoopContextFactory factory) {
        SubagentExecutor exec = new SubagentExecutor(
            new ToolRegistry(), null, null, null, null, "gpt-4", null);
        exec.setContextFactory(factory);
        return exec;
    }

    private static AgentLoopContextFactory throwingFactory(String message) {
        AgentLoopContextFactory factory = mock(AgentLoopContextFactory.class);
        when(factory.shared(any())).thenThrow(new IllegalStateException(message));
        return factory;
    }

    @Test
    @DisplayName("R1-1 executeStreaming 零产出异常 → 返回 status=failed（旧实现塌缩成 completed）")
    void executeStreaming_zeroOutputThrow_returnsFailedStatus() {
        SubagentExecutor.SubagentResult result = entryExecutor(throwingFactory("dependency boom"))
            .executeStreaming("ping", "general-purpose", null, null, null);

        assertThat(result.status())
            .as("生产入口返回的 status 必须是 failed（R1：:2872 状态保真；CC :297-300 throw → :671 failAsyncAgent）")
            .isEqualTo("failed");
        assertThat(result.summaryText())
            .as("结论 = 异常原文（不再交付占位 'Subagent completed without final answer.'）")
            .isEqualTo("dependency boom");
    }

    @Test
    @DisplayName("R1-2 execute（非流式包装）同走 failed —— 保真链路在两条入口都成立")
    void execute_nonStreamingWrapper_preservesFailedStatus() {
        SubagentExecutor.SubagentResult result = entryExecutor(throwingFactory("wrapper boom"))
            .execute("ping", "general-purpose", null, null);

        assertThat(result.status())
            .as("execute 委托 executeStreaming，status 同样不得被塌缩")
            .isEqualTo("failed");
        assertThat(result.summaryText()).isEqualTo("wrapper boom");
    }
}
