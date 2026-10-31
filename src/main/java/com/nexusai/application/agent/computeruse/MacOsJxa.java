package com.nexusai.application.agent.computeruse;

import java.util.Locale;

/**
 * macOS <b>JXA（JavaScript for Automation）脚本构造器</b> · computer-use macOS 执行端。
 *
 * <h2>WHY 进程外 {@code osascript}</h2>
 * <p>CCB 的 macOS 路线<b>不是</b> Swift 原生模块，而是 <b>JXA + CoreGraphics</b>：
 * <ul>
 *   <li>输入注入：{@code packages/@ant/computer-use-input/src/backends/darwin.ts}
 *       —— {@code jxa(script)} = {@code execFile('osascript', ['-l','JavaScript','-e',script])}（{@code :64-73}）；</li>
 *   <li>显示器 / 应用 / 前台应用 / 截屏：{@code packages/@ant/computer-use-swift/src/backends/darwin.ts}
 *       —— {@code jxaSync/jxa}（{@code :40-76}）同样是 {@code osascript -l JavaScript -e …}。</li>
 * </ul>
 * ⇒ 本仓<b>逐字沿用</b>该调用形态（{@code osascript -l JavaScript -e '<JXA>'}），
 * ⛔ 不引入 JNA 的 macOS 绑定（保证 Windows 上也能编译），
 * ⛔ 不用 CC 官方 2.1.88 的 Swift 原生路线。
 *
 * <h2>本类与 CCB 的逐项对照</h2>
 * <pre>
 * 方法                       CCB 真源（file:line）                        本类方法
 * ────────────────────────────────────────────────────────────────────────────────
 * mouseEvent                 input/backends/darwin.ts:75-88 buildMouseJxa   mouseEvent(...)
 * mouseLocation              input/backends/darwin.ts:130-136             mouseLocation()
 * scrollWheel                input/backends/darwin.ts:170-179 mouseScroll  scrollWheel(dx,dy)
 * displayList                swift/backends/darwin.ts:94-114 listAll      displayList()
 * frontmostApplication       swift/backends/darwin.ts:172-185 appUnder…  frontmostApplication()
 * screenshot（screencapture） swift/backends/darwin.ts:270-303            （在 MacOsScreenCapturer）
 * </pre>
 *
 * <h2>⚠️ 键盘原语：CCB 文件里<b>没有</b> {@code CGEventCreateKeyboardEvent}</h2>
 * <p>逐行读过 {@code packages/@ant/computer-use-input/src/backends/darwin.ts} 全文件（210 行）后确认：
 * CCB 的 macOS 键盘走的是 <b>AppleScript System Events</b>
 * （{@code :99} {@code tell application "System Events" to key code N}、
 * {@code :102} {@code … to keystroke "…"}、{@code :120-127} 的 {@code using {command down}} 修饰键语法），
 * <b>没有</b> {@code CGEventCreateKeyboardEvent}。
 * <br>⇒ 本仓的键盘走 <b>CoreGraphics</b>（{@link #keyEvent} / {@link #unicodeKey}），
 * 原因是<b>本仓的事件模型不同</b>：本仓把组合键拆成「修饰键 down → 主键 down → 主键 up → 修饰键 up」
 * 的<b>逐事件</b>序列（{@link ComputerUseKeyCombo#parse(String)}），
 * 而 CCB 的 {@code keys()} 是<b>一次</b> AppleScript 调用里带 {@code using {…}}。
 * ⇒ 逐事件派发必须显式管理「当前按住哪些修饰键」并写进事件的 flags
 * （{@link #keyEvent} 的 {@code flagsExpr}）—— 这是本仓<b>相对 CCB 的实现差异</b>，
 * 详见 {@link MacOsKeyboard} 类注释。
 */
public final class MacOsJxa {

    private MacOsJxa() {
        throw new AssertionError("utility class");
    }

