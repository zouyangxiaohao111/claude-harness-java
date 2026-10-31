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

/**
 * 点击变体工具的<b>共用基类</b> · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2376-2505} 的
 * {@code handleClickVariant}。
 *
 * <h2>WHY 抽基类（而不是 4 个各写一遍）</h2>
 * <p>CCB 的 5 个点击变体（{@code left_click} / {@code double_click} / {@code triple_click} /
 * {@code right_click} / {@code middle_click}）在 CCB 侧就是<b>同一个</b>
 * {@code handleClickVariant} 的不同 (button, count) 参数（{@code toolCalls.ts:4161-4170}）。
 * <br>⇒ 本仓把「共用的执行逻辑」放这里，各变体子类只提供
 * {@link #toolName()} / {@link #description()} / (button, count) + 自己的 javadoc。
 * <br>⚠️ <b>既有 {@code left_click} 工具不改</b>（本批只新增，不动既有 7 个工具的行为）。
 *
 * <h2>门控（逐字对齐 CCB）</h2>
 * <p>CCB {@code toolCalls.ts:2429-2432}：
 * <pre>
 * const clickActionKind: CuActionKind =
 *   button !== 'left' || (modifiers !== undefined &amp;&amp; modifiers.length &gt; 0)
 *     ? 'mouse_full'
 *     : 'mouse'
 * </pre>
 * ⇒ <b>右键 / 中键 ⇒ {@code mouse_full}</b>（需 full 档）；
 * <b>左键（含双击 / 三击）⇒ {@code mouse}</b>（click 或 full 档）。
 *
 * <h2>修饰键（{@code text} 参数）</h2>
 * <p>CCB 的点击变体支持 {@code text} 修饰键串（{@code toolCalls.ts:2402-2424}）：
 * <ul>
 *   <li>先做<b>系统级快捷键黑名单</b>判定（{@code isSystemKeyCombo}）——
 *       命中且无 {@code systemKeyCombos} 授权 ⇒ 报错（CCB {@code toolCalls.ts:2412-2422}）；</li>
 *   <li>修饰键存在 ⇒ actionKind 升级为 {@code mouse_full}；</li>
 *   <li>修饰键在点击期间被按住（CCB 的 {@code executor.click(..., modifiers)}）。</li>
 * </ul>
 * <p><b>[Phase 2b] 修饰键点击</b>：{@code text} 参数落地（CCB {@code toolCalls.ts:2402-2424}）——
 * 修饰键在点击<b>期间按住</b>，派发顺序逐字对齐 CCB {@code windowMessage.ts:341-366}
 * （修饰键<b>正序按下</b> → 点击 → 修饰键<b>逆序释放</b>），释放放在 {@code finally} 里
 * ⇒ <b>异常路径不卡键</b>。带修饰键的点击档位升级为 {@code mouse_full}
 * （CCB {@code toolCalls.ts:2429-2432}：按键盘等价输入计）；
 * 系统级组合键仍被 {@link ComputerUseKeyBlocklist} 拦（除非已授予 {@code systemKeyCombos}）。
 *
 * <p><b>[Phase 2b] 窗口定向</b>：会话有绑定窗口时改走<b>窗口定向</b>（{@code SendMessage} 到
 * 绑定 HWND，不动真实鼠标、不抢焦点），对齐 CCB {@code platforms/win32.ts:241-245}。
 * 详见 {@link ComputerUseClickRouter}。
 *
 * <h2>坐标换算</h2>
 * <p>走 {@link ComputerUseCoordinateScaler}（截图时刻快照，两态），
 * 与既有 {@code left_click} 同源 —— ⛔ 本仓<b>不自己实现换算</b>。
 */
