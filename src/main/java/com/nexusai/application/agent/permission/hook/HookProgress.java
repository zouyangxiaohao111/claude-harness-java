package com.nexusai.application.agent.permission.hook;

/**
 * Hook 进度消息载荷 · 对齐 CC {@code HookProgress} (types/hooks.ts:234-241).
 *
 * <p>CC 真源 (types/hooks.ts:234-241, grep 自验):
 * <pre>
 * export type HookProgress = {
 *   type: 'hook_progress'
 *   hookEvent: HookEvent
 *   hookName: string
 *   command: string
 *   promptText?: string
 *   statusMessage?: string
 * }
 * </pre>
 *
 * <p>WHY (规则三 + OPD-WF1-TY-05): 消息流 hook_progress 载荷 (hooks.ts:2094-2116)
 * {@code {type:'hook_progress', hookEvent, hookName, command: getHookDisplayText(hook),
 * promptText?, statusMessage?}} 在每匹配 hook 执行前经消息流通道发出 (对齐 CC
 * {@code yield {message: {type:'progress', data:{type:'hook_progress',...}}}})。
 * 旧 Java 无此 record，HookMessage 通道仅为 {@code List<String>} 命令清单
 * （HookRegistry.java 自证缺口：promptText 不可表达；statusMessage 亦未建模）。
 * 本 record 为消息流 hook_progress 载荷的 Java 表达，供消息流通道接线后
 * 承载 command/promptText/statusMessage。
 *
 * @param type          CC original: {@code type} (types/hooks.ts:235) — 恒 'hook_progress'
 * @param hookEvent     CC original: {@code hookEvent} (types/hooks.ts:236) — PascalCase 事件名
 * @param hookName      CC original: {@code hookName} (types/hooks.ts:237)
 * @param command       CC original: {@code command} (types/hooks.ts:238) — getHookDisplayText(hook)
 *                      （statusMessage ?? command/prompt/url）
 * @param promptText    CC original: {@code promptText} (types/hooks.ts:239) — 仅 prompt hook 携带
 * @param statusMessage CC original: {@code statusMessage} (types/hooks.ts:240) —
 *                      'statusMessage' in hook && hook.statusMessage != null 时携带
 * @since IMP-CF-04 (TY-05)
 */
public record HookProgress(
    String type,
    String hookEvent,
    String hookName,
    String command,
    String promptText,
    String statusMessage
) {

    /** CC original: type 字面量 (types/hooks.ts:235). */
    public static final String TYPE = "hook_progress";

    /** 工厂 · type 恒 'hook_progress'（CC 判别字段），其余字段原样透传。 */
    public static HookProgress of(String hookEvent, String hookName, String command,
                                  String promptText, String statusMessage) {
        return new HookProgress(TYPE, hookEvent, hookName, command, promptText, statusMessage);
    }
}
