package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PowerShell 子进程执行器 · computer-use 的 <b>UIA（UI Automation）与窗口浮层</b>底座。
 *
 * <p><b>WHY 用 PowerShell 而不是 JNA</b>：<b>UI Automation 是 COM API</b>
 * （{@code UIAutomationClient} / {@code UIAutomationTypes} / {@code WindowsBase} 三个 .NET 程序集），
 * 用 JNA 直调需要手写 COM vtable 绑定（复杂且易错）⇒ 本批<b>先做 CCB 的做法</b>
 * （进程外调 PowerShell 加载程序集），把 JNA-COM 留作后续优化。
 *
 * <p><b>⚠️ 编码陷阱（本类的存在理由之一）</b>：Windows PowerShell 5.1 的 stdout 默认走
 * <b>控制台代码页</b>（中文机器 = GBK/936）⇒ 直接按 UTF-8 解码会得到乱码（窗口标题中文全花）。
 * ⇒ 本类在脚本前置 {@code [Console]::OutputEncoding = [Text.Encoding]::UTF8}，
 * 并按 <b>UTF-8 严格解码</b>（{@link StandardCharsets#UTF_8}）。
 *
 * <p><b>⚠️ 显式失败（规则十二）</b>：本类<b>不吞异常</b> ——
 * 子进程非零退出 / 超时 / 输出解码失败 ⇒ 抛 {@link ComputerUsePowerShellException}
 * （带 stderr 原文）⇒ 调用方把它转成模型可见的工具错误。
 * <b>禁止</b>「一次性脚本 + 静默 catch ⇒ 当作没这回事」。
 */
public final class ComputerUsePowerShell {

    private static final Logger log = LoggerFactory.getLogger(ComputerUsePowerShell.class);

    /** 单次执行的超时（毫秒）—— UIA 树遍历最慢，60s 足够宽松。 */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** stderr 输出上限（避免超长错误把内存打爆）。 */
    private static final int MAX_STDERR = 8192;

    private final long timeoutMs;

    /** 默认超时（60s）。 */
    public ComputerUsePowerShell() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /**
     * @param timeoutMs 单次执行超时（毫秒）
     */
    public ComputerUsePowerShell(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * 执行一段 PowerShell 脚本并返回 stdout（已去首尾空白，UTF-8 解码）。
     *
     * @param script 脚本文本（<b>不要</b>自行加 {@code [Console]::OutputEncoding}，本类会加）
     * @return stdout 文本
     * @throws ComputerUsePowerShellException 子进程失败 / 超时 / 无法解码
     */
    public String run(String script) {
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-Command");
        cmd.add("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " + script);

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.redirectErrorStream(false);
        Process process = null;
        try {
            process = builder.start();
            byte[] stdout = readAll(process.getInputStream());
            byte[] stderr = readAll(process.getErrorStream());
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ComputerUsePowerShellException(
                        "PowerShell 执行超时（" + timeoutMs + "ms）: " + preview(script));
            }
            int exit = process.exitValue();
            String err = new String(stderr, StandardCharsets.UTF_8).trim();
            if (exit != 0) {
                throw new ComputerUsePowerShellException(
                        "PowerShell 退出码 " + exit + ": " + (err.isEmpty() ? "(无 stderr)" : err));
            }
            if (!err.isEmpty()) {
                // 退出码 0 但有 stderr ⇒ 记日志但不失败（PowerShell 的非致命告警）
                log.warn("[computer-use] PowerShell 有 stderr 输出: {}", err);
            }
            return new String(stdout, StandardCharsets.UTF_8).trim();
        } catch (ComputerUsePowerShellException e) {
            throw e;
        } catch (IOException e) {
            throw new ComputerUsePowerShellException("PowerShell 启动失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ComputerUsePowerShellException("PowerShell 执行被中断", e);
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

    /** 脚本预览（错误信息里只放脚本头部，避免日志爆炸）。 */
    private static String preview(String script) {
        String oneLine = script.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** 截断 stderr（超长时只留头部）。 */
    static String truncateStderr(String stderr) {
        if (stderr == null) {
            return "";
        }
        return stderr.length() <= MAX_STDERR ? stderr : stderr.substring(0, MAX_STDERR) + "…";
    }
}
