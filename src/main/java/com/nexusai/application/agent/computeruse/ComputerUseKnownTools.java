package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * computer-use 已知工具名（只读清单，供工具能力目录显示；⛔ 不实例化任何工具、不碰 JNA）。
 *
 * <p>名字直接引用各工具类的 {@code TOOL_NAME}（= {@code mcp__computer-use__<tool>}），<b>不复制字符串</b>。
 * 清单为 Windows(35) / macOS(24) 的<b>并集</b>（40 个）；平台差异由能力目录的说明字段表达。
 * ⚠️ 新增 computer-use 工具时需同步本清单（与注册路径同 PR 更新）。
 */
public final class ComputerUseKnownTools {

    private ComputerUseKnownTools() {}

    /** 全平台已知工具名（并集；顺序稳定）。 */
    public static final List<String> ALL = List.of(
        ComputerUseActivateWindowTool.TOOL_NAME,
        ComputerUseBatchTool.TOOL_NAME,
        ComputerUseBindWindowTool.TOOL_NAME,
        ComputerUseClickElementTool.TOOL_NAME,
        ComputerUseConfirmTool.TOOL_NAME,
        ComputerUseCursorPositionTool.TOOL_NAME,
        ComputerUseDoubleClickTool.TOOL_NAME,
        ComputerUseHoldKeyTool.TOOL_NAME,
        ComputerUseKeyTool.TOOL_NAME,
        ComputerUseLeftClickDragTool.TOOL_NAME,
        ComputerUseLeftClickTool.TOOL_NAME,
        ComputerUseLeftMouseDownTool.TOOL_NAME,
        ComputerUseLeftMouseUpTool.TOOL_NAME,
        ComputerUseListAppsTool.TOOL_NAME,
        ComputerUseListGrantedApplicationsTool.TOOL_NAME,
        ComputerUseMiddleClickTool.TOOL_NAME,
        ComputerUseMouseMoveTool.TOOL_NAME,
        ComputerUseMouseWheelTool.TOOL_NAME,
        ComputerUseObserveTool.TOOL_NAME,
        ComputerUseOpenApplicationTool.TOOL_NAME,
        ComputerUseOpenTerminalTool.TOOL_NAME,
        ComputerUsePerformActionTool.TOOL_NAME,
        ComputerUsePromptRespondTool.TOOL_NAME,
        ComputerUseReadClipboardTool.TOOL_NAME,
        ComputerUseRequestAccessTool.TOOL_NAME,
        ComputerUseRightClickTool.TOOL_NAME,
        ComputerUseScreenshotTool.TOOL_NAME,
        ComputerUseScrollTool.TOOL_NAME,
        ComputerUseStatusIndicatorTool.TOOL_NAME,
        ComputerUseSwitchDisplayTool.TOOL_NAME,
        ComputerUseTripleClickTool.TOOL_NAME,
        ComputerUseTypeIntoElementTool.TOOL_NAME,
        ComputerUseTypeTool.TOOL_NAME,
        ComputerUseVirtualKeyboardTool.TOOL_NAME,
        ComputerUseVirtualMouseTool.TOOL_NAME,
        ComputerUseWaitForConditionTool.TOOL_NAME,
        ComputerUseWaitTool.TOOL_NAME,
        ComputerUseWindowManagementTool.TOOL_NAME,
        ComputerUseWriteClipboardTool.TOOL_NAME,
        ComputerUseZoomTool.TOOL_NAME
    );
}
