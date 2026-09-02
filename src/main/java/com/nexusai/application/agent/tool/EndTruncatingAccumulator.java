package com.nexusai.application.agent.tool;

/**
 * 字符串累加器（超限从末尾截断）· 对齐 CC {@code src/utils/stringUtils.ts:140-215 EndTruncatingAccumulator}。
 *
 * <p><b>WHY (CC 真源 stringUtils.ts:86-88)</b>: "Keep in-memory accumulation modest to avoid blowing up
 * RSS. Overflow beyond this limit is spilled to disk by ShellCommand." —— 超大输出在内存中
 * 累积到 {@link #MAX_STRING_LENGTH}（2^25 ≈ 33MB）硬顶后从<b>末尾</b>截断，防止
 * {@code RangeError: Invalid string length} 崩溃，同时保留输出开头（BashTool.tsx:636
 * {@code stdoutAccumulator = new EndTruncatingAccumulator()} 的防护语义）。
 *
 * <p><b>CC 行为（stringUtils.ts:156-189）</b>:
 * <ol>
 *   <li>{@link #append(String)}: 先计 {@code totalBytesReceived}（累计接收字符数，含截断丢弃部分）；
 *       已截断且 content 已达 maxSize → 后续 append 直接忽略；新增会超出 maxSize → 只拼能放下的前缀
 *       并置 truncated；否则全量拼接。</li>
 *   <li>{@link #toString()}: 未截断 → 原样返回 content；截断 → content + {@code "\n... [output truncated - NKB removed]"}
 *       （N = {@code Math.round((totalBytesReceived - maxSize) / 1024)}，stringUtils.ts:186-188）。</li>
 *   <li>{@link #clear()} 重置 content/truncated/totalBytesReceived。</li>
 *   <li>{@link #length()} / {@link #truncated()} / {@link #totalBytes()} 访问器。</li>
 * </ol>
 *
 * <p><b>Java 接线</b>: {@code BashTool.captureOutput} 用本类替换 {@code StringBuilder preview} 累积
 * （BashTool.java:1450+），作为内存累积缓冲：{@code previewLimit}（getMaxOutputLength=30K）远小于
 * maxSize，常规路径不触发截断；仅当累积突破 33MB 病理场景才出现 {@code output truncated} 标记。
 *
 * <p>实现偏离（Java-idiom）：CC 用不可变 {@code string} {@code +=} 拼接，Java 用 {@link StringBuilder}
 * 避免 O(n²)；{@code append(Buffer)} 在 Java 端无等价（调用方已解码为 String）。
 */
public final class EndTruncatingAccumulator {

    /**
     * 内存累积上限 · CC original: {@code MAX_STRING_LENGTH = 2 ** 25}
     * （Open-ClaudeCode/src/utils/stringUtils.ts:88 = 33,554,432 字符 ≈ 33MB）。
     */
    public static final int MAX_STRING_LENGTH = 33_554_432;

    /** 截断标记模板 · CC original: {@code '\n... [output truncated - ${truncatedKB}KB removed]'}
     * （stringUtils.ts:188，N 为 KB 四舍五入值）。 */
    private static final String TRUNCATION_MARKER_PREFIX = "\n... [output truncated - ";
    private static final String TRUNCATION_MARKER_SUFFIX = "KB removed]";

    /** maxSize 上限（字符数）。 */
    private final int maxSize;

    /** 已累积内容（CC {@code content}，Java 用 StringBuilder 避免 O(n²) 拼接）。 */
    private final StringBuilder content = new StringBuilder();

    /** 是否已发生截断（CC {@code isTruncated}，stringUtils.ts:142）。 */
    private boolean isTruncated = false;

    /** 累计接收字符数（含被截断丢弃的部分，CC {@code totalBytesReceived}，stringUtils.ts:143）。 */
    private long totalBytesReceived = 0;

    /** 缺省构造器 · CC original: {@code constructor(private readonly maxSize: number = MAX_STRING_LENGTH)}
     * （stringUtils.ts:149）。 */
    public EndTruncatingAccumulator() {
        this(MAX_STRING_LENGTH);
    }

    /**
     * 指定上限构造器（测试可注入小 maxSize）。
     *
     * @param maxSize 截断触发上限（字符数）；CC original: {@code maxSize}（stringUtils.ts:149）
     */
    public EndTruncatingAccumulator(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * 追加数据 · CC original: {@code append(data)}（stringUtils.ts:156-176）。
     *
     * <p>语义（逐字对齐）:
     * <ol>
     *   <li>{@code totalBytesReceived += data.length}（:158）——即使后续被截断也计入总量，供标记 N 计算</li>
     *   <li>已截断且 content.length ≥ maxSize → 直接 return（:161-163）</li>
     *   <li>{@code content.length + data.length > maxSize} → 只拼剩余空间前缀 + isTruncated=true
     *       （:166-172）；否则全量拼接（:173-175）</li>
     * </ol>
     *
     * @param data 追加的字符串（null → no-op，防御）
     */
    public void append(String data) {
        if (data == null) {
            return;
        }
        this.totalBytesReceived += data.length();
        // 已截断且已达容量 → 不再修改 content（CC :161-163）
        if (this.isTruncated && this.content.length() >= this.maxSize) {
            return;
        }
        // 添加会超上限 → 只拼可放下的前缀（CC :166-172）
        if (this.content.length() + data.length() > this.maxSize) {
            int remainingSpace = this.maxSize - this.content.length();
            if (remainingSpace > 0) {
                this.content.append(data, 0, remainingSpace);
            }
            this.isTruncated = true;
        } else {
            this.content.append(data);
        }
    }

    /**
     * 返回累积字符串 · CC original: {@code toString()}（stringUtils.ts:181-189）。
     *
     * <p>未截断 → 原样返回 content；已截断 → content + {@code "\n... [output truncated - NKB removed]"}，
     * 其中 {@code N = Math.round((totalBytesReceived - maxSize) / 1024)}（CC :186-188，四舍五入到整数 KB）。
     */
    @Override
    public String toString() {
        if (!this.isTruncated) {
            return this.content.toString();
        }
        long truncatedBytes = this.totalBytesReceived - this.maxSize;
        long truncatedKB = Math.round(truncatedBytes / 1024.0);
        return this.content + TRUNCATION_MARKER_PREFIX + truncatedKB + TRUNCATION_MARKER_SUFFIX;
    }

    /** 清空累积 · CC original: {@code clear()}（stringUtils.ts:194-198）。 */
    public void clear() {
        this.content.setLength(0);
        this.isTruncated = false;
        this.totalBytesReceived = 0;
    }

    /** 当前已累积内容长度 · CC original: {@code get length()}（stringUtils.ts:203-205）。 */
    public int length() {
        return this.content.length();
    }

    /** 是否已发生截断 · CC original: {@code get truncated()}（stringUtils.ts:210-212）。 */
    public boolean truncated() {
        return this.isTruncated;
    }

    /** 累计接收字符数（截断前总量）· CC original: {@code get totalBytes()}（stringUtils.ts:217-219）。 */
    public long totalBytes() {
        return this.totalBytesReceived;
    }
}
