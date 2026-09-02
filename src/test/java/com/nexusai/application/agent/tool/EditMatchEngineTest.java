package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EditMatchEngine} 单测 · 对齐 CC {@code FileEditTool/utils.ts}
 * （normalizeQuotes :31-37 / stripTrailingWhitespace :44-63 / findActualString :73-93 /
 * preserveQuoteStyle :104-141 / applyEditToFile :206-228 / getPatchForEdits :262-353 /
 * DESANITIZATIONS :531-550 / desanitizeMatchString :557-574 / areFileEditsEquivalent :663-730）。
 *
 * <p>WHY（规则九 · 验证意图）：Edit 匹配从裸 indexOf 升级为 CC 完整算法，核心不变量是
 * (1) 文件内弯引号能通过 quote 归一化命中并保真；(2) 模型输出被 sanitize 的占位串
 * （fnr/name/output 等）能 desanitize 后命中且 new_string 同步应用替换；(3) 空 new_string
 * 删除时不得残留空行；(4) 连续多 edit 的 old ⊆ 前次 new 必须抛错（防重叠写坏文件）。
 * 任何一条漂移都会让 Edit 从"改不动"或"改坏文件"两个方向破坏用户数据，必须锁定。
 */
@DisplayName("EditMatchEngine · 匹配算法对齐 CC FileEditTool/utils.ts")
class EditMatchEngineTest {

    // ── normalizeQuotes ──────────────────────────────────────────────

    @Test
    @DisplayName("normalizeQuotes: 四个弯引号全部转直引号, 长度不变 (utils.ts:31-37)")
    void normalizeQuotesConvertsCurlyToStraight() {
        String src = "‘a’ “b”"; // ‘a’ “b”
        String norm = EditMatchEngine.normalizeQuotes(src);
        assertThat(norm).isEqualTo("'a' \"b\"");
        // replaceAll 长度不变: 每个弯引号 1:1 替换
        assertThat(norm).hasSameSizeAs(src);
    }

    // ── findActualString ─────────────────────────────────────────────

    @Test
    @DisplayName("findActualString: 精确命中直接返回搜索串 (utils.ts:76-78)")
    void findActualStringExactMatch() {
        assertThat(EditMatchEngine.findActualString("hello world", "world")).isEqualTo("world");
    }

    @Test
    @DisplayName("findActualString: 文件含弯引号+搜索串直引号 → 返回文件真实子串 (utils.ts:80-88)")
    void findActualStringQuoteNormalizedReturnsFileSubstring() {
        String fileContent = "say “hello” now"; // say “hello” now
        // 模型用直引号搜索 "hello", 文件里是弯引号
        String actual = EditMatchEngine.findActualString(fileContent, "\"hello\"");
        assertThat(actual).isEqualTo("“hello”"); // 返回文件真实弯引号子串
    }

    @Test
    @DisplayName("findActualString: 归一化后仍未命中 → null (utils.ts:90-91)")
    void findActualStringNotFoundReturnsNull() {
        assertThat(EditMatchEngine.findActualString("hello world", "nope")).isNull();
    }

    // ── preserveQuoteStyle ───────────────────────────────────────────

    @Test
    @DisplayName("preserveQuoteStyle: old==actual → new 原样返回, 无归一化发生 (utils.ts:106-109)")
    void preserveQuoteStyleOldEqualsActualReturnsNew() {
        String newString = "it is \"done\" now";
        assertThat(EditMatchEngine.preserveQuoteStyle("old", "old", newString)).isSameAs(newString);
    }

    @Test
    @DisplayName("preserveQuoteStyle: 文件用弯双引号 → new 的直引号按开合启发转弯引号 (utils.ts:111-139)")
    void preserveQuoteStyleAppliesCurlyDoubleQuotes() {
        String oldString = "\"hello\"";                 // 模型直引号
        String actualOldString = "“hello”";    // 文件弯引号 “hello”
        String newString = "\"world\"";
        // 字符串开头=开引号上下文 → “；末尾无后续=闭引号上下文 → ”
        assertThat(EditMatchEngine.preserveQuoteStyle(oldString, actualOldString, newString))
            .isEqualTo("“world”");
    }

    @Test
    @DisplayName("preserveQuoteStyle: 单引号收缩格 (don't) → 右弯引号, 开引号 → 左弯引号 (utils.ts:171-194)")
    void preserveQuoteStyleSingleQuoteContractionGuard() {
        String oldString = "'it'";              // 模型直引号
        String actualOldString = "‘it’"; // 文件 ‘it’
        // 收缩格: don't 中间撇号保持右弯; 句首开引号转左弯
        String newString = "don't say 'it'";
        assertThat(EditMatchEngine.preserveQuoteStyle(oldString, actualOldString, newString))
            .isEqualTo("don’t say ‘it’");
    }

    // ── stripTrailingWhitespace ──────────────────────────────────────

