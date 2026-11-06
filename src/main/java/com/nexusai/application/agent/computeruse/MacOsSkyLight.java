package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS <b>定向事件投递</b>（JNA）· 把输入事件只送给指定进程，不动系统光标、不抢前台。
 *
 * <h2>WHY 走 JNA 而不是 JXA</h2>
 * <p>JXA <b>没有 dlsym</b>，加载不了私有框架；Java 侧有 JNA，可以直接 {@code Native.load}。
 * 这是本轮之前判「必须写 Rust 侧车」的<b>误判来源</b>（漏算了 JNA）。
 *
 * <h2>两条通道，安全性不同</h2>
 * <table border="1">
 *   <caption>通道对照</caption>
 *   <tr><th>通道</th><th>API</th><th>性质</th><th>用于</th></tr>
 *   <tr><td>键盘</td><td>{@code CGEventPostToPid}</td><td><b>公开 API</b></td><td>{@link #postKey}</td></tr>
 *   <tr><td>指针</td><td>{@code SLEventPostToPid}（SkyLight 私有）</td>
 *       <td>私有框架</td><td>{@link #postScroll} / {@link #postClick} / {@link #postDrag}</td></tr>
 * </table>
 *
 * <h2>真机实测结论（2026-11-05 · TextEdit 靶）</h2>
 * <ul>
 *   <li><b>键盘定向可用</b>：后台 TextEdit 收到 {@code "a"}（AX 回读 {@code value="a"}），
 *       前台仍是别的 app ⇒ <b>不抢焦点</b>。这也是 dsh 的做法
 *       （{@code main.swift:777} {@code down.postToPid(app.processIdentifier)}）。</li>
 *   <li><b>滚轮定向可用，且不需要前台</b>：后台 TextEdit 文档滚动条
 *       {@code 0.5086 → 0.7562}，系统光标纹丝不动 {@code (994,338)}，前台仍是「访达」。</li>
 *   <li><b>点击/拖拽必须先激活目标应用</b>：后台投递 nodes 无变化；提到前台后同样投递
 *       nodes {@code 18 → 34}（菜单真弹出）。与 dsh {@code activateForCursor}
 *       （{@code service.ts:523}）的设计完全吻合 ⇒ <b>调用方负责先激活</b>。</li>
 * </ul>
 *
 * <h2>⛔ 踩过的坑（漏了这条，滚轮/点击会「静默无效」）</h2>
 * <p>必须<b>同时</b>调 {@code CGEventSetLocation(ev, 屏幕点)}。
 * {@code CGEventSetWindowLocation} 只设「目标窗口内部坐标」，<b>不能替代事件自身的屏幕坐标</b>
 * —— 只设后者时事件被丢弃且<b>不报错</b>（我第一次就漏了，白测两轮才定位）。
 *
 * <h2>字段序列（逐字照抄 dsh {@code TargetedPointer.swift:60-82}）</h2>
 * <pre>
 * CGEventSetWindowLocation(ev, localX, localY)
 * field  0 = gesturePhase      field  1 = clickState
 * field  3 = buttonNumber      field  7 = subtype
 * field 40 = pid               field 51 = windowNumber
 * field 58 = clickGroup        field 91 = windowNumber   field 92 = windowNumber
 * SLEventPostToPid(pid, ev)
 * </pre>
 *
 * <h2>降级</h2>
 * <p>SkyLight 是私有框架，未来可能消失/改签名。本类所有入口在不可用时
 * <b>返回 false 且不抛异常</b>，由调用方回落到全局 HID 投递（会抢焦点，但功能不丢）。
 */
final class MacOsSkyLight {

    private static final Logger log = LoggerFactory.getLogger(MacOsSkyLight.class);

    /** macOS 才可加载（Windows 上没有 CoreGraphics / SkyLight）。 */
    private static final boolean SUPPORTED = Platform.isMac();

    // ── CGEvent 字段索引（dsh TargetedPointer.swift 用字面量，这里命名以便检索）──
    private static final int FIELD_GESTURE_PHASE = 0;
    private static final int FIELD_CLICK_STATE = 1;
    private static final int FIELD_BUTTON_NUMBER = 3;
    private static final int FIELD_SUBTYPE = 7;
    private static final int FIELD_TARGET_PID = 40;
    private static final int FIELD_TARGET_WINDOW = 51;
    private static final int FIELD_CLICK_GROUP = 58;
    private static final int FIELD_TARGET_WINDOW_91 = 91;
    private static final int FIELD_TARGET_WINDOW_92 = 92;

    // ── CGEventType ──
    private static final int EVENT_LEFT_MOUSE_DOWN = 1;
    private static final int EVENT_LEFT_MOUSE_UP = 2;
    private static final int EVENT_MOUSE_MOVED = 5;
    /** {@code kCGScrollEventUnitLine}。 */
    private static final int SCROLL_UNIT_LINE = 1;
    /** {@code kCGEventSourceStatePrivate}（dsh 用 privateState 源）。 */
    private static final int SOURCE_PRIVATE = 1;
    /** 指针事件的 subtype（dsh 固定 3）。 */
    private static final int POINTER_SUBTYPE = 3;
    /** 手势阶段：移动 = 2，按下/抬起 = 3。 */
    private static final int PHASE_MOVED = 2;
    private static final int PHASE_PRESSED = 3;

    private static volatile Holder holder;

    private MacOsSkyLight() {
    }

    /** @return true = 指针定向通道可用（SkyLight 私有框架加载成功） */
    static boolean pointerAvailable() {
        return holder() != null && holder().sky != null;
    }

    /** @return true = 键盘定向通道可用（公开 API；macOS 上恒可用） */
    static boolean keyAvailable() {
        return SUPPORTED && holder() != null;
    }

    // ══════════════════════════════════════════════════════════════
    // 键盘（公开 CGEventPostToPid）
    // ══════════════════════════════════════════════════════════════

    /**
     * 把一次按键定向投递给指定进程。
     *
     * @param pid     目标进程
     * @param keyCode macOS 虚拟键码
     * @param keyDown true = 按下，false = 抬起
     * @param flags   CoreGraphics flags 位掩码（0 = 无修饰键）
     * @return true = 已投递；false = 不可用（调用方应回落全局投递）
     */
    static boolean postKey(long pid, int keyCode, boolean keyDown, long flags) {
        Holder h = holder();
        if (!SUPPORTED || h == null || pid <= 0) {
            return false;
        }
        Pointer event = null;
        try {
            // ⚠️ virtualKey 是 unsigned short（C 签名），用 short 以便 JNA 正确传 16 位
            event = h.cg.CGEventCreateKeyboardEvent(null, (short) keyCode, keyDown);
            if (event == null) {
                return false;
            }
            if (flags != 0L) {
                h.cg.CGEventSetFlags(event, flags);
            }
            h.cg.CGEventPostToPid((int) pid, event);
            return true;
        } catch (Throwable t) {
            log.warn("[computer-use] macOS 键盘定向投递失败（将回落全局投递）: pid={} keyCode={} err={}",
                    pid, keyCode, t.toString());
            return false;
        } finally {
            release(h, event);
        }
    }

    /**
     * 把一段 Unicode 文本<b>定向直投</b>给指定进程（不走剪贴板）。
     *
     * <h2>WHY 优于剪贴板 + ⌘V</h2>
     * <ul>
     *   <li><b>不碰用户剪贴板</b>：剪贴板方案要「先备份 → 写入 → 粘贴 → 还原」，
     *       中间任何一步失败都会让用户丢了原内容（本仓已在日志里如实警告过）；</li>
     *   <li><b>不受「目标屏蔽粘贴」限制</b>：某些密码框 / 终端安全输入会拒收 ⌘V，
     *       但接受 Unicode 键盘事件；</li>
     *   <li><b>与 dsh 一致</b>：dsh 的 {@code type-text} 就是 Unicode 键盘事件
     *       （{@code main.swift:856} {@code CGEvent(keyboardEventSource:...)} +
     *       {@code .postToPid(...)}），不用剪贴板。</li>
     * </ul>
     *
     * <h2>⚠️ 与 ⌘V 的语义差异（如实标注，不隐藏）</h2>
     * <p>Unicode 键盘事件会经过<b>目标应用的输入法</b>。若目标正在用中文输入法，
     * 投递的英文可能被输入法拦截成候选词。剪贴板粘贴则绕过输入法。
     * ⇒ 两者不是完全等价，遇到输入法干扰时仍需剪贴板兜底
     * （调用方 {@link MacOsKeyboard#typeText(String)} 保留该回落路径）。
     *
     * @param pid  目标进程
     * @param text 要投递的文本
     * @return true = 已投递；false = 通道不可用（调用方应回落剪贴板）
     */
    static boolean postUnicodeText(long pid, String text) {
        Holder h = holder();
        if (!SUPPORTED || h == null || pid <= 0 || text == null || text.isEmpty()) {
            return false;
        }
        // 按 UTF-16 码元切块：CGEventKeyboardSetUnicodeString 的 length 是码元数。
        // 单次事件塞太多字符时部分应用会截断 ⇒ 按 20 码元一块（实测足够稳）。
        final int chunkSize = 20;
        Pointer down = null;
        Pointer up = null;
        try {
            int offset = 0;
            while (offset < text.length()) {
                int end = Math.min(offset + chunkSize, text.length());
                // ⚠️ 不要把代理对切成两半（否则投出去是乱码）
                if (end < text.length()
                        && Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) {
                    end++;
                }
                char[] chars = text.substring(offset, end).toCharArray();

                down = h.cg.CGEventCreateKeyboardEvent(null, (short) 0, true);
                if (down == null) {
                    return false;
                }
                h.cg.CGEventKeyboardSetUnicodeString(down, chars.length, chars);
                h.cg.CGEventPostToPid((int) pid, down);
                release(h, down);
                down = null;

                sleepQuietly(2);

                up = h.cg.CGEventCreateKeyboardEvent(null, (short) 0, false);
                if (up == null) {
                    return false;
                }
                h.cg.CGEventKeyboardSetUnicodeString(up, chars.length, chars);
                h.cg.CGEventPostToPid((int) pid, up);
                release(h, up);
                up = null;

                offset = end;
                sleepQuietly(2);
            }
            return true;
        } catch (Throwable t) {
            log.warn("[computer-use] macOS 定向 Unicode 投递失败（将回落剪贴板）: pid={} 字符数={} err={}",
                    pid, text.length(), t.toString());
            return false;
        } finally {
            release(h, down);
            release(h, up);
        }
    }

    /**
     * 用 JNA 投递一次具名按键（比每次起 {@code osascript} 快约两个数量级）。
     *
     * <p>{@link MacOsJxa#keyEvent} 的 JXA 版本同样能定向（已真机验证），
     * 但每次要 fork 一个 osascript 进程（~100ms）。JNA 是进程内 JNI 调用（微秒级）。
     *
     * @param pid     目标进程（&lt;= 0 ⇒ 返回 false，由调用方回落）
     * @param keyCode macOS 虚拟键码
     * @param keyDown true = 按下
     * @param flags   CoreGraphics flags 位掩码
     * @return true = 已投递
     */
    static boolean postKeyTap(long pid, int keyCode, boolean keyDown, long flags) {
        // 复用 postKey 的实现（签名一致），保留独立名只为表达「这是 JNA 快路径」
        return postKey(pid, keyCode, keyDown, flags);
    }

    // ══════════════════════════════════════════════════════════════
    // 指针（SkyLight 私有）
    // ══════════════════════════════════════════════════════════════

    /**
     * 把滚轮事件定向投递给指定窗口。
     *
     * <p><b>后台可用</b>（实测）：不需要先把应用激活到前台。
     *
     * @param pid          目标进程
     * @param windowNumber 目标窗口号（{@code CGWindowList} 的 {@code kCGWindowNumber}）
     * @param screenX      屏幕坐标 X（点）· 用于 {@code CGEventSetLocation}
     * @param screenY      屏幕坐标 Y（点）
     * @param windowX      窗口原点 X（用于算窗口内局部坐标）
     * @param windowY      窗口原点 Y
     * @param vertical     纵向滚动量（正 = 向上）
     * @param horizontal   横向滚动量
     * @return true = 已投递
     */
    static boolean postScroll(long pid, long windowNumber,
                              double screenX, double screenY,
                              double windowX, double windowY,
                              int vertical, int horizontal) {
        Holder h = holder();
        if (!SUPPORTED || h == null || h.sky == null || pid <= 0) {
            return false;
        }
        long group = clickGroup();
        Pointer moved = null;
        Pointer scroll = null;
        try {
            // ① 先投一个 mouseMoved 把「指针在哪个窗口的哪个位置」建立起来（dsh 同款）
            moved = h.cg.CGEventCreateMouseEvent(null, EVENT_MOUSE_MOVED, screenX, screenY, 0);
            if (moved == null) {
                return false;
            }
            sendPointer(h, moved, pid, windowNumber, screenX, screenY, windowX, windowY, group,
                    0, 0, POINTER_SUBTYPE, PHASE_MOVED);
            sleepQuietly(12);

            // ② 滚轮本体（坐标由 sendPointer 成对设置）
            scroll = h.cg.CGEventCreateScrollWheelEvent2(null, SCROLL_UNIT_LINE, 2,
                    vertical, horizontal, 0);
            if (scroll == null) {
                return false;
            }
            sendPointer(h, scroll, pid, windowNumber, screenX, screenY, windowX, windowY, group,
                    0, 0, POINTER_SUBTYPE, PHASE_MOVED);
            return true;
        } catch (Throwable t) {
            log.warn("[computer-use] macOS 定向滚轮投递失败（将回落全局投递）: pid={} win={} err={}",
                    pid, windowNumber, t.toString());
            return false;
        } finally {
            release(h, moved);
            release(h, scroll);
        }
    }

    /**
     * 把左键单击定向投递给指定窗口。
     *
     * <p><b>⚠️ 前提：目标应用必须已在前台</b>（真机实测：后台投递无效果，前台投递生效）。
     * 这与 dsh 的 {@code activateForCursor} 设计一致 —— <b>调用方负责先激活</b>，
     * 本方法只负责「投递得准、不动系统光标」。
     *
     * @param pid          目标进程
     * @param windowNumber 目标窗口号
     * @param screenX      点击点屏幕坐标 X（点）
     * @param screenY      点击点屏幕坐标 Y
     * @param windowX      窗口原点 X
     * @param windowY      窗口原点 Y
     * @return true = 已投递
     */
    static boolean postClick(long pid, long windowNumber,
                             double screenX, double screenY,
                             double windowX, double windowY) {
        Holder h = holder();
        if (!SUPPORTED || h == null || h.sky == null || pid <= 0) {
            return false;
        }
        long group = clickGroup();
        Pointer moved = null;
        Pointer down = null;
        Pointer up = null;
        try {
            moved = h.cg.CGEventCreateMouseEvent(null, EVENT_MOUSE_MOVED, screenX, screenY, 0);
            if (moved == null) {
                return false;
            }
            sendPointer(h, moved, pid, windowNumber, screenX, screenY, windowX, windowY, group,
                    0, 0, POINTER_SUBTYPE, PHASE_MOVED);
            sleepQuietly(12);

            down = h.cg.CGEventCreateMouseEvent(null, EVENT_LEFT_MOUSE_DOWN, screenX, screenY, 0);
            if (down == null) {
                return false;
            }
            sendPointer(h, down, pid, windowNumber, screenX, screenY, windowX, windowY, group,
                    1, 0, POINTER_SUBTYPE, PHASE_PRESSED);
            sleepQuietly(28);

            up = h.cg.CGEventCreateMouseEvent(null, EVENT_LEFT_MOUSE_UP, screenX, screenY, 0);
            if (up == null) {
                return false;
            }
            sendPointer(h, up, pid, windowNumber, screenX, screenY, windowX, windowY, group,
                    1, 0, POINTER_SUBTYPE, PHASE_PRESSED);
            return true;
        } catch (Throwable t) {
            log.warn("[computer-use] macOS 定向点击投递失败（将回落全局投递）: pid={} win={} err={}",
                    pid, windowNumber, t.toString());
            return false;
        } finally {
            release(h, moved);
            release(h, down);
            release(h, up);
        }
    }

    /**
     * 定向拖拽（按下 → 中间移动若干步 → 抬起）。
     *
     * <p>与 {@link #postClick} 同样<b>要求目标已在前台</b>。
     *
     * @param pid          目标进程
     * @param windowNumber 目标窗口号
     * @param fromX        起点屏幕 X
     * @param fromY        起点屏幕 Y
     * @param toX          终点屏幕 X
     * @param toY          终点屏幕 Y
     * @param windowX      窗口原点 X
     * @param windowY      窗口原点 Y
     * @return true = 已投递
     */
    static boolean postDrag(long pid, long windowNumber,
                            double fromX, double fromY, double toX, double toY,
                            double windowX, double windowY) {
        Holder h = holder();
        if (!SUPPORTED || h == null || h.sky == null || pid <= 0) {
            return false;
        }
        long group = clickGroup();
        Pointer down = null;
        try {
            Pointer moved = h.cg.CGEventCreateMouseEvent(null, EVENT_MOUSE_MOVED, fromX, fromY, 0);
            if (moved == null) {
                return false;
            }
            sendPointer(h, moved, pid, windowNumber, fromX, fromY, windowX, windowY, group,
                    0, 0, POINTER_SUBTYPE, PHASE_MOVED);
            release(h, moved);
            sleepQuietly(12);

            down = h.cg.CGEventCreateMouseEvent(null, EVENT_LEFT_MOUSE_DOWN, fromX, fromY, 0);
            if (down == null) {
                return false;
            }
            sendPointer(h, down, pid, windowNumber, fromX, fromY, windowX, windowY, group,
                    1, 0, POINTER_SUBTYPE, PHASE_PRESSED);
            sleepQuietly(28);

            // 分 8 步插值：一步到位会被某些应用识别成「瞬移」而忽略拖拽
            final int steps = 8;
            for (int i = 1; i <= steps; i++) {
                double px = fromX + (toX - fromX) * i / steps;
                double py = fromY + (toY - fromY) * i / steps;
                Pointer step = h.cg.CGEventCreateMouseEvent(null, EVENT_MOUSE_MOVED, px, py, 0);
                if (step == null) {
                    continue;
                }
                // 拖拽中的移动事件：buttonNumber 留 0，clickState 保持 1 表示「仍在按住」
                sendPointer(h, step, pid, windowNumber, px, py, windowX, windowY, group,
                        1, 0, POINTER_SUBTYPE, PHASE_MOVED);
                release(h, step);
                sleepQuietly(16);
            }

            Pointer up = h.cg.CGEventCreateMouseEvent(null, EVENT_LEFT_MOUSE_UP, toX, toY, 0);
            if (up == null) {
                return false;
            }
            sendPointer(h, up, pid, windowNumber, toX, toY, windowX, windowY, group,
                    1, 0, POINTER_SUBTYPE, PHASE_PRESSED);
            release(h, up);
            return true;
        } catch (Throwable t) {
            log.warn("[computer-use] macOS 定向拖拽投递失败（将回落全局投递）: pid={} win={} err={}",
                    pid, windowNumber, t.toString());
            return false;
        } finally {
            release(h, down);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════════

    /**
     * 设置目标字段并投递 —— <b>本类的唯一投递出口</b>。
     *
     * <h2>⛔ 为什么两个坐标必须在这里成对设置（而不是交给调用方）</h2>
     * <p>一个事件上有<b>两个</b>坐标，缺任何一个都会表现为
     * 「调用返回成功、界面毫无变化、也没有任何报错」：
     * <ol>
     *   <li>{@code CGEventSetLocation(ev, 屏幕点)} —— <b>事件自身的屏幕坐标</b>。
     *       漏了它 ⇒ 系统<b>静默丢弃</b>该事件。
     *       <br>（2026-11-05 第一次实现时我漏了这一步，滚轮/点击全部无效，
     *       白测两轮才定位 —— 因为没有任何错误码可看。）</li>
     *   <li>{@code CGEventSetWindowLocation(ev, 窗口内局部点)} —— 目标窗口内部的落点。
     *       漏了它 ⇒ 事件落到错位置。<b>它不能替代①</b>（两者语义不同）。</li>
     * </ol>
     * <p><b>把这两步封在此处，是为了让「漏设屏幕坐标」这个错误无法再被写出来</b>：
     * 本类所有定向投递都经过这里，调用方只提供屏幕坐标 + 窗口原点，
     * <b>拿不到裸的 {@code SLEventPostToPid}</b> 也就无从漏设。
     *
     * @param screenX 事件自身的屏幕坐标 X（点）
     * @param screenY 事件自身的屏幕坐标 Y（点）
     * @param windowX 窗口原点 X（局部坐标由 {@code screenX - windowX} 现算）
     * @param windowY 窗口原点 Y
     */
    private static void sendPointer(Holder h, Pointer event, long pid, long windowNumber,
                                    double screenX, double screenY,
                                    double windowX, double windowY, long group,
                                    long clickState, long buttonNumber, long subtype, long phase) {
        // ① 事件自身的屏幕坐标 —— 漏了 ⇒ 静默丢弃（无错误码）
        h.cg.CGEventSetLocation(event, screenX, screenY);
        // ② 窗口内局部坐标 —— 由屏幕坐标减窗口原点现算，避免调用方算错
        h.sky.CGEventSetWindowLocation(event, screenX - windowX, screenY - windowY);
        h.sky.SLEventSetIntegerValueField(event, FIELD_GESTURE_PHASE, phase);
        h.sky.SLEventSetIntegerValueField(event, FIELD_CLICK_STATE, clickState);
        h.sky.SLEventSetIntegerValueField(event, FIELD_BUTTON_NUMBER, buttonNumber);
        h.sky.SLEventSetIntegerValueField(event, FIELD_SUBTYPE, subtype);
        h.sky.SLEventSetIntegerValueField(event, FIELD_TARGET_PID, pid);
        h.sky.SLEventSetIntegerValueField(event, FIELD_TARGET_WINDOW, windowNumber);
        h.sky.SLEventSetIntegerValueField(event, FIELD_CLICK_GROUP, group);
        h.sky.SLEventSetIntegerValueField(event, FIELD_TARGET_WINDOW_91, windowNumber);
        h.sky.SLEventSetIntegerValueField(event, FIELD_TARGET_WINDOW_92, windowNumber);
        h.sky.SLEventPostToPid((int) pid, event);
    }

    /** 释放 CGEvent（CGEventCreate* 返回 +1 引用；不释放会随观察次数累积）。 */
    private static void release(Holder h, Pointer event) {
        if (event == null || h == null) {
            return;
        }
        try {
            h.cf.CFRelease(event);
        } catch (Throwable ignore) {
            // 释放失败不影响功能
        }
    }

    private static long clickGroup() {
        return System.nanoTime() & 0x7fff_ffffL;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Holder holder() {
        Holder local = holder;
        if (local == null) {
            synchronized (MacOsSkyLight.class) {
                local = holder;
                if (local == null) {
                    local = Holder.create();
                    holder = local;
                }
            }
        }
        return local;
    }

    // ── JNA 绑定 ──

    /** CoreGraphics 的公开子集（键盘 + 鼠标事件创建）。 */
    public interface CoreGraphics extends Library {
        Pointer CGEventCreateKeyboardEvent(Pointer source, short virtualKey, boolean keyDown);

        Pointer CGEventCreateMouseEvent(Pointer source, int mouseType, double x, double y, int button);

        Pointer CGEventCreateScrollWheelEvent2(Pointer source, int units, int wheelCount,
                                               int wheel1, int wheel2, int wheel3);

        void CGEventSetFlags(Pointer event, long flags);

        void CGEventSetLocation(Pointer event, double x, double y);

        void CGEventPostToPid(int pid, Pointer event);

        /**
         * 把 Unicode 文本塞进键盘事件。
         *
         * <p>⚠️ {@code length} 是 <b>UTF-16 码元数</b>（{@code char[]} 的长度），不是码点数 ——
         * 增补平面字符（emoji）占 2 个码元。JNA 用 {@code char[]} 直传会映射成
         * {@code UniChar*}，实测可用。
         *
         * <p><b>⭐ 这是绕开剪贴板的正确做法</b>：JXA 桥传不进 {@code UniChar*}
         * （传 "BBB" 会按 keyCode 0 打出 "aaa"），但 <b>JNA 直调可以</b> ——
         * 真机实测向后台 TextEdit 投「中文定向测试」⇒ AX 回读 {中文定向测试}，
         * 前端应用未变。dsh 的 {@code type-text} 走的正是这条（Unicode 键盘事件，非剪贴板）。
         */
        void CGEventKeyboardSetUnicodeString(Pointer event, long length, char[] chars);
    }

    /** SkyLight 私有子集（指针定向投递）。 */
    public interface SkyLight extends Library {
        void SLEventPostToPid(int pid, Pointer event);

        void SLEventSetIntegerValueField(Pointer event, int field, long value);

        void CGEventSetWindowLocation(Pointer event, double x, double y);
    }

    /** CoreFoundation（只用于 CFRelease）。 */
    public interface CoreFoundation extends Library {
        void CFRelease(Pointer cf);
    }

    /** 懒加载容器：SkyLight 加载失败 ⇒ {@code sky == null}（指针通道不可用，键盘仍可用）。 */
    private static final class Holder {
        private final CoreGraphics cg;
        private final SkyLight sky;
        private final CoreFoundation cf;

        private Holder(CoreGraphics cg, SkyLight sky, CoreFoundation cf) {
            this.cg = cg;
            this.sky = sky;
            this.cf = cf;
        }

        private static Holder create() {
            if (!SUPPORTED) {
                return new Holder(null, null, null);
            }
            CoreGraphics cg = null;
            SkyLight sky = null;
            CoreFoundation cf = null;
            try {
                cg = Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
                        CoreGraphics.class);
            } catch (Throwable t) {
                log.warn("[computer-use] macOS CoreGraphics 加载失败 ⇒ 定向投递不可用: {}", t.toString());
            }
            try {
                // 私有框架：未来可能消失 ⇒ 失败只降级，不抛
                sky = Native.load("/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight",
                        SkyLight.class);
            } catch (Throwable t) {
                log.warn("[computer-use] macOS SkyLight 私有框架加载失败 ⇒ 指针定向投递不可用"
                        + "（滚轮/点击/拖拽将回落全局投递）: {}", t.toString());
            }
            try {
                cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                        CoreFoundation.class);
            } catch (Throwable ignore) {
                // 没它只是少释放，不影响功能
            }
            return new Holder(cg, sky, cf);
        }
    }
}
