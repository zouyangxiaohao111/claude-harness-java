package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P3 · OPD-WF3-01-09] 分类器错误 errorDumpPath 生产测试 ·
 * CC yoloClassifier.ts:961-985（dumpErrorPrompts → errorDumpPath）。
 *
 * <p>WHY（意图验证）：Java 端 {@code errorDumpPath} 此前恒 null（无生产消费者，⊕ 缺口）——
 * 分类器出错时无法向用户提示 prompts 已 dump 到 /share 收集点。本测试锁定两段生产闭环：
 * <ol>
 *   <li>分类器非 abort 错误 → 结果携带 errorDumpPath 且 dump 文件真实落盘（CC :961-985）；</li>
 *   <li>abort 错误 → 不携带 errorDumpPath（CC :945-952 abort 分支不 dump）。</li>
 * </ol>
 *
 * <p>ant 通知消费（permissions.ts:704-716）在
 * {@code PermissionPipelineClassifierErrorDumpNotificationTest}（同包 PermissionPipeline 可访问
 * 包级 {@code pushClassifierErrorDumpNotification}）。
 */
@DisplayName("[P3] 分类器错误 errorDumpPath 生产（CC yoloClassifier.ts:961-985）")
class YoloClassifierErrorDumpPathTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("[P3] 2-stage 分类器错误 → 结果携带 errorDumpPath 且 dump 文件存在（CC :961-985）")
    void twoStageError_producesErrorDumpPath() throws Exception {
        YoloClassifierImpl classifier = newClassifier(new RuntimeException("classifier api exploded"));
        JsonNode input = JSON.createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(),
                newCtx(AbortController.NOOP))
                .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock())
            .as("分类器错误 → fail-closed block（CC :973-979）")
            .isTrue();
        assertThat(result.errorDumpPath())
            .as("非 abort 分类错误必须携带 errorDumpPath（CC :985 errorDumpPath 字段，旧 Java 恒 null）")
            .isNotNull();
        assertThat(Files.exists(Path.of(result.errorDumpPath())))
            .as("dump 文件必须真实落盘（CC dumpErrorPrompts writeFile :244）")
            .isTrue();
    }

    @Test
    @DisplayName("[P3] abort 分类器错误 → 不携带 errorDumpPath（CC :945-952 abort 分支无 dump）")
    void abortError_noErrorDumpPath() throws Exception {
        YoloClassifierImpl classifier = newClassifier(new RuntimeException("classifier api exploded"));
        JsonNode input = JSON.createObjectNode().put("command", "ls -la");

        // 已 abort 的 AbortController → classify 预检短路（不发起 LLM 调用）
        AbortController aborted = new AbortController();
        aborted.abort("test");
        YoloClassifierResult result = classifier.classify("Bash", input, List.of(),
                newCtx(aborted))
                .get(10, TimeUnit.SECONDS);

        assertThat(result.unavailable()).isTrue();
        assertThat(result.errorDumpPath())
            .as("CC :945-952 abort 分支不 dump → errorDumpPath undefined（Java null）")
            .isNull();
    }

    @Test
    @DisplayName("[P3 返工] catch 内 abort（CancellationException）→ 无 dump 副作用（CC :941-953 先判 abort 再 dump）")
    void catchAbortException_noDumpSideEffect() throws Exception {
        // [P3 返工] 旧实现把 dumpErrorPrompts 作为 handleStageError 参数预执行 —— abort 异常也会
        //   触发 dump 文件落盘；CC :941-953 catch 开头先判 signal.aborted → abort 分支不 dump。
        //   WHY：abort 是用户主动取消，无诊断价值，不应在 temp 目录产生 /share 收集文件。
        YoloClassifierImpl classifier = newClassifier(new CancellationException("aborted mid-call"));
        JsonNode input = JSON.createObjectNode().put("command", "ls -la");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // 未 abort 的 NOOP 控制器 → 走完整分类链，provider 在调用中抛 CancellationException
        YoloClassifierResult result = classifier.classify("Bash", input, List.of(),
                newCtx(AbortController.NOOP, sessionId))
                .get(10, TimeUnit.SECONDS);

        assertThat(result.unavailable()).isTrue();
        assertThat(result.reason())
            .as("CC :947 reason 'Classifier request aborted'")
            .isEqualTo("Classifier request aborted");
        assertThat(result.errorDumpPath()).isNull();
        Path dumpFile = Path.of(NexusaiPaths.getAppTempDir(),
            "auto-mode-classifier-errors", sessionId + ".txt");
        assertThat(Files.exists(dumpFile))
            .as("CC :941-953 abort 分支不 dump → 无 dump 文件副作用")
            .isFalse();
    }

    @Test
    @DisplayName("[P3 返工] 1-stage catch 内 abort → 无 dump 副作用（CC :1261-1269 先判 abort 再 dump）")
    void singleStageCatchAbort_noDumpSideEffect() throws Exception {
        YoloClassifierImpl classifier = newClassifierSingleStage(new CancellationException("aborted mid-call (1-stage)"));
        JsonNode input = JSON.createObjectNode().put("command", "ls -la");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(),
                newCtx(AbortController.NOOP, sessionId))
                .get(10, TimeUnit.SECONDS);

        assertThat(result.unavailable()).isTrue();
        assertThat(result.errorDumpPath())
            .as("CC :1261-1269 abort 分支不带 errorDumpPath")
            .isNull();
        Path dumpFile = Path.of(NexusaiPaths.getAppTempDir(),
            "auto-mode-classifier-errors", sessionId + ".txt");
        assertThat(Files.exists(dumpFile))
            .as("CC :1261-1269 abort 分支不 dump → 无 dump 文件副作用")
            .isFalse();
    }

    // ═══════ helpers ═══════

    /** 10 参 ToolUseContext（PermissionPipelineAbortTest.newCtx 同款）。 */
    private static ToolUseContext newCtx(AbortController abortController) {
        return newCtx(abortController, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    /** 10 参 ToolUseContext + 显式 sessionId（dump 文件名校验用；注意 record 首字段为 agentId）。 */
    private static ToolUseContext newCtx(AbortController abortController, String sessionId) {
        return new ToolUseContext(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", abortController, List.of(),
            null, PermissionMode.DEFAULT);
    }

    /** 构造真实 YoloClassifierImpl（2-stage）· [usage-gateway] 分类器经 sideCallClient 发调用。 */
    private static YoloClassifierImpl newClassifier(Throwable callFailure) {
        return newClassifier(callFailure, true);
    }

    /** 构造真实 YoloClassifierImpl（1-stage 回退 · OPD-WF6-01-RV 默认无 twoStageClassifier 配置）。 */
    private static YoloClassifierImpl newClassifierSingleStage(Throwable callFailure) {
        return newClassifier(callFailure, false);
    }

    /** 构造真实 YoloClassifierImpl · twoStage 开关（CC isTwoStageClassifierEnabled()）。
     *  [usage-gateway] 旁路调用收口后：桩 {@code sideCallClient.message(...)} 抛指定异常
     *  （等价旧 fake provider 的 chatWithRaw/chatWithOptionsMessage 抛异常）。 */
    private static YoloClassifierImpl newClassifier(Throwable callFailure, boolean twoStage) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        // provider 解析保留（fail-closed 前置守卫）→ 返回非 null mock；真实调用已收口 sideCallClient
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.nullable(String.class)))
            .thenReturn(Mockito.mock(LlmProvider.class));
        com.nexusai.application.agent.cost.LlmSideCallClient sideCallClient =
            Mockito.mock(com.nexusai.application.agent.cost.LlmSideCallClient.class);
        Mockito.when(sideCallClient.message(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any(), Mockito.any(), Mockito.any())).thenThrow(callFailure);
        YoloClassifierImpl classifier = new YoloClassifierImpl(
            new YoloPromptBuilder(),
            new YoloTokenEstimator(),
            factory,
            "claude-fast",
            30,
            resolver,
            twoStage);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "sideCallClient", sideCallClient);
        // [OPD-24 G1] 注入 Bash stub 投影（否则 action 为空 → 短路 ALLOW 不进 LLM 错误路径）
        classifier.setToolRegistry(bashProjectionRegistry());
        return classifier;
    }

    /** Bash stub registry（CC BashTool.tsx:442-444 toAutoClassifierInput → command）。 */
    private static ToolRegistry bashProjectionRegistry() {
        ToolRegistry registry = new ToolRegistry();
        JsonNode schema = new ObjectMapper().createObjectNode();
        registry.register(new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "Tool: Bash"; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub ok");
            }
            @Override public String toAutoClassifierInput(JsonNode input) {
                return input != null && input.get("command") != null
                    ? input.get("command").asText() : "";
            }
        });
        return registry;
    }
}
