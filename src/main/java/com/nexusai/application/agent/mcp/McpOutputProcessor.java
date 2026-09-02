package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.tool.ToolResultStorage;

/**
 * MCP 大结果输出处理器 · 对齐 CC {@code client.ts processMCPResult}（:2720-2799）+
 * {@code utils/mcpValidation.ts}（:14-208）+ {@code utils/mcpOutputStorage.ts}（:16-59）。
 *
 * <p>[impl-I-4 T5] 结果三件套后半：
 * <ul>
 *   <li>{@link #processMCPResult}：估算 token → 超阈值 → 落盘 + 读取指令；含图片 → 截断；
 *       persist 失败 → 错误文本</li>
 *   <li>{@link #mcpContentNeedsTruncation}：先估算（{@code tokens*4} 字符）粗判 + 0.5 因子
 *       （CC MCP_TOKEN_COUNT_THRESHOLD_FACTOR=0.5）</li>
 *   <li>{@link #truncateMcpContentIfNeeded}：超阈值 → 前 maxChars + 截断提示</li>
 * </ul>
 *
 * <p>受控偏差（登记）：CC {@code countMessagesTokensWithAPI} 无 Java 等价 → 字符估算
 * （tokens*4）+ 0.5 因子粗判（规则五 确定性转换由代码处理）；ENABLE_MCP_LARGE_OUTPUT_FILES
 * Java 默认开（对齐 CC 生产默认）→ 大结果走落盘；指令文本按计划要求中文。
 */
public final class McpOutputProcessor {

    /** CC mcpValidation.ts:14 MCP_TOKEN_COUNT_THRESHOLD_FACTOR · 阈值先估算粗判因子。 */
    public static final double MCP_TOKEN_COUNT_THRESHOLD_FACTOR = 0.5;

    /** CC mcpValidation.ts:15 IMAGE_TOKEN_ESTIMATE · 图片块 token 估算。 */
    public static final int IMAGE_TOKEN_ESTIMATE = 1600;

    /** CC mcpValidation.ts:16 DEFAULT_MAX_MCP_OUTPUT_TOKENS · 默认输出 token 上限。 */
    public static final int DEFAULT_MAX_MCP_OUTPUT_TOKENS = 25000;

    private McpOutputProcessor() {
        // 工具类
    }

    /**
     * 处理 MCP 工具结果：超阈值 → 落盘 + 读取指令；否则原样返回。
     * 对齐 CC {@code processMCPResult}（client.ts:2720-2799）。
     *
     * @param transformed transformMCPResult 产物
     * @param tool        工具名（persistId 用）
     * @param name        server 名（'ide' 直返 + persistId 用）
     * @param ctx         落盘上下文（null → 截断降级）
     * @param maxTokens   输出 token 上限（{@code nexusai.mcp.output.max-tokens}）
     * @return 最终 tool result content
     */
    public static String processMCPResult(McpResultTransformer.TransformedMCPResult transformed,
                                          String tool, String name,
                                          McpResultTransformer.TransformContext ctx,
                                          int maxTokens) {
        String content = transformed.content();
        // CC :2725-2727 IDE 工具不进模型 → 不处理大输出
        if ("ide".equals(name)) {
            return content;
        }
        // CC :2730 mcpContentNeedsTruncation → false 直接返回
        if (!mcpContentNeedsTruncation(content, maxTokens)) {
            return content;
        }
        int sizeEstimate = getContentSizeEstimate(content, maxTokens);
        // CC ENABLE_MCP_LARGE_OUTPUT_FILES 生产默认开 → 走落盘；含图片 → 截断（:2758-2765）
        if (transformed.containsImages()) {
            logEvent("tengu_mcp_large_result_handled", "truncated", "contains_images", sizeEstimate);
            return truncateMcpContentIfNeeded(content, maxTokens);
        }
        if (ctx == null || ctx.workspaceDir() == null || ctx.sessionId() == null) {
            // 无落盘上下文 → 截断降级（不悬挂）
            return truncateMcpContentIfNeeded(content, maxTokens);
        }

        // CC :2768-2770 persistId = `mcp-${normalize(name)}-${normalize(tool)}-${timestamp}`
        String persistId = "mcp-" + McpStringUtils.normalizeNameForMCP(name)
            + "-" + McpStringUtils.normalizeNameForMCP(tool) + "-" + System.currentTimeMillis();
        ToolResultStorage.PersistedToolResult persist = ToolResultStorage.persistToolResult(
            ctx.workspaceDir(), ctx.sessionId(), content, persistId).join();
        if (persist == null) {
            // CC :2781-2783 persist 失败 → 错误文本（含截断提示）
            logEvent("tengu_mcp_large_result_handled", "truncated", "persist_failed", sizeEstimate);
            return "错误：结果（" + content.length() + " 字符）超过最大允许 token。输出保存到文件失败。"
                + "如果该 MCP 服务器提供分页或过滤工具，请使用它们检索数据的特定部分。";
        }
        logEvent("tengu_mcp_large_result_handled", "persisted", "file_saved", sizeEstimate);
        String formatDescription = getFormatDescription(transformed.type(), transformed.schema());
        return getLargeOutputInstructions(persist.filepath(), persist.originalSize(), formatDescription);
    }

