package com.nexusai.application.agent.permission.hook;

/**
 * Prompt 请求回调 (绑定版) · 对齐 CC {@code execCommandHook} 的
 * {@code requestPrompt?: (request: PromptRequest) => Promise<PromptResponse>} 参数
 * (Open-ClaudeCode/src/utils/hooks.ts:759)。
 *
 * <p>WHY: CC command hook 的 stdout 流式输出里若出现 {@code {prompt, message, options}} 行,
 * execCommandHook 通过该回调向用户发起交互式询问, 拿到 {@link PromptResponse} 后串行写回
 * hook stdin. Java 端用函数式接口等价表达; 调用方 (HookRegistry / StreamingToolExecutor /
 * 上层会话) 注入实现, 不注入 (null) 时 prompt 检测分支不激活 (stdin 在首轮写入后立即关闭)。
 *
 * <p>本接口是<b>绑定版</b> (已绑 sourceName + toolInputSummary)。未绑定工厂见
 * {@link PromptRequesterFactory} (对齐 CC executeHooks 的 {@code requestPrompt} 参数,
 * hooks.ts:1972-1975)。
 *
 * <p><b>CC 真源签名 (hooks.ts:759)</b>:
 * {@code requestPrompt?: (request: PromptRequest) => Promise<PromptResponse>}.
 *
 */
@FunctionalInterface
public interface PromptRequester {

    /**
     * 处理一个 prompt 请求, 返回用户响应.
     *
     * @param request CC original: request (hooks.ts:759); 来自 hook stdout 的 prompt 请求
     * @return 用户响应 (写回 hook stdin)
     */
    PromptResponse request(PromptRequest request);
}
