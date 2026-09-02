package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.permission.hook.SkillImprovementHook;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-M-P2-3] AwaySummaryService 服务层对齐 CC awaySummary.ts generateAwaySummary。
 *
 * <p>WHY (CLAUDE.md 规则 9): CC {@code generateAwaySummary(messages, signal)} 的 null 语义是产品核心
 * —— "while you were away" 卡片在用户回来时展示，任何失败（空 transcript / abort / API error /
 * 非 abort 异常 / 成功但 trim 后为空）都<b>不得</b>抛出或返回脏文本，必须静默降级为 null。
 * 旧静态 4 参实现（BiFunction seam + 自建 message 表示）无真实 llmInvoker，
 * 无法验证 querySource/skipCacheWrite/small-fast/thinking-disabled 契约 —— 本测试锁定
 * 两参契约 + llmInvoker 契约 + 5 类 null 语义。
 */
@DisplayName("[IMP-M-P2-3] AwaySummaryService 对齐 CC awaySummary.ts generateAwaySummary")
class AwaySummaryServiceTest {

    private static final String SMALL_FAST = "haiku-test";
    private static final ProviderConfig CFG = ProviderConfig.empty();
    private static final String SESSION_ID = "sess-away";

    /** JUnit 注入的临时目录（SessionMemoryService 读 session memory 文件用）。 */
    @TempDir
    Path tempDir;

    // ── 辅助：构造 ChatMessageDto（17-参兼容构造器，角色 user）──
    private static ChatMessageDto msg(String content) {
        return new ChatMessageDto(UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 构造 assistant 消息（Q2 去重判定用）。 */
    private static ChatMessageDto assistantMsg(String content) {
        return new ChatMessageDto(UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 构造 subtype='away_summary' 的 system 消息（CC createAwaySummaryMessage · useAwaySummary.ts:80）。 */
    private static ChatMessageDto awaySummaryMsg(String recapText) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.system, "system", recapText, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, "away_summary");   // 21 参构造：isMeta=false, isError=false, subtype='away_summary'
    }

    /** 生成 n 条 user 消息（i 从 0..n-1）。 */
    private static List<ChatMessageDto> messages(int n) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(msg("msg-" + i));
        }
        return list;
    }

    /** 空 SessionMemoryService（无 memory 文件 → getSessionMemoryContent 返回 null）。 */
    private static SessionMemoryService emptySms(Path tempDir) {
        return new SessionMemoryService(tempDir);
    }

