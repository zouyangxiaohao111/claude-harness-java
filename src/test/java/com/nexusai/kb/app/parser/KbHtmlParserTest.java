package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML 解析器意图测试 · 断言的是「结构保住了没有」，而不是「文本提到了没有」。
 *
 * <p><b>WHY 这些断言</b>：
 * <ol>
 *   <li><b>标题必须是 {@code #}/{@code ##}</b> —— 退回 Tika 扁平文本时标题层级全部丢失，
 *       下游分块无法按小节切，检索「某标段金额」会把整页塞进同一个块。</li>
 *   <li><b>表格必须是 Markdown 表格</b> —— 扁平文本会把 {@code th/td} 拍成制表符行，表格保护失效。</li>
 *   <li><b>链接必须变成 {@code [文字](url)}</b> —— {@code KbMarkdownHandler} 的 default 分支只留文字，
 *       链接目标丢失后「引用的是哪份规范」就查不到了。</li>
 *   <li><b>nav/footer 不得进入正文</b> —— 导航/版权文字混进正文会污染检索（源站 html_parser 用
 *       trafilatura 抽正文，本仓只删这三种语义容器，见 {@link KbHtmlParser} 类注释的边界说明）。</li>
 * </ol>
 *
 * <p>⚠️ 结构类用例<b>走 {@link KbParserRegistry}</b>：只有走注册表，才能证明「HTML 默认不再由
 * {@link KbTextParser} 解析」—— 把 {@code KbHtmlParser.supports} 改回 {@code false} 时，
 * 标题/表格断言必须变红（变异自检）。
 *
 * <p>⛔ 不用 {@code @SpringBootTest}（本仓铁律：会迁移用户真库）：解析器都能直接 new。
 */
@DisplayName("[C11] HTML 解析器（标题/表格/链接/去噪 → Markdown）")
class KbHtmlParserTest {

    private final KbHtmlParser parser = new KbHtmlParser();

    /** 含标题层级、th/td 表格、链接、nav/footer/aside 噪声的最小型网页。 */
    private static final String PAGE = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <title>测试页标题</title>
          <style>body { color: #333; }</style>
        </head>
        <body>
          <nav>站点导航噪声 <a href="/home">首页</a></nav>
          <aside>侧栏噪声</aside>
          <h1>工程概况</h1>
          <p>本次检测覆盖 <a href="https://example.com/spec">结构验收规范</a> 的全部条款。</p>
          <h2>标段与金额</h2>
          <table>
            <thead><tr><th>标段</th><th>金额</th></tr></thead>
            <tbody>
              <tr><td>A 标段</td><td>1200 万元</td></tr>
              <tr><td>B 标段</td><td>800 万元</td></tr>
            </tbody>
          </table>
          <footer><p>版权页脚噪声</p></footer>
        </body>
        </html>
        """;

    @Nested
    @DisplayName("类型识别与能力声明")
    class Supports {

        @Test
        @DisplayName("html / htm / xhtml 被接管")
        void recognisesHtmlExtensions() {
            for (String ext : new String[]{"html", "htm", "xhtml"}) {
                assertThat(parser.supports("a." + ext, null)).as("扩展名 %s", ext).isTrue();
            }
            assertThat(parser.supportedExtensions()).containsExactlyInAnyOrder("html", "htm", "xhtml");
            assertThat(parser.displayName()).isEqualTo("内置 HTML 解析");
        }

        @Test
        @DisplayName("不抢纯文本 / PDF / Office（它们各有解析器）")
        void doesNotClaimOtherFormats() {
            assertThat(parser.supports("a.txt", "text/plain")).isFalse();
            assertThat(parser.supports("a.md", "text/markdown")).isFalse();
            assertThat(parser.supports("a.pdf", "application/pdf")).isFalse();
            assertThat(parser.supports("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isFalse();
            assertThat(parser.supports("a.xml", "application/xml")).isFalse();
        }

        @Test
        @DisplayName("无扩展名时按 MIME 兜底（只认 text/html 与 xhtml+xml）")
        void recognisesHtmlMime() {
            assertThat(parser.supports("noext", "text/html")).isTrue();
            assertThat(parser.supports("noext", "text/html; charset=utf-8")).isTrue();
            assertThat(parser.supports("noext", "application/xhtml+xml")).isTrue();
            assertThat(parser.supports("noext", "text/plain")).isFalse();
        }

        @Test
        @DisplayName("⚠️ 行为变化：文本解析器已把 html/htm 让出")
        void textParserNoLongerClaimsHtml() {
            KbTextParser textParser = new KbTextParser();
            assertThat(textParser.supports("a.html", null)).isFalse();
            assertThat(textParser.supports("a.htm", null)).isFalse();
            assertThat(textParser.supports("a.xhtml", null)).isFalse();
            assertThat(textParser.supportedExtensions()).doesNotContain("html", "htm", "xhtml");
        }
    }

    @Nested
    @DisplayName("结构转换（走注册表，证明默认路由已切换）")
    class Structure {

        /** 只放两个解析器：Html(25) 必须排在 Text(30) 之前被选中。 */
        private KbParserRegistry registry() {
            return new KbParserRegistry(List.of(new KbHtmlParser(), new KbTextParser()));
        }

        @Test
        @DisplayName("h1/h2 → #/##，table（th+td）→ Markdown 表格，链接 → [text](url)")
        void convertsHeadingsTablesAndLinks(@TempDir Path dir) throws Exception {
            Path file = write(dir, "page.html", PAGE);

            KbParsedDocument doc = registry().parse(file, KbParseContext.defaults());
            String md = doc.text();

            // 标题层级（扁平文本下这些断言必红）
            assertThat(md).contains("# 工程概况");
            assertThat(md).contains("## 标段与金额");
            // 表格结构（th 行 + td 行，扁平文本会变成制表符行）
            assertThat(md).contains("| 标段 | 金额 |");
            assertThat(md).contains("| A 标段 | 1200 万元 |");
            assertThat(md).contains("| B 标段 | 800 万元 |");
            assertThat(md).doesNotContain("标段\t金额");
            // 链接目标不能丢
            assertThat(md).contains("[结构验收规范](https://example.com/spec)");
            // 正文本身仍在
            assertThat(md).contains("本次检测覆盖");
            assertThat(md).contains("的全部条款");
        }

        @Test
        @DisplayName("表格与标题计数（解析器日志用的口径）")
        void countsHeadingsAndTables(@TempDir Path dir) throws Exception {
            KbParsedDocument doc = parser.parse(write(dir, "page.html", PAGE), KbParseContext.defaults());
            assertThat(KbHtmlParser.countHeadings(doc.text())).isEqualTo(2);
            assertThat(KbHtmlParser.countTables(doc.text())).isEqualTo(1);
        }

        @Test
        @DisplayName("注册表选中 Html 而不是 Text（@Order(25) 生效）")
        void routesToHtmlParser(@TempDir Path dir) throws Exception {
            Path file = write(dir, "page.html", PAGE);
            KbParserRegistry reg = registry();
            assertThat(reg.select("page.html", "text/html")).isInstanceOf(KbHtmlParser.class);
            assertThat(reg.parsers()).extracting(KbDocumentParser::name)
                .containsExactly("KbHtmlParser", "KbTextParser");
            assertThat(reg.parse(file, KbParseContext.defaults()).metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbHtmlParser");
        }
    }

    @Nested
    @DisplayName("正文去噪与产物边界")
    class DenoiseAndProduct {

        @Test
        @DisplayName("nav / footer / aside 的文字不进正文，也不留下链接残留")
        void dropsNavigationFooterAside(@TempDir Path dir) throws Exception {
            KbParsedDocument doc = parser.parse(write(dir, "page.html", PAGE), KbParseContext.defaults());
            String md = doc.text();

            assertThat(md).doesNotContain("站点导航噪声");
            assertThat(md).doesNotContain("侧栏噪声");
            assertThat(md).doesNotContain("版权页脚噪声");
            assertThat(md).doesNotContain("](/home)");
            // 去噪不能把正文一起吃光
            assertThat(md).contains("工程概况");
            assertThat(md).contains("标段与金额");
        }

        @Test
        @DisplayName("head 里的 title/style 不进正文")
        void dropsHeadMetadata(@TempDir Path dir) throws Exception {
            String md = parser.parse(write(dir, "page.html", PAGE), KbParseContext.defaults()).text();
            assertThat(md).doesNotContain("测试页标题");
            assertThat(md).doesNotContain("color: #333");
        }

        @Test
        @DisplayName("没有 href 的 <a> 只留文字（不产出空链接/孤立方括号）")
        void anchorWithoutHrefKeepsTextOnly(@TempDir Path dir) throws Exception {
            String html = "<html><body><p>参见 <a>附录 A</a> 的说明。</p></body></html>";
            String md = parser.parse(write(dir, "no-href.html", html), KbParseContext.defaults()).text();
            assertThat(md).contains("参见");
            assertThat(md).contains("附录 A");
            assertThat(md).doesNotContain("[]");
            assertThat(md).doesNotContain("[(<");
        }

        @Test
        @DisplayName("images / sourceBlocks 为空（不伪造定位），元数据带解析器名与字符数")
        void productBoundaries(@TempDir Path dir) throws Exception {
            KbParsedDocument doc = parser.parse(write(dir, "page.html", PAGE), KbParseContext.defaults());
            assertThat(doc.images()).isEmpty();
            assertThat(doc.sourceBlocks()).isEmpty();
            assertThat(doc.metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbHtmlParser")
                .containsEntry(KbParsedDocument.META_MIME_TYPE, "text/html")
                .containsEntry(KbParsedDocument.META_SOURCE_BLOCKS, "0")
                .containsEntry(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(doc.text().length()));
        }

        @Test
        @DisplayName("xhtml 文件同样保结构")
        void parsesXhtml(@TempDir Path dir) throws Exception {
            String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>X</title></head>
                  <body>
                    <h1>XHTML 标题</h1>
                    <p>见 <a href="/a">附录</a> 说明。</p>
                  </body>
                </html>
                """;
            KbParsedDocument doc = parser.parse(write(dir, "page.xhtml", xhtml), KbParseContext.defaults());
            assertThat(doc.text()).contains("# XHTML 标题");
            assertThat(doc.text()).contains("[附录](/a)");
            assertThat(doc.metadata()).containsEntry(KbParsedDocument.META_PARSER, "KbHtmlParser");
        }
    }

    @Nested
    @DisplayName("正文抽取（块级文本/链接密度 + 噪声词）")
    class MainContent {

        /** 20 条导航链接（整块文字几乎全在 &lt;a&gt; 里）+ 一段长正文。 */
        private static final String NAV_PLUS_BODY = """
            <html><body>
              <div class="link-list">
                <a href="/a1">第一篇报道</a><a href="/a2">第二篇报道</a>
                <a href="/a3">第三篇报道</a><a href="/a4">第四篇报道</a>
                <a href="/a5">第五篇报道</a><a href="/a6">第六篇报道</a>
                <a href="/a7">第七篇报道</a><a href="/a8">第八篇报道</a>
                <a href="/a9">第九篇报道</a><a href="/a10">第十篇报道</a>
              </div>
              <div class="article-body">
                <p>工程概况：本次检测覆盖农村生活污水处理设施的全部条款，包含进出水水质、
                处理规模、管网配套与运维记录等四项内容。检测结论认为，现有设施在雨季存在
                溢流风险，需要补充调蓄池并完善在线监测点位，确保出水稳定达到一级 A 标准。</p>
              </div>
            </body></html>
            """;

        @Test
        @DisplayName("整块几乎全是链接的导航区被丢弃，正文块保留")
        void dropsLinkListKeepsBody(@TempDir Path dir) throws Exception {
            String md = parser.parse(write(dir, "nav.html", NAV_PLUS_BODY), KbParseContext.defaults()).text();
            assertThat(md).doesNotContain("第一篇报道");
            assertThat(md).doesNotContain("第十篇报道");
            assertThat(md).contains("工程概况");
            assertThat(md).contains("一级 A 标准");
        }

        @Test
        @DisplayName("article 内的短内容不算噪声（正文容器放行）")
        void articleIsNeverDropped(@TempDir Path dir) throws Exception {
            String html = "<html><body><article><a href=\"/x\">短链</a></article></body></html>";
            String md = parser.parse(write(dir, "art.html", html), KbParseContext.defaults()).text();
            assertThat(md).contains("短链");
        }

        @Test
        @DisplayName("class 命中噪声词（sidebar/menu 等）的块被丢弃")
        void dropsNoiseWordClass(@TempDir Path dir) throws Exception {
            String html = """
                <html><body>
                  <div class="sidebar-menu">订阅我们的邮件推送以获取最新资讯和独家内容</div>
                  <div class="article-body">
                    <p>农村污水治理应优先完善收集管网，再按水量确定处理工艺规模，
                    避免设施建成后长期低负荷运行。</p>
                  </div>
                </body></html>
                """;
            String md = parser.parse(write(dir, "sidebar.html", html), KbParseContext.defaults()).text();
            assertThat(md).doesNotContain("订阅我们的邮件推送");
            assertThat(md).contains("收集管网");
        }

        @Test
        @DisplayName("整页都被判噪声时回退整页（⛔ 不产出空文档）")
        void fallsBackWhenWholePageIsNoise(@TempDir Path dir) throws Exception {
            String html = """
                <html><body>
                  <div class="nav-menu"><a href="/a">首页</a><a href="/b">关于我们</a></div>
                </body></html>
                """;
            String md = parser.parse(write(dir, "onlynav.html", html), KbParseContext.defaults()).text();
            assertThat(md).contains("首页");
            assertThat(md).contains("关于我们");
        }
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
