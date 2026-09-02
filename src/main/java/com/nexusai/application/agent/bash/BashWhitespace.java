package com.nexusai.application.agent.bash;

/**
 * JS {@code \s}（legacy regex，无 u 标志）空白值域统一判定 · 单一事实源（G3-2）。
 *
 * <p>CC original: {@code tools/BashTool/bashSecurity.ts} 各 {@code /\s/} 字面量 +
 * {@code UNICODE_WS_RE}（bashSecurity.ts:1899-1901）。bashSecurity 各 {@code /\s/} 单字符
 * 测试与 {@code BashParser.tokenize}/{@code splitForSecurity} 的空白扫描必须同值域，否则
 * 同一输入两侧判定不一致（安全校验器按 JS {@code \s} 拦截、tokenizer 按 Java
 * {@code Character.isWhitespace} 切词，造成解析差异绕过）。
 *
 * <p>JS {@code \s} 与 {@code Character.isWhitespace} 双向不等价：
 * <ul>
 *   <li>Java 多含 FS/GS/RS/US（U+001C–U+001F，JS {@code \s} 不含）；</li>
 *   <li>Java 缺含 NBSP(U+00A0)/图空格(U+2007)/窄不换行空格(U+202F)/U+FEFF（JS {@code \s} 含）。</li>
 * </ul>
 * 统一以 JS {@code \s} 值域为准。
 *
 * <p>值域：ASCII 空白 = space / tab(U+0009) / LF(U+000A) / CR(U+000D) / FF(U+000C) /
 * VT(U+000B)；Unicode 空白 = NBSP(U+00A0) / U+1680 / U+2000–U+200A / U+2028 / U+2029 /
 * U+202F / U+205F / U+3000 / U+FEFF。
 */
public final class BashWhitespace {

    private BashWhitespace() {
        throw new AssertionError("utility class - do not instantiate");
    }

    /** JS {@code \s} 的 Unicode 空白段（不含方括号）· CC original: UNICODE_WS_RE (bashSecurity.ts:1899-1901)。 */
    public static final String UNICODE_WS_CHARS =
            "\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

    /** JS {@code \s} 完整字符段（ASCII + Unicode，不含方括号）。 */
    public static final String WS_CLASS_CHARS = " \\t\\n\\r\\f\\x0B" + UNICODE_WS_CHARS;

    /** JS {@code \s} 精确字符类（含方括号）。 */
    public static final String WS_CLASS = "[" + WS_CLASS_CHARS + "]";

    /** JS {@code \S} 精确补集字符类（含方括号）。 */
    public static final String NOT_WS_CLASS = "[^" + WS_CLASS_CHARS + "]";

    /**
     * 单字符 JS {@code \s} 判定 · CC original: bashSecurity.ts 各 {@code /\s/} 单字符测试。
     *
     * <p>不使用 {@code Character.isWhitespace}——与 JS {@code \s} 值域双向不符（见类注释）。
     * Unicode 段用十六进制码点，避开 Java 源码 {@code  }/{@code  } 换行符陷阱。
     */
    public static boolean isBashWhitespace(char c) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) {
            return true;
        }
        // Unicode 空白集，与 UNICODE_WS_RE 同源，对齐 JS \s 值域
        return c == 0x00A0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }
}
