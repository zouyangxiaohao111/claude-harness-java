package com.nexusai.infra.util;

/**
 * 终端显示宽度计算 · 对齐 CC ink/stringWidth.ts stringWidth()（eastAsianWidth CJK=2，
 * ambiguousAsWide:false → 歧义宽度按 1）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:SkillCatalog 字符预算若用 {@code String.length()}
 * 计宽，中文技能名/描述的预算与实际终端列宽不一致（1 个 CJK 字符 = 2 终端列），CC
 * formatCommandsWithinBudget（prompt.ts:85/:107/:118）用 stringWidth 计宽。本类为 Java 等价物，
 * 供 SkillCatalog 预算计算与宽度感知截断复用。
 *
 * <p>实现对齐 CC stringWidthJavaScript（ink/stringWidth.ts:20-90）：
 * <ul>
 *   <li>ASCII fast path（:25-45）：纯可打印 ASCII（0x20-0x7E）逐字符计 1，控制符 ≤0x1F 计 0</li>
 *   <li>eastAsianWidth W/F 宽字符计 2（:56-65）：CJK 表意（0x4E00-0x9FFF / 0x3400-0x4DBF /
 *       0xF900-0xFAFF / 0x20000-0x3FFFD）、假名（0x3040-0x30FF）、谚文（0xAC00-0xD7A3）、
 *       全角符号（0xFF00-0xFF60 / 0xFFE0-0xFFE6）等；ambiguousAsWide:false → 歧义宽按 1</li>
 *   <li>zero-width 排除（:129-203）：组合符（0x0300-0x036F 等）、ZWJ/ZWS（0x200B-0x200D）、
 *       变体选择符（0xFE00-0xFE0F）、BOM（0xFEFF）、控制符（≤0x1F / 0x7F-0x9F）计 0</li>
 *   <li>emoji 宽 2（:67-127）：ZWJ 序列按单簇计 2（family 👨👩👧），区域指示符单=1 成对=2
 *       （flag 🇺🇸，CC getEmojiWidth stringWidth.ts:93-101：single=1 pair=2）</li>
 * </ul>
 */
public final class StringWidth {

    private StringWidth() {
        // 静态工具类
    }

