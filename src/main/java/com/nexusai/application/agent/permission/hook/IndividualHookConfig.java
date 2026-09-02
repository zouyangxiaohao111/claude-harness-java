package com.nexusai.application.agent.permission.hook;

/**
 * 单个 hook 配置 (带来源元数据) · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/hooksSettings.ts:22-28}
 * {@code IndividualHookConfig}.
 *
 * <p>WHY: CC getAllHooks 把每个 hookCommand 包成 IndividualHookConfig (附 event/matcher/source
 * 元数据) 返回给 UI, 用于 hook 列表分组展示. Java 端用 record 等价表达.
 *
 * <p><b>CC 真源字段 (hooksSettings.ts:22-28)</b> — 5 字段 (非 6):
 * <ul>
 *   <li>{@code event} (:23) — HookEvent (27 种事件之一)</li>
 *   <li>{@code config} (:24) — HookCommand (4 子类型之一)</li>
 *   <li>{@code matcher?} (:25) — 匹配器字符串 (如工具名 "Write"), 可选</li>
 *   <li>{@code source} (:26) — HookSource (7 来源之一)</li>
 *   <li>{@code pluginName?} (:27) — 插件名 (仅 pluginHook 来源), 可选</li>
 * </ul>
 *
 * <p><b>关于 "6 字段" 提示词</b>: H3 提示词说 "6 字段", 但 CC 真源 (主 agent grep 确认)
 * 实际是 5 字段. 以 CC 真源为准 (规则七: 显式暴露冲突, 优先更新/经测试版本).
 *
 * <p><b>UI 集成预留</b>: 前端 hook 列表按 source 分组, 每行显示 getHookDisplayText(config).
 *
 * @param event      CC original: event (hooksSettings.ts:23); HookEventType
 * @param config     CC original: config (hooksSettings.ts:24); HookCommand 子类
 * @param matcher    CC original: matcher (hooksSettings.ts:25); null = 无匹配器
 * @param source     CC original: source (hooksSettings.ts:26); HookSource
 * @param pluginName CC original: pluginName (hooksSettings.ts:27); 仅 pluginHook 有
 */
public record IndividualHookConfig(
    HookEventType event,
    HookCommand config,
    String matcher,
    HookSource source,
    String pluginName
) {
}