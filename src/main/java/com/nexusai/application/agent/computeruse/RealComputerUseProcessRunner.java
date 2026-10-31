package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 生产版外部命令执行器（{@link ProcessBuilder}）· computer-use macOS 执行端底座。
 *
 * <p><b>WHY UTF-8 严格解码</b>：{@code osascript} 的输出是 UTF-8；按平台默认编码解码
 * 在中文 Windows 上会乱码（虽然 macOS 端恒为 UTF-8，但保持一致更安全）。
 *
 * <p><b>WHY 有超时</b>：{@code osascript} 在系统弹了 TCC 授权对话框时会被<b>阻塞等待用户点击</b>
 * ⇒ 没有超时就会把调用线程永久挂住（computer-use 是同步工具调用 ⇒ 会卡住整个 turn）。
 * <br>⇒ 超时后 {@code destroyForcibly()} 并抛异常（fail loud，不静默返回空结果）。
 */
public final class RealComputerUseProcessRunner implements ComputerUseProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(RealComputerUseProcessRunner.class);

    /** 单次执行超时（毫秒）—— 30s 足够跑完一次 JXA 脚本 / 一次截屏。 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** stderr 截断上限（避免超长错误把内存打爆）。 */
    private static final int MAX_STDERR = 8192;

    private final long timeoutMs;

    /** 默认超时（30s）。 */
    public RealComputerUseProcessRunner() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /**
     * @param timeoutMs 单次执行超时（毫秒）
     */
    public RealComputerUseProcessRunner(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Result run(List<String> command) throws Exception {
        return run(command, null);
    }

    @Override
    public Result runWithStdin(List<String> command, String stdin) throws Exception {
        return run(command, stdin);
    }

    /**
     * 执行命令（可选地写入 stdin）。
     *
     * @param command 命令与参数
     * @param stdin   要写入的标准输入（null / 空 ⇒ 不写）
     * @return 执行结果
     * @throws Exception 进程启动失败 / IO 异常
     */
    public Result run(List<String> command, String stdin) throws Exception {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("[computer-use] 命令不能为空");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = null;
        try {
            process = builder.start();
            if (stdin != null && !stdin.isEmpty()) {
                try (java.io.OutputStream os = process.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                // 不写 stdin ⇒ 必须关闭，否则子进程会一直等 EOF
                process.getOutputStream().close();
            }
            byte[] stdout = readAll(process.getInputStream());
            byte[] stderr = readAll(process.getErrorStream());
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.format(
                        "[computer-use] 外部命令执行超时（%dms）: %s", timeoutMs, command.get(0)));
            }
            int exit = process.exitValue();
            return new Result(exit,
                    new String(stdout, StandardCharsets.UTF_8),
                    truncate(new String(stderr, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[computer-use] 外部命令启动失败: " + command.get(0) + " —— " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[computer-use] 外部命令执行被中断: " + command.get(0), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 读取流全部字节。 */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 截断 stderr（超长时只留头部）。 */
    private static String truncate(String stderr) {
        if (stderr == null) {
            return "";
        }
        String trimmed = stderr.trim();
        return trimmed.length() <= MAX_STDERR ? trimmed : trimmed.substring(0, MAX_STDERR) + "…";
    }

    /** 调试日志（脚本预览，避免日志爆炸）。 */
    static String preview(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        String oneLine = String.join(" ", command).replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }
}