    @Test
    @DisplayName("stripTrailingWhitespace: 去每行尾随空白但保留行尾 CRLF/LF/CR (utils.ts:44-63)")
    void stripTrailingWhitespacePreservesLineEndings() {
        // CRLF 行
        String crlf = "a  \r\nb\t\r\nc \r\n";
        assertThat(EditMatchEngine.stripTrailingWhitespace(crlf)).isEqualTo("a\r\nb\r\nc\r\n");
        // LF 行
        String lf = "x \ny\t\nz \n";
        assertThat(EditMatchEngine.stripTrailingWhitespace(lf)).isEqualTo("x\ny\nz\n");
        // CR 行
        String cr = "m \rn\t\r";
        assertThat(EditMatchEngine.stripTrailingWhitespace(cr)).isEqualTo("m\rn\r");
    }

    @Test
    @DisplayName("stripTrailingWhitespace: 行内前导空白不受影响 (utils.ts:44-63)")
    void stripTrailingWhitespaceKeepsLeadingSpaces() {
        assertThat(EditMatchEngine.stripTrailingWhitespace("  keep  \n  keep2 \n"))
            .isEqualTo("  keep\n  keep2\n");
    }

    // ── applyEditToFile ──────────────────────────────────────────────

    @Test
    @DisplayName("applyEditToFile: 空 new_string 且 old 不以\\n结尾+file含old\\n → 替换 old\\n 防残留空行 (utils.ts:211-227)")
    void applyEditToFileEmptyNewStringStripsTrailingLine() {
        String fileContent = "line1\nremove-me\nline3\n";
        String result = EditMatchEngine.applyEditToFile(fileContent, "remove-me\n", "", false);
        // CC 逻辑: oldString 不以 \n 结尾且 file 含 old+\n → 整体删掉 old+\n, 不留空行
        String result2 = EditMatchEngine.applyEditToFile(fileContent, "remove-me", "", false);
        assertThat(result2).isEqualTo("line1\nline3\n");
        // 但若 old 本身含 \n, 无特判, 直接删除 old
        assertThat(result).isEqualTo("line1\nline3\n");
    }

    @Test
    @DisplayName("applyEditToFile: 非空 new_string 且 replaceAll=false → 只替换首个出现 (utils.ts:207-209)")
    void applyEditToFileReplacesFirstOccurrenceOnly() {
        String result = EditMatchEngine.applyEditToFile("a b a b", "a", "X", false);
        assertThat(result).isEqualTo("X b a b");
    }

    @Test
    @DisplayName("applyEditToFile: replaceAll=true → 替换全部 (utils.ts:207-209)")
    void applyEditToFileReplaceAll() {
        String result = EditMatchEngine.applyEditToFile("a b a b", "a", "X", true);
        assertThat(result).isEqualTo("X b X b");
    }

    @Test
    @DisplayName("applyEditToFile: new_string 含 $ 按字面处理, 不解释引用 (CC 函数式 replace 语义)")
    void applyEditToFileDollarLiteral() {
        String result = EditMatchEngine.applyEditToFile("x is y", "y", "$& $1", false);
        assertThat(result).isEqualTo("x is $& $1");
    }

    // ── desanitizeMatchString ────────────────────────────────────────

    @Test
    @DisplayName("desanitizeMatchString: DESANITIZATIONS 18 项逐个 replaceAll (utils.ts:531-550)")
    void desanitizeMatchStringAppliesAllEntries() {
        String input = "<fnr><n></n><o></o><e></e><s></s><r></r>"
            + "< META_START >< META_END >< EOT >< META >< SOS >\n\nH:\n\nA:";
        EditMatchEngine.DesanitizedMatch m = EditMatchEngine.desanitizeMatchString(input);
        assertThat(m.result()).isEqualTo(
            "<function_results><name></name><output></output><error></error>"
                + "<system></system><result></result>"
                + "<META_START><META_END><EOT><META><SOS>\n\nHuman:\n\nAssistant:");
        // 18 项全部应用
        assertThat(m.appliedReplacements()).hasSize(18);
    }

    @Test
    @DisplayName("desanitizeMatchString: 无匹配项时 appliedReplacements 为空, 结果原样 (utils.ts:557-574)")
    void desanitizeMatchStringNoop() {
        EditMatchEngine.DesanitizedMatch m = EditMatchEngine.desanitizeMatchString("plain text");
        assertThat(m.result()).isEqualTo("plain text");
        assertThat(m.appliedReplacements()).isEmpty();
    }

    // ── normalizeEdit (normalizeFileEditInput) ───────────────────────

    @Test
    @DisplayName("normalizeEdit: 精确命中 → new 仅 stripTrailingWhitespace (utils.ts:611-617)")
    void normalizeEditExactMatchStripsNewTrailingWhitespace() {
        EditMatchEngine.NormalizedEdit e = EditMatchEngine.normalizeEdit(
            "file.txt", "hello world", "world", "world \t ");
        assertThat(e.oldString()).isEqualTo("world");
        assertThat(e.newString()).isEqualTo("world");
    }

    @Test
    @DisplayName("normalizeEdit: 精确失败 → desanitize old, 相同替换应用到 new (utils.ts:619-634)")
    void normalizeEditDesanitizeAppliesToNew() {
        String fileContent = "<function_results> ok";
        EditMatchEngine.NormalizedEdit e = EditMatchEngine.normalizeEdit(
            "file.txt", fileContent, "<fnr>", "<fnr> done");
        assertThat(e.oldString()).isEqualTo("<function_results>");
        assertThat(e.newString()).isEqualTo("<function_results> done");
    }

