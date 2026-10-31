package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * {@code mcp__computer-use__request_access} 的返回 JSON 构造器 · 结构逐字对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:1044-1083}。
 *
 * <p><b>CCB 原形状</b>：
 * <pre>
 * return okJson(
 *   {
 *     granted: allGranted,
 *     denied: dialogDenied,
 *     ...(policyDenied.length &gt; 0 &amp;&amp; {
 *       policyDenied: { apps: policyDenied, guidance: buildPolicyDeniedGuidance(policyDenied) },
 *     }),
 *     ...(userDenied.length &gt; 0 &amp;&amp; {
 *       userDenied: { apps: userDenied, guidance: buildUserDeniedGuidance(userDenied) },
 *     }),
 *     ...(grantedTieredApps.length &gt; 0 &amp;&amp; { tierGuidance: buildTierGuidanceMessage(grantedTieredApps) }),
 *     screenshotFiltering: adapter.executor.capabilities.screenshotFiltering,
 *     ...(windowLocations.length &gt; 0 ? { windowLocations } : {}),
 *   },
 *   {
 *     granted_count: dialogGranted.length,
 *     denied_count: dialogDenied.length,
 *     ...tierAssignmentTelemetry(grantedTieredApps),
 *   },
 * )
 * </pre>
 *
 * <h2>与 CCB 的差异（如实标注）</h2>
 * <ul>
 *   <li><b>{@code granted[].appId}</b> 取代 CCB 的 {@code bundleId}：Windows 无 bundle id，
 *       本仓统一用归一后的 exe 名（与授权列 {@code sessions.computer_use_grants} 同源，
 *       见 {@link ComputerUseAppPolicy}）。</li>
 *   <li><b>{@code screenshotFiltering}</b>：CCB 按平台派生
 *       （{@code src/utils/computerUse/common.ts:55}：{@code darwin ? 'native' : 'none'}），
 *       Windows 上 CCB 会给 {@code "none"}。
 *       <b>Phase 1b 裁定 ⇒ 本仓固定为 {@code "none"}</b>：本仓<b>不做</b>白名单式截图过滤
 *       （见 {@link ComputerUseAppPolicy} —— 本仓没有 CCB 的 macOS 合成器级过滤实现），
 *       报 {@code "native"} 属于<b>对模型说谎</b>（模型会以为截图已按白名单过滤）。
 *       ⚠️ <b>与 CCB-Windows 的差异（如实标注 · 有意增强）</b>：CCB 在 Windows 因
 *       {@code screenshotFiltering === 'none'} 会<b>整段跳过</b>输入动作的<b>档位门控</b>
 *       （截图不过滤 ⇒ 无需按应用核对可见性）；
 *       <b>本仓的档位门控仍然生效</b>（{@link ComputerUseTierGate} + 授权位存储）⇒
 *       <b>本仓比 CCB-Windows 更严</b>。这是<b>有意增强</b>，不是缺陷：
 *       档位（read / click / full）是 CCB 的<b>全平台</b>语义，
 *       只在其 Windows 分支因截图不过滤而被顺带跳过。
 *       ⇒ <b>改这个常量不会削弱档位门控</b>（两者彼此独立）。</li>
 *   <li><b>{@code windowLocations}</b>：CCB 在多显示器时给出每个授权应用所在显示器。
 *       本仓 Phase 1a 无窗口枚举能力 ⇒ <b>恒省略</b>（CCB 单显示器时也是空数组 ⇒ 省略，
 *       同款语义）。</li>
 * </ul>
 */
public final class ComputerUseRequestAccessResult {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 截图过滤能力标识 · {@code "none"}（Phase 1b 裁定）· 见类 javadoc「与 CCB 的差异」第 2 条。
     *
     * <p><b>取值含义</b>：<b>截图不做任何过滤</b>（所有打开的窗口都可见）——
     * 与 CCB 的 Windows 分支同款（{@code common.ts:55}：{@code darwin ? 'native' : 'none'}）。
     * <p>⚠️ <b>与档位门控无关</b>：本仓的档位门控（{@link ComputerUseTierGate}）<b>仍然生效</b>，
     * 比 CCB-Windows（其 {@code 'none'} 分支整段跳过门控）<b>更严</b>（有意增强）。
     */
    public static final String SCREENSHOT_FILTERING = "none";

