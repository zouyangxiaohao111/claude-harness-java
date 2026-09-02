package com.nexusai.application.agent.permission.hook;

import java.util.List;

/**
 * Prompt 请求 · 对齐 CC {@code Open-ClaudeCode/src/types/hooks.ts:28-42}
 * {@code promptRequestSchema} (Zod object)。
 *
 * <p>WHY: CC command hook 可向用户发起交互式 prompt (stdout 输出一行 JSON
 * {@code {prompt, message, options:[...]}}, 由 execCommandHook 逐行解析并串行回调
 * {@code requestPrompt}, 拿到 {@link PromptResponse} 后写回 hook stdin, hook 据此继续执行)。
 * Java 端用 record 等价表达, 供 {@link CommandHookExecutor} 的 prompt 检测分支使用。
 *
 * <p><b>CC 真源字段 (types/hooks.ts:29-39)</b>:
 * <ul>
 *   <li>{@code prompt} (:30) — 请求 ID (Zod string), 区分 prompt 类型的关键字
 *       (CC 注释: "The {@code prompt} key acts as discriminator ... with the id as its value")</li>
 *   <li>{@code message} (:31) — 展示给用户的提示文案</li>
 *   <li>{@code options} (:32-38) — 选项数组 [{key, label, description?}]</li>
 * </ul>
 *
 * @param prompt  CC original: prompt (types/hooks.ts:30); 请求 ID
 * @param message CC original: message (types/hooks.ts:31); 提示文案
 * @param options CC original: options (types/hooks.ts:32); 选项列表
 */
public record PromptRequest(
    String prompt,
    String message,
    List<Option> options
) {
    /**
     * Prompt 选项 · 对齐 CC types/hooks.ts:33-37 (Zod object {@code {key, label, description?}}).
     *
     * @param key         CC original: key (types/hooks.ts:34); 选项标识
     * @param label       CC original: label (types/hooks.ts:35); 展示标签
     * @param description CC original: description (types/hooks.ts:36); 可选描述 (Zod optional)
     */
    public record Option(String key, String label, String description) {
    }
}
