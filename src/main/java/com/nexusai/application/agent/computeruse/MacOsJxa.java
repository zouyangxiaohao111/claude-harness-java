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
     * @deprecated JXA 桥缺少 {@code CGEventSetUnicodeString}；改用 {@link #pasteText(String)}
     */
    @Deprecated
    public static String unicodeKey(String character) {
        return unicodeKey(character, null);
    }

    /**
     * Unicode 文本注入脚本（CoreGraphics）· down + up 各一次。
     *
     * <h2>⛔ 已废弃：本方法的 {{@code CGEventSetUnicodeString}} 在 JXA 桥里根本不存在</h2>
     * <p>实测（macOS 15.7.9）：{@code typeof $.CGEventSetUnicodeString === "undefined"}，
     * 调用直接抛 {@code TypeError: $.CGEventSetUnicodeString is not a function (-2700)}。
     * <br>改用正式 API 名 {@code CGEventKeyboardSetUnicodeString} 虽然 {@code typeof} 是 function、
     * 调用也不报错，但<b>JS 字符串传不进 {@code UniChar*} 参数</b> —— 传 {@code "BBB"} 实际按
     * keyCode 0 的键盘布局打出 {@code "aaa"}。
     * <br>⇒ <b>JXA 无法注入任意 Unicode</b>。macOS 的文本输入改走
     * {@link #pasteText(String)}（剪贴板 + ⌘V），见 {@link MacOsKeyboard#typeChar(int)}。
     *
     * <p><b>保留本方法仅用于历史对照与单测断言「不可用」的负向验证</b>，
     * ⛔ <b>不得</b>再被生产路径调用。
     *
     * @param character 要注入的单个字符（Unicode 码点）
     * @param flagsExpr 修饰键 flags 的 JS 表达式
     * @return JXA 脚本文本
     * @deprecated JXA 桥缺少该函数，注入静默失效；改用 {@link #pasteText(String)}
     */
    @Deprecated
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
    // 文本输入（剪贴板快路径 —— macOS 唯一可行方案）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 文本粘贴脚本 · <b>一次 JXA 调用内完成</b>「快照剪贴板 → 写入文本 → ⌘V → 还原剪贴板」。
     *
     * <h2>WHY 必须是剪贴板（而不是 CoreGraphics unicode）</h2>
     * <p>见 {@link #unicodeKey(String, String)} 的废弃说明：JXA 桥既没有
     * {@code CGEventSetUnicodeString}，也就无法把任意 Unicode 塞进键盘事件。
     * AppleScript {@code keystroke} 同样只对 ASCII 有效（实测 {@code keystroke "中文"} 什么都不输入）。
     * <br>⇒ 剪贴板 + ⌘V 是 macOS 上<b>唯一</b>能保证任意 Unicode 的路径（实测中文通过）。
     *
     * <h2>WHY 一次调用而不是「写 / 粘贴 / 还原」三次调用</h2>
     * <p>用户剪贴板可能不是纯文本（图片、RTF、HTML、文件引用）。用 {@code pbpaste} 读文本再写回
     * 会把非文本内容<b>整块丢掉</b>。⇒ 本脚本在<b>同一个进程内存里</b>持有
     * {@code NSPasteboardItem} 的<b>全部类型</b>数据（{@code dataForType}），粘贴后按原类型重建。
     * <br>实测：文本 + {@code public.html} 双类型可完整还原。
     *
     * <h2>失败语义（fail loud）</h2>
     * <p>脚本<b>始终</b>尝试还原剪贴板（{@code try/finally} 语义用显式分支实现），
     * 再输出 JSON：{@code {"restored":bool,"pasted":bool,"error":"..."}}。
     * 粘贴失败（如目标不接收 ⌘V）≠ 剪贴板没还原 ⇒ 两个状态分开报。
     *
     * @param text 要粘贴的文本（由调用方用 {@link #quote(String)} 之外的 JSON 转义路径嵌入）
     * @return JXA 脚本文本（输出单行 JSON）
     */
    public static String pasteText(String text) {
        // 用 JSON 字符串字面量嵌入：JSON 的转义集合是 JS 转义集合的子集，天然安全
        String quoted = jsonStringLiteral(text);
        return """
                ObjC.import("AppKit");
                ObjC.import("CoreGraphics");
                function run() {
                  var pb = $.NSPasteboard.generalPasteboard;
                  // ── 1. 快照：每个 item 的 (types, datas) 平行数组（JXA 里 JS 对象不能当 ObjC 容器）──
                  var snapTypes = $.NSMutableArray.array;
                  var snapData  = $.NSMutableArray.array;
                  var items = pb.pasteboardItems;
                  var itemCount = items ? items.count : 0;
                  for (var i = 0; i < itemCount; i++) {
                    var it = items.objectAtIndex(i);
                    var ts = it.types;
                    var types = $.NSMutableArray.array;
                    var datas = $.NSMutableArray.array;
                    if (ts) {
                      for (var j = 0; j < ts.count; j++) {
                        var t = ts.objectAtIndex(j);
                        var d = it.dataForType(t);
                        if (d) { types.addObject(t); datas.addObject(d); }
                      }
                    }
                    snapTypes.addObject(types);
                    snapData.addObject(datas);
                  }
                  var snapCount = snapTypes.count;
                  // ── 2. 写入要粘贴的文本 ──
                  pb.clearContents;
                  pb.setStringForType(OBJC_WRAP_TEXT, $.NSPasteboardTypeString);
                  // ── 3. 合成 ⌘V（keyCode 9 = v）──
                  var vDown = $.CGEventCreateKeyboardEvent(null, 9, true);
                  var vUp   = $.CGEventCreateKeyboardEvent(null, 9, false);
                  $.CGEventSetFlags(vDown, $.kCGEventFlagMaskCommand);
                  $.CGEventSetFlags(vUp, $.kCGEventFlagMaskCommand);
                  $.CGEventPost($.kCGHIDEventTap, vDown);
                  $.NSThread.sleepForTimeInterval(0.01);
                  $.CGEventPost($.kCGHIDEventTap, vUp);
                  // 给目标应用一点时间真正消费粘贴（否则很快还原会与粘贴竞争）
                  $.NSThread.sleepForTimeInterval(0.12);
                  // ── 4. 还原剪贴板（全部类型）──
                  var restored = false;
                  try {
                    pb.clearContents;
                    var outArr = $.NSMutableArray.array;
                    for (var i = 0; i < snapCount; i++) {
                      var item = $.NSPasteboardItem.alloc.init;
                      var ts = snapTypes.objectAtIndex(i);
                      var ds = snapData.objectAtIndex(i);
                      for (var j = 0; j < ts.count; j++) {
                        item.setDataForType(ds.objectAtIndex(j), ts.objectAtIndex(j));
                      }
                      outArr.addObject(item);
                    }
                    restored = (snapCount === 0) ? true : pb.writeObjects(outArr);
                  } catch (e) {
                    restored = false;
                  }
                  return JSON.stringify({restored: restored, pasted: true, snapshotItems: snapCount});
                }
                run();
                """.replace("OBJC_WRAP_TEXT", "ObjC.wrap(" + quoted + ")");
    }

    /**
     * 把 Java 字符串转成 <b>JSON 字符串字面量</b>（含两侧引号）。
     *
     * <p><b>WHY 不用 {@link #quote(String)}</b>：那个方法面向 AppleScript 风格的单引号转义，
     * 而 JXA 是 JavaScript —— 需要的是 JS 字面量。JSON 字符串语法是 JS 字符串语法的子集
     * （引号 / 反斜杠 / 斜杠 / b / f / n / r / t / u 转义完全兼容），所以用 JSON 序列化最安全，
     * 不需要手写转义表（手写必漏 U+2028、U+2029 之类的边界）。
     *
     * @param raw 原串（可含换行、引号、反斜杠、emoji、中文）
     * @return 带两侧双引号的 JSON 字面量
     */
    static String jsonStringLiteral(String raw) {
        String s = raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                // U+2028 / U+2029 在旧 JS 引擎里是行终止符 ⇒ 必须转义
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
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

    /**
     * 运行中应用列表脚本（NSWorkspace 版）· 带 pid 与前台标记。
     *
     * <h2>WHY 不用 {@link #runningApplications()}（System Events 版）</h2>
     * <p>System Events 版需要辅助功能权限，且拿不到 pid 与「谁在前台」。
     * <p>dsh 的 {@code computer_list_apps} 返回 {@code bundleId + pid + name + frontmost}
     * （{@code types.ts:52-56} 的 {@code ComputerAppSummary}）⇒ 需要 pid。
     * <br>NSWorkspace（{@code ObjC.import("AppKit")}）无需辅助功能权限即可枚举，
     * 且 {@code frontmostApplication} 直接给出前台应用 ⇒ 本脚本走 NSWorkspace。
     *
     * @return JXA 脚本文本（输出 JSON 数组）
     */
    public static String runningApplicationsWithPid() {
        return """
                ObjC.import("AppKit");
                var ws = $.NSWorkspace.sharedWorkspace;
                var apps = ws.runningApplications;
                var front = ws.frontmostApplication;
                var frontPid = front ? front.processIdentifier : -1;
                var out = [];
                for (var i = 0; i < apps.count; i++) {
                  try {
                    var a = apps.objectAtIndex(i);
                    var pid = a.processIdentifier;
                    if (pid === 0) continue;
                    var bid = a.bundleIdentifier;
                    var nm = a.localizedName;
                    out.push({
                      bundleId: (bid && !bid.isNil()) ? String(ObjC.unwrap(bid)) : "",
                      displayName: (nm && !nm.isNil()) ? String(ObjC.unwrap(nm)) : "",
                      pid: pid,
                      frontmost: (pid === frontPid),
                      hidden: a.hidden ? true : false
                    });
                  } catch (e) {}
                }
                JSON.stringify(out);
                """;
    }

    // ══════════════════════════════════════════════════════════════════
    // Accessibility（AXUIElement）· observe / click_element / type_into_element 底座
    // ══════════════════════════════════════════════════════════════════

    /**
     * AX 树快照脚本 · 输出<b>扁平记录</b> + 供模型直读的紧凑文本。
     *
     * <h2>为什么扁平（而不是嵌套 JSON）</h2>
     * <p>JXA 里构造几百节点的嵌套对象开销大、且容易踩桥接对象递归的坑；扁平记录只做
     * {@code push}，树结构在 Java 侧按 {@code loc}（子节点索引路径）重建
     * （{@link MacOsUiaService#rebuildTree}）。这也让索引/路径逻辑可纯单测。
     *
     * <h2>脚本内的关键约束（实测得出，改动前务必读）</h2>
     * <ul>
     *   <li>AX 属性名必须 {@code CFStringCreateWithCString}；JS 字符串会得 -25201；</li>
     *   <li>CFArray 必须 {@code ObjC.castRefToObject} 才转成 {@code NSArray}；</li>
     *   <li>⛔ <b>不读 {@code AXPosition}/{@code AXSize}</b> —— 解 {@code AXValue} 会让
     *       osascript 进程崩溃（exit 139）。AX 动作不需要坐标。</li>
     * </ul>
     *
     * @param maxDepth     最大遍历深度
     * @param maxNodes     节点上限
     * @param maxTextBytes 文本字节上限
     * @return JXA 脚本文本（输出 JSON 对象）
     */
    public static String accessibilitySnapshot(int maxDepth, int maxNodes, int maxTextBytes) {
        return axResolvePrelude() + axSignatureWalkerJsFunction() + axHashJsFunction() + """
                var MAX_DEPTH = %d, MAX_NODES = %d, MAX_TEXT_BYTES = %d;
                function run() {
                  var app = $.NSWorkspace.sharedWorkspace.frontmostApplication;
                  var pid = app.processIdentifier;
                  var appEl = $.AXUIElementCreateApplication(pid);
                  var wins = [];
                  var wr = copyAttr(appEl, K.Windows);
                  if (wr !== null) {
                    try {
                      var wns = ObjC.castRefToObject(wr);
                      for (var wi = 0; wi < wns.count; wi++) wins.push(wns.objectAtIndex(wi));
                    } catch (e) {}
                  }
                  var root = wins.length ? wins[0] : appEl;
                  var records = [], queue = [[root, [], 0]], truncated = false;
                  var lines = [], textBytes = 0;
                  while (queue.length) {
                    if (records.length >= MAX_NODES) { truncated = true; break; }
                    var it = queue.shift(), el = it[0], loc = it[1], depth = it[2];
                    var role = str(el, K.Role) || "AXUnknown";
                    var title = str(el, K.Title), desc = str(el, K.Desc), ident = str(el, K.Identifier);
                    var rawValue = copyAttr(el, K.Value), value = null;
                    if (rawValue !== null) {
                      try {
                        var u = ObjC.unwrap(ObjC.castRefToObject(rawValue));
                        if (u !== null && typeof u !== "object") value = String(u);
                      } catch (e) {}
                    }
                    var acts = actionsOf(el), enabled = boolAttr(el, K.Enabled), focused = boolAttr(el, K.Focused);
                    var subrole = str(el, K.Subrole);
                    var idx = records.length;
                    var label = title || desc || "";
                    var rec = {
                      i: idx, loc: loc.join("."), role: String(role), name: label,
                      sid: ident || "", actions: acts,
                      enabled: (enabled === null ? null : enabled)
                    };
                    if (subrole) rec.subrole = subrole;
                    if (value !== null) rec.value = value;
                    if (focused !== null) rec.focused = focused;
                    records.push(rec);
                    var line = "[" + idx + "] " + String(role).replace(/^AX/, "");
                    if (label) line += " \\"" + label.slice(0, 40) + "\\"";
                    if (value) line += " value=\\"" + value.slice(0, 30) + "\\"";
                    if (enabled === false) line += " DISABLED";
                    if (acts.length) line += " (" + acts.join(",") + ")";
                    var size = line.length + 1;
                    if (textBytes + size <= MAX_TEXT_BYTES) { lines.push(line); textBytes += size; }
                    else { truncated = true; }
                    if (depth >= MAX_DEPTH) continue;
                    var kids = childList(el);
                    for (var ci = 0; ci < kids.length; ci++) queue.push([kids[ci], loc.concat([ci]), depth + 1]);
                  }
                  var bid = app.bundleIdentifier;
                  return JSON.stringify({
                    app: { name: String(ObjC.unwrap(app.localizedName)),
                           bid: (bid && !bid.isNil()) ? String(ObjC.unwrap(bid)) : "", pid: pid },
                    window: { title: root ? str(root, K.Title) : null },
                    truncated: truncated, records: records, text: lines.join("\\n"),
                    // ⭐ 状态指纹：与 accessibilityInvoke 用同一函数算同一份签名序列
                    stateHash: axHash(axSignatures(root, MAX_DEPTH))
                  });
                }
                run();
                """.formatted(maxDepth, maxNodes, maxTextBytes);
    }

    /**
     * 按 <b>locator 路径</b>反查元素并执行动作的公共前缀。
     *
     * <p>返回值由调用方拼接到 {@code var el = resolve(...)} 之后使用。
     * <p>⛔ 与快照脚本同一批约束：属性名用 CFString、CFArray 用 {@code castRefToObject}、
     * 不碰 {@code AXPosition}/{@code AXSize}。
     */
    private static String axResolvePrelude() {
        return """
                ObjC.import("AppKit");
                ObjC.import("ApplicationServices");
                function cf(s) { return ObjC.unwrap($.CFStringCreateWithCString(null, s, 0x08000100)); }
                var K = { Role: cf("AXRole"), Title: cf("AXTitle"), Desc: cf("AXDescription"),
                          Children: cf("AXChildren"), Windows: cf("AXWindows"), Value: cf("AXValue"),
                          SelectedText: cf("AXSelectedText"), Identifier: cf("AXIdentifier"),
                          Enabled: cf("AXEnabled"), Focused: cf("AXFocused"), Subrole: cf("AXSubrole") };
                function copyAttr(el, attr) {
                  var out = Ref();
                  var e = $.AXUIElementCopyAttributeValue(el, attr, out);
                  return (e === 0) ? out[0] : null;
                }
                function str(el, attr) {
                  var v = copyAttr(el, attr);
                  if (v === null) return null;
                  try {
                    var x = ObjC.unwrap(ObjC.castRefToObject(v));
                    return (x === null) ? null : String(x);
                  } catch (e) { return null; }
                }
                function boolAttr(el, attr) {
                  var v = copyAttr(el, attr);
                  if (v === null) return null;
                  try {
                    var x = ObjC.unwrap(ObjC.castRefToObject(v));
                    return (x === null) ? null : !!x;
                  } catch (e) { return null; }
                }
                function childList(el) {
                  var v = copyAttr(el, K.Children);
                  if (v === null) return [];
                  try {
                    var ns = ObjC.castRefToObject(v);
                    if (!ns) return [];
                    var out = [];
                    for (var i = 0; i < ns.count; i++) out.push(ns.objectAtIndex(i));
                    return out;
                  } catch (e) { return []; }
                }
                function actionsOf(el) {
                  var r = Ref();
                  var e = $.AXUIElementCopyActionNames(el, r);
                  if (e !== 0) return [];
                  try {
                    var ns = ObjC.castRefToObject(r[0]);
                    if (!ns || !ns.count) return [];
                    var out = [];
                    for (var i = 0; i < ns.count; i++) out.push(String(ObjC.unwrap(ns.objectAtIndex(i))));
                    return out;
                  } catch (x) { return []; }
                }
                function frontWindow() {
                  var appEl = $.AXUIElementCreateApplication(
                      $.NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier);
                  var wr = copyAttr(appEl, K.Windows);
                  if (wr === null) return appEl;
                  try {
                    var ns = ObjC.castRefToObject(wr);
                    return ns.count ? ns.objectAtIndex(0) : appEl;
                  } catch (e) { return appEl; }
                }
                function resolve(root, loc) {
                  if (!loc) return root;
                  var parts = String(loc).split(".");
                  var cur = root;
                  for (var i = 0; i < parts.length; i++) {
                    var idx = parseInt(parts[i], 10);
                    var kids = childList(cur);
                    if (isNaN(idx) || idx < 0 || idx >= kids.length) return null;
                    cur = kids[idx];
                  }
                  return cur;
                }
                """;
    }

    /**
     * {@code AXPress} 脚本 · 按 locator 反查后执行。
     *
     * <p>语义（对齐 dsh 的 {@code pressWithDescendantFallback} 的<b>保守版</b>）：
     * 元素<b>必须</b>声明了 {@code AXPress} 才执行 —— 不再下钻子节点（下钻会点错目标）。
     *
     * @param locator 子节点索引路径（如 {@code "0.2.1"}）
     * @param expectedStateHash 期望的整树状态哈希（空 = 不校验）；
     *                          <b>非空时脚本先重算当前树哈希，不一致即拒绝</b>
     *                          （dsh {@code expectedStateHash} 的 fail-closed 语义：
     *                          界面已经变了 ⇒ 按旧 locator 点会打到别的控件上）
     * @return JXA 脚本文本（输出 {@code {found, role, name, actions, ok, err, message}}）
     */
    public static String accessibilityInvoke(String locator, String expectedStateHash) {
        // ⛔ 三个片段缺一不可：prelude(AX 读写) + 签名遍历 + 哈希函数
        //   （observe 侧同样三件套算 stateHash；漏掉 axHashJsFunction 会导致
        //    "Can't find variable: axHash"，陈旧校验整段失效 —— 2026-11-02 真机实测）
        return axResolvePrelude() + axSignatureWalkerJsFunction() + axHashJsFunction() + """
                var LOCATOR = %s;
                var EXPECTED_HASH = %s;
                var win = frontWindow();
                var el = resolve(win, LOCATOR);
                var out = { locator: LOCATOR, found: el !== null };
                if (el && EXPECTED_HASH) {
                  // ── 陈旧校验（dsh expectedStateHash）：树变了就不动手 ──
                  var nowHash = %s(axSignatures(win, 14));
                  out.stateMatched = (nowHash === EXPECTED_HASH);
                  if (!out.stateMatched) {
                    out.ok = false; out.err = -25209;
                    out.message = "the accessibility tree changed since the observation (expected "
                        + EXPECTED_HASH + ", now " + nowHash
                        + "). Call observe again and use the new observationId.";
                  }
                }
                // ⛔ 不能用 `return;` 提前退出：osascript 的顶层代码里 return 非法
                //   （实测会抛 SyntaxError: Return statements are only valid inside functions，
                //   导致整条点击路径必然失败，而异常文案看起来像别的问题）。
                if (out.stateMatched === false) {
                  // 陈旧 ⇒ 已在上面写好错误，跳过动作
                } else if (el) {
                  out.role = str(el, K.Role) || "";
                  out.name = str(el, K.Title) || str(el, K.Desc) || "";
                  var acts = actionsOf(el);
                  out.actions = acts;
                  if (acts.indexOf("AXPress") < 0) {
                    out.ok = false; out.err = -25207;
                    out.message = "element does not support AXPress (actions: " + acts.join(",") + ")";
                  } else {
                    var err = $.AXUIElementPerformAction(el, cf("AXPress"));
                    out.err = err;
                    out.ok = (err === 0);
                    if (err !== 0) out.message = "AXPress returned " + err;
                  }
                } else {
                  out.ok = false; out.err = -25208;
                  out.message = "locator is not in the current AX tree (the UI changed — observe again)";
                }
                JSON.stringify(out);
                """.formatted(jsonStringLiteral(locator),
                jsonStringLiteral(expectedStateHash == null ? "" : expectedStateHash),
                "axHash");
    }

    /**
     * 按<b>动作名</b>执行 AX 动作的脚本（对齐 dsh {@code computer_perform_action}）。
     *
     * <h2>语义（逐条对齐 dsh {@code main.swift:1221-1247}）</h2>
     * <ol>
     *   <li>目标元素必须存在（否则 {@code COMPUTER_ELEMENT_UNAVAILABLE}）；</li>
     *   <li><b>动作名必须在该元素声明的 {@code actions} 里</b> ——
     *       dsh 原文：{@code guard available.contains(actionName)}，
     *       否则报 "the element did not advertise the requested Accessibility action"
     *       ⇒ <b>不盲目尝试任意动作名</b>（AX 会返回 -25207 但那不是有意义的拒绝）；</li>
     *   <li>执行失败 ⇒ {@code COMPUTER_ACTION_BLOCKED}。</li>
     * </ol>
     *
     * @param locator 子节点索引路径
     * @param action  动作名（如 {@code AXPress} / {@code AXExpand} / {@code AXShowMenu}）
     * @return JXA 脚本文本（输出 {@code {found, ok, err, available, message}}）
     */
    public static String accessibilityPerformAction(String locator, String action) {
        return axResolvePrelude() + """
                var LOCATOR = %s;
                var ACTION = %s;
                var el = resolve(frontWindow(), LOCATOR);
                var out = { locator: LOCATOR, action: ACTION, found: el !== null };
                if (!el) {
                  out.ok = false; out.err = -25208;
                  out.message = "locator is not in the current AX tree (the UI changed — observe again)";
                  JSON.stringify(out);
                } else {
                  var acts = actionsOf(el);
                  out.available = acts;
                  if (acts.indexOf(ACTION) < 0) {
                    out.ok = false; out.err = -25207;
                    out.message = "the element did not advertise the requested Accessibility action"
                        + " (available: " + (acts.length ? acts.join(",") : "none") + ")";
                    JSON.stringify(out);
                  } else {
                    var err = $.AXUIElementPerformAction(el, cf(ACTION));
                    out.err = err;
                    out.ok = (err === 0);
                    if (err !== 0) out.message = "Accessibility action was rejected (" + err + ")";
                    JSON.stringify(out);
                  }
                }
                """.formatted(jsonStringLiteral(locator), jsonStringLiteral(action));
    }

    /**
     * 设值脚本 · 先试 {@code AXValue}，不可写则回落 {@code AXSelectedText}。
     *
     * <p><b>WHY 要先问 settable</b>：直接 {@code SetAttributeValue} 到不可写属性会返回
     * 非 0，但<b>有些控件返回 0 却不生效</b>（写进去又弹回）⇒ 写完必须<b>读回校验</b>。
     *
     * @param locator 子节点索引路径
     * @param value   要写入的值
     * @return JXA 脚本文本（输出 {@code {found, ok, via, err, before, after}}）
     */
    public static String accessibilitySetValue(String locator, String value) {
        return axResolvePrelude() + """
                var LOCATOR = %s;
                var VALUE = %s;
                var el = resolve(frontWindow(), LOCATOR);
                var out = { locator: LOCATOR, found: el !== null };
                if (!el) {
                  out.ok = false; out.err = -25208;
                  out.message = "locator 在当前 AX 树中不存在（界面已变化，请重新 observe）";
                } else {
                  out.role = str(el, K.Role) || "";
                  out.name = str(el, K.Title) || str(el, K.Desc) || "";
                  out.before = str(el, K.Value);
                  // 1) AXValue（原生输入框 / 组合框）
                  var settable = Ref();
                  var probe = $.AXUIElementIsAttributeSettable(el, K.Value, settable);
                  var canValue = (probe === 0) && !!ObjC.unwrap(settable[0]);
                  if (canValue) {
                    var e1 = $.AXUIElementSetAttributeValue(el, K.Value, ObjC.wrap(VALUE));
                    var after1 = str(el, K.Value);
                    if (e1 === 0 && after1 === VALUE) {
                      out.ok = true; out.via = "AXValue"; out.err = 0; out.after = after1;
                    } else {
                      out.err = e1; out.after = after1;
                    }
                  }
                  // 2) 回落 AXSelectedText（富文本视图）
                  if (out.ok !== true) {
                    var settable2 = Ref();
                    var probe2 = $.AXUIElementIsAttributeSettable(el, K.SelectedText, settable2);
                    if (probe2 === 0 && !!ObjC.unwrap(settable2[0])) {
                      var e2 = $.AXUIElementSetAttributeValue(el, K.SelectedText, ObjC.wrap(VALUE));
                      var after2 = str(el, K.Value);
                      if (e2 === 0 && after2 === VALUE) {
                        out.ok = true; out.via = "AXSelectedText"; out.err = 0; out.after = after2;
                      } else {
                        out.err = e2; out.after = after2;
                      }
                    }
                  }
                  if (out.ok !== true) {
                    out.ok = false;
                    out.message = "元素不接受 AXValue / AXSelectedText 写入（role="
                        + out.role + "，请改用 click_element 聚焦后用 type）";
                  }
                }
                JSON.stringify(out);
                """.formatted(jsonStringLiteral(locator), jsonStringLiteral(value));
    }

    /**
     * 整树<b>状态指纹</b>（JXA 侧内联实现 · observe 与 act 两端<b>同一个函数</b>）。
     *
     * <h2>WHY 不用 SHA-256 / 不用 Java 侧算</h2>
     * <p>指纹必须能跨「observe 那次 JXA 调用」与「act 那次 JXA 调用」比较。
     * 若一端用 Java 的 SHA-256、另一端用 JS，算法/编码必然对不齐 ⇒ 永远判「陈旧」。
     * <br>⇒ 统一在 JXA 里算：FNV-1a 32 位 + 长度。**不需要密码学强度**，
     * 只需要「树变了能察觉」（dsh 用 SHA-256 是因为它的 backend 是同一个 Node 进程）。
     *
     * <p>输入 = 每个元素的签名串按先序拼接：
     * {@code role|name|value|actions(逗号)}，用 {@code \n} 连接。
     * 两端都用 {@link #axSignatureWalkerJsFunction()} 生成这个序列 ⇒ 天然同构。
     *
     * @return JXA 函数表达式
     */
    static String axHashJsFunction() {
        return """
                function axHash(sigLines) {
                  var s = sigLines.join("\\n");
                  var h = 0x811c9dc5;
                  for (var i = 0; i < s.length; i++) {
                    h ^= s.charCodeAt(i);
                    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
                  }
                  return (h >>> 0).toString(16) + ":" + s.length;
                }
                """;
    }

    /**
     * 元素签名遍历器（observe / act <b>两端共用</b>，保证指纹同构）。
     *
     * <p>方向固定为先序、深度上限 14、节点上限 500 —— 与
     * {@link #accessibilitySnapshot(int, int, int)} 的默认值一致。
     * <p>⚠️ 签名只取「语义字段」（role/name/value/actions），<b>不取坐标</b>
     * （macOS 本来就没坐标）也不取 index（index 随树序变化，属派生量）。
     *
     * @return JXA 函数表达式（入参 = 树根，返回签名串数组）
     */
    static String axSignatureWalkerJsFunction() {
        return """
                function axSignatures(root, maxDepth) {
                  var out = [], queue = [[root, 0]], guard = 0;
                  while (queue.length && guard < 2000) {
                    guard++;
                    var it = queue.shift(), el = it[0], depth = it[1];
                    var role = (str(el, K.Role) || "AXUnknown").replace(/^AX/, "");
                    var title = str(el, K.Title), desc = str(el, K.Desc) || "";
                    var rawValue = copyAttr(el, K.Value), value = "";
                    if (rawValue !== null) {
                      try {
                        var u = ObjC.unwrap(ObjC.castRefToObject(rawValue));
                        if (u !== null && typeof u !== "object") value = String(u);
                      } catch (e) {}
                    }
                    out.push(role + "|" + (title || desc) + "|" + value + "|" + actionsOf(el).join(","));
                    if (depth < maxDepth) {
                      var kids = childList(el);
                      for (var ci = 0; ci < kids.length; ci++) queue.push([kids[ci], depth + 1]);
                    }
                  }
                  return out;
                }
                """;
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
