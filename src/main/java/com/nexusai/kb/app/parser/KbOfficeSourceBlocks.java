package com.nexusai.kb.app.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Office 文件结构单元抽取 · Word / Excel / PPT 的「真结构」→ {@link KbSourceIndex.Unit}。
 *
 * <p><b>源站对照</b>：WeKnora {@code internal/sourceloc/office.go:26-369}
 * （{@code SupportsStructure} / {@code AlignStructure} / {@code docxUnits} /
 * {@code pptxUnits} / {@code xlsxUnits}）。
 *
 * <p><b>⭐ 为什么不能用 Tika 的 XHTML 事件记账</b>：{@code KbMarkdownHandler} 能看到
 * {@code <p>} / {@code <tr>} / {@code slide-content}，但<b>看不出</b>「这是 Word 正文的第 N 个
 * body 级段落」「这是 Excel 的第几行」「这是第几页幻灯片」—— Tika 不吐这些序号，
 * 靠事件计数遇空段落 / 合并单元格立刻错位，而错位不报错，只会让引用指向别处。
 *
 * <p><b>⛔ docx 的段落序号必须数是 body 级 {@code w:p} / {@code w:tbl}</b>
 * （源站 {@code office.go:70-73} 注释：{@code w:sdt} 内容控件是透明的，因为 docx-preview
 * 渲染器也这么算；表格按行算一个单元，长表格才能逐行对齐）。
 *
 * <p><b>失败一律 fail-soft</b>：抽结构只是「附加信息」，任何异常都返回空列表并由调用方打 warn
 * —— ⛔ 不能让正文解析因为这里失败而失败。
 */
final class KbOfficeSourceBlocks {

    private static final Logger log = LoggerFactory.getLogger(KbOfficeSourceBlocks.class);

    /** 单个文件最多抽多少个结构单元（源站 {@code office.go:21 maxUnits}）。 */
    static final int MAX_UNITS = 200_000;

    private KbOfficeSourceBlocks() {
    }

    /**
     * 该扩展名是否支持结构抽取（源站 {@code office.go:27-33}）。
     *
     * <p>⚠️ 本仓<b>不含</b> csv / epub：CSV 走 {@link KbTextParser}（文本解析器），
     * epub 由 Tika 解 spine、本仓拿不到 spine 序号（源站走 {@code epubUnits}，
     * 本批不做——⛔ 不伪造 section 号）。
     */
    static boolean supports(String ext) {
        if (ext == null) {
            return false;
        }
        String e = ext.toLowerCase(Locale.ROOT);
        return switch (e) {
            case "docx", "docm", "pptx", "pptm", "xlsx", "xlsm" -> true;
            default -> false;
        };
    }

