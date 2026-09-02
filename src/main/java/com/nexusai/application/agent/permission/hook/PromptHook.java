package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LLM prompt hook · 对齐 CC {@code Open-ClaudeCode/src/schemas/hooks.ts:67-95}
 * {@code PromptHookSchema} (type='prompt').
 *
 * <p>WHY: CC prompt hook 通过 LLM 单轮评估条件是否满足, 返回
 * {@code {ok: boolean, reason?: string}}. 字段语义对齐 CC Zod schema.
 *
 * <p><b>[IMPL-05 DEL-EX-04/DEL-SSE-02 收敛]</b>: 本 record 是 {@link HookCommand} sealed
 * 的子类 (持久化用)，同时直接作为 {@link ExecPromptHook#exec} 的运行时入参 —— 对齐 CC
 * 单一 PromptHook 类型 (schemas/hooks.ts:67-95)。原独立运行时配置 record
 * {@code PromptHookConfig} 已删除（CC 无此分离；timeoutMs/modelOrFallback 方法迁入本类）。
 *
 * <p><b>CC 真源字段 (schemas/hooks.ts:67-95)</b>:
 * <ul>
 *   <li>{@code prompt} (:69-73) — LLM 评估 prompt (含 $ARGUMENTS 占位符)</li>
 *   <li>{@code if} (:74) — 权限规则过滤条件</li>
 *   <li>{@code timeout} (:75-79) — 超时秒数 (正整数)</li>
 *   <li>{@code model} (:81-86) — 指定模型, 缺省走默认 fast model</li>
 *   <li>{@code statusMessage} (:87-90) — 自定义 spinner 文案</li>
 *   <li>{@code once} (:91-94) — true=执行一次后移除</li>
 * </ul>
 *
 * @param prompt        CC original: prompt (schemas/hooks.ts:69)
 * @param ifCondition   CC original: if (schemas/hooks.ts:74)
 * @param timeout       CC original: timeout (schemas/hooks.ts:75)
 * @param model         CC original: model (schemas/hooks.ts:81)
 * @param statusMessage CC original: statusMessage (schemas/hooks.ts:87)
 * @param once          CC original: once (schemas/hooks.ts:91)
 */
public record PromptHook(
    String prompt,
    @JsonProperty("if") String ifCondition,
    Integer timeout,
    String model,
    String statusMessage,
    Boolean once
) implements HookCommand, SessionHook {

    @Override
    public HookType hookType() {
        return HookType.PROMPT;
    }

    /**
     * CC original: {@code type} = 'prompt' · 对齐 schemas/hooks.ts discriminatedUnion 字面量.
     *
     * <p>[3-1 拆开] 显式实现 — 本类同时 implements {@link SessionHook} (抽象 type()) 与
     * {@link HookCommand} (default type()), Java 要求显式声明以同时满足两接口契约.
     */
    @Override
    public String type() {
        return "prompt";
    }

    /**
     * 返回超时毫秒数 · 对齐 CC execPromptHook.ts:55
     * {@code hook.timeout ? hook.timeout*1000 : 30000}.
     *
     * <p><b>[IMPL-06 OD-EX-03] truthy 语义修正</b>: 旧实现为「非 null 判断」
     * （{@code timeout != null ? timeout * 1000L : 30_000L}）——显式配 0 秒时得到 0ms →
     * {@code .get(0)} 立即超时 → 确定性 cancelled（EV-EX-019 △-EX-06）。CC truthy 判断中
     * {@code 0} 为 falsy → 走默认 30s。修正为 {@code timeout != null && timeout > 0}（0 → 默认 30s）。
     */
    public long timeoutMs() {
        return timeout != null && timeout > 0 ? timeout * 1000L : 30_000L;
    }

    /**
     * 返回解析后的模型名 · 对齐 CC execPromptHook.ts:79
     * {@code hook.model ?? getSmallFastModel()}.
     * 若本配置未指定 model, 返回 caller 传入的 fast model 兜底.
     *
     * @param defaultFastModel 默认 fast model 名 (HookRegistry 解析后的模型名)
     * @return hook.model 非空 → 该值；否则 defaultFastModel
     */
    public String modelOrFallback(String defaultFastModel) {
        return (model != null && !model.isBlank()) ? model : defaultFastModel;
    }
}