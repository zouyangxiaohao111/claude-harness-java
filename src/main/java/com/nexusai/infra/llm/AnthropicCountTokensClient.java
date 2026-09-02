package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Anthropic countTokens 客户端 · 对齐 CC {@code countMessagesTokensWithAPI}
 * （Open-ClaudeCode/src/services/tokenEstimation.ts:140-201）走真实 LLM countTokens API。
 *
 * <p><b>端点（grep 复验 @anthropic-ai/sdk bundled）</b>：
 * {@code POST {baseUrl}/v1/messages/count_tokens}，SDK（cli.js countTokens）追加
 * {@code anthropic-beta: token-counting-2024-11-01} header，响应取 {@code input_tokens}。
 * Java 端复用 AnthropicSdkProvider 的 HttpClient/REST 模式（baseUrl normalize + x-api-key +
 * anthropic-version + JSON body）。
 *
 * <p><b>对齐要点（tokenEstimation.ts:124-201）</b>：
 * <ol>
 *   <li>空内容短路返回 0（:127-130）；</li>
 *   <li>model = 注入的 mainLoopModel supplier（CC :146 getMainLoopModel()）；不可得 → null；</li>
 *   <li>config（baseUrl+apiKey）不可用 → null（CC getAnthropicClient 失败 → catch → null）；</li>
 *   <li>{@code input_tokens} 非 number → null（:189-193，Vertex/Bedrock 行为防御）；</li>
 *   <li>异常 catch → null（:196-199 logError）。</li>
 * </ol>
 *
 * <p><b>Java 适配偏差登记</b>：
 * <ul>
 *   <li>CC :150-170 bedrock/vertex 分支、:167-170 betas 过滤（VERTEX_COUNT_TOKENS_ALLOWED_BETAS）——
 *       Java 仅 anthropic provider 直连，无 bedrock/vertex 通道，跳过（行为等价：API 失败 → null）；</li>
 *   <li>CC getModelBetas(model) 注入请求 header（SDK 并入 anthropic-beta）——Java 端仅固定
 *       {@code token-counting-2024-11-01}，不追加模型级 betas（不阻塞计数，API 仍可计数）；</li>
 *   <li>CC hasThinkingBlocks / thinking 参数（:148/:181-186）——本路径单 user 文本消息无 thinking
 *       块，跳过；</li>
 *   <li>SDK 请求带 {@code ?beta=true} query——Java 端与 AnthropicSdkProvider 主路径一致省略（仅 header
 *       表达 beta 语义）。</li>
 * </ul>
 */
