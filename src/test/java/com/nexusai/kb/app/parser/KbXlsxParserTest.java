package com.nexusai.kb.app.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * xlsx 逐行渲染 + 行号定位 + 坏包修复 · 对齐源站 builtin {@code excel_parser.py} / {@code xlsx_repair.py}。
 *
 * <p><b>WHY 断言落在逐字文本上</b>：
 * <ol>
 *   <li>「解析成功 / 文本非空」对坏实现同样全绿 —— 源站形态是 {@code A: 值,B: 值}，
 *       退回 Markdown 表或空格拼接都能过「非空」，必须逐字比。</li>
 *   <li>行号必须在<b>块</b>上断言（locator.rowStart），只断文本永远测不出「行号偏一行」。</li>
 *   <li>首行语义默认是源站口径（第 1 行当数据、列名用列字母）；关/开两侧各一条，
 *       否则「默认值写反」不会变红。</li>
 *   <li>坏包修复必须断言「修复后能解析出原值」—— 只断言「没抛异常」时 POI 自身容错会让它假绿。</li>
 * </ol>
 *
 * <p>⛔ 本用例直接 new 解析器，不依赖 Spring（{@code @SpringBootTest} 会迁移真库，禁止）。
 */
@DisplayName("[xlsx] 逐行渲染 + 行号 locator + 坏包修复（对齐源站 builtin）")
class KbXlsxParserTest {

    private final KbXlsxParser parser = new KbXlsxParser();

    // ════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════

