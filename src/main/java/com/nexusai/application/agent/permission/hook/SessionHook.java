package com.nexusai.application.agent.permission.hook;

/**
 * Session hook 存储 union sealed interface · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/sessionHooks.ts}
 * 中 {@code SessionHookMatcher.hooks[i].hook} (L36-39) 的类型并集
 * {@code HookCommand | FunctionHook}.
 *
 * <p><b>[决策 3-1 拆开对齐 CC]</b>: CC 无 {@code SessionHook} 抽象 — {@code HookCommand}
 * 是独立 discriminated union (schemas/hooks.ts:176-189), 会话存储的 union 为
 * {@code HookCommand | FunctionHook} (sessionHooks.ts:38). 旧实现 {@code HookCommand extends
 * SessionHook} 把 command hook 强制耦合到会话语义 (settings/session/matching 各处共用
 * HookCommand 却被迫 is-a SessionHook). 拆开后 {@link HookCommand} 是独立 sealed interface,
 * 本接口以<b>扁平化 5 具体类型</b>承载 CC union: 4 个 command 具体类型 + function 类型.
 *
 * <p><b>WHY (规则三)</b>: Java 无 TS union 语法, 需一个 sealed 载体表达
 * {@code HookCommand | FunctionHook}. 由于 {@link HookCommand} 已独立 (不再 extends 本接口),
 * 改为直接 permits 其 5 个具体实现 (CommandHook/PromptHook/HttpHook/AgentHook/FunctionHook),
 * 语义上仍等于 CC 的 {@code HookCommand | FunctionHook}. {@link SessionHookStore} 的 matcher
 * entry 统一以 {@code SessionHook} 存取, 再按 {@code type()} 分流查询 (getSessionHooks /
 * getSessionFunctionHooks 分离依据).
 *
 * <p><b>字段来源对照</b>:
 * <ul>
 *   <li>{@code type()} — CC original: {@code type} (sessionHooks.ts:26 'function'; HookCommand
 *       4 子类型见 schemas/hooks.ts:176-189 discriminatedUnion)</li>
 *   <li>{@code statusMessage()} — CC original: {@code statusMessage} (schemas/hooks.ts 各 schema,
 *       FunctionHook sessionHooks.ts:30)</li>
 *   <li>{@code once()} — CC original: {@code once} (schemas/hooks.ts 各 schema; function hook
 *       无 once, FunctionHook 返回 null)</li>
 * </ul>
 *
 * @see HookCommand
 * @see FunctionHook
 * @see SessionHookStore
 */
public sealed interface SessionHook permits CommandHook, PromptHook, HttpHook, AgentHook, FunctionHook {

    /**
     * CC original: {@code type} — 'command'|'prompt'|'http'|'agent'|'function'.
     *
     * <p>WHY: isHookEqual (hooksSettings.ts:33-64) 首先按 type 分派, 不同类型直接不等;
     * getSessionHooks/getSessionFunctionHooks 也按 type 过滤. Java 端统一以字符串 type 对齐 CC,
     * {@link HookCommand#type()} 用默认方法按 {@code hookType()} 映射, {@link FunctionHook#type()}
     * 固定返回 "function".
     *
     * @return CC type 字面量 (小写英文)
     */
    String type();

    /**
     * CC original: {@code statusMessage} — 自定义 spinner 状态文案.
     *
     * <p>WHY: getHookDisplayText (hooksSettings.ts:68-90) 优先用 statusMessage 做展示,
     * 本字段承载之 (HookCommand 4 子类 record accessor 已天然满足; FunctionHook 由
     * record accessor 满足).
     *
     * @return 状态文案; null = 无自定义
     */
    String statusMessage();

    /**
     * CC original: {@code once} — true=执行一次后移除.
     *
     * <p>WHY: HookCommand 4 子类有 once 字段 (schemas/hooks.ts 各 schema), function hook
     * 无 once (FunctionHook 返回 null). 统一暴露以对齐 CC union 结构.
     *
     * @return true=once 语义; null=未指定
     */
    Boolean once();
}
