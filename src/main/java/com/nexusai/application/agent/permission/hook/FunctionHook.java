package com.nexusai.application.agent.permission.hook;

/**
 * Function hook record · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/sessionHooks.ts:24-31}
 * {@code type FunctionHook = { type:'function', id?, timeout?, callback, errorMessage, statusMessage? } }.
 *
 * <p>WHY (规则三): CC function hook 携带内存回调 (FunctionHookCallback), 仅 session 作用域,
 * 不可持久化 (CC L22-23 "Session-scoped only, cannot be persisted to settings.json";
 * getSessionHooks L288 "Filter out function hooks"). Java 端用 record 承载, 实现
 * {@link SessionHook} 的 {@code type()='function'} 语义.
 *
 * <p><b>字段来源对照</b>:
 * <ul>
 *   <li>{@code id} — CC original: {@code id} (sessionHooks.ts:26) 可选唯一 ID, 供 removeFunctionHook 定位</li>
 *   <li>{@code timeout} — CC original: {@code timeout} (sessionHooks.ts:27), 缺省 5000
 *       (CC L109 {@code options?.timeout || 5000})</li>
 *   <li>{@code callback} — CC original: {@code callback} (sessionHooks.ts:28) 内存回调</li>
 *   <li>{@code errorMessage} — CC original: {@code errorMessage} (sessionHooks.ts:29) 拦截时的错误提示</li>
 *   <li>{@code statusMessage} — CC original: {@code statusMessage} (sessionHooks.ts:30) 可选 spinner 文案</li>
 * </ul>
 *
 * <p><b>与 HookCommand 差异</b>: function hook 无 once 字段 (CC 真源无 once), {@link #once()}
 * 固定返回 null; 不可持久化 → 无 Jackson {@code @JsonSubTypes} 注册.
 *
 * @param id            CC original: id (sessionHooks.ts:26)
 * @param timeout       CC original: timeout (sessionHooks.ts:27); 毫秒, 缺省 5000
 * @param callback      CC original: callback (sessionHooks.ts:28)
 * @param errorMessage  CC original: errorMessage (sessionHooks.ts:29)
 * @param statusMessage CC original: statusMessage (sessionHooks.ts:30)
 * @see FunctionHookCallback
 * @see SessionHookStore#addFunctionHook(String, HookEventType, String, FunctionHookCallback, String, Long, String)
 */
public record FunctionHook(
    String id,
    long timeout,
    FunctionHookCallback callback,
    String errorMessage,
    String statusMessage
) implements SessionHook {

    /**
     * CC function hook 缺省超时 5000ms · 对齐 sessionHooks.ts:109 {@code options?.timeout || 5000}.
     */
    public static final long DEFAULT_TIMEOUT_MS = 5000L;

    @Override
    public String type() {
        return "function";
    }

    @Override
    public Boolean once() {
        // CC function hook 无 once 字段 (sessionHooks.ts:24-31 真源无 once) → null = 未指定
        return null;
    }
}
