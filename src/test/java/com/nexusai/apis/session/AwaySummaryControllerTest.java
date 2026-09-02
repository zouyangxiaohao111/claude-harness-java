package com.nexusai.apis.session;

import com.nexusai.application.agent.memory.AwaySummaryService;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [ODF-B1] AwaySummaryController REST 端点测试（POST /api/agent/away-summary）。
 *
 * <p>WHY (CLAUDE.md 规则 9 · 测试验证意图): CC useAwaySummary.ts 触发层在前端 REPL
 * （blur 5min + feature('AWAY_SUMMARY') + flag 'tengu_sedge_lantern' 默认 false），Web 后端无
 * blur/focus —— OPD-M-39 登记「触发层 N/A 待前端同步」，owner 2026-08-06 拍板补 REST 载体
 * （ODF-B1）。本测试锁定<b>端点语义</b>:
 * <ol>
 *   <li><b>成功 → 200 + recap 文本</b>——前端 blur 后拿文本回插 away_summary 系统消息
 *       （useAwaySummary.ts:80 createAwaySummaryMessage）；若 200 但 body 缺文本 → 前端无法回插。</li>
 *   <li><b>空 transcript / LLM 失败 → 204 空体不抛 500</b>——CC awaySummary.ts:33-35（空→null）
 *       与 :60-73（API error / abort / 异常→null）语义经 REST 表达：null → 204。若空/失败抛 500
 *       → 前端 blur 回来自动请求会污染错误上报，违背 CC 静默降级。</li>
 *   <li><b>端点真实驱动 AwaySummaryService.generate 走 chatWithOptions 契约</b>——捕获型
 *       provider 验证 querySource='away_summary' / skipCacheWrite=true（IMP-M-P2-3 / OPD-M-41），
 *       证明 REST 不是空壳，而是服务层契约的载体。</li>
 *   <li><b>sessionId 源 = 请求优先 + MDC 兜底</b>——ODF-B1R（2026-08-07）改造：CC 触发层在前端 REPL，
 *       会话上下文由前端持有（useAwaySummary.ts 内 messages 即前端侧），故 Web 前端 POST 时可随请求
 *       传 sessionId（body JSON {@code {"sessionId": "..."}} 或 query {@code ?sessionId=...}，请求优先）；
 *       请求未传时兜底 {@link RequestContext}（MDC），与 ToolRegistrationConfig:682 注入一致；
 *       请求与 MDC 双空 → 500 fail loud（对齐 CommandController executeResume 同语义）。</li>
 * </ol>
 */
@DisplayName("[ODF-B1] AwaySummaryController POST /api/agent/away-summary")
class AwaySummaryControllerTest {

    /** 构造 ChatMessageDto（17-参兼容构造器，角色 user）。 */
    private static ChatMessageDto msg(String content) {
        return new ChatMessageDto(UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 生成 n 条 user 消息（i 从 0..n-1）。 */
    private static List<ChatMessageDto> messages(int n) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(msg("msg-" + i));
        }
        return list;
    }