    // ══════════════════════════════════════════════════════════════════
    // 鼠标（CCB input/backends/darwin.ts）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 鼠标事件脚本 · 逐字对齐 CCB {@code buildMouseJxa}
     * （{@code packages/@ant/computer-use-input/src/backends/darwin.ts:75-88}）。
     *
     * <p>CCB 原脚本形状：
     * <pre>
     * ObjC.import("CoreGraphics");
     * var p = $.CGPointMake(${x},${y});
     * var e = $.CGEventCreateMouseEvent(null, $.${eventType}, p, ${btn});
     * [$.CGEventSetIntegerValueField(e, $.kCGMouseEventClickState, ${clickState});]
     * $.CGEventPost($.kCGHIDEventTap, e);
     * </pre>
     *
     * @param eventType  CGEvent 事件类型常量名（如 {@code kCGEventMouseMoved}）
     * @param x          Quartz 全局坐标 X（<b>点</b>，非像素 —— 见类注释「坐标空间」）
     * @param y          Quartz 全局坐标 Y（同上）
     * @param button     鼠标键序号（0=左 / 1=右 / 2=中）
     * @param clickState 点击序号（null ⇒ 不设 {@code kCGMouseEventClickState}；
     *                   CCB 的 {@code mouseButton} 在 click 时逐次传 {@code i+1}）
     * @return JXA 脚本文本
     */
    public static String mouseEvent(String eventType, int x, int y, int button, Integer clickState) {
        StringBuilder sb = new StringBuilder();
        sb.append("ObjC.import(\"CoreGraphics\"); ");
        sb.append("var p = $.CGPointMake(").append(x).append(',').append(y).append("); ");
        sb.append("var e = $.CGEventCreateMouseEvent(null, $.").append(eventType)
                .append(", p, ").append(button).append("); ");
        if (clickState != null) {
            sb.append("$.CGEventSetIntegerValueField(e, $.kCGMouseEventClickState, ")
                    .append(clickState).append("); ");
        }
        sb.append("$.CGEventPost($.kCGHIDEventTap, e);");
        return sb.toString();
    }

    /**
     * 读当前光标位置 · 逐字对齐 CCB
     * {@code packages/@ant/computer-use-input/src/backends/darwin.ts:130-136}。
     *
     * <p>CCB 原脚本：
     * <pre>
     * ObjC.import("CoreGraphics"); var e = $.CGEventCreate(null); var p = $.CGEventGetLocation(e); p.x + "," + p.y
     * </pre>
     *
     * @return JXA 脚本文本（输出形如 {@code "123.5,45.25"}）
     */
    public static String mouseLocation() {
        return "ObjC.import(\"CoreGraphics\"); var e = $.CGEventCreate(null); "
                + "var p = $.CGEventGetLocation(e); p.x + \",\" + p.y";
    }

    /**
     * 滚轮事件脚本 · 逐字对齐 CCB
     * {@code packages/@ant/computer-use-input/src/backends/darwin.ts:170-179}。
     *
     * <p>CCB 原脚本：
     * <pre>
     * 垂直：ObjC.import("CoreGraphics"); var e = $.CGEventCreateScrollWheelEvent(null, 0, 1, ${amount}); $.CGEventPost($.kCGHIDEventTap, e);
     * 水平：ObjC.import("CoreGraphics"); var e = $.CGEventCreateScrollWheelEvent(null, 0, 2, 0, ${amount}); $.CGEventPost($.kCGHIDEventTap, e);
     * </pre>
     *
     * <p>⚠️ <b>符号约定逐字沿用 CCB</b>：CCB 的 {@code handleScroll}
     * （{@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2647-2649}）把
     * 「上」映射成 {@code dy = -amount}、「下」映射成 {@code dy = +amount}，
     * 而 {@code mouseScroll} 把 amount <b>原样</b>传给 {@code CGEventCreateScrollWheelEvent}
     * ⇒ 本仓保持同款符号（{@code dy<0} = 上），与本仓 Windows 侧
     * （{@code Win32MouseActions.scroll}：{@code mouseData = dy * 120}）同款。
     *
     * @param dx 水平位移（正 = 右，负 = 左）
     * @param dy 垂直位移（正 = 下，负 = 上）
     * @return JXA 脚本文本
     */
    public static String scrollWheel(int dx, int dy) {
        if (dy == 0 && dx == 0) {
            throw new IllegalArgumentException("[computer-use] 滚轮位移不能同时为 0");
        }
        if (dy != 0) {
            return verticalScrollWheel(dy);
        }
        return horizontalScrollWheel(dx);
    }

    /**
     * 垂直滚轮脚本 · CCB {@code input/backends/darwin.ts:176}。
     *
     * @param dy 垂直位移（CCB 符号：正 = 下、负 = 上）
     * @return JXA 脚本文本
     */
    public static String verticalScrollWheel(int dy) {
        return "ObjC.import(\"CoreGraphics\"); "
                + "var e = $.CGEventCreateScrollWheelEvent(null, 0, 1, " + dy + "); "
                + "$.CGEventPost($.kCGHIDEventTap, e);";
    }

