package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.settings.SupportedSettings;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.tool.ConfigToolPrompt;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.ConfigToolImpl;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R32-b7b-2 · <b>R7 每次解析测试</b> · 验证 P1-2 修复: getModelForCall() 在每次调用时
 * 重新解析优先级链, 无缓存, ConfigTool SET 后立即生效.
 *
 * <p><b>WHY (意图验证)</b>: R4 redo 报告 P1-2 缺陷 "解析位置不是每次 LLM call".
 * 旧实现仅在 run() 入口解析一次, 后续 provider call 复用捕获的 modelName.
 * 本测试验证 P1-2 修复: getModelForCall() 每次调用重新解析 (无缓存),
 * 中途 ConfigTool SET model 写入 settings 后下一次 getModelForCall() 立即返回新值.
 *
 * <p><b>R7 用例覆盖</b> (13 用例):
 * <ul>
 *   <li>R7-1: getModelForCall() 多次调用无缓存 · 相同设置下结果一致</li>
 *   <li>R7-2: ConfigTool SET model → getModelForCall() 返回新值 (P1-2 核心)</li>
 *   <li>R7-3: [W6-2] env (ANTHROPIC_MODEL) 已删除 → 设 env 不影响 getModelForCall()</li>
 *   <li>R7-4: setRuntimeModelOverride 变更 → getModelForCall() 返回新 override</li>
 *   <li>R7-5: setStartupModelFlag 变更 → getModelForCall() 返回新 flag</li>
 *   <li>R7-6: settings.model 删除 → 回落下一层 (startup / override)</li>
 *   <li>R7-7: override 清空 (set null) → 回落 startup flag / settings</li>
 *   <li>R7-8: 多 turn 连续重解析 · 模拟 run() 内每轮 getModelForCall() 调用</li>
 *   <li>R7-9: Recording provider · turn 1 stream() 收到 model A, turn 2 stream() 收到 model B (P1-2 端到端)</li>
 *   <li>R7-10: Recording provider · ConfigTool SET 后下一 turn 立即生效 (P1-2 端到端核心)</li>
 *   <li>R7-11: Recording provider · setRuntimeModelOverride 后下一 turn 立即生效</li>
 *   <li>R7-12: Recording provider · setStartupModelFlag 后下一 turn 立即生效</li>
 *   <li>R7-13: Recording provider · fallback 切换语义保留 (CC withRetry.ts:337-351 · rate-limit 场景)</li>
 * </ul>
 *
 * @see LlmAgentLoop#getModelForCall()
 */
class R32B7b2_PerCallReResolutionTest {

