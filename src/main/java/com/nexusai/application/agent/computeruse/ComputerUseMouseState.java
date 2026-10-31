package com.nexusai.application.agent.computeruse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「左键是否被按住」的会话级状态 · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:761-770} 的模块级
 * {@code mouseButtonHeld} / {@code mouseMoved} 两个标志位。
 *
 * <p><b>CCB 原形状（TS，逐字语义）</b>：
 * <pre>
 * let mouseButtonHeld = false
 * // Whether mouse_move occurred between left_mouse_down and left_mouse_up.
 * let mouseMoved = false
 * </pre>
 *
 * <h2>WHY 本仓按会话分桶（与 CCB 的差异，如实标注）</h2>
 * <p>CCB 用<b>模块级</b>变量，靠 CU 跨会话锁（one session at a time）保证不串味。
 * 本仓是多会话（见 memory「multisession-prefix-cache-invariants」）⇒ 同一个 JVM 里
 * 可能有多个会话先后调用 computer-use ⇒ 若沿用模块级单例，<b>会话 A 的
 * {@code left_mouse_down} 会让会话 B 的 {@code left_mouse_down} 误报「已按住」。
 * <br>⇒ 本仓按 {@code sessionId} 分桶（无 sessionId ⇒ 落全局桶，同
 * {@link ComputerUseGeometryStore} 的既有约定）。
 *
 * <h2>三个标志位的语义（逐字对齐 CCB）</h2>
 * <ul>
 *   <li>{@code held} —— 左键当前是否被按住。{@code left_mouse_down} 在已按住时报错
 *       （CCB {@code toolCalls.ts:3769-3773}：{@code "mouse button already held,
 *       call left_mouse_up first"}）；{@code left_mouse_up} 在未按住时<b>不</b>报错
 *       （幂等释放，CCB {@code toolCalls.ts:3801-3803}）。</li>
 *   <li>{@code moved} —— {@code left_mouse_down} 与 {@code left_mouse_up} 之间是否发生过
 *       鼠标移动。CCB 用它区分「抬起 = 点击释放」与「抬起 = 拖放落点」
 *       （{@code toolCalls.ts:3839-3848}：{@code mouseMoved ? 'mouse_full' : 'mouse'}）。</li>
 * </ul>
 */
public final class ComputerUseMouseState {

    /** 无 sessionId 时的兜底桶 key（与 {@link ComputerUseGeometryStore#GLOBAL_KEY} 同名约定）。 */
    public static final String GLOBAL_KEY = "__global__";

    private final Map<String, Flags> bySession = new ConcurrentHashMap<>();

    /**
     * 左键当前是否被按住。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     * @return true = 已按住
     */
    public boolean isHeld(String sessionId) {
        Flags flags = bySession.get(key(sessionId));
        return flags != null && flags.held;
    }

    /**
     * 标记左键已被按住（{@code left_mouse_down} 成功后调用）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public void markHeld(String sessionId) {
        Flags flags = flagsOf(sessionId);
        flags.held = true;
        flags.moved = false;
    }

    /**
     * 标记左键已释放（{@code left_mouse_up} / 点击类动作的防御性释放之后调用）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public void markReleased(String sessionId) {
        Flags flags = flagsOf(sessionId);
        flags.held = false;
        flags.moved = false;
    }

    /**
     * 标记按住期间发生过鼠标移动（{@code mouse_move} / 拖拽落点）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public void markMoved(String sessionId) {
        Flags flags = flagsOf(sessionId);
        flags.moved = true;
    }

    /**
     * 按住期间是否发生过移动。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public boolean hasMoved(String sessionId) {
        Flags flags = bySession.get(key(sessionId));
        return flags != null && flags.moved;
    }

    /**
     * 清空某会话的状态（CCB 在换锁持有者时 {@code resetMouseButtonHeld()} 的同款语义）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public void reset(String sessionId) {
        bySession.remove(key(sessionId));
    }

    private Flags flagsOf(String sessionId) {
        return bySession.computeIfAbsent(key(sessionId), k -> new Flags());
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? GLOBAL_KEY : sessionId;
    }

    /** 会话桶内的两个标志位。 */
    private static final class Flags {
        volatile boolean held;
        volatile boolean moved;
    }
}