    @Test
    @DisplayName("normalizeEdit: .md/.mdx 跳过 stripTrailingWhitespace (utils.ts:598-600)")
    void normalizeEditSkipsStripForMarkdown() {
        EditMatchEngine.NormalizedEdit e = EditMatchEngine.normalizeEdit(
            "note.md", "title", "title", "title  ");
        assertThat(e.newString()).isEqualTo("title  "); // markdown 保留尾部双空格硬换行语义
    }

    // ── getPatchForEdits ─────────────────────────────────────────────

    @Test
    @DisplayName("getPatchForEdits: 子串守卫 —— old⊆前次 new → 抛错 (utils.ts:278-290)")
    void getPatchForEditsSubstringGuardThrows() {
        List<EditMatchEngine.EditMatch> edits = List.of(
            new EditMatchEngine.EditMatch("foo", "foobar", false),
            new EditMatchEngine.EditMatch("bar", "baz", false)
        );
        assertThatThrownBy(() -> EditMatchEngine.getPatchForEdits("foo qux", edits))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("old_string is a substring of a new_string");
    }

    @Test
    @DisplayName("getPatchForEdits: 空文件特例 —— 单 edit old='' new='' → updatedFile='' 不抛错 (utils.ts:265-276)")
    void getPatchForEditsEmptyFileSpecialCase() {
        EditMatchEngine.EditMatchResult r = EditMatchEngine.getPatchForEdits(
            "", List.of(new EditMatchEngine.EditMatch("", "", false)));
        assertThat(r.updatedFile()).isEmpty();
    }

    @Test
    @DisplayName("getPatchForEdits: 未变更抛错 —— old 不在文件中 (utils.ts:301-304)")
    void getPatchForEditsNoChangeThrows() {
        assertThatThrownBy(() -> EditMatchEngine.getPatchForEdits(
            "hello", List.of(new EditMatchEngine.EditMatch("nope", "x", false))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("String not found in file");
    }

    @Test
    @DisplayName("getPatchForEdits: 整体未变更抛错 —— 逐条 edit 各自改变但净结果与原文相同 (utils.ts:306-309)")
    void getPatchForEditsResultEqualsOriginalThrows() {
        // 单条 old==new 在 per-edit 检查即抛 "String not found" (utils.ts:301-304);
        // "match exactly" 只能由多条 edit 净效果互相抵消触发。
        assertThatThrownBy(() -> EditMatchEngine.getPatchForEdits(
            "abcd",
            List.of(new EditMatchEngine.EditMatch("ab", "xy", false),
                new EditMatchEngine.EditMatch("xycd", "abcd", false))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("match exactly");
    }

    @Test
    @DisplayName("getPatchForEdits: 正常编辑 → updatedFile + 显示 patch (utils.ts:311-351)")
    void getPatchForEditsAppliesAndReturnsPatch() {
        EditMatchEngine.EditMatchResult r = EditMatchEngine.getPatchForEdits(
            "line1\nline2\n", List.of(new EditMatchEngine.EditMatch("line2", "CHANGED", false)));
        assertThat(r.updatedFile()).isEqualTo("line1\nCHANGED\n");
        assertThat(r.patch()).isNotEmpty();
    }

    @Test
    @DisplayName("getPatchForEdits: 空文件 + 空 old + 非空 new → 插入内容 (applyEditToFile old=='' 分支 utils.ts:298)")
    void getPatchForEditsEmptyOldInsertion() {
        EditMatchEngine.EditMatchResult r = EditMatchEngine.getPatchForEdits(
            "", List.of(new EditMatchEngine.EditMatch("", "new-content", false)));
        assertThat(r.updatedFile()).isEqualTo("new-content");
    }

    // ── areFileEditsEquivalent ───────────────────────────────────────

    @Test
    @DisplayName("areFileEditsEquivalent: 字面相同 → true (utils.ts:671-684)")
    void areFileEditsEquivalentLiteralEqual() {
        List<EditMatchEngine.EditMatch> e1 = List.of(new EditMatchEngine.EditMatch("a", "b", false));
        assertThat(EditMatchEngine.areFileEditsEquivalent(e1, e1, "a c")).isTrue();
    }

    @Test
    @DisplayName("areFileEditsEquivalent: 语义等价 (不同 edit 产生相同结果) → true (utils.ts:711-730)")
    void areFileEditsEquivalentSemanticEqual() {
        List<EditMatchEngine.EditMatch> e1 = List.of(new EditMatchEngine.EditMatch("a", "X", true));
        List<EditMatchEngine.EditMatch> e2 = List.of(new EditMatchEngine.EditMatch("a", "X", false),
            new EditMatchEngine.EditMatch("a", "X", false));
        // "a X a X" 下: e1 全替换两处 = e2 替换两处, 结果一致
        assertThat(EditMatchEngine.areFileEditsEquivalent(e1, e2, "a b a b")).isTrue();
    }
}
