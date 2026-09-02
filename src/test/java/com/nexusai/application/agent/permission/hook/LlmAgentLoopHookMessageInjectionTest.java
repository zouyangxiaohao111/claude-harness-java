package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Session H3 v4 对抗核验残留缺口 Gap①] 非工具 hook 事件 message attachment → LLM 可见通道.
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: v2/v3 只修复了工具路径（StreamingToolExecutor 消费
 * {@code outcome.message()}）+ executeEvent 聚合层（折叠首个非阻断 message）; 但 LlmAgentLoop
 * 的 SessionStart/Setup/InstructionsLoaded/SessionEnd/UserPromptSubmit/notification 等
 * <b>非工具事件</b>消费者只检查 {@code preventContinuation()}/忽略返回值, executeEvent 返回的
 * {@code result.message()}（processHookJSONOutput 生成的 hook_blocking_error attachment）被
 * 丢弃 → message attachment 到不了 LLM. CC 真源: executeHooks yield {message}
 * （hooks.ts:2796）→ transcript → normalizeAttachmentForAPI（messages.ts:4090-4136）每轮渲染为
 * isMeta user message.
 *
 * <p>本测试锁定等价行为: {@code LlmAgentLoop.run()} 带配置的 SessionStart/UserPromptSubmit
 * command hook（FakeLauncher 内存进程）→ executeEvent 的 message attachment 追加到
 * {@code state.attachments()}（生产者侧）→ 首轮 LLM call 的 messagesForLlm（provider.stream
 * history 参数）必须包含渲染后的 hook_blocking_error 文本.
 *
 * <p><b>RED 条件</b>: 修复前 SessionStart 消费者 {@code hookRegistry.executeEvent(startEvent)}
 * 丢弃返回值 → attachments 恒空 → provider history 无 hook 文本 → 断言失败.
 *
 * @since Session H3 v4 对抗核验
 */
@DisplayName("[H3-v4 Gap①] 非工具 hook 事件 message attachment → LLM 可见通道")
class LlmAgentLoopHookMessageInjectionTest {

    /** 反射注入 hookRegistry 字段（@Autowired(required=false), 单测手动接线）. */
    private static void setHookRegistry(LlmAgentLoop loop, HookRegistry registry) throws Exception {
        Field f = LlmAgentLoop.class.getDeclaredField("hookRegistry");
        f.setAccessible(true);
        f.set(loop, registry);
    }

