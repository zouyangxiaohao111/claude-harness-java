package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 鼠标缓动路径 · <b>逐字对齐 CCB 2.8.4
 * {@code src/utils/computerUse/executor.ts:277-312} 的 {@code animatedMove}
 * + {@code :643-659} 的 {@code drag}</b>。
 *
 * <h2>CCB 的缓动算法（逐字）</h2>
 * <pre>
 * async function animatedMove(input, targetX, targetY, mouseAnimationEnabled) {
 *   if (!mouseAnimationEnabled) { await moveAndSettle(input, targetX, targetY); return }
 *   const start = await input.mouseLocation()
 *   const deltaX = targetX - start.x
 *   const deltaY = targetY - start.y
 *   const distance = Math.hypot(deltaX, deltaY)
 *   if (distance &lt; 1) return                       // ← 距离 &lt; 1px：<b>连瞬移都不做</b>
 *   const durationSec = Math.min(distance / 2000, 0.5)   // ← 2000px/s，上限 0.5s
 *   if (durationSec &lt; 0.03) { await moveAndSettle(input, targetX, targetY); return }
 *   const frameRate = 60
 *   const frameIntervalMs = 1000 / frameRate
 *   const totalFrames = Math.floor(durationSec * frameRate)
 *   for (let frame = 1; frame &lt;= totalFrames; frame++) {
 *     const t = frame / totalFrames
 *     const eased = 1 - (1 - t) ** 3                 // ← ease-out-cubic
 *     await input.moveMouse(Math.round(start.x + deltaX * eased),
 *                            Math.round(start.y + deltaY * eased), false)
 *     if (frame &lt; totalFrames) await sleep(frameIntervalMs)
 *   }
 *   await sleep(MOVE_SETTLE_MS)                      // ← 末帧之后再等 50ms
 * }
 * </pre>
 *
 * <h2>适用范围：<b>只有拖拽的 press → to 段</b></h2>
 * <p>CCB 的 {@code animatedMove} <b>只在 {@code drag} 里被调用</b>
 * （{@code executor.ts:655}）；普通移动走 {@code moveAndSettle}（{@code executor.ts:170-177}：
 * 移动 + {@code MOVE_SETTLE_MS = 50}）。
 * <br>⇒ 本仓<b>普通移动保持「移动 + 50ms settle」</b>，缓动<b>只</b>用于拖拽段。
 *
 * <h2>本仓与 CCB 的<b>路线差异</b>（如实标注）</h2>
 * <p>CCB 的 {@code animatedMove} 只存在于 <b>macOS</b> 执行器
 * （{@code createCliExecutor} 在非 darwin 上直接委派给 {@code createCrossPlatformExecutor}，
 * 而后者的 {@code drag}（{@code executorCrossPlatform.ts:461-503}）是<b>瞬移</b>，
 * <b>没有</b>缓动）。⇒ <b>CCB 在 Windows 上实际没有缓动</b>。
 * <br>⇒ 本仓按任务口径<b>实现</b>该缓动路径（Windows 上也能用）—— 这是<b>超出 CCB-Windows
 * 实际行为</b>的一处增强，参数（ease-out-cubic / 60fps / 2000px/s / 0.5s 上限）与 CCB 逐字一致。
 * 子开关 {@code mouseAnimation} 默认 <b>true</b>（与 CCB {@code gates.ts:15} 一致），
 * 关掉即退回「瞬移 + 50ms settle」。
 */
