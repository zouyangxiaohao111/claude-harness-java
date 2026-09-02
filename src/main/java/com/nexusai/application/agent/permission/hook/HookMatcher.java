package com.nexusai.application.agent.permission.hook;

import java.util.List;

/**
 * Hook 匹配器配置 · 对齐 CC {@code Open-ClaudeCode/src/schemas/hooks.ts:194-204}
 * {@code HookMatcherSchema}.
 *
 * <p>WHY: CC settings.json 中 hooks 字段结构为
 * {@code {<Event>: [{matcher?: string, hooks: HookCommand[]}]}}.
 * 每个 HookMatcher 绑定一个可选 matcher (工具名匹配模式) + 一组 HookCommand.
 * Java 端用 record 等价表达.
 *
 * <p><b>CC 真源字段 (schemas/hooks.ts:194-204)</b>:
 * <ul>
 *   <li>{@code matcher?} (:196-199) — 字符串匹配模式 (如 "Write"), 可选</li>
 *   <li>{@code hooks} (:200-202) — HookCommand 数组 (4 子类型之一, 非空)</li>
 * </ul>
 *
 * <p>持久化 schema 对齐 CC: hooks 是 HookCommand 列表, 不是 String 列表.
 *
 * <p><b>UI 集成预留</b>: 前端 hook 配置面板按 event → matcher 分组编辑 hooks 数组.
 *
 * @param matcher CC original: matcher (schemas/hooks.ts:196); null = 匹配所有
 * @param hooks   CC original: hooks (schemas/hooks.ts:200); HookCommand 子类列表
 *
 * <p><b>严格校验（IMP-DS-03 · DC-WF2-MT-03）</b>: CC {@code hooks} 为<b>必需数组</b>
 * （schemas/hooks.ts:200-202 {@code z.array(HookCommandSchema())}，无 optional）。
 * null {@code hooks} = 畸形 matcher，旧实现静默折叠为 {@code List.of()}（反序列化容忍，
 * 加载层不暴露），改为构造即抛 {@link IllegalArgumentException} —— 使加载层
 * （Jackson 反序列化，HooksConfigSnapshot.policyHooksFromSettings /
 * MultiSourceHooksConfigLoader）感知畸形配置 → warn + 该源置空，对齐 CC 整文件校验失败语义。
 * 空数组仍合法（CC z.array 允许空数组）。
 */
public record HookMatcher(
    String matcher,
    List<HookCommand> hooks
) {
    public HookMatcher {
        if (hooks == null) {
            throw new IllegalArgumentException(
                "HookMatcher.hooks 不能为 null：CC HookMatcherSchema hooks 为必需数组"
                    + "（Open-ClaudeCode/src/schemas/hooks.ts:200-202），畸形 matcher 应在加载层被拒绝");
        }
        hooks = List.copyOf(hooks);
    }
}