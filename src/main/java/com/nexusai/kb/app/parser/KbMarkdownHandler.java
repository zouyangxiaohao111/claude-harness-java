package com.nexusai.kb.app.parser;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Tika XHTML（SAX 事件）→ Markdown 转换器。
 *
 * <p><b>WHY 需要它（而不是直接用 {@code ToTextContentHandler}）</b>：Tika 的纯文本输出把表格拍平成
 * 制表符分隔的行（实测 {@code .docx} 与 {@code .xlsx} 都是 {@code \t项目\t金额\t}），表格结构一旦
 * 拍平就再也认不回来 —— 下游 {@code KbTextSplitter} 的表格保护（受保护片段）与表头前置全部失效，
 * 分块会把一张表切成「第一列一坨、第二列一坨」。XHTML 事件流里 {@code <table>/<tr>/<td>} 与
 * {@code <h1>} 都还在，必须在事件层就地转成 Markdown。
 *
 * <p><b>结构映射</b>（只处理有语义的标签，其余透传文字）：
 * <ul>
 *   <li>{@code h1..h6} → {@code #..######}（{@code headingLevelOffset} 可整体降级一级，用于 Excel 的
 *       sheet 名 —— Tika 把 sheet 名发成 {@code <h1>}，规格要求二级标题 {@code ##}）</li>
 *   <li>{@code p} / {@code div} → 段落换行</li>
 *   <li>{@code li} → {@code - } 列表项</li>
 *   <li>{@code table} → Markdown 表格（首行作表头，第二行写对齐分隔线）</li>
 *   <li>{@code div.slide-content} → {@code ## Slide N}（PPT 分页）</li>
 *   <li>{@code div.slide-master-content} → 整块丢弃（母版占位符会与正文重复）</li>
 * </ul>
 *
 * <p><b>⛔ 不处理</b>：{@code head}/{@code title}/{@code meta}（元信息走 Metadata，不是正文）。
 */
final class KbMarkdownHandler extends DefaultHandler {

    private final StringBuilder out = new StringBuilder();
    /** 当前文本缓冲（一个块级元素内的文字先攒起来，结束时统一落笔）。 */
    private final StringBuilder buf = new StringBuilder();

    /** 标题整体降级的级数（Excel: 1，使 Tika 的 h1 sheet 名成为 {@code ##}）。 */
    private final int headingLevelOffset;

    /** 正在跳过的元素深度（head/meta/title/slide-master-content 用）。 */
    private int skipDepth;
    /** {@code <li>} 内是否已写过项目符号。 */
    private boolean inListItem;
    /** 是否位于 pre 块（保留原始空白）。 */
    private boolean inPre;

    // ── 表格状态 ──
    private boolean inTable;
    private List<List<String>> tableRows;
    private List<String> currentRow;
    private StringBuilder cellBuf;
    /** 嵌套表格深度（内层表格拍平进当前单元格，不递归渲染）。 */
    private int nestedTableDepth;

    // ── PPT 分页计数 ──
    private int slideIndex;

    KbMarkdownHandler(int headingLevelOffset) {
        this.headingLevelOffset = headingLevelOffset;
    }

    /** 转换结果（已做尾部空白裁剪）。 */
    String markdown() {
        return out.toString().strip();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
        String name = name(localName, qName);
        if (skipDepth > 0) {
            // 跳过区间内一律只维护深度计数：⛔ 不能靠「名字是否属于跳过集」判断退出 ——
            //   跳过根可能是 <div class="slide-master-content">（名字是 div），按名字判会永远退不出，
            //   把后面所有页/段落一起吞掉（实测 PPT 只出第 1 页）。
            skipDepth++;
            return;
        }
        if (isSkipRoot(name, atts)) {
            skipDepth = 1;
            return;
        }
        switch (name) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> startHeading(name);
            case "p", "div", "section", "article" -> {
                if ("div".equals(name) && isSlideContent(atts)) {
                    startSlide();
                } else if ("div".equals(name) && isSlideMaster(atts)) {
                    // 双重保险：isSkipRoot 已拦；这里只防御属性大小写差异
                    skipDepth = 1;
                } else {
                    flushParagraph();
                }
            }
            case "li" -> {
                flushParagraph();
                out.append('\n');
                inListItem = true;
            }
            case "pre" -> {
                flushParagraph();
                out.append("\n```\n");
                inPre = true;
            }
            case "br" -> buf.append('\n');
            case "table" -> {
                flushParagraph();
                if (inTable) {
                    nestedTableDepth++;
                } else {
                    inTable = true;
                    tableRows = new ArrayList<>();
                }
            }
            case "tr" -> {
                if (inTable && nestedTableDepth == 0) {
                    currentRow = new ArrayList<>();
                }
            }
            case "td", "th" -> {
                if (inTable && nestedTableDepth == 0) {
                    cellBuf = new StringBuilder();
                }
            }
            case "img" -> {
                // 图片二进制不在 XHTML 里；用占位标记保住「这里原本有图」的位置信息
                String alt = atts == null ? null : atts.getValue("alt");
                buf.append("![image]");
                if (alt != null && !alt.isBlank()) {
                    buf.append("(").append(alt).append(')');
                }
            }
            default -> {
                // 其余标签（b/i/span/a/strong…）只保留文字，不加标记
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        if (skipDepth > 0) {
            // 与 startElement 的计数严格配对：每个 start 都 +1，每个 end 都 -1
            skipDepth--;
            return;
        }
        String name = name(localName, qName);
        switch (name) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> endHeading();
            case "p", "div", "section", "article" -> flushParagraph();
            case "li" -> {
                flushParagraph();
                inListItem = false;
            }
            case "pre" -> {
                flushParagraph();
                out.append("\n```\n");
                inPre = false;
            }
            case "table" -> endTable();
            case "tr" -> endRow();
            case "td", "th" -> endCell();
            default -> {
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (skipDepth > 0) {
            return;
        }
        String s = new String(ch, start, length);
        if (!inPre) {
            // 普通段落里的换行/多余空白压成单个空格（Markdown 段落内换行无意义）
            s = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[ ]{2,}", " ");
        }
        if (cellBuf != null && inTable && nestedTableDepth == 0) {
            cellBuf.append(s);
        } else {
            buf.append(s);
        }
    }

    // ── 标题 ──

    private void startHeading(String tag) {
        flushParagraph();
        int level = Math.min(6, Math.max(1, Integer.parseInt(tag.substring(1)) + headingLevelOffset));
        out.append('\n').append("#".repeat(level)).append(' ');
    }

    private void endHeading() {
        flushParagraph();
    }

    // ── 段落 ──

    private void flushParagraph() {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        if (inListItem) {
            out.append("- ");
        }
        boolean endsWithHeadingMarker = out.length() > 0 && out.charAt(out.length() - 1) == ' ';
        if (!endsWithHeadingMarker && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append(inPre ? "" : " ");
        }
        out.append(text);
        if (!inPre) {
            out.append('\n');
        }
    }

    // ── 表格 ──

    private void endCell() {
        if (!inTable || nestedTableDepth > 0) {
            return;
        }
        String text = cellBuf == null ? "" : cellBuf.toString().strip()
            .replace('|', '/')          // 单元格内的竖线会破坏 Markdown 表格
            .replaceAll("\\s{2,}", " ");
        if (currentRow == null) {
            currentRow = new ArrayList<>();
        }
        currentRow.add(text);
        cellBuf = null;
    }

    private void endRow() {
        if (!inTable || nestedTableDepth > 0) {
            return;
        }
        if (currentRow != null && !currentRow.isEmpty()) {
            tableRows.add(currentRow);
        }
        currentRow = null;
    }

    private void endTable() {
        if (!inTable) {
            return;
        }
        if (nestedTableDepth > 0) {
            nestedTableDepth--;
            return;
        }
        inTable = false;
        List<List<String>> rows = tableRows;
        tableRows = null;
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int columns = 0;
        for (List<String> r : rows) {
            columns = Math.max(columns, r.size());
        }
        if (columns == 0) {
            return;
        }
        out.append('\n');
        appendTableRow(rows.get(0), columns);
        out.append('|');
        for (int i = 0; i < columns; i++) {
            out.append(" --- |");
        }
        out.append('\n');
        for (int i = 1; i < rows.size(); i++) {
            appendTableRow(rows.get(i), columns);
        }
        out.append('\n');
    }

    private void appendTableRow(List<String> row, int columns) {
        out.append('|');
        for (int i = 0; i < columns; i++) {
            String cell = i < row.size() ? row.get(i) : "";
            out.append(' ').append(cell).append(" |");
        }
        out.append('\n');
    }

    // ── PPT ──

    private void startSlide() {
        flushParagraph();
        slideIndex++;
        out.append("\n## Slide ").append(slideIndex).append('\n');
    }

    // ── 工具 ──

    private static String name(String local, String qName) {
        String n = local != null && !local.isEmpty() ? local : qName;
        if (n == null) {
            return "";
        }
        int colon = n.indexOf(':');
        return (colon >= 0 ? n.substring(colon + 1) : n).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isSlideContent(Attributes atts) {
        return atts != null && containsClass(atts.getValue("class"), "slide-content");
    }

    private static boolean isSlideMaster(Attributes atts) {
        return atts != null && containsClass(atts.getValue("class"), "slide-master-content");
    }

    private static boolean containsClass(String classes, String wanted) {
        if (classes == null) {
            return false;
        }
        for (String c : classes.split("\\s+")) {
            if (wanted.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 需要整块跳过的元素根。
     *
     * <p>{@code head}/{@code title}/{@code meta} 是元信息；{@code slide-master-content} 是母版占位符
     * （实测 PPT 每页都会带一份空的，不跳过会产出成对空标题）。{@code script}/{@code style} 在
     * HTML 输入里也会出现，必须跳过否则正文混入 CSS。
     */
    private static boolean isSkipRoot(String name, Attributes atts) {
        return switch (name) {
            case "head", "title", "meta", "script", "style" -> true;
            default -> "div".equals(name) && isSlideMaster(atts);
        };
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        characters(ch, start, length);
    }
}
