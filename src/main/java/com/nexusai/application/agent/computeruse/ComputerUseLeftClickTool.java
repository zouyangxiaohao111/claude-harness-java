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
 * {@code mcp__computer-use__left_click} · 左键点击工具（对齐 CCB computer-use MCP 的 {@code left_click}）。
 *
 * <p><b>核心正确性</b>：模型给的坐标是<b>最近那张截图图像上的像素坐标</b>
 * （CCB {@code tools.ts:22-23} 原文 “read directly from the most recent screenshot image”），
 * 必须按 {@link ComputerUseCoordinateScaler#scaleCoord}（逐字对齐 CCB
 * {@code toolCalls.ts:253-297}）换算成屏幕坐标，且必须用<b>截图时刻</b>冻结的几何快照。
 *
 * <p><b>点击路线（Phase 2b 起可窗口定向）</b>：无绑定窗口时走
 * <b>全局注入</b>（{@code SetCursorPos} + {@code SendInput}）；<b>会话有绑定窗口</b>时
 * 改走<b>窗口定向</b>（{@code src/utils/computerUse/win32/windowMessage.ts} 的
 * {@code WM_LBUTTONDOWN/UP} 客户区坐标消息，不动真实鼠标、不抢焦点），
 * 对齐 CCB {@code platforms/win32.ts:241-245} 的安全模型。详见 {@link ComputerUseClickRouter}。
 *
 * <p><b>[Phase 2b] 修饰键点击</b>：{@code text} 参数落地（CCB {@code toolCalls.ts:2402-2424}）——
 * 修饰键在点击<b>期间按住</b>，派发顺序逐字对齐 CCB {@code windowMessage.ts:341-366}
 * （修饰键<b>正序按下</b> → 点击 → 修饰键<b>逆序释放</b>），释放放在 {@code finally} 里
 * ⇒ <b>异常路径不卡键</b>。带修饰键的点击档位升级为 {@code mouse_full}
 * （CCB {@code toolCalls.ts:2429-2432}：按键盘等价输入计）；
 * 系统级组合键仍被 {@link ComputerUseKeyBlocklist} 拦（除非已授予 {@code systemKeyCombos}）。
 *
 * <p><b>Phase 0 不实现</b>：命中测试门、前台应用白名单门。
 *
 * <p><b>Phase 1a 变更</b>：授权位（allowlist + tier）门控落地 ——
 * ①{@code checkPermissions} 改返回 {@code Allow}（<b>免弹窗</b>，对齐 CCB
 * {@code setup.ts:25-32} 把 {@code mcp__computer-use__*} 放进 {@code allowedTools}）；
 * ②{@code execute} 内加<b>档位门控</b>（对齐 CCB {@code runInputActionGates}
 * {@code toolCalls.ts:480-583}）：前台应用不在 allowlist ⇒ 拒绝；在 allowlist 但档位
 * 不覆盖 {@code mouse} ⇒ 拒绝。前台应用未知 ⇒ 放行（CCB {@code toolCalls.ts:502-506} 同款）。
 * <p>⛔ 命中测试门（CCB {@code runHitTestGate}）<b>仍不做</b>：Windows 的
 * {@code appUnderPoint} 恒 null（CCB {@code executorCrossPlatform.ts:524-529}）。
 *
 * <p><b>[Phase 1b · A] 视觉能力闸</b>：执行前经 {@link ComputerUseVisionCapability} 判定
 * <b>当前模型是否支持视觉</b>；<b>已确定不支持</b> ⇒ fail loud（模型看不到截图 ⇒
 * 点击坐标无来源 ⇒ 明确报错，好过盲点击）。判不出 ⇒ 放行。
 *
 * <p><b>[Phase 1e] grounding 降级路径</b>：当视觉闸判<b>确定不支持</b>视觉、但
 * <b>降级开关已打开</b>（生产注入 {@link ComputerUseGroundingService}）时，
 * 坐标<b>来源</b> = 截图工具的 grounding 结果（视觉模型给的<b>归一化百分比坐标</b>）
 * ⇒ 本工具<b>不再报错</b>，并把换算切到 {@code normalized_0_100}
 * （主模型看不到图 ⇒ 没有像素参照 ⇒ 百分比是唯一可用约定）。
 * <br>⛔ 降级路径下仍<b>必须</b>有截图时刻的几何快照（否则百分比换算无依据）⇒
 * 无快照 ⇒ fail loud（不放行）。
 */
public final class ComputerUseLeftClickTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseLeftClickTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__left_click";

    /**
     * 工具描述 · 对齐 CCB {@code tools.ts:246-257}。
     *
     * <p>CCB 原文：{@code `Left-click at the given coordinates. ${FRONTMOST_GATE_DESC}`}，
     * 其中 {@code FRONTMOST_GATE_DESC}（{@code tools.ts:31-32}）是
     * “The frontmost application must be in the session allowlist at the time of this call,
     * or this tool returns an error and does nothing.”
     * <br>Phase 0 <b>没有</b>应用白名单（{@code request_access} 留到 Phase 1）⇒ 该句移除
     * （留着等于对模型说谎）；<b>Phase 1a 起白名单与档位门控已落地</b>
     * （见本类 {@code execute} 内的一致性检查）⇒ Phase 1c 把该句<b>逐字带回</b>
     * （与 {@code type} / {@code key} 两个新工具的描述保持同一口径）。
     */
    private static final String DESCRIPTION =
            "Left-click at the given coordinates. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing.";

    /**
     * 输入 schema 模板 · 对齐 CCB {@code tools.ts:140-146 + 249-255}。
     *
     * <p><b>坐标描述按模式生成</b>：{@code %s} 处填
     * {@link ComputerUseCoordinateDescriptions#tupleDescription(ComputerUseCoordinateMode)}
     * —— 只填<b>当前模式</b>那一个态的文案（CCB {@code tools.ts:5-10}：
     * “The model sees exactly ONE coordinate convention in the param descriptions and never
     * learns the other exists.”）⇒ <b>绝不能</b>把两种约定同时写给模型。
     *
     * <p>CCB 的元组描述只带 x 文本、不带 y 文本（{@code tools.ts:143-149} 的
     * {@code `(x, y): ${coord.x}`}）—— 此处逐字保留。
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

    private final ComputerUseScreenCapturer capturer;
    private final ComputerUseMouse mouse;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseCoordinateMode coordinateMode;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseVisionCapability visionCapability;
    /** [Phase 1e] grounding 降级服务（null ⇒ 降级路径关闭）。 */
    private final ComputerUseGroundingService groundingService;
    /** [Phase 2b] 点击路由（窗口定向 / 全局注入 + 修饰键）；null ⇒ 恒全局注入且不支持修饰键。 */
    private final ComputerUseClickRouter clickRouter;

    /**
     * PoC 构造器（<b>不接授权体系</b>）· 保留给 Phase 0 的单测与 PoC 路径。
     *
     * <p>此构造器下 {@code grantStore == null} ⇒ <b>档位门控不生效</b>
     * （Phase 0 的"无白名单"行为）。生产路径见
     * {@link #ComputerUseLeftClickTool(ComputerUseScreenCapturer, ComputerUseMouse, ComputerUseGeometryStore, ComputerUseCoordinateMode, ComputerUseGrantStore, ComputerUseForegroundAppResolver)}。
     *
     * @param capturer      截屏实现（<b>[Phase 1d] 起不再用于换算</b> —— 两态统一用截图快照；
     *                      保留字段与构造器以维持既有调用点零改动）
     * @param mouse         鼠标实现
     * @param geometryStore 截图几何快照存储
     */
    public ComputerUseLeftClickTool(ComputerUseScreenCapturer capturer,
                                    ComputerUseMouse mouse,
                                    ComputerUseGeometryStore geometryStore) {
        this(capturer, mouse, geometryStore, ComputerUseCoordinateMode.PIXELS);
    }

    /**
     * @param capturer       截屏实现
     * @param mouse          鼠标实现
     * @param geometryStore  截图几何快照存储
     * @param coordinateMode 坐标模式（PoC 固定 {@link ComputerUseCoordinateMode#PIXELS}）
     */
    public ComputerUseLeftClickTool(ComputerUseScreenCapturer capturer,
                                    ComputerUseMouse mouse,
                                    ComputerUseGeometryStore geometryStore,
                                    ComputerUseCoordinateMode coordinateMode) {
        this(capturer, mouse, geometryStore, coordinateMode, null, null);
    }

    /**
     * 生产构造器（<b>接授权体系</b>）· Phase 1a。
     *
     * @param capturer             截屏实现
     * @param mouse                鼠标实现
     * @param geometryStore        截图几何快照存储
     * @param coordinateMode       坐标模式
     * @param grantStore           会话授权位读写（null ⇒ 不做档位门控，仅 PoC/单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     */
    public ComputerUseLeftClickTool(ComputerUseScreenCapturer capturer,
                                    ComputerUseMouse mouse,
                                    ComputerUseGeometryStore geometryStore,
                                    ComputerUseCoordinateMode coordinateMode,
                                    ComputerUseGrantStore grantStore,
                                    ComputerUseForegroundAppResolver foregroundAppResolver) {
        this(capturer, mouse, geometryStore, coordinateMode, grantStore, foregroundAppResolver, null);
    }

    /**
     * 生产构造器 · <b>带视觉能力闸</b>（Phase 1b · A）。
     *
     * <p><b>WHY 点击也要闸</b>：模型给的坐标来自<b>它看到的那张截图</b>
     * （{@code tools.ts:22-23}「read directly from the most recent screenshot image」）。
     * 主模型看不到图 ⇒ 坐标无来源 ⇒ 点击是盲操作（点在未知像素上）⇒ 比明确报错更危险。
     * <br><b>[Phase 1e] 例外</b>：降级路径开启且视觉闸判「确定不支持」时，坐标来源 =
     * grounding 结果（视觉模型给的百分比）⇒ 闸放行并切百分比态换算（见 8 参构造器）。
     *
     * @param capturer             截屏实现
     * @param mouse                鼠标实现
     * @param geometryStore        截图几何快照存储
     * @param coordinateMode       坐标模式
     * @param grantStore           会话授权位读写（null ⇒ 不做档位门控）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param visionCapability     视觉能力判定（可 null ⇒ 不做闸）
     */
    public ComputerUseLeftClickTool(ComputerUseScreenCapturer capturer,
                                    ComputerUseMouse mouse,
                                    ComputerUseGeometryStore geometryStore,
                                    ComputerUseCoordinateMode coordinateMode,
                                    ComputerUseGrantStore grantStore,
                                    ComputerUseForegroundAppResolver foregroundAppResolver,
                                    ComputerUseVisionCapability visionCapability) {
        this(capturer, mouse, geometryStore, coordinateMode, grantStore, foregroundAppResolver,
                visionCapability, null);
    }

    /**
     * 生产构造器 · <b>带视觉能力闸 + grounding 降级</b>（Phase 1e）。
     *
     * @param capturer             截屏实现
     * @param mouse                鼠标实现
     * @param geometryStore        截图几何快照存储
     * @param coordinateMode       坐标模式（降级路径生效时被强制为 {@code normalized_0_100}）
     * @param grantStore           会话授权位读写（null ⇒ 不做档位门控）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param visionCapability     视觉能力判定（可 null ⇒ 不做闸）
     * @param groundingService     grounding 降级服务（<b>null ⇒ 降级路径关闭</b> ⇒ 视觉闸判
     *                             「确定不支持」时维持 fail loud 报错）
     */
    public ComputerUseLeftClickTool(ComputerUseScreenCapturer capturer,
                                    ComputerUseMouse mouse,
                                    ComputerUseGeometryStore geometryStore,
                                    ComputerUseCoordinateMode coordinateMode,
                                    ComputerUseGrantStore grantStore,
                                    ComputerUseForegroundAppResolver foregroundAppResolver,
                                    ComputerUseVisionCapability visionCapability,
                                    ComputerUseGroundingService groundingService) {
        this(capturer, mouse, geometryStore, coordinateMode, grantStore, foregroundAppResolver,
                visionCapability, groundingService, null);
    }

    /**
     * 生产构造器 · <b>带点击路由</b>（Phase 2b：窗口定向 + 修饰键点击）。
     *
     * @param capturer             截屏实现
     * @param mouse                鼠标实现（全局注入路线）
     * @param geometryStore        截图几何快照存储
     * @param coordinateMode       坐标模式（降级路径生效时被强制为 {@code normalized_0_100}）
     * @param grantStore           会话授权位读写（null ⇒ 不做档位门控）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param visionCapability     视觉能力判定（可 null ⇒ 不做闸）
     * @param groundingService     grounding 降级服务（<b>null ⇒ 降级路径关闭</b>）
     * @param clickRouter          点击路由（null ⇒ 恒全局注入且不支持修饰键）
     */
    public ComputerUseLeftClickTool(ComputerUseScreenCapturer capturer,
                                    ComputerUseMouse mouse,
                                    ComputerUseGeometryStore geometryStore,
                                    ComputerUseCoordinateMode coordinateMode,
                                    ComputerUseGrantStore grantStore,
                                    ComputerUseForegroundAppResolver foregroundAppResolver,
                                    ComputerUseVisionCapability visionCapability,
                                    ComputerUseGroundingService groundingService,
                                    ComputerUseClickRouter clickRouter) {
        if (capturer == null || mouse == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseLeftClickTool 依赖不能为空");
        }
        this.capturer = capturer;
        this.mouse = mouse;
        this.geometryStore = geometryStore;
        this.coordinateMode = coordinateMode == null ? ComputerUseCoordinateMode.PIXELS : coordinateMode;
        this.grantStore = grantStore;
        this.foregroundAppResolver = foregroundAppResolver;
        this.visionCapability = visionCapability;
        this.groundingService = groundingService;
        this.clickRouter = clickRouter;
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
            return JSON.readTree(String.format(INPUT_SCHEMA_TEMPLATE,
                    ComputerUseCoordinateDescriptions.tupleDescription(coordinateMode)));
        } catch (Exception e) {
            throw new IllegalStateException("[computer-use] left_click inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 输入动作会改系统状态 ⇒ 非只读、不可并发。 */
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
     * {@code src/utils/computerUse/setup.ts:25-32}：CC 把 {@code mcp__computer-use__*}
     * 放进 {@code allowedTools} ⇒ 输入动作<b>不弹权限气泡</b>，审批由
     * {@code request_access} 一次授权整会话。
     *
     * <p>本仓等价物 = 1c 层工具自决 {@code Allow}（{@code CheckLayer1c_ToolCheck} 缓存）
     * → {@code CheckLayer3_PassthroughToAsk} 对非 Passthrough 结果<b>原样透传</b>
     * ⇒ <b>不弹窗</b>。
     *
     * <p><b>档位门控不在这里</b>：本工具的档位门控在 {@link #execute} 内做
     * （CCB 同款分层：{@code runInputActionGates} 在动作执行前、
     * {@code handleClickVariant} 之内），因为它需要"当前前台应用"这一运行时事实。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
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
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行左键点击。
     *
     * <p>流程：解析 {@code coordinate} → 读取<b>截图时刻</b>的几何快照 → {@code scaleCoord} 换算
     * → 移动 + settle → 左键 down/up。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        // ── [Phase 1b · A] 视觉能力闸 · 主模型看不到截图 ⇒ 坐标无来源 ⇒ 明确报错（不做盲点击）──
        // ── [Phase 1e] grounding 降级：视觉闸判「确定不支持」且降级服务已接线 ⇒
        //    坐标来源 = 截图工具的 grounding 结果（归一化百分比）⇒ 放行并切百分比态换算。
        ComputerUseVisionCapability.Verdict verdict =
                ComputerUseVisionCapability.verdictOf(visionCapability, ctx);
        boolean groundingActive = groundingService != null
                && verdict != null && !verdict.supported() && verdict.determinate();
        if (!groundingActive) {
            String rejection = ComputerUseVisionCapability.rejectionIfUnsupported(visionCapability, ctx);
            if (rejection != null) {
                return ToolResult.error(call.id(), rejection);
            }
        }
        // 降级路径只能用百分比坐标（主模型看不到图 ⇒ 无像素参照）。
        ComputerUseCoordinateMode effectiveMode = groundingActive
                ? ComputerUseCoordinateMode.NORMALIZED_0_100
                : coordinateMode;
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
            return ToolResult.error(call.id(), "\"coordinate\" must contain non-negative finite numbers");
        }

        JsonNode textNode = input.path("text");
        String modifierChord = null;
        if (textNode.isTextual() && !textNode.asText().isBlank()) {
            modifierChord = textNode.asText();
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // ── 修饰键解析（Phase 2b）· CCB toolCalls.ts:2402-2424 ──
        //   ① 系统级组合键黑名单（需 systemKeyCombos 授权）—— CCB toolCalls.ts:2412-2422
        //   ② 解析成有序的修饰键按下事件 —— CCB toolCalls.ts:2423
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

        // ── 档位门控（Phase 1a）· 对齐 CCB runInputActionGates ──
        //   CCB toolCalls.ts:480-583：取前台应用 → 按 allowlist 查档位 → tierSatisfies 放行/拒绝。
        //   ⛔ 命中测试门（runHitTestGate，toolCalls.ts:600-640）不做 —— Windows 的
        //      appUnderPoint 恒 null（CCB executorCrossPlatform.ts:524-529）。
        //   ⛔ 未接授权体系（grantStore == null，PoC/单测路径）⇒ 不做门控。
        //   （Phase 1c：抽成 ComputerUseInputGate，与 type / key 共用同一份实现，避免三份拷贝漂移。）
        //   [Phase 2b] 带修饰键的点击升级为 mouse_full（CCB toolCalls.ts:2429-2432）。
        ComputerUseActionKind actionKind = modifierEvents.isEmpty()
                ? ComputerUseActionKind.MOUSE : ComputerUseActionKind.MOUSE_FULL;
        String gateRejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, actionKind);
        if (gateRejection != null) {
            return ToolResult.error(call.id(), gateRejection);
        }

        // ── 坐标换算：两态统一用【截图时刻】的冻结几何（无快照 ⇒ fail loud，不猜）──
        ComputerUseScreenshotGeometry lastScreenshot = geometryStore.get(sessionId);
        int[] point;
        try {
            point = ComputerUseCoordinateScaler.scaleCoord(
                    rawX, rawY, effectiveMode, lastScreenshot);
        } catch (ComputerUseGeometryMissingException e) {
            log.warn("[computer-use] {} 拒绝执行: {}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        boolean windowTargeted = clickRouter != null && clickRouter.isWindowTargeted(sessionId);
        try {
            if (windowTargeted) {
                // 窗口定向：SendMessage 到绑定 HWND（不动真实鼠标、不抢焦点）
                clickRouter.clickWindowDirected(sessionId, point[0], point[1], false, 1, modifierEvents);
            } else if (clickRouter != null) {
                // 全局注入 + 修饰键按住（修饰键释放在 finally 里 ⇒ 异常不卡键）
                clickRouter.clickGlobal(modifierEvents, () -> {
                    mouse.moveAndSettle(point[0], point[1]);
                    mouse.leftClick(point[0], point[1]);
                });
            } else {
                // 无路由（PoC/单测路径）⇒ 修饰键不可用 ⇒ 显式报错（fail loud）
                if (!modifierEvents.isEmpty()) {
                    return ToolResult.error(call.id(),
                            "修饰键点击需要点击路由（ComputerUseClickRouter）接线后才能使用");
                }
                mouse.moveAndSettle(point[0], point[1]);
                mouse.leftClick(point[0], point[1]);
            }
        } catch (Exception e) {
            log.error("[computer-use] 左键点击失败: 图坐标 ({},{}) → 屏幕坐标 ({},{}) · 路由={} · {}",
                    rawX, rawY, point[0], point[1], windowTargeted ? "窗口定向" : "全局注入",
                    e.getMessage(), e);
            return ToolResult.error(call.id(), "左键点击失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 图坐标 ({},{}) → 屏幕坐标 ({},{}) · 模式={} · 有截图快照={} · 路由={} · 修饰键={}",
                    TOOL_NAME, rawX, rawY, point[0], point[1],
                    effectiveMode.wire(), lastScreenshot != null,
                    windowTargeted ? "窗口定向" : "全局注入", modifierChord);
        }

        // CCB toolCalls.ts:2504 成功文案逐字对齐："Clicked."
        return ToolResult.success(call.id(), "Clicked.");
    }
}