public abstract class ComputerUseClickVariantTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseClickVariantTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名前缀（CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}）。 */
    public static final String TOOL_NAME_PREFIX = "mcp__computer-use__";

    /**
     * 输入 schema 模板 · 对齐 CCB {@code tools.ts:140-146}（{@code coordinateTuple}）
     * + {@code tools.ts:148-152}（{@code clickModifierText}）。
     *
     * <p>坐标描述按模式生成（{@code %s} = 当前态的 x 文案）——
     * 与既有 {@code left_click} 工具<b>同一口径</b>（一态一文案，绝不把两种约定同时写给模型）。
     */
    private static final String INPUT_SCHEMA_TEMPLATE = """
            {
              "type": "object",
              "properties": {
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "%s"
                },
                "text": {
                  "type": "string",
                  "description": "Modifier keys to hold during the click (e.g. \\"shift\\", \\"ctrl+shift\\"). Supports the same syntax as the key tool."
                }
              },
              "required": ["coordinate"]
            }
            """;

    /**
     * CCB 的 {@code FRONTMOST_GATE_DESC} · 逐字对齐
     * {@code packages/@ant/computer-use-mcp/src/tools.ts:31-32}。
     */
    static final String FRONTMOST_GATE_DESC =
            "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing.";

    private final String toolName;
    private final String description;
    private final ComputerUseMouseButton button;
    private final int count;
    private final ComputerUseMouseActions mouse;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseCoordinateMode coordinateMode;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    /** [Phase 2b] 点击路由（窗口定向 / 全局注入 + 修饰键）；null ⇒ 恒全局注入且不支持修饰键。 */
    private final ComputerUseClickRouter clickRouter;

    /**
     * @param toolName              工具全名（{@code mcp__computer-use__<name>}）
     * @param description           工具描述（逐字抄 CCB）
     * @param button                按键（CCB 的 {@code handleClickVariant} 的 {@code button} 参数）
     * @param count                 点击次数（1 / 2 / 3）
     * @param mouse                 鼠标动作实现
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     */
    protected ComputerUseClickVariantTool(String toolName,
                                         String description,
                                         ComputerUseMouseButton button,
                                         int count,
                                         ComputerUseMouseActions mouse,
                                         ComputerUseGeometryStore geometryStore,
                                         ComputerUseCoordinateMode coordinateMode,
                                         ComputerUseGrantStore grantStore,
                                         ComputerUseForegroundAppResolver foregroundAppResolver) {
        this(toolName, description, button, count, mouse, geometryStore, coordinateMode,
                grantStore, foregroundAppResolver, null);
    }

    /**
     * 生产构造器 · <b>带点击路由</b>（Phase 2b：窗口定向 + 修饰键点击）。
     *
     * @param toolName              工具全名
     * @param description           工具描述（逐字抄 CCB）
     * @param button                按键
     * @param count                 点击次数（1 / 2 / 3）
     * @param mouse                 鼠标动作实现（全局注入路线）
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式
     * @param grantStore            会话授权位读写（null ⇒ 不做档位门控）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param clickRouter           点击路由（null ⇒ 恒全局注入且不支持修饰键）
     */
    protected ComputerUseClickVariantTool(String toolName,
                                          String description,
                                          ComputerUseMouseButton button,
                                          int count,
                                          ComputerUseMouseActions mouse,
                                          ComputerUseGeometryStore geometryStore,
                                          ComputerUseCoordinateMode coordinateMode,
                                          ComputerUseGrantStore grantStore,
                                          ComputerUseForegroundAppResolver foregroundAppResolver,
                                          ComputerUseClickRouter clickRouter) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (mouse == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseClickVariantTool 依赖不能为空");
        }
        if (count < 1 || count > 3) {
            throw new IllegalArgumentException("点击次数必须是 1/2/3，实际=" + count);
        }
        this.toolName = toolName;
        this.description = description;
        this.button = button;
        this.count = count;
        this.mouse = mouse;
        this.geometryStore = geometryStore;
        this.coordinateMode = coordinateMode == null ? ComputerUseCoordinateMode.PIXELS : coordinateMode;
        this.grantStore = grantStore;
        this.foregroundAppResolver = foregroundAppResolver;
        this.clickRouter = clickRouter;
    }

    @Override
    public final String name() {
        return toolName;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public final JsonNode inputSchema() {
        try {
            return JSON.readTree(String.format(INPUT_SCHEMA_TEMPLATE,
                    ComputerUseCoordinateDescriptions.tupleDescription(coordinateMode)));
        } catch (Exception e) {
            throw new IllegalStateException("[computer-use] " + toolName + " inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 输入动作会改系统状态 ⇒ 非只读、不可并发。 */
    @Override
    public final boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public final boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /**
     * 输入动作 · <b>权限自决 Allow（免弹窗）</b> · 对齐 CCB
     * {@code src/utils/computerUse/setup.ts:25-32}（{@code mcp__computer-use__*} 进
     * {@code allowedTools} ⇒ 不弹权限气泡，审批由 {@code request_access} 一次授权整会话）。
     */
    @Override
    public final PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use input action is gated by the "
                        + "session allowlist granted via request_access"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public final AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行点击变体。
     *
     * <p>流程：解析 {@code coordinate} → 修饰键检查 → 档位门控 → 坐标换算 → 点击。
     */
    @Override
    public final AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode coordinate = input.path("coordinate");
        if (!coordinate.isArray() || coordinate.size() != 2
                || !coordinate.get(0).isNumber() || !coordinate.get(1).isNumber()) {
            return ToolResult.error(call.id(),
                    "\"coordinate\" must be an array of length 2 with numeric x and y");
        }
        double rawX = coordinate.get(0).asDouble();
        double rawY = coordinate.get(1).asDouble();
        if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || rawX < 0 || rawY < 0) {
            return ToolResult.error(call.id(),
                    "\"coordinate\" must contain non-negative finite numbers");
        }

        // 修饰键 · [Phase 2b] CCB toolCalls.ts:2402-2424 —— 黑名单 → 解析
        JsonNode textNode = input.path("text");
        String modifierChord = null;
        if (textNode.isTextual() && !textNode.asText().isBlank()) {
            modifierChord = textNode.asText();
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        List<ComputerUseKeyEvent> modifierEvents = List.of();
        if (modifierChord != null) {
            if (ComputerUseKeyBlocklist.isSystemKeyCombo(modifierChord)) {
                boolean granted = grantStore != null
                        && grantStore.read(sessionId).flags().systemKeyCombos();
                if (!granted) {
                    return ToolResult.error(call.id(),
                            ComputerUseKeyBlocklist.blockedModifierMessage(modifierChord));
                }
            }
            try {
                modifierEvents = ComputerUseModifierChord.parse(modifierChord);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(call.id(), e.getMessage());
            }
            if (clickRouter == null) {
                // 无点击路由 ⇒ 修饰键无处投递 ⇒ 显式报错（fail loud，不静默忽略模型意图）
                return ToolResult.error(call.id(),
                        "修饰键点击需要点击路由（ComputerUseClickRouter）接线后才能使用");
            }
        }

        // 档位门控 · CCB toolCalls.ts:2429-2432（右键/中键 或 带修饰键 ⇒ mouse_full；左键 ⇒ mouse）
        ComputerUseActionKind actionKind =
                (button == ComputerUseMouseButton.LEFT && modifierEvents.isEmpty())
                        ? ComputerUseActionKind.MOUSE
                        : ComputerUseActionKind.MOUSE_FULL;
        String gateRejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, actionKind);
        if (gateRejection != null) {
            return ToolResult.error(call.id(), gateRejection);
        }

        // 坐标换算 · 走既有 scaler（截图时刻快照，两态）
        ComputerUseScreenshotGeometry lastScreenshot = geometryStore.get(sessionId);
        int[] point;
        try {
            point = ComputerUseCoordinateScaler.scaleCoord(rawX, rawY, coordinateMode, lastScreenshot);
        } catch (ComputerUseGeometryMissingException e) {
            log.warn("[computer-use] {} 拒绝执行: {}", toolName, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        boolean windowTargeted = clickRouter != null && clickRouter.isWindowTargeted(sessionId);
        try {
            if (windowTargeted) {
                // 窗口定向：SendMessage 到绑定 HWND（不动真实鼠标、不抢焦点）
                clickRouter.clickWindowDirected(sessionId, point[0], point[1],
                        button == ComputerUseMouseButton.RIGHT, count, modifierEvents);
            } else if (clickRouter != null) {
                // 全局注入 + 修饰键按住（释放在 finally ⇒ 异常不卡键）
                clickRouter.clickGlobal(modifierEvents, () ->
                        mouse.click(point[0], point[1], button, count));
            } else {
                if (!modifierEvents.isEmpty()) {
                    return ToolResult.error(call.id(),
                            "修饰键点击需要点击路由（ComputerUseClickRouter）接线后才能使用");
                }
                mouse.click(point[0], point[1], button, count);
            }
        } catch (Exception e) {
            log.error("[computer-use] {} 点击失败: 图坐标 ({},{}) → 屏幕坐标 ({},{}) · 路由={} · {}",
                    toolName, rawX, rawY, point[0], point[1],
                    windowTargeted ? "窗口定向" : "全局注入", e.getMessage(), e);
            return ToolResult.error(call.id(), "点击失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 图坐标 ({},{}) → 屏幕坐标 ({},{}) · 按键={} · 次数={} · 模式={} · 路由={} · 修饰键={}",
                    toolName, rawX, rawY, point[0], point[1], button.wire(), count, coordinateMode.wire(),
                    windowTargeted ? "窗口定向" : "全局注入", modifierChord);
        }

        // CCB toolCalls.ts:2504 成功文案逐字对齐："Clicked."
        return ToolResult.success(call.id(), "Clicked.");
    }

    /**
     * 构造点击变体的描述（逐字 = CCB 第一句 + {@link #FRONTMOST_GATE_DESC}）。
     *
     * @param sentence CCB 的第一句（逐字）
     * @return 完整描述
     */
    static String buildDescription(String sentence) {
        return sentence + " " + FRONTMOST_GATE_DESC;
    }
}
