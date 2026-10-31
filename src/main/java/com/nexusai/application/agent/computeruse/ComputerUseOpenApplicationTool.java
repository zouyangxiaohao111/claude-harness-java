package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * {@code mcp__computer-use__open_application} · 启动 / 置前应用工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:401-416}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:402-415}）</h2>
 * <pre>
 * name: 'open_application',
 * description: 'Bring an application to the front, launching it if necessary. The target application must already be in the session allowlist — call request_access first.',
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     app: { type: 'string', description: 'Display name (e.g. "Slack") or bundle identifier (e.g. "com.tinyspeck.slackmacgap").' },
 *   },
 *   required: ['app'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:2836-2895} 的 {@code handleOpenApplication}）</h2>
 * <ol>
 *   <li>取 {@code app}（{@code requireString}）；</li>
 *   <li><b>必须在会话 allowlist 里</b>：CCB {@code toolCalls.ts:2845-2866} 先按
 *       {@code bundleId} 精确匹配、否则按 {@code displayName.toLowerCase()} 匹配
 *       ⇒ 找不到 ⇒ 报错（CCB {@code toolCalls.ts:2861-2865}：
 *       {@code '"${app}" is not granted for this session. Call request_access first.'}）；</li>
 *   <li><b>无档位门控</b>：CCB {@code toolCalls.ts:2868-2870} 的注释明确：
 *       “open_application works at any tier — bringing an app forward is exactly what tier
 *       "read" enables.” ⇒ 本仓<b>不做</b>前台白名单/档位门控；</li>
 *   <li>启动应用；</li>
 *   <li>成功文案：CCB {@code toolCalls.ts:2895} {@code `Opened "${app}".`}；
 *       <b>多显示器</b>（{@code displayCount >= 2}）时 CCB 追加提示
 *       （{@code toolCalls.ts:2877-2893}）：
 *       {@code `Opened "${app}". If it isn't visible in the next screenshot, it may have
 *       opened on a different monitor — use switch_display to check.`}
 *       ⇒ 本仓<b>照做</b>（本仓有 {@link ComputerUseDisplayStore} ⇒ 等价于 CCB 的
 *       {@code onDisplayPinned} 回调存在）；显示器枚举失败时该提示<b>跳过</b>（CCB 同款：
 *       提示是建议性的）。</li>
 * </ol>
 *
 * <h2>⚠️ 与 CCB-Windows 的<b>路线差异</b>（如实标注）</h2>
 * <p>CCB-Windows 的 {@code apps.open()}（{@code platforms/win32.ts:565-756}）启动后会把窗口
 * <b>挪到屏幕外</b>（{@code (-32000,-32000)}）并绑定 —— 那是 CCB「窗口定向输入」路线的配套动作。
 * <br>本仓走<b>全局注入</b>路线（无窗口绑定语义）⇒ <b>不能</b>照抄挪窗口那一步
 * （会把用户的应用藏起来）⇒ 本仓只做「正常启动并置前」。
 *
 * <h2>应用标识解析</h2>
 * <p>CCB 用 bundleId / displayName 匹配 allowlist；本仓的授权键是
 * <b>归一后的可执行文件名</b>（{@link ComputerUseAppPolicy#normalizeAppKey(String)}）
 * ⇒ 解析时：先按授权键精确匹配，再按展示名（大小写不敏感）匹配 —— 与
 * {@link ComputerUseRequestAccessTool} 的解析口径同源。
 */
public final class ComputerUseOpenApplicationTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseOpenApplicationTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__open_application";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:403-404}。 */
    private static final String DESCRIPTION =
            "Bring an application to the front, launching it if necessary. "
                    + "The target application must already be in the session allowlist — call request_access first.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:405-414}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "app": {
                  "type": "string",
                  "description": "Display name (e.g. \\"Slack\\") or bundle identifier (e.g. \\"com.tinyspeck.slackmacgap\\")."
                }
              },
              "required": ["app"]
            }
            """;

    private final ComputerUseAppLauncher launcher;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseDisplayProvider displayProvider;

    /**
     * @param launcher   应用启动实现
     * @param grantStore 会话授权位/白名单存储（null ⇒ 无 allowlist ⇒ 一律拒绝）
     */
    public ComputerUseOpenApplicationTool(ComputerUseAppLauncher launcher, ComputerUseGrantStore grantStore) {
        this(launcher, grantStore, null);
    }

    /**
     * @param launcher        应用启动实现
     * @param grantStore      会话授权位/白名单存储（null ⇒ 无 allowlist ⇒ 一律拒绝）
     * @param displayProvider 显示器枚举（null ⇒ 不产出多显示器提示；
     *                        CCB {@code toolCalls.ts:2877-2893} 的同款建议性提示）
     */
    public ComputerUseOpenApplicationTool(ComputerUseAppLauncher launcher,
                                          ComputerUseGrantStore grantStore,
                                          ComputerUseDisplayProvider displayProvider) {
        if (launcher == null) {
            throw new IllegalArgumentException("ComputerUseOpenApplicationTool 需要启动器实现");
        }
        this.launcher = launcher;
        this.grantStore = grantStore;
        this.displayProvider = displayProvider;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        try {
            return JSON.readTree(INPUT_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[computer-use] open_application inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 启动应用会改系统状态 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /**
     * 输入动作 · <b>权限自决 Allow（免弹窗）</b> · 对齐 CCB
     * {@code src/utils/computerUse/setup.ts:25-32}。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use open_application is gated by the "
                        + "session allowlist granted via request_access"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行「启动 / 置前应用」。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode appNode = call.input().path("app");
        if (!appNode.isTextual()) {
            // CCB toolCalls.ts:2842-2843（requireString 的错误文案逐字）
            return ToolResult.error(call.id(), "\"app\" must be a string.");
        }
        String app = appNode.asText().trim();
        if (app.isEmpty()) {
            return ToolResult.error(call.id(), "\"app\" must be a string.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 必须在会话 allowlist 里 · CCB toolCalls.ts:2845-2866
        ComputerUseGrant grant = findGrant(sessionId, app);
        if (grant == null) {
            // CCB toolCalls.ts:2862-2865（错误文案逐字）
            return ToolResult.error(call.id(),
                    "\"" + app + "\" is not granted for this session. Call request_access first.");
        }

        try {
            launcher.launch(grant.appId());
        } catch (Exception e) {
            log.error("[computer-use] {} 启动失败: app={} appId={} err={}",
                    TOOL_NAME, app, grant.appId(), e.getMessage(), e);
            return ToolResult.error(call.id(), "启动应用失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} app={} → appId={} tier={}",
                    TOOL_NAME, sessionId, app, grant.appId(), grant.tier());
        }

        // 多显示器补充提示 · CCB toolCalls.ts:2877-2893
        // （单显示器 ⇒ 无提示；枚举失败 ⇒ 跳过提示，提示是建议性的）
        if (displayProvider != null && displayCount() >= 2) {
            // CCB toolCalls.ts:2888-2891（逐字文案）
            return ToolResult.success(call.id(),
                    "Opened \"" + app + "\". If it isn't visible in the next screenshot, it may "
                            + "have opened on a different monitor — use switch_display to check.");
        }
        // CCB toolCalls.ts:2895 成功文案逐字对齐：Opened "<app>".
        return ToolResult.success(call.id(), "Opened \"" + app + "\".");
    }

    /**
     * 已连接的显示器数量（枚举失败 ⇒ 0 ⇒ 不产出多显示器提示）。
     *
     * @return 显示器数量
     */
    private int displayCount() {
        try {
            return displayProvider.listDisplays().size();
        } catch (Exception e) {
            // 提示是建议性的 ⇒ 枚举失败不致命（CCB toolCalls.ts:2881-2885 同款）
            log.warn("[computer-use] {} 枚举显示器失败（跳过多屏提示）: {}",
                    TOOL_NAME, e.getMessage());
            return 0;
        }
    }

    /**
     * 在会话白名单里找应用：先按授权键精确匹配，再按展示名（大小写不敏感）匹配。
     *
     * <p>与 CCB {@code toolCalls.ts:2845-2866} 的两段式匹配（bundleId 精确 → displayName
     * 大小写不敏感）同款，只是本仓的「bundleId」= 归一后的 exe 名。
     *
     * @param sessionId 会话 id
     * @param app       模型给的应用名
     * @return 匹配的授权条目；没有 ⇒ null
     */
    private ComputerUseGrant findGrant(String sessionId, String app) {
        if (grantStore == null) {
            return null;
        }
        ComputerUseGrants grants = grantStore.read(sessionId);
        String normalized = ComputerUseAppPolicy.normalizeAppKey(app);
        for (ComputerUseGrant grant : grants.apps()) {
            if (grant.normalizedAppId().equals(normalized)) {
                return grant;
            }
        }
        String lower = app.toLowerCase(Locale.ROOT);
        for (ComputerUseGrant grant : grants.apps()) {
            if (!grant.displayName().isBlank() && grant.displayName().toLowerCase(Locale.ROOT).equals(lower)) {
                return grant;
            }
        }
        return null;
    }
}
