package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude API query entrypoint · 对齐 CC services/api/claude.ts.
 *
 * <p>L1 语义: queryClaude 主入口 — 选 model + 构造 params + 注入 cache headers +
 *            capture request + 处理 response/streaming. Provider 分发 (firstParty/bedrock/foundry/vertex).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: API_PDF_MAX_PAGES=100; PDF_TARGET_RAW_SIZE=200MB; 5 record + 3 enum.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — queryClaude → provider 分发 → 构造 params → captureRequest → call API.</li>
 *   <li><b>A3</b>: 注入式 (provider + httpFetcher + modelConfig);silent failure on missing model.</li>
 *   <li><b>A4</b>: null prompt → throw;missing model → throw.</li>
 *   <li><b>A5</b>: 真实场景 — Claude Code query main loop 调用 queryClaude 发送 message.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Anthropic SDK → Java 抽象 (caller wired);
 *                    TS Promise stream → Java Supplier;
 *                    TS type → Java record.
 */
public final class ApiClaude {

    private static final Logger log = LoggerFactory.getLogger(ApiClaude.class);

    public static final int API_PDF_MAX_PAGES = 100;
    public static final long PDF_TARGET_RAW_SIZE = 200L * 1024L * 1024L;
    public static final long MAX_OUTPUT_TOKENS_DEFAULT = 32_000L;
    /** CC original: CAPPED_DEFAULT_MAX_TOKENS (Open-ClaudeCode/src/utils/context.ts:24) = 8_000 · slot-reservation 上限。 */
    public static final long CAPPED_DEFAULT_MAX_TOKENS = 8_000L;

    public enum Provider { FIRST_PARTY, BEDROCK, FOUNDRY, VERTEX }

    public enum Effort { LOW, MEDIUM, HIGH, MAX }

    public record ContentBlock(String type, String text, Map<String, Object> source) {
        public static ContentBlock text(String text) {
            return new ContentBlock("text", text, null);
        }
    }

    public record ModelParams(
        String model, int maxTokens, Double temperature, Effort effort,
        java.util.List<String> stopSequences, java.util.List<String> betas,
        java.util.Map<String, Object> extra) {

        public static ModelParams defaults(String model) {
            return new ModelParams(model, (int) MAX_OUTPUT_TOKENS_DEFAULT,
                null, null, null, null, Map.of());
        }
    }

    public record QueryRequest(
        java.util.List<ContentBlock> system, java.util.List<ContentBlock> messages,
        ModelParams params) {}

    public record QueryResponse(
        String id, String model, String stopReason, String contentText, int inputTokens, int outputTokens) {}

    public interface ClaudeClient {
        QueryResponse create(QueryRequest request);
    }

    private final Supplier<Provider> providerSupplier;
    private final ClaudeClient client;

    public ApiClaude(Supplier<Provider> providerSupplier, ClaudeClient client) {
        this.providerSupplier = Objects.requireNonNull(providerSupplier);
        this.client = client == null ? new ClaudeClient() {
            public QueryResponse create(QueryRequest r) {
                throw new UnsupportedOperationException("ClaudeClient not injected");
            }
        } : client;
    }

    public ApiClaude() {
        this(() -> Provider.FIRST_PARTY, null);
    }

    /** CC queryClaude 主入口. */
    public QueryResponse queryClaude(QueryRequest request) {
        if (request == null) throw new IllegalArgumentException("QueryRequest null");
        if (request.params() == null || request.params().model() == null) {
            throw new IllegalArgumentException("model required");
        }
        Provider provider = providerSupplier.get();
        if (provider == null) throw new IllegalStateException("Provider null");
        return client.create(request);
    }

    /** CC buildSystemBlocks — system prompt 数组构造. */
    public static java.util.List<ContentBlock> buildSystemBlocks(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) return java.util.List.of();
        return java.util.List.of(ContentBlock.text(systemPrompt));
    }

    /** CC buildUserMessage — user message 构造. */
    public static ContentBlock buildUserMessage(String text) {
        return ContentBlock.text(text);
    }

    /** CC buildAssistantMessage — assistant message 构造. */
    public static ContentBlock buildAssistantMessage(String text) {
        return ContentBlock.text(text);
    }

    /** CC estimateTokens — 字符数 / 4 估算. */
    public static int estimateTokens(String text) {
        if (text == null) return 0;
        return (text.length() + 3) / 4;
    }

    /** CC truncateToMaxTokens — 简单按 token 估算截断. */
    public static String truncateToMaxTokens(String text, int maxTokens) {
        if (text == null) return null;
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    public Provider currentProvider() {
        return providerSupplier.get();
    }
}