    /** 装配 HookRegistry: StubMatcherEngine（预设匹配 hook）+ FakeLauncher（内存进程）. */
    private static HookRegistry registryWithCommandHook(String stdout, String stderr, int exitCode) {
        HookRegistryDispatchTest.StubMatcherEngine engine =
            new HookRegistryDispatchTest.StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new CommandHook("check.sh", null, null, null, null, null, null, null),
            null, null, null, "settings")));
        HookRegistryDispatchTest.FakeHookProcess proc =
            new HookRegistryDispatchTest.FakeHookProcess(stdout, stderr, exitCode);
        HookRegistryDispatchTest.FakeLauncher launcher =
            new HookRegistryDispatchTest.FakeLauncher(proc);
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(new CommandHookExecutor(launcher, null, null, null, null));
        return registry;
    }

    /** mocked provider: 捕获首轮 history（arg 3）到 holder + 首调返回纯文本 stop → loop 正常退出. */
    private static LlmProviderFactory captureFactory(AtomicReference<List<ChatMessageDto>> holder) {
        LlmProvider provider = mock(LlmProvider.class);
        // [IMP-SP-08] blocks 重载：history@3 不变，onChunk@9/onMsg@10/onComplete@16
        doAnswer(inv -> {
            holder.set(inv.getArgument(3));
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from test provider");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello from test provider", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    @Test
    @DisplayName("SessionStart hook blockingError → message attachment 到达 provider history（LLM 可见）")
    void sessionStartHook_blockingError_reachesMessagesForLlm() throws Exception {
        // WHY: 核心 Gap① — SessionStart 消费者此前丢弃 executeEvent 返回值, hook 返回
        //      {decision:block} 的 hook_blocking_error attachment 到不了 LLM. CC 端该 message
        //      yield 进 transcript 并渲染给模型（模型需要知道 SessionStart hook 为何阻断）.
        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        LlmProviderFactory factory = captureFactory(history);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        setHookRegistry(loop, registryWithCommandHook(
            "{\"decision\":\"block\",\"reason\":\"no access\"}", "", 0));

        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        assertThat(history.get())
            .as("SessionStart hook 的 hook_blocking_error attachment 必须渲染为 meta user message 注入 messagesForLlm")
            .anySatisfy(m -> assertThat(m.content())
                .isEqualTo("config-command:check.sh hook blocking error: no access"));
        assertThat(state.exitReason())
            .as("非工具事件 hook 的阻断不改变 loop 退出（消费者只消费 message, 不阻断）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
    }

    @Test
    @DisplayName("SessionStart hook success (content='') → 追加 attachments 但不渲染（不污染 LLM）")
    void sessionStartHook_success_emptyContent_doesNotPollute() throws Exception {
        // WHY: hook_success content:'' 对齐 CC 抑制 trivial reminder（messages.ts:3577 跳过 ''）;
        //      注入 attachments 是 transcript 记录, 但不得渲染进 messagesForLlm 污染模型上下文.
        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        LlmProviderFactory factory = captureFactory(history);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        setHookRegistry(loop, registryWithCommandHook(
            "{\"continue\":true}", "", 0));

        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        // hook_success attachment 已记录进 state.attachments()（transcript 语义）
        assertThat(state.attachments()).anySatisfy(a ->
            assertThat(a.type()).isEqualTo("hook_success"));
        // 但 content='' → 不渲染进 LLM（无 "hook success:" 文本）
        assertThat(history.get())
            .as("hook_success content='' 不得渲染进 messagesForLlm")
            .noneSatisfy(m -> assertThat(m.content()).contains("hook success:"));
    }

    @Test
    @DisplayName("UserPromptSubmit hook blockingError → message attachment 到达 provider history（LLM 可见）")
    void userPromptSubmitHook_blockingError_reachesMessagesForLlm() throws Exception {
        // WHY: UserPromptSubmit 消费者此前只检查 preventContinuation() 丢弃 message; hook 返回
        //      {decision:block} 的阻断反馈必须进入 LLM 上下文（模型需要知道提交被 hook 拒绝的原因）.
        AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
        LlmProviderFactory factory = captureFactory(history);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        // 配置 hook matcher 覆盖 UserPromptSubmit（engine 恒返回该 hook, 任意事件都命中）
        setHookRegistry(loop, registryWithCommandHook(
            "{\"decision\":\"block\",\"reason\":\"prompt rejected\"}", "", 0));

        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        assertThat(history.get())
            .anySatisfy(m -> assertThat(m.content())
                .isEqualTo("config-command:check.sh hook blocking error: prompt rejected"));
    }

    @Test
    @DisplayName("IMP-HOOKS-S5 D-02: SessionEnd hook 失败 → 不注入 message/attachment，仅 log.error（CC executeSessionEndHooks 无注入）")
    void sessionEndHook_failure_noMessageInjection_onlyErrorLog() throws Exception {
        // WHY (D-02): CC executeSessionEndHooks（hooks.ts:4097-4141）不注入任何 message ——
        //   失败结果只写 stderr `SessionEnd hook [command] failed: output`（:4127-4134）。
        //   旧实现 injectHookResultMessage 把 hook_blocking_error attachment 记进
        //   state.attachments() 并自称"对齐 CC message 进 transcript 语义"为误读（删除清单 D-02）。
        //   服务端无 stderr 通道 → HookRegistry log.error。用事件判别式 programmatic hook
        //   （SessionStart/Setup 等其它事件 proceed，避免混淆来源；RED 条件：注入路径存在时
        //   attachments 出现 SESSION_END 来源的 hook_blocking_error）。
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HookRegistry.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.ERROR);
        try {
            AtomicReference<List<ChatMessageDto>> history = new AtomicReference<>();
            LlmProviderFactory factory = captureFactory(history);
            LlmAgentLoop loop = new LlmAgentLoop(factory);
            HookRegistry registry = new HookRegistry();
            registry.register("session-end-blocker", event -> {
                if (event.type() == HookEventType.SESSION_END) {
                    return GenericHook.HookResult.stop("blocked", "session end note");
                }
                return GenericHook.HookResult.proceed();
            });
            setHookRegistry(loop, registry);

            AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

            assertThat(state.attachments())
                .as("D-02: SessionEnd hook 结果不得注入 state.attachments()（CC 无 message 注入）")
                .noneSatisfy(a -> assertThat("hook_blocking_error".equals(a.type())
                    && a.content() != null && a.content().contains("session end note")).isFalse());
            assertThat(appender.list)
                .as("D-02: 失败结果必须 log.error（CC stderr 写 'SessionEnd hook [command] failed: output' 的 Java 等价）")
                .anySatisfy(e -> {
                    assertThat(e.getFormattedMessage()).contains("SessionEnd hook [")
                        .contains("failed: ")
                        .contains("session end note");
                });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("LlmAgentLoop 不再以 'system-prompt'/'default' 假参数同步发射 InstructionsLoaded（占位发射器删除，单一发射源=ClaudemdEngine）")
    void noFakeInstructionsLoadedPlaceholderEmission() throws Exception {
        // WHY (ODF-B4R): 占位发射器以假参数 file_path="system-prompt"/memory_type="default" 同步
        //      executeEvent（LlmAgentLoop:1668）—— 既无真实 memory 文件（CC claudemd.ts:1060 要求
        //      file.path + file.type），又与 ClaudemdEngine.getMemoryFiles 已对齐的异步 fire-and-forget
        //      发射（ODF-B4）双发。CC 唯一发射源 = getMemoryFiles（claudemd.ts:1054-1071）。本测试锁定：
        //      LlmAgentLoop.run() 全程不得出现带假参数的 InstructionsLoaded 事件（session §8 委托/删除，防双发）。
        java.util.List<HookEvent> captured = new java.util.ArrayList<>();
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookRegistryDispatchTest.StubMatcherEngine() {
            @Override
            public List<MatchedHook> getMatchingHooks(HookEvent event) {
                captured.add(event);
                return java.util.List.of(); // 只记录不执行，避免命令 hook 副作用
            }
        });
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory(new AtomicReference<>()));
        setHookRegistry(loop, registry);

        loop.run(RunRequest.forTest("hello", "test-model", null));

        assertThat(captured)
            .as("占位发射器必须删除/委托 ClaudemdEngine 真实发射，不得再以 'system-prompt'/'default' 假参数同步发射 InstructionsLoaded")
            .noneMatch(e -> e.type() == HookEventType.INSTRUCTIONS_LOADED
                && "system-prompt".equals(e.data().get("file_path")));
    }
}
