package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Function hook 回调 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/sessionHooks.ts:15-18}
 * {@code FunctionHookCallback = (messages: Message[], signal?: AbortSignal) => boolean | Promise<boolean>}.
 *
 * <p>WHY (规则五): CC 回调返回 boolean (true=放行) 或 Promise&lt;boolean&gt;, 等价 Java 异步
 * 回调 {@link CompletableFuture}&lt;Boolean&gt;. 消息类型用项目统一的 {@link ChatMessageDto}
 * (与 {@code StreamingToolExecutor} 共用).
 *
 * <p><b>signal 参数 (D-06)</b>: CC 签名第二参是 {@code AbortSignal} (sessionHooks.ts:17) —
 * 回调经 {@code signal.aborted} 感知外部取消 (超时/父任务取消). Java 端以
 * {@link AbortController} 承载该信号 (先例: ExecPromptHook 以 AbortController 承载 combined
 * signal): 回调可经 {@link AbortController#isCancelled()} / {@link AbortController#onCancel}
 * 感知取消, 提前终止自身工作. 控制器由执行方每次调用新建, 作用域仅本次执行; 回调
 * 调用 {@code abort()} 只会取消自身信号, 无下游副作用 (CC 纯 AbortSignal 不暴露 abort,
 * 此处放宽为控制器, 角色等价: 只读 isCancelled/订阅 onCancel).
 *
 * @see FunctionHook
 * @see SessionHookStore#addFunctionHook(String, HookEventType, String, FunctionHookCallback, String, Long, String)
 */
@FunctionalInterface
public interface FunctionHookCallback {

    /**
     * 执行内存校验回调 · 对齐 CC (messages, signal?) => boolean | Promise&lt;boolean&gt;.
     *
     * @param messages 当前会话消息列表 (CC Message[]; Java 端 ChatMessageDto)
     * @param signal   本次执行的取消信号 (CC AbortSignal 等价物); 超时/取消时执行方先
     *                 abort 再返回结果, 回调可经 isCancelled()/onCancel 提前停止
     * @return true = 校验通过放行; false = 拦截 (与 CC boolean 语义一致); 异步用完成的 Future
     */
    CompletableFuture<Boolean> apply(List<ChatMessageDto> messages, AbortController signal);
}
