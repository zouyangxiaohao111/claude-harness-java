package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 单个被请求应用的<b>解析结果</b>（授权键 / 展示名 / 是否已授权 / 是否策略拒绝 / 默认档位）。
 *
 * <p><b>用途</b>：Phase 1b 的前端 CU 审批面板需要展示这些信息（CCB 的等价物是
 * {@code CuPermissionRequest.apps} 里每项的
 * {@code {requestedName, resolved: {bundleId, displayName}, alreadyGranted}} —
 * 见 CCB {@code src/components/permissions/ComputerUseApproval/ComputerUseApproval.tsx:122-131}
 * 与 {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:1139} 的 {@code ResolvedAppRequest}）。
 *
 * <p><b>为什么由后端算</b>：档位（read / click / full）依赖 CCB
 * {@code deniedApps.ts:537-542} 的应用分类表 ⇒ 只有后端算得出；前端拿不到分类表。
 *
 * <p><b>注入通道</b>：本仓的权限弹窗 STOMP 事件（{@code MessagePermissionRequestEvent}）
 * 只有 {@code toolInput} 一个结构化载荷 ⇒ 后端在
 * {@link ComputerUseRequestAccessTool#checkPermissions} 里把本列表<b>就地注入</b>到
 * 工具入参的 {@code resolvedApps} 键（不改 STOMP 事件、不改前端）。
 *
 * @param requestedName  模型请求里写的应用名（原文）
 * @param appId          归一后的授权键（exe 名；策略黑名单判定用）
 * @param displayName    展示名（前端面板显示用）
 * @param alreadyGranted 该应用是否已在本会话授权（true ⇒ 本轮不再问用户）
 * @param policyDenied   是否被策略黑名单拒绝（优先级高于用户拒绝，无逃生口）
 * @param tier           默认档位 wire 串（read / click / full）
 */
public record ComputerUseResolvedApp(
        String requestedName,
        String appId,
        String displayName,
        boolean alreadyGranted,
        boolean policyDenied,
        String tier
) {

    /**
     * @param requestedName 模型请求里的应用名
     * @param displayName   展示名
     * @param appId         授权键
     * @param alreadyGranted 是否已授权
     * @param policyDenied  是否策略拒绝
     * @param tier          默认档位
     * @return 解析结果
     */
    public static ComputerUseResolvedApp of(String requestedName, String displayName, String appId,
                                            boolean alreadyGranted, boolean policyDenied, String tier) {
        return new ComputerUseResolvedApp(requestedName, appId, displayName, alreadyGranted, policyDenied, tier);
    }

    /**
     * 写入 JSON（前端面板消费）。
     *
     * @param node 目标节点
     */
    public void writeTo(ObjectNode node) {
        node.put("requestedName", requestedName);
        node.put("appId", appId);
        node.put("displayName", displayName);
        node.put("alreadyGranted", alreadyGranted);
        node.put("policyDenied", policyDenied);
        node.put("tier", tier);
    }
}
