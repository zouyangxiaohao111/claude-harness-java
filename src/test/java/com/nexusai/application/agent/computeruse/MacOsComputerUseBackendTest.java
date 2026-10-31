package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * macOS 执行端单测（<b>假进程执行器 · 零真实命令</b>）· computer-use。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li>macOS 执行端全部走<b>进程外</b> {@code osascript -l JavaScript -e '…'} 与
 *       {@code screencapture -x} ⇒ 若不抽象出「执行外部命令」，单测就会在
 *       <b>Windows 开发机</b>上真去调 {@code osascript}（不存在 ⇒ 必红）
 *       ⇒ 必须用假执行器断言「<b>生成的命令/脚本</b>对不对」。</li>
 *   <li>断言的<b>意图</b>是：生成的 JXA 脚本里<b>真的调用了正确的 CoreGraphics 原语</b>
 *       （{@code CGEventCreateMouseEvent} / {@code kCGHIDEventTap} / …）且<b>参数正确</b>
 *       —— 这决定了在真 macOS 上事件能否落到目标位置。</li>
 *   <li>平台分派必须<b>明确失败</b>（Linux 上抛不支持）而不是静默降级
 *       —— 静默降级 = 模型拿到一堆「有工具但用不了」的能力。</li>
 *   <li>TCC 未授权必须<b>明确报错</b>：辅助功能未授权 ⇒ 注入静默失效；
 *       屏幕录制未授权 ⇒ 截图<b>拍到壁纸</b>（不是黑屏！）⇒ 不报错等于给模型一张假图。</li>
 * </ul>
 *
 * <p>⚠️ <b>本测试类只验证「命令/脚本的生成与解析」</b>；
 * <b>真实 macOS 上的运行时行为（osascript / screencapture / TCC / CoreGraphics）
 * 未在任何机器上验证过</b>（开发机是 Windows）⇒ 需一台 macOS 机器实测。
 */
class MacOsComputerUseBackendTest {

    /** 固定的显示器列表 JSON（喂给 JXA 解析用）。 */
    private static final String DISPLAY_JSON =
            "[{\"displayId\":1,\"pixelWidth\":2880,\"pixelHeight\":1800,"
                    + "\"pointWidth\":1440,\"pointHeight\":900,"
                    + "\"originX\":0,\"originY\":0,\"pixelsPerPoint\":2,\"isPrimary\":true}]";

    /** 固定的前台应用 JSON。 */
    private static final String FRONTMOST_JSON =
            "{\"bundleId\":\"com.google.Chrome\",\"displayName\":\"Google Chrome\"}";

    // ══════════════════════════════════════════════════════════════════
    // 假进程执行器
    // ══════════════════════════════════════════════════════════════════

    /** 记录型假执行器 · 按脚本内容分派返回值。 */
    private static final class FakeRunner implements ComputerUseProcessRunner {

        private final List<List<String>> commands = new ArrayList<>();
        private final Function<List<String>, Result> handler;
        private String lastStdin;

        FakeRunner(Function<List<String>, Result> handler) {
            this.handler = handler;
        }

        @Override
        public Result run(List<String> command) {
            commands.add(List.copyOf(command));
            return handler.apply(command);
        }

        @Override
        public Result runWithStdin(List<String> command, String stdin) {
            commands.add(List.copyOf(command));
            this.lastStdin = stdin;
            return handler.apply(command);
        }

        /** 全部命令里的 JXA 脚本（{@code -e} 之后的参数）。 */
        List<String> scripts() {
            List<String> out = new ArrayList<>();
            for (List<String> cmd : commands) {
                int idx = cmd.indexOf("-e");
                if (idx >= 0 && idx + 1 < cmd.size()) {
                    out.add(cmd.get(idx + 1));
                }
            }
            return out;
        }

        /** 含指定片段的脚本。 */
        List<String> scriptsContaining(String needle) {
            List<String> out = new ArrayList<>();
            for (String script : scripts()) {
                if (script.contains(needle)) {
                    out.add(script);
                }
            }
            return out;
        }
    }

