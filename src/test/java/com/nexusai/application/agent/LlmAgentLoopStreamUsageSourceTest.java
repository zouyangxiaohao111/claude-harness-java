package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.eventbus.ws.MessageUsageEvent;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [R3-5 · usage-source 合并入口] {@code setStreamContext(..., usageSource)} 4 参重载的语义钉死。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：原实现是「3 参 setStreamContext（内部复位来源为 user）
 * + 独立 {@code setStreamUsageSource}」两步 —— 顺序写反（先打标后建流上下文）会让来源被静默复位，
 * 且失败形态不可见（事件 source 恒 user ⇒ 前端底部数字照旧被后台任务污染，无任何报错）。
 * 合并后来源与流上下文**同一次调用**写入 ⇒ 顺序契约结构上不存在；本测试把它钉住：
 * <ul>
 *   <li>不传来源（3 参 / null）→ 缺省 user（前台主路径逐位不变）；</li>
 *   <li>传 {@code SOURCE_BACKGROUND} → 本 run 的 {@code AgentState.usageSource} = background
 *       （= message.usage 事件的 source，前端底部数字据此不计入）；</li>
 *   <li>先打标、再以 3 参建流上下文 → 回缺省 user（复位语义显式化，不再是隐性顺序坑）。</li>
 * </ul>
 *
 * <p><b>RED tooth</b>：把 4 参重载改成忽略 {@code usageSource} 形参（直接复位 user）→ 第 2 用例红。
 *
 * <p>harness 与 {@code ChannelInjectionUntrustedBranchTest} 同源（{@code new LlmAgentLoop(mock(factory))}
 * + {@code loop.run(RunRequest.forTest(...))} —— 只关心 doRun 建 state 后的盖章，不关心模型产出）。
 */
@DisplayName("[R3-5/D1] setStreamContext 4 参重载：usage 来源与流上下文同调用写入")
class LlmAgentLoopStreamUsageSourceTest {

    /** 单轮纯文本收尾的 provider（harness 与 {@code ChannelInjectionUntrustedBranchTest} 同源）。 */
    private static LlmAgentLoop newLoop() {
        LlmProvider provider = mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            onMsg.accept(new AssistantMessage("ok", "end_turn", List.of(), null, null));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        ctxFactory.setNotificationQueue(new NotificationQueue());
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setContextFactory(ctxFactory);
        return loop;
    }

    private static AgentState runOnce(LlmAgentLoop loop) {
        return loop.run(RunRequest.forTest("main-prompt", "test-model", null));
    }

    @Test
    @DisplayName("缺省（3 参重载 / usageSource=null）→ user（前台主路径行为逐位不变）")
    void defaultsToUser() {
        LlmAgentLoop viaThreeArg = newLoop();
        viaThreeArg.setStreamContext(null, "sess-r35", null);
        assertThat(runOnce(viaThreeArg).usageSource()).isEqualTo(MessageUsageEvent.SOURCE_USER);

        LlmAgentLoop viaNullSource = newLoop();
        viaNullSource.setStreamContext(null, "sess-r35", null, null);
        assertThat(runOnce(viaNullSource).usageSource()).isEqualTo(MessageUsageEvent.SOURCE_USER);
    }

    @Test
    @DisplayName("传入 background → 本 run 的 state.usageSource=background（事件 source 随之）")
    void carriesExplicitSource() {
        LlmAgentLoop loop = newLoop();
        loop.setStreamContext(null, "sess-r35", null, MessageUsageEvent.SOURCE_BACKGROUND);

        assertThat(runOnce(loop).usageSource()).isEqualTo(MessageUsageEvent.SOURCE_BACKGROUND);
    }

    @Test
    @DisplayName("复位语义显式化：先用单点 setter 打标、再以 3 参建流上下文 → 回缺省 user")
    void threeArgOverwritesPriorStamp() {
        LlmAgentLoop loop = newLoop();
        loop.setStreamUsageSource(MessageUsageEvent.SOURCE_BACKGROUND);
        loop.setStreamContext(null, "sess-r35", null);   // 3 参 = 缺省复位（原隐性顺序坑的显式化）

        assertThat(runOnce(loop).usageSource()).isEqualTo(MessageUsageEvent.SOURCE_USER);
    }
}
