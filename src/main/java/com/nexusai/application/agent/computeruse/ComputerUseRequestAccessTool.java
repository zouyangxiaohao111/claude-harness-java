package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code mcp__computer-use__request_access} · computer-use 授权申请工具
 * （对齐 CCB 2.8.4 computer-use MCP 的 {@code request_access}）。
 *
 * <h2>工具面</h2>
 * <p>本仓 Phase 1a <b>只加</b>这一个工具（工具面最终 35 个）。CCB 的
 * {@code request_access} 是 computer-use MCP 的<b>第一个</b>工具
 * （{@code packages/@ant/computer-use-mcp/src/tools.ts:161-200}）。
 *
 * <h2>处理流程（对齐 CCB {@code toolCalls.ts:887-1083} 的 {@code handleRequestAccess}）</h2>
 * <ol>
 *   <li><b>锁检查</b>（只查不拿）—— CCB {@code toolCalls.ts:4378-4404} 的 Gate-3：
 *       被<b>其它</b>会话持锁 ⇒ 直接报错，<b>连弹窗都不弹</b>（"a blocked session doesn't
 *       show an approval dialog for access it can't use"）。</li>
 *   <li><b>已授权的应用幂等跳过</b> —— CCB {@code toolCalls.ts:980-993} 的
 *       {@code buildAccessRequest}：已授权的应用不进弹窗；<b>全部已授权 ⇒ 完全不弹窗</b>
 *       （直接返回已授权列表）。</li>
 *   <li><b>策略黑名单优先</b> —— CCB {@code toolCalls.ts:1048-1055}：策略拒绝的应用在弹窗
 *       <b>之前</b>就被剥离，<b>优先级高于用户拒绝、无逃生口</b>。</li>
 *   <li><b>其余走审批气泡</b>（本仓现有的通用权限弹窗 · allow / deny <b>二值</b>）——
 *       整组批准 / 整组拒绝。</li>
 *   <li><b>按档位落库</b>：浏览器 → read；终端 / IDE → click；其它 → full
 *       （CCB {@code deniedApps.ts:537-542}）。</li>
 *   <li><b>授权位整组授予</b>：三个授权位随整组批准 / 拒绝一起生效 ⇒ <b>与 CCB 一致</b>
 *       （CCB 的 CLI 审批面板本身就是整组授予：{@code ComputerUseApproval.tsx:126-127} 的
 *       "Per-item toggles are a follow-up; for now every resolved app is granted when the
 *       user accepts" 与 {@code :180-184} 的 "Grant all requested flags on allow — per-flag
 *       toggles are a follow-up"）。CCB 自己把逐项勾选标注为 follow-up ⇒ 本仓也不做。</li>
 * </ol>
 *
 * <h2>授权位合并 = truthy-only 只增不减</h2>
 * <p>CCB {@code packages/@ant/computer-use-mcp/src/mcpServer.ts:58-77} 的
 * {@code mergePermissionResponse}：后一次 {@code request_access} <b>不能撤销</b>先前的
 * 剪贴板授权（撤销只发生在 Settings 页）。本仓逐字实现于
 * {@link ComputerUseGrantFlags#mergeTruthyOnly}。
 *
 * <h2>唯一审批入口</h2>
 * <p>本工具的 {@code checkPermissions} 返回 {@link PermissionResult#Passthrough} ⇒
 * 权限管线第 3 层（{@code CheckLayer3_PassthroughToAsk}）转 {@link PermissionResult#Ask}
 * ⇒ 走本仓现有的 STOMP 审批气泡（{@code WebSocketPermissionPrompter}）。
 * <b>不改 STOMP 事件、不改前端</b>。
 * <p>⛔ <b>不得</b>给 {@code mcp__computer-use__*} 注入 allow rule
 * （{@code CheckLayer2b_ToolAlwaysAllowed} 首个命中即返回、无 deny 优先 ⇒ 会把本工具一起放行）。
 *
 * <h2>锁语义（本仓与 CCB 的一处<b>有意差异</b>）</h2>
 * <p>CCB 的 {@code defersLockAcquire}（{@code toolCalls.ts:808-812}）把 {@code request_access}
 * 列为"只查不拿"（<b>所有</b>调用方都不拿锁）—— 因为锁由第一个<b>动作</b>工具拿。
 * <p>本仓 Phase 1a 尚无动作工具接锁 ⇒ 改为：<b>主线程</b>的 {@code request_access}
 * 在批准后拿锁（本会话从此刻起独占桌面，轮末由
 * {@code StopHookPipeline.cleanupComputerUseAfterTurn} 释放）；
 * <b>子代理</b>的 {@code request_access} 只查不拿（子代理释放会让主线程误判锁状态 ——
 * CCB {@code src/query/stopHooks.ts:169-180} 的注释明确指出这点）。
 */
public final class ComputerUseRequestAccessTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseRequestAccessTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__request_access";

    /**
     * 工具描述 · 逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:162-166}。
     *
     * <p>CCB 原文：
     * “Request user permission to control a set of applications for this session. Must be
     * called before any other tool in this server. The user sees a single dialog listing all
     * requested apps and either allows the whole set or denies it. Call this again mid-session
     * to add more apps; previously granted apps remain granted. Returns the granted apps,
     * denied apps, and screenshot filtering capability.”
     */
    private static final String DESCRIPTION =
            "Request user permission to control a set of applications for this session. "
                    + "Must be called before any other tool in this server. "
                    + "The user sees a single dialog listing all requested apps and either allows "
                    + "the whole set or denies it. "
                    + "Call this again mid-session to add more apps; previously granted apps remain granted. "
                    + "Returns the granted apps, denied apps, and screenshot filtering capability.";

    /**
     * 输入 schema · 逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:167-199}。
     *
     * <p>CCB 的 {@code apps} 描述末尾会追加一个运行时字段 {@code installedAppsHint}
     * （已安装应用名清单）—— 本仓 Phase 1a <b>无应用枚举能力</b> ⇒ 该运行时后缀省略，
     * 其余逐字保留。
     *
     * <p>⛔ <b>不声明</b> {@code resolvedApps} 等后端注入字段：那是<b>运行时</b>注塑进
     * 工具入参的载荷（见 {@link #injectResolvedApps}），<b>不是</b>模型可填的 schema 字段 ——
     * 加进 schema 就成了"模型可以自己声明授权结果"的伪造入口。
     */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "apps": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Application display names (e.g. \\"Slack\\", \\"Calendar\\") or bundle identifiers (e.g. \\"com.tinyspeck.slackmacgap\\"). Display names are resolved case-insensitively against installed apps."
                },
                "reason": {
                  "type": "string",
                  "description": "One-sentence explanation shown to the user in the approval dialog. Explain the task, not the mechanism."
                },
                "clipboardRead": {
                  "type": "boolean",
                  "description": "Also request permission to read the user's clipboard (separate checkbox in the dialog)."
                },
                "clipboardWrite": {
                  "type": "boolean",
                  "description": "Also request permission to write the user's clipboard. When granted, multi-line `type` calls use the clipboard fast path."
                },
                "systemKeyCombos": {
                  "type": "boolean",
                  "description": "Also request permission to send system-level key combos (quit app, switch app, lock screen). Without this, those specific combos are blocked."
                }
              },
              "required": ["apps", "reason"]
            }
            """;

    /** 注入到工具入参里的解析结果键名（前端 Phase 1b 的 CU 审批面板读它）。 */
    public static final String RESOLVED_APPS_KEY = "resolvedApps";

    private final ComputerUseGrantStore grantStore;
    private final ComputerUseLockService lockService;

    /**
     * @param grantStore  会话授权位读写
     * @param lockService 跨会话锁服务
     */
    public ComputerUseRequestAccessTool(ComputerUseGrantStore grantStore, ComputerUseLockService lockService) {
        if (grantStore == null || lockService == null) {
            throw new IllegalArgumentException("ComputerUseRequestAccessTool 依赖不能为空");
        }
        this.grantStore = grantStore;
        this.lockService = lockService;
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
            throw new IllegalStateException("[computer-use] request_access inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 授权动作改会话状态 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /**
     * 权限自决 · <b>保持 Passthrough</b> ⇒ 权限管线第 3 层转 Ask ⇒ 走本仓现有审批气泡
     * （唯一审批入口）。
     *
     * <p>本方法同时做三件事：
     * <ol>
     *   <li><b>锁检查</b>（只查不拿）：被其它会话持锁 ⇒ 直接 {@link PermissionResult#Deny}
     *       （CCB Gate-3：连弹窗都不弹）。</li>
     *   <li><b>解析 + 注塑</b>：把解析后的应用列表（授权键 / 是否已授权 / 是否策略拒绝 /
     *       档位）就地注入工具入参的 {@code resolvedApps} 键 ⇒ 前端 Phase 1b 的审批面板
     *       直接读（不改 STOMP 事件、不改前端）。</li>
     *   <li><b>幂等判定</b>：全部已授权且无新增授权位 ⇒ {@link PermissionResult#Allow}
     *       （<b>不弹窗</b>，对齐 CCB "全部已授权 ⇒ 完全不弹窗"）。</li>
     * </ol>
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        Request request = parse(input);
        if (request == null) {
            // 参数不合法 ⇒ 交给第 3 层兜底转 Ask（CCB 同款：bad_args 由 handler 报错）
            return new PermissionResult.Passthrough(
                    "Computer use request_access requires an \"apps\" array and a \"reason\" string.",
                    null, List.of(), null, null);
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 1. 锁检查（只查不拿）—— CCB Gate-3
        ComputerUseLockService.LockState lock = lockService.check(sessionId);
        if (lock.blocked()) {
            return new PermissionResult.Deny(
                    "Another session is currently using the computer. Wait for that session to finish, "
                            + "or find a non-computer-use approach.",
                    new PermissionDecisionReason.Other("cu_lock_held"), null);
        }

        // 2. 解析应用 + 注塑 resolvedApps
        ComputerUseGrants grants = grantStore.read(sessionId);
        Resolution resolution = resolve(request, grants);
        injectResolvedApps(input, resolution);

        // 3. 幂等判定：无可问 ⇒ 直接放行（不弹窗）
        if (!resolution.needsApproval()) {
            if (log.isDebugEnabled()) {
                log.debug("[computer-use] {} 无新增授权（全部已授权且授权位已覆盖）⇒ 不弹窗: session={}",
                        TOOL_NAME, sessionId);
            }
            return new PermissionResult.Allow(input,
                    new PermissionDecisionReason.Other("computer-use access already granted"), null, false, null, null);
        }

        // 4. 需要问用户 ⇒ Passthrough（第 3 层转 Ask ⇒ 审批气泡）
        return new PermissionResult.Passthrough(
                buildApprovalMessage(request, resolution),
                new PermissionDecisionReason.Other(buildApprovalMessage(request, resolution)),
                List.of(), null, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行授权申请。
     *
     * <p>流程：解析 → 锁检查 → 分区（策略拒绝 / 已授权 / 待授权）→ 主线程拿锁
     * → 落库（授权 + 授权位 truthy-only 合并）→ 返回 CCB 形状的 JSON。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        Request request = parse(call.input());
        if (request == null) {
            return ToolResult.error(call.id(),
                    "\"apps\" must be an array of strings and \"reason\" must be a string.");
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseGrants grants = grantStore.read(sessionId);
        Resolution resolution = resolve(request, grants);

        // 策略拒绝优先（无逃生口）—— 在任何落库之前剥离
        List<ComputerUseResolvedApp> policyDenied = resolution.policyDenied();
        List<ComputerUseResolvedApp> alreadyGranted = resolution.alreadyGranted();
        List<ComputerUseResolvedApp> toGrant = resolution.toGrant();

        long now = System.currentTimeMillis();
        List<ComputerUseGrant> newGrants = new ArrayList<>();
        for (ComputerUseResolvedApp app : toGrant) {
            newGrants.add(new ComputerUseGrant(app.appId(), app.displayName(), now, app.tier()));
        }

        // 拿锁（主线程拿；子代理只查不拿）
        if (!toGrant.isEmpty()) {
            boolean isSubAgent = ctx != null && ctx.agentId() != null;
            if (isSubAgent) {
                ComputerUseLockService.LockState lock = lockService.check(sessionId);
                if (lock.blocked()) {
                    return ToolResult.error(call.id(),
                            "Another session is currently using the computer. Wait for that session to finish, "
                                    + "or find a non-computer-use approach.");
                }
            } else if (!lockService.tryAcquire(sessionId)) {
                return ToolResult.error(call.id(),
                        "Another session is currently using the computer. Wait for that session to finish, "
                                + "or find a non-computer-use approach.");
            }
        }

        // 落库：授权 + 授权位（truthy-only 只增不减）
        ComputerUseGrants after = grants;
        if (!newGrants.isEmpty() || request.anyFlagRequested()) {
            after = grantStore.grant(sessionId, newGrants, request.flags());
        }

        // 返回 CCB 形状
        List<ComputerUseGrant> allGranted = new ArrayList<>();
        for (ComputerUseResolvedApp app : alreadyGranted) {
            ComputerUseGrant g = grants.findByAppId(app.appId());
            if (g != null) {
                allGranted.add(g);
            }
        }
        allGranted.addAll(newGrants);

        List<ComputerUseResolvedApp> tiered = new ArrayList<>();
        for (ComputerUseGrant g : allGranted) {
            if (g.tier() != null && !"full".equals(g.tier())) {
                tiered.add(new ComputerUseResolvedApp(g.displayName(), g.appId(), g.displayName(),
                        true, false, g.tier()));
            }
        }

        ObjectNode result = ComputerUseRequestAccessResult.build(
                allGranted,
                newGrants.size(),
                List.of(),
                0,
                policyDenied,
                List.of(),
                ComputerUseRequestAccessResult.tierGuidance(tiered));

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 新授予={} 已授权={} 策略拒绝={} 授权位="
                            + "clipboardRead={} clipboardWrite={} systemKeyCombos={}",
                    TOOL_NAME, sessionId, newGrants.size(), alreadyGranted.size(), policyDenied.size(),
                    after.flags().clipboardRead(), after.flags().clipboardWrite(),
                    after.flags().systemKeyCombos());
        }
        return ToolResult.success(call.id(), result);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部 · 请求解析
    // ════════════════════════════════════════════════════════════════════

    /**
     * 工具入参解析结果。
     *
     * @param apps   请求的应用名列表（去重、保序）
     * @param reason 给用户看的一句话理由
     * @param flags  请求的授权位
     */
    private record Request(List<String> apps, String reason, ComputerUseGrantFlags flags) {

        /** @return 是否请求了任一授权位 */
        boolean anyFlagRequested() {
            return flags.clipboardRead() || flags.clipboardWrite() || flags.systemKeyCombos();
        }
    }

    /**
     * 解析工具入参。
     *
     * @param input 工具入参
     * @return 解析结果；参数不合法 ⇒ null
     */
    private static Request parse(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        JsonNode appsNode = input.get("apps");
        if (appsNode == null || !appsNode.isArray()) {
            return null;
        }
        Set<String> apps = new LinkedHashSet<>();
        for (JsonNode item : appsNode) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                apps.add(item.asText().trim());
            }
        }
        if (apps.isEmpty()) {
            return null;
        }
        JsonNode reasonNode = input.get("reason");
        String reason = (reasonNode != null && reasonNode.isTextual()) ? reasonNode.asText().trim() : "";
        if (reason.isEmpty()) {
            return null;
        }
        ComputerUseGrantFlags flags = new ComputerUseGrantFlags(
                input.path("clipboardRead").asBoolean(false),
                input.path("clipboardWrite").asBoolean(false),
                input.path("systemKeyCombos").asBoolean(false));
        return new Request(List.copyOf(apps), reason, flags);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部 · 应用解析与分区
    // ════════════════════════════════════════════════════════════════════

    /**
     * 应用解析 + 分区结果。
     *
     * @param all           全部解析结果
     * @param alreadyGranted 已授权（幂等跳过，不进弹窗）
     * @param toGrant       待授权（进弹窗）
     * @param policyDenied  策略拒绝（弹窗前剥离，无逃生口）
     * @param flagsCovered  请求的授权位是否已被现有授权覆盖
     */
    private record Resolution(
            List<ComputerUseResolvedApp> all,
            List<ComputerUseResolvedApp> alreadyGranted,
            List<ComputerUseResolvedApp> toGrant,
            List<ComputerUseResolvedApp> policyDenied,
            boolean flagsCovered) {

        /**
         * @return 本轮是否需要问用户（CCB：{@code needDialog.length > 0 || 有请求的授权位}）
         */
        boolean needsApproval() {
            return !toGrant.isEmpty() || !flagsCovered;
        }
    }

    /**
     * 解析请求的应用并分区。
     *
     * @param request 请求
     * @param grants  当前会话的授权快照
     * @return 分区结果
     */
    private static Resolution resolve(Request request, ComputerUseGrants grants) {
        List<ComputerUseResolvedApp> all = new ArrayList<>();
        List<ComputerUseResolvedApp> already = new ArrayList<>();
        List<ComputerUseResolvedApp> toGrant = new ArrayList<>();
        List<ComputerUseResolvedApp> policy = new ArrayList<>();
        for (String requested : request.apps()) {
            String appId = ComputerUseAppPolicy.normalizeAppKey(requested);
            boolean granted = grants.isGranted(appId);
            boolean policyDenied = ComputerUseAppPolicy.isPolicyDenied(appId, requested);
            ComputerUseTier tier = ComputerUseAppPolicy.defaultTier(appId, requested);
            ComputerUseResolvedApp app = ComputerUseResolvedApp.of(
                    requested, requested, appId, granted, policyDenied, tier.wire());
            all.add(app);
            if (policyDenied) {
                policy.add(app);
            } else if (granted) {
                already.add(app);
            } else {
                toGrant.add(app);
            }
        }
        // 请求的授权位是否已被现有授权覆盖（truthy-only 语义）
        ComputerUseGrantFlags existing = grants.flags();
        boolean covered = (!request.flags().clipboardRead() || existing.clipboardRead())
                && (!request.flags().clipboardWrite() || existing.clipboardWrite())
                && (!request.flags().systemKeyCombos() || existing.systemKeyCombos());
        return new Resolution(all, already, toGrant, policy, covered);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部 · 弹窗文案 / 注塑
    // ════════════════════════════════════════════════════════════════════

    /**
     * 审批弹窗文案 · 本仓通用弹窗只显示单句消息（{@code PermissionMessageGenerator}），
     * 故把「应用列表 + 理由 + 授权位」拼进这一句（CCB 的弹窗面板是 React 组件，
     * 本仓 Phase 1a 不改前端 ⇒ 用文案承载同样的信息）。
     *
     * @param request   请求
     * @param resolution 分区结果
     * @return 弹窗文案
     */
    private static String buildApprovalMessage(Request request, Resolution resolution) {
        StringBuilder sb = new StringBuilder();
        sb.append("Computer use wants to control ");
        List<String> names = resolution.toGrant().stream().map(ComputerUseResolvedApp::displayName).toList();
        sb.append(names.isEmpty() ? "additional applications" : String.join(", ", names));
        sb.append(". Reason: ").append(request.reason());
        if (request.flags().clipboardRead()) {
            sb.append(" Also requested: read the user's clipboard.");
        }
        if (request.flags().clipboardWrite()) {
            sb.append(" Also requested: write the user's clipboard.");
        }
        if (request.flags().systemKeyCombos()) {
            sb.append(" Also requested: send system-level key combos.");
        }
        return sb.toString();
    }

    /**
     * 把解析后的应用列表<b>就地注入</b>工具入参的 {@code resolvedApps} 键。
     *
     * <p><b>WHY 就地注入</b>：本仓的权限弹窗 STOMP 事件（{@code MessagePermissionRequestEvent}）
     * 只有一个结构化载荷 {@code toolInput}（= 权限检查时那份 JsonNode 实例 ——
     * {@code ToolInputValidator.safeParseSchema} 返回的是<b>同一实例</b>，不复制）⇒
     * 就地注入即可让前端 Phase 1b 直接读到解析结果，<b>无需改 STOMP 事件、无需改前端</b>。
     *
     * <p><b>⚠️ 副作用（如实标注）</b>：该 JsonNode 实例同时是
     * {@code ToolUseBlock.input()}（{@code StreamingToolExecutor:1214}
     * {@code JsonNode input = t.call.input();}）⇒ 注入的 {@code resolvedApps} 键
     * <b>会一并出现在 transcript 里模型自己那条 tool_use 的入参</b>。
     * 该键对模型是<b>只读附加信息</b>（模型不填、也不依赖它做决策），不影响
     * {@code apps} / {@code reason} / 三个授权位的语义。
     *
     * <p><b>fail-soft</b>：入参不可变 / 非对象 ⇒ 跳过注入（前端面板拿不到解析结果，
     * 但授权流程本身不受影响）。
     *
     * @param input      工具入参
     * @param resolution 分区结果
     */
    private static void injectResolvedApps(JsonNode input, Resolution resolution) {
        if (!(input instanceof ObjectNode root)) {
            return;
        }
        try {
            ArrayNode arr = root.putArray(RESOLVED_APPS_KEY);
            for (ComputerUseResolvedApp app : resolution.all()) {
                app.writeTo(arr.addObject());
            }
        } catch (Exception e) {
            log.warn("[computer-use] 注入 resolvedApps 失败（前端面板将拿不到解析结果）: {}", e.toString());
        }
    }
}
