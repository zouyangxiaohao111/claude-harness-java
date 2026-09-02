package com.nexusai.infra.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * OpenAI countTokens 客户端 · 对齐 CC roughTokenCountEstimation
 * （Open-ClaudeCode/src/services/tokenEstimation.ts:203-208）+ countTokensWithFallback
 * （analyzeContext.ts:77-109）。
 *
 * <p><b>背景</b>：OpenAI 无独立 count_tokens 端点（Anthropic 专有），CC 走 Haiku fallback / rough
 * 估算兜底（analyzeContext.ts:77-109）。Java 端用 tiktoken（jtokkit）本地分段估算，比 CC rough
 * 估算（{@code content.length() / 4}，一刀切 4 bytes/token）更精准，语义等价 CC 兜底意图。
 *
 * <p><b>对齐要点</b>：
 * <ol>
 *   <li>空内容短路返回 0（对齐 tokenEstimation.ts:127-130）；</li>
 *   <li>tiktoken 分段数当前上下文（非 HTTP 请求，纯本地估算，不伪造 API 调用）；</li>
 *   <li>模型映射：gpt-4o / o1 / o3 系列 → O200K_BASE（若 jtokkit 支持），其余 → CL100K_BASE；
 *       未知模型 / Registry 失败 → CL100K_BASE 回落（不返回 null，比 CC rough 更精准）；</li>
 *   <li>估算异常 → null（对齐 CC catch → null → 调用方 0 语义，analyzeContext.ts:308 tokens||0）。</li>
 * </ol>
 *
 * <p><b>usage.prompt_tokens 参考登记</b>：analyze 结果中的"最近一次实际用量"（usage.prompt_tokens
 * 来自 LLM 响应）仅作参考展示，非主力计数路径（不可任意计数，仅事后数据）——登记于 ContextAnalyzeService，
 * 本类不处理 usage 字段。
 *
 * <p><b>JavaDoc CC 原名标注</b>：
 * <ul>
 *   <li>roughTokenCountEstimation: Open-ClaudeCode/src/services/tokenEstimation.ts:203-208</li>
 *   <li>countTokensWithFallback: Open-ClaudeCode/src/utils/analyzeContext.ts:77-109</li>
 * </ul>
 */