    /** 默认分派：TCC 探针 / 显示器 / 前台应用 / 光标位置 各回固定值。 */
    private static FakeRunner defaultRunner() {
        return new FakeRunner(cmd -> {
            String script = scriptOf(cmd);
            if (script.contains("AXIsProcessTrusted")) {
                return ok("true");
            }
            if (script.contains("CGPreflightScreenCaptureAccess")) {
                return ok("true");
            }
            if (script.contains("CGGetActiveDisplayList")) {
                return ok(DISPLAY_JSON);
            }
            if (script.contains("NSWorkspace")) {
                return ok(FRONTMOST_JSON);
            }
            if (script.contains("CGEventGetLocation")) {
                return ok("300,150");
            }
            return ok("");
        });
    }

    /** 取出全部 {@code screencapture} 命令。 */
    private static List<List<String>> screencaptureCommands(FakeRunner runner) {
        return runner.commands.stream()
                .filter(c -> !c.isEmpty() && "screencapture".equals(c.get(0)))
                .toList();
    }

    /** 取命令里的 JXA 脚本（无则空串）。 */
    private static String scriptOf(List<String> cmd) {
        int idx = cmd.indexOf("-e");
        return (idx >= 0 && idx + 1 < cmd.size()) ? cmd.get(idx + 1) : "";
    }

    private static ComputerUseProcessRunner.Result ok(String stdout) {
        return new ComputerUseProcessRunner.Result(0, stdout, "");
    }

    /** 造一套 macOS 依赖（假执行器 + 假显示器 + 假 TCC）。 */
    private static MacOsDisplays displays(FakeRunner runner) {
        return new MacOsDisplays(runner);
    }

    private static MacOsTccChecker tcc(FakeRunner runner) {
        return new MacOsTccChecker(runner);
    }

    // ══════════════════════════════════════════════════════════════════
    // 注入类 · JXA 脚本内容
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("鼠标移动生成的 JXA 脚本含正确的 CoreGraphics 调用与参数（含像素→点换算）")
    void mouseMoveScriptHasCoreGraphicsCalls() throws Exception {
        FakeRunner runner = defaultRunner();
        MacOsMouse mouse = new MacOsMouse(runner, displays(runner), tcc(runner));

        mouse.moveAndSettle(100, 50);

        List<String> moved = runner.scriptsContaining("kCGEventMouseMoved");
        assertThat(moved).hasSize(1);
        String script = moved.get(0);
        assertThat(script)
                .contains("ObjC.import(\"CoreGraphics\")")
                // CCB input/backends/darwin.ts:82 的脚本形状
                .contains("var p = $.CGPointMake(50,25)")   // 像素 (100,50) ÷ pixelsPerPoint(2) = 点
                .contains("$.CGEventCreateMouseEvent(null, $.kCGEventMouseMoved, p, 0)")
                .contains("$.CGEventPost($.kCGHIDEventTap, e)");
    }

    @Test
    @DisplayName("左键点击派发 down + up 两个事件，且带 kCGMouseEventClickState（CCB 的 click 语义）")
    void leftClickDispatchesDownAndUp() throws Exception {
        FakeRunner runner = defaultRunner();
        MacOsMouse mouse = new MacOsMouse(runner, displays(runner), tcc(runner));

        mouse.leftClick(200, 100);

        List<String> down = runner.scriptsContaining("kCGEventLeftMouseDown");
        List<String> up = runner.scriptsContaining("kCGEventLeftMouseUp");
        assertThat(down).hasSize(1);
        assertThat(up).hasSize(1);
        assertThat(down.get(0))
                .contains("$.CGEventCreateMouseEvent(null, $.kCGEventLeftMouseDown, p, 0)")
                .contains("$.CGEventSetIntegerValueField(e, $.kCGMouseEventClickState, 1)")
                .contains("$.CGEventPost($.kCGHIDEventTap, e)");
        assertThat(up.get(0)).contains("$.kCGEventLeftMouseUp");
    }

