package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions;
import com.nexusai.infra.llm.LlmProvider.LlmInvocationResult;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ContentBlockDto;
import com.nexusai.model.session.dto.MessageContentBlocks;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GoogleGeminiProvider 协议线测试（纯本地桩，⛔ 无真实 Google 请求 / 无 Spring / 无真库）。
 *
 * <p>覆盖主会话裁决必测项：多工具各不同签名、thinking/text 任意 part 签名、序列化落盘再读跨重启回放、
 * 跨模型/跨 provider 不回放、编辑/截断后旧签名不绑新内容、canonical 优先；以及信封/SSE 规范解析/
 * usage/abort/终态唯一/401 刷新一次/配额终态 vs 可重试/非流式透传。
 */
class GoogleGeminiWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String MODEL = "gemini-2.5-flash";

    // ===================== 桩基础设施 =====================

    static final class FakeResponse implements GoogleGeminiProvider.TransportResponse {
        final int status;
        final Map<String, List<String>> headers;
        final InputStream body;
        final AtomicBoolean closed = new AtomicBoolean(false);

        FakeResponse(int status, String body) {
            this(status, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }

        FakeResponse(int status, InputStream body) {
            this.status = status;
            this.headers = Map.of();
            this.body = body;
        }

        @Override public int status() { return status; }
        @Override public Map<String, List<String>> headers() { return headers; }
        @Override public InputStream body() { return body; }
        @Override public void close() {
            closed.set(true);
            try {
                body.close();
            } catch (IOException ignored) {
                // 桩关闭容错
            }
        }
    }

    static final class FakeTransport implements GoogleGeminiProvider.Transport {
        final List<GoogleGeminiProvider.TransportRequest> requests = new CopyOnWriteArrayList<>();
        final Deque<FakeResponse> responses = new ArrayDeque<>();

        FakeTransport enqueue(int status, String body) {
            responses.add(new FakeResponse(status, body));
            return this;
        }

        FakeTransport enqueue(FakeResponse r) {
            responses.add(r);
            return this;
        }

        @Override
        public GoogleGeminiProvider.TransportResponse send(GoogleGeminiProvider.TransportRequest request) {
            requests.add(request);
            FakeResponse r = responses.poll();
            if (r == null) {
                throw new IllegalStateException("桩无预备响应（请求次数超出预期）");
            }
            return r;
        }

        JsonNode lastEnvelope() throws IOException {
            return M.readTree(requests.get(requests.size() - 1).body());
        }
    }

    /** 逐字节喂入的流（模拟 SSE 跨网络块拆分）。 */
    static final class DribbleStream extends InputStream {
        private final byte[] data;
        private int pos;
        DribbleStream(String s) { this.data = s.getBytes(StandardCharsets.UTF_8); }
        @Override public int read() {
            return pos < data.length ? (data[pos++] & 0xFF) : -1;
        }
    }

    /** 阻塞流：read 挂起直到 close（abort 解阻塞验证）。 */
    static final class BlockingStream extends InputStream {
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean(false);
        @Override public int read() throws IOException {
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("blocking stream timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (closed.get()) {
                throw new IOException("stream closed");
            }
            return -1;
        }
        @Override public void close() {
            closed.set(true);
            released.countDown();
        }
    }

    static final class CapturingSink implements LlmUsageSink {
        final List<AgentUsage> usages = new CopyOnWriteArrayList<>();
        final List<LlmUsagePhase> phases = new CopyOnWriteArrayList<>();
        final List<LlmUsageSink.LlmUsageSnapshot> snaps = new CopyOnWriteArrayList<>();
        @Override
        public void record(AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase) {
            usages.add(usage);
            phases.add(phase);
            snaps.add(new LlmUsageSink.LlmUsageSnapshot(usage, source, phase));
        }
        @Override
        public List<LlmUsageSink.LlmUsageSnapshot> snapshots() {
            return List.copyOf(snaps);
        }
    }

    record Callbacks(List<String> chunks, List<String> reasoning, List<ToolUseBlock> tools,
                     List<AssistantMessage> messages, List<Throwable> errors, AtomicBoolean completed) {
        static Callbacks create() {
            return new Callbacks(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>(), new AtomicBoolean(false));
        }
    }

    private static ProviderConfig config(String project) {
        // baseUrl 故意填无关 host——生产必须锁定官方 host（测试验证）
        return new ProviderConfig("https://user-filled.example/v1", "test-token", Map.of(), Map.of(), project);
    }

    private static String sse(String... jsonEvents) {
        StringBuilder sb = new StringBuilder();
        for (String e : jsonEvents) {
            sb.append("data: ").append(e).append("\n\n");
        }
        return sb.toString();
    }

    private static String chunk(String partsJson, String finishReason, String usageJson) {
        StringBuilder sb = new StringBuilder("{\"response\":{\"candidates\":[{\"content\":{\"parts\":")
            .append(partsJson).append("}");
        if (finishReason != null) {
            sb.append(",\"finishReason\":\"").append(finishReason).append("\"");
        }
        sb.append("}]");
        if (usageJson != null) {
            sb.append(",\"usageMetadata\":").append(usageJson);
        }
        sb.append("},\"traceId\":\"trace-1\"}");
        return sb.toString();
    }

    private static ChatMessageDto user(String id, String content) {
        return new ChatMessageDto(id, "sess-1", Role.user, "user", content,
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto assistant(String id, String content, String reasoning, List<ToolCallDto> calls) {
        return new ChatMessageDto(id, "sess-1", Role.assistant, "assistant", content,
            reasoning, calls, null, null, null, null, null, null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto tool(String id, String toolCallId, String content, boolean isError) {
        return new ChatMessageDto(id, "sess-1", Role.tool, "tool", content,
            null, null, null, null, null, null, null, toolCallId, null,
            null, null, null, null, false, isError);
    }

    private static ArrayNode tools(String name) {
        ArrayNode tools = M.createArrayNode();
        ObjectNode t = tools.addObject();
        t.put("type", "function");
        ObjectNode fn = t.putObject("function");
        fn.put("name", name);
        fn.put("description", "test tool");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("command").put("type", "string");
        params.putArray("required").add("command");
        return tools;
    }

    private Callbacks runStream(GoogleGeminiProvider provider, ProviderConfig cfg, String model,
                                List<SystemPromptBlock> sys, List<ChatMessageDto> history,
                                ArrayNode tools, Integer maxTokens,
                                ChatRequestOptions.ThinkingConfig tc,
                                AbortController abort, LlmCallScope scope) {
        Callbacks cb = Callbacks.create();
        provider.stream(cfg, model, sys, history, tools, maxTokens, null, null, null, tc,
            cb.chunks()::add, cb.messages()::add, cb.tools()::add, cb.reasoning()::add,
            null, abort, cb.errors()::add, () -> cb.completed().set(true), null,
            scope, StreamIdleControl.NONE);
        return cb;
    }

    // ===================== 信封与 host =====================

    @Test
    void envelopeFieldsAndOfficialHostLocked() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
                "{\"promptTokenCount\":1,\"candidatesTokenCount\":1}")));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL,
            List.of(new SystemPromptBlock("main-system", CacheScope.NULL)),
            List.of(user("u1", "hi")), tools("Bash"), 1024, null, null, null);
        assertTrue(cb.completed.get());
        assertTrue(cb.errors().isEmpty());

        GoogleGeminiProvider.TransportRequest req = transport.requests.get(0);
        assertEquals("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", req.url());
        assertEquals("Bearer test-token", req.headers().get("Authorization"));
        assertTrue(req.headers().get("User-Agent").startsWith("GeminiCLI/0.71.1/" + MODEL));

        JsonNode env = M.readTree(req.body());
        assertEquals(MODEL, env.path("model").asText());
        assertEquals("proj-1", env.path("project").asText());
        assertFalse(env.path("user_prompt_id").asText().isBlank());
        JsonNode request = env.path("request");
        assertEquals("hi", request.path("contents").get(0).path("parts").get(0).path("text").asText());
        assertEquals("user", request.path("contents").get(0).path("role").asText());
        assertEquals("main-system", request.path("systemInstruction").path("parts").get(0).path("text").asText());
        JsonNode decl = request.path("tools").get(0).path("functionDeclarations").get(0);
        assertEquals("Bash", decl.path("name").asText());
        // 官方当前代码 parametersJsonSchema 原样透传（tools.ts:528-536），⛔ 不删字段
        assertEquals("object", decl.path("parametersJsonSchema").path("type").asText());
        assertEquals("string", decl.path("parametersJsonSchema").path("properties").path("command").path("type").asText());
        assertEquals("command", decl.path("parametersJsonSchema").path("required").get(0).asText());
        assertEquals(1024, request.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals(8192, request.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt());
        assertTrue(request.path("generationConfig").path("thinkingConfig").path("includeThoughts").asBoolean());
        assertEquals("sess-1", request.path("session_id").asText());
    }

    @Test
    void systemHistoryMessagesMergedIntoSystemInstruction() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        ChatMessageDto sysMsg = new ChatMessageDto("s1", "sess-1", Role.system, "system", "sys-extra",
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, false, false);
        runStream(provider, config("proj-1"), MODEL,
            List.of(new SystemPromptBlock("main", CacheScope.NULL)),
            List.of(sysMsg, user("u1", "hi")), null, null, null, null, null);
        JsonNode env = transport.lastEnvelope();
        assertEquals("main\n\nsys-extra",
            env.path("request").path("systemInstruction").path("parts").get(0).path("text").asText());
        // system 消息不进 contents
        for (JsonNode c : env.path("request").path("contents")) {
            assertNotEquals("system", c.path("role").asText());
        }
    }

    @Test
    void noProjectFailsFastWithoutRequest() {
        FakeTransport transport = new FakeTransport();
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config(null), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertFalse(cb.completed.get());
        assertEquals(1, cb.errors().size());
        assertTrue(cb.errors().get(0).getMessage().contains("项目初始化"));
        assertTrue(transport.requests.isEmpty(), "无 project 不得发请求");
    }

    // ===================== SSE 解析 =====================

    @Test
    void sseTextThoughtToolUsageAndSignatures() throws Exception {
        String parts1 = "[{\"text\":\"thinking hard\",\"thought\":true,\"thoughtSignature\":\"sigT\"},"
            + "{\"text\":\"Hello\",\"thoughtSignature\":\"sigX\"}]";
        String parts2 = "[{\"functionCall\":{\"name\":\"Bash\",\"args\":{\"command\":\"ls\"}},\"thoughtSignature\":\"sigF\"}]";
        FakeTransport transport = new FakeTransport().enqueue(200, sse(
            chunk(parts1, null, null),
            chunk(parts2, "STOP",
                "{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":3,\"cachedContentTokenCount\":2}")));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "run")), null, null, null, null, null);

        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        assertTrue(cb.completed.get());
        assertEquals(List.of("Hello"), cb.chunks());
        assertEquals(List.of("thinking hard"), cb.reasoning());
        assertEquals(1, cb.tools().size());
        ToolUseBlock call = cb.tools().get(0);
        assertEquals("Bash", call.name());
        assertTrue(call.id().startsWith("gcall_Bash_"), "合成 id 形态: " + call.id());
        assertEquals("ls", call.input().path("command").asText());

        AssistantMessage msg = cb.messages().get(0);
        assertEquals("tool_calls", msg.finishReason());
        assertEquals("trace-1", msg.requestId());
        assertEquals(10, msg.usage().inputTokens());
        assertEquals(8, msg.usage().outputTokens(), "output = candidates(5) + thoughts(3)");
        assertEquals(2L, msg.usage().cacheReadInputTokens());
        assertNull(msg.thinkingSignature(), "Gemini 签名专属 blocks，不经共享 thinkingSignature 字段");
        // blocks 按到达序：thinking / text / tool_use，签名封装带 provider+model 身份
        List<ContentBlockDto> blocks = msg.blocks();
        assertEquals(3, blocks.size());
        assertEquals("thinking", blocks.get(0).type());
        assertEquals("text", blocks.get(1).type());
        assertEquals("tool_use", blocks.get(2).type());
        for (ContentBlockDto b : blocks) {
            assertNotNull(b.signature());
            assertTrue(b.signature().startsWith("gca1|gemini_code_assist|" + MODEL + "|"),
                "封装身份: " + b.signature().substring(0, 30));
        }
        assertEquals("sigT", GoogleGeminiProvider.unwrapSignature(blocks.get(0).signature(), MODEL));
        assertEquals("sigX", GoogleGeminiProvider.unwrapSignature(blocks.get(1).signature(), MODEL),
            "text part 签名亦捕获");
        assertEquals("sigF", GoogleGeminiProvider.unwrapSignature(blocks.get(2).signature(), MODEL));
    }

    @Test
    void sseSplitAcrossNetworkChunks() throws Exception {
        String wire = sse(chunk("[{\"text\":\"A\"},{\"text\":\"B\"}]", "STOP",
            "{\"promptTokenCount\":1,\"candidatesTokenCount\":2}"));
        FakeTransport transport = new FakeTransport()
            .enqueue(new FakeResponse(200, new DribbleStream(wire)));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertTrue(cb.errors().isEmpty());
        assertEquals(List.of("A", "B"), cb.chunks());
        assertTrue(cb.completed.get());
    }

    @Test
    void sseMultiLineDataAndNoSpaceAndEofFlush() throws Exception {
        // 多行 data（拼接后为合法 JSON）+ data: 无空格 + EOF 无空行 flush
        String json = chunk("[{\"text\":\"X\"}]", "STOP", null);
        int split = json.indexOf(",\"traceId\""); // 顶层字段间拆分，拼接后仍为合法 JSON
        assertTrue(split > 0);
        String wire = "data: " + json.substring(0, split) + "\ndata: " + json.substring(split) + "\n\n"
            + "data:" + chunk("[{\"text\":\"Y\"}]", "STOP", null); // 末事件无尾空行
        FakeTransport transport = new FakeTransport().enqueue(200, wire);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        assertEquals(List.of("X", "Y"), cb.chunks());
        assertTrue(cb.completed.get());
    }

    @Test
    void sseMalformedJsonFailsLoudNotSuccess() {
        FakeTransport transport = new FakeTransport().enqueue(200, "data: {bad json\n\n");
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertFalse(cb.completed.get(), "坏 JSON 不得吞成成功");
        assertEquals(1, cb.errors().size());
        assertTrue(cb.messages().isEmpty());
    }

    @Test
    void sseInStreamErrorEventFailsLoud() {
        FakeTransport transport = new FakeTransport().enqueue(200,
            sse("{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertFalse(cb.completed.get());
        assertEquals(1, cb.errors().size());
        assertInstanceOf(LlmApiException.class, cb.errors().get(0));
        assertEquals(429, ((LlmApiException) cb.errors().get(0)).status());
    }

    @Test
    void streamTruncatedWithoutFinishReasonFailsLoud() {
        FakeTransport transport = new FakeTransport().enqueue(200,
            sse(chunk("[{\"text\":\"partial\"}]", null, null)));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertFalse(cb.completed.get(), "无 finishReason 的断流不得当成功");
        assertEquals(1, cb.errors().size());
    }

    // ===================== 历史回放 / 签名方案 A =====================

    @Test
    void toolBatchMergedSingleUserContentWithNamesAndOrder() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"done\"}]", "STOP", null)));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        List<ChatMessageDto> history = List.of(
            user("u1", "run"),
            assistant("a1", "", null, List.of(
                new ToolCallDto("call-1", "Bash", "{\"command\":\"ls\"}", null, null),
                new ToolCallDto("call-2", "Read", "{\"path\":\"a.txt\"}", null, null))),
            tool("t1", "call-1", "out1", false),
            tool("t2", "call-2", "out2", false),
            assistant("a2", "done", null, null),
            user("u2", "next"));
        runStream(provider, config("proj-1"), MODEL, null, history, null, null, null, null, null);
        JsonNode contents = transport.lastEnvelope().path("request").path("contents");
        assertEquals(5, contents.size(), "并行批合并单 user，不交错造 step");
        assertEquals("user", contents.get(0).path("role").asText());
        assertEquals("model", contents.get(1).path("role").asText());
        JsonNode modelParts = contents.get(1).path("parts");
        assertEquals(2, modelParts.size());
        assertEquals("Bash", modelParts.get(0).path("functionCall").path("name").asText());
        assertEquals("call-1", modelParts.get(0).path("functionCall").path("id").asText());
        assertEquals("Read", modelParts.get(1).path("functionCall").path("name").asText());
        // 首 FC 缺签名 → 官方合成令牌；第二个 FC 无签名
        assertEquals("skip_thought_signature_validator", modelParts.get(0).path("thoughtSignature").asText());
        assertFalse(modelParts.get(1).has("thoughtSignature"));
        assertEquals("user", contents.get(2).path("role").asText());
        JsonNode respParts = contents.get(2).path("parts");
        assertEquals(2, respParts.size());
        assertEquals("Bash", respParts.get(0).path("functionResponse").path("name").asText());
        assertEquals("call-1", respParts.get(0).path("functionResponse").path("id").asText());
        assertEquals("out1", respParts.get(0).path("functionResponse").path("response").path("output").asText());
        assertEquals("Read", respParts.get(1).path("functionResponse").path("name").asText());
        assertEquals("out2", respParts.get(1).path("functionResponse").path("response").path("output").asText());
        assertEquals("model", contents.get(3).path("role").asText());
        assertEquals("user", contents.get(4).path("role").asText());
        assertEquals("next", contents.get(4).path("parts").get(0).path("text").asText());
    }

    /** 落盘再读（MessageContentBlocks.toJson/fromJson = V331 content_blocks 列同通道）→ 跨重启回放。 */
    private static ChatMessageDto persistedAssistant(String id, String content, String reasoning,
                                                     List<ToolCallDto> calls, List<ContentBlockDto> blocks) {
        ChatMessageDto dto = assistant(id, content, reasoning, calls).withBlocks(blocks);
        String json = MessageContentBlocks.toJson(MessageContentBlocks.resolve(dto));
        assertNotNull(json, "blocks 必须可落库");
        List<ContentBlockDto> reloaded = MessageContentBlocks.fromJson(json);
        return assistant(id, content, reasoning, calls).withBlocks(reloaded);
    }

    @Test
    void signatureReplayAfterPersistRoundTripAndRestart() throws Exception {
        String sigA = GoogleGeminiProvider.wrapSignature("rawSigA", MODEL);
        String sigB = GoogleGeminiProvider.wrapSignature("rawSigB", MODEL);
        List<ContentBlockDto> blocks = List.of(
            ContentBlockDto.thinking("thought text", GoogleGeminiProvider.wrapSignature("sigT", MODEL), 0),
            ContentBlockDto.text("hello", 1),
            new ContentBlockDto("tool_use", null, null, sigA, "call-1", "Bash", "{\"command\":\"ls\"}", 2),
            new ContentBlockDto("tool_use", null, null, sigB, "call-2", "Read", "{\"path\":\"a\"}", 3));
        ChatMessageDto dto = persistedAssistant("a1", "hello", "thought text",
            List.of(new ToolCallDto("call-1", "Bash", "{\"command\":\"ls\"}", null, null),
                new ToolCallDto("call-2", "Read", "{\"path\":\"a\"}", null, null)),
            blocks);
        List<ChatMessageDto> history = List.of(user("u1", "run"), dto,
            tool("t1", "call-1", "out1", false), tool("t2", "call-2", "out2", false));

        // 新 provider 实例 = 重启后无进程内状态，签名只能来自落盘 blocks
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        GoogleGeminiProvider restarted = new GoogleGeminiProvider(transport);
        runStream(restarted, config("proj-1"), MODEL, null, history, null, null, null, null, null);
        JsonNode modelParts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        // thought 剥离（官方 stripThoughts）：无 thought:true part
        for (JsonNode p : modelParts) {
            assertFalse(p.path("thought").asBoolean(false), "thought part 不得回放");
        }
        assertEquals(3, modelParts.size(), "text + 2 FC");
        assertEquals("hello", modelParts.get(0).path("text").asText());
        assertEquals("rawSigA", modelParts.get(1).path("thoughtSignature").asText(), "按 id 精确回放各自签名");
        assertEquals("rawSigB", modelParts.get(2).path("thoughtSignature").asText(), "多工具各不同签名");
        assertEquals("call-1", modelParts.get(1).path("functionCall").path("id").asText());
        assertEquals("call-2", modelParts.get(2).path("functionCall").path("id").asText());
    }

    @Test
    void textPartSignatureReplayedByBlockOrder() throws Exception {
        String sigText = GoogleGeminiProvider.wrapSignature("textSig", MODEL);
        List<ContentBlockDto> blocks = List.of(
            new ContentBlockDto("text", "part1", null, sigText, null, null, null, 0),
            ContentBlockDto.text("part2", 1));
        ChatMessageDto dto = persistedAssistant("a1", "part1part2", null, null, blocks);
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto), null, null, null, null, null);
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals(2, parts.size());
        assertEquals("textSig", parts.get(0).path("thoughtSignature").asText());
        assertFalse(parts.get(1).has("thoughtSignature"));
    }

    @Test
    void crossModelSignatureNotReplayed() throws Exception {
        String foreign = GoogleGeminiProvider.wrapSignature("proSig", "gemini-2.5-pro");
        List<ContentBlockDto> blocks = List.of(
            new ContentBlockDto("tool_use", null, null, foreign, "call-1", "Bash", "{}", 0));
        ChatMessageDto dto = persistedAssistant("a1", "", null,
            List.of(new ToolCallDto("call-1", "Bash", "{}", null, null)), blocks);
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        // 当前请求 model = flash ≠ 封装内 pro → 弃签名走合成
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, tool("t1", "call-1", "o", false)),
            null, null, null, null, null);
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("skip_thought_signature_validator", parts.get(0).path("thoughtSignature").asText());
    }

    @Test
    void crossProviderSignatureNotReplayed() throws Exception {
        // Claude 原始签名（无 gca1 封装）不得被 Gemini 回放
        List<ContentBlockDto> blocks = List.of(
            new ContentBlockDto("tool_use", null, null, "claudeRawSignatureXYZ", "call-1", "Bash", "{}", 0));
        ChatMessageDto dto = persistedAssistant("a1", "", null,
            List.of(new ToolCallDto("call-1", "Bash", "{}", null, null)), blocks);
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, tool("t1", "call-1", "o", false)),
            null, null, null, null, null);
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("skip_thought_signature_validator", parts.get(0).path("thoughtSignature").asText());
    }

    @Test
    void editedContentDropsMismatchedSignature() throws Exception {
        String sig = GoogleGeminiProvider.wrapSignature("oldSig", MODEL);
        List<ContentBlockDto> blocks = List.of(
            ContentBlockDto.text("original text", 0),
            new ContentBlockDto("tool_use", null, null, sig, "call-1", "Bash", "{\"command\":\"ls\"}", 1));
        // 用户编辑了 content（与 blocks 拼接不一致）→ 弃 blocks 走 canonical，不复用旧签名
        ChatMessageDto edited = assistant("a1", "EDITED text", null,
            List.of(new ToolCallDto("call-1", "Bash", "{\"command\":\"ls\"}", null, null)))
            .withBlocks(blocks);
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), edited, tool("t1", "call-1", "o", false)),
            null, null, null, null, null);
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("EDITED text", parts.get(0).path("text").asText(), "canonical 内容优先");
        assertEquals("skip_thought_signature_validator", parts.get(1).path("thoughtSignature").asText());
    }

    @Test
    void editedArgumentsDropsMismatchedSignature() throws Exception {
        String sig = GoogleGeminiProvider.wrapSignature("oldSig", MODEL);
        List<ContentBlockDto> blocks = List.of(
            new ContentBlockDto("tool_use", null, null, sig, "call-1", "Bash", "{\"command\":\"ls\"}", 0));
        // arguments 被改（与 blocks 不一致）→ 核对失败 → 弃签名
        ChatMessageDto edited = assistant("a1", "", null,
            List.of(new ToolCallDto("call-1", "Bash", "{\"command\":\"rm -rf\"}", null, null)))
            .withBlocks(blocks);
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), edited, tool("t1", "call-1", "o", false)),
            null, null, null, null, null);
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("rm -rf", parts.get(0).path("functionCall").path("args").path("command").asText());
        assertEquals("skip_thought_signature_validator", parts.get(0).path("thoughtSignature").asText());
    }

    @Test
    void canonicalPriorityWhenNoBlocks() throws Exception {
        ChatMessageDto dto = assistant("a1", "text", "reasoning",
            List.of(new ToolCallDto("call-1", "Bash", "{}", null, null),
                new ToolCallDto("call-2", "Read", "{}", null, null)));
        assertNull(dto.blocks());
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, tool("t1", "call-1", "o", false), tool("t2", "call-2", "o", false)),
            null, null, null, null, null);
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals(3, parts.size());
        assertEquals("text", parts.get(0).path("text").asText());
        assertEquals("skip_thought_signature_validator", parts.get(1).path("thoughtSignature").asText());
        assertFalse(parts.get(2).has("thoughtSignature"), "仅首 FC 注入合成签名");
    }

    @Test
    void unansweredToolCallClosedWithSyntheticError() throws Exception {
        ChatMessageDto dto = assistant("a1", "", null,
            List.of(new ToolCallDto("call-1", "Bash", "{}", null, null)));
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, user("u2", "interrupt")),
            null, null, null, null, null);
        JsonNode contents = transport.lastEnvelope().path("request").path("contents");
        JsonNode userAfterModel = contents.get(2);
        assertEquals("user", userAfterModel.path("role").asText());
        boolean found = false;
        for (JsonNode p : userAfterModel.path("parts")) {
            if (p.has("functionResponse")) {
                assertEquals("call-1", p.path("functionResponse").path("id").asText());
                assertEquals("Response was lost or interrupted.",
                    p.path("functionResponse").path("response").path("error").asText());
                found = true;
            }
        }
        assertTrue(found, "未应答 FC 必须合成 error response 收口");
    }

    @Test
    void toolResultErrorFlagMapsToErrorResponse() throws Exception {
        ChatMessageDto dto = assistant("a1", "", null,
            List.of(new ToolCallDto("call-1", "Bash", "{}", null, null)));
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, tool("t1", "call-1", "boom", true)),
            null, null, null, null, null);
        JsonNode fr = transport.lastEnvelope().path("request").path("contents").get(2)
            .path("parts").get(0).path("functionResponse");
        assertEquals("boom", fr.path("response").path("error").asText());
        assertFalse(fr.path("response").has("output"));
    }

    @Test
    void unmatchedToolResultFailsLoud() {
        ChatMessageDto dto = assistant("a1", "", null,
            List.of(new ToolCallDto("call-1", "Bash", "{}", null, null)));
        FakeTransport transport = new FakeTransport();
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, tool("t1", "UNKNOWN-ID", "o", false)),
            null, null, null, null, null);
        assertEquals(1, cb.errors().size());
        assertTrue(cb.errors().get(0).getMessage().contains("无法配对"));
        assertTrue(transport.requests.isEmpty());
    }

    // ===================== thinking 映射 =====================

    @Test
    void thinkingMappingPerModelFamily() {
        // 2.5 Flash：disabled→0；adaptive→-1；默认 8192
        assertEquals(0, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-flash",
            ChatRequestOptions.ThinkingConfig.disabled(), null).path("thinkingBudget").asInt());
        assertEquals(-1, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-flash",
            ChatRequestOptions.ThinkingConfig.adaptive(), null).path("thinkingBudget").asInt());
        assertEquals(8192, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-flash", null, null)
            .path("thinkingBudget").asInt());
        assertEquals(4096, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-flash",
            ChatRequestOptions.ThinkingConfig.enabled(4096), null).path("thinkingBudget").asInt());
        // 2.5 Pro：⛔ disabled→0；落文档最小值 128
        assertEquals(128, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-pro",
            ChatRequestOptions.ThinkingConfig.disabled(), null).path("thinkingBudget").asInt());
        // 3 系：thinkingLevel；disabled→LOW；默认 HIGH；⛔ 不发 thinkingBudget
        JsonNode pro3 = GoogleGeminiProvider.mapThinkingConfig("gemini-3-pro-preview", null, null);
        assertEquals("HIGH", pro3.path("thinkingLevel").asText());
        assertFalse(pro3.has("thinkingBudget"));
        assertEquals("LOW", GoogleGeminiProvider.mapThinkingConfig("gemini-3-pro-preview",
            ChatRequestOptions.ThinkingConfig.disabled(), null).path("thinkingLevel").asText());
        assertEquals("HIGH", GoogleGeminiProvider.mapThinkingConfig("gemini-3.5-flash",
            ChatRequestOptions.ThinkingConfig.enabled(5000), null).path("thinkingLevel").asText());
        // effort off → disabled 等价
        assertEquals(0, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-flash", null, "off")
            .path("thinkingBudget").asInt());
        // 未知家族 → 不发
        assertNull(GoogleGeminiProvider.mapThinkingConfig("gemma-4-31b-it", null, null));
        // includeThoughts 恒 true（官方 chat-base）
        assertTrue(pro3.path("includeThoughts").asBoolean());
    }

    @Test
    void thinkingConfigExplicitOverridesEffort() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), "gemini-3-pro-preview",
            null, List.of(user("u1", "hi")), null, null,
            ChatRequestOptions.ThinkingConfig.disabled(), null, null);
        JsonNode tc = transport.lastEnvelope().path("request").path("generationConfig").path("thinkingConfig");
        assertEquals("LOW", tc.path("thinkingLevel").asText());
    }

    // ===================== 配额 / 重试 / 401 =====================

    private static String quotaBody(String reason, String quotaId, String retryDelay) {
        ObjectNode root = M.createObjectNode();
        ObjectNode err = root.putObject("error");
        err.put("code", 429);
        err.put("message", "Resource has been exhausted (e.g. check quota).");
        err.put("status", "RESOURCE_EXHAUSTED");
        ArrayNode details = err.putArray("details");
        if (reason != null) {
            ObjectNode ei = details.addObject();
            ei.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
            ei.put("reason", reason);
            ei.put("domain", "cloudcode-pa.googleapis.com");
        }
        if (quotaId != null) {
            ObjectNode qf = details.addObject();
            qf.put("@type", "type.googleapis.com/google.rpc.QuotaFailure");
            qf.putArray("violations").addObject().put("quotaId", quotaId);
        }
        if (retryDelay != null) {
            ObjectNode ri = details.addObject();
            ri.put("@type", "type.googleapis.com/google.rpc.RetryInfo");
            ri.put("retryDelay", retryDelay);
        }
        return root.toString();
    }

    @Test
    void streamTerminalDailyQuotaNoRetry() {
        FakeTransport transport = new FakeTransport()
            .enqueue(429, quotaBody(null, "GenerateContentPerDayPerProject", null));
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(1, cb.errors().size());
        assertInstanceOf(LlmApiException.class, cb.errors().get(0));
        assertTrue(((LlmApiException) cb.errors().get(0)).body().contains("今日配额"));
        assertEquals(1, transport.requests.size(), "流式不重试");
        assertFalse(cb.completed.get());
    }

    @Test
    void streamRetryableRateLimitStillNoRetry() {
        FakeTransport transport = new FakeTransport()
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "1s"));
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(1, cb.errors().size());
        assertEquals(1, transport.requests.size(), "流式不重试（官方 retry:false）");
    }

    @Test
    void nonStreamRetryableBoundedToThreeAttempts() {
        String ok = "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}";
        FakeTransport transport = new FakeTransport()
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "0.01s"))
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "0.01s"))
            .enqueue(200, ok);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        String result = provider.chat(config("proj-1"), MODEL, null, "hi");
        assertEquals("ok", result);
        assertEquals(3, transport.requests.size());
        assertTrue(transport.requests.get(0).url().endsWith(":generateContent"), "非流式端点");
        assertFalse(transport.requests.get(0).url().contains("alt=sse"));
    }

    @Test
    void nonStreamRetryExhaustionFailsLoud() {
        FakeTransport transport = new FakeTransport()
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "0.01s"))
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "0.01s"))
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "0.01s"));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> provider.chat(config("proj-1"), MODEL, null, "hi"));
        assertInstanceOf(LlmApiException.class, ex);
        assertEquals(3, transport.requests.size(), "⛔ 无限 retry");
    }

    @Test
    void nonStreamTerminalDailyNoRetry() {
        FakeTransport transport = new FakeTransport()
            .enqueue(429, quotaBody(null, "GenerateContentPerDayPerProject", null));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        assertThrows(LlmApiException.class, () -> provider.chat(config("proj-1"), MODEL, null, "hi"));
        assertEquals(1, transport.requests.size(), "终态日限立即抛");
    }

    @Test
    void quotaClassificationRules() {
        // 终态：容量耗尽 / QUOTA_EXHAUSTED / 日限 / 延迟>300s
        assertTrue(GoogleGeminiProvider.classifyQuota(429,
            quotaBody("MODEL_CAPACITY_EXHAUSTED", null, null)).terminal());
        assertTrue(GoogleGeminiProvider.classifyQuota(429,
            quotaBody("QUOTA_EXHAUSTED", null, null)).terminal());
        assertTrue(GoogleGeminiProvider.classifyQuota(429,
            quotaBody(null, "GenerateContentPerDayPerProject", null)).terminal());
        assertTrue(GoogleGeminiProvider.classifyQuota(429,
            quotaBody("RATE_LIMIT_EXCEEDED", null, "400s")).terminal());
        // 可重试：分钟限 / RATE_LIMIT_EXCEEDED≤300s
        assertFalse(GoogleGeminiProvider.classifyQuota(429,
            quotaBody("RATE_LIMIT_EXCEEDED", null, "30s")).terminal());
        GoogleGeminiProvider.QuotaClassification perMinute =
            GoogleGeminiProvider.classifyQuota(429, quotaBody(null, "GenerateContentPerMinutePerProject", null));
        assertFalse(perMinute.terminal());
        assertEquals(60_000L, perMinute.retryDelayMs());
        // 503 无详情 → 可重试兜底
        assertFalse(GoogleGeminiProvider.classifyQuota(503, "{\"error\":{\"code\":503,\"message\":\"high demand\"}}").terminal());
    }

    @Test
    void auth401RefreshOnceThenSuccess() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(401, "{\"error\":{\"code\":401,\"message\":\"Invalid credentials\"}}")
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        AtomicInteger refreshCalls = new AtomicInteger();
        provider.setProviderService(new com.nexusai.domain.provider.ProviderService() {
            @Override
            public String forceRefreshByAccessToken(String accessToken) {
                refreshCalls.incrementAndGet();
                assertEquals("test-token", accessToken);
                return "new-token";
            }
        });
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        assertTrue(cb.completed.get());
        assertEquals(1, refreshCalls.get());
        assertEquals(2, transport.requests.size());
        assertEquals("Bearer new-token", transport.requests.get(1).headers().get("Authorization"));
    }

    @Test
    void auth401RefreshFailureReloginHint() {
        FakeTransport transport = new FakeTransport()
            .enqueue(401, "{\"error\":{\"code\":401,\"message\":\"Invalid credentials\"}}");
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        provider.setProviderService(new com.nexusai.domain.provider.ProviderService() {
            @Override
            public String forceRefreshByAccessToken(String accessToken) {
                return null;
            }
        });
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(1, cb.errors().size());
        assertTrue(cb.errors().get(0).getMessage().contains("登录已失效"));
        assertEquals(1, transport.requests.size(), "刷新失败不重试");
    }

    // ===================== abort / 终态唯一 / usage 上报 =====================

    @Test
    void abortDisconnectsHttpAndTerminalOnce() throws Exception {
        BlockingStream blocking = new BlockingStream();
        FakeResponse resp = new FakeResponse(200, blocking);
        FakeTransport transport = new FakeTransport().enqueue(resp);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        AbortController abort = new AbortController();
        Callbacks cb = Callbacks.create();
        Thread worker = new Thread(() -> provider.stream(config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null, null,
            cb.chunks()::add, cb.messages()::add, cb.tools()::add, cb.reasoning()::add,
            null, abort, cb.errors()::add, () -> cb.completed().set(true), null,
            null, StreamIdleControl.NONE));
        worker.start();
        // 等请求发出后 abort
        long deadline = System.currentTimeMillis() + 5000;
        while (transport.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, transport.requests.size());
        abort.abort("interrupt");
        worker.join(5000);
        assertFalse(worker.isAlive(), "abort 必须解阻塞读循环");
        assertTrue(resp.closed.get(), "AbortController 断开 HTTP");
        assertEquals(1, cb.errors().size(), "终态唯一");
        assertInstanceOf(java.util.concurrent.CancellationException.class, cb.errors().get(0));
        assertFalse(cb.completed.get(), "abort 不触发 onComplete");
        assertTrue(cb.messages().isEmpty());
    }

    @Test
    void failureRecordsUsagePhaseAndTerminalOnce() {
        FakeTransport transport = new FakeTransport().enqueue(500,
            "{\"error\":{\"code\":500,\"message\":\"internal\"}}");
        CapturingSink sink = new CapturingSink();
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null,
            new LlmCallScope(null, sink));
        assertEquals(1, cb.errors().size(), "onError 恰好一次");
        assertFalse(cb.completed.get());
        assertEquals(1, sink.phases.size());
        assertEquals(LlmUsagePhase.FAILED, sink.phases.get(0));
    }

    @Test
    void successRecordsSettledUsage() {
        FakeTransport transport = new FakeTransport().enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
            "{\"promptTokenCount\":7,\"candidatesTokenCount\":3}")));
        CapturingSink sink = new CapturingSink();
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null,
            new LlmCallScope(null, sink));
        assertTrue(cb.completed.get());
        assertEquals(List.of(LlmUsagePhase.SETTLED), sink.phases);
        assertEquals(7, sink.usages.get(0).inputTokens());
        assertEquals(3, sink.usages.get(0).outputTokens());
    }

    // ===================== 非流式家族透传 =====================

    @Test
    void chatWithOptionsMessageToolsThinkingUsageAndInvoke() {
        String resp = "{\"response\":{\"candidates\":[{\"content\":{\"parts\":["
            + "{\"functionCall\":{\"name\":\"Bash\",\"args\":{\"command\":\"ls\"}},\"thoughtSignature\":\"nsSig\"},"
            + "{\"text\":\"thinking\",\"thought\":true}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":4,\"thoughtsTokenCount\":2}},\"traceId\":\"tr-ns\"}";
        FakeTransport transport = new FakeTransport().enqueue(200, resp);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        CapturingSink sink = new CapturingSink();
        ChatRequestOptions options = new ChatRequestOptions(
            List.of(user("u1", "earlier")), tools("Bash"), null,
            ChatRequestOptions.ThinkingConfig.disabled(), null, "test", null, 512, null);
        LlmInvocationResult result = provider.invoke(config("proj-1"), MODEL, "sys", "run",
            options, new LlmCallScope(null, sink));
        assertEquals(1, result.toolCalls().size());
        assertEquals("Bash", result.toolCalls().get(0).name());
        assertEquals("tr-ns", result.requestId());
        assertEquals(9, result.usage().inputTokens());
        assertEquals(6, result.usage().outputTokens());
        assertEquals(List.of(LlmUsagePhase.SETTLED), sink.phases);

        JsonNode env = assertDoesNotThrow(() -> transport.lastEnvelope());
        JsonNode request = env.path("request");
        assertEquals(512, request.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals(0, request.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt());
        assertEquals("Bash", request.path("tools").get(0).path("functionDeclarations").get(0).path("name").asText());
        assertEquals("sys", request.path("systemInstruction").path("parts").get(0).path("text").asText());
        // history + userMessage 追加为末条；相邻 user 按官方 coalesceConsecutiveRoles 合并为单 Content
        JsonNode contents = request.path("contents");
        assertEquals(1, contents.size());
        JsonNode mergedParts = contents.get(0).path("parts");
        assertEquals(2, mergedParts.size());
        assertEquals("earlier", mergedParts.get(0).path("text").asText());
        assertEquals("run", mergedParts.get(1).path("text").asText());
    }

    @Test
    void chatWithRawExposesUsageAndThinking() {
        String resp = "{\"response\":{\"candidates\":[{\"content\":{\"parts\":["
            + "{\"text\":\"answer\"},{\"text\":\"reason\",\"thought\":true}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"cachedContentTokenCount\":1}},\"traceId\":\"tr2\"}";
        FakeTransport transport = new FakeTransport().enqueue(200, resp);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        LlmProvider.LlmRawResponse raw = provider.chatWithRaw(config("proj-1"), MODEL, null, "hi", null);
        assertEquals("answer", raw.content());
        assertEquals("reason", raw.thinking());
        assertEquals("tr2", raw.requestId());
        assertEquals(5, raw.usage().inputTokens());
        assertEquals(2, raw.usage().outputTokens());
        assertEquals(1, raw.usage().cacheReadInputTokens());
    }

    @Test
    void toolChoiceMapsToFunctionCallingConfig() {
        String resp = "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}";
        FakeTransport transport = new FakeTransport().enqueue(200, resp);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        ChatRequestOptions options = new ChatRequestOptions(
            List.of(), tools("Bash"), null, null, null, "test", null, null, null,
            null, null, null, null, null,
            ChatRequestOptions.ToolChoice.tool("Bash"), null);
        provider.chatWithOptionsMessage(config("proj-1"), MODEL, null, "hi", options);
        JsonNode fc = assertDoesNotThrow(() -> transport.lastEnvelope())
            .path("request").path("toolConfig").path("functionCallingConfig");
        assertEquals("ANY", fc.path("mode").asText());
        assertEquals("Bash", fc.path("allowedFunctionNames").get(0).asText());
    }

    // ===================== 图片输入 =====================

    @Test
    void base64ImageMapsToInlineData() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        ObjectNode imageBlock = M.createObjectNode();
        imageBlock.put("type", "image");
        ObjectNode source = imageBlock.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "aGVsbG8=");
        ChatMessageDto withImage = user("u1", "look").withContentBlocks(List.of(imageBlock));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(withImage), null, null, null, null, null);
        JsonNode part = transport.lastEnvelope().path("request").path("contents").get(0).path("parts").get(0);
        assertEquals("image/png", part.path("inlineData").path("mimeType").asText());
        assertEquals("aGVsbG8=", part.path("inlineData").path("data").asText());
    }

    @Test
    void urlImageExplicitIncompatibilityNotSilentDrop() {
        FakeTransport transport = new FakeTransport();
        ObjectNode imageBlock = M.createObjectNode();
        imageBlock.put("type", "image");
        imageBlock.putObject("source").put("url", "https://example.com/a.png");
        ChatMessageDto withImage = user("u1", "look").withContentBlocks(List.of(imageBlock));
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(withImage), null, null, null, null, null);
        assertEquals(1, cb.errors().size());
        assertTrue(cb.errors().get(0).getMessage().contains("URL 图片"));
        assertTrue(transport.requests.isEmpty());
    }

    // ===================== 封装纯函数 =====================

    @Test
    void signatureWrapUnwrapIdentity() {
        String wrapped = GoogleGeminiProvider.wrapSignature("raw+sig/with=chars", MODEL);
        assertNotNull(wrapped);
        assertTrue(wrapped.startsWith("gca1|gemini_code_assist|" + MODEL + "|"));
        assertFalse(wrapped.contains("raw+sig"), "封装不得明文携带原签名");
        assertEquals("raw+sig/with=chars", GoogleGeminiProvider.unwrapSignature(wrapped, MODEL));
        assertNull(GoogleGeminiProvider.unwrapSignature(wrapped, "gemini-2.5-pro"), "跨模型拒");
        assertNull(GoogleGeminiProvider.unwrapSignature("plainClaudeSig", MODEL), "跨 provider 拒");
        assertNull(GoogleGeminiProvider.unwrapSignature("gca1|anthropic|" + MODEL + "|AAAA", MODEL), "provider 不符拒");
        assertNull(GoogleGeminiProvider.unwrapSignature("gca1|gemini_code_assist|" + MODEL + "|!!!bad", MODEL), "损坏拒");
        assertNull(GoogleGeminiProvider.wrapSignature(null, MODEL));
        assertNull(GoogleGeminiProvider.unwrapSignature(null, MODEL));
    }

    @Test
    void blocksMatchCanonicalVerification() {
        List<ToolCallDto> calls = List.of(new ToolCallDto("c1", "Bash", "{\"a\":1}", null, null));
        List<ContentBlockDto> good = List.of(
            ContentBlockDto.text("hello", 0),
            new ContentBlockDto("tool_use", null, null, "sig", "c1", "Bash", "{\"a\":1}", 1));
        ChatMessageDto dto = assistant("a1", "hello", null, calls);
        assertTrue(GoogleGeminiProvider.blocksMatchCanonical(dto, good));
        // 文本不等 → false
        assertFalse(GoogleGeminiProvider.blocksMatchCanonical(
            assistant("a1", "changed", null, calls), good));
        // 工具数不等 → false
        assertFalse(GoogleGeminiProvider.blocksMatchCanonical(
            assistant("a1", "hello", null, List.of()), good));
        // name 不等 → false
        assertFalse(GoogleGeminiProvider.blocksMatchCanonical(dto, List.of(
            ContentBlockDto.text("hello", 0),
            new ContentBlockDto("tool_use", null, null, "sig", "c1", "Read", "{\"a\":1}", 1))));
        // args 语义等价（键序不同）→ true
        assertTrue(GoogleGeminiProvider.blocksMatchCanonical(
            assistant("a1", "hello", null, List.of(new ToolCallDto("c1", "Bash", "{\"a\":1}", null, null))),
            List.of(ContentBlockDto.text("hello", 0),
                new ContentBlockDto("tool_use", null, null, "sig", "c1", "Bash", "{ \"a\" : 1 }", 1))));
        // 空 blocks → false
        assertFalse(GoogleGeminiProvider.blocksMatchCanonical(dto, null));
        assertFalse(GoogleGeminiProvider.blocksMatchCanonical(dto, List.of()));
    }

    // ===================== IND 独立验收回归（sess-0fd681bb probe 转生产，2026-11-25） =====================

    /** IND-A：非流式合法大响应（80KB）必须全量解析、1 次请求成功，⛔ 不得 64KB 截断当网络故障重试。 */
    @Test
    void indA_nonStreamLargeBodyMustNotTruncate() {
        String big = "a".repeat(80_000);
        String body = "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + big
            + "\"}]},\"finishReason\":\"STOP\"}]},\"traceId\":\"t\"}";
        FakeTransport transport = new FakeTransport()
            .enqueue(200, body).enqueue(200, body).enqueue(200, body);
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        String result = provider.chat(config("proj-1"), MODEL, null, "hi");
        assertEquals(80_000, result.length(), "非流式响应必须完整读回");
        assertEquals(1, transport.requests.size(), "合法大响应 1 次请求成功，不得截断重试");
    }

    /** IND-A2：非流式 200 但坏 JSON → 受控拒绝（不重试），⛔ 不当可重试网络故障。 */
    @Test
    void indA2_nonStreamMalformedSuccessBodyControlledRejection() {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, "{\"response\": {bad").enqueue(200, "{}").enqueue(200, "{}");
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> provider.chat(config("proj-1"), MODEL, null, "hi"));
        assertFalse(ex instanceof LlmApiException, "本地解析失败不是 HTTP API 错误");
        assertEquals(1, transport.requests.size(), "坏 JSON 受控拒绝，⛔ 不重试");
    }

    /** IND-B：流式刷新成功后仍 401 → 中文重登提示 + 恰一次终态 + 恰 2 请求（不再 refresh）。 */
    @Test
    void indB_streamSecond401AfterRefreshNeedsReloginHint() {
        FakeTransport transport = new FakeTransport()
            .enqueue(401, "{\"error\":{\"code\":401,\"message\":\"Invalid credentials\"}}")
            .enqueue(401, "{\"error\":{\"code\":401,\"message\":\"Invalid credentials\"}}");
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        AtomicInteger refreshCalls = new AtomicInteger();
        provider.setProviderService(new com.nexusai.domain.provider.ProviderService() {
            @Override public String forceRefreshByAccessToken(String t) {
                refreshCalls.incrementAndGet();
                return "new-token";
            }
        });
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(1, cb.errors().size(), "终态唯一");
        assertTrue(cb.errors().get(0).getMessage().contains("登录已失效"),
            "刷新后仍 401 必须重登提示，实际: " + cb.errors().get(0).getMessage());
        assertEquals(1, refreshCalls.get(), "只刷新一次");
        assertEquals(2, transport.requests.size(), "刷新后重试一次即止");
        assertFalse(cb.completed.get());
    }

    /** IND-C：非流式刷新后仍 401 → 同样重登提示，不再 refresh/retry。 */
    @Test
    void indC_nonStreamSecond401AfterRefreshNeedsReloginHint() {
        FakeTransport transport = new FakeTransport()
            .enqueue(401, "{\"error\":{\"code\":401,\"message\":\"Invalid credentials\"}}")
            .enqueue(401, "{\"error\":{\"code\":401,\"message\":\"Invalid credentials\"}}");
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        AtomicInteger refreshCalls = new AtomicInteger();
        provider.setProviderService(new com.nexusai.domain.provider.ProviderService() {
            @Override public String forceRefreshByAccessToken(String t) {
                refreshCalls.incrementAndGet();
                return "new-token";
            }
        });
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> provider.chat(config("proj-1"), MODEL, null, "hi"));
        assertTrue(ex.getMessage().contains("登录已失效"),
            "非流式刷新后仍 401 必须重登提示，实际: " + ex.getMessage());
        assertEquals(1, refreshCalls.get());
        assertEquals(2, transport.requests.size());
    }

    /** IND-D：可重试退避必须可被 abort 打断（长 retryAfter 等待中 abort 及时收口，不发下一 HTTP）。 */
    @Test
    void indD_retryBackoffMustBeAbortable() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(429, quotaBody("RATE_LIMIT_EXCEEDED", null, "1.5s"))
            .enqueue(200, "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}");
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        AbortController ac = new AbortController();
        ChatRequestOptions opts = new ChatRequestOptions(List.of(), null, null, null, null, "reg", ac, null, null);
        Throwable[] err = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                provider.chatWithOptionsMessage(config("proj-1"), MODEL, null, "hi", opts);
            } catch (Throwable t) {
                err[0] = t;
            }
        });
        worker.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (transport.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, transport.requests.size(), "首请求应发出");
        long abortAt = System.nanoTime();
        ac.abort("interrupt");
        worker.join(5000);
        long latencyMs = (System.nanoTime() - abortAt) / 1_000_000;
        assertFalse(worker.isAlive(), "abort 后应返回");
        assertNotNull(err[0], "abort 应以取消异常收口");
        assertInstanceOf(java.util.concurrent.CancellationException.class, err[0]);
        assertTrue(latencyMs < 600, "退避须可被 abort 打断，实测延迟=" + latencyMs + "ms（退避 1500ms）");
        assertEquals(1, transport.requests.size(), "取消后不得发下一 HTTP");
    }

    /** IND-E：2.5 Pro enabled(0) 归一 128（Pro 禁 0）；Flash enabled(0) 仍 0；adaptive(-1) 不乱改。 */
    @Test
    void indE_proEnabledZeroBudgetNormalizedToMin() {
        assertEquals(128, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-pro",
            ChatRequestOptions.ThinkingConfig.enabled(0), null).path("thinkingBudget").asInt(),
            "2.5 Pro enabled(0) 不得发 thinkingBudget:0");
        assertEquals(0, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-flash",
            ChatRequestOptions.ThinkingConfig.enabled(0), null).path("thinkingBudget").asInt(),
            "Flash 允许 0，不误改");
        assertEquals(-1, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-pro",
            ChatRequestOptions.ThinkingConfig.adaptive(), null).path("thinkingBudget").asInt(),
            "合法 sentinel -1 不乱改");
        assertEquals(4096, GoogleGeminiProvider.mapThinkingConfig("gemini-2.5-pro",
            ChatRequestOptions.ThinkingConfig.enabled(4096), null).path("thinkingBudget").asInt(),
            "显式合法 budget 不乱改");
        assertEquals("HIGH", GoogleGeminiProvider.mapThinkingConfig("gemini-3-pro-preview",
            ChatRequestOptions.ThinkingConfig.enabled(0), null).path("thinkingLevel").asText(),
            "3 系仍 thinkingLevel");
    }

    /** IND-F：预取消的 stream 零 HTTP + 终态一次（与非流 pre-check 一致）。 */
    @Test
    void indF_preCancelledStreamMustNotSendRequest() {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        GoogleGeminiProvider provider = new GoogleGeminiProvider(transport);
        AbortController ac = new AbortController();
        ac.abort("before-send");
        Callbacks cb = runStream(provider, config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, ac, null);
        assertTrue(transport.requests.isEmpty(), "已取消不得发请求，实际发了 " + transport.requests.size());
        assertFalse(cb.completed.get());
        assertEquals(1, cb.errors().size(), "终态一次（CancellationException）");
        assertInstanceOf(java.util.concurrent.CancellationException.class, cb.errors().get(0));
    }

    /** IND-G（守卫）：同名并行工具、结果乱序 → 按 id 精确配对，单 model + 单 user。 */
    @Test
    void indG_sameNameParallelToolsPairedByIdNotPosition() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        List<ChatMessageDto> history = List.of(
            user("u1", "run"),
            assistant("a1", "", null, List.of(
                new ToolCallDto("c1", "Bash", "{\"command\":\"ls\"}", null, null),
                new ToolCallDto("c2", "Bash", "{\"command\":\"pwd\"}", null, null))),
            tool("t2", "c2", "out2", false),
            tool("t1", "c1", "out1", false),
            user("u2", "next"));
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            history, null, null, null, null, null);
        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        JsonNode contents = transport.lastEnvelope().path("request").path("contents");
        assertEquals("model", contents.get(1).path("role").asText());
        assertEquals(2, contents.get(1).path("parts").size());
        assertEquals("user", contents.get(2).path("role").asText());
        int frCount = 0;
        for (JsonNode p : contents.get(2).path("parts")) {
            if (!p.has("functionResponse")) {
                continue;
            }
            frCount++;
            JsonNode fr = p.path("functionResponse");
            assertEquals("Bash", fr.path("name").asText());
            String id = fr.path("id").asText();
            String out = fr.path("response").path("output").asText();
            if ("c1".equals(id)) {
                assertEquals("out1", out);
            } else if ("c2".equals(id)) {
                assertEquals("out2", out);
            } else {
                fail("未知 FR id=" + id);
            }
        }
        assertEquals(2, frCount, "同名并行批 2 个 FR 按 id 配对，单 user Content");
    }

    /** IND-G2（守卫）：部分文本后流内 error → 终态唯一，不当成功、不再发 AssistantMessage。 */
    @Test
    void indG2_partialTextThenStreamErrorTerminalOnce() {
        String wire = sse(chunk("[{\"text\":\"partial\"}]", null, null),
            "{\"error\":{\"code\":429,\"message\":\"exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
        FakeTransport transport = new FakeTransport().enqueue(200, wire);
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(List.of("partial"), cb.chunks());
        assertEquals(1, cb.errors().size());
        assertInstanceOf(LlmApiException.class, cb.errors().get(0));
        assertFalse(cb.completed.get());
        assertTrue(cb.messages().isEmpty(), "error 终态不得再发 AssistantMessage");
    }

    /** IND-I（守卫）：reasoning 被裁剪 → blocks 核对失败 → 弃旧签名走合成。 */
    @Test
    void indI_trimmedReasoningDropsOldSignature() throws Exception {
        String sig = GoogleGeminiProvider.wrapSignature("oldSig", MODEL);
        List<ContentBlockDto> blocks = List.of(
            ContentBlockDto.thinking("thought text", GoogleGeminiProvider.wrapSignature("sigT", MODEL), 0),
            new ContentBlockDto("tool_use", null, null, sig, "c1", "Bash", "{}", 1));
        ChatMessageDto dto = assistant("a1", "", "", List.of(new ToolCallDto("c1", "Bash", "{}", null, null)))
            .withBlocks(blocks);
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi"), dto, tool("t1", "c1", "o", false)),
            null, null, null, null, null);
        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        JsonNode parts = transport.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("skip_thought_signature_validator", parts.get(0).path("thoughtSignature").asText(),
            "裁剪后不得复用旧签名");
    }

    // ===================== MODEL-MAP 官方无 flag 默认映射 oracle（主会话 2026-11-25 裁决） =====================

    /** 官方表精确匹配（models.ts:144-156 无 flag 分支）；⛔ 不凭 substring 猜别名，⛔ 不升 3.8。 */
    @Test
    void resolveBackendModelOfficialNoFlagTable() {
        assertEquals("gemini-3-flash", GoogleGeminiProvider.resolveBackendModel("gemini-3.5-flash"));
        // 其余已是 wire ID / Pro 按确切官方表不动
        assertEquals("gemini-2.5-pro", GoogleGeminiProvider.resolveBackendModel("gemini-2.5-pro"));
        assertEquals("gemini-2.5-flash", GoogleGeminiProvider.resolveBackendModel("gemini-2.5-flash"));
        assertEquals("gemini-3-pro-preview", GoogleGeminiProvider.resolveBackendModel("gemini-3-pro-preview"));
        assertEquals("gemini-3.1-flash-lite", GoogleGeminiProvider.resolveBackendModel("gemini-3.1-flash-lite"));
        assertEquals("gemini-3.5-flash-lite", GoogleGeminiProvider.resolveBackendModel("gemini-3.5-flash-lite"));
        assertEquals("gemma-4-31b-it", GoogleGeminiProvider.resolveBackendModel("gemma-4-31b-it"));
        // 精确匹配，非 substring
        assertEquals("my-gemini-3.5-flash", GoogleGeminiProvider.resolveBackendModel("my-gemini-3.5-flash"));
        assertEquals("gemini-3.5-flash-preview", GoogleGeminiProvider.resolveBackendModel("gemini-3.5-flash-preview"));
        assertNull(GoogleGeminiProvider.resolveBackendModel(null));
        // ⛔ 无证据不升 3.8
        assertNotEquals("gemini-3.8-flash", GoogleGeminiProvider.resolveBackendModel("gemini-3.5-flash"));
    }

    /** stream：display gemini-3.5-flash → envelope 实际 wire gemini-3-flash（UA 同源）。 */
    @Test
    void alias35FlashStreamEnvelopeSends3Flash() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), "gemini-3.5-flash", null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        JsonNode env = transport.lastEnvelope();
        assertEquals("gemini-3-flash", env.path("model").asText());
        assertTrue(transport.requests.get(0).headers().get("User-Agent").contains("gemini-3-flash"));
        // 3 系家族 → thinkingLevel（映射后家族判定同源）
        assertEquals("HIGH", env.path("request").path("generationConfig").path("thinkingConfig")
            .path("thinkingLevel").asText());
    }

    /** 非流式与 stream 同源映射。 */
    @Test
    void aliasNonStreamSameSource() throws Exception {
        FakeTransport transport = new FakeTransport().enqueue(200,
            "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}");
        new GoogleGeminiProvider(transport).chat(config("proj-1"), "gemini-3.5-flash", null, "hi");
        assertEquals("gemini-3-flash", transport.lastEnvelope().path("model").asText());
        assertTrue(transport.requests.get(0).url().endsWith(":generateContent"));
    }

    /** Pro 不受映射影响。 */
    @Test
    void aliasProUnchanged() throws Exception {
        FakeTransport transport = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(transport), config("proj-1"), "gemini-2.5-pro", null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        JsonNode env = transport.lastEnvelope();
        assertEquals("gemini-2.5-pro", env.path("model").asText());
        assertEquals(8192, env.path("request").path("generationConfig").path("thinkingConfig")
            .path("thinkingBudget").asInt());
    }

    /** 签名身份绑定最终 wire model：capture 封装 wire；display alias 身份不回放；wire 身份跨 display 回放。 */
    @Test
    void signatureIdentityBindsWireModelNotDisplayAlias() throws Exception {
        // capture：display 3.5-flash → 签名封装身份 = wire 3-flash
        FakeTransport cap = new FakeTransport().enqueue(200, sse(
            chunk("[{\"functionCall\":{\"name\":\"Bash\",\"args\":{}},\"thoughtSignature\":\"wireSig\"}]",
                "STOP", null)));
        Callbacks cb = runStream(new GoogleGeminiProvider(cap), config("proj-1"), "gemini-3.5-flash", null,
            List.of(user("u1", "run")), null, null, null, null, null);
        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        ContentBlockDto toolBlock = cb.messages().get(0).blocks().get(0);
        assertEquals("wireSig", GoogleGeminiProvider.unwrapSignature(toolBlock.signature(), "gemini-3-flash"),
            "capture 身份 = wire model");
        assertNull(GoogleGeminiProvider.unwrapSignature(toolBlock.signature(), "gemini-3.5-flash"),
            "⛔ 身份不得绑 display alias");

        // replay：wire 身份签名 + display 3.5-flash 请求（解析到同 wire）→ 回放
        List<ContentBlockDto> blocks = List.of(
            new ContentBlockDto("tool_use", null, null,
                GoogleGeminiProvider.wrapSignature("wireSig", "gemini-3-flash"), "c1", "Bash", "{}", 0));
        ChatMessageDto dto = assistant("a1", "", null, List.of(new ToolCallDto("c1", "Bash", "{}", null, null)))
            .withBlocks(blocks);
        FakeTransport re = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(re), config("proj-1"), "gemini-3.5-flash", null,
            List.of(user("u1", "hi"), dto, tool("t1", "c1", "o", false)), null, null, null, null, null);
        JsonNode parts = re.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("wireSig", parts.get(0).path("thoughtSignature").asText(), "wire 身份跨 display 回放");

        // 旧 display alias 身份签名（映射改变后）→ 不匹配 → 合成 fallback
        List<ContentBlockDto> legacy = List.of(
            new ContentBlockDto("tool_use", null, null,
                GoogleGeminiProvider.wrapSignature("oldSig", "gemini-3.5-flash"), "c1", "Bash", "{}", 0));
        ChatMessageDto legacyDto = assistant("a1", "", null, List.of(new ToolCallDto("c1", "Bash", "{}", null, null)))
            .withBlocks(legacy);
        FakeTransport lg = new FakeTransport()
            .enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP", null)));
        runStream(new GoogleGeminiProvider(lg), config("proj-1"), "gemini-3.5-flash", null,
            List.of(user("u1", "hi"), legacyDto, tool("t1", "c1", "o", false)), null, null, null, null, null);
        JsonNode lgParts = lg.lastEnvelope().path("request").path("contents").get(1).path("parts");
        assertEquals("skip_thought_signature_validator", lgParts.get(0).path("thoughtSignature").asText(),
            "display alias 身份不匹配 → 已批准合成 fallback");
    }

    // ===================== USAGE-MAP toolUsePromptTokenCount 规范映射（官方 converter.ts:332-337 独立加项） =====================

    /** prompt100 + toolUsePrompt50 → input150（官方 computedTotal 分解：toolUsePrompt 非 prompt 子集）。 */
    @Test
    void usageToolUsePromptAddedToInput() {
        FakeTransport transport = new FakeTransport().enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
            "{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"toolUsePromptTokenCount\":50}")));
        CapturingSink sink = new CapturingSink();
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, new LlmCallScope(null, sink));
        assertTrue(cb.errors().isEmpty(), () -> "errors=" + cb.errors());
        assertEquals(150, cb.messages().get(0).usage().inputTokens(), "input = prompt + toolUsePrompt");
        assertEquals(10, cb.messages().get(0).usage().outputTokens());
        assertEquals(150, sink.usages.get(0).inputTokens());
    }

    /** toolUsePrompt 缺失/null/0 → 按现有归一规则 0，input 保持 prompt 原值。 */
    @Test
    void usageToolUsePromptMissingOrZeroKeepsInput() {
        // 缺失
        FakeTransport t1 = new FakeTransport().enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
            "{\"promptTokenCount\":100,\"candidatesTokenCount\":10}")));
        Callbacks cb1 = runStream(new GoogleGeminiProvider(t1), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(100, cb1.messages().get(0).usage().inputTokens(), "缺失 → 0 加项");
        // 显式 0
        FakeTransport t2 = new FakeTransport().enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
            "{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"toolUsePromptTokenCount\":0}")));
        Callbacks cb2 = runStream(new GoogleGeminiProvider(t2), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(100, cb2.messages().get(0).usage().inputTokens(), "显式 0 → 不变");
        // null
        FakeTransport t3 = new FakeTransport().enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
            "{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"toolUsePromptTokenCount\":null}")));
        Callbacks cb3 = runStream(new GoogleGeminiProvider(t3), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        assertEquals(100, cb3.messages().get(0).usage().inputTokens(), "null → 0 加项");
    }

    /** 含 cached20：input150/cacheRead20；totalTokens(false)=160 明确常量（⛔ 不用生产算法算期望，不重复 cache）。 */
    @Test
    void usageToolUsePromptWithCachedNoDoubleCount() {
        FakeTransport transport = new FakeTransport().enqueue(200, sse(chunk("[{\"text\":\"ok\"}]", "STOP",
            "{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"toolUsePromptTokenCount\":50,\"cachedContentTokenCount\":20}")));
        Callbacks cb = runStream(new GoogleGeminiProvider(transport), config("proj-1"), MODEL, null,
            List.of(user("u1", "hi")), null, null, null, null, null);
        AgentUsage u = cb.messages().get(0).usage();
        assertEquals(150, u.inputTokens());
        assertEquals(20L, u.cacheReadInputTokens());
        assertEquals(10, u.outputTokens());
        // 明确常量期望：非 anthropic 口径 input+output = 150+10 = 160（prompt 已含 cached，⛔ 不再加 cacheRead）
        assertEquals(160L, u.totalTokens(false), "totalTokens(false) 不重复计 cache");
    }
}
