package com.nexusai.application.agent;

import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G24-bare · LlmAgentLoop.run() LSP 初始化 bare 门控（装配级）。
 *
 * <p>验证意图（规则九）：CC {@code services/lsp/manager.ts:145-150} {@code initializeLspServerManager()}
 * 入口 {@code if (isBareMode()) return} —— --bare / SIMPLE 无 LSP（编辑器集成诊断/hover 对脚本 -p
 * 调用无用，envUtils.ts:50 isBareMode ~30 gates 之一）。Java Web 端无 simple mode 概念 → 会话级
 * 判定（bareMode 随会话走，V33 列）：bare 会话启动不得触发 {@code LspManager.initialize()}（LSP
 * server 保持未初始化 → isLspConnected 恒 false → LspTool 恒禁用 + 文件 didChange/didSave 恒 no-op）。
 *
 * <p>变异点：删除 run() 的 bare 门控 → bare 会话仍调 lspManager.initialize() → verify never 红。
 */
@DisplayName("[G24-bare] LlmAgentLoop.run LSP 初始化 bare 门控（对齐 CC manager.ts:148）")
class LlmAgentLoopLspBareGateTest {

    /** mocked provider：首调 stream 纯文本 stop → loop 正常退出（同 PluginSessionStartHookWiringTest 装配）。 */
    private static LlmAgentLoop loopWithLsp(LspManager lsp) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setLspManager(lsp);
        return loop;
    }

    @Test
    @DisplayName("bare 会话启动不触发 LspManager.initialize()（CC manager.ts:148 isBareMode 早返）")
    void run_bareMode_neverInitializesLsp() {
        LspManager lsp = mock(LspManager.class);
        LlmAgentLoop loop = loopWithLsp(lsp);
        try {
            new MemoryBareModeConfig(true);   // forTest sessionId=null → isBareMode(null) 回落全局
            AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));
            assertThat(state).as("bare 模式 run() 必须正常完成").isNotNull();
            verify(lsp, never()).initialize();
        } finally {
            MemoryBareModeConfig.reset();
        }
    }

    @Test
    @DisplayName("非 bare 会话启动触发 LspManager.initialize()（门控反面 · 幂等接线不被误跳）")
    void run_nonBareMode_initializesLsp() {
        LspManager lsp = mock(LspManager.class);
        LlmAgentLoop loop = loopWithLsp(lsp);
        try {
            new MemoryBareModeConfig(false);
            AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));
            assertThat(state).as("非 bare 模式 run() 必须正常完成").isNotNull();
            verify(lsp).initialize();
        } finally {
            MemoryBareModeConfig.reset();
        }
    }
}
