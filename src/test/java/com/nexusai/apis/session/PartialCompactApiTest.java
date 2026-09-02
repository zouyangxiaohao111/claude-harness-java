package com.nexusai.apis.session;

import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.PartialCompactService;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [OD-14 D-1] POST /api/v1/sessions/{sessionId}/partial-compact REST 端点测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 端点是 CC REPL.tsx:4918-4972
 * onSummarize 语义的 REST 载体（对齐 AwaySummaryController 模式）。本测试锁定<b>端点语义</b>：
 * <ol>
 *   <li><b>200 成功</b>——重组后消息列表（boundary 在首位）+ 新 conversationId 非空；若返回
 *       缺 conversationId → 前端 setConversationId 无法触发 row key 刷新（F10）。</li>
 *   <li><b>404</b>——messageId 不在剥离后 active 列表（snipped/pre-compact，REPL.tsx:4923-4930）。</li>
 *   <li><b>400</b>——nothing_to_summarize（compact.ts:802-808，up_to 选首条）+ @Valid 空 messageId。</li>
 *   <li><b>真实服务链</b>——注入真实 PartialCompactService（mock MessageService/SessionService +
 *       StreamCompactSummary），证明端点不是空壳，而是驱动完整编排（剥离→pivot→摘要→重组→写回）。</li>
 * </ol>
 */
@DisplayName("[OD-14 D-1] ChatController POST /api/v1/sessions/{sessionId}/partial-compact")
class PartialCompactApiTest {

    private static final String SESSION = "00000000-0000-0000-0000-00000000000b";
    private static final String URL = "/api/v1/sessions/" + SESSION + "/partial-compact";

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static List<ChatMessageDto> fourMessages() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(msg("u0", Role.user));
        list.add(msg("a0", Role.assistant));
        list.add(msg("u1", Role.user));
        list.add(msg("a1", Role.assistant));
        return list;
    }

    /** 构造端点全链：真实 ChatController + 真实 PartialCompactService（mock 依赖）+
     *  mock StreamCompactSummary（fake 摘要生产）→ 证明端点驱动完整编排。 */
    private MockMvc mockMvc(List<ChatMessageDto> sessionMessages, String summaryText) {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(sessionMessages);
        when(messageService.replaceSessionMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [IMP-CM-14 F02] summarize 返回 SummaryResult（text + usage）；mock 摘要 usage=null
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new com.nexusai.application.agent.compact.CompactConversation.SummaryResult(summaryText, null));

        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "partialCompactService",
            new PartialCompactService(messageService, sessionService, summary));
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("200：from 选 u1 → 重组列表（boundary 首位 + keep 在 summary 前）+ 非空 conversationId")
    void success_fromReorg() throws Exception {
        mockMvc(fourMessages(), "summary ok")
            .perform(post(URL)
                .contentType("application/json")
                .content("{\"messageId\":\"u1\",\"direction\":\"from\"}"))
            .andExpect(status().isOk())
            // boundary 在首位（REPL.tsx:4952 postCompact[0]）
            .andExpect(jsonPath("$.messages[0].subtype").value("compact_boundary"))
            // from：keep 在 summary 之前（REPL.tsx:4950-4951）
            .andExpect(jsonPath("$.messages[1].id").value("u0"))
            .andExpect(jsonPath("$.messages[2].id").value("a0"))
            .andExpect(jsonPath("$.messages[3].subtype").value(CompactConversation.SUMMARY_SUBTYPE))
            // 新 conversationId 非空（REPL.tsx:4971 setConversationId(randomUUID())）
            .andExpect(jsonPath("$.conversationId").value(not(blankOrNullString())));
    }

    @Test
    @DisplayName("200：up_to 选 a1 → summary 在 keep 之前（REPL.tsx:4950-4951）")
    void success_upToReorg() throws Exception {
        mockMvc(fourMessages(), "summary ok")
            .perform(post(URL)
                .contentType("application/json")
                .content("{\"messageId\":\"a1\",\"direction\":\"up_to\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].subtype").value("compact_boundary"))
            .andExpect(jsonPath("$.messages[1].subtype").value(CompactConversation.SUMMARY_SUBTYPE))
            .andExpect(jsonPath("$.messages[2].id").value("a1"))
            .andExpect(jsonPath("$.conversationId").value(not(blankOrNullString())));
    }

    @Test
    @DisplayName("404：messageId 不在剥离后 active 列表（snipped/pre-compact，REPL.tsx:4923-4930）")
    void messageNotFound_404() throws Exception {
        mockMvc(fourMessages(), "summary ok")
            .perform(post(URL)
                .contentType("application/json")
                .content("{\"messageId\":\"nonexistent\",\"direction\":\"from\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("404：有 boundary 时选 boundary 前消息 → 剥离后不在 → 404（REPL.tsx:4921 先剥离再 indexOf）")
    void boundaryStripped_404() throws Exception {
        List<ChatMessageDto> messages = new ArrayList<>();
        messages.add(msg("u0", Role.user));
        messages.add(com.nexusai.application.agent.compact.CompactBoundaryMessage
            .createCompactBoundaryMessage("auto", 100, null, null, null).toChatMessageDto());
        messages.add(msg("u1", Role.user));
        mockMvc(messages, "summary ok")
            .perform(post(URL)
                .contentType("application/json")
                .content("{\"messageId\":\"u0\",\"direction\":\"from\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("400：up_to 选首条 u0 → nothing_to_summarize（compact.ts:802-808）")
    void upToFirst_400() throws Exception {
        mockMvc(fourMessages(), "summary ok")
            .perform(post(URL)
                .contentType("application/json")
                .content("{\"messageId\":\"u0\",\"direction\":\"up_to\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400：@Valid 空 messageId → MethodArgumentNotValidException")
    void blankMessageId_400() throws Exception {
        mockMvc(fourMessages(), "summary ok")
            .perform(post(URL)
                .contentType("application/json")
                .content("{\"messageId\":\"\",\"direction\":\"from\"}"))
            .andExpect(status().isBadRequest());
    }
}
