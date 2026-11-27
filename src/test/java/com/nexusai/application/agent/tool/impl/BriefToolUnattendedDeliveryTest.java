package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.config.ImUnattendedConfig;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.ImSendService;
import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 无人值守会话的 SendUserMessage 必须真正投到已绑定聊天（UI 送达 ≠ 渠道送达）。 */
class BriefToolUnattendedDeliveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ImUnattendedConfig.installChecker(null);
    }

    private static ToolUseBlock call(String message) {
        return new ToolUseBlock("toolu_1", "SendUserMessage",
            MAPPER.createObjectNode().put("message", message).put("status", "normal"));
    }

    private static ToolUseContext ctx(String sessionId) {
        com.nexusai.common.SessionProjectRoot.setForSession(sessionId,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        return ToolUseContext.of(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "");
    }

    private static ImChatEntry chat(String id) {
        // ⛔ 不能在里面再调用 when(...)（嵌套 stubbing ⇒ UnfinishedStubbing）。
        return new ImChatEntry(id, "inst-1", "feishu", "peer-1", "测试聊天", "p2p", null,
            null, null, null, null, null, null, null);
    }

    @Test
    void unattendedNormalMessageIsDeliveredToBoundChats() {
        ImUnattendedConfig.installChecker(sid -> true);
        ImSendService sendService = mock(ImSendService.class);
        ImChatService imChatService = mock(ImChatService.class);
        ImChatEntry bound = chat("imch-1");
        when(imChatService.listBySession("sess-u")).thenReturn(List.of(bound));
        when(sendService.send(anyList(), anyString(), any()))
            .thenReturn(List.of(ImSendResult.sent("m-1")));
        BriefTool tool = new BriefTool();
        ReflectionTestUtils.setField(tool, "sendService", sendService);
        ReflectionTestUtils.setField(tool, "imChatService", imChatService);

        var result = tool.execute(call("hello"), ctx("sess-u"));

        verify(sendService).send(List.of("imch-1"), "hello", List.of());
        assertThat(result.data()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.data()).get("delivery")).isNotNull();
    }

    @Test
    void unattendedWithoutBoundChatReportsNoTargetInsteadOfSilentSuccess() {
        ImUnattendedConfig.installChecker(sid -> true);
        ImSendService sendService = mock(ImSendService.class);
        ImChatService imChatService = mock(ImChatService.class);
        when(imChatService.listBySession("sess-u")).thenReturn(List.of());
        BriefTool tool = new BriefTool();
        ReflectionTestUtils.setField(tool, "sendService", sendService);
        ReflectionTestUtils.setField(tool, "imChatService", imChatService);

        var result = tool.execute(call("hello"), ctx("sess-u"));

        verify(sendService, never()).send(anyList(), anyString(), any());
        assertThat(((Map<?, ?>) result.data()).get("delivery").toString()).contains("NO_TARGET");
    }

    @Test
    void interactiveSessionWithBoundChatIsDelivered() {
        ImUnattendedConfig.installChecker(sid -> false);
        ImSendService sendService = mock(ImSendService.class);
        ImChatService imChatService = mock(ImChatService.class);
        ImChatEntry bound = chat("imch-9");
        when(imChatService.listBySession("sess-i")).thenReturn(List.of(bound));
        when(sendService.send(anyList(), anyString(), any()))
            .thenReturn(List.of(ImSendResult.sent("m-9")));
        BriefTool tool = new BriefTool();
        ReflectionTestUtils.setField(tool, "sendService", sendService);
        ReflectionTestUtils.setField(tool, "imChatService", imChatService);

        var result = tool.execute(call("hello"), ctx("sess-i"));

        verify(sendService).send(List.of("imch-9"), "hello", List.of());
        assertThat(((Map<?, ?>) result.data()).get("delivery")).isNotNull();
    }

    @Test
    void interactiveSessionWithoutBoundChatReportsNoTarget() {
        ImUnattendedConfig.installChecker(sid -> false);
        ImSendService sendService = mock(ImSendService.class);
        ImChatService imChatService = mock(ImChatService.class);
        when(imChatService.listBySession("sess-i2")).thenReturn(List.of());
        BriefTool tool = new BriefTool();
        ReflectionTestUtils.setField(tool, "sendService", sendService);
        ReflectionTestUtils.setField(tool, "imChatService", imChatService);

        var result = tool.execute(call("hello"), ctx("sess-i2"));

        verify(sendService, never()).send(anyList(), anyString(), any());
        assertThat(((Map<?, ?>) result.data()).get("delivery").toString()).contains("NO_TARGET");
    }
}
