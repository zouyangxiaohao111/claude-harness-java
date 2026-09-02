package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * countTokens 客户端策略接口 · 对齐 CC {@code countTokensWithFallback}
 * （Open-ClaudeCode/src/utils/analyzeContext.ts:77-109）。
 *
 * <p>两条计数路径（对齐 CC countTokensWithFallback 的两个调用场景）：
 * <ol>
 *   <li><b>逐 section 计数</b>（{@link #countTokens(String)}）：入参为单 user 消息、无 tools
 *       （analyzeContext.ts:301 {@code countTokensWithFallback([{role:'user',content}], [])}），
 *       用于 system prompt / memory 文件段；</li>
 *   <li><b>工具定义计数</b>（{@link #countTokensForTools(List)}）：入参为 tools 数组、无真实消息
 *       （analyzeContext.ts:250 {@code countTokensWithFallback([], toolSchemas)}），
 *       用于 built-in / MCP 工具段（CC countToolDefinitionTokens analyzeContext.ts:234-258）。</li>
 * </ol>
 *
 * <p>返回语义（CC number | null）：
 * <ul>
 *   <li>空内容/空工具 → {@code 0}（tokenEstimation.ts:127-130 countTokensWithAPI 短路）；</li>
 *   <li>API 成功 → {@code input_tokens}（tokenEstimation.ts:195）；</li>
 *   <li>API 失败 / {@code input_tokens} 非 number / model 或 config 不可得 → {@code null}
 *       （tokenEstimation.ts:189-199；CC Haiku 兜底在 Java 端无 count_tokens 通道，
 *       终极兜底 null → 调用方 {@code tokens||0} 记 0）。</li>
 * </ul>
 *
 * <p><b>RES-C9 变更</b>：原 @FunctionalInterface 升级为普通接口（新增 countTokensForTools 默认方法），
 * 既有 lambda 消费方（如 SystemPromptTokenCounter 测试的 {@code content -> 5}）不受影响
 * （单抽象方法 + 默认方法仍兼容 lambda）。
 */
public interface CountTokensClient {

    /**
     * 单 section 内容 token 计数 · CC original: countTokensWithFallback([{role:'user',content}], [])
     * （analyzeContext.ts:301）。
     *
     * @param content section 内容（非空；空 → 0 短路）
     * @return token 数或 null（null → 调用方按 0 处理，analyzeContext.ts:308 {@code tokens||0}）
     */
    Integer countTokens(String content);

    /**
     * 工具定义 token 计数 · CC original: countTokensWithFallback([], toolSchemas)
     * （analyzeContext.ts:250 countToolDefinitionTokens :234-258）。
     *
     * <p>tools 数组随请求发送（tokenEstimation.ts:172-187 请求体 {model, messages:[dummy], tools:[...]}），
     * 非把 schema 序列化为消息文本。默认实现返回 0（未知工具或无 tools 通道时调用方记 0，
     * analyzeContext.ts:257 {@code result ?? 0}）。
     *
     * <p><b>TOOL_TOKEN_COUNT_OVERHEAD 补偿</b>：API 返回的 input_tokens 包含约 500 token 的工具前缀
     * 开销（analyzeContext.ts:68-75），补偿逻辑由<b>调用方</b>（ContextAnalyzeService.countToolDefinitionTokens）
     * 按 {@code Math.max(0, raw - 500)} 扣减（analyzeContext.ts:479/:638-641），本方法返回原始值。
     *
     * @param tools 工具 schema 列表（CC toolToAPISchema 产物；空 → 0）
     * @return token 数（含 overhead；null → 调用方按 0）或 0（默认实现，无 tools 通道）
     */
    default Integer countTokensForTools(List<ToolSchema> tools) {
        return 0;
    }

    /**
     * 工具 schema · CC original: toolToAPISchema 产物（name/description/input_schema，
     * 用于 countTokensWithFallback([], toolSchemas) analyzeContext.ts:250 请求体 tools 数组元素）。
     *
     * @param name        工具名（CC original: tool.name → toolToAPISchema name）
     * @param description 工具描述（CC original: toolToAPISchema description，允许 null/空）
     * @param inputSchema 输入 schema（CC original: toolToAPISchema input_schema；null → 空 object）
     */
    record ToolSchema(String name, String description, JsonNode inputSchema) {
    }
}
