package com.nexusai.kb.app.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Excel（OOXML 表格）解析器 · 对齐 WeKnora builtin {@code excel_parser.py}。
 *
 * <p><b>为什么单独一类</b>：Excel 的行号是<b>结构化信息</b>，Tika 的 XHTML 事件流给不出来
 * （事件里只有 {@code <tr>}，没有「这是第几行」）。本类自己用 POI 逐行渲染，每行同时产出一个
 * {@link KbSourceBlock} + {@link KbSourceLocator#sheet}，下游沿用既有的 {@code KbTextSplitter}
 * 与 {@code attachSourceLocators}（⛔ 本类不写库、不切 chunk、不改检索链）。
 *
 * <p><b>输出形态逐字照源站 builtin</b>（{@code excel_parser.py:129-138}）：每行一条
 * {@code 列名: 值,列名: 值}，空单元格跳过、整行为空则整行跳过。⛔ 不是 Markdown 表格 ——
 * 源站 builtin 引擎就是这么出（用户 2026-11-12「功能 检索都照源站做」）。
 *
 * <p><b>首行语义</b>（{@code excel_parser.py:76,186-192}）：默认<b>不</b>把第 1 行当表头，
 * 列名用 Excel 列字母（A/B/C…，源站 {@code get_column_letter}），第 1 行作为数据输出。
 * 打开 {@code nexusai.knowledge.excel.first-row-as-header} 且行数 ≥2 时才把第 1 行当表头。
 *
 * <p><b>行号</b>（{@code excel_parser.py:146-162}）：每行一个 source block，行号 = POI 的 0-based
 * 行号 + 1（1-based，与 Excel 界面一致）；空行不压缩行号（第 5 行空、第 6 行有字，块行号是 6）。
 *
 * <p><b>坏包修复</b>（{@code xlsx_repair.py:28-52,72}，只做前两件，样式清洗是 P2 未做）：
 * <ol>
 *   <li>清单引用 {@code xl/sharedStrings.xml} 但 part 缺失：工作表不含共享字符串单元格
 *       （{@code t="s"}）⇒ 剥掉清单里的 Override + Relationship；非标准路径的 part 存在 ⇒ 改名成标准路径。</li>
 *   <li>{@code sqref} 不可解析（整列 {@code C:C} / 整行 {@code 1:1} / 逗号列表 / {@code #REF!} / 越界）的
 *       {@code <dataValidation>} / {@code <scenarios>} 元素整块删除。</li>
 * </ol>
 * 修复产出临时副本交给 POI；修复失败一律 fail-soft 回退原文件（⛔ 补能力不能变成新的解析失败源）。
 *
 * <p><b>合并单元格</b>：区域内除左上角外的格子填左上角的<b>显示值</b>（源站 builtin 走 pandas/read_excel，
 * 合并区域同样按左上角取值）。
 *
 * <p><b>上限</b>：单文档 ≤ {@value #MAX_TEXT_CHARS} 字符，超出截断并记 {@code truncated=true}。
 */
@Component
@Order(16)
public class KbXlsxParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbXlsxParser.class);

    static final int MAX_TEXT_CHARS = 2_000_000;

    /** 本解析器接管的扩展名：OOXML 表格（{@code .xls} 是 OLE2 二进制，仍留给 Office 解析器）。 */
    static final Set<String> EXTENSIONS = Set.of("xlsx", "xlsm", "xltx", "xlt");

    /** 无扩展名时的 MIME 兜底（spreadsheetml 家族；{@code vnd.ms-excel} 不是 OOXML，不在此列）。 */
    private static final String SPREADSHEET_MIME_MARK = "spreadsheetml";

    /** Excel 2007+ 的行/列上限（{@code sqref} 越界判定用）。 */
    static final int MAX_EXCEL_ROW = 1_048_576;
    static final int MAX_EXCEL_COL = 16_384;

    /** sheet 数（写进 metadata 便于线上排查）。 */
    static final String META_SHEET_COUNT = "excelSheetCount";

    /** 首行是否当表头 · 源站默认 false（第 1 行是数据，列名用列字母）。 */
    @Value("${nexusai.knowledge.excel.first-row-as-header:false}")
    boolean firstRowAsHeader = false;

    /** 坏包修复开关 · 默认开；关闭时原文件直接交给 POI。 */
    @Value("${nexusai.knowledge.excel.repair-enabled:true}")
    boolean repairEnabled = true;

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null) {
            // 有扩展名就只认扩展名：.xls 仍是 Office 的（本类不做 HSSF 二进制的坏包修复）
            return EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
        }
        return KbParsers.normalizeMime(mimeType).contains(SPREADSHEET_MIME_MARK);
    }

    /** 能力声明 · 与 {@link #supports} 同源（复用 {@link #EXTENSIONS}）。 */
    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String displayName() {
        return "内置 Excel 解析";
    }

    /** 源站 builtin 族名（{@code engines.go} 的 builtin 引擎覆盖表格），另收 {@code xlsx} 便于按类型点名。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("builtin", "xlsx");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString();

        Path repaired = null;
        try {
            Path source = file;
            if (repairEnabled) {
                repaired = repairPackageToTemp(file, fileName);
                if (repaired != null) {
                    source = repaired;
                }
            }

            List<SheetRows> sheets = new ArrayList<>();
            try (InputStream in = new BufferedInputStream(Files.newInputStream(source));
                 Workbook wb = WorkbookFactory.create(in)) {
                FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
                DataFormatter formatter = new DataFormatter();
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    Sheet sheet = wb.getSheetAt(i);
                    List<RowData> rows = renderSheet(sheet, formatter, evaluator);
                    if (!rows.isEmpty()) {
                        sheets.add(new SheetRows(sheet.getSheetName(), rows));
                    }
                }
            } catch (IOException | RuntimeException | LinkageError e) {
                throw KbTikaSupport.wrap("Excel 解析", file, e instanceof Exception ex ? ex : new IOException(e));
            }

            String full = renderText(sheets);
            KbParsers.Truncation trunc = KbParsers.truncate(full, MAX_TEXT_CHARS);
            String text = trunc.text();
            List<KbSourceBlock> blocks = trunc.truncated()
                ? clampBlocks(sheets, text.codePointCount(0, text.length()))
                : buildBlocks(sheets);

            Map<String, String> meta = KbParsers.newMeta();
            KbParsers.fillCommonMetadata(meta, fileName, null, this);
            String mime = KbTikaSupport.detectMime(file, fileName);
            if (mime != null && !mime.isBlank()) {
                meta.put(KbParsedDocument.META_MIME_TYPE, mime);
            }
            meta.put(META_SHEET_COUNT, String.valueOf(sheets.size()));
            meta.put(KbParsedDocument.META_SOURCE_BLOCKS, String.valueOf(blocks.size()));
            meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(text.length()));
            if (trunc.truncated()) {
                meta.put(KbParsedDocument.META_TRUNCATED, "true");
            }

            if (log.isInfoEnabled()) {
                int dataRows = 0;
                for (SheetRows s : sheets) {
                    dataRows += s.rows().size();
                }
                log.info("KbXlsxParser: 解析完成 file={} sheet数={} 数据行={} 字符={} 首行当表头={} sourceBlocks={}",
                    fileName, sheets.size(), dataRows, text.length(), firstRowAsHeader, blocks.size());
            }
            // 图片恒空：Excel 内嵌图不在本批范围（与源站 builtin 一致，只出文本与行号）
            return new KbParsedDocument(text, List.of(), meta, blocks);
        } finally {
            deleteQuietly(repaired);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 渲染
    // ════════════════════════════════════════════════════════════════════

    /** 一个 sheet 的渲染结果（仅在至少有一行可输出时保留）。 */
    private record SheetRows(String name, List<RowData> rows) {
    }

    /**
     * 一行的显示值 · {@code cells} 按列序保存（空单元格已剔除）。
     *
     * <p>{@code rowNumber} 是 1-based 显示行号（POI 0-based + 1），空行不压缩行号。
     */
    private record RowData(int rowNumber, TreeMap<Integer, String> cells) {
    }

    /**
     * 单 sheet 逐行取显示值。
     *
     * <p>合并区域：先建「(行,列) → 左上角显示值」的映射，再逐格渲染 —— 区域里 XML 上不存在的格子
     * 也能取到值。行列上界取「最后一行 / 最大列 / 合并区域上界」三者最大值，合并区域跨出行尾也能覆盖。
     */
    private List<RowData> renderSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Integer, Map<Integer, String>> mergedFill = mergedFillValues(sheet, formatter, evaluator);

        int lastRow = sheet.getLastRowNum();
        int maxCol = 0;
        for (Row row : sheet) {
            maxCol = Math.max(maxCol, row.getLastCellNum());
        }
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            lastRow = Math.max(lastRow, region.getLastRow());
            maxCol = Math.max(maxCol, region.getLastColumn() + 1);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                lastRow = Math.max(lastRow, r);
            }
        }
        maxCol = Math.min(maxCol, MAX_EXCEL_COL);

        List<RowData> out = new ArrayList<>();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            Map<Integer, String> mergedRow = mergedFill.getOrDefault(r, Map.of());
            TreeMap<Integer, String> cells = new TreeMap<>();
            int rowMaxCol = Math.max(maxCol, row == null ? 0 : row.getLastCellNum());
            for (int c = 0; c < rowMaxCol && c < MAX_EXCEL_COL; c++) {
                String value = cellDisplayValue(row == null ? null : row.getCell(c), formatter, evaluator);
                if (value == null || value.isEmpty()) {
                    value = mergedRow.get(c);
                }
                if (value == null || value.isEmpty()) {
                    continue;   // 源站：空单元格跳过
                }
                cells.put(c, value);
            }
            if (cells.isEmpty()) {
                continue;       // 源站：整行空跳过（但行号不压缩）
            }
            out.add(new RowData(r + 1, cells));
        }
        return out;
    }

    /** 合并区域填充值：区域内除左上角外每个格子 → 左上角的显示值。 */
    private static Map<Integer, Map<Integer, String>> mergedFillValues(
        Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<CellRangeAddress> regions = sheet.getMergedRegions();
        if (regions.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Map<Integer, String>> fill = new java.util.HashMap<>();
        for (CellRangeAddress region : regions) {
            Row firstRow = sheet.getRow(region.getFirstRow());
            Cell topLeft = firstRow == null ? null : firstRow.getCell(region.getFirstColumn());
            String text = cellDisplayValue(topLeft, formatter, evaluator);
            if (text == null || text.isEmpty()) {
                continue;   // 左上角本来为空 ⇒ 没有可复制的值
            }
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                Map<Integer, String> rowFill = fill.computeIfAbsent(r, k -> new java.util.HashMap<>());
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    if (r == region.getFirstRow() && c == region.getFirstColumn()) {
                        continue;
                    }
                    rowFill.put(c, text);
                }
            }
        }
        return fill;
    }

    /**
     * 单元格显示值（{@link DataFormatter} 口径，百分比 {@code 12.5%} 天然保留）。
     *
     * <p>公式格优先走求值器取显示值；求值失败（不支持的自定义函数等）回退到格式化的公式文本 ——
     * ⛔ 单个格子的公式不能拖垮整份文档。
     */
    private static String cellDisplayValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA && evaluator != null) {
            try {
                return formatter.formatCellValue(cell, evaluator);
            } catch (RuntimeException e) {
                // 回退：缓存值/公式文本
            }
        }
        return formatter.formatCellValue(cell);
    }

    /**
     * 渲染最终文本：逐 sheet 逐行 {@code 列名: 值,列名: 值}，行间一个 {@code \n}。
     *
     * <p>首行当表头（开关打开且该 sheet 行数 ≥2）：第 1 行只提供列名，不作为数据输出；
     * 否则列名用 Excel 列字母（A/B/C…）。
     */
    private String renderText(List<SheetRows> sheets) {
        StringBuilder sb = new StringBuilder();
        Cursor cursor = new Cursor();
        for (SheetRows sheet : sheets) {
            appendRows(sb, cursor, sheet, null);
        }
        return sb.toString();
    }

    /** 遍历输出（渲染与建块共用同一套行序，保证 start/end 与文本逐字一致）。 */
    private void appendRows(StringBuilder sb, Cursor cursor, SheetRows sheet, List<KbSourceBlock> blocks) {
        List<RowData> rows = sheet.rows();
        RowData header = firstRowAsHeader && rows.size() >= 2 ? rows.get(0) : null;
        int from = header == null ? 0 : 1;
        for (int i = from; i < rows.size(); i++) {
            RowData row = rows.get(i);
            String line = renderLine(row, header);
            if (line.isEmpty()) {
                continue;
            }
            if (blocks != null) {
                blocks.add(new KbSourceBlock(cursor.cp, cursor.cp + line.codePointCount(0, line.length()),
                    KbSourceLocator.sheet(sheet.name(), row.rowNumber(), row.rowNumber())));
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            cursor.cp += line.codePointCount(0, line.length()) + 1;   // +1 = 行间换行
        }
    }

    /** 单行文本：列名 = 表头该列显示值（空则列字母）或列字母。 */
    private static String renderLine(RowData row, RowData header) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : row.cells().entrySet()) {
            String name = columnName(e.getKey(), header);
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(name).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    private static String columnName(int colIndex, RowData header) {
        if (header != null) {
            String name = header.cells().get(colIndex);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return CellReference.convertNumToColString(colIndex);
    }

    /** 建全部 source block（未截断路径）。 */
    private List<KbSourceBlock> buildBlocks(List<SheetRows> sheets) {
        List<KbSourceBlock> blocks = new ArrayList<>();
        Cursor cursor = new Cursor();
        StringBuilder sink = new StringBuilder();
        for (SheetRows sheet : sheets) {
            appendRows(sink, cursor, sheet, blocks);
        }
        return blocks;
    }

    /**
     * 截断后裁掉越界块。
     *
     * <p>与 {@link KbEpubParser} 同口径：完全在截断点之后的块丢弃；跨截断点的块把 end 收到截断点。
     */
    private List<KbSourceBlock> clampBlocks(List<SheetRows> sheets, int textCpLength) {
        List<KbSourceBlock> clamped = new ArrayList<>();
        for (KbSourceBlock block : buildBlocks(sheets)) {
            if (block.start() >= textCpLength) {
                continue;
            }
            int end = Math.min(block.end(), textCpLength);
            if (end > block.start()) {
                clamped.add(new KbSourceBlock(block.start(), end, block.locator()));
            }
        }
        return clamped;
    }

    /** 输出游标 · 码点位置（⛔ 不用 char 下标；中文/emoji 会让定位整体偏移）。 */
    private static final class Cursor {
        int cp;
    }

    // ════════════════════════════════════════════════════════════════════
    // 坏包修复
    // ════════════════════════════════════════════════════════════════════

    /** 修复包并落临时文件；无需修复/失败 → null（fail-soft）。 */
    private static Path repairPackageToTemp(Path file, String fileName) {
        Path created = null;
        try {
            byte[] original = Files.readAllBytes(file);
            byte[] fixed = XlsxRepair.repairPackage(original);
            if (fixed == null) {
                return null;
            }
            created = Files.createTempFile("kb-xlsx-repaired-", ".xlsx");
            Files.write(created, fixed);
            log.info("KbXlsxParser: 坏包已修复，使用临时副本解析 file={} 原始字节={} 修复后字节={}",
                fileName, original.length, fixed.length);
            return created;
        } catch (Exception | LinkageError e) {
            deleteQuietly(created);
            log.warn("KbXlsxParser: 坏包修复失败，回退原文件 file={} 原因={}", fileName, e.toString());
            return null;
        }
    }

    /** 临时文件清理（失败只记 warn：残留临时文件比让解析失败轻得多）。 */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("KbXlsxParser: 临时文件清理失败（不影响解析结果）file={} 原因={}", p, e.getMessage());
        }
    }

    /**
     * 坏包修复 · 对齐源站 {@code xlsx_repair.py:28-52,72}。
     *
     * <p><b>只做前两件</b>：① sharedStrings 清单与 part 不一致；② {@code sqref} 不可解析的
     * {@code dataValidation} / {@code scenarios}。源站第三件 {@code sanitize_xlsx_styles}（样式清洗）
     * 属 P2，本批不做 —— 样式损坏只影响显示，不影响取文本。
     *
     * <p><b>为什么在 zip 层改而不是用 POI 重写</b>：POI 重写会把整包重新编码（丢时间戳、丢未识别 part、
     * 重排条目）；坏包修复的目标恰恰是「尽量少动」。这里只替换确实需要改的条目，其余字节原样写回，
     * 条目顺序与压缩方式保持不变。
     */
    static final class XlsxRepair {

        private static final String CONTENT_TYPES = "[Content_Types].xml";
        private static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
        private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
        private static final String SHARED_STRINGS_MARK = "sharedstrings";

        /** {@code <dataValidation>}（自闭合或成对）· 前缀可选。 */
        private static final Pattern DATA_VALIDATION = Pattern.compile(
            "(?s)<(?:[A-Za-z0-9_.-]+:)?dataValidation\\b[^>]*?(?:/>|>.*?</(?:[A-Za-z0-9_.-]+:)?dataValidation\\s*>)");

        /** {@code <scenarios>…</scenarios>} 整块。 */
        private static final Pattern SCENARIOS = Pattern.compile(
            "(?s)<(?:[A-Za-z0-9_.-]+:)?scenarios\\b[^>]*>.*?</(?:[A-Za-z0-9_.-]+:)?scenarios\\s*>");

        private static final Pattern SQREF_ATTR = Pattern.compile("\\bsqref\\s*=\\s*\"([^\"]*)\"");
        private static final Pattern REF_ATTR = Pattern.compile("\\b(?:sqref|r)\\s*=\\s*\"([^\"]*)\"");

        /** 单个区域引用：{@code A1} / {@code $A$1} / {@code A1:B2}（⛔ 不接受整列 C:C、整行 1:1、逗号列表）。 */
        private static final Pattern CELL_REF = Pattern.compile(
            "^\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7})(?::\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7}))?$");

        private static final Pattern SHARED_STRING_CELL = Pattern.compile("\\bt\\s*=\\s*[\"']s[\"']");

        private XlsxRepair() {
        }

        /**
         * 修复 OOXML 包。
         *
         * @param original 原始字节
         * @return 修复后的字节；无需修复 → {@code null}；结构损坏到无法判定（不是 zip / 无清单）→ {@code null}
         * @throws IOException 读写失败（调用方 fail-soft 回退原文件）
         */
        static byte[] repairPackage(byte[] original) throws IOException {
            LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> methods = new LinkedHashMap<>();
            LinkedHashMap<String, Long> times = new LinkedHashMap<>();
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(original))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    entries.put(e.getName(), zin.readAllBytes());
                    methods.put(e.getName(), e.getMethod());
                    times.put(e.getName(), e.getTime());
                }
            }
            if (entries.isEmpty() || !entries.containsKey(CONTENT_TYPES)) {
                return null;
            }

            boolean changed = false;
            String contentTypes = text(entries.get(CONTENT_TYPES));
            String workbookRels = entries.containsKey(WORKBOOK_RELS) ? text(entries.get(WORKBOOK_RELS)) : "";

            // ── ① sharedStrings：清单引用了 xl/sharedStrings.xml 但 part 缺失 ──
            boolean declaresSharedStrings =
                contentTypes.toLowerCase(Locale.ROOT).contains(SHARED_STRINGS_MARK);
            boolean sharedStringsPresent = containsEntryIgnoreCase(entries, SHARED_STRINGS);
            if (declaresSharedStrings && !sharedStringsPresent) {
                String alternate = findAlternateSharedStrings(entries);
                if (alternate != null) {
                    // part 存在但路径非标准 ⇒ 改名成标准路径
                    byte[] data = entries.remove(alternate);
                    Integer method = methods.remove(alternate);
                    Long time = times.remove(alternate);
                    entries.put(SHARED_STRINGS, data);
                    methods.put(SHARED_STRINGS, method == null ? ZipEntry.DEFLATED : method);
                    times.put(SHARED_STRINGS, time == null ? System.currentTimeMillis() : time);

                    String oldPart = "/" + alternate;
                    contentTypes = contentTypes.replaceAll("(?i)" + Pattern.quote(oldPart),
                        Matcher.quoteReplacement("/" + SHARED_STRINGS));
                    String altTarget = alternate.startsWith("xl/") ? alternate.substring(3) : alternate;
                    workbookRels = workbookRels.replace("Target=\"" + altTarget + "\"",
                        "Target=\"sharedStrings.xml\"");
                    changed = true;
                    log.info("[xlsx 修复] sharedStrings part 路径非标准，已改名为 {}（原 {}）", SHARED_STRINGS, alternate);
                } else if (!anyWorksheetUsesSharedStrings(entries)) {
                    // 工作表只用 inline string ⇒ 剥掉清单里的 Override + Relationship
                    String newContentTypes = contentTypes.replaceAll(
                        "(?i)<Override\\s+[^>]*PartName=\"/xl/sharedStrings\\.xml\"[^>]*/>", "");
                    String newRels = workbookRels.replaceAll(
                        "(?i)<Relationship\\s+[^>]*Target=\"[^\"]*sharedStrings\\.xml\"[^>]*/>", "");
                    if (!newContentTypes.equals(contentTypes) || !newRels.equals(workbookRels)) {
                        contentTypes = newContentTypes;
                        workbookRels = newRels;
                        changed = true;
                        log.info("[xlsx 修复] sharedStrings part 缺失且工作表只用 inline string，已剥离清单引用");
                    }
                } else {
                    // 工作表仍有 t="s" 单元格却没有 part：改不了（补内容等于伪造数据）⇒ 交给 POI 报错
                    log.warn("[xlsx 修复] sharedStrings part 缺失且工作表仍有共享字符串单元格，无法修复");
                    return null;
                }
            }

            // ── ② sqref 不可解析的 dataValidation / scenarios：整块删除 ──
            int removed = 0;
            for (String name : new ArrayList<>(entries.keySet())) {
                if (!isWorksheet(name)) {
                    continue;
                }
                String xml = text(entries.get(name));
                int[] removedHere = new int[1];
                String fixed = stripInvalidElements(xml, removedHere);
                if (!fixed.equals(xml)) {
                    entries.put(name, fixed.getBytes(StandardCharsets.UTF_8));
                    removed += removedHere[0];
                    changed = true;
                }
            }
            if (removed > 0) {
                log.warn("[xlsx 修复] 删除 sqref/引用不可解析的 dataValidation/scenarios 共 {} 处", removed);
            }

            if (!changed) {
                return null;
            }
            entries.put(CONTENT_TYPES, contentTypes.getBytes(StandardCharsets.UTF_8));
            if (entries.containsKey(WORKBOOK_RELS)) {
                entries.put(WORKBOOK_RELS, workbookRels.getBytes(StandardCharsets.UTF_8));
            }
            return writeZip(entries, methods, times);
        }

        /** 删除 {@code dataValidation}（判 sqref）与 {@code scenarios}（判内部引用）里不可解析的整块。 */
        static String stripInvalidElements(String xml, int[] removed) {
            String result = replaceInvalid(DATA_VALIDATION, xml, SQREF_ATTR, true, removed);
            return replaceInvalid(SCENARIOS, result, REF_ATTR, false, removed);
        }

        private static String replaceInvalid(Pattern element, String xml, Pattern refs,
                                             boolean firstBadWins, int[] removed) {
            Matcher m = element.matcher(xml);
            StringBuilder out = new StringBuilder(xml.length());
            int last = 0;
            while (m.find()) {
                String block = m.group();
                boolean bad = false;
                Matcher ref = refs.matcher(block);
                while (ref.find()) {
                    if (!isParseableRefList(ref.group(1))) {
                        bad = true;
                        if (firstBadWins) {
                            break;
                        }
                    }
                }
                out.append(xml, last, m.start());
                if (bad) {
                    removed[0]++;
                } else {
                    out.append(block);
                }
                last = m.end();
            }
            out.append(xml, last, xml.length());
            return out.toString();
        }

        /**
         * 引用列表是否可解析（{@code sqref} 语义：空格分隔的单元格/区域，⛔ 不接受逗号）。
         *
         * <p>整列 {@code C:C}、整行 {@code 1:1}、{@code #REF!}、越界（行 &gt; 1048576 / 列 &gt; 16384）
         * 全部判为不可解析 —— 源站 {@code xlsx_repair.py:72} 同口径。
         */
        static boolean isParseableRefList(String value) {
            if (value == null) {
                return false;
            }
            String v = value.strip();
            if (v.isEmpty()) {
                return false;
            }
            for (String token : v.split("\\s+")) {
                Matcher m = CELL_REF.matcher(token);
                if (!m.matches()) {
                    return false;
                }
                if (!inBounds(m.group(1), m.group(2))) {
                    return false;
                }
                if (m.group(3) != null && !inBounds(m.group(3), m.group(4))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean inBounds(String col, String row) {
            int column = CellReference.convertColStringToIndex(col.toUpperCase(Locale.ROOT));
            if (column < 0 || column >= MAX_EXCEL_COL) {
                return false;
            }
            try {
                long r = Long.parseLong(row);
                return r >= 1 && r <= MAX_EXCEL_ROW;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** 工作表里是否还有共享字符串单元格（{@code t="s"}）。 */
        private static boolean anyWorksheetUsesSharedStrings(Map<String, byte[]> entries) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (!isWorksheet(e.getKey())) {
                    continue;
                }
                if (SHARED_STRING_CELL.matcher(text(e.getValue())).find()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 找「其实是 sharedStrings 但不在标准路径」的 part。
         *
         * <p>判据两层：名字里含 {@code sharedstrings}，或内容是 {@code <sst …>} 根元素。
         * ⛔ 不看大小写、不匹配 worksheet 目录。
         */
        private static String findAlternateSharedStrings(Map<String, byte[]> entries) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".xml") || isWorksheet(name)
                    || SHARED_STRINGS.equalsIgnoreCase(name)) {
                    continue;
                }
                if (lower.contains(SHARED_STRINGS_MARK)) {
                    return name;
                }
                String content = text(e.getValue()).strip();
                if (content.startsWith("<sst") && content.length() > 4
                    && (Character.isWhitespace(content.charAt(4)) || content.charAt(4) == '>')) {
                    return name;
                }
            }
            return null;
        }

        private static boolean isWorksheet(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.startsWith("xl/worksheets/") && lower.endsWith(".xml");
        }

        private static boolean containsEntryIgnoreCase(Map<String, byte[]> entries, String name) {
            for (String key : entries.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        private static String text(byte[] bytes) {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }

        /** 按原条目顺序/压缩方式写回 zip（目录条目用 DEFLATED 空内容）。 */
        private static byte[] writeZip(Map<String, byte[]> entries, Map<String, Integer> methods,
                                       Map<String, Long> times) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zout = new ZipOutputStream(bos)) {
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    byte[] data = e.getValue();
                    ZipEntry entry = new ZipEntry(e.getKey());
                    Long time = times.get(e.getKey());
                    if (time != null && time > 0) {
                        entry.setTime(time);
                    }
                    int method = methods.getOrDefault(e.getKey(), ZipEntry.DEFLATED);
                    if (e.getKey().endsWith("/")) {
                        method = ZipEntry.DEFLATED;
                    }
                    if (method == ZipEntry.STORED) {
                        CRC32 crc = new CRC32();
                        crc.update(data);
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(data.length);
                        entry.setCompressedSize(data.length);
                        entry.setCrc(crc.getValue());
                    } else {
                        entry.setMethod(ZipEntry.DEFLATED);
                    }
                    zout.putNextEntry(entry);
                    zout.write(data);
                    zout.closeEntry();
                }
            }
            return bos.toByteArray();
        }
    }
}
