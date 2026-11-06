package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpStringUtils;
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
import java.util.List;

/**
 * {@code mcp__computer-use__observe} · <b>AX 语义树观察</b>
 * （逐条对齐 dsh {@code dsh-computer-use} 的 {@code computer_observe}）。
 *
 * <h2>dsh 的原始契约（本工具按此实现，不简化）</h2>
 * <ul>
 *   <li>返回 {@code observationId}：<b>元素序号只属于这一次观察</b>
 *       （{@code tools.ts:359} "Element indexes belong only to the returned observationId"）；</li>
 *   <li>返回 {@code elements}（index + targetHandle + role + name + value + actions）；</li>
 *   <li>返回 {@code tree.mode}（{@code full} / {@code diff}）+ {@code tree.text} + {@code truncated}；</li>
 *   <li>{@code full} 参数强制完整树；否则与同一应用上一次观察做差分
 *       （dsh {@code service.ts:770-778}）；</li>
 *   <li>返回权限状态（{@code accessibility} / {@code screenRecording}）。</li>
 * </ul>
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li><b>app 参数</b>：dsh 要求显式 {@code app}（bundleId / pid / name）。
 *       本仓沿用既有 computer-use 的「前台应用」语义（所有输入工具都按前台应用做档位门控），
 *       故观察对象 = <b>当前前台应用</b>，不额外要求选择 —— 避免观察 A 而动作打向 B。</li>
 *   <li><b>screenshot</b>：dsh 把它做成同一工具的 Artifact 选项。本仓已有独立的
 *       {@code screenshot} / {@code zoom} 工具 ⇒ 这里不重复（并在描述里说明分工）。</li>
 *   <li><b>截图 Artifact</b> 与<b>视觉交接</b>（dsh 的 {@code deferVisionHandoff}）不在此实现。</li>
 * </ul>
 */
