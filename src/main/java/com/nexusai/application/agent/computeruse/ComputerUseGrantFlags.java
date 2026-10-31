package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * computer-use 授权位（与「应用白名单」正交）· 逐字对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/types.ts:69-84} 的 {@code CuGrantFlags}
 * 与 {@code DEFAULT_GRANT_FLAGS}。
 *
 * <p><b>CCB 原形状</b>：
 * <pre>
 * export interface CuGrantFlags {
 *   clipboardRead: boolean
 *   clipboardWrite: boolean
 *   systemKeyCombos: boolean
 * }
 * export const DEFAULT_GRANT_FLAGS: CuGrantFlags = {
 *   clipboardRead: false,
 *   clipboardWrite: false,
 *   systemKeyCombos: false,
 * }
 * </pre>
 *
 * <p><b>合并语义（关键）</b>：CCB
 * {@code packages/@ant/computer-use-mcp/src/mcpServer.ts:58-77} 的
 * {@code mergePermissionResponse} —— <b>truthy-only 只增不减</b>：
 * <pre>
 * const truthyFlags = Object.fromEntries(
 *   Object.entries(response.flags).filter(([, v]) => v === true),
 * )
 * const flags: CuGrantFlags = { ...DEFAULT_GRANT_FLAGS, ...existingFlags, ...truthyFlags }
 * </pre>
 * ⇒ 后一次 {@code request_access} <b>不能撤销</b>先前的剪贴板授权
 * （撤销只发生在 Settings 页，不在这里 —— CCB 注释原话）。
 *
 * @param clipboardRead    是否允许读剪贴板
 * @param clipboardWrite   是否允许写剪贴板
 * @param systemKeyCombos  是否允许系统级快捷键组合
 */
public record ComputerUseGrantFlags(
        boolean clipboardRead,
        boolean clipboardWrite,
        boolean systemKeyCombos
) {

    /**
     * 默认授权位 · 逐字 CCB {@code types.ts:80-84} 的 {@code DEFAULT_GRANT_FLAGS}（全 false）。
     */
    public static final ComputerUseGrantFlags DEFAULT = new ComputerUseGrantFlags(false, false, false);

    /**
     * truthy-only 合并 · 逐字 CCB {@code mcpServer.ts:68-75}。
     *
     * <p>规则：{@code result = DEFAULT ∪ existing ∪ (incoming 中为 true 的位)} ——
     * incoming 里为 {@code false} 的位<b>不覆盖</b> existing 的 true。
     *
     * @param existing 已有授权位（可 null ⇒ 按 DEFAULT 处理）
     * @param incoming 本次请求的授权位（可 null ⇒ 按 DEFAULT 处理）
     * @return 合并后的授权位
     */
    public static ComputerUseGrantFlags mergeTruthyOnly(ComputerUseGrantFlags existing,
                                                        ComputerUseGrantFlags incoming) {
        ComputerUseGrantFlags base = existing == null ? DEFAULT : existing;
        ComputerUseGrantFlags add = incoming == null ? DEFAULT : incoming;
        return new ComputerUseGrantFlags(
                base.clipboardRead() || add.clipboardRead(),
                base.clipboardWrite() || add.clipboardWrite(),
                base.systemKeyCombos() || add.systemKeyCombos());
    }

    /**
     * @return 三个位是否全为 false
     */
    public boolean allFalse() {
        return !clipboardRead && !clipboardWrite && !systemKeyCombos;
    }

    /**
     * JSON → 授权位（缺字段 / 非布尔 ⇒ false，对齐 CCB {@code DEFAULT_GRANT_FLAGS}）。
     *
     * @param node JSON 节点（可 null）
     * @return 授权位
     */
    public static ComputerUseGrantFlags fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return DEFAULT;
        }
        return new ComputerUseGrantFlags(
                node.path("clipboardRead").asBoolean(false),
                node.path("clipboardWrite").asBoolean(false),
                node.path("systemKeyCombos").asBoolean(false));
    }

    /**
     * 授权位 → JSON（写入规范形；三个键恒存在）。
     *
     * @param node 目标节点
     */
    public void writeTo(ObjectNode node) {
        node.put("clipboardRead", clipboardRead);
        node.put("clipboardWrite", clipboardWrite);
        node.put("systemKeyCombos", systemKeyCombos);
    }
}