    @Test
    @DisplayName("滚轮：垂直走 wheelCount=1、水平走 wheelCount=2（CCB 的两段脚本形状）")
    void scrollUsesTwoWheelShapes() throws Exception {
        FakeRunner runner = defaultRunner();
        MacOsMouseActions actions = new MacOsMouseActions(runner, displays(runner), tcc(runner));

        actions.scroll(3, -5);

        List<String> scripts = runner.scriptsContaining("CGEventCreateScrollWheelEvent");
        assertThat(scripts).hasSize(2);
        // CCB input/backends/darwin.ts:176-177 —— 垂直先、水平后
        assertThat(scripts.get(0)).contains("CGEventCreateScrollWheelEvent(null, 0, 1, -5)");
        assertThat(scripts.get(1)).contains("CGEventCreateScrollWheelEvent(null, 0, 2, 0, 3)");
    }

    @Test
    @DisplayName("键盘：修饰键状态机 ⇒ 主键事件带上当前按住的修饰键 flags")
    void keyboardTracksHeldModifiers() throws Exception {
        FakeRunner runner = defaultRunner();
        MacOsKeyboard keyboard = new MacOsKeyboard(runner, tcc(runner));

        // 本仓的组合键事件序列（ComputerUseKeyCombo.parse）：ctrl down → a down → a up → ctrl up
        keyboard.sendKeyEvent(ComputerUseKeyEvent.down("ctrl", 0x11));
        keyboard.sendKeyEvent(ComputerUseKeyEvent.down("a", 0x41));
        keyboard.sendKeyEvent(ComputerUseKeyEvent.up("a", 0x41));
        keyboard.sendKeyEvent(ComputerUseKeyEvent.up("ctrl", 0x11));

        List<String> scripts = runner.scriptsContaining("CGEventCreateKeyboardEvent");
        assertThat(scripts).hasSize(4);
        // ctrl 按下 ⇒ 带 control flag
        assertThat(scripts.get(0))
                .contains("$.CGEventCreateKeyboardEvent(null, 59, true)")
                .contains("$.CGEventSetFlags(e, $.kCGEventFlagMaskControl)");
        // 主键 a ⇒ 带 control flag（修饰键仍按住）
        assertThat(scripts.get(1))
                .contains("$.CGEventCreateKeyboardEvent(null, 0, true)")
                .contains("$.CGEventSetFlags(e, $.kCGEventFlagMaskControl)");
        // ctrl 抬起 ⇒ 不再带自己的 flag
        assertThat(scripts.get(3))
                .contains("$.CGEventCreateKeyboardEvent(null, 59, false)")
                .doesNotContain("kCGEventFlagMaskControl");
        assertThat(keyboard.heldModifiers()).isEmpty();
    }

    @Test
    @DisplayName("文本注入走 CoreGraphics Unicode 通道（CGEventSetUnicodeString），无特殊键语义")
    void typeCharUsesUnicodeChannel() throws Exception {
        FakeRunner runner = defaultRunner();
        MacOsKeyboard keyboard = new MacOsKeyboard(runner, tcc(runner));

        keyboard.typeChar('+');

        List<String> scripts = runner.scriptsContaining("CGEventSetUnicodeString");
        assertThat(scripts).hasSize(1);
        // '+' 在 AppleScript keystroke 里无特殊含义，但本仓走 Unicode 直投 ⇒ 更稳
        assertThat(scripts.get(0))
                .contains("$.CGEventCreateKeyboardEvent(null, 0, true)")
                .contains("$.CGEventSetUnicodeString(d, '+')")
                .contains("$.CGEventPost($.kCGHIDEventTap, d)");
    }

