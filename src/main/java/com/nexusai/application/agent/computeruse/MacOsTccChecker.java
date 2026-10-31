package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * macOS TCC（用户隐私授权）检查 · computer-use macOS 执行端。
 *
 * <h2>⚠️ 本仓新增（CCB 的 JXA 副本<b>没有</b>实现）</h2>
 * <p>CCB 的 macOS TCC 检查在其<b>原生 Swift 模块</b>里
 * （{@code cu.tcc.checkAccessibility()} / {@code cu.tcc.checkScreenRecording()}，
 * 被 {@code src/utils/computerUse/hostAdapter.ts:84-103} 的 {@code ensureOsPermissions} 调用）。
 * <br>CCB 的 <b>JXA 副本</b>只有两个<b>间接探针</b>
 * （{@code src/utils/computerUse/hostAdapter.ts:36-64}）：
 * <ul>
 *   <li>辅助功能 = 「{@code osascript -e 'tell application "System Events" to get name of every
 *       process whose background only is false'}」的退出码是否 0；</li>
 *   <li>屏幕录制 = 「{@code screencapture -x -R 0,0,1,1 /dev/null}」的退出码是否 0。</li>
 * </ul>
 * ⇒ 本仓用 macOS 的<b>规范 API</b>做等价判定（<b>更准、更直接</b>）：
 * <ul>
 *   <li>辅助功能 = {@code AXIsProcessTrusted()}（ApplicationServices 框架）；</li>
 *   <li>屏幕录制 = {@code CGPreflightScreenCaptureAccess()}（CoreGraphics 框架，macOS 10.15+）。</li>
 * </ul>
 * 规范 API 探针<b>失败</b>（脚本报错 / 老系统没有该符号）⇒ <b>回落</b>到 CCB 的两个间接探针。
 *
 * <h2>WHY 必须 fail loud</h2>
 * <p>macOS 未授权时：辅助功能未授权 ⇒ <b>所有</b>鼠标/键盘注入静默失效（事件不落）；
 * 屏幕录制未授权 ⇒ {@code screencapture} 产出<b>桌面壁纸</b>（不是黑屏！）。
 * ⇒ 两者都必须<b>明确报错</b>，否则模型会拿到「看起来正常但内容不对」的截图并继续瞎操作。
 *
 * <p><b>缓存策略</b>：<b>成功</b>的结果永久缓存（授权不会自己消失）；
 * <b>失败</b>的结果<b>不缓存</b>（用户可能中途去系统设置授权 ⇒ 下次调用重新探测）。
 */
public final class MacOsTccChecker {

    private static final Logger log = LoggerFactory.getLogger(MacOsTccChecker.class);

    /** 辅助功能未授权时的报错文案。 */
    public static final String ACCESSIBILITY_DENIED_MESSAGE =
            "[computer-use] macOS 辅助功能（Accessibility）权限未授权：无法注入鼠标 / 键盘事件。"
                    + "请在「系统设置 → 隐私与安全性 → 辅助功能」中允许本应用"
                    + "（运行 nexusai 的终端 / IDE）后重试。";

    /** 屏幕录制未授权时的报错文案。 */
    public static final String SCREEN_RECORDING_DENIED_MESSAGE =
            "[computer-use] macOS 屏幕录制（Screen Recording）权限未授权：无法截图"
                    + "（未授权时 screencapture 只会拍到桌面壁纸）。"
                    + "请在「系统设置 → 隐私与安全性 → 屏幕录制」中允许本应用"
                    + "（运行 nexusai 的终端 / IDE）后重试。";

    /**
     * 授权状态。
     *
     * @param accessibilityGranted 辅助功能已授权
     * @param screenRecordingGranted 屏幕录制已授权
     */
    public record Status(boolean accessibilityGranted, boolean screenRecordingGranted) {

        /** @return true = 两项都已授权 */
        public boolean granted() {
            return accessibilityGranted && screenRecordingGranted;
        }
    }

    private final ComputerUseProcessRunner runner;

    /** 已缓存的辅助功能授权状态（null = 尚未探测 / 未缓存失败）。 */
    private volatile Boolean accessibility;

    /** 已缓存的屏幕录制授权状态。 */
    private volatile Boolean screenRecording;

    /** 生产构造器（真起进程）。 */
    public MacOsTccChecker() {
        this(ComputerUseProcessRunner.REAL);
    }

    /**
     * @param runner 外部命令执行器（单测注入假实现）
     */
    public MacOsTccChecker(ComputerUseProcessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("[computer-use] MacOsTccChecker 的 runner 不能为空");
        }
        this.runner = runner;
    }

    /**
     * 辅助功能是否已授权。
     *
     * @return true = 已授权
     */
    public boolean accessibilityGranted() {
        Boolean cached = accessibility;
        if (cached != null) {
            return cached;
        }
        boolean granted = probeAccessibility();
        if (granted) {
            accessibility = Boolean.TRUE;
        }
        return granted;
    }

    /**
     * 屏幕录制是否已授权。
     *
     * @return true = 已授权
     */
    public boolean screenRecordingGranted() {
        Boolean cached = screenRecording;
        if (cached != null) {
            return cached;
        }
        boolean granted = probeScreenRecording();
        if (granted) {
            screenRecording = Boolean.TRUE;
        }
        return granted;
    }

    /**
     * 一次性取两项状态。
     *
     * @return 授权状态
     */
    public Status status() {
        return new Status(accessibilityGranted(), screenRecordingGranted());
    }

    /**
     * 要求辅助功能已授权，否则抛错。
     *
     * @throws IllegalStateException 未授权
     */
    public void requireAccessibility() {
        if (!accessibilityGranted()) {
            throw new IllegalStateException(ACCESSIBILITY_DENIED_MESSAGE);
        }
    }

    /**
     * 要求屏幕录制已授权，否则抛错。
     *
     * @throws IllegalStateException 未授权
     */
    public void requireScreenRecording() {
        if (!screenRecordingGranted()) {
            throw new IllegalStateException(SCREEN_RECORDING_DENIED_MESSAGE);
        }
    }

    /** 辅助功能探测：规范 API 优先 ⇒ 失败回落 CCB 的间接探针。 */
    private boolean probeAccessibility() {
        Boolean viaApi = runBooleanProbe(MacOsJxa.accessibilityTrusted());
        if (viaApi != null) {
            return viaApi;
        }
        // 回落：CCB 的间接探针（hostAdapter.ts:36-51）
        try {
            ComputerUseProcessRunner.Result result = runner.run(List.of("osascript", "-e",
                    "tell application \"System Events\" to get name of every process whose background only is false"));
            return result.ok();
        } catch (Exception e) {
            log.warn("[computer-use] macOS 辅助功能权限探测异常: {}", e.toString());
            return false;
        }
    }

    /** 屏幕录制探测：规范 API 优先 ⇒ 失败回落 CCB 的间接探针。 */
    private boolean probeScreenRecording() {
        Boolean viaApi = runBooleanProbe(MacOsJxa.screenCaptureAccess());
        if (viaApi != null) {
            return viaApi;
        }
        // 回落：CCB 的间接探针（hostAdapter.ts:53-64）
        try {
            ComputerUseProcessRunner.Result result = runner.run(
                    List.of("screencapture", "-x", "-R", "0,0,1,1", "/dev/null"));
            return result.ok();
        } catch (Exception e) {
            log.warn("[computer-use] macOS 屏幕录制权限探测异常: {}", e.toString());
            return false;
        }
    }

    /**
     * 跑一个返回 {@code "true" / "false"} 的 JXA 探针。
     *
     * @param script JXA 脚本
     * @return true / false；<b>脚本本身跑不起来 ⇒ null</b>（调用方回落间接探针）
     */
    private Boolean runBooleanProbe(String script) {
        try {
            ComputerUseProcessRunner.Result result =
                    runner.run(List.of("osascript", "-l", "JavaScript", "-e", script));
            if (!result.ok()) {
                log.warn("[computer-use] macOS TCC 探针失败（退出码 {}）: {}",
                        result.exitCode(), result.stderr());
                return null;
            }
            String out = result.stdout().trim();
            if ("true".equalsIgnoreCase(out)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(out)) {
                return Boolean.FALSE;
            }
            log.warn("[computer-use] macOS TCC 探针输出无法解析: {}", out);
            return null;
        } catch (Exception e) {
            log.warn("[computer-use] macOS TCC 探针异常: {}", e.toString());
            return null;
        }
    }
}
