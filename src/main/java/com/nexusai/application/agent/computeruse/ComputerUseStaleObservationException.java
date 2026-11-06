package com.nexusai.application.agent.computeruse;

/**
 * 动作执行时发现<b>观察已陈旧</b>（对齐 dsh {@code COMPUTER_STALE_OBSERVATION}）。
 *
 * <h2>WHY 需要独立的异常类型</h2>
 * <p>macOS 的 {@code locator} 是「树内路径」，不是稳定 id：观察之后界面一变
 * （弹层、列表刷新、窗口切换），同一路径就指向<b>另一个元素</b>。
 * JXA 侧用 {@code expectedStateHash} 挡住了这种情况并返回 {@code err = -25209}，
 * 但 {@code invokeElement / performAction / setElementValue} 的返回值是 {@code boolean}
 * ⇒ 这个错误码会被<b>退化成「普通失败」</b>，模型看到的是
 * "cannot be pressed" 这类文案，误以为「元素不可按」，从而重复尝试或换元素 ——
 * 真正该做的其实是<b>重新 observe</b>。
 *
 * <p>dsh 在 {@code service.ts} 的 {@code actNow} 里把这种情况当作独立错误码
 * 一路传给模型；本仓用本异常把同一语义透传出来。
 */
public class ComputerUseStaleObservationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** JXA 侧「树变了」的错误码（与 {@code MacOsJxa} 脚本里的常量一致）。 */
    public static final int AX_ERR_STALE = -25209;

    public ComputerUseStaleObservationException(String message) {
        super(message);
    }
}