    /**
     * 水平滚轮脚本 · CCB {@code input/backends/darwin.ts:177}。
     *
     * @param dx 水平位移（CCB 符号：正 = 右、负 = 左）
     * @return JXA 脚本文本
     */
    public static String horizontalScrollWheel(int dx) {
        return "ObjC.import(\"CoreGraphics\"); "
                + "var e = $.CGEventCreateScrollWheelEvent(null, 0, 2, 0, " + dx + "); "
                + "$.CGEventPost($.kCGHIDEventTap, e);";
    }

    // ══════════════════════════════════════════════════════════════════
    // 键盘（CoreGraphics · 本仓相对 CCB 的实现差异，见类注释）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 按键事件脚本（CoreGraphics）· 带修饰键 flags。
     *
     * <p>脚本形状：
     * <pre>
     * ObjC.import("CoreGraphics");
     * var e = $.CGEventCreateKeyboardEvent(null, ${keyCode}, ${true|false});
     * $.CGEventSetFlags(e, ${flagsExpr});
     * $.CGEventPost($.kCGHIDEventTap, e);
     * </pre>
     *
     * @param keyCode   macOS 虚拟键码（{@link MacOsVirtualKeys}）
     * @param keyDown   true = 按下；false = 抬起
     * @param flagsExpr 修饰键 flags 的 JS 表达式（空 ⇒ 不调用 {@code CGEventSetFlags}）
     * @return JXA 脚本文本
     */
    public static String keyEvent(int keyCode, boolean keyDown, String flagsExpr) {
        StringBuilder sb = new StringBuilder();
        sb.append("ObjC.import(\"CoreGraphics\"); ");
        sb.append("var e = $.CGEventCreateKeyboardEvent(null, ").append(keyCode)
                .append(", ").append(keyDown ? "true" : "false").append("); ");
        if (flagsExpr != null && !flagsExpr.isBlank()) {
            sb.append("$.CGEventSetFlags(e, ").append(flagsExpr).append("); ");
        }
        sb.append("$.CGEventPost($.kCGHIDEventTap, e);");
        return sb.toString();
    }

    /**
     * Unicode 文本注入脚本（CoreGraphics）· down + up 各一次。
     *
     * <p>脚本形状：
     * <pre>
     * ObjC.import("CoreGraphics");
     * var d = $.CGEventCreateKeyboardEvent(null, 0, true);
     * $.CGEventSetUnicodeString(d, ${quoted});
     * $.CGEventPost($.kCGHIDEventTap, d);
     * var u = $.CGEventCreateKeyboardEvent(null, 0, false);
     * $.CGEventSetUnicodeString(u, ${quoted});
     * $.CGEventPost($.kCGHIDEventTap, u);
     * </pre>
     *
     * @param character 要注入的单个字符（Unicode 码点）
     * @return JXA 脚本文本
     */
    public static String unicodeKey(String character) {
        return unicodeKey(character, null);
    }

    /**
     * Unicode 文本注入脚本（CoreGraphics）· down + up 各一次（可带修饰键 flags）。
     *
     * @param character 要注入的单个字符（Unicode 码点）
     * @param flagsExpr 修饰键 flags 的 JS 表达式（空 ⇒ 不调用 {@code CGEventSetFlags}）
     * @return JXA 脚本文本
     */
    public static String unicodeKey(String character, String flagsExpr) {
        String quoted = quote(character);
        String flags = (flagsExpr == null || flagsExpr.isBlank())
                ? ""
                : "$.CGEventSetFlags(d, " + flagsExpr + "); $.CGEventSetFlags(u, " + flagsExpr + "); ";
        return "ObjC.import(\"CoreGraphics\"); "
                + "var d = $.CGEventCreateKeyboardEvent(null, 0, true); "
                + "$.CGEventSetUnicodeString(d, " + quoted + "); "
                + "var u = $.CGEventCreateKeyboardEvent(null, 0, false); "
                + "$.CGEventSetUnicodeString(u, " + quoted + "); "
                + flags
                + "$.CGEventPost($.kCGHIDEventTap, d); "
                + "$.CGEventPost($.kCGHIDEventTap, u);";
    }

