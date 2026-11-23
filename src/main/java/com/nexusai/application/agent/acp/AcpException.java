package com.nexusai.application.agent.acp;

/**
 * ACP 运行时异常 · 覆盖「帧/协议/进程」三类失败。
 *
 * <p>⛔ 不继承受检异常：调用方（{@link AcpAgentRunner}）要在同一处统一转成
 * {@code SubagentResult(status=failed)}，受检异常只会逼出无意义的 try/catch 噪声。
 */
public class AcpException extends RuntimeException {

    public AcpException(String message) {
        super(message);
    }

    public AcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
