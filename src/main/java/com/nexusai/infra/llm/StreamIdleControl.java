package com.nexusai.infra.llm;

import com.nexusai.application.agent.tool.AbortController;

/**
 * 流空闲看门狗对单次模型调用的控制载体（随 ModelRequest → ModelCaller → provider.stream 末参下传）
 * · 对齐 CC 2.1.296，方案规格 §3。
 *
 * <p><b>为什么单独一个专用 AbortController</b>：CC 的空闲超时中止的是「流对象自己的内部 controller」
 * （{@code zyr(IZ)} exe 223,173,787，非用户组合信号）——本仓对应物必须是独立通道：用户取消的
 * {@code perTurnTuc.abortController()} 上挂着十余个 {@code onCancel} 监听器（权限/工具/子代理…），
 * 复用它会把超时误播成"用户取消"语义。
 *
 * @param watchdogController 专用发起控制器：LlmAgentLoop 等待段持有，空闲超时时
 *                           {@code abort("stream_idle_timeout")}。⚠️ 它<b>不再承担解阻塞</b>——
 *                           实测消费后静默时 {@code StreamResponse.close()} 会死锁、
 *                           {@code Thread.interrupt()} 不解阻塞（见 {@code buildClient} 换挡说明）；
 *                           实际中止由 {@link #idleTimeoutMs} 的读超时完成，本控制器仅作
 *                           "看门狗已触发"标记与遥测锚点。null = 无看门狗
 * @param forceNonStreaming  判决矩阵「降级非流式」（CC retryWithoutStreaming · exe 223,028,125
 *                           {@code Kke}）的强制开关：true = provider 跳过流式直接走非流式发送
 * @param idleTimeoutMs      流式读超时毫秒（0 = 不武装）：provider 以它构造
 *                           {@code Timeout.read} ⇒ 静默 ≥ 该值由 okhttp 在读线程内抛
 *                           SocketTimeoutException（唯一可控的解阻塞通道）→ 映射 stall
 */
public record StreamIdleControl(AbortController watchdogController, boolean forceNonStreaming,
                                long idleTimeoutMs) {

    /** 无看门狗（fork/compact 直呼路径、测试桩）。 */
    public static final StreamIdleControl NONE = new StreamIdleControl(null, false, 0L);
}
