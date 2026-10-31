package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * computer-use <b>平台分派</b> · 按操作系统选择「执行端」实现。
 *
 * <h2>分派规则</h2>
 * <table border="1">
 *   <caption>平台 → 实现</caption>
 *   <tr><th>os.name</th><th>实现</th></tr>
 *   <tr><td>含 {@code Windows}</td><td>{@link Win32Mouse} / {@link Win32Keyboard} /
 *       {@link Win32ScreenCapturer} / {@link Win32Cursor} / {@link Win32MouseActions} /
 *       {@link Win32ForegroundAppResolver} / {@link Win32DisplayProvider} /
 *       {@link Win32Clipboard} / {@link Win32AppLauncher}（JNA 直调）</td></tr>
 *   <tr><td>含 {@code Mac} / {@code Darwin}</td><td>{@link MacOsMouse} / {@link MacOsKeyboard} /
 *       {@link MacOsScreenCapturer} / {@link MacOsCursor} / {@link MacOsMouseActions} /
 *       {@link MacOsForegroundAppResolver} / {@link MacOsDisplayProvider} /
 *       {@link MacOsClipboard} / {@link MacOsAppLauncher}（进程外 osascript / screencapture）</td></tr>
 *   <tr><td>其它（Linux 等）</td><td><b>明确抛不支持</b>（{@link UnsupportedOperationException}）</td></tr>
 * </table>
 *
 * <h2>WHY 遇到不支持的平台要<b>抛错</b>而不是静默降级</h2>
 * <p>computer-use 的每个工具都在<b>控制用户的真实桌面</b>。若在 Linux 上静默注册工具
 * 却让它们在调用时才炸，模型会拿到一堆「工具存在但用不了」的能力
 * ⇒ 更好的做法是<b>启动期就明确失败</b>（规则十二 · 显式失败）。
 *
 * <p>⚠️ <b>Windows 专属的 11 个工具</b>（{@code window_management} / {@code click_element} /
 * {@code type_into_element} / {@code open_terminal} / {@code bind_window} /
 * {@code activate_window} / {@code prompt_respond} / {@code status_indicator} /
 * {@code virtual_keyboard} / {@code virtual_mouse} / {@code mouse_wheel}）
 * 在 macOS 上<b>不注册</b> —— 这与 CCB 一致：CCB 的
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:420-836} 把它们放在
 * {@code caps.platform === 'win32' ? [...] : []} 条件块里
 * ⇒ <b>CCB 的 macOS 工具面就是 24 个</b>（35 − 11）。
 */
public final class ComputerUsePlatform {

    private static final Logger log = LoggerFactory.getLogger(ComputerUsePlatform.class);

    /**
     * 平台种类。
     */
    public enum Kind {
        /** Windows（JNA 直调 user32/gdi32/shell32）。 */
        WINDOWS,
        /** macOS（进程外 osascript / screencapture）。 */
        MACOS,
        /** 不支持（Linux 等）。 */
        UNSUPPORTED
    }

    private final Kind kind;
    private final ComputerUseScreenCapturer screenCapturer;
    private final ComputerUseMouse mouse;
    private final ComputerUseKeyboard keyboard;
    private final ComputerUseCursor cursor;
    private final ComputerUseMouseActions mouseActions;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseDisplayProvider displayProvider;
    private final ComputerUseClipboard clipboard;
    private final ComputerUseAppLauncher appLauncher;

    private ComputerUsePlatform(Kind kind,
                                ComputerUseScreenCapturer screenCapturer,
                                ComputerUseMouse mouse,
                                ComputerUseKeyboard keyboard,
                                ComputerUseCursor cursor,
                                ComputerUseMouseActions mouseActions,
                                ComputerUseForegroundAppResolver foregroundAppResolver,
                                ComputerUseDisplayProvider displayProvider,
                                ComputerUseClipboard clipboard,
                                ComputerUseAppLauncher appLauncher) {
        this.kind = kind;
        this.screenCapturer = screenCapturer;
        this.mouse = mouse;
        this.keyboard = keyboard;
        this.cursor = cursor;
        this.mouseActions = mouseActions;
        this.foregroundAppResolver = foregroundAppResolver;
        this.displayProvider = displayProvider;
        this.clipboard = clipboard;
        this.appLauncher = appLauncher;
    }