public class OpenAICountTokensClient implements CountTokensClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAICountTokensClient.class);

    /**
     * jtokkit Registry（进程级共享，线程安全）· Encodings.newDefaultEncodingRegistry() 构造
     * 包含所有内置 encoding（CL100K_BASE / O200K_BASE 等）。
     */
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    /** CL100K_BASE 编码（gpt-4 / gpt-3.5-turbo / 默认回落）。 */
    private static final Encoding CL100K_BASE = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /** O200K_BASE 编码（gpt-4o / o1 / o3 系列）· jtokkit 1.1.0+ 支持。 */
    private static final Encoding O200K_BASE = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    /** mainLoopModel 供应器 · CC original: getMainLoopModel()（tokenEstimation.ts:146）。 */
    private final Supplier<String> modelSupplier;

    /**
     * 构造 · 惰性解析 model（对齐 buildForkSuppliers 模式，bean 构造期不锁定 mock）。
     *
     * @param modelSupplier 主循环模型（不可得 → 回落 CL100K_BASE，不返回 null）
     */
    public OpenAICountTokensClient(Supplier<String> modelSupplier) {
        this.modelSupplier = modelSupplier;
    }

    @Override
    public Integer countTokens(String content) {
        // 空内容短路 → 0（对齐 tokenEstimation.ts:127-130）
        if (content == null || content.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[OpenAICountTokensClient] 空内容 → 0（tokenEstimation.ts:127-130 短路）");
            }
            return 0;
        }
        try {
            Encoding encoding = resolveEncoding();
            int tokens = encoding.countTokens(content);
            if (log.isDebugEnabled()) {
                log.debug("[OpenAICountTokensClient] countTokens 成功：content.length={} tokens={} encoding={}",
                    content.length(), tokens, encoding.getName());
            }
            return tokens;
        } catch (Exception e) {
            // 估算异常 → null（对齐 CC catch → null → 调用方 0，tokenEstimation.ts:196-199）
            if (log.isWarnEnabled()) {
                log.warn("[OpenAICountTokensClient] tiktoken 估算异常 → null → section 记 0：{}", e.toString());
            }
            return null;
        }
    }

    @Override
    public Integer countTokensForTools(List<ToolSchema> tools) {
        // 空工具列表短路 → 0（与 countTokens(String) 同语义）
        if (tools == null || tools.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[OpenAICountTokensClient] 空 tools 列表 → 0（countTokensForTools 短路）");
            }
            return 0;
        }
        try {
            // tools 数组序列化 JSON → tiktoken 分段（对齐 CC countTokensWithFallback([], toolSchemas)
            // analyzeContext.ts:250，CC 把 tools schema 随请求发送；Java 端本地估算 = 序列化 JSON 分段）
            String toolsJson = serializeToolsToJson(tools);
            Encoding encoding = resolveEncoding();
            int tokens = encoding.countTokens(toolsJson);
            if (log.isDebugEnabled()) {
                log.debug("[OpenAICountTokensClient] countTokensForTools 成功：tools={} tokens={} encoding={}",
                    tools.size(), tokens, encoding.getName());
            }
            return tokens;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[OpenAICountTokensClient] countTokensForTools tiktoken 估算异常 → null → tools 记 0：{}",
                    e.toString());
            }
            return null;
        }
    }

    /**
     * 按模型名解析 tiktoken encoding · CC 语义：gpt-4o / o1 / o3 用 O200K_BASE，其余用 CL100K_BASE。
     *
     * <p>模型不可得 → 回落 CL100K_BASE（不返回 null，比 CC rough 估算更精准）。
     *
     * @return 编码实例（永不返回 null，最差回落 CL100K_BASE）
     */
    private Encoding resolveEncoding() {
        String model = modelSupplier.get();
        if (model == null || model.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[OpenAICountTokensClient] model 不可得 → 回落 CL100K_BASE"
                    + "（CC getMainLoopModel 失败→null，Java 端比 CC rough 更精准，不返回 null）");
            }
            return CL100K_BASE;
        }
        String modelLower = model.toLowerCase();
        // gpt-4o / gpt-4o-mini / o1 / o1-mini / o1-pro / o3 / o4 系列 → O200K_BASE（jtokkit 1.1.0 已支持）
        boolean usesO200k = modelLower.startsWith("gpt-4o")
            || modelLower.startsWith("o1")
            || modelLower.startsWith("o3")
            || modelLower.startsWith("o4");
        if (usesO200k) {
            return O200K_BASE;
        }
        // 其余 → CL100K_BASE（gpt-4 / gpt-3.5-turbo / 未知模型，比 CC rough 更精准）
        return CL100K_BASE;
    }

    /**
     * 工具 schema 列表序列化 JSON（本地估算用）· CC 语义：tools 数组随请求发送
     * （tokenEstimation.ts:172-187），Java 端本地估算 = 序列化 JSON → tiktoken 分段。
     *
     * <p>序列化格式：JSON 数组，每个元素 {name, description, input_schema}（对齐 CC toolToAPISchema
     * 产物，analyzeContext.ts:250）。
     */
    private static String serializeToolsToJson(List<ToolSchema> tools) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
            for (ToolSchema schema : tools) {
                com.fasterxml.jackson.databind.node.ObjectNode obj = mapper.createObjectNode();
                obj.put("name", schema.name());
                if (schema.description() != null && !schema.description().isBlank()) {
                    obj.put("description", schema.description());
                }
                obj.set("input_schema",
                    schema.inputSchema() == null ? mapper.createObjectNode() : schema.inputSchema());
                arr.add(obj);
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            // 序列化失败 → 用 toString 兜底（不应发生，但 fail-loud）
            if (log.isWarnEnabled()) {
                log.warn("[OpenAICountTokensClient] tools JSON 序列化失败 → toString 兜底：{}", e.toString());
            }
            return tools.toString();
        }
    }
}
