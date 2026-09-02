package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H13] ExecPromptHook 语义补齐测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/hooks/execPromptHook.ts (211 行)。
 *
 * <p>WHY (CLAUDE.md 规则 9): H13 补齐 3 项语义——LLM 评估器 outcome 4 态（queryModelWithoutStreaming
 * 等价）、blockingError.command 非 null（旧实现恒 null = 语义 bug, 探查 §C 4）、HookResult.message
 * 补 hook_success / hook_non_blocking_error attachment（旧实现恒 null）。
 *
 * <p>RED 证明: {@link #execPromptHook_blocking_commandNonNull} 在旧实现下失败
 * （blockingError.command 恒 null）; {@link #execPromptHook_success_messageHookSuccessAttachment}
 * 在旧实现下失败（message 恒 null）。
 */
@DisplayName("[H13] ExecPromptHook 语义补齐对齐 CC execPromptHook.ts")
class ExecPromptHookSemanticsTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String HOOK_NAME = "test-prompt-hook";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HookEvent hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");

    private LlmProvider echoProvider(String response) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                if (response == null) throw new RuntimeException("provider exploded");
                return response;
            }
        };
    }

    private LlmProvider echoProviderWithCapture(AtomicReference<String> capturedUser, String response) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                if (capturedUser != null) capturedUser.set(u);
                return response;
            }
        };
    }

    private HookResult exec(String response, String prompt, Integer timeout) {
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook(prompt, null, timeout, null, null, null);
        ExecPromptHook.PromptLlmContext ctx =
            new ExecPromptHook.PromptLlmContext(echoProvider(response), ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);
        return hook.exec(cfg, HOOK_NAME, hookEvent, "{\"tool\":\"bash\"}", ctx, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // blockingError.command 非 null · CC execPromptHook.ts:162-163 {blockingError, command: hook.prompt}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ok:false -> blocking + blockingError.command = hook.prompt（非 null）· CC :162-163")
    void execPromptHook_blocking_commandNonNull() {
        // WHY: CC blockingError 是 {blockingError, command} record, command=hook.prompt 供审计/UI 追溯
        // 触发阻塞的 hook 命令。旧 Java 传 null command（探查 §C 4 "blockingError.command=null 是语义 bug"）。
        String prompt = "deny if rm";
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook(prompt, null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx =
            new ExecPromptHook.PromptLlmContext(echoProvider("{\"ok\": false, \"reason\": \"dangerous\"}"),
                ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);

        HookResult r = hook.exec(cfg, HOOK_NAME, hookEvent, "{\"tool\":\"rm\"}", ctx, null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().command()).isEqualTo(prompt);
        assertThat(r.blockingError().blockingError())
            .isEqualTo("Prompt hook condition was not met: dangerous");
    }

    // ════════════════════════════════════════════════════════════════════════
    // HookResult.message 补 attachment · CC :121-130 / :141-150 / :175-182 / :200-209
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ok:true -> success + message=hook_success attachment（非 null）· CC :172-182")
    void execPromptHook_success_messageHookSuccessAttachment() {
        // WHY: CC success 返回 message: createAttachmentMessage({type:'hook_success', hookName, toolUseID, hookEvent, content:''})。
        // 旧 Java message 恒 null → 前端/审计拿不到 hook 成功 attachment。
        HookResult r = exec("{\"ok\": true}", "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) r.message()).type()).isEqualTo("hook_success");
        assertThat(((AttachmentMessageDto) r.message()).hookName()).isEqualTo(HOOK_NAME);
    }

    @Test
    @DisplayName("JSON 解析失败 -> non_blocking_error + message=hook_non_blocking_error attachment（CC :113-130）")
    void execPromptHook_jsonParseFailure_messageHookNonBlockingErrorAttachment() {
        // WHY: CC :118-130 返回 message: createAttachmentMessage({type:'hook_non_blocking_error', stderr:'JSON validation failed', stdout}).
        // attachment 让前端/审计能看到解析失败详情。
        HookResult r = exec("not a json at all", "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) r.message()).type()).isEqualTo("hook_non_blocking_error");
        assertThat(((AttachmentMessageDto) r.message()).content()).contains("JSON validation failed");
    }

    @Test
    @DisplayName("hook_non_blocking_error attachment 携带 stderr/stdout/exitCode（CC :121-130 三字段不丢）")
    void execPromptHook_jsonParseFailure_attachmentCarriesStdoutAndExitCode() {
        // WHY (对抗核验 H13-GAP): CC createAttachmentMessage({type:'hook_non_blocking_error', stderr, stdout, exitCode:1})
        // 三字段齐全。旧 Java hookNonBlockingError 仅 content=stderr, stdout 被丢弃, exitCode 语义由 type 承担
        // —— 审计/前端拿不到 LLM 原始响应, 无法追溯解析失败原因。
        HookResult r = exec("not a json at all", "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) r.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        // stderr 文本（CC :126）
        assertThat(att.stderr()).isEqualTo("JSON validation failed");
        // stdout = LLM 原始响应（CC :127, Java 旧实现丢弃）
        assertThat(att.stdout()).isEqualTo("not a json at all");
        // exitCode = 1（CC :128）
        assertThat(att.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("schema 校验失败 -> non_blocking_error + message=hook_non_blocking_error attachment（CC :133-150）")
    void execPromptHook_schemaFailure_messageAttachment() {
        // WHY: CC :138-150 stderr='Schema validation failed: ${error}'。
        HookResult r = exec("{\"ok\": \"yes\"}", "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) r.message()).type()).isEqualTo("hook_non_blocking_error");
        assertThat(((AttachmentMessageDto) r.message()).content()).contains("Schema validation failed");
    }

    @Test
    @DisplayName("provider 异常 -> non_blocking_error + message=hook_non_blocking_error attachment（CC :194-209）")
    void execPromptHook_error_messageAttachment() {
        // WHY: CC :197-209 stderr='Error executing prompt hook: ${error}'。
        HookResult r = exec(null, "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) r.message()).type()).isEqualTo("hook_non_blocking_error");
        assertThat(((AttachmentMessageDto) r.message()).content()).contains("Error executing prompt hook");
    }

    // ════════════════════════════════════════════════════════════════════════
    // LLM 评估器 outcome 4 态（queryModelWithoutStreaming 等价）· CC :118/138/154/172/186/199
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LLM 评估器 4 态: success/blocking/non_blocking_error/cancelled（CC :154-209）")
    void execPromptHook_llmEvaluator_outcome4States() {
        // WHY: CC queryModelWithoutStreaming 单轮评估返回 {ok, reason}, 按 ok/解析/超时/异常分流 4 态。
        // $ARGUMENTS 占位符必须替换后送入 LLM（CC :35 addArgumentsToPrompt）。
        // success
        assertThat(exec("{\"ok\": true}", "check $ARGUMENTS", null).outcome())
            .isEqualTo(HookOutcome.SUCCESS);
        // blocking
        HookResult blocking = exec("{\"ok\": false, \"reason\": \"dangerous\"}", "check $ARGUMENTS", null);
        assertThat(blocking.outcome()).isEqualTo(HookOutcome.BLOCKING);
        assertThat(blocking.preventContinuation()).isTrue();
        assertThat(blocking.stopReason()).contains("dangerous");
        // non_blocking_error (解析失败)
        assertThat(exec("garbage", "check $ARGUMENTS", null).outcome())
            .isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        // cancelled (timeout)
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, 1, null, null, null);
        LlmProvider slow = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                try { Thread.sleep(2000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new RuntimeException(e);
                }
                return "{\"ok\": true}";
            }
        };
        HookResult cancelled = hook.exec(cfg, HOOK_NAME, hookEvent, "{\"tool\":\"bash\"}",
            new ExecPromptHook.PromptLlmContext(slow, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null), null);
        assertThat(cancelled.outcome()).isEqualTo(HookOutcome.CANCELLED);
    }

    @Test
    @DisplayName("$ARGUMENTS 占位符替换后送入 LLM（CC :35 addArgumentsToPrompt + argumentSubstitution.ts:136）")
    void execPromptHook_argumentsPlaceholder_substituted() {
        AtomicReference<String> capturedUser = new AtomicReference<>();
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("eval input: $ARGUMENTS", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            echoProviderWithCapture(capturedUser, "{\"ok\": true}"), ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);

        hook.exec(cfg, HOOK_NAME, hookEvent, "{\"tool\":\"bash\"}", ctx, null);

        assertThat(capturedUser.get()).contains("{\"tool\":\"bash\"}");
        assertThat(capturedUser.get()).doesNotContain("$ARGUMENTS");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [对抗核验 H13-GAP-3 v3] queryModelWithoutStreaming 选项透传 · CC execPromptHook.ts:62-99
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LLM 调用经 chatWithOptions 传 outputFormat=json_schema + thinkingConfig=disabled（CC :71,87-98）")
    void execPromptHook_chatWithOptions_carriesOutputFormatAndThinkingDisabled() {
        // WHY (J.md H13-GAP-3): CC queryModelWithoutStreaming 传 outputFormat:{type:'json_schema',
        //   schema:{ok,reason}} + thinkingConfig:{type:'disabled'}。旧 Java chat() 无法表达这些选项,
        //   outputFormat 只能降级 systemPrompt 描述。现经 chatWithOptions 扩展点真实透传 provider。
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider capturing = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("chatWithOptions 必须被调用");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                capturedOptions.set(options);
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            capturing, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);

        HookResult r = hook.exec(cfg, HOOK_NAME, hookEvent, "{\"tool\":\"bash\"}", ctx, null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(capturedOptions.get()).isNotNull();
        // outputFormat = json_schema（CC :87-98）
        assertThat(capturedOptions.get().outputFormat()).isNotNull();
        assertThat(capturedOptions.get().outputFormat().type()).isEqualTo("json_schema");
        // schema 含 ok 字段（CC hookResponseSchema required:['ok']）
        assertThat(capturedOptions.get().outputFormat().schema().path("properties").has("ok")).isTrue();
        // thinkingConfig = disabled（CC :71）
        assertThat(capturedOptions.get().thinkingConfig()).isNotNull();
        assertThat(capturedOptions.get().thinkingConfig().type()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("父工具集透传 → chatWithOptions.tools 非空（CC :72 tools: toolUseContext.options.tools）")
    void execPromptHook_chatWithOptions_carriesParentTools() {
        // WHY: CC 把父 toolUseContext.options.tools 传给 queryModelWithoutStreaming, 让 LLM 评估时可感知工具集。
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider capturing = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("chatWithOptions 必须被调用");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                capturedOptions.set(options);
                return "{\"ok\": true}";
            }
        };
        // 父工具集（一个 Read 工具 stub）· 对齐 CC toolUseContext.options.tools
        com.nexusai.application.agent.tool.Tool readTool = new com.nexusai.application.agent.tool.Tool() {
            @Override public String name() { return "Read"; }
            @Override public String description() { return "Read a file"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            }
            @Override
            public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(
                    call.id(), "");
            }
        };
        List<com.nexusai.application.agent.tool.Tool> parentTools = List.of(readTool);
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            capturing, ProviderConfig.empty(), DEFAULT_FAST_MODEL, parentTools);

        hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, null);

        assertThat(capturedOptions.get()).isNotNull();
        assertThat(capturedOptions.get().tools()).isNotNull();
        assertThat(capturedOptions.get().tools().size()).isEqualTo(1);
        assertThat(capturedOptions.get().tools().get(0).path("function").path("name").asText())
            .isEqualTo("Read");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [EX-C R10] model 回落 · CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R10: hook.model 与 defaultFastModel 均空 → 回落 getSmallFastModel env 链（不产出空串进 provider）")
    void execPromptHook_blankModel_fallsBackToSmallFastModel() {
        // WHY (completion R10): CC :79 hook.model ?? getSmallFastModel() —— fast model 兜底是 env 链
        //   （ANTHROPIC_SMALL_FAST_MODEL || ANTHROPIC_DEFAULT_HAIKU_MODEL || claude-haiku-4-5-20251001，
        //   model.ts:36-38）。旧 Java modelOrFallback 在 defaultFastModel==\"\" 时产出空串进
        //   provider.chatWithOptions（无守卫）。HookRegistry:2377-2382 空模型守卫属 EX-HOOK 域，
        //   本层保证 exec 直连路径模型名非空。
        AtomicReference<String> capturedModel = new AtomicReference<>();
        LlmProvider capturing = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("chatWithOptions 必须被调用");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                capturedModel.set(m);
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check", null, null, null, null, null);  // hook.model = null
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            capturing, ProviderConfig.empty(), "", null);   // defaultFastModel = 空串

        HookResult r = hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        // 模型名非空且等于 getSmallFastModel env 链解析值（与 SkillImprovementHook 同实现）
        assertThat(capturedModel.get()).as("hook.model/defaultFastModel 均空 → 不得产出空串进 provider")
            .isNotBlank();
        assertThat(capturedModel.get()).isEqualTo(SkillImprovementHook.getSmallFastModel());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S2] H2 messages prepend / CCJ-EXEC-12 reason:null / CCJ-EXEC-15 回退文本 /
    //   CCJ-EXEC-11 systemPrompt / E8 父监听器清理
    // ════════════════════════════════════════════════════════════════════════

    private static com.nexusai.model.session.dto.ChatMessageDto userMsg(String content) {
        return new com.nexusai.model.session.dto.ChatMessageDto(
            "msg-" + content.hashCode(), null, com.nexusai.model.session.dto.Role.user, "user",
            content, null, null, null, null, null, null, null, null, null, null,
            List.of(), List.of());
    }

    @Test
    @DisplayName("[H2/CCJ-EXEC-01] messages 历史 prepend：history==messages、userMessage==processedPrompt（CC :44-48）")
    void execPromptHook_messagesPrepend_historyCarriesMessages() {
        // WHY: CC :45-48 `messages && messages.length > 0 ? [...messages, userMessage] : [userMessage]` —
        //   Stop hook 评估必须携带会话上下文（旧 Java history=List.of() 丢弃 → 判定结果可不同）。
        //   Java 契约：ChatRequestOptions.history = messages（provider 侧追加 userMessage 为最后一条，
        //   OpenAiSdkProvider.chatWithOptions :364-370 已核验）。
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        LlmProvider capturing = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("chatWithOptions 必须被调用");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                capturedOptions.set(options);
                capturedUser.set(u);
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("eval: $ARGUMENTS", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            capturing, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);
        List<com.nexusai.model.session.dto.ChatMessageDto> prior =
            List.of(userMsg("prior-1"), userMsg("prior-2"));

        HookResult r = hook.exec(cfg, HOOK_NAME, hookEvent, "{\"tool\":\"bash\"}", ctx, null, prior);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        // history == messages（prepend 前缀；provider 追加 userMessage 在最后 — 见 OpenAiSdkProvider 契约）
        assertThat(capturedOptions.get().history()).isEqualTo(prior);
        // userMessage == processedPrompt（$ARGUMENTS 已替换）
        assertThat(capturedUser.get()).contains("{\"tool\":\"bash\"}");
        assertThat(capturedUser.get()).doesNotContain("$ARGUMENTS");
    }

    @Test
    @DisplayName("[H2/CCJ-EXEC-01] messages 为 null/空 → history 空（仅 userMessage，CC :46 单条分支）")
    void execPromptHook_messagesNull_historyEmpty() {
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider capturing = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("chatWithOptions 必须被调用");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                capturedOptions.set(options);
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            capturing, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);

        hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, null, null);   // 6 参入口等价 null

        assertThat(capturedOptions.get().history()).isEmpty();
    }

    @Test
    @DisplayName("[CCJ-EXEC-12] {\"ok\":true,\"reason\":null} → schema 校验失败 → non_blocking_error（zod optional 拒绝 null）")
    void execPromptHook_reasonNull_schemaRejected() {
        // WHY: hookHelpers.ts:16-24 z.string().optional() —— zod optional 不接受 null →
        //   safeParse 失败 → non_blocking_error（CC :133-151）。旧 Java isNull() 放行 → success。
        HookResult r = exec("{\"ok\": true, \"reason\": null}", "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) r.message()).content()).contains("Schema validation failed");
    }

    @Test
    @DisplayName("[CCJ-EXEC-15] {\"ok\":false} 无 reason → blockingError '…: undefined' + stopReason=null（CC 模板字面量）")
    void execPromptHook_blockingNoReason_usesUndefined() {
        // WHY: CC :162 `${parsed.data.reason}` 模板字面量在 reason=undefined 时拼 "undefined"；
        //   :166 stopReason = parsed.data.reason = undefined（null）。旧 Java 兜底
        //   'Prompt hook condition was not met' 文本 + stopReason 有值（文本错位）。
        HookResult r = exec("{\"ok\": false}", "check $ARGUMENTS", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.BLOCKING);
        assertThat(r.blockingError().blockingError())
            .isEqualTo("Prompt hook condition was not met: undefined");
        assertThat(r.stopReason()).isNull();
        assertThat(r.preventContinuation()).isTrue();   // prompt blocking 显式 true（CC :165）
    }

    @Test
    @DisplayName("[CCJ-EXEC-11] systemPrompt 与 CC :64-70 逐字一致（无 'Return ONLY the JSON object' 附加句）")
    void execPromptHook_systemPrompt_noExtraSentence() {
        AtomicReference<String> capturedSystem = new AtomicReference<>();
        LlmProvider capturing = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("chatWithOptions 必须被调用");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                capturedSystem.set(s);
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            capturing, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);

        hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, null);

        assertThat(capturedSystem.get())
            .as("CCJ-EXEC-11: 无旧附加句（JSON 约束由 outputFormat json_schema 承担）")
            .doesNotContain("Return ONLY the JSON object")
            .contains("You are evaluating a hook in Claude Code.")
            .contains("{\"ok\": false, \"reason\": \"Reason for why it is not met\"}");
    }

    @Test
    @DisplayName("[E8/CCJ-EXEC-17] exec 结束后父 abort 上不残留本 hook 监听器（CC cleanupSignal）")
    void execPromptHook_parentAbortListenerRemoved() {
        com.nexusai.application.agent.tool.AbortController parent =
            new com.nexusai.application.agent.tool.AbortController();
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            echoProvider("{\"ok\": true}"), ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);

        hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, parent);

        assertThat(parent.listenerCount())
            .as("CC execPromptHook.ts:102/:184 cleanupSignal() 必须移除父 abort 监听器（防累积）")
            .isZero();
    }
}
