package com.nexusai.application.agent.computeruse;

/**
 * 坐标换算时<b>缺少截图时刻几何快照</b>的显式失败（fail loud）。
 *
 * <p><b>WHY 单独一个异常类型</b>：点击工具必须能<b>只</b>捕获这一类失败并转成模型可见的
 * 明确错误文案；用泛化的 {@code IllegalStateException} 会把别的编程错误一起吞掉。
 *
 * <p><b>触发条件</b>：CU 动作工具（{@code left_click}）在<b>没有先截图</b>的情况下收到
 * 坐标。CCB 的冷启动兜底分支（{@code raw / scaleFactor}）在本仓被<b>删除</b>：
 * 见 {@link ComputerUseCoordinateScaler} 的「修正 CCB 几何漂移隐患」说明。
 */
public class ComputerUseGeometryMissingException extends RuntimeException {

    /**
     * @param message 用户/模型可见的失败说明
     */
    public ComputerUseGeometryMissingException(String message) {
        super(message);
    }
}
