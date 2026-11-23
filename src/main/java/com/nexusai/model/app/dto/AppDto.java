package com.nexusai.model.app.dto;

/**
 * {@code GET /api/v1/apps} 的单条应用（<b>应用层只含 MCP</b>；浏览器 / CU 回工具面板）。
 *
 * <p>字段与前端 P6 已冻结的 {@code front/src/api/apps.ts AppDto} 对齐：不可用原因字段名固定为
 * {@code disabledReason}（前端映射到 {@code AppEntry.unusableReason}）。协调者裁定
 * （2026-11-23）只保留这一份真相，⛔ 不再输出 {@code unusableReason} 别名。
 *
 * @param key            应用 key（MCP = server 名）。工具名 = {@code mcp__<key>__*}
 * @param kind           恒为 {@code mcp}（应用层只含 MCP）
 * @param name           人看的名字
 * @param description    描述（可 null）
 * @param toolCount      当前注册/连接的工具数（真实计数，非臆造）
 * @param tools          当前注册/连接的<b>原始 schema 工具名</b>（如 {@code mcp__codegraph__explore}；
 *                       供前端展开查看；排序去重，空列表 = 无）
 * @param defaultEnabled 缺省（不裁剪）时是否算开：MCP=true（不在默认禁用集）
 * @param defaultOpen    「安装即默认开」口径：connector 安装的=false；既有/built-in MCP=true
 * @param usable         当前是否真的可用
 * @param disabledReason {@code usable=false} 的原因（前端冻结字段名，唯一真相）
 * @param runtimeStatus  MCP 运行时状态名（{@code McpRuntimeStatus.name()}）
 * @param authType       连接器授权类型（预留；MCP server 恒 null）
 * @param icon           图标（URL 或 key）：connector 安装的取连接器 catalog 条目；
 *                       其它 MCP 取配置里的 icon，没有则 null（前端回落首字母）
 * @param serverId       MCP server 的 DB id（start/stop 与 MCP 设置页跳转用）
 * @param canStart       当前是否可点「启动」（= 未连接且非 pending 审批；启动会尝试启用并连接）
 * @param lastError      最近一次启动/连接错误（无则 null）
 */
public record AppDto(
    String key,
    String kind,
    String name,
    String description,
    Integer toolCount,
    java.util.List<String> tools,
    boolean defaultEnabled,
    boolean defaultOpen,
    boolean usable,
    String disabledReason,
    String runtimeStatus,
    String authType,
    String icon,
    String serverId,
    boolean canStart,
    String lastError
) {
    public AppDto {
        tools = tools == null ? java.util.List.of() : java.util.List.copyOf(tools);
    }
}
