package com.nexusai.application.terminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 单个终端输出的<b>环形缓冲</b>（最近 1000 行 / 256K 字符，先到者为准）。
 *
 * <p><b>WHY 需要它</b>（用户需求「Agent 还要能读终端输出」）：PTY 是<b>一次性消费</b>的字节流 ——
 * 主进程 shell 的 stdout 只有读者线程在读，Agent 工具/重连的前端<b>结构上读不到历史</b>。
 * 故读线程每读到一块就抄一份进来，供 {@link TerminalService#snapshot} 返回。
 *
 * <p><b>口径（照实声明）</b>：
 * <ul>
 *   <li><b>容量</b>：最多保留 {@link #DEFAULT_MAX_LINES} 行，且字符总数（已提交行 + 当前未提交行）
 *       不超过 {@link #DEFAULT_MAX_CHARS}，<b>先到者为准</b>。字符数≈字节数（终端输出以 ASCII 为主；
 *       非 ASCII 时按 UTF-16 char 计，属<b>近似</b>，不宣称字节级精确）。</li>
 *   <li><b>按行裁剪</b>：超限一律丢<b>最旧</b>的整行（不是截字符），保证 snapshot 拿到的是完整行。</li>
 *   <li><b>单行上限</b> {@link #MAX_LINE_CHARS}：超长单行（无换行的大块输出 / 进度条）只保留尾部，
 *       否则一行就能吃满整个缓冲。</li>
 *   <li><b>控制字符</b>：{@code '\r'} 按终端语义处理 —— {@code \r\n} = 换行；<b>裸</b> {@code \r} = 回车
 *       覆盖本行（进度条 {@code 10%\r20%\r30%} 最终只剩 {@code 30%}，不制造 3 行垃圾、也不会无限增长）。</li>
 *   <li><b>ANSI 转义只在 {@link #snapshot} 出口剥离</b>：缓冲里存<b>原始</b>字节解码文本 —— 同一份数据
 *       还要推给 xterm.js（它需要转义序列才能上色），剥离只能发生在「给人/给模型读」的出口。</li>
 * </ul>
 *
 * <p>线程模型：读线程写、REST/Agent 线程读 ⇒ 全部方法 {@code synchronized}。临界区只有内存搬运，
 * 无 IO，不存在被慢客户端拖住的读者（STOMP 推送发生在锁外，见 {@link TerminalService}）。
 */
public final class TerminalBuffer {

    /** 默认保留行数上限。 */
    public static final int DEFAULT_MAX_LINES = 1000;

    /** 默认保留字符上限（≈256KB，见类注释的口径说明）。 */
    public static final int DEFAULT_MAX_CHARS = 256 * 1024;

    /** 单行字符上限（超出只保留尾部）。 */
    public static final int MAX_LINE_CHARS = 16 * 1024;

    /**
     * ANSI 转义：CSI（{@code ESC [ ... letter}）/ OSC（{@code ESC ] ... BEL|ESC \}）/ 两字节转义
     * （{@code ESC X}）。终端上色、清屏、光标移动都靠它 —— Agent 读的时候这些是纯噪声。
     */
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
        "\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)|[@-Z\\\\-_])");

    /** 其余 C0 控制符（保留 {@code \t}）：BEL/BS/VT/FF 等对文本阅读无意义。 */
    private static final Pattern OTHER_CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private final int maxLines;
    private final int maxChars;

    /** 已提交的完整行（尾部最新）。 */
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    /** 尚未遇到换行的当前行。 */
    private final StringBuilder pending = new StringBuilder();

    /** 已提交行的字符总数（不含 {@link #pending}）。 */
    private int storedChars;

    /**
     * 刚被裸 {@code \r} 覆盖掉的那一行 —— 仅用于识别跨块的 {@code \r\n}
     * （{@code \r} 落在上一块末尾、{@code \n} 落在下一块开头：此时应提交「回车之前的那一行」，
     * 而不是提交空行）。非空表示「下一个字符若是 {@code \n} 就把它当换行提交」。
     */
    private String overwrittenLine;

    public TerminalBuffer() {
        this(DEFAULT_MAX_LINES, DEFAULT_MAX_CHARS);
    }

    /**
     * @param maxLines 行数上限（{@code <=0} ⇒ {@link #DEFAULT_MAX_LINES}）
     * @param maxChars 字符上限（{@code <=0} ⇒ {@link #DEFAULT_MAX_CHARS}）
     */
    public TerminalBuffer(int maxLines, int maxChars) {
        this.maxLines = maxLines > 0 ? maxLines : DEFAULT_MAX_LINES;
        this.maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
    }

    /**
     * 追加一段输出（原始文本，含 ANSI 转义）。
     *
     * @param text 读线程本次解码出的文本（null/空 ⇒ no-op）
     */
    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\r') {
                // 裸 \r：回车覆盖本行。把被覆盖的旧内容暂存，等下一个字符决定它是
                // 「\r\n 换行」（提交旧行）还是「真覆盖」（丢弃旧行）。
                overwrittenLine = pending.toString();
                pending.setLength(0);
                i++;
            } else if (c == '\n') {
                if (overwrittenLine != null) {
                    // 上一块的裸 \r 接到了 \n ⇒ 是 CRLF，提交回车之前的那一行
                    commit(overwrittenLine);
                    overwrittenLine = null;
                } else {
                    commitPending();
                }
                i++;
            } else {
                overwrittenLine = null;   // 真覆盖：旧行内容作废
                pending.append(c);
                if (pending.length() > MAX_LINE_CHARS) {
                    pending.delete(0, pending.length() - MAX_LINE_CHARS);
                }
                i++;
            }
        }
    }

    /**
     * 取最近 {@code lines} 行（已剥离 ANSI 转义），按时间顺序以 {@code \n} 连接。
     *
     * <p>包含当前尚未遇到换行的最后一行（dev server 的 {@code ready in 300ms} 常常没有尾换行，
     * 不含它 Agent 就永远读不到最后一条状态）。
     *
     * @param requestedLines 需要的行数（{@code <=0} ⇒ 全部）
     * @return 快照文本（无行 ⇒ 空串，绝不返回 null）
     */
    public synchronized String snapshot(int requestedLines) {
        int limit = (requestedLines <= 0 || requestedLines > maxLines) ? maxLines : requestedLines;
        boolean hasPending = pending.length() > 0;
        int committedWanted = hasPending ? limit - 1 : limit;
        List<String> tail = new ArrayList<>(Math.max(0, committedWanted) + 1);
        if (committedWanted > 0) {
            int skip = Math.max(0, lines.size() - committedWanted);
            int idx = 0;
            for (String line : lines) {
                if (idx++ < skip) {
                    continue;
                }
                tail.add(render(line));
            }
        }
        if (hasPending) {
            tail.add(render(pending.toString()));
        }
        return String.join("\n", tail);
    }

    /** 全部缓冲内容（同 {@code snapshot(0)}）。 */
    public synchronized String snapshotAll() {
        return snapshot(0);
    }

    /**
     * 当前缓冲行数（含尚未提交的最后一行，若有内容）。
     *
     * @return 行数
     */
    public synchronized int lineCount() {
        return lines.size() + (pending.length() > 0 ? 1 : 0);
    }

    /** 清空缓冲（关闭终端时释放内存用）。 */
    public synchronized void clear() {
        lines.clear();
        pending.setLength(0);
        storedChars = 0;
        overwrittenLine = null;
    }

    /** 出口渲染：剥离 ANSI + 其余控制符。 */
    private static String render(String raw) {
        String s = ANSI_ESCAPE.matcher(raw).replaceAll("");
        return OTHER_CONTROL.matcher(s).replaceAll("");
    }

    /** 剥离 ANSI 转义与控制符（供调用方对任意文本使用，也是本类出口的判据）。 */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return render(text);
    }

    private void commitPending() {
        // ⚠️ 必须先清 pending 再 commit：trim() 的字符预算里含 pending.length()，
        // 若先 commit 后清，正在提交的这一行会被算两遍 ⇒ 多丢一行旧内容（实测过）。
        String line = pending.toString();
        pending.setLength(0);
        commit(line);
    }

    private void commit(String line) {
        lines.addLast(line);
        storedChars += line.length();
        trim();
    }

    /** 超限裁剪：先按行数，再按字符数（都从最旧的行丢起）。 */
    private void trim() {
        while (lines.size() > maxLines) {
            storedChars -= lines.removeFirst().length();
        }
        while (storedChars + pending.length() > maxChars && !lines.isEmpty()) {
            storedChars -= lines.removeFirst().length();
        }
    }
}