    // ══════════════════════════════════════════════════════════════════
    // 截图
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("整屏截图命令 = screencapture -x（CCB 的 macOS 路线），输出按 ≤1568 缩放")
    void screenshotCommandIsScreencaptureDashX() throws Exception {
        FakeRunner runner = new FakeRunner(cmd -> {
            if ("screencapture".equals(cmd.get(0))) {
                writePng(new File(cmd.get(cmd.size() - 1)), 800, 600);
            }
            return defaultRunner().handler.apply(cmd);
        });
        MacOsScreenCapturer capturer =
                new MacOsScreenCapturer(runner, displays(runner), tcc(runner));

        ComputerUseCaptureResult result = capturer.capture();

        // 命令形状：screencapture -x <tmp.png>
        List<List<String>> shot = screencaptureCommands(runner);
        assertThat(shot).hasSize(1);
        assertThat(shot.get(0).get(1)).isEqualTo("-x");
        // 显示尺寸 = 抓到的原始像素；图像尺寸 = 按 ComputerUseApiResize 缩放后的尺寸
        assertThat(result.displayWidth()).isEqualTo(800);
        assertThat(result.displayHeight()).isEqualTo(600);
        assertThat(result.imageWidth()).isEqualTo(800);
        assertThat(result.imageHeight()).isEqualTo(600);
        assertThat(result.mediaType()).isEqualTo("image/jpeg");
        assertThat(result.scaleFactor()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("区域截图命令 = screencapture -x -R x,y,w,h（真按区域截 · CCB macOS 语义）")
    void regionScreenshotUsesRectFlag() throws Exception {
        FakeRunner runner = new FakeRunner(cmd -> {
            if ("screencapture".equals(cmd.get(0))) {
                writePng(new File(cmd.get(cmd.size() - 1)), 200, 100);
            }
            return defaultRunner().handler.apply(cmd);
        });
        MacOsScreenCapturer capturer =
                new MacOsScreenCapturer(runner, displays(runner), tcc(runner));

        capturer.captureRegion(400, 200, 200, 100);

        List<List<String>> shot = screencaptureCommands(runner);
        assertThat(shot).hasSize(1);
        assertThat(shot.get(0)).contains("-x");
        assertThat(shot.get(0)).contains("-R");
        // 像素 (400,200,200,100) ÷ pixelsPerPoint(2) = 点 (200,100,100,50)
        assertThat(shot.get(0)).contains("200,100,100,50");
    }

    // ══════════════════════════════════════════════════════════════════
    // 解析
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("显示器几何：喂固定 JSON ⇒ 解析出像素宽高 / 点宽高 / 缩放比 / 主屏标记")
    void displayGeometryIsParsed() {
        List<MacOsDisplays.Info> parsed = MacOsDisplays.parse(DISPLAY_JSON);
        assertThat(parsed).hasSize(1);
        MacOsDisplays.Info info = parsed.get(0);
        assertThat(info.pixelWidth()).isEqualTo(2880);
        assertThat(info.pixelHeight()).isEqualTo(1800);
        assertThat(info.pointWidth()).isEqualTo(1440);
        assertThat(info.pixelsPerPoint()).isEqualTo(2.0d);
        assertThat(info.primary()).isTrue();
        assertThat(info.displayId()).isEqualTo(1);
    }

    @Test
    @DisplayName("前台应用：喂固定 JSON ⇒ 解析出归一后的授权键（与授权表同命名空间）")
    void frontmostAppIsParsed() {
        FakeRunner runner = defaultRunner();
        MacOsForegroundAppResolver resolver = new MacOsForegroundAppResolver(runner);

        assertThat(resolver.currentAppId()).isEqualTo("google chrome");
    }

    @Test
    @DisplayName("应用枚举（运行中）：喂固定 JSON ⇒ 解析出 bundleId 与显示名")
    void runningAppsAreParsed() {
        String json = "[{\"bundleId\":\"com.google.Chrome\",\"displayName\":\"Google Chrome\"},"
                + "{\"bundleId\":\"com.apple.Terminal\",\"displayName\":\"Terminal\"}]";

        List<MacOsAppCatalog.AppInfo> parsed = MacOsAppCatalog.parseRunning(json);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).bundleId()).isEqualTo("com.google.Chrome");
        assertThat(parsed.get(0).displayName()).isEqualTo("Google Chrome");
        assertThat(parsed.get(1).bundleId()).isEqualTo("com.apple.Terminal");
    }

