package com.nexusai.application.agent.permission.hook;

/**
 * 匹配到的 hook + 来源上下文 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks.ts:1432-1438}
 * {@code MatchedHook}.
 *
 * <p>WHY: CC {@code getMatchingHooks} 返回 {@code MatchedHook[]}, 每个元素把 HookCommand
 * 与可选 plugin/skill 上下文绑定 (执行时应用 plugin env vars / skill 目录 / hookSource 标记).
 * Java 端用 record 等价表达, 供 {@link HookRegistry#getMatchingHooks(HookEvent)} 返回.
 *
 * <p><b>H1 范围</b>: 仅 settings 来源 (hookSource="settings"), pluginRoot/pluginId/skillRoot
 * 全为 null (plugin/skill 来源留 H12).
 *
 * <p><b>CC 真源字段 (hooks.ts:1432-1438)</b>:
 * <ul>
 *   <li>{@code hook} (:1433) — HookCommand | HookCallback | FunctionHook; Java 端仅 4 持久化类型</li>
 *   <li>{@code pluginRoot} (:1434) — 插件根目录 (PluginHookMatcher 有), optional</li>
 *   <li>{@code pluginId} (:1435) — 插件 ID (PluginHookMatcher 有), optional</li>
 *   <li>{@code skillRoot} (:1436) — skill 根目录 (SkillHookMatcher 有), optional</li>
 *   <li>{@code hookSource} (:1437) — "settings" / "plugin" / "skill" (CC :1694-1702 三元);
 *       session 派生 matcher（SessionDerivedHookMatcher, 无 pluginRoot）→ "settings"（CC 值域
 *       无 "sessionHook"; 对齐 hooks.ts:1694-1702）</li>
 * </ul>
 *
 * @param hook       CC original: hook (hooks.ts:1433); HookCommand 子类
 * @param pluginRoot CC original: pluginRoot (hooks.ts:1434); H1 为 null
 * @param pluginId   CC original: pluginId (hooks.ts:1435); H1 为 null
 * @param skillRoot  CC original: skillRoot (hooks.ts:1436); H1 为 null
 * @param hookSource CC original: hookSource (hooks.ts:1437); 来源标记
 */
public record MatchedHook(
    HookCommand hook,
    String pluginRoot,
    String pluginId,
    String skillRoot,
    String hookSource
) {
    public MatchedHook {
        if (hook == null) {
            throw new IllegalArgumentException("MatchedHook.hook is null");
        }
    }
}