    /**
     * 档位门控的反绕过提示 · 逐字 CCB {@code toolCalls.ts:370-376} 的
     * {@code TIER_ANTI_SUBVERSION}。
     */
    static final String TIER_ANTI_SUBVERSION =
            " Do not attempt to work around this restriction — never use AppleScript, "
                    + "System Events, shell commands, or any other method to send clicks or "
                    + "keystrokes to this app.";

    private ComputerUseRequestAccessResult() {
        throw new AssertionError("utility class");
    }

    /**
     * 构造返回 JSON。
     *
     * @param granted         已授权应用（本次弹窗授予 + 此前已授权的幂等项）
     * @param grantedCount    <b>本轮弹窗授予</b>的计数（CCB {@code dialogGranted.length} 语义：
     *                        已授权的幂等项不计入）
     * @param denied          被拒绝的应用（CCB {@code dialogDenied} 形状）
     * @param deniedCount     被拒绝计数
     * @param policyDenied    策略拒绝的应用（可空 / 空 ⇒ 不出 {@code policyDenied} 字段）
     * @param userDenied      用户拒绝的应用（可空 / 空 ⇒ 不出 {@code userDenied} 字段）
     * @param tierGuidance    档位引导文案（null / 空 ⇒ 不出该字段）
     * @return 返回 JSON 根节点
     */
    public static ObjectNode build(List<ComputerUseGrant> granted,
                                   int grantedCount,
                                   List<ComputerUseResolvedApp> denied,
                                   int deniedCount,
                                   List<ComputerUseResolvedApp> policyDenied,
                                   List<ComputerUseResolvedApp> userDenied,
                                   String tierGuidance) {
        ObjectNode root = JSON.createObjectNode();

        ArrayNode grantedNode = root.putArray("granted");
        if (granted != null) {
            for (ComputerUseGrant grant : granted) {
                if (grant == null) {
                    continue;
                }
                ObjectNode node = grantedNode.addObject();
                node.put("appId", grant.appId());
                node.put("displayName", grant.displayName());
                node.put("grantedAt", grant.grantedAt());
                if (grant.tier() != null) {
                    node.put("tier", grant.tier());
                }
            }
        }

        ArrayNode deniedNode = root.putArray("denied");
        if (denied != null) {
            for (ComputerUseResolvedApp app : denied) {
                if (app == null) {
                    continue;
                }
                ObjectNode node = deniedNode.addObject();
                node.put("appId", app.appId());
                node.put("requestedName", app.requestedName());
                node.put("reason", "user_denied");
            }
        }

        if (policyDenied != null && !policyDenied.isEmpty()) {
            ObjectNode policyNode = root.putObject("policyDenied");
            ArrayNode apps = policyNode.putArray("apps");
            for (ComputerUseResolvedApp app : policyDenied) {
                ObjectNode node = apps.addObject();
                node.put("requestedName", app.requestedName());
                node.put("displayName", app.displayName());
            }
            policyNode.put("guidance", ComputerUseAppPolicy.policyDeniedGuidance(
                    policyDenied.stream().map(ComputerUseResolvedApp::displayName).toList()));
        }

        if (userDenied != null && !userDenied.isEmpty()) {
            ObjectNode userNode = root.putObject("userDenied");
            ArrayNode apps = userNode.putArray("apps");
            for (ComputerUseResolvedApp app : userDenied) {
                ObjectNode node = apps.addObject();
                node.put("requestedName", app.requestedName());
                node.put("displayName", app.displayName());
            }
            userNode.put("guidance", userDeniedGuidance(
                    userDenied.stream().map(ComputerUseResolvedApp::displayName).toList()));
        }

        if (tierGuidance != null && !tierGuidance.isBlank()) {
            root.put("tierGuidance", tierGuidance);
        }

        root.put("screenshotFiltering", SCREENSHOT_FILTERING);

        // windowLocations：CCB 单显示器时为空数组 ⇒ 省略；本仓 Phase 1a 无窗口枚举 ⇒ 恒省略。

        root.put("granted_count", grantedCount);
        root.put("denied_count", deniedCount);
        return root;
    }