    /** 捕获型 stub · options/model 捕获 + 固定响应或抛出。 */
    private static LlmProvider capturingProvider(
            AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions,
            AtomicReference<String> capturedModel,
            String response,
            RuntimeException toThrow) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<ChatMessageDto> h, com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) {
                throw new UnsupportedOperationException();
            }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("away-summary 必须走 chatWithOptions");
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                if (capturedOptions != null) capturedOptions.set(options);
                if (capturedModel != null) capturedModel.set(m);
                if (toThrow != null) throw toThrow;
                return response;
            }
        };
    }

    private static AwaySummaryService service(LlmProvider provider, SessionMemoryService sms) {
        return new AwaySummaryService(provider, CFG, sms, () -> SMALL_FAST);
    }

    private static String join(AwaySummaryService s, List<ChatMessageDto> msgs, AbortController signal) {
        CompletableFuture<String> f = s.generate(msgs, signal, SESSION_ID);
        return f.join();
    }


    // ════════════════════════════════════════════════════════════════════
    // 5 类 null 语义 · CC awaySummary.ts:33-35/60-73 + messages.ts:2855
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空 transcript → null（CC awaySummary.ts:33-35）· 不调 LLM")
    void emptyMessages_null() {
        // WHY: 无对话内容时 recap 无意义；CC messages.length===0 提前返回 null，绝不触达 LLM。
        AtomicReference<LlmProvider.ChatRequestOptions> captured = new AtomicReference<>();
        AwaySummaryService s = service(
            capturingProvider(captured, null, "unused", null), emptySms(tempDir));
        // 用不存在的 provider 也能证明未触达（join 不会因异常失败）
        String result = s.generate(List.of(), new AbortController(), SESSION_ID).join();
        assertThat(result).isNull();
        // 空列表不会进 supplyAsync → captured 恒 null
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("abort → null（CC awaySummary.ts:68）· CancellationException 无日志路径")
    void abort_cancellation_null() {
        // WHY: 用户聚焦回 terminal 时 useAwaySummary abortInFlight()（useAwaySummary.ts:64-67），
        // 服务必须静默返回 null（不展示卡片）；CC catch(err){ if APIUserAbortError||signal.aborted return null }。
        AbortController signal = new AbortController();
        signal.abort();
        // provider 预检 abort（claude.ts:744-745 等价）→ 抛 CancellationException
        LlmProvider aborting = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<ChatMessageDto> h, com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) {
                throw new UnsupportedOperationException();
            }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException();
            }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                if (options != null && options.abortController() != null
                        && options.abortController().isCancelled()) {
                    throw new java.util.concurrent.CancellationException(
                        "aborted (CC claude.ts:744-745)");
                }
                return "should-not-reach";
            }
        };
        AwaySummaryService s = service(aborting, emptySms(tempDir));
        assertThat(s.generate(messages(3), signal, SESSION_ID).join()).isNull();
    }

    @Test
    @DisplayName("API error（LlmApiException）→ null · CC awaySummary.ts:60-65 isApiErrorMessage")
    void apiError_llmApiException_null() {
        // WHY: CC 响应对象带 isApiErrorMessage 标志 → logForDebugging + null；Java provider 以
        // LlmApiException（HTTP 非 2xx）表达 → 服务 catch → null（卡片不展示）。
        LlmProvider failing = capturingProvider(null, null, null,
            new LlmApiException(429, java.util.Map.of(), "rate limited"));
        AwaySummaryService s = service(failing, emptySms(tempDir));
        assertThat(s.generate(messages(3), new AbortController(), SESSION_ID).join()).isNull();
    }

    @Test
    @DisplayName("非 abort 异常 → null · CC awaySummary.ts:71 generation failed")
    void nonAbortException_null() {
        // WHY: 任意其它异常（provider 内部/序列化/IO）→ logForDebugging('generation failed') + null，
        // 绝不把异常抛给触发方（前端 blur 回调无 catch 上下文）。
        LlmProvider failing = capturingProvider(null, null, null,
            new IllegalStateException("provider exploded"));
        AwaySummaryService s = service(failing, emptySms(tempDir));
        assertThat(s.generate(messages(3), new AbortController(), SESSION_ID).join()).isNull();
    }

    @Test
    @DisplayName("成功 → trim 后返回 · CC messages.ts:2855 getAssistantMessageText")
    void success_trim() {
        // WHY: CC getAssistantMessageText = join('\\n').trim()（messages.ts:2850-2857），
        // 首尾空白剥除后才返回 recap 文本。
        LlmProvider ok = capturingProvider(null, null, "  Building a memory system. Next: finish the test.  ", null);
        AwaySummaryService s = service(ok, emptySms(tempDir));
        assertThat(join(s, messages(3), new AbortController())).isEqualTo(
            "Building a memory system. Next: finish the test.");
    }

    @Test
    @DisplayName("成功但 trim 后为空 → null · CC messages.ts:2855 join.trim()||null")
    void success_trimEmpty_null() {
        // WHY: 模型返回纯空白（或仅换行）→ trim 后空串 → `|| null` 兜底返回 null（卡片不展示空内容）。
        LlmProvider blank = capturingProvider(null, null, "   \n  ", null);
        AwaySummaryService s = service(blank, emptySms(tempDir));
        assertThat(join(s, messages(3), new AbortController())).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // llmInvoker 契约 · CC awaySummary.ts:41-57 queryModelWithoutStreaming options
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("llmInvoker 契约：querySource='away_summary' + skipCacheWrite=true + thinking=disabled + small-fast")
    void llmInvoker_contract_querySourceSkipCacheWriteThinkingDisabled() {
        // WHY: OPD-M-41 —— CC queryModelWithoutStreaming options 四契约必须真实到达 provider
        // （querySource: 'away_summary' :54 / skipCacheWrite: true :56 / thinkingConfig: {type:'disabled'} :44 /
        //  model: getSmallFastModel() :49）。旧 4 参 BiFunction seam 无此契约载体。
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        AtomicReference<String> capturedModel = new AtomicReference<>();
        LlmProvider capturing = capturingProvider(capturedOptions, capturedModel, "recap text", null);
        AwaySummaryService s = service(capturing, emptySms(tempDir));

        assertThat(join(s, messages(3), new AbortController())).isEqualTo("recap text");

        LlmProvider.ChatRequestOptions options = capturedOptions.get();
        assertThat(options).isNotNull();
        // CC :54 querySource: 'away_summary'
        assertThat(options.querySource()).isEqualTo("away_summary");
        // CC :56 skipCacheWrite: true
        assertThat(options.skipCacheWrite()).isEqualTo(Boolean.TRUE);
        // CC :44 thinkingConfig: {type:'disabled'}
        assertThat(options.thinkingConfig()).isNotNull();
        assertThat(options.thinkingConfig().type()).isEqualTo("disabled");
        // CC :49 model: getSmallFastModel()
        assertThat(capturedModel.get()).isEqualTo(SMALL_FAST);
        // CC :46 tools: [] → Java tools=null（空数组 = 不调工具）
        assertThat(options.tools()).isNull();
    }

    @Test
    @DisplayName("MockLlmProvider 反射 skipCacheWrite/querySource/thinking/history（对齐 querySource 先例）")
    void mockProvider_reflects_contract() {
        // WHY: 契约经 MockLlmProvider.chatWithOptions 反射到响应尾部可观测（对齐既有
        //   outputFormat/thinkingConfig/temperature/querySource 反射先例）—— mock 不真请求 LLM，
        //   反射是测试验证契约到达 provider 的唯一载体。
        AwaySummaryService s = service(new MockLlmProvider(), emptySms(tempDir));

        String result = join(s, messages(3), new AbortController());

        assertThat(result).contains("[querySource=away_summary]");
        assertThat(result).contains("[skipCacheWrite=true]");
        assertThat(result).contains("[thinking=disabled]");
        // history = 3 条 + 1 条 prompt = 4
        assertThat(result).contains("[history=4]");
    }

    @Test
    @DisplayName("session memory 读入 prompt 前缀（CC awaySummary.ts:38 + buildAwaySummaryPrompt :18-23）")
    void memory_read_intoPrompt() throws Exception {
        // WHY: CC getSessionMemoryContent() 读当前会话 memory（sessionMemoryUtils.ts:110-126），
        // memory 非空 → prompt 前缀 "Session memory (broader context):\n{memory}\n\n"（:19-21）。
        Path memoryFile = tempDir.resolve(SESSION_ID).resolve("session-memory").resolve("summary.md");
        Files.createDirectories(memoryFile.getParent());
        Files.writeString(memoryFile, "TEST MEMORY CONTENT");

        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider capturing = capturingProvider(capturedOptions, null, "recap", null);
        AwaySummaryService s = service(capturing, new SessionMemoryService(tempDir));

        assertThat(join(s, messages(3), new AbortController())).isEqualTo("recap");

        List<ChatMessageDto> history = capturedOptions.get().history();
        ChatMessageDto promptMsg = history.get(history.size() - 1);
        assertThat(promptMsg.role()).isEqualTo(Role.user);
        assertThat(promptMsg.content()).isEqualTo(AwaySummaryService.buildAwaySummaryPrompt("TEST MEMORY CONTENT"));
        assertThat(promptMsg.content()).contains("Session memory (broader context):\nTEST MEMORY CONTENT\n\n");
    }

    @Test
    @DisplayName("RECENT_MESSAGE_WINDOW=30 截断（CC awaySummary.ts:16/:39-40 slice(-30)）")
    void recentWindow_slice30() {
        // WHY: CC 防止大 session prompt too long —— 只取最近 30 条 + 追加 prompt = 31 条入 LLM。
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider capturing = capturingProvider(capturedOptions, null, "recap", null);
        AwaySummaryService s = service(capturing, emptySms(tempDir));

        join(s, messages(35), new AbortController());

        List<ChatMessageDto> history = capturedOptions.get().history();
        assertThat(history.size()).isEqualTo(31);
        // 第 0 条 = 原第 5 条（35-30=5）
        assertThat(history.get(0).content()).isEqualTo("msg-5");
        assertThat(history.get(30).role()).isEqualTo(Role.user); // 最后一条 = prompt
    }

    // ════════════════════════════════════════════════════════════════════
    // rev2 IMP-M-R2-P1-AS：AS-01/AS-04/D-19 对齐补充
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AS-01 模型 env 链（ANTHROPIC_SMALL_FAST_MODEL → ANTHROPIC_DEFAULT_HAIKU_MODEL → claude-haiku-4-5-20251001）")
    void smallFastModel_envChain() {
        // WHY: CC getSmallFastModel()（model.ts:36-38）= env.ANTHROPIC_SMALL_FAST_MODEL ||
        // getDefaultHaikuModel()（:131-138）= env.ANTHROPIC_DEFAULT_HAIKU_MODEL ||
        // 'claude-haiku-4-5-20251001'（configs.ts:31 firstParty）。无参公开方法读真实 env
        // （JVM 测试不可写 env），按 CC 链语义计算期望值后断言。
        String expected = System.getenv("ANTHROPIC_SMALL_FAST_MODEL");
        if (expected == null || expected.isBlank()) {
            expected = System.getenv("ANTHROPIC_DEFAULT_HAIKU_MODEL");
        }
        if (expected == null || expected.isBlank()) {
            expected = "claude-haiku-4-5-20251001";
        }
        assertThat(SkillImprovementHook.getSmallFastModel()).isEqualTo(expected);
        // 未设 env 时默认 firstParty haiku45（≠ 旧硬编码 "haiku" 字面值）
        if (System.getenv("ANTHROPIC_SMALL_FAST_MODEL") == null
                && System.getenv("ANTHROPIC_DEFAULT_HAIKU_MODEL") == null) {
            assertThat(SkillImprovementHook.getSmallFastModel())
                .isEqualTo("claude-haiku-4-5-20251001");
        }
    }

    @Test
    @DisplayName("AS-01 生产 bean smallFastModelSupplier 接共享 env 链（ToolRegistrationConfig 非硬编码 'haiku'）")
    void productionBean_smallFastModel_sharedChain() throws Exception {
        // WHY: OPD-R2-AS-01 —— 生产 bean 旧接线 `() -> "haiku"`（ToolRegistrationConfig.java:851）
        // 硬编码字面值，真实 provider 下模型名非法 → away-summary 恒静默 null；rev2 改接
        // SkillImprovementHook.getSmallFastModel() 共享 env 链。本测试构造生产 bean（实参形态
        // 对齐 ToolRegistrationConfig.awaySummaryService：无 MDC sessionId 后 4 参）并反射
        // smallFastModelSupplier，验证接线 = 共享链且非字面值。
        com.nexusai.application.agent.config.ToolRegistrationConfig cfg =
            new com.nexusai.application.agent.config.ToolRegistrationConfig();
        com.nexusai.application.agent.memory.AwaySummaryService away = cfg.awaySummaryService(
            org.mockito.Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
            new SessionMemoryService(tempDir), null, null, null, null);
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<String> smallFast = (java.util.function.Supplier<String>)
            org.springframework.test.util.ReflectionTestUtils.getField(away, "smallFastModelSupplier");
        assertThat(smallFast).isNotNull();
        assertThat(smallFast.get()).isEqualTo(SkillImprovementHook.getSmallFastModel());
        assertThat(smallFast.get()).isNotEqualTo("haiku");
    }

    @Test
    @DisplayName("AS-04 abort 后任何异常 → null 静默（CC awaySummary.ts:68 signal.aborted 双条件）")
    void abortedSignal_anyError_null() {
        // WHY: CC catch 内 `err instanceof APIUserAbortError || signal.aborted` —— 任何错误 +
        // signal.aborted → 静默 null（无日志）；Java 侧 catch(Exception) 必须检查
        // signal.isCancelled()，否则 abort 状态下的非 CancellationException 异常
        // （Anthropic 路径包装面 / LlmApiException）会落入 generation-failed / API-error 日志分支。
        AbortController signal = new AbortController();
        signal.abort();
        // ① LlmApiException + aborted → 静默 null（CC :68 优先于 :60-65 API error 日志）
        LlmProvider apiFail = capturingProvider(null, null, null,
            new LlmApiException(429, java.util.Map.of(), "rate limited"));
        AwaySummaryService s1 = service(apiFail, emptySms(tempDir));
        assertThat(s1.generate(messages(3), signal, SESSION_ID).join()).isNull();
        // ② 普通异常 + aborted → 静默 null（CC :68 优先于 :71 generation failed 日志）
        LlmProvider boom = capturingProvider(null, null, null,
            new IllegalStateException("provider exploded after abort"));
        AwaySummaryService s2 = service(boom, emptySms(tempDir));
        assertThat(s2.generate(messages(3), signal, SESSION_ID).join()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-CM-25 Q2 · hasSummarySinceLastUserTurn 完成消息去重（useAwaySummary.ts:16-23/:71）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Q2 去重判定: 末条真实 user 之后存在 away_summary → true（跳过生成，useAwaySummary.ts:16-23）")
    void hasSummarySinceLastUserTurn_awaySummaryAfterUser_true() {
        // WHY: CC useAwaySummary.ts:16-23 —— 自末尾向前扫，遇到 subtype='away_summary' 的 system
        //   消息返回 true（已存在摘要，跳过）；本消息序列 user→assistant→away_summary 的
        //   末条 user 之后有摘要 → 不应重复 recap。
        List<ChatMessageDto> msgs = List.of(
            msg("u1"), assistantMsg("a1"), awaySummaryMsg("recap text"));
        assertThat(AwaySummaryService.hasSummarySinceLastUserTurn(msgs)).isTrue();
    }

    @Test
    @DisplayName("Q2 去重判定: 末条 user 之后无 away_summary → false（应生成，useAwaySummary.ts:19）")
    void hasSummarySinceLastUserTurn_userAfterSummary_false() {
        // WHY: CC useAwaySummary.ts:19 —— 自末尾向前遇到真实 user（!isMeta && !isCompactSummary）
        //   返回 false（末条 user 之后无摘要 → 应生成）；本序列 user→away_summary→user
        //   的末条真实 user 之后无摘要。
        List<ChatMessageDto> msgs = List.of(
            msg("u1"), awaySummaryMsg("old recap"), msg("u2"));
        assertThat(AwaySummaryService.hasSummarySinceLastUserTurn(msgs)).isFalse();
    }

    @Test
    @DisplayName("Q2 去重判定: 无任何 away_summary → false（应生成）")
    void hasSummarySinceLastUserTurn_noSummary_false() {
        // WHY: CC useAwaySummary.ts:22 兜底 return false —— 消息流从未回插过摘要时恒应生成。
        List<ChatMessageDto> msgs = List.of(msg("u1"), assistantMsg("a1"), msg("u2"));
        assertThat(AwaySummaryService.hasSummarySinceLastUserTurn(msgs)).isFalse();
    }

    @Test
    @DisplayName("Q2 去重判定: meta / compactSummary user 消息不视为真实 user（CC :19 双排除）")
    void hasSummarySinceLastUserTurn_metaOrCompactSummaryUser_notReal() {
        // WHY: CC useAwaySummary.ts:19 `type==='user' && !isMeta && !isCompactSummary` ——
        //   meta 消息（progress）与 compactSummary 摘要 user 均非真实 user，向前扫到它们不返回
        //   false，继续找真实 user 或 away_summary。isMeta=true 的 user 之后跟 away_summary → true。
        // 21 参构造 (…, structuredOutput, isMeta, isError, subtype)：isMeta=true
        ChatMessageDto metaUser = new ChatMessageDto(
            "m1", null, Role.user, "user", "progress", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of(),
            null, true, false, null);   // structuredOutput=null, isMeta=true, isError=false, subtype=null
        // canonical 31 参构造：isCompactSummary=true（末段 true, false）
        ChatMessageDto compactSummaryUser = new ChatMessageDto(
            "m2", null, Role.user, "user", "[Compact boundary]", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of(),
            null, false, false, null, null, false, null, null, null, null, null, null, true, false);
        // compactSummaryUser 为 isCompactSummary=true（canonical 构造）—— 不视为真实 user，
        // 之后有 away_summary → true
        List<ChatMessageDto> withCompact = List.of(compactSummaryUser, awaySummaryMsg("r"));
        assertThat(AwaySummaryService.hasSummarySinceLastUserTurn(withCompact)).isTrue();
        // meta user 之后有 away_summary → true
        List<ChatMessageDto> withMeta = List.of(metaUser, awaySummaryMsg("r"));
        assertThat(AwaySummaryService.hasSummarySinceLastUserTurn(withMeta)).isTrue();
        // meta user 之后无 away_summary、也无真实 user → 扫到开头 false（CC :22 兜底）
        assertThat(AwaySummaryService.hasSummarySinceLastUserTurn(List.of(metaUser))).isFalse();
    }

    @Test
    @DisplayName("Q2 去重生效: generate 跳过 LLM 调用返回 null（已有 away_summary 自末条 user 之后）")
    void generate_skipWhenSummarySinceLastUserTurn() {
        // WHY: OPD-CM3-24 Q2 + F15 —— 后端亦应去重：前端两次 blur 连续 POST 时，第二次消息列表
        //   已含回插的 away_summary → 服务不得再调 LLM（避免重复 recap + token 浪费）。
        AtomicReference<LlmProvider.ChatRequestOptions> captured = new AtomicReference<>();
        AwaySummaryService s = service(
            capturingProvider(captured, null, "should-not-reach", null), emptySms(tempDir));

        List<ChatMessageDto> msgs = List.of(msg("u1"), awaySummaryMsg("existing recap"));
        String result = s.generate(msgs, new AbortController(), SESSION_ID).join();

        assertThat(result).as("已有 away_summary 必须跳过生成返回 null").isNull();
        assertThat(captured.get()).as("跳过生成不得触达 LLM").isNull();
    }

    @Test
    @DisplayName("Q2 去重不误伤: 末条真实 user 之后无摘要仍正常生成（LLM 被调用）")
    void generate_doesNotSkipWhenNoSummarySinceLastUserTurn() {
        // WHY: 去重仅在"已有摘要自末条 user 之后"时跳过；无摘要时正常调 LLM 返回 recap。
        AtomicReference<LlmProvider.ChatRequestOptions> captured = new AtomicReference<>();
        AwaySummaryService s = service(capturingProvider(captured, null, "recap", null), emptySms(tempDir));

        List<ChatMessageDto> msgs = List.of(
            msg("u1"), awaySummaryMsg("old recap"), assistantMsg("a1"), msg("u2"));
        String result = s.generate(msgs, new AbortController(), SESSION_ID).join();

        assertThat(result).isEqualTo("recap");
        assertThat(captured.get()).as("无摘要自末条 user 之后必须触达 LLM").isNotNull();
    }

    @Test
    @DisplayName("D-19 messages==null 契约违规 → NPE（CC readonly Message[] 恒非空）")
    void nullMessages_contractViolation_npe() {
        // WHY: CC generateAwaySummary(messages: readonly Message[]) 参数恒非空（awaySummary.ts:29-33
        // 无 null 分支）；Java 删除 `== null` 半支后传 null 为契约违规 → NPE（Java 表达）。
        AwaySummaryService s = service(
            capturingProvider(null, null, "unused", null), emptySms(tempDir));
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> s.generate(null, new AbortController(), SESSION_ID))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AS-05 显式 sessionId 单轨：memory 按参数 sessionId 读取（对齐 CC 无参读当前会话经调用方注入）")
    void explicitSessionId_singleTrack_memoryRead() throws Exception {
        // WHY: OPD-R2-AS-05 —— REST 载体 resolveSessionId（body→query→MDC）必须同时驱动
        // listBySession 与 memory 读（消除 MDC supplier 双轨）；generate 显式 sessionId
        // 参数 = 调用方注入「当前会话」的 Java 表达（CC getSessionMemoryContent() 无参）。
        String otherSession = "sess-other";
        Path memoryFile = tempDir.resolve(otherSession).resolve("session-memory").resolve("summary.md");
        Files.createDirectories(memoryFile.getParent());
        Files.writeString(memoryFile, "OTHER SESSION MEMORY");

        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider capturing = capturingProvider(capturedOptions, null, "recap", null);
        AwaySummaryService s = service(capturing, new SessionMemoryService(tempDir));

        assertThat(join(s, messages(3), new AbortController())).isEqualTo("recap");
        // 默认 SESSION_ID 无 memory 文件 → prompt 无 memory 前缀（原 SESSION_ID 路径未写）
        List<ChatMessageDto> history = capturedOptions.get().history();
        ChatMessageDto promptMsg = history.get(history.size() - 1);
        assertThat(promptMsg.content()).doesNotContain("Session memory");

        // 显式传入 otherSession → memory 读该会话
        String recap = s.generate(messages(3), new AbortController(), otherSession).join();
        assertThat(recap).isEqualTo("recap");
        List<ChatMessageDto> history2 = capturedOptions.get().history();
        ChatMessageDto promptMsg2 = history2.get(history2.size() - 1);
        assertThat(promptMsg2.content()).contains("Session memory (broader context):\nOTHER SESSION MEMORY\n\n");
    }
}

