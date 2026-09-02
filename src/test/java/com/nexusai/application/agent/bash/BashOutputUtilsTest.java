package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BashOutputUtils 行为测试 · 验证 WHY=对齐 CC tools/BashTool/utils.ts 输出格式化语义.
 *
 * <p>WHY 本测试存在: 这些函数是 BashTool.execute 输出通道的边界（stripEmptyLines →
 * isImageOutput → formatOutput / buildImageToolResult）。若语义漂移（如 trim 误判、
 * 截断行数错计、image 前缀锚丢失），LLM 会看到截断错位或 image 块构造失败。
 */
class BashOutputUtilsTest {

    // ───────────────────────── stripEmptyLines · CC utils.ts:22-44 ─────────────────────────

    @Test
    @DisplayName("stripEmptyLines 去除首尾纯空白行，保留行内空白")
    void stripEmptyLines_removesLeadingTrailingBlankLines() {
        // WHY: CC utils.ts:22-44 只删首尾「完全空白」行（trim()==''），
        //      行内空白必须保留（"  hello  " 是内容行）。
        String out = BashOutputUtils.stripEmptyLines("\n\n  hello  \nworld\n\n  \n");
        assertEquals("  hello  \nworld", out);
    }

    @Test
    @DisplayName("stripEmptyLines 全空行 → 空串")
    void stripEmptyLines_allBlank_returnsEmpty() {
        assertEquals("", BashOutputUtils.stripEmptyLines("\n\n  \n"));
        assertEquals("", BashOutputUtils.stripEmptyLines(""));
    }

    @Test
    @DisplayName("stripEmptyLines null → 空串（防御）")
    void stripEmptyLines_null_returnsEmpty() {
        assertEquals("", BashOutputUtils.stripEmptyLines(null));
    }

    // ───────────────────────── isImageOutput · CC utils.ts:49-51 ─────────────────────────

    @Test
    @DisplayName("isImageOutput 标准 data URI → true（前缀锚 find）")
    void isImageOutput_validDataUri_true() {
        // WHY: CC utils.ts:50 `/^data:image\/[a-z0-9.+_-]+;base64,/i` 前缀 test。
        assertTrue(BashOutputUtils.isImageOutput("data:image/png;base64,AAAA"));
    }

    @Test
    @DisplayName("isImageOutput 尾部换行仍 true（换行边界 · 旧 .matches 会 false）")
    void isImageOutput_trailingNewline_stillTrue() {
        // WHY: 旧 Java `content.trim().matches("^...base64,.*")` 中 `.` 不跨行，
        //      base64 后有 \n 会整串 false；CC test() 只测前缀不管尾部。
        //      这是探查 D3 指出的换行边界偏差。
        assertTrue(BashOutputUtils.isImageOutput("data:image/png;base64,AAAA\n\n"));
    }

    @Test
    @DisplayName("isImageOutput 前导空白 → false（CC 不 trim，旧 Java trim 会误判 true）")
    void isImageOutput_leadingWhitespace_false() {
        // WHY: CC 直接 test(content) 不 trim；前导空格使 `^` 锚不命中 → false。
        //      旧 Java trim() 后 matches 误判 true（trim 语义偏差，探查 D3）。
        assertFalse(BashOutputUtils.isImageOutput("  data:image/png;base64,AAAA"));
    }

    @Test
    @DisplayName("isImageOutput 大小写不敏感（CC i 旗标）")
    void isImageOutput_caseInsensitive_true() {
        assertTrue(BashOutputUtils.isImageOutput("DATA:IMAGE/PNG;BASE64,AAAA"));
        assertTrue(BashOutputUtils.isImageOutput("data:image/jpeg;base64,AAAA"));
    }

    @Test
    @DisplayName("isImageOutput 非 image 文本 → false")
    void isImageOutput_nonImage_false() {
        assertFalse(BashOutputUtils.isImageOutput("hello world"));
        assertFalse(BashOutputUtils.isImageOutput("data:application/pdf;base64,AAAA"));
        assertFalse(BashOutputUtils.isImageOutput(null));
    }

    // ───────────────────────── formatOutput · CC utils.ts:133-165 ─────────────────────────

