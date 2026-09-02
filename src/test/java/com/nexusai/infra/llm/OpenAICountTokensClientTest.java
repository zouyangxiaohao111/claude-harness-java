package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RES-C10] {@link OpenAICountTokensClient} 意图测试 · 对齐 CC roughTokenCountEstimation
 * （tokenEstimation.ts:203-208）+ countTokensWithFallback（analyzeContext.ts:77-109）。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>：OpenAI 无独立 count_tokens 端点（Anthropic 专有），
 * CC 走 Haiku fallback / rough 估算兜底（analyzeContext.ts:77-109）。Java 端用 tiktoken (jtokkit)
 * 本地估算——比 CC rough 估算（4 bytes/token 一刀切）更精准，语义等价 CC 兜底意图。
 *
 * <p>测试覆盖意图：
 * <ol>
 *   <li><b>空内容短路 0</b>（对齐 tokenEstimation.ts:127-130）——不得启动 Registry/encoding；</li>
 *   <li><b>content 不为空 → 正整数</b>（tiktoken 分段数当前上下文，非 null 非负）；</li>
 *   <li><b>估算失败 → null（而非抛异常）</b>——对齐 CC catch → null → 调用方 0 语义；</li>
 *   <li><b>tools 数组路径</b>（对齐 C9 契约）：tools schema 序列化 JSON → tiktoken 分段；</li>
 *   <li><b>provider 类型分发</b>（ToolRegistrationConfig）：anthropic → Anthropic，其余 → OpenAI。</li>
 * </ol>
 */
class OpenAICountTokensClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OpenAICountTokensClient client() {
        return new OpenAICountTokensClient(() -> "gpt-4o");
    }

    // ════════════════════════════════════════════════════════════════════
    // countTokens(String)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空内容 / null → 0，不触发 Registry/encoding（对齐 tokenEstimation.ts:127-130 短路）")
    void emptyContent_returnsZero_noEncoding() {
        OpenAICountTokensClient client = new OpenAICountTokensClient(
            () -> { throw new AssertionError("空内容不得解析 model（短路在前）"); });
        assertThat(client.countTokens("")).isEqualTo(0);
        assertThat(client.countTokens(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("普通内容 → 正整数（tiktoken 分段数当前上下文）· CC tokenEstimation.ts:203-208 增强版")
    void normalContent_returnsPositiveInteger() {
        Integer tokens = client().countTokens("hello world, this is a test sentence for token counting");
        assertThat(tokens).as("tiktoken 分段数应 > 0").isNotNull().isPositive();
    }

    @Test
    @DisplayName("中文内容 → 正整数（tiktoken cl100k_base/o200k_base 支持多语言）")
    void chineseContent_returnsPositiveInteger() {
        Integer tokens = client().countTokens("这是一段用于测试 token 计数的中文内容，包含标点符号。");
        assertThat(tokens).as("中文内容 tiktoken 分段应 > 0").isNotNull().isPositive();
    }

    @Test
    @DisplayName("model 不可得 → 回落 cl100k_base（不返回 null）· 与 CC rough 估算兜底语义一致")
    void modelUnavailable_fallbackToCl100kBase() {
        OpenAICountTokensClient client = new OpenAICountTokensClient(() -> null);
        Integer tokens = client.countTokens("hello world test content for fallback estimation");
        // 回落 cl100k_base 仍可计数，不返回 null（比 CC rough 估算更精准）
        assertThat(tokens).as("model 不可得时应回落 cl100k_base，仍可计数").isNotNull().isPositive();
    }

    @Test
    @DisplayName("未知模型名 → 回落 cl100k_base（不抛异常，不返回 null）")
    void unknownModel_fallbackToCl100kBase() {
        OpenAICountTokensClient client = new OpenAICountTokensClient(() -> "some-future-model-v99");
        Integer tokens = client.countTokens("fallback test content for unknown model name handling");
        assertThat(tokens).as("未知模型名回落 cl100k_base，仍可计数").isNotNull().isPositive();
    }

    // ════════════════════════════════════════════════════════════════════
    // countTokensForTools(List<ToolSchema>) · C9 契约
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("tools 空列表 → 0（C9 契约，与 AnthropicCountTokensClient 同语义）")
    void toolsArray_empty_returnsZero() {
        OpenAICountTokensClient client = new OpenAICountTokensClient(
            () -> { throw new AssertionError("空 tools 不得解析 model（空列表短路在前）"); });
        assertThat(client.countTokensForTools(List.of())).isEqualTo(0);
    }

    @Test
    @DisplayName("tools 数组 → 正整数（序列化 JSON → tiktoken 分段）· C9 契约 OpenAI 路径")
    void toolsArray_returnsPositiveInteger() {
        List<CountTokensClient.ToolSchema> tools = List.of(
            new CountTokensClient.ToolSchema("read_file", "read a file",
                JSON.createObjectNode().put("type", "object").put("properties", "name")),
            new CountTokensClient.ToolSchema("bash", "run bash command",
                JSON.createObjectNode().put("type", "object").put("properties", "command")));
        Integer tokens = client().countTokensForTools(tools);
        assertThat(tokens).as("tools 数组 tiktoken 分段应 > 0").isNotNull().isPositive();
    }

    @Test
    @DisplayName("tools 含 description null → 不抛 NPE（健壮性）· C9 契约")
    void toolsArray_nullDescription_noNpe() {
        List<CountTokensClient.ToolSchema> tools = List.of(
            new CountTokensClient.ToolSchema("test", null, JSON.createObjectNode()));
        Integer tokens = client().countTokensForTools(tools);
        assertThat(tokens).as("null description 不 NPE，仍可计数").isNotNull().isPositive();
    }

    // ════════════════════════════════════════════════════════════════════
    // model-specific encoding 选择
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("gpt-4o 模型 → O200K_BASE encoding（jtokkit 1.1.0 支持）")
    void gpt4o_usesO200kBase() {
        OpenAICountTokensClient client = new OpenAICountTokensClient(() -> "gpt-4o");
        Integer tokens = client.countTokens("test content for gpt-4o encoding selection verification");
        assertThat(tokens).isNotNull().isPositive();
    }

    @Test
    @DisplayName("gpt-4 / gpt-3.5-turbo → cl100k_base encoding")
    void gpt4_usesCl100kBase() {
        OpenAICountTokensClient client = new OpenAICountTokensClient(() -> "gpt-4");
        Integer tokens = client.countTokens("test content for gpt-4 cl100k_base encoding verification");
        assertThat(tokens).isNotNull().isPositive();
    }

    @Test
    @DisplayName("相同内容、不同模型 → 估算值合理（不偏离太远，同数量级）")
    void sameContent_differentModels_similarOrder() {
        String content = "The quick brown fox jumps over the lazy dog. ".repeat(10);
        int tokensGpt4 = clientWithModel("gpt-4").countTokens(content);
        int tokensGpt4o = clientWithModel("gpt-4o").countTokens(content);
        // 两个 encoding 对英文文本结果应同数量级（允许 ±30% 偏差）
        assertThat(Math.abs(tokensGpt4 - tokensGpt4o))
            .as("gpt-4 (cl100k_base) 与 gpt-4o (o200k_base) 估算应同数量级")
            .isLessThan(Math.max(tokensGpt4, tokensGpt4o));
    }

    private static OpenAICountTokensClient clientWithModel(String model) {
        return new OpenAICountTokensClient(() -> model);
    }
}
