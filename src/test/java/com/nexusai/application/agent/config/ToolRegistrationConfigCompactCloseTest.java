package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.prompt.SystemPromptInjection;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-C2 · {@code ToolRegistrationConfig.handleCompactCommand} manual /compact provider
 * close 接线意图测试（R5-4 另两处 new provider 接注销通道之一）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：{@code buildManualSystemPromptCtxProvider}（:1383）每次
 * /compact 新建一个 {@code SystemPromptContextProvider}，构造即向
 * {@code SystemPromptInjection.CACHE_CLEAR_HOOKS} 静态表注册缓存清理回调；若压缩结束不
 * close → 每次 /compact 永久泄漏一个 Runnable（与 RES-R5-4 前 ContextAnalyzeService 同类有界累积）。
 * 本测试断言连续 N 次 /compact 后静态表回到基线（每次 new provider → {@code finally close}）。
 * 若 handleCompactCommand 删掉 finally close（回退到「只构造不注销」），本测试变红。
 *
 * <p>隔离：{@code CACHE_CLEAR_HOOKS} 为进程级静态表，本用例只做相对断言（before/after），
 * 不依赖表的绝对基线；不污染其他测试（handleCompactCommand 在空消息时快速失败，
 * 不走真实压缩，压缩业务状态零残留）。
 */
class ToolRegistrationConfigCompactCloseTest {

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** 反射读静态表当前大小 · 断言表有界性（SystemPromptInjectionTest 同款观察点）。 */
    private static int tableSize() throws Exception {
        Field field = SystemPromptInjection.class.getDeclaredField("CACHE_CLEAR_HOOKS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> table = (List<Runnable>) field.get(null);
        return table.size();
    }

    @Test
    @DisplayName("连续 3 次 /compact 后 CACHE_CLEAR_HOOKS 回到基线（每次 new provider → finally close）")
    void repeatedCompact_doesNotLeakCacheClearHook() throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("sys", sessionId, null);
        registry.register(sessionId, state);

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        RequestContext.set(sessionId.toString(), "req-compact");
        try {
            int before = tableSize();
            for (int i = 0; i < 3; i++) {
                invokeHandleCompact(config, registry);
            }
            assertThat(tableSize())
                .as("3 次 /compact 后 CACHE_CLEAR_HOOKS 回到基线（每次 new provider → finally close，"
                    + "旧实现此处每次 +1 永久泄漏）")
                .isEqualTo(before);
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 反射驱动私有 {@code handleCompactCommand}（R1 分发线程的 /compact 执行体）。
     *
     * <p>空消息 AgentState → CompactCommand.call 在空校验（compact.ts:48-50）快速抛
     * IllegalArgumentException(ERROR_NO_MESSAGES_TO_COMPACT)，被 handler 捕获；provider 已
     * 构造（hook +1），finally 必须注销（hook -1）——失败路径同样不得泄漏。
     *
     * <p>[Fix-P1 HIGH] handleCompactCommand 返回类型 void → String（displayText 回传 result
     * handler → <local-command-stdout> 落库），空消息失败路径返回业务错误文案（非 null）。
     */
    private static void invokeHandleCompact(ToolRegistrationConfig config,
                                            SessionAgentStateRegistry registry) {
        Object result = ReflectionTestUtils.invokeMethod(
            config, "handleCompactCommand",
            "", registry, null, null, null, null, null, null, null, null);
        assertThat(result).as("handleCompactCommand 失败路径返回业务错误文案（非 null）")
            .isInstanceOf(String.class).isNotNull();
    }
}
