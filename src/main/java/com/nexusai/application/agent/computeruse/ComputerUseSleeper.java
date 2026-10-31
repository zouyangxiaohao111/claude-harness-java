package com.nexusai.application.agent.computeruse;

/**
 * 可注入的休眠器（假时钟）· computer-use 的时间相关行为（等待 / 逐字打字的帧间隔 /
 * 缓动动画的帧间隔）全部经它 ⇒ 单测可<b>零耗时</b>断言调用序列。
 *
 * <p><b>WHY 需要它</b>：CCB 的三处时间行为都有<b>真实耗时</b>：
 * <ul>
 *   <li>{@code wait} 工具：{@code sleep(duration * 1000)}（CCB {@code toolCalls.ts:3652}）；</li>
 *   <li>{@code type} 工具：每个字形前 {@code sleep(8)}（CCB {@code toolCalls.ts:715}
 *       {@code INTER_GRAPHEME_SLEEP_MS = 8}，125 Hz USB 轮询节奏）；</li>
 *   <li>鼠标缓动：每帧 {@code sleep(1000/60)}（CCB {@code executor.ts:290}）。</li>
 * </ul>
 * 单测若直接 {@code Thread.sleep} ⇒ 一旦用例写长文本就会变成"分钟级测试"。
 * ⇒ 本接口把"等多久"从"做什么"里剥出来，生产用 {@link #REAL}（真睡），单测用记录型假实现。
 */
@FunctionalInterface
public interface ComputerUseSleeper {

    /**
     * 生产实现 · 真睡。
     */
    ComputerUseSleeper REAL = Thread::sleep;

    /**
     * 休眠指定毫秒。
     *
     * @param millis 毫秒数
     * @throws InterruptedException 线程被中断
     */
    void sleep(long millis) throws InterruptedException;
}
