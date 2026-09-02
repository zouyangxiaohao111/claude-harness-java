package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.hook.CommandHookExecutor.CommandHookResult;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-ts04 IMPL-02 / OD-TS04-03 + OD-TS04-07] 入口级 signal 早退（匹配后、执行前整批静默跳过）· 对齐 CC 真源:
 * <ul>
 *   <li>{@code executeHooks} 入口 (hooks.ts:2015-2017 {@code if (signal?.aborted) return})
 *       — Java {@link HookRegistry#executeEvent} 等价落点（主落点 1）</li>
 *   <li>{@code executeHooksOutsideREPL} 入口 (hooks.ts:3051-3053 {@code if (signal?.aborted) return []})
 *       — Java {@link HookRegistry#executeEventAll} 等价落点（主落点 2）</li>
 *   <li>第二道批级预检 = {@link HookRegistry} {@code executeConfiguredHooks} 空检查后、序列化前
 *       （防未来绕过入口的新调用路径漏检，与入口级幂等）</li>
 * </ul>
 *
 * <p>验收锚点（REQ-07/11）：
 * <ol>
 *   <li>早退位置 = 匹配后、执行前（顺序断言：matcher 计数 1 + 4 类型 executor 0 次调用）</li>
 *   <li>已取消父 → executeEvent/executeEventAll 零结果产出（proceed / 空列表，对齐 CC 静默跳过 OD-TS04-07）</li>
 *   <li>无 hook_cancelled attachment（零结果 → 无 message）</li>
 *   <li>反向：未取消父（NOOP）→ 4 类型正常执行（防误杀）</li>
 *   <li>第二道：executeConfiguredHooks 反射直调 → 空列表</li>
 * </ol>
 *
 * <p>不依赖 Spring 容器：全部 executor 为计数桩（镜像 CancellationSemanticsTest /
 * CommandHookAbortPropagationTest 基建），不触达真实 LLM / 子进程 / 网络 I/O。
 */
@DisplayName("[fix-ts04 IMPL-02] 入口级 signal 早退（匹配后、执行前整批静默跳过）")
class FixTs04SignalEarlyExitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════════
    // 计数型 executor 桩（覆写 exec/execute 计数，不触达真实执行面）
    // ════════════════════════════════════════════════════════════════════════

    /** 计数型 command executor · 镜像 CommandHookAbortPropagationTest.CountingCommandExecutor. */
    static class CountingCommandExecutor extends CommandHookExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput, String pluginRoot, String pluginId, String skillRoot, Integer hookIndex, boolean forceSyncExecution, AbortController parentAbort) {
            calls.incrementAndGet();
            return new CommandHookResult("{\"decision\":\"approve\"}", "", "", 0, false, false);
        }

            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                             String pluginRoot, String pluginId, String skillRoot,
                                             Integer hookIndex, boolean forceSyncExecution,
                                             AbortController parentAbort, long defaultTimeoutMs, String hookCwd) {
                // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
                return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                    hookIndex, forceSyncExecution, parentAbort);
            }
    }

    /** 计数型 prompt executor · 覆写 exec 计数（不触达真实 LLM）. */
    static class CountingPromptExecutor extends ExecPromptHook {
        final AtomicInteger calls = new AtomicInteger();

        CountingPromptExecutor() {
            super(JSON);
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort) {
            // [H2/CCJ-EXEC-01] 6 参委托 7 参（分发层现调 7 参版本）
            return exec(hook, hookName, hookEvent, jsonInput, llmContext, parentAbort, null);
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort,
                               java.util.List<com.nexusai.model.session.dto.ChatMessageDto> messages) {
            calls.incrementAndGet();
            return GenericHook.HookResult.proceed();
        }
    }

    /** 计数型 agent executor · 覆写 exec 计数（不触达真实 loop）· 镜像 CancellationSemanticsTest.agentHookWith. */
    static class CountingAgentExecutor extends ExecAgentHook {
        final AtomicInteger calls = new AtomicInteger();

        CountingAgentExecutor() {
            super(JSON, new AgentLoopContextFactory(), new ToolRegistry(), null,
                ProviderConfig.empty(), "haiku-test", null, null, null);
        }

        @Override
        public HookResult exec(AgentHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, String transcriptPath, AbortController parentAbort,
                               String sessionId, String agentName, ToolPermissionContext parentPermCtx) {
            calls.incrementAndGet();
            return GenericHook.HookResult.proceed();
        }
    }

    /** 计数型 http executor · 覆写 exec 计数（不发起网络 I/O）. */
    static class CountingHttpExecutor extends ExecHttpHook {
        final AtomicInteger calls = new AtomicInteger();

        CountingHttpExecutor() {
            super(new HooksSettings(key -> null), new SsrfGuard());
        }

        @Override
        public HttpHookResult exec(HttpHook hook, String hookName, HookEvent hookEvent,
                                   String jsonInput, AbortController parentAbort) {
            calls.incrementAndGet();
            return new HttpHookResult(true, 200, "{\"ok\":true}", null, false);
        }
    }

    /** 计数型 matcher · 记录 getMatchingHooks 调用次数（顺序断言: 匹配发生 1 次 + 执行 0 次）. */
    static class CountingMatcherEngine extends HookMatcherEngine {
        final AtomicInteger matchCalls = new AtomicInteger();
        volatile List<MatchedHook> hooks = List.of();

        CountingMatcherEngine() {
            super(null, null);
        }

        void setHooks(List<MatchedHook> hooks) {
            this.hooks = hooks;
        }

        @Override
        public List<MatchedHook> getMatchingHooks(HookEvent event) {
            matchCalls.incrementAndGet();
            return hooks;
        }
    }

    /** Stub ModelConfigResolver（镜像 CancellationSemanticsTest）· 任意非空模型名 → 可用 provider. */
    static class StubModelConfigResolver extends ModelConfigResolver {
        @Override
        public ResolvedModel resolve(String modelName) {
            if (modelName == null || modelName.isBlank()) {
                return null;
            }
            return new ResolvedModel(new ProviderConfig("https://llm.example.com", "sk-test-123"),
                "openai_compatible");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════════

    private static ToolUseContext parentTucWith(AbortController abort) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abort, List.of(), null, PermissionMode.DEFAULT);
    }

    private static HookEvent userPrompt() {
        return HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");
    }

    /** 4 类型配置驱动 hook（command/prompt/agent/http）· hookSource=settings. */
    private static List<MatchedHook> fourTypeHooks() {
        return List.of(
            new MatchedHook(new CommandHook("echo stub", null, null, null, null, null, null, null),
                null, null, null, "settings"),
            new MatchedHook(new PromptHook("check $ARGUMENTS", null, null, "haiku-test", null, null),
                null, null, null, "settings"),
            new MatchedHook(new AgentHook("check $ARGUMENTS", null, null, null, null, null),
                null, null, null, "settings"),
            new MatchedHook(new HttpHook("http://127.0.0.1:9/hook", null, 10, null, null, null, null),
                null, null, null, "settings"));
    }

    /** 装配 registry：计数 matcher + 4 计数 executor. */
    private static HookRegistry registryWith(CountingMatcherEngine matcher,
                                             CountingCommandExecutor cmd,
                                             CountingPromptExecutor prompt,
                                             CountingAgentExecutor agent,
                                             CountingHttpExecutor http) {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(matcher);
        registry.setCommandHookExecutor(cmd);
        registry.setExecPromptHook(prompt);
        registry.setExecAgentHook(agent);
        registry.setExecHttpHook(http);
        // prompt 分支执行前置 provider 解析需要（reverse 测试触达 executor 时才用到）
        registry.setLlmProviderFactory(new LlmProviderFactory() {
            @Override
            public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return new MockLlmProvider();
            }
        });
        registry.setModelConfigResolver(new StubModelConfigResolver());
        return registry;
    }

    // ════════════════════════════════════════════════════════════════════════
    // REQ-07/OD-TS04-03: executeEvent 入口级早退（匹配后、执行前）
    // CC executeHooks hooks.ts:2015-2017 `if (signal?.aborted) return`
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeEvent: 父已取消 → 匹配发生 1 次 + 4 类型 executor 0 次调用 + 无 message attachment")
    void executeEvent_cancelledParent_fourTypesAllSkipped_noAttachment() {
        // WHY (OD-TS04-03): CC 早返位置 = getMatchingHooks(:2004) 之后、执行(:2143) 之前
        //   (hooks.ts:2011 length0 return → :2015 signal?.aborted return)。Java 等价：
        //   getMatchingHooks 后、executeConfiguredHooks 前单点检查 → 整批静默跳过。
        //   顺序断言 = matcher 计数 1（匹配发生）&& 4 executor 计数 0（未执行）。
        CountingMatcherEngine matcher = new CountingMatcherEngine();
        matcher.setHooks(fourTypeHooks());
        CountingCommandExecutor cmd = new CountingCommandExecutor();
        CountingPromptExecutor prompt = new CountingPromptExecutor();
        CountingAgentExecutor agent = new CountingAgentExecutor();
        CountingHttpExecutor http = new CountingHttpExecutor();
        HookRegistry registry = registryWith(matcher, cmd, prompt, agent, http);

        AbortController cancelled = new AbortController();
        cancelled.abort("user_cancelled");
        HookResult result = registry.executeEvent(userPrompt(), null, parentTucWith(cancelled));

        // 顺序断言: 闸门(disableAll→bare→trust) → 匹配(getMatchingHooks=1) → signal 早退(执行 0)
        assertThat(matcher.matchCalls.get())
            .as("匹配必须发生且仅 1 次（signal 检查在匹配之后、执行之前，对齐 CC hooks.ts:2015-2017）")
            .isEqualTo(1);
        assertThat(cmd.calls.get())
            .as("已取消父 → command executor 0 次调用（整批跳过）")
            .isZero();
        assertThat(prompt.calls.get())
            .as("已取消父 → prompt executor 0 次调用（整批跳过）")
            .isZero();
        assertThat(agent.calls.get())
            .as("已取消父 → agent executor 0 次调用（整批跳过）")
            .isZero();
        assertThat(http.calls.get())
            .as("已取消父 → http executor 0 次调用（整批跳过）")
            .isZero();
        // OD-TS04-07: 对齐 CC 静默跳过 → 零结果产出，无 hook_cancelled/任何 message attachment
        assertThat(result.message())
            .as("早退零结果 → 无 message attachment（含 hook_cancelled）")
            .isNull();
        assertThat(result.preventContinuation())
            .as("早退零结果 → 无干预（preventContinuation=false）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // REQ-07/OD-TS04-03: executeEventAll 入口级早退（匹配后、执行前）
    // CC executeHooksOutsideREPL hooks.ts:3051-3053 `if (signal?.aborted) return []`
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeEventAll: 父已取消 → 匹配发生 1 次 + 4 类型 executor 0 次调用 + 空列表")
    void executeEventAll_cancelledParent_returnsEmptyList() {
        // WHY (OD-TS04-03): CC executeHooksOutsideREPL 同构（:3041 匹配 → :3047 length0 return []
        //   → :3051-3053 signal?.aborted return []）。Java executeEventAll 匹配后、执行前
        //   单点检查 → 空列表（对齐 CC return []）。
        CountingMatcherEngine matcher = new CountingMatcherEngine();
        matcher.setHooks(fourTypeHooks());
        CountingCommandExecutor cmd = new CountingCommandExecutor();
        CountingPromptExecutor prompt = new CountingPromptExecutor();
        CountingAgentExecutor agent = new CountingAgentExecutor();
        CountingHttpExecutor http = new CountingHttpExecutor();
        HookRegistry registry = registryWith(matcher, cmd, prompt, agent, http);

        AbortController cancelled = new AbortController();
        cancelled.abort("user_cancelled");
        List<HookResult> results = registry.executeEventAll(userPrompt(), parentTucWith(cancelled));

        assertThat(matcher.matchCalls.get())
            .as("匹配必须发生且仅 1 次（signal 检查在匹配之后、执行之前，对齐 CC hooks.ts:3051-3053）")
            .isEqualTo(1);
        assertThat(results)
            .as("已取消父 → executeEventAll 返回空列表（对齐 CC return []）")
            .isEmpty();
        assertThat(cmd.calls.get()).as("已取消父 → command executor 0 次调用").isZero();
        assertThat(prompt.calls.get()).as("已取消父 → prompt executor 0 次调用").isZero();
        assertThat(agent.calls.get()).as("已取消父 → agent executor 0 次调用").isZero();
        assertThat(http.calls.get()).as("已取消父 → http executor 0 次调用").isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 反向: 未取消父 → 正常执行（早退检查不误杀）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("反向: 父未取消(NOOP) → 4 类型 executor 各执行 1 次（防误杀）")
    void uncancelledParent_fourTypesExecute() {
        // WHY (PROBE-02 §3.3 断言 4): 反向断言 — 父 abort 未取消 → 正常执行，
        //   保证入口级早退检查不误杀正常路径（NOOP.isCancelled() 恒 false）。
        CountingMatcherEngine matcher = new CountingMatcherEngine();
        matcher.setHooks(fourTypeHooks());
        CountingCommandExecutor cmd = new CountingCommandExecutor();
        CountingPromptExecutor prompt = new CountingPromptExecutor();
        CountingAgentExecutor agent = new CountingAgentExecutor();
        CountingHttpExecutor http = new CountingHttpExecutor();
        HookRegistry registry = registryWith(matcher, cmd, prompt, agent, http);

        HookResult result = registry.executeEvent(userPrompt(), null, parentTucWith(AbortController.NOOP));

        assertThat(matcher.matchCalls.get()).isEqualTo(1);
        assertThat(cmd.calls.get()).as("未取消父 → command executor 正常执行 1 次").isEqualTo(1);
        assertThat(prompt.calls.get()).as("未取消父 → prompt executor 正常执行 1 次").isEqualTo(1);
        assertThat(agent.calls.get()).as("未取消父 → agent executor 正常执行 1 次").isEqualTo(1);
        assertThat(http.calls.get()).as("未取消父 → http executor 正常执行 1 次").isEqualTo(1);
        assertThat(result)
            .as("正常执行路径结果非 null")
            .isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // OD-TS04-03 第二道: executeConfiguredHooks 批级预检（防未来入口漏检）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("第二道: executeConfiguredHooks（反射直调）→ 父已取消返回空列表（空检查后、序列化前）")
    void executeConfiguredHooks_cancelledParent_secondDefense_returnsEmpty() throws Exception {
        // WHY (OD-TS04-03): 第二道防御 — 防未来绕过 executeEvent/executeEventAll 的
        //   新调用路径漏检；与入口级幂等（同条件同结果 List.of()）。反射直调私有方法
        //   独立验证批级预检本身（不经入口）。
        HookRegistry registry = new HookRegistry();
        AbortController cancelled = new AbortController();
        cancelled.abort("user_cancelled");

        Method m = HookRegistry.class.getDeclaredMethod("executeConfiguredHooks",
            HookEvent.class, List.class, ToolUseContext.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<HookResult> results = (List<HookResult>) m.invoke(registry,
            userPrompt(), fourTypeHooks(), parentTucWith(cancelled));

        assertThat(results)
            .as("executeConfiguredHooks 批级预检：父已取消 → 空列表（对齐 CC executeHooks 入口早返）")
            .isEmpty();
    }
}
