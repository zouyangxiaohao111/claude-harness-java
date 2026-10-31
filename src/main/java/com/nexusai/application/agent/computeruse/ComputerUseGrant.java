package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 单个应用的 computer-use 授权 · 逐字对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/types.ts:58-66} 的 {@code AppGrant}。
 *
 * <p><b>CCB 原形状</b>：
 * <pre>
 * export interface AppGrant {
 *   bundleId: string
 *   displayName: string
 *   // Epoch ms. For Settings-page display ("Granted 3m ago").
 *   grantedAt: number
 *   // Undefined → "full" (back-compat for pre-tier grants persisted in session state).
 *   tier?: CuAppPermTier
 * }
 * </pre>
 *
 * <p><b>字段名映射（唯一的差异）</b>：CCB 的 {@code bundleId} 在 Windows 上无意义
 * （macOS bundle id）⇒ 本仓改用 {@code appId}，其值 = <b>归一后的可执行文件名</b>
 * （见 {@link ComputerUseAppPolicy#normalizeAppKey}）。这正是本仓对 CCB「授权键与前台判定键
 * 不同命名空间」缺陷的修正（见 {@link ComputerUseAppPolicy} 类 javadoc）。
 *
 * @param appId       授权键（归一后的 exe 名；不可为空）
 * @param displayName 展示名（模型请求里给的应用名）
 * @param grantedAt   授予时刻（epoch 毫秒）
 * @param tier        授权档位 wire 串（read / click / full；null = 未设 ⇒ 视为 full）
 */
public record ComputerUseGrant(
        String appId,
        String displayName,
        long grantedAt,
        String tier
) {

    public ComputerUseGrant {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("ComputerUseGrant.appId is blank");
        }
        if (displayName == null) {
            displayName = "";
        }
    }

    /**
     * @return 授权键的小写形态（归一后已是小写；此方法只是防御性再确认）
     */
    public String normalizedAppId() {
        return appId.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return 授权档位枚举（未设 / 未知 ⇒ null，交给
     *         {@link ComputerUseTierGate#tierSatisfies(String, ComputerUseActionKind)} 按
     *         CCB 语义判定）
     */
    public ComputerUseTier tierEnum() {
        return tier == null ? null : ComputerUseTier.fromWire(tier);
    }

    /**
     * JSON → 授权条目。
     *
     * <p><b>fail-soft</b>：坏条目（缺 appId / 非对象）⇒ 返回 null（调用方跳过），
     * 绝不因一条脏数据让整列消失 —— 对齐
     * {@code SessionPermissionOverlay.parseRules} 的逐条跳过 + WARN 语义。
     *
     * @param node JSON 节点
     * @return 授权条目；坏条目 ⇒ null
     */
    public static ComputerUseGrant fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode appIdNode = node.get("appId");
        if (appIdNode == null || !appIdNode.isTextual() || appIdNode.asText().isBlank()) {
            return null;
        }
        JsonNode displayNode = node.get("displayName");
        String displayName = (displayNode != null && displayNode.isTextual()) ? displayNode.asText() : "";
        JsonNode grantedAtNode = node.get("grantedAt");
        long grantedAt = (grantedAtNode != null && grantedAtNode.isNumber()) ? grantedAtNode.asLong() : 0L;
        JsonNode tierNode = node.get("tier");
        String tier = (tierNode != null && tierNode.isTextual()) ? tierNode.asText() : null;
        return new ComputerUseGrant(appIdNode.asText(), displayName, grantedAt, tier);
    }

    /**
     * 授权条目 → JSON（写入规范形）。
     *
     * @param node 目标节点
     */
    public void writeTo(ObjectNode node) {
        node.put("appId", appId);
        node.put("displayName", displayName);
        node.put("grantedAt", grantedAt);
        if (tier != null) {
            node.put("tier", tier);
        }
    }
}
