package com.nexusai.model.hook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.permission.hook.IndividualHookConfig;

/**
 * Hook 列表项 DTO（GET /api/v1/hooks 返回项）· 对齐前端
 * {@code nexusai/src/api/types.ts:855-865 HookItem}。
 *
 * <p>WHY (联调三问题·hooks 端点): 前端 HookItem = {@code {event, config, matcher, source, pluginName}}，
 * 其中 config 为扁平 {@link HookCommandConfigDto}（types.ts:836-849）。本 DTO 是
 * {@link IndividualHookConfig}（domain，5 字段：event/config/matcher/source/pluginName）
 * 的 REST 视图：event/source 取枚举 {@code name()}（UPPER_SNAKE，如 SESSION_START /
 * USER_SETTINGS，与前端注释一致）；config 经 {@link HookCommandConfigDto#from} 扁平化；
 * matcher/pluginName 原样透传（null 时 JSON 省略，NON_NULL）。
 *
 * <p><b>映射单点</b>: {@link #from(IndividualHookConfig)} —— 唯一映射入口，集中
 * event/source 枚举名 + config 扁平映射逻辑（避免跨 Controller 重复）。
 *
 * @param event      前端 HookItem.event — HookEventType 枚举名（UPPER_SNAKE，如 SESSION_START）
 * @param config     前端 HookItem.config — 扁平 HookCommandConfigDto（type + 子类型字段）
 * @param matcher    前端 HookItem.matcher — 匹配器串（如工具名 "Write"）；null 省略
 * @param source     前端 HookItem.source — HookSource 枚举名（如 USER_SETTINGS）
 * @param pluginName 前端 HookItem.pluginName — 仅 pluginHook 来源有；null 省略
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookItemDto(
    String event,
    HookCommandConfigDto config,
    String matcher,
    String source,
    String pluginName
) {

    /**
     * domain {@link IndividualHookConfig} → REST HookItemDto · 对齐前端 HookItem 形状。
     *
     * <p>event=h.event().name()（UPPER_SNAKE）；source=h.source().name()；
     * config=HookCommandConfigDto.from(h.config())；matcher/pluginName 原样透传。
     *
     * @param h IndividualHookConfig（domain）；null 防御 → null
     * @return HookItemDto；h null → null
     */
    public static HookItemDto from(IndividualHookConfig h) {
        if (h == null) {
            return null;
        }
        return new HookItemDto(
            h.event().name(),
            HookCommandConfigDto.from(h.config()),
            h.matcher(),
            h.source().name(),
            h.pluginName()
        );
    }
}
