package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * macOS 剪贴板实现（{@code pbpaste} / {@code pbcopy}）· computer-use 的
 * {@code read_clipboard} / {@code write_clipboard} 底座。
 *
 * <h2>⚠️ CCB 的 macOS 路线<b>没有</b>剪贴板实现（本仓补充）</h2>
 * <p>CCB 的 {@code src/utils/computerUse/platforms/darwin.ts} 只委派
 * <b>input / screenshot / display / apps</b> 四块，<b>没有 clipboard</b>
 * ⇒ macOS 上 CCB 的剪贴板工具无对应实现。
 * <br>⇒ 本仓用 macOS 自带的 {@code pbpaste} / {@code pbcopy}（系统自带 CLI，
 * ⛔ 不引入新依赖）补上，并在此如实标注「CCB 无对应」。
 *
 * <p>（Windows 侧本仓走 JNA 直调 Win32 剪贴板 API，见 {@link Win32Clipboard}；
 * 语义与本类一致：读不到非文本 ⇒ 空串。）
 */
public final class MacOsClipboard implements ComputerUseClipboard {

    private static final Logger log = LoggerFactory.getLogger(MacOsClipboard.class);

    private final ComputerUseProcessRunner runner;

    /** 生产构造器（真起进程）。 */
    public MacOsClipboard() {
        this(ComputerUseProcessRunner.REAL);
    }

    /**
     * @param runner 外部命令执行器
     */
    public MacOsClipboard(ComputerUseProcessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("[computer-use] MacOsClipboard 依赖不能为空");
        }
        this.runner = runner;
    }

    @Override
    public String readText() throws Exception {
        ComputerUseProcessRunner.Result result = runner.run(List.of("pbpaste"));
        if (!result.ok()) {
            throw new IllegalStateException(
                    "[computer-use] pbpaste 失败（退出码 " + result.exitCode() + "）: " + result.stderr());
        }
        return result.stdout();
    }

    @Override
    public void writeText(String text) throws Exception {
        String payload = text == null ? "" : text;
        ComputerUseProcessRunner.Result result = runner.runWithStdin(List.of("pbcopy"), payload);
        if (!result.ok()) {
            throw new IllegalStateException(
                    "[computer-use] pbcopy 失败（退出码 " + result.exitCode() + "）: " + result.stderr());
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 剪贴板已写入: {} 字符", payload.length());
        }
    }
}