    /**
     * 按当前操作系统装配。
     *
     * @return 平台实例
     * @throws UnsupportedOperationException 当前操作系统不支持 computer-use
     */
    public static ComputerUsePlatform detect() {
        return of(detect(System.getProperty("os.name")));
    }

    /**
     * 按给定 {@code os.name} 判定平台种类。
     *
     * @param osName {@code System.getProperty("os.name")} 的值（可 null）
     * @return 平台种类
     */
    public static Kind detect(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("windows")) {
            return Kind.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Kind.MACOS;
        }
        return Kind.UNSUPPORTED;
    }

    /**
     * 按平台种类装配。
     *
     * @param kind 平台种类
     * @return 平台实例
     * @throws UnsupportedOperationException 平台不支持
     */
    public static ComputerUsePlatform of(Kind kind) {
        Kind resolved = kind == null ? Kind.UNSUPPORTED : kind;
        switch (resolved) {
            case WINDOWS -> {
                return new ComputerUsePlatform(
                        resolved,
                        new Win32ScreenCapturer(),
                        new Win32Mouse(),
                        new Win32Keyboard(),
                        new Win32Cursor(),
                        new Win32MouseActions(),
                        new Win32ForegroundAppResolver(),
                        new Win32DisplayProvider(),
                        new Win32Clipboard(),
                        new Win32AppLauncher());
            }
            case MACOS -> {
                return new ComputerUsePlatform(
                        resolved,
                        new MacOsScreenCapturer(),
                        new MacOsMouse(),
                        new MacOsKeyboard(),
                        new MacOsCursor(),
                        new MacOsMouseActions(),
                        new MacOsForegroundAppResolver(),
                        new MacOsDisplayProvider(),
                        new MacOsClipboard(),
                        new MacOsAppLauncher());
            }
            default -> throw new UnsupportedOperationException(
                    "[computer-use] 当前操作系统不支持 computer-use（仅支持 Windows 与 macOS）: os.name="
                            + System.getProperty("os.name"));
        }
    }

    /**
     * 当前平台是否支持 Windows 专属的 11 个工具。
     *
     * @return true = Windows（注册全部 35 个工具）
     */
    public boolean supportsWindowsOnlyTools() {
        return kind == Kind.WINDOWS;
    }

    /** @return 平台种类 */
    public Kind kind() {
        return kind;
    }

    /** @return 截屏实现 */
    public ComputerUseScreenCapturer screenCapturer() {
        return screenCapturer;
    }

    /** @return 鼠标实现 */
    public ComputerUseMouse mouse() {
        return mouse;
    }

    /** @return 键盘实现 */
    public ComputerUseKeyboard keyboard() {
        return keyboard;
    }

    /** @return 光标实现 */
    public ComputerUseCursor cursor() {
        return cursor;
    }

    /** @return 鼠标动作实现 */
    public ComputerUseMouseActions mouseActions() {
        return mouseActions;
    }

    /** @return 前台应用解析器 */
    public ComputerUseForegroundAppResolver foregroundAppResolver() {
        return foregroundAppResolver;
    }

    /** @return 显示器枚举实现 */
    public ComputerUseDisplayProvider displayProvider() {
        return displayProvider;
    }

    /** @return 剪贴板实现 */
    public ComputerUseClipboard clipboard() {
        return clipboard;
    }

    /** @return 应用启动实现 */
    public ComputerUseAppLauncher appLauncher() {
        return appLauncher;
    }

    /** 摘要（供日志）。 */
    @Override
    public String toString() {
        return "ComputerUsePlatform{kind=" + kind + "}";
    }

    /** 装配日志（供调用方在启动期打印）。 */
    public void logResolved() {
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 平台分派完成: os.name={} → kind={} · Windows 专属工具={}",
                    System.getProperty("os.name"), kind, supportsWindowsOnlyTools());
        }
    }
}