    @Test
    @DisplayName("应用枚举（已安装）：喂 path|name|bundleId 行 ⇒ 解析并按 bundleId 去重、丢弃空 bundleId")
    void installedAppsAreParsed() {
        String lines = String.join("\n",
                "/Applications/Google Chrome.app|Google Chrome|com.google.Chrome",
                "/Applications/Safari.app|Safari|com.apple.Safari",
                "/Applications/Broken.app|Broken|(null)");

        List<MacOsAppCatalog.AppInfo> parsed = MacOsAppCatalog.parseInstalled(lines);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).path()).isEqualTo("/Applications/Google Chrome.app");
        assertThat(parsed.get(0).bundleId()).isEqualTo("com.google.Chrome");
        assertThat(parsed.get(1).displayName()).isEqualTo("Safari");
    }

    @Test
    @DisplayName("应用枚举：运行中走 JXA（System Events），已安装走 bash + mdls")
    void appCatalogUsesCcbRoutes() {
        FakeRunner runner = new FakeRunner(cmd -> {
            if (cmd.contains("bash")) {
                return ok("/Applications/Safari.app|Safari|com.apple.Safari");
            }
            if (scriptOf(cmd).contains("applicationProcesses")) {
                return ok("[{\"bundleId\":\"com.apple.Safari\",\"displayName\":\"Safari\"}]");
            }
            return ok("");
        });
        MacOsAppCatalog catalog = new MacOsAppCatalog(runner);

        assertThat(catalog.listRunning()).hasSize(1);
        assertThat(catalog.listInstalled()).hasSize(1);

        assertThat(runner.commands.stream()
                .anyMatch(c -> c.contains("bash")
                        && c.stream().anyMatch(arg -> arg.contains("mdls"))))
                .isTrue();
        assertThat(runner.scriptsContaining("applicationProcesses")).hasSize(1);
    }

    @Test
    @DisplayName("光标位置：Quartz 点 → 屏幕像素（÷/× pixelsPerPoint）")
    void cursorLocationIsConvertedToPixels() throws Exception {
        FakeRunner runner = defaultRunner();
        MacOsCursor cursor = new MacOsCursor(runner, displays(runner), tcc(runner));

        int[] pos = cursor.location();

        // JXA 回 "300,150"（点）；pixelsPerPoint=2 ⇒ 像素 (600,300)
        assertThat(pos).containsExactly(600, 300);
    }

    // ══════════════════════════════════════════════════════════════════
    // 平台分派
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("平台分派：Windows ⇒ 选 Win32 实现")
    void platformDispatchPicksWindows() {
        assertThat(ComputerUsePlatform.detect("Windows 11")).isEqualTo(ComputerUsePlatform.Kind.WINDOWS);
        ComputerUsePlatform platform = ComputerUsePlatform.of(ComputerUsePlatform.Kind.WINDOWS);
        assertThat(platform.mouse()).isInstanceOf(Win32Mouse.class);
        assertThat(platform.keyboard()).isInstanceOf(Win32Keyboard.class);
        assertThat(platform.screenCapturer()).isInstanceOf(Win32ScreenCapturer.class);
        // Windows 上 11 个 Windows 专属工具照旧注册
        assertThat(platform.supportsWindowsOnlyTools()).isTrue();
    }

    @Test
    @DisplayName("平台分派：macOS ⇒ 选 MacOs 实现，且不注册 Windows 专属工具")
    void platformDispatchPicksMacOs() {
        assertThat(ComputerUsePlatform.detect("Mac OS X")).isEqualTo(ComputerUsePlatform.Kind.MACOS);
        assertThat(ComputerUsePlatform.detect("Darwin")).isEqualTo(ComputerUsePlatform.Kind.MACOS);
        ComputerUsePlatform platform = ComputerUsePlatform.of(ComputerUsePlatform.Kind.MACOS);
        assertThat(platform.mouse()).isInstanceOf(MacOsMouse.class);
        assertThat(platform.keyboard()).isInstanceOf(MacOsKeyboard.class);
        assertThat(platform.screenCapturer()).isInstanceOf(MacOsScreenCapturer.class);
        assertThat(platform.cursor()).isInstanceOf(MacOsCursor.class);
        assertThat(platform.foregroundAppResolver()).isInstanceOf(MacOsForegroundAppResolver.class);
        // 与 CCB 一致：macOS 上没有那 11 个 Windows 专属工具
        assertThat(platform.supportsWindowsOnlyTools()).isFalse();
    }

    @Test
    @DisplayName("平台分派：Linux 等不支持的平台 ⇒ 明确抛不支持（不静默降级）")
    void platformDispatchThrowsOnUnsupportedOs() {
        assertThat(ComputerUsePlatform.detect("Linux")).isEqualTo(ComputerUsePlatform.Kind.UNSUPPORTED);

        assertThatThrownBy(() -> ComputerUsePlatform.of(ComputerUsePlatform.Kind.UNSUPPORTED))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("仅支持 Windows 与 macOS");
    }

    // ══════════════════════════════════════════════════════════════════
    // TCC
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TCC 未授权（辅助功能）⇒ 明确报错，不静默继续")
    void tccUnauthorizedAccessibilityFailsLoud() {
        FakeRunner runner = new FakeRunner(cmd -> {
            String script = scriptOf(cmd);
            if (script.contains("AXIsProcessTrusted")) {
                return ok("false");
            }
            return defaultRunner().handler.apply(cmd);
        });
        MacOsMouse mouse = new MacOsMouse(runner, displays(runner), tcc(runner));

        assertThatThrownBy(() -> mouse.leftClick(10, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("辅助功能")
                .hasMessageContaining("系统设置");
        // 未授权 ⇒ 不该派发任何鼠标事件
        assertThat(runner.scriptsContaining("CGEventCreateMouseEvent")).isEmpty();
    }

    @Test
    @DisplayName("TCC 未授权（屏幕录制）⇒ 明确报错（不拍到壁纸给模型）")
    void tccUnauthorizedScreenRecordingFailsLoud() {
        FakeRunner runner = new FakeRunner(cmd -> {
            String script = scriptOf(cmd);
            if (script.contains("CGPreflightScreenCaptureAccess")) {
                return ok("false");
            }
            return defaultRunner().handler.apply(cmd);
        });
        MacOsScreenCapturer capturer =
                new MacOsScreenCapturer(runner, displays(runner), tcc(runner));

        assertThatThrownBy(capturer::capture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("屏幕录制")
                .hasMessageContaining("系统设置");
        assertThat(runner.commands.stream().filter(c -> "screencapture".equals(c.get(0)))).isEmpty();
    }

    @Test
    @DisplayName("TCC 已授权 ⇒ 放行（辅助功能与屏幕录制都 true 时不报错）")
    void tccGrantedPasses() {
        FakeRunner runner = defaultRunner();
        MacOsTccChecker checker = tcc(runner);

        assertThat(checker.accessibilityGranted()).isTrue();
        assertThat(checker.screenRecordingGranted()).isTrue();
        assertThat(checker.status().granted()).isTrue();
        checker.requireAccessibility();
        checker.requireScreenRecording();
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /** 写一张纯色 PNG（模拟 screencapture 的产物）。 */
    private static void writePng(File file, int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, w, h);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "png", file);
        } catch (Exception e) {
            throw new IllegalStateException("测试写 PNG 失败", e);
        }
    }
}