    /** 捕获型 provider · options 捕获 + 固定响应或抛出（对齐 AwaySummaryServiceTest 模式）。 */
    private static LlmProvider capturingProvider(
            AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions,
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
                if (toThrow != null) throw toThrow;
                return response;
            }
        };
    }

    @TempDir
    Path tempDir;

    private MessageService messageService;
    private AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        capturedOptions = new AtomicReference<>();
    }

    @AfterEach
    void tearDown() {
        // 测试设置了 MDC sessionId，清理避免线程复用泄漏（对齐 CommandControllerBuiltInCommandsTest）
        RequestContext.clear();
    }

    /**
     * 构造端点全链：真实 AwaySummaryService（对齐生产 bean：sessionId=MDC + smallFast=haiku）
     * + stub provider + mock MessageService —— 证明端点驱动服务层走真实 chatWithOptions 契约。
     */
    private MockMvc mockMvc(LlmProvider provider) {
        AwaySummaryService service = new AwaySummaryService(
            provider, ProviderConfig.empty(), new SessionMemoryService(tempDir), () -> "haiku");
        AwaySummaryController controller = new AwaySummaryController();
        ReflectionTestUtils.setField(controller, "awaySummaryService", service);
        ReflectionTestUtils.setField(controller, "messageService", messageService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("成功：listBySession 3 条 → 200 + recap 文本 + chatWithOptions 契约（querySource/skipCacheWrite）")
    void success_returnsRecapAndContract() throws Exception {
        // WHY: 前端 blur 5min 后 POST 拿 recap 文本回插 away_summary 系统消息（useAwaySummary.ts:80）。
        // 若 200 但 body 缺文本 → 前端无法回插；若契约未达 provider（querySource/skipCacheWrite）
        // → 侧信道查询可能写 API cache / 遥测丢失（OPD-M-41）。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000b");
        when(messageService.listForResume(anyString())).thenReturn(messages(3));

        mockMvc(capturingProvider(capturedOptions, "Building the memory system. Next: run tests.", null))
            .perform(post("/api/agent/away-summary"))
            .andExpect(status().isOk())
            .andExpect(content().string("Building the memory system. Next: run tests."));

        // 端点必须真实驱动服务层 → provider 收到契约（CC awaySummary.ts:54/:56）
        LlmProvider.ChatRequestOptions options = capturedOptions.get();
        org.assertj.core.api.Assertions.assertThat(options).isNotNull();
        org.assertj.core.api.Assertions.assertThat(options.querySource()).isEqualTo("away_summary");
        org.assertj.core.api.Assertions.assertThat(options.skipCacheWrite()).isEqualTo(Boolean.TRUE);
        // history = 3 条会话消息 + 1 条 prompt = 4
        org.assertj.core.api.Assertions.assertThat(options.history()).hasSize(4);
    }

    @Test
    @DisplayName("空 transcript（listBySession 空）→ 204 空体不抛 500（CC awaySummary.ts:33-35）")
    void emptyTranscript_204() throws Exception {
        // WHY: CC messages.length===0 提前返回 null（awaySummary.ts:33-35）——无对话内容时 recap
        // 无意义；REST 用 204 表达 null，若抛 500 会污染前端 blur 回来自动请求的错误上报。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000b");
        when(messageService.listForResume(anyString())).thenReturn(List.of());

        mockMvc(capturingProvider(capturedOptions, "unused", null))
            .perform(post("/api/agent/away-summary"))
            .andExpect(status().isNoContent());
        // 空列表不触达 LLM → provider 未收到 options
        org.assertj.core.api.Assertions.assertThat(capturedOptions.get()).isNull();
    }

    @Test
    @DisplayName("LLM 失败（LlmApiException）→ 服务返回 null → 204 空体不抛 500（CC awaySummary.ts:60-65）")
    void llmFailure_204() throws Exception {
        // WHY: CC isApiErrorMessage → logForDebugging + null（awaySummary.ts:60-65）——模型/网关
        // 暂时不可用不得让前端 blur 请求炸 500；null 语义经 REST 表达为 204。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000b");
        when(messageService.listForResume(anyString())).thenReturn(messages(3));

        mockMvc(capturingProvider(capturedOptions, null,
                new LlmApiException(429, java.util.Map.of(), "rate limited")))
            .perform(post("/api/agent/away-summary"))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("请求与 MDC 双空（无 body/query sessionId + 无 MDC）→ 500 fail loud（对齐 CommandController executeResume）")
    void noSession_500() throws Exception {
        // WHY: ODF-B1R sessionId 源 = 请求优先 + MDC 兜底；请求未传 sessionId 且 MDC 无会话上下文 →
        // 双空显式失败暴露（规则十二），与 CommandController executeResume 无 sessionId →
        // IllegalStateException → 500 同语义。
        RequestContext.clear();
        when(messageService.listForResume(anyString())).thenReturn(messages(3));

        mockMvc(capturingProvider(capturedOptions, "recap", null))
            .perform(post("/api/agent/away-summary"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("请求体 body 传 sessionId（无 MDC）→ 200 + listBySession 用请求 sessionId")
    void requestBodySessionId_used() throws Exception {
        // WHY (ODF-B1R): CC useAwaySummary.ts 触发层在前端 REPL，会话上下文由前端持有；Web 前端
        // POST 必须能随请求携带 sessionId（后端 MDC 仅在请求处于会话链路内时可用）。若 body 传入被忽略
        // → 前端无 MDC 会话时 away-summary 不可用。
        String reqSessionId = "00000000-0000-0000-0000-0000000000aa";
        RequestContext.clear();
        when(messageService.listForResume(reqSessionId)).thenReturn(messages(3));

        mockMvc(capturingProvider(capturedOptions, "recap-body", null))
            .perform(post("/api/agent/away-summary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + reqSessionId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string("recap-body"));
        // 端点必须用请求 sessionId 驱动服务层（而非 MDC/其他）→ 消息加载按请求 sessionId 命中
        verify(messageService).listForResume(reqSessionId);
    }

    @Test
    @DisplayName("query 参数传 sessionId（无 MDC）→ 200 + listBySession 用请求 sessionId")
    void queryParamSessionId_used() throws Exception {
        // WHY (ODF-B1R): 与 body 等价的前端传参通道（§8 契约 body/query 二选一）；纯 query 无
        // Content-Type/body 场景也必须命中请求 sessionId。
        String reqSessionId = "00000000-0000-0000-0000-0000000000bb";
        RequestContext.clear();
        when(messageService.listForResume(reqSessionId)).thenReturn(messages(3));

        mockMvc(capturingProvider(capturedOptions, "recap-query", null))
            .perform(post("/api/agent/away-summary?sessionId=" + reqSessionId))
            .andExpect(status().isOk())
            .andExpect(content().string("recap-query"));
        verify(messageService).listForResume(reqSessionId);
    }

    @Test
    @DisplayName("session 不存在（listBySession 抛 NotFoundException）→ 404")
    void sessionNotFound_404() throws Exception {
        // WHY: MessageService.listBySession 校验 session 存在（MessageService.java:46-48），
        // 不存在抛 NotFoundException → REST 404（REST 语义，区别于 CC 前端内存 messages 无此场景）。
        RequestContext.setSession("00000000-0000-0000-0000-00000000dead");
        when(messageService.listForResume(anyString()))
            .thenThrow(new NotFoundException("Session 00000000-0000-0000-0000-00000000dead not found"));

        mockMvc(capturingProvider(capturedOptions, "recap", null))
            .perform(post("/api/agent/away-summary"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AS-05 body sessionId 单轨：memory 读同一请求会话（对齐 CC getSessionMemoryContent 无参读当前会话）")
    void requestBodySessionId_memoryReadSameSession() throws Exception {
        // WHY: OPD-R2-AS-05 —— resolveSessionId（body→query→MDC）此前只驱动 listBySession，
        // 服务内 memory 读恒走 MDC supplier（无 MDC 时恒 null / MDC 有其它会话时读错会话，双轨）。
        // rev2 改 generate 显式 sessionId 参数：body sessionId 必须同时驱动 memory 读（单轨），
        // 对齐 CC 无参读当前会话经调用方注入。
        String reqSessionId = "00000000-0000-0000-0000-0000000000cc";
        RequestContext.clear();
        when(messageService.listForResume(reqSessionId)).thenReturn(messages(3));

        // 写入请求 sessionId 的 memory 文件（SessionMemoryService 路径：{sessionId}/session-memory/summary.md）
        java.nio.file.Path memoryFile = tempDir.resolve(reqSessionId)
            .resolve("session-memory").resolve("summary.md");
        java.nio.file.Files.createDirectories(memoryFile.getParent());
        java.nio.file.Files.writeString(memoryFile, "REQUEST SESSION MEMORY");

        mockMvc(capturingProvider(capturedOptions, "recap-session-memory", null))
            .perform(post("/api/agent/away-summary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + reqSessionId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string("recap-session-memory"));

        // memory 读同一请求 sessionId → prompt 最后一条 user 消息带该会话 memory 前缀
        LlmProvider.ChatRequestOptions options = capturedOptions.get();
        org.assertj.core.api.Assertions.assertThat(options).isNotNull();
        java.util.List<ChatMessageDto> history = options.history();
        ChatMessageDto promptMsg = history.get(history.size() - 1);
        org.assertj.core.api.Assertions.assertThat(promptMsg.content())
            .contains("Session memory (broader context):\nREQUEST SESSION MEMORY\n\n");
    }
}
