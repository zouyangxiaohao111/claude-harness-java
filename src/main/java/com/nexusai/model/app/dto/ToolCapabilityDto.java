package com.nexusai.model.app.dto;

import java.util.List;

/**
 * 工具能力目录条目（CU / BR）：只读展示「有哪些工具、为什么现在不可用」。
 *
 * <p>⛔ 不改变注册：flag 关闭时工具仍不注册、不碰 JNA；本目录只列出已知名字 + 原因。
 *
 * @param category        分类（{@code CU} / {@code BR}）
 * @param label           中文标签（电脑操作 / 浏览器控制）
 * @param featureEnabled  对应 feature flag 是否开启
 * @param registered      当前进程是否已注册工具
 * @param registeredCount 已注册数量
 * @param knownTools      已知工具名清单（原始 schema 名）
 * @param status          disabled | unregistered | disconnected | ready
 * @param reason          不可用原因（{@code 功能未开启} / {@code 工具未注册} / {@code Chrome 扩展未连接}）；可用 → null
 */
public record ToolCapabilityDto(
    String category,
    String label,
    boolean featureEnabled,
    boolean registered,
    int registeredCount,
    List<String> knownTools,
    String status,
    String reason
) {
    public ToolCapabilityDto {
        knownTools = knownTools == null ? List.of() : List.copyOf(knownTools);
    }
}
