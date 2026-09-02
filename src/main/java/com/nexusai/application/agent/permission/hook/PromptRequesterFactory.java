package com.nexusai.application.agent.permission.hook;

/**
 * Prompt 请求回调工厂 (未绑定) · 对齐 CC {@code executeHooks} / {@code executePreToolHooks} /
 * {@code executeUserPromptSubmitHooks} 的
 * {@code requestPrompt?: (sourceName: string, toolInputSummary?: string | null) => (request: PromptRequest) => Promise<PromptResponse>}
 * 参数 (Open-ClaudeCode/src/utils/hooks.ts:1972-1975 / :3402-3405 / :3830-3833)。
 *
 * <p>WHY: CC REPL 层 (REPL.tsx:2383-2391) 把 prompt 回调一次性创建 (闭包推入
 * {@code setPromptQueue} UI 队列), 经 toolUseContext.requestPrompt (REPL.tsx:2520,
 * {@code feature('HOOK_PROMPTS') ? requestPrompt : undefined} 门控) 透传给 hook 链
 * (toolHooks.ts:474 / processUserInput.ts:186); {@code executeHooks} 内绑定 hook 名 +
 * tool 输入摘要:
 * {@code boundRequestPrompt = requestPrompt?.(hookName, toolInputSummary)} (hooks.ts:1990),
 * 绑定结果 ({@link PromptRequester}) 传给 {@code execCommandHook} (hooks.ts:2460)。
 *
 * <p>Java 端 {@link PromptRequesterFactory#bind(String, String)} 即 CC 未绑定 requestPrompt;
 * 绑定结果 {@link PromptRequester} 对齐 hooks.ts:759. 工厂由 UI 消费端实现 (等价
 * setPromptQueue), 未注入 (null) 时通道关闭 — 对齐 CC {@code feature('HOOK_PROMPTS')}
 * 编译期宏关闭 (发布产物 package/cli.js v2.1.88 编译为 {@code requestPrompt: undefined})。
 *
 * <p><b>CC 真源签名 (hooks.ts:1972-1975)</b>:
 * {@code requestPrompt?: (sourceName: string, toolInputSummary?: string | null) => (request: PromptRequest) => Promise<PromptResponse>}.
 */
@FunctionalInterface
public interface PromptRequesterFactory {

    /**
     * 绑定 hook 名与 tool 输入摘要, 返回绑定版 {@link PromptRequester}.
     *
     * @param sourceName       CC original: sourceName (hooks.ts:1973); hook 名 (CC
     *                         {@code hookName = `${hookEvent}:${matchQuery}`}, 如
     *                         "PreToolUse:Bash"), UI 展示上下文 (等价 REPL title)
     * @param toolInputSummary CC original: toolInputSummary (hooks.ts:1974); 工具输入摘要,
     *                         null = 工具未提供 (CC optional ?.)
     * @return 绑定版回调 (对齐 hooks.ts:759); UI 消费端决定返回实现
     */
    PromptRequester bind(String sourceName, String toolInputSummary);
}