public final class ComputerUseMouseAnimator {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseMouseAnimator.class);

    /** CCB {@code executor.ts:168} {@code MOVE_SETTLE_MS = 50}。 */
    public static final long MOVE_SETTLE_MS = 50L;

    /** 帧率 · CCB {@code executor.ts:292} {@code frameRate = 60}。 */
    public static final int FRAME_RATE = 60;

    /**
     * 帧间隔（毫秒）· CCB {@code executor.ts:293} {@code frameIntervalMs = 1000 / 60}
     * = 16.666…ms。
     *
     * <p>Node 的 {@code setTimeout} 对小数延迟按<b>整数毫秒</b>截断 ⇒ 实际是 16ms ⇒ 本仓取 16。
     */
    public static final long FRAME_INTERVAL_MS = 1000L / FRAME_RATE;

    /** 速度（像素/秒）· CCB {@code executor.ts:288} {@code distance / 2000}。 */
    public static final double SPEED_PX_PER_SEC = 2000d;

    /** 单次动画时长上限（秒）· CCB {@code executor.ts:288} {@code Math.min(…, 0.5)}。 */
    public static final double MAX_DURATION_SEC = 0.5d;

    /**
     * 低于该时长直接瞬移（秒）· CCB {@code executor.ts:290} {@code durationSec < 0.03}。
     */
    public static final double MIN_ANIMATED_DURATION_SEC = 0.03d;

    private final ComputerUseCursor cursor;
    private final ComputerUseSleeper sleeper;
    private final boolean mouseAnimationEnabled;

    /**
     * @param cursor              光标原语
     * @param sleeper             休眠器（可注入假时钟 ⇒ 单测零耗时）
     * @param mouseAnimationEnabled {@code mouseAnimation} 子开关（CCB {@code gates.ts:15}，默认 true）
     */
    public ComputerUseMouseAnimator(ComputerUseCursor cursor,
                                    ComputerUseSleeper sleeper,
                                    boolean mouseAnimationEnabled) {
        if (cursor == null) {
            throw new IllegalArgumentException("ComputerUseMouseAnimator 需要光标实现");
        }
        this.cursor = cursor;
        this.sleeper = sleeper == null ? ComputerUseSleeper.REAL : sleeper;
        this.mouseAnimationEnabled = mouseAnimationEnabled;
    }

    /**
     * 缓动移动到目标点 · 逐字 CCB {@code executor.ts:277-312} 的 {@code animatedMove}。
     *
     * @param targetX 目标屏幕 X
     * @param targetY 目标屏幕 Y
     * @throws Exception 注入失败
     */
    public void animatedMoveTo(int targetX, int targetY) throws Exception {
        if (!mouseAnimationEnabled) {
            moveAndSettle(targetX, targetY);
            return;
        }
        int[] start = cursor.location();
        double deltaX = targetX - start[0];
        double deltaY = targetY - start[1];
        double distance = Math.hypot(deltaX, deltaY);
        if (distance < 1d) {
            // CCB executor.ts:286 —— 距离 < 1px ⇒ 直接返回（连瞬移都不做）
            return;
        }
        double durationSec = Math.min(distance / SPEED_PX_PER_SEC, MAX_DURATION_SEC);
        if (durationSec < MIN_ANIMATED_DURATION_SEC) {
            moveAndSettle(targetX, targetY);
            return;
        }
        int totalFrames = (int) Math.floor(durationSec * FRAME_RATE);
        for (int frame = 1; frame <= totalFrames; frame++) {
            double t = (double) frame / totalFrames;
            double eased = 1d - Math.pow(1d - t, 3);
            cursor.moveTo(
                    (int) Math.round(start[0] + deltaX * eased),
                    (int) Math.round(start[1] + deltaY * eased));
            if (frame < totalFrames) {
                sleeper.sleep(FRAME_INTERVAL_MS);
            }
        }
        sleeper.sleep(MOVE_SETTLE_MS);
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 鼠标缓动完成: ({},{}) → ({},{}) 帧数={} 距离={}",
                    start[0], start[1], targetX, targetY, totalFrames, Math.round(distance));
        }
    }

    /**
     * 拖拽 · 逐字 CCB {@code executor.ts:643-659} 的 {@code drag}。
     *
     * <p>顺序：移动到起点 + settle → 左键按下 → 等 50ms → <b>缓动</b>到终点
     * → <b>finally</b> 左键抬起（中途抛异常也必须抬起，否则用户的左键卡在按下态）。
     *
     * @param fromX 起点屏幕 X
     * @param fromY 起点屏幕 Y
     * @param toX   终点屏幕 X
     * @param toY   终点屏幕 Y
     * @throws Exception 注入失败
     */
    public void drag(int fromX, int fromY, int toX, int toY) throws Exception {
        moveAndSettle(fromX, fromY);
        cursor.leftButtonDown();
        sleeper.sleep(MOVE_SETTLE_MS);
        try {
            animatedMoveTo(toX, toY);
        } finally {
            cursor.leftButtonUp();
        }
    }

    /**
     * 普通移动（瞬移 + settle）· 逐字 CCB {@code executor.ts:170-177} 的 {@code moveAndSettle}。
     *
     * @param x 屏幕 X
     * @param y 屏幕 Y
     * @throws Exception 移动失败
     */
    public void moveAndSettle(int x, int y) throws Exception {
        cursor.moveTo(x, y);
        sleeper.sleep(MOVE_SETTLE_MS);
    }

    /**
     * 从<b>当前光标位置</b>拖到目标点 · 逐字 CCB
     * {@code src/utils/computerUse/executor.ts:643-659} 的 {@code drag}（{@code from === undefined} 分支）。
     *
     * <p>CCB 原文（{@code executor.ts:648-651}）：
     * <pre>
     * async drag(from: {x, y} | undefined, to: {x, y}) {
     *   if (from !== undefined) { await moveAndSettle(input, from.x, from.y) }
     *   await input.mouseButton('left', 'press')
     *   await sleep(MOVE_SETTLE_MS)
     *   try { await animatedMove(input, to.x, to.y, getMouseAnimationEnabled()) }
     *   finally { await input.mouseButton('left', 'release') }
     * }
     * </pre>
     * ⇒ {@code from === undefined} ⇒ <b>不先移动</b>，直接在当前光标处按下。
     *
     * @param toX 终点屏幕 X
     * @param toY 终点屏幕 Y
     * @throws Exception 拖拽失败（异常路径下左键必被释放）
     */
    public void dragFromCurrent(int toX, int toY) throws Exception {
        cursor.leftButtonDown();
        sleeper.sleep(MOVE_SETTLE_MS);
        try {
            animatedMoveTo(toX, toY);
        } finally {
            cursor.leftButtonUp();
        }
    }

    /**
     * 释放当前悬空未放的左键 · 对齐 CCB
     * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2698-2702} 的防呆清场。
     *
     * <p>CCB 原文（{@code toolCalls.ts:2693-2702}）：
     * <pre>
     * // executor.drag() does its own press+release internally. Without this
     * // defensive clear, a prior left_mouse_down leaves mouseButtonHeld=true
     * // across the drag and desyncs the flag from OS state.
     * if (mouseButtonHeld) {
     *   await adapter.executor.mouseUp()
     *   mouseButtonHeld = false
     *   mouseMoved = false
     * }
     * </pre>
     *
     * @throws Exception 释放失败（调用方只记日志：此刻拖拽才是主错误）
     */
    public void releaseHeldButton() throws Exception {
        cursor.leftButtonUp();
    }
}