    /**
     * 抽取结构单元（源站 {@code office.go:46-62 StructureUnits}）。
     *
     * @param ext  小写扩展名
     * @param file 原文件
     * @return 结构单元（按文档顺序）；不支持或失败时为空列表
     */
    static List<KbSourceIndex.Unit> units(String ext, Path file) {
        if (!supports(ext) || file == null) {
            return List.of();
        }
        long started = System.currentTimeMillis();
        List<KbSourceIndex.Unit> units;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            units = switch (ext.toLowerCase(Locale.ROOT)) {
                case "docx", "docm" -> docxUnits(new XWPFDocument(in));
                case "pptx", "pptm" -> pptxUnits(new XMLSlideShow(in));
                case "xlsx", "xlsm" -> xlsxUnits(WorkbookFactory.create(in));
                default -> List.of();
            };
        } catch (Exception | LinkageError e) {
            // fail-soft：抽不到结构只是没有 locator，正文照常入库
            log.warn("[溯源] 抽取 {} 结构失败（不影响正文解析）file={} 原因={}",
                ext, file.getFileName(), e.toString());
            return List.of();
        }
        if (log.isInfoEnabled()) {
            log.info("[溯源] Office 结构抽取完成 file={} 类型={} 单元数={} 耗时={}ms",
                file.getFileName(), ext, units.size(), System.currentTimeMillis() - started);
        }
        return units;
    }

    // ════════════════════════════════════════════════════════════════════
    // Word（源站 office.go:68-182 docxUnits）
    // ════════════════════════════════════════════════════════════════════

    /**
     * Word：每个 body 级段落 / 表格行一个单元。
     *
     * <p><b>段落序号（{@code block}）的算法必须与源站一致</b>：
     * 深度优先走 {@code w:body} 的直接子元素，<b>只数 {@code w:p} 与 {@code w:tbl}</b>，
     * 序号从 1 起；{@code w:sdt} 包装层被穿透（不占号）。
     * 表格内部：{@code w:tbl} 自身占一个号（与源站 {@code depth == 1 && (p || tbl)} 一致），
     * 之后<b>每行</b>产出单元，行号沿用表格的 block 号 —— 因为「第 N 个 body 级元素」
     * 在查看器里就是那张表，逐行只是为了让长表格能按行对齐。
     */
    private static List<KbSourceIndex.Unit> docxUnits(XWPFDocument doc) {
        List<KbSourceIndex.Unit> units = new ArrayList<>();
        int block = 0;
        for (Object bodyItem : doc.getBodyElements()) {
            if (units.size() >= MAX_UNITS) {
                log.warn("[溯源] docx 单元数达到上限 {}，后续内容不再记源位置", MAX_UNITS);
                break;
            }
            if (bodyItem instanceof XWPFParagraph p) {
                block++;
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    units.add(new KbSourceIndex.Unit(text.strip(), KbSourceLocator.docx(block)));
                }
            } else if (bodyItem instanceof XWPFTable t) {
                block++;
                for (XWPFTableRow row : t.getRows()) {
                    String text = rowText(row);
                    if (!text.isBlank()) {
                        units.add(new KbSourceIndex.Unit(text, KbSourceLocator.docx(block)));
                    }
                    if (units.size() >= MAX_UNITS) {
                        break;
                    }
                }
            }
            // 其余 body 级元素（w:sectPr 等）不占号，与源站「sdt 透明」同理
        }
        return units;
    }

    /** 表格行 → 纯文本（单元格间用空格，与源站 {@code tc} 写空格一致）。 */
    private static String rowText(XWPFTableRow row) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) {
            String t = cell.getText();
            if (t == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t.strip());
        }
        return sb.toString().strip();
    }

    // ════════════════════════════════════════════════════════════════════
    // PowerPoint（源站 office.go:200-294 pptxUnits）
    // ════════════════════════════════════════════════════════════════════

    /**
     * PPT：每页幻灯片的<b>每条文本</b>一个单元，定位到该页（{@code slide} 从 1 起）。
     *
     * <p>源站注释（{@code office.go:200-202}）：用「段落」而不是「整页」作为单元，
     * 是为了「解析器把形状顺序换了也还能对齐」。本仓 Tika 输出顺序与 POI 形状顺序可能不同，
     * 更要用细单元。
     *
     * <p>备注页：源站在页正文之后追备注段落（{@code office.go:235-247}），本仓 POI 的
     * {@code getNotesSlide} 拿不到时跳过 —— ⛔ 不猜。
     */
    private static List<KbSourceIndex.Unit> pptxUnits(XMLSlideShow show) {
        List<KbSourceIndex.Unit> units = new ArrayList<>();
        List<XSLFSlide> slides = show.getSlides();
        for (int i = 0; i < slides.size() && units.size() < MAX_UNITS; i++) {
            XSLFSlide slide = slides.get(i);
            KbSourceLocator loc = KbSourceLocator.slide(i + 1, null);
            for (XSLFShape shape : slide.getShapes()) {
                if (!(shape instanceof XSLFTextShape textShape)) {
                    continue;
                }
                // 按段落取，不整块取：一段一个单元（源站 drawingParagraphs）
                for (String para : paragraphsOf(textShape)) {
                    if (!para.isBlank()) {
                        units.add(new KbSourceIndex.Unit(para, loc));
                    }
                    if (units.size() >= MAX_UNITS) {
                        break;
                    }
                }
            }
            // 标题：本页第一个非空文本当作 title（源站 epub 才写 title，slide 不写；
            // 这里也不写，保持两侧 JSON 一致 —— ⛔ 不自造字段）
        }
        return units;
    }

    /** 形状里的段落文本（按 {@code \n} 切 POI 给的整块文本，POI 每段以换行分隔）。 */
    private static List<String> paragraphsOf(XSLFTextShape shape) {
        String all = shape.getText();
        if (all == null || all.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : all.split("\r\n|\r|\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // Excel（源站 office.go:296-340 xlsxUnits）
    // ════════════════════════════════════════════════════════════════════

    /**
     * Excel：每个 sheet 的每个<b>非空行</b>一个单元，行号是<b>1-based 的显示行号</b>
     * （源站 {@code office.go:326-338}：POI 的 {@code row.getRowNum()} 是 0-based，
     * 显示行号 = +1；源站 excelize 的迭代器直接给 1-based）。
     *
     * <p>⚠️ 空行不产出单元 —— 源站同样「{@code text == ""} 就 continue」。
     * 但<b>行号不能压缩</b>：第 5 行是空行、第 6 行有字，单元的行号必须是 6。
     */
    private static List<KbSourceIndex.Unit> xlsxUnits(Workbook wb) {
        List<KbSourceIndex.Unit> units = new ArrayList<>();
        for (int si = 0; si < wb.getNumberOfSheets() && units.size() < MAX_UNITS; si++) {
            Sheet sheet = wb.getSheetAt(si);
            String sheetName = sheet.getSheetName();
            for (Row row : sheet) {
                if (units.size() >= MAX_UNITS) {
                    break;
                }
                String text = rowText(row);
                if (text.isBlank()) {
                    continue;
                }
                int displayRow = row.getRowNum() + 1;   // 0-based → 1-based 显示行号
                units.add(new KbSourceIndex.Unit(text,
                    KbSourceLocator.sheet(sheetName, displayRow, displayRow)));
            }
        }
        return units;
    }

    /** Excel 行 → 纯文本（单元格间空格，与源站 {@code strings.Join(cells, " ")} 一致）。 */
    private static String rowText(Row row) {
        StringBuilder sb = new StringBuilder();
        for (Cell cell : row) {
            String t = cellText(cell);
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString().strip();
    }

    /**
     * 单元格 → 文本。
     *
     * <p>⛔ 不用 {@code DataFormatter}（它会按格式串造出 {@code 1,234.00} 之类 Markdown 里没有的文本）；
     * 源站 excelize 的 {@code GetRows} 给的是「单元格显示值」，本仓用 {@code toString()} 近似，
     * 数字型会退化成 {@code 1234.0} —— 但 {@link KbSourceIndex#normalizeKey} 只保留字母数字，
     * 小数点的差异在对齐阶段被容忍（{@code matchRune} 只在两侧都是数字时保留小数点）。
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue() == null ? "" : cell.getStringCellValue().strip();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.rint(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d)
                    : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getCachedFormulaResultType() == org.apache.poi.ss.usermodel
                        .CellType.NUMERIC ? cell.getNumericCellValue() : cell.getStringCellValue());
                } catch (RuntimeException e) {
                    yield "";
                }
            }
            default -> "";
        };
    }

}
