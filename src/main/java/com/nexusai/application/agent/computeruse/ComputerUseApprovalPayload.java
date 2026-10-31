package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * computer-use 审批面板载荷 · 下行事件（{@code MessagePermissionRequestEvent.computerUse}）的
 * <b>固定形状</b>（前端按此实现）。
 *
 * <p><b>JSON 形状（前端契约）</b>：
 * <pre>
 * "computerUse": {
 *   "apps": [
 *     { "appId": "chrome", "displayName": "Chrome",
 *       "tier": "read", "tierLabel": "只能查看",
 *       "alreadyGranted": false, "notInstalled": false }
 *   ],
 *   "reason": "模型给的理由",
 *   "requestedFlags": ["clipboardRead"],
 *   "flagLabels": { "clipboardRead": "允许读取剪贴板" }
 * }
 * </pre>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>{@code tier} 保留机器值</b>（read / click / full）⇒ 前端可做逻辑判断；
 *       <b>{@code tierLabel} 由后端给</b>（大白话中文）⇒ 前端<b>不翻译业务语义</b>。</li>
 *   <li><b>{@code requestedFlags} 只含被请求的位</b>（CCB
 *       {@code packages/@ant/computer-use-mcp/src/types.ts:134-137} 同款语义：
 *       {@code CuGrantFlags} 三个 boolean 位只列被置 true 的；
 *       本仓同款授权位语义见 {@link ComputerUseGrantFlags}（truthy-only 合并，
 *       逐字对齐 CCB {@code mcpServer.ts:58-77}））；
 *       {@code flagLabels} <b>只含同名键</b>。</li>
 *   <li>{@code computerUse} 为 <b>null</b> ⇒ 表示「不是 CU 请求」⇒ 前端走普通分支。</li>
 * </ul>
 *
 * <h2>数据来源</h2>
 * <p>{@link ComputerUseRequestAccessTool#checkPermissions} 已把解析后的应用列表
 * （{@code resolvedApps}）<b>就地注入</b>工具入参 ⇒ 本类从<b>同一份入参 JsonNode</b>提取。
 * <ul>
 *   <li>{@code apps[]} ← {@code resolvedApps[]}（{@link ComputerUseResolvedApp#writeTo} 的形状：
 *       {@code requestedName / appId / displayName / alreadyGranted / policyDenied / tier}）。</li>
 *   <li>{@code reason} ← 入参 {@code reason}（模型给用户看的一句话理由）。</li>
 *   <li>{@code requestedFlags} ← 入参 {@code clipboardRead / clipboardWrite / systemKeyCombos}。</li>
 * </ul>
 * <p>⚠️ {@code notInstalled}：本仓 Phase 1a <b>无应用枚举能力</b> ⇒ <b>恒 false</b>
 * （CCB 的等价字段来自其 macOS 应用枚举；本仓 Windows 侧无对应实现 ⇒ 如实报 false，
 * 不编造「未安装」结论）。
 *
 * <p><b>与 CCB 的差异（如实标注）</b>：CCB 的审批面板是 React 组件
 * （{@code ComputerUseApproval.tsx}），逐项勾选是它标注的 follow-up（整组授予）；
 * 本仓沿用整组授予，<b>面板数据由后端一次算好推给前端</b>。
 *
 * @param apps           被请求的应用列表（含档位与展示名）
 * @param reason         模型给用户看的一句话理由（可空）
 * @param requestedFlags 被请求的授权位（机器名；只含被请求的位）
 * @param flagLabels     授权位 → 大白话中文标签（只含同名键）
 */
public record ComputerUseApprovalPayload(
        List<App> apps,
        String reason,
        List<String> requestedFlags,
        Map<String, String> flagLabels) {

    /** 构造时防御性拷贝（不可变快照）。 */
    public ComputerUseApprovalPayload {
        apps = apps == null ? List.of() : List.copyOf(apps);
        requestedFlags = requestedFlags == null ? List.of() : List.copyOf(requestedFlags);
        flagLabels = flagLabels == null ? Map.of() : Map.copyOf(flagLabels);
    }

    /**
     * 单个被请求应用的展示项。
     *
     * @param appId          归一后的授权键（exe 名）
     * @param displayName    展示名
     * @param tier           机器档位值（read / click / full）
     * @param tierLabel      大白话中文档位标签（后端给，前端不翻译）
     * @param alreadyGranted 该应用此前是否已授权
     * @param notInstalled   是否未安装（本仓恒 false —— 无应用枚举能力）
     */
    public record App(
            String appId,
            String displayName,
            String tier,
            String tierLabel,
            boolean alreadyGranted,
            boolean notInstalled) {
    }

    // ─────────────────────────── 档位 / 授权位文案（前端不翻译业务语义）───────────────────────────

    /** 档位 → 大白话中文标签。 */
    private static final Map<String, String> TIER_LABELS = Map.of(
            "read", "只能查看",
            "click", "可以点击和滚动",
            "full", "可以完全操作");

    /** 授权位 → 大白话中文标签。 */
    private static final Map<String, String> FLAG_LABELS = Map.of(
            "clipboardRead", "允许读取剪贴板",
            "clipboardWrite", "允许写入剪贴板",
            "systemKeyCombos", "允许发送系统级快捷键组合");

    /**
     * 档位机器值 → 中文标签（未知档位 ⇒ 原样回显机器值，不编造语义）。
     *
     * @param tier 档位机器值（可空）
     * @return 中文标签（可空）
     */
    public static String tierLabel(String tier) {
        if (tier == null || tier.isBlank()) {
            return null;
        }
        return TIER_LABELS.getOrDefault(tier.trim().toLowerCase(java.util.Locale.ROOT), tier);
    }

    /**
     * 从工具入参提取 CU 审批面板载荷。
     *
     * @param toolName 工具名（只处理 {@code mcp__computer-use__request_access}；其余 ⇒ null）
     * @param input    工具入参（{@link ComputerUseRequestAccessTool#checkPermissions} 已注入
     *                 {@code resolvedApps} 的<b>同一实例</b>）
     * @return 载荷；非 CU 请求 / 入参不可用 ⇒ {@code null}（事件省略 {@code computerUse} 字段）
     */
    public static ComputerUseApprovalPayload fromToolInput(String toolName, JsonNode input) {
        if (!ComputerUseRequestAccessTool.TOOL_NAME.equals(toolName)) {
            return null;
        }
        if (input == null || !input.isObject()) {
            return null;
        }
        List<App> apps = readApps(input);
        String reason = input.path("reason").isTextual() ? input.path("reason").asText() : null;

        List<String> requestedFlags = new ArrayList<>();
        Map<String, String> flagLabels = new LinkedHashMap<>();
        for (String flag : new String[]{"clipboardRead", "clipboardWrite", "systemKeyCombos"}) {
            if (input.path(flag).asBoolean(false)) {
                requestedFlags.add(flag);
                flagLabels.put(flag, FLAG_LABELS.get(flag));
            }
        }
        return new ComputerUseApprovalPayload(apps, reason, requestedFlags, flagLabels);
    }

    /**
     * 应用列表提取。
     *
     * <p><b>主路径</b>：{@code resolvedApps}（工具在权限检查时就地注入的解析结果）。
     * <p><b>兜底路径</b>：{@code resolvedApps} 缺失时，按入参 {@code apps[]} +
     * {@link ComputerUseAppPolicy} 的同一套归一/档位规则推导（{@code alreadyGranted} 恒 false
     * —— 无从得知）。兜底只在注塑未发生（异常/未来改造）时生效，不影响主路径。
     */
    private static List<App> readApps(JsonNode input) {
        JsonNode resolved = input.get(ComputerUseRequestAccessTool.RESOLVED_APPS_KEY);
        List<App> out = new ArrayList<>();
        if (resolved != null && resolved.isArray()) {
            for (JsonNode node : resolved) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                String tier = node.path("tier").isTextual() ? node.path("tier").asText() : null;
                out.add(new App(
                        node.path("appId").asText(null),
                        node.path("displayName").asText(null),
                        tier,
                        tierLabel(tier),
                        node.path("alreadyGranted").asBoolean(false),
                        false));
            }
            return out;
        }
        JsonNode requested = input.get("apps");
        if (requested == null || !requested.isArray()) {
            return out;
        }
        for (JsonNode node : requested) {
            if (node == null || !node.isTextual()) {
                continue;
            }
            String name = node.asText();
            String appId = ComputerUseAppPolicy.normalizeAppKey(name);
            String tier = ComputerUseAppPolicy.defaultTier(appId, name).wire();
            out.add(new App(appId, name, tier, tierLabel(tier), false, false));
        }
        return out;
    }
}
