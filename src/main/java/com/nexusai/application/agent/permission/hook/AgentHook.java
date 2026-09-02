package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agentic verifier hook · 对齐 CC {@code Open-ClaudeCode/src/schemas/hooks.ts:128-163}
 * {@code AgentHookSchema} (type='agent').
 *
 * <p>WHY: CC agent hook 用一个子 agent 验证某条件 (如 "验证单元测试已运行且通过"),
 * 返回 {ok, reason}. 字段语义对齐 CC Zod schema.
 *
 * <p><b>CC 真源字段 (schemas/hooks.ts:128-163)</b>:
 * <ul>
 *   <li>{@code prompt} (:138-142) — 验证 prompt 描述 (含 $ARGUMENTS 占位符)</li>
 *   <li>{@code if} (:143) — 权限规则过滤条件</li>
 *   <li>{@code timeout} (:144-148) — 超时秒数 (默认 60)</li>
 *   <li>{@code model} (:149-154) — 指定模型, 缺省 Haiku</li>
 *   <li>{@code statusMessage} (:155-158) — 自定义 spinner 文案</li>
 *   <li>{@code once} (:159-162) — true=执行一次后移除</li>
 * </ul>
 *
 * @param prompt        CC original: prompt (schemas/hooks.ts:138)
 * @param ifCondition   CC original: if (schemas/hooks.ts:143)
 * @param timeout       CC original: timeout (schemas/hooks.ts:144); null → 默认 60s
 * @param model         CC original: model (schemas/hooks.ts:149); null → Haiku
 * @param statusMessage CC original: statusMessage (schemas/hooks.ts:155)
 * @param once          CC original: once (schemas/hooks.ts:159)
 */
public record AgentHook(
    String prompt,
    @JsonProperty("if") String ifCondition,
    Integer timeout,
    String model,
    String statusMessage,
    Boolean once
) implements HookCommand, SessionHook {

    /** CC agent hook 默认超时 60s (schemas/hooks.ts:148 "default 60"). */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    @Override
    public HookType hookType() {
        return HookType.AGENT;
    }

    /**
     * CC original: {@code type} = 'agent' · 对齐 schemas/hooks.ts discriminatedUnion 字面量.
     *
     * <p>[3-1 拆开] 显式实现 — 本类同时 implements {@link SessionHook} (抽象 type()) 与
     * {@link HookCommand} (default type()), Java 要求显式声明以同时满足两接口契约.
     */
    @Override
    public String type() {
        return "agent";
    }
}