package com.nexusai.application.agent.computeruse;

/**
 * PowerShell 子进程执行失败 · computer-use 的 UIA / 浮层路径的<b>显式失败</b>载体。
 *
 * <p><b>WHY 单独一个异常类型</b>：调用方（工具层）需要把它与其它运行时异常区分开 ——
 * UIA 失败时模型需要知道「元素没找到」还是「UIA 通道本身坏了」，
 * 前者可降级（回落坐标点击），后者必须 fail loud。
 *
 * @see ComputerUsePowerShell
 */
public class ComputerUsePowerShellException extends RuntimeException {

    /**
     * @param message 失败原因（含 stderr 原文）
     */
    public ComputerUsePowerShellException(String message) {
        super(message);
    }

    /**
     * @param message 失败原因
     * @param cause   原始异常
     */
    public ComputerUsePowerShellException(String message, Throwable cause) {
        super(message, cause);
    }
}