    // ══════════════════════════════════════════════════════════════════
    // 显示器 / 应用（CCB swift/backends/darwin.ts）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 显示器列表脚本 · 对齐 CCB
     * {@code packages/@ant/computer-use-swift/src/backends/darwin.ts:94-114}
     * （{@code listAll} 的 JXA 分支）。
     *
     * <p>CCB 原脚本（{@code :94-114}）用 {@code CGGetActiveDisplayList} 取显示器列表、
     * {@code CGDisplayPixelsWide/High} 取像素宽高、
     * {@code CGDisplayCopyDisplayMode} + {@code CGDisplayModeGetPixelWidth} 推缩放比。
     *
     * <p><b>本仓的两处差异（如实标注）</b>：
     * <ul>
     *   <li><b>缩放比取法不同</b>：CCB 算的是 {@code modePixelWidth / CGDisplayPixelsWide}
     *       （<b>点/像素</b>，Retina 上 &lt; 1）；本仓按<b>本仓既有约定</b>
     *       （{@code ComputerUseCaptureResult.scaleFactor} = <b>像素/点</b>，
     *       与 Windows 侧 {@code LOGPIXELSX/96} 同向）算
     *       {@code CGDisplayPixelsWide / CGDisplayBounds.width}。</li>
     *   <li><b>额外产出原点</b>：CCB 的 macOS {@code listAll} <b>不产出</b> origin
     *       ⇒ 多屏时全部显示器的 origin 都是 0；本仓额外用
     *       {@code CGDisplayBounds} 给出每块屏在 Quartz 全局空间里的原点
     *       （多屏偏移才是真实值）。</li>
     * </ul>
     *
     * @return JXA 脚本文本（输出 JSON 数组）
     */
    public static String displayList() {
        return "ObjC.import(\"CoreGraphics\"); "
                + "var countRef = Ref(); "
                + "$.CGGetActiveDisplayList(0, null, countRef); "
                + "var count = countRef[0]; "
                + "var idBuf = Ref(); "
                + "$.CGGetActiveDisplayList(count, idBuf, countRef); "
                + "var mainId = $.CGMainDisplayID(); "
                + "var result = []; "
                + "for (var i = 0; i < count; i++) { "
                + "  var did = idBuf[i]; "
                + "  var pw = $.CGDisplayPixelsWide(did); "
                + "  var ph = $.CGDisplayPixelsHigh(did); "
                + "  var b = $.CGDisplayBounds(did); "
                + "  var bw = b.size.width; "
                + "  var bh = b.size.height; "
                + "  var ppt = (bw > 0 && pw > 0) ? (pw / bw) : 2; "
                + "  result.push({displayId: did, pixelWidth: pw, pixelHeight: ph, "
                + "pointWidth: bw, pointHeight: bh, originX: b.origin.x, originY: b.origin.y, "
                + "pixelsPerPoint: ppt, isPrimary: (did == mainId)}); "
                + "} "
                + "JSON.stringify(result);";
    }

    /**
     * 前台应用脚本 · 对齐 CCB
     * {@code packages/@ant/computer-use-swift/src/backends/darwin.ts:172-185}
     * （{@code appUnderPoint} 的 JXA 分支 —— 它取的正是
     * {@code NSWorkspace.sharedWorkspace.frontmostApplication}）。
     *
     * <p>CCB 原脚本：
     * <pre>
     * ObjC.import("CoreGraphics");
     * ObjC.import("AppKit");
     * var pt = $.CGPointMake(${x}, ${y});
     * var app = $.NSWorkspace.sharedWorkspace.frontmostApplication;
     * JSON.stringify({bundleId: app.bundleIdentifier.js, displayName: app.localizedName.js});
     * </pre>
     *
     * @return JXA 脚本文本（输出 JSON 对象）
     */
    public static String frontmostApplication() {
        return "ObjC.import(\"CoreGraphics\"); "
                + "ObjC.import(\"AppKit\"); "
                + "var app = $.NSWorkspace.sharedWorkspace.frontmostApplication; "
                + "JSON.stringify({bundleId: app.bundleIdentifier.js, displayName: app.localizedName.js});";
    }

