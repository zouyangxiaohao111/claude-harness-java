package com.nexusai.application.agent.permission.hook;

import java.util.List;
import java.util.Map;

/**
 * [Session H4] PermissionRequest 结果 sealed interface · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/types/hooks.ts:248-258} {@code PermissionRequestResult}.
 *
 * <p>CC 真源 (types/hooks.ts:248-258, grep 自验 2026-07-30):
 * <pre>
 * export type PermissionRequestResult =
 *   | { behavior: 'allow'; updatedInput?: Record&lt;string, unknown&gt;; updatedPermissions?: PermissionUpdate[] }
 *   | { behavior: 'deny'; message?: string; interrupt?: boolean }
 * </pre>
 *
 * <p>WHY (规则三 + Pattern #4): CC 是 discriminated union 2 变体, Java 用 sealed interface
 * + 2 record 表达 (对齐 CC union 语义). 之前 Java AggregatedHookResult.permissionRequestResult
 * 是 Object 承载, 丢失编译期类型安全. H4 补全为 sealed, consumer 按 {@code instanceof}
 * 分流 allow/deny.
 *
 * <p>每个 record 字段 JavaDoc 标注 CC 原名 + 行号 (未来审计无需重跑).
 *
 * @since Session H4
 */
public sealed interface PermissionRequestResult
    permits PermissionRequestResult.Allow, PermissionRequestResult.Deny {

    /**
     * CC allow 变体 (types/hooks.ts:250-253): 允许工具执行, 可携带 updatedInput / updatedPermissions.
     *
     * @param updatedInput       CC original: {@code updatedInput} (types/hooks.ts:251);
     *                           允许时替换的工具输入, optional
     * @param updatedPermissions CC original: {@code updatedPermissions} (types/hooks.ts:252);
     *                           允许时同步更新的权限规则列表, optional
     */
    record Allow(
        Map<String, Object> updatedInput,
        List<Object> updatedPermissions
    ) implements PermissionRequestResult {
        public Allow {
            if (updatedInput != null) {
                updatedInput = Map.copyOf(updatedInput);
            }
            if (updatedPermissions != null) {
                updatedPermissions = List.copyOf(updatedPermissions);
            }
        }
    }

    /**
     * CC deny 变体 (types/hooks.ts:254-257): 拒绝工具执行, 可携带 message / interrupt.
     *
     * @param message   CC original: {@code message} (types/hooks.ts:255);
     *                  拒绝原因文本 (展示给用户), optional
     * @param interrupt CC original: {@code interrupt} (types/hooks.ts:256);
     *                  是否中断会话 (true = 中断, false/null = 仅拒绝本次), optional
     */
    record Deny(
        String message,
        Boolean interrupt
    ) implements PermissionRequestResult {
    }
}