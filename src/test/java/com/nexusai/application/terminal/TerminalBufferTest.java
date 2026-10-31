package com.nexusai.application.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终端环形缓冲单测（纯 JUnit，无 Spring）。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：这个缓冲是 Agent「读终端输出」的<b>唯一</b>数据源 ——
 * 它一旦退化，模型会拿到过时/带转义噪声/被截断的状态，然后据此做出错误判断（例如把上一个项目的
 * 构建成功当成这次的）。故这里钉死四条意图：
 * <ul>
 *   <li><b>只留最近</b>：超限丢最旧（否则老状态冒充当前状态）；</li>
 *   <li><b>snapshot(lines) 取尾部</b>（「最近发生了什么」才是决策依据）；</li>
 *   <li><b>ANSI 剥干净</b>（{@code \u001B[31m} 这类噪声不该进模型上下文）；</li>
 *   <li><b>进度条不爆炸</b>（{@code \r} 覆盖而非追加，否则 dev server 一行能占满整个缓冲）。</li>
 * </ul>
 */
class TerminalBufferTest {

    @Test
    @DisplayName("行数超限：丢最旧、保留最近（超过 1000 行按上限裁剪）")
    void dropsOldestLinesBeyondCap() {
        TerminalBuffer buffer = new TerminalBuffer(3, 1024);

        buffer.append("a\nb\nc\nd\n");

        assertThat(buffer.lineCount()).isEqualTo(3);
        assertThat(buffer.snapshotAll()).isEqualTo("b\nc\nd");
    }

    @Test
    @DisplayName("snapshot(lines) 只返回尾部 N 行（Agent 读「最近状态」的判据）")
    void snapshotReturnsTail() {
        TerminalBuffer buffer = new TerminalBuffer();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("line-").append(i).append('\n');
        }
        buffer.append(sb.toString());

        assertThat(buffer.snapshot(3)).isEqualTo("line-8\nline-9\nline-10");
        assertThat(buffer.snapshotAll()).contains("line-1\n");
    }

    @Test
    @DisplayName("ANSI 转义被剥离，但缓冲内部保留原始字节（xterm 仍需要转义）")
    void stripsAnsiEscapesOnSnapshot() {
        TerminalBuffer buffer = new TerminalBuffer();

        buffer.append("\u001B[31mred\u001B[0m and \u001B]0;title\u0007plain\n");

        assertThat(buffer.snapshotAll()).isEqualTo("red and plain");
    }

    @Test
    @DisplayName("其余控制字符（BEL/BEL 等）也被剥离，制表符保留")
    void stripsOtherControlChars() {
        TerminalBuffer buffer = new TerminalBuffer();

        buffer.append("a\u0007b\tc\n");

        assertThat(buffer.snapshotAll()).isEqualTo("ab\tc");
    }

    @Test
    @DisplayName("无尾换行的最后一行仍在快照里（dev server 的 ready 行常常没有换行）")
    void keepsUnterminatedLastLine() {
        TerminalBuffer buffer = new TerminalBuffer();

        buffer.append("VITE v5 ready in 300 ms");

        assertThat(buffer.snapshot(10)).isEqualTo("VITE v5 ready in 300 ms");
    }

    @Test
    @DisplayName("字符上限：按先到者裁剪，丢掉最旧的整行")
    void dropsOldestWhenCharCapReached() {
        TerminalBuffer buffer = new TerminalBuffer(1000, 25);

        buffer.append("aaaaaaaaaa\nbbbbbbbbbb\ncccccccccc\n");

        // 3 行共 30 字符 > 25 ⇒ 丢最旧一行，剩 20 字符
        assertThat(buffer.snapshotAll()).isEqualTo("bbbbbbbbbb\ncccccccccc");
    }

    @Test
    @DisplayName("裸 \\r 覆盖当前行（进度条 10%→20%→30% 只留 30%，不制造三行垃圾）")
    void carriageReturnOverwritesProgressLine() {
        TerminalBuffer buffer = new TerminalBuffer();

        buffer.append("10%\r20%\r30%\n");

        assertThat(buffer.snapshotAll()).isEqualTo("30%");
    }

    @Test
    @DisplayName("CRLF 只算一次换行；跨块劈开的 \\r\\n 也不产生空行")
    void crlfCountsAsSingleBreakEvenAcrossChunks() {
        TerminalBuffer buffer = new TerminalBuffer();

        buffer.append("abc\r");
        buffer.append("\ndef\n");

        assertThat(buffer.snapshotAll()).isEqualTo("abc\ndef");
    }

    @Test
    @DisplayName("超长单行只保留尾部（否则一行就能吃满整个缓冲）")
    void longSingleLineKeepsTailOnly() {
        TerminalBuffer buffer = new TerminalBuffer(10, 1024 * 1024);

        String huge = "x".repeat(TerminalBuffer.MAX_LINE_CHARS + 5000) + "TAIL";
        buffer.append(huge);

        String snap = buffer.snapshotAll();
        assertThat(snap).endsWith("TAIL");
        assertThat(snap.length()).isLessThanOrEqualTo(TerminalBuffer.MAX_LINE_CHARS);
    }

    @Test
    @DisplayName("空/ null 追加是 no-op（读线程遇到空块不该炸）")
    void nullOrEmptyAppendIsNoop() {
        TerminalBuffer buffer = new TerminalBuffer();

        buffer.append(null);
        buffer.append("");

        assertThat(buffer.lineCount()).isZero();
        assertThat(buffer.snapshotAll()).isEmpty();
    }
}
