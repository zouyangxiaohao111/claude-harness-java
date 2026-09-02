package com.nexusai.application.agent.command;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Haiku 调用生成 session name · 对齐 CC commands/rename/generateSessionName.ts:10-67.
 *
 * <p>L1 语义: 提取 conversation text → Haiku 调用生成 2-4 词 kebab-case name → JSON 解析 → 返回.
 *            失败 (timeout/rate-limit/network) 返回 null (CC 注释: logForDebugging 而非 logError 避免 flood).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `generate(conversationText, llmInvoker) → CompletableFuture&lt;String&gt;`</li>
 *   <li><b>A2 Golden Trace</b>: 空 conversation → null (CC extractConversationText 空); 成功 → name 字符串</li>
 *   <li><b>A3</b>: LLM 抛错 → null (CC catch + logForDebugging); JSON 解析失败 → null</li>
 *   <li><b>A4</b>: 响应缺 name 字段 / 类型非 string → null (CC type guard)</li>
 *   <li><b>A5</b>: 真实场景 — Haiku 返回 {"name":"fix-login-bug"} → "fix-login-bug"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): BiFunction&lt;String,String,String&gt; 替代 CC queryHaiku({userPrompt}); CompletableFuture
 *                    替代 Promise; Optional.ofNullable 替代 undefined.
 *
 * <p><b>[prompt-align TOOLS-04] 生产注册接线结论</b>：本类 + {@link RenameCommand} 全仓
 * 无生产调用方（grep 复验：仅 ChatService 注释提及，无 {@code CommandRegistry.java}）。
 * Web 架构中无参自动生成名由 {@code ChatService.maybeGenerateTitle}（:1414-1475，
 * sessionTitle.ts 对齐 prompt）覆盖（有参显式命名走前端 PATCH /api/v1/sessions/{id}）——
 * 即 CC generateSessionName 的生产语义等价。本类保留为 CC 对齐的 CLI 命令实现但<b>不注册</b>。
 * 若后续需真实后端 REST suggest-name 端点，登记 待前端对接.md 待用户拍板，本批次不新增 API 面。
 */
public final class GenerateSessionName {

    private static final String SYSTEM_PROMPT =
        "Generate a short kebab-case name (2-4 words) that captures the main topic " +
        "of this conversation. Use lowercase words separated by hyphens. " +
        "Examples: \"fix-login-bug\", \"add-auth-feature\", \"refactor-api-client\", " +
        "\"debug-test-failures\". Return JSON with a \"name\" field.";

    private GenerateSessionName() {}

    /**
     * 生成 session name (CC generateSessionName).
     *
     * @param conversationText 已提取的对话文本 (CC extractConversationText); 空 → null
     * @param haikuInvoker    (systemPrompt, userPrompt) → JSON 字符串响应
     * @return Optional.of(name); 任何失败 → Optional.empty()
     */
    public static CompletableFuture<java.util.Optional<String>> generate(
            String conversationText,
            BiFunction<String, String, String> haikuInvoker) {
        if (conversationText == null || conversationText.isBlank()) {
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = haikuInvoker.apply(SYSTEM_PROMPT, conversationText);
                String name = parseNameField(content);
                return java.util.Optional.ofNullable(name);
            } catch (Exception e) {
                // CC: logForDebugging 而非 logError (避免 3rd bridge msg flood)
                return java.util.Optional.empty();
            }
        });
    }

    /** CC safeParseJSON + type guard (response.name 是 string). */
    static String parseNameField(String content) {
        if (content == null || content.isBlank()) return null;
        // CC safeParseJSON 支持 prose 包 JSON — 用 regex 提取第一个 {...} 块再解析
        var jsonBlockMatcher = java.util.regex.Pattern.compile("\\{[^{}]*\"name\"[^{}]*\\}")
            .matcher(content);
        String candidate = jsonBlockMatcher.find() ? jsonBlockMatcher.group() : content;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(candidate);
            if (node != null && node.has("name") && node.get("name").isTextual()) {
                return node.get("name").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}