    /**
     * 估算 content 是否需截断 · 对齐 CC {@code mcpContentNeedsTruncation}（mcpValidation.ts:151-178）。
     *
     * <p>CC 先估算粗判（estimate <= maxTokens*0.5 → false），再 countMessagesTokensWithAPI 精确判定。
     * Java 无 API 计数 → 粗判估算值直接作为 token 数（估算 = 字符数/4）。
     */
    public static boolean mcpContentNeedsTruncation(String content, int maxTokens) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        int estimate = getContentSizeEstimate(content, maxTokens);
        if (estimate <= maxTokens * MCP_TOKEN_COUNT_THRESHOLD_FACTOR) {
            return false;
        }
        return estimate > maxTokens;
    }

    /**
     * 截断 content（超阈值）· 对齐 CC {@code truncateMcpContentIfNeeded}（mcpValidation.ts:200-208）。
     *
     * @param content   内容字符串
     * @param maxTokens 输出 token 上限
     * @return 截断后内容 + 截断提示
     */
    public static String truncateMcpContentIfNeeded(String content, int maxTokens) {
        if (!mcpContentNeedsTruncation(content, maxTokens)) {
            return content;
        }
        int maxChars = maxTokens * 4;
        String truncated = content.length() <= maxChars
            ? content : content.substring(0, maxChars);
        return truncated + getTruncationMessage(maxTokens);
    }

    /** CC getTruncationMessage（mcpValidation.ts:88-94）· 截断提示。 */
    static String getTruncationMessage(int maxTokens) {
        return "\n\n[OUTPUT TRUNCATED - exceeded " + maxTokens + " token limit]\n\n"
            + "The tool output was truncated. If this MCP server provides pagination or "
            + "filtering tools, use them to retrieve specific portions of the data. If "
            + "pagination is not available, inform the user that you are working with "
            + "truncated output and results may be incomplete.";
    }

    /**
     * 估算 content token · 对齐 CC {@code getContentSizeEstimate}（mcpValidation.ts:59-75）。
     * 字符串估算 = 字符数/4（roughTokenCountEstimation 近似）；图片块 +1600。
     *
     * @param content   内容字符串（可为 JSON 序列化数组）
     * @param maxTokens 仅用于图片块判定（未用，保留签名对齐）
     */
    public static int getContentSizeEstimate(String content, int maxTokens) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        // 粗略估算：4 字符 ≈ 1 token（CC roughTokenCountEstimation）
        int chars = content.length();
        int tokenEstimate = Math.max(1, chars / 4);
        // 含图片块 → 每块 +1600（近似；JSON 序列化无法精确计数 → 按 IMAGE_TOKEN_ESTIMATE 粗算）
        if (content.contains("\"type\":\"image\"")) {
            int imageCount = countOccurrences(content, "\"type\":\"image\"");
            tokenEstimate += imageCount * IMAGE_TOKEN_ESTIMATE;
        }
        return tokenEstimate;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** CC getFormatDescription（mcpOutputStorage.ts:16-28）· 格式描述（中文计划要求）。 */
    static String getFormatDescription(String type, String schema) {
        if (schema == null || schema.isBlank()) {
            return switch (type == null ? "" : type) {
                case "toolResult" -> "纯文本";
                case "structuredContent" -> "JSON";
                case "contentArray" -> "JSON 数组";
                default -> "文本";
            };
        }
        return switch (type == null ? "" : type) {
            case "toolResult" -> "纯文本";
            case "structuredContent" -> "JSON，schema: " + schema;
            case "contentArray" -> "JSON 数组，schema: " + schema;
            default -> "文本，schema: " + schema;
        };
    }

    /**
     * CC getLargeOutputInstructions（mcpOutputStorage.ts:39-59）· 中文读取指令（计划要求）。
     */
    static String getLargeOutputInstructions(String rawOutputPath, int contentLength,
                                             String formatDescription) {
        return "错误：结果（" + String.format("%,d", contentLength) + " 字符）超过最大允许 token。"
            + "输出已保存到 " + rawOutputPath + "。\n"
            + "格式：" + formatDescription + "\n"
            + "使用 offset 和 limit 参数读取文件的特定部分，在其中搜索特定内容，并用 jq 进行结构化查询。\n"
            + "汇总/分析/审阅要求：\n"
            + "- 您必须从 " + rawOutputPath + " 处的文件中按顺序分块读取内容，直到 100% 的内容已被读取。\n"
            + "- 如果读取文件时收到截断警告（\"[N lines truncated]\"），请减小块大小，直到在无截断的情况下读取 100% 的内容。\n"
            + "- 在生成任何汇总或分析之前，您必须明确描述您已读取内容的哪些部分。"
            + "***如果您没有读取完整内容，您必须明确说明这一点。***\n";
    }

    /** 数据流日志（对齐 CC logEvent tengu_mcp_large_result_handled，Java 用 slf4j）。 */
    private static void logEvent(String event, String outcome, String reason, int sizeEstimateTokens) {
        // Java 无 analytics 通道 → slf4j debug 记录（对齐 CC 事件语义）
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpOutputProcessor.class);
        if (log.isDebugEnabled()) {
            log.debug("[McpOutputProcessor] {} outcome={} reason={} sizeEstimateTokens={}",
                event, outcome, reason, sizeEstimateTokens);
        }
    }
}