    /**
     * 获取字符串的终端显示宽度 · 对齐 CC stringWidth()（ink/stringWidth.ts:220-222）。
     *
     * @param str 待测字符串（null → 0）
     * @return 终端列宽
     */
    public static int stringWidth(String str) {
        if (str == null || str.isEmpty()) {
            return 0;
        }
        // ASCII fast path（CC :25-45）：无非 ASCII / ANSI 转义（0x1b）→ 逐可打印字符计 1
        boolean pureAscii = true;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= 0x80 || c == 0x1b) {
                pureAscii = false;
                break;
            }
        }
        if (pureAscii) {
            int width = 0;
            for (int i = 0; i < str.length(); i++) {
                if (str.charAt(i) > 0x1f) {
                    width++;
                }
            }
            return width;
        }

        int width = 0;
        boolean skipNext = false;   // ZWJ 后随字符属同一簇（CC :78-87 单 glyph 计数）
        boolean prevRi = false;     // 区域指示符成对计 2（CC :107-113 flag）
        int i = 0;
        int len = str.length();
        while (i < len) {
            int cp = str.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == 0x200d) {          // ZWJ（zero-width 已排除，先在此显式置标记）
                skipNext = true;
                prevRi = false;
                continue;
            }
            if (isZeroWidth(cp)) {
                prevRi = false;
                continue;
            }
            if (skipNext && isEmojiLike(cp)) {   // ZWJ 簇后续 emoji 不另计（CC grapheme 聚类）
                skipNext = false;
                prevRi = false;
                continue;
            }
            if (isRegionalIndicator(cp) && prevRi) { // RI 成对 → 第二个 RI 不新增 glyph，但补齐第 2 列（flag 共 2 列）
                width += 1; // CC getEmojiWidth：regional indicators single=1 pair=2（stringWidth.ts:93-101）
                prevRi = false;
                continue;
            }
            skipNext = false;
            prevRi = isRegionalIndicator(cp);
            width += widthOf(cp);
        }
        return width;
    }

    /**
     * 宽度感知截断 · 对齐 CC truncateToWidth（utils/truncate.ts:63-75）——
     * 按终端列宽累加 code point，超 {@code maxWidth - 1}（留 '…' 位）截断并追加 '…'；不劈代理对。
     *
     * <p>SkillCatalog.truncate（对齐 CC truncate，prompt.ts 消费链）委托本方法。
     *
     * @param text     待截断文本
     * @param maxWidth 目标最大终端列宽（≤0 → '…'）
     * @return 未超宽 → 原串；超宽 → 前缀 + '…'
     */
    public static String truncateToWidth(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (stringWidth(text) <= maxWidth) {
            return text;
        }
        if (maxWidth <= 1) {
            return "…";
        }
        StringBuilder sb = new StringBuilder();
        int width = 0;
        boolean skipNext = false;
        boolean prevRi = false;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == 0x200d) {
                skipNext = true;
                prevRi = false;
                continue;
            }
            if (isZeroWidth(cp)) {
                prevRi = false;
                continue;
            }
            int w;
            if (skipNext && isEmojiLike(cp)) {
                skipNext = false;
                prevRi = false;
                w = 0;
            } else if (isRegionalIndicator(cp) && prevRi) {
                // RI 成对 → 不追加第二个 RI（flag 是单 grapheme），但预算消耗第 2 列（pair=2）
                prevRi = false;
                w = 1;
            } else {
                skipNext = false;
                prevRi = isRegionalIndicator(cp);
                w = widthOf(cp);
            }
            if (width + w > maxWidth - 1) {
                break; // 留 '…' 位（CC truncateToWidth :70-73）
            }
            sb.appendCodePoint(cp);
            width += w;
        }
        return sb.append('…').toString();
    }

    /** 单 code point 终端列宽（EastAsianWidth W/F + emoji → 2，其余 → 1；ambiguousAsWide:false） */
    private static int widthOf(int cp) {
        // Emoji 区（CC needsSegmentation :92-104 同类范围）→ 2
        if ((cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)) {
            return 2;
        }
        // EastAsianWidth W/F（宽字符）→ 2；Hangul Jamo 块（0x1100-0x115F）同 wcwidth 宽
        if ((cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)   // CJK 部首/康熙/符号/假名/注音/CJK 笔画/表意/彝文
                || (cp >= 0xAC00 && cp <= 0xD7A3)   // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)   // CJK 兼容表意
                || (cp >= 0xFE30 && cp <= 0xFE4F)   // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)   // 全角形式
                || (cp >= 0xFFE0 && cp <= 0xFFE6)   // 全角符号
                || (cp >= 0x20000 && cp <= 0x3FFFD) // CJK 扩展 B-F
                ) {
            return 2;
        }
        return 1;
    }

    /** 零宽字符（CC isZeroWidth ink/stringWidth.ts:129-203 等价子集） */
    private static boolean isZeroWidth(int cp) {
        if (cp >= 0x20 && cp < 0x7f) return false;
        if (cp >= 0xa0 && cp < 0x300) return cp == 0x00ad; // 软连字符
        // 控制字符
        if (cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f)) return true;
        // 零宽空间/连接符/BOM/词连接符
        if ((cp >= 0x200b && cp <= 0x200d) || cp == 0xfeff || (cp >= 0x2060 && cp <= 0x2064)) return true;
        // 变体选择符
        if ((cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xe0100 && cp <= 0xe01ef)) return true;
        // 组合附加符号
        if ((cp >= 0x300 && cp <= 0x36f)
                || (cp >= 0x1ab0 && cp <= 0x1aff)
                || (cp >= 0x1dc0 && cp <= 0x1dff)
                || (cp >= 0x20d0 && cp <= 0x20ff)
                || (cp >= 0xfe20 && cp <= 0xfe2f)) {
            return true;
        }
        return false;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isEmojiLike(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || isRegionalIndicator(cp);
    }
}