    /** 三行数据 · 首行是数据（默认不当表头）。 */
    private static Path writePlain(Path dir) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("项目清单");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("项目");
            row0.createCell(1).setCellValue("金额");
            row0.createCell(2).setCellValue("负责人");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("A 标段");
            row1.createCell(1).setCellValue(6000);
            row1.createCell(2).setCellValue("张三");
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("B 标段");
            row2.createCell(1).setCellValue(0);
            row2.createCell(2).setCellValue("李四");
            return write(wb, dir.resolve("项目清单.xlsx"));
        }
    }

    private static Path write(Workbook wb, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            wb.write(out);
        }
        return file;
    }

    private KbParsedDocument parse(Path file) {
        return parser.parse(file, KbParseContext.defaults());
    }

    /** 码点区间切片（⛔ 不用 char 下标：source block 的 start/end 是码点）。 */
    private static String cpSlice(String text, KbSourceBlock block) {
        int start = text.offsetByCodePoints(0, block.start());
        int end = text.offsetByCodePoints(0, block.end());
        return text.substring(start, end);
    }

    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 形态 + 行号
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认：逐行 `列名: 值,列名: 值`，列名用列字母，第 1 行当数据；每行一个 1-based 行号块")
    void rendersRowsWithColumnLettersAndRowLocators(@TempDir Path dir) throws Exception {
        KbParsedDocument doc = parse(writePlain(dir));

        assertThat(lines(doc.text())).containsExactly(
            "A: 项目,B: 金额,C: 负责人",
            "A: A 标段,B: 6000,C: 张三",
            "A: B 标段,B: 0,C: 李四");

        assertThat(doc.sourceBlocks()).hasSize(3);
        int[] expectedRows = {1, 2, 3};
        for (int i = 0; i < 3; i++) {
            KbSourceBlock block = doc.sourceBlocks().get(i);
            assertThat(block.locator().type()).isEqualTo(KbSourceLocator.TYPE_SHEET);
            assertThat(block.locator().sheet()).isEqualTo("项目清单");
            assertThat(block.locator().rowStart()).as("第 %d 个块的行号（1-based，表头行也算 1 行）", i + 1)
                .isEqualTo(expectedRows[i]);
            assertThat(block.locator().rowEnd()).isEqualTo(expectedRows[i]);
            assertThat(cpSlice(doc.text(), block)).isEqualTo(lines(doc.text()).get(i));
        }
        assertThat(doc.metadata())
            .containsEntry(KbParsedDocument.META_PARSER, "KbXlsxParser")
            .containsEntry(KbXlsxParser.META_SHEET_COUNT, "1");
    }

    @Test
    @DisplayName("空单元格跳过、整行为空跳过，但行号不压缩（空行后的数据行仍是 Excel 显示行号）")
    void emptyCellsSkippedAndRowNumbersNotCompressed(@TempDir Path dir) throws Exception {
        Path file;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("含空行");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("甲");
            row0.createCell(2).setCellValue(100);   // B 列空
            sheet.createRow(1);                      // 整行空
            Row row3 = sheet.createRow(3);           // 第 2 行（index 2）也整行空
            row3.createCell(1).setCellValue("备注");  // 只填 B 列
            file = write(wb, dir.resolve("空行.xlsx"));
        }

        KbParsedDocument doc = parse(file);

        assertThat(lines(doc.text())).containsExactly(
            "A: 甲,C: 100",
            "B: 备注");
        assertThat(doc.sourceBlocks()).hasSize(2);
        assertThat(doc.sourceBlocks().get(0).locator().rowStart()).isEqualTo(1);
        assertThat(doc.sourceBlocks().get(1).locator().rowStart())
            .as("⛔ 空行不能把行号压缩成 2：第 4 行在 Excel 里就是第 4 行")
            .isEqualTo(4);
    }

    @Test
    @DisplayName("first-row-as-header=true：第 1 行当表头（不输出），列名取表头值，数据从 Excel 第 2 行起")
    void firstRowAsHeaderWhenEnabled(@TempDir Path dir) throws Exception {
        KbXlsxParser headerParser = new KbXlsxParser();
        headerParser.firstRowAsHeader = true;

        KbParsedDocument doc = headerParser.parse(writePlain(dir), KbParseContext.defaults());

        assertThat(lines(doc.text())).containsExactly(
            "项目: A 标段,金额: 6000,负责人: 张三",
            "项目: B 标段,金额: 0,负责人: 李四");
        assertThat(doc.sourceBlocks()).hasSize(2);
        assertThat(doc.sourceBlocks().get(0).locator().rowStart())
            .as("表头行被消费，但行号仍是 Excel 显示行号 ⇒ 第 2 行")
            .isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // ② 合并单元格 / 百分比 / 多 sheet
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("合并单元格：A1:B1 的值在 A1，B1 位置也必须出现同一值（按左上角填充）")
    void mergedCellsFilledFromTopLeft(@TempDir Path dir) throws Exception {
        Path file;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("合并");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("合并标题");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("甲");
            file = write(wb, dir.resolve("合并.xlsx"));
        }

        KbParsedDocument doc = parse(file);

        assertThat(lines(doc.text()))
            .as("B1 在 XML 里不存在 ⇒ 必须由合并区域左上角值补齐")
            .containsExactly("A: 合并标题,B: 合并标题", "A: 甲");
    }

    @Test
    @DisplayName("百分比格式：0.125 且 0.0% 格式 → 文本是 12.5%，⛔ 不是裸 0.125")
    void percentFormatKeepsDisplayValue(@TempDir Path dir) throws Exception {
        Path file;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("百分比");
            Row row0 = sheet.createRow(0);
            Cell percent = row0.createCell(0);
            percent.setCellValue(0.125);
            CellStyle style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            percent.setCellStyle(style);
            file = write(wb, dir.resolve("百分比.xlsx"));
        }

        String text = parse(file).text();

        assertThat(text).contains("A: 12.5%");
        assertThat(text).as("⛔ 原始数值 0.125 不得出现（差 100 倍）").doesNotContain("0.125");
    }

    @Test
    @DisplayName("多 sheet：按工作簿顺序逐行输出，locator 带上各自的 sheet 名")
    void multipleSheetsKeepOrderAndSheetNames(@TempDir Path dir) throws Exception {
        Path file;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet first = wb.createSheet("第一表");
            first.createRow(0).createCell(0).setCellValue(1);
            Sheet second = wb.createSheet("第二表");
            second.createRow(0).createCell(1).setCellValue("备注");
            file = write(wb, dir.resolve("两个表.xlsx"));
        }

        KbParsedDocument doc = parse(file);

        assertThat(lines(doc.text())).containsExactly("A: 1", "B: 备注");
        assertThat(doc.sourceBlocks()).hasSize(2);
        assertThat(doc.sourceBlocks().get(0).locator().sheet()).isEqualTo("第一表");
        assertThat(doc.sourceBlocks().get(1).locator().sheet()).isEqualTo("第二表");
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 坏包修复（源站 xlsx_repair.py 前两件）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("坏包：清单引用共享字符串 part 但 part 缺失、工作表只用 inline string ⇒ 剥引用后能解析出原值")
    void repairsMissingSharedStringsPartWhenInlineOnly(@TempDir Path dir) throws Exception {
        byte[] broken = inlineStringsWithoutSharedStringsPart(Files.readAllBytes(writePlain(dir)));

        // ⚠️ 实测：POI 对本场景是容错的（日志 "Skipped invalid entry /xl/sharedStrings.xml"，
        //    inline string 仍读得出）⇒ 不能拿「原包解析失败」当判据（会假绿）。判据改为
        //    「repairPackage 真的产出了副本 + 清单引用被剥掉」，再从修复副本走完整解析。

        // 前提：修复确实做了事（这个包原本带着指向不存在 part 的 Override + Relationship）
        byte[] repaired = KbXlsxParser.XlsxRepair.repairPackage(broken);
        assertThat(repaired).as("缺 sharedStrings part 且只用 inline string ⇒ 必须产出修复副本").isNotNull();
        Map<String, byte[]> fixed = unzip(repaired);
        assertThat(text(fixed.get("[Content_Types].xml")))
            .as("清单里对 sharedStrings 的 Override 必须被剥掉")
            .doesNotContain("sharedStrings");
        assertThat(text(fixed.get("xl/_rels/workbook.xml.rels")))
            .as("关系里对 sharedStrings 的引用必须被剥掉")
            .doesNotContain("sharedStrings");

        Path brokenFile = dir.resolve("坏包.xlsx");
        Files.write(brokenFile, broken);
        KbParsedDocument doc = parse(brokenFile);

        assertThat(lines(doc.text()))
            .as("修复后必须解析出原值（只断言「没抛异常」会让 POI 自身容错构成假绿）")
            .containsExactly(
                "A: 项目,B: 金额,C: 负责人",
                "A: A 标段,B: 6000,C: 张三",
                "A: B 标段,B: 0,C: 李四");
    }

    @Test
    @DisplayName("坏包：sqref 不可解析的 dataValidation / scenarios 整块删除，可解析的原样保留")
    void removesInvalidSqrefElements(@TempDir Path dir) throws Exception {
        byte[] base = Files.readAllBytes(writePlain(dir));
        LinkedHashMap<String, byte[]> entries = unzip(base);
        String sheetName = worksheetName(entries);
        String sheet = text(entries.get(sheetName));
        String injected = "<dataValidations count=\"2\">"
            + "<dataValidation sqref=\"C:C\"/>"
            + "<dataValidation sqref=\"A1:B2\"/>"
            + "</dataValidations>"
            + "<scenarios><scenario name=\"s\" count=\"1\"><inputCells r=\"1:1\" val=\"1\"/></scenario></scenarios>";
        entries.put(sheetName, sheet.replace("</worksheet>", injected + "</worksheet>")
            .getBytes(StandardCharsets.UTF_8));

        byte[] repaired = KbXlsxParser.XlsxRepair.repairPackage(zip(entries));

        assertThat(repaired).isNotNull();
        String fixedSheet = text(unzip(repaired).get(sheetName));
        assertThat(fixedSheet).as("整列 C:C 不可解析 ⇒ 整块删除").doesNotContain("C:C");
        assertThat(fixedSheet).as("整行 1:1 不可解析 ⇒ scenarios 整块删除").doesNotContain("<scenarios");
        assertThat(fixedSheet).as("可解析的 A1:B2 必须保留（⛔ 不能一刀切删全表校验）").contains("A1:B2");
    }

    @Test
    @DisplayName("sqref 解析真值表：单元格/区域/绝对引用合法；整列/整行/逗号/#REF!/越界非法")
    void sqrefParsingTruthTable() {
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("A1")).isTrue();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("$A$1")).isTrue();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("A1:B2")).isTrue();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("A1 B2:C3")).isTrue();

        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("C:C")).as("整列").isFalse();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("1:1")).as("整行").isFalse();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("A1,B2")).as("逗号列表").isFalse();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("#REF!")).isFalse();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("A1048577")).as("行越界").isFalse();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("XFE1")).as("列越界（XFD 是最后一列）").isFalse();
        assertThat(KbXlsxParser.XlsxRepair.isParseableRefList("")).isFalse();
    }

    @Test
    @DisplayName("能力声明/路由输入：认 xlsx/xlsm/xltx/xlt，不抢 .xls 与其他格式")
    void supportsOwnExtensionsOnly() {
        for (String ext : new String[]{"xlsx", "xlsm", "xltx", "xlt", "XLSX"}) {
            assertThat(parser.supports("a." + ext, null)).as(ext).isTrue();
        }
        assertThat(parser.supports("a.xls", null)).as(".xls 是 OLE2，留给 Office").isFalse();
        assertThat(parser.supports("a.txt", "text/plain")).isFalse();
        assertThat(parser.supportedExtensions()).containsExactlyInAnyOrder("xlsx", "xlsm", "xltx", "xlt");
        assertThat(parser.supports("noext",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // zip 夹具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把 POI 生成的包改成「工作表全是 inline string、但清单仍引用 xl/sharedStrings.xml」的坏包。
     *
     * <p>POI 优先写共享字符串；这里把每个 {@code t="s"} 单元格换成 {@code t="inlineStr"}（值取自
     * sharedStrings.xml），再整体删掉 sharedStrings part —— 正好复现源站 {@code xlsx_repair.py:28-52} 的场景。
     */
    private static byte[] inlineStringsWithoutSharedStringsPart(byte[] xlsx) throws Exception {
        LinkedHashMap<String, byte[]> entries = unzip(xlsx);
        byte[] sharedBytes = entries.get("xl/sharedStrings.xml");
        if (sharedBytes == null) {
            throw new IllegalStateException("夹具前提不成立：POI 没有写 sharedStrings.xml");
        }
        List<String> shared = new ArrayList<>();
        Matcher si = Pattern.compile("(?s)<si>(.*?)</si>").matcher(text(sharedBytes));
        while (si.find()) {
            Matcher t = Pattern.compile("(?s)<t[^>]*>(.*?)</t>").matcher(si.group(1));
            shared.add(t.find() ? t.group(1) : "");
        }
        String sheetName = worksheetName(entries);
        String sheet = text(entries.get(sheetName));

        Matcher cell = Pattern.compile("(?s)<c\\b([^>]*)><v>(\\d+)</v></c>").matcher(sheet);
        StringBuilder out = new StringBuilder(sheet.length());
        int last = 0;
        while (cell.find()) {
            out.append(sheet, last, cell.start());
            String attrs = cell.group(1);
            if (attrs.contains("t=\"s\"")) {
                int index = Integer.parseInt(cell.group(2));
                String value = index >= 0 && index < shared.size() ? shared.get(index) : "";
                String ref = attribute(attrs, "r");
                String style = attribute(attrs, "s");
                out.append("<c r=\"").append(ref).append('"');
                if (style != null) {
                    out.append(" s=\"").append(style).append('"');
                }
                out.append(" t=\"inlineStr\"><is><t>").append(value).append("</t></is></c>");
            } else {
                out.append(cell.group());
            }
            last = cell.end();
        }
        out.append(sheet, last, sheet.length());
        entries.put(sheetName, out.toString().getBytes(StandardCharsets.UTF_8));
        entries.remove("xl/sharedStrings.xml");   // part 缺失，但 [Content_Types] / rels 仍引用它
        return zip(entries);
    }

    private static String attribute(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String worksheetName(Map<String, byte[]> entries) {
        return entries.keySet().stream()
            .filter(n -> n.startsWith("xl/worksheets/") && n.toLowerCase().endsWith(".xml"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("包里没有工作表 XML"));
    }

    private static LinkedHashMap<String, byte[]> unzip(byte[] bytes) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