public final class ComputerUseObserveTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseObserveTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "observe");

    /** 工具描述（对齐 dsh 的取向：先观察、索引只属于本次观察、优先树而非截图）。 */
    private static final String DESCRIPTION =
            "Read a fresh Accessibility observation of one exact running app. Element indexes belong only "
                    + "to the returned observationId. Prefer the tree over a screenshot: it names every "
                    + "control and is far smaller. Pass observationId plus elementIndex (or targetHandle) "
                    + "to click_element / type_into_element / perform_action. A stale observation is "
                    + "rejected — observe again after the UI changes. When the tree does not answer a "
                    + "visual question, request a screenshot artifact (screenshot=optional) and pass its "
                    + "path to vision_analyze for OCR or visual inspection; coordinates read off that "
                    + "image are image pixels and must be divided by pixelScale.";

    /** 输入 schema（对齐 dsh {@code tools.ts:359-377} 的 {@code computer_observe}）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "app": {
                  "type": "object",
                  "description": "The exact app to observe. Omit to observe the frontmost app. Prefer bundleId.",
                  "properties": {
                    "bundleId": {
                      "type": "string",
                      "description": "Preferred exact macOS bundle identifier."
                    },
                    "pid": {
                      "type": "integer",
                      "description": "Exact current process id when already observed."
                    },
                    "name": {
                      "type": "string",
                      "description": "Display name accepted only when it resolves uniquely."
                    }
                  },
                  "additionalProperties": false
                },
                "screenshot": {
                  "type": "string",
                  "enum": ["none", "optional", "required"],
                  "description": "Default optional: capture a screenshot artifact when Screen Recording is granted. Required fails when it is not."
                },
                "full": {
                  "type": "boolean",
                  "description": "Return a full tree instead of a diff from the previous observation of the same app."
                },
                "max_depth": {
                  "type": "integer",
                  "description": "Maximum tree depth to walk. Defaults to 14 (matches dsh)."
                }
              },
              "required": []
            }
            """;

    /**
     * 默认最大深度 · dsh {@code config.ts:93} {@code maxDepth: z.number().default(14)}。
     *
     * <p><b>⚠️ 与 Windows UIA 的 4 不同</b>：Windows 的 UIA 树层级浅、每层节点多，
     * 4 层足够；macOS 的 AX 树在背景应用里会多套几层 Group（实测 Finder 工具栏在 5~7 层），
     * 4 层会漏掉真正的控件。dsh 用 14 ⇒ 本仓 macOS 侧对齐 14。
     */
    public static final int DEFAULT_MAX_DEPTH = 14;

    // ⛔ 不在此声明节点/字节上限：真正的执行者是 MacOsUiaService.MAX_NODES /
    //   MAX_TEXT_BYTES。曾在这里留两个「500 / 64000」常量且从未被读取
    //   ⇒ 改这边不会生效（假单源）。现统一以 MacOsUiaService 为准。

    private final ComputerUseUiaService uiaService;
    private final ComputerUseUiSnapshotStore snapshotStore;
    private final MacOsTccChecker tcc;
    /** 截图器（可为 null ⇒ 不支持截图产物）。 */
    private final ComputerUseScreenCapturer capturer;

    /**
     * @param uiaService    AX/UIA 服务
     * @param snapshotStore 观察存储
     * @param tcc           权限检查（可为 null ⇒ 不报权限状态）
     */
    public ComputerUseObserveTool(ComputerUseUiaService uiaService,
                                  ComputerUseUiSnapshotStore snapshotStore,
                                  MacOsTccChecker tcc) {
        this(uiaService, snapshotStore, tcc, null);
    }

    /**
     * @param uiaService    AX/UIA 服务
     * @param snapshotStore 观察存储
     * @param tcc           权限检查（可为 null ⇒ 不报权限状态）
     * @param capturer      截图器（可为 null ⇒ {@code screenshot} 参数退化为「不截」）
     */
    public ComputerUseObserveTool(ComputerUseUiaService uiaService,
                                  ComputerUseUiSnapshotStore snapshotStore,
                                  MacOsTccChecker tcc,
                                  ComputerUseScreenCapturer capturer) {
        this.uiaService = uiaService;
        this.snapshotStore = snapshotStore;
        this.tcc = tcc;
        this.capturer = capturer;
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
                    "[computer-use] observe inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只读。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读工具 ⇒ 免弹窗（与 screenshot 同类；审批由 {@code request_access} 一次授权整会话）。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use observe is read-only"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        boolean full = input.path("full").asBoolean(false);
        int maxDepth = input.path("max_depth").asInt(DEFAULT_MAX_DEPTH);
        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseAppSelector selector = readSelector(input.path("app"));
        String screenshotMode = readScreenshotMode(input);

        try {
            ComputerUseAccessibilitySnapshot snapshot =
                    uiaService.captureSnapshot(0L, maxDepth, selector);
            List<ComputerUseUiSnapshotStore.ObservedElement> elements = toObservedElements(snapshot.nodes());

            if (elements.isEmpty()) {
                ObjectNode empty = JSON.createObjectNode();
                empty.put("observationId", "");
                empty.put("tree", "(no accessibility elements exposed by the observed window)");
                empty.put("hint", "The app may not implement accessibility (canvas-drawn UI) — "
                        + "use screenshot + click coordinates instead.");
                return ToolResult.success(call.id(), empty.toPrettyString());
            }

            String treeText = snapshot.text().isEmpty()
                    ? ComputerUseAccessibilitySnapshot.formatForModel(snapshot.nodes())
                    : snapshot.text();

            ComputerUseUiSnapshotStore.Observation observation = snapshotStore.record(
                    sessionId,
                    snapshot.appName(),
                    snapshot.bundleId(),
                    snapshot.pid(),
                    snapshot.windowTitle(),
                    snapshot.stateHash(),
                    treeText,
                    snapshot.truncated(),
                    elements,
                    full,
                    // 窗口几何：供后续 scroll/drag 定向投递（投给「哪个窗口的哪个点」）
                    snapshot.windowId(),
                    snapshot.windowX(),
                    snapshot.windowY());

            ObjectNode out = JSON.createObjectNode();
            out.put("observationId", observation.observationId());
            // app / window 身份：模型据此确认「操作的是哪个应用的哪个窗口」（dsh observationSchema）
            ObjectNode app = out.putObject("app");
            app.put("name", observation.appName());
            app.put("bundleId", observation.bundleId());
            app.put("pid", observation.pid());
            // 观察的有效期：模型据此知道手上的观察还能用多久（dsh createdAt / expiresAt）
            out.put("createdAt", observation.createdAt());
            out.put("expiresAt", observation.expiresAt() == Long.MAX_VALUE
                    ? "9999-12-31T23:59:59.999Z"
                    : java.time.Instant.ofEpochMilli(observation.expiresAt()).toString());
            // 观察对象是否在前台：后台观察时模型应知道「我看到的是后台窗口」
            out.put("frontmost", snapshot.frontmost());
            if (!observation.windowTitle().isEmpty() || snapshot.hasWindowFrame()) {
                ObjectNode window = out.putObject("window");
                window.put("title", observation.windowTitle());
                if (snapshot.hasWindowFrame()) {
                    ObjectNode frame = window.putObject("frame");
                    frame.put("x", snapshot.windowX());
                    frame.put("y", snapshot.windowY());
                    frame.put("width", snapshot.windowWidth());
                    frame.put("height", snapshot.windowHeight());
                }
                if (snapshot.windowId() > 0) {
                    window.put("id", snapshot.windowId());
                }
            }
            ObjectNode tree = out.putObject("tree");
            tree.put("mode", observation.treeMode());
            tree.put("text", observation.treeText());
            tree.put("truncated", observation.truncated());
            out.set("elements", ComputerUseUiSnapshotStore.elementsToJson(observation.elements()));
            ObjectNode permissions = out.putObject("permissions");
            if (tcc != null) {
                MacOsTccChecker.Status status = tcc.status();
                permissions.put("accessibility", status.accessibilityGranted() ? "granted" : "denied");
                permissions.put("screenRecording", status.screenRecordingGranted() ? "granted" : "denied");
            } else {
                permissions.put("accessibility", "unavailable");
                permissions.put("screenRecording", "unavailable");
            }
            // 截图产物（dsh Artifact 形态：文件路径 + pixelScale，不是 inline image）
            ObjectNode screenshot = ComputerUseScreenshotArtifact.capture(
                    capturer, tcc, sessionId, observation.observationId(),
                    screenshotMode, permissions);
            if (screenshot != null) {
                out.set("screenshot", screenshot);
            }

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: session={} observationId={} 元素={} 模式={} 文本字节={} 前台={} 截图={}",
                        TOOL_NAME, sessionId, observation.observationId(), elements.size(),
                        observation.treeMode(), treeText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        snapshot.frontmost(), screenshot == null ? "无" : "有");
            }
            return ToolResult.success(call.id(), out.toPrettyString());
        } catch (Exception e) {
            log.error("[computer-use] {} 失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "读取辅助功能树失败: " + e.getMessage());
        }
    }

    /**
     * 读 {@code app} 选择器（dsh {@code appSelectorSchema}）。
     *
     * @param appNode 输入里的 {@code app} 节点（可缺失）
     * @return 选择器（缺失 ⇒ 前台应用）
     */
    static ComputerUseAppSelector readSelector(JsonNode appNode) {
        if (appNode == null || !appNode.isObject()) {
            return ComputerUseAppSelector.frontmost();
        }
        return new ComputerUseAppSelector(
                appNode.path("bundleId").asText(""),
                appNode.path("pid").asLong(0L),
                appNode.path("name").asText(""));
    }

    /**
     * 读 {@code screenshot} 模式（dsh {@code none|optional|required}）。
     *
     * @param input 输入
     * @return 模式（缺省 {@code optional}）
     */
    static String readScreenshotMode(JsonNode input) {
        String mode = input.path("screenshot").asText("");
        if ("none".equals(mode) || "optional".equals(mode) || "required".equals(mode)) {
            return mode;
        }
        return "optional";
    }

    /**
     * 节点树 → 观察元素表（先序 + 每个元素分配 targetHandle）。
     *
     * <p>对齐 dsh {@code publicElements()}（{@code service.ts:54-63}）：
     * <b>每个元素都发一个不透明 {@code targetHandle}</b>，与 locator 一起构成「精确 locator + 可重绑句柄」两件套。
     *
     * @param nodes 节点树
     * @return 元素表（先序）
     */
    static List<ComputerUseUiSnapshotStore.ObservedElement> toObservedElements(
            List<ComputerUseAccessibilityNode> nodes) {
        List<ComputerUseUiSnapshotStore.ObservedElement> out = new ArrayList<>();
        flatten(nodes, out);
        return out;
    }

    private static void flatten(List<ComputerUseAccessibilityNode> nodes,
                                List<ComputerUseUiSnapshotStore.ObservedElement> out) {
        if (nodes == null) {
            return;
        }
        for (ComputerUseAccessibilityNode node : nodes) {
            out.add(new ComputerUseUiSnapshotStore.ObservedElement(
                    node.index(),
                    java.util.UUID.randomUUID().toString(),
                    node.locator(),
                    node.role(),
                    node.name(),
                    node.value(),
                    node.enabled(),
                    node.actions(),
                    node.x(),
                    node.y(),
                    node.width(),
                    node.height(),
                    // 子角色与 nativeIdentifier 是 dsh 三级解析的稳定字段
                    node.subrole(),
                    node.nativeIdentifier(),
                    // ⛔ focused / selected 曾在此被漏掉（用 14 参兼容构造）⇒ 观察里恒为 null
                    //   ⇒ elementsToJson 的两个 if 永远不成立、模型永远看不到焦点/选中态
                    node.focused(),
                    node.selected()));
            flatten(node.children(), out);
        }
    }
}
