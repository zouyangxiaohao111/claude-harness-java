package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.SessionReadFileStateRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [批 rfs-replay-3b] 跨进程 readFileState 恢复的<b>真入口接线</b>验证（真实 {@code LlmAgentLoop.run}）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 + 记忆「改了代码≠在真入口生效」）</b>：本批新代码如果在
 * {@code LlmAgentLoop#buildBaseToolUseContext} 里没接上，或者接上了但用的不是 doRun 已读到的那份
 * DB 历史，那么单元级用例（{@code ReadFileStateReplayTest}）<b>全绿也等于没交付</b>。
 * 本类经真实 run（mocked provider 首调 stop）钉死三件事：
 * <ol>
 *   <li><b>真入口生效</b>：run 返回后 {@code SessionReadFileStateRegistry.peek(state.sessionId())}
 *       里真的有 replay 灌进去的 entry，content = 反渲染后的 raw。</li>
 *   <li><b>不新增 DB I/O</b>：{@code listRawForTranscript} 恰好被调用 <b>1</b> 次
 *       （replay 自己再查一次就会变 2 ⇒ RED）。</li>
 *   <li><b>非续跑不产假条目</b>：历史里没有 Read 调用时表保持空（防「无脑灌空 entry」）。</li>
 * </ol>
 * ⛔ 纯单测（无 {@code @SpringBootTest}，不触碰任何真库）。
 */
class LlmAgentLoopReadFileStateReplayWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SESSION_KEY = "sess-replay-wire";

    /** provider 首调返回 stop 纯文本 → loop 正常退出（镜像 LlmAgentLoopDbHistoryInjectionTest）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    private static final class CapturingContextFactory extends AgentLoopContextFactory {
        final AtomicReference<AgentLoopContext.LoopSessionState> captured = new AtomicReference<>();

        @Override
        public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId,
                AgentLoopContext.LoopSessionState session, ApplicationEventPublisher overridePublisher) {
            captured.set(session);
            return super.forSession(streamTopic, streamSessionId, streamUserMessageId, session, overridePublisher);
        }
    }

    private LlmAgentLoop buildLoop(MessageService messageService, LlmProvider provider) {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, SESSION_KEY, "msg-1");
        return loop;
    }

    private static ChatMessageDto assistantReadUse(String id, String toolCallId, String absPath, Path cwd) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", absPath);
        ChatMessageDto dto = new ChatMessageDto(id, SESSION_KEY, Role.assistant, null, "", null,
            List.of(new ToolCallDto(toolCallId, "Read", input.toString(), null, false)),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
        return dto.withCwd(cwd.toString());
    }

    private static ChatMessageDto toolResult(String id, String toolCallId, String content, Path cwd) {
        ChatMessageDto dto = new ChatMessageDto(id, SESSION_KEY, Role.tool, null, content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            toolCallId, null, null, List.of(), List.of(), null, false, false, null);
        return dto.withCwd(cwd.toString());
    }

    private static ChatMessageDto userMsg(String id) {
        return new ChatMessageDto(id, SESSION_KEY, Role.user, null, "resume query", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
    }

    /** 用真实 ReadFileTool + 真实 mapper 产出「会落库的那份渲染文本」。 */
    private static String realRenderedReadBody(Path workspace, Path file) throws Exception {
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            null, false, "", workspace.toRealPath());
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", file.toString());
        var result = tool.execute(new ToolUseBlock("call-read-wire", "read_file", input), ctx);
        return (String) tool.mapToToolResultBlockParam(result, "call-read-wire", false).content();
    }

    @Test
    @DisplayName("真入口: 真实 run 后会话 readFileState 出现 replay entry（content=反渲染 raw）+ listRawForTranscript 只调 1 次")
    void realRun_replaysIntoSessionReadFileState_withoutExtraDbRead(@TempDir Path workspace) throws Exception {
        SessionReadFileStateRegistry.resetForTest();
        try {
            Path target = workspace.resolve("resumed.txt");
            String rawBody = "alpha\nbeta\n";
            Files.writeString(target, rawBody);
            String renderedBody = realRenderedReadBody(workspace, target);
            assertThat(renderedBody).as("真实渲染体带行号（否则本用例不构成 replay 测试）").startsWith("1\talpha");

            List<ChatMessageDto> history = List.of(
                assistantReadUse("hist-asst", "tc-hist-read", target.toString(), workspace),
                toolResult("hist-tool", "tc-hist-read", renderedBody, workspace));
            List<ChatMessageDto> raw = List.of(history.get(0), history.get(1), userMsg("msg-1"));

            MessageService messageService = mock(MessageService.class);
            when(messageService.listRawForTranscript(SESSION_KEY)).thenReturn(raw);
            when(messageService.listForResumeExcluding(anyList(), anyString())).thenReturn(history);

            LlmAgentLoop loop = buildLoop(messageService, stopProvider("resume response"));

            String sessionUuid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
            AgentState state = loop.run(RunRequest.session("resume query", sessionUuid, null,
                ProviderConfig.empty(), "test-model", null, null));

            // ① 真入口生效：forSession(state.sessionId()) 那张会话表里必须有 replay 灌进去的 entry
            String sid = state.sessionId();
            assertThat(sid).as("sessionId 不得为空（否则 forSession 走 null 兜底、replay 整段跳过）").isNotBlank();
            FileStateCache sessionCache = SessionReadFileStateRegistry.peek(sid);
            assertThat(sessionCache)
                .as("真实 run 后必须能 peek 到该会话的 readFileState（跨进程 replay 的落点）").isNotNull();

            String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), target.toString());
            ToolUseContext.ReadState st = sessionCache.get(key);
            assertThat(st)
                .as("replay 必须把上一进程 Read 过的文件灌回会话表（key 必须与门禁同源）").isNotNull();
            assertThat(st.content())
                .as("replay content 必须是反渲染后的 raw（与 readFileState 存的形态一致）").isEqualTo(rawBody);
            assertThat(st.contentNotInModelContext())
                .as("Read 派生条目不置标记").isFalse();

            // ② 不新增 DB I/O：历史只被读一次（replay 自己再查 → 2 ⇒ RED）
            verify(messageService, times(1)).listRawForTranscript(SESSION_KEY);
        } finally {
            SessionReadFileStateRegistry.resetForTest();
        }
    }

    @Test
    @DisplayName("非续跑（历史无 Read 调用）⇒ 会话表保持空（不产假条目）")
    void noReadInHistory_createsNoEntry(@TempDir Path workspace) throws Exception {
        SessionReadFileStateRegistry.resetForTest();
        try {
            List<ChatMessageDto> history = List.of(userMsg("hist-user-1"));
            MessageService messageService = mock(MessageService.class);
            when(messageService.listRawForTranscript(SESSION_KEY))
                .thenReturn(List.of(userMsg("hist-user-1"), userMsg("msg-1")));
            when(messageService.listForResumeExcluding(anyList(), anyString())).thenReturn(history);

            LlmAgentLoop loop = buildLoop(messageService, stopProvider("fresh response"));
            String sessionUuid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
            AgentState state = loop.run(RunRequest.session("fresh query", sessionUuid, null,
                ProviderConfig.empty(), "test-model", null, null));

            FileStateCache sessionCache = SessionReadFileStateRegistry.peek(state.sessionId());
            if (sessionCache != null) {
                assertThat(sessionCache.size())
                    .as("历史里没有任何 Read/Write/Edit 调用 ⇒ replay 不得凭空建 entry").isZero();
            }
        } finally {
            SessionReadFileStateRegistry.resetForTest();
        }
    }
}
