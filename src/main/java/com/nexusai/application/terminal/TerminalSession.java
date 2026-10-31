package com.nexusai.application.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 一个 PTY 终端的进程内状态（由 {@link TerminalService} 独占持有，不对外暴露）。
 *
 * <p><b>线程模型</b>：三个线程会碰它 ——
 * <ul>
 *   <li><b>读线程</b>（每终端一个 daemon）：读 stdout → 写 {@link #buffer} → 推 STOMP；</li>
 *   <li><b>REST 线程</b>（Tomcat 池）：{@link #write} / {@link #resize} / {@link #info} / snapshot；</li>
 *   <li><b>会话删除 / {@code @PreDestroy} 线程</b>：{@link #destroy}。</li>
 * </ul>
 * 故：{@link #closed} / {@link #exitCode} 用 volatile；写 stdout 走 {@link #writeLock}
 * （pty4j 的 OutputStream 非线程安全，前端连续按键 + Agent 输入会并发写）；
 * 缓冲自身内部同步（见 {@link TerminalBuffer}）。
 */
final class TerminalSession {

    private final String sessionId;
    private final String terminalId;
    private final String shell;
    private final String cwd;
    private final Instant startedAt;
    private final PtyProcess process;
    private final TerminalBuffer buffer;
    private final Object writeLock = new Object();

    private volatile int cols;
    private volatile int rows;
    private volatile boolean closed;
    private volatile int exitCode = -1;

    TerminalSession(String sessionId, String terminalId, String shell, String cwd, int cols, int rows,
                    PtyProcess process, TerminalBuffer buffer) {
        this.sessionId = sessionId;
        this.terminalId = terminalId;
        this.shell = shell;
        this.cwd = cwd;
        this.cols = cols;
        this.rows = rows;
        this.process = process;
        this.buffer = buffer;
        this.startedAt = Instant.now();
    }

    String sessionId() {
        return sessionId;
    }

    String terminalId() {
        return terminalId;
    }

    String shell() {
        return shell;
    }

    String cwd() {
        return cwd;
    }

    Instant startedAt() {
        return startedAt;
    }

    PtyProcess process() {
        return process;
    }

    TerminalBuffer buffer() {
        return buffer;
    }

    Object writeLock() {
        return writeLock;
    }

    boolean isClosed() {
        return closed || !process.isAlive();
    }

    int exitCode() {
        return exitCode;
    }

    /** 进程已退出（或被杀）后由读线程/关闭路径调用；exitCode = -1 表示拿不到（被强杀）。 */
    void markClosed(int code) {
        this.exitCode = code;
        this.closed = true;
    }

    /**
     * 写入 PTY（回车符由调用方提供：xterm 发的是 {@code \r}，不是 {@code \n}）。
     *
     * @param data 原始文本（UTF-8 编码后写）
     * @throws IOException 写失败（进程已退出 / PTY 句柄失效）
     */
    void write(String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        OutputStream out = process.getOutputStream();
        synchronized (writeLock) {
            out.write(bytes);
            out.flush();
        }
    }

    /**
     * 调整 PTY 窗口尺寸（xterm.js 的 {@code onResize} 直连此处；SIGWINCH 由 PTY 自动发）。
     *
     * @param newCols 列数
     * @param newRows 行数
     * @throws IOException 底层 ioctl 失败
     */
    void resize(int newCols, int newRows) throws IOException {
        process.setWinSize(new WinSize(newCols, newRows));
        this.cols = newCols;
        this.rows = newRows;
    }

    TerminalInfo info() {
        return new TerminalInfo(terminalId, shell, cwd, cols, rows, !isClosed(), startedAt.toString());
    }
}