    @Test
    @DisplayName("formatOutput 未超阈值 → totalLines=换行数+1，内容原样")
    void formatOutput_withinLimit_keepsContent() {
        // WHY: CC utils.ts:148-154 totalLines=countCharInString(content,'\n')+1。
        BashOutputUtils.FormattedOutput f = BashOutputUtils.formatOutput("line1\nline2\nline3", 30_000);
        assertEquals(3, f.totalLines());
        assertEquals("line1\nline2\nline3", f.truncatedContent());
        assertFalse(f.isImage());
    }

    @Test
    @DisplayName("formatOutput 超阈值 → 截断 + [... N lines truncated] + totalLines 全量")
    void formatOutput_overLimit_truncatesWithLineCount() {
        // WHY: CC utils.ts:156-164 truncatedPart=前 maxOutputLength 字符，
        //      remainingLines=从 maxOutputLength 起的换行数+1，totalLines 仍计全量换行+1。
        //      每行 120 字符 × 400 行 = 48000 字符 > 30000，确保触发截断。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("line").append(i).append(' ');
            for (int j = 0; j < 100; j++) sb.append('x');
            sb.append('\n');
        }
        String content = sb.toString();
        assertTrue(content.length() > 30_000, "前置: 内容长度须超阈值才测截断");
        BashOutputUtils.FormattedOutput f = BashOutputUtils.formatOutput(content, 30_000);
        // CC utils.ts:161 totalLines = countCharInString(content, '\n') + 1。
        // 400 行每行尾 '\n' → 400 个换行 +1 = 401。
        assertEquals(401, f.totalLines());
        assertTrue(f.truncatedContent().contains("lines truncated] ..."));
        // 截断后内容必须短于原文（30k 截断生效）
        assertTrue(f.truncatedContent().length() < content.length());
    }

    @Test
    @DisplayName("formatOutput 单参签名默认阈值 30000（CC getMaxOutputLength 默认）")
    void formatOutput_singleArg_defaultThreshold30000() {
        // WHY: CC utils.ts:133-136 单参，内部 getMaxOutputLength()=30000（outputLimits.ts:5）。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("line").append(i).append(' ');
            for (int j = 0; j < 100; j++) sb.append('x');
            sb.append('\n');
        }
        BashOutputUtils.FormattedOutput f = BashOutputUtils.formatOutput(sb.toString());
        assertTrue(f.truncatedContent().contains("lines truncated] ..."));
    }

    @Test
    @DisplayName("formatOutput image 输出 → totalLines=1 isImage=true 内容原样")
    void formatOutput_imageOutput_markImage() {
        // WHY: CC utils.ts:138-145 isImage 短路，不截断 base64。
        BashOutputUtils.FormattedOutput f = BashOutputUtils.formatOutput("data:image/png;base64,AAAA");
        assertEquals(1, f.totalLines());
        assertTrue(f.isImage());
        assertEquals("data:image/png;base64,AAAA", f.truncatedContent());
    }

    // ───────────────────────── buildImageToolResult · CC utils.ts:71-91 ─────────────────────────

    @Test
    @DisplayName("buildImageToolResult 解析 data URI → mediaType + base64（无 data: 前缀）")
    void buildImageToolResult_validDataUri_extractsMediaAndData() {
        // WHY: CC utils.ts:74-90 parseDataUri 拆 mediaType + data，data 无 `data:image/...;base64,` 前缀。
        BashOutputUtils.ImageToolResult img =
            BashOutputUtils.buildImageToolResult("data:image/png;base64,QUJD", "tool-1");
        assertEquals("tool-1", img.toolUseId());
        assertEquals("image/png", img.mediaType());
        assertEquals("QUJD", img.data());
    }

    @Test
    @DisplayName("buildImageToolResult 非 data URI → null（caller 回退文本）")
    void buildImageToolResult_invalid_returnsNull() {
        assertNull(BashOutputUtils.buildImageToolResult("plain text", "tool-1"));
        assertNull(BashOutputUtils.buildImageToolResult(null, "tool-1"));
    }

    // ───────────────────────── parseDataUri · CC utils.ts:53-65 ─────────────────────────

    @Test
    @DisplayName("parseDataUri 解析 mediaType + base64 payload")
    void parseDataUri_extractsParts() {
        // WHY: CC utils.ts:59-65 s.trim().match(/^data:([^;]+);base64,(.+)$/)。
        BashOutputUtils.DataUri d = BashOutputUtils.parseDataUri("data:image/jpeg;base64,MTIz");
        assertEquals("image/jpeg", d.mediaType());
        assertEquals("MTIz", d.data());
    }
}
