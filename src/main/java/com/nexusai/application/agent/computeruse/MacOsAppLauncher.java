package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * macOS 应用启动实现（{@code open -a} / {@code open -b}）· computer-use 的
 * {@code open_application} 底座。
 *
 * <h2>CCB 对照</h2>
 * <p>CCB 的 macOS 启动：{@code packages/@ant/computer-use-swift/src/backends/darwin.ts:251-253}
 * 的 {@code apps.open(bundleId)} ⇒ {@code osascript -e 'tell application id "<bundleId>" to activate'}。
 * <br>⇒ 本仓用 {@code open}（系统自带 CLI，语义等价且<b>不需要辅助功能权限</b>）：
 * <ul>
 *   <li>应用键像 <b>bundle id</b>（含 {@code .} 且无空格，如 {@code com.google.Chrome}）
 *       ⇒ {@code open -b <bundleId>}；</li>
 *   <li>否则（显示名 / 归一后的 exe 名，如 {@code safari}）⇒ {@code open -a <name>}。</li>
 * </ul>
 *
 * <p>⚠️ 与 CCB 的差异（如实标注）：CCB 只接受 <b>bundle id</b>；本仓的授权键是
 * {@link ComputerUseAppPolicy#normalizeAppKey(String)} 归一后的<b>名字</b>
 * （与 Windows 侧同款命名空间）⇒ 本实现按「像不像 bundle id」自动二选一。
 */
public final class MacOsAppLauncher implements ComputerUseAppLauncher {

    private static final Logger log = LoggerFactory.getLogger(MacOsAppLauncher.class);

    private final ComputerUseProcessRunner runner;

    /** 生产构造器（真起进程）。 */
    public MacOsAppLauncher() {
        this(ComputerUseProcessRunner.REAL);
    }

    /**
     * @param runner 外部命令执行器
     */
    public MacOsAppLauncher(ComputerUseProcessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("[computer-use] MacOsAppLauncher 依赖不能为空");
        }
        this.runner = runner;
    }

    @Override
    public void launch(String appKey) throws Exception {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("[computer-use] 应用标识不能为空");
        }
        String target = appKey.trim();
        List<String> cmd = looksLikeBundleId(target)
                ? List.of("open", "-b", target)
                : List.of("open", "-a", target);
        ComputerUseProcessRunner.Result result = runner.run(cmd);
        if (!result.ok()) {
            throw new IllegalStateException(String.format(
                    "[computer-use] 启动应用失败: %s（open 退出码 %d）: %s",
                    target, result.exitCode(), result.stderr()));
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 应用已启动: {}（命令 {}）", target, cmd);
        }
    }

    /**
     * 判断应用键是否像 bundle id（含 {@code .}、无空格）。
     *
     * @param appKey 应用键
     * @return true = 像 bundle id
     */
    static boolean looksLikeBundleId(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return false;
        }
        String s = appKey.trim();
        return s.indexOf('.') > 0 && !s.contains(" ") && !s.contains("\t");
    }
}