    /**
     * 档位引导文案 · 逐字对齐 CCB {@code toolCalls.ts:1287-1345} 的
     * {@code buildTierGuidanceMessage}。
     *
     * <p><b>WHY（CCB 注释原意）</b>：模型<b>撞上档位门控之前</b>就要知道每档能做什么 ——
     * 否则模型会在被拒后去找别的路子（CCB 末尾追加 {@code TIER_ANTI_SUBVERSION} 显式关门）。
     *
     * @param tieredApps 已授予且档位非 full 的应用
     * @return 引导文案（无受限档位 ⇒ 空串）
     */
    public static String tierGuidance(List<ComputerUseResolvedApp> tieredApps) {
        if (tieredApps == null || tieredApps.isEmpty()) {
            return "";
        }
        List<ComputerUseResolvedApp> readBrowsers = tieredApps.stream()
                .filter(a -> "read".equals(a.tier()))
                .toList();
        List<ComputerUseResolvedApp> clickTier = tieredApps.stream()
                .filter(a -> "click".equals(a.tier()))
                .toList();

        java.util.List<String> parts = new java.util.ArrayList<>();

        if (!readBrowsers.isEmpty()) {
            String names = readBrowsers.stream()
                    .map(a -> "\"" + a.displayName() + "\"").reduce((x, y) -> x + ", " + y).orElse("");
            boolean one = readBrowsers.size() == 1;
            parts.add(names + (one ? " is a browser" : " are browsers") + " — "
                    + "granted at tier \"read\" (visible in screenshots only; no clicks or "
                    + "typing). You can read what's on screen but cannot navigate, click, "
                    + "or type into " + (one ? "it" : "them") + ". For browser "
                    + "interaction, use the Claude-in-Chrome MCP (tools named "
                    + "`mcp__Claude_in_Chrome__*`; load via SearchExtraTools if deferred).");
        }

        if (!clickTier.isEmpty()) {
            String names = clickTier.stream()
                    .map(a -> "\"" + a.displayName() + "\"").reduce((x, y) -> x + ", " + y).orElse("");
            boolean one = clickTier.size() == 1;
            parts.add(names + (one ? " has" : " have") + " terminal or IDE "
                    + "capabilities — granted at tier \"click\" (visible + plain left-click "
                    + "only; NO typing, key presses, right-click, modifier-clicks, or "
                    + "drag-drop). You can click buttons and scroll output, but "
                    + (one ? "its" : "their") + " integrated terminal and "
                    + "editor are off-limits to keyboard input. Right-click (context-menu "
                    + "Paste) and dragging text onto " + (one ? "it" : "them") + " "
                    + "require tier \"full\". For shell commands, use the Bash tool.");
        }

        if (parts.isEmpty()) {
            return "";
        }
        return String.join("\n\n", parts) + TIER_ANTI_SUBVERSION;
    }

    /**
     * 用户拒绝引导文案 · 逐字对齐 CCB {@code toolCalls.ts:1353-1366} 的
     * {@code buildUserDeniedGuidance}。
     *
     * @param displayNames 被用户拒绝的应用展示名
     * @return 引导文案（英文）
     */
    public static String userDeniedGuidance(List<String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return "";
        }
        String names = displayNames.stream().map(n -> "\"" + n + "\"").reduce((x, y) -> x + ", " + y).orElse("");
        boolean one = displayNames.size() == 1;
        return names + (one ? " is" : " are") + " in the user's auto-deny list "
                + "(Settings → Desktop app (General) → Computer Use → Denied apps). "
                + "Requests for " + (one ? "this app" : "these apps") + " are automatically "
                + "denied. If you need access for this task, ask the user to remove "
                + (one ? "it" : "them") + " from their deny list in Settings — you cannot "
                + "request this through the tool.";
    }
}
