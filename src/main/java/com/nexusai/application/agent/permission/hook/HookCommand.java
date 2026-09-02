package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Hook 命令 sealed interface · 对齐 CC {@code Open-ClaudeCode/src/schemas/hooks.ts:176-189}
 * {@code HookCommandSchema = z.discriminatedUnion('type', [Command, Prompt, Agent, Http])}.
 *
 * <p>WHY (规则九·测试验证意图): CC 用 Zod discriminatedUnion 按 {@code type} 字段做多态分发,
 * 4 子类型各自字段不同 (command/prompt/url/agent prompt). Java 端用 sealed interface +
 * Jackson {@link JsonTypeInfo} + {@link JsonSubTypes} 实现等价的多态分发, 保证
 * 反序列化时按 {@code type} 字段路由到正确子类 record, 序列化时写回 {@code type}.
 *
 * <p><b>CC 真源已验证字段 (主 agent grep 确认, 直接用)</b>:
 * <ul>
 *   <li>{@code CommandHook} (type='command', schemas/hooks.ts:32-65)</li>
 *   <li>{@code PromptHook} (type='prompt', schemas/hooks.ts:67-95)</li>
 *   <li>{@code HttpHook} (type='http', schemas/hooks.ts:97-126)</li>
 *   <li>{@code AgentHook} (type='agent', schemas/hooks.ts:128-163)</li>
 * </ul>
 *
 * <p><b>不包含 function hook</b> — CC 注释 (hooks.ts:174) 明确 "excludes function hooks -
 * they can't be persisted". 本 sealed interface 仅覆盖 4 个可持久化类型.
 *
 * <p><b>UI 集成预留</b>: 前端 hook 配置面板按 {@code type} 渲染不同表单, 提交后 Jackson
 * 反序列化为对应子类 record.
 *
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(name = "command", value = CommandHook.class),
    @JsonSubTypes.Type(name = "prompt", value = PromptHook.class),
    @JsonSubTypes.Type(name = "http", value = HttpHook.class),
    @JsonSubTypes.Type(name = "agent", value = AgentHook.class),
})
public sealed interface HookCommand permits CommandHook, PromptHook, HttpHook, AgentHook {

    /** CC discriminatedUnion 的 4 个 type 字面量 · 对齐 schemas/hooks.ts:176-189. */
    enum HookType { COMMAND, PROMPT, HTTP, AGENT }

    /** 返回本 hook 的类型标识 (用于 isHookEqual/getHookDisplayText 分支, 不参与 Jackson 序列化). */
    HookType hookType();

    /**
     * [Session H5] CC original: {@code type} — 对齐 schemas/hooks.ts:176-189 discriminatedUnion 字面量.
     *
     * <p>WHY (规则三): 4 子类已有 {@code hookType()} enum, 用 default 方法映射到 CC 字面量即可,
     * 无需在 4 子类逐个补 {@code type()} 实现. {@code statusMessage()}/{@code once()}
     * 由 4 子类 record accessor 天然满足, 无需实现.
     *
     * @return CC type 字面量 (command/prompt/http/agent)
     */
    default String type() {
        return switch (hookType()) {
            case COMMAND -> "command";
            case PROMPT -> "prompt";
            case HTTP -> "http";
            case AGENT -> "agent";
        };
    }

    /**
     * CC original: {@code statusMessage} — 自定义 spinner 状态文案.
     *
     * <p>WHY: getHookDisplayText (hooksSettings.ts:68-90) 优先用 statusMessage 做展示,
     * 本字段承载之 (4 子类 record accessor 已天然满足). 拆开自 {@link SessionHook}
     * (决策 3-1: CC 无 SessionHook 抽象, HookCommand 是独立 discriminated union).
     *
     * @return 状态文案; null = 无自定义
     */
    String statusMessage();

    /**
     * CC original: {@code once} — true=执行一次后移除.
     *
     * <p>WHY: 4 子类有 once 字段 (schemas/hooks.ts 各 schema), record accessor 已天然满足.
     * 拆开自 {@link SessionHook} (决策 3-1).
     *
     * @return true=once 语义; null=未指定
     */
    Boolean once();
}