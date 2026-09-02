package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.JournalEntry;
import com.nexusai.application.agent.workflow.JournalStore;
import com.nexusai.application.agent.workflow.PermissionGate;
import com.nexusai.application.agent.workflow.ProgressEmitter;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.RunStatus;
import com.nexusai.application.agent.workflow.TaskRegistrar;
import com.nexusai.application.agent.workflow.WorkflowLogger;
import com.nexusai.application.agent.workflow.WorkflowPorts;
import com.nexusai.application.agent.workflow.WorkflowRunResult;
import com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry;
import com.nexusai.application.agent.workflow.agent.ClaudeCodeBackendAdapter;
import com.nexusai.application.agent.workflow.registry.WorkflowRegistry;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.OpenAiSdkProvider;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E：真机 LLM 往返验证（deepseek 配置 · P1 Report D-6 补跑）。
 *
 * <p><b>WHY（P1 Report D-6）</b>：workflow 编排 P1 已落地 WorkflowRunEngine → 真 adapter
 * （ClaudeCodeBackendAdapter）→ 真 SubagentExecutor 委托链，但<b>真机 LLM 往返从未验证</b>。
 * 本测试用 deepseek 真配置（Anthropic + OpenAI 双格式）验证：
 * <ol>
 *   <li><b>Part A · LLM 层 E2E</b> — 构造 {@code ProviderConfig}（deepseek + 真 token）→
 *       {@link LlmProviderFactory#getProvider}（真实工厂反射注入真实 provider，走真实路由）→
 *       {@link LlmProvider#chat} 真调用。断言返回真实 LLM 文本（非 mock）。</li>
 *   <li><b>Part B · workflow 编排 E2E</b> — 最小 workflow 脚本经 {@link WorkflowRunEngine#run}
 *       → 真 {@code WorkflowHooksImpl} → 真 {@code AgentAdapterRegistry}（claude-code default）
 *       → 真 {@code ClaudeCodeBackendAdapter} → 真 {@code SubagentExecutor}（注入 deepseek 配置）
 *       → 真 {@code LlmAgentLoop.queryLoop} → deepseek。断言 run 返回 completed + agent 输出
 *       真实 LLM 文本（非 mock）。</li>
 * </ol>
 *
 * <p><b>生产路径（G-1/G-2 修复后 · 无手动注册）</b>：
 * <ul>
 *   <li><b>G-1 注册生效</b>：{@code WorkflowRegistry.buildRegistry}（:167）在构造时把
 *       {@code ClaudeCodeBackendAdapter.WORKFLOW_AGENT}（'workflow-worker'）注册进
 *       SubagentExecutor.additionalAgentDefinitions —— 生产回落路径
 *       {@code resolveAgentDefinition('workflow-worker')} 命中，不再 AgentNotFoundException。
 *       测试不再手动注册。</li>
 *   <li><b>G-2 executor 接线</b>：{@code new WorkflowRunEngine()}（无参，executor=null）→ parser
 *       默认编译 {@code RestrictedScriptExecutor}（受限 DSL 解释器，替换 NOT_WIRED）—— 生产
 *       WorkflowServiceImpl 同路径。B.1 以真实脚本 {@code phase('A') + await agent('do')} 经解释器
 *       执行（非 lambda 直表）。</li>
 *   <li><b>能力边界（受限模型，非全 JS）</b>：RestrictedScriptExecutor 支持 const/let/var、赋值、
 *       if/else、while、for...of、continue/break、箭头函数、await、.then/.catch、对象/数组字面量
 *       （含 spread / shorthand）、模板字符串、三元、成员访问、方法调用（map/filter/flat/includes/
 *       push/find/forEach/length/join/indexOf）、Date/Boolean/String/Number/Object.keys-values-entries；
 *       <b>不支持</b> → 编译期 fail-loud ScriptError：import、function/class 声明、正则、解构、C 风格
 *       for、generator、原生模块（fs/process）、非 Date 的 new、Date.now()/new Date() 无参/Math.random()
 *       （NonDeterministicError 沙箱）。全 JS（GraalJS）待 DEC-P0-02 拍板。</li>
 *   <li>真机调用超时 / 无网络时测试以断言失败显式暴露（不静默跳过、不谎报通过）；恢复网络后重跑
 *       即可。（CLAUDE.md 规则 12 · Fail loud）</li>
 * </ul>
 */
@DisplayName("[D-6 E2E] 真机 LLM 往返（deepseek）")
class WorkflowE2EDeepseekTest {

    private static final Logger log = LoggerFactory.getLogger(WorkflowE2EDeepseekTest.class);

    // ── deepseek 真机配置（P1 Report D-6 提供）──
    private static final String DEEPSEEK_ANTHROPIC_BASE_URL = "https://api.deepseek.com/anthropic";
    private static final String DEEPSEEK_OPENAI_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_API_KEY = "sk-88ca20f21d394c46bb646b857094c3be";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    /** mock 特征前缀（断言「非 mock」用）· MockLlmProvider.chat 恒返回 {@code "Mock reply to: " + userMessage}。 */
    private static final String MOCK_PREFIX = "Mock reply to: ";

    // ════════════════════════════════════════════════════════════════════
    // Part A · LLM 层 E2E：ProviderConfig → LlmProviderFactory → 真 LLM
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A.1 Anthropic 格式（deepseek/anthropic）：getProvider→chat 返回真实 LLM 文本（非 mock）")
    void directLlm_anthropicFormat_returnsRealText() {
        // WHY（D-6）：deepseek 提供 Anthropic 兼容端点（baseUrl=https://api.deepseek.com/anthropic）。
        //   Java AnthropicSdkProvider（官方 anthropic-java SDK）以 baseUrl 覆盖支持该端点。
        //   真调用成功 + 文本非 mock = deepseek Anthropic 格式真实可连通。
        LlmProviderFactory factory = realFactory();
        ProviderConfig config = new ProviderConfig(DEEPSEEK_ANTHROPIC_BASE_URL, DEEPSEEK_API_KEY);

        LlmProvider provider = factory.getProvider(config, "anthropic");
        log.info("[D-6 E2E] A.1 factory.getProvider(config,'anthropic') → provider.type={}（应=anthropic 真实 provider）", provider.type());
        assertThat(provider).as("Anthropic 格式必须路由到真实 AnthropicSdkProvider（非 mock）")
                .isInstanceOf(AnthropicSdkProvider.class);

        String out = provider.chat(config, DEEPSEEK_MODEL, "You are a testing assistant. Answer briefly.",
                "Reply with exactly one word: PONG");
        log.info("[D-6 E2E] A.1 真机返回：provider={} model={} output={}", provider.type(), DEEPSEEK_MODEL, out);

        assertThat(out).as("真机 LLM 必须返回非空文本").isNotBlank();
        assertThat(out).as("绝不能是 MockLlmProvider 反射文本").doesNotStartWith(MOCK_PREFIX);
        assertThat(out.trim().toLowerCase())
                .as("deepseek 应真实理解指令并回复 PONG").contains("pong");
    }

    @Test
    @DisplayName("A.2 OpenAI 格式（deepseek/）：getProvider→chat 返回真实 LLM 文本（非 mock）")
    void directLlm_openaiFormat_returnsRealText() {
        // WHY（D-6）：deepseek 原生 OpenAI 兼容端点（baseUrl=https://api.deepseek.com）。
        //   OpenAiSdkProvider（官方 openai-java SDK）以 baseUrl 覆盖支持；也是 workflow 主链
        //   resolver 未注入时的默认路由（openai_sdk）——Part B 依赖此格式。
        LlmProviderFactory factory = realFactory();
        ProviderConfig config = new ProviderConfig(DEEPSEEK_OPENAI_BASE_URL, DEEPSEEK_API_KEY);

        LlmProvider provider = factory.getProvider(config, "openai_sdk");
        log.info("[D-6 E2E] A.2 factory.getProvider(config,'openai_sdk') → provider.type={}", provider.type());
        assertThat(provider).as("OpenAI 格式必须路由到真实 OpenAiSdkProvider（非 mock）")
                .isInstanceOf(OpenAiSdkProvider.class);

        String out = provider.chat(config, DEEPSEEK_MODEL, "You are a testing assistant. Answer briefly.",
                "Reply with exactly one word: PONG");
        log.info("[D-6 E2E] A.2 真机返回：provider={} model={} output={}", provider.type(), DEEPSEEK_MODEL, out);

        assertThat(out).as("真机 LLM 必须返回非空文本").isNotBlank();
        assertThat(out).as("绝不能是 MockLlmProvider 反射文本").doesNotStartWith(MOCK_PREFIX);
        assertThat(out.trim().toLowerCase())
                .as("deepseek 应真实理解指令并回复 PONG").contains("pong");
    }

    // ════════════════════════════════════════════════════════════════════
    // Part B · workflow 编排 E2E：WorkflowRunEngine → 真 adapter → 真 executor → 真 LLM
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B.1 生产路径（G-1 注册 + G-2 executor 接线）：真实脚本 phase+await agent → run_done COMPLETED + 真实 LLM 文本")
    void workflowRun_realAdapter_realExecutor_realLlm() {
        // WHY（D-6 + G-1/G-2）：完整生产委托链 WorkflowRunEngine → WorkflowHooksImpl →
        //   真 AgentAdapterRegistry（claude-code default）→ 真 ClaudeCodeBackendAdapter → 真
        //   SubagentExecutor（workflow-worker 经 G-1 自动注册命中，无手动注册）→ 真
        //   LlmAgentLoop.queryLoop → deepseek；脚本经 G-2 默认 RestrictedScriptExecutor 解释执行。
        //   链上任一环节断（G-1 注册缺 / G-2 NOT_WIRED / adapter / LLM），run 不会返回
        //   completed + 真实 LLM 文本。
        LlmProviderFactory factory = realFactory();
        // OpenAI 格式配置：Part B 主链 resolveMainProviderType 在 resolver 未注入时回落 openai_sdk，
        //   baseUrl=https://api.deepseek.com 正好命中 deepseek OpenAI 端点（A.2 已验证该端点连通）。
        ProviderConfig config = new ProviderConfig(DEEPSEEK_OPENAI_BASE_URL, DEEPSEEK_API_KEY);

        // 1) 真 AgentLoopContextFactory（queryLoop 必需 · 缺则 fail loud IllegalStateException）
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);

        // 2) 真 SubagentExecutor（最小接线：真实 factory + deepseek config + fallbackModel）
        SubagentExecutor executor = new SubagentExecutor(
                new ToolRegistry(), null, null, factory, config,
                DEEPSEEK_MODEL, "You are a workflow sub-agent.");
        executor.setContextFactory(ctxFactory);
        // [Fix-G1] 生产注册：下方 WorkflowRegistry.buildRegistry（:186）现已在构造时把
        //   ClaudeCodeBackendAdapter.WORKFLOW_AGENT 注册进 SubagentExecutor additionalAgentDefinitions，
        //   生产回落路径 resolveAgentDefinition('workflow-worker') 命中（不再 AgentNotFoundException）。
        //   本链即生产路径（对齐 CC claudeCodeBackend.ts:36-44 WORKFLOW_AGENT 定义可达性），无需测试手动注册。

        // 3) 真 adapter 注册表（claude-code default）· 对齐 WorkflowRegistry.buildRegistry（registry.ts:9-13）
        AgentAdapterRegistry registry = WorkflowRegistry.buildRegistry(executor, new AgentWorktreeManager());

        // 4) 最小 ports（真 registry + 内存 journal/registrar/emitter 桩）
        E2ePorts ports = new E2ePorts(registry);

        // 5) 生产路径（G-2 接线）：new WorkflowRunEngine()（无参，executor=null）→ parser 默认编译
        //    RestrictedScriptExecutor（受限 DSL 解释器，替换 NOT_WIRED）。真实脚本
        //    `phase('A') + return await agent('do')` 经解释器执行：phase hook → hooks.agent →
        //    真 adapter → 真 SubagentExecutor（workflow-worker G-1 自动注册命中）→ 真 LLM。
        //    不再注入 lambda executor（旧测试手动接线 = 绕过 G-2 生产路径，本测试验证生产全链）。
        WorkflowRunEngine engine = new WorkflowRunEngine();
        RunWorkflowOptions opts = new RunWorkflowOptions(
                "phase('A')\nreturn await agent('do')", null, "run-e2e-d6", "e2e", ports,
                HostHandle.create("host"), new AbortController(),
                System.getProperty("user.dir"), null, null, false, false);

        log.info("[D-6 E2E] B.1 启动生产路径 workflow run：runId=run-e2e-d6 "
                + "script='phase(\\'A\\') + return await agent(\\'do\\')' engine=new WorkflowRunEngine() "
                + "（G-2 RestrictedScriptExecutor）config.baseUrl={}", DEEPSEEK_OPENAI_BASE_URL);
        WorkflowRunResult result = engine.run(opts).join();
        log.info("[D-6 E2E] B.1 run 完成：status={} returnValue={} error={}",
                result.status(), result.returnValue(), result.error());

        assertThat(result.status())
                .as("workflow run 必须返回 completed（真 agent 成功 = 编排链+LLM 全程跑通）")
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(result.returnValue())
                .as("agent() 返回值必须是 String（真实 LLM 文本，非 null）")
                .isInstanceOf(String.class);
        String agentOut = (String) result.returnValue();
        assertThat(agentOut).as("agent 输出必须非空").isNotBlank();
        assertThat(agentOut).as("agent 输出绝不能是 MockLlmProvider 反射文本").doesNotStartWith(MOCK_PREFIX);
        log.info("[D-6 E2E] B.1 PASS：workflow completed + agent 输出真实 LLM 文本={}", agentOut);
    }

    // ════════════════════════════════════════════════════════════════════
    // 基建
    // ════════════════════════════════════════════════════════════════════

    /**
     * 真实 LlmProviderFactory · 反射注入 3 个真实 provider（工厂是 Spring @Component，字段 @Autowired
     * 无 setter；反射注入后调用<b>真实 getProvider 路由</b>，非匿名覆写）。
     */
    private static LlmProviderFactory realFactory() {
        LlmProviderFactory factory = new LlmProviderFactory();
        try {
            setField(factory, "mockLlmProvider", new MockLlmProvider());
            OpenAiSdkProvider openAi = new OpenAiSdkProvider();
            // 生产为 Spring @Resource 注入；非 Spring 测试手动注入（openai-reasoning-field 配置，缺则
            // chatWithRaw 提取 reasoning 字段时 NPE）。NexusProperties 有默认值，无需 yml。
            setField(openAi, "properties", new com.nexusai.infra.properties.NexusProperties());
            setField(factory, "openAiSdkProvider", openAi);
            setField(factory, "anthropicProvider", new AnthropicSdkProvider());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射注入 LlmProviderFactory 真实 provider 失败", e);
        }
        return factory;
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 最小 WorkflowPorts 桩：真 adapter registry + 内存 journal/registrar/emitter。 */
    static final class E2ePorts implements WorkflowPorts {
        final AgentAdapterRegistry registry;
        final E2eJournalStore journal = new E2eJournalStore();
        final E2eTaskRegistrar registrar = new E2eTaskRegistrar();
        final E2eEmitter emitter = new E2eEmitter();

        E2ePorts(AgentAdapterRegistry registry) {
            this.registry = registry;
        }

        @Override public com.nexusai.application.agent.workflow.AgentRunner agentRunner() { return null; }
        @Override public AgentAdapterRegistry agentAdapterRegistry() { return registry; }
        @Override public ProgressEmitter progressEmitter() { return emitter; }
        @Override public TaskRegistrar taskRegistrar() { return registrar; }
        @Override public JournalStore journalStore() { return journal; }
        @Override public PermissionGate permissionGate() { return host -> false; }
        @Override public WorkflowLogger logger() {
            return new WorkflowLogger() {
                @Override public void debug(String message) { }
                @Override public void warn(String message, Object... args) { }
                @Override public void event(String name, Map<String, Object> metadata) { }
            };
        }
    }

    static final class E2eJournalStore implements JournalStore {
        final Map<String, List<JournalEntry>> store = new HashMap<>();
        @Override public List<JournalEntry> read(String runId) { return new ArrayList<>(store.getOrDefault(runId, List.of())); }
        @Override public void append(String runId, JournalEntry entry) { store.computeIfAbsent(runId, k -> new ArrayList<>()).add(entry); }
        @Override public void truncate(String runId) { store.remove(runId); }
    }

    static final class E2eTaskRegistrar implements TaskRegistrar {
        @Override public RegisterResult register(RegisterOpts opts, HostHandle host) { return null; }
        @Override public void complete(String runId, String summary) { }
        @Override public void fail(String runId, String error) { }
        @Override public void kill(String runId) { }
        @Override public boolean killAgent(String runId, int agentId) { return false; }
        @Override public PendingAction pendingAction(String runId) { return null; }
        @Override public void registerAgentAbort(String runId, int agentId, AbortController abortController) { }
        @Override public void unregisterAgentAbort(String runId, int agentId) { }
    }

    static final class E2eEmitter implements ProgressEmitter {
        final List<ProgressEvent> events = new ArrayList<>();
        @Override public void emit(ProgressEvent event) { events.add(event); }
    }
}