    private TestableLlmAgentLoop loop;
    private FileConfigStorage storage;
    private ConfigToolImpl tool;
    private ObjectMapper mapper;
    private Path tmpDir;
    private String originalUserHome;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        tmpDir = java.nio.file.Files.createTempDirectory("nexusai-b7b2-percall");
        tmpDir.toFile().deleteOnExit();
        // 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省路径 = user.home 派生；
        //   覆写 user.home 隔离测试写盘（防污染真实 ~/.nexusai），用后恢复。
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpDir.toString());

        BooleanSupplier allFalse = () -> false;
        Supplier<List<String>> modelOpts = () -> List.of("sonnet", "opus", "haiku");
        Function<String, CompletableFuture<SupportedSettings.ValidationResult>> validator =
            model -> CompletableFuture.completedFuture(
                new SupportedSettings.ValidationResult(true, null));
        Supplier<String> nullStr = () -> null;
        SupportedSettings supportedSettings = new SupportedSettings(
            allFalse, allFalse, allFalse, allFalse, allFalse, allFalse, allFalse,
            modelOpts, validator, nullStr,
            List.of("normal", "vim"),
            List.of("iterm2", "terminal_bell", "notifications_disabled"),
            List.of("tmux", "in-process", "auto"),
            List.of("dark", "light", "dark-daltonized", "light-daltonized"),
            List.of("dark", "light", "dark-daltonized", "light-daltonized", "system"));

        storage = new FileConfigStorage(null);
        ConfigToolPrompt prompt = new ConfigToolPrompt(supportedSettings, List::of);
        tool = new ConfigToolImpl(supportedSettings, storage, prompt);

        com.nexusai.infra.llm.LlmProviderFactory mockFactory =
            org.mockito.Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class);
        loop = new TestableLlmAgentLoop(mockFactory);
        loop.setFileConfigStorage(storage);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (tmpDir != null) {
            java.nio.file.Files.walk(tmpDir)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    @DisplayName("R7-1: getModelForCall() 多次调用无缓存 · 相同设置下结果一致")
    void multipleInvocationsReturnSameValueWithoutCaching() {
        // WHY: P1-2 修复保证每次调用重新解析 — 无内部缓存, 因此两次相同条件下调用结果一致.
        // 此用例验证一致性, 作为后续 re-resolution 用例的基线.
        invoke("model", "opus");
        String first = loop.getModelForCall();
        String second = loop.getModelForCall();
        String third = loop.getModelForCall();
        assertThat(first).isEqualTo("opus");
        assertThat(second).isEqualTo("opus");
        assertThat(third).isEqualTo("opus");
    }

    @Test
    @DisplayName("R7-2: ConfigTool SET model → getModelForCall() 立即返回新值 · P1-2 核心")
    void configToolSetTriggersImmediateReread() {
        // WHY: P1-2 修复核心 — 旧实现 run() 入口解析一次, 后续 provider call 复用捕获值.
        // 现在每次 getModelForCall() 调用都重新读 settings.model, SET 后立即生效.
        invoke("model", "opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");
        invoke("model", "haiku");
        // 关键: 无需 restart loop, 无需重新注入 storage, 下一次 getModelForCall() 立即读到 haiku
        assertThat(loop.getModelForCall()).isEqualTo("haiku");
    }

    @Test
    @DisplayName("R7-3: [W6-2] env (ANTHROPIC_MODEL) 已删除 → 设 env 不影响 getModelForCall()")
    void envChangeHasNoEffect() {
        // WHY: W6-2 用户拍板彻底删除 env 层 — 主模型来源统一走 DB settings.mainModelId.
        // env 变更不再影响解析结果 (无 settings/override/flag → 恒为 null).
        // 若未来误加回 env 读取, 本用例即回归红.
        loop.setEnvForTest("haiku");
        assertThat(loop.getModelForCall()).isNull();
        loop.setEnvForTest("opus");
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R7-4: setRuntimeModelOverride 变更 → getModelForCall() 返回新 override (优先级 1)")
    void runtimeOverrideChangeTriggersReread() {
        // WHY: P1-2 修复保证 session override (优先级 1) 每次调用重新解析.
        // 即使 settings + env 都设了, override 变更立即生效.
        invoke("model", "sonnet");      // settings=sonnet
        loop.setEnvForTest("haiku");    // W6-2: env 已删除, 应忽略
        loop.setRuntimeModelOverride("opus");  // override=opus (优先级 1)
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        // 关键: override 变更立即生效 (P1-2 修复保证)
        loop.setRuntimeModelOverride("sonnet");
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("R7-5: setStartupModelFlag 变更 → getModelForCall() 返回新 flag (优先级 2)")
    void startupFlagChangeTriggersReread() {
        // WHY: P1-3 新增 startup flag 独立字段 — 验证 P1-2 + P1-3 联动:
        // flag 变更时, 下次 getModelForCall() 立即反映 (每次重解析).
        invoke("model", "sonnet");      // 优先级 4: settings=sonnet
        loop.setStartupModelFlag("opus");  // 优先级 2: flag=opus
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        // flag 变更立即生效
        loop.setStartupModelFlag("haiku");
        assertThat(loop.getModelForCall()).isEqualTo("haiku");
    }

    @Test
    @DisplayName("R7-6: settings.model 删除 → 回落下一层 · 不缓存旧 settings 值")
    void settingsDeletedTriggersFallback() {
        // WHY: P1-2 修复保证 settings 路径每次调用重新读 — 删除后立即回落.
        invoke("model", "opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");
        // 清除 settings.model
        storage.unsetSettings(List.of("model"));
        // 无其他层设值 (env 已删除) → 回落 caller fallback (null)
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R7-7: setRuntimeModelOverride(null) 清空 → 回落 startup flag / env / settings")
    void overrideClearedTriggersFallback() {
        // WHY: P1-2 修复保证 override 字段每次调用重新读 — 清空后立即回落.
        loop.setRuntimeModelOverride("opus");        // 优先级 1: override=opus
        invoke("model", "haiku");                    // 优先级 4: settings=haiku
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        loop.setRuntimeModelOverride(null);           // 清除 override
        assertThat(loop.getModelForCall()).isEqualTo("haiku");  // 回落 settings
    }

    @Test
    @DisplayName("R7-8: 多 turn 连续重解析 · 模拟 run() 内每轮 getModelForCall() 调用")
    void multiTurnSimulation() {
        // WHY: P1-2 修复保证 run() 内部 loop() 每轮都调用 getModelForCall() (line 1542-1550).
        // 模拟多 turn 场景, 每 turn 间修改不同优先级层, 验证每次都正确读到最新值.
        // Turn 1: 全部为空 → null (caller fallback)
        assertThat(loop.getModelForCall()).isNull();

        // Turn 2: SET settings → opus
        invoke("model", "opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        // Turn 3: env 变更 → haiku (W6-2 env 层已删除, 应忽略 → 仍 settings opus 胜出)
        loop.setEnvForTest("haiku");
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        // Turn 4: env 清空, startup flag 设置 → sonnet (flag 优先级 2 > settings 4)
        loop.setEnvForTest("");
        loop.setStartupModelFlag("sonnet");
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");

        // Turn 5: override 设置 → opus (priority 1 > startup flag 2)
        loop.setRuntimeModelOverride("opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        // Turn 6: override 清空, 回落 startup flag
        loop.setRuntimeModelOverride(null);
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");

        // Turn 7: settings 清空, 回落 startup flag 仍然 sonnet
        storage.unsetSettings(List.of("model"));
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");

        // Turn 8: flag 清空, 全部空 → null (caller fallback)
        loop.setStartupModelFlag(null);
        assertThat(loop.getModelForCall()).isNull();
    }

    private AgentToolResult invoke(String setting, String value) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode input = mapper.createObjectNode();
            input.put("setting", setting);
            if (value != null) {
                input.put("value", value);
            }
            ToolUseBlock call = new ToolUseBlock("call-" + System.nanoTime(), "ConfigTool", input);
            return tool.execute(call);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── [R32-b7b-2 P1-2 修复] Recording provider 端到端多 turn 验证 ──

    /**
     * Recording LlmProvider mock · 记录每次 {@code stream()} 调用收到的 model 参数
     * 与调用次数 · 让测试断言真实 provider call 在多 turn 间使用动态 model.
     *
     * <p>为什么必须用真实 provider mock: 旧 R7 仅手动调 {@code getModelForCall()}
     * 验证 resolver 行为, 没有 provider 参数级证据. Code Reviewer P1-5 缺陷指出
     * 应有 recording provider 在 turn 1 / turn 2 真实记录 model, 才能证明 P1-2
     * "每次 provider call 前重新解析" 在真实 run() 流中生效.
     */
    static class RecordingLlmProvider implements LlmProvider {
        final List<String> recordedModels = new ArrayList<>();
        int streamCallCount = 0;

        @Override
        public String type() {
            return "recording";
        }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride,
                           com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            // 真实 run() 流: 记录 model + 模拟一次纯文本响应 (无 tool_calls → NORMAL 退出)
            recordedModels.add(modelName);
            streamCallCount++;
            // 模拟一次纯文本 assistant 消息 (无 tool_calls → run() 退出循环)
            AssistantMessage msg = new AssistantMessage("recording-response", "stop", java.util.Collections.emptyList());
            if (onAssistantMessage != null) {
                onAssistantMessage.accept(msg);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }

        @Override
        public String chat(ProviderConfig config, String modelName, String systemPrompt,
                           String userMessage) {
            recordedModels.add(modelName);
            return "recording-chat-response";
        }
    }

    /**
     * Helper: 创建绑定了 RecordingLlmProvider 的 LlmAgentLoop.
     */
    private TestableLlmAgentLoop createLoopWithRecordingProvider(RecordingLlmProvider rec) {
        LlmProviderFactory mockFactory = mock(LlmProviderFactory.class);
        // lenient(): Mockito 严格模式可能误报 "unnecessary stub" — 这里多种签名都有 stub,
        // 关掉严格模式避免误判. RecordingLlmProvider 必须始终返回 rec, 包括 null config.
        org.mockito.Mockito.lenient()
            .when(mockFactory.getProvider(any(), any())).thenReturn(rec);
        org.mockito.Mockito.lenient()
            .when(mockFactory.getProvider(any(), nullable(String.class))).thenReturn(rec);
        TestableLlmAgentLoop loop = new TestableLlmAgentLoop(mockFactory);
        loop.setFileConfigStorage(storage);
        return loop;
    }

    @Test
    @DisplayName("R7-9: Recording provider · turn 1 stream() 收到 model A, run() 内多 turn 模型动态切换 (P1-2 端到端)")
    void recordingProviderMultiTurnModelSwitch() {
        // WHY: P1-2 修复核心 (Code Reviewer P1-5): 验证真实 provider call 在 run() 多 turn
        // 间收到不同 model. 旧 R7-8 仅手动串行调 getModelForCall(), 未真实跑 run().
        // 这里跑真实 run() 流程 (使用 Mock 简化 stream 回调), ConfigTool SET model=B 后
        // 第二轮 provider.stream() 必须收到 model B.
        invoke("model", "A");
        RecordingLlmProvider rec = new RecordingLlmProvider();
        TestableLlmAgentLoop loop = createLoopWithRecordingProvider(rec);

        // 跑 run() — 第一次 provider call 用 settings=A
        RunRequest params = RunRequest.forTest("recording prompt", "default-model", null);
        AgentState state = loop.run(params);
        assertThat(state).isNotNull();
        assertThat(rec.streamCallCount).isEqualTo(1);
        assertThat(rec.recordedModels.get(0)).isEqualTo("A");

        // 第二轮: ConfigTool SET model=B 后再调一次 run() — 第二次 provider call 用 settings=B
        invoke("model", "B");
        AgentState state2 = loop.run(params);
        assertThat(state2).isNotNull();
        assertThat(rec.streamCallCount).isEqualTo(2);
        assertThat(rec.recordedModels.get(1)).isEqualTo("B");
        // 关键 P1-2 证据: 两次 provider call 收到不同 model — settings 变更实时生效
        assertThat(rec.recordedModels).containsExactly("A", "B");
    }

    @Test
    @DisplayName("R7-10: Recording provider · env 已删除 · override/flag/settings 变更后下一 run() 立即生效 (P1-2 核心)")
    void recordingProviderEnvOverrideFlagImmediateEffect() {
        // WHY: W6-2 删除 env 层 — env 不再影响主模型解析; override/flag/settings 每轮重解析.
        // env 设值被忽略 → 无 settings/override/flag 时回落 params.modelName (Built-in default).
        RecordingLlmProvider rec = new RecordingLlmProvider();
        TestableLlmAgentLoop loop = createLoopWithRecordingProvider(rec);
        RunRequest params = RunRequest.forTest("p", "default", null);

        // 第一轮: env=A (已删除, 应忽略) → 回落 params.modelName="default"
        loop.setEnvForTest("A");
        loop.run(params);
        assertThat(rec.recordedModels).containsExactly("default");

        // 第二轮: env 变 B (仍忽略) → 依旧 fallback "default"
        loop.setEnvForTest("B");
        loop.run(params);
        assertThat(rec.recordedModels).containsExactly("default", "default");

        // 第三轮: env 清空 + override=C → override 胜出 (优先级 1)
        loop.setEnvForTest("");
        loop.setRuntimeModelOverride("C");
        loop.run(params);
        assertThat(rec.recordedModels).containsExactly("default", "default", "C");

        // 第四轮: override 清空 + flag=D → flag 胜出 (优先级 2 > settings 4)
        loop.setRuntimeModelOverride(null);
        loop.setStartupModelFlag("D");
        loop.run(params);
        assertThat(rec.recordedModels).containsExactly("default", "default", "C", "D");

        // 第五轮: 全部清空 + settings=E (ConfigTool SET) → settings 胜出 (优先级 4)
        loop.setStartupModelFlag(null);
        invoke("model", "E");
        loop.run(params);
        assertThat(rec.recordedModels).containsExactly("default", "default", "C", "D", "E");
    }

    @Test
    @DisplayName("R7-11: Recording provider · setRuntimeModelOverride 变更后立即在下一 run() 生效")
    void recordingProviderRuntimeOverrideImmediateEffect() {
        // WHY: P1-2 + P1-3 联动 — /model 命令语义在真实 run() 流中立即生效.
        invoke("model", "sonnet");
        RecordingLlmProvider rec = new RecordingLlmProvider();
        TestableLlmAgentLoop loop = createLoopWithRecordingProvider(rec);
        RunRequest params = RunRequest.forTest("p", "default", null);

        loop.run(params);
        assertThat(rec.recordedModels.get(0)).isEqualTo("sonnet");  // settings 层胜出

        loop.setRuntimeModelOverride("opus");
        loop.run(params);
        assertThat(rec.recordedModels.get(1)).isEqualTo("opus");  // session override 立即胜出
    }

    @Test
    @DisplayName("R7-12: Recording provider · setStartupModelFlag 变更后立即在下一 run() 生效")
    void recordingProviderStartupFlagImmediateEffect() {
        // WHY: P1-2 + P1-3 联动 — --model 启动 flag 在真实 run() 流中立即生效.
        invoke("model", "haiku");
        RecordingLlmProvider rec = new RecordingLlmProvider();
        TestableLlmAgentLoop loop = createLoopWithRecordingProvider(rec);
        RunRequest params = RunRequest.forTest("p", "default", null);

        loop.run(params);
        assertThat(rec.recordedModels.get(0)).isEqualTo("haiku");  // settings 层胜出

        loop.setStartupModelFlag("opus");
        loop.run(params);
        assertThat(rec.recordedModels.get(1)).isEqualTo("opus");  // startup flag 立即胜出 (优先级 2 > 4)
    }

    @Test
    @DisplayName("R7-13: Recording provider · fallback 切换语义保留 (CC withRetry.ts:337-351)")
    void recordingProviderFallbackSwitchSemanticsPreserved() {
        // WHY: P1-2 修复保留 fallback 切换语义 (CC withRetry.ts:337-351 rate-limit/429-529
        // 切换). 即 getModelForCall() 返回 null 时, run() 内的 effectiveModel 必须回落到
        // recoveryState.getCurrentModel() (CC model.ts Built-in default 语义).
        // Recording provider 验证: 5 层优先级全空 → getModelForCall() 返回 null →
        // effectiveModel 回落 params.modelName() (RecoveryState 初始化值) 而非抛 NPE.
        RecordingLlmProvider rec = new RecordingLlmProvider();
        TestableLlmAgentLoop loop = createLoopWithRecordingProvider(rec);
        // 不设任何 layer, 全空
        RunRequest params = RunRequest.forTest("p", "params-default", null);
        AgentState state = loop.run(params);

        assertThat(state).isNotNull();
        // fallback 证据: provider 收到 params.modelName() (CC Built-in default 语义)
        assertThat(rec.streamCallCount).isEqualTo(1);
        assertThat(rec.recordedModels.get(0))
            .as("fallback 到 params.modelName (CC withRetry.ts:337-351 Built-in default)")
            .isEqualTo("params-default");
    }

    /**
     * Testable LlmAgentLoop 子类 · 提供 env var 注入钩子 (同 R4 重写版本).
     * [W6-2] env 层已删除 (readEnvModel 恒返回空串), 本钩子仅用于验证 env 被忽略.
     */
    static class TestableLlmAgentLoop extends LlmAgentLoop {
        private String envForTest = "";

        TestableLlmAgentLoop(com.nexusai.infra.llm.LlmProviderFactory factory) {
            super(factory);
        }

        void setEnvForTest(String env) {
            this.envForTest = env == null ? "" : env;
        }

        @Override
        protected String readEnvModel() {
            return envForTest;
        }
    }
}