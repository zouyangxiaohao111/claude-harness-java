package com.nexusai.infra.llm;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.PromptCachingTtlConfig;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptBlocksBuilder;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-06] AnthropicSdkProvider 发送边界 system 数组意图测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9)</b>:
 * <ol>
 *   <li><b>请求体 system 必须为 text block 数组</b>（对齐 CC buildSystemPromptBlocks 产物，
 *       claude.ts:3213-3237）——不是单字符串。若实现仍写 {@code system=string}，
 *       API 侧缓存语义与 CC 不一致。</li>
 *   <li><b>[⊕C-1] String 兼容契约已删除（IMP-SP2-04）</b>：发送契约数组态唯一，
 *       无 String 兜底（OPD-SP-28 委托链退出）。</li>
 *   <li><b>[⊕C-2] 单一实现（IMP-SP2-05）</b>：发送 wire system 与 prompt state
 *       snapshot 同源——均经 {@link SystemPromptBlocksBuilder#buildSystemPromptBlocks}，
 *       cache_control 门控（caching && scope != NULL）与 ttl/scope 取值仅存在于 builder 一处。
 *       本文件一致性矩阵用例（{@code consistencyMatrix_sendWireEqualsBuilderOutput}）钉死两端逐字段等价。</li>
 *   <li><b>≤4 block 红线</b>：split 产物最多 4 block（claude.ts:3214-3216，400 风险注释）。</li>
 * </ol>
 *
 * <p>[DEC-RV-07 REWORK-2] 断言目标从生产已废弃的 {@code AnthropicProvider.buildRequestBody}
 * 迁移到生产实际路径 {@code AnthropicSdkProvider.buildMessageParams(...)._body()} 的 wire JSON
 * （SDK 序列化经 {@link com.anthropic.core.ObjectMappers#jsonMapper()}），修复"测试验证死代码"。
 */
class AnthropicSdkProviderSystemBlocksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** [DEC-RV-07 REWORK-2] SDK params → wire JSON JsonNode（生产实际发送的请求体）。 */
    private static JsonNode sdkWire(MessageCreateParams params) throws Exception {
        return JSON.readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
    }

    @Test
    @DisplayName("blocks 变体 → system 为 text block 数组 + cache_control（caching 默认开）")
    void blocksVariant_systemArrayWithCacheControl() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("static", CacheScope.GLOBAL),
                new SystemPromptBlock("dynamic", CacheScope.NULL)),
            List.of(), null, null, null, null, null, null);
        JsonNode system = sdkWire(params).get("system");
        assertThat(system.isArray()).as("system 必须是数组而非字符串").isTrue();
        assertThat(system).hasSize(2);
        assertThat(system.get(0).get("type").asText()).isEqualTo("text");
        assertThat(system.get(0).get("text").asText()).isEqualTo("static");
        assertThat(system.get(0).get("cache_control").get("type").asText()).isEqualTo("ephemeral");
        assertThat(system.get(0).get("cache_control").get("ttl").asText())
            .as("默认配置 → Anthropic 边界 cache_control.ttl='1h'（RES-R7 默认 1h 生效）").isEqualTo("1h");
        assertThat(system.get(0).get("cache_control").get("scope").asText()).isEqualTo("global");
        assertThat(system.get(1).has("cache_control")).as("NULL scope → 无 cache_control").isFalse();
    }

    @Test
    @DisplayName("blocks 变体 · 4 block（静态+动态+attribution+prefix）≤4 红线不超（claude.ts:3214-3216）")
    void blocksVariant_atMostFourBlocks() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-opus-4-6",
            List.of(
                new SystemPromptBlock("attribution", CacheScope.NULL),
                new SystemPromptBlock("prefix", CacheScope.NULL),
                new SystemPromptBlock("static", CacheScope.GLOBAL),
                new SystemPromptBlock("dynamic", CacheScope.NULL)),
            List.of(), null, null, null, null, null, null);
        JsonNode system = sdkWire(params).get("system");
        assertThat(system).hasSize(4);
        assertThat(system).as("≤4 block（API 400 风险红线）").hasSizeLessThanOrEqualTo(4);
    }


    @Test
    @DisplayName("null blocks → 无 system 字段（与旧 null systemPrompt 行为一致）")
    void nullBlocks_noSystemField() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6", (List<SystemPromptBlock>) null,
            List.of(), null, null, null, null, null, null);
        assertThat(sdkWire(params).has("system")).isFalse();
    }

    @Test
    @DisplayName("blocks 序列化产物 JSON 合法（发送边界可写入请求体）")
    void blocksVariant_validJson() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("t", CacheScope.ORG)),
            List.of(), null, null, null, null, null, null);
        JsonNode root = sdkWire(params);
        assertThat(root.get("system").isArray()).isTrue();
        ArrayNode arr = (ArrayNode) root.get("system");
        assertThat(arr.get(0).get("text").asText()).isEqualTo("t");
    }

    // ════════════════════════════════════════════════════════════════════
    // [ODF-B3] messages 通道 message-level cache_control marker
    // ════════════════════════════════════════════════════════════════════

    /** 构造普通 user 消息（对齐 R32C1 regularUserMessage 最小形态）。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            "msg-id", "session-id-1", Role.user, null, content,
            null, null, null, null, null, null, null, null, null, null,
            null, List.of(), null, false);
    }

    @Test
    @DisplayName("messages 通道 · caching 开 + skipCacheWrite 未设 → marker 落最后一条（CC claude.ts:3089）")
    void messagesChannel_markerOnLastMessage() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("sys", CacheScope.GLOBAL)),
            List.of(userMessage("first"), userMessage("second")),
            null, null, null, null, null, null);
        JsonNode messages = sdkWire(params).get("messages");
        // 非 marker 消息 content 保持字符串
        assertThat(messages.get(0).get("content").isTextual())
            .as("非 marker 消息 content 保持字符串").isTrue();
        assertThat(messages.get(0).get("content").asText()).isEqualTo("first");
        // marker 消息（最后一条）字符串 content → 数组单 text block + cache_control
        JsonNode last = messages.get(1);
        assertThat(last.get("content").isArray())
            .as("marker 消息 content 必须转数组（CC userMessageToMessageParam claude.ts:594-607）").isTrue();
        assertThat(last.get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(last.get("content").get(0).get("text").asText()).isEqualTo("second");
        assertThat(last.get("content").get(0).get("cache_control").get("type").asText())
            .isEqualTo("ephemeral");
        // exactly one message marker（+ system 通道 1 处 = 全 body 恰 2 处 cache_control）
        String wire = ObjectMappers.jsonMapper().writeValueAsString(params._body());
        assertThat(wire.split("cache_control").length - 1)
            .as("每请求恰好 1 个 message marker + 1 个 system marker").isEqualTo(2);
    }

    @Test
    @DisplayName("messages 通道 · skipCacheWrite=true → marker 移位到倒数第二条（CC claude.ts:3089）")
    void messagesChannel_skipCacheWrite_markerOnSecondToLast() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("sys", CacheScope.GLOBAL)),
            List.of(userMessage("first"), userMessage("second"), userMessage("third")),
            null, null, null, null, null, true);
        JsonNode messages = sdkWire(params).get("messages");
        // 倒数第二条（index 1）是 marker
        assertThat(messages.get(1).get("content").isArray())
            .as("skipCacheWrite=true → marker 移位到倒数第二条").isTrue();
        assertThat(messages.get(1).get("content").get(0).get("cache_control").get("type").asText())
            .isEqualTo("ephemeral");
        // 最后一条（index 2）保持字符串 —— 移位非删除（CC :3084-3090）
        assertThat(messages.get(2).get("content").isTextual())
            .as("末条 content 保持字符串（marker 移位非删除）").isTrue();
        assertThat(messages.get(2).get("content").asText()).isEqualTo("third");
        // 首条无 marker
        assertThat(messages.get(0).get("content").isTextual()).isTrue();
        assertThat(messages.get(0).get("content").asText()).isEqualTo("first");
    }

    @Test
    @DisplayName("messages 通道 · 注入 resume 历史后 marker 仍 exactly-one（历史不产生额外 marker）")
    void messagesChannel_injectedResumeHistory_stillExactlyOneMarker() throws Exception {
        // [fix-loop-resume-history T4] 主路径注入历史（历史 assistant + 当前用户消息，普通
        // ChatMessageDto 无 cacheControl 字段）进 messages 通道后，exactly-one marker 仍只落在
        // 末条（当前用户消息）——历史成为缓存前缀、不产生陈旧/重复 marker（对齐 CC
        // addCacheBreakpoints）。RED：若 buildSdkMessages 对历史消息也附 marker（如错误地把
        // cache_control 写进每条），split 计数 > 2。
        ChatMessageDto historyAssistant = new ChatMessageDto(
            "hist-asst", "session-1", Role.assistant, null, "上一轮回复",
            null, null, FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("sys", CacheScope.GLOBAL)),
            List.of(historyAssistant, userMessage("resume query")),
            null, null, null, null, null, null, true, false); // enablePromptCaching=true → caching 确定
        String wire = ObjectMappers.jsonMapper().writeValueAsString(params._body());
        assertThat(wire.split("cache_control").length - 1)
            .as("注入历史后每请求仍恰好 1 个 message marker + 1 个 system marker（历史不产生额外 marker）")
            .isEqualTo(2);
        JsonNode messages = sdkWire(params).get("messages");
        assertThat(messages.get(0).get("content").isTextual())
            .as("历史 assistant 消息不承载 marker（内容保持字符串，无 cache_control）").isTrue();
        assertThat(messages.get(1).get("content").isArray())
            .as("末条（当前用户消息）= marker 消息（marker 恒在末条，CC claude.ts:3089）").isTrue();
        assertThat(messages.get(1).get("content").get(0).get("cache_control").get("type").asText())
            .isEqualTo("ephemeral");
    }

    // ════════════════════════════════════════════════════════════════════
    // [C] 经 provider.stream 的 skipCacheWrite 端到端（wire 级 marker 落位）
    //   WHY 独立于上面的 buildMessageParams 直调断言：上面 4 条用例直接调 9 参
    //   buildMessageParams（skipCacheWrite 位置硬编码 null），**绕过 stream 链**——
    //   若 LlmProvider.stream 抽象签名不带该参数（Java 无 default 形参），或
    //   AnthropicSdkProvider.doStream 调 buildMessageParams 时又写回 null（B3 断点回归），
    //   上面用例全绿而生产流式路径 marker 永不移位。本组用例从 provider.stream 入口发起
    //   真实 HTTP 请求（本地 HttpServer 捕获请求体），钉死「参数 → wire」全链。
    //   RED 变异：把 doStream 里第 9 实参改回 null → 本组 markerOnSecondToLast 必红。
    // ════════════════════════════════════════════════════════════════════

    /** 本地 HttpServer + 最小 SSE 响应（对齐 AnthropicSdkProviderEffortTest.buildSseResponse）。 */
    private static byte[] minimalSseResponse() {
        return ("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude\",\"role\":\"assistant\"}}\n"
            + "\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n"
            + "\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n"
            + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 经 provider.stream 发起一次真实调用并返回服务端收到的请求体 JSON。
     *
     * @param skipCacheWrite 传给 stream 的末参（null / true / false）
     */
    private static JsonNode streamAndCaptureBody(Boolean skipCacheWrite) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> capturedBody =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
            byte[] sse = minimalSseResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
            got.countDown();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            new AnthropicSdkProvider().stream(
                config, "claude-sonnet-4-6",
                List.of(new SystemPromptBlock("sys", CacheScope.GLOBAL)),
                List.of(userMessage("first"), userMessage("second"), userMessage("third")),
                null, null, null, null, null,
                c -> {}, m -> {}, t -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown,
                skipCacheWrite);
            assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("provider.stream 必须正常收尾（onComplete）").isTrue();
            assertThat(got.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("本地 server 必须收到请求").isTrue();
            return JSON.readTree(capturedBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[C] 经 provider.stream · skipCacheWrite=true → wire marker 落倒数第二条（claude.ts:3243）")
    void streamPath_skipCacheWriteTrue_markerOnSecondToLast() throws Exception {
        JsonNode messages = streamAndCaptureBody(Boolean.TRUE).get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).get("content").isArray())
            .as("stream 路径 skipCacheWrite=true → marker 必须移位到倒数第二条"
                + "（若 doStream 第 9 实参回退 null，marker 落末条 → 本断言红）").isTrue();
        assertThat(messages.get(1).get("content").get(0).get("cache_control").get("type").asText())
            .isEqualTo("ephemeral");
        assertThat(messages.get(2).get("content").isTextual())
            .as("末条 content 保持字符串（marker 移位非删除）").isTrue();
        assertThat(messages.get(2).get("content").asText()).isEqualTo("third");
    }

    @Test
    @DisplayName("[C] 经 provider.stream · skipCacheWrite=null/false（主循环）→ marker 落在最后一条（行为不变）")
    void streamPath_skipCacheWriteUnset_markerOnLast() throws Exception {
        for (Boolean value : new Boolean[] {null, Boolean.FALSE}) {
            JsonNode messages = streamAndCaptureBody(value).get("messages");
            assertThat(messages).hasSize(3);
            assertThat(messages.get(2).get("content").isArray())
                .as("skipCacheWrite=" + value + " → marker 保持末条（零行为变化）").isTrue();
            assertThat(messages.get(2).get("content").get(0).get("cache_control").get("type").asText())
                .isEqualTo("ephemeral");
            assertThat(messages.get(1).get("content").isTextual()).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-SP2-07 ✗-13] PROMPT_CACHING_SCOPE_BETA_HEADER（claude.ts:1217-1222）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("firstParty config → anthropic-beta 追加 prompt-caching-scope-2026-01-05（claude.ts:1217-1222）")
    void firstPartyConfig_pushesPromptCachingScopeBeta() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("sys", CacheScope.ORG)),
            List.of(), null, null, null, null, null, null, null,
            StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl("https://api.anthropic.com"),
            new ProviderConfig("https://api.anthropic.com", "sk-test"));
        List<String> betas = params._additionalHeaders().values("anthropic-beta");
        assertThat(betas)
            .as("firstParty（api.anthropic.com）必须 push PROMPT_CACHING_SCOPE_BETA_HEADER（CC constants/betas.ts:17-18）")
            .anyMatch(v -> v.contains(AnthropicSdkProvider.PROMPT_CACHING_SCOPE_BETA_HEADER));
    }

    @Test
    @DisplayName("3P config → anthropic-beta 不含 prompt-caching-scope（gate=false 零变化防护）")
    void thirdPartyConfig_noPromptCachingScopeBeta() throws Exception {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new SystemPromptBlock("sys", CacheScope.ORG)),
            List.of(), null, null, null, null, null, null, null,
            StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl("https://other-provider.example.com"),
            new ProviderConfig("https://other-provider.example.com", "sk-test"));
        List<String> betas = params._additionalHeaders().values("anthropic-beta");
        assertThat(betas)
            .as("3P（非 api.anthropic.com）不得 push PROMPT_CACHING_SCOPE_BETA_HEADER")
            .noneMatch(v -> v.contains(AnthropicSdkProvider.PROMPT_CACHING_SCOPE_BETA_HEADER));
    }

    @Test
    @DisplayName("OpenAI 请求体恒 0 cache_control（对齐 CC 无对应，不写 markers 通道 · [OpenAI-SDK 迁移] SDK wire）")
    void openAiProvider_noCacheControl() throws Exception {
        // [OpenAI-SDK 迁移] 旧 OpenAiProvider.buildRequestBody 已删除 → 生产 SDK wire：
        //   OpenAiSdkProvider.buildRequestParams → _body() 序列化 JsonNode
        com.openai.models.ChatCompletionCreateParams params = OpenAiSdkProvider.buildRequestParams(
            "gpt-test", "sys", List.of(userMessage("hello")), null,
            null, false, null, null, null);
        // [OpenAI-SDK] ChatCompletionCreateParams 直接序列化产出 {} → 断言 messages 通道（cache marker 所在通道）无 cache_control
        String messagesWire = com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params.messages());
        assertThat(messagesWire)
            .as("OpenAI 请求体必须无 cache_control 键").doesNotContain("cache_control");
    }

    // ════════════════════════════════════════════════════════════════════
    // [⊕C-2 IMP-SP2-05] 发送 wire vs builder 单一实现 · cache_control 门控矩阵一致性
    // ════════════════════════════════════════════════════════════════════

    /** 每用例后复位静态 ttl 配置（防 register 泄漏到后续用例，同 SystemPromptBlocksBuilderTest:33-36）。 */
    @AfterEach
    void restoreTtlConfig() {
        PromptCachingTtlConfig.register(PromptCachingTtlConfig.DEFAULTS);
    }

    @Test
    @DisplayName("一致性矩阵 · caching × scope(NULL/ORG/GLOBAL) × ttl 配置 → 发送 wire system 与 builder 产物逐字段等价（⊕C-2 单一实现）")
    void consistencyMatrix_sendWireEqualsBuilderOutput() throws Exception {
        List<SystemPromptBlock> blocks = List.of(
            new SystemPromptBlock("null-scope", CacheScope.NULL),
            new SystemPromptBlock("org-scope", CacheScope.ORG),
            new SystemPromptBlock("global-scope", CacheScope.GLOBAL));
        PromptCachingTtlConfig[] configs = {
            PromptCachingTtlConfig.DEFAULTS,
            new PromptCachingTtlConfig(false, "1h"),
        };
        for (boolean caching : new boolean[] {false, true}) {
            for (PromptCachingTtlConfig cfg : configs) {
                PromptCachingTtlConfig.register(cfg);
                // 发送产物：生产发送路径（SDK ObjectMappers 序列化 wire）
                MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
                    "claude-sonnet-4-6", blocks, List.of(), null, null, null, null, null, null, caching);
                JsonNode wireSystem = sdkWire(params).get("system");
                // 单一实现产物：prompt state snapshot 同源（recordPromptState 路径）
                ArrayNode built = SystemPromptBlocksBuilder.buildSystemPromptBlocks(blocks, caching);
                String label = String.format("caching=%s ttlEnabled=%s", caching, cfg.isEnabled());
                assertThat(wireSystem).as(label + " block 数一致").hasSize(built.size());
                for (int i = 0; i < built.size(); i++) {
                    JsonNode w = built.get(i);
                    JsonNode s = wireSystem.get(i);
                    String bLabel = label + " block[" + i + "]";
                    assertThat(s.get("type").asText()).as(bLabel + " type").isEqualTo(w.get("type").asText());
                    assertThat(s.get("text").asText()).as(bLabel + " text").isEqualTo(w.get("text").asText());
                    assertThat(s.has("cache_control")).as(bLabel + " cache_control 存在性").isEqualTo(w.has("cache_control"));
                    if (w.has("cache_control")) {
                        JsonNode wc = w.get("cache_control");
                        JsonNode sc = s.get("cache_control");
                        assertThat(sc.size()).as(bLabel + " cache_control 无多余字段").isEqualTo(wc.size());
                        wc.fieldNames().forEachRemaining(f ->
                            assertThat(sc.get(f)).as(bLabel + " cache_control." + f).isEqualTo(wc.get(f)));
                    }
                }
            }
        }
    }
}