public class AnthropicCountTokensClient implements CountTokensClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCountTokensClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Anthropic API version（与 AnthropicSdkProvider 同源）。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** count_tokens 端点 beta header · @anthropic-ai/sdk bundled cli.js countTokens。 */
    private static final String COUNT_TOKENS_BETA_HEADER = "token-counting-2024-11-01";

    /** HTTP 读/连超时（秒）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** provider 运行时配置（baseUrl + apiKey）· 对齐 StreamCompactSummary configSupplier 惰性解析。 */
    private final Supplier<ProviderConfig> configSupplier;

    /** mainLoopModel 供应器 · CC original: getMainLoopModel()（tokenEstimation.ts:146）。 */
    private final Supplier<String> modelSupplier;

    /**
     * 构造 · 惰性解析 model/config（对齐 buildForkSuppliers 模式，bean 构造期不锁定 mock）。
     *
     * @param configSupplier provider 配置（不可用 → ProviderConfig.empty() → 计数失败 → null）
     * @param modelSupplier  主循环模型（不可得 → null → 计数失败 → null）
     */
    public AnthropicCountTokensClient(Supplier<ProviderConfig> configSupplier, Supplier<String> modelSupplier) {
        this.configSupplier = configSupplier;
        this.modelSupplier = modelSupplier;
    }

    @Override
    public Integer countTokens(String content) {
        // 空内容短路 → 0（tokenEstimation.ts:127-130）
        if (content == null || content.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicCountTokensClient] 空内容 → 0（tokenEstimation.ts:127-130）");
            }
            return 0;
        }
        String model = modelSupplier.get();
        if (model == null || model.isBlank()) {
            if (log.isWarnEnabled()) {
                log.warn("[AnthropicCountTokensClient] mainLoopModel 不可得（model={}）→ null → section 记 0 · "
                    + "对齐 CC getMainLoopModel 失败→null→0（tokenEstimation.ts:146）", model);
            }
            return null;
        }
        ProviderConfig config = configSupplier.get();
        if (config == null || !config.isUsable()) {
            if (log.isWarnEnabled()) {
                log.warn("[AnthropicCountTokensClient] ProviderConfig 不可用 → null → section 记 0（model={}）", model);
            }
            return null;
        }
        try {
            String url = normalizeBaseUrl(config.baseUrl()) + "/v1/messages/count_tokens";
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

            // 请求体：{model, messages:[{role:'user',content}], tools:[]}（tokenEstimation.ts:172-187
            //   countTokensWithAPI → 单 user 消息 + tools=[]；analyzeContext.ts:301 同形）
            com.fasterxml.jackson.databind.node.ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            com.fasterxml.jackson.databind.node.ArrayNode messages = JSON.createArrayNode();
            com.fasterxml.jackson.databind.node.ObjectNode userMsg = JSON.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", content);
            messages.add(userMsg);
            body.set("messages", messages);
            body.set("tools", JSON.createArrayNode());

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", COUNT_TOKENS_BETA_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

            if (log.isDebugEnabled()) {
                log.debug("[AnthropicCountTokensClient] count_tokens → POST {} model={} · CC tokenEstimation.ts:172", url, model);
            }
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                if (log.isWarnEnabled()) {
                    log.warn("[AnthropicCountTokensClient] count_tokens HTTP {} → null → section 记 0（model={}）",
                        resp.statusCode(), model);
                }
                return null;
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode inputTokens = root.path("input_tokens");
            // 非 number → null（tokenEstimation.ts:189-193：Vertex throws / Bedrock 未知操作防御）
            if (!inputTokens.isNumber()) {
                if (log.isWarnEnabled()) {
                    log.warn("[AnthropicCountTokensClient] input_tokens 非 number → null → section 记 0（model={}）",
                        model);
                }
                return null;
            }
            int tokens = inputTokens.asInt();
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicCountTokensClient] count_tokens 成功：input_tokens={}（model={}）", tokens, model);
            }
            return tokens;
        } catch (Exception e) {
            // 异常 catch → null（tokenEstimation.ts:196-199 logError）
            if (log.isWarnEnabled()) {
                log.warn("[AnthropicCountTokensClient] count_tokens 调用失败 → null → section 记 0：{}", e.toString());
            }
            return null;
        }
    }

    @Override
    public Integer countTokensForTools(List<ToolSchema> tools) {
        // 空工具列表短路 → 0（与 countTokens(String) 空内容同理）
        if (tools == null || tools.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicCountTokensClient] 空 tools 列表 → 0（countTokensForTools 短路）");
            }
            return 0;
        }
        String model = modelSupplier.get();
        if (model == null || model.isBlank()) {
            if (log.isWarnEnabled()) {
                log.warn("[AnthropicCountTokensClient] mainLoopModel 不可得（model={}）→ null → tools 记 0 · "
                    + "对齐 CC getMainLoopModel 失败→null→0（tokenEstimation.ts:146）", model);
            }
            return null;
        }
        ProviderConfig config = configSupplier.get();
        if (config == null || !config.isUsable()) {
            if (log.isWarnEnabled()) {
                log.warn("[AnthropicCountTokensClient] ProviderConfig 不可用 → null → tools 记 0（model={}）", model);
            }
            return null;
        }
        try {
            String url = normalizeBaseUrl(config.baseUrl()) + "/v1/messages/count_tokens";
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

            // 请求体：{model, messages:[{role:'user',content:'foo'}], tools:[...]}
            // CC tokenEstimation.ts:172-187：传 tools 无真实消息时补 dummy message（:174）
            com.fasterxml.jackson.databind.node.ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            com.fasterxml.jackson.databind.node.ArrayNode messages = JSON.createArrayNode();
            com.fasterxml.jackson.databind.node.ObjectNode dummyMsg = JSON.createObjectNode();
            dummyMsg.put("role", "user");
            dummyMsg.put("content", "foo");
            messages.add(dummyMsg);
            body.set("messages", messages);
            // tools 数组随请求发送（CC countTokensWithFallback([], toolSchemas) analyzeContext.ts:250）
            com.fasterxml.jackson.databind.node.ArrayNode toolsArr = JSON.createArrayNode();
            for (ToolSchema schema : tools) {
                com.fasterxml.jackson.databind.node.ObjectNode tool = JSON.createObjectNode();
                tool.put("name", schema.name());
                if (schema.description() != null && !schema.description().isBlank()) {
                    tool.put("description", schema.description());
                }
                tool.set("input_schema",
                    schema.inputSchema() == null ? JSON.createObjectNode() : schema.inputSchema());
                toolsArr.add(tool);
            }
            body.set("tools", toolsArr);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", COUNT_TOKENS_BETA_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

            if (log.isDebugEnabled()) {
                log.debug("[AnthropicCountTokensClient] countTokensForTools → POST {} model={} tools={} · "
                    + "CC analyzeContext.ts:250 countTokensWithFallback([],toolSchemas)", url, model, tools.size());
            }
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                if (log.isWarnEnabled()) {
                    log.warn("[AnthropicCountTokensClient] countTokensForTools HTTP {} → null → tools 记 0（model={}）",
                        resp.statusCode(), model);
                }
                return null;
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode inputTokens = root.path("input_tokens");
            if (!inputTokens.isNumber()) {
                if (log.isWarnEnabled()) {
                    log.warn("[AnthropicCountTokensClient] countTokensForTools input_tokens 非 number → null（model={}）",
                        model);
                }
                return null;
            }
            int tokens = inputTokens.asInt();
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicCountTokensClient] countTokensForTools 成功：input_tokens={}（model={}, tools={}）",
                    tokens, model, tools.size());
            }
            return tokens;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[AnthropicCountTokensClient] countTokensForTools 调用失败 → null → tools 记 0：{}",
                    e.toString());
            }
            return null;
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String u = baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
