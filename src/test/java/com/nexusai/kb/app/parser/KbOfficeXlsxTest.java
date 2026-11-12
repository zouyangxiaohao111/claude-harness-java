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

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * xlsx 值物化 · 合并单元格 + 百分比格式。
 *
 * <p><b>为什么断言落在「具体那一行的文本」上</b>：
 * <ol>
 *   <li>合并单元格缺值不报错、不抛异常 —— 只断言「解析成功 / 文本非空」的用例对坏实现同样全绿。
 *       判据必须是「B1 那个位置也出现了 A1 的值」。</li>
 *   <li>百分比不物化得到的是 {@code 0.125}（差 100 倍），必须同时断言 {@code 12.5%} 在、
 *       {@code 0.125} 不在。</li>
 *   <li>普通数字不能被格式物化改坏（{@code 1234.5} 原样）。</li>
 * </ol>
 *
 * <p>⛔ 本用例直接 new {@link KbOfficeParser}，不依赖 Spring（@SpringBootTest 会迁移真库，禁止）。
 */
@DisplayName("[C13] xlsx 值物化（合并单元格 / 百分比格式）")
class KbOfficeXlsxTest {

    private final KbOfficeParser parser = new KbOfficeParser();

    /**
     * 造一份「合并 + 百分比 + 普通数字」的最小工作簿：
     * <pre>
     * A1:B1 合并 = 合并标题   （值只在 A1）
     * A2 项目 | B2 占比 | C2 金额
     * A3 甲   | B3 0.125（0.0% 格式）| C3 1234.5
     * </pre>
     */
    private static Path writeWorkbook(Path dir, String sheetName) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("合并标题");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("占比");
            header.createCell(2).setCellValue("金额");

            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("甲");
            Cell percent = data.createCell(1);
            percent.setCellValue(0.125);
            CellStyle style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            percent.setCellStyle(style);
            data.createCell(2).setCellValue(1234.5);

            Path file = dir.resolve("合并与格式.xlsx");
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
            return file;
        }
    }

    private String render(Path dir, String sheetName) throws Exception {
        Path xlsx = writeWorkbook(dir, sheetName);
        KbParsedDocument doc = parser.parse(xlsx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));
        return doc.text();
    }

    /** 取以给定前缀开头的第一行（Markdown 表格行），找不到直接失败。 */
    private static String lineStartingWith(String text, String prefix) {
        return Arrays.stream(text.split("\\R"))
            .filter(line -> line.startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Markdown 里没有以「" + prefix + "」开头的行，实际文本：\n" + text));
    }

    @Test
    @DisplayName("合并单元格：A1:B1 的值在 A1，B1 位置也必须出现同一值")
    void mergedCellValueIsMaterialized(@TempDir Path dir) throws Exception {
        String text = render(dir, "合并测试");

        assertThat(lineStartingWith(text, "| 合并标题 |"))
            .as("B1 原本是合并区域的从属格（XML 里没有值）⇒ 物化后该行两个格子都要有值")
            .contains("| 合并标题 | 合并标题 |");
    }

    @Test
    @DisplayName("百分比格式：0.125 且 0.0% 格式 → 文本含 12.5%，不含裸 0.125")
    void percentFormatIsMaterialized(@TempDir Path dir) throws Exception {
        String text = render(dir, "格式测试");

        assertThat(text).as("显示值必须是 12.5%").contains("12.5%");
        assertThat(text).as("⛔ 不能把原始数值 0.125 当文本吐出来（差 100 倍）").doesNotContain("0.125");
    }

    @Test
    @DisplayName("普通数字不受影响，表格结构（表头 + 分隔行）保持不变")
    void plainNumberAndTableStructureUntouched(@TempDir Path dir) throws Exception {
        String text = render(dir, "结构测试");

        assertThat(text)
            .as("既有结构不变：sheet 名二级标题 + 表头行 + 分隔行")
            .contains("## 结构测试")
            .contains("| 项目 | 占比 | 金额 |")
            .contains("| --- | --- | --- |");

        assertThat(lineStartingWith(text, "| 甲 |"))
            .as("普通数字原样渲染，不被百分比物化波及")
            .contains("| 1234.5 |");
    }
}
