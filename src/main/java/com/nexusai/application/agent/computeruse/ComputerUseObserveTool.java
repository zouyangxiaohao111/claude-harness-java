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
            "Read a fresh Accessibility observation of the frontmost app. Element indexes belong only "
                    + "to the returned observationId. Prefer the tree over a screenshot: it names every "
                    + "control and is far smaller. Pass observationId plus elementIndex (or targetHandle) "
                    + "to click_element / type_into_element / set_value. A stale observation is rejected — "
                    + "observe again after the UI changes.";

    /** 输入 schema。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
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

    /** 默认节点上限 · dsh {@code config.ts:92} {@code maxNodes: 500}。 */
    public static final int DEFAULT_MAX_NODES = 500;

    /** 默认文本字节上限 · dsh {@code config.ts:94} {@code maxTextBytes: 64000}。 */
    public static final int DEFAULT_MAX_TEXT_BYTES = 64_000;

    private final ComputerUseUiaService uiaService;
    private final ComputerUseUiSnapshotStore snapshotStore;
    private final MacOsTccChecker tcc;

    /**
     * @param uiaService    AX/UIA 服务
     * @param snapshotStore 观察存储
     * @param tcc           权限检查（可为 null ⇒ 不报权限状态）
     */
    public ComputerUseObserveTool(ComputerUseUiaService uiaService,
                                  ComputerUseUiSnapshotStore snapshotStore,
                                  MacOsTccChecker tcc) {
        this.uiaService = uiaService;
        this.snapshotStore = snapshotStore;
        this.tcc = tcc;
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

        try {
            ComputerUseAccessibilitySnapshot snapshot = uiaService.captureSnapshot(0L, maxDepth);
            List<ComputerUseUiSnapshotStore.ObservedElement> elements = toObservedElements(snapshot.nodes());

            if (elements.isEmpty()) {
                ObjectNode empty = JSON.createObjectNode();
                empty.put("observationId", "");
                empty.put("tree", "(no accessibility elements exposed by the frontmost window)");
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
                    full);

            ObjectNode out = JSON.createObjectNode();
            out.put("observationId", observation.observationId());
            // app / window 身份：模型据此确认「操作的是哪个应用的哪个窗口」（dsh observationSchema）
            ObjectNode app = out.putObject("app");
            app.put("name", observation.appName());
            app.put("bundleId", observation.bundleId());
            app.put("pid", observation.pid());
            if (!observation.windowTitle().isEmpty()) {
                out.putObject("window").put("title", observation.windowTitle());
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

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: session={} observationId={} 元素={} 模式={} 文本字节={}",
                        TOOL_NAME, sessionId, observation.observationId(), elements.size(),
                        observation.treeMode(), treeText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            return ToolResult.success(call.id(), out.toPrettyString());
        } catch (Exception e) {
            log.error("[computer-use] {} 失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "读取辅助功能树失败: " + e.getMessage());
        }
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
                    node.nativeIdentifier()));
            flatten(node.children(), out);
        }
    }
}