    /**
     * 已安装应用枚举脚本 · 对齐 CCB
     * {@code packages/@ant/computer-use-swift/src/backends/darwin.ts:187-226}
     * （{@code listInstalled}）。
     *
     * <p>CCB 用 {@code mdls -name kMDItemCFBundleIdentifier} 逐个 {@code .app} 取<b>真实 bundle id</b>
     * （CCB 注释原文：早前的 AppleScript 方案会造出假 bundle id，导致 request_access 匹配不到）。
     * <br>本仓把它折成<b>一条</b> JXA 脚本（{@code /bin/sh} + {@code mdls}），
     * 输出与 CCB 同构的 {@code path|displayName|bundleId} 行。
     *
     * @return shell 脚本文本（不是 JXA —— CCB 这里用的是 {@code bash -c}）
     */
    public static String installedApplicationsShell() {
        return "for d in \"/Applications\" \"$HOME/Applications\" \"/System/Applications\"; do "
                + "[ -d \"$d\" ] || continue; "
                + "for f in \"$d\"/*.app; do "
                + "[ -d \"$f\" ] || continue; "
                + "bid=$(mdls -name kMDItemCFBundleIdentifier \"$f\" 2>/dev/null | sed 's/.*= \"//;s/\"//'); "
                + "name=$(basename \"$f\" .app); "
                + "echo \"$f|$name|$bid\"; "
                + "done; done";
    }

    /**
     * 运行中应用枚举脚本 · 对齐 CCB
     * {@code packages/@ant/computer-use-swift/src/backends/darwin.ts:232-249}
     * （{@code listRunning}）。
     *
     * <p>CCB 原脚本：
     * <pre>
     * var apps = Application("System Events").applicationProcesses.whose({backgroundOnly: false});
     * var result = [];
     * for (var i = 0; i &lt; apps.length; i++) {
     *   try { var a = apps[i]; result.push({bundleId: a.bundleIdentifier(), displayName: a.name()}); } catch(e) {}
     * }
     * JSON.stringify(result);
     * </pre>
     *
     * @return JXA 脚本文本（输出 JSON 数组）
     */
    public static String runningApplications() {
        return "var apps = Application(\"System Events\").applicationProcesses"
                + ".whose({backgroundOnly: false}); "
                + "var result = []; "
                + "for (var i = 0; i < apps.length; i++) { "
                + "  try { var a = apps[i]; "
                + "    result.push({bundleId: a.bundleIdentifier(), displayName: a.name()}); "
                + "  } catch(e) {} "
                + "} "
                + "JSON.stringify(result);";
    }

    // ══════════════════════════════════════════════════════════════════
    // TCC 授权探测（CCB 无 JXA 对应 · 本仓新增）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 辅助功能（Accessibility）授权探测脚本。
     *
     * <p><b>本仓新增</b>：CCB 的 macOS TCC 检查在其<b>原生 Swift 模块</b>里
     * （{@code cu.tcc.checkAccessibility()}），CCB 的 <b>JXA 副本没有实现</b>
     * —— 只有 {@code src/utils/computerUse/hostAdapter.ts:36-51} 的
     * 「问 System Events 要进程列表」式探针。
     * <br>⇒ 本仓用 macOS 的<b>规范 API</b> {@code AXIsProcessTrusted()}
     * （ApplicationServices 框架）做等价判定。
     *
     * @return JXA 脚本文本（输出 {@code "true"} / {@code "false"}）
     */
    public static String accessibilityTrusted() {
        return "ObjC.import(\"ApplicationServices\"); "
                + "$.AXIsProcessTrusted() ? \"true\" : \"false\"";
    }

    /**
     * 屏幕录制（Screen Recording）授权探测脚本。
     *
     * <p><b>本仓新增</b>：CCB 的 JXA 副本用 {@code screencapture -x -R 0,0,1,1 /dev/null}
     * 的退出码做探针（{@code src/utils/computerUse/hostAdapter.ts:53-64}），
     * 没有用规范 API。
     * <br>⇒ 本仓用 macOS 10.15+ 的 {@code CGPreflightScreenCaptureAccess()}
     * （CoreGraphics 框架）做等价判定。
     *
     * @return JXA 脚本文本（输出 {@code "true"} / {@code "false"}）
     */
    public static String screenCaptureAccess() {
        return "ObjC.import(\"CoreGraphics\"); "
                + "$.CGPreflightScreenCaptureAccess() ? \"true\" : \"false\"";
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════════

    /**
     * JS 单引号字符串转义（{@code unicodeKey} 用）。
     *
     * @param raw 原始串
     * @return 带引号的 JS 字面量
     */
    static String quote(String raw) {
        if (raw == null) {
            return "''";
        }
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('\'').toString();
    }
}
