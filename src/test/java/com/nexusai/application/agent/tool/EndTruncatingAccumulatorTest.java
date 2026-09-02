package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EndTruncatingAccumulator} 行为测试 · 对齐 CC {@code stringUtils.ts:140-215 EndTruncatingAccumulator}。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: CC 用本类保护 Bash 前台 stdout 累积的内存上限
 * （stringUtils.ts:86-88 "Keep in-memory accumulation modest... Overflow beyond this limit is spilled
 * to disk"；BashTool.tsx:636 {@code stdoutAccumulator = new EndTruncatingAccumulator()}）。核心不变量：
 * <ol>
 *   <li>超限从<b>末尾</b>截断而非中途砍头（保留输出开头供模型读）——截断语义不能被简化成
 *       {@code substring(0, maxSize)} 丢弃超限部分（那样会丢开头）。</li>
 *   <li>{@code totalBytesReceived} 记录<b>截断前</b>接收总量（含被丢弃部分）——标记 N 据此计算，
 *       不能只记 content 长度。</li>
 *   <li>已截断后不再累积（防后续 append 反复翻倍）——内存硬顶语义。</li>
 * </ol>
 * 用小 maxSize 构造器（CC 支持注入）验证截断逻辑，避免分配 33MB 测试串。
 */
@DisplayName("EndTruncatingAccumulator CC 语义对齐（stringUtils.ts:140-215）")
class EndTruncatingAccumulatorTest {

    @Test
    @DisplayName("MAX_STRING_LENGTH = 2^25 = 33,554,432（CC stringUtils.ts:88）")
    void maxStringLength_is2pow25() {
        assertThat(EndTruncatingAccumulator.MAX_STRING_LENGTH).isEqualTo(33_554_432);
    }

    @Test
    @DisplayName("未超限 → 原样返回，不截断，totalBytes 精确计数")
    void appendUnderLimit_returnsOriginal() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(100);
        acc.append("hello ");
        acc.append("world");
        assertThat(acc.truncated()).isFalse();
        assertThat(acc.toString()).isEqualTo("hello world");
        assertThat(acc.length()).isEqualTo(11);
        assertThat(acc.totalBytes()).isEqualTo(11);
    }

    @Test
    @DisplayName("恰好等于 maxSize → 不截断（CC :166 条件为 '>' 而非 '>='）")
    void appendExactlyAtLimit_notTruncated() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(10);
        acc.append("abcdefghij");
        assertThat(acc.truncated()).isFalse();
        assertThat(acc.toString()).isEqualTo("abcdefghij");
        assertThat(acc.totalBytes()).isEqualTo(10);
    }

    @Test
    @DisplayName("超限 → 保留开头 + 末尾截断标记，content 不超 maxSize，totalBytes 计截断前总量")
    void appendOverLimit_truncatesEnd_andAddsMarker() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(100);
        acc.append("x".repeat(100));  // 恰满 → 未截断
        assertThat(acc.truncated()).isFalse();
        acc.append("y".repeat(500));  // 超限 → 截断
        assertThat(acc.truncated()).isTrue();
        // 开头保留（截断从末尾），content 停在 maxSize
        assertThat(acc.toString()).startsWith("x".repeat(100));
        assertThat(acc.length()).isEqualTo(100);
        // totalBytes 计截断前总量 100+500=600
        assertThat(acc.totalBytes()).isEqualTo(600);
        // 标记 N = round((600-100)/1024) = round(0.488) = 0
        assertThat(acc.toString()).contains("output truncated - 0KB removed");
    }

    @Test
    @DisplayName("超限截断在中间切段时只拼可放下的前缀（CC :168-171 remainingSpace 分支）")
    void appendOverLimit_partialLineKept() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(12);
        acc.append("abcdefgh");        // 8 字符
        acc.append("ijklmnopqrstuv");  // 14 字符 → 剩 4 格只拼 "ijkl"
        assertThat(acc.truncated()).isTrue();
        assertThat(acc.toString()).startsWith("abcdefghijkl");
        assertThat(acc.length()).isEqualTo(12);
        assertThat(acc.totalBytes()).isEqualTo(22);
        // N = round((22-12)/1024) = round(0.0098) = 0
        assertThat(acc.toString()).contains("output truncated - 0KB removed");
    }

    @Test
    @DisplayName("已截断且达容量 → 后续 append 不再修改 content（内存硬顶，CC :161-163）")
    void appendWhenAlreadyTruncated_ignoresFurther() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(10);
        acc.append("abcdefghij");
        assertThat(acc.truncated()).isFalse();
        acc.append("k");              // 超限 → 截断
        assertThat(acc.truncated()).isTrue();
        int lenBefore = acc.length();
        acc.append("zzzzzzzzzzzz");   // 已截断且达容量 → 忽略（content 不变）
        assertThat(acc.length()).isEqualTo(lenBefore);
        assertThat(acc.toString()).doesNotContain("z");
        // totalBytes 仍累计：CC stringUtils.ts:158 append 第一步恒 totalBytesReceived += str.length，
        // 即使随后被 :161-163 提前 return 丢弃也照计 —— 10+1+12 = 23
        assertThat(acc.totalBytes()).isEqualTo(23);
    }

    @Test
    @DisplayName("KB 标记四舍五入（CC :187 Math.round）：截断 1500 字符 → 1KB removed")
    void truncatedKB_roundsToNearest() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(1000);
        acc.append("a".repeat(2500));  // totalBytes=2500，超限 1500
        assertThat(acc.truncated()).isTrue();
        // N = round(1500/1024) = round(1.465) = 1
        assertThat(acc.toString()).contains("output truncated - 1KB removed");
    }

    @Test
    @DisplayName("clear 重置 content / truncated / totalBytes（CC :194-198）")
    void clear_resetsState() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(5);
        acc.append("abcdefghij");
        assertThat(acc.truncated()).isTrue();
        acc.clear();
        assertThat(acc.toString()).isEqualTo("");
        assertThat(acc.truncated()).isFalse();
        assertThat(acc.length()).isZero();
        assertThat(acc.totalBytes()).isZero();
        acc.append("hi");
        assertThat(acc.toString()).isEqualTo("hi");
        assertThat(acc.totalBytes()).isEqualTo(2);
    }

    @Test
    @DisplayName("null append 为 no-op（防御）")
    void appendNull_noop() {
        EndTruncatingAccumulator acc = new EndTruncatingAccumulator(10);
        acc.append("abc");
        acc.append(null);
        assertThat(acc.toString()).isEqualTo("abc");
        assertThat(acc.totalBytes()).isEqualTo(3);
    }
}
