package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shell command hook · 对齐 CC {@code Open-ClaudeCode/src/schemas/hooks.ts:32-65}
 * {@code BashCommandHookSchema} (type='command').
 *
 * <p>WHY: CC command hook 是最常用的 hook 类型, 执行 shell 命令并按 exit code 分流
 * (0=success, 2=blocking, 其他=warning). 字段语义对齐 CC Zod schema, 每个字段
 * JavaDoc 标注 CC 原名 + 行号, 便于未来审计无需重跑 CC 源码.
 *
 * <p><b>CC 真源字段 (schemas/hooks.ts:32-65)</b>:
 * <ul>
 *   <li>{@code command} (:34) — Shell 命令字符串 (必填)</li>
 *   <li>{@code if} (:35) — 权限规则语法过滤条件, 如 "Bash(git *)"; Java 关键字 {@code if}
 *       避让为 {@code ifCondition}, Jackson 通过 {@link JsonProperty} 映射回 "if"</li>
 *   <li>{@code shell} (:36-41) — Shell 解释器, enum {bash, powershell}, 缺省 bash
 *       (CC shellProvider.ts:2 DEFAULT_HOOK_SHELL='bash')</li>
 *   <li>{@code timeout} (:42-46) — 超时秒数 (正整数)</li>
 *   <li>{@code statusMessage} (:47-50) — 自定义 spinner 状态文案</li>
 *   <li>{@code once} (:51-54) — true=执行一次后移除</li>
 *   <li>{@code async} (:55-58) — true=后台运行不阻塞</li>
 *   <li>{@code asyncRewake} (:59-64) — true=后台运行, exit 2 唤醒模型 (隐含 async)</li>
 * </ul>
 *
 * <p><b>UI 集成预留</b>: 前端 command hook 表单字段对齐本 record.
 *
 * @param command       CC original: command (schemas/hooks.ts:34)
 * @param ifCondition   CC original: if (schemas/hooks.ts:35); 权限规则过滤条件
 * @param shell         CC original: shell (schemas/hooks.ts:36); null → 默认 bash
 * @param timeout       CC original: timeout (schemas/hooks.ts:42); 秒
 * @param statusMessage CC original: statusMessage (schemas/hooks.ts:47)
 * @param once          CC original: once (schemas/hooks.ts:51)
 * @param asyncFlag     CC original: async (schemas/hooks.ts:55); Java 关键字避让
 * @param asyncRewake   CC original: asyncRewake (schemas/hooks.ts:59)
 */
public record CommandHook(
    String command,
    @JsonProperty("if") String ifCondition,
    String shell,
    Integer timeout,
    String statusMessage,
    Boolean once,
    @JsonProperty("async") Boolean asyncFlag,
    Boolean asyncRewake
) implements HookCommand, SessionHook {

    /** CC DEFAULT_HOOK_SHELL='bash' (shellProvider.ts:2) — shell 缺省值. */
    public static final String DEFAULT_SHELL = "bash";

    @Override
    public HookType hookType() {
        return HookType.COMMAND;
    }

    /**
     * CC original: {@code type} = 'command' · 对齐 schemas/hooks.ts discriminatedUnion 字面量.
     *
     * <p>[3-1 拆开] 显式实现 — 本类同时 implements {@link SessionHook} (抽象 type()) 与
     * {@link HookCommand} (default type()), Java 要求显式声明以同时满足两接口契约.
     */
    @Override
    public String type() {
        return "command";
    }
}